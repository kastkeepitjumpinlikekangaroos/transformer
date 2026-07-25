package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.Test

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.util.Random

/** Direct [[ExchangeExec]] coverage. The exchange's job is to hash-partition
  * its input into K key-disjoint shards; tests assert the three things every
  * downstream operator relies on:
  *
  *   1. **Permutation** — total rows in equals total rows out (no drops,
  *      no duplicates). The exchange must not lose or invent rows.
  *   2. **Co-location** — every row with the same key value lands in the
  *      same shard, across child-partition boundaries and across repeated
  *      `execute(s)` calls.
  *   3. **NULL routing** — NULL-key rows route deterministically to the
  *      bucket selected by the configured [[HashPartitioner.NullPolicy]],
  *      and only to that bucket.
  *
  * No correctness claim is made about the specific shard *number* a key
  * lands in — that's an implementation detail of [[HashPartitioner]]'s
  * pinned hash. Tests assert the equivalence-class structure instead.
  */
class ExchangeExecTest {

  // ---- Single-column Int key ------------------------------------------------

  private val intSchema = Schema(Vector(
    Field("k", DataType.IntType),
    Field("v", DataType.IntType)))
  private val intKey: Expr = ColRefExpr(0, "k", DataType.IntType)

  @Test def passesThroughRowsAsAPermutation(): Unit = {
    val parts = Vector(
      Vector(row(1, 100), row(2, 200), row(3, 300)),
      Vector(row(4, 400), row(5, 500), row(6, 600)))
    val out = runExchange(parts, intSchema, Seq(intKey), numShards = 4)
    assertPermutationOf("permutation", parts, out.flatten)
  }

  @Test def collocatesEqualKeysInOneShard(): Unit = {
    // Repeat 7 distinct keys many times across input partitions; each key
    // must end up in exactly one shard.
    val rnd = new Random(42L)
    val keys = Vector(11, 22, 33, 44, 55, 66, 77)
    val perPart = 200
    val parts = Vector.tabulate(4) { _ =>
      Vector.tabulate(perPart) { _ =>
        val k = keys(rnd.nextInt(keys.length))
        row(k, rnd.nextInt(1_000))
      }
    }
    val shards = runExchange(parts, intSchema, Seq(intKey), numShards = 8)
    assertPermutationOf("collocate Int", parts, shards.flatten)
    assertKeyCollocated("collocate Int", shards, keyExtractor = _(0))
  }

  @Test def emptyInputProducesEmptyShards(): Unit = {
    val parts = Vector.fill(3)(Vector.empty[Array[Any]])
    val shards = runExchange(parts, intSchema, Seq(intKey), numShards = 5)
    assertEquals(5, shards.length)
    assertTrue("all shards empty", shards.forall(_.isEmpty))
  }

  @Test def singleShardCollapsesAllRows(): Unit = {
    val parts = Vector(
      Vector(row(1, 1), row(2, 2)),
      Vector(row(3, 3), row(4, 4), row(5, 5)))
    val shards = runExchange(parts, intSchema, Seq(intKey), numShards = 1)
    assertEquals(1, shards.length)
    assertEquals(5, shards(0).length)
    assertPermutationOf("single shard", parts, shards.flatten)
  }

  @Test def moreShardsThanDistinctKeysLeavesSomeEmpty(): Unit = {
    // Only 3 distinct keys, K = 16. Most shards must be empty; the 3
    // populated shards must hold all rows for their key (no spread).
    val parts = Vector(
      Vector.tabulate(30)(i => row(1, i)),
      Vector.tabulate(30)(i => row(2, i)),
      Vector.tabulate(30)(i => row(3, i)))
    val shards = runExchange(parts, intSchema, Seq(intKey), numShards = 16)
    assertEquals(90, shards.flatten.length)
    val populated = shards.count(_.nonEmpty)
    assertTrue(s"expected ≤ 3 populated shards, got $populated", populated <= 3)
    assertKeyCollocated("low cardinality", shards, _(0))
  }

  // ---- Multi-column key -----------------------------------------------------

