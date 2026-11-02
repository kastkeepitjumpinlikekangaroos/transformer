package com.transformer.core

import scala.util.control.NonFatal

/** The close protocol (decision 4 of plans/testing/17): what a query opens, a
  * file a reader reads or a spill directory an operator writes, is released
  * when its consumer stops reading it, whether it read every batch, stopped
  * early (a LIMIT), or failed or was stopped part way.
  *
  *   - An iterator of batches that holds something to release, itself or
  *     upstream of it, is `AutoCloseable`: `close()` releases it whether or not
  *     it was drained, and a second close does nothing. A reader closes its file
  *     when it runs out, too.
  *   - An operator that reads an input closes it when it stops reading early:
  *     a LIMIT once it has its rows ([[Closing.limited]]), a breaker's pool task
  *     however its drain ends ([[Closing.draining]]); and its own output closes
  *     its inputs ([[Closing.over]]), so closing a query's output closes the
  *     plan under it.
  *   - A query's consumer closes it: `ExecutedQuery.close()`, which the job
  *     runner, the GUI's query console, the data viewer and `transformer
  *     query` call, however their drain ends.
  *
  * Before it, a LIMIT, a failing drain and a stopped query left their files
  * open until garbage collection and their spill directories until the JVM
  * exited, which in the GUI, the data viewer or a shell accumulates;
  * `--config=leaks` ([[LeakAudit]]) holds every test to it.
  */
object Closing {

  /** Release what `it` holds, when it holds anything. A failure to release
    * is not the caller's to handle: it is swallowed, as a close in a
    * `finally` must not hide the failure that got there. */
  def close(it: Iterator[_]): Unit = it match {
    case c: AutoCloseable => try c.close() catch { case NonFatal(_) => () }
    case _ => ()
  }

  /** `derived`, which reads `sources` (and may hold something itself), as an
    * iterator whose `close()` closes it and them. Returns `derived` itself
    * when nothing in it or them is closeable. */
  def over(derived: Iterator[ColumnarBatch], sources: Iterator[_]*): Iterator[ColumnarBatch] = {
    val held = (derived +: sources).collect { case c: AutoCloseable => c }
    if (held.isEmpty) derived
    else new Releasing(derived, () => held.foreach(c => try c.close() catch { case NonFatal(_) => () }))
  }

  /** `inner`, whose `close()` runs `release` once, and closes `inner` too. */
  def releasing(inner: Iterator[ColumnarBatch])(release: => Unit): Iterator[ColumnarBatch] =
    new Releasing(inner, () => { close(inner); release })

  /** Run `body` over `it`, then close `it`, however `body` ends: a pool
    * task that drains an input, which a failure part way would leave open. */
  def draining[A](it: Iterator[ColumnarBatch])(body: Iterator[ColumnarBatch] => A): A =
    try body(it) finally close(it)

  /** The batches of each of `parts` in turn, each made when the one before it
    * runs out, so none is open before it is read: `close()` closes the one
    * being read. */
  def concat(parts: Iterator[() => Iterator[ColumnarBatch]]): Iterator[ColumnarBatch] =
    new Concat(parts)

  /** The first `n` rows of `it`, the batch that crosses the boundary cut to
    * fit: once it has them it closes `it`, which it reads no more. */
  def limited(it: Iterator[ColumnarBatch], n: Long): Iterator[ColumnarBatch] = new Limited(it, n)

  private final class Releasing(inner: Iterator[ColumnarBatch], release: () => Unit)
      extends Iterator[ColumnarBatch] with AutoCloseable {
    private var released = false
    def hasNext: Boolean = inner.hasNext
    def next(): ColumnarBatch = inner.next()
    def close(): Unit = if (!released) { released = true; release() }
  }

  private final class Concat(parts: Iterator[() => Iterator[ColumnarBatch]])
      extends Iterator[ColumnarBatch] with AutoCloseable {
    private var current: Iterator[ColumnarBatch] = Iterator.empty
    private var closed = false
    def hasNext: Boolean = {
      while (!closed && !current.hasNext && parts.hasNext) {
        Closing.close(current)
        current = parts.next()()
      }
      !closed && current.hasNext
    }
    def next(): ColumnarBatch = {
      if (!hasNext) throw new NoSuchElementException("no batch left")
      current.next()
    }
    def close(): Unit = if (!closed) { closed = true; Closing.close(current) }
  }

  private final class Limited(it: Iterator[ColumnarBatch], n: Long)
      extends Iterator[ColumnarBatch] with AutoCloseable {
    private var remaining = n
    private var closed = false
    def hasNext: Boolean = {
      if (remaining <= 0) close()
      !closed && it.hasNext
    }
    def next(): ColumnarBatch = {
      if (!hasNext) throw new NoSuchElementException("no batch left")
      val b = it.next()
      val out =
        if (b.numRows <= remaining) {
          remaining -= b.numRows
          b
        } else {
          val mask = new Array[Boolean](b.numRows)
          var i = 0
          while (i < remaining.toInt) { mask(i) = true; i += 1 }
          remaining = 0
          b.select(mask)
        }
      // Its rows are all here: the input is read no more.
      if (remaining <= 0) close()
      out
    }
    def close(): Unit = if (!closed) { closed = true; Closing.close(it) }
  }
}
