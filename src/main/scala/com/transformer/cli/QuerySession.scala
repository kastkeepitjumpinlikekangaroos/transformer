package com.transformer.cli

import com.transformer.cli.SqlScript.{Piece, Statement}
import com.transformer.core.{Catalog, CatalogView, ColumnarBatch, DataType, ExecutedQuery, ExecutionOptions,
  OptionValues, ParallelDrain, QueryStoppedException, Schema}
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

  /** Stop here, as [[printing]] stops before a batch, when `out` can take no
    * more or the run was cancelled: for a result printed only once it is all
    * read, so a script piped into `head` stops at its next statement. */
  def stopIfClosed(): Unit = {
    if (out.checkError()) cancel.cancel()
    if (cancel.isCancelled) throw new QueryStoppedException
  }
}

/** Stops a statement from another thread, as Ctrl-C in the shell does. The
  * engine's scans ask it before each batch they read ([[ExecutionOptions.stopped]]),
  * and so does the CLI before each batch it prints or writes: the statement
  * fails with a [[QueryStoppedException]] wherever it is, and a file it was
  * writing is not written.
  *
  * A run's `Cancel` stops every statement of it; each statement runs with a
  * [[child]] of its own, which stops it alone when one of its partitions
  * fails ([[ParallelDrain]]). */
final class Cancel private (parent: Cancel) {
  def this() = this(null)

  @volatile private var cancelled = false

  def cancel(): Unit = cancelled = true

  /** Whether this, or the `Cancel` it is a child of, was cancelled. */
  def isCancelled: Boolean = cancelled || (parent != null && parent.isCancelled)

  /** What a statement's scans ask before each batch ([[ExecutionOptions.stopped]]). */
  val stopped: () => Boolean = () => isCancelled

  /** A stop for one statement: cancelled when it is, or when this is.
    * Cancelling it leaves this as it was. */
  def child(): Cancel = new Cancel(this)

  /** This as a drain's stop: it asks [[stopped]], and stops by cancelling. */
  def drainStop: ParallelDrain.Stop = ParallelDrain.Stop(stopped, () => cancel())

  /** `batches`, checked before each one. */
  def watch(batches: Iterator[ColumnarBatch]): Iterator[ColumnarBatch] = new Iterator[ColumnarBatch] {
    def hasNext: Boolean = { if (isCancelled) throw new QueryStoppedException; batches.hasNext }
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
  /** Let a statement's sorts, aggregates, DISTINCT, windows and joins spill
    * to disk past their memory threshold, as a job's table with `"spill":
    * "true"` does. */
  var spill: Boolean = false
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
        // Stopped as it was asked to; once the run is cancelled, a failure
        // met on the way out says nothing either.
        case e: Throwable if ParallelDrain.isStop(e) || io.cancel.isCancelled => false
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
    // The statement's own stop: a partition that fails stops the others
    // through it, and leaves the run's to Ctrl-C and a closed output.
    val stop = io.cancel.child()
    val executed = SqlEngine.execute(withViews(sql), catalog, optionsFor(stop))
    // Closed however the print ends: `| head` stops it, Ctrl-C stops it, a
    // partition fails.
    try {
      if (io.results > 0 && format.setApart) io.out.println()
      io.results += 1
      if (format == OutputFormat.Table && SqlParser.explained(sql).isDefined)
        ResultPrinter.lines(io.out, io.printing(executed.batches))
      else ResultPrinter.print(io, executed, stop, format, maxRows, width)
    } finally executed.close()
  }

