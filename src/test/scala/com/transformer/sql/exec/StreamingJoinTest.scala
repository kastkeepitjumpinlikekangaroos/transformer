package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.Assume.assumeTrue
import org.junit.Test

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import java.util.concurrent.atomic.AtomicInteger
import scala.util.Random

/** Every hash join streams its output: it holds its build side and a bounded
  * amount of output, never its whole result (the target runs at `-Xmx256m`).
  *
  *   - **The equivalence matrix.** Every kind, both build sides, every key
  *     path (one long, packed keys, one string, object keys, a computed key),
  *     with and without a residual condition, NULL keys on both sides, an
  *     empty build side, an empty probe side, a probe side of no partitions
  *     and empty partitions, over 1, 3 and 5 partitions, on every path
  *     (broadcast or in waves as built, per shard over exchanges of four
  *     shards, grace hash, grace hash per shard): each answers as a nested
  *     loop over the two sides does, and a broadcast join in its probe
  *     order.
  *   - **The shared build side.** A broadcast join's build side is built
  *     once, by the pass, and read by every partition: `SqlEngine.execute`
  *     reads the build side and not the probe side; a diamond builds once and
  *     answers twice; the pass sees through the metrics wrapper; racing
  *     first callers of a plan run without the pass build once, and a failed
  *     build reaches every one of them as itself.
  *   - **A window over a join** reads the join's partitions at once and keeps
  *     its ties in partition order.
  *   - **Bounded memory.** Joins returning 50 to 80 million rows, several GB
  *     boxed, through a 256 MB heap: INNER under an aggregate, LEFT with
  *     unmatched keys, INNER read flat on one thread, FULL in waves, per shard
  *     and grace hash; and a pairing of 25 million rows.
  */
class StreamingJoinTest {

  import StreamingJoinTest._

  // ---------------------------------------------------------------------------
  // Pairings (no equality between the sides)
  // ---------------------------------------------------------------------------

  private val ints = Schema(Vector(Field("x", DataType.IntType), Field("tag", DataType.StringType)))

  /** Inner, LEFT, RIGHT and FULL joins on `l.x < r.x` over tables of 300
    * rows with NULLs, in several partitions: each probe partition takes
    * several waves (a wave's task does about 32768 units of work, a hundred
    * probe rows here), and the answer is the nested loop's. */
  @Test def everyKindOfPairingAnswersAsANestedLoop(): Unit = {
    val rng = new Random(7)
    def table(n: Int, name: String) =
      (0 until n).map(i => Array[Any](if (rng.nextInt(10) == 0) null else rng.nextInt(50), s"$name$i"))
    val (l, r) = (table(300, "l"), table(300, "r"))
    val catalog = new Catalog
    catalog.register("l", view(ints, l.toVector, 3))
    catalog.register("r", view(ints, r.toVector, 2))
    def less(a: Array[Any], b: Array[Any]) =
      a(0) != null && b(0) != null && a(0).asInstanceOf[Int] < b(0).asInstanceOf[Int]
    val inner = for (a <- l; b <- r if less(a, b)) yield Seq[Any](a(0), a(1), b(0), b(1))
    val leftOnly = l.filterNot(a => r.exists(less(a, _))).map(a => Seq[Any](a(0), a(1), null, null))
    val rightOnly = r.filterNot(b => l.exists(less(_, b))).map(b => Seq[Any](null, null, b(0), b(1)))
    def check(kind: String, expected: Seq[Seq[Any]]): Unit = {
      val got = rows(SqlEngine.execute(s"SELECT l.x, l.tag, r.x, r.tag FROM l $kind JOIN r ON l.x < r.x", catalog,
        ExecutionOptions.Default))
      assertEquals(kind, sorted(expected), sorted(got))
    }
    check("INNER", inner)
    check("LEFT", inner ++ leftOnly)
    check("RIGHT", inner ++ rightOnly)
    check("FULL", inner ++ leftOnly ++ rightOnly)
    // An empty side pairs nothing, and an outer join keeps the other.
    catalog.register("e", view(ints, Vector.empty, 1))
    assertEquals(0, rows(SqlEngine.execute("SELECT * FROM l JOIN e ON l.x < e.x", catalog, ExecutionOptions.Default)).size)
    assertEquals(300, rows(SqlEngine.execute("SELECT * FROM l LEFT JOIN e ON l.x < e.x", catalog,
      ExecutionOptions.Default)).size)
  }

