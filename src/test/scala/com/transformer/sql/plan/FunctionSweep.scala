package com.transformer.sql.plan

import com.transformer.core.DataType
import com.transformer.fuzz.oracle.ExprParity
import com.transformer.sql.plan.FunctionCatalog.{Entry, Kind => FnKind}
import com.transformer.sql.plan.Funcs.Param
import com.transformer.sql.plan.Semantics._

import scala.util.control.NonFatal

/** The scalar function sweep, `functions-text.txt`, `functions-math.txt` and
  * `functions-flow.txt`: every function `FunctionCatalog` binds as a call,
  * at each arity it takes (a variadic one at one, two and three arguments),
  * each argument run over every kind of operand at its edge values
  * ([[Semantics.edges]], a flow function's at the first five of each), the
  * others at a typical value; and the two-argument numeric functions over
  * every pair of a small edge set.
  *
  * A cell is a value, a bind refusal, or a run-time error, the same on both
  * paths. A NULL that no argument's NULL explains must be explained by the
  * entry's NULL rule or its domain, which the cell names; a run-time error
  * must be one the entry declares, in `ExprParity`'s owned set and clean by
  * `CleanRejection`'s rule. Either failing is a [[Generated.diverged]] line,
  * which fails the tables' test. */
object FunctionSweep {

  val TextFile = "functions-text.txt"
  val MathFile = "functions-math.txt"
  val FlowFile = "functions-flow.txt"

  /** The file an entry's cells are in. */
  def fileOf(e: Entry): String = e.kind match {
    case FnKind.Flow | FnKind.Clock => FlowFile
    case _ if e.params.contains(Param.Text) => TextFile
    case _ => MathFile
  }

  /** Every entry the sweep calls: the scalar, flow, operator and clock ones. */
  val Swept: Seq[Entry] = FunctionCatalog.Entries.filter(e => FunctionCatalog.call(e.name).isDefined)

  /** The typical value of a parameter that is not swept: a number, a count
    * of its role, a text, a condition that chooses the swept value, or, for a
    * value the call answers with or compares, `y`, the swept kind's typical
    * value. */
  private def typicalSql(p: Param, kind: Kind, condition: Boolean): String = p match {
    case Param.Text => "'abc'"
    case Param.Number | Param.Operand => "2"
    case Param.Integer("scale") => "1"
    case Param.Integer(_) => "2"
    case Param.Condition => if (condition) "TRUE" else "FALSE"
    case Param.Branch | Param.Compared | Param.AnyValue => kind match {
      case _: Column => "y"
      case _ => typical(kind).shown
    }
  }

  /** The arities a sweep calls an entry at, and the positions it sweeps at
    * each: every position of a fixed arity; a variadic entry's first at one,
    * two and three arguments, and its second at two. */
  private def sweeps(e: Entry): Seq[(Int, Int)] = e.arity.max match {
    case Some(max) => (math.max(1, e.arity.min) to max).filterNot(e.readsRandom).flatMap(n => (0 until n).map(n -> _))
    case None => Seq(1 -> 0, 2 -> 0, 2 -> 1, 3 -> 0).filter(p => e.arity.admits(p._1))
  }

  /** The values a sweep runs an argument over: every edge value, but a flow
    * function's first five of each kind, whose type rule the coercion tables
    * already give. */
  private def values(e: Entry, kind: Kind): Seq[Operand] =
    if (fileOf(e) == FlowFile) edges(kind).take(5)
    else if (fileOf(e) == TextFile) edges(kind) ++ (kind match {
      case Column(DataType.StringType) => (Letters :+ Tabbed).map(t => Operand(kind, t, showValue(t, DataType.StringType)))
      case TextLiteral => Letters.map(t => Operand(kind, null, quote(t)))
      case _ => Nil
    })
    else edges(kind)

  /** Text whose case or length a character at its edge decides: a sharp s,
    * which UPPER makes two letters; a capital I with a dot above beside an i,
    * which a Turkish locale would map otherwise; and a character outside the
    * Basic Multilingual Plane, which Java holds as two chars and SQL counts
    * as one. Built from their code points, so no source file holds them. */
  private val Letters: Seq[String] =
    Seq(Character.toString(0xDF), Character.toString(0x130) + "i", "x" + Character.toString(0x20000) + "y")

  /** Spaces around a tab, of which TRIM removes the spaces alone. */
  private val Tabbed: String = " a" + Character.toString(9) + "b "

