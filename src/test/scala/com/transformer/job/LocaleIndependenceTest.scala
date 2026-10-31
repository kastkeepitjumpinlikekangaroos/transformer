package com.transformer.job

import com.transformer.cli.Cli
import com.transformer.core.Catalog
import com.transformer.gui.JobSession
import com.transformer.read.csv.{CsvOptions, CsvReader}
import com.transformer.read.parquet.ParquetReader
import com.transformer.sql.exec.SqlEngine
import com.transformer.sql.parse.SqlParser
import com.transformer.sql.plan.LogicalBuilder
import com.transformer.temporal.{TemplateRenderer, TemporalVariables}
import com.transformer.write.csv.CsvWriter
import com.transformer.write.parquet.ParquetWriter
import org.junit.Assert._
import org.junit.Test

import java.io.{ByteArrayInputStream, DataInputStream, File}
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Locale
import java.util.jar.JarFile
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** A job answers the same whatever the JVM's locale. This target runs under
  * tr-TR (see BUILD), where `"I".toLowerCase()` is a dotless i and
  * `"i".toUpperCase()` a dotted I: every name the engine folds to compare
  * (views, columns, functions, CAST types, template variables, option values)
  * would stop matching, and `UPPER` / `LOWER` would answer differently, if any
  * of them read the default locale. The last test holds the rule for code not
  * yet written: no compiled class of a module a job runs calls a
  * default-locale conversion or format. */
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

  // ---------------------------------------------------------------------------
  // The rule, held over the compiled classes
  // ---------------------------------------------------------------------------

  /** A call that reads the default locale, and what to write instead. */
  private val DefaultLocaleCalls: Map[(String, String, String), String] = Map(
    ("java/lang/String", "toLowerCase", "()Ljava/lang/String;") -> "toLowerCase(Locale.ROOT)",
    ("java/lang/String", "toUpperCase", "()Ljava/lang/String;") -> "toUpperCase(Locale.ROOT)",
    ("java/lang/String", "format", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;") -> "String.format(Locale.ROOT, ...)",
    ("java/lang/String", "formatted", "([Ljava/lang/Object;)Ljava/lang/String;") -> "String.format(Locale.ROOT, ...)",
    ("scala/collection/StringOps$", "format$extension", "(Ljava/lang/String;Lscala/collection/immutable/Seq;)Ljava/lang/String;") ->
      "\"...\".formatLocal(Locale.ROOT, ...) (an f\"...\" interpolator formats in the default locale)",
    ("java/time/format/DateTimeFormatter", "ofPattern", "(Ljava/lang/String;)Ljava/time/format/DateTimeFormatter;") ->
      "DateTimeFormatter.ofPattern(pattern, Locale.ROOT)",
    ("java/text/NumberFormat", "getInstance", "()Ljava/text/NumberFormat;") -> "an explicit Locale",
    ("java/text/NumberFormat", "getNumberInstance", "()Ljava/text/NumberFormat;") -> "an explicit Locale"
  )

  /** One class from each module a job, the CLI or a reopened run executes. */
  private val ModuleClasses: Seq[Class[_]] = Seq(
    classOf[Catalog], LogicalBuilder.getClass, SqlEngine.getClass, SqlParser.getClass,
    classOf[CsvReader], ParquetReader.getClass, classOf[CsvWriter], classOf[ParquetWriter],
    classOf[DataJob], TemplateRenderer.getClass, Cli.getClass, classOf[JobSession])

  @Test def noModuleCallsADefaultLocaleConversionOrFormat(): Unit = {
    val jars = ModuleClasses.map(c => new File(c.getProtectionDomain.getCodeSource.getLocation.toURI)).distinct
    val offences = for {
      jar <- jars
      (className, bytes) <- classFiles(jar)
      ref <- memberRefs(bytes)
      advice <- DefaultLocaleCalls.get(ref)
    } yield s"$className calls ${ref._1}.${ref._2}${ref._3}; use $advice"
    assertTrue(s"read ${jars.size} module jars, expected ${ModuleClasses.size}", jars.size == ModuleClasses.size)
    assertTrue(offences.distinct.mkString("\n"), offences.isEmpty)
  }

  private def classFiles(location: File): Seq[(String, Array[Byte])] =
    if (location.isDirectory) {
      val root = location.toPath
      Files.walk(root).iterator.asScala.filter(p => Files.isRegularFile(p) && p.toString.endsWith(".class"))
        .map(p => root.relativize(p).toString -> Files.readAllBytes(p)).toVector
    } else {
      val jar = new JarFile(location)
      try jar.entries.asScala.filter(_.getName.endsWith(".class"))
        .map(e => e.getName -> jar.getInputStream(e).readAllBytes()).toVector
      finally jar.close()
    }

  /** The (owner, name, descriptor) of every method a class file's constant
    * pool references (JVMS section 4.4), read without a bytecode library. */
  private def memberRefs(bytes: Array[Byte]): Seq[(String, String, String)] = {
    val in = new DataInputStream(new ByteArrayInputStream(bytes))
    require(in.readInt() == 0xCAFEBABE, "not a class file")
    in.readUnsignedShort(); in.readUnsignedShort()
    val n = in.readUnsignedShort()
    val tag = new Array[Int](n)
    val a = new Array[Int](n)
    val b = new Array[Int](n)
    val utf8 = new Array[String](n)
    var i = 1
    while (i < n) {
      tag(i) = in.readUnsignedByte()
      tag(i) match {
        case 1                          => utf8(i) = in.readUTF()
        case 3 | 4                      => in.readInt()
        case 5 | 6                      => in.readLong(); i += 1
        case 7 | 8 | 16 | 19 | 20       => a(i) = in.readUnsignedShort()
        case 9 | 10 | 11 | 12 | 17 | 18 => a(i) = in.readUnsignedShort(); b(i) = in.readUnsignedShort()
        case 15                         => in.readUnsignedByte(); a(i) = in.readUnsignedShort()
        case other                      => sys.error(s"unknown constant-pool tag $other")
      }
      i += 1
    }
    // Methodref and InterfaceMethodref: a Class (its name), then a NameAndType.
    (1 until n).filter(j => tag(j) == 10 || tag(j) == 11).map { j =>
      val nameAndType = b(j)
      (utf8(a(a(j))), utf8(a(nameAndType)), utf8(b(nameAndType)))
    }
  }
}
