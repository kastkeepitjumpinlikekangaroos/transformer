package com.transformer.read.csv

import com.transformer.core.{ColumnarBatch, DataType, Field, Schema}
import com.transformer.fuzz.oracle.CleanRejection
import com.transformer.job.{Json, JsonArray, JsonNull, JsonObject, JsonString, JsonValue}
import com.transformer.slt.Ledger
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

/** CSV files and cases other programs wrote, each held to its line of
  * `src/test/data/csv/MANIFEST` (README.md there says where each came from
  * and how its line was made).
  *
  * A file of its own (DuckDB's) is read with the default options:
  *
  *   - `reads <rows> <expect>`: it reads `rows` rows, whose CSV text is the
  *     expect file's;
  *   - `refused by-design <id>` / `refused <text>`: it is refused, cleanly,
  *     naming the file, with the ledger rule's refusal text or `text`.
  *
  * A case is a text with another parser's reading of it beside it (`.json`:
  * the options, and the rows PapaParse or csv-spectrum read), read with the
  * case's options:
  *
  *   - `agrees`: the engine reads the other parser's rows, an unquoted empty
  *     field excepted only where the line says so;
  *   - `differs by-design <id> <expect>`: it reads rows of its own under the
  *     ledger's rule `id`, the CSV text of which is the expect file's, and they
  *     are not the other parser's;
  *   - `refused ...`: as for a file.
  *
  * A writer case (a `.json` under `unparse/`: rows, the writer's options and
  * PapaParse's text) is `writes agrees` or `writes differs by-design <id>
  * <expect>`: the engine's writer writes the rows as that text.
  *
  * Every refusal is an `IllegalArgumentException` naming the file first
  * (`CSV file '<path>'`) and showing no internal marker, and each read ends
  * within [[TimeLimitSeconds]].
  */
class CsvCorpusTest {
  import CsvCorpusTest._

  @Test def everyFileHasOneLineAndEveryLineAFile(): Unit = {
    val listed = manifest.map(_.file)
    assertEquals("inputs with more than one line", Vector.empty, listed.diff(listed.distinct))
    val onDisk = inputs(Root)
    assertEquals("inputs with no line", Vector.empty, onDisk.filterNot(listed.toSet))
    assertEquals("lines with no input", Vector.empty, listed.filterNot(onDisk.toSet))
  }

  @Test def everyInputIsReadOrRefusedAsItsLineSays(): Unit = {
    val started = System.nanoTime
    val problems = manifest.flatMap { l =>
      val p = try check(l) catch { case e: Throwable if NonFatal(e) => Some(s"the check threw ${e.getClass.getName}: ${e.getMessage}") }
      p.map(why => s"MANIFEST:${l.number}: ${l.file}: $why")
    }
    val tally = manifest.groupBy(_.outcome.kind).map { case (k, v) => s"$k ${v.length}" }.toSeq.sorted
    println(s"[csv-corpus] ${manifest.length} inputs in ${(System.nanoTime - started) / 1000000} ms: " +
      tally.mkString(", "))
    assertTrue(problems.mkString("\n"), problems.isEmpty)
  }

  /** With `CSV_CORPUS_REPORT` set, prints what the engine does with every
    * input, one JSON object a line, from which a manifest line and its expect
    * file are made (README.md there); otherwise does nothing. */
  @Test def reportWhatTheEngineDoes(): Unit = if (sys.env.contains("CSV_CORPUS_REPORT")) {
    inputs(Root).foreach { f =>
      val path = Root.resolve(f)
      def j(s: String) = "\"" + Json.escape(s) + "\""
      if (f.contains("/unparse/")) println("CSVCORPUS " + j(f))
      else {
        val other = otherReading(path)
        val line = withTimeLimit(read(path, other.map(_.options))) match {
          case None => s"{\"file\":${j(f)},\"timeout\":true}"
          case Some(Left(e)) => s"{\"file\":${j(f)},\"refused\":${j(String.valueOf(e.getMessage))}," +
            s"\"clean\":${CsvFileRefusals.problems(path, e).isEmpty}}"
          case Some(Right(r)) => s"{\"file\":${j(f)},\"rows\":${r.rows.length},\"text\":${j(r.text)}," +
            s"\"agrees\":${other.map(_.matches(r).toString).getOrElse("null")}," +
            s"\"shown\":${j(show(r.rows))},\"theirs\":${j(other.map(_.shown).getOrElse(""))}}"
        }
        println("CSVCORPUS " + line)
      }
    }
  }

