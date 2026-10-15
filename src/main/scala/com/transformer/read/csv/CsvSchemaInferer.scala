package com.transformer.read.csv

import com.transformer.core.{DataType, Field, Schema}

/** Infers a [[Schema]] from a sample of CSV rows.
  *
  * Each field's text has a narrowest type it reads as ([[CsvValues.classify]],
  * the reader's own grammar), and a column's type is the narrowest every
  * sampled field reads as ([[CsvValues.join]]): numbers widen from INT to
  * BIGINT to DECIMAL(38,0), for integers past BIGINT, to DOUBLE; a DATE widens
  * to a TIMESTAMP, its dates read as midnight UTC; and any other mix is a
  * STRING, since no other type reads both. NULL and empty fields say nothing
  * about the type, and a column with nothing else is a STRING.
  *
  * Only the sample is read, so a later value can still be no value of its
  * column's type; the reader then fails naming it.
  */
object CsvSchemaInferer {

  /** @param headerNames column names from the file (or synthetic _c0, _c1, …)
    * @param sampleRows up to N row samples to inspect
    */
  def infer(headerNames: Seq[String], sampleRows: Iterable[Array[String]]): Schema = {
    val n = headerNames.length
    val types = Array.fill[Option[DataType]](n)(None)
    for (row <- sampleRows) {
      var i = 0
      while (i < n && i < row.length) {
        val v = row(i)
        if (v != null && v.nonEmpty) {
          val t = CsvValues.classify(v)
          types(i) = Some(types(i).fold(t)(CsvValues.join(_, t)))
        }
        i += 1
      }
    }
    Schema(headerNames.zipWithIndex.map { case (name, i) =>
      Field(name, types(i).getOrElse(DataType.StringType), nullable = true)
    }.toVector)
  }
}
