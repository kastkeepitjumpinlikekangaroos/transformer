package com.transformer.cli

import com.transformer.cli.SqlScript.{Piece, Statement}
import com.transformer.core.{Catalog, CatalogView, ColumnarBatch, DataType, ExecutionOptions, QueryStoppedException,
  Schema}
import com.transformer.gui.{JobSession, RunState, ViewKind}
import com.transformer.job.{InputFilePath, InputResolver, TaskDag, TaskRunRecord}
import com.transformer.read.csv.PathGlob
import com.transformer.sql.exec.SqlEngine
import com.transformer.sql.parse.SqlParser
import com.transformer.sql.plan.LogicalBuilder
import com.transformer.write.csv.{CsvWriteOptions, CsvWriter}
import com.transformer.write.parquet.ParquetWriter

import java.io.PrintStream
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.Locale
import scala.collection.mutable
import scala.util.control.NonFatal

/** Where one run of statements prints, and what stops it: the rows its
  * statements return go to `out`, messages and errors to `err`. */
final class Io(val out: PrintStream, val err: PrintStream, val cancel: Cancel = new Cancel) {
  // The results printed so far, so a table after another is set apart.
  private[cli] var results: Int = 0

  /** `batches` of rows to print on `out`, as [[Cancel.watch]] checks them,
    * and stopped the same way once `out` can take no more: a reader that has
    * closed its end of a pipe, as `head` does. A `PrintStream` keeps the
    * failure to itself, so a statement printing into a closed pipe went on
    * reading every row it had. */
  def printing(batches: Iterator[ColumnarBatch]): Iterator[ColumnarBatch] = {
    val watched = cancel.watch(batches)
    new Iterator[ColumnarBatch] {
      def hasNext: Boolean = { if (out.checkError()) cancel.cancel(); watched.hasNext }
      def next(): ColumnarBatch = watched.next()
    }
  }
}

/** Stops a statement from another thread, as Ctrl-C in the shell does. The
  * engine's scans ask it before each batch they read ([[ExecutionOptions.stopped]]),
  * and so does the CLI before each batch it prints or writes: the statement
  * fails with a [[QueryStoppedException]] wherever it is, and a file it was
  * writing is not written. */
final class Cancel {
  @volatile private var cancelled = false

  def cancel(): Unit = cancelled = true

  def isCancelled: Boolean = cancelled

  /** The options a statement runs with, which stop its scans once cancelled. */
  val options: ExecutionOptions = ExecutionOptions(stopped = () => cancelled)

  /** `batches`, checked before each one. */
  def watch(batches: Iterator[ColumnarBatch]): Iterator[ColumnarBatch] = new Iterator[ColumnarBatch] {
    def hasNext: Boolean = { if (cancelled) throw new QueryStoppedException; batches.hasNext }
    def next(): ColumnarBatch = batches.next()
  }
}

/** What `transformer query` runs statements against: the files `--table` and
  * `.table` name, the job `--job` names, and the views the session creates.
  * A one-off command runs its script in one session; the shell keeps one for
  * as long as it runs.
  *
  * A view is its query, kept as written. A statement that reads a view has
  * the view defined in a WITH ahead of it, and every view that view reads, so
  * the engine binds and optimizes the view's query inside the statement, as if
  * it had been written there: a filter on the view reaches the files it reads,
  * and a view always reads the files as they are now. */
final class QuerySession {
  import QuerySession._

  SqlEngine.init()

  private val catalog = new Catalog
  // Every name the session knows, by its lower-case form, in the order named.
  private val sources = mutable.LinkedHashMap.empty[String, Source]
  private var reading = 0 // how deep .read scripts are running inside each other
  private var _quit = false

  /** How results print. */
  var format: OutputFormat = OutputFormat.Table
  /** The rows a table shows, the first half and the last; 0 shows every row. */
  var maxRows: Int = DefaultMaxRows
  /** Print each statement's run time. */
  var timer: Boolean = false
  /** The terminal's width, which a table fits itself to; None when the output
    * is no terminal. */
  @volatile var width: Option[Int] = None

  /** Whether `.quit` (or `.exit`) has run. */
  def quit: Boolean = _quit

  /** Every name a statement can read: tables, the job's views, views. */
  def names: Seq[String] = sources.values.map(_.name).toSeq

