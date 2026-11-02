package com.transformer.sql.plan

import com.transformer.core._
import com.transformer.fuzz.DataGen
import com.transformer.slt.Ledger
import org.junit.Assert._
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** [[FunctionCatalog]] against what it must agree with: README.md's lists of
  * functions and CAST types, both ways, read by their bold labels; the binder,
  * which binds every entry at each arity it declares and refuses every other
  * with the catalog's message; the ledger in `docs/gotchas.md`, which has a
  * row for every rule an entry cites; and `Funcs.applyVec`, which has a path
  * for exactly the entries that say so. */
class FunctionCatalogTest {

  import FunctionCatalogTest._

  // ---- README.md ---------------------------------------------------------------

  /** Every name of every entry, aliases included, is in the README's list
    * for its kind. */
  @Test def everyRegisteredNameIsDocumented(): Unit = {
    val missing = for {
      (label, kinds) <- Lists
      documented = Readme.names(label)
      entry <- FunctionCatalog.Entries if kinds(entry)
      name <- entry.names if !documented(name)
    } yield s"$name (${entry.kind}) is not in README's **$label:** list"
    assertTrue(missing.mkString("\n"), missing.isEmpty)
  }

  /** Every name the README's lists document is a function's, and every name
    * they say there is none of (`No FIRST_VALUE ...`) is none. */
  @Test def everyDocumentedNameIsRegistered(): Unit = {
    val wrong = Lists.flatMap { case (label, _) =>
      Readme.names(label).toSeq.sorted.filter(FunctionCatalog.lookup(_).isEmpty)
        .map(n => s"README's **$label:** list documents $n, which no entry of FunctionCatalog names") ++
        Readme.absent(label).toSeq.sorted.filter(FunctionCatalog.lookup(_).isDefined)
          .map(n => s"README's **$label:** list says there is no $n, which FunctionCatalog has")
    }
    assertTrue(wrong.mkString("\n"), wrong.isEmpty)
  }

  /** The README's `**Types in CAST:**` list names exactly the types a CAST
    * takes, a DECIMAL's names written with their precision and scale. */
  @Test def theCastTypesAreTheDocumentedOnes(): Unit = {
    val named = FunctionCatalog.CastTargets.flatMap(_._2).toSet
    val registered = named ++ FunctionCatalog.DecimalCastNames.map(_ + "(p,s)")
    val documented = Readme.castTypes
    assertEquals("README's **Types in CAST:** list against FunctionCatalog.CastTargets and DecimalCastNames",
      registered.toSeq.sorted, documented.toSeq.sorted)
    named.foreach(n => assertTrue(n, FunctionCatalog.castTarget(n.toLowerCase(java.util.Locale.ROOT)).isDefined))
    FunctionCatalog.DecimalCastNames.foreach { n =>
      assertEquals(n, Some(DataType.DecimalType(10, 2)), FunctionCatalog.castTarget(s"${n.toLowerCase(java.util.Locale.ROOT)} (10, 2)"))
      assertEquals(n, None, FunctionCatalog.castTarget(n))
    }
  }

  // ---- the binder ----------------------------------------------------------------

