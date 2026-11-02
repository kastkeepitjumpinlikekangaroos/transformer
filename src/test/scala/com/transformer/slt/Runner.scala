package com.transformer.slt

import com.transformer.core._
import com.transformer.slt.Modes.Mode
import com.transformer.slt.Script._
import com.transformer.sql.exec.NestedLoopGuardException
import com.transformer.sql.plan.LogicalBuilder

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.util.control.NonFatal

/** Runs a sqllogictest script against the engine: carries out its statements
  * ([[Database]]), runs each query under the baseline mode and compares the
  * printed result with the script's, then runs it under every verification
  * mode and compares each run with the baseline's ([[Modes]]).
  *
  * Each record gets an [[Runner.Outcome]]. A script of this repo's own must
  * pass every record. SQLite's corpus is read leniently: a query the engine
  * refuses is counted as [[Runner.Refused]] (the corpus uses subqueries and
  * SQLite's loose typing, which the engine refuses by design), and a
  * statement this runner cannot carry out ends the script, since every record
  * after it reads what it would have built.
  *
  * A record `skipif transformer <rule>` keeps from the engine still runs,
  * once, in the baseline alone, and must still diverge as its rule in the
  * [[Ledger]] says ([[Runner.checkSkip]]); one that no longer does is
  * [[Runner.Stale]], so a skip cannot outlive its reason.
  *
  * A script of CockroachDB's or sqllogictest-rs's ([[Script.Format]]) is read
  * in its dialect's format: a statement record may hold several statements
  * separated by `;`, an expected error's regular expression must find a match
  * in the engine's message, and a result compares as that engine's runner
  * compares it ([[Values.checkRows]]). A statement this runner cannot carry
  * out fails there, rather than passing unseen. A record such a script holds
  * for its own engine (`onlyif cockroachdb`), for what it needs or as a
  * finding not yet decided, runs once in the baseline, if it is a query the
  * parser can read ([[Dialect.unreadable]]), and must still not pass, or it is
  * [[Runner.Stale]] ([[Runner.checkHeld]]).
  */