  /** Name `path` `name` for SQL to read, as `--table` does: a file, a glob or
    * a directory of files, read as an input of a job is (`format` and
    * `options` as its `config.json` takes them, the format by default from
    * the path, or from the files a directory holds when every one is
    * `.parquet`), or a job's output directory, read as its run wrote it. A
    * table of the name is replaced; a view or a job's view is not. */
  def addTable(name: String, path: String, format: Option[String], options: Map[String, String]): Unit = {
    checkTableName(name)
    checkFree(name) // before reading the file, which may take a while
    register(name, readFile(name, path, format, options), path)
  }

  /** Name `view` `name`, a table read from `source`. */
  private[cli] def addTable(name: String, view: CatalogView, source: String): Unit = {
    checkFree(name)
    register(name, view, source)
  }

  private def checkFree(name: String): Unit = sources.get(key(name)) match {
    case None | Some(_: FileTable) => ()
    case Some(other) => throw new IllegalArgumentException(
      s"The name $name is taken by ${describe(other)}; give the table another name")
  }

  private def register(name: String, view: CatalogView, source: String): Unit = {
    catalog.replace(name, view)
    sources(key(name)) = FileTable(name, source)
  }

  private def readFile(name: String, path: String, format: Option[String], options: Map[String, String]): CatalogView = {
    val dir = Paths.get(path)
    val record = if (Files.isDirectory(dir)) TaskRunRecord.read(dir) else None
    record match {
      case Some(rec) =>
        if (format.nonEmpty || options.nonEmpty) throw new IllegalArgumentException(
          s"$path is a job's output, which reads with the format, options and column types its run recorded; " +
            "give it no format or options")
        TaskRunRecord.outputView(dir, rec)
      case None =>
        val files = PathGlob.expand(path)
        if (files.isEmpty) throw new IllegalArgumentException(s"No file is at $path")
        InputResolver.resolve(InputFilePath(path, viewName = name, options = options,
          format = format.orElse(formatOfFiles(path, files))))
    }
  }

  /** Name the inputs of the job in `dir` and the tables of its last run, as
    * the GUI's SQL console reads them: inputs at their paths rendered for
    * `executionTime`, tables from the run under `output` (the job's
    * `<dir>/output` by default) for that time, else the newest run there.
    * What cannot be read is a warning on `err`. */
  def addJob(dir: Path, output: Option[String], executionTime: Instant, err: PrintStream): Unit = {
    if (!Files.isDirectory(dir)) throw new IllegalArgumentException(s"Job directory not found: $dir")
    val job = new JobSession
    job.setExecutionTime(executionTime)
    job.setOutputDirOverride(output)
    job.openJobDir(dir)
    job.runState match {
      case RunState.LoadFailed(reason) => throw new IllegalArgumentException(s"Could not load job $dir: $reason")
      case _ => ()
    }
    job.hydrationWarnings.foreach(w => err.println(s"warning: $w"))
    val interactive = job.buildInteractiveCatalog()
    interactive.errors.foreach(e => err.println(s"warning: $e"))
    interactive.views.foreach { v =>
      val kind = if (v.kind == ViewKind.Input) "job input" else "job table"
      catalog.replace(v.name, interactive.catalog(v.name))
      sources(key(v.name)) = JobTable(v.name, kind, v.path.getOrElse(""))
    }
    if (job.dag.exists(_.nodes.nonEmpty) && !interactive.views.exists(_.kind == ViewKind.Task)) {
      val under = job.effectiveOutputDirRendered.getOrElse(dir.toString)
      err.println(s"warning: no run of the job is on disk under $under; only its inputs can be read")
    }
  }

  /** Run each statement and dot command of `text` in turn, printing what each
    * returns; at the first that fails, say why on `io.err` and stop. With
    * `into`, the script's one query writes its result to that file, as
    * `COPY (query) TO 'file'` does, instead of printing it. True when every
    * statement ran. */
  def runScript(text: String, io: Io, into: Option[String] = None): Boolean = {
    val pieces = SqlScript.split(text).all
    into match {
      case None => runPieces(pieces, io, identity)
      case Some(path) =>
        val queries =
          try pieces.count(p => SqlScript.statement(p).isInstanceOf[Statement.Query])
          catch { case NonFatal(_) => 1 } // a statement that does not read fails as the script runs, saying why
        if (queries != 1) {
          io.err.println(s"Error: --output writes the result of one query, and the script holds $queries; " +
            "write each result with COPY (query) TO 'file' instead")
          false
        } else runPieces(pieces, io, {
          case Statement.Query(sql) => Statement.Copy(Right(sql), path, Nil)
          case other => other
        })
    }
  }

