package com.transformer.fuzz.oracle

import com.transformer.core.{Catalog, ColumnarBatch, ExecutedQuery, ExecutionOptions, Schema}
import com.transformer.sql.exec.SqlEngine

import scala.collection.mutable
import scala.util.control.NonFatal

/** Shared engine-run helpers for the metamorphic oracles (`Tlp`, `NoRec`,
  * `MetaModeDifferential`, `JoinCommutativity`, `AggDecomposition`): run a SQL
  * string against a catalog and collect its rows, plus the common [[Verdict]] the
  * oracles return.
  *
  * A query the engine REJECTS at bind/plan time (an `IllegalArgumentException`
  * from the generator's own SQL) is not a finding — the oracles return
  * [[Rejected]] and the caller counts it toward the bind-reject rate. A query
  * that has nothing to check (e.g. TLP with no type-reliable output column to
  * partition on) returns [[Skipped]]. Only a successfully-planned query whose
  * metamorphic relation fails throws `AssertionError`, which the harness
  * minimizes.
  */
object RelEngine {

  sealed trait Verdict
  /** The metamorphic relation held. */
  case object Held extends Verdict
  /** Nothing to check for this case. */
  case object Skipped extends Verdict
  /** The engine rejected the generated SQL at bind/plan time. */
  final case class Rejected(reason: String) extends Verdict

  /** Run `sql` against `catalog` under `opts`. */
  def execute(catalog: Catalog, sql: String, opts: ExecutionOptions): ExecutedQuery =
    SqlEngine.execute(sql, catalog, opts)

  /** Run `sql` and materialize every output row as an `Array[Any]` aligned to the
    * result schema (SQL NULL as `null`). */
  def runRows(catalog: Catalog, sql: String, opts: ExecutionOptions): Vector[Array[Any]] =
    collectRows(execute(catalog, sql, opts))

  def collectRows(q: ExecutedQuery): Vector[Array[Any]] = {
    val ncols = q.schema.length
    val buf = mutable.ArrayBuffer.empty[Array[Any]]
    val it = q.batches
    while (it.hasNext) {
      val b: ColumnarBatch = it.next()
      var r = 0
      while (r < b.numRows) {
        val arr = new Array[Any](ncols)
        var c = 0
        while (c < ncols) {
          arr(c) = if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)
          c += 1
        }
        buf += arr
        r += 1
      }
    }
    buf.toVector
  }

  /** Read a single `LONG`/`INT`-valued one-row, one-column result, mapping a NULL
    * cell (e.g. `SUM` over an empty relation) to `None`. */
  def scalarLong(rows: Vector[Array[Any]]): Option[Long] =
    rows.headOption.flatMap(r => Option(r(0))).map(_.asInstanceOf[Number].longValue)

  /** A query's answer: its schema and every row it returned. */
  final case class Answer(schema: Schema, rows: Vector[Array[Any]])

  /** `sql` run under `opts` to its last row, or the message of the failure on
    * its data it stopped with (`CleanRejection.isDataError`), whether the
    * drain met it or planning did: a global aggregate runs as its query is
    * planned. A rejection at bind or plan time is thrown, as the
    * `IllegalArgumentException` it is, and so is any other failure. `after`
    * sees the query once it is drained. */
  def answer(catalog: Catalog, sql: String, opts: ExecutionOptions,
      after: ExecutedQuery => Unit = _ => ()): Either[String, Answer] =
    try {
      val q = execute(catalog, sql, opts)
      val rows = collectRows(q)
      after(q)
      Right(Answer(q.schema, rows))
    } catch { case NonFatal(e) if CleanRejection.isDataError(e) => Left(dataMessage(e)) }

  /** The message of the failure about a value `e` carries, itself or a
    * cause. */
  def dataMessage(e: Throwable): String =
    CleanRejection.causes(e).map(_.getMessage).find(CleanRejection.isDataMessage).getOrElse(String.valueOf(e.getMessage))

  def rejectReason(e: IllegalArgumentException): String = {
    val m = Option(e.getMessage).getOrElse(e.getClass.getName)
    if (m.length <= 200) m else m.take(200) + "..."
  }
}
