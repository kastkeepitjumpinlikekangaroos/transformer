package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan.{LogicalBuilder, LogicalOptimizer, LogicalPlan, LogicalScan}

import org.junit.Assert._
import org.junit.Test

import java.util.concurrent.atomic.AtomicInteger

/** Tests for CTE materialization: a `WITH name AS (...)` CTE referenced
  * >= 2 times in a query is executed ONCE into a shared in-memory result and
  * every reference replays that shared result; a CTE referenced <= 1 time is
  * inlined (executed per-reference).
  *
  * NOTE TO THE INTEGRATING ENGINEER: tests 1-3 below are BEHAVIORAL and are
  * EXPECTED TO FAIL AT RUNTIME until the materialization code lands. They are
  * authored against the public `SqlEngine.execute` entry point and the
  * intended semantics, not against any internal materialization API. The file
  * compiles today; the assertions become true once materialize-once is wired
  * in.
  *
  *   - [[computeOnceUnderMultipleReferences]] proves the source under a CTE
  *     referenced 3 times is scanned exactly once (once per partition), not 3x,
  *     while staying result-invisible (equal to the inline self-join).
  *   - [[emptyCteReferencedTwiceReadOnce]] proves the read-once property holds
  *     even when the CTE body produces no rows.
  *   - [[determinismFenceMaterializedReferencesAgree]] locks in the
  *     optimization-fence semantics: a multiply-referenced non-deterministic
  *     CTE body (`RAND()`) is computed once, so all references see identical
  *     values and agree.
  */
class CteMaterializationTest {

  // ---------------------------------------------------------------------------
  // In-memory test views
  // ---------------------------------------------------------------------------

  /** Schema shared by the counting views: id + score, both LongType, so a join
    * on id between two references is 1:1.
    */
  private val idScoreSchema = Schema(Vector(
    Field("id", DataType.LongType),
    Field("score", DataType.LongType)))

  /** A view over a fixed (id, score) dataset, split across `parts` partitions
    * round-robin, that increments `reads` exactly once per [[readPartition]]
    * call. The counter is bumped eagerly when `readPartition` is invoked (i.e.
    * once per partition the engine actually reads), which is what we assert
    * against `numPartitions` after a full drain.
    */
  private final class CountingIdScoreView(
      rows: Vector[(Long, Long)],
      parts: Int,
      val reads: AtomicInteger) extends CatalogView {

    def schema: Schema = idScoreSchema
    def numPartitions: Int = parts

    // Partition `p` owns rows whose index mod `parts` == p. Keeps a handful of
    // rows spread across partitions so "once per partition" is meaningful.
    private def rowsFor(p: Int): Vector[(Long, Long)] =
      rows.zipWithIndex.collect { case (rc, i) if i % parts == p => rc }

    def readPartition(p: Int): Iterator[ColumnarBatch] = {
      // Count this physical read of the underlying source. Materialization must
      // ensure the engine calls this once per partition total, not once per
      // CTE reference.
      reads.incrementAndGet()
      val mine = rowsFor(p)
      if (mine.isEmpty) Iterator.empty
      else {
        val b = new ColumnarBatch(idScoreSchema, mine.length)
        val idC = b.column(0).asInstanceOf[LongVector]
        val scoreC = b.column(1).asInstanceOf[LongVector]
        var r = 0
        while (r < mine.length) {
          idC.set(r, mine(r)._1)
          scoreC.set(r, mine(r)._2)
          r += 1
        }
        b.setNumRows(mine.length)
        Iterator.single(b)
      }
    }
  }

