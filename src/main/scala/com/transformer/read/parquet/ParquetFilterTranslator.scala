package com.transformer.read.parquet

import com.transformer.core._
import com.transformer.sql.plan._

import org.apache.parquet.filter2.predicate.{FilterApi, FilterPredicate}
import org.apache.parquet.io.api.Binary

/** Best-effort translator from a transformer [[Expr]] (the bound predicate
  * sitting above a parquet [[ScanExec]]) to a parquet-mr
  * [[FilterPredicate]] suitable for `ParquetFileReader.setFilter`.
  *
  * The translation is intentionally partial: it walks the AND-chain of
  * conjuncts and emits a parquet predicate for the ones it understands,
  * dropping the rest. The full transformer-side `FilterExec` always still
  * runs on top of the scan, so a dropped conjunct just means parquet doesn't
  * help us skip row groups on that condition — correctness is preserved.
  *
  * Pushed forms (with `c` a column ref and `lit` a literal of a supported
  * scalar type):
  *
  *   - `c = lit`, `c != lit`, `c < lit`, `c <= lit`, `c > lit`, `c >= lit`
  *     (and the swapped `lit OP c` forms)
  *   - `c IS NULL` / `c IS NOT NULL` — translates to `FilterApi.eq(col, null)`
  *     / `FilterApi.notEq(col, null)`. The stats filter then uses
  *     `numNulls` / `numRows` to drop row groups that are proven all-null
  *     (for IS NOT NULL) or contain no nulls (for IS NULL). Big win on
  *     sparse columns: e.g. polymarket's `data` JSON blob is empty in many
  *     rows, so `WHERE data IS NOT NULL` skips entire row groups.
  *   - `c IN (lit1, lit2, …)` over a homogeneous literal list — lowered to
  *     `(c = lit1) OR (c = lit2) OR …`. `NOT IN` wraps that in `FilterApi.not`.
  *   - `c BETWEEN lo AND hi` — already lowered to `c >= lo AND c <= hi` by
  *     the LogicalBuilder, so both halves go through the standard comparator
  *     path. Verified by [[ParquetFilterTranslatorTest]].
  *   - `NOT(<above>)`
  *   - `<above> AND <above>`, `<above> OR <above>`
  *
  * Not pushed (left as residual in the FilterExec):
  *   - predicates over computed expressions (`a - b > 0`)
  *   - predicates referencing two columns (`a > b`)
  *   - `LIKE` patterns (no public FilterApi LIKE)
  *   - `IN (…)` with a NULL literal in the list — SQL semantics for NULL in
  *     the list interact with three-valued logic in ways a row-group skip
  *     can't safely approximate (see [[translateInList]] for the bailout
  *     and the test that pins the behaviour).
  *   - `IN (…)` with non-literal items
  *   - decimal columns
  *
  * Returns None when nothing could be pushed.
  */
object ParquetFilterTranslator {

  def translate(predicate: Expr): Option[FilterPredicate] = {
    val conjuncts = splitAnd(predicate)
    val translated = conjuncts.flatMap(translateOne)
    if (translated.isEmpty) None
    else Some(translated.reduceLeft((a, b) => FilterApi.and(a, b)))
  }

  private def splitAnd(e: Expr): Seq[Expr] = e match {
    case BinOpExpr("AND", l, r, _) => splitAnd(l) ++ splitAnd(r)
    case other => Seq(other)
  }

  private def translateOne(e: Expr): Option[FilterPredicate] = e match {
    case BinOpExpr("AND", l, r, _) =>
      (translateOne(l), translateOne(r)) match {
        case (Some(a), Some(b)) => Some(FilterApi.and(a, b))
        case (Some(a), None)    => Some(a)
        case (None, Some(b))    => Some(b)
        case _                  => None
      }
    case BinOpExpr("OR", l, r, _) =>
      // OR is only safe to push when BOTH sides translate — dropping either
      // side of an OR would over-prune (rows that match the dropped branch
      // would be missing from the scan output). And/and-only chains have no
      // such constraint because the residual FilterExec re-checks per row.
      for {
        a <- translateOne(l)
        b <- translateOne(r)
      } yield FilterApi.or(a, b)
    case UnaryOpExpr("NOT", c, _) => translateOne(c).map(FilterApi.not)
    case BinOpExpr(op, ColRefExpr(_, name, dt), LitExpr(v, _), _) =>
      compareToLiteral(name, dt, op, v)
    case BinOpExpr(op, LitExpr(v, _), ColRefExpr(_, name, dt), _) =>
      // Swap the comparator so it reads "column OP literal" — e.g. `5 > x`
      // becomes `x < 5`.
      compareToLiteral(name, dt, swap(op), v)
    case IsNullExpr(ColRefExpr(_, name, dt), negated) =>
      nullPredicate(name, dt, negated)
    case InListExpr(ColRefExpr(_, name, dt), items, negated) =>
      translateInList(name, dt, items, negated)
    case _ => None
  }