  private def check(l: Line): Option[String] = {
    val path = Root.resolve(l.file)
    l.outcome match {
      case Writes(how) => writerCase(path, how)
      case outcome =>
        val other = otherReading(path)
        withTimeLimit(read(path, other.map(_.options))) match {
          case None => Some(s"not read or refused within $TimeLimitSeconds s")
          case Some(Left(e)) => outcome match {
            case RefusedByDesign(id) => CsvFileRefusals.problems(path, e).orElse(ruleText(id, e))
            case Refused(text) => CsvFileRefusals.problems(path, e)
              .orElse(if (e.getMessage.contains(text)) None else Some(s"refused without '$text': ${e.getMessage}"))
            case _ => Some(s"refused, where its line says it reads: ${e.getClass.getName}: ${e.getMessage}")
          }
          case Some(Right(r)) => outcome match {
            case Reads(rows, expect) =>
              if (r.rows.length != rows) Some(s"read ${r.rows.length} rows, where its line says $rows")
              else differs(r.text, Root.resolve(expect))
            case Agrees =>
              other match {
                case None => Some("its line says it agrees, and no other parser's reading is beside it")
                case Some(o) => if (o.matches(r)) None else Some(s"read ${show(r.rows)}, where ${o.who} reads ${o.shown}")
              }
            case Differs(id, expect) =>
              if (ledger.rule(id).isEmpty) Some(s"the ledger has no rule '$id'; the nearest is '${ledger.nearest(id)}'")
              else if (other.exists(_.matches(r))) Some(s"reads as the other parser does, where its line says it differs by $id")
              else differs(r.text, Root.resolve(expect))
            case _ => Some(s"read ${r.rows.length} rows, where its line says it is refused")
          }
        }
    }
  }

  private def ruleText(id: String, e: Throwable): Option[String] = ledger.rule(id) match {
    case None => Some(s"the ledger has no rule '$id'; the nearest is '${ledger.nearest(id)}'")
    case Some(rule) if !rule.refuses => Some(s"'$id' is no refusal's rule")
    case Some(rule) if !e.getMessage.contains(rule.refusal.get) =>
      Some(s"refused without '${rule.refusal.get}', the text of '$id': ${e.getMessage}")
    case _ => None
  }

  /** A writer case: the engine's writer writes the case's rows as its line
    * says. */
  private def writerCase(path: Path, how: Outcome): Option[String] = {
    val c = Json.parse(text(path)).asObject(path.toString)
    val options = stringMap(c.get("options"))
    val fields = c.get("fields").collect { case JsonArray(vs) => vs.map(_.stringValue) }
    val rows = c.get("rows").collect { case JsonArray(rs) => rs.map(cells) }.getOrElse(Seq.empty)
    val width = fields.map(_.length).orElse(rows.headOption.map(_.length)).getOrElse(0)
    val schema = Schema(fields.getOrElse((0 until width).map(i => s"c$i")).map(n => Field(n, DataType.StringType)).toVector)
    val batch = new ColumnarBatch(schema, math.max(1, rows.length))
    rows.zipWithIndex.foreach { case (row, r) =>
      row.zipWithIndex.foreach { case (v, col) => v match {
        case Some(s) => batch.column(col).setBoxed(r, s)
        case None => batch.column(col).setNull(r)
      }}
    }
    batch.setNumRows(rows.length)
    val out = new StringWriter
    val writer = new CsvRowWriter(out, schema, CsvWriteOptions.fromMap(options), path.toString)
    writer.write(batch)
    val written = out.toString
    val theirs = c.get("text").map(_.stringValue).getOrElse("")
    // A file the writer writes ends its last line; PapaParse's text does not.
    val agrees = written == theirs || written == theirs + options.getOrElse("lineEnding", "\n")
    how match {
      case Agrees => if (agrees) None else Some(s"writes ${quote(written)}, where PapaParse writes ${quote(theirs)}")
      case Differs(id, expect) =>
        if (ledger.rule(id).isEmpty) Some(s"the ledger has no rule '$id'")
        else if (agrees) Some(s"writes as PapaParse does, where its line says it differs by $id")
        else differs(written, Root.resolve(expect))
      case other => Some(s"a writer case is 'writes agrees' or 'writes differs ...', not $other")
    }
  }
}

