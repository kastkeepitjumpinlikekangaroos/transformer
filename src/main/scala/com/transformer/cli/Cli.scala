package com.transformer.cli

import com.transformer.job.{DirectoryJobLoader, TaskProgressListener, TaskResult, TaskStatus}
import com.transformer.sql.exec.SqlEngine
import com.transformer.temporal.TemporalVariables

import java.io.{InputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import scala.collection.mutable
import scala.util.control.NonFatal

/** The `transformer` command line, minus the GUI. `TransformerMain` hands
  * `gui` to JavaFX and everything else here, so this object needs no toolkit
  * on its classpath and a test can drive it directly.
  *
  * Exit codes: 0 when every task succeeded, 1 when the run finished with any
  * task not Succeeded (a failed validation counts) or the job could not be
  * loaded, 2 for a usage error. A scheduler such as Airflow or an agent
  * harness needs nothing beyond the exit code to tell a good run from a bad
  * one; the per-task lines and `<output>/job.json` say which task went wrong.
  * `query` exits 0 when every statement ran and 1 at the first that failed.
  */
object Cli {

  sealed trait Command
  object Command {
    final case class Run(jobDir: Path, outputDir: Option[String], executionTime: Instant) extends Command
    /** `transformer query`: SQL over `tables` and `job`, from `sql`, `file`
      * or standard input, else a shell. */
    final case class Query(
        sql: Option[String] = None,
        file: Option[Path] = None,
        tables: Seq[TableArg] = Nil,
        job: Option[JobArg] = None,
        format: OutputFormat = OutputFormat.Table,
        maxRows: Int = QuerySession.DefaultMaxRows,
        output: Option[String] = None,
        spill: Boolean = false
    ) extends Command
    final case class Gui(jobDir: Option[Path]) extends Command
    case object Help extends Command
  }

  /** `--table name=path`, with the `--option name.key=value` that name it. */
  final case class TableArg(name: String, path: String, format: Option[String] = None,
      options: Map[String, String] = Map.empty)

  /** `--job dir`, with `--job-output` and `--execution-time`. */
  final case class JobArg(dir: Path, output: Option[String], executionTime: Instant)

  val ExitOk = 0
  val ExitRunFailed = 1
  val ExitUsage = 2

  val Usage: String =
    """Usage:
      |  transformer run <jobDir> [--output <dir>] [--execution-time <instant>]
      |  transformer query [<sql>] [--table <name>=<path>]... [options]
      |  transformer gui [jobDir]
      |  transformer help
      |
      |run    Load the job directory and run every task. Exits 0 when all tasks
      |       succeed, 1 when any task fails, fails a validation, or is skipped.
      |         --output <dir>             Where outputs and job.json go. Templated,
      |                                    e.g. /data/out/day={{ today }}.
      |                                    Default: <jobDir>/output
      |         --execution-time <instant> ISO-8601 instant the templates resolve
      |                                    against, e.g. 2026-01-01T00:00:00Z.
      |                                    Default: now
      |query  Run SQL over files: the statements in <sql>, in --file, or on
      |       standard input; with none of them on a terminal, open a shell.
      |       Exits 0 when every statement runs, 1 at the first that fails.
      |         --table <name>=<path>      A file, glob or directory SQL reads as
      |                                    <name>: CSV or Parquet by extension, or a
      |                                    job's output directory. Repeatable.
      |         --option <name>.<key>=<v>  A read option of table <name>, as an
      |                                    input's config.json takes it, or
      |                                    format=csv|parquet. Repeatable.
      |         --job <jobDir>             Read the job's inputs and the tables of
      |                                    its last run by name.
      |         --job-output <dir>         Where that job writes. Default:
      |                                    <jobDir>/output
      |         --execution-time <instant> The instant the job's templates resolve
      |                                    against. Default: now
      |         -f, --file <file>          Run the statements in <file>.
      |         --format <format>          table (default), line, csv, tsv, json,
      |                                    jsonl or markdown.
      |         --max-rows <n>             Rows a table shows, the first and last
      |                                    ones; 0 shows every row. Default: 40
      |         --output <file>            Write the query's result to <file>
      |                                    (.csv or .parquet) instead of printing it.
      |         --spill                    Let sorts, aggregates and joins spill to
      |                                    disk past their memory threshold.
      |gui    Open the desktop GUI, optionally on a job directory.
      |""".stripMargin

  /** Parse `args` into a command, or a message saying what is wrong with them. */
  def parse(args: Seq[String], now: => Instant = Instant.now()): Either[String, Command] =
    args.toList match {
      case Nil | ("help" | "--help" | "-h") :: Nil => Right(Command.Help)
      case "gui" :: Nil => Right(Command.Gui(None))
      case "gui" :: dir :: Nil => Right(Command.Gui(Some(Paths.get(dir))))
      case "gui" :: _ => Left("gui takes at most one argument, the job directory")
      case ("run" | "query") :: rest if rest.takeWhile(_ != "--").exists(a => a == "--help" || a == "-h") =>
        Right(Command.Help)
      case "run" :: rest => parseRun(rest, now)
      case "query" :: rest => parseQuery(rest, now)
      case other :: _ => Left(s"unknown command '$other'")
    }

  private def parseRun(args: List[String], now: => Instant): Either[String, Command] = {
    def loop(
        rest: List[String],
        jobDir: Option[String],
        output: Option[String],
        time: Option[Instant]
    ): Either[String, Command] = rest match {
      case Nil =>
        jobDir match {
          case None => Left("run needs a job directory")
          case Some(d) =>
            Right(Command.Run(Paths.get(d), output, time.getOrElse(now.truncatedTo(ChronoUnit.SECONDS))))
        }
      case "--output" :: value :: tail if output.isEmpty =>
        loop(tail, jobDir, Some(value), time)
      case "--execution-time" :: value :: tail if time.isEmpty =>
        try loop(tail, jobDir, output, Some(Instant.parse(value)))
        catch {
          case _: DateTimeParseException =>
            Left(s"--execution-time '$value' is not an ISO-8601 instant such as 2026-01-01T00:00:00Z")
        }
      case ("--output" | "--execution-time") :: tail if tail.isEmpty =>
        Left(s"${rest.head} needs a value")
      case ("--output" | "--execution-time") :: _ =>
        Left(s"${rest.head} given twice")
      case flag :: _ if flag.startsWith("-") =>
        Left(s"unknown option '$flag'")
      case dir :: tail if jobDir.isEmpty =>
        loop(tail, Some(dir), output, time)
      case extra :: _ =>
        Left(s"unexpected argument '$extra'; run takes one job directory")
    }
    loop(args, None, None, None)
  }

  private val QueryFlags =
    Set("--table", "--option", "--job", "--job-output", "--execution-time", "-f", "--file", "--format", "--max-rows",
      "--output")

  private def parseQuery(args: List[String], now: => Instant): Either[String, Command] = {
    var sql = Option.empty[String]
    var file = Option.empty[Path]
    val tables = mutable.LinkedHashMap.empty[String, TableArg]
    val settings = mutable.ArrayBuffer.empty[(String, String, String)]
    var job = Option.empty[Path]
    var jobOutput = Option.empty[String]
    var time = Option.empty[Instant]
    var format = Option.empty[OutputFormat]
    var maxRows = Option.empty[Int]
    var output = Option.empty[String]
    var spill = false
    val seen = mutable.Set.empty[String]

    def positional(value: String): Either[String, Unit] =
      if (sql.isDefined) Left(s"unexpected argument '$value'; query takes one SQL argument, in quotes")
      else { sql = Some(value); Right(()) }

    def flag(name: String, value: String): Either[String, Unit] = {
      val canonical = if (name == "-f") "--file" else name
      if (!Set("--table", "--option")(canonical) && !seen.add(canonical)) return Left(s"$canonical given twice")
      canonical match {
        case "--table" =>
          val eq = value.indexOf('=')
          if (eq <= 0 || eq == value.length - 1) return Left(s"--table takes <name>=<path>, not '$value'")
          val name = value.take(eq)
          try QuerySession.checkTableName(name) catch { case e: IllegalArgumentException => return Left(e.getMessage) }
          if (tables.contains(name.toLowerCase(Locale.ROOT))) return Left(s"--table $name given twice")
          tables(name.toLowerCase(Locale.ROOT)) = TableArg(name, value.drop(eq + 1))
        case "--option" =>
          val dot = value.indexOf('.')
          val eq = value.indexOf('=', dot + 1)
          if (dot <= 0 || eq <= dot + 1) return Left(s"--option takes <table>.<key>=<value>, not '$value'")
          settings += ((value.take(dot), value.substring(dot + 1, eq), value.drop(eq + 1)))
        case "--job" => job = Some(Paths.get(value))
        case "--job-output" => jobOutput = Some(value)
        case "--execution-time" =>
          try time = Some(Instant.parse(value))
          catch {
            case _: DateTimeParseException =>
              return Left(s"--execution-time '$value' is not an ISO-8601 instant such as 2026-01-01T00:00:00Z")
          }
        case "--file" => file = Some(Paths.get(value))
        case "--format" =>
          OutputFormat.named(value) match {
            case None => return Left(s"--format takes ${OutputFormat.names}, not '$value'")
            case f => format = f
          }
        case "--max-rows" =>
          value.toIntOption.filter(_ >= 0) match {
            case None => return Left(s"--max-rows takes a whole number, 0 for every row, not '$value'")
            case n => maxRows = n
          }
        case "--output" => output = Some(value)
      }
      Right(())
    }

    /** `settings`, each the format or an option of the table it names. */
    def applySettings(): Either[String, Unit] = {
      val it = settings.iterator
      while (it.hasNext) {
        val (table, key, value) = it.next()
        val k = table.toLowerCase(Locale.ROOT)
        tables.get(k) match {
          case None => return Left(s"--option names table '$table', which no --table names")
          case Some(t) if key == "format" =>
            if (t.format.isDefined) return Left(s"--option $table.format given twice")
            tables(k) = t.copy(format = Some(value))
          case Some(t) =>
            if (t.options.contains(key)) return Left(s"--option $table.$key given twice")
            tables(k) = t.copy(options = t.options + (key -> value))
        }
      }
      Right(())
    }

    var rest = args
    while (rest.nonEmpty) {
      val step = rest match {
        case "--" :: tail =>
          // Everything after `--` is the SQL, even text that starts with `-`.
          val r = tail match {
            case Nil => Left("-- needs the SQL after it")
            case value :: Nil => positional(value)
            case _ :: extra :: _ => Left(s"unexpected argument '$extra'; query takes one SQL argument, in quotes")
          }
          rest = Nil
          r
        case "--spill" :: more =>
          rest = more
          if (spill) Left("--spill given twice") else { spill = true; Right(()) }
        case name :: tail if QueryFlags(name) =>
          tail match {
            case Nil => rest = Nil; Left(s"$name needs a value")
            case value :: more => rest = more; flag(name, value)
          }
        case name :: _ if name.startsWith("-") => rest = Nil; Left(s"unknown option '$name'")
        case value :: more => rest = more; positional(value)
        case Nil => Right(())
      }
      step match { case Left(problem) => return Left(problem); case Right(()) => () }
    }

    applySettings() match { case Left(problem) => return Left(problem); case Right(()) => () }
    if (job.isEmpty && jobOutput.isDefined) return Left("--job-output is for --job")
    if (job.isEmpty && time.isDefined) return Left("--execution-time is for --job")
    if (sql.isDefined && file.isDefined) return Left("give the SQL as an argument or with --file, not both")
    Right(Command.Query(
      sql = sql,
      file = file,
      tables = tables.values.toSeq,
      job = job.map(JobArg(_, jobOutput, time.getOrElse(now.truncatedTo(ChronoUnit.SECONDS)))),
      format = format.getOrElse(OutputFormat.Table),
      maxRows = maxRows.getOrElse(QuerySession.DefaultMaxRows),
      output = output,
      spill = spill
    ))
  }

  /** Run `transformer query`: name its tables and job, then run its SQL, the
    * argument's, the file's, or, when standard input is no terminal, what it
    * holds, printing results to `out` and errors to `err`; with no SQL on a
    * terminal (`terminal`), open the shell there. Returns the exit code. */
  def query(cmd: Command.Query, in: InputStream, out: PrintStream, err: PrintStream, terminal: Boolean): Int = {
    val session = new QuerySession
    session.format = cmd.format
    session.maxRows = cmd.maxRows
    session.spill = cmd.spill
    try {
      cmd.job.foreach(j => session.addJob(j.dir, j.output, j.executionTime, err))
      cmd.tables.foreach(t => session.addTable(t.name, t.path, t.format, t.options))
    } catch {
      case NonFatal(e) =>
        err.println(s"Error: ${QuerySession.message(e)}")
        return ExitRunFailed
    }
    cmd.file.foreach { f =>
      if (!Files.isRegularFile(f)) {
        err.println(s"Error: No file is at $f")
        return ExitRunFailed
      }
    }
    val script = cmd.sql.orElse(cmd.file.map(f => Files.readString(f)))
      .orElse(if (terminal) None else Some(new String(in.readAllBytes(), StandardCharsets.UTF_8)))
    script match {
      case Some(text) =>
        if (terminal && cmd.format == OutputFormat.Table) session.width = Shell.terminalWidth()
        if (session.runScript(text, new Io(out, err), cmd.output)) ExitOk else ExitRunFailed
      case None if cmd.output.isDefined =>
        err.println("transformer: --output writes a query's result; give the query as an argument, with --file, " +
          "or on standard input")
        ExitUsage
      case None =>
        val t = Shell.systemTerminal()
        try Shell.run(session, t, err, Some(Paths.get(System.getProperty("user.home"), ".transformer_history")))
        finally t.close()
        ExitOk
    }
  }

  /** Whether standard input and output are a terminal. Before JDK 22,
    * `System.console()` is one only then; from 22 it is one regardless, and
    * says with `isTerminal`. */
  def onTerminal(): Boolean = Option(System.console()).exists { console =>
    try classOf[java.io.Console].getMethod("isTerminal").invoke(console).asInstanceOf[Boolean]
    catch { case _: NoSuchMethodException => true }
  }

  /** Load and run the job `cmd` names, printing progress to `out` and the
    * failure reasons to `err`. Returns the exit code. */
  def run(cmd: Command.Run, out: PrintStream, err: PrintStream): Int = {
    if (!Files.isDirectory(cmd.jobDir)) {
      err.println(s"Job directory not found: ${cmd.jobDir}")
      return ExitRunFailed
    }
    val job =
      try {
        DirectoryJobLoader.load(
          jobDir = cmd.jobDir,
          outputDir = cmd.outputDir,
          temporalVariables = Some(TemporalVariables(cmd.executionTime))
        )
      } catch {
        case NonFatal(e) =>
          err.println(s"Could not load job ${cmd.jobDir}: ${e.getMessage}")
          return ExitRunFailed
      }

    out.println(s"Job:            ${cmd.jobDir.toAbsolutePath.normalize()}")
    out.println(s"Execution time: ${cmd.executionTime}")
    out.println(s"Inputs:         ${job.inputs.size}")
    out.println(s"Tables:         ${job.sql.size}")
    out.println()

    // Worker threads finish tasks concurrently; one lock keeps lines whole.
    val listener = new TaskProgressListener {
      def onTaskStart(taskIndex: Int, taskName: String): Unit = ()
      def onTaskFinish(taskIndex: Int, result: TaskResult): Unit =
        out.synchronized(out.println(describe(result)))
    }
    val result = job.run(SqlEngine, listener)

    out.println()
    result.warnings.foreach(w => out.println(s"warning: $w"))
    val counts = result.tasks.groupBy(t => statusName(t.status)).map { case (k, v) => s"${v.size} $k" }
    out.println(s"Job ${if (result.succeeded) "SUCCEEDED" else "FAILED"} (${counts.toSeq.sorted.mkString(", ")})")
    result.error.foreach(e => err.println(s"Error: $e"))
    if (result.succeeded) ExitOk else ExitRunFailed
  }

  private def statusName(s: TaskStatus): String = s match {
    case TaskStatus.Pending => "Pending"
    case TaskStatus.Succeeded => "Succeeded"
    case _: TaskStatus.Failed => "Failed"
    case _: TaskStatus.ValidationFailed => "ValidationFailed"
    case _: TaskStatus.Skipped => "Skipped"
  }

  private def describe(t: TaskResult): String = {
    val head = "  %-16s %-28s rows=%-10d %6d ms".formatLocal(Locale.ROOT,
      statusName(t.status), t.taskName, t.rowsProduced, t.durationMillis)
    t.status match {
      case TaskStatus.Failed(reason) => s"$head\n    $reason"
      case TaskStatus.Skipped(reason) => s"$head\n    $reason"
      case TaskStatus.ValidationFailed(fs) =>
        (head +: fs.map(f => s"    validation '${f.validationName}' returned ${f.rowCount} row(s)")).mkString("\n")
      case _ => head
    }
  }
}