  /** The SQL of a call of `e` with `n` arguments, `swept` at `position`.
    * Beside a swept condition the values it chooses between are 1 and 2. */
  private def call(e: Entry, n: Int, position: Int, kind: Kind, swept: String): String = {
    val params = e.paramsAt(n)
    val conditionChooses = position != n - 1
    val choices = Iterator("1", "2")
    val args = params.indices.map { i =>
      if (i == position) swept
      else if (params(position) == Param.Condition && params(i) == Param.Branch) choices.next()
      else typicalSql(params(i), kind, conditionChooses)
    }
    e.kind match {
      case FnKind.Clock => s"${e.name} ()"
      case _ => s"${e.name}(${args.mkString(", ")})"
    }
  }

  /** The values the function itself was given on the row: its arguments,
    * evaluated, or None where the bound expression is no call of it. */
  private def argumentsOf(e: Expr, columns: Seq[(String, DataType)], row: Seq[Any]): Option[Seq[Any]] = {
    val b = batch(columns, Seq(row))
    try e match {
      case FuncExpr(_, args, _) => Some(args.map(_.eval(b, 0)))
      case BinOpExpr(_, l, r, _) => Some(Seq(l.eval(b, 0), r.eval(b, 0)))
      case _ => None
    } catch { case NonFatal(_) => None }
  }

  /** `outcome` of `entry` held to the entry: a NULL explained by its NULL rule
    * or its domain, named in the cell where the arguments were not NULL; an
    * error it declares, which `ExprParity` owns. Returns the cell's text and
    * what is wrong with it, if anything. */
  private def check(entry: Entry, outcome: Outcome, args: => Option[Seq[Any]]): (String, Option[String]) =
    outcome match {
      case Value(DataType.DoubleType, d: java.lang.Double) if Approximate(entry.name) && d != 0.0 && !d.isNaN &&
          !d.isInfinite =>
        (s"DOUBLE ~${new java.math.BigDecimal(d.doubleValue).round(new java.math.MathContext(15)).stripTrailingZeros}", None)
      case Value(t, null) =>
        args match {
          case Some(a) => entry.explainsNull(a) match {
            case Some(_) if a.contains(null) && entry.nulls == FunctionCatalog.Nulls.Propagate => (show(outcome), None)
            case Some(why) => (s"${show(outcome)} ($why)", None)
            case None => (show(outcome), Some("a NULL neither its NULL rule nor its domain explains"))
          }
          case None => (show(outcome), None)
        }
      case RunError(err) =>
        val m = message(err)
        if (!entry.errors.exists(_.matches(m))) (show(outcome), Some(s"an error ${entry.name} does not declare"))
        else if (!ExprParity.owned(err)) (show(outcome), Some("an error ExprParity does not own"))
        else (show(outcome), None)
      case _ => (show(outcome), None)
    }

  private val Widths = Seq(34, 16, 44)

  /** The functions whose DOUBLE Java's `Math` may round in its last bit
    * otherwise on another platform or JVM, so a table shows it to 15
    * significant digits, marked `~`: all but the square root, which IEEE
    * rounds exactly, and DEGREES and RADIANS, one multiplication each. */
  private val Approximate: Set[String] = Set("CBRT", "EXP", "LN", "LOG", "LOG10", "LOG2", "SIN", "COS", "TAN", "ASIN",
    "ACOS", "ATAN", "SINH", "COSH", "TANH", "ATAN2", "POWER")

