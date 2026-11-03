package com.transformer.fuzz.oracle

import com.transformer.core.{Catalog, DataType, ExecutionOptions, Field, MaterializedView, Schema}
import com.transformer.fuzz.{DataGen, Rng, RowOracle}

/** Several windows in one SELECT equal a join, on a row id, of one-window
  * queries (RisingWave's `over_window` cross_check, `e2e_test/`).
  *
  * `SELECT id, w1, w2 FROM t`, with two window functions over different
  * PARTITION BY or ORDER BY, must answer as `SELECT id, w1 FROM t` and
  * `SELECT id, w2 FROM t` joined on `id`, which is unique. The engine computes
  * every window of a SELECT in one `WindowExec`, partitioning and sorting its
  * input once per spec, so the two-window query is the one place one spec's
  * pass can disturb another's. Only windows whose value cannot depend on how
  * ties fall are drawn ([[WindowCase]]): a rank, an aggregate over the whole
  * partition, the default frame or a RANGE frame, or any function whose ORDER
  * BY ends in `id`, which breaks every tie.
  */
object WindowCrossCheck {

  /** `t(id, g, h, k, d, x, s)`: `id` unique, `g` and `h` partition keys of a
    * few values, `k`, `d` and `x` numbers with ties and NULLs to order by and
    * add up, `s` text. */
  val TableSchema: Schema = Schema(Vector(
    Field("id", DataType.IntType), Field("g", DataType.IntType), Field("h", DataType.IntType),
    Field("k", DataType.IntType), Field("d", DataType.DoubleType), Field("x", DataType.LongType),
    Field("s", DataType.StringType)))

  /** One window function: its SQL, and what kind of frame and function it
    * is, for the guard. */
  final case class Win(sql: String, partition: Seq[String], order: Seq[String], kind: String) {
    def spec: (Seq[String], Seq[String]) = (partition, order)
  }

  final case class WindowCase(dataset: DataGen.Dataset, w1: Win, w2: Win) {
    def both: String = s"SELECT id, ${w1.sql} AS w1, ${w2.sql} AS w2 FROM t"
    def first: String = s"SELECT id, ${w1.sql} AS w1 FROM t"
    def second: String = s"SELECT id, ${w2.sql} AS w2 FROM t"
    override def toString: String = s"WindowCase(\n  $both\n  data = $dataset)"
  }

  def generate(rng: Rng): WindowCase = {
    val dataRng = rng.split()
    val n = dataRng.between(0, 24)
    def maybe(p: Double)(v: => Any): Any = if (dataRng.bool(p)) null else v
    val rows = (0 until n).map { i =>
      Array[Any](i, maybe(0.1)(dataRng.between(0, 2)), maybe(0.1)(dataRng.between(0, 1)),
        maybe(0.15)(dataRng.between(-2, 3)),
        maybe(0.15)(dataRng.oneOf(Seq(-1.5, 0.0, 0.5, 2.5, Double.NaN))),
        maybe(0.1)(dataRng.between(-3, 9).toLong),
        maybe(0.15)(dataRng.oneOf(Seq("a", "b", "ab", ""))))
    }
    val q = rng.split()
    val w1 = genWin(q)
    var w2 = genWin(q)
    var tries = 0
    while (w2.spec == w1.spec && tries < 20) { w2 = genWin(q); tries += 1 }
    WindowCase(DataGen.Dataset(TableSchema, rows), w1, w2)
  }

  private def direction(r: Rng, key: String): String =
    key + (if (r.bool()) "" else " DESC") + r.weighted((3, ""), (1, " NULLS FIRST"), (1, " NULLS LAST"))

