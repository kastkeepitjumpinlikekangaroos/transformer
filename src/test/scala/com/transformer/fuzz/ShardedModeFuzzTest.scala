package com.transformer.fuzz

import com.transformer.core._
import com.transformer.fuzz.MetaQueryGen._
import com.transformer.fuzz.oracle.{AggDecomposition, JoinCommutativity, MetaModeDifferential, NoRec, RelEngine, Tlp}
import com.transformer.sql.exec.PhysicalPlanner
import com.transformer.sql.plan.{LogicalBuilder, LogicalOptimizer, LogicalPlanCardinality}
import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}

/** The metamorphic gate re-run under SHARDED planning — a separate JVM forced
  * onto the shuffle-join + sharded-aggregate/distinct paths the default gate
  * never reaches.
  *
  * The two sharding levers,
  * [[LogicalPlanCardinality.MinShardableSize]] (aggregate/distinct exchange) and
  * [[LogicalPlanCardinality.BroadcastBuildThreshold]] (broadcast-vs-shuffle
  * join), are `val`s read from system properties at class load, so they cannot
  * be toggled in-JVM. This target sets both to `1` via `jvm_flags` (see the
  * `BUILD.bazel` `sharded_mode_fuzz_test`), which drops every generated query
  * onto the sharded path: aggregates and DISTINCT get an `ExchangeExec`, and
  * hash joins shuffle both sides instead of broadcasting the build side.
  * [[shardingGateIsActive]] asserts the flags actually arrived, so a misconfigured
  * target fails loudly instead of silently re-running the default gate.
  *
  * The target also pins `transformer.scheduler.shard_count=4`, so every breaker
  * fans out to four shards and the fuzzer exercises real nested multi-shard plans
  * (a breaker over an exchange over a breaker over an exchange ...). Sharded
  * execution at K>1 was long feared to deadlock the shared `Scheduler.pool` (deep
  * plans park every worker awaiting sub-tasks); plans/bugfixes/02a refuted that on
  * JDK 21 — `ForkJoinTask.get()` work-helps, so an awaiting worker steals and runs
  * the very sub-tasks it awaits, materialising the nested exchange tree on one
  * worker's stack rather than starving the pool. (A `ForkJoinPool.managedBlock`
  * compensation variant was tried in 02b and reverted — under deep K-shard nesting
  * it spawned a spare-thread storm that wedged the pool; see docs/gotchas.md.)
  * A fixed K rather than the default (`= Scheduler.parallelism`) keeps behaviour
  * deterministic across machines with different core counts. Operator-level
  * multi-shard routing is covered by `exchange_exec_test`.
  *
  * The properties are the same generators + oracles + shrinker as
  * [[MetamorphicFuzzTest]]; the scope here is narrow: do the metamorphic
  * relations ([[Tlp]], [[NoRec]], [[JoinCommutativity]], [[AggDecomposition]])
  * and in-JVM mode agreement ([[MetaModeDifferential]]) still hold when the
  * planner shards? Bind-reject rate and shape coverage are planner-independent
  * and stay in [[MetamorphicFuzzTest]]; they are not duplicated here.
  *
  * Budgets are small (each case runs the engine several times); scale for a
  * campaign with `-Dfuzz.seeds=N` / `FUZZ_SEEDS=N` against
  * `sharded_mode_fuzz_campaign`. A failure prints the seed and the minimized
  * counterexample exactly as [[MetamorphicFuzzTest]] does.
  */
class ShardedModeFuzzTest {

  /** Default per-property budget — small, as each case executes the engine
    * several times across modes/partitions; raise via `-Dfuzz.seeds=N`. */
  private val DefaultShardedSeeds: Int = 60

  // Isolate spill files in a temp dir (the spill-on mode flushes at a 1-byte
  // threshold) so the default spill location is never littered.
  private val originalDirProp: String = System.getProperty(Spill.SpillDirProperty)
  private var tmpRoot: Path = _

