package com.transformer.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import scala.jdk.CollectionConverters._

/** `--config=chaos`: interleavings on purpose (plans/testing/17, W3). The
  * pool's timing gives one interleaving of its threads per run, and
  * concurrency defects have needed volume to show: the K>1 sharded deadlock
  * reproduced only at 20000 seeds (plans/bugfixes/02e).
  *
  * Under `-Dtransformer.chaos=<seed>` ([[Settings.ChaosSeed]]), read once per
  * JVM as `transformer.hash_zero` is ([[KeyHash]]), each named point the engine
  * passes ([[at]]) does one of three things, drawn by a random number
  * generator of the passing thread's own, seeded from the seed and the
  * thread's id: nothing, a `Thread.yield`, or a sleep of 0 to 2 ms. The
  * points are the handoffs between threads: a pool task about to run
  * ([[Submit]]) and a wait for one ([[Await]]); an exchange's claim and
  * publish ([[ExchangeClaim]], [[ExchangePublish]]); the job runner's event
  * queue ([[JobEvent]]) and its task submissions ([[JobSubmit]]); a
  * partitioned writer's commit ([[WriterCommit]]); a spill directory made
  * ([[SpillDir]]); a task's output read back for the tasks after it
  * ([[TaskOutput]]).
  *
  * Interleavings do not replay from a seed: the threads that reach a point,
  * and their order, are the run's. So each thread keeps the last points it
  * passed ([[lastPoints]]), for the report of a failure under chaos, such as
  * the DAG fuzzer's watchdog's thread dump. Off, [[at]] is one read of a
  * final val.
  */
object Chaos {

  /** The seed, when `-Dtransformer.chaos=<seed>` sets one: a whole number. */
  val seed: Option[Long] = Settings.ChaosSeed.get

  /** Whether the points perturb the threads that pass them. */
  val enabled: Boolean = seed.isDefined

  val Submit: String = "submit"
  val Await: String = "await"
  val ExchangeClaim: String = "exchange-claim"
  val ExchangePublish: String = "exchange-publish"
  val JobEvent: String = "job-event"
  val JobSubmit: String = "job-submit"
  val WriterCommit: String = "writer-commit"
  val SpillDir: String = "spill-dir"
  val TaskOutput: String = "task-output"

  /** Every point. */
  val Points: Vector[String] =
    Vector(Submit, Await, ExchangeClaim, ExchangePublish, JobEvent, JobSubmit, WriterCommit, SpillDir, TaskOutput)

  /** What a point does. */
  sealed trait Action
  object Action {
    case object Nothing extends Action
    case object Yield extends Action
    final case class Sleep(nanos: Long) extends Action
  }

  /** How many last points each thread keeps. */
  val Kept: Int = 8

  /** A thread's generator and the last points it passed. */
  private final class ThreadState(threadSeed: Long) {
    val random = new java.util.SplittableRandom(threadSeed)
    val last = new Array[String](Kept)
    var passed = 0L
  }

  private val states = new ConcurrentHashMap[Thread, ThreadState]()

  private val local: ThreadLocal[ThreadState] = new ThreadLocal[ThreadState] {
    override def initialValue(): ThreadState = {
      val t = Thread.currentThread
      val s = new ThreadState(seed.getOrElse(0L) * 0x9E3779B97F4A7C15L ^ t.threadId)
      val _ = states.put(t, s)
      s
    }
  }

  private val passedTotal = new AtomicLong()

  /** How often each point has been passed, by any thread. */
  private val counts = new ConcurrentHashMap[String, java.util.concurrent.atomic.LongAdder]()

  /** The passing thread has reached `point`: under chaos it does nothing,
    * yields, or sleeps 0 to 2 ms. */
  def at(point: String): Unit = if (enabled) perturb(point)

  private def perturb(point: String): Unit = {
    val s = local.get
    s.last((s.passed % Kept).toInt) = point
    s.passed += 1
    val _ = passedTotal.incrementAndGet()
    counts.computeIfAbsent(point, _ => new java.util.concurrent.atomic.LongAdder).increment()
    draw(s.random) match {
      case Action.Nothing => ()
      case Action.Yield => Thread.`yield`()
      case Action.Sleep(nanos) => LockSupport.parkNanos(nanos)
    }
  }

  /** One draw: nothing, a yield or a sleep, a third each, a sleep 0 to 2 ms
    * long. */
  def draw(random: java.util.SplittableRandom): Action = random.nextInt(3) match {
    case 0 => Action.Nothing
    case 1 => Action.Yield
    case _ => Action.Sleep(random.nextLong(2000001L))
  }

  /** How many times any thread has passed a point. */
  def passed: Long = passedTotal.get

  /** How many times any thread has passed `point`. */
  def passed(point: String): Long = Option(counts.get(point)).fold(0L)(_.sum())

  /** The last points `thread` passed, oldest first, for a failure's report:
    * none when chaos is off or it passed none. */
  def lastPointsOf(thread: Thread): Seq[String] = Option(states.get(thread)).fold(Seq.empty[String]) { s =>
    val passed = s.passed
    val n = math.min(passed, Kept.toLong).toInt
    val first = ((passed - n) % Kept).toInt
    (0 until n).map(i => s.last((first + i) % Kept))
  }

  /** The last points each thread passed, oldest first, by thread name. */
  def lastPoints: Map[String, Seq[String]] =
    states.keySet.asScala.iterator.map(t => t.getName -> lastPointsOf(t)).toMap
}
