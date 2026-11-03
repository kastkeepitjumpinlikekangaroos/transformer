package com.transformer.job

import com.transformer.core.Catalog
import com.transformer.read.csv.{CsvOptions, CsvReader}
import com.transformer.sql.exec.SqlEngine
import com.transformer.temporal.{TemplateRenderer, TemporalVariables}
import com.transformer.write.csv.CsvWriter
import org.junit.Assert._
import org.junit.Test

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Locale
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** A job answers the same whatever the JVM's locale. This target runs under
  * tr-TR (see BUILD), where `"I".toLowerCase()` is a dotless i and
  * `"i".toUpperCase()` a dotted I: every name the engine folds to compare
  * (views, columns, functions, CAST types, template variables, option values)
  * would stop matching, and `UPPER` / `LOWER` would answer differently, if any
  * of them read the default locale. Rule R1 of `bytecode:bytecode_rules_test`
  * holds the rule for code not yet written: no compiled class of a module a job
  * runs calls a default-locale conversion or format, and none of the desktop
  * GUI's or the data viewer's folds case in the default locale. */
class LocaleIndependenceTest {

  @Test def runsUnderATurkishLocale(): Unit = {
    assertEquals("the BUILD jvm_flags must set tr-TR", "tr", Locale.getDefault.getLanguage)
    assertNotEquals("the locale must fold I differently", "i", "I".toLowerCase)
  }

  private def tmpCsv(contents: String): Path = {
    val p = Files.createTempDirectory("locale-").resolve("items.csv")
    Files.writeString(p, contents)
    p
  }

  private def rows(sql: String, catalog: Catalog): Seq[Seq[Any]] = {
    val q = SqlEngine.execute(sql, catalog)
    val out = mutable.ArrayBuffer.empty[Seq[Any]]
    q.batches.foreach { b =>
      (0 until b.numRows).foreach { r =>
        out += (0 until b.schema.length).map(c => if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r))
      }
    }
    out.toSeq
  }

  private def items: Catalog = {
    val c = new Catalog
    c.register("Items", CsvReader.fromPath(tmpCsv("ID,title\n1,istanbul\n2,Iowa\n").toString, CsvOptions()))
    c
  }

  @Test def viewsColumnsFunctionsAndTypesResolveWhateverTheirCase(): Unit = {
    val c = items
    assertEquals(Seq(Seq(1), Seq(2)), rows("SELECT id FROM items ORDER BY ID", c))
    assertEquals(Seq(Seq(1, 2, 0.0, 1L)),
      rows("SELECT min(ID) AS lo, MAX(id) AS hi, sin(0) AS z, count_if(ID > 1) AS n FROM ITEMS", c))
    assertEquals(Seq(Seq("b", 1, 1L, "x")),
      rows("SELECT if(ID > 1, 'a', 'b') AS f, sign(ID) AS s, CAST(ID AS bigint) AS l, " +
        "CAST('x' AS string) AS t FROM Items WHERE id = 1", c))
    assertEquals(Seq(Seq(Instant.parse("2026-01-01T00:00:00Z"))),
      rows("SELECT CAST('2026-01-01' AS timestamp) AS ts FROM Items WHERE ID = 1", c))
    assertEquals(Seq(Seq(1L)),
      rows("WITH Iq AS (SELECT ID FROM Items) SELECT COUNT(DISTINCT id) AS n FROM IQ WHERE ID = 2", c))
  }

  @Test def upperLowerAndIlikeFoldTheSameEverywhere(): Unit = {
    assertEquals(Seq(Seq("ISTANBUL", "iowa")),
      rows("SELECT UPPER(title) AS u, LOWER('IOWA') AS l FROM Items WHERE ID = 1", items))
    assertEquals(Seq(Seq("istanbul"), Seq("Iowa")),
      rows("SELECT title FROM Items WHERE title ILIKE 'i%' ORDER BY ID", items))
  }

  @Test def templateVariablesMatchWhateverTheirCase(): Unit = {
    val vars = TemporalVariables(Instant.parse("2026-03-04T05:06:07Z"))
    assertEquals("2026-03-04 03 04", TemplateRenderer.render("{{ ISO_DATE }} {{ Current_Month_Pad }} {{ current_day_pad }}", vars))
  }

  /** A locale whose digits are not ASCII: what a `%d` or a date pattern would
    * print there if it read the default locale. */
  private val thaiDigits: Locale = Locale.forLanguageTag("th-TH-u-nu-thai")

  @Test def templateValuesAndPartFileNamesUseAsciiDigitsInEveryLocale(): Unit = {
    assertNotEquals("the JVM must localize digits for this test to mean anything",
      "05", String.format(thaiDigits, "%02d", Integer.valueOf(5)))
    val previous = Locale.getDefault(Locale.Category.FORMAT)
    Locale.setDefault(Locale.Category.FORMAT, thaiDigits)
    try {
      val vars = TemporalVariables(Instant.parse("2026-03-04T05:06:07Z"))
      assertEquals("20260304 03 05 2026-03-04T05:06:07Z",
        TemplateRenderer.render("{{ today }} {{ current_month_pad }} {{ current_hour_pad }} {{ iso_timestamp }}", vars))
      val dir = Files.createTempDirectory("locale-parts-")
      val view = CsvReader.fromPath(tmpCsv("ID\n1\n").toString, CsvOptions())
      val q = SqlEngine.execute("SELECT ID FROM t", { val c = new Catalog; c.register("t", view); c })
      CsvWriter.writePartitioned(dir, q.schema, (0 until q.numPartitions).map(q.partition))
      assertEquals(Seq("part-00000.csv"), Files.list(dir).iterator.asScala.map(_.getFileName.toString).toSeq.sorted)
    } finally Locale.setDefault(Locale.Category.FORMAT, previous)
  }

  @Test def aDirectoryJobWithParquetGzipOutputRuns(): Unit = {
    val dir = Files.createTempDirectory("locale-job-")
    def write(rel: String, text: String): Unit = {
      val p = dir.resolve(rel); Files.createDirectories(p.getParent); Files.writeString(p, text)
    }
    write("Items.csv", "ID,title\n1,istanbul\n2,Iowa\n")
    write("inputs/Items/input.json", """{"path": "Items.csv"}""")
    write("tables/Indexed/main.sql", "SELECT ID, UPPER(title) AS Title FROM ITEMS")
    write("tables/Indexed/output.json", """{"format": "parquet", "options": {"compression": "gzip"}}""")
    write("tables/Index_Count/main.sql", "SELECT COUNT(*) AS n FROM indexed WHERE title = 'ISTANBUL'")
    val job = DirectoryJobLoader.load(dir, outputDir = Some(dir.resolve("out").toString),
      temporalVariables = Some(TemporalVariables(Instant.parse("2026-01-01T00:00:00Z"))))
    val result = job.run(SqlEngine)
    assertTrue(s"the job failed: ${result.error.getOrElse("")} ${result.tasks.map(t => s"${t.taskName}: ${t.status}")}",
      result.succeeded)
    assertEquals(Seq(1L, 2L), result.tasks.map(_.rowsProduced).sorted)
  }
}
