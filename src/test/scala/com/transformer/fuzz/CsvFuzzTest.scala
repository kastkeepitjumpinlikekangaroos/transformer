package com.transformer.fuzz

import com.transformer.core.{DataType, Field, Schema}
import com.transformer.fuzz.CsvTextGen.{Blank, DataCase, InferenceCase, Malformed, Row, TextCase}
import com.transformer.fuzz.oracle.{CsvRoundTrip, InferenceRoundTrip}
import org.junit.Assert._
import org.junit.Test

import scala.collection.mutable

/** Property-based gate over the CSV reader and writer, which every other
  * fuzzer reaches only through in-memory views.
  *
  * Three properties ([[CsvRoundTrip]], [[InferenceRoundTrip]]):
  *
  *   - '''data round trip''': what `CsvWriter` writes under any option bag,
  *     read back with `CsvOptions.forWrittenFile` (how a job re-reads a task's
  *     output), is what it wrote, every type and every value of it; a value
  *     the charset cannot hold fails the write by name;
  *   - '''text''': CSV text written here, with its fields known, reads as
  *     those fields through the parser and the reader, and malformed text
  *     fails naming its file and line;
  *   - '''inference''': read with types inferred, each column takes the
  *     narrowest type its sample reads as, every value is what its text says,
  *     a later value that does not fit fails by name, and a second and third
  *     pass agree.
  *
  * Found before it landed (each now pinned by `csv_row_parser_test`,
  * `csv_reader_test`, `csv_writer_test` or `data_job_test`): a NULL in a
  * one-column table lost its row, an empty string came back NULL, a BINARY
  * could not be read back at all, the writer did not escape a custom escape,
  * a delimiter past ASCII crashed it, an unclosed quote swallowed the rest of
  * a file, inference typed `1.5` and `true` as a BOOLEAN that read 1.5 as
  * false, and a later BOOLEAN read `yes` as false.
  *
  * Budgets are small (each case writes and reads real files) and scale with
  * `-Dfuzz.seeds=N` / `FUZZ_SEEDS=N` against `csv_fuzz_campaign`.
  */
class CsvFuzzTest {

  private val DefaultCsvSeeds: Int = 300

  @Test def fuzzDataRoundTrip(): Unit =
    Props.forAll[DataCase]("csv-data-round-trip", CsvTextGen.dataCase, Shrinker.csvDataCase,
      count = Props.seedCountOr(DefaultCsvSeeds)) { c => CsvRoundTrip.checkData(c); () }

  @Test def fuzzText(): Unit =
    Props.forAll[TextCase]("csv-text", CsvTextGen.textCase, Shrinker.csvTextCase,
      count = Props.seedCountOr(DefaultCsvSeeds)) { c => CsvRoundTrip.checkText(c); () }

  @Test def fuzzInferenceRoundTrip(): Unit =
    Props.forAll[InferenceCase]("csv-inference-round-trip", CsvTextGen.inferenceCase, Shrinker.csvInferenceCase,
      count = Props.seedCountOr(DefaultCsvSeeds)) { c => InferenceRoundTrip.check(c); () }

  // ---- corpus health ---------------------------------------------------------

  private def tally(counts: mutable.Map[String, Int], hits: Seq[(String, Boolean)]): Unit =
    hits.foreach { case (label, hit) => if (hit) counts(label) += 1 }

  private def assertBounds(property: String, n: Int, counts: mutable.Map[String, Int], bounds: Seq[(String, Int)]): Unit = {
    println(s"[$property] over $n seeds:")
    bounds.foreach { case (label, bound) => println(f"  ${counts(label)}%6d  (bound $bound%5d)  $label") }
    bounds.foreach { case (label, bound) =>
      assertTrue(s"$label on ${counts(label)} of $n seeds, under $bound", counts(label) >= bound)
    }
  }

