package com.transformer.core

sealed trait DataType {
  def name: String
  override def toString: String = name
}

object DataType {
  case object StringType extends DataType { val name = "string" }
  case object IntType extends DataType { val name = "int" }
  case object LongType extends DataType { val name = "long" }
  case object FloatType extends DataType { val name = "float" }
  case object DoubleType extends DataType { val name = "double" }
  case object BooleanType extends DataType { val name = "boolean" }
  case object DateType extends DataType { val name = "date" }
  case object TimestampType extends DataType { val name = "timestamp" }
  case object BinaryType extends DataType { val name = "binary" }
  case object NullType extends DataType { val name = "null" }
  final case class DecimalType(precision: Int, scale: Int) extends DataType {
    val name = s"decimal($precision,$scale)"
  }

  // Aliases that match the brief's call style: DataType.STRING, DataType.INT, …
  val STRING: DataType = StringType
  val INT: DataType = IntType
  val LONG: DataType = LongType
  val FLOAT: DataType = FloatType
  val DOUBLE: DataType = DoubleType
  val BOOLEAN: DataType = BooleanType
  val DATE: DataType = DateType
  val TIMESTAMP: DataType = TimestampType
  val BINARY: DataType = BinaryType
  val NULL: DataType = NullType

  private val Named: Map[String, DataType] =
    Seq(StringType, IntType, LongType, FloatType, DoubleType, BooleanType, DateType, TimestampType, BinaryType,
      NullType).map(t => t.name -> t).toMap
  private val DecimalName = """decimal\((\d+),(\d+)\)""".r

  /** The type whose [[DataType.name]] is `name` (`long`, `decimal(10,2)`), as
    * a run record writes a column's type; None for a name no type has. */
  def fromName(name: String): Option[DataType] = name match {
    case DecimalName(p, s) => scala.util.Try(DecimalType(p.toInt, s.toInt)).toOption
    case other => Named.get(other)
  }

  /** `t` as SQL writes it, as every grid and listing shows a column's type:
    * BIGINT, DECIMAL(10,2). */
  def sqlName(t: DataType): String = t match {
    case StringType => "STRING"
    case IntType => "INT"
    case LongType => "BIGINT"
    case FloatType => "FLOAT"
    case DoubleType => "DOUBLE"
    case BooleanType => "BOOLEAN"
    case DateType => "DATE"
    case TimestampType => "TIMESTAMP"
    case BinaryType => "BINARY"
    case NullType => "NULL"
    case DecimalType(p, s) => s"DECIMAL($p,$s)"
  }

  def isNumeric(t: DataType): Boolean = t match {
    case IntType | LongType | FloatType | DoubleType | _: DecimalType => true
    case _ => false
  }

  def isIntegral(t: DataType): Boolean = t match {
    case IntType | LongType => true
    case _ => false
  }

  // Returns the wider numeric type for binary numeric operations.
  // Caller should ensure both inputs are numeric.
  def widerNumeric(a: DataType, b: DataType): DataType = {
    def rank(t: DataType): Int = t match {
      case IntType => 1
      case LongType => 2
      case FloatType => 3
      case DoubleType => 4
      case _: DecimalType => 5
      case _ => 0
    }
    if (rank(a) >= rank(b)) a else b
  }
}