  /** A trivial in-memory view exposing a single `id` LongType column with the
    * given ids in one partition. Used by the determinism-fence test, where no
    * read counting is needed.
    */
  private final class IdOnlyView(ids: Vector[Long]) extends CatalogView {
    private val sch = Schema(Vector(Field("id", DataType.LongType)))
    def schema: Schema = sch
    def numPartitions: Int = 1
    def readPartition(p: Int): Iterator[ColumnarBatch] = {
      if (ids.isEmpty) Iterator.empty
      else {
        val b = new ColumnarBatch(sch, ids.length)
        val idC = b.column(0).asInstanceOf[LongVector]
        var r = 0
        while (r < ids.length) { idC.set(r, ids(r)); r += 1 }
        b.setNumRows(ids.length)
        Iterator.single(b)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers (mirroring SqlEngineTest.collectAllRows)
  // ---------------------------------------------------------------------------

  /** Fully drain an executed query into boxed row maps. Draining is mandatory
    * before inspecting any read counter: the engine is lazy/streaming, so the
    * underlying source is not touched until batches are pulled.
    */
  private def collectAllRows(q: ExecutedQuery): Seq[Map[String, Any]] = {
    val buf = scala.collection.mutable.ArrayBuffer.empty[Map[String, Any]]
    while (q.batches.hasNext) {
      val b = q.batches.next()
      var r = 0
      while (r < b.numRows) {
        buf += q.schema.fieldNames.zipWithIndex.map { case (n, i) =>
          n -> (if (b.column(i).isNull(r)) null else b.column(i).getBoxed(r))
        }.toMap
        r += 1
      }
    }
    buf.toSeq
  }

  private def catalogWith(name: String, view: CatalogView): Catalog = {
    val c = new Catalog
    c.register(name, view)
    c
  }

  private def asLong(v: Any): Long = v match {
    case l: java.lang.Long    => l.longValue()
    case i: java.lang.Integer => i.longValue()
    case other                => fail(s"expected an integral value, got $other (${other.getClass})").asInstanceOf[Long]
  }

  private def asBool(v: Any): Boolean = v match {
    case b: java.lang.Boolean => b.booleanValue()
    case other                => fail(s"expected a boolean value, got $other (${other.getClass})").asInstanceOf[Boolean]
  }

  // ---------------------------------------------------------------------------
  // 1. THE KEY TEST: a CTE referenced 3 times is computed once.
  // ---------------------------------------------------------------------------

  @Test def computeOnceUnderMultipleReferences(): Unit = {
    // ids 1..4 with distinct scores; the join on id between references is 1:1.
    val data = Vector((1L, 10L), (2L, 20L), (3L, 30L), (4L, 40L))
    val parts = 2
    val reads = new AtomicInteger(0)
    val view = new CountingIdScoreView(data, parts, reads)
    val cat = catalogWith("t", view)

    // `c` is referenced 3 times (x, y, z). Under materialization the body
    // (SELECT id, score FROM t) is executed once into a shared result; all
    // three references replay it. Under naive inlining the source `t` would be
    // scanned 3x.
    val q = SqlEngine.execute(
      "WITH c AS (SELECT id, score FROM t) " +
        "SELECT x.id AS xid, y.id AS yid, z.id AS zid " +
        "FROM c x JOIN c y ON x.id = y.id JOIN c z ON x.id = z.id " +
        "ORDER BY x.id",
      cat, ExecutionOptions.Default)

    // Drain FULLY before reading the counter: execution is lazy/streaming.
    val rows = collectAllRows(q)

    // Result-invisibility: the 1:1:1 self-join yields exactly ids 1..4 with
    // xid == yid == zid on every row. Materialization must not change output.
    assertEquals("expected one output row per matched id", 4, rows.size)
    assertEquals(Seq(1L, 2L, 3L, 4L), rows.map(r => asLong(r("xid"))))
    rows.foreach { r =>
      val xid = asLong(r("xid"))
      assertEquals(s"yid must equal xid for id=$xid", xid, asLong(r("yid")))
      assertEquals(s"zid must equal xid for id=$xid", xid, asLong(r("zid")))
    }

    // The proof of the feature: the underlying source was read exactly once per
    // partition (numPartitions total), NOT once per CTE reference (3x).
    val count = reads.get()
    assertEquals(
      s"source read $count times, expected ${view.numPartitions} (once per partition)",
      view.numPartitions, count)
  }

  // ---------------------------------------------------------------------------
  // 2. Empty CTE referenced twice is still read once.
  // ---------------------------------------------------------------------------

  @Test def emptyCteReferencedTwiceReadOnce(): Unit = {
    // Zero rows, still 2 partitions (each yields no batches).
    val parts = 2
    val reads = new AtomicInteger(0)
    val view = new CountingIdScoreView(Vector.empty, parts, reads)
    val cat = catalogWith("t", view)

    // `c` referenced twice (x, y).
    val q = SqlEngine.execute(
      "WITH c AS (SELECT id, score FROM t) " +
        "SELECT x.id FROM c x JOIN c y ON x.id = y.id",
      cat, ExecutionOptions.Default)

    // Drain fully before reading the counter.
    val rows = collectAllRows(q)

    assertTrue(s"expected empty result, got ${rows.size} rows", rows.isEmpty)

    val count = reads.get()
    assertEquals(
      s"source read $count times, expected ${view.numPartitions} (once per partition)",
      view.numPartitions, count)
  }

  // ---------------------------------------------------------------------------
  // 3. Determinism fence: multiply-referenced non-deterministic body agrees.
  // ---------------------------------------------------------------------------

  @Test def determinismFenceMaterializedReferencesAgree(): Unit = {
    // This test locks in the optimization-fence semantics of materialization:
    // a CTE body containing a non-deterministic expression (RAND() with no
    // argument draws from a thread-local RNG, so each evaluation differs) is
    // computed ONCE. Both references (x, y) therefore read the SAME
    // computed-once `r` value per id, so `x.r = y.r` is true on every matched
    // row. Under naive inlining x.r and y.r would be evaluated independently
    // and (almost surely) differ, making the CTE self-inconsistent.
    val cat = catalogWith("t", new IdOnlyView(Vector(1L, 2L, 3L, 4L)))

    val q = SqlEngine.execute(
      "WITH c AS (SELECT RAND() AS r, id FROM t) " +
        "SELECT x.r = y.r AS same, x.id AS id " +
        "FROM c x JOIN c y ON x.id = y.id ORDER BY x.id",
      cat, ExecutionOptions.Default)

    val rows = collectAllRows(q)

    // One row per matched id (1:1 self-join over distinct ids 1..4).
    assertEquals("expected one row per id", 4, rows.size)
    assertEquals(Seq(1L, 2L, 3L, 4L), rows.map(r => asLong(r("id"))))
    rows.foreach { r =>
      val id = asLong(r("id"))
      assertTrue(
        s"materialized references must agree on RAND() for id=$id (x.r == y.r)",
        asBool(r("same")))
    }
  }

  // ---------------------------------------------------------------------------
  // 4. Planner-level controls: single-ref inlines (no MV); multi-ref materializes.
  // ---------------------------------------------------------------------------

  /** Count `LogicalScan`s over a `MaterializedView` anywhere in `plan` — the
    * marker that the resolver materialized a CTE rather than inlining it. */
  private def countMaterializedViewScans(plan: LogicalPlan): Int = {
    val self = plan match {
      case LogicalScan(_, _: MaterializedView, _) => 1
      case _                                      => 0
    }
    self + plan.children.iterator.map(countMaterializedViewScans).sum
  }

  private def idScoreView(reads: AtomicInteger): CountingIdScoreView =
    new CountingIdScoreView(Vector((1L, 10L), (2L, 20L), (3L, 30L), (4L, 40L)), 2, reads)

  @Test def singleRefCteIsInlinedNotMaterialized(): Unit = {
    // Negative control for the compute-once test: a CTE referenced exactly once
    // is inlined, so the resolved plan has NO MaterializedView scan and the
    // resolve pass itself never executes the body (materialization is what
    // triggers an eager read; inlining defers to main-query execution).
    val reads = new AtomicInteger(0)
    val cat = catalogWith("t", idScoreView(reads))
    val built = LogicalBuilder.build("WITH c AS (SELECT id, score FROM t) SELECT id FROM c", cat)
    val resolved = CteResolver.resolve(built, ExecutionOptions.Default)
    assertEquals("single-ref CTE must not introduce a MaterializedView scan",
      0, countMaterializedViewScans(resolved))
    assertEquals("resolve must not execute the body for a single-ref CTE",
      0, reads.get())
  }

  @Test def multiRefCteIntroducesMaterializedViewScan(): Unit = {
    // The positive planner-level control: two references => the body is
    // materialized once during resolve (reading the source once per partition)
    // and each reference becomes a scan over the shared MaterializedView.
    val reads = new AtomicInteger(0)
    val view = idScoreView(reads)
    val cat = catalogWith("t", view)
    val built = LogicalBuilder.build(
      "WITH c AS (SELECT id, score FROM t) SELECT x.id FROM c x JOIN c y ON x.id = y.id", cat)
    val resolved = CteResolver.resolve(built, ExecutionOptions.Default)
    assertEquals("two-ref CTE must materialize: both references scan the shared MV",
      2, countMaterializedViewScans(resolved))
    assertEquals("body materialized once, source read once per partition",
      view.numPartitions, reads.get())
  }

  // ---------------------------------------------------------------------------
  // 5. Dependent CTEs (b reads a), both referenced >= 2 times.
  // ---------------------------------------------------------------------------

  @Test def dependentCtesBothMaterializedReadSourceOnce(): Unit = {
    // a reads t; b reads a. a is referenced by b's body AND by main (z) => 2;
    // b is referenced twice in main (x, y) => 2. Resolving a first rewrites b's
    // body to scan a's MV, so b's single materialization reads the already
    // materialized a -- and the underlying source t is scanned exactly once.
    val reads = new AtomicInteger(0)
    val view = idScoreView(reads)
    val cat = catalogWith("t", view)
    val q = SqlEngine.execute(
      "WITH a AS (SELECT id, score FROM t), " +
        "b AS (SELECT id, score FROM a) " +
        "SELECT x.id FROM b x JOIN b y ON x.id = y.id JOIN a z ON x.id = z.id " +
        "ORDER BY x.id",
      cat, ExecutionOptions.Default)
    val rows = collectAllRows(q)
    assertEquals(Seq(1L, 2L, 3L, 4L), rows.map(r => asLong(r("id"))))
    assertEquals(
      s"source read ${reads.get()} times, expected ${view.numPartitions} (once per partition)",
      view.numPartitions, reads.get())
  }

  // ---------------------------------------------------------------------------
  // 6. A CTE shared across both arms of a UNION is materialized once.
  // ---------------------------------------------------------------------------

  @Test def unionArmsSharingCteReadSourceOnce(): Unit = {
    // `c` is referenced once per UNION arm => 2 refs total => materialized once;
    // both arms scan the shared MV, so the source is read once.
    val reads = new AtomicInteger(0)
    val view = idScoreView(reads)
    val cat = catalogWith("t", view)
    val q = SqlEngine.execute(
      "WITH c AS (SELECT id, score FROM t) " +
        "SELECT id FROM c WHERE score < 25 " +
        "UNION ALL SELECT id FROM c WHERE score >= 25 " +
        "ORDER BY id",
      cat, ExecutionOptions.Default)
    val rows = collectAllRows(q)
    assertEquals(Seq(1L, 2L, 3L, 4L), rows.map(r => asLong(r("id"))))
    assertEquals(
      s"source read ${reads.get()} times, expected ${view.numPartitions} (once per partition)",
      view.numPartitions, reads.get())
  }

  // ---------------------------------------------------------------------------
  // 7. COUNT(*) over a materialized CTE hits the metadata fast-path.
  // ---------------------------------------------------------------------------

  /** Count `CountStarMetadataExec` nodes in a physical plan — the marker that
    * `SELECT COUNT(*)` answered from `CatalogView.exactRowCount` (here a
    * MaterializedView's row count) without scanning any data. */
  private def countMetadataFastPaths(p: PhysicalPlan): Int = p match {
    case _: CountStarMetadataExec => 1
    case UnionExec(l, r)          => countMetadataFastPaths(l) + countMetadataFastPaths(r)
    case ProjectExec(c, _)        => countMetadataFastPaths(c)
    case FilterExec(c, _)         => countMetadataFastPaths(c)
    case LocalLimitExec(c, _)     => countMetadataFastPaths(c)
    case GlobalLimitExec(c, _)    => countMetadataFastPaths(c)
    case _                        => 0
  }

  @Test def countStarOverMaterializedCteUsesMetadataFastPath(): Unit = {
    // `c` is referenced twice (both UNION arms) -> materialized once into a
    // MaterializedView, which reports exactRowCount. Each `SELECT COUNT(*) FROM c`
    // therefore collapses to a CountStarMetadataExec that returns the row count
    // from MV metadata -- no scan of the materialized data.
    val sql =
      "WITH c AS (SELECT id, score FROM t) " +
        "SELECT COUNT(*) AS n FROM c UNION ALL SELECT COUNT(*) AS n FROM c"

    // Behavioral: both arms report the CTE's row count (4).
    val cat = catalogWith("t", idScoreView(new AtomicInteger(0)))
    val rows = collectAllRows(SqlEngine.execute(sql, cat, ExecutionOptions.Default))
    assertEquals(Seq(4L, 4L), rows.map(r => asLong(r("n"))))

    // White-box: both COUNT(*) arms planned to the metadata fast-path over the MV.
    val cat2 = catalogWith("t", idScoreView(new AtomicInteger(0)))
    val resolved = CteResolver.resolve(LogicalBuilder.build(sql, cat2), ExecutionOptions.Default)
    val physical = PhysicalPlanner.plan(LogicalOptimizer.optimize(resolved), ExecutionOptions.Default)
    assertEquals("both COUNT(*) arms over the materialized CTE must hit the metadata fast-path",
      2, countMetadataFastPaths(physical))
  }
}