object CsvCorpusTest {

  val Root: Path = Paths.get("src/test/data/csv")

  val TimeLimitSeconds: Long = 60L

  sealed trait Outcome { def kind: String }
  final case class Reads(rows: Int, expect: String) extends Outcome { def kind = "reads" }
  final case class RefusedByDesign(id: String) extends Outcome { def kind = "refused by design" }
  final case class Refused(text: String) extends Outcome { def kind = "refused" }
  case object Agrees extends Outcome { def kind = "agrees" }
  final case class Differs(id: String, expect: String) extends Outcome { def kind = "differs by design" }
  final case class Writes(how: Outcome) extends Outcome { def kind = s"writes, ${how.kind}" }

  final case class Line(file: String, outcome: Outcome, number: Int)

  def parse(text: String): Vector[Line] =
    text.split("\n", -1).toVector.zipWithIndex.flatMap { case (raw, i) =>
      val l = raw.takeWhile(_ != '#').trim
      def bad(why: String) = throw new IllegalArgumentException(s"MANIFEST:${i + 1}: $why")
      def outcome(words: Seq[String], rest: String): Outcome = words match {
        case Seq("reads", n, expect) if n.forall(_.isDigit) => Reads(n.toInt, expect)
        case Seq("refused", "by-design", id) => RefusedByDesign(id)
        case "refused" +: _ => Refused(rest.stripPrefix("refused").trim)
        case Seq("agrees") => Agrees
        case Seq("differs", "by-design", id, expect) => Differs(id, expect)
        case _ => bad(s"no outcome reads '$rest'")
      }
      if (l.isEmpty) None
      else {
        val (file, rest) = l.span(_ != ' ')
        val body = rest.trim
        val words = body.split("\\s+").toSeq
        Some(Line(file, words match {
          case "writes" +: more => Writes(outcome(more, body.stripPrefix("writes").trim))
          case _ => outcome(words, body)
        }, i + 1))
      }
    }

  lazy val manifest: Vector[Line] = parse(text(Root.resolve(Ledger.ManifestName)))

  lazy val ledger: Ledger = Ledger.load(Ledger.DefaultPath)

  def text(p: Path): String = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  /** Every input under `dir`: each file but a case's `.json` reading, a
    * README, a licence, the manifest, the expect files, and the scripts that
    * made the cases and what they left out. */
  def inputs(dir: Path): Vector[String] = {
    val walk = Files.walk(dir)
    try walk.iterator.asScala.filter(Files.isRegularFile(_)).map(p => dir.relativize(p).toString.replace('\\', '/'))
      .filterNot(n => n.startsWith("expect/") || n == Ledger.ManifestName || n == "BUILD.bazel" ||
        n.endsWith("README.md") || n.endsWith("LICENSE") || n.endsWith("LICENSE.txt") || n.endsWith("left-out.json") ||
        n.endsWith(".py"))
      .filterNot(n => n.endsWith(".json") && !n.contains("/unparse/") && !n.startsWith("csv-spectrum/json/") &&
        Files.isRegularFile(dir.resolve(n.stripSuffix(".json") + ".csv")))
      .filterNot(n => n.startsWith("csv-spectrum/json/"))
      .toVector.sorted
    finally walk.close()
  }

