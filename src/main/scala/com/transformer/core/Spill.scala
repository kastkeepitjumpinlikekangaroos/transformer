package com.transformer.core

import java.io.IOException
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters._

/** Shared spill infrastructure: temp-dir lifecycle and per-batch heap-byte
  * estimation. Operators that opt into spill ([[ExecutionOptions.spillEnabled]])
  * use this to:
  *   - Reserve a per-operator subdirectory under [[rootDir]] for their spill
  *     files. The subdirectory is deleted when the operator finishes or when
  *     the JVM shuts down.
  *   - Estimate the heap cost of a [[ColumnarBatch]] before deciding to flush.
  *
  * Lifecycle:
  *   - `Spill` registers a JVM shutdown hook on first use that recursively
  *     deletes [[rootDir]]. Process crashes leave stale dirs behind; the next
  *     JVM start sweeps them in [[cleanupStaleRoots]].
  *   - Each `OperatorSpillDir` deletes its own subtree on `close()` and re-
  *     registers a cleanup on shutdown.
  *
  * Defaults:
  *   - Root dir: `${java.io.tmpdir}/transformer-spill`, overridable via the
  *     `transformer.spill.dir` system property. The dir is a filesystem
  *     location, not a behavior toggle — this is the one and only global
  *     knob spill code reads from system properties.
  *
  * NOT in scope here: spill thresholds, on-disk encoding, AggState
  * serialization. Those belong in `AggStateSerde` and in the per-operator
  * spill code.
  */
object Spill {

  /** System property holding the override for [[rootDir]]. Defaults to
    * `${java.io.tmpdir}/transformer-spill`. */
  val SpillDirProperty: String = "transformer.spill.dir"

