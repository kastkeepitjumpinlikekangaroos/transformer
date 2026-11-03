package com.transformer.fuzz

import com.transformer.core._
import com.transformer.fuzz.oracle.ForcedPlans
import com.transformer.sql.plan.LogicalPlanCardinality
import org.junit.Assert._
import org.junit.{After, Test}


/** The strategy half of [[ForcedPlanFuzzTest]] under sharded planning: its
  * own JVM, whose `jvm_flags` open the sharding gates
  * (`LogicalPlanCardinality.MinShardableSize` and `BroadcastBuildThreshold`,
  * read at class load), as `ShardedModeFuzzTest`'s do. Here every join on a
  * key shuffles both sides into four shards and joins each, and the spelling
  * with no key cannot: it pairs row by row, unsharded. So one join is held
  * sharded against unsharded in one JVM, which the gates otherwise rule out,
  * and the query as written, sharded, against both. */
class ShardedForcedPlanFuzzTest {

  import ForcedPlanFuzzTest._

  // This JVM's spill directory is read once (`Spill.baseDir`, under
  // java.io.tmpdir, which Bazel sets to the target's own TEST_TMPDIR), so a
  // test cannot move it. Every operator that spills closes its directory once
  // nothing reads it, so each test leaves none (plans/testing/17).
  @After def leavesNoSpillDirectory(): Unit =
    assertEquals("spill directories left", Nil, Spill.operatorDirsLeft)

  /** The `jvm_flags` reached `LogicalPlanCardinality` at class load. */
  @Test def shardingGateIsActive(): Unit = {
    assertEquals("jvm_flags shard_min_size=1 must reach LogicalPlanCardinality at class load",
      1L, LogicalPlanCardinality.MinShardableSize)
    assertEquals("jvm_flags broadcast_threshold=1 must reach LogicalPlanCardinality at class load",
      1L, LogicalPlanCardinality.BroadcastBuildThreshold)
  }

  @Test def fuzzStrategies(): Unit =
    Props.forAll[Case](
      name = "sharded-forced-strategies",
      gen = generate,
      shrink = shrink,
      count = Props.seedCountOr(DefaultStrategySeeds)
    ) { c => ForcedPlans.checkStrategies(c.value, strategies(c)); () }

  /** A join on a key runs per shard, and one with none pairs unsharded, on a
    * share of cases. Planning only. */
  @Test def strategiesReachThePerShardAndPairingPaths(): Unit = {
    val (paths, _, report) = pathTally(StrategyGuardSeeds)
    println(s"[sharded-forced-plans] $report")
    Seq("per-shard", "pairing", "grace hash").foreach(p =>
      assertTrue(s"a join rarely takes the $p path: $report", paths(p) > PathFloor))
  }
}
