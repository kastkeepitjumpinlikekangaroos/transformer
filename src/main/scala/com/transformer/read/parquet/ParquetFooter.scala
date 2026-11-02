package com.transformer.read.parquet

import org.apache.parquet.crypto.ParquetCryptoRuntimeException
import org.apache.parquet.format.{CompressionCodec, Encoding, FileMetaData, LogicalType, RowGroup, SchemaElement, Type, Util}
import org.apache.parquet.format.converter.ParquetMetadataConverter

import java.io.ByteArrayInputStream
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, StandardOpenOption}
import java.util.Locale

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** Why parquet-mr could not read a file's footer, in the reader's words.
  *
  * parquet-mr fails on a footer it cannot read with whatever its code met
  * first: a `NullPointerException` for a logical type newer than it, Thrift's
  * dump of a struct for an enum value it does not know, its own text for an
  * annotation on the wrong physical type, or for an encrypted file. None of
  * those names the file, and some name nothing the file says. So when the
  * footer does not read, the reader reads it again here, its Thrift walked
  * leniently ([[CompactThrift]]: a union member or an enum value is a number,
  * known or not), and says what it finds, naming the file and, where one is
  * at fault, the column. The library's exception stays the cause.
  */
private[parquet] object ParquetFooter {

  /** Logical types the format added after the parquet-mr this reads with,
    * by their Thrift id, so a refusal names them rather than a number. */
  private val LaterLogicalTypes: Map[Int, String] = Map(16 -> "VARIANT", 17 -> "GEOMETRY", 18 -> "GEOGRAPHY")

  /** Encodings the format added after the parquet-mr this reads with. */
  private val LaterEncodings: Map[Int, String] = Map(10 -> "ALP")

  /** The refusal for `file`, named `label` in messages, whose footer
    * parquet-mr failed to read with `thrown`. */
  def refusal(label: String, file: Path, thrown: Throwable): ParquetReader.Unreadable = {
    def refuse(what: String) = new ParquetReader.Unreadable(s"Parquet file '$label' $what", thrown)
    if (ParquetReader.causes(thrown).exists(_.isInstanceOf[ParquetCryptoRuntimeException]))
      return refuse(Encrypted)
    val tail = try Some(readTail(file)) catch { case NonFatal(_) => None }
    tail match {
      case None => refuse("cannot be read")
      case Some(Tail(_, magic, _)) if magic == "PARE" => refuse(Encrypted)
      case Some(Tail(size, magic, _)) if size < 12 || magic != "PAR1" =>
        refuse("is not a parquet file: it does not end in PAR1, parquet's magic bytes")
      case Some(Tail(_, _, None)) => refuse(NotMetadata)
      case Some(Tail(_, _, Some(footer))) =>
        val walked = try Some(CompactThrift.struct(footer)) catch { case NonFatal(_) => None }
        walked.flatMap(newerThanTheLibrary).orElse(annotationNotAllowed(footer)) match {
          case Some(what) => refuse(what)
          case None => refuse(NotMetadata)
        }
    }
  }

  val Encrypted: String = "is encrypted, and transformer reads no encrypted parquet"

  private val NotMetadata: String = "has a footer that is not valid parquet metadata"

  /** A file's size, its last four bytes, and its footer's bytes when the
    * length before the magic gives a footer that fits the file. */
  private final case class Tail(size: Long, magic: String, footer: Option[Array[Byte]])

  private def readTail(file: Path): Tail = {
    val channel = FileChannel.open(file, StandardOpenOption.READ)
    try {
      val size = channel.size
      if (size < 8) return Tail(size, "", None)
      val end = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
      channel.read(end, size - 8)
      val length = end.getInt(0).toLong
      val magic = new String(end.array, 4, 4, StandardCharsets.ISO_8859_1)
      if (length <= 0 || length > size - 12) return Tail(size, magic, None)
      val footer = ByteBuffer.allocate(length.toInt)
      var at = size - 8 - length
      while (footer.hasRemaining) {
        val n = channel.read(footer, at)
        if (n < 0) return Tail(size, magic, None)
        at += n
      }
      Tail(size, magic, Some(footer.array))
    } finally channel.close()
  }

  /** What in a footer walked as `fmd` the library does not know: a logical
    * type, an encoding or a codec a writer newer than it uses, named with the
    * column it is on. */
  private def newerThanTheLibrary(fmd: Map[Int, Any]): Option[String] = {
    val elements = list(fmd.get(2)).collect { case e: Map[Int, Any] @unchecked => e }
    val logical = elements.iterator.flatMap { e =>
      struct(e.get(10)).flatMap(_.keys.headOption).filter(id => LogicalType._Fields.findByThriftId(id) == null).map { id =>
        val annotation = LaterLogicalTypes.getOrElse(id, s"with a logical type this reader does not know (numbered $id)")
        s"column '${text(e.get(4))}' is ${physical(e)} $annotation, which has no transformer type"
      }
    }.nextOption()
    def chunks: Iterator[Map[Int, Any]] = list(fmd.get(4)).iterator
      .flatMap(g => list(struct(g).flatMap(_.get(1))))
      .flatMap(c => struct(c).flatMap(chunk => struct(chunk.get(3))))
    def column(meta: Map[Int, Any]) = list(meta.get(3)).map(text).mkString(".")
    def unknownEncoding(meta: Map[Int, Any]): Option[Int] =
      (list(meta.get(2)) ++ list(meta.get(13)).flatMap(s => struct(s).flatMap(_.get(2))))
        .collect { case v: Long => v.toInt }.find(v => Encoding.findByValue(v) == null)
    val encoding = chunks.flatMap { meta =>
      unknownEncoding(meta).map { v =>
        s"column '${column(meta)}' is encoded ${LaterEncodings.getOrElse(v, s"in the encoding numbered $v")}, " +
          "which this reader does not decode"
      }
    }.nextOption()
    val codec = chunks.flatMap { meta =>
      meta.get(4).collect { case v: Long if CompressionCodec.findByValue(v.toInt) == null =>
        s"column '${column(meta)}' is compressed with the codec numbered $v, which this reader does not have"
      }
    }.nextOption()
    val leaves = leafPaths(elements)
    val stray = chunks.map(column).find(path => leaves.nonEmpty && !leaves(path)).map { path =>
      s"has a column chunk of '$path', which its schema does not have"
    }
    logical.orElse(encoding).orElse(codec).orElse(stray)
  }

  /** The dotted paths of the leaves of a schema given as the format lists it:
    * the root, then each element followed by its children, depth first. None
    * when the list does not hold together. */
  private def leafPaths(elements: Vector[Map[Int, Any]]): Set[String] = {
    val out = Set.newBuilder[String]
    var next = 1
    def children(e: Map[Int, Any]): Int = e.get(5) match {
      case Some(n: Long) if n >= 0 && n <= elements.length => n.toInt
      case _ => 0
    }
    def walk(prefix: String, count: Int, depth: Int): Boolean = {
      var i = 0
      while (i < count) {
        if (next >= elements.length || depth > CompactThrift.MaxDepth) return false
        val e = elements(next)
        next += 1
        val path = prefix + text(e.get(4))
        val n = children(e)
        if (n == 0) out += path
        else if (!walk(path + ".", n, depth + 1)) return false
        i += 1
      }
      true
    }
    if (elements.isEmpty || !walk("", children(elements.head), 0)) Set.empty else out.result()
  }

  /** A column whose annotation the format does not allow on its physical
    * type (a `UUID` on an `INT32`, a `TIMESTAMP` on a `FIXED_LEN_BYTE_ARRAY`),
    * which parquet-mr refuses as it converts the schema: found by converting
    * each column alone, with parquet-mr's own rules. */
  private def annotationNotAllowed(footer: Array[Byte]): Option[String] = {
    val fmd = try Util.readFileMetaData(new ByteArrayInputStream(footer)) catch { case NonFatal(_) => return None }
    val converter = new ParquetMetadataConverter()
    Option(fmd.getSchema).toSeq.flatMap(_.asScala).filter(e => !e.isSetNum_children || e.getNum_children == 0).find { leaf =>
      val root = new SchemaElement("schema")
      root.setNum_children(1)
      val alone = new FileMetaData(1, List(root, leaf).asJava, 0L, java.util.List.of[RowGroup]())
      try { converter.fromParquetMetadata(alone); false } catch { case NonFatal(_) => true }
    }.map { leaf =>
      val annotation =
        if (leaf.isSetLogicalType && leaf.getLogicalType.getSetField != null) leaf.getLogicalType.getSetField.getFieldName
        else if (leaf.isSetConverted_type) leaf.getConverted_type.name
        else "its annotation"
      val physicalType = physical(Option(leaf.getType).map(_.getValue.toLong), Some(leaf.getType_length.toLong))
      s"column '${leaf.getName}' is $physicalType annotated $annotation, which the format does not allow"
    }
  }

  /** A walked schema element's physical type, as parquet's schema text
    * writes it. */
  private def physical(e: Map[Int, Any]): String =
    physical(e.get(1).collect { case t: Long => t }, e.get(2).collect { case n: Long => n })

  /** The physical type numbered `t`, of `length` bytes when it is fixed, as
    * parquet's schema text writes it (`binary`, `fixed_len_byte_array(16)`);
    * none is a group. */
  private def physical(t: Option[Long], length: Option[Long]): String = t match {
    case Some(n) =>
      Option(Type.findByValue(n.toInt)) match {
        case Some(Type.BYTE_ARRAY) => "binary"
        case Some(Type.FIXED_LEN_BYTE_ARRAY) => s"fixed_len_byte_array(${length.getOrElse("?")})"
        case Some(other) => other.name.toLowerCase(Locale.ROOT)
        case None => s"of the physical type numbered $n"
      }
    case None => "a group"
  }

  private def list(v: Option[Any]): Vector[Any] = v match {
    case Some(xs: Vector[Any] @unchecked) => xs
    case _ => Vector.empty
  }

  private def struct(v: Any): Option[Map[Int, Any]] = v match {
    case Some(m: Map[Int, Any] @unchecked) => Some(m)
    case m: Map[Int, Any] @unchecked => Some(m)
    case _ => None
  }

  private def text(v: Any): String = v match {
    case Some(b: Array[Byte]) => new String(b, StandardCharsets.UTF_8)
    case b: Array[Byte] => new String(b, StandardCharsets.UTF_8)
    case _ => "?"
  }
}

