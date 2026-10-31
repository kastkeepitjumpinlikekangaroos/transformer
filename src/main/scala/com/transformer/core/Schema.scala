package com.transformer.core

import java.util.Locale

final case class Field(name: String, dataType: DataType, nullable: Boolean = true)

final case class Schema(fields: Vector[Field]) {
  def length: Int = fields.length
  def fieldNames: Vector[String] = fields.map(_.name)

  def indexOf(name: String): Int = {
    val lower = name.toLowerCase(Locale.ROOT)
    fields.indexWhere(_.name.toLowerCase(Locale.ROOT) == lower)
  }

  def fieldOpt(name: String): Option[Field] = {
    val i = indexOf(name)
    if (i < 0) None else Some(fields(i))
  }

  def field(name: String): Field =
    fieldOpt(name).getOrElse(
      throw new IllegalArgumentException(s"Field '$name' not in schema [${fieldNames.mkString(",")}]")
    )

  def add(f: Field): Schema = Schema(fields :+ f)

  def select(names: Seq[String]): Schema = Schema(names.iterator.map(field).toVector)

  override def toString: String =
    fields.map(f => s"${f.name}:${f.dataType}${if (f.nullable) "?" else ""}").mkString("Schema(", ", ", ")")
}

object Schema {
  val empty: Schema = Schema(Vector.empty)
  def apply(fields: Field*): Schema = Schema(fields.toVector)

  /** The 0-based positions of the first name more than one of `names` has,
    * compared as a query compares names, regardless of case; None when each
    * name is its own. A table whose columns repeat a name cannot be read by
    * that name, here or in another engine, without a guess. */
  def repeatedName(names: Seq[String]): Option[Seq[Int]] = {
    val positions = names.indices.groupBy(i => names(i).toLowerCase(Locale.ROOT))
    names.indices.iterator.map(i => positions(names(i).toLowerCase(Locale.ROOT))).find(_.size > 1)
  }

  /** `positions` of `names` for a message: `1 (a), 2 (a) and 3 (A)`. */
  def describePositions(names: Seq[String], positions: Seq[Int]): String = {
    val shown = positions.map(i => s"${i + 1} (${names(i)})")
    if (shown.size == 1) shown.head else shown.init.mkString(", ") + " and " + shown.last
  }
}