  private def formatHits(f: CsvTextGen.Format): Seq[(String, Boolean)] = Seq(
    "a delimiter other than a comma" -> (f.delimiter != ','),
    "a delimiter or quote past ASCII" -> (f.delimiter > 0x7f || f.quote > 0x7f),
    "an escape apart from the quote" -> (f.escape != f.quote),
    "a null string that is a word" -> f.nullValue.nonEmpty,
    "a CRLF or CR line ending" -> (f.lineEnding != "\n"),
    "a UTF-16 charset" -> f.charset.name.startsWith("UTF-16"),
    "a charset short of Unicode" -> !f.unicode,
    "no header" -> !f.header)

  /** Coverage guard for the data round trip, read off generated cases: every
    * type, the rows and values CSV is easy to lose (a NULL row of a
    * one-column table, an empty string, the null string's own text, a value
    * holding the delimiter, quote, escape or a line break, a leading
    * byte-order mark, a value the charset cannot hold), every option bag, and
    * the part-file shapes (several, an empty one, none). */
  @Test def dataCorpusReachesEveryTypeValueAndFormat(): Unit = {
    val n = Props.seedCountOr(4000)
    val counts = mutable.Map.empty[String, Int].withDefaultValue(0)
    (0 until n).foreach { i =>
      val c = CsvTextGen.dataCase(new Rng(Props.baseSeed + i))
      val f = c.format
      val strings = c.rows.flatten.collect { case s: String => s }
      c.schema.fields.foreach(fd => counts(s"type ${ExprGen.kind(fd.dataType)}") += 1)
      tally(counts, formatHits(f) ++ Seq(
        "a NULL row of a one-column table" -> (c.schema.length == 1 && c.rows.exists(_.head == null)),
        "an empty string" -> strings.contains(""),
        "a string that is the null string" -> (f.nullValue.nonEmpty && strings.contains(f.nullValue)),
        "a value holding the delimiter" -> strings.exists(_.contains(f.delimiter)),
        "a value holding the quote" -> strings.exists(_.contains(f.quote)),
        "a value holding the escape" -> (f.escape != f.quote && strings.exists(_.contains(f.escape))),
        "a value ending in the escape" -> (f.escape != f.quote && strings.exists(_.endsWith(f.escape.toString))),
        "a value holding a line break" -> strings.exists(s => s.contains('\n') || s.contains('\r')),
        "a value starting with a byte-order mark" -> strings.exists(_.startsWith(0xfeff.toChar.toString)),
        "a value the charset cannot hold" -> strings.exists(s => !f.unicode && !f.charset.newEncoder().canEncode(s)),
        "half of a surrogate pair" -> strings.exists(s => s.exists(Character.isSurrogate) && s.length < 4),
        "a decimal below its declared scale" -> c.rows.flatten.exists {
          case d: java.math.BigDecimal => c.schema.fields.exists(_.dataType match {
            case t: DataType.DecimalType => d.scale < t.scale
            case _ => false
          })
          case _ => false
        },
        "an empty byte string" -> c.rows.flatten.exists { case b: Array[Byte] => b.isEmpty; case _ => false },
        "NaN or -0.0" -> c.rows.flatten.exists {
          case d: Double => d.isNaN || java.lang.Double.doubleToRawLongBits(d) == java.lang.Double.doubleToRawLongBits(-0.0)
          case f: Float => f.isNaN || java.lang.Float.floatToRawIntBits(f) == java.lang.Float.floatToRawIntBits(-0.0f)
          case _ => false
        },
        "several part files" -> (c.partitions > 1),
        "an empty part file" -> (c.partitions > c.rows.length),
        "no part file at all" -> (c.partitions == 0)))
    }
    // Measured over 20000 seeds; each bound sits about 3x below its count.
    assertBounds("csv-data-round-trip", n, counts,
      ExprGen.GenTypes.map(t => s"type ${ExprGen.kind(t)}" -> n / 20).distinct ++ Seq(
        "a NULL row of a one-column table" -> n / 20, "an empty string" -> n / 40,
        "a string that is the null string" -> n / 120, "a value holding the delimiter" -> n / 80,
        "a value holding the quote" -> n / 50, "a value holding the escape" -> n / 300,
        "a value ending in the escape" -> n / 300, "a value holding a line break" -> n / 60,
        "a value starting with a byte-order mark" -> n / 70, "a value the charset cannot hold" -> n / 300,
        "half of a surrogate pair" -> n / 60, "a decimal below its declared scale" -> n / 45,
        "an empty byte string" -> n / 35, "NaN or -0.0" -> n / 20, "several part files" -> n / 10,
        "an empty part file" -> n / 20, "no part file at all" -> n / 120,
        "a delimiter other than a comma" -> n / 10, "a delimiter or quote past ASCII" -> n / 15,
        "an escape apart from the quote" -> n / 10, "a null string that is a word" -> n / 10,
        "a CRLF or CR line ending" -> n / 10, "a UTF-16 charset" -> n / 15, "a charset short of Unicode" -> n / 25,
        "no header" -> n / 15))
  }

