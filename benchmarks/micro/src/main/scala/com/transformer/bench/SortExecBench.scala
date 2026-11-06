package com.transformer.bench

import com.transformer.core._
import com.transformer.sql.exec.{PhysicalPlan, SortExec, TopNExec}
import com.transformer.sql.plan.{ColRefExpr, SortKey}

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** The real [[SortExec]] and [[TopNExec]], drained, over `rows` rows in 8
  * in-memory partitions of full batches, built once a trial.
  *
  * Four shapes:
  *
  *   - **query** — polymarket's `SELECT market_id, change_size FROM ob ORDER
  *     BY change_size DESC`: a FLOAT key, a third of it 0.0, and a STRING
  *     payload drawn from 13,000 strings of 66 characters.
  *   - **bigint** — one BIGINT key over random longs, an INT payload.
  *   - **string** — one STRING key drawn from 262,144 strings of 8 to 32
  *     characters, a BIGINT payload.
  *   - **twoKeys** — an INT key ASC (1,000 values) and a STRING key DESC
  *     NULLS FIRST (the 13,000 strings), each 5% NULL, a BIGINT payload.
  *
  * `spill` is off, or on at a 16 MiB threshold a partition, where a sort
  * writes runs to the fork's temp directory and merges them back. A top-n
  * keeps 3 rows and never spills, so its two `spill` rows measure the same
  * thing. Each invocation is one whole sort: `SingleShotTime`. */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SingleShotTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xmx4g"))
@Warmup(iterations = 2)
@Measurement(iterations = 5)
class SortExecBench {

  @Param(Array("query", "bigint", "string", "twoKeys"))
  var shape: String = _

  @Param(Array("1000000", "4000000"))
  var rows: Int = _

  @Param(Array("off", "16MiB"))
  var spill: String = _

  private var child: PhysicalPlan = _
  private var keys: Seq[SortKey] = _
  private var opts: ExecutionOptions = _

  @Setup(Level.Trial)
  def setUp(): Unit = {
    val (plan, sortKeys) = SortExecBench.input(shape, rows)
    child = plan
    keys = sortKeys
    opts = spill match {
      case "off" => ExecutionOptions.Default
      case "16MiB" => ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(16L << 20))
    }
  }

  /** Every row, sorted. */
  @Benchmark
  def sort(bh: Blackhole): Unit = SortExecBench.drain(SortExec(child, keys, opts).execute(0), bh)

  /** The first 3 rows of the same sort. */
  @Benchmark
  def topN(bh: Blackhole): Unit = SortExecBench.drain(TopNExec(child, keys, 3).execute(0), bh)
}

private[bench] object SortExecBench {

  val Partitions: Int = 8

  def drain(it: Iterator[ColumnarBatch], bh: Blackhole): Unit = {
    var n = 0L
    while (it.hasNext) n += it.next().numRows
    bh.consume(n)
  }

  /** `n` random strings of `minLen` to `maxLen` characters from `alphabet`. */
  private def pool(n: Int, minLen: Int, maxLen: Int, alphabet: String, prefix: String, seed: Long): Array[String] = {
    val rng = new java.util.SplittableRandom(seed)
    Array.fill(n) {
      val len = minLen + rng.nextInt(maxLen - minLen + 1)
      val sb = new java.lang.StringBuilder(prefix.length + len)
      sb.append(prefix)
      var i = 0
      while (i < len) { sb.append(alphabet.charAt(rng.nextInt(alphabet.length))); i += 1 }
      sb.toString
    }
  }

  /** 13,000 market ids: `0x` and 64 hex digits, 66 characters. */
  private lazy val MarketIds: Array[String] = pool(13000, 64, 64, "0123456789abcdef", "0x", 0x13000L)

  private lazy val Words: Array[String] =
    pool(1 << 18, 8, 32, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", "", 0x40000L)

  /** The plan and keys of `shape` over `rows` rows. */
  def input(shape: String, rows: Int): (PhysicalPlan, Seq[SortKey]) = shape match {
    case "query" =>
      val schema = Schema(Vector(Field("market_id", DataType.StringType), Field("change_size", DataType.FloatType)))
      val plan = build(schema, rows, 0x51L) { (b, r, rng) =>
        b.column(0).asInstanceOf[StringVector].set(r, MarketIds(rng.nextInt(MarketIds.length)))
        val v = if (rng.nextInt(3) == 0) 0.0f else ((rng.nextInt(2000001) - 1000000) / 100.0).toFloat
        b.column(1).asInstanceOf[FloatVector].set(r, v)
      }
      (plan, Seq(SortKey(ColRefExpr(1, "change_size", DataType.FloatType), asc = false)))
    case "bigint" =>
      val schema = Schema(Vector(Field("k", DataType.LongType), Field("v", DataType.IntType)))
      val plan = build(schema, rows, 0x52L) { (b, r, rng) =>
        b.column(0).asInstanceOf[LongVector].set(r, rng.nextLong())
        b.column(1).asInstanceOf[IntVector].set(r, r)
      }
      (plan, Seq(SortKey(ColRefExpr(0, "k", DataType.LongType), asc = true)))
    case "string" =>
      val schema = Schema(Vector(Field("s", DataType.StringType), Field("id", DataType.LongType)))
      val plan = build(schema, rows, 0x53L) { (b, r, rng) =>
        b.column(0).asInstanceOf[StringVector].set(r, Words(rng.nextInt(Words.length)))
        b.column(1).asInstanceOf[LongVector].set(r, rng.nextLong())
      }
      (plan, Seq(SortKey(ColRefExpr(0, "s", DataType.StringType), asc = true)))
    case "twoKeys" =>
      val schema = Schema(Vector(Field("a", DataType.IntType), Field("b", DataType.StringType),
        Field("c", DataType.LongType)))
      val plan = build(schema, rows, 0x54L) { (b, r, rng) =>
        val a = b.column(0).asInstanceOf[IntVector]
        if (rng.nextInt(20) == 0) a.setNull(r) else a.set(r, rng.nextInt(1000))
        val s = b.column(1).asInstanceOf[StringVector]
        if (rng.nextInt(20) == 0) s.setNull(r) else s.set(r, MarketIds(rng.nextInt(MarketIds.length)))
        b.column(2).asInstanceOf[LongVector].set(r, rng.nextLong())
      }
      (plan, Seq(SortKey(ColRefExpr(0, "a", DataType.IntType), asc = true),
        SortKey(ColRefExpr(1, "b", DataType.StringType), asc = false, nullsFirst = true)))
  }

  /** [[Partitions]] partitions of `rows` rows between them, each a list of
    * full batches, every row filled by `fill`. */
  private def build(schema: Schema, rows: Int, seed: Long)(
      fill: (ColumnarBatch, Int, java.util.SplittableRandom) => Unit): PhysicalPlan = {
    val rng = new java.util.SplittableRandom(seed)
    val cap = ColumnarBatch.DefaultCapacity
    val parts: Array[Array[ColumnarBatch]] = Array.tabulate(Partitions) { p =>
      val here = rows / Partitions + (if (p < rows % Partitions) 1 else 0)
      val nBatches = (here + cap - 1) / cap
      Array.tabulate(nBatches) { i =>
        val n = math.min(cap, here - i * cap)
        val b = new ColumnarBatch(schema, n)
        var r = 0
        while (r < n) { fill(b, r, rng); r += 1 }
        b.setNumRows(n)
        b
      }
    }
    new PhysicalPlan {
      def outputSchema: Schema = schema
      def numPartitions: Int = Partitions
      def execute(partition: Int): Iterator[ColumnarBatch] = parts(partition).iterator
    }
  }
}
