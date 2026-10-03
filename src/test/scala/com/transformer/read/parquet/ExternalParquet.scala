package com.transformer.read.parquet

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path => HPath}
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.hadoop.{ParquetReader => MrReader}
import org.apache.parquet.hadoop.example.{ExampleParquetWriter, GroupReadSupport}
import org.apache.parquet.io.api.Binary
import org.apache.parquet.schema.{MessageType, MessageTypeParser}
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName

import java.nio.file.Path
import scala.collection.mutable

/** Parquet files written and read by parquet-mr's own example object model,
  * never by transformer's `ParquetWriter` or `ParquetReader`.
  *
  * A file our writer produced cannot test our reader's reading of the format:
  * the two share every assumption, so they agree with each other while both
  * disagree with a file neither wrote. This writes the files other tools write
  * (a `MILLIS` timestamp, a decimal stored four ways, an unsigned integer), and
  * reads files back the way another tool would, so a test can stand our reader
  * or our writer next to an implementation that is not ours.
  */
object ExternalParquet {

  /** Parquet's schema text, as a footer prints it:
    * `message m { optional int64 ts (TIMESTAMP(MILLIS,true)); }`. */
  def schema(text: String): MessageType = MessageTypeParser.parseMessageType(text)

  /** Write `rows` to a new file at `path`. Each row has one cell per field, in
    * schema order, holding the value parquet stores rather than the value it
    * stands for: an `Int` for INT32, a `Long` for INT64, a `Float`, `Double` or
    * `Boolean`, and an `Array[Byte]` for INT96, FIXED_LEN_BYTE_ARRAY and BINARY
    * (a `String` for BINARY is its UTF-8 bytes). A `null` leaves an OPTIONAL
    * field unset. */
  def write(path: Path, schema: MessageType, rows: Seq[Seq[Any]]): Unit = {
    val factory = new SimpleGroupFactory(schema)
    val writer = ExampleParquetWriter.builder(new HPath(path.toUri))
      .withConf(conf)
      .withType(schema)
      .build()
    try {
      rows.foreach { row =>
        require(row.length == schema.getFieldCount,
          s"row has ${row.length} cells for ${schema.getFieldCount} fields: $row")
        val g = factory.newGroup()
        row.iterator.zipWithIndex.foreach {
          case (null, _) => ()
          case (v: Int, i) => g.add(i, v)
          case (v: Long, i) => g.add(i, v)
          case (v: Float, i) => g.add(i, v)
          case (v: Double, i) => g.add(i, v)
          case (v: Boolean, i) => g.add(i, v)
          case (v: String, i) => g.add(i, Binary.fromString(v))
          case (v: Array[Byte], i) => g.add(i, Binary.fromConstantByteArray(v))
          case (v, i) => throw new IllegalArgumentException(
            s"no parquet value for ${v.getClass.getName} in field '${schema.getFieldName(i)}'")
        }
        writer.write(g)
      }
    } finally writer.close()
  }

  /** Every row of `path`, each cell as parquet-mr renders the stored value for
    * the type the footer declares (its own decimal and timestamp stringifiers),
    * or null for an unset OPTIONAL field. */
  def read(path: Path): Seq[Seq[String]] = {
    val reader = MrReader.builder(new GroupReadSupport(), new HPath(path.toUri)).withConf(conf).build()
    val out = mutable.ArrayBuffer.empty[Seq[String]]
    try {
      var g: Group = reader.read()
      while (g != null) {
        val fields = g.getType.getFields
        out += (0 until fields.size).map { i =>
          if (g.getFieldRepetitionCount(i) == 0) null
          else {
            val pt = fields.get(i).asPrimitiveType
            val text = pt.stringifier
            pt.getPrimitiveTypeName match {
              case PrimitiveTypeName.INT32 => text.stringify(g.getInteger(i, 0))
              case PrimitiveTypeName.INT64 => text.stringify(g.getLong(i, 0))
              case PrimitiveTypeName.FLOAT => text.stringify(g.getFloat(i, 0))
              case PrimitiveTypeName.DOUBLE => text.stringify(g.getDouble(i, 0))
              case PrimitiveTypeName.BOOLEAN => text.stringify(g.getBoolean(i, 0))
              case PrimitiveTypeName.INT96 => text.stringify(g.getInt96(i, 0))
              case PrimitiveTypeName.BINARY | PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY =>
                text.stringify(g.getBinary(i, 0))
            }
          }
        }
        g = reader.read()
      }
    } finally reader.close()
    out.toSeq
  }

  /** The footer schema of `path`, as parquet-mr reads it. */
  def footerSchema(path: Path): MessageType = {
    val r = org.apache.parquet.hadoop.ParquetFileReader.open(
      org.apache.parquet.hadoop.util.HadoopInputFile.fromPath(new HPath(path.toUri), conf))
    try r.getFooter.getFileMetaData.getSchema finally r.close()
  }

  private def conf: Configuration = {
    val c = new Configuration()
    c.set("fs.defaultFS", "file:///")
    // No `.crc` sidecars next to the fixtures.
    c.set("fs.file.impl", "org.apache.hadoop.fs.RawLocalFileSystem")
    c
  }
}
