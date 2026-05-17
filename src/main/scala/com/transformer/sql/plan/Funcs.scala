package com.transformer.sql.plan

import com.transformer.core.DataType

/** Scalar function registry. */
object Funcs {

  /** Return type for `fn(argTypes)`, or `None` if the function is unknown.
    * The analyzer uses this for type-checking; the executor uses [[apply]] at runtime.
    */
  def returnType(name: String, argTypes: Seq[DataType]): Option[DataType] = name.toUpperCase match {
    case "LENGTH" | "LEN" if argTypes.size == 1 => Some(DataType.IntType)
    case "UPPER" | "LOWER" | "TRIM" if argTypes.size == 1 => Some(DataType.StringType)
    case "SUBSTRING" if argTypes.size == 2 || argTypes.size == 3 => Some(DataType.StringType)
    case "CONCAT" => Some(DataType.StringType)
    case "COALESCE" if argTypes.nonEmpty => Some(widestNonNull(argTypes))
    case "IF" if argTypes.size == 3 => Some(widestNonNull(argTypes.drop(1)))
    case "NULLIF" if argTypes.size == 2 => Some(argTypes.head)
    case "ABS" if argTypes.size == 1 => Some(argTypes.head)
    case "ROUND" if argTypes.size >= 1 => Some(DataType.DoubleType)
    case "FLOOR" | "CEIL" | "CEILING" if argTypes.size == 1 => Some(DataType.DoubleType)
    case "MOD" if argTypes.size == 2 => Some(DataType.widerNumeric(argTypes(0), argTypes(1)))
    case "POW" | "POWER" if argTypes.size == 2 => Some(DataType.DoubleType)
    case "CURRENT_DATE" if argTypes.isEmpty => Some(DataType.DateType)
    case "CURRENT_TIMESTAMP" if argTypes.isEmpty => Some(DataType.TimestampType)
    case _ => None
  }

  private def widestNonNull(types: Seq[DataType]): DataType = {
    val nonNull = types.filterNot(_ == DataType.NullType)
    if (nonNull.isEmpty) DataType.NullType
    else if (nonNull.forall(DataType.isNumeric)) nonNull.reduce(DataType.widerNumeric)
    else nonNull.head
  }

  def apply(name: String, args: Seq[Any], resultType: DataType, argTypes: Seq[DataType]): Any =
    name.toUpperCase match {
      case "LENGTH" | "LEN" =>
        val v = args.head
        if (v == null) null else v.toString.length
      case "UPPER" =>
        if (args.head == null) null else args.head.toString.toUpperCase
      case "LOWER" =>
        if (args.head == null) null else args.head.toString.toLowerCase
      case "TRIM" =>
        if (args.head == null) null else args.head.toString.trim
      case "SUBSTRING" =>
        val s = args.head
        if (s == null) null
        else {
          val str = s.toString
          val start = math.max(0, args(1).asInstanceOf[Number].intValue - 1)
          val end =
            if (args.size == 3) math.min(str.length, start + args(2).asInstanceOf[Number].intValue)
            else str.length
          if (start >= str.length) "" else str.substring(start, math.max(start, end))
        }
      case "CONCAT" =>
        if (args.exists(_ == null)) null else args.map(_.toString).mkString
      case "COALESCE" =>
        args.find(_ != null).orNull
      case "IF" =>
        val cond = args(0)
        if (cond != null && cond.asInstanceOf[Boolean]) args(1) else args(2)
      case "NULLIF" =>
        if (Ops.eq(args(0), args(1))) null else args(0)
      case "ABS" =>
        args.head match {
          case n: java.lang.Number => resultType match {
            case DataType.IntType => math.abs(n.intValue)
            case DataType.LongType => math.abs(n.longValue)
            case _ => math.abs(n.doubleValue)
          }
          case _ => null
        }
      case "ROUND" =>
        val v = args.head.asInstanceOf[Number].doubleValue
        val scale = if (args.size >= 2) args(1).asInstanceOf[Number].intValue else 0
        val factor = math.pow(10, scale)
        math.round(v * factor) / factor
      case "FLOOR" => math.floor(args.head.asInstanceOf[Number].doubleValue)
      case "CEIL" | "CEILING" => math.ceil(args.head.asInstanceOf[Number].doubleValue)
      case "MOD" =>
        Ops.apply("%", args(0), args(1), argTypes(0), argTypes(1), resultType)
      case "POW" | "POWER" =>
        math.pow(args(0).asInstanceOf[Number].doubleValue, args(1).asInstanceOf[Number].doubleValue)
      case "CURRENT_DATE" =>
        java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay.toInt
      case "CURRENT_TIMESTAMP" =>
        val now = java.time.Instant.now()
        now.getEpochSecond * 1000000L + now.getNano / 1000L
      case other => throw new IllegalArgumentException(s"Unsupported function '$other'")
    }
}
