package com.transformer.slt

import com.transformer.core._
import com.transformer.fuzz.{AgreedGen, PortableSql, Rng}
import com.transformer.fuzz.AgreedGen.Feature
import com.transformer.fuzz.PortableSql.{Engine, Shape, Variant}
import com.transformer.slt.Script.{Column, SortMode}
import com.transformer.sql.plan.LogicalBuilder

import java.io.ByteArrayOutputStream
import java.math.{BigDecimal => JBigDecimal, MathContext, RoundingMode}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{Callable, Executors, TimeUnit}
import scala.collection.mutable
import scala.util.control.NonFatal

/** Writes the corpus of answers PostgreSQL and DuckDB agree on
  * (plans/testing/14): `src/test/slt/agreed/<feature>.test`, a script a
  * feature of [[AgreedGen.Feature]], each record a query the fuzz generators
  * drew ([[AgreedGen]]), printed in the SQL the three read alike
  * ([[PortableSql]]), whose answer both references gave and the engine gives
  * too, under every verification mode. `slt_test` then runs the scripts as
  * it runs every other.
  *
  * {{{
  *   bazel run //src/test/scala/com/transformer/slt:agreed_corpus -- \
  *       --seeds 0:20000 --pg-port 54329 [--pg-host 127.0.0.1] [--features window,decimal] \
  *       [--threads 6] [--out src/test/slt/agreed] [--report <file>] [--dry-run]
  * }}}
  *
  * It runs `psql` and `duckdb` from `PATH` as subprocesses (`--psql` and
  * `--duckdb` name others): the references are never on a classpath. A
  * Postgres session per case makes its tables `TEMP`, and a DuckDB process
  * per query holds them in memory with `SET integer_division = true`, so
  * `INT / INT` truncates there as in Postgres and the engine. The cluster
  * runs with `--locale=C --encoding=UTF8` and `TimeZone = 'UTC'`.
  *
  * Each query of a case lands in one of:
  *
  *   - '''kept''': both references give one answer, by the rules of
  *     [[Normalize]], and the engine gives it too, in the baseline and every
  *     verification mode;
  *   - '''by design''': both agree and the engine differs by a rule of the
  *     ledger in `docs/gotchas.md`: refused in the rule's words, or a
  *     `DECIMAL` the engine rounds to the type its rules give
  *     (`decimal-result-type`), which Postgres confirms by computing the
  *     query with each such `DECIMAL` rounded so ([[Variant.EngineTyped]]).
  *     Written `skipif transformer <id>`, a value's with its twin;
  *   - '''held''': a finding already reported and not yet decided
  *     ([[Held]]), not written;
  *   - '''finding''': both agree and the engine differs, refuses, fails or
  *     disagrees with itself across modes, by no rule; reported with its
  *     case and a repro whose rows are cut down, not written;
  *   - '''references disagree''': dropped, counted by why, samples reported;
  *   - '''both error''': written `statement error` where the engine refuses
  *     cleanly too;
  *   - '''outside''': the renderer refused it ([[PortableSql.Outside]]).
  *
  * A feature takes cases in seed order until it keeps its quota; cases run
  * in parallel and are written in order, so two runs over one checkout write
  * the same bytes.
  */
object AgreedCorpus {

  // ---- options -----------------------------------------------------------------

  final case class Options(
      seedsFrom: Int = 0,
      seedsTo: Int = 20000,
      pgHost: String = "127.0.0.1",
      pgPort: Int = 54329,
      pgUser: String = "postgres",
      psql: String = "psql",
      duckdb: String = "duckdb",
      out: Path = resolve("src/test/slt/agreed"),
      features: Seq[Feature] = Feature.All,
      threads: Int = 6,
      report: Option[Path] = None,
      dryRun: Boolean = false,
      batch: Int = 24)

  def main(args: Array[String]): Unit = {
    val options = parse(args.toList, Options())
    val ledger = Ledger.load(resolve(Ledger.DefaultPath.toString))
    val tool = new AgreedCorpus(options, ledger)
    val report = tool.run()
    options.report match {
      case Some(path) =>
        Files.write(path, report.getBytes(StandardCharsets.UTF_8))
        System.out.println(s"report written to $path")
      case None => System.out.println(report)
    }
  }

  private def parse(args: List[String], o: Options): Options = args match {
    case Nil => o
    case "--seeds" :: range :: rest =>
      val Array(a, b) = range.split(":")
      parse(rest, o.copy(seedsFrom = a.toInt, seedsTo = b.toInt))
    case "--pg-host" :: h :: rest => parse(rest, o.copy(pgHost = h))
    case "--pg-port" :: p :: rest => parse(rest, o.copy(pgPort = p.toInt))
    case "--pg-user" :: u :: rest => parse(rest, o.copy(pgUser = u))
    case "--psql" :: p :: rest => parse(rest, o.copy(psql = p))
    case "--duckdb" :: d :: rest => parse(rest, o.copy(duckdb = d))
    case "--out" :: d :: rest => parse(rest, o.copy(out = resolve(d)))
    case "--features" :: fs :: rest =>
      parse(rest, o.copy(features = fs.split(",").toSeq.map(f =>
        Feature.byId(f).getOrElse(throw new IllegalArgumentException(s"no feature $f: ${Feature.All.map(_.id).mkString(", ")}")))))
    case "--threads" :: n :: rest => parse(rest, o.copy(threads = n.toInt))
    case "--report" :: f :: rest => parse(rest, o.copy(report = Some(resolve(f))))
    case "--dry-run" :: rest => parse(rest, o.copy(dryRun = true))
    case "--batch" :: n :: rest => parse(rest, o.copy(batch = n.toInt))
    case other :: _ => throw new IllegalArgumentException(s"unknown option $other")
  }

  /** A path as given, or under `bazel run` against the workspace. */
  private def resolve(p: String): Path = {
    val path = Paths.get(p)
    if (path.isAbsolute) path
    else Option(System.getenv("BUILD_WORKSPACE_DIRECTORY")).map(w => Paths.get(w).resolve(p)).getOrElse(path)
  }

  /** The text a reference prints for NULL, which no generated value holds. */
  private val NullMarker = "#NULL#"

