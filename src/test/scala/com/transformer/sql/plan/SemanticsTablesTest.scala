package com.transformer.sql.plan

import com.transformer.core.DataType
import com.transformer.fuzz.oracle.CleanRejection
import com.transformer.sql.plan.Semantics.Generated
import org.junit.Assert._
import org.junit.Test

/** The semantics tables under `src/test/semantics` ([[SemanticsTables]]):
  * each is generated in memory and must be its file line for line, a
  * difference failing with its unified diff; the row path and the vector path
  * agree on every cell; and every error a cell shows is a clean one by
  * `CleanRejection`'s rule, refused at bind time in words that name the SQL,
  * or failing on a value with a message it knows. */
class SemanticsTablesTest {

  import SemanticsTablesTest._
  import CoercionMatrix.{Cell, Refused, Typed}

  @Test def everyTableIsItsFile(): Unit = {
    val diffs = generated.flatMap { case (file, g) =>
      Semantics.diff(file, SemanticsTables.read(SemanticsTables.Dir, file), g.lines)
    }
    assertTrue(diffs.mkString("\n") + "\nIf the rule changed on purpose, regenerate the tables with\n" +
      "  bazel run //src/test/scala/com/transformer/sql/plan:semantics_tables -- --update\n" +
      "and review the diff in the same commit.", diffs.isEmpty)
  }

  /** Every cell is one answer on both paths (both layouts, for an aggregate
    * or a window), and the function sweep's every NULL and run-time error is
    * one its FunctionCatalog entry explains. */
  @Test def everyCellIsOneAnswerItsEntryExplains(): Unit = {
    val wrong = generated.flatMap { case (file, g) => g.diverged.map(l => s"$file: $l") }
    assertTrue(s"${wrong.size} cells whose paths disagree, or whose NULL or error its entry does not explain:\n" +
      wrong.take(20).mkString("\n"), wrong.isEmpty)
  }

  // ---- the coercion tables' properties -------------------------------------

  /** Every construct of a family gives one cell per pair: the comparisons
    * meet as one type, the branches become one, and arithmetic's operators
    * agree but on a DECIMAL, whose type each declares as its own
    * (`Decimals.resultType`, the ledger's decimal-result-type). */
  @Test def everyConstructOfAFamilyGivesOneCell(): Unit = {
    val problems = for {
      family <- CoercionMatrix.Families
      ((a, b), cells) <- CoercionMatrix.cells(family)
      shown = family.constructs.zip(cells).map { case (c, (_, cell)) => same(c, cell) }.distinct
      if shown.size > 1
      if !(family == CoercionMatrix.Arithmetic && cells.forall(c => isDecimal(c._2)))
    } yield s"${family.file}: $a, $b: ${shown.mkString(" / ")}"
    assertTrue(problems.mkString("\n"), problems.isEmpty)
  }

  /** A pair gives the same cell in either order, `THEN a ELSE b` as `THEN b
    * ELSE a`: one type, or a refusal, whose message may name the operand it
    * read first (`Arithmetic on BINARY` or `on BOOLEAN`). */
  @Test def aPairGivesOneCellInEitherOrder(): Unit = {
    val problems = for {
      family <- CoercionMatrix.Families
      byPair = CoercionMatrix.cells(family).toMap
      ((a, b), cells) <- byPair.toSeq
      (construct, i) <- family.constructs.zipWithIndex
      (x, y) = (same(construct, cells(i)._2), same(construct, byPair((b, a))(i)._2))
      if x != y && !(cells(i)._2.isInstanceOf[Refused] && byPair((b, a))(i)._2.isInstanceOf[Refused])
      // Arithmetic declares a quotient's or a remainder's DECIMAL by which side is which.
      if !(family == CoercionMatrix.Arithmetic && isDecimal(cells(i)._2))
    } yield s"${family.file}: ${construct.heading}: $a, $b gives $x and $b, $a gives $y"
    assertTrue(problems.sorted.mkString("\n"), problems.isEmpty)
  }

  /** The three families give a pair one cell, but where a rule of
    * [[SemanticsTablesTest.ExpectedDifferences]] says two of them differ, or
    * a finding of [[SemanticsTablesTest.KnownFindings]] shows; and each
    * finding still shows, so one cannot outlive its fix. Each family's cell
    * is its first construct's, arithmetic's that of `+`. */
  @Test def theFamiliesDifferOnlyWhereARuleSays(): Unit = {
    def cellsOf(f: CoercionMatrix.Family) = CoercionMatrix.cells(f).map { case (pair, cs) => pair -> cs.head._2 }.toMap
    val families = Map("comparisons" -> cellsOf(CoercionMatrix.Comparisons),
      "branches" -> cellsOf(CoercionMatrix.Branches), "arithmetic" -> cellsOf(CoercionMatrix.Arithmetic))
    def agree(x: Cell, y: Cell) = (x, y) match {
      case (Typed(s), Typed(t)) => s == t
      case (_: Refused, _: Refused) => true
      case _ => false
    }
    val explained = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    val problems = for {
      (f, g) <- Seq("comparisons" -> "branches", "branches" -> "arithmetic", "comparisons" -> "arithmetic")
      pair <- families(f).keys.toSeq
      (x, y) = (families(f)(pair), families(g)(pair))
      if !agree(x, y)
      rule = (ExpectedDifferences ++ KnownFindings).find(d => d.families == (f, g) && d.explains(pair, x, y))
      _ = rule.foreach(r => explained(r.id) += 1)
      if rule.isEmpty
    } yield s"${pair._1}, ${pair._2}: $f ${show(x)}, $g ${show(y)}"
    println("[semantics] differences between the families by rule: " +
      (ExpectedDifferences ++ KnownFindings).map(_.id).distinct.map(id => s"$id=${explained(id)}").mkString(", "))
    assertTrue(s"${problems.size} pairs two families type differently, by no listed rule:\n" +
      problems.sorted.mkString("\n"), problems.isEmpty)
    KnownFindings.foreach { f =>
      assertTrue(s"the finding ${f.id} no longer shows; if it was fixed, remove it: ${f.decision}", explained(f.id) > 0)
    }
  }

