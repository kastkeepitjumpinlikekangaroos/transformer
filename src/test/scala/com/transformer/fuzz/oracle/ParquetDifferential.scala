package com.transformer.fuzz.oracle

import com.transformer.core._
import com.transformer.fuzz.ParquetGen
import com.transformer.fuzz.ParquetGen.{ParquetCase, PqFile}
import com.transformer.fuzz.RowOracle
import com.transformer.read.parquet.{ExternalParquet, ParquetReader}
import com.transformer.sql.plan.Expr

import java.nio.file.{Files, Path}
import scala.collection.mutable

/** The external-format differential: parquet files parquet-mr wrote, read by
  * transformer's `ParquetReader`, against what the format says they hold.
  *
  * Every check compares our reader with something that is not our code. The
  * values come from [[ParquetGen.Kind.expected]], which reads each stored value
  * by the format's spec with `java.time` and `java.math`; and before our reader
  * is consulted at all, parquet-mr's own reader must print every stored value
  * the way [[ParquetGen.Kind.rendered]] says it will, so that a misreading of
  * the spec the generator and the reader share still fails, against
  * parquet-mr. A self-written round trip cannot do either: our writer and
  * reader agree with each other however wrong they both are.
  *
  * For one case, in order:
  *
  *   1. parquet-mr reads each file as the generator wrote it: the row groups
  *      it was cut into, and every value as `rendered` prints it.
  *   2. The view's schema: the first file's columns in its order, each the
  *      engine type its layout reads as, nullable where any file allows NULL.
  *   3. Every row, in file and row-group order, each cell exactly the value
  *      `expected` says (a decimal at its declared scale, `-0.0` apart from
  *      `0.0`, every byte), with the partitions read in the case's order and
  *      each batch between one row and the batch size. Where a column holds a
  *      value no engine value holds, the read fails instead, naming it.
  *   4. `exactRowCount` is the row total, and partitions are whole runs of row
  *      groups: one per group at a one-byte target, one per file at the
  *      default.
  *   5. A projection reads the same rows' projected cells, through partitions
  *      that start past the first row group.
  *   6. A predicate the reader accepts for row-group skipping never skips a
  *      row the predicate keeps: the rows it keeps from the pushed scan are
  *      the rows it keeps from the whole one. The statistics being judged are
  *      parquet-mr's, which is the point.
  */
object ParquetDifferential {

  /** What one case exercised, for the coverage guards. */
  final case class Report(
      rows: Int,
      rowGroups: Int,
      partitions: Int,
      failedAsExpected: Boolean,
      projectedPastFirstGroup: Boolean,
      pushed: Boolean,
      pushdownSkippedRows: Boolean)

  def check(c: ParquetCase): Report = {
    val dir = Files.createTempDirectory("parquet-fuzz-")
    try {
      ParquetGen.writeTo(dir, c)
      c.files.foreach(f => checkParquetMr(dir.resolve(f.name), f))
      checkReader(dir, c)
    } finally deleteRecursively(dir)
  }

  // ---- 1. parquet-mr's reading ---------------------------------------------

  private def checkParquetMr(path: Path, f: PqFile): Unit = {
    val sizes = ExternalParquet.rowGroupSizes(path)
    if (sizes != f.groups.map(_.rows.length.toLong))
      fail(s"${f.name} was written as row groups of $sizes rows, not ${f.groups.map(_.rows.length)}")
    val read = ExternalParquet.read(path)
    val stored = f.groups.flatMap(_.rows)
    if (read.length != stored.length) fail(s"parquet-mr reads ${read.length} rows of ${f.name}, not ${stored.length}")
    var r = 0
    while (r < stored.length) {
      var col = 0
      while (col < f.columns.length) {
        val v = stored(r)(col)
        val kind = f.columns(col).kind
        val want: Option[String] = if (v == null) Some(null) else kind.rendered(v)
        want.foreach { w =>
          if (w != read(r)(col)) fail(
            s"parquet-mr reads ${f.name} row $r column '${f.columns(col).name}' ($kind) stored as " +
              s"${ParquetGen.show(v)} as ${quote(read(r)(col))}, but the generator says it is ${quote(w)}: " +
              "the generator's reading of the format is wrong, and the reader may share it")
        }
        col += 1
      }
      r += 1
    }
  }

  // ---- 2-6. our reader -------------------------------------------------------

  /** One expected cell: the engine value, or the reason a read must fail. */
  private final case class Cell(value: Any, unreadable: Boolean)