final class Runner(dialect: Dialect, ledger: Ledger, modes: Seq[Mode] = Modes.Verification,
    audit: Option[Runner.Audit] = None) {

  import Runner._

  private val format = dialect.format

  /** Run the script at `path`, named `file` in what it reports. */
  def run(file: String, path: Path): FileResult = {
    val text = read(path)
    new Runner(dialect.forScript(text), ledger, modes, audit)
      .run(file, Script.parse(file, text, ledger, format, includer(path)), directory(path))
  }

  /** Run `records`, whose COPY statements name files in `dir`. */
  def run(file: String, records: Seq[Record], dir: Path): FileResult = {
    val db = new Database(dialect, dir)
    val views = new Modes.Views
    val labels = mutable.HashMap.empty[String, String]
    val results = Vector.newBuilder[RecordResult]
    var stoppedAt: Option[String] = None
    try {
      val it = records.iterator
      while (stoppedAt.isEmpty && it.hasNext) {
        val rec = it.next()
        rec.skipRule match {
          case Some(id) => results += RecordResult(rec, checkSkip(rec, rule(id), db, views))
          case None if rec.heldFor(format) => results += RecordResult(rec, checkHeld(rec, db, views))
          case None if !rec.runs => results += RecordResult(rec, Skipped("its conditions exclude this engine", None))
          case None => rec match {
            case HashThreshold(_, _) => ()
            case Halt(_, _) => stoppedAt = Some("halt")
            case s: Statement =>
              val outcome = statement(s, db, views)
              results += RecordResult(rec, outcome)
              outcome match {
                case _ if audit.exists(_.keepGoing) => ()
                case Skipped(reason, _) if !s.expectError => stoppedAt = Some(s"line ${s.line}: $reason")
                case Failed(reason) if !s.expectError => stoppedAt = Some(s"line ${s.line}: a statement failed: $reason")
                case _ => ()
              }
            case q: Query => results += RecordResult(rec, query(q, db, views, labels))
          }
        }
      }
    } finally views.close()
    FileResult(file, results.result(), stoppedAt)
  }

  private def rule(id: String): Ledger.Rule =
    ledger.rule(id).getOrElse(throw new IllegalArgumentException(s"no rule '$id' in the ledger"))

  /** `text` with each query's `----` block replaced by what the engine
    * returns under the baseline, a row per line with its values separated by
    * tabs: SQLite's completion mode, for writing a new script. Every result it
    * writes must then be checked by hand, since it is only what the engine
    * says. A query the engine refuses or fails on keeps the block it had, and
    * is listed in the second value. */
  def complete(file: String, path: Path): (String, Seq[String]) = {
    val text = read(path)
    val lines = text.split("\n", -1).toVector
    val dialect = this.dialect.forScript(text)
    val db = new Database(dialect, directory(path))
    val views = new Modes.Views
    val out = Vector.newBuilder[String]
    val problems = Vector.newBuilder[String]
    var copied = 0
    try {
      Script.parse(file, text, ledger, format, includer(path)).foreach {
        case q: Query if q.runs =>
          out ++= lines.slice(copied, q.sqlEnd)
          try {
            val (schema, rows) = run(dialect.prepare(q.sql), db, views, Modes.Baseline)
            shapeProblem(schema, q.columns).foreach(p => throw new IllegalArgumentException(p))
            out += "----"
            if (format == Script.Format.Sqlite)
              out ++= Values.arrangeRows(Values.formatRows(rows, q.columns), q.sort).map(_.mkString("\t"))
            else {
              if (q.colNames) out += schema.fields.map(_.name.replace(' ', '_')).mkString(" ")
              out ++= rows.map(r => q.columns.indices.map(c => Values.upstream(r(c), format)).mkString(" "))
            }
          } catch {
            case NonFatal(e) =>
              val cause = e match { case f: RunFailure => f.cause; case other => other }
              problems += s"$file:${q.line}: ${describe(cause)}"
              out ++= lines.slice(q.sqlEnd, q.end)
          }
          copied = q.end
        case s: Statement if s.runs =>
          try statements(s.sql).foreach(one => db.execute(dialect.prepare(one), sql => baselineRows(sql, db, views)))
          catch { case NonFatal(e) => if (!s.expectError) problems += s"$file:${s.line}: ${describe(e)}" }
        case _ => ()
      }
    } finally views.close()
    out ++= lines.drop(copied)
    (out.result().mkString("\n"), problems.result())
  }

  // ---- statements ---------------------------------------------------------------

  private def statement(s: Statement, db: Database, views: Modes.Views): Outcome = {
    val isQuery = startsQuery(s.sql)
    if (s.expectError && isQuery) return expectedError(s, db, views)
    try {
      statements(s.sql).foreach(one => db.execute(dialect.prepare(one), sql => baselineRows(sql, db, views)))
      if (s.expectError) Failed("the statement succeeded; it should fail")
      else Passed
    } catch {
      case NonFatal(e) if audit.isDefined && { audit.foreach(_.observe(s, Left(e))); false } => Passed
      case u: Database.Unsupported if format == Script.Format.Sqlite => Skipped(u.getMessage, None)
      case u: Database.Unsupported =>
        Failed(s"${u.getMessage}: hold the record for ${format.upstream.getOrElse("its engine")}, 'onlyif " +
          s"${format.upstream.getOrElse("")} # needs ...', and every record that reads what it would have made")
      case NonFatal(e) =>
        if (!s.expectError) Failed(describe(e))
        else if (!clean(e, running = true)) Failed("an internal error passed for the expected one: " + describe(e))
        else errorProblem(s, e).map(Failed(_)).getOrElse(Passed)
    }
  }

  /** The statements of a record's SQL: in CockroachDB's and sqllogictest-rs's
    * scripts each statement separated by `;`, in this repo's own the text. */
  private def statements(sql: String): Seq[String] =
    if (format == Script.Format.Sqlite) Seq(sql) else Lexer.splitStatements(sql)

  /** What an expected error's record says of `e` that its message does not
    * hold: DuckDB's text it must contain, or CockroachDB's and
    * sqllogictest-rs's regular expression, which must find a match in it. */
  private def errorProblem(s: Statement, e: Throwable): Option[String] = {
    // A script of another engine's words, CockroachDB's or sqllogictest-rs's,
    // takes the engine's words for the same reason; this repo's own, and the
    // ports, give the engine's words.
    def same(expected: String): Boolean = format != Script.Format.Sqlite && sameReason(expected, message(e)).isDefined
    s.errorText.filter(text => !message(e).contains(text) && !same(text)).map { text =>
      s"the statement failed with ${describe(e)}, which does not contain [$text]"
    }.orElse(s.errorPattern.filter(p => !finds(p, message(e)) && !same(p)).map { p =>
      s"the statement failed with ${describe(e)}, in which the record's pattern [$p] finds nothing"
    })
  }

  /** A query that must fail, under every mode. Each mode must fail with the
    * baseline's exception class and, when the record gives one, with the
    * record's text in its message; the rest of the message may differ, since
    * which row of several that each fail is met first depends on the layout. */
  private def expectedError(s: Statement, db: Database, views: Modes.Views): Outcome = {
    def attempt(mode: Mode): Option[RunFailure] =
      try { run(dialect.prepare(s.sql), db, views, mode); None }
      catch { case f: RunFailure => Some(f) }
    if (audit.isDefined) {
      val base = try Right(run(dialect.prepare(s.sql), db, views, Modes.Baseline)) catch { case f: RunFailure => Left(f.cause) }
      audit.foreach(_.observe(s, base))
    }
    attempt(Modes.Baseline) match {
      case None => Failed("the query succeeded; it should fail")
      case Some(f) if !clean(f.cause, running = !f.atPlan || binds(dialect.prepare(s.sql), db, views)) =>
        Failed("an internal error passed for the expected one: " + describe(f.cause))
      case Some(f) =>
        val e = f.cause
        errorProblem(s, e) match {
          case Some(problem) => Failed(problem.replace("the statement failed", "the query failed"))
          case None =>
            modes.iterator.filter(_.runs(s.sql)).map(m => m -> attempt(m).map(_.cause)).collectFirst {
              case (m, None) => Diverged(m.name, "the query succeeded where the baseline failed with " + describe(e))
              case (m, Some(other)) if other.getClass != e.getClass || errorProblem(s, other).isDefined =>
                Diverged(m.name, s"the query failed with ${describe(other)} where the baseline failed with ${describe(e)}")
            }.getOrElse(Passed)
        }
    }
  }

  // ---- skips ---------------------------------------------------------------------

  /** A record `skipif transformer <rule>` keeps from the engine, run once, in
    * the baseline alone (the verification modes compare runs with each other,
    * not with a skip): it must still diverge as `rule` says, or it is
    * [[Stale]]. Under a refusal rule the engine must refuse it cleanly, with
    * the rule's text in the message. Under any other rule the record must not
    * pass: a query must not return the record's answer, and a statement error
    * must run, since the rule says the engine answers where the other engine
    * refused. An internal error fails the record, skipped or not. */
  private def checkSkip(rec: Record, rule: Ledger.Rule, db: Database, views: Modes.Views): Outcome = {
    val written = Script.sqlOf(rec).getOrElse("")
    def stale(why: String): Outcome = Stale(rule.id, s"skipped as ${rule.id}, but $why")
    if (!startsQuery(written))
      return Failed(s"skipped as ${rule.id}, but it is no query: a skipped record runs to check its skip, so it " +
        "must change no table")
    val sql = dialect.prepare(written)
    val base =
      try Right(run(sql, db, views, Modes.Baseline))
      catch { case f: RunFailure => Left(f) }
    val still = Skipped(s"skipped as ${rule.id}", Some(rule.id))
    base match {
      case Left(f) if !clean(f.cause, running = !f.atPlan || binds(sql, db, views)) =>
        Failed(s"skipped as ${rule.id}, but the engine fails with an internal error: ${describe(f.cause)}")
      case Left(f) => (rule.refusal, rec) match {
        case (Some(_), _) if !f.atPlan =>
          stale(s"the engine does not refuse it: it fails on the data with ${describe(f.cause)}")
        case (Some(text), _) if !message(f.cause).contains(text) =>
          stale(s"the engine refuses it for another reason, ${describe(f.cause)}; the rule's refusal says [$text]")
        case (None, s: Statement) if s.expectError && errorProblem(s, f.cause).isEmpty =>
          stale(s"the engine now fails as the record expects, with ${describe(f.cause)}; remove the skip")
        case (None, s: Statement) if s.expectError =>
          stale(s"the rule says the engine answers where the other engine refuses, and the engine now fails: " +
            describe(f.cause))
        // A refusal, or a query the engine fails where the other engine
        // answers, which the twin after it pins.
        case _ => still
      }
      case Right((schema, rows)) =>
        val problem = rec match {
          case q: Query => shapeProblem(schema, q.columns, rows).orElse(answerProblem(q, schema, rows))
          case s: Statement if s.expectError => Some("the statement succeeds where the record expects an error")
          case _ => None
        }
        val compared = rec match {
          case q: Query => s", compared as ${q.columns.map(columnText).mkString(", ")}"
          case _ => ""
        }
        (rule.refusal, problem) match {
          case (_, None) => stale(s"the engine now gives the record's answer$compared; remove the skip")
          case (Some(_), Some(p)) =>
            stale(s"the engine no longer refuses it, and answers otherwise than the record ($p); cite the rule " +
              "that says why, or hold the record out as a finding")
          case (None, Some(_)) => still
        }
    }
  }

  /** A record a script of CockroachDB's or sqllogictest-rs's holds for its own
    * engine (`onlyif cockroachdb # needs ...`): run once, in the baseline, if
    * it is a query or an error of one, and it must still not pass, or it is
    * [[Stale]], so a hold cannot outlive its reason either. Any other record
    * is held unrun: a statement that changes a table cannot be run to check
    * it. */
  private def checkHeld(rec: Record, db: Database, views: Modes.Views): Outcome = {
    val held = Skipped(s"held for ${format.upstream.getOrElse("its engine")}", None)
    val written = Script.sqlOf(rec).getOrElse("")
    if (!startsQuery(written) || dialect.unreadable(written)) return held
    val sql = dialect.prepare(written)
    val base =
      try Right(run(sql, db, views, Modes.Baseline))
      catch { case f: RunFailure => Left(f) }
    def stale(why: String) = Stale("held", s"held for ${format.upstream.getOrElse("its engine")}, but $why; remove the hold")
    (base, rec) match {
      case (Left(f), _) if !clean(f.cause, running = !f.atPlan || binds(sql, db, views)) =>
        Failed(s"held, but the engine fails with an internal error: ${describe(f.cause)}")
      case (Left(f), s: Statement) if s.expectError && errorProblem(s, f.cause).isEmpty =>
        stale(s"the engine now fails as the record expects, with ${describe(f.cause)}")
      case (Right((schema, rows)), q: Query) if shapeProblem(schema, q.columns, rows).orElse(answerProblem(q, schema, rows)).isEmpty =>
        stale("the engine now gives the record's answer")
      case _ => held
    }
  }

  // ---- queries -------------------------------------------------------------------

  private def query(q: Query, db: Database, views: Modes.Views,
      labels: mutable.HashMap[String, String]): Outcome = {
    val sql = dialect.prepare(q.sql)
    val base =
      try Right(run(sql, db, views, Modes.Baseline))
      catch { case f: RunFailure => Left(f) }
    audit.foreach(_.observe(q, base.left.map(_.cause)))
    base match {
      case Left(f) if f.atPlan && clean(f.cause, running = binds(sql, db, views)) => Refused(message(f.cause))
      case Left(f) if f.atPlan => Failed("an internal error, not a refusal: " + describe(f.cause))
      case Left(f) => Failed("failed on the data: " + describe(f.cause))
      case Right((schema, rows)) =>
        shapeProblem(schema, q.columns, rows).map(Failed(_)).getOrElse {
          val printed = printedResult(q, schema, rows)
          val labelProblem = q.label.flatMap { l =>
            val h = Values.hash(printed)
            labels.get(l) match {
              case Some(prev) if prev != h => Some(s"label $l returned another result than an earlier query with the label")
              case Some(_) => None
              case None => labels(l) = h; None
            }
          }
          answerProblem(q, schema, rows).orElse(labelProblem) match {
            case Some(problem) => Failed(problem)
            case None => verify(q, sql, db, views, printed)
          }
        }
    }
  }

  /** Run `q` under every verification mode; the first whose printed result
    * differs from the baseline's, or that fails, is the outcome. */
  private def verify(q: Query, sql: String, db: Database, views: Modes.Views, baseline: Vector[String]): Outcome =
    modes.iterator.filter(_.runs(sql)).map { m =>
      try {
        val (schema, rows) = run(sql, db, views, m)
        shapeProblem(schema, q.columns, rows) match {
          case Some(p) => Some(Diverged(m.name, p))
          case None =>
            diff(baseline, printedResult(q, schema, rows)).map(d => Diverged(m.name, d))
        }
      } catch {
        // A mode that does not order the joins runs them as written, and may
        // meet the nested-loop guard where the ordered baseline did not.
        case f: RunFailure if f.atPlan && m.optimizer != Modes.Optimizer.Full && m.text == Modes.Text.AsWritten &&
            f.cause.isInstanceOf[NestedLoopGuardException] => None
        case f: RunFailure => Some(Diverged(m.name, (if (f.atPlan) "refused: " else "failed on the data: ") + describe(f.cause)))
      }
    }.collectFirst { case Some(o) => o }.getOrElse(Passed)

  /** Whether a result's columns are the record's: as many, and each exact
    * column of the type it names. */
  private def shapeProblem(schema: Schema, columns: Seq[Column], rows: Vector[Array[Any]] = Vector.empty): Option[String] =
    if (schema.length != columns.length)
      Some(s"the query returns ${schema.length} columns; the record has ${columns.length}")
    else columns.zip(schema.fields).zipWithIndex.collectFirst(Function.unlift {
      case ((Column.Exact(t), f), i) if f.dataType != t =>
        Some(s"column ${i + 1} (${f.name}) is ${typeName(f.dataType)}; the record says ${typeName(t)}")
      // CockroachDB's and sqllogictest-rs's runners check a letter against
      // each value, so a column of NULLs takes any letter.
      case ((Column.Letter(c), f), i) if rows.exists(r => r(i) != null) =>
        Values.letterProblem(c, f.dataType).map(p => s"column ${i + 1} (${f.name}) $p")
      case _ => None
    })

  /** A result as the verification modes compare it with the baseline's: its
    * values printed as the record's columns print them, in the order its
    * sort mode compares them. A CockroachDB or sqllogictest-rs record's rows
    * are sorted as text unless it keeps their order, and a float in them is
    * printed to the 15 significant digits its answer is compared at, since a
    * statistic's last digits follow the order its partial results merge in,
    * which the layout sets. */
  private def printedResult(q: Query, schema: Schema, rows: Vector[Array[Any]]): Vector[String] =
    if (format == Script.Format.Sqlite) Values.arrange(Values.formatRows(rows, q.columns), q.sort)
    else {
      val printed = rows.map(r => q.columns.indices.map(c => r(c) match {
        case d: java.lang.Double if !d.isNaN && !d.isInfinite && d.doubleValue != 0.0 =>
          new java.math.BigDecimal(d.doubleValue).round(new java.math.MathContext(15)).toString
        case f: java.lang.Float if !f.isNaN && !f.isInfinite && f.floatValue != 0.0f =>
          new java.math.BigDecimal(f.doubleValue).round(new java.math.MathContext(7)).toString
        case v => Values.upstream(v, format)
      }).mkString("\u0001"))
      if (q.sort == SortMode.NoSort && q.partialSort.isEmpty) printed else printed.sorted
    }

  /** What the record's `----` block says that the result does not, or None. */
  private def answerProblem(q: Query, schema: Schema, rows: Vector[Array[Any]]): Option[String] = q.expected match {
    case Expected.Rows(lines) =>
      Values.checkRows(lines, q.columns, q.sort, q.partialSort, q.colNames, schema.fields.map(_.name),
        schema.fields.map(f => f.dataType == DataType.DoubleType || f.dataType == DataType.FloatType), rows, format)
    case Expected.Hash(n, md5) if format != Script.Format.Sqlite =>
      val got = Values.hashLine(Values.upstreamWords(q.columns.length, q.sort, rows, format))
      if (got == s"$n values hashing to $md5") None else Some(s"expected $n values hashing to $md5, got $got")
    case _ => expectedProblem(q, printedResult(q, schema, rows))
  }

  /** Whether `sql`, as the dialect prepared it, binds, so that a failure
    * before its first row was one while it ran: the engine computes some of a
    * query, such as an aggregate without keys, while it plans it. The fuzz
    * harness's clean-rejection property draws the line here too. Asked only
    * of a query that failed. */
  private def binds(sql: String, db: Database, views: Modes.Views): Boolean =
    try { LogicalBuilder.build(sql, views.catalog(db.allTables, Modes.Baseline.layout)); true }
    catch { case NonFatal(_) => false }

  private def baselineRows(sql: String, db: Database, views: Modes.Views): (Schema, Vector[Array[Any]]) =
    try run(sql, db, views, Modes.Baseline)
    catch { case f: RunFailure => throw f.cause }

  private def run(sql: String, db: Database, views: Modes.Views, mode: Mode): (Schema, Vector[Array[Any]]) = {
    db.refreshViews(body => baselineRows(body, db, views))
    val q =
      try Modes.execute(sql, views.catalog(db.allTables, mode.layout), mode)
      catch { case NonFatal(e) => throw new RunFailure(e, atPlan = true) }
    try {
      val rows = drain(q)
      if (mode.options.metricsEnabled) q.metricsSnapshot
      (q.schema, rows)
    } catch { case NonFatal(e) => throw new RunFailure(e, atPlan = false) }
  }
}

