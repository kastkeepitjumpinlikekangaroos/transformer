package com.transformer.core

import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Fault points: every place a run changes the disk, by name, where a test can
  * make the change fail. A run calls [[at]] just before each change: a
  * partition's staged file after its first batch, each rename that commits a
  * part, each record written (`Running`, final, `Failed`, `Skipped`), each file
  * deleted while a directory is cleared, the validation samples, `_perf.json`
  * and `job.json`. The calls are per file, never per row.
  *
  * Unarmed, [[at]] is one volatile read. Armed, the `nth` time its point is
  * reached it acts, once, as the [[Action]] says: [[Action.Throw]] and
  * [[Action.Enospc]] throw the `IOException` a failed write would, and
  * [[Action.Halt]] stops the JVM where it stands, so no `finally` block runs,
  * as a kill or a power cut would.
  *
  * Armed two ways: in this JVM through [[arm]] (tests), or in a child JVM
  * through `-Dtransformer.fault=<point>:<n>:<action>`, read once when this
  * object loads, as `transformer.hash_zero` is ([[KeyHash]]). A value that
  * names no point or action fails the run loudly rather than arming nothing.
  */
object Faults {

  /** A partition's staged part file, once its first batch is written. */
  val FirstBatch: String = "first-batch"
  /** A staged part renamed to its part name, as the write commits. */
  val Commit: String = "commit"
  /** The `Running` record that marks a directory before a run changes it. */
  val RunningRecord: String = "running-record"
  /** The final record, `Succeeded` or `ValidationFailed`: the commit. */
  val FinalRecord: String = "final-record"
  /** The `Failed` record of a task that failed. */
  val FailedRecord: String = "failed-record"
  /** The `Skipped` record of a task whose upstream failed. */
  val SkippedRecord: String = "skipped-record"
  /** A file deleted while a directory is cleared of an earlier run's files. */
  val ClearDelete: String = "clear-delete"
  /** A failed validation's sample rows, `_validation-<name>.csv`. */
  val ValidationSample: String = "validation-sample"
  /** A task's metrics, `_perf.json`. */
  val PerfRecord: String = "perf-record"
  /** The job's manifest, `job.json`. */
  val JobRecord: String = "job-record"

  /** Every point, in the order a run reaches them. */
  val Points: Vector[String] = Vector(RunningRecord, ClearDelete, FirstBatch, Commit, ValidationSample, PerfRecord,
    FinalRecord, FailedRecord, SkippedRecord, JobRecord)

  /** What an armed point does. Serializable, as an [[Arming]] is, so a test
    * can hand one to a child JVM. */
  sealed abstract class Action(val name: String) extends Serializable
  object Action {
    /** An `IOException` naming the point. */
    case object Throw extends Action("throw")
    /** An `IOException` reading `No space left on device`, as a full disk's
      * write fails. */
    case object Enospc extends Action("enospc")
    /** `Runtime.halt`: the JVM stops at once, no `finally` block runs. */
    case object Halt extends Action("halt")

    val All: Vector[Action] = Vector(Throw, Enospc, Halt)
  }

  /** The exit status of a JVM a [[Action.Halt]] stopped. */
  val HaltStatus: Int = 113

  /** The property a child JVM is armed by: `<point>:<n>:<action>`. */
  val PropertyName: String = "transformer.fault"

  /** `action` the `nth` time `point` is reached, counting from 1. */
  final case class Arming(point: String, nth: Int, action: Action) {
    require(Points.contains(point), s"$PropertyName: no fault point is named '$point'; the points are ${Points.mkString(", ")}")
    require(nth >= 1, s"$PropertyName: a point is reached for the first time at 1, not $nth")

    override def toString: String = s"$point:$nth:${action.name}"
  }

  object Arming {
    /** `<point>:<n>:<action>`, as [[PropertyName]] holds it. */
    def parse(text: String): Arming = text.split(':') match {
      case Array(point, n, action) =>
        val a = Action.All.find(_.name == action.toLowerCase(Locale.ROOT)).getOrElse(throw new IllegalArgumentException(
          s"$PropertyName: no fault action is named '$action'; the actions are ${Action.All.map(_.name).mkString(", ")}"))
        val nth = n.toIntOption.getOrElse(throw new IllegalArgumentException(
          s"$PropertyName: '$n' is no count of times a point is reached"))
        Arming(point, nth, a)
      case _ => throw new IllegalArgumentException(s"$PropertyName must be <point>:<n>:<action>, not '$text'")
    }
  }

  /** An arming and how often its point has been reached. */
  private final class Armed(val arming: Arming) {
    val reached = new AtomicInteger()
    @volatile var fired: Boolean = false
  }

  @volatile private var armed: Armed =
    Option(System.getProperty(PropertyName)).map(_.trim).filter(_.nonEmpty).map(p => new Armed(Arming.parse(p))).orNull

  /** A run is about to change the disk at `point`. */
  def at(point: String): Unit = {
    val a = armed
    if (a != null && a.arming.point == point && a.reached.incrementAndGet() == a.arming.nth) fire(a)
  }

  private def fire(a: Armed): Unit = {
    a.fired = true
    a.arming.action match {
      case Action.Throw => throw new IOException(s"Injected fault at ${a.arming}")
      case Action.Enospc => throw new IOException("No space left on device")
      case Action.Halt =>
        System.err.println(s"Injected fault at ${a.arming}: halting")
        System.err.flush()
        Runtime.getRuntime.halt(HaltStatus)
    }
  }

  /** Arm `arming` in this JVM, replacing what was armed. */
  def arm(arming: Arming): Unit = armed = new Armed(arming)

  /** Arm nothing. */
  def disarm(): Unit = armed = null

  /** Whether the point armed now has acted. */
  def fired: Boolean = { val a = armed; a != null && a.fired }

  /** How often the point armed now has been reached since it was armed. */
  def reached: Int = { val a = armed; if (a == null) 0 else a.reached.get }
}