  /** 5000 rows a side, the most the nested-loop guard lets two sides hold,
    * paired on a condition every pair meets: 25 million rows of four columns,
    * a few GB held at once, counted in a 256 MB heap. */
  @Test def aPairingOf25MillionRowsStreams(): Unit = {
    val catalog = new Catalog
    val side = (0 until 5000).map(i => Array[Any](i, s"t$i")).toVector
    catalog.register("a", view(ints, side, 4))
    catalog.register("b", view(ints, side, 4))
    val q = SqlEngine.execute("SELECT COUNT(*), MIN(a.tag || b.tag) FROM a JOIN b ON a.x - b.x < 10000", catalog,
      ExecutionOptions.Default)
    assertEquals(Seq[Any](25000000L, "t0t0"), rows(q).head)
  }

  // ---------------------------------------------------------------------------
  // The equivalence matrix
  // ---------------------------------------------------------------------------

  /** Every kind × build side × key path × residual × shape × partitioning,
    * built directly on each path, against the nested loop. */
  @Test def everyPathAnswersAsANestedLoop(): Unit = {
    var cases = 0
    for {
      keys <- KeyPaths
      parts <- Seq(1, 3, 5)
      shape <- Shapes
      withExtra <- Seq(false, true)
      kind <- Kinds
      buildRight <- Seq(true, false)
    } {
      val c = MatrixCase(keys, parts, shape, withExtra, kind, buildRight)
      val expected = c.expected
      Paths.foreach { path =>
        val j = c.join(path)
        val got = runPlan(j)
        assertEquals(s"$c on the $path path", sorted(expected), sorted(got))
        assertEquals(s"$c on the $path path: partitions", c.expectedPartitions(path), j.numPartitions)
        if (path == "as built" && c.broadcast)
          assertEquals(s"$c on the $path path: probe order", c.inProbeOrder.map(_.mkString("|")), got.map(_.mkString("|")))
        cases += 1
      }
    }
    assertEquals(KeyPaths.size * 3 * Shapes.size * 2 * Kinds.size * 2 * Paths.size, cases)
  }

  /** The same tables as SQL, through the planner, which picks the build side
    * and the path: the nested loop's answer every time. */
  @Test def everyKindAnswersAsANestedLoopThroughThePlanner(): Unit =
    for {
      keys <- KeyPaths
      parts <- Seq(1, 3, 5)
      withExtra <- Seq(false, true)
      kind <- Kinds
    } {
      val c = MatrixCase(keys, parts, "rows", withExtra, kind, buildRight = true)
      val catalog = new Catalog
      catalog.register("l", new MaterializedView(c.leftSchema, c.leftParts))
      catalog.register("r", new MaterializedView(c.rightSchema, c.rightParts))
      val sql = s"SELECT * FROM l ${kind.toString.toUpperCase(java.util.Locale.ROOT)} JOIN r ON ${c.sqlCondition}"
      val got = rows(SqlEngine.execute(sql, catalog, ExecutionOptions.Default))
      assertEquals(sql, sorted(c.expected), sorted(got))
    }

  // ---------------------------------------------------------------------------
  // The shared build side
  // ---------------------------------------------------------------------------

  private val kw = Schema(Vector(Field("k", DataType.IntType), Field("w", DataType.LongType)))
  private val kv = Schema(Vector(Field("k", DataType.IntType), Field("v", DataType.LongType)))

  /** `SqlEngine.execute` builds a broadcast join's build side, reading each
    * of its partitions once, and opens none of the probe side's: each probe
    * partition is read as it is pulled. The join runs in the probe side's
    * four partitions. */
  @Test def executeBuildsTheBuildSideAndReadsNoProbeRow(): Unit = {
    val build = new Counting(view(kw, (0 until 30).map(i => Array[Any](i % 10, i.toLong)).toVector, 3))
    val probe = new Counting(view(kv, (0 until 200).map(i => Array[Any](i % 20, i.toLong)).toVector, 4))
    val catalog = new Catalog
    catalog.register("p", probe)
    catalog.register("b", build)
    val q = SqlEngine.execute("SELECT p.k, p.v, b.w FROM p JOIN b ON p.k = b.k", catalog, ExecutionOptions.Default)
    assertEquals("the join runs in the probe side's partitions", 4, q.numPartitions)
    assertEquals("each build partition read once", Vector(1, 1, 1), build.reads)
    assertEquals("no probe partition read", Vector(0, 0, 0, 0), probe.reads)
    val got = rows(q)
    assertEquals(100 * 3, got.size)
    assertEquals("the build side is not read again", Vector(1, 1, 1), build.reads)
    assertEquals(Vector(1, 1, 1, 1), probe.reads)
  }

