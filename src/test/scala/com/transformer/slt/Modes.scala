package com.transformer.slt

import com.transformer.core._
import com.transformer.fuzz.DataGen
import com.transformer.read.parquet.ParquetReader
import com.transformer.sql.exec.{CteResolver, PhysicalPlanner, SqlEngine}
import com.transformer.sql.plan.{LogicalBuilder, LogicalOptimizer}
import com.transformer.write.parquet.ParquetWriter

import java.nio.file.{Files, Path}
import scala.collection.mutable

/** The ways one query is run, DuckDB's query verification and Spark's
  * configuration dimensions in one list: every query of a script runs under
  * the [[Modes.Baseline]] and then under each of [[Modes.Verification]], and
  * every run must print the same result (or fail with the same message).
  *
  * A mode varies what the query text cannot: the tables' physical layout (one
  * row per batch, several partitions with empty ones between, parquet files
  * read back through the reader's pushdown), the execution options (spill at a
  * one-byte threshold, metrics), and how much of the logical optimizer runs
  * (none of it, or each pass alone). The planner's JVM-wide gates (sharding,
  * the pool's parallelism, the batch capacity every operator emits) are read
  * once per JVM, so they are the separate test targets that run these scripts
  * with `jvm_flags`, not modes here.
  */
object Modes {

  sealed trait Layout
  object Layout {
    /** One partition of default-sized batches. */
    case object Single extends Layout
    /** `partitions` partitions of `batchRows`-row batches; with `gaps`, the
      * rows sit in every other partition, so empty ones lead, fall between
      * and trail. */
    final case class Split(partitions: Int, batchRows: Int, gaps: Boolean) extends Layout
    /** Each table written as `files` parquet files and read back by the
      * parquet reader, which prunes the columns a query does not read and
      * pushes its comparisons into the row-group filter. */
    final case class Parquet(files: Int) extends Layout
    /** Each table's columns in reverse order with a column of NULLs before
      * each, DuckDB's `verification_projection`: a query names the columns it
      * reads, so its answer cannot change unless the engine reads one by its
      * position. A query that selects `*` is not run in it, since the star
      * returns the columns in their stored order ([[Mode.runs]]). */
    case object Permuted extends Layout
  }

  sealed trait Optimizer
  object Optimizer {
    case object Full extends Optimizer
    case object Off extends Optimizer
    case object FilterPushdownOnly extends Optimizer
    case object ProjectionPushdownOnly extends Optimizer
  }

  final case class Mode(name: String, layout: Layout, options: ExecutionOptions, optimizer: Optimizer) {
    /** Whether `sql` runs in this mode. */
    def runs(sql: String): Boolean = layout != Layout.Permuted || !Lexer.selectsStar(sql)
  }

