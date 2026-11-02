package com.transformer.fuzz

import com.transformer.core._
import com.transformer.fuzz.MetaQueryGen.MetaCase
import com.transformer.fuzz.oracle.ForcedPlans
import com.transformer.fuzz.oracle.ForcedPlans.Strategy
import com.transformer.sql.plan.LogicalBuilder
import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}
import scala.collection.mutable

/** Forced plans ([[ForcedPlans]], DQP's relation): every plan the engine can
  * be made to choose for one [[MetaQueryGen]] query gives one multiset.
  *
  *   - [[fuzzJoinOrders]] writes the leading run of inner joins of a query's
  *     FROM in every order of four tables or fewer, and in 24 of a run of
  *     five or six, and runs each with the optimizer off (the joins in that
  *     order) and on (`JoinOrder` choosing from it).
  *   - [[fuzzStrategies]] respells every join of the optimized plan six ways,
  *     on its key, on computed keys, on two keys, on no key, and the first two
  *     with spill on, each with the sides swapped at every join half the time,
  *     and runs each as printed.
  *
  * The guards say the corpus is not vacuous: runs of three tables or more
  * are written in other orders, and the strategies reach every path a join
  * takes and every key codec, read off the physical plan and the metrics node.
  * `ShardedForcedPlanFuzzTest` runs the strategies under sharded planning.
  */
class ForcedPlanFuzzTest {

  import ForcedPlanFuzzTest._

  // Spill files in a temp dir, so the spilling strategies never litter the
  // default location.
  private val originalDirProp: String = System.getProperty(Spill.SpillDirProperty)
  private var tmpRoot: Path = _

  @Before def setSpillDir(): Unit = {
    tmpRoot = Files.createTempDirectory("forced-plans-spill-")
    System.setProperty(Spill.SpillDirProperty, tmpRoot.resolve("transformer-spill").toString)
  }

  @After def restoreSpillDir(): Unit = {
    if (originalDirProp == null) System.clearProperty(Spill.SpillDirProperty)
    else System.setProperty(Spill.SpillDirProperty, originalDirProp)
    deleteRecursively(tmpRoot)
  }

  @Test def fuzzJoinOrders(): Unit =
    Props.forAll[Case](
      name = "forced-join-orders",
      gen = generate,
      shrink = shrink,
      count = Props.seedCountOr(DefaultOrderSeeds)
    ) { c => ForcedPlans.checkOrders(c.value, orders(c)); () }

  @Test def fuzzStrategies(): Unit =
    Props.forAll[Case](
      name = "forced-strategies",
      gen = generate,
      shrink = shrink,
      count = Props.seedCountOr(DefaultStrategySeeds)
    ) { c => ForcedPlans.checkStrategies(c.value, strategies(c)); () }

  /** Runs of three inner tables or more are written in other orders, of four
    * or more too, and both runs of an order check something: over a block of
    * seeds, the cases a run binds in and is layout-invariant, and the orders
    * each would run. Generation and binding only. */
  @Test def joinOrdersReachRunsOfThreeOrMore(): Unit = {
    val runs = mutable.Map.empty[Int, Int].withDefaultValue(0)
    var orderCount, beyondBound = 0
    (0 until OrderGuardSeeds).foreach { i =>
      val c = generate(new Rng(GuardBase + i))
      val q = c.value.query
      val n = ForcedPlans.runLength(q.from)
      if (n >= 2 && q.isLayoutInvariant && binds(c.value)) {
        runs(math.min(n, 5)) += 1
        val all = ForcedPlans.orders(n, new Rng(c.seed), Sample)
        val kept = orders(c)
        orderCount += kept.size
        beyondBound += all.size - kept.size
      }
    }
    val report = s"over $OrderGuardSeeds seeds, leading runs checked by length ${runs.toSeq.sorted.mkString(", ")} " +
      s"(5 is five or more); $orderCount orders written, $beyondBound past the bound on pairs"
    println(s"[forced-plans] $report")
    assertTrue(s"runs of three tables are rarely checked: $report", runs(3) + runs(4) + runs(5) > RunsOfThreeFloor)
    assertTrue(s"runs of four tables or more are rarely checked: $report", runs(4) + runs(5) > RunsOfFourFloor)
  }

