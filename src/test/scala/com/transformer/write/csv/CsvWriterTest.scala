package com.transformer.write.csv

import com.transformer.core._
import com.transformer.read.csv.{CsvOptions, CsvReader}
import org.junit.Assert._
import org.junit.Test

import java.nio.file.{Files, Path}

class CsvWriterTest {

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

  @Test def writesHeaderAndSimpleRows(): Unit = {
    val s = Schema(Field("id", DataType.IntType), Field("name", DataType.StringType))
    val batch = makeBatch(s, Seq(Seq(1, "alice"), Seq(2, "bob")))
    val tmp = Files.createTempFile("out-", ".csv")
    Files.delete(tmp)
    CsvWriter.writeAll(tmp, s, Iterator(batch))
    val content = Files.readString(tmp)
    assertEquals("id,name\n1,alice\n2,bob\n", content)
  }

  @Test def quotesFieldsContainingDelimiterOrQuoteOrNewline(): Unit = {
    val s = Schema(Field("text", DataType.StringType))
    val batch = makeBatch(s, Seq(
      Seq("hello, world"),
      Seq("she said \"hi\""),
      Seq("line1\nline2")
    ))
    val tmp = Files.createTempFile("out-", ".csv")
    Files.delete(tmp)
    CsvWriter.writeAll(tmp, s, Iterator(batch))
    val content = Files.readString(tmp)
    val expected =
      "text\n\"hello, world\"\n\"she said \"\"hi\"\"\"\n\"line1\nline2\"\n"
    assertEquals(expected, content)
  }

  @Test def nullsWriteAsEmptyByDefault(): Unit = {
    val s = Schema(Field("id", DataType.IntType), Field("name", DataType.StringType))
    val batch = makeBatch(s, Seq(Seq(1, null), Seq(null, "bob")))
    val tmp = Files.createTempFile("out-", ".csv")
    Files.delete(tmp)
    CsvWriter.writeAll(tmp, s, Iterator(batch))
    val content = Files.readString(tmp)
    assertEquals("id,name\n1,\n,bob\n", content)
  }

  @Test def roundtripThroughCsvReader(): Unit = {
    val s = Schema(Field("id", DataType.IntType), Field("score", DataType.DoubleType), Field("name", DataType.StringType))
    val rows = Seq(
      Seq(1, 9.5, "alice"),
      Seq(2, 7.25, "bob, jr."),
      Seq(3, 0.0, "with \"quote\"")
    )
    val batch = makeBatch(s, rows)
    val tmp = Files.createTempFile("out-", ".csv")
    Files.delete(tmp)
    CsvWriter.writeAll(tmp, s, Iterator(batch))

    val r = CsvReader.fromPath(tmp.toString, CsvOptions())
    assertEquals(s.length, r.schema.length)
    assertEquals(DataType.IntType, r.schema.fields(0).dataType)
    assertEquals(DataType.DoubleType, r.schema.fields(1).dataType)
    assertEquals(DataType.StringType, r.schema.fields(2).dataType)
    val out = r.readPartition(0).next()
    assertEquals(3, out.numRows)
    assertEquals(1, out.column(0).asInstanceOf[IntVector].get(0))
    assertEquals(9.5, out.column(1).asInstanceOf[DoubleVector].get(0), 1e-9)
    assertEquals("alice", out.column(2).asInstanceOf[StringVector].get(0))
    assertEquals("bob, jr.", out.column(2).asInstanceOf[StringVector].get(1))
    assertEquals("with \"quote\"", out.column(2).asInstanceOf[StringVector].get(2))
  }

  @Test def abortLeavesNoFileAtTarget(): Unit = {
    val s = Schema(Field("x", DataType.IntType))
    val tmp = Files.createTempFile("out-", ".csv")
    Files.delete(tmp)
    val w = new CsvWriter(tmp, s)
    w.write(makeBatch(s, Seq(Seq(1), Seq(2))))
    w.abort()
    assertFalse(Files.exists(tmp))
  }
}
