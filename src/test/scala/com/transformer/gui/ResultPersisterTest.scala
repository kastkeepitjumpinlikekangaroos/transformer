package com.transformer.gui

import com.transformer.core.{ColumnarBatch, DataType, Field, IntVector, MaterializedView, Schema, StringVector}
import com.transformer.job.{TaskRunRecord, TaskRunStatus}

import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path, Paths}
import java.util.Comparator
import scala.jdk.CollectionConverters._

/** Drives [[ResultPersister]] end-to-end against a synthetic in-memory result
  * so we exercise the per-partition write + `_run.json` record stamping
  * without spinning up the SQL engine.
  */
class ResultPersisterTest {

  private var tmpDir: Path = _

  @Before def setUp(): Unit = {
    tmpDir = Files.createTempDirectory("result-persister-test-")
  }

  @After def tearDown(): Unit = {
    if (tmpDir != null && Files.exists(tmpDir)) {
      Files.walk(tmpDir).sorted(Comparator.reverseOrder())
        .iterator().asScala
        .foreach(Files.deleteIfExists(_))
    }
  }

  private val schema = Schema(Vector(
    Field("id", DataType.IntType),
    Field("name", DataType.StringType)
  ))

  /** Build a single batch with the given (id, name) rows. */
  private def makeBatch(rows: Seq[(Int, String)]): ColumnarBatch = {
    val b = new ColumnarBatch(schema, math.max(1, rows.size))
    rows.iterator.zipWithIndex.foreach { case ((id, name), i) =>
      b.column(0).asInstanceOf[IntVector].set(i, id)
      b.column(1).asInstanceOf[StringVector].set(i, name)
    }
    b.setNumRows(rows.size)
    b
  }

  private def mvOfPartitions(parts: Seq[Seq[(Int, String)]]): MaterializedView =
    new MaterializedView(schema, parts.map(rows => IndexedSeq(makeBatch(rows))).toIndexedSeq)

  @Test def persistCsvWritesOnePartPerPartitionAndStampsRecord(): Unit = {
    val mv = mvOfPartitions(Seq(
      Seq((1, "alpha"), (2, "beta")),
      Seq((3, "gamma"))
    ))
    val dir = tmpDir.resolve("out")
    val cfg = PersistConfig(
      outputDir = dir.toString,
      format = "csv",
      maxPartitions = None,
      csvHeader = true
    )
    val rows = ResultPersister.persist(mv, cfg)
    assertEquals(3L, rows)
    assertTrue(Files.isRegularFile(dir.resolve("part-00000.csv")))
    assertTrue(Files.isRegularFile(dir.resolve("part-00001.csv")))
    val part0 = Files.readString(dir.resolve("part-00000.csv"))
    assertEquals("id,name\n1,alpha\n2,beta\n", part0)
    val part1 = Files.readString(dir.resolve("part-00001.csv"))
    assertEquals("id,name\n3,gamma\n", part1)
    // _run.json stamped with Succeeded status and both part files listed.
    val record = TaskRunRecord.read(dir).getOrElse(fail("expected _run.json").asInstanceOf[TaskRunRecord])
    assertEquals(TaskRunStatus.Succeeded, record.status)
    assertEquals(3L, record.rowsProduced)
    assertEquals("csv", record.format)
    assertEquals(Seq("part-00000.csv", "part-00001.csv"), record.outputFiles)
  }

  @Test def persistCsvHonoursHeaderFalse(): Unit = {
    val mv = mvOfPartitions(Seq(Seq((1, "alpha"))))
    val dir = tmpDir.resolve("noheader")
    val cfg = PersistConfig(
      outputDir = dir.toString,
      format = "csv",
      maxPartitions = None,
      csvHeader = false
    )
    ResultPersister.persist(mv, cfg)
    assertEquals("1,alpha\n", Files.readString(dir.resolve("part-00000.csv")))
  }

  @Test def persistCsvCoalescesToMaxPartitions(): Unit = {
    val mv = mvOfPartitions(Seq(
      Seq((1, "a")),
      Seq((2, "b")),
      Seq((3, "c"))
    ))
    val dir = tmpDir.resolve("single")
    val cfg = PersistConfig(
      outputDir = dir.toString,
      format = "csv",
      maxPartitions = Some(1),
      csvHeader = true
    )
    val rows = ResultPersister.persist(mv, cfg)
    assertEquals(3L, rows)
    // maxPartitions=1 collapses everything into one part file.
    assertTrue(Files.isRegularFile(dir.resolve("part-00000.csv")))
    assertFalse(Files.exists(dir.resolve("part-00001.csv")))
    val all = Files.readString(dir.resolve("part-00000.csv"))
    assertEquals("id,name\n1,a\n2,b\n3,c\n", all)
  }

  @Test def persistClearsStaleOutputsWhenRecordPresent(): Unit = {
    val dir = tmpDir.resolve("rerun")
    Files.createDirectories(dir)
    // Pre-seed a stale part file + run record from a previous "run".
    val stale = dir.resolve("part-00099.csv")
    Files.writeString(stale, "old\n999\n")
    val t = java.time.Instant.now()
    TaskRunRecord.write(dir, TaskRunRecord(
      schemaVersion = TaskRunRecord.SchemaVersion,
      taskName = "stale",
      status = TaskRunStatus.Succeeded,
      errorMessage = None,
      executionTime = t,
      startedAt = t,
      finishedAt = Some(t),
      writtenAt = t,
      rowsProduced = 1, format = "csv",
      outputFiles = Seq("part-00099.csv"),
      validations = Nil
    ))

    val mv = mvOfPartitions(Seq(Seq((1, "alpha"))))
    val cfg = PersistConfig(
      outputDir = dir.toString, format = "csv",
      maxPartitions = None, csvHeader = true
    )
    ResultPersister.persist(mv, cfg)

    assertFalse(s"stale part file should be deleted: $stale", Files.exists(stale))
    assertTrue(Files.isRegularFile(dir.resolve("part-00000.csv")))
    val record = TaskRunRecord.read(dir).getOrElse(fail("expected fresh record").asInstanceOf[TaskRunRecord])
    assertFalse(record.outputFiles.contains("part-00099.csv"))
  }