  /** A table's lines for the entries of `file`. */
  def generate(file: String): Generated = {
    val lines = Vector.newBuilder[String]
    val raised = Vector.newBuilder[Raised]
    val wrong = Vector.newBuilder[String]
    def cell(fields: Seq[String], sql: String, outcome: Outcome, checked: (String, Option[String])): Unit = {
      val l = fields.zipAll(Widths, "", 0).map { case (f, w) => f.padTo(w, ' ') + " " }.mkString + "-> " + checked._1
      lines += l
      raised ++= Semantics.raised(sql, Seq(outcome))
      if (outcome.isInstanceOf[Diverged]) wrong += l
      checked._2.foreach(why => wrong += s"$l: $why")
    }
    def plain(fields: Seq[String], text: String): Unit =
      lines += fields.zipAll(Widths, "", 0).map { case (f, w) => f.padTo(w, ' ') + " " }.mkString + "-> " + text
    for (entry <- Swept if fileOf(entry) == file) {
      if (entry.kind == FnKind.Clock) {
        // The statement's start, whose value is no table's.
        val sql = s"SELECT ${entry.name} AS v FROM t"
        plain(Seq(entry.name, "", ""), bindExpr(sql, Seq("x" -> DataType.IntType)) match {
          case Left(e) => show(BindError(e))
          case Right(e) => s"${DataType.sqlName(e.dataType)} (the statement's start, the same on every row)"
        })
      }
      // A call with no argument: a constant's, or a variadic entry's.
      if (entry.kind != FnKind.Clock && entry.arity.admits(0) && !entry.readsRandom(0)) {
        val sql = s"SELECT ${entry.name}() AS v FROM t"
        val columns = Seq("x" -> DataType.IntType)
        val outcome = bindExpr(sql, columns).fold(BindError, evaluate(_, columns, Seq(null)))
        cell(Seq(s"${entry.name}()", "", ""), sql, outcome, check(entry, outcome, Some(Nil)))
      }
      // A call that reads a fresh value each time, whose value is no table's.
      entry.random.toSeq.sorted.foreach { n =>
        val written = s"${entry.name}(${Seq.fill(n)("1").mkString(", ")})"
        plain(Seq(written, "", ""), bindExpr(s"SELECT $written AS v FROM t", Seq("x" -> DataType.IntType)) match {
          case Left(e) => show(BindError(e))
          case Right(e) => s"${DataType.sqlName(e.dataType)} (a fresh value each time it is read)"
        })
      }
      for ((n, position) <- sweeps(entry); kind <- Kinds) {
        val shownCall = call(entry, n, position, kind, "x")
        kind match {
          case Column(t) =>
            val columns = Seq("x" -> t, "y" -> t)
            val sql = s"SELECT $shownCall AS v FROM t"
            bindExpr(sql, columns) match {
              case Left(e) => cell(Seq(shownCall, kind.label, "(any)"), sql, BindError(e), (show(BindError(e)), None))
              case Right(e) =>
                val operands = values(entry, kind)
                val typicalValue = typical(kind).value
                val rows = operands.map(o => Seq(o.value, typicalValue))
                val outcomes = acrossABatch(e, columns, rows, rows.map(evaluate(e, columns, _)))
                operands.zip(rows).zip(outcomes).foreach { case ((o, row), outcome) =>
                  cell(Seq(shownCall, kind.label, o.shown), sql, outcome, check(entry, outcome, argumentsOf(e, columns, row)))
                }
            }
          case _ =>
            val columns = Seq("x" -> DataType.IntType, "y" -> DataType.IntType)
            val cells = values(entry, kind).map { o =>
              val sql = s"SELECT ${call(entry, n, position, kind, o.sql("x"))} AS v FROM t"
              val bound = bindExpr(sql, columns)
              val outcome = bound.fold(BindError, evaluate(_, columns, Seq(null, null)))
              (o, sql, outcome, bound.toOption)
            }
            val shown = cells.map(c => show(c._3)).distinct
            if (cells.size > 1 && shown.size == 1 && cells.head._3.isInstanceOf[BindError])
              cell(Seq(shownCall, kind.label, "(any)"), cells.head._2, cells.head._3, (shown.head, None))
            else cells.foreach { case (o, sql, outcome, bound) =>
              cell(Seq(shownCall, kind.label, o.shown), sql, outcome,
                check(entry, outcome, bound.flatMap(argumentsOf(_, columns, Seq(null, null)))))
            }
        }
      }
      // Every pair of a small edge set, for the two-argument numeric functions.
      Pairs.get(entry.name).foreach { case (shownCall, xs, ys) =>
        for ((xt, xvs) <- xs; (yt, yvs) <- ys) {
          val columns = Seq("x" -> xt, "y" -> yt)
          val sql = s"SELECT $shownCall AS v FROM t"
          bindExpr(sql, columns) match {
            case Left(e) => cell(Seq(shownCall, DataType.sqlName(xt), "(any)"), sql, BindError(e), (show(BindError(e)), None))
            case Right(e) =>
              val rows = for (xv <- xvs; yv <- yvs) yield Seq(xv, yv)
              val outcomes = acrossABatch(e, columns, rows, rows.map(evaluate(e, columns, _)))
              rows.zip(outcomes).foreach { case (row, outcome) =>
                cell(Seq(shownCall, s"${DataType.sqlName(xt)} ${showValue(row(0), xt)}",
                  s"${DataType.sqlName(yt)} ${showValue(row(1), yt)}"), sql, outcome,
                  check(entry, outcome, argumentsOf(e, columns, row)))
              }
          }
        }
      }
    }
    Generated(header(file, About(file)) ++ lines.result(), raised.result(), wrong.result())
  }