  /** A join two parents share builds once, in the pass, and answers in
    * both. */
  @Test def aDiamondSharedJoinBuildsOnceAndAnswersTwice(): Unit = {
    val build = new Counting(view(kw, (0 until 30).map(i => Array[Any](i % 10, i.toLong)).toVector, 3))
    val probe = ScanExec(view(kv, (0 until 200).map(i => Array[Any](i % 20, i.toLong)).toVector, 4))
    val j = HashJoinExec(probe, ScanExec(build), Seq(key(0)), Seq(key(0)), None, JoinKind.Inner)
    val diamond = UnionExec(j, j)
    PhysicalPlanner.preMaterialize(diamond)
    assertEquals("the build side read once, by the pass", Vector(1, 1, 1), build.reads)
    assertEquals(2 * 100 * 3, runPlanAsIs(diamond).size)
    assertEquals("and not again", Vector(1, 1, 1), build.reads)
  }

  /** With metrics on every operator is wrapped; the pass reaches the join
    * beneath its wrapper. */
  @Test def thePassReachesAMeteredJoin(): Unit = {
    val build = new Counting(view(kw, (0 until 30).map(i => Array[Any](i % 10, i.toLong)).toVector, 3))
    val probe = ScanExec(view(kv, (0 until 200).map(i => Array[Any](i % 20, i.toLong)).toVector, 4))
    val (wrapped, _) = PhysicalPlanner.wrapWithMetrics(
      HashJoinExec(probe, ScanExec(build), Seq(key(0)), Seq(key(0)), None, JoinKind.Inner))
    PhysicalPlanner.preMaterialize(wrapped)
    assertEquals(Vector(1, 1, 1), build.reads)
    assertEquals(4, wrapped.numPartitions)
    assertEquals(300, runPlanAsIs(wrapped).size)
    assertEquals(Vector(1, 1, 1), build.reads)
  }

  /** A plan run without the pass builds on its first `execute`: of 32
    * threads racing for it, one builds, and every one reads the same rows. */
  @Test(timeout = 20000) def racingFirstCallersBuildOnce(): Unit = {
    val build = new Counting(view(kw, (0 until 300).map(i => Array[Any](i % 10, i.toLong)).toVector, 4))
    val probe = ScanExec(view(kv, (0 until 400).map(i => Array[Any](i % 20, i.toLong)).toVector, 4))
    val j = HashJoinExec(probe, ScanExec(build), Seq(key(0)), Seq(key(0)), None, JoinKind.Inner)
    val start = new CountDownLatch(1)
    val results = new Array[Vector[String]](32)
    val failures = new ConcurrentLinkedQueue[Throwable]
    val threads = (0 until 32).map { i =>
      val t = new Thread(() => {
        try {
          start.await()
          results(i) = sorted(drain(j.execute(i % 4)))
        } catch { case t: Throwable => failures.add(t) }
      })
      t.start()
      t
    }
    start.countDown()
    threads.foreach(_.join())
    assertTrue(s"racing callers failed: $failures", failures.isEmpty)
    assertEquals("the build side read once", Vector(1, 1, 1, 1), build.reads)
    (0 until 32).foreach(i => assertEquals(s"caller $i", results(i % 4), results(i)))
    assertEquals(200 * 30, (0 until 4).map(results(_).size).sum)
  }

  /** A build that fails reaches every caller as itself, and is never tried
    * again. */
  @Test(timeout = 20000) def aFailedBuildReachesEveryCallerAndIsNotRetried(): Unit = {
    val failing = new FailingPlan(kw, failPartition = 1, "a build side that fails")
    val probe = ScanExec(view(kv, (0 until 40).map(i => Array[Any](i % 20, i.toLong)).toVector, 4))
    val j = HashJoinExec(probe, failing, Seq(key(0)), Seq(key(0)), None, JoinKind.Inner)
    val start = new CountDownLatch(1)
    val observed = new ConcurrentLinkedQueue[Throwable]
    val threads = (0 until 16).map { i =>
      val t = new Thread(() => {
        try { start.await(); val _ = j.execute(i % 4); observed.add(new AssertionError(s"caller $i succeeded")) }
        catch { case t: Throwable => observed.add(t) }
      })
      t.start()
      t
    }
    start.countDown()
    threads.foreach(_.join())
    assertEquals(16, observed.size)
    val first = observed.peek()
    assertEquals("a build side that fails", first.getMessage)
    observed.forEach(t => assertSame(first, t))
    assertEquals("the failing partition read once", 1, failing.failCalls.get)
    try { val _ = j.execute(0); fail("the failure stays") }
    catch { case t: RuntimeException => assertSame(first, t) }
  }

