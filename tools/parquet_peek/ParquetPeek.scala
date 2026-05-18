package com.transformer.tools.parquet_peek

import com.transformer.read.parquet.ParquetReader

/** Inspect a parquet file or glob: prints the schema, partition layout, exact
  * row count (from footer metadata — no decode), and a configurable sample of
  * decoded rows. String values are truncated to keep output readable when a
  * column holds JSON blobs.
  *
  * Usage:
  *
  *   bazel run //tools/parquet_peek -- <path-or-glob> [--rows N]
  *
  * The argument must be absolute (or relative to the bazel-run cwd). `--rows 0`
  * skips the sample read entirely — handy when all you want is the schema.
  */
object ParquetPeek {
  def main(args: Array[String]): Unit = {
    if (args.isEmpty || args.contains("--help") || args.contains("-h")) {
      System.err.println("usage: parquet_peek <path-or-glob> [--rows N]")
      sys.exit(if (args.isEmpty) 1 else 0)
    }
    val path = args(0)
    val sampleRows: Int = args.indexOf("--rows") match {
      case -1 => 3
      case i if i + 1 < args.length =>
        try args(i + 1).toInt
        catch { case _: NumberFormatException =>
          System.err.println(s"--rows expects an integer, got '${args(i + 1)}'")
          sys.exit(1)
        }
      case _ =>
        System.err.println("--rows requires an integer argument")
        sys.exit(1)
    }

    val r = ParquetReader.fromPath(path)
    println(s"path:          $path")
    println(s"numPartitions: ${r.numPartitions}  (one per file)")
    println(s"exactRowCount: ${r.exactRowCount.map(_.toString).getOrElse("unknown")}")
    println("schema:")
    r.schema.fields.foreach(f => println(s"  ${f.name}: ${f.dataType}"))

    if (sampleRows > 0) {
      println(s"\nfirst $sampleRows row(s) of partition 0:")
      val it = r.readPartition(0)
      var emitted = 0
      while (emitted < sampleRows && it.hasNext) {
        val batch = it.next()
        var row = 0
        while (row < batch.numRows && emitted < sampleRows) {
          val cells = (0 until r.schema.length).map { c =>
            val v = batch.column(c)
            val raw = if (v.isNull(row)) "NULL" else String.valueOf(v.getBoxed(row))
            val truncated = if (raw.length > 80) raw.take(77) + "..." else raw
            s"${r.schema.fields(c).name}=$truncated"
          }
          println(s"  row $emitted: ${cells.mkString(", ")}")
          row += 1
          emitted += 1
        }
      }
    }
  }
}