  private val pairSchema = Schema(Vector(
    Field("a", DataType.IntType),
    Field("b", DataType.IntType),
    Field("v", DataType.LongType)))
  private val aKey: Expr = ColRefExpr(0, "a", DataType.IntType)
  private val bKey: Expr = ColRefExpr(1, "b", DataType.IntType)

  @Test def multiColumnKeyCoLocates(): Unit = {
    val parts = Vector(
      Vector(row(1, 10, 1L), row(2, 20, 2L), row(1, 10, 3L)),
      Vector(row(3, 30, 4L), row(1, 10, 5L), row(2, 20, 6L)),
      Vector(row(3, 30, 7L), row(2, 20, 8L), row(1, 10, 9L)))
    val shards = runExchange(parts, pairSchema, Seq(aKey, bKey), numShards = 4)
    assertPermutationOf("multi col", parts, shards.flatten)
    // (a, b) tuple is the equivalence key.
    assertKeyCollocated("multi col", shards, r => (r(0), r(1)))
  }

  // ---- String key (ObjectArrayCodec-style routing) --------------------------

  private val strSchema = Schema(Vector(
    Field("k", DataType.StringType),
    Field("v", DataType.IntType)))
  private val strKey: Expr = ColRefExpr(0, "k", DataType.StringType)

  @Test def stringKeyCoLocates(): Unit = {
    val parts = Vector(
      Vector(row("alpha", 1), row("beta", 2), row("gamma", 3), row("alpha", 4)),
      Vector(row("delta", 5), row("alpha", 6), row("beta", 7)),
      Vector(row("gamma", 8), row("epsilon", 9), row("alpha", 10)))
    val shards = runExchange(parts, strSchema, Seq(strKey), numShards = 8)
    assertPermutationOf("string", parts, shards.flatten)
    assertKeyCollocated("string", shards, _(0))
  }

  // ---- NULL routing ---------------------------------------------------------

  @Test def nullKeysRouteToLastShardUnderNullsToLast(): Unit = {
    val K = 6
    val parts = Vector(
      Vector(row(null, 1), row(1, 2), row(null, 3), row(2, 4)),
      Vector(row(3, 5), row(null, 6), row(null, 7)))
    val shards = runExchange(parts, intSchema, Seq(intKey),
      numShards = K, nullPolicy = HashPartitioner.NullsToLast)
    // All NULL-key rows must be in shard K-1; non-NULL rows must be in
    // shards [0, K-1).
    val nullRowsInLast = shards(K - 1).count(_(0) == null)
    val nullRowsElsewhere = (0 until K - 1).map(s => shards(s).count(_(0) == null)).sum
    assertEquals("NULL rows in last shard", 4, nullRowsInLast)
    assertEquals("no NULL rows outside last shard", 0, nullRowsElsewhere)
    assertPermutationOf("nulls-to-last permutation", parts, shards.flatten)
  }

  @Test def nullKeysRouteToFirstShardUnderNullsToZero(): Unit = {
    val K = 6
    val parts = Vector(
      Vector(row(null, 1), row(1, 2), row(null, 3), row(2, 4)),
      Vector(row(3, 5), row(null, 6), row(null, 7)))
    val shards = runExchange(parts, intSchema, Seq(intKey),
      numShards = K, nullPolicy = HashPartitioner.NullsToZero)
    val nullRowsInFirst = shards(0).count(_(0) == null)
    val nullRowsElsewhere = (1 until K).map(s => shards(s).count(_(0) == null)).sum
    assertEquals("NULL rows in first shard", 4, nullRowsInFirst)
    assertEquals("no NULL rows outside first shard", 0, nullRowsElsewhere)
    assertPermutationOf("nulls-to-zero permutation", parts, shards.flatten)
  }

