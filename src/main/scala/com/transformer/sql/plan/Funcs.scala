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
    case "TRUNC" | "TRUNCATE" if argTypes.size == 1 || argTypes.size == 2 => Some(DataType.DoubleType)
    case "MOD" if argTypes.size == 2 => Some(DataType.widerNumeric(argTypes(0), argTypes(1)))
    case "POW" | "POWER" if argTypes.size == 2 => Some(DataType.DoubleType)
    case "SQRT" | "CBRT" | "EXP" if argTypes.size == 1 => Some(DataType.DoubleType)
    case "LN" if argTypes.size == 1 => Some(DataType.DoubleType)
    case "LOG" if argTypes.size == 1 || argTypes.size == 2 => Some(DataType.DoubleType)
    case "LOG10" | "LOG2" if argTypes.size == 1 => Some(DataType.DoubleType)
    case "SIN" | "COS" | "TAN" | "ASIN" | "ACOS" | "ATAN" if argTypes.size == 1 => Some(DataType.DoubleType)
    case "SINH" | "COSH" | "TANH" if argTypes.size == 1 => Some(DataType.DoubleType)
    case "ATAN2" if argTypes.size == 2 => Some(DataType.DoubleType)
    case "DEGREES" | "RADIANS" if argTypes.size == 1 => Some(DataType.DoubleType)
    case "SIGN" if argTypes.size == 1 => Some(DataType.IntType)
    case "PI" | "E" if argTypes.isEmpty => Some(DataType.DoubleType)
    case "RAND" | "RANDOM" if argTypes.isEmpty || argTypes.size == 1 => Some(DataType.DoubleType)
    case "GREATEST" | "LEAST" if argTypes.nonEmpty => Some(widestNonNull(argTypes))
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

  /** Per-thread RNG so RAND() is safe under parallel execution. Seeded RAND(x) returns
    * a deterministic value (not a per-row stream) — sufficient for tests and tagging. */
  private val threadRng: ThreadLocal[java.util.Random] = new ThreadLocal[java.util.Random] {
    override def initialValue(): java.util.Random = new java.util.Random()
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
        if (args.head == null) null
        else {
          val v = args.head.asInstanceOf[Number].doubleValue
          val scale = if (args.size >= 2) args(1).asInstanceOf[Number].intValue else 0
          val factor = math.pow(10, scale)
          math.round(v * factor) / factor
        }
      case "FLOOR" =>
        if (args.head == null) null else math.floor(args.head.asInstanceOf[Number].doubleValue)
      case "CEIL" | "CEILING" =>
        if (args.head == null) null else math.ceil(args.head.asInstanceOf[Number].doubleValue)
      case "TRUNC" | "TRUNCATE" =>
        if (args.head == null) null
        else {
          val v = args.head.asInstanceOf[Number].doubleValue
          val scale = if (args.size >= 2) {
            if (args(1) == null) return null
            args(1).asInstanceOf[Number].intValue
          } else 0
          val factor = math.pow(10, scale)
          val truncated = math.signum(v) * math.floor(math.abs(v) * factor) / factor
          if (truncated == 0.0) 0.0 else truncated
        }
      case "MOD" =>
        if (args(0) == null || args(1) == null) null
        else Ops.apply("%", args(0), args(1), argTypes(0), argTypes(1), resultType)
      case "POW" | "POWER" =>
        if (args(0) == null || args(1) == null) null
        else math.pow(args(0).asInstanceOf[Number].doubleValue, args(1).asInstanceOf[Number].doubleValue)
      case "SQRT" =>
        if (args.head == null) null else math.sqrt(args.head.asInstanceOf[Number].doubleValue)
      case "CBRT" =>
        if (args.head == null) null else math.cbrt(args.head.asInstanceOf[Number].doubleValue)
      case "EXP" =>
        if (args.head == null) null else math.exp(args.head.asInstanceOf[Number].doubleValue)
      case "LN" =>
        if (args.head == null) null else math.log(args.head.asInstanceOf[Number].doubleValue)
      case "LOG" =>
        if (args.exists(_ == null)) null
        else if (args.size == 1) math.log(args.head.asInstanceOf[Number].doubleValue)
        else {
          val base = args(0).asInstanceOf[Number].doubleValue
          val x = args(1).asInstanceOf[Number].doubleValue
          math.log(x) / math.log(base)
        }
      case "LOG10" =>
        if (args.head == null) null else math.log10(args.head.asInstanceOf[Number].doubleValue)
      case "LOG2" =>
        if (args.head == null) null
        else math.log(args.head.asInstanceOf[Number].doubleValue) / math.log(2.0)
      case "SIN" => if (args.head == null) null else math.sin(args.head.asInstanceOf[Number].doubleValue)
      case "COS" => if (args.head == null) null else math.cos(args.head.asInstanceOf[Number].doubleValue)
      case "TAN" => if (args.head == null) null else math.tan(args.head.asInstanceOf[Number].doubleValue)
      case "ASIN" => if (args.head == null) null else math.asin(args.head.asInstanceOf[Number].doubleValue)
      case "ACOS" => if (args.head == null) null else math.acos(args.head.asInstanceOf[Number].doubleValue)
      case "ATAN" => if (args.head == null) null else math.atan(args.head.asInstanceOf[Number].doubleValue)
      case "ATAN2" =>
        if (args(0) == null || args(1) == null) null
        else math.atan2(args(0).asInstanceOf[Number].doubleValue, args(1).asInstanceOf[Number].doubleValue)
      case "SINH" => if (args.head == null) null else math.sinh(args.head.asInstanceOf[Number].doubleValue)
      case "COSH" => if (args.head == null) null else math.cosh(args.head.asInstanceOf[Number].doubleValue)
      case "TANH" => if (args.head == null) null else math.tanh(args.head.asInstanceOf[Number].doubleValue)
      case "DEGREES" =>
        if (args.head == null) null else math.toDegrees(args.head.asInstanceOf[Number].doubleValue)
      case "RADIANS" =>
        if (args.head == null) null else math.toRadians(args.head.asInstanceOf[Number].doubleValue)
      case "SIGN" =>
        if (args.head == null) null
        else {
          val d = args.head.asInstanceOf[Number].doubleValue
          if (d > 0.0) 1 else if (d < 0.0) -1 else 0
        }
      case "PI" => math.Pi
      case "E"  => math.E
      case "RAND" | "RANDOM" =>
        if (args.isEmpty) threadRng.get().nextDouble()
        else {
          val seed = args.head.asInstanceOf[Number].longValue
          new java.util.Random(seed).nextDouble()
        }
      case "GREATEST" =>
        pickByOrdering(args, argTypes, resultType, greatest = true)
      case "LEAST" =>
        pickByOrdering(args, argTypes, resultType, greatest = false)
      case "CURRENT_DATE" =>
        java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay.toInt
      case "CURRENT_TIMESTAMP" =>
        val now = java.time.Instant.now()
        now.getEpochSecond * 1000000L + now.getNano / 1000L
      case other => throw new IllegalArgumentException(s"Unsupported function '$other'")
    }

  private def pickByOrdering(args: Seq[Any], argTypes: Seq[DataType], resultType: DataType, greatest: Boolean): Any = {
    var best: Any = null
    var i = 0
    while (i < args.size) {
      val v = args(i)
      if (v != null) {
        val cast = Casts.cast(v, argTypes(i), resultType)
        if (best == null) best = cast
        else {
          val c = Ops.cmp(cast, best)
          if ((greatest && c > 0) || (!greatest && c < 0)) best = cast
        }
      }
      i += 1
    }
    best
  }
}