  /** Build `column IS NULL` (or `IS NOT NULL` when `negated`). Parquet-mr's
    * `FilterApi.eq(col, null)` is the "match null" predicate; the statistics
    * filter then uses `numNulls` to drop whole row groups (groups with zero
    * nulls can't satisfy IS NULL; groups whose every row is null can't
    * satisfy IS NOT NULL).
    *
    * The `null.asInstanceOf[java.lang.Integer]` shape is the price of the
    * Java overload set: `FilterApi.eq` is generic over the column's element
    * type `T extends Comparable<T>`, and Scala's untyped `null` doesn't
    * satisfy the bound until we ascribe it.
    */
  private def nullPredicate(name: String, dt: DataType, negated: Boolean): Option[FilterPredicate] = {
    dt match {
      case DataType.IntType | DataType.DateType =>
        val col = FilterApi.intColumn(name)
        val nullLit = null.asInstanceOf[java.lang.Integer]
        Some(if (negated) FilterApi.notEq(col, nullLit) else FilterApi.eq(col, nullLit))
      case DataType.LongType | DataType.TimestampType =>
        val col = FilterApi.longColumn(name)
        val nullLit = null.asInstanceOf[java.lang.Long]
        Some(if (negated) FilterApi.notEq(col, nullLit) else FilterApi.eq(col, nullLit))
      case DataType.FloatType =>
        val col = FilterApi.floatColumn(name)
        val nullLit = null.asInstanceOf[java.lang.Float]
        Some(if (negated) FilterApi.notEq(col, nullLit) else FilterApi.eq(col, nullLit))
      case DataType.DoubleType =>
        val col = FilterApi.doubleColumn(name)
        val nullLit = null.asInstanceOf[java.lang.Double]
        Some(if (negated) FilterApi.notEq(col, nullLit) else FilterApi.eq(col, nullLit))
      case DataType.BooleanType =>
        val col = FilterApi.booleanColumn(name)
        val nullLit = null.asInstanceOf[java.lang.Boolean]
        Some(if (negated) FilterApi.notEq(col, nullLit) else FilterApi.eq(col, nullLit))
      case DataType.StringType | DataType.BinaryType =>
        val col = FilterApi.binaryColumn(name)
        val nullLit = null.asInstanceOf[Binary]
        Some(if (negated) FilterApi.notEq(col, nullLit) else FilterApi.eq(col, nullLit))
      case _: DataType.DecimalType | DataType.NullType => None
    }
  }

  /** Decompose `c IN (lit1, lit2, …)` to `c = lit1 OR c = lit2 OR …` so each
    * disjunct can be checked against per-row-group stats. `NOT IN` wraps the
    * disjunction in `FilterApi.not`.
    *
    * Bails (returns None) in three cases — partial translation here would
    * over-prune row groups:
    *
    *   - Empty list (SQL grammar forbids it; defensive guard).
    *   - Any non-literal item (subqueries, expressions). Caller's residual
    *     FilterExec handles it.
    *   - Any NULL literal in the list. SQL three-valued logic says
    *     `x IN (1, NULL)` is NULL when x≠1 (not false), and `x NOT IN (…NULL…)`
    *     is NULL for every row. Pushing as an `=` chain would over-prune.
    */
  private def translateInList(
      name: String,
      dt: DataType,
      items: Seq[Expr],
      negated: Boolean
  ): Option[FilterPredicate] = {
    if (items.isEmpty) return None
    val lits = new scala.collection.mutable.ArrayBuffer[Any](items.length)
    val it = items.iterator
    while (it.hasNext) {
      it.next() match {
        case LitExpr(null, _) => return None  // NULL in list — bail (3VL).
        case LitExpr(v, _)    => lits += v
        case _                => return None  // non-literal — bail.
      }
    }
    val eqs = lits.iterator.flatMap(v => compareToLiteral(name, dt, "=", v)).toIndexedSeq
    if (eqs.length != lits.length) return None
    val disjunction = eqs.reduceLeft((a, b) => FilterApi.or(a, b))
    Some(if (negated) FilterApi.not(disjunction) else disjunction)
  }

