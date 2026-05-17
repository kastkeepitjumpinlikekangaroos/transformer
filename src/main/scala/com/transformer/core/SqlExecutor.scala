package com.transformer.core

/** Boundary between the job runner and the SQL engine. Implementations live in
  * the `sql` module. The job runner gets one via [[SqlExecutorRegistry]].
  */
trait SqlExecutor {
  def execute(sql: String, catalog: Catalog): ExecutedQuery
}

/** Result of running one SQL statement. The executor decides internal parallelism;
  * callers drain `batches` sequentially. `numRows` is filled in lazily as batches
  * are consumed.
  */
final class ExecutedQuery(val schema: Schema, source: Iterator[ColumnarBatch]) {
  private var _rows: Long = 0L
  private val it: Iterator[ColumnarBatch] = new Iterator[ColumnarBatch] {
    override def hasNext: Boolean = source.hasNext
    override def next(): ColumnarBatch = {
      val b = source.next()
      _rows += b.numRows
      b
    }
  }
  def batches: Iterator[ColumnarBatch] = it
  def rowsProduced: Long = _rows
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
