package com.transformer.read.parquet

import com.transformer.core.{DataType, Field, Schema}

import org.apache.parquet.schema.{LogicalTypeAnnotation, MessageType, Type}
import org.apache.parquet.schema.LogicalTypeAnnotation._
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName

import scala.jdk.CollectionConverters._

/** How a parquet file's columns are read: the engine type each one reads as,
  * and what each stored value goes through to become a value of that type.
  *
  * Decided per file, from that file's own footer. Two files of one glob can
  * store a column differently and still be one column of the view (a TIMESTAMP
  * as INT96 in one and as INT64 in the next), and a reader that decided once,
  * or rebuilt the file's schema from the type it would itself write, would
  * misread every file that disagreed. So the view's schema comes from the first
  * file, and each file is read by matching the view's columns to its own by
  * name and decoding them as it declares them.
  *
  * Every pairing of physical type and annotation is decided here, and anything
  * not listed fails naming the column, rather than falling through to its
  * physical type: a parquet annotation says what the stored bits mean, and
  * reading the bits without it (an unsigned integer as a signed one, a decimal
  * as its unscaled integer) is a wrong answer, not a fallback.
  */
private[parquet] object ParquetColumns {

  /** How one column's stored values become engine values. `dataType` is the
    * type the column reads as. `storesEngineValue` says whether each stored
    * value already is the engine value, so that the column's row-group
    * statistics bound engine values and a comparison can be pushed to them:
    * true when the decode is a plain copy, false when the stored bits mean
    * something else (another time unit, an unscaled integer, an unsigned one,
    * a half-precision float). */
  sealed abstract class Decoding(val dataType: DataType, val storesEngineValue: Boolean)

  object Decoding {
    case object Booleans extends Decoding(DataType.BooleanType, true)
    /** INT32, signed, or unsigned in 8 or 16 bits, whose values all fit. */
    case object Ints extends Decoding(DataType.IntType, true)
    /** INT32 with INTEGER(32, false): an unsigned int, whose top half reads
      * negative as an INT, so it reads as the BIGINT that holds it. */
    case object UnsignedInts extends Decoding(DataType.LongType, false)
    /** INT32 days since the epoch. */
    case object Dates extends Decoding(DataType.DateType, true)
    case object Longs extends Decoding(DataType.LongType, true)
    /** INT64 with INTEGER(64, false): an unsigned long, which no engine integer
      * holds, so it reads as the DECIMAL(20,0) that does. */
    case object UnsignedLongs extends Decoding(DataType.DecimalType(20, 0), false)
    /** INT64 counting `unit`s since the epoch, read as the microseconds a
      * TIMESTAMP holds: a MILLIS value times 1000, a MICROS one as is, and a
      * NANOS one truncated to its microsecond, which before 1970 is the
      * earlier one (`Math.floorDiv`), as `Instant.truncatedTo` truncates on
      * every other path into the engine. Whether the file marks the value
      * as UTC (`isAdjustedToUTC`) is not consulted: the engine has one
      * timestamp type, a UTC instant, so a local date-time reads as the UTC
      * instant with the same wall-clock reading. */
    final case class Timestamps(unit: LogicalTypeAnnotation.TimeUnit) extends Decoding(DataType.TimestampType, false)
    /** The legacy 12-byte timestamp Hive, Impala and old Spark write. */
    case object Int96Timestamps extends Decoding(DataType.TimestampType, false)
    case object Floats extends Decoding(DataType.FloatType, true)
    /** FIXED_LEN_BYTE_ARRAY(2) FLOAT16: an IEEE half-precision float, stored
      * little-endian, read as the FLOAT that holds it exactly. */
    case object HalfFloats extends Decoding(DataType.FloatType, false)
    case object Doubles extends Decoding(DataType.DoubleType, true)
    /** UTF-8 text: STRING, ENUM or JSON. */
    case object Strings extends Decoding(DataType.StringType, true)
    /** Bytes as stored: an unannotated BINARY or FIXED_LEN_BYTE_ARRAY, a BSON
      * document (binary, not text) or a UUID. */
    case object Bytes extends Decoding(DataType.BinaryType, true)
    /** A DECIMAL's unscaled value as an INT32. */
    final case class IntDecimals(precision: Int, scale: Int)
        extends Decoding(DataType.DecimalType(precision, scale), false)
    /** A DECIMAL's unscaled value as an INT64. */
    final case class LongDecimals(precision: Int, scale: Int)
        extends Decoding(DataType.DecimalType(precision, scale), false)
    /** A DECIMAL's unscaled value as big-endian two's-complement bytes, in a
      * BINARY of any length or a FIXED_LEN_BYTE_ARRAY sign-extended to its
      * length. */
    final case class ByteDecimals(precision: Int, scale: Int)
        extends Decoding(DataType.DecimalType(precision, scale), false)
  }

  /** The decoding for column `t`, or an error naming it when the engine has
    * no type for what it holds. */
  def decoding(t: Type): Decoding = {
    if (!t.isPrimitive) throw new UnsupportedOperationException(
      s"Parquet column '${t.getName}' is nested ($t), and only flat schemas are supported")
    if (t.isRepetition(Type.Repetition.REPEATED)) throw new UnsupportedOperationException(
      s"Parquet column '${t.getName}' is repeated, a list of values in each row, which a column cannot hold")
    val pt = t.asPrimitiveType
    (pt.getPrimitiveTypeName, pt.getLogicalTypeAnnotation) match {
      case (PrimitiveTypeName.BOOLEAN, null) => Decoding.Booleans

      case (PrimitiveTypeName.INT32, null) => Decoding.Ints
      case (PrimitiveTypeName.INT32, i: IntLogicalTypeAnnotation) if i.getBitWidth < 32 => Decoding.Ints
      case (PrimitiveTypeName.INT32, i: IntLogicalTypeAnnotation) if i.getBitWidth == 32 =>
        if (i.isSigned) Decoding.Ints else Decoding.UnsignedInts
      case (PrimitiveTypeName.INT32, _: DateLogicalTypeAnnotation) => Decoding.Dates
      case (PrimitiveTypeName.INT32, d: DecimalLogicalTypeAnnotation) => Decoding.IntDecimals(d.getPrecision, d.getScale)

      case (PrimitiveTypeName.INT64, null) => Decoding.Longs
      case (PrimitiveTypeName.INT64, i: IntLogicalTypeAnnotation) if i.getBitWidth == 64 =>
        if (i.isSigned) Decoding.Longs else Decoding.UnsignedLongs
      case (PrimitiveTypeName.INT64, ts: TimestampLogicalTypeAnnotation) => Decoding.Timestamps(ts.getUnit)
      case (PrimitiveTypeName.INT64, d: DecimalLogicalTypeAnnotation) => Decoding.LongDecimals(d.getPrecision, d.getScale)

      case (PrimitiveTypeName.INT96, null) => Decoding.Int96Timestamps
      case (PrimitiveTypeName.FLOAT, null) => Decoding.Floats
      case (PrimitiveTypeName.DOUBLE, null) => Decoding.Doubles

      case (PrimitiveTypeName.BINARY, null | _: BsonLogicalTypeAnnotation) => Decoding.Bytes
      case (PrimitiveTypeName.BINARY, _: StringLogicalTypeAnnotation | _: EnumLogicalTypeAnnotation | _: JsonLogicalTypeAnnotation) =>
        Decoding.Strings
      case (PrimitiveTypeName.BINARY, d: DecimalLogicalTypeAnnotation) => Decoding.ByteDecimals(d.getPrecision, d.getScale)

      case (PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY, null | _: UUIDLogicalTypeAnnotation) => Decoding.Bytes
      case (PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY, _: Float16LogicalTypeAnnotation) => Decoding.HalfFloats
      case (PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY, d: DecimalLogicalTypeAnnotation) =>
        Decoding.ByteDecimals(d.getPrecision, d.getScale)

      // TIME, INTERVAL, and anything a later format version adds.
      case (name, annotation) => throw new UnsupportedOperationException(
        s"Parquet column '${t.getName}' is ${name.toString.toLowerCase} $annotation, which has no transformer type")
    }
  }

  private def nullable(t: Type): Boolean = !t.isRepetition(Type.Repetition.REQUIRED)

  /** The top-level column of `mt` named exactly `name`. */
  private def field(mt: MessageType, name: String): Option[Type] =
    if (mt.containsField(name)) Some(mt.getFields.get(mt.getFieldIndex(name))) else None

  /** The schema of a view over `files`, each a label for messages and the
    * file's footer schema: the first file's columns in its order, each
    * nullable if any file allows it a NULL. */
  def viewSchema(files: Seq[(String, MessageType)]): Schema = {
    val (_, first) = files.head
    Schema(first.getFields.asScala.map { t =>
      val anyNullable = files.exists { case (_, mt) => field(mt, t.getName).exists(nullable) }
      Field(t.getName, decoding(t).dataType, anyNullable)
    }.toVector)
  }

  /** What to ask one file for to read `schema` out of it: its own columns of
    * those names, in `schema`'s order and exactly as the file stores them (the
    * request replaces the file's column descriptors for decoding, so anything
    * else would be decoded as something it is not), and the decoding of each. */
  final case class FileColumns(requested: MessageType, decodings: Array[Decoding])

  /** Match `schema`'s columns to the file's by name. Fails, naming the file
    * (`label`) and the column, when the file lacks one or reads it as another
    * type. */
  def resolve(schema: Schema, file: MessageType, label: String): FileColumns = {
    val types: Vector[Type] = schema.fields.map { f =>
      field(file, f.name).getOrElse(throw new IllegalArgumentException(
        s"Parquet file '$label' has no column '${f.name}', which the first file of its glob has"))
    }
    val decodings = schema.fields.zip(types).map { case (f, t) =>
      val d = decoding(t)
      if (d.dataType != f.dataType) throw new IllegalArgumentException(
        s"Parquet file '$label' has column '${f.name}' as ${d.dataType}, " +
          s"but the first file of its glob has it as ${f.dataType}")
      d
    }
    FileColumns(new MessageType(file.getName, types.asJava), decodings.toArray)
  }
}
