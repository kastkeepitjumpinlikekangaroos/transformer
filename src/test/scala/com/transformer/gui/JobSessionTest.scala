package com.transformer.gui

import com.transformer.job.RunMarker

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

  private def writeMarker(dir: Path, format: String): Unit = {
    Files.createDirectories(dir)
    RunMarker.write(dir, RunMarker(
      executionTime = Instant.parse("2026-01-01T00:00:00Z"),
      writtenAt     = Instant.parse("2026-01-01T00:00:00Z"),
      rowsProduced  = 0L,
      format        = format,
      outputFiles   = Seq(s"part-00000.$format")
    ))
  }

  /** Ext-less directory with a parquet-format marker must be routed to the
    * parquet reader, NOT to `CsvReader`. We feed it a fake `PAR1` part file
    * so a wrong (CSV) routing would surface a CSV-specific error — a correct
    * (parquet) routing surfaces a parquet error instead.
    */
  @Test def readOutputAsViewUsesMarkerFormatForExtlessParquetDir(): Unit = {
    val dir = tmpDir.resolve("test")
    writeMarker(dir, "parquet")
    Files.writeString(dir.resolve("part-00000.parquet"), "PAR1binary")

    val ex = try { JobSession.readOutputAsView(dir.toString); null }
             catch { case t: Throwable => t }

    assertNotNull("expected a parquet decode error on the fake file", ex)
    val msg = Option(ex.getMessage).getOrElse("").toLowerCase
    assertFalse(s"unexpectedly routed through CSV: ${ex.getMessage}",
      msg.contains("no csv files"))
  }

  /** A directory whose marker says `csv` must still be routed to the CSV
    * reader even if `.parquet` appears somewhere in the path (an unusual but
    * possible case — e.g. the user named a parent directory `runs.parquet/`).
    * The marker takes precedence over the substring check.
    */
  @Test def readOutputAsViewMarkerCsvOverridesPathSubstring(): Unit = {
    val dir = tmpDir.resolve("runs.parquet").resolve("test")
    writeMarker(dir, "csv")
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
