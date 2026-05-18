package com.transformer.gui

import com.transformer.job.{ParquetReaderHook, TaskRunRecord, TaskRunStatus}

import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Comparator
import scala.jdk.CollectionConverters._

/** Covers [[JobSession.readOutputAsView]]'s format detection — the path the
  * interactive SQL console takes to register a task's persisted output as a
  * queryable view.
  *
  * Regression for: ext-less parquet output directories (the default emitted by
  * `DirectoryJobLoader`, e.g. `output/<view>/`) were routed to `CsvReader`,
  * which parsed the raw `PAR1` parquet bytes as a CSV header and produced
  * garbled "column names" in any subsequent `Unknown column` error.
  */
class JobSessionTest {

  private var tmpDir: Path = _

  @Before def setUp(): Unit = {
    tmpDir = Files.createTempDirectory("job-session-test-")
  }

  @After def tearDown(): Unit = {
    if (tmpDir != null && Files.exists(tmpDir)) {
      Files.walk(tmpDir).sorted(Comparator.reverseOrder())
        .iterator().asScala
        .foreach(Files.deleteIfExists(_))
    }
  }

  private def writeRecord(dir: Path, format: String): Unit = {
    Files.createDirectories(dir)
    val t = Instant.parse("2026-01-01T00:00:00Z")
    TaskRunRecord.write(dir, TaskRunRecord(
      schemaVersion = TaskRunRecord.SchemaVersion,
      taskName      = "test",
      status        = TaskRunStatus.Succeeded,
      errorMessage  = None,
      executionTime = t,
      startedAt     = t,
      finishedAt    = t,
      writtenAt     = t,
      rowsProduced  = 0L,
      format        = format,
      outputFiles   = Seq(s"part-00000.$format"),
      validations   = Nil
    ))
  }

  /** Ext-less directory with a parquet-format marker must be routed to the
    * parquet reader hook, NOT to `CsvReader`. We don't install the hook in
    * this test classpath, so a correct routing surfaces the
    * "parquet read module not on classpath" error — a wrong routing would
    * surface CSV's "No CSV files matched" instead.
    */
  @Test def readOutputAsViewUsesMarkerFormatForExtlessParquetDir(): Unit = {
    val dir = tmpDir.resolve("test")
    writeRecord(dir, "parquet")
    // Drop a fake part file so the CSV fallback (if the routing were wrong)
    // would at least find something to open and produce its CSV-specific
    // error — distinguishing routing failure from "no files at all".
    Files.writeString(dir.resolve("part-00000.parquet"), "PAR1binary")

    val installed = ParquetReaderHook.get.isDefined
    val ex = try { JobSession.readOutputAsView(dir.toString); null }
             catch { case t: Throwable => t }

    if (installed) {
      // If some other test in the same JVM has installed a real hook, the
      // call should succeed in dispatching to it (we don't care what error
      // the real reader throws on a fake parquet file — only that we got
      // past the CSV trap).
      // No further assertion needed: a CSV-trap routing would have thrown
      // an IllegalArgumentException("No CSV files matched ...") with no
      // mention of parquet, which we'd catch and fail on below.
      if (ex != null) {
        val msg = Option(ex.getMessage).getOrElse("").toLowerCase
        assertFalse(s"routed through CSV reader: ${ex.getMessage}",
          msg.contains("no csv files"))
      }
    } else {
      assertNotNull("expected an error because no parquet hook is installed", ex)
      val msg = ex.getMessage.toLowerCase
      assertTrue(s"expected parquet-routing error, got: ${ex.getMessage}",
        msg.contains("parquet read module"))
      assertFalse(s"unexpectedly routed through CSV: ${ex.getMessage}",
        msg.contains("no csv files"))
    }
  }

  /** A directory whose marker says `csv` must still be routed to the CSV
    * reader even if `.parquet` appears somewhere in the path (an unusual but
    * possible case — e.g. the user named a parent directory `runs.parquet/`).
    * The marker takes precedence over the substring check.
    */
  @Test def readOutputAsViewMarkerCsvOverridesPathSubstring(): Unit = {
    val dir = tmpDir.resolve("runs.parquet").resolve("test")
    writeRecord(dir, "csv")
    Files.writeString(dir.resolve("part-00000.csv"), "id,name\n1,alpha\n")

    val view = JobSession.readOutputAsView(dir.toString)
    assertEquals(Vector("id", "name"), view.schema.fieldNames.toVector)
  }

  /** Without a marker we fall back to the path-substring check. A bare CSV
    * file path still works (legacy single-file path).
    */
  @Test def readOutputAsViewFallsBackToSubstringWhenNoMarker(): Unit = {
    val dir = tmpDir.resolve("legacy")
    Files.createDirectories(dir)
    val csv = dir.resolve("data.csv")
    Files.writeString(csv, "a,b\n1,2\n")
    val view = JobSession.readOutputAsView(csv.toString)
    assertEquals(Vector("a", "b"), view.schema.fieldNames.toVector)
  }
}
