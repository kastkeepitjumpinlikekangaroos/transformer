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
  *   - Every JVM spills into a directory of its own under [[baseDir]],
  *     [[rootDir]], and registers a shutdown hook on first use that deletes
  *     it and nothing else, so JVMs that spill at once leave each other's
  *     files alone (under `--config=leaks` the audit deletes it at exit,
  *     after it has looked for what was left there, [[LeakAudit]]). Process
  *     crashes leave stale dirs behind; the next JVM to spill deletes those
  *     of JVMs no longer running ([[cleanupStaleRoots]]).
  *   - Each `OperatorSpillDir` deletes its own subtree on `close()`, which
  *     its operator calls once nothing reads it: when its output is drained or
  *     closed, or the operator fails ([[Closing]]).
  *
  * Defaults:
  *   - Base dir: `${java.io.tmpdir}/transformer-spill`, overridable via the
  *     `transformer.spill.dir` system property, read once per JVM. The dir
  *     is a filesystem location, not a behavior toggle — this is the one and
  *     only global knob spill code reads from system properties. Nothing
  *     outside it is ever deleted. A test target's is its own: Bazel sets
  *     `java.io.tmpdir` to the target's `TEST_TMPDIR`.
  *
  * NOT in scope here: spill thresholds, on-disk encoding, AggState
  * serialization. Those belong in `AggStateSerde` and in the per-operator
  * spill code.
  */
object Spill {

  /** System property holding the override for [[baseDir]]. Defaults to
    * `${java.io.tmpdir}/transformer-spill`. */
  val SpillDirProperty: String = "transformer.spill.dir"

  /** The name of a JVM's or an operator's spill directory, its pid captured
    * ([[cleanupStaleRoots]]); declared before [[rootDir]], whose first use
    * reads it. */
  private val StaleName = """^[A-Za-z0-9_]+-(\d{1,19})-[0-9a-f]+(?:-[0-9a-f]+)?$""".r

  /** The directory every JVM's spill directory is made in. */
  lazy val baseDir: Path = {
    val configured = Option(System.getProperty(SpillDirProperty)).map(_.trim).filter(_.nonEmpty)
    val tmp = configured.getOrElse(System.getProperty("java.io.tmpdir") + "/transformer-spill")
    Paths.get(tmp).toAbsolutePath.normalize()
  }

