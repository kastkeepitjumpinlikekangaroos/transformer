package com.transformer.read.parquet

import com.transformer.core.{Catalog, ExecutionOptions}
import com.transformer.fuzz.oracle.CleanRejection
import com.transformer.slt.Ledger
import com.transformer.sql.exec.SqlEngine
import com.transformer.write.csv.{CsvRowWriter, CsvWriteOptions}
import org.junit.Assert._
import org.junit.Test

import java.io.{ByteArrayInputStream, StringWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{Callable, Executors, ThreadFactory, TimeUnit, TimeoutException}
import java.util.zip.GZIPInputStream
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** Parquet files other programs wrote, each held to its line of its
  * directory's `MANIFEST` (README.md there says how each line was made):
  *
  *   - `reads <rows> <expect>`: the file reads, `SELECT *` giving `rows` rows
  *     and `COUNT(*)` as many, and the engine's CSV text of them is the
  *     expect file's, or their values are those of upstream's `*_expect.csv`;
  *   - `refused by-design <id>`: it is refused under the ledger's rule `id`
  *     (docs/gotchas.md), the message holding the rule's refusal text;
  *   - `refused <text>`: it is refused, the message holding `text`.
  *
  * Every refusal is the reader's own (`ParquetFileRefusals.problems`): an
  * `IllegalArgumentException`, or an `ArithmeticException` for a value past
  * what the engine holds, whose message names the file first and shows none
  * of parquet-mr's words. A file that reads where its line says it is
  * refused fails too, so a refusal held as a finding cannot outlive its fix.
  *
  * Each file is read within [[TimeLimitSeconds]], at the batch capacity of
  * the run (`--config=batch1` reads every file a row at a time).
  */
class ParquetTestingTest {
  import ParquetTestingTest._

  @Test def everyFileHasOneLineAndEveryLineAFile(): Unit = {
    val listed = manifest.map(_.file)
    assertEquals("files with more than one line", Vector.empty, listed.diff(listed.distinct))
    val onDisk = parquetFiles(Root)
    assertEquals("files with no line", Vector.empty, onDisk.filterNot(listed.toSet))
    assertEquals("lines with no file", Vector.empty, listed.filterNot(onDisk.toSet))
    manifest.collect { case Line(f, Reads(_, expect), _) if !Files.isRegularFile(Root.resolve(expect)) => f }
      .foreach(f => fail(s"$f: its expect file is not there"))
  }

  @Test def everyFileIsReadOrRefusedAsItsLineSays(): Unit = {
    val started = System.nanoTime
    val problems = manifest.flatMap(l => check(l).map(p => s"MANIFEST:${l.number}: ${l.file}: $p"))
    val tally = manifest.groupBy(l => l.outcome match {
      case _: Reads => "reads"
      case _: RefusedByDesign => "refused by design"
      case _: Refused => "refused"
    }).map { case (k, v) => s"$k ${v.length}" }.toSeq.sorted
    println(s"[parquet-testing] ${manifest.length} files in ${(System.nanoTime - started) / 1000000} ms: " +
      tally.mkString(", "))
    assertTrue(problems.mkString("\n"), problems.isEmpty)
  }

  /** What is wrong with `l`'s file against its line, if anything. */
  private def check(l: Line): Option[String] = {
    val path = Root.resolve(l.file)
    withTimeLimit(read(path)) match {
      case None => Some(s"not read or refused within $TimeLimitSeconds s")
      case Some(Right(r)) => l.outcome match {
        case Reads(rows, expect) =>
          if (r.rows != rows) Some(s"read ${r.rows} rows, where its line says $rows")
          else if (r.count != rows) Some(s"COUNT(*) is ${r.count}, where a scan reads $rows rows")
          else differs(r.text, Root.resolve(expect))
        case _ => Some(s"read ${r.rows} rows, where its line says it is refused")
      }
      case Some(Left(e)) => l.outcome match {
        case Reads(_, _) => Some(s"refused, where its line says it reads: ${e.getClass.getName}: ${e.getMessage}")
        case RefusedByDesign(id) =>
          ParquetFileRefusals.problems(path, e).orElse(ledger.rule(id) match {
            case None => Some(s"the ledger has no rule '$id'; the nearest is '${ledger.nearest(id)}'")
            case Some(rule) if !rule.refuses => Some(s"'$id' is no refusal's rule")
            case Some(rule) if !e.getMessage.contains(rule.refusal.get) =>
              Some(s"refused without '${rule.refusal.get}', the text of '$id': ${e.getMessage}")
            case _ => None
          })
        case Refused(text) =>
          ParquetFileRefusals.problems(path, e).orElse(
            if (e.getMessage.contains(text)) None else Some(s"refused without '$text': ${e.getMessage}"))
      }
    }
  }
}

object ParquetTestingTest {

  /** Where the files are: the runfiles tree under Bazel. */
  val Root: Path = Paths.get("src/test/data/parquet-testing")

  val TimeLimitSeconds: Long = 60L

  sealed trait Outcome
  final case class Reads(rows: Long, expect: String) extends Outcome
  final case class RefusedByDesign(id: String) extends Outcome
  final case class Refused(text: String) extends Outcome

  final case class Line(file: String, outcome: Outcome, number: Int)

  /** A manifest's lines; a `#` starts a comment, and a blank line is none. */
  def parse(text: String): Vector[Line] =
    text.split("\n", -1).toVector.zipWithIndex.flatMap { case (raw, i) =>
      val l = raw.takeWhile(_ != '#').trim
      if (l.isEmpty) None
      else Some(l.split("\\s+", 3) match {
        case Array(f, "reads", rest) => rest.split("\\s+") match {
          case Array(n, expect) if n.forall(_.isDigit) => Line(f, Reads(n.toLong, expect), i + 1)
          case _ => throw new IllegalArgumentException(s"MANIFEST:${i + 1}: 'reads' takes a row count and an expect file")
        }
        case Array(f, "refused", rest) =>
          rest.split("\\s+") match {
            case Array("by-design", id) => Line(f, RefusedByDesign(id), i + 1)
            case _ => Line(f, Refused(rest), i + 1)
          }
        case _ => throw new IllegalArgumentException(s"MANIFEST:${i + 1}: a line is '<file> reads ...' or '<file> refused ...'")
      })
    }

  lazy val manifest: Vector[Line] =
    parse(new String(Files.readAllBytes(Root.resolve(Ledger.ManifestName)), StandardCharsets.UTF_8))

  lazy val ledger: Ledger = Ledger.load(Ledger.DefaultPath)

  /** The parquet files under `dir`, by their paths from it. */
  def parquetFiles(dir: Path): Vector[String] = {
    val walk = Files.walk(dir)
    try walk.iterator.asScala.filter(Files.isRegularFile(_)).map(p => dir.relativize(p).toString.replace('\\', '/'))
      .filter(n => n.endsWith(".parquet") || n.endsWith(".parquet.encrypted")).filterNot(_.startsWith("expect/"))
      .toVector.sorted
    finally walk.close()
  }

  /** What reading a file gave: its rows, `COUNT(*)`, and the CSV text of
    * `SELECT *`, as `transformer query --format csv` prints it. */
  final case class Read(rows: Long, count: Long, text: String)

  def read(path: Path): Read = {
    val catalog = new Catalog
    catalog.register("t", ParquetReader.fromPath(path.toString))
    val q = SqlEngine.execute("SELECT * FROM t", catalog, ExecutionOptions.Default)
    val out = new StringWriter
    val writer = new CsvRowWriter(out, q.schema, CsvWriteOptions(), s"the reading of $path")
    var rows = 0L
    q.batches.foreach { b => writer.write(b); rows += b.numRows }
    val count = SqlEngine.execute("SELECT COUNT(*) AS n FROM t", catalog, ExecutionOptions.Default).batches
      .collectFirst { case b if b.numRows > 0 => b.column(0).getBoxed(0).asInstanceOf[Long] }.getOrElse(-1L)
    Read(rows, count, out.toString)
  }

  /** Whether `text` differs from what `expect` holds: the same text for an
    * expect file of this repo's (gzipped when its name ends `.gz`), the same
    * values for upstream's `*_expect.csv`, whose header is not compared. */
  def differs(text: String, expect: Path): Option[String] = {
    val bytes = Files.readAllBytes(expect)
    val want = new String(
      if (expect.toString.endsWith(".gz")) new GZIPInputStream(new ByteArrayInputStream(bytes)).readAllBytes() else bytes,
      StandardCharsets.UTF_8)
    if (expect.getFileName.toString.endsWith("_expect.csv")) {
      val (got, wanted) = (cells(text).drop(1), cells(want).drop(1))
      if (got.length != wanted.length) Some(s"${got.length} rows, where ${expect.getFileName} has ${wanted.length}")
      else got.indices.find(i => got(i) != wanted(i)).map(i =>
        s"row ${i + 1} is ${got(i).mkString(",")}, where ${expect.getFileName} has ${wanted(i).mkString(",")}")
    } else if (text == want) None
    else {
      val (a, b) = (text.split("\n", -1), want.split("\n", -1))
      val i = a.indices.find(k => k >= b.length || a(k) != b(k)).getOrElse(a.length)
      Some(s"its CSV text differs from ${expect.getFileName} at line ${i + 1}: " +
        s"'${a.lift(i).getOrElse("")}', where it has '${b.lift(i).getOrElse("")}'")
    }
  }

  /** CSV text as rows of fields: a quoted field is its text, an empty field
    * that is not quoted NULL (None), as both the engine's writer and
    * upstream's expect files write them. */
  def cells(text: String): Vector[Vector[Option[String]]] = {
    val rows = Vector.newBuilder[Vector[Option[String]]]
    val row = Vector.newBuilder[Option[String]]
    val field = new StringBuilder
    var quoted, inQuotes, any = false
    def endField(): Unit = {
      row += (if (quoted || field.nonEmpty) Some(field.result()) else None)
      field.clear(); quoted = false
    }
    var i = 0
    while (i < text.length) {
      val c = text.charAt(i)
      if (inQuotes) {
        if (c == '"' && i + 1 < text.length && text.charAt(i + 1) == '"') { field += '"'; i += 1 }
        else if (c == '"') inQuotes = false
        else field += c
      } else c match {
        case '"' => inQuotes = true; quoted = true; any = true
        case ',' => endField(); any = true
        case '\n' => endField(); rows += row.result(); row.clear(); any = false
        case '\r' => ()
        case _ => field += c; any = true
      }
      i += 1
    }
    if (any) { endField(); rows += row.result() }
    rows.result()
  }

  /** `body`'s value or what it threw, or None when it took past
    * [[TimeLimitSeconds]]. */
  def withTimeLimit[A](body: => A): Option[Either[Throwable, A]] = {
    val pool = Executors.newSingleThreadExecutor(new ThreadFactory {
      def newThread(r: Runnable): Thread = { val t = new Thread(r, "parquet-testing-read"); t.setDaemon(true); t }
    })
    try {
      val f = pool.submit(new Callable[Either[Throwable, A]] {
        def call(): Either[Throwable, A] = try Right(body) catch { case e: Throwable if NonFatal(e) => Left(e) }
      })
      try Some(f.get(TimeLimitSeconds, TimeUnit.SECONDS))
      catch { case _: TimeoutException => f.cancel(true); None }
    } finally pool.shutdownNow()
  }
}

/** The rule every refusal of a parquet file keeps. */
object ParquetFileRefusals {

  /** What a library's failure shows: its classes, its code, Thrift's dump of
    * a struct. */
  val LibraryWords: Seq[String] = Seq("org.apache", "shaded.", "java.", "Exception", "Cannot invoke", "Struct:")

  /** What is wrong with `thrown` as the refusal of `file`: anything but an
    * `IllegalArgumentException` (or an `ArithmeticException`, a value past
    * what the engine holds) whose message starts `Parquet file '<file>'`, the
    * path the reader was given, which a glob may have made absolute, and
    * holds, outside the names it quotes from the file, none of
    * [[LibraryWords]], no bare `null` and none of the clean-rejection rule's
    * internal markers. */
  def problems(file: Path, thrown: Throwable): Option[String] = {
    val msg = thrown.getMessage
    val unquoted = if (msg == null) "" else msg.replaceAll("'[^']*'", "''")
    if (!thrown.isInstanceOf[IllegalArgumentException] && !thrown.isInstanceOf[ArithmeticException])
      Some(s"refused with an internal exception class, ${thrown.getClass.getName}: $msg")
    else if (msg == null || msg.trim.isEmpty) Some("refused with no message")
    else if (!msg.startsWith("Parquet file '") || !msg.drop("Parquet file '".length).takeWhile(_ != '\'').endsWith(file.toString))
      Some(s"the refusal does not name the file first: $msg")
    else LibraryWords.find(unquoted.contains).map(w => s"the refusal shows '$w': $msg")
      .orElse(if ("""\bnull\b""".r.findFirstIn(unquoted).isDefined) Some(s"the refusal holds a bare null: $msg") else None)
      .orElse(CleanRejection.leaks("", unquoted).headOption.map(l => s"the refusal carries $l: $msg"))
  }
}
