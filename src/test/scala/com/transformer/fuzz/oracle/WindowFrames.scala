package com.transformer.fuzz.oracle

import com.transformer.core._
import com.transformer.fuzz.QueryGen.{GenBound, WindowFrameCase}
import com.transformer.sql.exec.SqlEngine

import scala.util.control.NonFatal

/** A window aggregate held to SQL's definition of its frame, in every
  * execution layout.
  *
  * The agreement properties compare layouts with each other, and RANGE frames
  * ran as ROWS in all of them alike, so a running total over tied keys was
  * wrong the same way everywhere, and `RANGE 1 PRECEDING` counted one row
  * (plans/robustness/20). Here the harness works out each row's frame itself,
  * as a set rather than as positions found by a search: a ROWS frame is the
  * rows within the offsets of the current row's position, and a RANGE frame
  * the rows whose ORDER BY value is no earlier in the sort than the start's
  * value and no later than the end's, `CURRENT ROW` being the current row's
  * own value (so its peers come with it), and `k PRECEDING` / `k FOLLOWING`
  * the value `k` before or after it in the sort's direction. NULL sorts where
  * the key says, and NaN above every number, as the engine sorts them. The
  * aggregate over that set is the answer, for every row, in every mode of
  * [[ModeDifferential.allModes]], spill included.
  */
object WindowFrames {

  private val Id = 0
  private val G = 1
  private val K = 2
  private val X = 3

  /** Order two ORDER BY values as the sort does: NULLs first or last, NaN above
    * every number, and the direction. */
  private def sortCompare(c: WindowFrameCase, a: Any, b: Any): Int = {
    val o = c.order.get
    if (a == null && b == null) 0
    else if (a == null) { if (o.nullsFirstNow) -1 else 1 }
    else if (b == null) { if (o.nullsFirstNow) 1 else -1 }
    else {
      val v = (a, b) match {
        case (x: java.lang.Double, y: java.lang.Double) => java.lang.Double.compare(x, y)
        case (x: java.lang.Double, y: java.math.BigDecimal) => java.lang.Double.compare(x, y.doubleValue)
        case (x: java.lang.Number, y: java.lang.Number) =>
          new java.math.BigDecimal(x.toString).compareTo(new java.math.BigDecimal(y.toString))
        case (x, y) => throw new IllegalArgumentException(s"an ORDER BY value that is not a number: $x or $y")
      }
      if (o.asc) v else -v
    }
  }

  /** `v` moved `k` along the sort (`forward`) or against it; NULL, NaN and the
    * infinities do not move. */
  private def moved(c: WindowFrameCase, v: Any, k: java.math.BigDecimal, forward: Boolean): Any = {
    val up = forward == c.order.get.asc
    v match {
      case null => null
      case d: java.lang.Double => java.lang.Double.valueOf(if (up) d + k.doubleValue else d - k.doubleValue)
      case n: java.lang.Number =>
        val x = new java.math.BigDecimal(n.toString)
        if (up) x.add(k) else x.subtract(k)
      case other => throw new IllegalArgumentException(s"an ORDER BY value that is not a number: $other")
    }
  }