  /** A window over every row of a join of five probe partitions reads them
    * at once, each into a buffer of its own, concatenated in partition
    * order: its ties come out as reading one partition after another gives
    * them. The probe side's size is unknown, so the join is broadcast
    * whatever the sharding gates. */
  @Test def aWindowOverAJoinKeepsItsTiesInPartitionOrder(): Unit = {
    val probeRows = (0 until 500).map(i => Array[Any](i % 7, i.toLong)).toVector
    val buildRows = (0 until 14).map(j => Array[Any](j % 7, j.toLong)).toVector
    val catalog = new Catalog
    catalog.register("p", new Counting(view(kv, probeRows, 5)))
    catalog.register("b", view(kw, buildRows, 2))
    val got = rows(SqlEngine.execute(
      "SELECT p.k, p.v, b.w, ROW_NUMBER() OVER (ORDER BY p.k) AS rn FROM p JOIN b ON p.k = b.k", catalog,
      ExecutionOptions.Default))
    // The join's rows in probe order, each numbered by its place in a stable
    // sort of them by k.
    val joined = probeRows.flatMap(p => buildRows.filter(_(0) == p(0)).map(b => Seq[Any](p(0), p(1), b(1))))
    val place = joined.indices.sortBy(i => joined(i)(0).asInstanceOf[Int]).zipWithIndex.toMap
    val expected = joined.indices.map(i => joined(i) :+ (place(i) + 1).toLong)
    assertEquals(expected.map(_.mkString("|")), got.map(_.mkString("|")))
  }

  // ---------------------------------------------------------------------------
  // Bounded memory
  // ---------------------------------------------------------------------------

  /** 2 million probe rows on 100 keys against 4000 build rows, 40 a key: 80
    * million joined rows, through an aggregate's partials. */
  @Test def anInnerJoinUnderAnAggregateStreams(): Unit = {
    assumeTrue("needs the default batch capacity", ColumnarBatch.DefaultCapacity >= 1024)
    val catalog = bigCatalog(probeRows = 2000000, probeKeys = 100, buildRows = 4000, buildKeys = 100, buildOffset = 0)
    val got = rows(SqlEngine.execute("SELECT COUNT(*), SUM(p.v), SUM(b.w) FROM p JOIN b ON p.k = b.k", catalog,
      ExecutionOptions.Default)).head
    val n = 2000000L
    assertEquals(Seq[Any](n * 40, 40L * (n * (n - 1) / 2), (n / 100) * (4000L * 3999 / 2)), got)
  }

  /** A LEFT join whose probe keys 100 to 109 match nothing: each keeps its
    * probe row, null-padded. */
  @Test def aLeftJoinWithUnmatchedKeysStreams(): Unit = {
    assumeTrue("needs the default batch capacity", ColumnarBatch.DefaultCapacity >= 1024)
    val catalog = bigCatalog(probeRows = 2200000, probeKeys = 110, buildRows = 4000, buildKeys = 100, buildOffset = 0)
    val got = rows(SqlEngine.execute("SELECT COUNT(*), COUNT(b.w), SUM(p.v) FROM p LEFT JOIN b ON p.k = b.k",
      catalog, ExecutionOptions.Default)).head
    // 20,000 probe rows a key: 100 keys of 40 matches, 10 of none.
    val matched = 100L * 20000 * 40
    val unmatched = 10L * 20000
    val probeSum = (0L until 2200000L).iterator.map(i => if (i % 110 < 100) i * 40 else i).sum
    assertEquals(Seq[Any](matched + unmatched, matched, probeSum), got)
  }

  /** 80 million joined rows read through `batches`, one partition after
    * another on this thread. */
  @Test def aJoinReadFlatStreams(): Unit = {
    assumeTrue("needs the default batch capacity", ColumnarBatch.DefaultCapacity >= 1024)
    val catalog = bigCatalog(probeRows = 2000000, probeKeys = 100, buildRows = 4000, buildKeys = 100, buildOffset = 0)
    val q = SqlEngine.execute("SELECT p.k, p.v, b.k, b.w FROM p JOIN b ON p.k = b.k", catalog, ExecutionOptions.Default)
    var count = 0L
    var sum = 0L
    try q.batches.foreach { b =>
      count += b.numRows
      val w = b.column(3).asInstanceOf[LongVector]
      var r = 0
      while (r < b.numRows) { sum += w.values(r); r += 1 }
    } finally q.close()
    assertEquals(80000000L, count)
    assertEquals(20000L * (4000L * 3999 / 2), sum)
  }