  private def runPieces(pieces: Seq[Piece], io: Io, rewrite: Statement => Statement): Boolean = {
    val many = pieces.size > 1
    val it = pieces.iterator
    var ok = true
    while (ok && it.hasNext && !_quit) {
      if (io.cancel.isCancelled) ok = false
      else ok = run(it.next(), io, many, rewrite)
    }
    ok
  }

  private def run(piece: Piece, io: Io, many: Boolean, rewrite: Statement => Statement): Boolean = {
    val started = System.nanoTime()
    val ok =
      try { execute(rewrite(SqlScript.statement(piece)), io); true }
      catch {
        case _: ScriptFailed => false
        case e: Throwable if stopped(e) => false
        case e: VirtualMachineError => report(piece, e, io, many)
        case NonFatal(e) => report(piece, e, io, many)
      }
    if (timer && !piece.dot && !io.cancel.isCancelled)
      io.err.println("Run time: %.3f s".formatLocal(Locale.ROOT, (System.nanoTime() - started) / 1e9))
    ok
  }

  private def report(piece: Piece, e: Throwable, io: Io, many: Boolean): Boolean = {
    val where = if (many) s" in the ${if (piece.dot) "command" else "statement"} on line ${piece.line}" else ""
    io.err.println(s"Error$where: ${message(e)}")
    false
  }

  private def execute(statement: Statement, io: Io): Unit = statement match {
    case Statement.Query(sql) => query(sql, io)
    case v: Statement.CreateView => createView(v)
    case Statement.DropView(name, ifExists) => dropView(name, ifExists)
    case c: Statement.Copy => copy(c, io)
    case d: Statement.Dot => dot(d, io)
  }

  private def query(sql: String, io: Io): Unit = {
    val executed = SqlEngine.execute(withViews(sql), catalog, io.cancel.options)
    val batches = io.printing(executed.batches)
    if (io.results > 0 && format.setApart) io.out.println()
    io.results += 1
    if (format == OutputFormat.Table && SqlParser.explained(sql).isDefined) ResultPrinter.lines(io.out, batches)
    else ResultPrinter.print(io.out, executed.schema, batches, format, maxRows, width)
  }

  // ---------------------------------------------------------------------------
  // Views
  // ---------------------------------------------------------------------------

  private def createView(v: Statement.CreateView): Unit = {
    sources.get(key(v.name)) match {
      case None => ()
      case Some(_: View) if v.orReplace => ()
      case Some(_: View) => throw new IllegalArgumentException(
        s"View ${v.name} already exists; CREATE OR REPLACE VIEW replaces it")
      case Some(other) => throw new IllegalArgumentException(
        s"The name ${v.name} is taken by ${describe(other)}; give the view another name")
    }
    val created = View(v.name, v.columns, v.query, TaskDag.viewsRead(v.query))
    val others = views - key(v.name)
    if (inDependencyOrder(created.reads, others).exists(_.reads(key(v.name))) || created.reads(key(v.name)))
      throw new IllegalArgumentException(s"View ${v.name} would read itself")
    // Bound now, so a view that cannot be read is never created.
    val _ = schemaOf(created, others + (key(v.name) -> created))
    sources(key(v.name)) = created
  }

  private def dropView(name: String, ifExists: Boolean): Unit = sources.get(key(name)) match {
    case Some(_: View) => sources -= key(name)
    case None if ifExists => ()
    case None => throw new IllegalArgumentException(s"There is no view $name")
    case Some(other) => throw new IllegalArgumentException(
      s"$name is ${describe(other)}, not a view; DROP VIEW drops a view")
  }

  private def views: Map[String, View] = sources.iterator.collect { case (k, v: View) => k -> v }.toMap

  /** The columns `view` returns: its query's, bound with the views it reads,
    * or with a column list, `SELECT *` of it, which checks the list against the
    * query. A view is read by its column names, so none may repeat. */
  private def schemaOf(view: View, all: Map[String, View]): Schema = {
    val sql = if (view.columns.isEmpty) view.query else s"SELECT * FROM ${SqlParser.quote(view.name)}"
    val schema = LogicalBuilder.build(withViews(sql, all), catalog).main.outputSchema
    Schema.repeatedName(schema.fieldNames).foreach { positions =>
      val names = schema.fieldNames
      throw new IllegalArgumentException(
        s"View ${view.name} returns more than one column named '${names(positions.head)}', columns " +
          s"${Schema.describePositions(names, positions)}, which a query reading it could not tell apart, " +
          "names being read regardless of case; give each its own name with AS")
    }
    schema
  }

