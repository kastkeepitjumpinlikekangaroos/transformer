package com.transformer.core

import java.util.concurrent.atomic.LongAdder

import com.transformer.core.metrics.QueryMetrics

/** Boundary between the job runner and the SQL engine. Implementations live in
  * the `sql` module. The job runner gets one via [[SqlExecutorRegistry]].
  *
  * Two overloads:
  *   - `execute(sql, catalog, opts)` is the primary; concrete implementations
  *     must override this and may consult [[ExecutionOptions]] to enable
  *     spill, set per-operator thresholds, etc.
  *   - `execute(sql, catalog)` is a `final` shim that delegates with
  *     [[ExecutionOptions.Default]]. Callers that don't carry per-task config
  *     (GUI SQL Console, ad-hoc tests) use it without thinking about opts.
  */
trait SqlExecutor {
  def execute(sql: String, catalog: Catalog, opts: ExecutionOptions): ExecutedQuery

  final def execute(sql: String, catalog: Catalog): ExecutedQuery =
    execute(sql, catalog, ExecutionOptions.Default)
}

/** Result of running one SQL statement.
  *
  * The executor produces one logical *partition* per independent stream of
  * [[ColumnarBatch]]es (e.g. one input file, one Parquet row group). Callers can
  * either drain everything sequentially via [[batches]] or consume partitions
  * individually via [[partition]] — the latter is what the multi-file writer uses
  * to fan out one output file per partition in parallel.
  *
  * `rowsProduced` is filled in lazily as batches are pulled; it is safe to
  * read from any thread once all partitions have been drained (it uses a
  * [[LongAdder]] internally).
  *
  * Metrics: when the executor was invoked with
  * [[ExecutionOptions.metricsEnabled]] = true, [[metricsSnapshot]] returns
  * a fresh [[QueryMetrics]] computed from the underlying operator tree —
  * call after the partitions have been drained so the counter values are
  * final. Returns None when metrics were disabled.
  *
  * Its consumer closes it ([[close]]) once it stops reading, however it
  * stops: a query that was read to its end has released what it opened, and a
  * read that fails or is stopped closes its partition and every partition not
  * yet read as the failure goes by, but one read in part holds its files and
  * spill directories until it is closed ([[Closing]]).
  */
final class ExecutedQuery(
    val schema: Schema,
    partitionSources: IndexedSeq[Iterator[ColumnarBatch]]
) extends AutoCloseable {
  private val _rows = new LongAdder()

  /** Whether each partition has been read, or closed unread. */
  private val started = Array.fill(partitionSources.length)(new java.util.concurrent.atomic.AtomicBoolean(false))

  /** A read of a partition failed or was stopped: the partitions no one has
    * read yet are closed, as nothing will read them now. One being read on
    * another thread is its reader's to close, or [[close]]'s. */
  private def failed(): Unit =
    partitionSources.indices.foreach { j =>
      if (started(j).compareAndSet(false, true)) Closing.close(partitionSources(j))
    }

  // Lazy snapshot supplier. The captured operator tree is mutable and only
  // settles after the partition iterators have been drained, so we don't
  // freeze the snapshot at executor construction time.
  @volatile private var _metricsSupplier: () => QueryMetrics = null

  def numPartitions: Int = partitionSources.length

  /** Counted view of partition `i`. The underlying source iterator is consumed
    * lazily; consuming partitions from multiple threads concurrently is safe so
    * long as each partition is drained by exactly one thread (the underlying
    * physical-plan iterator is not itself thread-safe).
    */
  def partition(i: Int): Iterator[ColumnarBatch] = new Iterator[ColumnarBatch] with AutoCloseable {
    private val src = partitionSources(i)
    // A read that fails ends the partition, and the query: it is closed, and
    // so is every partition no one has read yet, and the failure goes on.
    override def hasNext: Boolean = {
      started(i).set(true)
      try src.hasNext catch { case t: Throwable => close(); failed(); throw t }
    }
    override def next(): ColumnarBatch = {
      started(i).set(true)
      val b = try src.next() catch { case t: Throwable => close(); failed(); throw t }
      _rows.add(b.numRows.toLong)
      b
    }
    def close(): Unit = Closing.close(src)
  }

  /** Release what every partition holds, read or not: the files its scans
    * read and the spill directories its operators wrote. Call it once the
    * query is read, however reading it ended; a second call does nothing. */
  def close(): Unit = partitionSources.foreach(Closing.close)

  /** Flat view across all partitions, in partition order. Useful for callers that
    * don't care about partition boundaries (validation drains, in-memory
    * materialization, etc.).
    */
  def batches: Iterator[ColumnarBatch] =
    (0 until numPartitions).iterator.flatMap(partition)

  def rowsProduced: Long = _rows.sum()

  /** Hook the SQL engine uses to attach a metrics snapshot supplier. The
    * supplier is called on demand by [[metricsSnapshot]]; consumers should
    * call it AFTER the partition iterators have been drained so the counter
    * values are final. Public so unit tests can verify metrics plumbing
    * without an end-to-end DataJob run; not part of the user-facing API.
    */
  def attachMetrics(supplier: () => QueryMetrics): Unit = {
    _metricsSupplier = supplier
  }

  /** Snapshot of per-query metrics. Returns None when metrics were disabled
    * for this execution (`opts.metricsEnabled = false`). When enabled, each
    * call materializes a fresh snapshot from the live operator-metrics tree
    * — callers that snapshot mid-drain see partial counts and that is
    * intentional. */
  def metricsSnapshot: Option[QueryMetrics] = {
    val s = _metricsSupplier
    if (s == null) None else Some(s())
  }
}

object ExecutedQuery {
  /** Convenience for callers that have a single, already-flattened iterator. */
  def single(schema: Schema, batches: Iterator[ColumnarBatch]): ExecutedQuery =
    new ExecutedQuery(schema, IndexedSeq(batches))
}

/** Global registry so the job module can find an executor at runtime without
  * depending on the sql module at compile time. The sql module's entry point
  * installs itself here.
  */
object SqlExecutorRegistry {
  @volatile private var executor: Option[SqlExecutor] = None

  def install(e: SqlExecutor): Unit = { executor = Some(e) }

  def get: SqlExecutor = executor.getOrElse(
    throw new IllegalStateException(
      "No SqlExecutor is installed. Ensure com.transformer.sql.exec.SqlEngine is on the classpath " +
        "and was initialized (depend on the //src/main/scala/com/transformer/sql/exec:exec target)."
    )
  )

  def isInstalled: Boolean = executor.isDefined

  /** For tests: clear the installed executor. */
  def reset(): Unit = { executor = None }
}
