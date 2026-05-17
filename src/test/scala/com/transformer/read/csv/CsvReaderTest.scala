package com.transformer.read.csv

import com.transformer.core._
import org.junit.Assert._
import org.junit.Test

import java.nio.file.{Files, Path}

class CsvReaderTest {

  private def tmpCsv(name: String, contents: String): Path = {
    val dir = Files.createTempDirectory("transformer-csv-")
    val p = dir.resolve(name)
    Files.writeString(p, contents)
    p
  }

  @Test def inferSchemaIntAndString(): Unit = {
    val p = tmpCsv("data.csv", "id,name\n1,alice\n2,bob\n3,charlie\n")
    val r = CsvReader.fromPath(p.toString, CsvOptions())
    assertEquals(2, r.schema.length)
    assertEquals(DataType.IntType, r.schema.fields(0).dataType)
    assertEquals(DataType.StringType, r.schema.fields(1).dataType)
    val batches = r.readPartition(0).toList
    assertEquals(1, batches.size)
    val b = batches.head
    assertEquals(3, b.numRows)
    assertEquals(1, b.column(0).asInstanceOf[IntVector].get(0))
    assertEquals("alice", b.column(1).asInstanceOf[StringVector].get(0))
    assertEquals(3, b.column(0).asInstanceOf[IntVector].get(2))
    assertEquals("charlie", b.column(1).asInstanceOf[StringVector].get(2))
  }

  @Test def inferSchemaWidensIntToDouble(): Unit = {
    val p = tmpCsv("nums.csv", "x\n1\n2\n3.14\n4\n")
    val r = CsvReader.fromPath(p.toString, CsvOptions())
    assertEquals(DataType.DoubleType, r.schema.fields(0).dataType)
  }

  @Test def inferSchemaBoolean(): Unit = {
    val p = tmpCsv("b.csv", "flag\ntrue\nfalse\nTRUE\nFalse\n")
    val r = CsvReader.fromPath(p.toString, CsvOptions())
    assertEquals(DataType.BooleanType, r.schema.fields(0).dataType)
  }

  @Test def inferSchemaDate(): Unit = {
    val p = tmpCsv("d.csv", "d\n2026-01-01\n2025-12-31\n")
    val r = CsvReader.fromPath(p.toString, CsvOptions())
    assertEquals(DataType.DateType, r.schema.fields(0).dataType)
  }

  @Test def nullValueHandling(): Unit = {
    val p = tmpCsv("nulls.csv", "id,name\n1,alice\n2,\n3,charlie\n")
    val r = CsvReader.fromPath(p.toString, CsvOptions())
    val b = r.readPartition(0).next()
    assertFalse(b.column(1).isNull(0))
    assertTrue(b.column(1).isNull(1))
    assertFalse(b.column(1).isNull(2))
  }

  @Test def explicitColumnsNoInfer(): Unit = {
    val p = tmpCsv("e.csv", "1,alice\n2,bob\n")
    val schema = Seq(Field("id", DataType.IntType), Field("name", DataType.StringType))
    val opts = CsvOptions(inferSchema = false, header = false, columns = Some(schema))
    val r = CsvReader.fromPath(p.toString, opts)
    val b = r.readPartition(0).next()
    assertEquals(2, b.numRows)
    assertEquals(1, b.column(0).asInstanceOf[IntVector].get(0))
    assertEquals("bob", b.column(1).asInstanceOf[StringVector].get(1))
  }

  @Test def quotedFieldsWithDelimiter(): Unit = {
    val p = tmpCsv("q.csv", "name,note\n\"smith, john\",hello\n\"doe, jane\",\"hi, there\"\n")
    val r = CsvReader.fromPath(p.toString, CsvOptions())
    val b = r.readPartition(0).next()
    assertEquals("smith, john", b.column(0).asInstanceOf[StringVector].get(0))
    assertEquals("hi, there", b.column(1).asInstanceOf[StringVector].get(1))
  }

  @Test def globExpansion(): Unit = {
    val dir = Files.createTempDirectory("transformer-csv-glob-")
    Files.writeString(dir.resolve("a.csv"), "x\n1\n")
    Files.writeString(dir.resolve("b.csv"), "x\n2\n")
    Files.writeString(dir.resolve("ignore.txt"), "y\n3\n")
    val r = CsvReader.fromPath(dir.toString + "/*.csv", CsvOptions())
    assertEquals(2, r.numPartitions)
    val all = (0 until r.numPartitions).flatMap { p =>
      val b = r.readPartition(p).next()
      (0 until b.numRows).map(b.column(0).asInstanceOf[IntVector].get)
    }.sorted
    assertEquals(Seq(1, 2), all)
  }

  @Test def globMatchesBareDirectory(): Unit = {
    val dir = Files.createTempDirectory("transformer-csv-dir-")
    Files.writeString(dir.resolve("a.csv"), "x\n5\n")
    Files.writeString(dir.resolve("b.csv"), "x\n10\n")
    val r = CsvReader.fromPath(dir.toString, CsvOptions())
    assertEquals(2, r.numPartitions)
  }

  @Test def batchSplittingAtCapacity(): Unit = {
    val sb = new StringBuilder("x\n")
    (0 until 10).foreach(i => sb.append(i).append('\n'))
    val p = tmpCsv("many.csv", sb.toString)
    val opts = CsvOptions(batchSize = 4)
    val r = CsvReader.fromPath(p.toString, opts)
    val batches = r.readPartition(0).toList
    // 10 rows / batch of 4 → 4 + 4 + 2 = 3 batches
    assertEquals(3, batches.size)
    assertEquals(4, batches(0).numRows)
    assertEquals(4, batches(1).numRows)
    assertEquals(2, batches(2).numRows)
  }

  @Test def emptyMatchThrows(): Unit = {
    try {
      CsvReader.fromPath("/tmp/does-not-exist-" + System.nanoTime() + "/*.csv", CsvOptions())
      fail("expected exception")
    } catch { case _: IllegalArgumentException => () }
  }
}
