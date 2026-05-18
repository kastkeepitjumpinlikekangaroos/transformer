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
    val outBase = Files.createTempDirectory("pq-out-")
    val outDir = outBase.resolve("totals")

    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.parquet", viewName = "scores")),
      sql = Seq(SQLTask(
        sqlString = Some("SELECT name, SUM(score) AS total FROM scores GROUP BY name ORDER BY name"),
        outputFile = Some(OutputFilePath(outDir.toString, format = Some("parquet")))
      ))
    )
    val result = job.run()
    assertTrue(result.error.getOrElse("(no error)"), result.succeeded)
    assertTrue(s"$outDir should be a directory", Files.isDirectory(outDir))

    val out = ParquetReader.fromPath(outDir.toString)
    val rows = collectRows(out)
    assertEquals(2, rows.size)
    assertEquals("alice", rows.head("name"))
    assertEquals(40.0, rows.head("total"))
    assertEquals("bob", rows(1)("name"))
    assertEquals(60.0, rows(1)("total"))
  }

  @Test def parquetMultiPartitionInputProducesMultipleOutputFiles(): Unit = {
    ParquetSupport.init()
    val schema = Schema(Field("v", DataType.IntType))

    val inDir = Files.createTempDirectory("pq-multi-in-")
    // Two files => two source partitions => two part files in the output dir.
    val rows1 = makeBatch(schema, Seq(Seq(1), Seq(2)))
    val rows2 = makeBatch(schema, Seq(Seq(3), Seq(4)))
    TParquetWriter.writeAll(inDir.resolve("a.parquet"), schema, Iterator(rows1))
    TParquetWriter.writeAll(inDir.resolve("b.parquet"), schema, Iterator(rows2))

    val outDir = Files.createTempDirectory("pq-multi-out-").resolve("doubled")
    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.parquet", viewName = "src")),
      sql = Seq(SQLTask(
        sqlString = Some("SELECT v * 2 AS w FROM src"),
        outputFile = Some(OutputFilePath(outDir.toString, format = Some("parquet")))
      ))
    )
    val result = job.run()
    assertTrue(result.error.getOrElse("(no error)"), result.succeeded)
    val parts = Files.list(outDir).iterator()
    var count = 0
    while (parts.hasNext) {
      val p = parts.next()
      if (p.getFileName.toString.startsWith("part-")) count += 1
    }
    assertEquals(2, count)
    val out = ParquetReader.fromPath(outDir.toString)
    val values = collectRows(out).map(_("w").asInstanceOf[Int]).sorted
    assertEquals(Vector(2, 4, 6, 8), values)
  }

  @Test def parquetReaderExposesExactRowCount(): Unit = {
    val schema = Schema(Field("v", DataType.IntType))
    val inDir = Files.createTempDirectory("pq-rc-in-")
    TParquetWriter.writeAll(inDir.resolve("a.parquet"), schema,
      Iterator(makeBatch(schema, Seq(Seq(1), Seq(2), Seq(3)))))
    TParquetWriter.writeAll(inDir.resolve("b.parquet"), schema,
      Iterator(makeBatch(schema, Seq(Seq(4), Seq(5)))))
    val reader = ParquetReader.fromPath(inDir.toString + "/*.parquet")
    assertEquals(Some(5L), reader.exactRowCount)
  }

  @Test def parquetRowGroupSizeOptionRoundTrips(): Unit = {
    // Write 3072 rows with a tiny row-group cap so multiple row groups end up
    // in the same file. Verify the read path stitches them back together —
    // partition unit is one file, exactRowCount comes from footer metadata,
    // and the full row content reads cleanly.
    val schema = Schema(Field("v", DataType.IntType))
    val rows = (0 until 1024).map(i => Seq[Any](i))
    val batch = makeBatch(schema, rows)
    val target = Files.createTempFile("transformer-pq-rg-", ".parquet")
    Files.delete(target)
    TParquetWriter.writeAll(target, schema, Iterator(batch, batch, batch),
      options = Map("parquet_row_group_size" -> "1024"))  // 1KB — well below one batch

    val reader = ParquetReader.fromPath(target.toString)
    assertEquals(1, reader.numPartitions)            // partition = file
    assertEquals(Some(3072L), reader.exactRowCount)  // metadata sum across row groups
    val all = collectRows(reader)
    assertEquals(3072, all.size)
  }

  @Test def parquetPartitionEqualsFile(): Unit = {
    // Even when input files contain multiple row groups (tiny row-group-size),
    // ParquetReader exposes exactly one partition per file. This is the contract
    // that prevents the old O(rowsBefore²) seek-by-skipping-rows pattern.
    val schema = Schema(Field("v", DataType.IntType))
    val rows = (0 until 256).map(i => Seq[Any](i))
    val opts = Map("parquet_row_group_size" -> "256")
    val inDir = Files.createTempDirectory("pq-part-eq-file-")
    TParquetWriter.writeAll(inDir.resolve("a.parquet"), schema,
      Iterator(makeBatch(schema, rows), makeBatch(schema, rows)), opts)
    TParquetWriter.writeAll(inDir.resolve("b.parquet"), schema,
      Iterator(makeBatch(schema, rows), makeBatch(schema, rows), makeBatch(schema, rows)), opts)
    val reader = ParquetReader.fromPath(inDir.toString + "/*.parquet")
    assertEquals(2, reader.numPartitions)
    assertEquals(Some((256 * 2 + 256 * 3).toLong), reader.exactRowCount)
  }
}
