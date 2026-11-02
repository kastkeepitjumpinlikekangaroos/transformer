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
  */
final class Runner(dialect: Dialect, ledger: Ledger, modes: Seq[Mode] = Modes.Verification) {

  import Runner._

  /** Run the script at `path`, named `file` in what it reports. */
  def run(file: String, path: Path): FileResult = run(file, Script.parse(file, read(path), ledger), directory(path))

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
          case None if !rec.runs => results += RecordResult(rec, Skipped("its conditions exclude this engine", None))
          case None => rec match {
            case HashThreshold(_, _) => ()
            case Halt(_, _) => stoppedAt = Some("halt")
            case s: Statement =>
              val outcome = statement(s, db, views)
              results += RecordResult(rec, outcome)
              outcome match {
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
    val db = new Database(dialect, directory(path))
    val views = new Modes.Views
    val out = Vector.newBuilder[String]
    val problems = Vector.newBuilder[String]
    var copied = 0
    try {
      Script.parse(file, text, ledger).foreach {
        case q: Query if q.runs =>
          out ++= lines.slice(copied, q.sqlEnd)
          try {
            val (schema, rows) = run(dialect.prepare(q.sql), db, views, Modes.Baseline)
            shapeProblem(schema, q.columns).foreach(p => throw new IllegalArgumentException(p))
            out += "----"
            out ++= Values.arrangeRows(Values.formatRows(rows, q.columns), q.sort).map(_.mkString("\t"))
          } catch {
            case NonFatal(e) =>
              val cause = e match { case f: RunFailure => f.cause; case other => other }
              problems += s"$file:${q.line}: ${describe(cause)}"
              out ++= lines.slice(q.sqlEnd, q.end)
          }
          copied = q.end
        case s: Statement if s.runs =>
          try db.execute(dialect.prepare(s.sql), sql => baselineRows(sql, db, views))
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
      db.execute(dialect.prepare(s.sql), sql => baselineRows(sql, db, views))
      if (s.expectError) Failed("the statement succeeded; it should fail")
      else Passed
    } catch {
      case u: Database.Unsupported => Skipped(u.getMessage, None)
      case NonFatal(e) =>
        if (!s.expectError) Failed(describe(e))
        else if (!clean(e, running = true)) Failed("an internal error passed for the expected one: " + describe(e))
        else s.errorText match {
          case Some(text) if !message(e).contains(text) =>
            Failed(s"the statement failed with ${describe(e)}, which does not contain [$text]")
          case _ => Passed
        }
    }
  }

  /** A query that must fail, under every mode. Each mode must fail with the
    * baseline's exception class and, when the record gives one, with the
    * record's text in its message; the rest of the message may differ, since
    * which row of several that each fail is met first depends on the layout. */
  private def expectedError(s: Statement, db: Database, views: Modes.Views): Outcome = {
    def attempt(mode: Mode): Option[RunFailure] =
      try { run(dialect.prepare(s.sql), db, views, mode); None }
      catch { case f: RunFailure => Some(f) }
    attempt(Modes.Baseline) match {
      case None => Failed("the query succeeded; it should fail")
      case Some(f) if !clean(f.cause, running = !f.atPlan || binds(dialect.prepare(s.sql), db, views)) =>
        Failed("an internal error passed for the expected one: " + describe(f.cause))
      case Some(f) =>
        val e = f.cause
        s.errorText match {
          case Some(text) if !message(e).contains(text) =>
            Failed(s"the query failed with ${describe(e)}, which does not contain [$text]")
          case _ =>
            modes.iterator.filter(_.runs(s.sql)).map(m => m -> attempt(m).map(_.cause)).collectFirst {
              case (m, None) => Diverged(m.name, "the query succeeded where the baseline failed with " + describe(e))
              case (m, Some(other)) if other.getClass != e.getClass || s.errorText.exists(t => !message(other).contains(t)) =>
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
        case (None, s: Statement) if s.expectError && s.errorText.forall(message(f.cause).contains(_)) =>
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
          case q: Query => shapeProblem(schema, q.columns)
            .orElse(expectedProblem(q, Values.arrange(Values.formatRows(rows, q.columns), q.sort)))
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

  // ---- queries -------------------------------------------------------------------

  private def query(q: Query, db: Database, views: Modes.Views,
      labels: mutable.HashMap[String, String]): Outcome = {
    val sql = dialect.prepare(q.sql)
    val base =
      try Right(run(sql, db, views, Modes.Baseline))
      catch { case f: RunFailure => Left(f) }
    base match {
      case Left(f) if f.atPlan && clean(f.cause, running = binds(sql, db, views)) => Refused(message(f.cause))
      case Left(f) if f.atPlan => Failed("an internal error, not a refusal: " + describe(f.cause))
      case Left(f) => Failed("failed on the data: " + describe(f.cause))
      case Right((schema, rows)) =>
        shapeProblem(schema, q.columns).map(Failed(_)).getOrElse {
          val printed = Values.arrange(Values.formatRows(rows, q.columns), q.sort)
          val labelProblem = q.label.flatMap { l =>
            val h = Values.hash(printed)
            labels.get(l) match {
              case Some(prev) if prev != h => Some(s"label $l returned another result than an earlier query with the label")
              case Some(_) => None
              case None => labels(l) = h; None
            }
          }
          expectedProblem(q, printed).orElse(labelProblem) match {
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
        shapeProblem(schema, q.columns) match {
          case Some(p) => Some(Diverged(m.name, p))
          case None =>
            val printed = Values.arrange(Values.formatRows(rows, q.columns), q.sort)
            diff(baseline, printed).map(d => Diverged(m.name, d))
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
  private def shapeProblem(schema: Schema, columns: Seq[Column]): Option[String] =
    if (schema.length != columns.length)
      Some(s"the query returns ${schema.length} columns; the record has ${columns.length}")
    else columns.zip(schema.fields).zipWithIndex.collectFirst {
      case ((Column.Exact(t), f), i) if f.dataType != t =>
        s"column ${i + 1} (${f.name}) is ${typeName(f.dataType)}; the record says ${typeName(t)}"
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

  private def read(path: Path): String = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

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
  }

  /** How a column compares, for a stale skip's message. */
  private def columnText(c: Column): String = c match {
    case Column.Integer => "I (an integer's low 32 bits)"
    case Column.Real => "R (to three places)"
    case Column.Text => "T (as text)"
    case Column.Exact(t) => typeName(t)
  }

  private def preview(printed: Seq[String]): String =
    if (printed.length <= 24) s" (values: ${printed.mkString(" | ")})" else ""
}