  /** Each row's answer, by id. */
  private def reference(c: WindowFrameCase): Map[Int, Any] = {
    val rows = c.dataset.rows
    val parts = if (c.partitioned) rows.groupBy(r => Option(r(G))).values.toSeq else Seq(rows)
    val rowsFrame = c.frame.exists(_.rows)
    val (start, end): (GenBound, GenBound) = c.frame match {
      case Some(f) => (f.start, if (f.short) GenBound(2, java.math.BigDecimal.ZERO) else f.end)
      // The default frame: the whole partition, or up to the current row's
      // last peer when there is an ORDER BY.
      case None => (GenBound(0, null), if (c.order.isDefined) GenBound(2, null) else GenBound(4, null))
    }
    parts.flatMap { part =>
      val sorted: IndexedSeq[Array[Any]] = c.order match {
        case Some(_) => part.sortWith { (a, b) =>
          val byKey = sortCompare(c, a(K), b(K))
          if (byKey != 0) byKey < 0 else a(Id).asInstanceOf[Int] < b(Id).asInstanceOf[Int]
        }
        case None => part
      }
      sorted.indices.map { i =>
        val cur = sorted(i)
        def startOk(p: Int): Boolean =
          if (rowsFrame) start.kind match {
            case 0 => true
            case 1 => p >= i - start.offset.intValue
            case 2 => p >= i
            case _ => p >= i + start.offset.intValue
          } else start.kind match {
            case 0 => true
            case _ if c.order.isEmpty => true
            case 1 => sortCompare(c, sorted(p)(K), moved(c, cur(K), start.offset, forward = false)) >= 0
            case 2 => sortCompare(c, sorted(p)(K), cur(K)) >= 0
            case _ => sortCompare(c, sorted(p)(K), moved(c, cur(K), start.offset, forward = true)) >= 0
          }
        def endOk(p: Int): Boolean =
          if (rowsFrame) end.kind match {
            case 4 => true
            case 1 => p <= i - end.offset.intValue
            case 2 => p <= i
            case _ => p <= i + end.offset.intValue
          } else end.kind match {
            case 4 => true
            case _ if c.order.isEmpty => true
            case 1 => sortCompare(c, sorted(p)(K), moved(c, cur(K), end.offset, forward = false)) <= 0
            case 2 => sortCompare(c, sorted(p)(K), cur(K)) <= 0
            case _ => sortCompare(c, sorted(p)(K), moved(c, cur(K), end.offset, forward = true)) <= 0
          }
        val frame = sorted.indices.filter(p => startOk(p) && endOk(p)).map(sorted(_))
        cur(Id).asInstanceOf[Int] -> aggregate(c.fn, frame)
      }
    }.toMap
  }

  private def aggregate(fn: String, frame: Seq[Array[Any]]): Any = {
    val xs = frame.flatMap(r => Option(r(X)).map(_.asInstanceOf[Long]))
    fn match {
      case "COUNT(*)" => frame.size.toLong
      case "COUNT(x)" => xs.size.toLong
      case "SUM(x)" => if (xs.isEmpty) null else xs.sum
      case "MIN(x)" => if (xs.isEmpty) null else xs.min
      case "MAX(x)" => if (xs.isEmpty) null else xs.max
      case "AVG(x)" => if (xs.isEmpty) null else xs.sum.toDouble / xs.size
    }
  }

  def check(c: WindowFrameCase): Unit = {
    val expected = reference(c)
    ModeDifferential.allModes(c.dataset).foreach { case (label, view, opts) =>
      val catalog = new Catalog
      catalog.register("t", view)
      val got: Map[Int, Any] =
        try {
          val q = SqlEngine.execute(c.sql, catalog, opts)
          val out = Map.newBuilder[Int, Any]
          q.batches.foreach { b =>
            (0 until b.numRows).foreach { r =>
              out += b.column(0).getBoxed(r).asInstanceOf[Int] -> (if (b.column(1).isNull(r)) null else b.column(1).getBoxed(r))
            }
          }
          out.result()
        } catch {
          case NonFatal(t) => throw new AssertionError(s"[$label] ${c.sql} threw $t", t)
        }
      if (got.keySet != expected.keySet)
        throw new AssertionError(s"[$label] ${c.sql}: rows ${got.keySet.toSeq.sorted} but expected ${expected.keySet.toSeq.sorted}")
      expected.foreach { case (id, want) =>
        val have = got(id)
        val same = (want, have) match {
          case (null, null) => true
          case (w: Double, h: java.lang.Double) => w == h.doubleValue
          case (w: Long, h: java.lang.Long) => w == h.longValue
          case _ => false
        }
        if (!same) throw new AssertionError(s"[$label] ${c.sql}: row id=$id is $have, but its frame gives $want")
      }
    }
  }

  /** Whether a case reaches what ROWS semantics gets wrong: a RANGE frame (or
    * the default one with an ORDER BY) in which some row's frame by value is not
    * the frame its offsets would give by position, because of a tie or a gap
    * in the ORDER BY values. */
  def rangeDiffersFromRows(c: WindowFrameCase): Boolean =
    c.order.isDefined && !c.frame.exists(_.rows) && {
      val byValue = reference(c)
      val byPosition = reference(c.copy(order = c.order, frame = Some(c.frame.getOrElse(
        com.transformer.fuzz.QueryGen.GenFrame(rows = false, GenBound(0, null), GenBound(2, null), short = false)).copy(rows = true))))
      byValue != byPosition
    }
}
