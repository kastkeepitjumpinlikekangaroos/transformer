package com.transformer.job

import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Comparator
import scala.jdk.CollectionConverters._

/** Covers [[JobOutputLayout.detect]] — the helper the GUI uses to figure out
  * whether an output directory is a single-run snapshot or a parent of many
  * such snapshots.
  */
class JobOutputLayoutTest {

  private var root: Path = _

  @Before def setUp(): Unit = {
    root = Files.createTempDirectory("job-output-layout-test-")
  }

  @After def tearDown(): Unit = {
    if (root != null && Files.exists(root)) {
      Files.walk(root).sorted(Comparator.reverseOrder())
        .iterator().asScala
        .foreach(Files.deleteIfExists(_))
    }
  }

  /** Build a JobRunRecord with explicit times so multi-run sort order is
    * deterministic in the assertions. */
  private def writeJobRecord(dir: Path, finishedAt: Instant, succeeded: Boolean = true): Unit = {
    Files.createDirectories(dir)
    JobRunRecord.write(dir.resolve("job.json"), JobRunRecord(
      schemaVersion = JobRunRecord.SchemaVersion,
      succeeded = succeeded,
      errorMessage = None,
      executionTime = finishedAt,
      startedAt = finishedAt,
      finishedAt = finishedAt,
      tasks = Nil,
      warnings = Nil
    ))
  }

  @Test def detectSingleRunWhenJobJsonIsDirectChild(): Unit = {
    val dir = root.resolve("snapshot")
    val t = Instant.parse("2026-01-01T00:00:00Z")
    writeJobRecord(dir, t)
    JobOutputLayout.detect(dir) match {
      case JobOutputLayout.SingleRun(d, rec) =>
        assertEquals(dir.toAbsolutePath.normalize(), d.toAbsolutePath.normalize())
        assertEquals(t, rec.finishedAt)
      case other => fail(s"expected SingleRun, got $other")
    }
  }

  @Test def detectMultiRunSortedNewestFirstByFinishedAt(): Unit = {
    val parent = root.resolve("runs")
    Files.createDirectories(parent)
    val r1 = parent.resolve("2026-01-01"); val t1 = Instant.parse("2026-01-01T12:00:00Z")
    val r2 = parent.resolve("2026-01-03"); val t3 = Instant.parse("2026-01-03T12:00:00Z")
    val r3 = parent.resolve("2026-01-02"); val t2 = Instant.parse("2026-01-02T12:00:00Z")
    // Write out of order to make sure detection sorts.
    writeJobRecord(r1, t1)
    writeJobRecord(r2, t3)
    writeJobRecord(r3, t2)
    JobOutputLayout.detect(parent) match {
      case JobOutputLayout.MultiRun(runs) =>
        assertEquals(3, runs.size)
        // Newest first.
        assertEquals(t3, runs(0)._2.finishedAt)
        assertEquals(t2, runs(1)._2.finishedAt)
        assertEquals(t1, runs(2)._2.finishedAt)
      case other => fail(s"expected MultiRun, got $other")
    }
  }

  @Test def detectEmptyWhenNoJobJsonAnywhere(): Unit = {
    val dir = root.resolve("empty-data")
    Files.createDirectories(dir)
    Files.createDirectories(dir.resolve("customers"))
    Files.writeString(dir.resolve("customers/part-00000.csv"), "id\n1\n")
    assertEquals(JobOutputLayout.Empty, JobOutputLayout.detect(dir))
  }

  @Test def detectEmptyWhenDirectoryDoesNotExist(): Unit = {
    val missing = root.resolve("does-not-exist")
    assertEquals(JobOutputLayout.Empty, JobOutputLayout.detect(missing))
  }

  @Test def detectSkipsSubdirsWithoutJobJson(): Unit = {
    // Parent has one run subdir + one unrelated subdir (e.g., a cache).
    val parent = root.resolve("mixed")
    Files.createDirectories(parent)
    val realRun = parent.resolve("real")
    writeJobRecord(realRun, Instant.parse("2026-01-01T12:00:00Z"))
    val unrelated = parent.resolve("cache")
    Files.createDirectories(unrelated)
    Files.writeString(unrelated.resolve("anything.txt"), "noise")
    JobOutputLayout.detect(parent) match {
      case JobOutputLayout.MultiRun(runs) =>
        assertEquals(1, runs.size)
        assertEquals(realRun.toAbsolutePath.normalize(), runs.head._1.toAbsolutePath.normalize())
      case other => fail(s"expected MultiRun, got $other")
    }
  }

  @Test def detectIgnoresMalformedJobJson(): Unit = {
    val dir = root.resolve("malformed")
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("job.json"), "{not valid json")
    // Malformed → can't parse → no usable single-run record → Empty.
    assertEquals(JobOutputLayout.Empty, JobOutputLayout.detect(dir))
  }
}