  @Test def multiColumnNullRoutesNullsToConfiguredBucket(): Unit = {
    val K = 4
    // Rows where ONE of two key columns is NULL still count as a NULL key.
    val parts = Vector(Vector(
      row(1, 10, 1L),
      row(null, 10, 2L),    // a=NULL
      row(1, null, 3L),     // b=NULL
      row(null, null, 4L),  // both NULL
      row(2, 20, 5L)))
    val shards = runExchange(parts, pairSchema, Seq(aKey, bKey),
      numShards = K, nullPolicy = HashPartitioner.NullsToLast)
    val anyNullRows = (r: Array[Any]) => r(0) == null || r(1) == null
    val nullRowsInLast = shards(K - 1).count(anyNullRows)
    val nullRowsElsewhere = (0 until K - 1).map(s => shards(s).count(anyNullRows)).sum
    assertEquals("any-NULL rows in last shard", 3, nullRowsInLast)
    assertEquals("no any-NULL rows outside last shard", 0, nullRowsElsewhere)
  }

  // ---- Schema preservation --------------------------------------------------

  @Test def outputSchemaMatchesChild(): Unit = {
    val plan = new InMemoryPartitionedPlan(pairSchema, Vector(Vector(row(1, 2, 3L))))
    val ex = ExchangeExec(plan, Seq(aKey), numShards = 4)
    assertEquals(pairSchema, ex.outputSchema)
    assertEquals(4, ex.numPartitions)
  }

  // ---- Lazy materialization & determinism ----------------------------------

  @Test def repeatedExecuteCallsAreDeterministicAndReproducible(): Unit = {
    // Call execute(s) twice for the same s; same rows, same order.
    // Also re-create the ExchangeExec and verify the second instance routes
    // identically (the partitioner is pinned).
    val parts = Vector(
      Vector(row(7, 1), row(13, 2), row(7, 3), row(42, 4)),
      Vector(row(13, 5), row(7, 6), row(42, 7), row(13, 8)))
    val plan = new InMemoryPartitionedPlan(intSchema, parts)
    val ex1 = ExchangeExec(plan, Seq(intKey), numShards = 4)

    val first = (0 until 4).map(s => toComparable(drainRows(ex1.execute(s), intSchema))).toVector
    val second = (0 until 4).map(s => toComparable(drainRows(ex1.execute(s), intSchema))).toVector
    assertEquals("same instance, repeated execute", first, second)

    // Independent instance over the same input — must produce same shard
    // contents. Build a fresh plan because `InMemoryPartitionedPlan` is
    // consumed by the first ExchangeExec's materialization.
    val plan2 = new InMemoryPartitionedPlan(intSchema, parts)
    val ex2 = ExchangeExec(plan2, Seq(intKey), numShards = 4)
    val third = (0 until 4).map(s => toComparable(drainRows(ex2.execute(s), intSchema))).toVector
    assertEquals("fresh instance, same routing", first, third)
  }

  /** Convert a Vector[Array[Any]] into a Vector[Vector[Any]] so equality
    * compares by structure rather than array-reference identity. */
  private def toComparable(rows: Vector[Array[Any]]): Vector[Vector[Any]] =
    rows.map(_.toVector)

  @Test def materializesOnlyOnceAcrossConcurrentShardReaders(): Unit = {
    // Many threads calling execute(s) for different s in parallel must
    // share one materialization — the lazy init holds.
    val parts = Vector.tabulate(4) { p =>
      Vector.tabulate(500)(i => row(i % 7, p * 1000 + i))
    }
    val plan = new InMemoryPartitionedPlan(intSchema, parts)
    val K = 8
    val ex = ExchangeExec(plan, Seq(intKey), numShards = K)

    val tasks: Seq[java.util.concurrent.Callable[Vector[Array[Any]]]] = (0 until K).map { s =>
      new java.util.concurrent.Callable[Vector[Array[Any]]] {
        def call(): Vector[Array[Any]] = drainRows(ex.execute(s), intSchema)
      }
    }
    val drained = Scheduler.submitAndAwaitAll(tasks).toVector
    assertEquals(K, drained.length)
    val totalIn = parts.map(_.length).sum
    val totalOut = drained.map(_.length).sum
    assertEquals("concurrent execute preserves total row count", totalIn, totalOut)
    assertKeyCollocated("concurrent execute", drained, _(0))
  }

