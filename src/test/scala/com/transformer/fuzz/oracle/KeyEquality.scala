package com.transformer.fuzz.oracle

import com.transformer.core._
import com.transformer.fuzz.DataGen
import com.transformer.fuzz.DataGen.KeyCase
import com.transformer.sql.exec.SqlEngine
import com.transformer.sql.plan.Ops

import scala.util.control.NonFatal

/** Every operator that matches, groups or ranks keys agrees with `=`.
  *
  * A join, GROUP BY, DISTINCT, COUNT(DISTINCT), UNION, PARTITION BY and a rank's
  * peers each decide when two keys are the same, and each used to decide it
  * its own way for floats: `=` called NaN unequal to itself and -0.0 equal to
  * 0.0, while the key codecs keyed floats by bit pattern and the sort
  * separated -0.0 from 0.0. So `l JOIN r ON l.k = r.k` matched NaN to NaN,
  * which `WHERE l.k = r.k` over the same rows did not, and missed -0.0 to 0.0,
  * which it kept (plans/robustness/21). No property saw it: the join
  * relations compare a hash join with another hash join.
  *
  * Here the harness holds every one of those operators to `Ops.eq`, the
  * engine's `=`, over keys of float, decimal and integer types that carry
  * NaN (several payloads), both zeros, the infinities, one decimal at several
  * scales and NULL, in every execution layout ([[ModeDifferential.allModes]]),
  * spill and its grace hash join included:
  *
  *   - an inner join, the same predicate over a cross join, and a left join
  *     give exactly the pairs whose keys are `=`;
  *   - GROUP BY, DISTINCT, COUNT(DISTINCT) and UNION give one group per class
  *     of keys `=` calls equal (NULLs one more), each group's count its class's
  *     size and no two groups' keys `=`;
  *   - PARTITION BY gives each row its class's size, and RANK a row 1 plus the
  *     rows `Ops.cmp` orders before it.
  */
object KeyEquality {

  private def keysOf(d: DataGen.Dataset): Seq[(Int, Any)] = d.rows.map(r => (r(0).asInstanceOf[Int], r(1)))

  /** Partition values into the classes `=` makes, NULLs left out. */
  private def classes(values: Seq[Any]): Seq[Seq[Any]] = {
    val out = scala.collection.mutable.ArrayBuffer.empty[scala.collection.mutable.ArrayBuffer[Any]]
    values.filter(_ != null).foreach { v =>
      out.find(c => Ops.eq(c.head, v)) match {
        case Some(c) => c += v
        case None => out += scala.collection.mutable.ArrayBuffer(v)
      }
    }
    out.map(_.toSeq).toSeq
  }

