package com.transformer.fuzz

import com.transformer.core._
import com.transformer.fuzz.MetaQueryGen._
import com.transformer.fuzz.oracle.{MetaModeDifferential, NoRec, Tlp}
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
  * The target also sets `transformer.scheduler.shard_count=1`. Sharded execution
  * at K>1 has a KNOWN, unfixed nested-pool-blocking deadlock: deep sharded plans
  * (a breaker over an exchange over a breaker over an exchange ...) park every
  * `Scheduler.pool` worker awaiting sub-tasks, starving the pool of threads to
  * run them (see docs/gotchas.md). At the default shard count the fuzzer hangs.
  * K=1 keeps each breaker's per-shard fan-out to a single task — no nested
  * starvation — while still driving every sharded CODE path (exchange insertion,
  * the `executePerShard` aggregate/distinct/join paths) for result correctness;
  * the multi-shard routing itself is covered operator-level by `exchange_exec_test`.
  *
  * The properties are the same generators + oracles + shrinker as
  * [[MetamorphicFuzzTest]]; the scope here is narrow: do the metamorphic
  * relations ([[Tlp]], [[NoRec]]) and in-JVM mode agreement
  * ([[MetaModeDifferential]]) still hold when the planner shards? Bind-reject
  * rate and shape coverage are planner-independent and stay in
  * [[MetamorphicFuzzTest]]; they are not duplicated here.
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
  // threshold; it still runs for the non-join queries the join-spill bug spares)
  // so the default spill location is never littered.
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
}
