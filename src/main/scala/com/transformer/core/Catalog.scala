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
