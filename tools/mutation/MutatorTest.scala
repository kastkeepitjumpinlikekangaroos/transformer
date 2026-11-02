package com.transformer.tools.mutation

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import org.junit.Assert._
import org.junit.Test

/** The lexer and the mutator over the fixtures in `testdata/`: one source per
  * operator, each with sites the operator must find and look-alikes it must
  * not, and one whose operator characters are all inside strings, characters,
  * comments and backquoted names. */
class MutatorTest {
  private def fixture(name: String): String =
    Files.readString(Paths.get(s"tools/mutation/testdata/$name.scala.txt"), StandardCharsets.UTF_8)

  private def sitesOf(name: String, operator: String): Vector[String] =
    Mutator.sites(fixture(name)).filter(_.operator == operator).map(_.describe)

  private def tokensOnLine(src: String, line: Int): Vector[Token] = Lexer.tokens(src).filter(_.line == line)

  @Test def aStringACharacterACommentAndABackquotedNameAreEachOneToken(): Unit = {
    val src = fixture("lexer")
    def kinds(line: Int) = tokensOnLine(src, line).map(_.kind)
    assertEquals(Vector(Token.Comment), kinds(6))
    assertEquals("a nested block comment ends at its own end", Vector(Token.Comment), kinds(7))
    assertEquals(Vector(Token.Ident, Token.Ident, Token.Operator, Token.StringLit), kinds(8))
    assertEquals("an escaped quote and an escaped backslash stay inside",
      Vector(Token.Ident, Token.Ident, Token.Operator, Token.StringLit, Token.Operator, Token.StringLit), kinds(9))
    val triple = tokensOnLine(src, 10).last
    assertEquals(Token.StringLit, triple.kind)
    assertTrue("a triple-quoted string runs over its line", triple.text.endsWith("0 * 1 \"\"\""))
    assertEquals("quotes before a triple quote's end are inside it",
      Vector(Token.Ident, Token.Ident, Token.Operator, Token.StringLit, Token.Operator, Token.StringLit), kinds(12))
    assertEquals("an interpolation's code, braces and strings are inside its string",
      Vector(Token.Ident, Token.Ident, Token.Operator, Token.StringLit), kinds(13))
    assertEquals("an interpolation inside an interpolation",
      Vector(Token.Ident, Token.Ident, Token.Operator, Token.StringLit), kinds(14))
    assertEquals(Vector(Token.Ident, Token.Ident, Token.Operator, Token.StringLit), kinds(15))
    (16 to 19).foreach { line =>
      assertEquals(s"line $line", Vector(Token.Ident, Token.Ident, Token.Operator, Token.CharLit), kinds(line))
    }
    assertEquals(Vector(Token.Ident, Token.Backquoted, Token.Operator, Token.IntLit), kinds(20))
  }

  @Test def nothingInsideALiteralACommentOrABackquotedNameIsASite(): Unit =
    assertEquals(
      Vector("20:17 constant 1 -> 0", "21:21 relational >= -> >", "21:24 constant 0 -> 1"),
      Mutator.sites(fixture("lexer")).map(_.describe))

  @Test def numbers(): Unit = {
    val kinds = Lexer.tokens("1 1L 0x1F 1.5 1e3 2f 1.max(2) 1_000").map(t => t.text -> t.kind)
    assertEquals(
      Vector("1" -> Token.IntLit, "1L" -> Token.IntLit, "0x1F" -> Token.IntLit, "1.5" -> Token.FloatLit,
        "1e3" -> Token.FloatLit, "2f" -> Token.FloatLit, "1" -> Token.IntLit, "." -> Token.Delimiter,
        "max" -> Token.Ident, "(" -> Token.Delimiter, "2" -> Token.IntLit, ")" -> Token.Delimiter,
        "1_000" -> Token.IntLit),
      kinds)
  }

  @Test def anUnclosedStringFails(): Unit = {
    val e = assertThrows(classOf[IllegalArgumentException], () => { val _ = Lexer.tokens("val a = \"open\nval b = 1") })
    assertTrue(e.getMessage, e.getMessage.startsWith("line 1:"))
  }

  @Test def relational(): Unit = assertEquals(
    Vector("2:38 relational < -> <=", "3:38 relational <= -> <", "3:48 relational > -> >=",
      "3:57 relational >= -> >", "4:38 relational == -> !=", "4:48 relational != -> =="),
    sitesOf("relational", "relational"))

  @Test def logical(): Unit = assertEquals(
    Vector("2:46 logical && -> ||", "2:51 logical || -> &&", "2:54 logical ! -> ", "3:32 logical ! -> ",
      "3:36 logical && -> ||", "3:39 logical ! -> ", "7:12 logical ! -> "),
    sitesOf("logical", "logical"))

  @Test def arithmetic(): Unit = assertEquals(
    Vector("2:34 arithmetic + -> -", "2:38 arithmetic - -> +", "2:42 arithmetic * -> /", "2:46 arithmetic / -> *",
      "3:26 arithmetic + -> -", "3:28 arithmetic 1 -> 0", "4:28 arithmetic - -> +", "4:30 arithmetic 1L -> 0L",
      "6:27 arithmetic + -> -", "10:10 arithmetic 1 -> 0", "11:10 arithmetic 1 -> 0"),
    sitesOf("arithmetic", "arithmetic"))