  /** Every name of every entry binds at each arity the entry declares, and is
    * refused at every other with the message the catalog gives it. */
  @Test def everyEntryBindsAtItsAritiesAndIsRefusedAtEveryOther(): Unit = {
    val problems = Seq.newBuilder[String]
    def expect(sql: String, refusal: Option[Option[String]]): Unit = {
      val outcome =
        try { LogicalBuilder.build(sql, catalog()); None }
        catch { case e: IllegalArgumentException => Some(e) }
      (refusal, outcome) match {
        case (None, None) => ()
        case (None, Some(e)) => problems += s"`$sql` was refused: ${e.getMessage}"
        case (Some(_), None) => problems += s"`$sql` bound"
        case (Some(Some(message)), Some(e)) if e.getMessage != message =>
          problems += s"`$sql` was refused with `${e.getMessage}`, not the catalog's `$message`"
        case (Some(None), Some(e)) if !e.isInstanceOf[UnsupportedClauseException] =>
          problems += s"`$sql` was refused by the binder (`${e.getMessage}`), not the rejection pass"
        case _ => ()
      }
    }
    for (entry <- FunctionCatalog.Entries; written <- entry.names) {
      val upper = written
      val top = entry.arity.max.getOrElse(entry.arity.min + 2) + 1
      (0 to top).foreach { n =>
        val declared = entry.arity.admits(n)
        val args = arguments(entry, n)
        val types = Seq.fill(n)(DataType.IntType)
        entry.kind match {
          case FunctionCatalog.Kind.Scalar | FunctionCatalog.Kind.Flow | FunctionCatalog.Kind.Operator =>
            expect(s"SELECT $written($args) AS v FROM t", if (declared) None else Some(Some(
              entry.refusedArities.getOrElse(n, FunctionCatalog.unknownFunction(written, types)))))
          case FunctionCatalog.Kind.Clock =>
            // `CURRENT_DATE ()`, with a space, is a call; written alone it is the keyword.
            expect(s"SELECT $written ($args) AS v FROM t", if (declared) None else Some(Some(
              FunctionCatalog.unknownFunction(written, types))))
            if (n == 0) expect(s"SELECT $written AS v FROM t", None)
          case FunctionCatalog.Kind.Aggregate =>
            expect(s"SELECT $written($args) AS v FROM t", if (declared) None else Some(Some(
              entry.refusedArities.getOrElse(n, FunctionCatalog.unsupportedAggregate(written, n)))))
            if (n <= 3) expect(s"SELECT $written($args) OVER (ORDER BY a) AS v FROM t",
              if (!entry.window) Some(if (n <= 1) Some(FunctionCatalog.unsupportedWindow(upper)) else None)
              else if (declared) None
              else if (n == 0) Some(Some(entry.refusedArities.getOrElse(0, FunctionCatalog.requiresArgument(upper))))
              else Some(None))
          case FunctionCatalog.Kind.Ranking | FunctionCatalog.Kind.Offset =>
            if (n <= 3) expect(s"SELECT $written($args) OVER (ORDER BY a) AS v FROM t",
              if (declared) None
              else if (n == 0) Some(Some(FunctionCatalog.requiresArgument(upper)))
              else Some(None))
            // Outside OVER a window function is no function at all.
            if (declared) expect(s"SELECT $written($args) AS v FROM t",
              Some(Some(FunctionCatalog.unknownFunction(written, entry.paramsAt(n).map(_ => DataType.IntType)))))
        }
      }
      if (entry.star) {
        expect(s"SELECT $written(*) AS v FROM t", None)
        if (entry.window) expect(s"SELECT $written(*) OVER () AS v FROM t", None)
      }
      if (entry.kind == FunctionCatalog.Kind.Aggregate)
        expect(s"SELECT a FROM t WHERE $written(${arguments(entry, entry.arity.min)}) IS NULL",
          Some(Some(FunctionCatalog.aggregateOutOfPlace(written))))
      // TRIM's node of its own takes no OVER: the parser refuses it.
      if (FunctionCatalog.call(written).isDefined && entry.arity.admits(1) && written != "TRIM")
        expect(s"SELECT $written(${arguments(entry, 1)}) OVER () AS v FROM t",
          Some(Some(FunctionCatalog.unsupportedWindow(upper))))
    }
    expect("SELECT NO_SUCH_FUNCTION(a) AS v FROM t", Some(Some(FunctionCatalog.unknownFunction("NO_SUCH_FUNCTION",
      Seq(DataType.IntType)))))
    val all = problems.result()
    assertTrue(s"${all.size} problems:\n${all.take(40).mkString("\n")}", all.isEmpty)
  }