  private val Spill = ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L))

  val Baseline: Mode = Mode("baseline", Layout.Single, ExecutionOptions.Default, Optimizer.Full)

  val Verification: Seq[Mode] = Seq(
    Mode("one-row-batches", Layout.Split(1, 1, gaps = false), ExecutionOptions.Default, Optimizer.Full),
    Mode("4-partitions", Layout.Split(4, 2, gaps = false), ExecutionOptions.Default, Optimizer.Full),
    Mode("empty-partitions", Layout.Split(7, 3, gaps = true), ExecutionOptions.Default, Optimizer.Full),
    Mode("spill", Layout.Single, Spill, Optimizer.Full),
    Mode("spill/3-partitions", Layout.Split(3, 2, gaps = false), Spill, Optimizer.Full),
    Mode("metrics", Layout.Split(2, 3, gaps = false), ExecutionOptions(metricsEnabled = true), Optimizer.Full),
    Mode("parquet", Layout.Parquet(3), ExecutionOptions.Default, Optimizer.Full),
    Mode("permuted-columns", Layout.Permuted, ExecutionOptions.Default, Optimizer.Full),
    Mode("optimizer-off", Layout.Single, ExecutionOptions.Default, Optimizer.Off),
    Mode("filter-pushdown-only", Layout.Split(2, 2, gaps = false), ExecutionOptions.Default, Optimizer.FilterPushdownOnly),
    Mode("projection-pushdown-only", Layout.Split(2, 2, gaps = false), ExecutionOptions.Default, Optimizer.ProjectionPushdownOnly))

  /** Run `sql` over `catalog` under `mode`. The plan is built eagerly, as
    * `SqlEngine.execute` builds it, so a refusal throws here and a failure on
    * the data throws while the result is drained. */
  def execute(sql: String, catalog: Catalog, mode: Mode): ExecutedQuery = mode.optimizer match {
    case Optimizer.Full => SqlEngine.execute(sql, catalog, mode.options)
    case partial =>
      val built = LogicalBuilder.build(sql, catalog)
      val resolved = CteResolver.resolve(built, mode.options)
      val optimized = partial match {
        case Optimizer.FilterPushdownOnly => LogicalOptimizer.filterPushdown(resolved)
        case Optimizer.ProjectionPushdownOnly => LogicalOptimizer.projectionPushdown(resolved)
        case _ => resolved
      }
      val physical = PhysicalPlanner.plan(optimized, mode.options)
      PhysicalPlanner.preMaterializeExchanges(physical)
      new ExecutedQuery(physical.outputSchema, (0 until physical.numPartitions).map(p => physical.execute(p)))
  }

  /** The views a mode's catalog holds for a database's tables, built once
    * per table version and layout: a script's later queries over an unchanged
    * table reuse them, and a table's earlier versions are dropped once a later
    * one is read. Parquet files live under one directory, removed by
    * [[close]]. */
  final class Views extends AutoCloseable {
    private val cache = mutable.HashMap.empty[(String, Long, Layout), CatalogView]
    private var parquetDir: Option[Path] = None

    private def dir: Path = parquetDir.getOrElse {
      val d = Files.createTempDirectory("slt-parquet-")
      parquetDir = Some(d)
      d
    }

    def catalog(tables: Seq[Database.Table], layout: Layout): Catalog = {
      val c = new Catalog
      tables.foreach { t => c.register(t.name, view(t, layout)) }
      c
    }

    private def view(t: Database.Table, layout: Layout): CatalogView = {
      val name = t.name.toLowerCase(java.util.Locale.ROOT)
      cache.filterInPlace { case ((n, v, _), _) => n != name || v == t.version }
      cache.getOrElseUpdate((name, t.version, layout), build(t, layout))
    }

    private def build(t: Database.Table, layout: Layout): CatalogView = {
      val data = DataGen.Dataset(t.schema, t.rows)
      layout match {
        case Layout.Single => data.singlePartition()
        case Layout.Split(p, b, false) => data.evenPartitions(p, b)
        case Layout.Split(p, b, true) => data.withEmptyPartitions(p, b)
        case Layout.Permuted =>
          val n = t.schema.length
          val fields = (0 until n).reverse.flatMap { c =>
            Seq(Field(s"slt_pad_${t.name}_$c", DataType.IntType), t.schema.fields(c))
          }
          val rows = t.rows.map { r =>
            val out = new Array[Any](2 * n)
            (0 until n).foreach(c => out(2 * (n - 1 - c) + 1) = r(c))
            out
          }
          DataGen.Dataset(Schema(fields.toVector), rows).evenPartitions(2, 3)
        case Layout.Parquet(files) =>
          // Parquet stores no NULL type; such a table is read as it is held.
          if (t.schema.fields.exists(_.dataType == DataType.NullType)) data.singlePartition()
          else {
            // A version belongs to one table, so it names the directory.
            val tableDir = Files.createDirectories(dir.resolve(s"v${t.version}"))
            val view = data.evenPartitions(files, 2)
            val paths = (0 until view.numPartitions).map { p =>
              val path = tableDir.resolve(f"part-$p%05d.parquet")
              ParquetWriter.writeAll(path, t.schema, view.readPartition(p), Map("parquet_row_group_size" -> "1024"))
              path
            }
            ParquetReader.fromFiles(paths, batchSize = 2, targetBytesPerPartition = 1L)
          }
      }
    }

    def close(): Unit = {
      cache.clear()
      parquetDir.foreach { d =>
        val walk = Files.walk(d)
        try walk.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
        finally walk.close()
      }
      parquetDir = None
    }
  }
}