  @Test def constant(): Unit = assertEquals(
    Vector("3:14 constant 0 -> 1", "4:13 constant 1L -> 0L", "5:14 constant true -> false",
      "6:15 constant false -> true", "11:33 constant 0 -> 1", "11:41 constant 1 -> 0"),
    sitesOf("constant", "constant"))

  @Test def extremum(): Unit = assertEquals(
    Vector("2:37 extremum max -> min", "3:40 extremum min -> max", "6:43 extremum min -> max"),
    sitesOf("extremum", "extremum"))

  @Test def checked(): Unit = assertEquals(
    Vector("2:32 checked Math.addExact(a, b) -> (a + b)",
      "3:35 checked Math.multiplyExact(a, f(2, 3).toLong) -> (a * f(2, 3).toLong)",
      "4:26 checked Math.negateExact(a) -> (-(a))", "5:25 checked Math.toIntExact(a) -> (a).toInt"),
    sitesOf("checked", "checked"))

  @Test def statement(): Unit = assertEquals(
    Vector("3:5 statement w.write(\"x\") -> ", "4:5 statement dir.foreach(_.close()) -> ",
      "8:5 statement w.abort() -> ", "18:7 statement println(\"none\") -> ", "23:5 statement w.flush() -> ",
      "27:5 statement w.flush() -> ", "31:5 statement w.flush() -> ", "32:5 statement w.close() -> "),
    sitesOf("statement", "statement"))

  @Test def refusal(): Unit = assertEquals(
    Vector("2:36 refusal throw new IllegalArgumentException(s\"negative " + "$" + "a\") -> ()",
      "6:7 refusal throw new IllegalArgumentException( \"too big\") -> ()",
      "13:37 refusal throw overflow(a) -> ()"),
    sitesOf("refusal", "refusal"))

  private val fixtures = Seq("lexer", "relational", "logical", "arithmetic", "constant", "extremum", "checked",
    "statement", "refusal")

  /** A mutant is its source with its site's tokens replaced by the
    * replacement's, and nothing else changed; a site of the token operators
    * is one token, which the mutant changes or (a dropped `!`) removes. */
  @Test def eachMutantDiffersFromItsSourceInItsSiteAlone(): Unit = fixtures.foreach { name =>
    val src = fixture(name)
    val tokens = Lexer.tokens(src)
    val before = tokens.map(_.text)
    Mutator.sites(src).foreach { s =>
      val what = s"$name ${s.describe}"
      val first = tokens.indexWhere(_.start == s.start)
      val count = tokens.indexWhere(_.end == s.end) - first + 1
      assertTrue(what, first >= 0 && count >= 1)
      val replacement = Lexer.tokens(s.replacement).map(_.text)
      val after = Lexer.tokens(s.applyTo(src)).map(_.text)
      assertEquals(what, before.take(first) ++ replacement ++ before.drop(first + count), after)
      if (!Set("checked", "statement", "refusal")(s.operator)) {
        assertEquals(what, 1, count)
        assertTrue(what, replacement.size <= 1)
      }
    }
  }

  @Test def eachOperatorHasAFixture(): Unit =
    assertEquals(Mutator.Operators.toSet, fixtures.flatMap(f => Mutator.sites(fixture(f)).map(_.operator)).toSet)

  @Test def aSampleTakesEachOperatorInTurnAndIsFixedBySeed(): Unit = {
    val src = fixture("arithmetic") + fixture("relational").replace("object Relational", "object R2")
    val sites = Mutator.sites(src)
    val sample = Sample.stratified("f.scala", sites, 6, 7L)
    assertEquals(6, sample.size)
    val byOperator = sample.groupBy(_.operator).map { case (op, ss) => op -> ss.size }
    assertEquals("6 sites over 4 operators: 2, 2, 1, 1", Seq(1, 1, 2, 2), byOperator.values.toSeq.sorted)
    assertEquals(sample, Sample.stratified("f.scala", sites, 6, 7L))
    assertEquals("a quota past the sites takes them all", sites.toSet, Sample.stratified("f.scala", sites, 999, 7L).toSet)
  }

  @Test def theLedgerLeavesOutAnEquivalentMutantByItsLineText(): Unit = {
    val src = fixture("relational")
    val equivalent = Equivalent("r.scala", "relational", "<", "<=",
      "def f(a: Int, b: Int): Boolean = a < b", "a fixture's reason")
    val all = Sample.mutants(Seq("r.scala" -> src), None, 1L, Nil)
    val kept = Sample.mutants(Seq("r.scala" -> ("\n\n" + src)), None, 1L, Seq(equivalent))
    assertEquals(all.size - 1, kept.size)
    assertFalse(kept.exists(m => m.site.original == "<" && m.site.replacement == "<="))
  }
}
