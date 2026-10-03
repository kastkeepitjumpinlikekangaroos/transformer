package com.transformer.read.parquet

import com.transformer.core.{DataType, Field, Schema}

import org.apache.parquet.schema.{LogicalTypeAnnotation, MessageType, Type}
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
  */
private[parquet] object ParquetColumns {

  /** How one column's stored values become engine values. `dataType` is the
    * type the column reads as. */
  sealed abstract class Decoding(val dataType: DataType)

  object Decoding {
    case object Booleans extends Decoding(DataType.BooleanType)
    case object Ints extends Decoding(DataType.IntType)
    /** INT32 days since the epoch. */
    case object Dates extends Decoding(DataType.DateType)
    case object Longs extends Decoding(DataType.LongType)
    /** INT64 counting `unit`s since the epoch, read as the microseconds a
      * TIMESTAMP holds: a MILLIS value times 1000, a MICROS one as is, and a
      * NANOS one truncated to its microsecond, which before 1970 is the
      * earlier one (`Math.floorDiv`), as `Instant.truncatedTo` truncates on
      * every other path into the engine. Whether the file marks the value
      * as UTC (`isAdjustedToUTC`) is not consulted: the engine has one
      * timestamp type, a UTC instant, so a local date-time reads as the UTC
      * instant with the same wall-clock reading. */
    final case class Timestamps(unit: LogicalTypeAnnotation.TimeUnit) extends Decoding(DataType.TimestampType)
    /** The legacy 12-byte timestamp Hive, Impala and old Spark write. */
    case object Int96Timestamps extends Decoding(DataType.TimestampType)
    case object Floats extends Decoding(DataType.FloatType)
    case object Doubles extends Decoding(DataType.DoubleType)
    /** UTF-8 text. */
    case object Strings extends Decoding(DataType.StringType)
    case object Bytes extends Decoding(DataType.BinaryType)
    /** A DECIMAL's unscaled value as an INT32. */
    final case class IntDecimals(precision: Int, scale: Int) extends Decoding(DataType.DecimalType(precision, scale))
    /** A DECIMAL's unscaled value as an INT64. */
    final case class LongDecimals(precision: Int, scale: Int) extends Decoding(DataType.DecimalType(precision, scale))
    /** A DECIMAL's unscaled value as big-endian two's-complement bytes, in a
      * BINARY of any length or a FIXED_LEN_BYTE_ARRAY sign-extended to its
      * length. */
    final case class ByteDecimals(precision: Int, scale: Int) extends Decoding(DataType.DecimalType(precision, scale))
  }

  /** The decoding for column `t`, or an error naming it when the engine has
    * no type for what it holds. */
  def decoding(t: Type): Decoding = {
    if (!t.isPrimitive) throw new UnsupportedOperationException(
      s"Parquet column '${t.getName}' is nested ($t), and only flat schemas are supported")
    val pt = t.asPrimitiveType
    (pt.getPrimitiveTypeName, pt.getLogicalTypeAnnotation) match {
      case (PrimitiveTypeName.BOOLEAN, _) => Decoding.Booleans
      case (PrimitiveTypeName.INT32, d: LogicalTypeAnnotation.DecimalLogicalTypeAnnotation) =>
        Decoding.IntDecimals(d.getPrecision, d.getScale)
      case (PrimitiveTypeName.INT64, d: LogicalTypeAnnotation.DecimalLogicalTypeAnnotation) =>
        Decoding.LongDecimals(d.getPrecision, d.getScale)
      case (PrimitiveTypeName.BINARY | PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY,
            d: LogicalTypeAnnotation.DecimalLogicalTypeAnnotation) =>
        Decoding.ByteDecimals(d.getPrecision, d.getScale)
      case (PrimitiveTypeName.INT32, _: LogicalTypeAnnotation.DateLogicalTypeAnnotation) => Decoding.Dates
      case (PrimitiveTypeName.INT32, _) => Decoding.Ints
      case (PrimitiveTypeName.INT64, ts: LogicalTypeAnnotation.TimestampLogicalTypeAnnotation) =>
        Decoding.Timestamps(ts.getUnit)
      case (PrimitiveTypeName.INT64, _) => Decoding.Longs
      case (PrimitiveTypeName.INT96, _) => Decoding.Int96Timestamps
      case (PrimitiveTypeName.FLOAT, _) => Decoding.Floats
      case (PrimitiveTypeName.DOUBLE, _) => Decoding.Doubles
      case (PrimitiveTypeName.BINARY, _: LogicalTypeAnnotation.StringLogicalTypeAnnotation) => Decoding.Strings
      case (PrimitiveTypeName.BINARY, _: LogicalTypeAnnotation.EnumLogicalTypeAnnotation) => Decoding.Strings
      case (PrimitiveTypeName.BINARY | PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY, _) => Decoding.Bytes
      case (name, annotation) => throw new UnsupportedOperationException(
        s"Parquet column '${t.getName}' is $name $annotation, which has no transformer type")
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