  /** `sql` with the views of `all` that it reads, and the views those read,
    * defined in a WITH ahead of it. An EXPLAIN's are its query's. SQL that does
    * not parse is left as written, for the engine to say why. */
  private def withViews(sql: String, all: Map[String, View] = views): String = {
    if (all.isEmpty) return sql
    val query = SqlParser.explained(sql).fold(sql)(_.query)
    val reads = try TaskDag.viewsRead(query) catch { case NonFatal(_) => return sql }
    val needed = inDependencyOrder(reads, all)
    if (needed.isEmpty) sql
    else {
      // An EXPLAIN's query starts right after its keywords.
      val explain = sql.substring(0, sql.length - query.length)
      (if (explain.isEmpty) "" else explain + "\n") + SqlScript.withDefinitions(query, needed.map(definition))
    }
  }

  /** The views of `all` that `reads` names, and those they read, each after
    * every view it reads. */
  private def inDependencyOrder(reads: Set[String], all: Map[String, View]): Vector[View] = {
    val ordered = mutable.LinkedHashMap.empty[String, View]
    def visit(k: String, path: Set[String]): Unit = all.get(k).foreach { v =>
      if (!ordered.contains(k) && !path(k)) {
        v.reads.toSeq.sorted.foreach(visit(_, path + k))
        ordered(k) = v
      }
    }
    reads.toSeq.sorted.foreach(visit(_, Set.empty))
    ordered.values.toVector
  }

  private def definition(v: View): String = {
    val columns = if (v.columns.isEmpty) "" else v.columns.map(SqlParser.quote).mkString(" (", ", ", ")")
    // The query on lines of its own, so a comment ending it ends before the parenthesis.
    s"${SqlParser.quote(v.name)}$columns AS (\n${v.query}\n)"
  }

  // ---------------------------------------------------------------------------
  // COPY ... TO
  // ---------------------------------------------------------------------------

  private def copy(c: Statement.Copy, io: Io): Unit = {
    val sql = c.source.fold(name => s"SELECT * FROM ${SqlParser.quote(name)}", identity)
    val target = Paths.get(c.path)
    if (Files.isDirectory(target))
      throw new IllegalArgumentException(s"${c.path} is a directory; COPY TO writes one file")
    val (fileFormat, options) = writeOptions(c.path, c.options)
    val executed = SqlEngine.execute(withViews(sql), catalog, io.cancel.options)
    val names = executed.schema.fieldNames
    Schema.repeatedName(names).foreach { positions =>
      throw new IllegalArgumentException(
        s"The result has more than one column named '${names(positions.head)}', columns " +
          s"${Schema.describePositions(names, positions)}, which a file read by column name could not tell apart, " +
          "names being read regardless of case; give each its own name with AS")
    }
    val batches = io.cancel.watch(executed.batches)
    val rows =
      if (fileFormat == "csv") CsvWriter.writeAll(target, executed.schema, batches, CsvWriteOptions.fromMap(options))
      else ParquetWriter.writeAll(target, executed.schema, batches, options)
    io.err.println(s"Wrote ${count(rows, "row")} to ${c.path}")
  }

  /** The format a COPY writes `path` in, by its FORMAT option or else its
    * extension, and the writer's options among those `written`, each named in
    * any case and read as an `output.json` reads it. */
  private def writeOptions(path: String, written: Seq[(String, String)]): (String, Map[String, String]) = {
    written.groupBy(_._1.toLowerCase(Locale.ROOT)).collectFirst { case (_, Seq(first, _, _*)) => first._1 }
      .foreach(k => throw new IllegalArgumentException(s"COPY names the option $k twice"))
    val (formats, rest) = written.partition(_._1.equalsIgnoreCase("format"))
    val named = formats.headOption.map(_._2.toLowerCase(Locale.ROOT))
    named.filterNot(f => WriteFormats.contains(f)).foreach { f =>
      throw new IllegalArgumentException(s"COPY writes FORMAT csv or FORMAT parquet, not $f")
    }
    val lower = path.toLowerCase(Locale.ROOT)
    val fileFormat = named.orElse(WriteFormats.find(f => lower.endsWith("." + f))).getOrElse(
      throw new IllegalArgumentException(s"Cannot tell the format of $path from its name; name it .csv or .parquet, " +
        "or add (FORMAT csv) or (FORMAT parquet)"))
    val accepted = if (fileFormat == "csv") CsvWriteOptions.Keys else ParquetFileKeys
    val options = rest.map { case (k, v) =>
      accepted.find(_.equalsIgnoreCase(k)).getOrElse(throw new IllegalArgumentException(
        s"COPY TO a $fileFormat file takes FORMAT and ${accepted.mkString(", ")}, not $k")) -> v
    }.toMap
    if (fileFormat == "csv") { val _ = CsvWriteOptions.fromMap(options) } else ParquetWriter.checkOptions(options)
    (fileFormat, options)
  }

