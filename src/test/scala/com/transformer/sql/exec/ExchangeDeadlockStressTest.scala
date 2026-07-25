package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.Test

import java.util.concurrent.{Callable, TimeUnit, TimeoutException}
import scala.collection.mutable

/** Regression stress for the K>1 sharded-execution deadlock family
  * (docs/gotchas.md "Sharded execution at K>1"; plans/bugfixes/02e diagnosed
  * it, 02f fixed it via bottom-up exchange pre-materialization).
  *
  * The wedge captured at campaign scale (plans/bugfixes/02e-evidence/, and
  * reproduced by this test's lazy mode) has this shape:
  *
  *   - an exchange E2's materializing winner (itself a probe task of the
  *     collapsing join J1 above E2) parks in `submitAndAwaitAll` awaiting one
  *     of E2's shard tasks;
  *   - that shard task was stolen by another worker, whose own nested
  *     `.get()` (the build fan-out of a collapsing join J2 inside E2's child)
  *     entered `ForkJoinPool.helpJoin`, which inlined ANOTHER of J1's probe
  *     tasks — a *consumer* of E2 — beneath the shard-task frames;
  *   - the guest calls `E2.execute` and parks as an exchange loser, closing
  *     the cycle: winner -> shard task -> inlined consumer -> exchange ->
  *     winner. The shard task can never resume (its thread is stuck under
  *     the guest), so the winner can never publish.
  *
  * The test rebuilds exactly that plan shape from the real exec classes and
  * drives it in a loop, fresh instances per iteration, K concurrent
  * pool-task drivers per iteration.
  *
  * DEFAULT (CI) MODE encodes the 02f contract: each iteration runs
  * [[PhysicalPlanner.preMaterializeExchanges]] on the graph — exactly what
  * the engine's drain paths do after planning — before the drivers are
  * submitted. Under that ordering the wedge is structurally impossible and
  * the loop must stay green under full steal churn.
  *
  * `STRESS_LAZY=1` skips the pass and drains the same graph through the lazy
  * `ensureMaterialized` fallback from pool tasks — the pre-02f engine
  * behavior. That mode WEDGES within seconds (measured on an 8-core arm64
  * mac, JDK 21.0.6, parallelism=16: 7/7 trials, median ~130 iterations to
  * hit, ~1/150 per iteration — full table in plans/bugfixes/02f). It is the
  * manual red instrument, not a CI assertion: the hit is probabilistic
  * (helpJoin inlining rides ForkJoinPool steal-victim randomness and queue
  * layout, neither forceable from public API), and one wedge permanently
  * eats the workers involved.
  *
  *   bazel test //src/test/scala/com/transformer/sql/exec:exchange_deadlock_stress_test \
  *       --test_tag_filters= --nocache_test_results --test_timeout=420 \
  *       --test_env=STRESS_LAZY=1
  *
  * Knobs (env vars, read once at class load; parallelism is pinned to 16 in
  * the BUILD's jvm_flags — at 8 the lazy-mode hit rate collapses):
  *
  *   STRESS_ITERS            iterations per driver group   (default 3000)
  *   STRESS_GROUPS           concurrent driver groups      (default 2)
  *   STRESS_K                shard count for E1 and E2     (default 4)
  *   STRESS_UNION_FAN        J2 subtrees under E2's union  (default 2)
  *   STRESS_BUILD_PARTS      build partitions per J2       (default 4)
  *   STRESS_SPIN_NANOS       busy-spin per J2 build task   (default 200000)
  *   STRESS_ITER_DEADLINE_MS per-iteration stall deadline  (default 10000)
  *   STRESS_LAZY             1 = skip the 02f pass (red)   (default 0)
  *
  * On a stall the test dumps every thread whose stack holds transformer or
  * ForkJoinPool frames, classifies whether the captured 02e shape is present
  * (`ensureMaterialized` + `helpJoin`), and fails.
  */
class ExchangeDeadlockStressTest {