  private[fuzz] def checkReader(dir: Path, c: ParquetCase): Report = {
    val names = c.viewColumns
    val expectedSchema = Schema(names.map { n =>
      val kinds = c.files.map(f => f.columns.find(_.name == n).get)
      Field(n, kinds.head.kind.engineType, nullable = kinds.exists(!_.required))
    })
    // Expected rows of the view, in file then row-group order, and where each
    // row group ends.
    val expected = mutable.ArrayBuffer.empty[Vector[Cell]]
    val groupEnds = mutable.LinkedHashSet.empty[Int]
    c.files.foreach { f =>
      val at = names.map(n => f.columns.indexWhere(_.name == n))
      f.groups.foreach { g =>
        g.rows.foreach { row =>
          expected += at.map { i =>
            val v = row(i)
            val kind = f.columns(i).kind
            if (v == null) Cell(null, unreadable = false)
            else if (kind.unreadable(v)) Cell(null, unreadable = true)
            else Cell(kind.expected(v), unreadable = false)
          }
        }
        groupEnds += expected.length
      }
    }
    val totalGroups = c.files.map(_.groups.length).sum

    val view = ParquetReader.fromPath(dir.toString, c.read.batchSize, c.read.targetBytes)
    if (view.schema != expectedSchema) fail(s"the view's schema is ${view.schema}, not $expectedSchema")
    if (view.exactRowCount != Some(expected.length.toLong))
      fail(s"exactRowCount is ${view.exactRowCount}, not ${expected.length}")

    // A column holding a value no engine value holds makes any read of it
    // fail; nothing else about the read can be checked then.
    val unreadable = names.indices.filter(i => expected.exists(_(i).unreadable)).map(names)
    if (unreadable.nonEmpty) {
      expectFailure(view, unreadable, c)
      c.read.projection.foreach { keep =>
        val projected = view.withProjectedColumns(keep).getOrElse(fail(s"projection onto $keep was refused"))
        val stillUnreadable = unreadable.filter(keep.contains)
        if (stillUnreadable.nonEmpty) expectFailure(projected, stillUnreadable, c)
        else compareRows("projected read", projected, expected.toSeq.map(keepCells(names, keep)), c)
      }
      return Report(expected.length, totalGroups, view.numPartitions, failedAsExpected = true,
        projectedPastFirstGroup = false, pushed = false, pushdownSkippedRows = false)
    }

    // 3. Every row, in order.
    val partitionSizes = compareRows("read", view, expected.toSeq.map(_.map(_.value)), c)

    // 4. Partitions are whole runs of row groups.
    var end = 0
    partitionSizes.foreach { n =>
      end += n
      if (!groupEnds.contains(end)) fail(
        s"a partition ends at row $end, inside a row group (groups end at ${groupEnds.mkString(", ")})")
    }
    val filesWithRows = c.files.count(_.groups.nonEmpty)
    if (c.read.targetBytes == 1L && view.numPartitions != totalGroups)
      fail(s"a one-byte target gives ${view.numPartitions} partitions for $totalGroups row groups")
    if (c.read.targetBytes == ParquetReader.DefaultTargetBytesPerPartition && view.numPartitions != filesWithRows)
      fail(s"the default target gives ${view.numPartitions} partitions for $filesWithRows files with rows")

    // 5. A projection.
    val projectedPastFirstGroup = c.read.projection.exists { keep =>
      val projected = view.withProjectedColumns(keep).getOrElse(fail(s"projection onto $keep was refused"))
      val wantNames = names.filter(keep.contains)
      if (projected.schema.fieldNames != wantNames)
        fail(s"projection onto ${keep.mkString(",")} has columns ${projected.schema.fieldNames}, not $wantNames")
      compareRows("projected read", projected, expected.toSeq.map(keepCells(names, keep)), c)
      view.numPartitions > filesWithRows
    }

    // 6. Pushdown never skips a row the predicate keeps.
    val (pushed, skipped) = c.read.pushdown match {
      case None => (false, false)
      case Some(p) =>
        val expr = ParquetGen.toExpr(p, view.schema)
        view.withPushdownFilter(expr) match {
          case None => (false, false)
          case Some(pushedView) =>
            val all = kept(view, expr)
            val fromPushed = kept(pushedView, expr)
            if (!sameRows(all, fromPushed)) fail(
              s"pushdown of ${ParquetGen.showPred(p)} skips rows the predicate keeps: " +
                s"${all.length} rows match over the whole scan, ${fromPushed.length} over the pushed one\n" +
                firstDifference(all, fromPushed))
            (true, rowsOf(pushedView, reversed = false).length < expected.length)
        }
    }

    Report(expected.length, totalGroups, view.numPartitions, failedAsExpected = false,
      projectedPastFirstGroup, pushed, skipped)
  }

  private def keepCells(names: Vector[String], keep: Seq[String])(row: Vector[Cell]): Seq[Any] =
    names.indices.filter(i => keep.contains(names(i))).map(i => row(i).value)