  /** A call's alias is the function it names: one signature, one answer. */
  @Test def anAliasBindsAsItsName(): Unit =
    for (entry <- FunctionCatalog.Entries if entry.aliases.nonEmpty; alias <- entry.aliases) {
      assertEquals(alias, Some(entry), FunctionCatalog.lookup(alias.toLowerCase(java.util.Locale.ROOT)))
      if (FunctionCatalog.signature(entry.name, Seq(LitExpr(2.5, DataType.DoubleType))).isDefined) {
        val args = Seq(LitExpr(2.5, DataType.DoubleType))
        assertEquals(alias, FunctionCatalog.signature(entry.name, args), FunctionCatalog.signature(alias, args))
        assertEquals(alias, Funcs(entry.name, Seq(2.5), DataType.DoubleType, Seq(DataType.DoubleType)),
          Funcs(alias, Seq(2.5), DataType.DoubleType, Seq(DataType.DoubleType)))
      }
    }

  // ---- the ledger and the executor -------------------------------------------------

  /** Every rule an entry, a domain or an error cites is a row of the ledger. */
  @Test def everyCitedRuleIsARowOfTheLedger(): Unit = {
    val ledger = Ledger.load(Ledger.DefaultPath)
    val cited = FunctionCatalog.Entries.flatMap(e =>
      e.rules.map(e.name -> _) ++ e.domain.flatMap(_.rule).map(e.name -> _) ++ e.errors.flatMap(_.rule).map(e.name -> _))
    val unknown = cited.filter { case (_, id) => ledger.rule(id).isEmpty }
    assertTrue(s"rules the ledger has no row for: $unknown", unknown.isEmpty)
    assertTrue("the catalog cites no rule", cited.nonEmpty)
  }

  /** `Funcs.applyVec` has a path for exactly the entries whose `vectorPath`
    * says so, under each of their names. */
  @Test def theVectorPathIsWhereTheCatalogSaysItIs(): Unit = {
    val batch = ColumnarBatch.fromColumns(Schema(Vector.empty), Array.empty, 1)
    for (entry <- FunctionCatalog.Entries if entry.kind == FunctionCatalog.Kind.Scalar ||
        entry.kind == FunctionCatalog.Kind.Flow; name <- entry.names) {
      val n = (entry.arity.min to entry.arity.max.getOrElse(entry.arity.min + 1)).find(_ > 0).getOrElse(0)
      val args = entry.paramsAt(n).map {
        case Funcs.Param.Text => LitExpr("x", DataType.StringType)
        case Funcs.Param.Condition => LitExpr(true, DataType.BooleanType)
        case _ => LitExpr(2, DataType.IntType)
      }
      val vector = Funcs.applyVec(name, args, entry.resultType(args), batch)
      assertEquals(s"$name has a vector path", entry.vectorPath, vector.isDefined)
    }
  }

  /** The catalog's size by kind, for the record. */
  @Test def theCatalogHasAnEntryOfEachKind(): Unit = {
    val byKind = FunctionCatalog.Kind.All.map(k => k -> FunctionCatalog.Entries.filter(_.kind == k))
    println("[function-catalog] " + byKind.map { case (k, es) =>
      s"$k: ${es.size} entries, ${es.map(_.names.size).sum} names"
    }.mkString("; "))
    byKind.foreach { case (k, es) => assertTrue(s"no $k entry", es.nonEmpty) }
  }
}

object FunctionCatalogTest {

  /** Each README list, by its bold label, and the entries it documents. */
  private val Lists: Seq[(String, FunctionCatalog.Entry => Boolean)] = Seq(
    "Scalar functions" -> (e => FunctionCatalog.call(e.name).isDefined),
    "Aggregates" -> (_.kind == FunctionCatalog.Kind.Aggregate),
    "Window functions" -> (_.window))

  /** `t(a INT, b BIGINT, d DOUBLE, s STRING, f BOOLEAN)`, one row. */
  private def catalog(): Catalog = {
    val c = new Catalog
    val schema = Schema(Vector(Field("a", DataType.IntType), Field("b", DataType.LongType),
      Field("d", DataType.DoubleType), Field("s", DataType.StringType), Field("f", DataType.BooleanType)))
    c.register("t", DataGen.Dataset(schema, IndexedSeq(Array[Any](1, 2L, 2.5, "x", true))).singlePartition())
    c
  }