object Runner {

  /** What an audit of a corpus asks of the runner beyond its outcomes: to
    * carry on past a statement that fails (`keepGoing`), so a first pass over
    * a file reports every record, and to be shown what the engine did with
    * each record in the baseline (`observe`: its result, or the error). */
  trait Audit {
    def keepGoing: Boolean
    def observe(rec: Record, result: Either[Throwable, (Schema, Vector[Array[Any]])]): Unit
  }

  private def read(path: Path): String = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  /** The text of a file an `include` line of the script at `path` names, a
    * file beside it. */
  private def includer(path: Path): String => String = name => {
    val file = directory(path).resolve(name).normalize
    if (!file.startsWith(directory(path)) || !Files.isRegularFile(file))
      throw new IllegalArgumentException(s"no file $name beside ${path.getFileName}")
    read(file)
  }

  /** A reason both this engine and another refuse a statement for, each in
    * its own words: what the other engine's expected error says (CockroachDB's
    * and DataFusion's, matched against the record's text or pattern), and
    * what the engine's message says. An expected error whose words are
    * another engine's passes when the engine refuses for the same reason, as
    * a port's `statement error` takes the engine's words for one
    * (plans/testing/10, 11); a refusal for any other reason fails. */
  final case class Reason(name: String, theirs: scala.util.matching.Regex, ours: scala.util.matching.Regex)