  // ---------------------------------------------------------------------------
  // Dot commands
  // ---------------------------------------------------------------------------

  private def dot(d: Statement.Dot, io: Io): Unit = {
    def usage: Nothing = {
      val syntax = DotCommands.map(_._1).find(_.drop(1).takeWhile(_ != ' ') == d.command)
      throw new IllegalArgumentException(s"Usage: ${syntax.getOrElse("." + d.command)}")
    }
    def listing(names: Seq[String], rows: Seq[Seq[String]]): Unit = {
      val (schema, batches) = ResultPrinter.strings(names, rows)
      if (io.results > 0 && format.setApart) io.out.println()
      io.results += 1
      ResultPrinter.print(io.out, schema, batches, format, maxRows = 0, width)
    }
    d.command match {
      case "help" => if (d.args.nonEmpty) usage else io.out.print(Help)
      case "tables" =>
        if (d.args.nonEmpty) usage
        listing(Seq("name", "kind", "source"), sources.values.toSeq.map {
          case FileTable(name, path) => Seq(name, "table", path)
          case JobTable(name, kind, path) => Seq(name, kind, path)
          case v: View => Seq(v.name, "view", v.query.split("\\s+").mkString(" "))
        })
      case "schema" => d.args match {
        case Seq() =>
          listing(Seq("name", "column_name", "column_type"), sources.values.toSeq.flatMap { s =>
            schemaOf(s).fields.map(f => Seq(s.name, f.name, DataType.sqlName(f.dataType)))
          })
        case Seq(name) =>
          val s = sources.getOrElse(key(name), throw new IllegalArgumentException(
            s"No table or view is named $name; .tables lists them"))
          listing(Seq("column_name", "column_type"),
            schemaOf(s).fields.map(f => Seq(f.name, DataType.sqlName(f.dataType))))
        case _ => usage
      }
      case "table" => d.args match {
        case name +: path +: settings =>
          val pairs = settings.map { s =>
            s.indexOf('=') match {
              case i if i > 0 => s.take(i) -> s.drop(i + 1)
              case _ => throw new IllegalArgumentException(s"An option of .table is key=value, not '$s'")
            }
          }
          pairs.groupBy(_._1).collectFirst { case (k, Seq(_, _, _*)) => k }.foreach { k =>
            throw new IllegalArgumentException(s".table names the option $k twice")
          }
          val (formats, options) = pairs.partition(_._1 == "format")
          addTable(name, path, formats.headOption.map(_._2), options.toMap)
        case _ => usage
      }
      case "mode" => d.args match {
        case Seq() => io.out.println(format.name)
        case Seq(name) => format = OutputFormat.named(name).getOrElse(throw new IllegalArgumentException(
          s".mode takes ${OutputFormat.names}, not $name"))
        case _ => usage
      }
      case "maxrows" => d.args match {
        case Seq() => io.out.println(maxRows)
        case Seq(n) => maxRows = n.toIntOption.filter(_ >= 0).getOrElse(throw new IllegalArgumentException(
          s".maxrows takes a whole number, 0 for every row, not $n"))
        case _ => usage
      }
      case "timer" => d.args.map(_.toLowerCase(Locale.ROOT)) match {
        case Seq() => io.out.println(if (timer) "on" else "off")
        case Seq("on") => timer = true
        case Seq("off") => timer = false
        case _ => usage
      }
      case "read" => d.args match {
        case Seq(file) =>
          val path = Paths.get(file)
          if (!Files.isRegularFile(path)) throw new IllegalArgumentException(s"No file is at $file")
          if (reading >= MaxReadDepth) throw new IllegalArgumentException(
            s".read runs scripts inside each other at most $MaxReadDepth deep")
          reading += 1
          try if (!runScript(Files.readString(path), io)) throw new ScriptFailed
          finally reading -= 1
        case _ => usage
      }
      case "quit" | "exit" => if (d.args.nonEmpty) usage else _quit = true
      case other => throw new IllegalArgumentException(s"Unknown command .$other; .help lists the commands")
    }
  }

