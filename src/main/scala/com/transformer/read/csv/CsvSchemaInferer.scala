package com.transformer.read.csv

import com.transformer.core.{DataType, Field, Schema}

import java.time.format.DateTimeFormatter

/** Infers a [[Schema]] from a sample of CSV rows.
  *
  * Per-column type priority: Int → Long → Double → Boolean → Date → Timestamp → String.
  * The first sample value that does not parse at the current candidate widens the
  * candidate. Null cells (empty string or `nullValue`) never affect the candidate.
  */
object CsvSchemaInferer {

  private val DateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

  /** @param headerNames column names from the file (or synthetic _c0, _c1, …)
    * @param sampleRows up to N row samples to inspect
    */
  def infer(headerNames: Seq[String], sampleRows: Iterable[Array[String]], nullValue: String): Schema = {
    val n = headerNames.length
    val candidates = Array.fill[DataType](n)(DataType.IntType)
    val anyNonNull = Array.fill[Boolean](n)(false)

    for (row <- sampleRows) {
      var i = 0
      while (i < n && i < row.length) {
        val v = row(i)
        if (v != null && v != nullValue && v.nonEmpty) {
          anyNonNull(i) = true
          candidates(i) = widen(candidates(i), v)
        }
        i += 1
      }
    }

    val fields = headerNames.zipWithIndex.map { case (name, i) =>
      // Columns that were all-null in the sample default to StringType.
      val dt = if (anyNonNull(i)) candidates(i) else DataType.StringType
      Field(name, dt, nullable = true)
    }.toVector
    Schema(fields)
  }

  /** Returns the smallest type that can hold both the current candidate and `s`. */
  private def widen(current: DataType, s: String): DataType = current match {
    case DataType.IntType =>
      if (parsesAsInt(s)) DataType.IntType
      else widen(DataType.LongType, s)
    case DataType.LongType =>
      if (parsesAsLong(s)) DataType.LongType
      else widen(DataType.DoubleType, s)
    case DataType.DoubleType =>
      if (parsesAsDouble(s)) DataType.DoubleType
      else widen(DataType.BooleanType, s)
    case DataType.BooleanType =>
      if (parsesAsBoolean(s)) DataType.BooleanType
      else widen(DataType.DateType, s)
    case DataType.DateType =>
      if (parsesAsDate(s)) DataType.DateType
      else widen(DataType.TimestampType, s)
    case DataType.TimestampType =>
      if (parsesAsTimestamp(s)) DataType.TimestampType
      else DataType.StringType
    case DataType.StringType => DataType.StringType
    case _ => DataType.StringType
  }

  private def parsesAsInt(s: String): Boolean =
    try { s.toInt; true } catch { case _: NumberFormatException => false }

  private def parsesAsLong(s: String): Boolean =
    try { s.toLong; true } catch { case _: NumberFormatException => false }

  private def parsesAsDouble(s: String): Boolean =
    try { s.toDouble; true } catch { case _: NumberFormatException => false }

  private def parsesAsBoolean(s: String): Boolean = {
    val lc = s.toLowerCase
    lc == "true" || lc == "false"
  }

  private def parsesAsDate(s: String): Boolean =
    try { DateFormatter.parse(s); true } catch { case _: Exception => false }

  private def parsesAsTimestamp(s: String): Boolean = {
    // Accept Instant-format (ends with Z) or LocalDateTime (no zone).
    try { java.time.Instant.parse(s); true } catch {
      case _: Exception =>
        try { java.time.LocalDateTime.parse(s); true } catch {
          case _: Exception => false
        }
    }
  }
}