  /** A FULL join, in waves: probe keys 0 to 99, build keys 10 to 109, so
    * both sides keep unmatched rows. */
  @Test def aFullJoinInWavesStreams(): Unit = {
    assumeTrue("needs the default batch capacity", ColumnarBatch.DefaultCapacity >= 1024)
    val catalog = bigCatalog(probeRows = 2000000, probeKeys = 100, buildRows = 4000, buildKeys = 100, buildOffset = 10)
    val got = rows(SqlEngine.execute("SELECT COUNT(*), COUNT(p.v), COUNT(b.w) FROM p FULL JOIN b ON p.k = b.k",
      catalog, ExecutionOptions.Default)).head
    val matched = 90L * 20000 * 40
    val probeOnly = 10L * 20000
    val buildOnly = 10L * 40
    assertEquals(Seq[Any](matched + probeOnly + buildOnly, matched + probeOnly, matched + buildOnly), got)
  }

  /** Per shard, over exchanges of four shards, which hold both inputs: 1
    * million probe rows against 5000 build rows, 50 million joined rows. */
  @Test def aJoinPerShardStreams(): Unit = {
    assumeTrue("needs the default batch capacity", ColumnarBatch.DefaultCapacity >= 1024)
    val probe = ScanExec(new Generated(kv, 4, 250000, 100, 0))
    val build = ScanExec(new Generated(kw, 1, 5000, 100, 0))
    val j = HashJoinExec(
      ExchangeExec(probe, Seq(key(0)), 4, HashPartitioner.NullsToZero),
      ExchangeExec(build, Seq(key(0)), 4, HashPartitioner.NullsToZero),
      Seq(key(0)), Seq(key(0)), None, JoinKind.Inner)
    PhysicalPlanner.preMaterialize(j)
    assertEquals(4, j.numPartitions)
    val count = (0 until 4).map(p => countRows(j.execute(p))).sum
    assertEquals(1000000L * 50, count)
  }

  /** Grace hash, spill on: both sides bucketed to disk, then bucket by
    * bucket: 1 million probe rows against 5000 build rows, 50 million joined
    * rows, on one partition. */
  @Test def aGraceHashJoinStreams(): Unit = {
    assumeTrue("needs the default batch capacity", ColumnarBatch.DefaultCapacity >= 1024)
    val catalog = bigCatalog(probeRows = 1000000, probeKeys = 100, buildRows = 5000, buildKeys = 100, buildOffset = 0)
    val got = rows(SqlEngine.execute("SELECT COUNT(*), SUM(b.w) FROM p JOIN b ON p.k = b.k", catalog,
      ExecutionOptions(spillEnabled = true))).head
    assertEquals(Seq[Any](50000000L, 10000L * (5000L * 4999 / 2)), got)
  }

  /** `probeRows` probe rows `p` (k = i mod `probeKeys`, v = i) of four
    * partitions made as they are read, and `buildRows` build rows `b` (k = j
    * mod `buildKeys` + `buildOffset`, w = j) held in memory. */
  private def bigCatalog(probeRows: Int, probeKeys: Int, buildRows: Int, buildKeys: Int, buildOffset: Int): Catalog = {
    val catalog = new Catalog
    catalog.register("p", new Generated(kv, 4, probeRows / 4, probeKeys, 0))
    catalog.register("b", view(kw, (0 until buildRows).map(j => Array[Any](j % buildKeys + buildOffset, j.toLong)).toVector, 2))
    catalog
  }
}

object StreamingJoinTest {

  private def key(i: Int): Expr = ColRefExpr(i, "k", DataType.IntType)

  /** `rows` as a view of `partitions` partitions, in order, each in batches
    * of at most 7 rows. */
  def view(schema: Schema, rows: Vector[Array[Any]], partitions: Int): MaterializedView =
    new MaterializedView(schema, partitioned(schema, rows, partitions, gaps = false))