  val SameReasons: Seq[Reason] = Seq(
    Reason("a column outside GROUP BY", "(?i)must appear in the GROUP BY clause".r, "must appear in GROUP BY".r),
    Reason("an aggregate where none can be",
      "(?i)aggregate functions are not allowed|aggregate function calls cannot be nested".r,
      "Aggregate '[^']*' used in non-aggregating position|cannot refer to aggregate".r),
    Reason("a window function where none can be",
      "(?i)window functions are not allowed|window function calls cannot be nested".r,
      "Window function '[^']*' used in non-window position".r),
    Reason("an integer past its type", "(?i)integer out of range".r, "Integer overflow".r),
    Reason("a decimal past its type", "(?i)value with precision \\d+, scale \\d+ must round|numeric field overflow".r,
      "(?i)does not fit decimal".r),
    Reason("a name the query's tables do not have", "(?i)column \\S+ does not exist|no data source matches".r,
      "Unknown column|Unknown table".r),
    Reason("a key that reads no column", "(?i)non-integer constant in (ORDER|GROUP) BY".r, "reads no column".r),
    Reason("arms of a UNION of different widths", "(?i)must have the same number of columns".r,
      "different column counts".r),
    Reason("an ambiguous name", "(?i)is ambiguous".r, "(?i)ambiguous".r),
    Reason("text that is no value of its type", "(?i)could not parse \"|invalid input syntax for type".r,
      "Cannot cast '".r),
    Reason("text that does not parse", "(?i)syntax error".r, "SQL parse error".r),
    Reason("a DECIMAL's scale past its precision", "(?i)scale \\S+ must be between 0 and precision".r,
      "is no DECIMAL".r),
    Reason("a star with no FROM", "(?i)cannot use \\S+ without a FROM clause".r, "needs a FROM clause".r),
    Reason("a WITH name given twice", "(?i)WITH query name \\S+ specified more than once".r, "defines '[^']*' twice".r),
    Reason("a LIMIT or OFFSET that is no count",
      "(?i)argument of (LIMIT|OFFSET) must be type int|negative value for (LIMIT|OFFSET)".r,
      "(LIMIT|OFFSET) must be a non-negative integer literal".r),
    Reason("a count past BIGINT", "(?i)numeric constant out of int64 range".r, "is larger than the largest BIGINT".r),
    Reason("branches of types that do not meet", "(?i)expected \\S+ to be of type \\S+, found type|incompatible \\S+ expressions".r,
      "cannot mix".r))

