package com.transformer.write.parquet

import com.transformer.core._

import org.apache.parquet.schema.{LogicalTypeAnnotation, MessageType, Type, Types => PTypes}
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName

import scala.jdk.CollectionConverters._

/** The parquet schema transformer writes for a [[Schema]].
  *
  * Reading goes the other way through `read/parquet`'s `ParquetColumns`, which
  * takes each file's types from that file rather than from this mapping: files
  * written by other tools store the same engine types in other ways.
  */
object ParquetSchema {

  /** The parquet schema of a file written from `schema`. */
  def toMessageType(schema: Schema, rootName: String = "row"): MessageType = {
    val ptypes = schema.fields.map { f =>
      val rep = if (f.nullable) Type.Repetition.OPTIONAL else Type.Repetition.REQUIRED
      f.dataType match {
        case DataType.BooleanType => PTypes.primitive(PrimitiveTypeName.BOOLEAN, rep).named(f.name)
        case DataType.IntType => PTypes.primitive(PrimitiveTypeName.INT32, rep).named(f.name)
        case DataType.LongType => PTypes.primitive(PrimitiveTypeName.INT64, rep).named(f.name)
        case DataType.FloatType => PTypes.primitive(PrimitiveTypeName.FLOAT, rep).named(f.name)
        case DataType.DoubleType => PTypes.primitive(PrimitiveTypeName.DOUBLE, rep).named(f.name)
        case DataType.StringType =>
          PTypes.primitive(PrimitiveTypeName.BINARY, rep)
            .as(LogicalTypeAnnotation.stringType())
            .named(f.name)
        case DataType.BinaryType => PTypes.primitive(PrimitiveTypeName.BINARY, rep).named(f.name)
        case DataType.DateType =>
          PTypes.primitive(PrimitiveTypeName.INT32, rep)
            .as(LogicalTypeAnnotation.dateType())
            .named(f.name)
        case DataType.TimestampType =>
          PTypes.primitive(PrimitiveTypeName.INT64, rep)
            .as(LogicalTypeAnnotation.timestampType(true, LogicalTypeAnnotation.TimeUnit.MICROS))
            .named(f.name)
        case other => throw new UnsupportedOperationException(s"Writing $other to Parquet not supported in v1")
      }
    }
    new MessageType(rootName, ptypes.map(_.asInstanceOf[Type]).asJava)
  }
}
