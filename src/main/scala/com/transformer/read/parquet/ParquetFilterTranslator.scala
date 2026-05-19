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
  *   - `NOT(<above>)`
  *   - `<above> AND <above>`, `<above> OR <above>`
  *
  * Not pushed (left as residual in the FilterExec):
  *   - `IS NULL` / `IS NOT NULL` — parquet-mr's null-aware FilterApi requires
  *     erasure gymnastics from Scala that aren't worth the row-group savings
  *     (column null-counts rarely flip whole groups in real datasets).
  *   - predicates over computed expressions (`a - b > 0`)
  *   - predicates referencing two columns (`a > b`)
  *   - `LIKE` patterns (no public FilterApi LIKE)
  *   - `IN (...)` lists with non-literal items
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
    case _ => None
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