  /** Coverage guard for the text property: each way a field can be written
    * (quoted with its quotes doubled or escaped, its escapes escaped, a line
    * break inside, text after the closing quote, a quote inside a bare
    * field), each way a line can end and be blank (a row in a one-column
    * file, skipped in a wider one, before a header), a byte-order mark, a
    * last line with no break, and every malformation. */
  @Test def textCorpusReachesEveryShape(): Unit = {
    val n = Props.seedCountOr(4000)
    val counts = mutable.Map.empty[String, Int].withDefaultValue(0)
    (0 until n).foreach { i =>
      val c = CsvTextGen.textCase(new Rng(Props.baseSeed + i))
      val f = c.format
      val fields = (c.header.toSeq ++ c.lines.collect { case r: Row => r }).flatMap(_.fields)
      val rows = c.lines.collect { case r: Row => r }
      tally(counts, formatHits(f) ++ Seq(
        "a quote doubled inside quotes" -> fields.exists(t => t.quoted && t.doubled && t.value.contains(f.quote)),
        "a quote escaped inside quotes" -> fields.exists(t => t.quoted && !t.doubled && t.value.contains(f.quote)),
        "an escape escaped inside quotes" -> fields.exists(t => t.quoted && f.escape != f.quote && t.value.contains(f.escape)),
        "a line break inside quotes" -> fields.exists(t => t.quoted && (t.value.contains('\n') || t.value.contains('\r'))),
        "text after a closing quote" -> fields.exists(_.trailing.nonEmpty),
        "a quote inside a bare field" -> fields.exists(t => !t.quoted && t.value.drop(1).contains(f.quote)),
        "a quoted empty field" -> fields.exists(t => t.quoted && t.value.isEmpty),
        "a bare null string" -> fields.exists(t => !t.quoted && t.value == f.nullValue),
        "a quoted null string" -> fields.exists(t => t.quoted && t.value == f.nullValue && f.nullValue.nonEmpty),
        "a blank line in a one-column file" -> (c.width == 1 && c.lines.exists(_.isInstanceOf[Blank])),
        "a blank line in a wider file" -> (c.width > 1 && c.lines.exists(_.isInstanceOf[Blank])),
        "blank lines before a header" -> (c.header.isDefined && c.leadingBlanks > 0),
        "a byte-order mark" -> c.bom,
        "a last line with no break" -> c.lines.lastOption.exists {
          case r: Row => r.ending.isEmpty
          case _ => false
        },
        "rows ending in CRLF and in CR" -> (rows.exists(_.ending == "\r\n") && rows.exists(_.ending == "\r")),
        "a field past 8192 characters" -> fields.exists(_.value.length > 8192),
        "an unclosed quote" -> c.malformed.contains(Malformed.UnclosedQuote),
        "a row with an extra field" -> c.malformed.exists(_.isInstanceOf[Malformed.ExtraField]),
        "a row with a missing field" -> c.malformed.exists(_.isInstanceOf[Malformed.MissingField]),
        "bytes that are no text" -> c.malformed.exists(_.isInstanceOf[Malformed.InvalidBytes])))
    }
    // Measured over 20000 seeds; each bound sits about 3x below its count.
    assertBounds("csv-text", n, counts, Seq(
      "a quote doubled inside quotes" -> n / 10, "a quote escaped inside quotes" -> n / 30,
      "an escape escaped inside quotes" -> n / 45, "a line break inside quotes" -> n / 10,
      "text after a closing quote" -> n / 10, "a quote inside a bare field" -> n / 12,
      "a quoted empty field" -> n / 5, "a bare null string" -> n / 5, "a quoted null string" -> n / 25,
      "a blank line in a one-column file" -> n / 15, "a blank line in a wider file" -> n / 10,
      "blank lines before a header" -> n / 20, "a byte-order mark" -> n / 25, "a last line with no break" -> n / 10,
      "rows ending in CRLF and in CR" -> n / 5, "a field past 8192 characters" -> n / 10,
      "an unclosed quote" -> n / 180, "a row with an extra field" -> n / 80, "a row with a missing field" -> n / 150,
      "bytes that are no text" -> n / 180, "a delimiter or quote past ASCII" -> n / 15,
      "an escape apart from the quote" -> n / 10, "a null string that is a word" -> n / 10,
      "a UTF-16 charset" -> n / 15, "a charset short of Unicode" -> n / 25))
  }