  private def envInt(name: String, dflt: Int): Int =
    Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty).map(_.toInt).getOrElse(dflt)
  private def envLong(name: String, dflt: Long): Long =
    Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty).map(_.toLong).getOrElse(dflt)

  private val iters        = envInt("STRESS_ITERS", 3000)
  private val groups       = envInt("STRESS_GROUPS", 2)
  private val numShards    = envInt("STRESS_K", 4)
  private val unionFan     = envInt("STRESS_UNION_FAN", 2)
  private val buildParts   = envInt("STRESS_BUILD_PARTS", 4)
  private val spinNanos    = envLong("STRESS_SPIN_NANOS", 200_000L)
  private val iterDeadline = envLong("STRESS_ITER_DEADLINE_MS", 10_000L)
  private val lazyMode     = envInt("STRESS_LAZY", 0) == 1

  // ---- Schemas / keys -------------------------------------------------------

  private val probeSchema = Schema(Vector(
    Field("k", DataType.IntType),
    Field("v", DataType.IntType)))
  private val buildSchema = Schema(Vector(
    Field("k", DataType.IntType),
    Field("w", DataType.IntType)))
  private val tinySchema = Schema(Vector(
    Field("z", DataType.IntType)))

  private val probeKey: Expr = ColRefExpr(0, "k", DataType.IntType)
  private val buildKey: Expr = ColRefExpr(0, "k", DataType.IntType)

  private val distinctKeys = 16
  private val probeRowsPerJ2 = 64

  /** J2 output = probeSchema ++ buildSchema; key col 0 is `k`. */
  private val j2OutKey: Expr = ColRefExpr(0, "k", DataType.IntType)
  /** J1 output = (J2 output) ++ tinySchema; key col 0 is still `k`. */
  private val j1OutKey: Expr = ColRefExpr(0, "k", DataType.IntType)

  // ---- The test -------------------------------------------------------------

  @Test(timeout = 400_000) def campaignShapedPlanLoopDoesNotWedge(): Unit = {
    val failure = new java.util.concurrent.atomic.AtomicReference[Throwable](null)
    val done = new java.util.concurrent.atomic.AtomicInteger(0)
    val threads = (0 until groups).map { g =>
      val t = new Thread(() => {
        try {
          var i = 0
          while (i < iters && failure.get() == null) {
            runOneIteration(g, i)
            i += 1
          }
          done.addAndGet(i)
        } catch {
          case t: Throwable => failure.compareAndSet(null, t)
        }
      }, s"stress-group-$g")
      t.setDaemon(true)
      t.start()
      t
    }
    threads.foreach(_.join())
    if (failure.get() != null) throw failure.get()
    // Sanity that the loop actually exercised the shape.
    assertEquals("all iterations ran", groups * iters, done.get())
  }

  /** One fresh instance graph, driven by K concurrent pool tasks (the same
    * role the collapsing aggregate's partial tasks play in the captured
    * dump), awaited with a stall deadline. */
  private def runOneIteration(group: Int, iter: Int): Unit = {
    val e1 = buildTopology()
    // The 02f contract: engine drain paths publish every exchange bottom-up
    // before consumers exist. STRESS_LAZY=1 skips this to demonstrate the
    // historical wedge on the lazy fallback path.
    if (!lazyMode) PhysicalPlanner.preMaterializeExchanges(e1)
    val drivers: Seq[Callable[Long]] = (0 until numShards).map { s =>
      new Callable[Long] {
        def call(): Long = countRows(e1.execute(s))
      }
    }
    val futures = drivers.map(Scheduler.submit)
    var total = 0L
    futures.zipWithIndex.foreach { case (f, s) =>
      try total += f.get(iterDeadline, TimeUnit.MILLISECONDS)
      catch {
        case _: TimeoutException =>
          failWithWedgeDiagnostics(group, iter, s)
      }
    }
    val expected = (unionFan * probeRowsPerJ2).toLong
    assertEquals(s"group $group iteration $iter row count", expected, total)
  }

  /** driver(K) -> E1 -> J1(probe = E2, build = 1-row scan; cartesian)
    *           -> E2 -> union of `unionFan` J2 subtrees
    *           -> J2_i = equi join, build side `buildParts` partitions.
    * All collapsing joins: J1/J2 have a non-exchange side, so
    * `numPartitions == 1` and both fan out via `submitAndAwaitAll` — J1's
    * probe fan-out spawns the K consumer tasks of E2, J2's build fan-out is
    * the nested `.get()` where helpJoin inlines one of them. */
  private def buildTopology(): ExchangeExec = {
    val j2s: Seq[PhysicalPlan] = (0 until unionFan).map { i =>
      val probe = new InMemoryRowsPlan(probeSchema, partitionRows(
        nParts = 2, rowsPerPart = probeRowsPerJ2 / 2,
        row = j => Array[Any](Int.box(j % distinctKeys), Int.box(i * 1000 + j))))
      val build = new SpinPlan(new InMemoryRowsPlan(buildSchema, partitionRows(
        nParts = buildParts, rowsPerPart = distinctKeys / buildParts,
        row = j => Array[Any](Int.box(j), Int.box(j)))), spinNanos)
      HashJoinExec(left = probe, right = build,
        leftKeys = Seq(probeKey), rightKeys = Seq(buildKey),
        extra = None, kind = JoinKind.Inner)
    }
    val e2Child = j2s.reduce(UnionExec(_, _))
    val e2 = ExchangeExec(e2Child, Seq(j2OutKey), numShards)
    val tiny = new InMemoryRowsPlan(tinySchema,
      Vector(Vector(Array[Any](Int.box(7)))))
    val j1 = HashJoinExec(left = e2, right = tiny,
      leftKeys = Nil, rightKeys = Nil, extra = None, kind = JoinKind.Inner)
    ExchangeExec(j1, Seq(j1OutKey), numShards)
  }

  /** Build-side keys are dealt round-robin so every partition holds
    * `rowsPerPart` distinct keys and the union of partitions covers
    * [0, distinctKeys). Probe rows cycle over the same key space, so the
    * equi join matches 1:1 and row counts stay closed-form. */
  private def partitionRows(
      nParts: Int, rowsPerPart: Int, row: Int => Array[Any]): Vector[Vector[Array[Any]]] =
    Vector.tabulate(nParts) { p =>
      Vector.tabulate(rowsPerPart)(r => row(p * rowsPerPart + r))
    }

  private def countRows(it: Iterator[ColumnarBatch]): Long = {
    var n = 0L
    while (it.hasNext) n += it.next().numRows
    n
  }

  // ---- Wedge diagnostics ----------------------------------------------------

  private def failWithWedgeDiagnostics(group: Int, iter: Int, shard: Int): Nothing = {
    val sb = new StringBuilder
    sb.append(s"WEDGE: group $group iteration $iter driver shard $shard exceeded ")
    sb.append(s"${iterDeadline}ms — permanent stall suspected (02e helpJoin cycle).\n")
    var sawEnsure = false
    var sawHelpJoin = false
    val stacks = Thread.getAllStackTraces
    stacks.forEach { (t, frames) =>
      val interesting = frames.exists { f =>
        f.getClassName.startsWith("com.transformer") ||
        f.getClassName.contains("ForkJoinPool") ||
        f.getClassName.contains("ForkJoinTask")
      }
      if (interesting && frames.nonEmpty) {
        sb.append(s"\n\"${t.getName}\" state=${t.getState}\n")
        frames.foreach { f =>
          val line = f.toString
          if (line.contains("ensureMaterialized")) sawEnsure = true
          if (line.contains("helpJoin")) sawHelpJoin = true
          sb.append("    at ").append(line).append('\n')
        }
      }
    }
    sb.append(s"\nclassification: ensureMaterialized frames=$sawEnsure, helpJoin frames=$sawHelpJoin ")
    sb.append(if (sawEnsure) "(exchange-readiness wait present — the 02e family)"
              else "(NO exchange frames — different stall, investigate)")
    System.err.println(sb.toString)
    fail(sb.toString.take(4000))
    throw new IllegalStateException("unreachable")
  }
}