  @Test(timeout = 20000) def materializesExactlyOnceUnderConcurrentCallers(): Unit = {
    // N >> K external threads race execute(s) on ONE exchange. The CAS claim
    // must admit exactly one materializer, so the child's execute(p) runs
    // child.numPartitions times total — never N x that — and every racer of
    // the same shard sees identical rows.
    val parts = Vector.tabulate(4) { p =>
      Vector.tabulate(250)(i => row(i % 11, p * 1000 + i))
    }
    val counting = new CountingPlan(new InMemoryPartitionedPlan(intSchema, parts))
    val K = 4
    val N = 32
    val ex = ExchangeExec(counting, Seq(intKey), numShards = K)

    val start = new CountDownLatch(1)
    val results = new Array[Vector[Vector[Any]]](N)
    val failures = new ConcurrentLinkedQueue[Throwable]
    val threads = (0 until N).map { i =>
      val t = new Thread(() => {
        try {
          start.await()
          results(i) = toComparable(drainRows(ex.execute(i % K), intSchema))
        } catch { case t: Throwable => failures.add(t) }
      })
      t.start()
      t
    }
    start.countDown()
    threads.foreach(_.join())

    assertTrue(s"racing callers failed: $failures", failures.isEmpty)
    assertEquals("child.execute(p) calls (materialize ran exactly once)",
      counting.numPartitions, counting.executeCalls.get)
    (0 until N).foreach { i =>
      assertEquals(s"caller $i disagrees with another reader of shard ${i % K}",
        results(i % K), results(i))
    }
    assertPermutationOf("racing callers reassemble the input", parts,
      (0 until K).flatMap(s => results(s)).toVector.map(_.toArray))
  }

  @Test(timeout = 20000) def materializationFailurePropagatesToEveryConcurrentCaller(): Unit = {
    // A throwing child fails the single materialize(). The winner AND every
    // latch-waiting loser must observe the wrapped failure with the original
    // cause preserved somewhere in the chain, and the failed materialization
    // is never retried: the failing partition executes exactly once.
    val marker = "synthetic exchange child failure"
    val child = new ThrowingPlan(intSchema, nParts = 3, failPartition = 1, marker)
    val K = 4
    val N = 32
    val ex = ExchangeExec(child, Seq(intKey), numShards = K)

    val start = new CountDownLatch(1)
    val observed = new ConcurrentLinkedQueue[Throwable]
    val unexpected = new ConcurrentLinkedQueue[String]
    val threads = (0 until N).map { i =>
      val t = new Thread(() => {
        try {
          start.await()
          ex.execute(i % K)
          unexpected.add(s"caller $i succeeded")
        } catch { case t: Throwable => observed.add(t) }
      })
      t.start()
      t
    }
    start.countDown()
    threads.foreach(_.join())

    assertTrue(s"callers unexpectedly succeeded: $unexpected", unexpected.isEmpty)
    assertEquals("every caller observed the failure", N, observed.size)
    observed.forEach { t =>
      assertTrue(s"wrong exception shape: $t", t.isInstanceOf[RuntimeException]
        && t.getMessage == "ExchangeExec materialization failed")
      var cause = t.getCause
      var found = false
      while (cause != null && !found) {
        if (marker == cause.getMessage) found = true
        cause = cause.getCause
      }
      assertTrue(s"original cause lost from chain: $t", found)
    }
    assertEquals("failing partition executed exactly once (no retry)",
      1, child.failCalls.get)
    // A caller arriving after the failure gets the same wrapped failure.
    try {
      ex.execute(0)
      fail("expected materialization failure to persist")
    } catch {
      case t: RuntimeException =>
        assertEquals("ExchangeExec materialization failed", t.getMessage)
    }
  }

  // ---- preMaterializeExchanges (the 02f engine pass) ------------------------

  @Test def preMaterializePassPublishesADiamondSharedExchangeOnce(): Unit = {
    // CTE inlining shares plan subtrees, so one ExchangeExec instance can have
    // two parents. The pass must walk by identity: one materialization, both
    // parents read the published shards.
    val parts = Vector.tabulate(3) { p =>
      Vector.tabulate(40)(i => row(i % 5, p * 100 + i))
    }
    val counting = new CountingPlan(new InMemoryPartitionedPlan(intSchema, parts))
    val ex = ExchangeExec(counting, Seq(intKey), numShards = 4)
    val diamond = UnionExec(ex, ex)

    PhysicalPlanner.preMaterializeExchanges(diamond)
    assertEquals("child drained exactly once by the pass",
      counting.numPartitions, counting.executeCalls.get)

    val drained = (0 until diamond.numPartitions)
      .flatMap(p => drainRows(diamond.execute(p), intSchema)).toVector
    assertEquals("each row appears twice through the two parents",
      2 * parts.map(_.length).sum, drained.length)
    assertEquals("no further child executions on consumption",
      counting.numPartitions, counting.executeCalls.get)
  }

