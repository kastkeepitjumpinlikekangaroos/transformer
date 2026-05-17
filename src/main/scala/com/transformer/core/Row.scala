package com.transformer.core

/** Boxed row used at API boundaries (writers, validation diagnostics, tests).
  * Operators work on [[ColumnarBatch]] internally for performance.
  */
final case class Row(schema: Schema, values: Array[Any]) {
  def length: Int = values.length
  def apply(i: Int): Any = values(i)
  def apply(name: String): Any = values(schema.indexOf(name))
  def isNullAt(i: Int): Boolean = values(i) == null
  def isNullAt(name: String): Boolean = isNullAt(schema.indexOf(name))

  def getString(i: Int): String = values(i).asInstanceOf[String]
  def getInt(i: Int): Int = values(i) match {
    case n: Number => n.intValue
    case s: String => s.toInt
  }
  def getLong(i: Int): Long = values(i) match {
    case n: Number => n.longValue
    case s: String => s.toLong
  }
  def getDouble(i: Int): Double = values(i) match {
    case n: Number => n.doubleValue
    case s: String => s.toDouble
  }
  def getBoolean(i: Int): Boolean = values(i) match {
    case b: Boolean => b
    case s: String => java.lang.Boolean.parseBoolean(s)
  }

  override def toString: String =
    schema.fieldNames.zip(values).map { case (n, v) => s"$n=${if (v == null) "null" else v.toString}" }.mkString("Row(", ", ", ")")
}