  /** `rows` dealt into `parts` partitions in order (the first ones one row
    * longer), each in batches of at most 7 rows; with `gaps`, an empty
    * partition before each and after the last. */
  def partitioned(schema: Schema, rows: Vector[Array[Any]], parts: Int, gaps: Boolean): IndexedSeq[IndexedSeq[ColumnarBatch]] = {
    val base = rows.size / parts
    val extra = rows.size % parts
    val sizes = (0 until parts).map(p => base + (if (p < extra) 1 else 0))
    val starts = sizes.scanLeft(0)(_ + _)
    val full = (0 until parts).map(p => rows.slice(starts(p), starts(p + 1)).grouped(7).map(batch(schema, _)).toIndexedSeq)
    if (!gaps) full else full.flatMap(p => Seq(IndexedSeq.empty, p)) :+ IndexedSeq.empty
  }

  def batch(schema: Schema, rows: Seq[Array[Any]]): ColumnarBatch = {
    val b = new ColumnarBatch(schema, math.max(1, rows.size))
    rows.zipWithIndex.foreach { case (row, r) =>
      row.indices.foreach(c => if (row(c) == null) b.column(c).setNull(r) else b.column(c).setBoxed(r, row(c)))
    }
    b.setNumRows(rows.size)
    b
  }

