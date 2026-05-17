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

  /** Promote `(l, r)` to a common numeric type for arithmetic and comparison. */
  def promotePair(l: Expr, r: Expr): (Expr, Expr, DataType) = {
    val lt = l.dataType
    val rt = r.dataType
    if (lt == rt) (l, r, lt)
    else if (DataType.isNumeric(lt) && DataType.isNumeric(rt)) {
      val target = DataType.widerNumeric(lt, rt)
      (implicitCast(l, target), implicitCast(r, target), target)
    } else if (lt == DataType.NullType) (implicitCast(l, rt), r, rt)
    else if (rt == DataType.NullType) (l, implicitCast(r, lt), lt)
    else if (lt == DataType.StringType || rt == DataType.StringType) {
      // String comparison: coerce non-strings to string.
      (implicitCast(l, DataType.StringType), implicitCast(r, DataType.StringType), DataType.StringType)
    } else (l, r, lt)
  }
}