  /** What reading gave: the rows, each value's CSV text (None for NULL), and
    * the CSV text of them as the writer writes it, header first. */
  final case class Read(names: Vector[String], rows: Vector[Vector[Option[String]]], text: String)

  def read(path: Path, caseOptions: Option[Map[String, String]]): Read = {
    val reader = caseOptions match {
      case None => CsvReader.fromPath(path.toString, CsvOptions())
      case Some(raw) => CsvReader.fromPath(path.toString, caseReading(path, raw))
    }
    val out = new StringWriter
    val writer = new CsvRowWriter(out, reader.schema, CsvWriteOptions(), path.toString)
    val rows = Vector.newBuilder[Vector[Option[String]]]
    (0 until reader.numPartitions).foreach { p =>
      reader.readPartition(p).foreach { b =>
        writer.write(b)
        var r = 0
        while (r < b.numRows) {
          rows += Vector.tabulate(b.schema.length)(c =>
            if (b.column(c).isNull(r)) None else Some(CsvValues.format(b.column(c), r)))
          r += 1
        }
      }
    }
    Read(reader.schema.fieldNames.toVector, rows.result(), out.toString)
  }

  /** A case's options: with no header, every column a STRING, as many as
    * the other parser's first row has, so a value is read as the text it is;
    * with a header, the types are inferred, as the engine reads an input. */
  def caseReading(path: Path, raw: Map[String, String]): CsvOptions = {
    val base = CsvOptions.fromMap(raw - "width")
    if (base.header) base
    else {
      val width = raw.get("width").map(_.toInt).getOrElse(1)
      base.copy(inferSchema = false, columns = Some((0 until width).map(i => Field(s"c$i", DataType.StringType))))
    }
  }

  /** The other parser's reading of a case: its rows, as text, by position
    * or, read with a header, by column name (the JSON parser keeps no order
    * of an object's keys), and the options the case is read with. */
  final case class Other(who: String, options: Map[String, String], rows: Vector[Vector[Option[String]]],
      keyed: Vector[Map[String, Option[String]]]) {
    /** Whether the engine's reading `r` is this one, value for value. */
    def matches(r: Read): Boolean =
      if (keyed.nonEmpty || options.get("header").forall(_ == "true") && rows.isEmpty)
        r.rows.map(row => r.names.zip(row).toMap) == keyed
      else r.rows == rows
    def shown: String =
      if (keyed.nonEmpty) keyed.map(_.toSeq.sortBy(_._1).map { case (k, v) => s"$k=${v.fold("NULL")(quote)}" }
        .mkString("(", ", ", ")")).mkString("[", ", ", "]")
      else show(rows)
  }

  /** The other parser's reading beside `path`, if one is: PapaParse's
    * `.json`, or csv-spectrum's `json/<name>.json` beside `csvs/<name>.csv`. */
  def otherReading(path: Path): Option[Other] = {
    val name = path.getFileName.toString
    val beside = path.resolveSibling(name.stripSuffix(".csv") + ".json")
    val spectrum = path.getParent.resolveSibling("json").resolve(name.stripSuffix(".csv") + ".json")
    if (name.endsWith(".csv") && Files.isRegularFile(beside)) {
      val c = Json.parse(text(beside)).asObject(beside.toString)
      val options = stringMap(c.get("options"))
      val data = c.get("rows").collect { case JsonArray(rs) => rs.toVector }.getOrElse(Vector.empty)
      val header = options.get("header").contains("true")
      val rows = data.collect { case v if !v.isInstanceOf[JsonObject] => cells(v).toVector }
      val keyed = data.collect { case o: JsonObject => o.fields.map { case (k, v) => k -> cell(v) } }
      val width = if (header) Map.empty[String, String] else Map("width" -> rows.headOption.map(_.length).getOrElse(1).toString)
      Some(Other("PapaParse", options ++ width, rows, keyed))
    } else if (path.toString.contains("csv-spectrum/csvs/") && Files.isRegularFile(spectrum)) {
      val data = Json.parse(text(spectrum)) match { case JsonArray(rs) => rs.toVector; case _ => Vector.empty }
      Some(Other("csv-spectrum", Map.empty, Vector.empty,
        data.collect { case o: JsonObject => o.fields.map { case (k, v) => k -> cell(v) } }))
    } else None
  }

