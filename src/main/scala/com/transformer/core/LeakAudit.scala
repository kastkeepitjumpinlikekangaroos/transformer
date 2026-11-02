package com.transformer.core

import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import scala.jdk.CollectionConverters._

/** `--config=leaks`: what the engine opens, counted, and reported at JVM exit
  * when still open, as Spark's `DebugFilesystem.assertNoOpenStreams` fails a
  * test that leaves a stream open (decision 3 of plans/testing/17).
  *
  * Under `-Dtransformer.audit_leaks=true`, read once per JVM as
  * `transformer.hash_zero` is ([[KeyHash]]), each resource registers itself
  * when it opens ([[opened]]) and leaves when it closes ([[closed]]): a file a
  * CSV or parquet reader reads (`CsvPartitionIterator`,
  * `ParquetPartitionIterator`, spill runs read back included), a file a writer
  * writes (`CsvWriter`, `ParquetWriter`, spill runs included) and an
  * operator's spill directory ([[Spill.openOperatorDir]]). Each is recorded
  * with the stack that opened it.
  *
  * At exit a shutdown hook waits for the pool to finish what it is running,
  * then reports each resource still open, with its stack, each directory left
  * in this JVM's spill directory, and each thread the engine started (its name
  * begins `transformer-`) that is alive and is not one of the pool's daemons
  * or a shutdown hook; if it found any, it ends the JVM with [[LeakStatus]],
  * so Bazel fails the target. Off, [[opened]] and [[closed]] are one read of a
  * final val.
  */
object LeakAudit {

  /** Whether the engine counts what it opens: `-Dtransformer.audit_leaks=true`. */
  val enabled: Boolean = java.lang.Boolean.getBoolean("transformer.audit_leaks")

  /** The exit status of a JVM the audit found something left open in. */
  val LeakStatus: Int = 117

  /** The name of the thread that runs the audit at exit. */
  val HookName: String = "transformer-leak-audit"

  /** A resource by identity: two equal readers are two files. */
  private final class Key(val resource: AnyRef) {
    override def hashCode: Int = System.identityHashCode(resource)
    override def equals(other: Any): Boolean = other match {
      case k: Key => k.resource eq resource
      case _ => false
    }
  }

  /** What a resource is, and where it was opened. */
  private final class Opened(val what: String, val where: Throwable)

  private val open = new ConcurrentHashMap[Key, Opened]()

  /** Register the exit audit, when the audit is on: the first resource
    * opened does too. */
  def watch(): Unit = if (enabled) hook

  /** `resource`, described by `what`, is open now. */
  def opened(resource: AnyRef, what: => String): Unit =
    if (enabled) {
      hook
      val _ = open.put(new Key(resource), new Opened(what,
        new Throwable(s"opened on thread ${Thread.currentThread.getName}")))
    }

  /** `resource` is closed. Closing what was never registered, or closing
    * twice, does nothing. */
  def closed(resource: AnyRef): Unit =
    if (enabled) { val _ = open.remove(new Key(resource)) }

  /** What is open now, each resource described: for a test that holds a query
    * to releasing what it opened. Empty when the audit is off. */
  def openNow: Seq[String] = open.values.asScala.map(_.what).toSeq.sorted

  /** What [[exit]] would report now: each resource still open, with the stack
    * that opened it, each directory left in this JVM's spill directory, and
    * each thread the engine started that is still alive. */
  def report(): Seq[String] = {
    val resources = open.values.asScala.toSeq.sortBy(_.what).map { o =>
      val stack = o.where.getStackTrace.iterator.drop(1).take(25).map(f => s"    at $f").mkString("\n")
      s"still open: ${o.what}, ${o.where.getMessage}\n$stack"
    }
    val dirs = Spill.operatorDirsLeft.map(d => s"left in the spill directory: $d")
    val threads = Thread.getAllStackTraces.keySet.asScala.toSeq
      .filter(t => t.isAlive && engineThread(t.getName))
      .sortBy(_.getName)
      .map(t => s"thread still alive: ${t.getName} (${t.getState})")
    resources ++ dirs ++ threads
  }

  /** A thread the engine started that should not outlive what started it:
    * not one of the pool's daemon workers, which live as long as the JVM, nor
    * a shutdown hook. */
  private def engineThread(name: String): Boolean =
    name.startsWith("transformer-") && !name.startsWith("transformer-worker-") &&
      name != HookName && name != Spill.CleanupThreadName

  /** The exit audit: registered the first time anything opens. */
  private lazy val hook: Unit =
    Runtime.getRuntime.addShutdownHook(new Thread(() => exit(), HookName))

  private def exit(): Unit = {
    // A task the pool is finishing, a failed query's sibling or a writer
    // being aborted, releases what it holds as it ends.
    val _ = Scheduler.pool.awaitQuiescence(30, TimeUnit.SECONDS)
    val found = report()
    if (found.nonEmpty) {
      System.err.println(s"transformer.audit_leaks: ${found.size} left open at exit")
      found.foreach(f => System.err.println(f))
      System.err.flush()
      Runtime.getRuntime.halt(LeakStatus)
    }
    Spill.deleteRootAtExit()
  }
}