  /** A script's line naming its feature, which W6's guard reads. */
  val FeatureLine: String = "# feature: "

  // ---- normalization (W4) --------------------------------------------------------

  /** The rules by which the two references' answers count as one, each named
    * in every script's header. */
  object Normalize {
    val Rules: Seq[(String, String)] = Seq(
      "by-value" -> "NULL, BOOLEAN (t, f, true, false), INT and BIGINT compare by value, whatever integer type each reference gives",
      "decimal-exact" -> "a DECIMAL compares by value and prints at the engine's scale, kept where that is the references' value, or for a quotient or an average the exact quotient rounded half away from zero",
      "decimal-from-double" -> "where DuckDB computes a DECIMAL in DOUBLE (a quotient, an average), its value agrees to 15 significant digits and the digits are Postgres's",
      "double-shortest-or-15" -> "a DOUBLE agrees exactly when both give the same shortest value, else at 15 significant digits, written T, else not at all",
      "float-exact" -> "a FLOAT only in comparisons, ordering and projections, compared exactly",
      "text-exact" -> "text compares exactly, in code point order (Postgres's C collation)",
      "temporal-by-value" -> "a DATE and a TIMESTAMP (Postgres's timestamp without time zone, in UTC) compare as values",
      "binary-bytes" -> "a BINARY compares by its bytes (Postgres's \\x hex, DuckDB's escapes)",
      "special-floats" -> "NaN, the infinities and -0.0 read as values: Postgres's NaN, Infinity, -0 and DuckDB's nan, inf, -0.0",
      "row-order" -> "rowsort unless the query orders totally (nosort); a window whose answer depends on row order orders by a unique key")

    /** A reference's text as a value of the engine's type `t`, or why it is
      * none. */
    def read(text: String, t: DataType): Either[String, Any] =
      if (text == NullMarker) Right(null)
      else try {
        t match {
          case DataType.IntType => integral(text).filter(v => v.bitLength < 32).map(v => Integer.valueOf(v.intValue)).toRight(s"$text is no INT")
          case DataType.LongType => integral(text).filter(v => v.bitLength < 64).map(v => java.lang.Long.valueOf(v.longValue)).toRight(s"$text is no BIGINT")
          case DataType.FloatType => floating(text).map(d => java.lang.Float.valueOf(d.toFloat)).toRight(s"$text is no FLOAT")
          case DataType.DoubleType => floating(text).map(d => java.lang.Double.valueOf(d)).toRight(s"$text is no DOUBLE")
          case DataType.DecimalType(_, scale) =>
            // By value, at the engine's scale where the value has one there.
            decimal(text).map(d => try d.setScale(scale, RoundingMode.UNNECESSARY) catch { case _: ArithmeticException => d })
              .toRight(s"$text is no DECIMAL")
          case DataType.StringType => Right(text)
          case DataType.BooleanType => text match {
            case "t" | "true" => Right(java.lang.Boolean.TRUE)
            case "f" | "false" => Right(java.lang.Boolean.FALSE)
            case other => Left(s"$other is no BOOLEAN")
          }
          case DataType.DateType => Right(java.time.LocalDate.parse(text))
          case DataType.TimestampType =>
            Right(java.time.LocalDateTime.parse(text.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC))
          case DataType.BinaryType => bytes(text).toRight(s"$text is no BINARY")
          case DataType.NullType => Left(s"$text is not NULL")
          case other => Left(s"no reading of $other")
        }
      } catch { case NonFatal(e) => Left(s"$text: ${e.getMessage}") }

    private def decimal(text: String): Option[JBigDecimal] =
      try Some(new JBigDecimal(text)) catch { case _: NumberFormatException => None }

    private def integral(text: String): Option[java.math.BigInteger] =
      decimal(text).flatMap(d => try Some(d.toBigIntegerExact) catch { case _: ArithmeticException => None })

    def floating(text: String): Option[Double] = text match {
      case "NaN" | "nan" | "-nan" => Some(Double.NaN)
      case "Infinity" | "inf" => Some(Double.PositiveInfinity)
      case "-Infinity" | "-inf" => Some(Double.NegativeInfinity)
      case other => try Some(java.lang.Double.parseDouble(other)) catch { case _: NumberFormatException => None }
    }

    /** Postgres's `\x0182`, or DuckDB's escapes (`\x01\x82`, a printable
      * byte as itself). */
    def bytes(text: String): Option[Array[Byte]] =
      if (text.startsWith("\\x") && text.length % 2 == 0 && text.drop(2).forall(c => Character.digit(c, 16) >= 0) &&
          !text.drop(2).contains('\\'))
        Some(text.drop(2).grouped(2).map(h => Integer.parseInt(h, 16).toByte).toArray)
      else {
        val out = new ByteArrayOutputStream
        var i = 0
        while (i < text.length) {
          if (text.startsWith("\\x", i) && i + 4 <= text.length) {
            out.write(Integer.parseInt(text.substring(i + 2, i + 4), 16)); i += 4
          } else { out.write(text.charAt(i).toInt); i += 1 }
        }
        Some(out.toByteArray)
      }

    /** Whether two doubles are the same shortest value (NaN NaN). */
    def sameDouble(a: Any, b: Any): Boolean = (a, b) match {
      case (x: java.lang.Double, y: java.lang.Double) => x.equals(y)
      case _ => a == b
    }

    def fifteen(d: Double): String = Values.format(java.lang.Double.valueOf(d), Column.Text)

    /** A number rounded to 15 significant digits, for a DuckDB `DOUBLE`
      * against Postgres's exact `DECIMAL`. */
    def fifteenDigits(d: JBigDecimal): JBigDecimal = d.round(new MathContext(15, RoundingMode.HALF_EVEN)).stripTrailingZeros
  }

  // ---- the references' outputs ---------------------------------------------------------

  /** A result as a reference printed it: its column names and rows of
    * printed values. */
  final case class Csv(header: Vector[String], rows: Vector[Vector[String]])