/** Thrift's compact protocol, as parquet writes its footer, read into plain
  * values: a struct as a map from field id to value, a list or set as a
  * vector, an integer of any width as a `Long`, binary as bytes. Nothing is
  * checked against a schema, so a union member or an enum value newer than
  * parquet-mr's generated classes reads as its number, where those classes
  * drop it or fail. Every length is bounded by the bytes left, and nesting by
  * [[MaxDepth]], so corrupt bytes fail with [[Malformed]], never an
  * allocation of what a corrupt length says.
  */
private[parquet] object CompactThrift {

  final class Malformed(message: String) extends Exception(message)

  val MaxDepth: Int = 64

  def struct(bytes: Array[Byte]): Map[Int, Any] = new Reader(bytes).struct(0)

  private final class Reader(b: Array[Byte]) {
    private var pos = 0

    private def byte(): Int = {
      if (pos >= b.length) throw new Malformed("the bytes end inside a value")
      val v = b(pos) & 0xff
      pos += 1
      v
    }

    private def varint(): Long = {
      var result = 0L
      var shift = 0
      while (shift < 64) {
        val x = byte()
        result |= (x & 0x7fL) << shift
        if ((x & 0x80) == 0) return result
        shift += 7
      }
      throw new Malformed("a varint longer than ten bytes")
    }

    private def zigzag(): Long = {
      val n = varint()
      (n >>> 1) ^ -(n & 1)
    }

    /** A count that the bytes left could hold, each item a byte at least. */
    private def count(n: Long): Int = {
      if (n < 0 || n > b.length - pos) throw new Malformed(s"a count of $n past the bytes left")
      n.toInt
    }

    def struct(depth: Int): Map[Int, Any] = {
      if (depth > MaxDepth) throw new Malformed("structs nested too deep")
      val out = Map.newBuilder[Int, Any]
      var last = 0
      var h = byte()
      while (h != 0) {
        val delta = h >>> 4
        val id = if (delta == 0) zigzag().toInt else last + delta
        last = id
        out += id -> value(h & 0x0f, depth)
        h = byte()
      }
      out.result()
    }

    private def value(t: Int, depth: Int): Any = t match {
      case 1 => true
      case 2 => false
      case 3 => byte().toByte.toLong
      case 4 | 5 | 6 => zigzag()
      case 7 =>
        if (b.length - pos < 8) throw new Malformed("a double past the bytes left")
        val v = ByteBuffer.wrap(b, pos, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble
        pos += 8
        v
      case 8 =>
        val n = count(varint())
        val v = java.util.Arrays.copyOfRange(b, pos, pos + n)
        pos += n
        v
      case 9 | 10 =>
        val h = byte()
        val n = count(if ((h >>> 4) == 15) varint() else (h >>> 4).toLong)
        val et = h & 0x0f
        Vector.fill(n)(if (et == 1 || et == 2) byte() == 1 else value(et, depth + 1))
      case 11 =>
        val n = count(varint())
        if (n == 0) Vector.empty
        else {
          val kv = byte()
          Vector.fill(n)((value(kv >>> 4, depth + 1), value(kv & 0x0f, depth + 1)))
        }
      case 12 => struct(depth + 1)
      case other => throw new Malformed(s"a value of the unknown type $other")
    }
  }
}
