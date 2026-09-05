package com.transformer.fuzz

import com.transformer.core._
import com.transformer.fuzz.MetaQueryGen._
import com.transformer.fuzz.oracle.{AggDecomposition, JoinCommutativity, MetaModeDifferential, NoRec, Tlp}
import com.transformer.sql.plan.LogicalPlanCardinality
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