  /** The reason both engines refuse for, if one is the other engine's
    * `expected` error (its text or pattern) and the engine's `message`. */
  def sameReason(expected: String, message: String): Option[Reason] =
    SameReasons.find(r => r.theirs.findFirstIn(expected).isDefined && r.ours.findFirstIn(message).isDefined)

  /** Whether the regular expression `pattern`, as Go's RE2 reads it (both
    * CockroachDB's and sqllogictest-rs's runners use RE2's syntax), finds a
    * match in `text`. */
  private def finds(pattern: String, text: String): Boolean =
    java.util.regex.Pattern.compile(javaRegex(pattern)).matcher(text).find()

  /** An RE2 pattern as Java reads it: a `{` that begins no repetition (`<tuple{int}>`)
    * is the character itself in RE2, and Java's syntax error. */
  private[slt] def javaRegex(pattern: String): String = {
    val out = new StringBuilder
    var i = 0
    while (i < pattern.length) {
      val c = pattern(i)
      if (c == '\\' && i + 1 < pattern.length) { out += c; out += pattern(i + 1); i += 2 }
      else {
        if (c == '{' && !Repetition.pattern.matcher(pattern.substring(i)).lookingAt()) out ++= "\\{"
        else if (c == '}' && !closesRepetition(pattern, i)) out ++= "\\}"
        else out += c
        i += 1
      }
    }
    out.result()
  }