  /** `n` arguments for a call of `entry`, each of the kind its parameter
    * takes where the entry takes `n`, and an INT column otherwise. */
  private def arguments(entry: FunctionCatalog.Entry, n: Int): String =
    if (!entry.arity.admits(n)) Seq.fill(n)("a").mkString(", ")
    else entry.paramsAt(n).map {
      case Funcs.Param.Text => "s"
      case Funcs.Param.Condition => "f"
      case Funcs.Param.Integer(_) => "1"
      case _ => "a"
    }.mkString(", ")

  /** README.md's lists, each the paragraph that starts with its bold label,
    * up to the next blank line. Read by label, never by line, since the
    * README is edited by hand. */
  private object Readme {

    private lazy val text: String =
      new String(Files.readAllBytes(Paths.get("README.md")), StandardCharsets.UTF_8).replace("\r\n", "\n")

    def paragraph(label: String): String = {
      val marker = s"**$label:**"
      val start = text.indexOf(marker)
      assertTrue(s"README.md has no paragraph that starts $marker", start >= 0)
      assertEquals(s"README.md has two paragraphs that start $marker", -1, text.indexOf(marker, start + 1))
      val end = text.indexOf("\n\n", start)
      text.substring(start + marker.length, if (end < 0) text.length else end)
    }

    private val CodeSpan = "`([^`]+)`".r
    private val Name = """\b[A-Z][A-Z0-9_]*\b""".r
    /** A run of code spans right after "no", as in "there is no `LTRIM`/`RTRIM`"
      * and "No `FIRST_VALUE`/... yet": names the list says there are none of. */
    private val Absent = """(?i)\bno\s+((?:`[^`]+`(?:\s*(?:/|,|or|and)\s*)?)+)""".r

    /** The upper-case names in the code spans of `label`'s list, but for the
      * words of SQL's grammar and its types, and the names it says are
      * absent. */
    def names(label: String): Set[String] = {
      val p = paragraph(label)
      CodeSpan.findAllMatchIn(p).flatMap(m => Name.findAllIn(m.group(1))).toSet -- Vocabulary -- absent(label)
    }

    def absent(label: String): Set[String] =
      Absent.findAllMatchIn(paragraph(label)).flatMap(m =>
        CodeSpan.findAllMatchIn(m.group(1)).flatMap(s => Name.findAllIn(s.group(1)))).toSet

    /** The types the `**Types in CAST:**` list's first sentence names, each
      * code span one name. */
    def castTypes: Set[String] = {
      val p = paragraph("Types in CAST")
      val firstSentence = p.substring(0, """\.\s""".r.findFirstMatchIn(p).map(_.start).getOrElse(p.length))
      CodeSpan.findAllMatchIn(firstSentence).map(_.group(1)).toSet
    }

    /** The words of SQL's grammar and its types that the lists write in code
      * spans beside the functions' names. */
    private val Vocabulary: Set[String] = Set(
      "INT", "INTEGER", "BIGINT", "LONG", "FLOAT", "REAL", "DOUBLE", "PRECISION", "STRING", "VARCHAR", "TEXT", "CHAR",
      "BOOLEAN", "BOOL", "DATE", "TIMESTAMP", "TIME", "DECIMAL", "BINARY", "NULL", "TRUE", "FALSE",
      "DISTINCT", "OVER", "PARTITION", "ORDER", "BY", "ASC", "DESC", "NULLS", "FIRST", "LAST", "ROWS", "RANGE",
      "BETWEEN", "AND", "OR", "NOT", "UNBOUNDED", "PRECEDING", "FOLLOWING", "CURRENT", "ROW", "LEADING", "TRAILING",
      "BOTH", "FROM", "CAST", "AS", "SELECT", "WHERE", "GROUP", "HAVING", "CASE", "WHEN", "THEN", "ELSE", "END",
      "IN", "IS", "LIKE", "ILIKE", "ALL")
  }
}