  @Before def setSpillDir(): Unit = {
    tmpRoot = Files.createTempDirectory("sharded-spill-")
    System.setProperty(Spill.SpillDirProperty, tmpRoot.resolve("transformer-spill").toString)
  }

  @After def restoreSpillDir(): Unit = {
    if (originalDirProp == null) System.clearProperty(Spill.SpillDirProperty)
    else System.setProperty(Spill.SpillDirProperty, originalDirProp)
    deleteRecursively(tmpRoot)
  }

  private def deleteRecursively(p: Path): Unit = {
    if (p == null || !Files.exists(p)) return
    if (Files.isDirectory(p)) {
      val s = Files.list(p)
      try { val it = s.iterator(); while (it.hasNext) deleteRecursively(it.next()) }
      finally s.close()
    }
    try Files.deleteIfExists(p) catch { case _: java.io.IOException => () }
  }

  // ---- gate -----------------------------------------------------------------

  /** Proves the `jvm_flags` reached [[LogicalPlanCardinality]] at class load, so
    * the properties below genuinely exercise the sharded path rather than
    * silently re-running the default gate (where `MinShardableSize` is
    * `Long.MaxValue` and `BroadcastBuildThreshold` is 1M). */
  @Test def shardingGateIsActive(): Unit = {
    assertEquals(
      "jvm_flags shard_min_size=1 must reach LogicalPlanCardinality at class load",
      1L, LogicalPlanCardinality.MinShardableSize)
    assertEquals(
      "jvm_flags broadcast_threshold=1 must reach LogicalPlanCardinality at class load",
      1L, LogicalPlanCardinality.BroadcastBuildThreshold)
  }

  /** A GROUP BY or PARTITION BY key that reads RAND() is read once per row
    * here too: an exchange read it to route the row and the grouping read it
    * again in the shard, so `GROUP BY RAND() > 0.5` made four groups (TRUE and
    * FALSE in two shards each) and `PARTITION BY FLOOR(RAND() * 3)` nine
    * partitions. Such a key is planned without the exchange. */
  @Test def aKeyThatReadsRandIsReadOncePerRow(): Unit = {
    val schema = Schema(Field("id", DataType.IntType))
    val rowsPerPartition = 500
    val view = new MaterializedView(schema, (0 until 4).map { p =>
      val b = new ColumnarBatch(schema, rowsPerPartition)
      (0 until rowsPerPartition).foreach(r => b.column(0).setBoxed(r, p * rowsPerPartition + r))
      b.setNumRows(rowsPerPartition)
      IndexedSeq(b)
    })
    val cat = new Catalog
    cat.register("t", view)
    val groups = RelEngine.runRows(cat,
      "SELECT RAND() > 0.5 AS r, COUNT(*) AS n FROM t GROUP BY RAND() > 0.5", ExecutionOptions.Default)
    assertEquals("one group per value", groups.map(_(0)).distinct.size, groups.size)
    assertEquals(2000L, groups.map(_(1).asInstanceOf[Long]).sum)
    val sized = RelEngine.runRows(cat,
      "SELECT COUNT(*) OVER (PARTITION BY FLOOR(RAND() * 3)) AS n FROM t", ExecutionOptions.Default)
    // A partition of n rows gives n rows of n, so the sum of 1/n over the rows counts the partitions.
    val partitions = sized.map(r => 1.0 / r(0).asInstanceOf[Long]).sum
    assertTrue(s"$partitions partitions of FLOOR(RAND() * 3)", math.round(partitions) <= 3)
  }

