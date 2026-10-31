package com.transformer.sql.plan

import com.transformer.core.{DataType, Schema}

import java.util.Locale

/** Helpers for the analyzer: name resolution, implicit casting, type widening. */
object Analyzer {

  /** Bind `(qualifier, name)` against one or more child schemas with optional aliases. */
  def resolveColumn(
      qualifier: Option[String],
      name: String,
      sources: Seq[(Option[String], Schema)]): ColRefExpr =
    findColumn(qualifier, name, sources).getOrElse(throw unknownColumn(qualifier, name, sources))

  /** The column `(qualifier, name)` names among `sources`, if one does; an
    * error when several do. */
  def findColumn(
      qualifier: Option[String],
      name: String,
      sources: Seq[(Option[String], Schema)]): Option[ColRefExpr] = {
    val nameLower = name.toLowerCase(Locale.ROOT)
    val qLower = qualifier.map(_.toLowerCase(Locale.ROOT))

    // Index columns in source order, tracking offset within concatenated schema.
    var offset = 0
    var matches: List[ColRefExpr] = Nil
    sources.foreach { case (alias, schema) =>
      val aliasLower = alias.map(_.toLowerCase(Locale.ROOT))
      schema.fields.zipWithIndex.foreach { case (f, i) =>
        val fNameLower = f.name.toLowerCase(Locale.ROOT)
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
      case Nil => None
      case one :: Nil => Some(one)
      case multi =>
        throw new IllegalArgumentException(
          s"Ambiguous column reference '$name' matches ${multi.size} columns"
        )
    }
  }

  /** The error for a column none of `sources` has, listing the ones they do. */
  def unknownColumn(
      qualifier: Option[String],
      name: String,
      sources: Seq[(Option[String], Schema)]): IllegalArgumentException = {
    val qstr = qualifier.map(q => s"$q.").getOrElse("")
    val available = sources.flatMap { case (a, s) =>
      s.fieldNames.map(n => a.map(_ + "." + n).getOrElse(n))
    }.mkString(", ")
    new IllegalArgumentException(s"Unknown column '$qstr$name'. Available: [$available]")
  }

  /** Insert an implicit cast if `from != to` and the cast is safe. A BINARY
    * casts to nothing else, so a byte array beside another type is an error
    * rather than its identity read as a string. */
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
    * arithmetic, the three of a BETWEEN, an IN list's left side and items, or
    * NULLIF's two.
    *
    * Numbers widen to the widest among them, and NULL takes any type. A string
    * literal beside an operand of another type is read as that type now, at
    * bind time, so `i < '10'` compares numbers, `ts < '2026-01-01 06:00:00'`
    * compares instants, and a literal that is no such value fails before the
    * query runs. Every other mix is an error, naming the CAST that would say
    * what is meant: comparing a number or a date as text, a date at its
    * midnight, or a boolean as 1 or 0 are all choices, and the engine makes
    * none of them for the user. */
  def unify(operands: Seq[Expr]): (Seq[Expr], DataType) =
    meet(operands, _.reduce(commonType), cannotCompare)

  /** One type for values that become one result: the THEN and ELSE values of
    * a CASE, the arguments of COALESCE, GREATEST and LEAST, IF's two values,
    * LAG's or LEAD's value and default, and one column of every arm of a
    * UNION. `what` names them in an error.
    *
    * They meet as [[unify]]'s operands do, but for a DECIMAL. A comparison
    * only reads its operands; these values are kept, under the type found
    * here, so it must hold each of them. Two DECIMALs meet as the DECIMAL
    * that holds both ([[Decimals.holding]]), an INT or a BIGINT as the
    * DECIMAL its every value fits, and a number written in the query as the
    * decimal it is, so `COALESCE(price, 0)` is `price`'s type. A FLOAT or
    * DOUBLE that is not written in the query is an error, as it is in
    * [[arithmetic]]: no DECIMAL holds every DOUBLE, and a DOUBLE holds the
    * DECIMAL's values only approximately. */
  def branches(what: String, values: Seq[Expr]): (Seq[Expr], DataType) = {
    val read =
      if (values.exists(e => isDecimal(e.dataType))) values.map(e => writtenDecimal(e).getOrElse(e))
      else values
    meet(read, branchType(what, _), cannotMix(what, _, _))
  }

  /** `operands` under one type: `common`'s for the types of those that have
    * one, NULL taking any type and a string literal read as that type now;
    * `cannot` is the error for a type the literal is no text of. */
  private def meet(
      operands: Seq[Expr],
      common: Seq[DataType] => DataType,
      cannot: (DataType, DataType) => IllegalArgumentException): (Seq[Expr], DataType) = {
    val typed = operands.filterNot(e => e.dataType == DataType.NullType || isStringLiteral(e))
    val t =
      if (typed.nonEmpty) common(typed.map(_.dataType))
      else if (operands.exists(isStringLiteral)) DataType.StringType
      else DataType.NullType
    val met = operands.map {
      case LitExpr(s: String, DataType.StringType) if t != DataType.StringType =>
        if (readsText(t)) readLiteral(s, t) else throw cannot(t, DataType.StringType)
      case e => implicitCast(e, t)
    }
    (met, t)
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
    * as the type the others meet as. Two DECIMALs are compared as the DECIMAL
    * that holds both as they are, however many digits it takes, since a CAST
    * between DECIMALs rounds to the narrower one. */
  private def commonType(a: DataType, b: DataType): DataType = (a, b) match {
    case _ if a == b => a
    case (x: DataType.DecimalType, y: DataType.DecimalType) => Decimals.wider(Seq(x, y))
    case _ if DataType.isNumeric(a) && DataType.isNumeric(b) => DataType.widerNumeric(a, b)
    case _ => throw cannotCompare(a, b)
  }

  /** The type values of `types` become one result as ([[branches]]), or the
    * error saying why there is none. */
  private def branchType(what: String, types: Seq[DataType]): DataType = {
    val decimals = types.collect { case d: DataType.DecimalType => d }
    if (decimals.isEmpty) types.reduce { (a, b) =>
      if (a == b) a
      else if (DataType.isNumeric(a) && DataType.isNumeric(b)) DataType.widerNumeric(a, b)
      else throw cannotMix(what, a, b)
    } else {
      types.find(!DataType.isNumeric(_)).foreach(t => throw cannotMix(what, decimals.head, t))
      types.find(t => t == DataType.FloatType || t == DataType.DoubleType).foreach { t =>
        val name = typeName(t)
        throw new IllegalArgumentException(
          s"$what cannot mix ${typeName(decimals.head)} and $name: a $name has no scale for a DECIMAL result " +
            s"to keep; CAST the DECIMAL to $name to return a $name")
      }
      Decimals.holding(decimals ++ types.filter(DataType.isIntegral).map(Decimals.ofIntegral))
    }
  }

  private def isDecimal(t: DataType): Boolean = t.isInstanceOf[DataType.DecimalType]

  /** `e` as the decimal it is, when it is a number written in the query:
    * `2.5` is a DECIMAL(2,1), and `-1.5`, which binds as a minus over the
    * literal 1.5, that decimal's negation. */
  private def writtenDecimal(e: Expr): Option[LitExpr] = e match {
    case LitExpr(n: Number, t) if !isDecimal(t) => Some(Decimals.literal(n))
    case UnaryOpExpr("-", LitExpr(n: Number, t), _) if !isDecimal(t) =>
      val lit = Decimals.literal(n)
      Some(lit.copy(value = lit.value.asInstanceOf[java.math.BigDecimal].negate))
    case _ => None
  }

  /** The types a CAST reads from text. */
  private def readsText(t: DataType): Boolean = t match {
    case DataType.IntType | DataType.LongType | DataType.FloatType | DataType.DoubleType |
         DataType.BooleanType | DataType.DateType | DataType.TimestampType => true
    case _ => false
  }

  private def cannotCompare(a: DataType, b: DataType): IllegalArgumentException = {
    val (x, y) = stringSecond(a, b)
    val pair = Set(x, y)
    val advice =
      if (y == DataType.StringType && readsText(x)) {
        val t = typeName(x)
        s"; only a string literal is read as ${article(t)} $t, so CAST the expression to $t, or the $t to STRING to compare text"
      } else if (pair.forall(isTemporal))
        "; CAST one side: a DATE as a TIMESTAMP is its midnight, a TIMESTAMP as a DATE its date in UTC"
      else if (pair.contains(DataType.BooleanType) && pair.exists(DataType.isNumeric))
        "; CAST the BOOLEAN to INT for 1 and 0, or compare it with TRUE or FALSE"
      else ""
    new IllegalArgumentException(s"Cannot compare ${typeName(x)} with ${typeName(y)}$advice")
  }

  /** [[cannotCompare]] for values that become one result ([[branches]]). */
  private def cannotMix(what: String, a: DataType, b: DataType): IllegalArgumentException = {
    val (x, y) = stringSecond(a, b)
    val pair = Set(x, y)
    val advice =
      if (y == DataType.StringType && readsText(x)) {
        val t = typeName(x)
        s"; only a string literal is read as ${article(t)} $t, so CAST the STRING to $t, or the $t to STRING"
      } else if (pair.forall(isTemporal))
        "; CAST one: a DATE as a TIMESTAMP is its midnight, a TIMESTAMP as a DATE its date in UTC"
      else if (pair.contains(DataType.BooleanType) && pair.exists(DataType.isNumeric))
        "; CAST the BOOLEAN to INT for 1 and 0"
      else ""
    new IllegalArgumentException(s"$what cannot mix ${typeName(x)} and ${typeName(y)}$advice")
  }

  /** A string is named second, after the type a literal would be read as. */
  private def stringSecond(a: DataType, b: DataType): (DataType, DataType) =
    if (a == DataType.StringType) (b, a) else (a, b)

  private def article(typeName: String): String = if ("AEIOU".indexOf(typeName.head) >= 0) "an" else "a"

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
    def asDecimal(e: Expr): Expr = writtenDecimal(e).getOrElse(e match {
      case _ if isDecimal(e.dataType) => e
      case _ if e.dataType == DataType.NullType => LitExpr(null, decimal)
      case _ if DataType.isIntegral(e.dataType) => CastExpr(e, Decimals.ofIntegral(e.dataType))
      case _ =>
        val t = typeName(e.dataType)
        throw new IllegalArgumentException(
          s"Arithmetic on ${typeName(decimal)} and $t is not supported: a $t has no scale for a DECIMAL " +
            s"result to keep; CAST the DECIMAL to $t to compute in $t")
    })
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

  /** `e` as the condition of `what` (WHERE, HAVING, ON, CASE WHEN, IF,
    * COUNT_IF, AND, OR or NOT), which is a BOOLEAN; NULL is the unknown truth
    * value. Any other value would have to be read as a truth value, a number
    * as TRUE when it is not 0, text as `'true'` or `'false'`, a date as
    * nothing at all, so it is an error naming the comparison or the CAST
    * that says which truth is meant. */
  def condition(e: Expr, what: String): Expr = e.dataType match {
    case DataType.BooleanType => e
    case DataType.NullType => implicitCast(e, DataType.BooleanType)
    case t =>
      val advice =
        if (isStringLiteral(e)) "write TRUE or FALSE"
        else if (DataType.isNumeric(t)) "compare it with a value, or CAST it to BOOLEAN for TRUE where it is not 0"
        else if (t == DataType.StringType) "compare it with a value, or CAST it to BOOLEAN to read 'true' and 'false'"
        else "compare it with a value"
      throw new IllegalArgumentException(s"$what takes a BOOLEAN, not ${typeName(t)}; $advice")
  }

  /** Refuse an argument of the function `fn` that is not the kind its
    * [[Funcs.Param]] says. */
  def checkArgument(fn: String, param: Funcs.Param, e: Expr): Unit = param match {
    case Funcs.Param.AnyValue => ()
    case Funcs.Param.Number => checkNumber(e.dataType, fn)
    case Funcs.Param.Integer(role) => checkInteger(e.dataType, s"$fn's $role")
    case Funcs.Param.Text => checkText(e.dataType, fn)
  }

  /** `what` (a math function, or an aggregate that adds its values up)
    * computes on numbers. Text goes through a CAST first, which says the
    * number type it is read as; a boolean is 1 or 0 only through a CAST that
    * says so; a date and a byte array are no number at all. */
  def checkNumber(t: DataType, what: String): Unit =
    if (!(DataType.isNumeric(t) || t == DataType.NullType)) {
      val why = t match {
        case DataType.BooleanType => "CAST it to INT for 1 and 0"
        case DataType.DateType => "a date is not a number"
        case DataType.TimestampType => "a timestamp is not a number"
        case DataType.BinaryType => "a byte array is not a number"
        case _ => "CAST it to a number"
      }
      throw new IllegalArgumentException(s"$what on ${typeName(t)} is not supported; $why")
    }

  /** `what` (a scale, a position, a length or a seed) counts, so it is an
    * INT or a BIGINT: a scale of 1.5 would have to mean 1 or 2. */
  def checkInteger(t: DataType, what: String): Unit =
    if (!(DataType.isIntegral(t) || t == DataType.NullType)) {
      val advice = if (meetsNumbers(t)) "; CAST it to INT" else ""
      throw new IllegalArgumentException(s"$what must be an integer, not ${typeName(t)}$advice")
    }

  /** The types a CAST takes to a number. */
  private def meetsNumbers(t: DataType): Boolean =
    DataType.isNumeric(t) || t == DataType.StringType || t == DataType.BooleanType

  /** `what`, which reads its operands as text (LIKE, `||`, a string
    * function), takes a STRING. Any other value's text is a choice the engine
    * would make for the user, how a DOUBLE prints (`1.0E10`) or where a
    * TIMESTAMP puts its zone, so it is a CAST to STRING, where the choice is
    * written down. A BINARY is no text at all and casts to nothing, for the
    * reason [[checkBinaryCast]] gives. */
  def checkText(t: DataType, what: String): Unit = t match {
    case DataType.StringType | DataType.NullType => ()
    case DataType.BinaryType =>
      throw new IllegalArgumentException(s"$what on BINARY is not supported; a byte array is not text")
    case _ =>
      throw new IllegalArgumentException(s"$what on ${typeName(t)} is not supported; CAST it to STRING")
  }

  private def typeName(t: DataType): String = t.name.toUpperCase(Locale.ROOT)
}