  private val Repetition = """\{\d+(,\d*)?\}""".r

  private def closesRepetition(p: String, at: Int): Boolean = {
    val open = p.lastIndexOf('{', at)
    open >= 0 && Repetition.pattern.matcher(p.substring(open, at + 1)).matches()
  }

  /** The directory a script's COPY statements name their files in: its own. */
  private def directory(path: Path): Path = path.toAbsolutePath.getParent

  sealed trait Outcome
  case object Passed extends Outcome
  /** A record the engine does not run: one its conditions give to other
    * engines (`rule` None), or one `skipif transformer <rule>` keeps from it,
    * which still diverges as the rule says. */
  final case class Skipped(reason: String, rule: Option[String]) extends Outcome
  /** A record `skipif transformer <rule>` keeps from the engine that no
    * longer diverges as the rule says: the skip has outlived its reason. */
  final case class Stale(rule: String, detail: String) extends Outcome
  /** The engine refused a query whose result the script gives. */
  final case class Refused(message: String) extends Outcome
  /** A wrong result, a wrong error, or no error where the script expects one. */
  final case class Failed(detail: String) extends Outcome
  /** The baseline was right and a verification mode disagreed with it. */
  final case class Diverged(mode: String, detail: String) extends Outcome

  final case class RecordResult(record: Record, outcome: Outcome)

