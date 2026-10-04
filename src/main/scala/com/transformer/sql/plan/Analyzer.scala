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

  /** Insert an implicit cast if `from != to` and the cast is safe. A BINARY
    * casts to nothing else, so a byte array in a WHERE, or beside text in a
    * CASE, is an error rather than its identity read as a string. */
  def implicitCast(e: Expr, to: DataType): Expr =
    if (e.dataType == to) e
    else {
      checkBinaryCast(e.dataType, to)
      CastExpr(e, to)
    }

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

  /** `l op r` for an arithmetic `op`, typed: both sides widen to the type
    * they meet as ([[unify]]), or, where one is a DECIMAL, the result is
    * declared as [[decimalArithmetic]] says. */
  def arithmetic(op: String, l: Expr, r: Expr): BinOpExpr = {
    // A string literal is checked once unify has read it as the number beside
    // it: `x + '1'` adds 1, and `'1' + '2'`, with no number to read the
    // literals as, is arithmetic on text.
    Seq(l, r).filterNot(isStringLiteral).foreach(e => checkArithmetic(e.dataType))
    val (le, re, t) = promotePair(l, r)
    checkArithmetic(t)
    t match {
      case _: DataType.DecimalType => decimalArithmetic(op, l, r)
      case _ => BinOpExpr(op, le, re, t)
    }
  }

  /** Arithmetic with a DECIMAL on one side. Each side is read as a DECIMAL of
    * its own: a number written in the query as the decimal it is
    * ([[Decimals.literal]]), an INT or a BIGINT as the DECIMAL its every value
    * fits, and NULL as the other side's type. The result is declared as
    * [[Decimals.resultType]] says, which depends on both sides' digits, so an
    * INT is not read as the other side's DECIMAL(5,2), which would leave its
    * value no room. A FLOAT or DOUBLE that is not written in the query is an
    * error: it has no scale, and any DECIMAL it were read as would be a guess
    * at how many digits of it the result should keep. */
  private def decimalArithmetic(op: String, l: Expr, r: Expr): BinOpExpr = {
    val decimal = Seq(l, r).map(_.dataType).collectFirst { case d: DataType.DecimalType => d }.get
    def asDecimal(e: Expr): Expr = e match {
      case _ if e.dataType.isInstanceOf[DataType.DecimalType] => e
      case _ if e.dataType == DataType.NullType => LitExpr(null, decimal)
      case LitExpr(n: Number, _) => Decimals.literal(n)
      // `-1.5` binds as a minus over the literal 1.5.
      case UnaryOpExpr("-", LitExpr(n: Number, _), _) =>
        val lit = Decimals.literal(n)
        lit.copy(value = lit.value.asInstanceOf[java.math.BigDecimal].negate)
      case _ if DataType.isIntegral(e.dataType) => CastExpr(e, Decimals.ofIntegral(e.dataType))
      case _ =>
        val t = typeName(e.dataType)
        throw new IllegalArgumentException(
          s"Arithmetic on ${typeName(decimal)} and $t is not supported: a $t has no scale for a DECIMAL " +
            s"result to keep; CAST the DECIMAL to $t to compute in $t")
    }
    val (le, re) = (asDecimal(l), asDecimal(r))
    def declared(e: Expr): DataType.DecimalType = e.dataType.asInstanceOf[DataType.DecimalType]
    BinOpExpr(op, le, re, Decimals.resultType(op, declared(le), declared(re)))
  }

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
    * and out. A byte array is none of these and casts to nothing else. */
  def checkCast(from: DataType, to: DataType): Unit = {
    def meetsTemporal(t: DataType) = isTemporal(t) || t == DataType.StringType || t == DataType.NullType
    if ((isTemporal(to) && !meetsTemporal(from)) || (isTemporal(from) && !meetsTemporal(to)))
      throw new IllegalArgumentException(s"Cannot cast ${typeName(from)} to ${typeName(to)}")
    checkBinaryCast(from, to)
  }

  /** A byte array is not text, a number or a truth value. Every cast from one
    * read the array's identity as text, `[B@1a96d94c`, a different string on
    * every run, and then parsed that if it could. */
  private def checkBinaryCast(from: DataType, to: DataType): Unit =
    if (from == DataType.BinaryType && to != DataType.BinaryType)
      throw new IllegalArgumentException(s"Cannot cast BINARY to ${typeName(to)}")

  /** `what`, which reads its operands as text (LIKE, `||`, a string
    * function), refuses a BINARY for the reason [[checkBinaryCast]] does. */
  def checkText(t: DataType, what: String): Unit =
    if (t == DataType.BinaryType)
      throw new IllegalArgumentException(s"$what on BINARY is not supported; a byte array is not text")

  private def typeName(t: DataType): String = t.name.toUpperCase
}