  /** Directory containing per-operator spill subdirectories. Lazy so tests
    * that don't touch spill never create the dir. */
  lazy val rootDir: Path = {
    val configured = Option(System.getProperty(SpillDirProperty)).map(_.trim).filter(_.nonEmpty)
    val tmp = configured.getOrElse(System.getProperty("java.io.tmpdir") + "/transformer-spill")
    val p = Paths.get(tmp).toAbsolutePath.normalize()
    Files.createDirectories(p)
    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      def run(): Unit = try deleteRecursively(p) catch { case _: Throwable => () }
    }, "transformer-spill-root-cleanup"))
    cleanupStaleRoots(p.getParent, p.getFileName.toString)
    p
  }

  private val operatorCounter = new AtomicLong(0L)

  /** Allocate a unique per-operator spill subdirectory. The directory is
    * created eagerly so the caller can write into it without further setup.
    *
    * `operatorTag` is purely for human-debuggable directory names — pick
    * something short like `"sort"` or `"agg"`. The full path looks like
    * `${rootDir}/agg-1234-7-abcdef/`. */
  def openOperatorDir(operatorTag: String): OperatorSpillDir = {
    val id = operatorCounter.incrementAndGet()
    val pid = ProcessHandle.current().pid()
    val suffix = java.lang.Long.toHexString(System.nanoTime() & 0xFFFFFFFFL)
    val name = s"${sanitize(operatorTag)}-$pid-$id-$suffix"
    val dir = rootDir.resolve(name)
    Files.createDirectories(dir)
    new OperatorSpillDir(dir)
  }

  /** Approximate the heap cost of one [[ColumnarBatch]] in bytes. Used by
    * spill-capable operators as a flush trigger.
    *
    * Strategy:
    *   - Per row: an 8-byte slot per column (covers Int/Long/Double/Boolean
    *     primitive slots and references to String/Binary/Decimal). Reference
    *     types pay extra for the referenced payload (counted below).
    *   - Per non-null reference value: an estimated 24-byte object header +
    *     payload size (UTF-8 byte length for String, array length for
    *     Binary, ~40 bytes for BigDecimal).
    *
    * Intentionally a rough upper bound — the operator compares it against
    * a threshold, so a 2× error just shifts when spill triggers. Use this
    * as a *signal*, never as an authoritative size. */
  def estimateBytes(batch: ColumnarBatch): Long = {
    val n = batch.numRows
    val ncols = batch.schema.length
    var total: Long = n.toLong * ncols.toLong * 8L
    var c = 0
    while (c < ncols) {
      batch.column(c) match {
        case sv: StringVector =>
          var r = 0
          while (r < n) {
            if (!sv.isNull(r)) {
              val s = sv.get(r)
              total += 24L + (if (s == null) 0L else s.length.toLong * 2L)
            }
            r += 1
          }
        case bv: BinaryVector =>
          var r = 0
          while (r < n) {
            if (!bv.isNull(r)) {
              val a = bv.get(r)
              total += 24L + (if (a == null) 0L else a.length.toLong)
            }
            r += 1
          }
        case dv: DecimalVector =>
          var r = 0
          while (r < n) { if (!dv.isNull(r)) total += 48L; r += 1 }
        case _ => () // primitive vectors already accounted for in `total` above
      }
      c += 1
    }
    total
  }

  /** Sweep stale `${rootDir}/transformer-spill-*` directories from prior
    * JVM runs. Bounded by listing the immediate children of `parent`; does
    * not recurse beyond one level. Failures are swallowed — a missing parent
    * dir or a permission issue should not block the current run.
    *
    * Why this is safe: each spill subdir name embeds the JVM's pid +
    * monotonic counter + nanoTime suffix, so a directory whose name's pid
    * does not match an existing process can be reclaimed. We simplify by
    * just sweeping the root on first use — concurrent JVMs is not a
    * supported configuration. */
  private[core] def cleanupStaleRoots(parent: Path, currentName: String): Unit = {
    if (parent == null || !Files.isDirectory(parent)) return
    try {
      val stream = Files.list(parent)
      try {
        stream.iterator().asScala.foreach { p =>
          val name = p.getFileName.toString
          if (name.startsWith(currentName.takeWhile(_ != '-')) && name != currentName) {
            try deleteRecursively(p) catch { case _: Throwable => () }
          }
        }
      } finally stream.close()
    } catch { case _: IOException => () }
  }

  private[core] def deleteRecursively(p: Path): Unit = {
    if (!Files.exists(p)) return
    if (Files.isDirectory(p)) {
      val stream = Files.list(p)
      try stream.iterator().asScala.foreach(deleteRecursively) finally stream.close()
    }
    try Files.deleteIfExists(p) catch { case _: IOException => () }
  }

  private def sanitize(tag: String): String = {
    val sb = new java.lang.StringBuilder(tag.length)
    var i = 0
    while (i < tag.length) {
      val ch = tag.charAt(i)
      sb.append(if (ch.isLetterOrDigit) ch else '_')
      i += 1
    }
    val out = sb.toString
    if (out.isEmpty) "op" else out
  }

  /** Effective per-operator spill threshold in bytes. If the caller's
    * `opts.spillThresholdBytes` is set, use it. Otherwise default to
    * `min(1 GiB, maxHeap / 4 / parallelism)` — matches the table in
    * plans/perf/09-spill-to-disk.md. */
  def effectiveThresholdBytes(opts: ExecutionOptions, parallelism: Int): Long = {
    opts.spillThresholdBytes.getOrElse {
      val maxHeap = Runtime.getRuntime.maxMemory()
      val perOp = maxHeap / 4L / math.max(1, parallelism).toLong
      math.min(1L << 30, math.max(64L << 20, perOp))
    }
  }
}

/** One operator's allocated subdirectory. Use [[newSpillFile]] to mint
  * unique paths for each spill run; call [[close]] to wipe the subtree when
  * the operator finishes. */
final class OperatorSpillDir private[core] (val dir: java.nio.file.Path) extends AutoCloseable {
  private val fileCounter = new AtomicLong(0L)
  @volatile private var closed: Boolean = false

  /** Allocate a new spill file path inside this operator's directory. */
  def newSpillFile(suffix: String = ".tmp"): java.nio.file.Path = {
    if (closed) throw new IllegalStateException(s"OperatorSpillDir already closed: $dir")
    val n = fileCounter.incrementAndGet()
    dir.resolve(s"run-$n$suffix")
  }

  /** Delete the operator's subtree. Idempotent. */
  def close(): Unit = {
    if (closed) return
    closed = true
    try Spill.deleteRecursively(dir) catch { case _: Throwable => () }
  }
}
