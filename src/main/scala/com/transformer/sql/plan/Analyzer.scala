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

  /** Promote `(l, r)` to a common type for arithmetic and comparison.
    *
    * A DATE or TIMESTAMP meets another type only as a string literal, which
    * [[asTemporal]] reads as that type now, at bind time. Anything else beside
    * one is an error that names the CAST to write: comparing it as text, at a
    * midnight, or as a date in UTC are all choices, and the engine makes none
    * of them for the user. */
  def promotePair(l: Expr, r: Expr): (Expr, Expr, DataType) = {
    val lt = l.dataType
    val rt = r.dataType
    if (lt == rt) (l, r, lt)
    else if (DataType.isNumeric(lt) && DataType.isNumeric(rt)) {
      val target = DataType.widerNumeric(lt, rt)
      (implicitCast(l, target), implicitCast(r, target), target)
    } else if (lt == DataType.NullType) (implicitCast(l, rt), r, rt)
    else if (rt == DataType.NullType) (l, implicitCast(r, lt), lt)
    else if (isTemporal(lt)) (l, asTemporal(r, lt), lt)
    else if (isTemporal(rt)) (asTemporal(l, rt), r, rt)
    else if (lt == DataType.StringType || rt == DataType.StringType) {
      // String comparison: coerce non-strings to string.
      (implicitCast(l, DataType.StringType), implicitCast(r, DataType.StringType), DataType.StringType)
    } else (l, r, lt)
  }

  def isTemporal(t: DataType): Boolean = t == DataType.DateType || t == DataType.TimestampType

  /** `e` as the operand beside one of type `temporal`: a string literal read as
    * that type, so a bad one fails before the query runs, or an error. */
  def asTemporal(e: Expr, temporal: DataType): Expr = e match {
    case LitExpr(s: String, DataType.StringType) => LitExpr(Casts.cast(s, DataType.StringType, temporal), temporal)
    case _ =>
      val (t, o) = (typeName(temporal), typeName(e.dataType))
      val advice =
        if (isTemporal(e.dataType)) "; CAST one side: a DATE as a TIMESTAMP is its midnight, a TIMESTAMP as a DATE its date in UTC"
        else if (e.dataType == DataType.StringType)
          s"; only a string literal is read as a $t, so CAST the expression to $t, or the $t to STRING to compare text"
        else ""
      throw new IllegalArgumentException(s"Cannot compare $t with $o$advice")
  }

  /** The operands of an IN list, when one of them is a DATE or TIMESTAMP:
    * every other one must be of that type, NULL, or a string literal read as
    * that type, as in [[promotePair]]. */
  def unifyTemporal(operands: Seq[Expr]): Seq[Expr] =
    operands.find(e => isTemporal(e.dataType)) match {
      case None => operands
      case Some(anchor) =>
        val t = anchor.dataType
        operands.map(e => if (e.dataType == t || e.dataType == DataType.NullType) e else asTemporal(e, t))
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

  def typeName(t: DataType): String = t.name.toUpperCase
}