  private def show(c: Cell): String = c match {
    case Typed(t) => DataType.sqlName(t)
    case Refused(e) => s"refused (${Semantics.message(e)})"
  }

  @Test def everyErrorIsClean(): Unit = {
    val unclean = for {
      (file, g) <- generated
      r <- g.raised
      why <- try { CleanRejection.classify(r.sql, r.phase, r.error); None }
             catch { case e: AssertionError => Some(e.getMessage) }
    } yield s"$file: $why"
    assertTrue(s"${unclean.size} errors that are not clean:\n${unclean.distinct.take(20).mkString("\n")}", unclean.isEmpty)
    assertTrue("no table shows an error", generated.exists(_._2.raised.nonEmpty))
  }
}

object SemanticsTablesTest {

  import CoercionMatrix.{Cell, Refused, Typed}
  import Semantics.{Column, Kind}

  /** A family's cell for a pair, read the same across its constructs: its
    * type, or its refusal with the construct's name left out. */
  def same(construct: CoercionMatrix.Construct, c: Cell): String = CoercionMatrix.shown(construct, c)

  def isDecimal(c: Cell): Boolean = c match {
    case Typed(_: DataType.DecimalType) => true
    case _ => false
  }

  def decimalKind(k: Kind): Boolean = k match {
    case Column(_: DataType.DecimalType) => true
    case _ => false
  }

  def floatColumn(k: Kind): Boolean =
    k == Column(DataType.FloatType) || k == Column(DataType.DoubleType)

  def integerColumn(k: Kind): Boolean =
    k == Column(DataType.IntType) || k == Column(DataType.LongType)

  /** One rule by which two families may give a pair different cells: its
    * id, the decision it follows, the families it reads (the first, then
    * the second) and whether it explains the pair's cells `x` and `y` there. */
  final case class Difference(id: String, decision: String, families: (String, String),
      explains: ((Kind, Kind), Cell, Cell) => Boolean)

  private val (cmp, branch, arith) = ("comparisons", "branches", "arithmetic")

  private def nonNumeric(c: Cell) = c match {
    case Typed(t) => !DataType.isNumeric(t)
    case _ => false
  }

  private def decimalBesideFloat(pair: (Kind, Kind)) =
    (decimalKind(pair._1) && floatColumn(pair._2)) || (decimalKind(pair._2) && floatColumn(pair._1))

  /** Where two families may differ, each by a rule the user decided. */
  val ExpectedDifferences: Seq[Difference] = Seq(
    Difference("decimal-beside-float",
      "a DECIMAL beside a FLOAT or DOUBLE that is not written in the query compares, as the decimal each value " +
        "prints as, but is refused where it would be kept, in a branch and in arithmetic (plans/robustness/04, 09)",
      (cmp, branch), (pair, x, y) => decimalBesideFloat(pair) && isDecimal(x) && y.isInstanceOf[Refused]),
    Difference("decimal-beside-float", "as above, arithmetic's side",
      (cmp, arith), (pair, x, y) => decimalBesideFloat(pair) && isDecimal(x) && y.isInstanceOf[Refused]),
    Difference("decimal-result-type",
      "arithmetic declares a DECIMAL as Spark and SQL Server do (Decimals.resultType, the ledger's " +
        "decimal-result-type), where a branch takes the DECIMAL that holds both (Decimals.holding) and a " +
        "comparison reads both exactly",
      (branch, arith), (_, x, y) => isDecimal(x) && isDecimal(y)),
    Difference("decimal-result-type", "as above, beside a comparison", (cmp, arith), (_, x, y) => isDecimal(x) && isDecimal(y)),
    Difference("arithmetic-on-numbers",
      "arithmetic is on numbers alone, so a STRING (the ledger's refused-text-arithmetic), a BOOLEAN, a DATE, a " +
        "TIMESTAMP or a BINARY that compares and becomes one result is refused there (plans/robustness/11)",
      (branch, arith), (_, x, y) => nonNumeric(x) && y.isInstanceOf[Refused]),
    Difference("arithmetic-on-numbers", "as above, beside a comparison",
      (cmp, arith), (_, x, y) => nonNumeric(x) && y.isInstanceOf[Refused]))

  /** Differences no decision covers, each a finding held for the user. Each
    * must still show, or the test fails, so one cannot outlive its fix. */
  val KnownFindings: Seq[Difference] = Seq(
    Difference("integer-beside-decimal",
      "plans/testing/09 finding: a comparison types an INT or BIGINT column beside a DECIMAL as that DECIMAL's own " +
        "type, reading the integer exactly at its scale, where a branch takes the DECIMAL that holds both: " +
        "DECIMAL(5,2) beside INT compares as DECIMAL(5,2) and becomes one result as DECIMAL(12,2)",
      (cmp, branch), { case ((a, b), x, y) =>
        ((decimalKind(a) && integerColumn(b)) || (decimalKind(b) && integerColumn(a))) && isDecimal(x) && isDecimal(y)
      }))

  /** Every table, generated once for the test's every check. */
  lazy val generated: Seq[(String, Generated)] = SemanticsTables.Tables.map { case (file, g) => file -> g() }
}
