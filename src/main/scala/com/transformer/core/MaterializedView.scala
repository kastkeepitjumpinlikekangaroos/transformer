package com.transformer.core

import java.util.concurrent.Callable

/** A [[CatalogView]] backed by [[ColumnarBatch]]es already held in memory.
  *
  * Each entry of `partitionBatches` is one partition; iterating that entry
  * replays its batches without doing any I/O. Constructed by
  * [[MaterializedView.materializeInParallel]] after eager input loading.
  *
  * Its strings are text ([[CatalogView.stringsAreText]]): one built in Scala,
  * through the public constructor, refuses a STRING holding half a surrogate
  * pair, naming its column and row; one the engine builds holds what a query
  * or a view of text gave it.
  */
final class MaterializedView private (
    val schema: Schema,
    private val partitionBatches: IndexedSeq[IndexedSeq[ColumnarBatch]],
    checked: Boolean
) extends CatalogView {

  /** A view of `partitionBatches`, one entry a partition, built in Scala: a
    * STRING in them that holds half a surrogate pair is refused. */
  def this(schema: Schema, partitionBatches: IndexedSeq[IndexedSeq[ColumnarBatch]]) =
    this(schema, partitionBatches, checked = false)

  if (!checked) partitionBatches.foreach(_.foreach(b => BatchInvariants.requireText(b, "a view built in Scala")))

  override def stringsAreText: Boolean = true

  def numPartitions: Int = partitionBatches.length

  def readPartition(p: Int): Iterator[ColumnarBatch] = {
    require(p >= 0 && p < partitionBatches.length,
      s"partition $p out of range [0,${partitionBatches.length})")
    partitionBatches(p).iterator
  }

  def totalRows: Long = {
    var sum = 0L
    var i = 0
    while (i < partitionBatches.length) {
      val parts = partitionBatches(i)
      var j = 0
      while (j < parts.length) { sum += parts(j).numRows.toLong; j += 1 }
      i += 1
    }
    sum
  }

  override def exactRowCount: Option[Long] = Some(totalRows)
}

object MaterializedView {

  /** Drain a single view into a [[MaterializedView]] by reading each partition
    * concurrently on the shared [[Scheduler]] pool. Partitions are submitted in
    * order so the resulting view preserves the source's partition layout.
    */
  def materializeInParallel(view: CatalogView): MaterializedView = {
    val n = view.numPartitions
    if (n == 0) return new MaterializedView(view.schema, IndexedSeq.empty, checked = true)
    val tasks: Seq[Callable[IndexedSeq[ColumnarBatch]]] = (0 until n).map { p =>
      new Callable[IndexedSeq[ColumnarBatch]] {
        def call(): IndexedSeq[ColumnarBatch] = drain(view.readPartition(p))
      }
    }
    val partitions = Scheduler.submitAndAwaitAll(tasks)
    new MaterializedView(view.schema, partitions, checked = view.stringsAreText)
  }

  /** Drain a finished query into a [[MaterializedView]], one pool task per
    * output partition, preserving the query's partition layout.
    *
    * This is how a result that was never written to disk becomes readable
    * again: an [[ExecutedQuery]]'s partition iterators are single-use, so a
    * consumer that needs to read the result more than once (a validation, a
    * downstream task) has to hold it somewhere. Draining through
    * `q.partition(p)` also keeps [[ExecutedQuery.rowsProduced]] accurate, so
    * the caller can still report the row count afterwards.
    *
    * The whole result is held in heap for as long as the view is referenced —
    * the same trade the `cache = true` input path and CTE materialization make.
    */
  def fromQuery(q: ExecutedQuery): MaterializedView = {
    val n = q.numPartitions
    if (n == 0) return new MaterializedView(q.schema, IndexedSeq.empty, checked = true)
    val tasks: Seq[Callable[IndexedSeq[ColumnarBatch]]] = (0 until n).map { p =>
      new Callable[IndexedSeq[ColumnarBatch]] {
        def call(): IndexedSeq[ColumnarBatch] = drain(q.partition(p))
      }
    }
    // A query's strings are text: its scans and literals refuse anything else.
    new MaterializedView(q.schema, Scheduler.submitAndAwaitAll(tasks), checked = true)
  }

  /** Drain many views concurrently on the shared [[Scheduler]] pool. All input
    * partitions across all views are submitted as independent tasks so the
    * cores-sized pool stays saturated even with mixes of small + large inputs.
    * Returns one [[MaterializedView]] per input in the original order.
    */
  def materializeManyInParallel(views: Seq[CatalogView]): IndexedSeq[MaterializedView] = {
    val perViewFutures: IndexedSeq[(CatalogView, IndexedSeq[Scheduler.Submitted[IndexedSeq[ColumnarBatch]]])] =
      views.toIndexedSeq.map { view =>
        val n = view.numPartitions
        val fs = (0 until n).map { p =>
          Scheduler.submit(new Callable[IndexedSeq[ColumnarBatch]] {
            def call(): IndexedSeq[ColumnarBatch] = drain(view.readPartition(p))
          })
        }
        (view, fs)
      }
    perViewFutures.map { case (view, fs) =>
      new MaterializedView(view.schema, fs.map(_.await()), checked = view.stringsAreText)
    }
  }

  /** Every batch of `it`, which is closed however the drain ends. */
  private def drain(it: Iterator[ColumnarBatch]): IndexedSeq[ColumnarBatch] = Closing.draining(it) { it =>
    val buf = scala.collection.mutable.ArrayBuffer.empty[ColumnarBatch]
    while (it.hasNext) buf += it.next()
    buf.toIndexedSeq
  }
}