  /** Coverage guard for inference, over real reads: every category of text
    * that looks like another type, every type inferred, a value past the
    * sample that does not fit (a failed read), and enough cases that run the
    * whole fixed point. */
  @Test def inferenceReachesEveryCategoryAndType(): Unit = {
    val n = Props.seedCountOr(200)
    val counts = mutable.Map.empty[String, Int].withDefaultValue(0)
    val steps = mutable.Map.empty[(String, String), (Int, Boolean)].withDefaultValue((0, false))
    (0 until n).foreach { i =>
      val c = CsvTextGen.inferenceCase(new Rng(Props.baseSeed + 300000 + i))
      val held = c.rows.flatten.collect { case s: String => s }.toSet
      CsvTextGen.Lookalikes.foreach { case (category, texts) => if (texts.exists(held)) counts(s"text: $category") += 1 }
      val r = InferenceRoundTrip.check(c)
      if (r.failedAsExpected) counts("a value past the sample that does not fit") += 1
      else counts("the whole fixed point") += 1
      r.steps.foreach { s =>
        counts(s"inferred ${ExprGen.kind(s.to)}") += 1
        val key = (s.from.toString, s.to.toString)
        val (k, changed) = steps(key)
        steps(key) = (k + 1, changed || s.changedValue)
      }
    }
    println(s"[csv-inference-round-trip] written type -> inferred type (columns, whether any value changed):")
    steps.toSeq.sortBy(_._1).foreach { case ((from, to), (k, changed)) =>
      println(f"  $k%5d  $from%-15s -> $to%-15s ${if (changed) "values change" else ""}")
    }
    assertBounds("csv-inference-round-trip", n, counts,
      CsvTextGen.Lookalikes.map { case (category, _) => s"text: $category" -> n / 20 } ++ Seq(
        "inferred int" -> n / 10, "inferred long" -> n / 50, "inferred decimal" -> n / 50, "inferred double" -> n / 10,
        "inferred boolean" -> n / 50, "inferred date" -> n / 50, "inferred timestamp" -> n / 50,
        "inferred string" -> n / 5,
        "a value past the sample that does not fit" -> n / 100, "the whole fixed point" -> n / 2))
  }

  // ---- the oracles themselves ----------------------------------------------------

  /** The data oracle reports a reader that disagrees with the writer: read
    * back without the writer's escape, as `DataJob` once did, a value holding
    * the escape comes back changed. */
  @Test def theDataOracleReportsAWrongReRead(): Unit = {
    val schema = Schema(Field("s", DataType.StringType), Field("t", DataType.StringType))
    val esc = CsvTextGen.DefaultFormat.copy(escape = '\\')
    val c = DataCase(schema, Vector(Vector("a\\b,c", "x")), esc, partitions = 1)
    CsvRoundTrip.checkData(c)
    val dir = java.nio.file.Files.createTempDirectory("csv-oracle-")
    try {
      com.transformer.write.csv.CsvWriter.writeAll(dir.resolve("x.csv"), schema, Iterator({
        val b = new com.transformer.core.ColumnarBatch(schema, 1)
        b.column(0).setBoxed(0, "a\\b,c"); b.column(1).setBoxed(0, "x"); b.setNumRows(1); b
      }), com.transformer.write.csv.CsvWriteOptions.fromMap(esc.options))
      val withoutEscape = com.transformer.read.csv.CsvOptions.forWrittenFile(esc.options - "escape", schema)
      val read = CsvRoundTrip.rowsOf(com.transformer.read.csv.CsvReader.fromPath(dir.toString, withoutEscape))
      assertNotEquals("a re-read without the escape must change the value", Vector(Vector("a\\b,c", "x")), read)
    } finally CsvRoundTrip.deleteRecursively(dir)
  }

