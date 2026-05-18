package com.transformer.gui

import com.transformer.core.{ColumnarBatch, DataType, Field, IntVector, MaterializedView, Schema, StringVector}
import com.transformer.job.RunMarker

import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path, Paths}
import java.util.Comparator
import scala.jdk.CollectionConverters._

/** Drives [[ResultPersister]] end-to-end against a synthetic in-memory result
  * so we exercise the per-partition write + `_SUCCESS` marker stamping without
  * spinning up the SQL engine.
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

  @Test def persistCsvWritesOnePartPerPartitionAndStampsMarker(): Unit = {
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
    // _SUCCESS marker stamped with 3 rows + both part files listed.
    val marker = RunMarker.read(dir).getOrElse(fail("expected _SUCCESS marker").asInstanceOf[RunMarker])
    assertEquals(3L, marker.rowsProduced)
    assertEquals("csv", marker.format)
    assertEquals(Seq("part-00000.csv", "part-00001.csv"), marker.outputFiles)
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