  /** Read every partition of `view` (in the case's order, reassembled by
    * index) and compare with `want` positionally. Returns the rows per
    * partition. */
  private def compareRows(label: String, view: CatalogView, want: Seq[Seq[Any]], c: ParquetCase): Seq[Int] = {
    val parts = partitions(view, c.read.reversed, Some(c.read.batchSize))
    val got = parts.flatten
    if (got.length != want.length) fail(s"$label returns ${got.length} rows, not ${want.length}\n" +
      firstDifference(want.map(_.toVector).toVector, got.toVector))
    var r = 0
    while (r < want.length) {
      var col = 0
      while (col < want(r).length) {
        if (!same(want(r)(col), got(r)(col))) fail(
          s"$label row $r column '${view.schema.fields(col).name}' is ${RowOracle.describe(got(r)(col))}, " +
            s"not ${RowOracle.describe(want(r)(col))}")
        col += 1
      }
      r += 1
    }
    parts.map(_.length)
  }

  /** Every row of every partition, partitions read in reverse when
    * `reversed` (each opens its file on its own, so order must not matter),
    * returned in partition order. Checks each batch holds between one row and
    * `batchSize`. */
  private def partitions(view: CatalogView, reversed: Boolean, batchSize: Option[Int]): Seq[Vector[Vector[Any]]] = {
    val n = view.numPartitions
    val out = new Array[Vector[Vector[Any]]](n)
    val order = if (reversed) (n - 1 to 0 by -1) else (0 until n)
    order.foreach { p =>
      val rows = Vector.newBuilder[Vector[Any]]
      val it = view.readPartition(p)
      while (it.hasNext) {
        val b = it.next()
        batchSize.foreach { max =>
          if (b.numRows < 1 || b.numRows > max) fail(s"partition $p returned a batch of ${b.numRows} rows (batch size $max)")
        }
        var r = 0
        while (r < b.numRows) {
          rows += Vector.tabulate(view.schema.length)(col => b.column(col).getBoxed(r))
          r += 1
        }
      }
      out(p) = rows.result()
    }
    out.toSeq
  }

  private def rowsOf(view: CatalogView, reversed: Boolean): Vector[Vector[Any]] =
    partitions(view, reversed, None).flatten.toVector

  /** The rows of `view` the predicate keeps, as a filter above the scan
    * keeps them: true, not NULL. */
  private def kept(view: CatalogView, expr: Expr): Vector[Vector[Any]] = {
    val out = Vector.newBuilder[Vector[Any]]
    (0 until view.numPartitions).foreach { p =>
      val it = view.readPartition(p)
      while (it.hasNext) {
        val b = it.next()
        val mask = expr.evalVec(b)
        var r = 0
        while (r < b.numRows) {
          if (!mask.isNull(r) && mask.getBoxed(r) == true)
            out += Vector.tabulate(view.schema.length)(col => b.column(col).getBoxed(r))
          r += 1
        }
      }
    }
    out.result()
  }

  private def expectFailure(view: CatalogView, columns: Seq[String], c: ParquetCase): Unit = {
    val message =
      try {
        partitions(view, c.read.reversed, None)
        fail(s"reading ${columns.mkString(", ")}, which hold a value no engine value holds, succeeded")
      } catch {
        case e: AssertionError => throw e
        case e: Exception => String.valueOf(e.getMessage)
      }
    if (!columns.exists(col => message.contains(s"'$col'")))
      fail(s"the failed read names none of ${columns.mkString(", ")}: $message")
  }

  // ---- comparison ------------------------------------------------------------

  private def same(want: Any, got: Any): Boolean = RowOracle.identical(want, got)

  private def sameRows(a: Vector[Vector[Any]], b: Vector[Vector[Any]]): Boolean =
    a.length == b.length && a.indices.forall(i => a(i).length == b(i).length && a(i).indices.forall(j => same(a(i)(j), b(i)(j))))

  private def firstDifference(want: Vector[Seq[Any]], got: Vector[Seq[Any]]): String = {
    def show(row: Seq[Any]): String = row.map(RowOracle.show).mkString("(", ", ", ")")
    val i = want.indices.find(i => i >= got.length || got(i).length != want(i).length ||
      got(i).indices.exists(j => !same(want(i)(j), got(i)(j)))).getOrElse(want.length)
    if (i < want.length) s"  first difference at row $i: wanted ${show(want(i))}, got ${got.lift(i).fold("nothing")(show)}"
    else if (got.length > want.length) s"  extra row $i: ${show(got(i))}"
    else "  (no row differs)"
  }

  private def quote(s: String): String = if (s == null) "NULL" else ParquetGen.show(s)

  private def fail(msg: String): Nothing = throw new AssertionError(s"[parquet-differential] $msg")

  private[fuzz] def deleteRecursively(p: Path): Unit = {
    if (p == null || !Files.exists(p)) return
    if (Files.isDirectory(p)) {
      val s = Files.list(p)
      try { val it = s.iterator(); while (it.hasNext) deleteRecursively(it.next()) }
      finally s.close()
    }
    Files.deleteIfExists(p)
  }
}