  /** A small edge set of DOUBLEs, for the pairs. */
  private val Doubles: Seq[Any] = Seq(null, 0.0, -0.0, 1.0, -1.0, 2.5, -2.5, Double.NaN,
    Double.PositiveInfinity, Double.NegativeInfinity)

  private def decimals(xs: String*): Seq[Any] = xs.map(new java.math.BigDecimal(_))

  /** A scale, a position or a length: NULL, past every edge either way, and
    * the small ones. */
  private val Scales: Seq[Any] = Seq(null, -400, -2, -1, 0, 1, 2, 400)

  /** For each two-argument numeric function, its call over `x` and `y` and
    * the types and values each runs over. */
  private val Pairs: Map[String, (String, Seq[(DataType, Seq[Any])], Seq[(DataType, Seq[Any])])] = Map(
    "POWER" -> ("POWER(x, y)", Seq(DataType.DoubleType -> Doubles), Seq(DataType.DoubleType -> Doubles)),
    "ATAN2" -> ("ATAN2(x, y)", Seq(DataType.DoubleType -> Doubles), Seq(DataType.DoubleType -> Doubles)),
    "LOG" -> ("LOG(x, y)", Seq(DataType.DoubleType -> Doubles), Seq(DataType.DoubleType -> Doubles)),
    "MOD" -> ("MOD(x, y)", Seq(DataType.DoubleType -> Doubles, DataType.IntType -> Seq(null, 0, 7, -7, Int.MinValue)),
      Seq(DataType.DoubleType -> Doubles, DataType.IntType -> Seq(null, 0, 2, -1))),
    "ROUND" -> ("ROUND(x, y)", Seq(
      DataType.DoubleType -> Seq(null, 2.5, -2.5, 1.005, 123.456, Double.NaN, Double.PositiveInfinity),
      DataType.DecimalType(5, 2) -> decimals("2.50", "-2.50", "999.99"),
      DataType.IntType -> Seq(15, Int.MaxValue)), Seq(DataType.IntType -> Scales)),
    "TRUNC" -> ("TRUNC(x, y)", Seq(
      DataType.DoubleType -> Seq(null, 2.5, -2.5, 1.005, 123.456, Double.NaN, Double.PositiveInfinity),
      DataType.DecimalType(5, 2) -> decimals("2.50", "-2.50", "999.99"),
      DataType.IntType -> Seq(15, Int.MaxValue)), Seq(DataType.IntType -> Scales)),
    "SUBSTRING" -> ("SUBSTRING('abc', x, y)",
      Seq(DataType.LongType -> Seq(null, -1L, 0L, 1L, 2L, 4L, 2147483648L)),
      Seq(DataType.LongType -> Seq(null, -1L, 0L, 1L, 2L, Long.MaxValue))))

  private def About(file: String): Seq[String] = Seq(
    file match {
      case TextFile => "The function sweep, functions over text: LENGTH, UPPER, LOWER, TRIM, SUBSTRING, CONCAT."
      case MathFile => "The function sweep, functions of numbers: the rounding functions, ABS, SIGN, MOD and the math functions."
      case _ => "The function sweep, the flow functions (COALESCE, IF, NULLIF, GREATEST, LEAST) and the clock keywords."
    },
    "Every function at each arity it takes (a variadic one at one, two and three arguments), each argument run over",
    "every kind of operand x at its edge values (a flow function's at the first five of each), the others at a typical",
    "value: a number 2, a scale 1, a position or length 2, a text 'abc', a condition that chooses x, and for a value",
    "the call answers with or compares, y, a column of x's type holding the type's typical value (2, 2.5, 'abc',",
    "2026-01-01, TRUE, ...) or for a literal its typical literal (NULL, 'abc', 2, 2.5). The two-argument numeric",
    "functions also run over every pair of a small edge set, their cell naming both operands.",
    "",
    "A cell is the result's type and value, as an slt exact column prints it (a STRING quoted), or the error: `bind",
    "error` when the query is refused before it runs, `run error` when it fails on the value; `(any)` stands for every",
    "value of a kind refused alike. A NULL that no NULL argument explains names the rule of the function's",
    "FunctionCatalog entry that does (its domain, or a flow function's NULL rule). Each cell is the same on the row path",
    "and the vector path; every NULL is explained by the entry, and every run-time error is one it declares. A DOUBLE",
    "marked ~ is shown to 15 significant digits: Java's Math may round the last bit of an exponential, a logarithm, a",
    "power or a trigonometric function otherwise on another platform.")
}
