package com.transformer.core

import java.util.concurrent.Callable

/** A [[CatalogView]] backed by [[ColumnarBatch]]es already held in memory.
  *
  * Each entry of `partitionBatches` is one partition; iterating that entry
  * replays its batches without doing any I/O. Constructed by
  * [[MaterializedView.materializeInParallel]] after eager input loading.
  */
final class MaterializedView(
    val schema: Schema,
    private val partitionBatches: IndexedSeq[IndexedSeq[ColumnarBatch]]
) extends CatalogView {

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
    if (n == 0) return new MaterializedView(view.schema, IndexedSeq.empty)
    val tasks: Seq[Callable[IndexedSeq[ColumnarBatch]]] = (0 until n).map { p =>
      new Callable[IndexedSeq[ColumnarBatch]] {
        def call(): IndexedSeq[ColumnarBatch] = drain(view.readPartition(p))
      }
    }
    val partitions = Scheduler.submitAndAwaitAll(tasks)
    new MaterializedView(view.schema, partitions)
  }

  /** Drain many views concurrently on the shared [[Scheduler]] pool. All input
    * partitions across all views are submitted as independent tasks so the
    * cores-sized pool stays saturated even with mixes of small + large inputs.
    * Returns one [[MaterializedView]] per input in the original order.
    */
  def materializeManyInParallel(views: Seq[CatalogView]): IndexedSeq[MaterializedView] = {
    val perViewFutures: IndexedSeq[(Schema, IndexedSeq[java.util.concurrent.ForkJoinTask[IndexedSeq[ColumnarBatch]]])] =
      views.toIndexedSeq.map { view =>
        val n = view.numPartitions
        val fs = (0 until n).map { p =>
          Scheduler.submit(new Callable[IndexedSeq[ColumnarBatch]] {
            def call(): IndexedSeq[ColumnarBatch] = drain(view.readPartition(p))
          })
        }
        (view.schema, fs)
      }
    perViewFutures.map { case (schema, fs) =>
      new MaterializedView(schema, fs.map(_.get()))
    }
  }

  private def drain(it: Iterator[ColumnarBatch]): IndexedSeq[ColumnarBatch] = {
    val buf = scala.collection.mutable.ArrayBuffer.empty[ColumnarBatch]
    while (it.hasNext) buf += it.next()
    buf.toIndexedSeq
  }
}