  /** One window over `t` whose value no tie decides. */
  def genWin(r: Rng): Win = {
    val partition = Seq("g", "h", "s").filter(_ => r.bool(0.4))
    val keys = Seq("k", "d", "x", "s", "g").filter(_ => r.bool(0.35))
    val ordered = if (keys.isEmpty) Seq(r.oneOf(Seq("k", "d", "x"))) else keys
    def over(order: Seq[String], frame: String): String = {
      val parts = Seq(
        if (partition.isEmpty) "" else "PARTITION BY " + partition.mkString(", "),
        if (order.isEmpty) "" else "ORDER BY " + order.map(direction(r, _)).mkString(", "),
        frame).filter(_.nonEmpty)
      s"OVER (${parts.mkString(" ")})"
    }
    val withId = ordered :+ "id"
    def aggregate(): String = r.oneOf(Seq("SUM(x)", "COUNT(x)", "COUNT(*)", "MIN(x)", "MAX(s)", "SUM(k)", "MIN(d)"))
    r.weighted[() => Win](
      (2, () => Win(s"ROW_NUMBER() ${over(withId, "")}", partition, withId, "ROW_NUMBER by id")),
      (2, () => Win(s"${r.oneOf(Seq("RANK", "DENSE_RANK"))}() ${over(ordered, "")}", partition, ordered, "rank")),
      (2, () => {
        val value = r.oneOf(Seq("x", "s", "k"))
        val args = r.weighted((2, value), (1, s"$value, ${r.between(1, 3)}"),
          (1, s"$value, ${r.between(1, 3)}, ${if (value == "s") "'z'" else if (value == "x") "CAST(0 AS BIGINT)" else "0"}"))
        Win(s"${r.oneOf(Seq("LAG", "LEAD"))}($args) ${over(withId, "")}", partition, withId, "LAG or LEAD by id")
      }),
      (2, () => Win(s"${aggregate()} ${over(Nil, "")}", partition, Nil, "whole partition")),
      (2, () => Win(s"${aggregate()} ${over(ordered, "")}", partition, ordered, "default frame")),
      (2, () => {
        val frame = com.transformer.fuzz.QueryGen.genFrame(r, rows = true)
        Win(s"${aggregate()} ${over(withId, frame.sql)}", partition, withId, "ROWS frame by id")
      }),
      (2, () => {
        val key = r.oneOf(Seq("k", "x"))
        val frame = com.transformer.fuzz.QueryGen.genFrame(r, rows = false)
        Win(s"${aggregate()} ${over(Seq(key), frame.sql)}", partition, Seq(key), "RANGE frame")
      }))()
  }

  /** The layouts each case runs in: one partition, and three of two-row
    * batches. */
  private def layouts(c: WindowCase): Seq[(String, MaterializedView)] =
    Seq("one partition" -> c.dataset.singlePartition(), "three partitions" -> c.dataset.evenPartitions(3, 2))

  /** Run the two-window query and the two one-window ones under each layout;
    * an [[AssertionError]] says where the two-window query answered
    * otherwise. False when the engine refuses a query (a frame its key cannot
    * take), so nothing was checked. */
  def check(c: WindowCase, opts: ExecutionOptions = ExecutionOptions.Default): Boolean = {
    layouts(c).foreach { case (label, view) =>
      val catalog = new Catalog
      catalog.register("t", view)
      val (schema, both, first, second) =
        try {
          val q = RelEngine.execute(catalog, c.both, opts)
          (q.schema, RelEngine.collectRows(q), RelEngine.runRows(catalog, c.first, opts),
            RelEngine.runRows(catalog, c.second, opts))
        } catch { case _: IllegalArgumentException => return false }
      val w1 = first.map(r => r(0) -> r(1)).toMap
      val w2 = second.map(r => r(0) -> r(1)).toMap
      val joined = first.map(r => Array[Any](r(0), r(1), w2.getOrElse(r(0), MissingId)))
      if (w1.size != first.size || w2.size != second.size)
        throw new AssertionError(s"[window-cross-check] a one-window query repeats an id ($label)\n$c")
      RowOracle.multisetEquals(schema, joined, both).foreach { diff =>
        throw new AssertionError(s"[window-cross-check] two windows in one SELECT answer otherwise than each " +
          s"alone ($label)\n  both:   ${c.both}\n  first:  ${c.first}\n  second: ${c.second}\n$diff")
      }
    }
    true
  }

  private object MissingId { override def toString: String = "<no row of this id>" }

  /** The kinds of window [[genWin]] draws, by name. */
  val Kinds: Seq[String] = Seq("ROW_NUMBER by id", "rank", "LAG or LEAD by id", "whole partition", "default frame",
    "ROWS frame by id", "RANGE frame")

  /** Smaller cases: the table with a row dropped. */
  def shrink(c: WindowCase): Iterator[WindowCase] =
    c.dataset.rows.indices.iterator.map(i => c.copy(dataset = c.dataset.copy(rows = c.dataset.rows.patch(i, Nil, 1))))
}