  @Test def preMaterializePassSeesThroughMeteredPlanWrappers(): Unit = {
    // Metrics-enabled plans wrap every operator in MeteredPlan; the pass must
    // still find and publish the exchange underneath before consumers run.
    val parts = Vector.tabulate(2) { p =>
      Vector.tabulate(30)(i => row(i % 3, p * 100 + i))
    }
    val counting = new CountingPlan(new InMemoryPartitionedPlan(intSchema, parts))
    val ex = ExchangeExec(counting, Seq(intKey), numShards = 4)
    val (wrapped, _) = PhysicalPlanner.wrapWithMetrics(ex)

    PhysicalPlanner.preMaterializeExchanges(wrapped)
    assertEquals("exchange under MeteredPlan published by the pass",
      counting.numPartitions, counting.executeCalls.get)

    val total = (0 until wrapped.numPartitions)
      .map(s => drainRows(wrapped.execute(s), intSchema).length).sum
    assertEquals("metered consumption reads the published shards",
      parts.map(_.length).sum, total)
  }

  @Test def invalidPartitionIndexThrows(): Unit = {
    val plan = new InMemoryPartitionedPlan(intSchema, Vector(Vector(row(1, 1))))
    val ex = ExchangeExec(plan, Seq(intKey), numShards = 4)
    try {
      ex.execute(4)
      fail("expected IAE for out-of-range partition")
    } catch { case _: IllegalArgumentException => () }
    try {
      ex.execute(-1)
      fail("expected IAE for negative partition")
    } catch { case _: IllegalArgumentException => () }
  }

  @Test def zeroShardsRejectedAtConstruction(): Unit = {
    val plan = new InMemoryPartitionedPlan(intSchema, Vector(Vector(row(1, 1))))
    try {
      ExchangeExec(plan, Seq(intKey), numShards = 0)
      fail("expected IAE for zero shards")
    } catch { case _: IllegalArgumentException => () }
  }

  @Test def distributesRandomKeysAcrossShards(): Unit = {
    // Statistical sanity: 4096 random Int keys across K=8 shards. The
    // distribution shouldn't be perfectly uniform, but every shard should
    // hold at least one row. Catches a partitioner whose modulo collapses
    // to a single shard (the historical `Int.hashCode % K` regression on
    // contiguous small keys, which `fmix32` is meant to fix).
    val rnd = new Random(0xCAFEBABEL)
    val parts = Vector.tabulate(4)(_ => Vector.tabulate(1024)(_ => row(rnd.nextInt(), 0)))
    val K = 8
    val shards = runExchange(parts, intSchema, Seq(intKey), numShards = K)
    val sizes = shards.map(_.length)
    assertEquals("total rows", 4096, sizes.sum)
    // Every shard should be hit; if one is empty, the partitioner is
    // pathologically uneven.
    sizes.zipWithIndex.foreach { case (s, i) =>
      assertTrue(s"shard $i must be non-empty under uniform random keys (got $s)", s > 0)
    }
  }

  // ---- Helpers --------------------------------------------------------------

  private def runExchange(
      partitions: Vector[Vector[Array[Any]]],
      schema: Schema,
      partitionBy: Seq[Expr],
      numShards: Int,
      nullPolicy: HashPartitioner.NullPolicy = HashPartitioner.NullsToLast
  ): Vector[Vector[Array[Any]]] = {
    val plan = new InMemoryPartitionedPlan(schema, partitions)
    val ex = ExchangeExec(plan, partitionBy, numShards, nullPolicy)
    (0 until numShards).map(s => drainRows(ex.execute(s), schema)).toVector
  }