  private def filesIn(dir: Path): Seq[String] = {
    val stream = Files.list(dir)
    try stream.iterator().asScala.map(_.getFileName.toString).toVector.sorted finally stream.close()
  }

  private def strays(dir: Path, n: Int): Seq[String] = {
    Files.createDirectories(dir)
    (0 until n).map { i =>
      val name = f"part-$i%05d.csv"
      Files.writeString(dir.resolve(name), s"id,name\n${100 + i},stray\n")
      name
    }
  }

  /** A directory of part files no record names is someone else's: a save
    * there is refused before anything is written, as a job's task is. It
    * wrote over them and stamped a record naming every one (plans/testing/06,
    * found by plans/testing/07's survey). */
  @Test def aDirectoryOfStrayPartsIsRefused(): Unit = {
    val dir = tmpDir.resolve("strays")
    val before = strays(dir, 4)
    val cfg = PersistConfig(outputDir = dir.toString, format = "csv", maxPartitions = None, csvHeader = true)
    val refused = try { ResultPersister.persist(mvOfPartitions(Seq(Seq((1, "alpha")))), cfg); null }
      catch { case e: IllegalArgumentException => e }
    assertNotNull("a save over stray part files went ahead", refused)
    assertTrue(refused.getMessage, refused.getMessage.contains("which no run record names"))
    assertEquals(before, filesIn(dir))
    before.zipWithIndex.foreach { case (f, i) =>
      assertEquals(s"id,name\n${100 + i},stray\n", Files.readString(dir.resolve(f)))
    }
  }

  /** A save that fails part way leaves no part file, staged or committed,
    * only a Failed record. Here the second partition holds half a surrogate
    * pair, which no CSV file can hold; the first partition's part stayed. */
  @Test def aFailedPersistLeavesNoPartFile(): Unit = {
    val dir = tmpDir.resolve("failed")
    val mv = mvOfPartitions(Seq(Seq((1, "alpha")), Seq((2, "half " + 0xd800.toChar))))
    val cfg = PersistConfig(outputDir = dir.toString, format = "csv", maxPartitions = None, csvHeader = true)
    val thrown = try { ResultPersister.persist(mv, cfg); null } catch { case e: IllegalArgumentException => e }
    assertNotNull("wrote half a surrogate pair", thrown)
    assertTrue(thrown.getMessage, thrown.getMessage.contains("surrogate"))
    assertEquals(Seq(TaskRunRecord.FileName), filesIn(dir))
    val record = TaskRunRecord.read(dir).get
    assertEquals(TaskRunStatus.Failed, record.status)
    assertEquals(Some(thrown.getMessage), record.errorMessage)
  }

  /** The record names the part files the save wrote, never a listing of the
    * directory: four stray one-row parts and then one saved row gave a record
    * naming all four with `rowsProduced` 1. Strays are refused now; over an
    * earlier save's four parts, a save of one row names its one part, and
    * the earlier parts are gone. */
  @Test def theRecordNamesExactlyTheFilesWritten(): Unit = {
    val one = mvOfPartitions(Seq(Seq((1, "alpha"))))
    val strayDir = tmpDir.resolve("four-strays")
    strays(strayDir, 4)
    val cfg = PersistConfig(outputDir = strayDir.toString, format = "csv", maxPartitions = None, csvHeader = true)
    try ResultPersister.persist(one, cfg) catch { case _: IllegalArgumentException => () }
    assertFalse("a record names the strays", TaskRunRecord.read(strayDir).exists(_.outputFiles.nonEmpty))

    val dir = tmpDir.resolve("resaved")
    val again = cfg.copy(outputDir = dir.toString)
    assertEquals(4L, ResultPersister.persist(mvOfPartitions(Seq.tabulate(4)(i => Seq((i, "earlier")))), again))
    assertEquals(1L, ResultPersister.persist(one, again))
    val record = TaskRunRecord.read(dir).get
    assertEquals(Seq("part-00000.csv"), record.outputFiles)
    assertEquals(1L, record.rowsProduced)
    assertEquals(Seq(TaskRunRecord.FileName, "part-00000.csv"), filesIn(dir))
    assertEquals("id,name\n1,alpha\n", Files.readString(dir.resolve("part-00000.csv")))
  }

  @Test def persistRejectsUnknownFormat(): Unit = {
    val mv = mvOfPartitions(Seq(Seq((1, "alpha"))))
    val cfg = PersistConfig(
      outputDir = tmpDir.resolve("bogus").toString,
      format = "orc",
      maxPartitions = None,
      csvHeader = true
    )
    val ex = try { ResultPersister.persist(mv, cfg); null }
    catch { case t: Throwable => t }
    assertNotNull(ex)
    assertTrue(ex.getMessage.toLowerCase.contains("unsupported"))
  }
}
