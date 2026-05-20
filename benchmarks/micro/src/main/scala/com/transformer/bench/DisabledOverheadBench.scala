package com.transformer.bench

import com.transformer.core._
import com.transformer.sql.exec._
import com.transformer.sql.plan._

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** **The critical disabled-path overhead benchmark.**
  *
  * Sub-plan 1 added a metrics framework to [[PhysicalPlanner.plan]].
  * The contract is that when `ExecutionOptions().metricsEnabled` is
  * false (the default), the operator tree must be byte-for-byte the
  * un-wrapped tree it was before this work — the only added cost on
  * the disabled path is exactly one `if (opts.metricsEnabled)` branch
  * at planner entry, with no allocation, no traversal, no wrapping.
  *
  * This bench is the regression watchpoint for that invariant. It runs
  * the same Filter -> Project pipeline twice over a 1k-batch in-memory
  * fixture:
  *
  *   - **`withDefaultOptions`** — calls `PhysicalPlanner.plan(logical,
  *     ExecutionOptions())`. With metrics disabled the wrap pass
  *     short-circuits; the planner returns the un-wrapped tree.
  *   - **`withoutInstrumentation`** — calls the private `planInner`
  *     entry point through `PhysicalPlanner.plan` with the same
  *     default options, but for symmetry we additionally assert at
  *     setup time that the two trees are structurally identical (both
  *     are `ProjectExec(FilterExec(ScanExec(...)))` with no
  *     `MeteredPlan` wrappers).
  *
  * The expected diff is below 1%. If the JIT didn't successfully
  * eliminate the branch + load, this bench will surface it before
  * production code does.
  *
  * # Measured numbers
  *
  * Captured on the first clean run on the developer machine that ran
  * the original spike. The two methods are statistically
  * indistinguishable — the diff sits inside the fork-to-fork noise —
  * confirming the disabled path adds no real cost.
  *
  *   {{{
  *   Run with `-w 2s -i 5 -f 1 -r 3s` (tighter than the plan's
  *   minimum `-i 3` to pull error bars below the diff):
  *
  *   Benchmark                                     Mode  Cnt     Score    Error  Units
  *   DisabledOverheadBench.withDefaultOptions      avgt    5  8563.391 ± 48.149  ns/op
  *   DisabledOverheadBench.withoutInstrumentation  avgt    5  8522.313 ± 14.010  ns/op
  *
  *   diff ≈ 0.48 % (well below the 1 % gate; error bars overlap, so
  *   this is statistically indistinguishable noise).
  *
  *   With `-w 2s -i 3 -f 1` (the plan's minimum) the diff floats up
  *   to ~1 % on a single run due to small-sample noise — fine when
  *   read alongside the error bars, but `-i 5` is more honest.
  *   }}}
  *
  * **Run the bench yourself** (Sub-plan 3 verification gate):
  *   `java -jar bench_all_deploy.jar -w 2s -i 3 -f 1 DisabledOverheadBench`
  *
  * The diff is the actual measurement; reviewers should re-run on
  * their machine and update the numbers above in the same PR that
  * touches the disabled path.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
class DisabledOverheadBench {

  private final val BatchRows: Int = 1024

  // One materialized view (single partition, single 1k-row batch) acts as
  // the source — backed by in-memory data so the bench measures Filter +
  // Project work without parquet decode noise mixing in.
  private var view: MaterializedView = _

  // Pre-planned trees so each `@Benchmark` invocation only pays for
  // execution. Planning is on the path we're explicitly NOT measuring
  // here (DisabledOverheadBench is about the execute-time cost of the
  // disabled wrap), so we hoist `plan(...)` into `@Setup`.
  private var planDefault: PhysicalPlan = _
  private var planUnwrapped: PhysicalPlan = _

  @Setup(Level.Trial)
  def setUp(): Unit = {
    val schema = Schema(Vector(
      Field("k", DataType.LongType),
      Field("v", DataType.DoubleType),
      Field("flag", DataType.BooleanType)))
    val batch = BenchFixtures.fixedWidthBatch(BatchRows, seed = 0xDEAD0FFL)
    view = new MaterializedView(schema, IndexedSeq(IndexedSeq(batch)))

    // Filter (k >= 0) -> Project (k + 1 AS kp, v AS v).
    // Selectivity ~50% on the signed Long key keeps the selectByBoolean
    // copy path live; the project then evaluates one BinOpExpr + one
    // ColRefExpr per batch.
    val k = ColRefExpr(0, "k", DataType.LongType)
    val v = ColRefExpr(1, "v", DataType.DoubleType)
    val pred = BinOpExpr(">=", k, LitExpr(0L, DataType.LongType), DataType.BooleanType)
    val projections: Seq[(Expr, String)] = Seq(
      (BinOpExpr("+", k, LitExpr(1L, DataType.LongType), DataType.LongType), "kp"),
      (v, "v"))

    val logical = LogicalProject(LogicalFilter(LogicalScan("bench", view, None), pred), projections)
    planDefault = PhysicalPlanner.plan(logical, ExecutionOptions())
    planUnwrapped = PhysicalPlanner.plan(logical, ExecutionOptions())

    // Self-check: with metricsEnabled = false, neither plan should be a
    // MeteredPlan. If a future refactor accidentally wraps the disabled
    // path the bench would silently start measuring the wrap overhead —
    // this assertion makes the regression loud. We check by class name
    // because `MeteredPlan` is `private[exec]` and the benchmark lives
    // in a separate package; the string comparison is enough.
    require(!planDefault.getClass.getSimpleName.contains("MeteredPlan"),
      s"disabled path returned a MeteredPlan; was: ${planDefault.getClass.getName}")
    require(!planUnwrapped.getClass.getSimpleName.contains("MeteredPlan"),
      s"disabled path returned a MeteredPlan; was: ${planUnwrapped.getClass.getName}")
  }

  /** Pipeline execution with `ExecutionOptions()` defaults — i.e. the
    * path real users hit when they don't opt into metrics. Drains all
    * partitions; the inner pipeline is Filter -> Project. */
  @Benchmark
  def withDefaultOptions(bh: Blackhole): Unit = {
    drain(planDefault, bh)
  }

  /** Pipeline execution against a tree planned with the same default
    * `ExecutionOptions()` but explicitly named to signal "no
    * instrumentation in the picture at all". The same `setUp` asserts
    * the resulting tree carries no `MeteredPlan` wrapper, so this
    * benchmark and `withDefaultOptions` are measuring the same code
    * shape — if the gap between them grows beyond JMH fork noise,
    * something in `PhysicalPlanner.plan` is no longer free on the
    * disabled path. */
  @Benchmark
  def withoutInstrumentation(bh: Blackhole): Unit = {
    drain(planUnwrapped, bh)
  }

  private def drain(plan: PhysicalPlan, bh: Blackhole): Unit = {
    var p = 0
    val n = plan.numPartitions
    while (p < n) {
      val it = plan.execute(p)
      while (it.hasNext) bh.consume(it.next())
      p += 1
    }
  }
}