  def stringMap(v: Option[JsonValue]): Map[String, String] = v match {
    case Some(o: JsonObject) => o.fields.map { case (k, x) => k -> x.stringValue }
    case _ => Map.empty
  }

  /** A value of the other parser's rows as text: a list of the fields past
    * a header's (PapaParse's `__parsed_extra`) as its JSON. */
  def cell(v: JsonValue): Option[String] = v match {
    case JsonNull => None
    case JsonString(s) => Some(s)
    case JsonArray(vs) => Some(vs.map(x => cell(x).fold("null")(t => "\"" + Json.escape(t) + "\"")).mkString("[", ",", "]"))
    case o: JsonObject => Some(o.toString)
    case other => Some(other.stringValue)
  }

  def cells(v: JsonValue): Seq[Option[String]] = v match {
    case JsonArray(vs) => vs.map(cell)
    case other => Seq(cell(other))
  }

  def show(rows: Vector[Vector[Option[String]]]): String =
    rows.map(_.map(_.fold("NULL")(quote)).mkString("(", ", ", ")")).mkString("[", ", ", "]")

  def quote(s: String): String = "'" + s.flatMap {
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c => c.toString
  } + "'"

  /** Whether `got` differs from the expect file's text (gzipped when its
    * name ends `.gz`). */
  def differs(got: String, expect: Path): Option[String] = {
    if (!Files.isRegularFile(expect)) return Some(s"its expect file ${Root.relativize(expect)} is not there")
    val bytes = Files.readAllBytes(expect)
    val want = new String(
      if (expect.toString.endsWith(".gz")) new GZIPInputStream(new ByteArrayInputStream(bytes)).readAllBytes() else bytes,
      StandardCharsets.UTF_8)
    if (got == want) None
    else {
      val (a, b) = (got.split("\n", -1), want.split("\n", -1))
      val i = a.indices.find(k => k >= b.length || a(k) != b(k)).getOrElse(a.length)
      Some(s"its text differs from ${expect.getFileName} at line ${i + 1}: " +
        s"${quote(a.lift(i).getOrElse(""))}, where it has ${quote(b.lift(i).getOrElse(""))}")
    }
  }

  def withTimeLimit[A](body: => A): Option[Either[Throwable, A]] = {
    val pool = Executors.newSingleThreadExecutor(new ThreadFactory {
      def newThread(r: Runnable): Thread = { val t = new Thread(r, "csv-corpus-read"); t.setDaemon(true); t }
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

/** The rule every refusal of a CSV file keeps. */
object CsvFileRefusals {

  /** What is wrong with `thrown` as the refusal of `file`: anything but an
    * `IllegalArgumentException` whose message starts `CSV file '<file>'`
    * (the path as the reader was given it, which a glob may have made
    * absolute) and holds, outside what it quotes from the file, no internal
    * marker of the clean-rejection rule and no bare `null`. */
  def problems(file: Path, thrown: Throwable): Option[String] = {
    val msg = thrown.getMessage
    val unquoted = if (msg == null) "" else msg.replaceAll("'[^']*'", "''")
    if (!thrown.isInstanceOf[IllegalArgumentException])
      Some(s"refused with an internal exception class, ${thrown.getClass.getName}: $msg")
    else if (msg == null || msg.trim.isEmpty) Some("refused with no message")
    else if (!msg.startsWith("CSV file '") || !msg.drop("CSV file '".length).takeWhile(_ != '\'').endsWith(file.toString))
      Some(s"the refusal does not name the file first: $msg")
    else if ("""\bnull\b""".r.findFirstIn(unquoted).isDefined) Some(s"the refusal holds a bare null: $msg")
    else CleanRejection.leaks("", unquoted).headOption.map(l => s"the refusal carries $l: $msg")
  }
}
