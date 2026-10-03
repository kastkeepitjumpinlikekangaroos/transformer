package com.transformer.sql.plan

import com.transformer.core.{DataType, Schema}

/** Helpers for the analyzer: name resolution, implicit casting, type widening. */
object Analyzer {

  /** Bind `(qualifier, name)` against one or more child schemas with optional aliases. */
  def resolveColumn(
      qualifier: Option[String],
      name: String,
      sources: Seq[(Option[String], Schema)]): ColRefExpr = {
    val nameLower = name.toLowerCase
    val qLower = qualifier.map(_.toLowerCase)

    // Index columns in source order, tracking offset within concatenated schema.
    var offset = 0
    var matches: List[ColRefExpr] = Nil
    sources.foreach { case (alias, schema) =>
      val aliasLower = alias.map(_.toLowerCase)
      schema.fields.zipWithIndex.foreach { case (f, i) =>
        val fNameLower = f.name.toLowerCase
        val nameMatches = fNameLower == nameLower
        val qualMatches = qLower.isEmpty ||
          aliasLower.contains(qLower.get) ||
          (qLower.contains(fNameLower) && false)  // unused, kept for symmetry
        if (nameMatches && qualMatches) {
          matches = ColRefExpr(offset + i, f.name, f.dataType) :: matches
        }
      }
      offset += schema.length
    }
    matches match {
      case Nil =>
        val qstr = qualifier.map(q => s"$q.").getOrElse("")
        val available = sources.flatMap { case (a, s) =>
          s.fieldNames.map(n => a.map(_ + "." + n).getOrElse(n))
        }.mkString(", ")
        throw new IllegalArgumentException(
          s"Unknown column '$qstr$name'. Available: [$available]"
        )
      case one :: Nil => one
      case multi =>
        throw new IllegalArgumentException(
          s"Ambiguous column reference '$name' matches ${multi.size} columns"
        )
    }
  }

  /** Insert an implicit cast if `from != to` and the cast is safe. */
  def implicitCast(e: Expr, to: DataType): Expr =
    if (e.dataType == to) e else CastExpr(e, to)

  /** Promote `(l, r)` to a common type for arithmetic and comparison: the
    * two-operand case of [[unify]]. */
  def promotePair(l: Expr, r: Expr): (Expr, Expr, DataType) = {
    val (operands, t) = unify(Seq(l, r))
    (operands(0), operands(1), t)
  }

  /** One type for operands that meet: the two sides of a comparison or of
    * arithmetic, the three of a BETWEEN, or an IN list's left side and items.
    *
    * Numbers widen to the widest among them, and NULL takes any type. A string
    * literal beside an operand of another type is read as that type now, at
    * bind time, so `i < '10'` compares numbers, `ts < '2026-01-01 06:00:00'`
    * compares instants, and a literal that is no such value fails before the
    * query runs. Every other mix is an error, naming the CAST that would say
    * what is meant: comparing a number or a date as text, a date at its
    * midnight, or a boolean as 1 or 0 are all choices, and the engine makes
    * none of them for the user. */
  def unify(operands: Seq[Expr]): (Seq[Expr], DataType) = {
    val typed = operands.filterNot(e => e.dataType == DataType.NullType || isStringLiteral(e))
    val common =
      if (typed.nonEmpty) typed.map(_.dataType).reduce(commonType)
      else if (operands.exists(isStringLiteral)) DataType.StringType
      else DataType.NullType
    val unified = operands.map {
      case LitExpr(s: String, DataType.StringType) if common != DataType.StringType =>
        if (readsText(common)) readLiteral(s, common) else throw cannotCompare(common, DataType.StringType)
      case e => implicitCast(e, common)
    }
    (unified, common)
  }

  def isStringLiteral(e: Expr): Boolean = e match {
    case LitExpr(_: String, DataType.StringType) => true
    case _ => false
  }

  /** `s` read as `t` now, through the CAST text takes to `t`, so text that is
    * no such value fails before the query runs. */
  def readLiteral(s: String, t: DataType): LitExpr = LitExpr(Casts.cast(s, DataType.StringType, t), t)

  /** The type operands of types `a` and `b` meet as, or the error saying why
    * there is none. A string literal is never one of them: [[unify]] reads it
    * as the type the others meet as. */
  private def commonType(a: DataType, b: DataType): DataType =
    if (a == b) a
    else if (DataType.isNumeric(a) && DataType.isNumeric(b)) DataType.widerNumeric(a, b)
    else throw cannotCompare(a, b)

  /** The types a CAST reads from text. */
  private def readsText(t: DataType): Boolean = t match {
    case DataType.IntType | DataType.LongType | DataType.FloatType | DataType.DoubleType |
         DataType.BooleanType | DataType.DateType | DataType.TimestampType => true
    case _ => false
  }

  private def cannotCompare(a: DataType, b: DataType): IllegalArgumentException = {
    // A string is named second, after the type a literal would be read as.
    val (x, y) = if (a == DataType.StringType) (b, a) else (a, b)
    val pair = Set(x, y)
    val advice =
      if (y == DataType.StringType && readsText(x)) {
        val t = typeName(x)
        val article = if ("AEIOU".indexOf(t.head) >= 0) "an" else "a"
        s"; only a string literal is read as $article $t, so CAST the expression to $t, or the $t to STRING to compare text"
      } else if (pair.forall(isTemporal))
        "; CAST one side: a DATE as a TIMESTAMP is its midnight, a TIMESTAMP as a DATE its date in UTC"
      else if (pair.contains(DataType.BooleanType) && pair.exists(DataType.isNumeric))
        "; CAST the BOOLEAN to INT for 1 and 0, or compare it with TRUE or FALSE"
      else ""
    new IllegalArgumentException(s"Cannot compare ${typeName(x)} with ${typeName(y)}$advice")
  }

  private def isTemporal(t: DataType): Boolean = t == DataType.DateType || t == DataType.TimestampType

  /** Arithmetic is on numbers (and NULL). There is no interval type for a date
    * or a time to produce, and text or a boolean taken as a number would be a
    * guess. */
  def checkArithmetic(t: DataType): Unit =
    if (!(DataType.isNumeric(t) || t == DataType.NullType)) {
      val advice = if (t == DataType.StringType) "; CAST it to a number, or use || to join text" else ""
      throw new IllegalArgumentException(s"Arithmetic on ${typeName(t)} is not supported$advice")
    }

  /** Refuse a CAST whose answer would be a guess. A number or a boolean has no
    * date or time in it (days? seconds? milliseconds since when?), and a date
    * or time is no number; text and the other temporal type are the ways in
    * and out. */
  def checkCast(from: DataType, to: DataType): Unit = {
    def meetsTemporal(t: DataType) = isTemporal(t) || t == DataType.StringType || t == DataType.NullType
    if ((isTemporal(to) && !meetsTemporal(from)) || (isTemporal(from) && !meetsTemporal(to)))
      throw new IllegalArgumentException(s"Cannot cast ${typeName(from)} to ${typeName(to)}")
  }

  private def typeName(t: DataType): String = t.name.toUpperCase
}
