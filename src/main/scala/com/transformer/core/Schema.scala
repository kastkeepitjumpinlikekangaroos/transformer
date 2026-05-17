package com.transformer.core

final case class Field(name: String, dataType: DataType, nullable: Boolean = true)

final case class Schema(fields: Vector[Field]) {
  def length: Int = fields.length
  def fieldNames: Vector[String] = fields.map(_.name)

  def indexOf(name: String): Int = {
    val lower = name.toLowerCase
    fields.indexWhere(_.name.toLowerCase == lower)
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
}