  def drain(it: Iterator[ColumnarBatch]): Vector[Seq[Any]] =
    Closing.draining(it)(_.flatMap(b => (0 until b.numRows).map(r => (0 until b.schema.length).map(c =>
      if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)))).toVector)

  def rows(q: ExecutedQuery): Vector[Seq[Any]] =
    try q.batches.flatMap(b => (0 until b.numRows).map(r => (0 until b.schema.length).map(c =>
      if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)))).toVector
    finally q.close()

  def sorted(rows: Seq[Seq[Any]]): Vector[String] = rows.map(_.mkString("|")).toVector.sorted

  def countRows(it: Iterator[ColumnarBatch]): Long =
    Closing.draining(it) { batches => var n = 0L; batches.foreach(n += _.numRows); n }

  /** Every partition of `plan`, the pass run first, as the engine runs one. */
  def runPlan(plan: PhysicalPlan): Vector[Seq[Any]] = {
    PhysicalPlanner.preMaterialize(plan)
    runPlanAsIs(plan)
  }

  def runPlanAsIs(plan: PhysicalPlan): Vector[Seq[Any]] =
    (0 until plan.numPartitions).flatMap(p => drain(plan.execute(p))).toVector

  /** `inner`, counting each partition's reads. */
  final class Counting(inner: CatalogView) extends CatalogView {
    private val counts = Vector.fill(inner.numPartitions)(new AtomicInteger)
    def schema: Schema = inner.schema
    def numPartitions: Int = inner.numPartitions
    override def stringsAreText: Boolean = true
    def readPartition(p: Int): Iterator[ColumnarBatch] = { counts(p).incrementAndGet(); inner.readPartition(p) }
    def reads: Vector[Int] = counts.map(_.get)
  }

  /** Three partitions of one row each, partition `failPartition` throwing
    * `message` when read. */
  final class FailingPlan(val outputSchema: Schema, failPartition: Int, message: String) extends PhysicalPlan {
    val failCalls = new AtomicInteger
    def numPartitions: Int = 3
    def execute(p: Int): Iterator[ColumnarBatch] =
      if (p == failPartition) { failCalls.incrementAndGet(); throw new IllegalStateException(message) }
      else Iterator.single(batch(outputSchema, Seq(Array[Any](p, p.toLong))))
  }

  /** `parts` partitions of `rowsPer` rows each, made a batch at a time as
    * they are read, holding nothing: row i (counted over all partitions) has
    * k = i mod `keys` + `offset` and its second column i. */
  final class Generated(val schema: Schema, parts: Int, rowsPer: Int, keys: Int, offset: Int) extends CatalogView {
    def numPartitions: Int = parts
    def readPartition(p: Int): Iterator[ColumnarBatch] = Iterator.unfold(0) { at =>
      if (at >= rowsPer) None
      else {
        val n = math.min(ColumnarBatch.DefaultCapacity, rowsPer - at)
        val b = new ColumnarBatch(schema, n)
        val k = b.column(0).asInstanceOf[IntVector]
        val v = b.column(1).asInstanceOf[LongVector]
        var r = 0
        while (r < n) {
          val i = p.toLong * rowsPer + at + r
          k.set(r, (i % keys).toInt + offset)
          v.set(r, i)
          r += 1
        }
        b.setNumRows(n)
        Some((b, at + n))
      }
    }
  }

  // ---- The matrix -----------------------------------------------------------

  /** How a matrix case's tables key their rows: each key column's type and
    * how its value is drawn, and whether the left key is computed
    * (`l.k0 + 1 = r.k0`). */
  final case class KeyPath(name: String, types: Vector[DataType], draws: Vector[Random => Any], computed: Boolean = false) {
    override def toString: String = name
  }

  val KeyPaths: Vector[KeyPath] = Vector(
    KeyPath("one long key", Vector(DataType.IntType), Vector(r => r.nextInt(8))),
    KeyPath("packed keys", Vector(DataType.IntType, DataType.IntType), Vector(r => r.nextInt(4), r => r.nextInt(3))),
    KeyPath("one string key", Vector(DataType.StringType), Vector(r => ('a' + r.nextInt(8)).toChar.toString)),
    KeyPath("object keys", Vector(DataType.StringType, DataType.IntType),
      Vector(r => ('a' + r.nextInt(4)).toChar.toString, r => r.nextInt(3))),
    KeyPath("a computed key", Vector(DataType.IntType), Vector(r => r.nextInt(8)), computed = true))

  val Shapes: Vector[String] = Vector("rows", "an empty build side", "an empty probe side", "a probe of no partitions",
    "empty partitions")

  val Kinds: Vector[JoinKind] = Vector(JoinKind.Inner, JoinKind.Left, JoinKind.Right, JoinKind.Full)

  /** "as built": the broadcast or in-waves path its kind and build side
    * give; then per shard, grace hash and grace hash per shard. */
  val Paths: Vector[String] = Vector("as built", "per shard", "grace hash", "grace hash per shard")

  final case class MatrixCase(keys: KeyPath, parts: Int, shape: String, withExtra: Boolean, kind: JoinKind,
      buildRight: Boolean) {
    override def toString: String =
      s"$kind join, building the ${if (buildRight) "right" else "left"} side, on $keys" +
        s"${if (withExtra) " and l.v < r.v" else ""}, $shape, $parts partitions"

    private val nKeys = keys.types.length
    private def schemaOf(side: String) = Schema(
      keys.types.zipWithIndex.map { case (t, i) => Field(s"${side}k$i", t) } ++
        Vector(Field(s"${side}v", DataType.IntType), Field(s"${side}tag", DataType.StringType)))
    val leftSchema: Schema = schemaOf("l")
    val rightSchema: Schema = schemaOf("r")
    private val width = nKeys + 2

    private val rng = new Random((keys.name, parts, shape, withExtra, kind.toString, buildRight).hashCode.toLong)
    private def table(n: Int, side: String): Vector[Array[Any]] = Vector.tabulate(n) { i =>
      val ks = keys.draws.map(d => if (rng.nextInt(8) == 0) null else d(rng))
      (ks :+ (if (rng.nextInt(8) == 0) null else rng.nextInt(20)) :+ s"$side$i").toArray[Any]
    }
    private val buildEmpty = shape == "an empty build side"
    private val probeEmpty = shape == "an empty probe side" || shape == "a probe of no partitions"
    private def empty(left: Boolean) = if (left == buildRight) probeEmpty else buildEmpty
    val leftRows: Vector[Array[Any]] = if (empty(left = true)) Vector.empty else table(40, "l")
    val rightRows: Vector[Array[Any]] = if (empty(left = false)) Vector.empty else table(30, "r")
    private val gaps = shape == "empty partitions"
    private def partsOf(left: Boolean, schema: Schema, rows: Vector[Array[Any]]) =
      if (shape == "a probe of no partitions" && left == buildRight) IndexedSeq.empty[IndexedSeq[ColumnarBatch]]
      else partitioned(schema, rows, parts, gaps)
    val leftParts: IndexedSeq[IndexedSeq[ColumnarBatch]] = partsOf(left = true, leftSchema, leftRows)
    val rightParts: IndexedSeq[IndexedSeq[ColumnarBatch]] = partsOf(left = false, rightSchema, rightRows)

    private def keyOf(row: Array[Any], left: Boolean): Option[Seq[Any]] = {
      val ks = (0 until nKeys).map(row(_))
      if (ks.contains(null)) None
      else if (keys.computed && left) Some(Seq(ks.head.asInstanceOf[Int] + 1))
      else Some(ks)
    }
    private def matches(l: Array[Any], r: Array[Any]): Boolean =
      keyOf(l, left = true).exists(k => keyOf(r, left = false).contains(k)) &&
        (!withExtra || (l(nKeys) != null && r(nKeys) != null && l(nKeys).asInstanceOf[Int] < r(nKeys).asInstanceOf[Int]))
    private def nulls = Seq.fill[Any](width)(null)

    /** The nested loop's rows. */
    def expected: Vector[Seq[Any]] = {
      val inner = for (l <- leftRows; r <- rightRows if matches(l, r)) yield l.toSeq ++ r.toSeq
      val leftOnly = for (l <- leftRows if !rightRows.exists(matches(l, _))) yield l.toSeq ++ nulls
      val rightOnly = for (r <- rightRows if !leftRows.exists(matches(_, r))) yield nulls ++ r.toSeq
      kind match {
        case JoinKind.Inner => inner
        case JoinKind.Left => inner ++ leftOnly
        case JoinKind.Right => inner ++ rightOnly
        case JoinKind.Full => inner ++ leftOnly ++ rightOnly
      }
    }

    private def keepsUnmatchedBuildRows: Boolean =
      if (buildRight) kind == JoinKind.Right || kind == JoinKind.Full
      else kind == JoinKind.Left || kind == JoinKind.Full

    /** Whether the join as built is a broadcast one, in its probe side's
      * partitions. */
    def broadcast: Boolean = !keepsUnmatchedBuildRows

    /** The nested loop's rows in the probe side's order: each probe row's
      * matches in build order, or its null-padded row if the join keeps it. */
    def inProbeOrder: Vector[Seq[Any]] = {
      val (probe, build) = if (buildRight) (leftRows, rightRows) else (rightRows, leftRows)
      val keepsProbe =
        if (buildRight) kind == JoinKind.Left || kind == JoinKind.Full
        else kind == JoinKind.Right || kind == JoinKind.Full
      probe.flatMap { p =>
        val found = build.filter(b => if (buildRight) matches(p, b) else matches(b, p))
          .map(b => if (buildRight) p.toSeq ++ b.toSeq else b.toSeq ++ p.toSeq)
        if (found.isEmpty && keepsProbe) Vector(if (buildRight) p.toSeq ++ nulls else nulls ++ p.toSeq) else found
      }
    }

    def expectedPartitions(path: String): Int = path match {
      case "as built" =>
        if (broadcast) (if (buildRight) leftParts else rightParts).size else 1
      case "per shard" | "grace hash per shard" => 4
      case _ => 1
    }

    private def col(i: Int, name: String, t: DataType) = ColRefExpr(i, name, t)
    private val leftKeys: Seq[Expr] = {
      val ks = keys.types.indices.map(i => col(i, s"lk$i", keys.types(i)))
      if (keys.computed) Seq(BinOpExpr("+", ks.head, LitExpr(1, DataType.IntType), DataType.IntType)) else ks
    }
    private val rightKeys: Seq[Expr] = keys.types.indices.map(i => col(i, s"rk$i", keys.types(i)))
    private val extra: Option[Expr] =
      if (!withExtra) None
      else Some(BinOpExpr("<", col(nKeys, "lv", DataType.IntType), col(width + nKeys, "rv", DataType.IntType),
        DataType.BooleanType))

    /** The ON condition as SQL. */
    def sqlCondition: String = {
      val eqs = keys.types.indices.map(i => if (keys.computed) s"l.lk$i + 1 = r.rk$i" else s"l.lk$i = r.rk$i")
      (eqs ++ (if (withExtra) Seq("l.lv < r.rv") else Nil)).mkString(" AND ")
    }

    def join(path: String): HashJoinExec = {
      val l = ScanExec(new MaterializedView(leftSchema, leftParts))
      val r = ScanExec(new MaterializedView(rightSchema, rightParts))
      def exchanged(p: PhysicalPlan, ks: Seq[Expr]) = ExchangeExec(p, ks, 4, HashPartitioner.NullsToZero)
      val spill = ExecutionOptions(spillEnabled = true)
      path match {
        case "as built" => HashJoinExec(l, r, leftKeys, rightKeys, extra, kind, buildRight)
        case "per shard" =>
          HashJoinExec(exchanged(l, leftKeys), exchanged(r, rightKeys), leftKeys, rightKeys, extra, kind, buildRight)
        case "grace hash" => HashJoinExec(l, r, leftKeys, rightKeys, extra, kind, buildRight, spill)
        case "grace hash per shard" =>
          HashJoinExec(exchanged(l, leftKeys), exchanged(r, rightKeys), leftKeys, rightKeys, extra, kind, buildRight, spill)
      }
    }
  }
}
