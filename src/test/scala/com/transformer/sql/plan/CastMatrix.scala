package com.transformer.sql.plan

import com.transformer.core.DataType
import com.transformer.sql.plan.Semantics._

/** The cast matrix, `src/test/semantics/casts.txt`: `CAST(x AS T)` for every
  * operand kind `x` (a column of each of the ten types, DECIMAL at (5,2) and
  * (38,10), the untyped NULL, a text, an integer and a decimal literal) at
  * each of its edge values ([[Semantics.edges]]), and every target `T`: the
  * eight types a CAST takes, under their first name, and `DECIMAL(10,2)` and
  * `BINARY`, which it does not. A column's cast is bound once, so where the
  * binder refuses it the cell is the kind's, for any value, as it is for a
  * literal whose every value the binder refuses alike. */
object CastMatrix {

  val File = "casts.txt"

  /** Every target, as a CAST writes it. */
  val Targets: Seq[String] = FunctionCatalog.CastTargets.map(_._2.head) ++ Seq("DECIMAL(10,2)", "BINARY")

  private val Widths = Seq(26, 16, 44)

  def generate(): Generated = {
    val lines = Vector.newBuilder[String]
    val raised = Vector.newBuilder[Raised]
    val diverged = Vector.newBuilder[String]
    def cell(target: String, kind: Kind, value: String, sql: String, outcome: Outcome): Unit = {
      val l = line(Seq(s"CAST(x AS $target)", kind.label, value), Widths, outcome)
      lines += l
      raised ++= Semantics.raised(sql, Seq(outcome))
      if (outcome.isInstanceOf[Diverged]) diverged += l
    }
    for (target <- Targets; kind <- Kinds) kind match {
      case Column(t) =>
        val columns = Seq("x" -> t)
        val sql = s"SELECT CAST(x AS $target) AS v FROM t"
        bindExpr(sql, columns) match {
          case Left(e) => cell(target, kind, "(any)", sql, BindError(e))
          case Right(e) =>
            val operands = edges(kind)
            val rows = operands.map(o => Seq(o.value))
            val outcomes = acrossABatch(e, columns, rows, rows.map(evaluate(e, columns, _)))
            operands.zip(outcomes).foreach { case (o, outcome) => cell(target, kind, o.shown, sql, outcome) }
        }
      case _ =>
        val columns = Seq("x" -> DataType.IntType)
        val cells = edges(kind).map { o =>
          val sql = s"SELECT CAST(${o.sql("x")} AS $target) AS v FROM t"
          (o, sql, bindExpr(sql, columns).fold(BindError, evaluate(_, columns, Seq(null))))
        }
        // A literal whose every value is refused alike is refused for any.
        val shown = cells.map(c => show(c._3)).distinct
        if (cells.size > 1 && shown.size == 1 && cells.head._3.isInstanceOf[BindError])
          cell(target, kind, "(any)", cells.head._2, cells.head._3)
        else cells.foreach { case (o, sql, outcome) => cell(target, kind, o.shown, sql, outcome) }
    }
    Generated(header(File, About) ++ lines.result(), raised.result(), diverged.result())
  }

  private val About = Seq(
    "The cast matrix: CAST(x AS T) for every kind of operand x at each of its edge values, and every target T.",
    "An operand is a column of a type (its value shown as a value of the type) or a literal (shown as written);",
    "`(any)` stands for every value of the kind, where the cast is refused before it runs whatever the value.",
    "",
    "A cell is the result's type and value, as an slt exact column prints it (a STRING quoted), or the error:",
    "`bind error` when the query is refused before it runs, `run error` when it fails on the value. The row path",
    "(eval) and the vector path (evalVec, on the row alone and on every value of the column at once) agree on",
    "each, and every error is a clean one by CleanRejection's rule.")
}