  /** Build a parquet `FilterPredicate` for `<column> <op> <literal>`.
    *
    * Returns None for type/op combinations that don't translate (decimal
    * columns, unsupported operators, null literals, etc.) so the caller can
    * drop the conjunct.
    */
  private def compareToLiteral(name: String, dt: DataType, op: String, value: Any): Option[FilterPredicate] = {
    if (value == null) return None
    dt match {
      case DataType.IntType | DataType.DateType =>
        val col = FilterApi.intColumn(name)
        val lit = java.lang.Integer.valueOf(value.asInstanceOf[Number].intValue)
        op match {
          case "=" => Some(FilterApi.eq(col, lit))
          case "!=" | "<>" => Some(FilterApi.notEq(col, lit))
          case "<" => Some(FilterApi.lt(col, lit))
          case "<=" => Some(FilterApi.ltEq(col, lit))
          case ">" => Some(FilterApi.gt(col, lit))
          case ">=" => Some(FilterApi.gtEq(col, lit))
          case _ => None
        }
      case DataType.LongType | DataType.TimestampType =>
        val col = FilterApi.longColumn(name)
        val lit = java.lang.Long.valueOf(value.asInstanceOf[Number].longValue)
        op match {
          case "=" => Some(FilterApi.eq(col, lit))
          case "!=" | "<>" => Some(FilterApi.notEq(col, lit))
          case "<" => Some(FilterApi.lt(col, lit))
          case "<=" => Some(FilterApi.ltEq(col, lit))
          case ">" => Some(FilterApi.gt(col, lit))
          case ">=" => Some(FilterApi.gtEq(col, lit))
          case _ => None
        }
      case DataType.FloatType =>
        val col = FilterApi.floatColumn(name)
        val lit = java.lang.Float.valueOf(value.asInstanceOf[Number].floatValue)
        op match {
          case "=" => Some(FilterApi.eq(col, lit))
          case "!=" | "<>" => Some(FilterApi.notEq(col, lit))
          case "<" => Some(FilterApi.lt(col, lit))
          case "<=" => Some(FilterApi.ltEq(col, lit))
          case ">" => Some(FilterApi.gt(col, lit))
          case ">=" => Some(FilterApi.gtEq(col, lit))
          case _ => None
        }
      case DataType.DoubleType =>
        val col = FilterApi.doubleColumn(name)
        val lit = java.lang.Double.valueOf(value.asInstanceOf[Number].doubleValue)
        op match {
          case "=" => Some(FilterApi.eq(col, lit))
          case "!=" | "<>" => Some(FilterApi.notEq(col, lit))
          case "<" => Some(FilterApi.lt(col, lit))
          case "<=" => Some(FilterApi.ltEq(col, lit))
          case ">" => Some(FilterApi.gt(col, lit))
          case ">=" => Some(FilterApi.gtEq(col, lit))
          case _ => None
        }
      case DataType.BooleanType =>
        val col = FilterApi.booleanColumn(name)
        val lit = java.lang.Boolean.valueOf(value.asInstanceOf[Boolean])
        op match {
          case "=" => Some(FilterApi.eq(col, lit))
          case "!=" | "<>" => Some(FilterApi.notEq(col, lit))
          case _ => None
        }
      case DataType.StringType =>
        val col = FilterApi.binaryColumn(name)
        val lit = Binary.fromString(value.asInstanceOf[String])
        op match {
          case "=" => Some(FilterApi.eq(col, lit))
          case "!=" | "<>" => Some(FilterApi.notEq(col, lit))
          case "<" => Some(FilterApi.lt(col, lit))
          case "<=" => Some(FilterApi.ltEq(col, lit))
          case ">" => Some(FilterApi.gt(col, lit))
          case ">=" => Some(FilterApi.gtEq(col, lit))
          case _ => None
        }
      case DataType.BinaryType =>
        val col = FilterApi.binaryColumn(name)
        val lit = Binary.fromConstantByteArray(value.asInstanceOf[Array[Byte]])
        op match {
          case "=" => Some(FilterApi.eq(col, lit))
          case "!=" | "<>" => Some(FilterApi.notEq(col, lit))
          case _ => None
        }
      case _: DataType.DecimalType => None
      case DataType.NullType => None
    }
  }

  private def swap(op: String): String = op match {
    case "<" => ">"
    case "<=" => ">="
    case ">" => "<"
    case ">=" => "<="
    case _ => op
  }
}
