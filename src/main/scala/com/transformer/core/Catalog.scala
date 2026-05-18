package com.transformer.core

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

/** A view in the catalog: a named source of [[ColumnarBatch]] iterators with a known [[Schema]]. */
trait CatalogView {
  def schema: Schema
  def numPartitions: Int

  /** Read partition `p`. Each partition is an independent [[ColumnarBatch]] iterator —
    * a unit of parallelism for the executor.
    */
  def readPartition(p: Int): Iterator[ColumnarBatch]

  /** Exact row count if the view can produce it without decoding any column data.
    * Parquet returns the sum of per-row-group counts from the footer; in-memory
    * views return their materialized row count. CSV-style views return None.
    *
    * Used by the SQL planner to short-circuit `SELECT COUNT(*) FROM <view>` —
    * no scan, no aggregate pipeline, one row produced directly.
    */
  def exactRowCount: Option[Long] = None

  /** Optional column projection. If supported, returns a view that decodes only
    * the named columns; its [[schema]] contains exactly those columns in the
    * order they appear in *this* view's schema (callers don't get to reorder).
    * Returning None means the view can't prune — callers fall back to a full
    * scan.
    *
    * Used by the SQL planner's column-pruning pass: scans under analytical
    * queries skip the wide JSON-blob columns the query never references.
    * Parquet implements it via `parquet.read.schema`; row-oriented views
    * (CSV today) leave the default None.
    *
    * `names` must be a subset of this view's [[schema.fieldNames]]. Order in
    * `names` is ignored; the returned view's schema follows the original
    * column order so already-bound ColRefExpr indices remain consistent
    * after a single name → new-index lookup.
    */
  def withProjectedColumns(names: Seq[String]): Option[CatalogView] = None
}

/** Registry of named views available to SQL execution. View lookups are case-insensitive. */
final class Catalog {
  private val views = new ConcurrentHashMap[String, CatalogView]()

  def register(name: String, view: CatalogView): Unit = {
    val key = name.toLowerCase
    if (views.putIfAbsent(key, view) != null) {
      throw new IllegalStateException(s"View '$name' is already registered")
    }
  }

  def replace(name: String, view: CatalogView): Unit = {
    views.put(name.toLowerCase, view)
  }

  def remove(name: String): Unit = { views.remove(name.toLowerCase); () }

  def get(name: String): Option[CatalogView] = Option(views.get(name.toLowerCase))

  def apply(name: String): CatalogView =
    get(name).getOrElse(throw new IllegalArgumentException(
      s"View '$name' is not registered. Known views: [${viewNames.mkString(", ")}]"
    ))

  def viewNames: Seq[String] = views.keys.asScala.toSeq.sorted

  def isEmpty: Boolean = views.isEmpty
}