  /** Metrics watch a plan; they do not change it. With metrics on, a sharded
    * join, aggregate, DISTINCT and window each ran on one partition: each
    * told its exchange child by its class, and with metrics on the child is
    * the wrapper that meters it (found by plans/testing/16's forced-plan
    * guard, reading a join's path off its plan). */
  @Test def metricsLeaveAShardedPlanSharded(): Unit = {
    val schema = Schema(Vector(Field("k", DataType.IntType), Field("v", DataType.IntType)))
    val rows = (0 until 40).map(i => Array[Any](i % 7, i))
    val cat = new Catalog
    cat.register("a", DataGen.Dataset(schema, rows).evenPartitions(4, 5))
    cat.register("b", DataGen.Dataset(schema, rows).evenPartitions(4, 5))
    val metrics = ExecutionOptions(metricsEnabled = true)
    Seq(
      "SELECT a.k, b.v FROM a JOIN b ON a.k = b.k",
      "SELECT k, COUNT(*) AS n FROM a GROUP BY k",
      "SELECT DISTINCT k FROM a",
      "SELECT k, SUM(v) OVER (PARTITION BY k) AS s FROM a").foreach { sql =>
      val logical = LogicalOptimizer.optimize(LogicalBuilder.build(sql, cat).main)
      assertEquals(s"the planner shards $sql", 4, PhysicalPlanner.plan(logical, ExecutionOptions.Default).numPartitions)
      assertEquals(s"with metrics on, $sql runs on as many partitions", 4, PhysicalPlanner.plan(logical, metrics).numPartitions)
      val q = RelEngine.execute(cat, sql, ExecutionOptions.Default)
      val expected = RelEngine.collectRows(q)
      val metered = RelEngine.execute(cat, sql, metrics)
      RowOracle.multisetEquals(q.schema, expected, RelEngine.collectRows(metered)).foreach(d => fail(s"$sql\n$d"))
      metered.metricsSnapshot
    }
  }

  // ---- the properties (identical to MetamorphicFuzzTest, under sharding) -----

  @Test def fuzzTlp(): Unit =
    Props.forAll[MetaCase](
      name = "sharded-tlp",
      gen = MetaQueryGen.generate,
      shrink = Shrinker.metaCase,
      count = Props.seedCountOr(DefaultShardedSeeds)
    ) { mc => Tlp.check(mc); () }

  @Test def fuzzNoRec(): Unit =
    Props.forAll[NoRecCase](
      name = "sharded-norec",
      gen = MetaQueryGen.generateNoRec,
      shrink = Shrinker.noRecCase,
      count = Props.seedCountOr(DefaultShardedSeeds * 2) // NoREC is cheap (2 queries)
    ) { nc => NoRec.check(nc); () }

  @Test def fuzzModeAgreement(): Unit =
    Props.forAll[MetaCase](
      name = "sharded-meta-mode-differential",
      gen = MetaQueryGen.generate,
      shrink = Shrinker.metaCase,
      count = Props.seedCountOr(DefaultShardedSeeds)
    ) { mc => MetaModeDifferential.check(mc); () }

  /** Commuting a join matters most here: under `broadcast_threshold=1` the
    * planner takes the shuffle-join path, so the swap moves which side is
    * shuffled and built, not merely which side is the in-heap hash table. */
  @Test def fuzzJoinCommutativity(): Unit =
    Props.forAll[MetaCase](
      name = "sharded-join-commutativity",
      gen = MetaQueryGen.generate,
      shrink = Shrinker.metaCase,
      count = Props.seedCountOr(DefaultShardedSeeds * 2) // cheap: two executions per case
    ) { mc => JoinCommutativity.check(mc); () }

  /** Under `shard_min_size=1` the grouped side of a decomposition really is a
    * partial/final pair across an exchange, so this is where the partial-merge
    * path it targets is fully wired. Budget is a quarter of the others' — each
    * case runs ~7 decompositions under two modes. */
  @Test def fuzzAggDecomposition(): Unit =
    Props.forAll[MetaCase](
      name = "sharded-agg-decomposition",
      gen = MetaQueryGen.generate,
      shrink = Shrinker.metaCase,
      count = Props.seedCountOr(DefaultShardedSeeds / 4)
    ) { mc => AggDecomposition.check(mc); () }
}