  /** The inference oracle's own grammar agrees with the reader's on every
    * text the generator draws, so a disagreement it reports is the reader's,
    * and it rejects the texts the old ladder misread. */
  @Test def theInferenceGrammarIsTheAgreedOne(): Unit = {
    assertEquals(DataType.StringType, Seq("1.5", "true").map(InferenceRoundTrip.classify).reduce(InferenceRoundTrip.join))
    assertEquals(DataType.TimestampType,
      Seq("2024-01-01", "2024-01-01T00:00:00").map(InferenceRoundTrip.classify).reduce(InferenceRoundTrip.join))
    assertEquals(DataType.DecimalType(38, 0), InferenceRoundTrip.classify("99999999999999999999"))
    assertEquals(DataType.StringType, InferenceRoundTrip.classify("1d"))
    assertTrue(InferenceRoundTrip.denote("1.5", DataType.BooleanType).isLeft)
    assertEquals(Right(7), InferenceRoundTrip.denote("007", DataType.IntType))
  }

  @Test def sameSeedReproduces(): Unit = {
    assertEquals(CsvTextGen.dataCase(new Rng(77)).toString, CsvTextGen.dataCase(new Rng(77)).toString)
    assertEquals(CsvTextGen.render(CsvTextGen.textCase(new Rng(77))), CsvTextGen.render(CsvTextGen.textCase(new Rng(77))))
    assertEquals(CsvTextGen.inferenceCase(new Rng(77)).toString, CsvTextGen.inferenceCase(new Rng(77)).toString)
  }

  /** Every candidate a shrinker offers must be smaller, or `Props.minimize`
    * loops to its step cap. */
  @Test def shrinkersReduce(): Unit = {
    def dataSize(c: DataCase): Long = c.rows.length * 100L + c.schema.length * 10000L + c.partitions + formatSize(c.format)
    def textSize(c: TextCase): Long = c.lines.length * 1000000L + c.width * 100000000L + c.malformed.size +
      (if (c.bom) 1 else 0) + c.leadingBlanks + c.lines.collect { case r: Row => r }.flatMap(_.fields)
        .count(t => t.value != "a" || t.trailing.nonEmpty) * 10L
    def inferenceSize(c: InferenceCase): Long = c.rows.length * 100L + c.schema.length * 10000L + c.files +
      (if (c.sampleRows != 1000) 1 else 0) + formatSize(c.format)
    (0 until 60).foreach { i =>
      val d = CsvTextGen.dataCase(new Rng(i))
      Shrinker.csvDataCase(d).take(60).foreach(s => assertTrue(s"$d -> $s", dataSize(s) < dataSize(d)))
      val t = CsvTextGen.textCase(new Rng(i))
      Shrinker.csvTextCase(t).take(60).foreach(s => assertTrue(s"$t -> $s", textSize(s) < textSize(t)))
      val f = CsvTextGen.inferenceCase(new Rng(i))
      Shrinker.csvInferenceCase(f).take(60).foreach(s => assertTrue(s"$f -> $s", inferenceSize(s) < inferenceSize(f)))
    }
  }

  private def formatSize(f: CsvTextGen.Format): Long = {
    val d = CsvTextGen.DefaultFormat
    Seq(f.charset != d.charset, f.lineEnding != d.lineEnding, f.nullValue != d.nullValue, f.escape != f.quote).count(identity)
  }
}