  /** An outcome as a report prints it: a stale skip as the sentence that
    * says what to do. */
  def show(o: Outcome): String = o match {
    case Stale(_, detail) => detail
    case other => other.toString
  }

  /** A script's outcomes reduced to counts by kind, refusals tallied by
    * message and skips by rule, and the records an audit reports (failures,
    * divergences and stale skips, and refusals when asked). */
  final case class CompactResult(file: String, counts: Map[String, Int], refusalTally: Map[String, Int],
      skipTally: Map[String, Int], kept: Vector[RecordResult], stoppedAt: Option[String])

  final case class FileResult(file: String, results: Vector[RecordResult], stoppedAt: Option[String]) {
    def compact(keepRefusals: Boolean): CompactResult = {
      def kind(o: Outcome): String = o match {
        case Passed => "passed"
        case _: Skipped => "skipped"
        case _: Refused => "refused"
        case _: Failed => "failed"
        case _: Diverged => "diverged"
        case _: Stale => "stale"
      }
      CompactResult(file, results.groupMapReduce(r => kind(r.outcome))(_ => 1)(_ + _),
        results.collect { case RecordResult(_, Refused(m)) => Corpus.normalizeMessage(m) }.groupMapReduce(identity)(_ => 1)(_ + _),
        results.collect { case RecordResult(_, Skipped(_, Some(id))) => id }.groupMapReduce(identity)(_ => 1)(_ + _),
        results.filter(r => r.outcome match {
          case _: Failed | _: Diverged | _: Stale => true
          case _: Refused => keepRefusals
          case _ => false
        }),
        stoppedAt)
    }

    def count(p: Outcome => Boolean): Int = results.count(r => p(r.outcome))
    def problems: Vector[RecordResult] = results.filter(r => r.outcome match {
      case Passed | Skipped(_, _) => false
      case _ => true
    })
    def summary: String = {
      val q = results.count(_.record.isInstanceOf[Query])
      s"$file: ${results.length} records ($q queries): ${count(_ == Passed)} passed, " +
        s"${count(_.isInstanceOf[Refused])} refused, ${count(_.isInstanceOf[Failed])} failed, " +
        s"${count(_.isInstanceOf[Diverged])} diverged, ${count(_.isInstanceOf[Skipped])} skipped, " +
        s"${count(_.isInstanceOf[Stale])} stale" +
        stoppedAt.map(s => s"; stopped at $s").getOrElse("")
    }
  }