  /** This JVM's spill directory, `jvm-<pid>-<suffix>` under [[baseDir]],
    * holding every per-operator subdirectory. Lazy so tests that don't touch
    * spill never create the dir. It was [[baseDir]] itself, shared by every
    * JVM, so one JVM's exit deleted the files another was spilling; and the
    * sweep of stale ones deleted whatever stood beside it with a name
    * starting as its did up to the first `-`, `transformer-jaffle-out` in the
    * temp directory among them. */
  lazy val rootDir: Path = {
    val base = baseDir
    Files.createDirectories(base)
    val pid = ProcessHandle.current().pid()
    val suffix = java.lang.Long.toHexString(System.nanoTime() & 0xFFFFFFFFL)
    val p = base.resolve(s"jvm-$pid-$suffix")
    Files.createDirectories(p)
    // Under the audit its exit hook deletes the directory, once it has looked
    // for what was left in it: two hooks run at once.
    if (!LeakAudit.enabled) Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      def run(): Unit = try deleteRecursively(p) catch { case _: Throwable => () }
    }, CleanupThreadName))
    cleanupStaleRoots(base, p.getFileName.toString)
    made = p
    p
  }

  /** The name of the shutdown hook that deletes [[rootDir]]. */
  val CleanupThreadName: String = "transformer-spill-root-cleanup"

  /** [[rootDir]], once something has made it. */
  @volatile private var made: Path = null

  /** The operator directories in this JVM's spill directory now: none once
    * every operator that spilled has closed its own, so a test's `@After`
    * holds a test to leaving none. Nothing when no operator has spilled. */
  def operatorDirsLeft: Seq[Path] = {
    val root = made
    if (root == null || !Files.isDirectory(root)) Nil
    else {
      val s = Files.list(root)
      try s.iterator().asScala.toVector.sortBy(_.toString) finally s.close()
    }
  }

  /** Delete this JVM's spill directory: the audit's exit hook, in place of
    * the one [[rootDir]] registers without it. */
  private[core] def deleteRootAtExit(): Unit = {
    val root = made
    if (root != null) try deleteRecursively(root) catch { case _: Throwable => () }
  }

  private val operatorCounter = new AtomicLong(0L)

  /** Allocate a unique per-operator spill subdirectory. The directory is
    * created eagerly so the caller can write into it without further setup.
    *
    * `operatorTag` is purely for human-debuggable directory names — pick
    * something short like `"sort"` or `"agg"`. The full path looks like
    * `${rootDir}/agg-1234-7-abcdef/`. */
  def openOperatorDir(operatorTag: String): OperatorSpillDir = {
    Chaos.at(Chaos.SpillDir)
    val id = operatorCounter.incrementAndGet()
    val pid = ProcessHandle.current().pid()
    val suffix = java.lang.Long.toHexString(System.nanoTime() & 0xFFFFFFFFL)
    val name = s"${sanitize(operatorTag)}-$pid-$id-$suffix"
    val dir = rootDir.resolve(name)
    Files.createDirectories(dir)
    val opened = new OperatorSpillDir(dir)
    LeakAudit.opened(opened, s"the spill directory '$dir'")
    opened
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

  /** A copy of `schema` with field names replaced by positional, collision-free
    * placeholders (`_c0`, `_c1`, ...) and types/order preserved. Every field is
    * made nullable (parquet OPTIONAL) so any null value round-trips regardless
    * of the logical schema's repetition flags.
    *
    * Spill files are read back by column INDEX, never by name (every spill read
    * path materializes rows positionally and reinterprets types from the
    * operator's own logical schema), so the original names carry no information
    * the reader uses. They are also actively harmful: parquet's by-name column
    * model cannot round-trip two columns that share a name — a join output like
    * `[k, v, k, v]`, or name-colliding GROUP BY keys — because the second
    * column's pages resolve to `null` on read and the decoder NPEs. Writing
    * positional names sidesteps that entirely.
    *
    * Spill-only: do not route real output files (the non-spill `ParquetWriter`
    * callers) through here — those must keep their real column names. Types go
    * through [[storedType]]. */
  def positionalSchema(schema: Schema): Schema =
    Schema(schema.fields.iterator.zipWithIndex.map {
      case (f, i) => Field(s"_c$i", storedType(f.dataType))
    }.toVector)

  /** The type a spill file stores a column of `t` as: `t`, except for a
    * NULL-typed column (an untyped `NULL`, or an aggregate over one), which has
    * no parquet type. It holds nothing but NULLs, so it is stored as a BOOLEAN
    * column with no values in it: the writer skips a NULL cell whatever the
    * column's type, and every spill reader rebuilds rows cell by cell into the
    * operator's own schema, where a NULL read back is all the column held. */
  def storedType(t: DataType): DataType =
    if (t == DataType.NullType) DataType.BooleanType else t

  /** Delete the spill directories in `base` that JVMs no longer running
    * left behind: a JVM's own (`jvm-<pid>-<suffix>`), or an operator's from
    * before each JVM had one (`<tag>-<pid>-<n>-<suffix>`), whose pid names no
    * live process. Only `base`'s immediate children of those two shapes are
    * candidates, so nothing else there and nothing outside it is touched.
    * Failures are swallowed: a missing dir or a permission issue should not
    * block the current run. A pid a later process has reused keeps its dir,
    * which costs disk, never another JVM's files. */
  private[core] def cleanupStaleRoots(base: Path, currentName: String): Unit = {
    if (base == null || !Files.isDirectory(base)) return
    try {
      val stream = Files.list(base)
      try {
        stream.iterator().asScala.foreach { p =>
          val name = p.getFileName.toString
          if (name != currentName && Files.isDirectory(p)) StaleName.unapplySeq(name).foreach { groups =>
            val pid = groups.head.toLongOption
            if (pid.exists(id => !ProcessHandle.of(id).isPresent))
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
  * the operator finishes, or fails, or its output is closed ([[Closing]]). */
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
    LeakAudit.closed(this)
  }

  /** `inner`, which reads this directory's files, as an iterator that wipes
    * the directory when it is drained or closed, whichever comes first, and
    * closes `inner` too ([[Closing]]): the emit path of an operator that
    * reads its spill files as it emits. */
  def closeOnDrain(inner: Iterator[ColumnarBatch]): Iterator[ColumnarBatch] =
    new Iterator[ColumnarBatch] with AutoCloseable {
      def hasNext: Boolean = {
        val h = !closed && inner.hasNext
        if (!h) shut()
        h
      }
      def next(): ColumnarBatch = inner.next()
      def close(): Unit = shut()
      private def shut(): Unit = {
        Closing.close(inner)
        OperatorSpillDir.this.close()
      }
    }
}