  /** RFC 4180 as psql and DuckDB write it, every record ended by a line
    * break: a row of one empty string is an empty line. */
  def parseCsv(text: String): Csv = {
    val records = Vector.newBuilder[Vector[String]]
    var fields = Vector.newBuilder[String]
    val cur = new StringBuilder
    var i = 0
    var quoted = false
    var any = false
    def endField(): Unit = { fields += cur.result(); cur.clear() }
    def endRecord(): Unit = { endField(); records += fields.result(); fields = Vector.newBuilder[String]; any = false }
    while (i < text.length) {
      val c = text.charAt(i)
      if (quoted) {
        if (c == '"') {
          if (i + 1 < text.length && text.charAt(i + 1) == '"') { cur += '"'; i += 1 }
          else quoted = false
        } else cur += c
      } else c match {
        case '"' => quoted = true; any = true
        case ',' => endField(); any = true
        case '\r' => ()
        case '\n' => endRecord()
        case other => cur += other; any = true
      }
      i += 1
    }
    if (any || cur.nonEmpty) endRecord()
    val all = records.result()
    if (all.isEmpty) Csv(Vector.empty, Vector.empty) else Csv(all.head, all.tail)
  }

  /** What a reference made of one query: its result and, from DuckDB, each
    * column's type, or its error. */
  final case class RefResult(result: Either[String, Csv], types: Vector[String] = Vector.empty)

  /** What became of one query of a case. */
  sealed trait Verdict { def label: String }
  final case class OutsideV(reason: String) extends Verdict { def label = "outside" }
  final case class Disagree(kind: String, detail: String) extends Verdict { def label = "references disagree" }
  final case class Kept(record: String) extends Verdict { def label = "kept" }
  final case class BothError(record: String) extends Verdict { def label = "both error" }
  final case class ByDesign(rule: String, record: String) extends Verdict { def label = "by design" }
  final case class HeldV(id: String) extends Verdict { def label = "held" }
  final case class FindingV(kind: String, detail: String, repro: String) extends Verdict { def label = "finding" }

  final case class QueryResult(sql: String, verdict: Verdict)
  final case class CaseResult(feature: Feature, index: Int, block: String, queries: Vector[QueryResult]) {
    def kept: Int = queries.count(_.verdict.isInstanceOf[Kept]) + queries.count(_.verdict.isInstanceOf[BothError])
  }

  final case class FeatureRun(feature: Feature, cases: Vector[CaseResult], drawn: Int, millis: Long) {
    def kept: Int = cases.map(_.kept).sum
  }

}

final class AgreedCorpus(options: AgreedCorpus.Options, ledger: Ledger) {

  import AgreedCorpus._

  // ---- one case ------------------------------------------------------------------

  private def seedOf(feature: Feature, index: Int): Long =
    (feature.id.hashCode.toLong << 32) ^ (index.toLong * 0x9E3779B97F4A7C15L)

  def caseOf(feature: Feature, index: Int): AgreedGen.Case = AgreedGen.generate(feature, new Rng(seedOf(feature, index)))

  /** Run one case through the three engines and sort each query. */
  def runCase(feature: Feature, index: Int): CaseResult = {
    val c = caseOf(feature, index)
    val tables = c.tables
    def setup(engine: Engine): Vector[String] = tables.flatMap { t =>
      PortableSql.createTable(t.name, t.data.schema, engine) +: PortableSql.insert(t.name, t.data.schema, t.data.rows, engine).toVector
    }
    val engineSetup = setup(Engine.Transformer)
    val rendered: Vector[(Shape, Either[String, String])] = c.queries.map { shape =>
      shape -> (try Right(PortableSql.query(shape)) catch { case o: PortableSql.Outside => Left(o.reason) })
    }
    val db = new Database(Dialect.Transformer, Files.createTempDirectory("agreed-"))
    val views = new Modes.Views
    try {
      engineSetup.foreach(s => db.execute(s, sql => baseline(db, views, sql).fold(e => throw e._1, identity)))
      val runnable = rendered.collect { case (shape, Right(sql)) => (shape, sql) }
      // Each query's engine-typed variants for Postgres: with each DECIMAL
      // rounded to the engine's type, and with that and the rules of the held
      // findings whose constructs it uses written in too.
      val variants: Map[String, (Option[String], Option[(String, Set[String])])] = runnable.map { case (shape, sql) =>
        val types = engineTypes(shape, db, views).getOrElse(Map.empty[AnyRef, DataType.DecimalType])
        val held = PortableSql.heldIn(shape)
        val decimalVariant = if (types.nonEmpty) Some(PortableSql.query(shape, Variant.EngineTyped(types))) else None
        val heldVariant = if (held.nonEmpty) Some(PortableSql.query(shape, Variant.EngineTyped(types, held)) -> held) else None
        sql -> (decimalVariant, heldVariant)
      }.toMap
      val pgQueries = runnable.flatMap { case (_, sql) =>
        val (d, h) = variants(sql)
        sql +: (d.toVector ++ h.map(_._1).toVector)
      }.distinct
      val pg = runPostgres(setup(Engine.Postgres), pgQueries)
      val verdicts = rendered.map {
        case (_, Left(reason)) => (reason, OutsideV(reason): Verdict)
        case (shape, Right(sql)) =>
          val duck = runDuckDb(setup(Engine.DuckDb), sql)
          val (d, h) = variants(sql)
          val verdict = triage(shape, sql, pg(sql), duck, d.map(pg), h.map { case (q, ids) => (pg(q), ids) },
            baseline(db, views, sql), tables)
          (sql, verdict)
      }
      val checked = verify(feature, index, engineSetup, tables, verdicts)
      CaseResult(feature, index, block(feature, index, engineSetup, tables, checked), checked.map { case (s, v) => QueryResult(s, v) })
    } finally views.close()
  }

  // ---- the engine ---------------------------------------------------------------------

  /** The engine's answer in the baseline, or its failure and whether it
    * came before the first row (a refusal). */
  private def baseline(db: Database, views: Modes.Views, sql: String): Either[(Throwable, Boolean), (Schema, Vector[Array[Any]])] = {
    db.refreshViews(body => baseline(db, views, body).fold(e => throw e._1, identity))
    val q =
      try Modes.execute(sql, views.catalog(db.allTables, Modes.Baseline.layout), Modes.Baseline)
      catch { case NonFatal(e) => return Left((e, true)) }
    try Right((q.schema, Runner.drain(q)))
    catch { case NonFatal(e) => Left((e, false)) }
  }