  private def schemaOf(source: Source): Schema = source match {
    case v: View => schemaOf(v, views)
    case other => catalog(other.name).schema
  }
}

object QuerySession {

  val DefaultMaxRows = 40

  /** What a statement can read, besides the engine's tables. */
  private sealed trait Source { def name: String }
  private final case class FileTable(name: String, path: String) extends Source
  private final case class JobTable(name: String, kind: String, path: String) extends Source
  /** `query` as written, `columns` its column list, and `reads` the names it
    * reads, in lower case. */
  private final case class View(name: String, columns: Seq[String], query: String, reads: Set[String])
    extends Source

  private def describe(s: Source): String = s match {
    case FileTable(name, path) => s"the table $name, from $path"
    case JobTable(name, kind, _) => s"the $kind $name"
    case v: View => s"the view ${v.name}"
  }

  private def key(name: String): String = name.toLowerCase(Locale.ROOT)

  private val TableName = "[A-Za-z_][A-Za-z0-9_]*".r

  /** A table's name is one SQL reads without quotes. */
  def checkTableName(name: String): Unit =
    if (!TableName.matches(name)) throw new IllegalArgumentException(
      s"A table's name is letters, digits and underscores, not starting with a digit, so SQL reads it " +
        s"without quotes: not '$name'")

  /** A directory's format, when its path names none and every file it holds
    * is `.parquet`; else the path decides, as an input's does. */
  private def formatOfFiles(path: String, files: Seq[Path]): Option[String] = {
    val lower = path.toLowerCase(Locale.ROOT)
    if (lower.contains(".parquet") || lower.contains(".csv")) None
    else if (files.forall(_.getFileName.toString.toLowerCase(Locale.ROOT).endsWith(".parquet"))) Some("parquet")
    else None
  }

  private val WriteFormats = Seq("csv", "parquet")

  /** The parquet writer's options that a single file can take: how many files
    * are written at once is no question for one. */
  private val ParquetFileKeys = ParquetWriter.Keys.filterNot(_ == "parquet_write_parallelism")

  private val MaxReadDepth = 16

  /** A `.read` script that failed, having said why. */
  private final class ScriptFailed extends RuntimeException("the script failed", null, false, false)

  private def count(n: Long, what: String): String = if (n == 1) s"1 $what" else s"$n ${what}s"

  /** Whether `t` is a statement stopping as it was asked to, however the
    * engine's threads passed the stop on. */
  private def stopped(t: Throwable): Boolean =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).exists(_.isInstanceOf[QueryStoppedException])

  /** What a failure says: its message, or for an `Error`, what happened. */
  def message(t: Throwable): String =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).collectFirst { case e: VirtualMachineError => e } match {
      case Some(_: StackOverflowError) => "the query nests too deeply for the thread's stack"
      case Some(e: OutOfMemoryError) => s"out of memory (${e.getMessage}); give the JVM more heap with -Xmx"
      case Some(e) => e.toString
      case None => Option(t.getMessage).filter(_.nonEmpty).getOrElse(t.toString)
    }

  /** Each dot command's syntax and what it does. */
  val DotCommands: Seq[(String, String)] = Seq(
    ".tables" -> "List the tables and views SQL can read",
    ".schema [name]" -> "List the columns of a table or view, or of every one",
    ".table name path [key=value ...]" ->
      "Name a file, glob or directory for SQL to read, as --table does; format=parquet and an input's options",
    ".mode [format]" -> s"Print results as ${OutputFormat.names}",
    ".maxrows [n]" -> "Rows a table shows, the first and last ones; 0 shows every row",
    ".timer [on|off]" -> "Print how long each statement takes",
    ".read file" -> "Run the statements in a file",
    ".help" -> "List these commands",
    ".quit" -> "Leave the shell (.exit and Ctrl-D too)")

  val Help: String = {
    val width = DotCommands.map(_._1.length).max
    val commands = DotCommands.map { case (syntax, what) => s"  ${syntax.padTo(width, ' ')}  $what" }
    (commands ++ Seq(
      "",
      "Statements end with ;. A query (SELECT, WITH, EXPLAIN) prints its rows. Besides queries:",
      s"  ${SqlScript.CreateViewSyntax}",
      s"  ${SqlScript.DropViewSyntax}",
      "  COPY (query) TO 'file' [(option value, ...)]   and   COPY name TO 'file' [(...)]",
      "    write one .csv or .parquet file; options: FORMAT, and output.json's options for the format",
      "Ctrl-C stops a running statement, and clears a line being typed."
    )).mkString("", "\n", "\n")
  }
}
