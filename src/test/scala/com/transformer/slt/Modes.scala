package com.transformer.slt

import com.transformer.core._
import com.transformer.fuzz.{DataGen, Rng}
import com.transformer.fuzz.oracle.{Eet, PlanRoundTrip}
import com.transformer.read.parquet.ParquetReader
import com.transformer.sql.exec.{CteResolver, NestedLoopGuardException, PhysicalPlanner, SqlEngine}
import com.transformer.sql.parse.NestingLimitException
import com.transformer.sql.plan.{LogicalBuilder, LogicalOptimizer, PlanSql}
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

  /** The text a mode runs: the query as written, or its plan printed as SQL
    * ([[PlanSql]]), DuckDB's re-parse verification, as it is or rewritten. */
  sealed trait Text
  object Text {
    case object AsWritten extends Text
    /** The query's plan printed, which must bind back to the same plan
      * ([[PlanRoundTrip.check]]), run in its place. */
    case object Reprinted extends Text
    /** The plan the optimizer makes of the query printed, and run with the
      * optimizer off: each pass's rewrite, read afresh by the binder. */
    case object OptimizedReprinted extends Text
    /** The query's plan with up to three subexpressions in forms that cannot
      * change their values ([[Eet]], EET's relation), under a seed the query's
      * text fixes, printed and run in its place. A rewrite can change the
      * order of rows an ORDER BY leaves tied, so the result is compared as a
      * multiset ([[Mode.unordered]]); the order is the baseline's to check. */
    case object Rewritten extends Text
  }

  final case class Mode(name: String, layout: Layout, options: ExecutionOptions, optimizer: Optimizer,
      text: Text = Text.AsWritten) {
    /** Whether `sql` runs in this mode: an EXPLAIN's text is the plan the
      * baseline makes, and a query selecting `*` returns the columns in their
      * stored order, which the permuted layout changes. */
    def runs(sql: String): Boolean =
      !Lexer.explains(sql) && (layout != Layout.Permuted || !Lexer.selectsStar(sql))

    /** Whether this mode's rows are compared with the baseline's as a
      * multiset rather than in their order. */
    def unordered: Boolean = text == Text.Rewritten
  }

  /** Spill at a one-byte threshold, so each batch an operator holds is a run
    * of its own, and with no limit on the runs: `spill_max_runs` stops a
    * workload whose runs never end, and a table here is a script's, the
    * largest Postgres's tenk1, whose 10,000 rows in two-row batches write about
    * 1,700 runs a partition in spill/3-partitions, past the default 1,000. */
  private val Spill = ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L), spillMaxRuns = Int.MaxValue)

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
    Mode("projection-pushdown-only", Layout.Split(2, 2, gaps = false), ExecutionOptions.Default, Optimizer.ProjectionPushdownOnly),
    Mode("reprinted", Layout.Split(2, 2, gaps = false), ExecutionOptions.Default, Optimizer.Full, Text.Reprinted),
    Mode("optimized-reprinted", Layout.Single, ExecutionOptions.Default, Optimizer.Off, Text.OptimizedReprinted),
    Mode("rewritten", Layout.Split(2, 2, gaps = false), ExecutionOptions.Default, Optimizer.Full, Text.Rewritten))

  /** Run `sql` over `catalog` under `mode`. The plan is built eagerly, as
    * `SqlEngine.execute` builds it, so a refusal throws here and a failure on
    * the data throws while the result is drained. A mode that reprints the
    * query runs its printed plan; a query whose printed plan nests past the
    * parser's limit runs as written. */
  def execute(sql: String, catalog: Catalog, mode: Mode): ExecutedQuery = mode.text match {
    case Text.AsWritten => executeText(sql, catalog, mode)
    case Text.Reprinted =>
      PlanRoundTrip.check(sql, catalog) match {
        case PlanRoundTrip.Same(printed) => executeText(printed, catalog, mode)
        case PlanRoundTrip.TooDeep(_) => executeText(sql, catalog, mode)
        case PlanRoundTrip.Unprintable(reason) => throw new IllegalStateException(s"The plan has no SQL: $reason")
      }
    case Text.OptimizedReprinted =>
      val printed = PlanRoundTrip.optimizedSql(sql, catalog).getOrElse(
        throw new IllegalStateException("The optimized plan has no SQL"))
      executeText(printed, catalog, mode)
    case Text.Rewritten => executeRewritten(sql, catalog, mode)
  }

  /** `sql` rewritten ([[Eet.rewrite]]) under a seed its text fixes, and run.
    * A query that reads RAND() or the clock runs as written, since two runs
    * of it may answer differently, and so does one whose parentheses nest
    * [[DeepParentheses]] deep, one whose rewritten text nests past the
    * parser's limit, and one whose rewritten text JSqlParser does not read
    * ([[Eet.unparsed]]: it reads three stacked parentheses in some chains and
    * not in others). A
    * rewritten condition can move a join off its keys, which the nested-loop
    * guard may refuse over a script's larger tables: the rewrite is drawn
    * again with every WHERE and ON condition kept. */
  private def executeRewritten(sql: String, catalog: Catalog, mode: Mode): ExecutedQuery = {
    val built = LogicalBuilder.build(sql, catalog)
    if (Eet.nondeterministic(sql, built) || parenthesesDeep(sql) >= DeepParentheses) return executeText(sql, catalog, mode)
    def attempt(keepConditions: Boolean): ExecutedQuery = {
      val rewritten = Eet.rewrite(built, new Rng(RewriteSeed ^ sql.hashCode.toLong), keepConditions = keepConditions)
      if (rewritten.rewrites.isEmpty) executeText(sql, catalog, mode)
      else {
        val printed =
          try rewritten.printed(catalog)
          catch {
            case e: PlanSql.UnprintablePlanException =>
              throw new IllegalStateException(s"The rewritten plan has no SQL: ${e.getMessage}")
          }
        try executeText(printed, catalog, mode)
        catch {
          case _: NestingLimitException => executeText(sql, catalog, mode)
          case e: IllegalArgumentException if Eet.unparsed(e) => executeText(sql, catalog, mode)
        }
      }
    }
    try attempt(keepConditions = false)
    catch { case _: NestedLoopGuardException => attempt(keepConditions = true) }
  }

  /** Mixed with a query's text into the seed its rewrite draws from. */
  private val RewriteSeed: Long = 0x5eedL

  /** How deep a query's parentheses may nest for the `rewritten` mode to
    * rewrite it. A form adds a CASE, an IF or a NOT to a condition, and
    * JSqlParser backtracks over that inside parentheses nested this deep,
    * for seconds and up to its time limit: SQLite's `index/` scripts' 200
    * such queries, 3% of those the mode rewrites, cost it a hundred seconds,
    * and any shallower took a few milliseconds. */
  private val DeepParentheses = 5

  /** How deep `sql`'s parentheses nest, outside quotes. */
  private def parenthesesDeep(sql: String): Int = {
    var depth, most = 0
    var quote: Char = 0
    sql.foreach { ch =>
      if (quote != 0) { if (ch == quote) quote = 0 }
      else if (ch == '\'' || ch == '"') quote = ch
      else if (ch == '(') { depth += 1; most = math.max(most, depth) }
      else if (ch == ')') depth -= 1
    }
    most
  }

  private def executeText(sql: String, catalog: Catalog, mode: Mode): ExecutedQuery = mode.optimizer match {
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
      PhysicalPlanner.preMaterialize(physical)
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