/** Pre-partitioned in-memory rows. Same shape as the private helpers in
  * [[ExchangeExecTest]] / [[SortExecTest]] — duplicated per file on purpose
  * so suites stay independent. */
private final class InMemoryRowsPlan(
    schema: Schema,
    partitions: Vector[Vector[Array[Any]]]) extends PhysicalPlan {
  def outputSchema: Schema = schema
  def numPartitions: Int = partitions.length
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    val rows = partitions(partition)
    if (rows.isEmpty) return Iterator.empty
    val b = new ColumnarBatch(schema, rows.length)
    var r = 0
    while (r < rows.length) {
      val row = rows(r)
      var c = 0
      while (c < schema.length) {
        if (row(c) == null) b.column(c).setNull(r) else b.column(c).setBoxed(r, row(c))
        c += 1
      }
      r += 1
    }
    b.setNumRows(rows.length)
    Iterator.single(b)
  }
}

/** Busy-spins for `spinNanos` before delegating, WITHOUT parking — widens the
  * window in which a claimed task is in progress (so its awaiter's helpJoin
  * keeps scanning for guest work) while staying an ordinary CPU-bound leaf. */
private final class SpinPlan(inner: PhysicalPlan, spinNanos: Long) extends PhysicalPlan {
  def outputSchema: Schema = inner.outputSchema
  def numPartitions: Int = inner.numPartitions
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    if (spinNanos > 0) {
      val end = System.nanoTime() + spinNanos
      while (System.nanoTime() < end) {}
    }
    inner.execute(partition)
  }
}