  private final class RunFailure(val cause: Throwable, val atPlan: Boolean) extends RuntimeException(cause)

  private def startsQuery(sql: String): Boolean = {
    val first = sql.trim.takeWhile(c => c.isLetter).toUpperCase(java.util.Locale.ROOT)
    first == "SELECT" || first == "WITH" || first == "VALUES" || sql.trim.startsWith("(")
  }

  def message(e: Throwable): String = Option(e.getMessage).getOrElse("")

  /** An error a user is shown, as the fuzz harness's clean-rejection property
    * has it: an `IllegalArgumentException` with a message, or, while a query
    * runs on its data, an `ArithmeticException` with one. Any other exception
    * is the engine's own failure, which an expected error must not be, as
    * DuckDB fails a test on an internal error even where it expects an error,
    * and which a corpus audit counts as a failure, not a refusal. */
  def clean(e: Throwable, running: Boolean): Boolean = {
    val m = message(e).trim
    val shown = e match {
      case _: IllegalArgumentException => true
      case _: ArithmeticException => running
      case _ => false
    }
    shown && m.nonEmpty && m != "null"
  }

  /** Set `SLT_TRACE` to see where each error came from. */
  private val Trace = Option(System.getenv("SLT_TRACE")).exists(_.nonEmpty)

  def describe(e: Throwable): String = {
    val head = s"${e.getClass.getSimpleName}: ${message(e).take(500)}"
    if (!Trace) head
    else head + e.getStackTrace.take(25).map(f => s"\n        at $f").mkString
  }

  def drain(q: ExecutedQuery): Vector[Array[Any]] = {
    val ncols = q.schema.length
    val buf = Vector.newBuilder[Array[Any]]
    val it = q.batches
    while (it.hasNext) {
      val b = it.next()
      var r = 0
      while (r < b.numRows) {
        val row = new Array[Any](ncols)
        var c = 0
        while (c < ncols) {
          row(c) = if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)
          c += 1
        }
        buf += row
        r += 1
      }
    }
    buf.result()
  }

  /** The first place two printed results differ, or None. */
  def diff(expected: Seq[String], got: Seq[String]): Option[String] =
    if (expected == got) None
    else {
      val i = expected.indices.find(k => k >= got.length || expected(k) != got(k)).getOrElse(expected.length)
      val shown = 12
      Some(s"${expected.length} values expected, ${got.length} returned; first difference at value ${i + 1}: " +
        s"expected [${expected.lift(i).getOrElse("<none>")}] got [${got.lift(i).getOrElse("<none>")}]\n" +
        s"    expected: ${expected.slice(math.max(0, i - 2), i + shown).mkString(" | ")}\n" +
        s"    returned: ${got.slice(math.max(0, i - 2), i + shown).mkString(" | ")}")
    }

  /** What a query's `----` block says that `printed`, its printed result,
    * does not, or None. */
  private def expectedProblem(q: Query, printed: Vector[String]): Option[String] = q.expected match {
    case Expected.Unchecked => None
    case Expected.Hash(n, md5) =>
      val got = Values.hashLine(printed)
      if (got == s"$n values hashing to $md5") None
      else Some(s"expected $n values hashing to $md5, got $got${preview(printed)}")
    case Expected.Values(values) => diff(values, printed)
    case Expected.Rows(_) => throw new IllegalStateException("a record's rows are compared by Values.checkRows")
  }

  /** How a column compares, for a stale skip's message. */
  private def columnText(c: Column): String = c match {
    case Column.Integer => "I (an integer's low 32 bits)"
    case Column.Real => "R (to three places)"
    case Column.Text => "T (as text)"
    case Column.Exact(t) => typeName(t)
    case Column.Letter(c) => Values.letterName(c)
  }

  private def preview(printed: Seq[String]): String =
    if (printed.length <= 24) s" (values: ${printed.mkString(" | ")})" else ""
}