  def check(c: KeyCase): Unit = {
    val l = keysOf(c.l)
    val r = keysOf(c.r)
    def eqKeys(a: Any, b: Any): Boolean = a != null && b != null && Ops.eq(a, b)
    val pairs = (for ((li, lk) <- l; (ri, rk) <- r if eqKeys(lk, rk)) yield s"$li|$ri").sorted
    val leftPairs = (l.flatMap { case (li, lk) =>
      val matched = r.collect { case (ri, rk) if eqKeys(lk, rk) => s"$li|$ri" }
      if (matched.isEmpty) Seq(s"$li|NULL") else matched
    }).sorted

    val lModes = ModeDifferential.allModes(c.l)
    val rModes = ModeDifferential.allModes(c.r)
    lModes.zip(rModes).foreach { case ((label, lView, opts), (_, rView, _)) =>
      val catalog = new Catalog
      catalog.register("l", lView)
      catalog.register("r", rView)
      def rows(sql: String): Seq[Seq[Any]] =
        try {
          val q = SqlEngine.execute(sql, catalog, opts)
          val out = Seq.newBuilder[Seq[Any]]
          q.batches.foreach { b =>
            (0 until b.numRows).foreach { i =>
              out += (0 until b.schema.length).map(col => if (b.column(col).isNull(i)) null else b.column(col).getBoxed(i))
            }
          }
          out.result()
        } catch { case NonFatal(t) => throw new AssertionError(s"[$label] $sql threw $t", t) }
      def fail(sql: String, why: String): Nothing = throw new AssertionError(s"[$label] $sql: $why\n  $c")
      def show(rs: Seq[Seq[Any]]): Seq[String] = rs.map(_.map(v => if (v == null) "NULL" else v.toString).mkString("|")).sorted

      Seq(
        "SELECT l.id AS a, r.id AS b FROM l JOIN r ON l.k = r.k",
        "SELECT l.id AS a, r.id AS b FROM l CROSS JOIN r WHERE l.k = r.k"
      ).foreach { sql =>
        val got = show(rows(sql))
        if (got != pairs) fail(sql, s"pairs ${got.mkString(", ")}, but the keys `=` pair ${pairs.mkString(", ")}")
      }
      val leftSql = "SELECT l.id AS a, r.id AS b FROM l LEFT JOIN r ON l.k = r.k"
      val gotLeft = show(rows(leftSql))
      if (gotLeft != leftPairs) fail(leftSql, s"pairs ${gotLeft.mkString(", ")}, expected ${leftPairs.mkString(", ")}")

      // One group per class, counted, and no two groups equal.
      def groups(sql: String, keys: Seq[Any], counted: Boolean): Unit = {
        val got = rows(sql)
        val nonNull = got.map(_.head).filter(_ != null)
        for (i <- nonNull.indices; j <- nonNull.indices if i < j)
          if (Ops.eq(nonNull(i), nonNull(j))) fail(sql, s"two groups ${nonNull(i)} and ${nonNull(j)} are `=`")
        val want = classes(keys)
        if (nonNull.size != want.size) fail(sql, s"${nonNull.size} groups of non-NULL keys, but `=` makes ${want.size}")
        if (got.count(_.head == null) != (if (keys.contains(null)) 1 else 0)) fail(sql, "NULL keys are not one group")
        if (counted) got.foreach { row =>
          val members = if (row.head == null) keys.count(_ == null) else keys.count(k => eqKeys(k, row.head))
          if (row(1).asInstanceOf[Long] != members) fail(sql, s"group ${row.head} counts ${row(1)}, but $members keys are `=` to it")
        }
      }
      groups("SELECT k, COUNT(*) AS n FROM l GROUP BY k", l.map(_._2), counted = true)
      groups("SELECT DISTINCT k FROM l", l.map(_._2), counted = false)
      groups("SELECT k FROM l UNION SELECT k FROM r", l.map(_._2) ++ r.map(_._2), counted = false)
      val distinctSql = "SELECT COUNT(DISTINCT k) AS n FROM l"
      val distinct = rows(distinctSql).head.head.asInstanceOf[Long]
      if (distinct != classes(l.map(_._2)).size) fail(distinctSql, s"$distinct, but `=` makes ${classes(l.map(_._2)).size} classes")

      val partitionSql = "SELECT id, COUNT(*) OVER (PARTITION BY k) AS n FROM l"
      rows(partitionSql).foreach { row =>
        val key = l.find(_._1 == row.head).get._2
        val members = if (key == null) l.count(_._2 == null) else l.count(x => eqKeys(x._2, key))
        if (row(1).asInstanceOf[Long] != members) fail(partitionSql, s"row ${row.head}'s partition has ${row(1)} rows, but $members keys are `=` to its key")
      }
      val rankSql = "SELECT id, RANK() OVER (ORDER BY k) AS rk FROM l"
      rows(rankSql).foreach { row =>
        val key = l.find(_._1 == row.head).get._2
        // NULLs sort first under ASC; a non-NULL key ranks after them.
        val before = l.count { case (_, k) =>
          if (key == null) false else k == null || Ops.cmp(k, key) < 0
        }
        if (row(1).asInstanceOf[Long] != before + 1) fail(rankSql, s"row ${row.head} ranks ${row(1)}, but $before rows order before it")
      }
    }
  }
}