  /** The type the engine gives each node of the query that may compute a
    * `DECIMAL`, from binding it alone; None when one does not bind. */
  private def engineTypes(shape: Shape, db: Database, views: Modes.Views): Option[Map[AnyRef, DataType.DecimalType]] =
    try {
      val catalog = views.catalog(db.allTables, Modes.Layout.Single)
      Some(PortableSql.typingProbes(shape).flatMap { case (key, sql) =>
        LogicalBuilder.build(sql, catalog).main.outputSchema.fields.head.dataType match {
          case d: DataType.DecimalType => Some(key -> d)
          case _ => None
        }
      }.toMap)
    } catch { case NonFatal(_) => None }

  // ---- the references ---------------------------------------------------------------

  /** Every query of a case in one Postgres session: its tables made, then
    * each query between markers, and whether it failed and why from psql's
    * own variables. */
  private def runPostgres(setup: Seq[String], queries: Seq[String]): Map[String, RefResult] = {
    val script = new StringBuilder
    script ++= "SET client_min_messages = warning;\nSET statement_timeout = '30s';\nSET TimeZone = 'UTC';\n"
    setup.foreach(s => script ++= s ++= ";\n")
    script ++= "\\echo #@#SETUP :ERROR\n"
    queries.zipWithIndex.foreach { case (q, i) =>
      script ++= s"\\echo #@#Q $i\n$q;\n\\echo #@#E :ERROR\n\\echo #@#M :LAST_ERROR_MESSAGE\n"
    }
    val (_, out, err) = exec(Seq(options.psql, "-X", "-A", "--csv", "-q", "-P", s"null=$NullMarker", "-h", options.pgHost,
      "-p", options.pgPort.toString, "-U", options.pgUser, "-d", "postgres", "-f", "-"), script.result(), Map("PGCLIENTENCODING" -> "UTF8"))
    val lines = out.split("\n", -1).toVector
    val setupLine = lines.find(_.startsWith("#@#SETUP"))
    if (!setupLine.contains("#@#SETUP false"))
      return queries.map(q => q -> RefResult(Left(s"the tables were not made: ${err.trim.take(300)}"))).toMap
    queries.zipWithIndex.map { case (q, i) =>
      val start = lines.indexOf(s"#@#Q $i")
      val end = lines.indexWhere(_.startsWith("#@#E "), start + 1)
      val result =
        if (start < 0 || end < 0) Left(s"psql printed no result: ${err.trim.take(300)}")
        else if (lines(end) == "#@#E true") Left(lines.lift(end + 1).getOrElse("").stripPrefix("#@#M "))
        else Right(parseCsv(lines.slice(start + 1, end).mkString("", "\n", "\n")))
      q -> RefResult(result)
    }.toMap
  }

  /** One query in a DuckDB process of its own, over its tables in memory,
    * and the types `DESCRIBE` gives its columns. */
  private def runDuckDb(setup: Seq[String], query: String): RefResult = {
    val script = new StringBuilder
    script ++= "SET integer_division = true;\nSET threads = 1;\n"
    setup.foreach(s => script ++= s ++= ";\n")
    script ++= s".print #@#QUERY\n$query;\n.print #@#TYPES\nDESCRIBE $query;\n"
    val (code, out, err) = exec(Seq(options.duckdb, "-no-agent", "-csv", "-nullvalue", NullMarker, "-bail"), script.result(), Map.empty)
    val lines = out.split("\n", -1).toVector
    val start = lines.indexOf("#@#QUERY")
    val typesAt = lines.indexOf("#@#TYPES")
    if (start < 0) RefResult(Left(s"the tables were not made: ${err.trim.take(300)}"))
    else if (typesAt < 0) RefResult(Left(firstLine(err)))
    else {
      val csv = parseCsv(lines.slice(start + 1, typesAt).mkString("", "\n", "\n"))
      val types = if (code == 0) parseCsv(lines.drop(typesAt + 1).mkString("\n")).rows.map(_.lift(1).getOrElse("")) else Vector.empty
      RefResult(Right(csv), types)
    }
  }

  private def firstLine(s: String): String = s.trim.linesIterator.nextOption().getOrElse("").trim

