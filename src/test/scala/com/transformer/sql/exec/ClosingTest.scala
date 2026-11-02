package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.read.csv.{CsvOptions, CsvReader}
import com.transformer.read.parquet.ParquetReader
import com.transformer.write.parquet.ParquetWriter
import org.junit.Assert._
import org.junit.Test

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters._

/** The close protocol (decision 4 of plans/testing/17): a query releases the
  * files and spill directories it opened when its consumer stops reading,
  * however it stops. A LIMIT closes the input it reads no more, over a parquet
  * file, a CSV glob and a spilled sort; a pool task whose drain fails or is
  * stopped closes its input; and a query that fails or is stopped as its
  * consumer reads it releases everything on `ExecutedQuery.close()`. Each is
  * held to the JVM's count of open file descriptors and to its spill
  * directory, which the code before left raised and holding the sort's
  * directory, and under `--config=leaks` to [[LeakAudit]] too.
  */
class ClosingTest {

  private val dir: Path = Files.createTempDirectory("closing-")

  private val schema = Schema(Vector(Field("k", DataType.IntType), Field("s", DataType.StringType)))

  private def batchOf(rows: Seq[(Int, String)]): ColumnarBatch = {
    val b = new ColumnarBatch(schema, math.max(1, rows.size))
    rows.zipWithIndex.foreach { case ((k, s), r) =>
      b.column(0).asInstanceOf[IntVector].set(r, k)
      b.column(1).asInstanceOf[StringVector].set(r, s)
    }
    b.setNumRows(rows.size)
    b
  }

  /** A parquet file of 5000 rows in row groups of a few hundred. */
  private lazy val parquetFile: Path = {
    val p = dir.resolve("t.parquet")
    val rows = (0 until 5000).map(i => (i, s"value $i"))
    val _ = ParquetWriter.writeAll(p, schema, rows.grouped(500).map(batchOf), Map("parquet_row_group_size" -> "1024"))
    p
  }

  /** Three CSV files of 4000 rows each, so a sort of them emits more than
    * one batch; `bad` puts a value that is no INT in the last row of the
    * second. */
  private def csvGlob(name: String, bad: Boolean = false): String = {
    val d = dir.resolve(name)
    Files.createDirectories(d)
    (0 until 3).foreach { f =>
      val lines = (0 until 4000).map { i =>
        val k = if (bad && f == 1 && i == 3999) "oops" else (f * 4000 + i).toString
        s"$k,row $i"
      }
      Files.writeString(d.resolve(s"part-$f.csv"), ("k,s" +: lines).mkString("", "\n", "\n"))
    }
    d.resolve("*.csv").toString
  }

  private val declared = CsvOptions(inferSchema = false, columns = Some(schema.fields))

  private def catalog: Catalog = {
    val c = new Catalog
    c.register("p", ParquetReader.fromPath(parquetFile.toString, 100))
    c.register("c", CsvReader.fromPath(csvGlob("good"), declared))
    c.register("bad", CsvReader.fromPath(csvGlob("bad", bad = true), declared))
    c
  }

  private def openFiles: Long =
    java.lang.management.ManagementFactory.getOperatingSystemMXBean
      .asInstanceOf[com.sun.management.UnixOperatingSystemMXBean].getOpenFileDescriptorCount

  private def operatorDirs: Seq[Path] = {
    val root = Spill.rootDir
    val s = Files.list(root)
    try s.iterator().asScala.toVector finally s.close()
  }

  /** Spill a run every 64 KB a partition reads: a run or more a partition at
    * any batch capacity, and never past `spill_max_runs`. */
  private val spill = ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(64L * 1024))

  /** Run `body` twice, the first time to load every class it needs, and hold
    * the second to leaving as many files open as it found and no spill
    * directory. */
  private def releases(what: String)(body: Catalog => Unit): Unit = {
    val c = catalog
    body(c)
    val before = openFiles
    body(c)
    // A pool task the query started may still be ending.
    val _ = Scheduler.pool.awaitQuiescence(10, java.util.concurrent.TimeUnit.SECONDS)
    assertEquals(s"$what leaves no file open", before, openFiles)
    assertEquals(s"$what leaves no spill directory", Nil, operatorDirs)
  }

  private def rows(q: ExecutedQuery): Int = q.batches.map(_.numRows).sum

  @Test def aLimitOverAParquetFileClosesIt(): Unit =
    releases("LIMIT 1 over a parquet file of 50 batches") { c =>
      assertEquals(1, rows(SqlEngine.execute("SELECT k, s FROM p LIMIT 1", c)))
    }

  @Test def aLimitOverACsvGlobClosesTheFileItStopsIn(): Unit =
    releases("LIMIT 3 over three CSV files") { c =>
      assertEquals(3, rows(SqlEngine.execute("SELECT k, s FROM c LIMIT 3", c)))
    }

  @Test def aLimitOverASpilledSortClosesItsRunsAndDeletesItsDirectory(): Unit =
    releases("LIMIT 2 over a sort spilled to runs") { c =>
      val q = SqlEngine.execute("SELECT k, s FROM c ORDER BY s, k LIMIT 2", c, spill)
      assertEquals(2, rows(q))
    }

  @Test def aBreakerWhoseInputFailsClosesEveryInput(): Unit =
    releases("a GROUP BY over a scan that fails") { c =>
      val e = assertThrows(classOf[IllegalArgumentException], () =>
        rows(SqlEngine.execute("SELECT k, COUNT(*) AS n FROM bad GROUP BY k", c, spill)))
      assertTrue(e.getMessage, e.getMessage.contains("'oops' cannot be read as"))
    }

  @Test def aStoppedBreakerClosesEveryInput(): Unit =
    releases("a stopped sort") { c =>
      val stopped = ExecutionOptions(stopped = () => true)
      assertThrows(classOf[QueryStoppedException], () => rows(SqlEngine.execute("SELECT k FROM c ORDER BY k", c, stopped)))
    }

  @Test def aQueryThatFailsAsItIsReadIsReleasedByClose(): Unit =
    releases("a scan that fails as it is read") { c =>
      val q = SqlEngine.execute("SELECT k, s FROM bad", c)
      try assertThrows(classOf[IllegalArgumentException], () => rows(q))
      finally q.close()
    }

  @Test def aQueryStoppedAsItIsReadIsReleasedByClose(): Unit =
    releases("a scan stopped as it is read") { c =>
      val stop = new AtomicBoolean(false)
      val q = SqlEngine.execute("SELECT k, s FROM c", c, ExecutionOptions(stopped = () => stop.get))
      try {
        assertTrue(q.batches.hasNext)
        val _ = q.batches.next()
        stop.set(true)
        assertThrows(classOf[QueryStoppedException], () => rows(q))
      } finally q.close()
    }

  @Test def aQueryReadInPartIsReleasedByClose(): Unit =
    releases("a spilled sort read in part") { c =>
      val q = SqlEngine.execute("SELECT k, s FROM c ORDER BY s, k", c, spill)
      try assertTrue(q.batches.hasNext) finally q.close()
    }

  @Test def closingTwiceAndClosingADrainedQueryDoNothing(): Unit =
    releases("a query closed twice") { c =>
      val q = SqlEngine.execute("SELECT k FROM c", c)
      assertEquals(12000, rows(q))
      q.close()
      q.close()
    }

  @Test def underTheAuditNothingIsLeftOpen(): Unit = {
    aLimitOverASpilledSortClosesItsRunsAndDeletesItsDirectory()
    aQueryThatFailsAsItIsReadIsReleasedByClose()
    assertEquals("the audit counts nothing open", Nil, LeakAudit.openNow)
  }
}
