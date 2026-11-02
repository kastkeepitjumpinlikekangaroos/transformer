package com.transformer.read.parquet

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path => HPath}
import org.apache.parquet.column.ParquetProperties
import org.apache.parquet.column.ParquetProperties.WriterVersion
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.SimpleGroupFactory
import org.apache.parquet.format.{FileMetaData, Util}
import org.apache.parquet.hadoop.{ParquetFileWriter, ParquetReader => MrReader}
import org.apache.parquet.hadoop.example.{ExampleParquetWriter, GroupReadSupport}
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.hadoop.util.{HadoopInputFile, HadoopOutputFile}
import org.apache.parquet.io.api.Binary
import org.apache.parquet.schema.{MessageType, MessageTypeParser}
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters._

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

  /** How parquet-mr lays out what it writes: the choices other writers make
    * differently, each of which changes the bytes a reader meets and none of
    * which changes what they mean. The defaults are parquet-mr's own.
    *
    *   - `dictionary` encodes a column chunk as a dictionary page plus indices,
    *     falling back to plain pages for the rest of the chunk once the
    *     dictionary passes `dictionaryPageSize` bytes, so a small size gives a
    *     chunk of both kinds.
    *   - `pageSize` (bytes) and `pageRowLimit` (rows) end a data page, so a
    *     small value of either splits a row group's values across many pages.
    *   - `writerVersion` `PARQUET_2_0` writes data pages v2, and plain values
    *     in the delta encodings rather than PLAIN.
    *   - `byteStreamSplit` stores FLOAT and DOUBLE values byte-plane by
    *     byte-plane.
    *   - `codec` compresses each page. */
  final case class Layout(
      dictionary: Boolean = ParquetProperties.DEFAULT_IS_DICTIONARY_ENABLED,
      dictionaryPageSize: Int = ParquetProperties.DEFAULT_DICTIONARY_PAGE_SIZE,
      pageSize: Int = ParquetProperties.DEFAULT_PAGE_SIZE,
      pageRowLimit: Int = ParquetProperties.DEFAULT_PAGE_ROW_COUNT_LIMIT,
      writerVersion: WriterVersion = WriterVersion.PARQUET_1_0,
      byteStreamSplit: Boolean = ParquetProperties.DEFAULT_IS_BYTE_STREAM_SPLIT_ENABLED,
      codec: CompressionCodecName = CompressionCodecName.UNCOMPRESSED) {

    /** Only what differs from parquet-mr's defaults, so a failure message
      * shows the choices that were made. */
    override def toString: String = {
      val d = Layout()
      Seq(
        if (dictionary != d.dictionary) Some(s"dictionary=$dictionary") else None,
        if (dictionaryPageSize != d.dictionaryPageSize) Some(s"dictionaryPageSize=$dictionaryPageSize") else None,
        if (pageSize != d.pageSize) Some(s"pageSize=$pageSize") else None,
        if (pageRowLimit != d.pageRowLimit) Some(s"pageRowLimit=$pageRowLimit") else None,
        if (writerVersion != d.writerVersion) Some(s"$writerVersion") else None,
        if (byteStreamSplit != d.byteStreamSplit) Some("byteStreamSplit") else None,
        if (codec != d.codec) Some(s"$codec") else None
      ).flatten.mkString("Layout(", ", ", ")")
    }
  }

  /** Write `rows` to a new file at `path`. Each row has one cell per field, in
    * schema order, holding the value parquet stores rather than the value it
    * stands for: an `Int` for INT32, a `Long` for INT64, a `Float`, `Double` or
    * `Boolean`, and an `Array[Byte]` for INT96, FIXED_LEN_BYTE_ARRAY and BINARY
    * (a `String` for BINARY is its UTF-8 bytes). A `null` leaves an OPTIONAL
    * field unset. parquet-mr decides where row groups end, which for a small
    * file is after the last row. */
  def write(path: Path, schema: MessageType, rows: Seq[Seq[Any]], layout: Layout = Layout()): Unit = {
    val factory = new SimpleGroupFactory(schema)
    val writer = ExampleParquetWriter.builder(new HPath(path.toUri))
      .withConf(conf)
      .withType(schema)
      .withDictionaryEncoding(layout.dictionary)
      .withDictionaryPageSize(layout.dictionaryPageSize)
      .withPageSize(layout.pageSize)
      .withPageRowCountLimit(layout.pageRowLimit)
      // A page ends only where parquet-mr checks its size, every 100 rows
      // by default, so a small `pageSize` needs a check after every row.
      .withMinRowCountForPageSizeCheck(if (layout.pageSize < ParquetProperties.DEFAULT_PAGE_SIZE) 1
        else ParquetProperties.DEFAULT_MINIMUM_RECORD_COUNT_FOR_CHECK)
      .withWriterVersion(layout.writerVersion)
      .withByteStreamSplitEncoding(layout.byteStreamSplit)
      .withCompressionCodec(layout.codec)
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

  /** Write a file at `path` whose row groups are exactly `groups`, in order:
    * each one's rows (as [[write]] takes them) laid out its own way. No group
    * may be empty, and no groups at all is a file with a schema and no rows.
    *
    * Each group is written as a file of its own, which a group this small
    * fills in one row group, and the files are joined by parquet-mr's
    * `appendFile`, which copies their column chunks and statistics as they
    * are and writes a footer listing them all: what `parquet-tools merge`
    * does. A real writer chooses where groups end by bytes buffered, and this
    * chooses by row, so a test can put a boundary anywhere. */
  def writeRowGroups(path: Path, schema: MessageType, groups: Seq[(Seq[Seq[Any]], Layout)]): Unit = {
    require(groups.forall(_._1.nonEmpty), "a row group holds at least one row")
    val parts = Files.createTempDirectory("external-parquet-groups-")
    try {
      val writer = new ParquetFileWriter(
        HadoopOutputFile.fromPath(new HPath(path.toUri), conf), schema, ParquetFileWriter.Mode.CREATE,
        // The groups are already cut; this bounds only padding, of which
        // there is none.
        org.apache.parquet.hadoop.ParquetWriter.DEFAULT_BLOCK_SIZE.toLong, 0,
        ParquetProperties.DEFAULT_COLUMN_INDEX_TRUNCATE_LENGTH, ParquetProperties.DEFAULT_STATISTICS_TRUNCATE_LENGTH,
        ParquetProperties.DEFAULT_PAGE_WRITE_CHECKSUM_ENABLED)
      writer.start()
      groups.zipWithIndex.foreach { case ((rows, layout), i) =>
        val part = parts.resolve(s"group-$i.parquet")
        write(part, schema, rows, layout)
        val blocks = footer(part).getBlocks.size
        require(blocks == 1, s"group $i was written as $blocks row groups, not one")
        writer.appendFile(HadoopInputFile.fromPath(new HPath(part.toUri), conf))
      }
      writer.end(java.util.Collections.emptyMap[String, String]())
    } finally {
      Files.list(parts).iterator.asScala.foreach(Files.deleteIfExists)
      Files.deleteIfExists(parts)
    }
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

  /** Rewrite the footer of the file at `path` in place: `edit` changes the
    * format's own Thrift record of it (`org.apache.parquet.format`), as a
    * writer that got a field wrong would have written it. The pages are left
    * as they are, so every offset the footer gives still holds. */
  def rewriteFooter(path: Path)(edit: FileMetaData => Unit): Unit = {
    val bytes = Files.readAllBytes(path)
    val footerLength = ByteBuffer.wrap(bytes, bytes.length - 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt
    val footerStart = bytes.length - 8 - footerLength
    val metadata = Util.readFileMetaData(new ByteArrayInputStream(bytes, footerStart, footerLength))
    edit(metadata)
    val footer = new ByteArrayOutputStream
    Util.writeFileMetaData(metadata, footer)
    val out = new ByteArrayOutputStream
    out.write(bytes, 0, footerStart)
    footer.writeTo(out)
    out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(footer.size).array)
    out.write("PAR1".getBytes(StandardCharsets.US_ASCII))
    Files.write(path, out.toByteArray)
  }

  /** The footer schema of `path`, as parquet-mr reads it. */
  def footerSchema(path: Path): MessageType = footer(path).getFileMetaData.getSchema

  /** The number of rows in each row group of `path`, in file order. */
  def rowGroupSizes(path: Path): Seq[Long] = footer(path).getBlocks.asScala.map(_.getRowCount).toSeq

  private def footer(path: Path): org.apache.parquet.hadoop.metadata.ParquetMetadata = {
    val r = org.apache.parquet.hadoop.ParquetFileReader.open(HadoopInputFile.fromPath(new HPath(path.toUri), conf))
    try r.getFooter finally r.close()
  }

  /** One Hadoop configuration for every call: building one reads Hadoop's
    * default resources, which costs more than writing a small file. */
  private lazy val conf: Configuration = {
    val c = new Configuration()
    c.set("fs.defaultFS", "file:///")
    // No `.crc` sidecars next to the fixtures.
    c.set("fs.file.impl", "org.apache.hadoop.fs.RawLocalFileSystem")
    c
  }
}
