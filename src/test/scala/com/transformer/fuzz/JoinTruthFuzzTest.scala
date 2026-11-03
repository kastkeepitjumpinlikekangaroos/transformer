package com.transformer.fuzz

import com.transformer.fuzz.oracle.JoinTruth
import com.transformer.fuzz.oracle.JoinTruth.TruthCase
import com.transformer.sql.plan.JoinKind
import org.junit.Assert._
import org.junit.Test

import scala.collection.mutable

/** Known join answers ([[JoinTruth]], TQS's relation): a wide table split
  * along its keys into a fact table and dimension tables, and queries that
  * walk the keys, each answer worked out from the tables without the engine
  * and held to the engine's.
  *
  * The guard says the corpus reaches what the relation is for: every join
  * kind, rows unmatched on the left and on the right and padded, joins of
  * three tables or more, a NULL key, a WHERE and a GROUP BY, over answers
  * that hold rows.
  */
class JoinTruthFuzzTest {

  import JoinTruthFuzzTest._

  @Test def fuzzJoinTruth(): Unit =
    Props.forAll[TruthCase](
      name = "join-truth",
      gen = JoinTruth.generate,
      shrink = shrink,
      count = Props.seedCountOr(DefaultSeeds)
    ) { tc => JoinTruth.check(tc); () }

  /** Over a block of seeds, the queries checked reach each join kind, a row
    * padded on each side, three tables or more and a NULL key, and hold
    * rows. Runs each query once. */
  @Test def queriesReachEveryKindAndBothUnmatchedSides(): Unit = {
    val tally = mutable.Map.empty[String, Int].withDefaultValue(0)
    (0 until GuardSeeds).foreach { i =>
      val tc = JoinTruth.generate(new Rng(GuardBase + i))
      JoinTruth.check(tc).zip(tc.queries).foreach { case (c, q) =>
        tally("queries") += 1
        if (c.rows > 0) tally("with rows") += 1
        if (c.tables >= 3) tally("three tables or more") += 1
        if (q.where.isDefined) tally("a WHERE") += 1
        q.output match {
          case _: JoinTruth.Grouped => tally("a GROUP BY") += 1
          case _ => ()
        }
        c.facts.foreach { f =>
          tally(s"${f.kind} join") += 1
          if (f.leftPadded) tally("left rows padded") += 1
          if (f.rightPadded) tally("right rows padded") += 1
          if (f.nullKey) tally("a NULL key") += 1
        }
      }
    }
    val report = s"over $GuardSeeds seeds: ${tally.toSeq.sorted.mkString(", ")}"
    println(s"[join-truth] $report")
    Seq(JoinKind.Inner, JoinKind.Left, JoinKind.Right, JoinKind.Full).foreach(k =>
      assertTrue(s"a $k join is rarely checked: $report", tally(s"$k join") > KindFloor))
    Seq("left rows padded", "right rows padded", "three tables or more", "a NULL key", "a WHERE", "a GROUP BY")
      .foreach(k => assertTrue(s"rarely $k: $report", tally(k) > ShapeFloor))
    assertTrue(s"too few answers hold rows: $report", tally("with rows") > tally("queries") / 2)
  }

  @Test def sameSeedReproduces(): Unit =
    assertEquals(JoinTruth.generate(new Rng(42)).toString, JoinTruth.generate(new Rng(42)).toString)

  /** The evaluator's own answers on a split written out by hand: an inner
    * join keeps the matched rows, a LEFT join pads a fact with no dimension
    * row (a NULL key and one no row has), a RIGHT join pads a dimension row
    * no fact keys, a FULL join both, and a WHERE over a padded column is
    * UNKNOWN for it. */
  @Test def theEvaluatorKnowsEachJoinsAnswer(): Unit = {
    import com.transformer.core.DataType
    import JoinTruth._
    val f = Table("f", Vector("id" -> DataType.IntType, "m" -> DataType.IntType, "t" -> DataType.StringType,
      "k1" -> DataType.IntType), Vector(Vector(1, 10, "a", 1), Vector(2, 20, "b", null), Vector(3, 30, "c", 9)))
    val d1 = Table("d1", Vector("k1" -> DataType.IntType, "a1" -> DataType.IntType, "s1" -> DataType.StringType),
      Vector(Vector(1, 5, "x"), Vector(2, 6, "y")))
    val split = Split(Vector(f, d1), Vector(Edge("f", "k1", "d1", "k1")))
    def q(kind: JoinKind, where: Option[Pred] = None) = TruthQuery("f", "f",
      Vector(JoinStep(kind, "d1", "d1", Col("f", "k1", DataType.IntType), Col("d1", "k1", DataType.IntType))), where,
      Projection(Vector(Col("f", "id", DataType.IntType), Col("d1", "a1", DataType.IntType))))
    def rows(query: TruthQuery): Set[Seq[Any]] = expected(split, query)._2.map(_.toSeq).toSet
    assertEquals(Set(Seq(1, 5)), rows(q(JoinKind.Inner)))
    assertEquals(Set(Seq(1, 5), Seq(2, null), Seq(3, null)), rows(q(JoinKind.Left)))
    assertEquals(Set(Seq(1, 5), Seq(null, 6)), rows(q(JoinKind.Right)))
    assertEquals(Set(Seq(1, 5), Seq(2, null), Seq(3, null), Seq(null, 6)), rows(q(JoinKind.Full)))
    assertEquals(Set(Seq(1, 5)), rows(q(JoinKind.Full, Some(Cmp(Col("d1", "a1", DataType.IntType), "<", 6)))))
    assertEquals(Set(Seq(1, 5), Seq(3, null)),
      rows(q(JoinKind.Left, Some(Or(Cmp(Col("f", "t", DataType.StringType), "<>", "b"), IsNull(Col("f", "m", DataType.IntType), negated = false))))))
    JoinTruth.check(TruthCase(split, Vector(q(JoinKind.Inner), q(JoinKind.Left), q(JoinKind.Right), q(JoinKind.Full))))
  }
}

object JoinTruthFuzzTest {

  /** Each case runs about five queries over a handful of rows. */
  val DefaultSeeds: Int = 600
  val GuardSeeds: Int = 1500
  val GuardBase: Long = 600000L
  val KindFloor: Int = 300
  val ShapeFloor: Int = 100

  /** Smaller cases: a query dropped, a fact or a dimension row dropped. A
    * dimension row a fact keys may go: the fact then keys no row, which the
    * relation answers as it answers any such key. */
  def shrink(tc: TruthCase): Iterator[TruthCase] = {
    val fewerQueries = tc.queries.indices.iterator.filter(_ => tc.queries.length > 1)
      .map(i => tc.copy(queries = tc.queries.patch(i, Nil, 1)))
    val fewerRows = for {
      (t, ti) <- tc.split.tables.iterator.zipWithIndex
      r <- t.rows.indices.iterator
    } yield tc.copy(split = tc.split.copy(tables = tc.split.tables.updated(ti, t.copy(rows = t.rows.patch(r, Nil, 1)))))
    fewerQueries ++ fewerRows
  }
}