  /** The options a statement runs with: spill as the session has it, and
    * scans that stop once `cancel`, the statement's own, is set. */
  private[cli] def optionsFor(cancel: Cancel): ExecutionOptions =
    ExecutionOptions(spillEnabled = spill, stopped = cancel.stopped)

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
    val perPartition = c.options.collectFirst { case (k, v) if k.equalsIgnoreCase(PerPartitionKey) =>
      OptionValues.bool(PerPartitionKey, v)
    }.getOrElse(false)
    if (!perPartition && Files.isDirectory(target)) throw new IllegalArgumentException(
      s"${c.path} is a directory; COPY TO writes one file, or a directory of parquet files with " +
        s"(FORMAT parquet, $PerPartitionKey true)")
    val to = writeOptions(c.path, c.options, perPartition)
    if (perPartition) checkPartsDirectory(c.path, target)
    // The statement's own stop, as a query's.
    val stop = io.cancel.child()
    val executed = SqlEngine.execute(withViews(sql), catalog, optionsFor(stop))
    val (rows, files) = try {
      val names = executed.schema.fieldNames
      Schema.repeatedName(names).foreach { positions =>
        throw new IllegalArgumentException(
          s"The result has more than one column named '${names(positions.head)}', columns " +
            s"${Schema.describePositions(names, positions)}, which a file read by column name could not tell apart, " +
            "names being read regardless of case; give each its own name with AS")
      }
      if (perPartition) writeParts(target, executed, stop, to.options)
      else (writeOne(target, executed, stop, io, to), 1)
    } finally executed.close()
    val parts = if (perPartition) s" (${count(files.toLong, "file")})" else ""
    io.err.println(s"Wrote ${count(rows, "row")} to ${c.path}$parts")
  }

  /** `executed` written to the one file `target`, its partitions read ahead
    * on the pool and written in order on this thread. Returns the rows
    * written. */
  private def writeOne(target: Path, executed: ExecutedQuery, stop: Cancel, io: Io, to: CopyTarget): Long = {
    val ordered = ParallelDrain.inOrder(executed, ParallelDrain.inFlight(executed.numPartitions),
      ParallelDrain.ChunkBatches, stop.drainStop)
    try {
      val batches = io.cancel.watch(ordered)
      if (to.format == "csv") CsvWriter.writeAll(target, executed.schema, batches, CsvWriteOptions.fromMap(to.options))
      else ParquetWriter.writeAll(target, executed.schema, batches, to.options)
    } finally ordered.close()
  }

  /** `executed` written to the directory `target` as a parquet file a
    * partition, as a job writes a table: `part-00000.parquet`, ..., each
    * written on the pool, committed together once all are written, an empty
    * partition writing none. A partition that fails stops the others, and
    * neither a failed write nor a stopped one leaves a part file, or a
    * directory this made. Returns the rows written and the files. */
  private def writeParts(target: Path, executed: ExecutedQuery, stop: Cancel,
      options: Map[String, String]): (Long, Int) = {
    val n = executed.numPartitions
    // Each partition's failure, first by partition.
    val failures = new java.util.concurrent.atomic.AtomicReferenceArray[Throwable](math.max(1, n))
    // Claimed before any is read, so a failure in one does not close another
    // under the writer reading it; `executed.close()` closes them all.
    val parts = (0 until n).map { p =>
      val it = executed.claim(p)
      new Iterator[ColumnarBatch] {
        private def failed(t: Throwable): Nothing = {
          val _ = failures.compareAndSet(p, null, t)
          stop.cancel()
          throw t
        }
        def hasNext: Boolean = {
          if (stop.isCancelled) throw new QueryStoppedException
          try it.hasNext catch { case t: Throwable => failed(t) }
        }
        def next(): ColumnarBatch = try it.next() catch { case t: Throwable => failed(t) }
      }
    }
    // The directories the write makes, the deepest first: the target and each
    // parent it lacks.
    val made = Iterator.iterate(target.toAbsolutePath)(_.getParent).takeWhile(d => d != null && !Files.exists(d))
      .toVector
    try {
      val written = ParquetWriter.writePartitioned(target, executed.schema, parts, options)
      (written.rows, written.files.size)
    } catch {
      case t: Throwable =>
        made.foreach(d => try Files.deleteIfExists(d) catch { case NonFatal(_) => () })
        // The writer gives the first failure in partition order, which can be
        // a partition stopped by another's failure: that failure is said.
        val real = (0 until n).iterator.map(failures.get).find(f => f != null && !ParallelDrain.isStop(f))
        throw (if (ParallelDrain.isStop(t)) real.getOrElse(t) else t)
    }
  }

  /** A COPY's directory of part files is a new one or an empty one. */
  private def checkPartsDirectory(path: String, target: Path): Unit =
    if (Files.exists(target)) {
      if (!Files.isDirectory(target)) throw new IllegalArgumentException(
        s"$path is a file; COPY TO with $PerPartitionKey true writes a directory of part files")
      val entries = Files.list(target)
      val empty = try !entries.iterator.hasNext finally entries.close()
      if (!empty) throw new IllegalArgumentException(
        s"$path is a directory that holds files; COPY TO with $PerPartitionKey true writes its part files into " +
          "a new directory or an empty one")
    }

  /** The format a COPY writes `path` in, by its FORMAT option or else its
    * extension, and the writer's options among those `written`, each named in
    * any case and read as an `output.json` reads it. With `perPartition` it
    * writes a directory of parquet files, which `parquet_write_parallelism`
    * says how many of to write at once. */
  private def writeOptions(path: String, written: Seq[(String, String)], perPartition: Boolean): CopyTarget = {
    written.groupBy(_._1.toLowerCase(Locale.ROOT)).collectFirst { case (_, Seq(first, _, _*)) => first._1 }
      .foreach(k => throw new IllegalArgumentException(s"COPY names the option $k twice"))
    val (formats, others) = written.partition(_._1.equalsIgnoreCase("format"))
    val rest = others.filterNot(_._1.equalsIgnoreCase(PerPartitionKey))
    val named = formats.headOption.map(_._2.toLowerCase(Locale.ROOT))
    named.filterNot(f => WriteFormats.contains(f)).foreach { f =>
      throw new IllegalArgumentException(s"COPY writes FORMAT csv or FORMAT parquet, not $f")
    }
    val lower = path.toLowerCase(Locale.ROOT)
    val fileFormat = named.orElse(WriteFormats.find(f => lower.endsWith("." + f))).getOrElse(
      throw new IllegalArgumentException(s"Cannot tell the format of $path from its name; name it .csv or .parquet, " +
        "or add (FORMAT csv) or (FORMAT parquet)"))
    if (perPartition && fileFormat == "csv") throw new IllegalArgumentException(
      s"COPY TO with $PerPartitionKey true writes parquet files; a CSV result is written as one file")
    val accepted = if (fileFormat == "csv") CsvWriteOptions.Keys else if (perPartition) ParquetWriter.Keys else ParquetFileKeys
    val options = rest.map { case (k, v) =>
      accepted.find(_.equalsIgnoreCase(k)).getOrElse {
        if (fileFormat == "parquet" && k.equalsIgnoreCase(ParallelismKey)) throw new IllegalArgumentException(
          s"COPY TO a parquet file takes $ParallelismKey only with $PerPartitionKey true, which writes a directory " +
            "of a file a partition")
        val listed = if (fileFormat == "csv") accepted else accepted :+ PerPartitionKey
        throw new IllegalArgumentException(s"COPY TO a $fileFormat file takes FORMAT and ${listed.mkString(", ")}, not $k")
      } -> v
    }.toMap
    if (fileFormat == "csv") { val _ = CsvWriteOptions.fromMap(options) } else ParquetWriter.checkOptions(options)
    CopyTarget(fileFormat, options)
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
      case "spill" => d.args.map(_.toLowerCase(Locale.ROOT)) match {
        case Seq() => io.out.println(if (spill) "on" else "off")
        case Seq("on") => spill = true
        case Seq("off") => spill = false
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

  /** COPY's option for a directory of a parquet file a partition. */
  val PerPartitionKey = "PER_PARTITION_OUTPUT"

  /** The parquet writer's option for how many part files it writes at once. */
  private val ParallelismKey = "parquet_write_parallelism"

  /** The parquet writer's options that a single file can take: how many files
    * are written at once is no question for one. */
  private val ParquetFileKeys = ParquetWriter.Keys.filterNot(_ == ParallelismKey)

  /** What a COPY writes: its format, and the writer's options. */
  private final case class CopyTarget(format: String, options: Map[String, String])

  private val MaxReadDepth = 16

  /** A `.read` script that failed, having said why. */
  private final class ScriptFailed extends RuntimeException("the script failed", null, false, false)

  private def count(n: Long, what: String): String = if (n == 1) s"1 $what" else s"$n ${what}s"

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
    ".spill [on|off]" -> "Let sorts, aggregates and joins spill to disk past their memory threshold, as --spill does",
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
      "    write one .csv or .parquet file; options: FORMAT, and output.json's options for the format;",
      s"    with (FORMAT parquet, $PerPartitionKey true), a new or empty directory of a parquet file a partition",
      "Ctrl-C stops a running statement, and clears a line being typed."
    )).mkString("", "\n", "\n")
  }
}
