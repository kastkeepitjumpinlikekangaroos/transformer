package com.transformer.write.parquet

import com.transformer.core._

import org.apache.parquet.schema.{LogicalTypeAnnotation, MessageType, PrimitiveType, Type, Types => PTypes}
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName

import scala.jdk.CollectionConverters._

/** Schema conversion between Parquet's [[MessageType]] and our [[Schema]].
  *
  * v1 only handles flat (non-nested) schemas. Nested groups/lists/maps raise.
  */
object ParquetSchema {

  /** Parquet → Transformer. */
  def toSchema(mt: MessageType): Schema = {
    val fields = mt.getFields.asScala.map(field).toVector
    Schema(fields)
  }

  private def field(t: Type): Field = {
    if (!t.isPrimitive) throw new UnsupportedOperationException(
      s"Nested Parquet types not supported in v1 (field='${t.getName}', kind='${t.toString}')"
    )
    val pt = t.asPrimitiveType()
    val nullable = t.isRepetition(Type.Repetition.OPTIONAL) || t.isRepetition(Type.Repetition.REPEATED)
    Field(t.getName, primitiveToDataType(pt), nullable)
  }

  private def primitiveToDataType(pt: PrimitiveType): DataType = {
    val name = pt.getPrimitiveTypeName
    val logical = pt.getLogicalTypeAnnotation
    (name, logical) match {
      case (PrimitiveTypeName.BOOLEAN, _) => DataType.BooleanType
      case (PrimitiveTypeName.INT32, _: LogicalTypeAnnotation.DateLogicalTypeAnnotation) => DataType.DateType
      case (PrimitiveTypeName.INT32, _) => DataType.IntType
      case (PrimitiveTypeName.INT64, _: LogicalTypeAnnotation.TimestampLogicalTypeAnnotation) => DataType.TimestampType
      case (PrimitiveTypeName.INT64, _) => DataType.LongType
      case (PrimitiveTypeName.FLOAT, _) => DataType.FloatType
      case (PrimitiveTypeName.DOUBLE, _) => DataType.DoubleType
      case (PrimitiveTypeName.BINARY, _: LogicalTypeAnnotation.StringLogicalTypeAnnotation) => DataType.StringType
      case (PrimitiveTypeName.BINARY, _: LogicalTypeAnnotation.EnumLogicalTypeAnnotation) => DataType.StringType
      case (PrimitiveTypeName.BINARY, null) => DataType.BinaryType
      case (PrimitiveTypeName.BINARY, _) => DataType.BinaryType
      case (PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY, _) => DataType.BinaryType
      case (PrimitiveTypeName.INT96, _) => DataType.TimestampType
      case _ => throw new UnsupportedOperationException(s"Unsupported Parquet type: $name / $logical")
    }
  }

  /** Transformer → Parquet. */
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