  /** The written orders run and check something: over a block of seeds,
    * each order with the optimizer off and on answers as the query does
    * (`held`), and few are refused by the nested-loop guard or part on a
    * failure a condition moves. */
  @Test def joinOrdersRunAndHold(): Unit = {
    val kinds = mutable.Map.empty[String, Int].withDefaultValue(0)
    var reordering = 0
    (0 until OrderRunSeeds).foreach { i =>
      val c = generate(new Rng(GuardBase + i))
      val runs = ForcedPlans.checkOrders(c.value, orders(c))
      runs.foreach(r => kinds(s"${r.kind}, optimizer ${if (r.optimized) "on" else "off"}") += 1)
      if (runs.exists(r => r.order != r.order.sorted && r.kind == "held")) reordering += 1
    }
    val report = s"over $OrderRunSeeds seeds: ${kinds.toSeq.sorted.mkString(", ")}; cases holding in another order " +
      s"than written $reordering"
    println(s"[forced-plans] $report")
    assertTrue(s"few orders are run with the optimizer off: $report", kinds("held, optimizer off") > HeldFloor)
    assertTrue(s"few orders are run with the optimizer on: $report", kinds("held, optimizer on") > HeldFloor)
    assertTrue(s"few cases run another order than written: $report", reordering > ReorderedFloor)
  }

  /** Every path a hash join takes and every key codec it hashes with is
    * reached on a share of cases: read off the physical plan each strategy's
    * printed plan makes (the path) and the metrics node the planner stamps
    * (the codec). Planning only. */
  @Test def strategiesReachEveryPathAndCodec(): Unit = {
    val (paths, codecs, report) = pathTally(StrategyGuardSeeds)
    println(s"[forced-plans] $report")
    Seq("collapsing", "pairing", "grace hash").foreach(p =>
      assertTrue(s"a join rarely takes the $p path: $report", paths(p) > PathFloor))
    Seq("long", "packed", "single object", "object").foreach(k =>
      assertTrue(s"a join rarely hashes with the $k codec: $report", codecs(k) > CodecFloor))
  }
}

object ForcedPlanFuzzTest {

  /** A generated case and the seed its orders and strategies draw from. */
  final case class Case(value: MetaCase, seed: Long) {
    override def toString: String = s"$value\n  forced-plan seed = $seed"
  }

  def generate(rng: Rng): Case = Case(MetaQueryGen.generate(rng.split()), rng.nextLong())

  def shrink(c: Case): Iterator[Case] = Shrinker.metaCase(c.value).map(v => c.copy(value = v))

  /** Each order runs twice, and a run of four tables has 24 orders: about
    * ten seconds. */
  val DefaultOrderSeeds: Int = 300
  /** Each case runs six strategies: about ten seconds. */
  val DefaultStrategySeeds: Int = 300
  /** How many orders a run of five or six tables is written in. */
  val Sample: Int = 24
  /** A written order whose cross products would pair past this many rows is
    * not run: the nested-loop guard's limit is 25 million, and a property
    * runs each order twice a case. */
  val MaxPairs: Double = 1000000.0