  private def drainRows(it: Iterator[ColumnarBatch], schema: Schema): Vector[Array[Any]] = {
    val buf = mutable.ArrayBuffer.empty[Array[Any]]
    while (it.hasNext) {
      val b = it.next()
      var r = 0
      while (r < b.numRows) {
        val arr = new Array[Any](schema.length)
        var c = 0
        while (c < schema.length) {
          arr(c) = if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)
          c += 1
        }
        buf += arr
        r += 1
      }
    }
    buf.toVector
  }

  /** Assert that for every key value, all rows with that key live in exactly
    * one shard. The exchange's central contract: matching keys collocate. */
  private def assertKeyCollocated[K](
      label: String,
      shards: Vector[Vector[Array[Any]]],
      keyExtractor: Array[Any] => K): Unit = {
    val keyToShards = mutable.Map.empty[K, mutable.Set[Int]]
    shards.zipWithIndex.foreach { case (rows, s) =>
      rows.foreach { r =>
        keyToShards.getOrElseUpdate(keyExtractor(r), mutable.Set.empty[Int]).add(s)
      }
    }
    keyToShards.foreach { case (k, ss) =>
      assertEquals(s"$label: key $k spans shards $ss (expected exactly 1)", 1, ss.size)
    }
  }

  /** Assert two batches of rows are identical as multisets — order within and
    * across rows doesn't matter, but every row in one must appear with the
    * same multiplicity in the other. */
  private def assertPermutationOf(
      label: String,
      expected: Vector[Vector[Array[Any]]],
      actual: Vector[Array[Any]]): Unit = {
    val expectedMs = expected.flatten.map(_.toVector).groupBy(identity).view.mapValues(_.size).toMap
    val actualMs = actual.map(_.toVector).groupBy(identity).view.mapValues(_.size).toMap
    assertEquals(s"$label: row multiset", expectedMs, actualMs)
  }

  private def row(values: Any*): Array[Any] = values.toArray
}

/** Wraps a plan and counts `execute(p)` calls, so tests can assert the child
  * was drained exactly `numPartitions` times under racing exchange readers. */
private final class CountingPlan(inner: PhysicalPlan) extends PhysicalPlan {
  val executeCalls = new AtomicInteger(0)
  def outputSchema: Schema = inner.outputSchema
  def numPartitions: Int = inner.numPartitions
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    executeCalls.incrementAndGet()
    inner.execute(partition)
  }
}

/** Plan whose partition `failPartition` always throws `marker`; the other
  * partitions yield no batches. Counts attempts on the failing partition so
  * tests can assert a failed materialization is never retried. */
private final class ThrowingPlan(
    schema: Schema,
    nParts: Int,
    failPartition: Int,
    marker: String) extends PhysicalPlan {
  val failCalls = new AtomicInteger(0)
  def outputSchema: Schema = schema
  def numPartitions: Int = nParts
  def execute(partition: Int): Iterator[ColumnarBatch] =
    if (partition == failPartition) {
      failCalls.incrementAndGet()
      throw new RuntimeException(marker)
    } else Iterator.empty
}

/** Hand-rolled [[PhysicalPlan]] that hands back pre-supplied rows partitioned
  * as given. Mirrors the helper in [[SortExecTest]] — kept separate so the
  * two tests stay independent and the helper's behavior never silently
  * changes under another suite. */
private final class InMemoryPartitionedPlan(
    schema: Schema,
    partitions: Vector[Vector[Array[Any]]]) extends PhysicalPlan {
  def outputSchema: Schema = schema
  def numPartitions: Int = partitions.length
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    val rows = partitions(partition)
    val cap = ColumnarBatch.DefaultCapacity
    rows.grouped(cap).map { chunk =>
      val b = new ColumnarBatch(schema, math.max(1, chunk.length))
      var r = 0
      while (r < chunk.length) {
        val row = chunk(r)
        var c = 0
        while (c < schema.length) {
          if (row(c) == null) b.column(c).setNull(r) else b.column(c).setBoxed(r, row(c))
          c += 1
        }
        r += 1
      }
      b.setNumRows(chunk.length)
      b
    }
  }
}
