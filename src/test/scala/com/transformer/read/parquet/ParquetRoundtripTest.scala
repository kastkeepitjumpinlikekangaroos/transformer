package com.transformer.read.parquet

import com.transformer.core._
import com.transformer.job.{DataJob, InputFilePath, OutputFilePath, SQLTask}
import com.transformer.write.parquet.{ParquetWriter => TParquetWriter}
import org.junit.Assert._
import org.junit.Test

import java.nio.file.{Files, Path}

class ParquetRoundtripTest {

  private def makeBatch(schema: Schema, rows: Seq[Seq[Any]]): ColumnarBatch = {
    val b = new ColumnarBatch(schema, rows.length max 1)
    rows.zipWithIndex.foreach { case (row, i) =>
      row.zipWithIndex.foreach { case (v, c) =>
        if (v == null) b.column(c).setNull(i) else b.column(c).setBoxed(i, v)
      }
    }
    b.setNumRows(rows.length)
    b
  }

  private def collectRows(view: CatalogView): Seq[Map[String, Any]] = {
    val buf = scala.collection.mutable.ArrayBuffer.empty[Map[String, Any]]
    (0 until view.numPartitions).foreach { p =>
      val it = view.readPartition(p)
      while (it.hasNext) {
        val b = it.next()
        var r = 0
        while (r < b.numRows) {
          val m = view.schema.fieldNames.zipWithIndex.map { case (n, i) =>
            n -> (if (b.column(i).isNull(r)) null else b.column(i).getBoxed(r))
          }.toMap
          buf += m
          r += 1
        }
      }
    }
    buf.toSeq
  }

  @Test def writeAndReadBackAllSupportedTypes(): Unit = {
    val schema = Schema(
      Field("i", DataType.IntType),
      Field("l", DataType.LongType),
      Field("d", DataType.DoubleType),
      Field("b", DataType.BooleanType),
      Field("s", DataType.StringType)
    )
    val batch = makeBatch(schema, Seq(
      Seq(1, 100L, 1.5, true, "alice"),
      Seq(2, 200L, 2.5, false, "bob"),
      Seq(null, 300L, null, true, null)
    ))
    val tmp = Files.createTempFile("transformer-pq-", ".parquet")
    Files.delete(tmp)
    TParquetWriter.writeAll(tmp, schema, Iterator(batch))
    assertTrue(Files.exists(tmp))

    val reader = ParquetReader.fromPath(tmp.toString)
    assertEquals(schema.fieldNames, reader.schema.fieldNames)
    val rows = collectRows(reader)
    assertEquals(3, rows.size)
    assertEquals(1, rows.head("i"))
    assertEquals(100L, rows.head("l"))
    assertEquals(1.5, rows.head("d"))
    assertEquals(true, rows.head("b"))
    assertEquals("alice", rows.head("s"))
    assertNull(rows(2)("i"))
    assertNull(rows(2)("d"))
    assertNull(rows(2)("s"))
  }

  @Test def dataJobWithParquetInputAndOutput(): Unit = {
    // Force-load Parquet hooks (via touching the object).
    ParquetSupport.init()

    val inSchema = Schema(
      Field("id", DataType.IntType),
      Field("name", DataType.StringType),
      Field("score", DataType.DoubleType)
    )
    val inDir = Files.createTempDirectory("pq-in-")
    val inFile = inDir.resolve("data.parquet")
    val batch = makeBatch(inSchema, Seq(
      Seq(1, "alice", 10.0),
      Seq(2, "bob", 20.0),
      Seq(3, "alice", 30.0),
      Seq(4, "bob", 40.0)
    ))
    TParquetWriter.writeAll(inFile, inSchema, Iterator(batch))
    val outDir = Files.createTempDirectory("pq-out-")
    val outFile = outDir.resolve("totals.parquet")

    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.parquet", viewName = "scores")),
      sql = Seq(SQLTask(
        sqlString = Some("SELECT name, SUM(score) AS total FROM scores GROUP BY name ORDER BY name"),
        outputFile = Some(OutputFilePath(outFile.toString))
      ))
    )
    val result = job.run()
    assertTrue(result.error.getOrElse("(no error)"), result.succeeded)
    assertTrue(Files.exists(outFile))

    val out = ParquetReader.fromPath(outFile.toString)
    val rows = collectRows(out)
    assertEquals(2, rows.size)
    assertEquals("alice", rows.head("name"))
    assertEquals(40.0, rows.head("total"))
    assertEquals("bob", rows(1)("name"))
    assertEquals(60.0, rows(1)("total"))
  }
}