  /** Measured over 3000 seeds: leading runs of two tables checked on 842,
    * three on 414, four on 22 and five or six on 28. Bounds ~3x below. */
  val OrderGuardSeeds: Int = 3000
  val RunsOfThreeFloor: Int = 150
  val RunsOfFourFloor: Int = 16
  /** Measured over 600 seeds: 1036 orders held with the optimizer off, 1037
    * on, 11 moved; 258 cases held in another order than written. */
  val OrderRunSeeds: Int = 600
  val HeldFloor: Int = 340
  val ReorderedFloor: Int = 85
  /** Measured over 600 seeds (443 cases with joins): the collapsing path
    * 2094, grace hash 1396, pairing 1358 (sharded: per-shard 1455, the
    * collapsing path 639 where a side is empty); the long codec 468, packed
    * 2017, single object 748, object 257. Bounds ~3x below the thinnest. */
  val StrategyGuardSeeds: Int = 600
  val PathFloor: Int = 210
  val CodecFloor: Int = 85
  val GuardBase: Long = 800000L

  /** The orders `c`'s leading run is written in, within [[MaxPairs]]. */
  def orders(c: Case): Seq[IndexedSeq[Int]] = {
    val q = c.value.query
    val n = ForcedPlans.runLength(q.from)
    if (n < 2) Nil
    else {
      val sizes = sizesOf(c.value)
      ForcedPlans.orders(n, new Rng(c.seed), Sample).filter(o => ForcedPlans.pairsBound(q, o, sizes) <= MaxPairs)
    }
  }

  /** The six strategies, each swapped at every join on a coin `c` draws. */
  def strategies(c: Case): Seq[Strategy] = {
    val rng = new Rng(c.seed)
    ForcedPlans.Strategies.map { case (spelling, spill) => Strategy(spelling, spill, swapped = rng.bool()) }
  }

  /** The rows a FROM leaf scans: a relation's, or for a CTE the most any
    * relation holds. */
  def sizesOf(mc: MetaCase): String => Int = {
    val most = mc.env.relations.map(_.dataset.numRows).maxOption.getOrElse(0)
    name => mc.env.relations.find(_.name == name).map(_.dataset.numRows).getOrElse(most)
  }

  def binds(mc: MetaCase): Boolean =
    try { LogicalBuilder.build(mc.query.renderBody, mc.env.catalog()); true }
    catch { case _: IllegalArgumentException => false }

  /** Over `n` seeds, how often each strategy's printed plan takes each path
    * and each codec, and a report. */
  def pathTally(n: Int): (Map[String, Int], Map[String, Int], String) = {
    val paths = mutable.Map.empty[String, Int].withDefaultValue(0)
    val codecs = mutable.Map.empty[String, Int].withDefaultValue(0)
    var cases = 0
    (0 until n).foreach { i =>
      val c = generate(new Rng(GuardBase + i))
      val q = c.value.query
      if (q.hasAnyJoin && q.isLayoutInvariant && binds(c.value)) {
        cases += 1
        val catalog = c.value.env.catalog()
        val built = LogicalBuilder.build(q.renderBody, catalog)
        strategies(c).foreach { s =>
          try {
            val text = com.transformer.sql.plan.PlanSql.print(ForcedPlans.respelled(built, s), catalog)
            ForcedPlans.joinPaths(catalog, text, s.options).foreach { j =>
              paths(j.path) += 1
              j.codec.foreach(codecs(_) += 1)
            }
          } catch {
            case _: com.transformer.sql.plan.PlanSql.UnprintablePlanException => paths("unprintable") += 1
            case scala.util.control.NonFatal(e) if oracle.CleanRejection.isDataError(e) => paths("failed on its data") += 1
          }
        }
      }
    }
    val report = s"over $n seeds, $cases cases with joins: paths ${paths.toSeq.sorted.mkString(", ")}; codecs " +
      s"${codecs.toSeq.sorted.mkString(", ")}"
    (paths.toMap.withDefaultValue(0), codecs.toMap.withDefaultValue(0), report)
  }

  def deleteRecursively(p: Path): Unit = {
    if (p == null || !Files.exists(p)) return
    if (Files.isDirectory(p)) {
      val s = Files.list(p)
      try { val it = s.iterator(); while (it.hasNext) deleteRecursively(it.next()) }
      finally s.close()
    }
    try Files.deleteIfExists(p) catch { case _: java.io.IOException => () }
  }
}