  /** Run a reference's client with `input` on its stdin: its exit code,
    * stdout and stderr. The agent environment variables DuckDB reads are
    * dropped, so it prints as a terminal would see it. */
  private def exec(command: Seq[String], input: String, env: Map[String, String]): (Int, String, String) = {
    val pb = new ProcessBuilder(command: _*)
    pb.environment().remove("AI_AGENT")
    pb.environment().remove("CLAUDECODE")
    env.foreach { case (k, v) => pb.environment().put(k, v) }
    val p = pb.start()
    val errBuf = new ByteArrayOutputStream
    val errReader = new Thread(() => { val _ = p.getErrorStream.transferTo(errBuf) })
    errReader.start()
    val outBuf = new ByteArrayOutputStream
    val outReader = new Thread(() => { val _ = p.getInputStream.transferTo(outBuf) })
    outReader.start()
    try {
      p.getOutputStream.write(input.getBytes(StandardCharsets.UTF_8))
      p.getOutputStream.close()
    } catch { case NonFatal(_) => () }
    if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); p.waitFor() }
    outReader.join()
    errReader.join()
    (p.exitValue(), new String(outBuf.toByteArray, StandardCharsets.UTF_8), new String(errBuf.toByteArray, StandardCharsets.UTF_8))
  }

  // ---- triage --------------------------------------------------------------------

  /** A result printed as a record's columns print it, in the order its sort
    * mode compares. */
  private def printed(rows: Seq[Array[Any]], columns: Seq[Column], sort: SortMode): Vector[Vector[String]] =
    Values.arrangeRows(Values.formatRows(rows, columns), sort)

  /** A reference's rows read into the engine's types, or why one is none. */
  private def typedRows(csv: Csv, schema: Schema): Either[String, Vector[Array[Any]]] = {
    if (csv.header.length != schema.length)
      return Left(s"${csv.header.length} columns where the engine has ${schema.length}")
    val out = Vector.newBuilder[Array[Any]]
    csv.rows.foreach { r =>
      if (r.length != schema.length) return Left(s"a row of ${r.length} values")
      out += r.indices.map(c => Normalize.read(r(c), schema.fields(c).dataType) match {
        case Right(v) => v
        case Left(why) => return Left(s"column ${c + 1}: $why")
      }).toArray[Any]
    }
    Right(out.result())
  }

  /** The two references' answers as one, if they agree by [[Normalize]]'s
    * rules: Postgres's rows read into the engine's types, and the columns a
    * record prints them as (a DOUBLE agreeing only to 15 digits as `T`). */
  private def agree(pgRows: Vector[Array[Any]], duck: RefResult, duckRows: Vector[Array[Any]], schema: Schema,
      sort: SortMode): Either[String, Vector[Column]] = {
    val exact: Vector[Column] = schema.fields.map(f => Column.Exact(f.dataType)).toVector
    if (pgRows.length != duckRows.length) return Left(s"${pgRows.length} rows from Postgres, ${duckRows.length} from DuckDB")
    if (printed(pgRows, exact, sort) == printed(duckRows, exact, sort)) return Right(exact)
    // Where DuckDB computed a DECIMAL in DOUBLE (decimal-from-double), the
    // two compare at 15 significant digits and the record prints
    // Postgres's; where a DOUBLE differs past its 15th digit
    // (double-shortest-or-15), the record prints it as T.
    val duckDouble = schema.fields.indices.map(c => schema.fields(c).dataType.isInstanceOf[DataType.DecimalType] &&
      duck.types.lift(c).exists(t => t == "DOUBLE" || t == "FLOAT"))
    def key(rows: Vector[Array[Any]], doublesAsText: Boolean): Vector[Vector[String]] = {
      val cols = schema.fields.indices.map { c =>
        if (duckDouble(c) || (doublesAsText && schema.fields(c).dataType == DataType.DoubleType)) Column.Text else exact(c)
      }
      printed(rows.map(r => r.indices.map { c =>
        r(c) match {
          case d: JBigDecimal if duckDouble(c) => Normalize.fifteenDigits(d)
          case v => v
        }
      }.toArray[Any]), cols, sort)
    }
    if (key(pgRows, doublesAsText = false) == key(duckRows, doublesAsText = false)) Right(exact)
    else if (key(pgRows, doublesAsText = true) == key(duckRows, doublesAsText = true))
      Right(schema.fields.map(f => if (f.dataType == DataType.DoubleType) Column.Text else Column.Exact(f.dataType)).toVector)
    else {
      val a = printed(pgRows, exact, sort)
      val b = printed(duckRows, exact, sort)
      val i = a.indices.find(k => k >= b.length || a(k) != b(k)).getOrElse(0)
      Left(s"Postgres [${a.lift(i).map(_.mkString(" | ")).getOrElse("")}], DuckDB [${b.lift(i).map(_.mkString(" | ")).getOrElse("")}]")
    }
  }

  /** The two references' rows as text each normalizes alike, for a query
    * whose answer the engine's types cannot read (it refused it, say):
    * booleans, NaN and the infinities in one spelling, a number as its value
    * (to 15 digits where DuckDB's column is a float), a BLOB as hex. */
  private def generic(csv: Csv, duckTypes: Vector[String], sort: SortMode): Vector[Vector[String]] = {
    val rows = csv.rows.map(r => r.indices.map { c =>
      val t = duckTypes.lift(c).getOrElse("")
      r(c) match {
        case NullMarker => "NULL"
        case "t" | "true" => "true"
        case "f" | "false" => "false"
        case v if t == "BLOB" => Normalize.bytes(v).map(_.map(b => f"${b & 0xff}%02x").mkString).getOrElse(v)
        case v => Normalize.floating(v) match {
          case Some(d) if d.isNaN || d.isInfinite => d.toString
          case Some(_) if t == "DOUBLE" || t == "FLOAT" => Normalize.fifteenDigits(new JBigDecimal(v)).toPlainString
          case Some(_) if v.forall(ch => ch.isDigit || ch == '.' || ch == '-' || ch == 'e' || ch == 'E' || ch == '+') =>
            new JBigDecimal(v).stripTrailingZeros.toPlainString
          case _ => v
        }
      }
    }.toVector)
    if (sort == SortMode.RowSort) rows.sortBy(_.mkString("\u0001")) else rows
  }

  private def triage(shape: Shape, sql: String, pg: RefResult, duck: RefResult, variant: Option[RefResult],
      heldVariant: Option[(RefResult, Set[String])],
      engine: Either[(Throwable, Boolean), (Schema, Vector[Array[Any]])], tables: Vector[AgreedGen.Table]): Verdict = {
    val sort = if (PortableSql.ordersTotally(shape)) SortMode.NoSort else SortMode.RowSort
    def repro(engineSaid: String): String = reproText(tables, sql, pg, duck, engineSaid)
    // The engine's answer is what Postgres gives with the held findings'
    // rules written in: the record is held under them.
    def held(schema: Schema, rows: Vector[Array[Any]], columns: Seq[Column]): Option[Verdict] = heldVariant.flatMap {
      case (ref, ids) =>
        ref.result.toOption.flatMap(csv => typedRows(csv, schema).toOption)
          .filter(r => printed(r, columns, sort) == printed(rows, columns, sort))
          .map(_ => HeldV(ids.toSeq.sorted.mkString(", ")))
    }
    (pg.result, duck.result) match {
      case (Left(pe), Left(_)) => engine match {
        case Left((e, atPlan)) if Runner.clean(e, running = !atPlan) => BothError(s"statement error\n$sql")
        case Left((e, _)) => FindingV("internal error where both references fail", Runner.describe(e), repro(Runner.describe(e)))
        case Right(_) => FindingV("the engine answers where both references fail", pe, repro("an answer"))
      }
      case (Left(pe), Right(_)) => Disagree("Postgres refuses", pe)
      case (Right(_), Left(de)) => Disagree("DuckDB refuses", de)
      case (Right(pgCsv), Right(duckCsv)) => engine match {
        case Left((e, atPlan)) =>
          val pgText = generic(pgCsv, duck.types, sort)
          if (pgText != generic(duckCsv, duck.types, sort)) Disagree("values", "the two answer otherwise, and the engine " +
            (if (atPlan) "refuses" else "fails"))
          else {
            val message = Runner.message(e)
            if (atPlan && Runner.clean(e, running = false)) {
              ledger.rules.find(r => r.refusal.exists(message.contains)) match {
                case Some(rule) =>
                  val values = pgText.flatten
                  val block = if (values.length > 8 || pgText.exists(_.mkString("\t").trim.isEmpty)) Values.hashLine(values) else pgText.map(_.mkString("\t")).mkString("\n")
                  ByDesign(rule.id, s"skipif transformer ${rule.id}\nquery ${pgCsv.header.map(_ => "T").mkString} " +
                    s"${if (sort == SortMode.NoSort) "nosort" else "rowsort"}\n$sql\n----" + (if (block.isEmpty) "" else s"\n$block"))
                case None => FindingV("the engine refuses a query both answer", message, repro(Runner.describe(e)))
              }
            } else if (Runner.clean(e, running = !atPlan))
              FindingV("the engine fails on the data where both answer", message, repro(Runner.describe(e)))
            else FindingV("internal error", Runner.describe(e), repro(Runner.describe(e)))
          }
        case Right((schema, rows)) =>
          (typedRows(pgCsv, schema), typedRows(duckCsv, schema)) match {
            case (Right(pgRows), Right(duckRows)) =>
              agree(pgRows, duck, duckRows, schema, sort) match {
                case Left(why) => Disagree("values", why)
                case Right(columns) =>
                  val expected = printed(pgRows, columns, sort)
                  val got = printed(rows, columns, sort)
                  if (expected == got) Kept(queryRecord(sql, columns, sort, expected))
                  else decimalByDesign(shape, sql, schema, columns, sort, pgRows, rows, variant)
                    .orElse(doublePrecision(shape, sql, columns, sort, pgRows, rows))
                    .orElse(held(schema, rows, columns))
                    .getOrElse(FindingV("the engine answers otherwise",
                      Runner.diff(expected.flatten, got.flatten).getOrElse(""), repro(engineText(schema, rows, sort))))
              }
            case (pgTyped, duckTyped) =>
              val why = pgTyped.left.toOption.orElse(duckTyped.left.toOption).getOrElse("")
              if (generic(pgCsv, duck.types, sort) != generic(duckCsv, duck.types, sort)) Disagree("values", why)
              else FindingV("the references' answer is not of the engine's type", why, repro(engineText(schema, rows, sort)))
          }
      }
    }
  }


  /** The engine's answer where it differs from the references': a `DECIMAL`
    * it rounds to its type's scale, which Postgres confirms by computing the
    * query rounded so. Kept where the only difference is a quotient or an
    * average at the top of a column, which the engine rounds half away from
    * zero at its scale as its rule says; `decimal-result-type`, with the
    * engine's answer as the twin, where it rounded inside the query. */
  private def decimalByDesign(shape: Shape, sql: String, schema: Schema, columns: Vector[Column], sort: SortMode,
      pgRows: Vector[Array[Any]], rows: Vector[Array[Any]], variant: Option[RefResult]): Option[Verdict] = {
    val engineTyped = variant.flatMap(_.result.toOption).flatMap(csv => typedRows(csv, schema).toOption).getOrElse(return None)
    val got = printed(rows, columns, sort)
    if (printed(engineTyped, columns, sort) != got) return None
    val quotients = PortableSql.quotientColumns(shape)
    val roundedAtTop = pgRows.length == engineTyped.length && {
      val a = Values.arrangeRows(pgRows.map(r => r.indices.map(c => Values.format(r(c), Column.Exact(schema.fields(c).dataType))).toVector), sort)
      val b = Values.arrangeRows(engineTyped.map(r => r.indices.map(c => Values.format(r(c), Column.Exact(schema.fields(c).dataType))).toVector), sort)
      a.indices.forall(i => a(i).indices.forall { c =>
        a(i)(c) == b(i)(c) || (quotients(c) && (schema.fields(c).dataType match {
          case DataType.DecimalType(_, s) =>
            Normalize.read(a(i)(c), schema.fields(c).dataType).toOption.collect { case d: JBigDecimal => d.setScale(s, RoundingMode.HALF_UP).toPlainString }
              .contains(b(i)(c))
          case _ => false
        }))
      })
    }
    if (roundedAtTop) Some(Kept(queryRecord(sql, columns, sort, got)))
    else {
      val rule = "decimal-result-type"
      val theirs = printed(pgRows, columns, sort)
      Some(ByDesign(rule, s"skipif transformer $rule\n${queryRecord(sql, columns, sort, theirs)}\n\n" +
        s"onlyif transformer $rule\n${queryRecord(sql, columns, sort, got)}"))
    }
  }

  /** A statistic the engine computes in DOUBLE (Welford's update, Chan's
    * merge) whose last digits differ from the value both references give, the
    * one nearest the exact answer, and agree to 15 significant digits:
    * `double-precision`, its twin printing each DOUBLE as `T`, the 15 digits
    * every layout agrees on. */
  private def doublePrecision(shape: Shape, sql: String, columns: Vector[Column], sort: SortMode,
      pgRows: Vector[Array[Any]], rows: Vector[Array[Any]]): Option[Verdict] =
    if (!PortableSql.usesStatistic(shape)) None
    else {
      val loose = columns.map {
        case Column.Exact(DataType.DoubleType) => Column.Text
        case other => other
      }
      if (loose == columns || printed(pgRows, loose, sort) != printed(rows, loose, sort)) None
      else {
        val rule = "double-precision"
        Some(ByDesign(rule, s"skipif transformer $rule\n${queryRecord(sql, columns, sort, printed(pgRows, columns, sort))}\n\n" +
          s"onlyif transformer $rule\n${queryRecord(sql, loose, sort, printed(rows, loose, sort))}"))
      }
    }

  private def columnsText(columns: Seq[Column]): String = columns.map {
    case Column.Exact(t) => Script.typeName(t)
    case Column.Text => "T"
    case Column.Integer => "I"
    case Column.Real => "R"
    case Column.Letter(c) => c.toString
  }.mkString(",")

  /** A query record: its columns, sort mode, SQL and expected block, hashed
    * past eight values (`hash-threshold 8`). */
  private def queryRecord(sql: String, columns: Seq[Column], sort: SortMode, rows: Vector[Vector[String]]): String = {
    val values = rows.flatten
    val mode = sort match {
      case SortMode.NoSort => "nosort"
      case SortMode.RowSort => "rowsort"
      case SortMode.ValueSort => "valuesort"
    }
    // A row printed as a blank line (a string of spaces alone) would end
    // the block, so such a result is hashed whatever its size.
    val lines = rows.map(_.mkString("\t"))
    val block = if (values.length > 8 || lines.exists(_.trim.isEmpty)) Values.hashLine(values) else lines.mkString("\n")
    s"query ${columnsText(columns)} $mode\n$sql\n----" + (if (block.isEmpty) "" else s"\n$block")
  }

  private def engineText(schema: Schema, rows: Vector[Array[Any]], sort: SortMode): String =
    printed(rows, schema.fields.map(f => Column.Exact(f.dataType)), sort).map(_.mkString(" | ")).take(12).mkString("; ")

  private def refText(r: RefResult): String = r.result match {
    case Left(e) => s"error: $e"
    case Right(csv) => csv.rows.map(_.mkString(" | ")).take(12).mkString("; ")
  }

  private def reproText(tables: Vector[AgreedGen.Table], sql: String, pg: RefResult, duck: RefResult, engine: String): String = {
    val setup = tables.flatMap(t => PortableSql.createTable(t.name, t.data.schema, Engine.Transformer) +:
      PortableSql.insert(t.name, t.data.schema, t.data.rows, Engine.Transformer).toVector)
    s"${setup.mkString(";\n")};\n$sql;\n-- Postgres: ${refText(pg)}\n-- DuckDB: ${refText(duck)}\n-- engine: $engine"
  }

  // ---- the records, under every mode -----------------------------------------------------

  /** Each vendorable record of the case run as `slt_test` runs it, under the
    * baseline and every verification mode: a kept one must pass, a skip must
    * still diverge as its rule says and its twin pass. One that does not is
    * a finding (the engine disagrees with itself across modes). */
  private def verify(feature: Feature, index: Int, setup: Vector[String], tables: Vector[AgreedGen.Table],
      verdicts: Vector[(String, Verdict)]): Vector[(String, Verdict)] = {
    val vendored = verdicts.zipWithIndex.collect {
      case ((_, v @ (_: Kept | _: BothError | _: ByDesign)), i) => i -> recordOf(v)
    }
    if (vendored.isEmpty) return verdicts
    val text = new StringBuilder
    setup.foreach(s => text ++= s"statement ok\n$s\n\n")
    val starts = mutable.HashMap.empty[Int, Int]
    vendored.foreach { case (i, rec) =>
      starts(i) = text.count(_ == '\n') + 1
      text ++= rec ++= "\n\n"
    }
    val name = s"agreed/${feature.id}.test#case-$index"
    val records = Script.parse(name, text.result(), ledger)
    val result = new Runner(Dialect.Transformer, ledger, Modes.Verification).run(name, records, Files.createTempDirectory("agreed-"))
    val outcomes = result.results.map(r => r.record.line -> r.outcome).toMap
    verdicts.zipWithIndex.map { case ((sql, v), i) =>
      starts.get(i) match {
        case None => (sql, v)
        case Some(line) =>
          // The record's lines, and the twin's after a skip.
          val mine = outcomes.filter { case (l, _) => l >= line && l < line + recordOf(v).count(_ == '\n') + 1 }.values.toVector
          val ok = v match {
            case _: ByDesign => mine.exists { case Runner.Skipped(_, Some(_)) => true; case _ => false } &&
              mine.forall { case Runner.Passed | Runner.Skipped(_, Some(_)) => true; case _ => false }
            case _ => mine.nonEmpty && mine.forall(_ == Runner.Passed)
          }
          if (ok) (sql, v)
          else {
            val why = mine.collectFirst { case o if o != Runner.Passed && !o.isInstanceOf[Runner.Skipped] => Runner.show(o) }
              .getOrElse("the record did not run")
            (sql, FindingV("the record fails under the runner", why,
              reproText(tables, sql, RefResult(Left("(see the record)")), RefResult(Left("(see the record)")), why)))
          }
      }
    }
  }

  private def recordOf(v: Verdict): String = v match {
    case Kept(r) => r
    case BothError(r) => r
    case ByDesign(_, r) => r
    case other => throw new IllegalArgumentException(s"no record for $other")
  }

  /** The case as its script holds it: its tables made, its vendored records,
    * its tables dropped; empty when none of its queries is vendored. */
  private def block(feature: Feature, index: Int, setup: Vector[String], tables: Vector[AgreedGen.Table],
      verdicts: Vector[(String, Verdict)]): String = {
    val records = verdicts.collect { case (_, v @ (_: Kept | _: BothError | _: ByDesign)) => recordOf(v) }
    if (records.isEmpty) ""
    else {
      val sb = new StringBuilder
      sb ++= s"# ${feature.id} case $index\n"
      setup.foreach(s => sb ++= s"statement ok\n$s\n\n")
      records.foreach(r => sb ++= r ++= "\n\n")
      tables.foreach(t => sb ++= s"statement ok\nDROP TABLE ${t.name}\n\n")
      sb.result()
    }
  }

  // ---- a feature at a time ---------------------------------------------------------------

  def run(): String = {
    val pgVersion = postgresVersion()
    val duckVersion = duckDbVersion()
    val commit = toolCommit()
    val runs = options.features.map(runFeature)
    if (!options.dryRun) {
      Files.createDirectories(options.out)
      runs.foreach(r => Files.write(options.out.resolve(s"${r.feature.id}.test"),
        script(r, commit, pgVersion, duckVersion).getBytes(StandardCharsets.UTF_8)))
    }
    reportText(runs, commit, pgVersion, duckVersion)
  }

  private def runFeature(feature: Feature): FeatureRun = {
    val started = System.nanoTime()
    val pool = Executors.newFixedThreadPool(options.threads)
    val taken = Vector.newBuilder[CaseResult]
    var kept = 0
    var next = options.seedsFrom
    try {
      while (kept < feature.quota && next < options.seedsTo) {
        val batch = (next until math.min(options.seedsTo, next + options.batch)).toVector
        next += batch.length
        val futures = batch.map(i => pool.submit(new Callable[CaseResult] { def call(): CaseResult = runCase(feature, i) }))
        futures.foreach { f =>
          val c = f.get()
          if (kept < feature.quota) {
            taken += c
            kept += c.kept
          }
        }
        System.err.println(s"${feature.id}: ${kept}/${feature.quota} kept after case ${next - 1}")
      }
    } finally {
      pool.shutdownNow()
      pool.awaitTermination(1, TimeUnit.MINUTES)
    }
    val cases = taken.result()
    FeatureRun(feature, cases, cases.length, (System.nanoTime() - started) / 1000000)
  }

  private def script(r: FeatureRun, commit: String, pg: String, duck: String): String = {
    val verdicts = r.cases.flatMap(_.queries.map(_.verdict))
    val byDesign = verdicts.collect { case ByDesign(rule, _) => rule }.groupMapReduce(identity)(_ => 1)(_ + _)
    val header = Seq(
      "# The corpus of answers PostgreSQL and DuckDB agree on (plans/testing/14), written by",
      "# //src/test/scala/com/transformer/slt:agreed_corpus: regenerate it rather than edit it",
      "# (docs/testing.md, \"The agreed corpus\"). Each record's query was drawn by the fuzz",
      "# generators and run in Postgres, DuckDB and the engine; its answer is the references'.",
      s"$FeatureLine${r.feature.id}",
      s"# covers: ${r.feature.title}",
      s"# quota: ${r.feature.quota} records kept",
      s"# seeds: cases ${options.seedsFrom} to ${options.seedsFrom + r.drawn - 1} of ${options.seedsFrom}:${options.seedsTo}",
      s"# tool commit: $commit",
      s"# references: $pg; $duck",
      s"# normalization: ${Normalize.Rules.map(_._1).mkString(", ")}",
      s"# records: ${verdicts.count(_.isInstanceOf[Kept])} kept, ${verdicts.count(_.isInstanceOf[BothError])} errors both " +
        s"references and the engine raise, ${byDesign.values.sum} by design" +
        (if (byDesign.isEmpty) "" else byDesign.toSeq.sorted.map { case (k, n) => s"$k $n" }.mkString(" (", ", ", ")")))
    (header :+ "" :+ "hash-threshold 8" :+ "" :+ "").mkString("\n") + r.cases.map(_.block).filter(_.nonEmpty).mkString
  }

  // ---- versions and the report ---------------------------------------------------------

  private def postgresVersion(): String = {
    val (_, out, _) = exec(Seq(options.psql, "-X", "-A", "-t", "-h", options.pgHost, "-p", options.pgPort.toString,
      "-U", options.pgUser, "-d", "postgres", "-c", "SHOW server_version"), "", Map.empty)
    s"PostgreSQL ${out.trim.split("\\s+").headOption.getOrElse("?")}"
  }

  private def duckDbVersion(): String = {
    val (_, out, _) = exec(Seq(options.duckdb, "--version"), "", Map.empty)
    val words = out.trim.split("\\s+").toVector
    s"DuckDB ${words.headOption.getOrElse("?")}${words.lastOption.filter(_ => words.length > 1).map(w => s" ($w)").getOrElse("")}"
  }

  private def toolCommit(): String = {
    val dir = Option(System.getenv("BUILD_WORKSPACE_DIRECTORY")).getOrElse(".")
    val (_, sha, _) = exec(Seq("git", "-C", dir, "rev-parse", "--short=7", "HEAD"), "", Map.empty)
    val (_, status, _) = exec(Seq("git", "-C", dir, "status", "--porcelain", "--untracked-files=no"), "", Map.empty)
    sha.trim + (if (status.trim.nonEmpty) " (with uncommitted changes)" else "")
  }

  private def reportText(runs: Seq[FeatureRun], commit: String, pg: String, duck: String): String = {
    val sb = new StringBuilder
    sb ++= s"agreed corpus: seeds ${options.seedsFrom}:${options.seedsTo}, tool $commit, $pg, $duck\n\n"
    runs.foreach { r =>
      val qs = r.cases.flatMap(_.queries)
      val vs = qs.map(_.verdict)
      sb ++= s"== ${r.feature.id} (quota ${r.feature.quota}): ${r.drawn} cases, ${qs.length} queries, ${r.millis / 1000} s\n"
      vs.groupMapReduce(_.label)(_ => 1)(_ + _).toSeq.sortBy(_._1).foreach { case (k, n) => sb ++= s"  $k: $n\n" }
      def tally(title: String, keys: Seq[String]): Unit = if (keys.nonEmpty) {
        sb ++= s"  $title:\n"
        keys.groupMapReduce(identity)(_ => 1)(_ + _).toSeq.sortBy(-_._2).foreach { case (k, n) => sb ++= s"    $n  $k\n" }
      }
      tally("outside, by reason", vs.collect { case OutsideV(reason) => reason })
      tally("references disagree, by kind", vs.collect { case Disagree(k, _) => k })
      tally("by design, by rule", vs.collect { case ByDesign(rule, _) => rule })
      tally("held, by finding", vs.collect { case HeldV(id) => id })
      tally("findings, by kind", vs.collect { case FindingV(k, _, _) => k })
      val disagreements = qs.collect { case QueryResult(sql, Disagree(k, d)) => s"[$k] $d\n      $sql" }
      if (disagreements.nonEmpty) {
        sb ++= "  references disagree, samples:\n"
        disagreements.groupBy(_.takeWhile(_ != ']')).values.flatMap(_.take(6)).foreach(d => sb ++= s"    $d\n")
      }
      val findings = r.cases.flatMap(c => c.queries.collect { case QueryResult(_, f: FindingV) => (c.index, f) })
      findings.foreach { case (i, f) =>
        sb ++= s"  FINDING (case $i) ${f.kind}: ${f.detail.take(400)}\n"
        sb ++= f.repro.linesIterator.map(l => s"      $l\n").mkString
      }
      sb ++= "\n"
    }
    sb.result()
  }
}
