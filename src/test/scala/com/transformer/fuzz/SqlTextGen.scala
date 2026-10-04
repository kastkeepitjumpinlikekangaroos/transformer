package com.transformer.fuzz

import com.transformer.core._
import com.transformer.sql.plan.UnsupportedClauses

import java.util.Locale

/** Generator of SQL TEXT, for the frontend properties (`oracle/CleanRejection`,
  * `oracle/NoSilentDrop`, `oracle/SyntaxEquivalence`).
  *
  * [[QueryGen]] and [[MetaQueryGen]] build a typed AST and render it, so their
  * corpus is exactly the grammar they know: they can never write a clause the
  * engine does not implement, a second spelling of one it does, or a query that
  * does not parse, and a query the engine mis-binds or silently truncates never
  * gets built. This generator writes text instead, over a fixed small catalog,
  * in four modes:
  *
  *   - '''Unsupported clause.''' Every entry of `UnsupportedClauses.audits`
  *     with an example, read from src/main so this corpus cannot drift from the
  *     rejection pass: the example itself in every context a query can sit in
  *     ([[Contexts]]), and, for an entry on an expression class, the fragment
  *     that carries the clause at every position an expression can sit in
  *     ([[ValuePositions]], [[PredicatePositions]], [[StarPositions]]). A new
  *     entry in the table is new coverage here without a change to this file.
  *   - '''Honoured clause.''' A clause the binder reads and that the table no
  *     longer lists because plan 01 honoured it (OFFSET, `NULLS LAST`,
  *     `SUM(DISTINCT x)`, ILIKE, ESCAPE, FETCH FIRST, TOP, a HAVING with no GROUP
  *     BY, ...), with its clause-free twin, so a context that reads the clause
  *     in one place and drops it in another shows up as an answer that did not
  *     change.
  *   - '''Equivalent spelling.''' Two spellings that must produce the same
  *     rows (`a BETWEEN x AND y` and `a >= x AND a <= y`, `LIMIT n` and `FETCH
  *     FIRST n ROWS ONLY` and `TOP n`, ...), with a third that drops what the
  *     pair is about, so a guard can tell a pair that agrees because it is right
  *     from one that agrees because the data cannot tell them apart.
  *   - '''Mutation.''' A query any mode or generator writes, with one textual
  *     mutation: a token dropped, a clause duplicated, a keyword swapped, the
  *     text truncated, a parenthesis unbalanced, a unicode identifier or a
  *     10k-character literal injected, a token inserted, a literal replaced by
  *     a boundary value, an expression nested deep. Most of these do not parse,
  *     and that is fine: the property over them is about how they fail.
  *
  * The catalog is the one the table's examples are written against, `t(a, b,
  * s)` and `u(a, b)`, so an entry's own text is a query here. Its data is drawn
  * small and from narrow pools, so a clause that can change an answer does:
  * values repeat (DISTINCT), NULLs sit beside values (NULL placement), strings
  * differ only in case (ILIKE) or hold an underscore and its escaped spelling
  * (ESCAPE), and rows of `t` miss `u` (a join condition).
  */
object SqlTextGen {

  // ===========================================================================
  // The catalog
  // ===========================================================================

  val TSchema: Schema = Schema(Vector(
    Field("a", DataType.IntType), Field("b", DataType.IntType), Field("s", DataType.StringType)))
  val USchema: Schema = Schema(Vector(Field("a", DataType.IntType), Field("b", DataType.IntType)))

  /** The tables a case runs over, by the name a query uses for each. */
  final case class Tables(tables: Vector[(String, DataGen.Dataset)]) {
    def catalog(): Catalog = {
      val c = new Catalog
      tables.foreach { case (name, d) => c.register(name, d.singlePartition()) }
      c
    }
    def numRows: Int = tables.iterator.map(_._2.numRows).sum
    def table(name: String): DataGen.Dataset = tables.find(_._1 == name).map(_._2).get
    override def toString: String =
      tables.map { case (name, d) => s"$name${d.toString.stripPrefix("Dataset")}" }.mkString("Tables(", "; ", ")")
  }

  private val TaPool: IndexedSeq[Any] = IndexedSeq(-1, 0, 1, 2, 3)
  private val TbPool: IndexedSeq[Any] = IndexedSeq(0, 1, 2, 10, 20)
  /** Strings that make the honoured clauses observable: case variants for
    * ILIKE, an underscore and the `!`-escaped spelling of one for ESCAPE,
    * numbers and a date as text for the casts, and a quote. */
  private val TsPool: IndexedSeq[Any] = IndexedSeq(
    "", "a", "A", "ab", "Ab", "AB", "abc", "a_b", "a!_b", "a%b", "x", "7", "-1", "1.5", "2024-02-29", "it's")
  private val UaPool: IndexedSeq[Any] = IndexedSeq(0, 1, 2, 5)
  private val UbPool: IndexedSeq[Any] = IndexedSeq(0, 10, 100)

  /** Fixed rows of `t` and `u` for deterministic checks: every column holds a
    * NULL, `t.s` text no number cast reads, `t.a` a repeated value, and a row
    * of each table misses the other. */
  val ReferenceTables: Tables = Tables(Vector(
    "t" -> DataGen.Dataset(TSchema, IndexedSeq[Array[Any]](
      Array[Any](1, 10, "x"), Array[Any](2, 20, "ab"), Array[Any](2, null, null), Array[Any](null, 30, "Ab"),
      Array[Any](3, 40, "7"))),
    "u" -> DataGen.Dataset(USchema, IndexedSeq[Array[Any]](
      Array[Any](1, 100), Array[Any](2, 200), Array[Any](5, 500), Array[Any](null, 0)))))

  /** `t` and `u`, each 0-16 rows; most of either 2-8. Each column has its own
    * NULL density, from none to two in five. */
  def genTables(rng: Rng): Tables =
    Tables(Vector(
      "t" -> genRows(rng.split(), TSchema, Vector(TaPool, TbPool, TsPool)),
      "u" -> genRows(rng.split(), USchema, Vector(UaPool, UbPool))))

  private def genRows(rng: Rng, schema: Schema, pools: Vector[IndexedSeq[Any]]): DataGen.Dataset = {
    val n = rng.weighted((1, 0), (2, 1), (9, -1), (3, -2)) match {
      case -1 => rng.between(2, 8)
      case -2 => rng.between(9, 16)
      case k => k
    }
    val nullProb = pools.map(_ => rng.weighted((3, 0.0), (4, 0.15), (2, 0.4)))
    val rows = IndexedSeq.fill(n)(
      Array.tabulate[Any](schema.length)(c => if (rng.bool(nullProb(c))) null else rng.oneOf(pools(c))))
    DataGen.Dataset(schema, rows)
  }

  // ===========================================================================
  // Lexer
  // ===========================================================================

  /** Cuts SQL text into tokens that render back to exactly that text, so a
    * mutation or a shrink step can work a token at a time. It is not a parser:
    * it knows how a word, a number, a quoted literal, a comment and an operator
    * are spelled, and nothing of the grammar. */
  object Lexer {
    sealed abstract class Kind(val name: String) { override def toString: String = name }
    object Kind {
      case object Word extends Kind("word")
      case object Number extends Kind("number")
      case object Str extends Kind("string")
      case object Quoted extends Kind("quoted")
      case object Op extends Kind("op")
      case object Space extends Kind("space")
      case object Comment extends Kind("comment")
    }

    final case class Token(kind: Kind, text: String) {
      def upper: String = text.toUpperCase(Locale.ROOT)
      def isSpace: Boolean = kind == Kind.Space || kind == Kind.Comment
      def is(word: String): Boolean = kind == Kind.Word && upper == word
    }

    private val MultiCharOps = Seq("<>", "!=", "<=", ">=", "||", "::", "&&")

    def lex(sql: String): Vector[Token] = {
      val out = Vector.newBuilder[Token]
      val n = sql.length
      var i = 0
      def emit(kind: Kind, end: Int): Unit = { out += Token(kind, sql.substring(i, end)); i = end }
      def digitsFrom(j0: Int): Int = { var j = j0; while (j < n && Character.isDigit(sql.charAt(j))) j += 1; j }
      while (i < n) {
        val c = sql.charAt(i)
        if (Character.isWhitespace(c)) {
          var j = i + 1
          while (j < n && Character.isWhitespace(sql.charAt(j))) j += 1
          emit(Kind.Space, j)
        } else if (sql.startsWith("--", i)) {
          val j = sql.indexOf('\n', i)
          emit(Kind.Comment, if (j < 0) n else j)
        } else if (sql.startsWith("/*", i)) {
          val j = sql.indexOf("*/", i + 2)
          emit(Kind.Comment, if (j < 0) n else j + 2)
        } else if (c == '\'') {
          // A doubled quote stays inside the literal; an unclosed one runs to the end.
          var j = i + 1
          var closed = false
          while (j < n && !closed) {
            if (sql.charAt(j) != '\'') j += 1
            else if (j + 1 < n && sql.charAt(j + 1) == '\'') j += 2
            else { j += 1; closed = true }
          }
          emit(Kind.Str, j)
        } else if (c == '"' || c == '`') {
          val j = sql.indexOf(c.toInt, i + 1)
          emit(Kind.Quoted, if (j < 0) n else j + 1)
        } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(sql.charAt(i + 1)))) {
          var j = digitsFrom(i)
          if (j < n && sql.charAt(j) == '.') j = digitsFrom(j + 1)
          if (j < n && (sql.charAt(j) == 'e' || sql.charAt(j) == 'E')) {
            val k = if (j + 1 < n && (sql.charAt(j + 1) == '+' || sql.charAt(j + 1) == '-')) j + 2 else j + 1
            if (k < n && Character.isDigit(sql.charAt(k))) j = digitsFrom(k)
          }
          emit(Kind.Number, j)
        } else if (Character.isLetter(c) || c == '_' || c == '$') {
          var j = i + 1
          while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_' || sql.charAt(j) == '$')) j += 1
          emit(Kind.Word, j)
        } else {
          emit(Kind.Op, i + MultiCharOps.find(op => sql.startsWith(op, i)).map(_.length).getOrElse(1))
        }
      }
      out.result()
    }

    def render(tokens: Iterable[Token]): String = tokens.iterator.map(_.text).mkString

    /** The parenthesis depth each token sits at: a `(` is at the depth outside
      * it and a `)` at the depth inside it is closing. */
    def depths(tokens: IndexedSeq[Token]): Array[Int] = {
      val out = new Array[Int](tokens.length)
      var d = 0
      var i = 0
      while (i < tokens.length) {
        val t = tokens(i).text
        if (t == ")") d -= 1
        out(i) = d
        if (t == "(") d += 1
        i += 1
      }
      out
    }

    /** The words of `sql`, upper-cased: its identifiers and keywords, its
      * numbers, and the text inside its quotes. */
    def words(sql: String): Set[String] =
      lex(sql).iterator.flatMap { t =>
        t.kind match {
          case Kind.Word | Kind.Number => Iterator(t.upper)
          case Kind.Str | Kind.Quoted => """[A-Za-z0-9_]+""".r.findAllIn(t.text).map(_.toUpperCase(Locale.ROOT))
          case _ => Iterator.empty
        }
      }.toSet
  }

  import Lexer.{Kind, Token}

  // ===========================================================================
  // Unsupported clauses: the table, read from src/main
  // ===========================================================================

  /** Every rejection in `UnsupportedClauses.audits` that has an example, each
    * once (several audited classes share one rejection object), ordered by
    * class name and then by position in the class's audit, so a seed reaches
    * the same entry from run to run. */
  lazy val TableEntries: Vector[UnsupportedClauses.Rejection] = distinctEntries.filter(_.example.isDefined)

  /** The entries with no example, which no SELECT text JSqlParser accepts can
    * reach; they stand guard against a grammar change and are not spliced. */
  lazy val EntriesWithoutExample: Vector[UnsupportedClauses.Rejection] = distinctEntries.filter(_.example.isEmpty)

  private lazy val distinctEntries: Vector[UnsupportedClauses.Rejection] = {
    val seen = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[UnsupportedClauses.Rejection, java.lang.Boolean])
    UnsupportedClauses.audits.toVector.sortBy(_._1.getName).flatMap(_._2.rejections).filter(seen.add)
  }

  /** The audited classes an entry sits under. */
  def classesOf(entry: UnsupportedClauses.Rejection): Vector[String] =
    UnsupportedClauses.audits.iterator
      .collect { case (cls, audit) if audit.rejections.exists(_ eq entry) => cls.getName }.toVector.sorted

  /** Whether an entry's clause lives inside an expression or a SELECT-list
    * star rather than on a query or a FROM item: its audited class is an
    * expression node, a column, a CAST's type, or a star. `Alias` sits in
    * JSqlParser's expression package but names a FROM item. */
  def isExpressionLevel(entry: UnsupportedClauses.Rejection): Boolean =
    classesOf(entry).exists { name =>
      (name.startsWith("net.sf.jsqlparser.expression.") && name != "net.sf.jsqlparser.expression.Alias") ||
        name == "net.sf.jsqlparser.schema.Column" ||
        name == "net.sf.jsqlparser.statement.create.table.ColDataType" ||
        name == "net.sf.jsqlparser.statement.select.AllColumns" ||
        name == "net.sf.jsqlparser.statement.select.AllTableColumns"
    }

  /** The part of an example that carries its clause. */
  sealed trait Fragment { def text: String }
  /** A value: `STDDEV(DISTINCT a)`, `TRY_CAST(s AS INT)`. */
  final case class ValueFragment(text: String) extends Fragment
  /** A predicate over `t`, or over `t` and `u` when it names `u`: `s RLIKE
    * 'a.b'`, `t.a = u.a(+)`. */
  final case class PredicateFragment(text: String, needsU: Boolean) extends Fragment
  /** A SELECT-list star with a modifier: `* EXCEPT (a)`. */
  final case class StarFragment(text: String) extends Fragment

  /** Cut an example into the fragment that carries its clause, by the two
    * shapes the expression-level examples are written in: `SELECT <one item>
    * FROM t` and `SELECT <items> FROM t [, u] WHERE <predicate>`. The cut is
    * depth-aware, so the FROM inside `SUBSTRING(s FROM 1 FOR 2)` is not the
    * query's. `None` for any other shape. */
  def fragmentOf(example: String): Option[Fragment] = {
    val tokens = Lexer.lex(example.trim)
    val depth = Lexer.depths(tokens)
    val sig = tokens.indices.filterNot(i => tokens(i).isSpace)
    def topWord(word: String, from: Int): Option[Int] =
      sig.find(i => i >= from && depth(i) == 0 && tokens(i).is(word))
    def text(from: Int, until: Int): String = Lexer.render(tokens.slice(from, until)).trim
    def sigWords(from: Int, until: Int): Seq[String] = sig.filter(i => i >= from && i < until).map(i => tokens(i).text)
    if (sig.isEmpty || !tokens(sig.head).is("SELECT")) return None
    val select = sig.head
    topWord("FROM", select + 1).flatMap { from =>
      val where = topWord("WHERE", from + 1)
      where match {
        case None =>
          val item = text(select + 1, from)
          val oneItem = !sig.exists(i => i > select && i < from && depth(i) == 0 && tokens(i).text == ",")
          if (!oneItem || item.isEmpty || sigWords(from + 1, tokens.length) != Seq("t")) None
          else if (item.startsWith("*") || item.matches("""\w+\.\*.*""")) Some(StarFragment(item))
          else Some(ValueFragment(item))
        case Some(w) =>
          val scope = sigWords(from + 1, w)
          val predicate = text(w + 1, tokens.length)
          val tail = sig.exists(i => i > w && depth(i) == 0 && tokens(i).kind == Kind.Word &&
            Set("GROUP", "ORDER", "LIMIT", "OFFSET", "FETCH", "HAVING", "UNION")(tokens(i).upper))
          if (tail || predicate.isEmpty || !(scope == Seq("t") || scope == Seq("t", ",", "u"))) None
          else Some(PredicateFragment(predicate, needsU = """\bu\.""".r.findFirstIn(predicate).isDefined))
      }
    }
  }

  /** A place an expression can sit: a query with a `{}` hole, and what fills
    * the hole in the clause-free twin, which shows the position itself binds. */
  final case class Position(name: String, template: String, neutral: String) {
    def render(fragment: String): String = template.replace("{}", fragment)
    def twin: String = render(neutral)
  }

  val ValuePositions: Vector[Position] = Vector(
    Position("select", "SELECT {} FROM t", "a"),
    Position("select-beside", "SELECT a, {} AS x1 FROM t", "b"),
    Position("where", "SELECT a FROM t WHERE {} IS NULL", "a"),
    Position("order-by", "SELECT a FROM t ORDER BY {}", "a"),
    Position("group-by", "SELECT a FROM t GROUP BY a, {}", "b"),
    Position("having", "SELECT b FROM t GROUP BY b HAVING {} IS NOT NULL", "MAX(a)"),
    Position("case", "SELECT CASE WHEN a > 0 THEN {} END AS x1 FROM t", "a"),
    Position("function-arg", "SELECT COALESCE({}, NULL) AS x1 FROM t", "a"),
    Position("in-list", "SELECT a FROM t WHERE a IN ({})", "b"),
    Position("between", "SELECT a FROM t WHERE {} BETWEEN 1 AND 2", "a"),
    Position("join-on", "SELECT t.a FROM t JOIN u ON t.a = u.a AND {} IS NULL", "t.b"),
    Position("window-partition", "SELECT ROW_NUMBER() OVER (PARTITION BY {} ORDER BY a) AS r FROM t", "b"),
    Position("window-order", "SELECT RANK() OVER (ORDER BY {}) AS r FROM t", "a"),
    Position("parenthesized", "SELECT ({}) AS x1 FROM t", "a"),
    Position("arithmetic", "SELECT {} + 1 AS x1 FROM t", "a"))

  val PredicatePositions: Vector[Position] = Vector(
    Position("where", "SELECT a FROM t WHERE {}", "a = 1"),
    Position("where-not", "SELECT a FROM t WHERE NOT ({})", "a = 1"),
    Position("where-or", "SELECT a FROM t WHERE a = 1 OR {}", "b = 2"),
    Position("where-and", "SELECT a FROM t WHERE ({}) AND b > 0", "a = 1"),
    Position("having", "SELECT b, COUNT(*) AS n FROM t GROUP BY b HAVING {}", "b > 0"),
    Position("join-on", "SELECT t.a FROM t JOIN u ON t.a = u.a AND {}", "t.b > 0"),
    Position("case", "SELECT CASE WHEN {} THEN 1 ELSE 0 END AS x1 FROM t", "a = 1"),
    Position("count-if", "SELECT COUNT_IF({}) AS n FROM t", "a = 1"),
    Position("if", "SELECT IF({}, 'y', 'n') AS x1 FROM t", "a = 1"),
    Position("order-by", "SELECT a FROM t ORDER BY CASE WHEN {} THEN 0 ELSE 1 END", "a = 1"),
    Position("select", "SELECT {} AS p FROM t", "a = 1"),
    Position("window-partition", "SELECT COUNT(*) OVER (PARTITION BY CASE WHEN {} THEN 1 END) AS n FROM t", "a = 1"))

  /** The predicate positions for a fragment that names `u`. */
  val JoinedPredicatePositions: Vector[Position] = Vector(
    Position("where", "SELECT t.a FROM t, u WHERE {}", "t.a = u.a"),
    Position("where-and", "SELECT t.a FROM t, u WHERE ({}) AND t.b > 0", "t.a = u.a"),
    Position("join-on", "SELECT t.a FROM t JOIN u ON {}", "t.a = u.a"),
    Position("case", "SELECT CASE WHEN {} THEN 1 ELSE 0 END AS x1 FROM t, u", "t.a = u.a"))

  val StarPositions: Vector[Position] = Vector(
    Position("select", "SELECT {} FROM t", "*"),
    Position("select-beside", "SELECT b, {} FROM t", "*"),
    Position("join", "SELECT {} FROM t JOIN u ON t.a = u.a", "t.*"),
    Position("qualified", "SELECT t.{} FROM t", "*"))

  def positionsFor(f: Fragment): Vector[Position] = f match {
    case _: ValueFragment => ValuePositions
    case p: PredicateFragment => if (p.needsU) JoinedPredicatePositions else PredicatePositions
    case _: StarFragment => StarPositions
  }

  /** What a context does to the rows of the query inside it. */
  sealed abstract class RowEffect(val name: String)
  object RowEffect {
    /** The same rows. */
    case object Same extends RowEffect("same")
    /** Every row twice. */
    case object Doubled extends RowEffect("doubled")
    /** One row holding the row count. */
    case object Counted extends RowEffect("counted")
  }

  /** A place a whole query can sit. Every context is valid around any query
    * that is valid on its own, and changes its rows only as `effect` says. */
  final case class Context(name: String, effect: RowEffect, wrap: String => String) {
    override def toString: String = name
  }

  val TopLevel: Context = Context("top", RowEffect.Same, q => q)

  /** Top level; a CTE body, inlined (one reference), the second of two,
    * nested in another CTE's own WITH, or materialized (two references); each
    * arm of a parenthesized UNION ALL; and a CTE counted. */
  val Contexts: Vector[Context] = Vector(
    TopLevel,
    Context("cte", RowEffect.Same, q => s"WITH q AS ($q) SELECT * FROM q"),
    Context("second-cte", RowEffect.Same, q => s"WITH p AS (SELECT a FROM t), q AS ($q) SELECT * FROM q"),
    Context("nested-with", RowEffect.Same, q => s"WITH q AS (WITH r AS ($q) SELECT * FROM r) SELECT * FROM q"),
    Context("materialized-cte", RowEffect.Same,
      q => s"WITH q AS ($q) SELECT * FROM q UNION ALL SELECT * FROM q WHERE FALSE"),
    Context("union-arms", RowEffect.Doubled, q => s"($q) UNION ALL ($q)"),
    Context("cte-count", RowEffect.Counted, q => s"WITH q AS ($q) SELECT COUNT(*) AS n FROM q"))

  /** A context only an unsupported clause goes in: both arms of an
    * unparenthesized UNION ALL, where a trailing clause of the last arm binds
    * to the whole union, so its rows are not the arm's. */
  val BareUnion: Context = Context("union-bare", RowEffect.Doubled, q => s"$q UNION ALL $q")

  /** One spliced entry: its example, or its fragment at `position`, in
    * `context`. `twin` is the same text with the fragment's position filled by
    * a supported expression, when there is a fragment. */
  final case class ClauseCase(
      entry: UnsupportedClauses.Rejection,
      position: String,
      context: Context,
      sql: String,
      twin: Option[String]) {
    override def toString: String =
      s"ClauseCase(${entry.clause} at $position in $context)\n  sql  = $sql" + twin.map(t => s"\n  twin = $t").getOrElse("")
  }

  /** Every (entry, position, context): each entry's example in every context
    * and, for an expression-level entry, its fragment at every position in
    * every context. The table is finite, so the property over it enumerates
    * these rather than sampling them. */
  lazy val AllClauseCases: Vector[ClauseCase] = TableEntries.flatMap { entry =>
    val example = entry.example.get
    val contexts = Contexts :+ BareUnion
    val whole = contexts.map(c => ClauseCase(entry, "example", c, c.wrap(example), None))
    val spliced =
      if (!isExpressionLevel(entry)) Vector.empty
      else fragmentOf(example).toVector.flatMap { f =>
        for { p <- positionsFor(f); c <- contexts }
          yield ClauseCase(entry, p.name, c, c.wrap(p.render(f.text)), Some(c.wrap(p.twin)))
      }
    whole ++ spliced
  }

  /** Text the parser accepts that never reaches an AST getter, so the clause
    * table cannot list it: JSqlParser stops at the first statement, keeps the
    * last of a repeated tail clause, and takes a JOIN with no condition. Each
    * must be refused, as a table entry must; `knownPlan` names the plan
    * recording one that binds today. A `wrappable` one is also checked in
    * every context, as a table entry's example is. */
  final case class Beyond(name: String, sql: String, wrappable: Boolean, knownPlan: Option[String])

  private val TextBeyondPlan = "plans/robustness/12-text-the-binder-never-sees.md"
  private val LiteralsPlan = "plans/robustness/13-literals-are-read-as-written.md"

  val BeyondTheTable: Vector[Beyond] = Vector(
    Beyond("a second statement after ;", "SELECT a FROM t; SELECT b FROM t", wrappable = false, Some(TextBeyondPlan)),
    Beyond("text after ; that is no statement", "SELECT a FROM t; garbage", wrappable = false, Some(TextBeyondPlan)),
    Beyond("ORDER BY twice", "SELECT a FROM t ORDER BY a DESC ORDER BY a ASC LIMIT 1", wrappable = true,
      Some(TextBeyondPlan)),
    Beyond("LIMIT twice", "SELECT a FROM t ORDER BY a LIMIT 1 LIMIT 2", wrappable = true, Some(TextBeyondPlan)),
    Beyond("OFFSET twice", "SELECT a FROM t ORDER BY a OFFSET 1 OFFSET 2", wrappable = true, Some(TextBeyondPlan)),
    Beyond("FETCH FIRST twice", "SELECT a FROM t ORDER BY a FETCH FIRST 1 ROWS ONLY FETCH FIRST 3 ROWS ONLY",
      wrappable = true, Some(TextBeyondPlan)),
    Beyond("a CTE name defined twice", "WITH q AS (SELECT a FROM t), q AS (SELECT b AS a FROM u) SELECT * FROM q",
      wrappable = false, Some(TextBeyondPlan)),
    Beyond("JOIN with no condition", "SELECT t.a FROM t JOIN u", wrappable = true, Some(TextBeyondPlan)),
    Beyond("LEFT JOIN with no condition", "SELECT t.a FROM t LEFT JOIN u", wrappable = true, Some(TextBeyondPlan)),
    Beyond("string literals side by side", "SELECT 'a' 'b' FROM t", wrappable = true, Some(LiteralsPlan)))

  // ===========================================================================
  // Honoured clauses
  // ===========================================================================

  /** A clause the binder reads, at one position: the query with it and the
    * same query without it. */
  final case class Honoured(clause: String, position: String, withSql: String, withoutSql: String) {
    def key: String = s"$clause @ $position"
  }

  /** One honoured clause in one context, over generated tables. */
  final case class HonouredCase(tables: Tables, honoured: Honoured, context: Context) {
    def contextSql: String = context.wrap(honoured.withSql)
    override def toString: String =
      s"HonouredCase(${honoured.key} in $context)\n  with    = ${honoured.withSql}\n  without = " +
        s"${honoured.withoutSql}\n  context = $contextSql\n  $tables"
  }

  /** `{m}` an OFFSET, `{n}` a row count, `{k}` a number the data's values sit
    * around, `{pat}` a LIKE pattern with letters of both cases, `{epat}` one
    * with a `!`-escaped wildcard. */
  private def honouredParams(rng: Rng): Map[String, String] = Map(
    "m" -> rng.oneOf(Seq("1", "2", "3")),
    "n" -> rng.oneOf(Seq("1", "2", "3", "5")),
    "k" -> rng.oneOf(Seq("0", "1", "2", "10")),
    "pat" -> rng.oneOf(Seq("'ab'", "'a%'", "'%B'", "'A_'", "'AB%'", "'_b'")),
    "epat" -> rng.oneOf(Seq("'a!_b'", "'a!%b'", "'%!_%'", "'!_%'")))

  /** Every row of `t` in a total order, so a cutoff between tied rows keeps
    * the same multiset whichever of them it keeps: tied rows are identical. */
  private val TAll = "SELECT a, b, s FROM t"
  private val TOrder = "ORDER BY a, b, s"

  /** The clauses plan 01 taught the binder to read, and the ones its probing
    * found read in one context and dropped in another (HAVING with no GROUP
    * BY, an aggregate only in ORDER BY), plus the core clauses every context
    * must keep. Each is (clause, position, with, without). */
  val HonouredTemplates: Vector[Honoured] = Vector(
    Honoured("OFFSET", "tail", s"$TAll $TOrder OFFSET {m}", s"$TAll $TOrder"),
    Honoured("OFFSET", "after LIMIT", s"$TAll $TOrder LIMIT {n} OFFSET {m}", s"$TAll $TOrder LIMIT {n}"),
    Honoured("OFFSET", "before FETCH", s"$TAll $TOrder OFFSET {m} ROWS FETCH NEXT {n} ROWS ONLY",
      s"$TAll $TOrder FETCH NEXT {n} ROWS ONLY"),
    Honoured("OFFSET", "LIMIT m, n", s"$TAll $TOrder LIMIT {m}, {n}", s"$TAll $TOrder LIMIT {n}"),
    Honoured("OFFSET", "on a union", "SELECT a FROM t UNION ALL SELECT a FROM u ORDER BY 1 OFFSET {m}",
      "SELECT a FROM t UNION ALL SELECT a FROM u ORDER BY 1"),
    Honoured("OFFSET", "over DISTINCT", "SELECT DISTINCT a FROM t ORDER BY a OFFSET {m}",
      "SELECT DISTINCT a FROM t ORDER BY a"),
    Honoured("OFFSET", "over GROUP BY", "SELECT b, COUNT(*) AS n FROM t GROUP BY b ORDER BY b OFFSET {m}",
      "SELECT b, COUNT(*) AS n FROM t GROUP BY b ORDER BY b"),
    Honoured("LIMIT", "tail", s"$TAll $TOrder LIMIT {n}", s"$TAll $TOrder"),
    Honoured("LIMIT", "on a union", "SELECT a FROM t UNION ALL SELECT a FROM u ORDER BY 1 LIMIT {n}",
      "SELECT a FROM t UNION ALL SELECT a FROM u ORDER BY 1"),
    Honoured("FETCH FIRST", "tail", s"$TAll $TOrder FETCH FIRST {n} ROWS ONLY", s"$TAll $TOrder"),
    Honoured("FETCH FIRST", "on a union",
      "SELECT a FROM t UNION ALL SELECT a FROM u ORDER BY 1 FETCH FIRST {n} ROWS ONLY",
      "SELECT a FROM t UNION ALL SELECT a FROM u ORDER BY 1"),
    Honoured("TOP", "select", s"SELECT TOP {n} a, b, s FROM t $TOrder", s"$TAll $TOrder"),
    Honoured("TOP", "after DISTINCT", "SELECT DISTINCT TOP {n} a FROM t ORDER BY a",
      "SELECT DISTINCT a FROM t ORDER BY a"),
    Honoured("NULLS LAST", "ORDER BY ... LIMIT", s"$TAll ORDER BY a NULLS LAST, b, s LIMIT {n}",
      s"$TAll $TOrder LIMIT {n}"),
    Honoured("NULLS FIRST", "ORDER BY DESC ... LIMIT", s"$TAll ORDER BY a DESC NULLS FIRST, b, s LIMIT {n}",
      s"$TAll ORDER BY a DESC, b, s LIMIT {n}"),
    Honoured("NULLS LAST", "window", "SELECT a, RANK() OVER (ORDER BY a NULLS LAST) AS r FROM t",
      "SELECT a, RANK() OVER (ORDER BY a) AS r FROM t"),
    Honoured("NULLS FIRST", "window DESC", "SELECT a, RANK() OVER (ORDER BY a DESC NULLS FIRST) AS r FROM t",
      "SELECT a, RANK() OVER (ORDER BY a DESC) AS r FROM t"),
    Honoured("NULLS LAST", "window partition",
      "SELECT b, a, DENSE_RANK() OVER (PARTITION BY b ORDER BY a NULLS LAST) AS r FROM t",
      "SELECT b, a, DENSE_RANK() OVER (PARTITION BY b ORDER BY a) AS r FROM t"),
    Honoured("NULLS LAST", "LAG", "SELECT a, b, s, LAG(b) OVER (ORDER BY a NULLS LAST, b, s) AS p FROM t",
      s"SELECT a, b, s, LAG(b) OVER ($TOrder) AS p FROM t"),
    Honoured("NULLS LAST", "over DISTINCT", "SELECT DISTINCT a FROM t ORDER BY a NULLS LAST LIMIT {n}",
      "SELECT DISTINCT a FROM t ORDER BY a LIMIT {n}"),
    Honoured("NULLS LAST", "on a union", "SELECT a FROM t UNION ALL SELECT a FROM u ORDER BY 1 NULLS LAST LIMIT {n}",
      "SELECT a FROM t UNION ALL SELECT a FROM u ORDER BY 1 LIMIT {n}"),
    Honoured("SUM(DISTINCT)", "select", "SELECT SUM(DISTINCT a) AS x FROM t", "SELECT SUM(a) AS x FROM t"),
    Honoured("AVG(DISTINCT)", "select", "SELECT AVG(DISTINCT a) AS x FROM t", "SELECT AVG(a) AS x FROM t"),
    Honoured("COUNT(DISTINCT)", "select", "SELECT COUNT(DISTINCT a) AS x FROM t", "SELECT COUNT(a) AS x FROM t"),
    Honoured("SUM(DISTINCT)", "grouped", "SELECT b, SUM(DISTINCT a) AS x FROM t GROUP BY b",
      "SELECT b, SUM(a) AS x FROM t GROUP BY b"),
    Honoured("AVG(DISTINCT)", "grouped", "SELECT b, AVG(DISTINCT a) AS x FROM t GROUP BY b",
      "SELECT b, AVG(a) AS x FROM t GROUP BY b"),
    Honoured("SUM(DISTINCT)", "in arithmetic", "SELECT SUM(DISTINCT a) + 1 AS x FROM t",
      "SELECT SUM(a) + 1 AS x FROM t"),
    // Coarse groups, so a group repeats a value often enough for DISTINCT to
    // matter, and a difference that is not zero exactly when one does.
    Honoured("SUM(DISTINCT)", "HAVING",
      "SELECT b > 15 AS g, COUNT(*) AS n FROM t GROUP BY b > 15 HAVING SUM(DISTINCT a) < SUM(a)",
      "SELECT b > 15 AS g, COUNT(*) AS n FROM t GROUP BY b > 15 HAVING SUM(a) < SUM(a)"),
    Honoured("SUM(DISTINCT)", "ORDER BY",
      "SELECT b > 5 AS g FROM t GROUP BY b > 5 ORDER BY SUM(DISTINCT a) - SUM(a), b > 5 LIMIT 1",
      "SELECT b > 5 AS g FROM t GROUP BY b > 5 ORDER BY SUM(a) - SUM(a), b > 5 LIMIT 1"),
    Honoured("COUNT(DISTINCT)", "window", "SELECT a, COUNT(DISTINCT b) OVER () AS n FROM t",
      "SELECT a, COUNT(b) OVER () AS n FROM t"),
    Honoured("ILIKE", "WHERE", "SELECT a, s FROM t WHERE s ILIKE {pat}", "SELECT a, s FROM t WHERE s LIKE {pat}"),
    Honoured("ILIKE", "NOT", "SELECT a, s FROM t WHERE s NOT ILIKE {pat}",
      "SELECT a, s FROM t WHERE s NOT LIKE {pat}"),
    Honoured("ILIKE", "select", "SELECT s ILIKE {pat} AS m FROM t", "SELECT s LIKE {pat} AS m FROM t"),
    Honoured("ILIKE", "CASE", "SELECT CASE WHEN s ILIKE {pat} THEN 1 ELSE 0 END AS m FROM t",
      "SELECT CASE WHEN s LIKE {pat} THEN 1 ELSE 0 END AS m FROM t"),
    Honoured("ILIKE", "join ON", "SELECT t.a, u.b FROM t JOIN u ON t.a = u.a AND t.s ILIKE {pat}",
      "SELECT t.a, u.b FROM t JOIN u ON t.a = u.a AND t.s LIKE {pat}"),
    Honoured("ILIKE", "HAVING", "SELECT b FROM t GROUP BY b HAVING MAX(s) ILIKE {pat}",
      "SELECT b FROM t GROUP BY b HAVING MAX(s) LIKE {pat}"),
    Honoured("ILIKE", "COUNT_IF", "SELECT COUNT_IF(s ILIKE {pat}) AS n FROM t",
      "SELECT COUNT_IF(s LIKE {pat}) AS n FROM t"),
    Honoured("ESCAPE", "WHERE", "SELECT a, s FROM t WHERE s LIKE {epat} ESCAPE '!'",
      "SELECT a, s FROM t WHERE s LIKE {epat}"),
    Honoured("ESCAPE", "select", "SELECT s LIKE {epat} ESCAPE '!' AS m FROM t",
      "SELECT s LIKE {epat} AS m FROM t"),
    Honoured("ESCAPE", "ILIKE", "SELECT a, s FROM t WHERE s ILIKE {epat} ESCAPE '!'",
      "SELECT a, s FROM t WHERE s ILIKE {epat}"),
    Honoured("ESCAPE ''", "WHERE", "SELECT a, s FROM t WHERE s LIKE 'a\\_b' ESCAPE ''",
      "SELECT a, s FROM t WHERE s LIKE 'a\\_b'"),
    Honoured("HAVING", "no GROUP BY", "SELECT COUNT(*) AS n FROM t HAVING COUNT(*) > {k}",
      "SELECT COUNT(*) AS n FROM t"),
    Honoured("HAVING", "no GROUP BY, no aggregate", "SELECT 1 AS x FROM t HAVING COUNT(*) > {k}",
      "SELECT 1 AS x FROM t"),
    Honoured("HAVING", "grouped", "SELECT b, COUNT(*) AS n FROM t GROUP BY b HAVING COUNT(*) > 1",
      "SELECT b, COUNT(*) AS n FROM t GROUP BY b"),
    Honoured("HAVING", "on the key", "SELECT b FROM t GROUP BY b HAVING b > {k}", "SELECT b FROM t GROUP BY b"),
    Honoured("ORDER BY aggregate", "only in ORDER BY", "SELECT b FROM t GROUP BY b ORDER BY SUM(a) DESC, b LIMIT {n}",
      "SELECT b FROM t GROUP BY b ORDER BY b LIMIT {n}"),
    Honoured("DISTINCT", "select", "SELECT DISTINCT a, b FROM t", "SELECT a, b FROM t"),
    Honoured("DISTINCT", "union arm", "SELECT DISTINCT a FROM t UNION ALL SELECT DISTINCT a FROM u",
      "SELECT a FROM t UNION ALL SELECT a FROM u"),
    Honoured("UNION", "dedup", "SELECT a FROM t UNION SELECT a FROM u", "SELECT a FROM t UNION ALL SELECT a FROM u"),
    Honoured("WHERE", "filter", "SELECT a, b FROM t WHERE a > {k}", "SELECT a, b FROM t"),
    Honoured("ON", "join condition", "SELECT t.a, u.b FROM t JOIN u ON t.a = u.a",
      "SELECT t.a, u.b FROM t CROSS JOIN u"),
    Honoured("LEFT JOIN", "kind", "SELECT t.a, u.b FROM t LEFT JOIN u ON t.a = u.a",
      "SELECT t.a, u.b FROM t JOIN u ON t.a = u.a"),
    Honoured("FULL JOIN", "kind", "SELECT t.a, u.b FROM t FULL JOIN u ON t.a = u.a",
      "SELECT t.a, u.b FROM t JOIN u ON t.a = u.a"),
    Honoured("ROWS frame", "window", s"SELECT a, b, s, SUM(b) OVER ($TOrder ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) AS w FROM t",
      s"SELECT a, b, s, SUM(b) OVER ($TOrder) AS w FROM t"),
    Honoured("PARTITION BY", "window", "SELECT a, b, COUNT(*) OVER (PARTITION BY b) AS n FROM t",
      "SELECT a, b, COUNT(*) OVER () AS n FROM t"))

  private def fill(template: String, params: Map[String, String]): String =
    params.foldLeft(template) { case (s, (k, v)) => s.replace(s"{$k}", v) }

  def genHonouredCase(rng: Rng): HonouredCase = {
    val tables = genTables(rng.split())
    val shapeRng = rng.split()
    val h = shapeRng.oneOf(HonouredTemplates)
    val params = honouredParams(shapeRng)
    HonouredCase(tables, h.copy(withSql = fill(h.withSql, params), withoutSql = fill(h.withoutSql, params)),
      shapeRng.oneOf(HonouredContexts))
  }

  /** The contexts an honoured clause is checked in: all but top level, where
    * the query is its own reference. */
  val HonouredContexts: Vector[Context] = Contexts.filterNot(_ == TopLevel)

  /** Every honoured clause, its parameters at their first values, so a
    * deterministic check can run each one. */
  def honouredAtFirstParams: Vector[Honoured] = {
    val params = Map("m" -> "1", "n" -> "2", "k" -> "1", "pat" -> "'ab'", "epat" -> "'a!_b'")
    HonouredTemplates.map(h => h.copy(withSql = fill(h.withSql, params), withoutSql = fill(h.withoutSql, params)))
  }

  // ===========================================================================
  // Equivalent spellings
  // ===========================================================================

  /** Spellings that must mean the same thing, and the query that drops what
    * they are about (`None` when nothing is dropped: the spellings differ only
    * in how they are written). */
  final case class Spelling(shape: String, spellings: Vector[String], degenerate: Option[String])

  /** Two spellings of one shape over generated tables. */
  final case class SpellingCase(tables: Tables, shape: String, left: String, right: String, degenerate: Option[String]) {
    override def toString: String =
      s"SpellingCase($shape)\n  left       = $left\n  right      = $right\n  degenerate = " +
        s"${degenerate.getOrElse("<none>")}\n  $tables"
  }

  /** `{x}` and `{y}` numbers in `t.a`'s range, `x <= y` more often than not;
    * `{n}` a row count; `{m}` an OFFSET; `{p}` a LIKE pattern. */
  private def spellingParams(rng: Rng): Map[String, String] = {
    val x = rng.between(-1, 3)
    val y = if (rng.bool(0.8)) rng.between(x, 3) else rng.between(-1, 3)
    Map("x" -> x.toString, "y" -> y.toString,
      "n" -> rng.oneOf(Seq("1", "2", "3", "5")), "m" -> rng.oneOf(Seq("1", "2", "3")),
      "p" -> rng.oneOf(Seq("'ab'", "'a%'", "'%B'", "'A_'", "'_b'")))
  }

  private def where(p: String): String = s"$TAll WHERE $p"

  /** The plan's shapes: BETWEEN, negation, IN, parentheses (redundant and
    * needed), a join written three ways, and the row-count spellings plan 01
    * taught the binder; plus every spelling the audit calls inert (`UNIQUE`,
    * `!=`, `&&`, `!`, `ISNULL`, `::`, a typed literal, `{fn ...}`, `LIKE
    * BINARY`, `N'...'`, `OFFSET 1 ROW`, `FETCH NEXT`, `UNION DISTINCT`, `LEFT
    * OUTER`), NULL placement written as a sort key, the default quantifier
    * `ALL`, and the conditional functions written as CASE. */
  val SpellingShapes: Vector[Spelling] = Vector(
    Spelling("BETWEEN", Vector(where("a BETWEEN {x} AND {y}"), where("a >= {x} AND a <= {y}"),
      where("NOT (a < {x} OR a > {y})")), Some(TAll)),
    Spelling("NOT BETWEEN", Vector(where("a NOT BETWEEN {x} AND {y}"), where("a < {x} OR a > {y}"),
      where("NOT (a BETWEEN {x} AND {y})")), Some(TAll)),
    Spelling("NOT =", Vector(where("NOT (a = {x})"), where("a <> {x}"), where("a != {x}"), where("NOT a = {x}"),
      where("!(a = {x})")), Some(where("a = {x}"))),
    Spelling("NOT <", Vector(where("NOT (a < b)"), where("a >= b"), where("NOT a < b")), Some(where("a < b"))),
    Spelling("NOT >=", Vector(where("NOT (a >= b)"), where("a < b"), where("NOT a >= b")), Some(where("a >= b"))),
    Spelling("IN one", Vector(where("a IN ({x})"), where("a = {x}"), where("(a) IN (({x}))")), Some(TAll)),
    Spelling("IN two", Vector(where("a IN ({x}, {y})"), where("a = {x} OR a = {y}"), where("(a = {x}) OR (a = {y})")),
      Some(TAll)),
    Spelling("NOT IN", Vector(where("a NOT IN ({x}, {y})"), where("a <> {x} AND a <> {y}"),
      where("NOT (a IN ({x}, {y}))")), Some(TAll)),
    Spelling("NOT IN with NULL", Vector(where("a NOT IN ({x}, NULL)"), where("a <> {x} AND a <> NULL")), Some(TAll)),
    Spelling("redundant parentheses", Vector("SELECT a + b AS x FROM t", "SELECT (a + b) AS x FROM t",
      "SELECT ((a)) + (b) AS x FROM t", "SELECT (((a + b))) AS x FROM t"), None),
    Spelling("parentheses a - (b - x)", Vector("SELECT a - (b - {x}) AS v FROM t", "SELECT a - b + {x} AS v FROM t"),
      Some("SELECT a - b - {x} AS v FROM t")),
    Spelling("parentheses (a + b) * x", Vector("SELECT (a + b) * {x} AS v FROM t", "SELECT a * {x} + b * {x} AS v FROM t"),
      Some("SELECT a + b * {x} AS v FROM t")),
    Spelling("parentheses x - (a + b)", Vector("SELECT {x} - (a + b) AS v FROM t", "SELECT {x} - a - b AS v FROM t"),
      Some("SELECT {x} - a + b AS v FROM t")),
    Spelling("parenthesized predicate", Vector(where("a = {x} OR b = {y}"), where("(a = {x}) OR ((b = {y}))"),
      where("((a = {x} OR b = {y}))")), Some(TAll)),
    Spelling("parenthesized GROUP BY", Vector("SELECT a, COUNT(*) AS n FROM t GROUP BY a",
      "SELECT a, COUNT(*) AS n FROM t GROUP BY (a)"), None),
    Spelling("parenthesized ORDER BY", Vector(s"$TAll $TOrder LIMIT {n}", s"$TAll ORDER BY (a), (b), (s) LIMIT {n}"),
      Some(TAll)),
    Spelling("inner join", Vector(
      "SELECT t.a, t.b, u.b AS ub FROM t JOIN u ON t.a = u.a",
      "SELECT t.a, t.b, u.b AS ub FROM t, u WHERE t.a = u.a",
      "SELECT t.a, t.b, u.b AS ub FROM t CROSS JOIN u WHERE t.a = u.a",
      "SELECT t.a, t.b, u.b AS ub FROM t INNER JOIN u ON (t.a = u.a)",
      "SELECT t.a, t.b, u.b AS ub FROM u JOIN t ON u.a = t.a"),
      Some("SELECT t.a, t.b, u.b AS ub FROM t CROSS JOIN u")),
    Spelling("left join", Vector(
      "SELECT t.a, t.b, u.b AS ub FROM t LEFT JOIN u ON t.a = u.a",
      "SELECT t.a, t.b, u.b AS ub FROM t LEFT OUTER JOIN u ON t.a = u.a",
      "SELECT t.a, t.b, u.b AS ub FROM u RIGHT JOIN t ON t.a = u.a"),
      Some("SELECT t.a, t.b, u.b AS ub FROM t JOIN u ON t.a = u.a")),
    Spelling("row count", Vector(s"$TAll $TOrder LIMIT {n}", s"$TAll $TOrder FETCH FIRST {n} ROWS ONLY",
      s"$TAll $TOrder FETCH NEXT {n} ROW ONLY", s"SELECT TOP {n} a, b, s FROM t $TOrder",
      s"SELECT TOP ({n}) a, b, s FROM t $TOrder"), Some(s"$TAll $TOrder")),
    Spelling("one row", Vector(s"$TAll $TOrder LIMIT 1", s"$TAll $TOrder FETCH FIRST ROW ONLY",
      s"SELECT TOP 1 a, b, s FROM t $TOrder"), Some(s"$TAll $TOrder")),
    Spelling("row page", Vector(s"$TAll $TOrder LIMIT {n} OFFSET {m}",
      s"$TAll $TOrder OFFSET {m} ROWS FETCH NEXT {n} ROWS ONLY", s"$TAll $TOrder LIMIT {m}, {n}",
      s"$TAll $TOrder OFFSET {m} ROW FETCH FIRST {n} ROWS ONLY"), Some(s"$TAll $TOrder LIMIT {n}")),
    Spelling("window NULLS LAST", Vector("SELECT a, RANK() OVER (ORDER BY a NULLS LAST) AS r FROM t",
      "SELECT a, RANK() OVER (ORDER BY a IS NULL, a) AS r FROM t",
      "SELECT a, RANK() OVER (ORDER BY a ASC NULLS LAST) AS r FROM t"),
      Some("SELECT a, RANK() OVER (ORDER BY a) AS r FROM t")),
    Spelling("window DESC NULLS FIRST", Vector("SELECT a, RANK() OVER (ORDER BY a DESC NULLS FIRST) AS r FROM t",
      "SELECT a, RANK() OVER (ORDER BY a IS NOT NULL, a DESC) AS r FROM t"),
      Some("SELECT a, RANK() OVER (ORDER BY a DESC) AS r FROM t")),
    Spelling("window default placement", Vector("SELECT a, RANK() OVER (ORDER BY a) AS r FROM t",
      "SELECT a, RANK() OVER (ORDER BY a ASC NULLS FIRST) AS r FROM t",
      "SELECT a, RANK() OVER (ORDER BY a IS NOT NULL, a) AS r FROM t"),
      Some("SELECT a, RANK() OVER (ORDER BY a NULLS LAST) AS r FROM t")),
    Spelling("ORDER BY NULLS LAST", Vector(s"$TAll ORDER BY a NULLS LAST, b, s LIMIT {n}",
      s"$TAll ORDER BY a IS NULL, a, b, s LIMIT {n}"), Some(s"$TAll $TOrder LIMIT {n}")),
    Spelling("ALL quantifier", Vector("SELECT COUNT(ALL a) AS c, SUM(ALL b) AS sb FROM t",
      "SELECT COUNT(a) AS c, SUM(b) AS sb FROM t"), Some("SELECT COUNT(*) AS c, SUM(b) AS sb FROM t")),
    Spelling("ALL quantifier grouped", Vector("SELECT b, COUNT(ALL a) AS c FROM t GROUP BY b",
      "SELECT b, COUNT(a) AS c FROM t GROUP BY b"), Some("SELECT b, COUNT(*) AS c FROM t GROUP BY b")),
    Spelling("UNIQUE", Vector("SELECT DISTINCT a, b FROM t", "SELECT UNIQUE a, b FROM t"), Some("SELECT a, b FROM t")),
    Spelling("UNION DISTINCT", Vector("SELECT a FROM t UNION SELECT a FROM u", "SELECT a FROM t UNION DISTINCT SELECT a FROM u"),
      Some("SELECT a FROM t UNION ALL SELECT a FROM u")),
    Spelling("&&", Vector(where("a = {x} AND b = {y}"), where("a = {x} && b = {y}")), Some(where("a = {x}"))),
    Spelling("ISNULL", Vector(where("s IS NULL"), where("s ISNULL")), Some(TAll)),
    Spelling("::", Vector("SELECT CAST(a AS BIGINT) AS v FROM t", "SELECT a::BIGINT AS v FROM t"), None),
    Spelling("{fn ...}", Vector("SELECT UPPER(s) AS v FROM t", "SELECT {fn UPPER(s)} AS v FROM t"),
      Some("SELECT s AS v FROM t")),
    Spelling("LIKE BINARY", Vector(where("s LIKE {p}"), where("s LIKE BINARY {p}")), Some(TAll)),
    Spelling("N'...'", Vector(where("s = 'ab'"), where("s = N'ab'")), Some(TAll)),
    Spelling("typed literal", Vector("SELECT a + CAST('1' AS INT) AS v FROM t", "SELECT a + INT '1' AS v FROM t"),
      Some("SELECT a AS v FROM t")),
    Spelling("CASE switch", Vector("SELECT CASE a WHEN {x} THEN 'p' ELSE 'q' END AS v FROM t",
      "SELECT CASE WHEN a = {x} THEN 'p' ELSE 'q' END AS v FROM t"), Some("SELECT 'q' AS v FROM t")),
    Spelling("IF", Vector("SELECT IF(a > {x}, 'p', 'q') AS v FROM t",
      "SELECT CASE WHEN a > {x} THEN 'p' ELSE 'q' END AS v FROM t"), Some("SELECT 'q' AS v FROM t")),
    Spelling("COALESCE", Vector("SELECT COALESCE(a, b) AS v FROM t",
      "SELECT CASE WHEN a IS NOT NULL THEN a ELSE b END AS v FROM t"), Some("SELECT a AS v FROM t")),
    Spelling("NULLIF", Vector("SELECT NULLIF(a, {x}) AS v FROM t",
      "SELECT CASE WHEN a = {x} THEN NULL ELSE a END AS v FROM t"), Some("SELECT a AS v FROM t")),
    Spelling("ILIKE", Vector(where("s ILIKE {p}"), where("LOWER(s) LIKE LOWER({p})"), where("UPPER(s) LIKE UPPER({p})")),
      Some(where("s LIKE {p}"))),
    Spelling("HAVING on a key", Vector("SELECT b, COUNT(*) AS n FROM t GROUP BY b HAVING b > {x}",
      "SELECT b, COUNT(*) AS n FROM t WHERE b > {x} GROUP BY b"), Some("SELECT b, COUNT(*) AS n FROM t GROUP BY b")),
    Spelling("ordinals", Vector(s"$TAll ORDER BY 1, 2, 3 LIMIT {n}", s"$TAll $TOrder LIMIT {n}"), Some(TAll)),
    Spelling("GROUP BY ordinal", Vector("SELECT b, COUNT(*) AS n FROM t GROUP BY 1",
      "SELECT b, COUNT(*) AS n FROM t GROUP BY b"), None),
    Spelling("OFFSET ROW", Vector(s"$TAll $TOrder OFFSET {m} ROW", s"$TAll $TOrder OFFSET {m} ROWS",
      s"$TAll $TOrder OFFSET {m}"), Some(s"$TAll $TOrder")),
    Spelling("CTE inlined", Vector("SELECT a, b FROM t WHERE a > {x}",
      "WITH q AS (SELECT a, b FROM t WHERE a > {x}) SELECT * FROM q",
      "WITH q AS (SELECT a, b FROM t) SELECT * FROM q WHERE a > {x}"), Some("SELECT a, b FROM t")),
    // Shapes whose spellings disagree today, each a known defect of
    // oracle/SyntaxEquivalence with the plan that records it.
    Spelling("IF numeric branches", Vector("SELECT IF(a > {x}, 1, 2.5) AS v FROM t",
      "SELECT CASE WHEN a > {x} THEN 1 ELSE 2.5 END AS v FROM t"), Some("SELECT 1 AS v FROM t")),
    Spelling("COALESCE numeric branches", Vector("SELECT COALESCE(a, 2.5) AS v FROM t",
      "SELECT CASE WHEN a IS NOT NULL THEN a ELSE 2.5 END AS v FROM t"), Some("SELECT a AS v FROM t")),
    Spelling("quoted identifier", Vector("SELECT a, b FROM t", "SELECT \"a\", \"b\" FROM t"), None),
    Spelling("a quote inside a literal", Vector("SELECT LENGTH('it''s') AS n FROM t", "SELECT 4 AS n FROM t"), None),
    Spelling("NOT NOT", Vector(where("NOT NOT a = {x}"), where("a = {x}"), where("NOT (NOT (a = {x}))")),
      Some(TAll)))

  def genSpellingCase(rng: Rng): SpellingCase = {
    val tables = genTables(rng.split())
    val shapeRng = rng.split()
    val shape = shapeRng.oneOf(SpellingShapes)
    val params = spellingParams(shapeRng)
    val i = shapeRng.between(0, shape.spellings.length - 1)
    val j = (i + shapeRng.between(1, shape.spellings.length - 1)) % shape.spellings.length
    SpellingCase(tables, shape.shape, fill(shape.spellings(i), params), fill(shape.spellings(j), params),
      shape.degenerate.map(fill(_, params)))
  }

  // ===========================================================================
  // Mutations
  // ===========================================================================

  /** One textual mutation, by kind, with what it did. */
  final case class Mutation(kind: String, detail: String) {
    override def toString: String = s"$kind: $detail"
  }

  /** The mutations, by the weight they are drawn with. */
  val MutationKinds: Vector[(Int, String)] = Vector(
    3 -> "drop-token", 3 -> "duplicate-clause", 4 -> "swap-keyword", 2 -> "truncate",
    2 -> "unbalance-paren", 2 -> "unicode-identifier", 1 -> "long-literal", 3 -> "inject-token",
    2 -> "boundary-literal", 1 -> "deep-nesting")

  /** Words a swap trades for another of their group: each group is words that
    * can stand in the same place. */
  private val KeywordGroups: Vector[Vector[String]] = Vector(
    Vector("AND", "OR", "XOR"),
    Vector("IS", "IN", "LIKE", "ILIKE", "RLIKE", "REGEXP", "BETWEEN", "SIMILAR"),
    Vector("INNER", "LEFT", "RIGHT", "FULL", "CROSS", "OUTER", "NATURAL", "SEMI", "ANTI", "STRAIGHT_JOIN"),
    Vector("UNION", "INTERSECT", "EXCEPT", "MINUS"),
    Vector("WHERE", "HAVING", "QUALIFY", "ON", "USING", "WINDOW"),
    Vector("ASC", "DESC", "FIRST", "LAST", "NULLS"),
    Vector("DISTINCT", "ALL", "UNIQUE", "TOP"),
    Vector("COUNT", "SUM", "AVG", "MIN", "MAX", "STDDEV", "VARIANCE", "COUNT_IF", "COVAR_SAMP", "CORR",
      "ARRAY_AGG", "ANY_VALUE", "LISTAGG"),
    Vector("ROW_NUMBER", "RANK", "DENSE_RANK", "LAG", "LEAD", "FIRST_VALUE", "NTILE", "PERCENT_RANK"),
    Vector("UPPER", "LOWER", "LENGTH", "ABS", "ROUND", "FLOOR", "CEIL", "SUBSTRING", "CONCAT", "COALESCE",
      "NULLIF", "IF", "TRIM", "GREATEST", "LEAST", "SQRT", "POWER", "SIGN", "LN", "REPLACE"),
    Vector("INT", "BIGINT", "FLOAT", "DOUBLE", "STRING", "VARCHAR", "BOOLEAN", "DATE", "TIMESTAMP", "TIME",
      "DECIMAL", "BINARY", "CHAR", "TEXT", "INTERVAL", "ARRAY", "JSON"),
    Vector("NULL", "TRUE", "FALSE", "CURRENT_DATE", "CURRENT_TIMESTAMP", "CURRENT_TIME", "DEFAULT"),
    Vector("ROWS", "RANGE", "GROUPS", "PRECEDING", "FOLLOWING", "CURRENT", "ROW", "UNBOUNDED"),
    Vector("LIMIT", "OFFSET", "FETCH", "NEXT", "ONLY", "PERCENT", "TIES"),
    Vector("SELECT", "FROM", "AS", "BY", "GROUP", "ORDER", "PARTITION", "OVER", "CASE", "WHEN", "THEN", "ELSE",
      "END", "CAST", "WITH", "RECURSIVE", "VALUES", "INTO", "LATERAL", "EXISTS", "ANY", "SOME"))

  private val Keywords: Set[String] = KeywordGroups.flatten.toSet

  /** What an inserted token can be: any keyword, punctuation (a `;` among
    * it), an operator, a parameter, a literal. */
  private val InjectVocabulary: Vector[String] = KeywordGroups.flatten ++ Vector(
    "(", ")", ",", ".", ";", "*", "+", "-", "/", "%", "=", "<", ">", "<>", "!=", "<=", ">=", "||", "::", "?", ":x",
    "@", "#", "[", "]", "{", "}", "\\", "'", "\"", "1", "'x'", "NULL", "1.5", "-1")

  /** Identifiers in scripts and forms the engine's own never use: a letter
    * with an accent, Greek, CJK, a combining mark, a zero-width space inside,
    * a right-to-left override, a fullwidth letter, a Cyrillic letter that looks
    * like `a`, a no-break space. */
  private val UnicodeIdentifiers: Vector[String] = Vector(
    "\u00e9", "caf\u00e9", "\u03a9", "\u8868", "a\u0301", "a\u200bb", Character.toString(0x202e) + "a", "\uff41",
    "\u0430", "\u00a0a")

  /** Numbers and strings at the edges: the ends of INT and BIGINT and one
    * past each, an overflowing and an underflowing double, a negative zero,
    * the empty string, a quote, the LIKE wildcards, a backslash. */
  private val BoundaryNumbers: Vector[String] = Vector(
    "0", "-1", "2147483647", "2147483648", "-2147483649", "9223372036854775807", "9223372036854775808",
    "-9223372036854775808", "1e308", "1e400", "1e-400", "-0.0", "0.000001")
  private val BoundaryStrings: Vector[String] = Vector("''", "'it''s'", "'%'", "'_'", "'\\'", "' '", "'NULL'")

  private val LongLength = 10000

  /** A mutated query and the tables it runs over. `base` is the query before
    * the mutation and `origin` the mode or generator that wrote it. */
  final case class MutationCase(tables: Tables, origin: String, base: String, mutation: Mutation, sql: String) {
    override def toString: String =
      s"MutationCase($origin, $mutation)\n  sql  = ${clip(sql)}\n  base = ${clip(base)}\n  $tables"
  }

  def clip(s: String): String = if (s.length <= 400) s else s.take(400) + s"... (${s.length} chars)"

  def genMutationCase(rng: Rng): MutationCase = {
    val (tables, origin, base) = genBase(rng.split())
    val mrng = rng.split()
    val kind = mrng.weighted(MutationKinds: _*)
    val (mutation, sql) = mutate(mrng, kind, base)
      .orElse(mutate(mrng, "inject-token", base))
      .get
    MutationCase(tables, origin, base, mutation, sql)
  }

  /** A query to mutate: from [[QueryGen]] (one table of any column types),
    * [[MetaQueryGen]] (joins, CTEs, unions, windows), an entry of the clause
    * table, either side of a spelling pair, an honoured clause, a typing
    * query ([[genTyping]]) or a cast of text ([[genCasts]]). */
  private def genBase(rng: Rng): (Tables, String, String) =
    rng.weighted[() => (Tables, String, String)](
      (2, () => genTyping(rng)),
      (1, () => genCasts(rng)),
      (4, () => {
        val qc = QueryGen.generate(rng)
        (Tables(Vector(QueryGen.ViewName -> qc.dataset)), "query-gen", qc.sql)
      }),
      (4, () => {
        val mc = MetaQueryGen.generate(rng)
        (Tables(mc.env.relations.map(r => r.name -> r.dataset)), "meta-query-gen", mc.bodySql)
      }),
      (2, () => {
        val c = rng.oneOf(AllClauseCases)
        (genTables(rng.split()), "unsupported-clause", c.sql)
      }),
      (2, () => {
        val sc = genSpellingCase(rng)
        (sc.tables, "spelling", if (rng.bool()) sc.left else sc.right)
      }),
      (1, () => {
        val hc = genHonouredCase(rng)
        (hc.tables, "honoured-clause", if (rng.bool()) hc.contextSql else hc.honoured.withoutSql)
      }))()

  // ---- typing queries -------------------------------------------------------

  private val TypingFunctions1: Vector[String] = Vector("UPPER", "LOWER", "TRIM", "LENGTH", "ABS", "ROUND", "FLOOR",
    "CEIL", "SQRT", "EXP", "LN", "SIN", "SIGN", "DEGREES", "CONCAT")
  private val TypingFunctions2: Vector[String] = Vector("ROUND", "TRUNC", "POWER", "ATAN2", "LOG", "SUBSTRING",
    "COALESCE", "NULLIF", "GREATEST", "LEAST", "CONCAT")
  private val TypingAggregates: Vector[String] = Vector("SUM", "AVG", "MIN", "MAX", "COUNT", "STDDEV", "VARIANCE",
    "COUNT_IF", "SUM(DISTINCT", "AVG(DISTINCT")
  private val CastTargets: Vector[String] = Vector("INT", "BIGINT", "FLOAT", "DOUBLE", "STRING", "BOOLEAN", "DATE",
    "TIMESTAMP")
  private val TypingLiterals: Vector[String] = Vector("1", "2.5", "'x'", "NULL", "TRUE", "DATE '2024-02-29'", "-1")

  /** A function, an aggregate, a CAST, a UNION, a branch list or a join
    * condition over columns of any of the ten types (a [[DataGen]] table),
    * whether or not it takes that type, over at most 16 rows. [[QueryGen]] is
    * type-correct by construction, so without
    * these the corpus would never offer the binder a type it cannot run, and
    * "rejection is total" would go untested: the binder must refuse at bind
    * time what fails at run time. A string column's values (`"a"`, `"x"`)
    * also make a CAST of it fail on the data, which is the clean run-time
    * failure the property allows. */
  private def genTyping(rng: Rng): (Tables, String, String) = {
    // At most 16 rows: a duplicated join over a table of hundreds nests loops
    // past the heap (plans/robustness/14), which is not this corpus's subject.
    val drawn = DataGen.generate(rng.split())
    val ds = drawn.copy(rows = drawn.rows.take(16))
    val cols = ds.schema.fields.map(_.name)
    def col: String = rng.oneOf(cols)
    def arg: String = if (rng.bool(0.6)) col else rng.oneOf(TypingLiterals)
    val sql = rng.between(0, 7) match {
      case 0 => s"SELECT ${rng.oneOf(TypingFunctions1)}($col) AS v FROM t"
      case 1 => s"SELECT ${rng.oneOf(TypingFunctions2)}($col, $arg) AS v FROM t"
      case 2 =>
        val agg = rng.oneOf(TypingAggregates)
        val call = if (agg.contains("(")) s"$agg $col)" else s"$agg($col)"
        if (rng.bool(0.3)) s"SELECT $call OVER () AS v FROM t" else s"SELECT $call AS v FROM t"
      case 3 => s"SELECT CAST($arg AS ${rng.oneOf(CastTargets)}) AS v FROM t"
      case 4 => s"SELECT $col AS v FROM t UNION ${if (rng.bool()) "ALL " else ""}SELECT $col FROM t"
      case 5 => s"SELECT CASE WHEN $col IS NULL THEN $arg ELSE $col END AS v FROM t"
      case 6 => s"SELECT x.$col AS v FROM t x JOIN t y ON ${if (rng.bool()) s"x.$col" else s"x.$col = y.$col AND y.$col"}"
      case _ => s"SELECT $col AS v FROM t ORDER BY ${rng.oneOf(TypingFunctions1)}($col)"
    }
    (Tables(Vector(QueryGen.ViewName -> ds)), "typing", sql)
  }

  /** A CAST of `t.s`, whose text no number, date or boolean cast reads all
    * of, through each operator that evaluates it: a projection, a filter, an
    * aggregate's argument and group key, a sort key, a join key and a window.
    * A failure while running is the clean outcome there is, and the pooled
    * operators are where it can arrive wrapped. */
  private def genCasts(rng: Rng): (Tables, String, String) = {
    val cast = s"CAST(s AS ${rng.oneOf(Vector("INT", "BIGINT", "DOUBLE", "DATE", "TIMESTAMP", "BOOLEAN"))})"
    val sql = rng.oneOf(Vector(
      s"SELECT a, $cast AS v FROM t",
      s"SELECT a FROM t WHERE $cast IS NOT NULL",
      s"SELECT b, MAX($cast) AS v FROM t GROUP BY b",
      s"SELECT $cast AS k, COUNT(*) AS n FROM t GROUP BY $cast",
      s"SELECT a FROM t ORDER BY $cast",
      s"SELECT t.a FROM t JOIN u ON $cast = u.a",
      s"SELECT a, ROW_NUMBER() OVER (ORDER BY $cast) AS r FROM t",
      s"SELECT DISTINCT $cast AS v FROM t"))
    (genTables(rng.split()), "casts", sql)
  }

  /** Apply one mutation of `kind` to `base`, or `None` when `base` has
    * nothing it applies to (no literal to replace, no clause to duplicate). */
  def mutate(rng: Rng, kind: String, base: String): Option[(Mutation, String)] = {
    val tokens = Lexer.lex(base)
    val sig = tokens.indices.filterNot(i => tokens(i).isSpace).toVector
    def pick(p: Token => Boolean): Option[Int] = {
      val eligible = sig.filter(i => p(tokens(i)))
      if (eligible.isEmpty) None else Some(rng.oneOf(eligible))
    }
    def replace(i: Int, text: String): String = Lexer.render(tokens.updated(i, Token(Kind.Word, text)))
    def insertAt(boundary: Int, text: String): String =
      Lexer.render(tokens.take(boundary)) + s" $text " + Lexer.render(tokens.drop(boundary))
    def boundary: Int = rng.between(0, tokens.length)
    def isIdentifier(t: Token): Boolean = t.kind == Kind.Word && !Keywords(t.upper)

    kind match {
      case "drop-token" =>
        pick(_ => true).map(i => (Mutation(kind, s"dropped '${clip(tokens(i).text)}'"), Lexer.render(tokens.patch(i, Nil, 1))))

      case "duplicate-clause" =>
        val spans = clauseSpans(tokens)
        if (spans.isEmpty) None
        else {
          val (s, e) = rng.oneOf(spans)
          val clause = Lexer.render(tokens.slice(s, e)).trim
          val sql = Lexer.render(tokens.take(e)).stripSuffix(" ") + " " + clause + " " + Lexer.render(tokens.drop(e))
          Some((Mutation(kind, s"repeated '${clip(clause)}'"), sql.trim))
        }

      case "swap-keyword" =>
        pick(t => t.kind == Kind.Word && Keywords(t.upper)).map { i =>
          val word = tokens(i).upper
          val group = KeywordGroups.find(_.contains(word)).get
          val others = group.filterNot(_ == word)
          val to = if (others.nonEmpty && rng.bool(0.7)) rng.oneOf(others) else rng.oneOf(KeywordGroups.flatten)
          (Mutation(kind, s"$word -> $to"), replace(i, to))
        }

      case "truncate" =>
        if (base.length < 2) None
        else {
          val strings = sig.filter(i => tokens(i).kind == Kind.Str && tokens(i).text.length > 1)
          val cut =
            if (strings.nonEmpty && rng.bool(0.5)) {
              val i = rng.oneOf(strings)
              Lexer.render(tokens.take(i)).length + rng.between(1, tokens(i).text.length - 1)
            } else rng.between(1, base.length - 1)
          Some((Mutation(kind, s"cut at $cut of ${base.length}"), base.substring(0, cut)))
        }

      case "unbalance-paren" =>
        val parens = sig.filter(i => tokens(i).text == "(" || tokens(i).text == ")")
        if (parens.nonEmpty && rng.bool(0.5)) {
          val i = rng.oneOf(parens)
          Some((Mutation(kind, s"dropped '${tokens(i).text}' at token $i"), Lexer.render(tokens.patch(i, Nil, 1))))
        } else {
          val p = if (rng.bool()) "(" else ")"
          val at = boundary
          Some((Mutation(kind, s"inserted '$p' at token $at"), insertAt(at, p)))
        }

      case "unicode-identifier" =>
        val ident = rng.oneOf(UnicodeIdentifiers)
        val shown = ident.flatMap(c => if (c < 128) c.toString else f"\\u${c.toInt}%04x")
        pick(isIdentifier) match {
          case Some(i) if rng.bool(0.7) => Some((Mutation(kind, s"'${tokens(i).text}' -> $shown"), replace(i, ident)))
          case _ =>
            val at = boundary
            Some((Mutation(kind, s"inserted $shown at token $at"), insertAt(at, ident)))
        }

      case "long-literal" =>
        val literal = rng.oneOf(Vector(
          "'" + "x" * LongLength + "'",
          "9" * LongLength,
          "0." + "0" * (LongLength - 3) + "1",
          "a" * LongLength))
        pick(t => t.kind == Kind.Number || t.kind == Kind.Str)
          .orElse(pick(isIdentifier))
          .map(i => (Mutation(kind, s"'${clip(tokens(i).text)}' -> a ${literal.length}-character literal"), replace(i, literal)))

      case "inject-token" =>
        val tok = rng.oneOf(InjectVocabulary)
        val at = boundary
        Some((Mutation(kind, s"inserted '$tok' at token $at"), insertAt(at, tok)))

      case "boundary-literal" =>
        pick(t => t.kind == Kind.Number || t.kind == Kind.Str).map { i =>
          val to = if (tokens(i).kind == Kind.Number) rng.oneOf(BoundaryNumbers) else rng.oneOf(BoundaryStrings)
          (Mutation(kind, s"'${clip(tokens(i).text)}' -> $to"), replace(i, to))
        }

      case "deep-nesting" =>
        // Only a number outside every parenthesis: one is always an
        // expression, while nested where a name stands (a table, an alias) or
        // inside a call's arguments the text buys seconds of JSqlParser
        // backtracking and tests nothing past it. Parentheses stay shallow for
        // the same reason; a chain and nested calls go deep enough to overflow
        // the binder's stack and the parser's.
        val depth = Lexer.depths(tokens)
        val topNumbers = sig.filter(i => tokens(i).kind == Kind.Number && depth(i) == 0)
        (if (topNumbers.isEmpty) None else Some(rng.oneOf(topNumbers))).map { i =>
          val x = tokens(i).text
          val (form, depth, nested) = rng.between(0, 2) match {
            case 0 => ("parentheses", 50, "(" * 50 + x + ")" * 50)
            case 1 =>
              val d = rng.oneOf(Vector(50, 300, 2500))
              ("a + chain", d, x + " + 0" * d)
            case _ =>
              val d = rng.oneOf(Vector(50, 300, 2500))
              ("nested calls", d, "ABS(" * d + x + ")" * d)
          }
          (Mutation(kind, s"'$x' -> $depth-deep $form"), replace(i, nested))
        }

      case other => throw new IllegalArgumentException(s"unknown mutation $other")
    }
  }

  /** Words that start a clause after the FROM list. */
  private val TailStarters = Set("WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "FETCH", "QUALIFY", "WINDOW")
  /** Words that end a clause without starting one a query can repeat. */
  private val QueryWords = Set("SELECT", "FROM", "UNION", "EXCEPT", "INTERSECT", "MINUS", "WITH")
  private val JoinKindWords = Set("INNER", "LEFT", "RIGHT", "FULL", "CROSS", "OUTER", "NATURAL")

  /** `[start, end)` token ranges of the clauses in `tokens` a query can carry
    * twice: a tail clause (WHERE ... WINDOW) or a join, `LEFT OUTER JOIN x ON
    * p` whole. Each runs to the next clause word at its depth, a `)` that
    * leaves its depth, a `;`, or the end. */
  private def clauseSpans(tokens: Vector[Token]): Vector[(Int, Int)] = {
    val depth = Lexer.depths(tokens)
    def prevWord(i: Int): String = {
      var j = i - 1
      while (j >= 0 && tokens(j).isSpace) j -= 1
      if (j >= 0) tokens(j).upper else ""
    }
    def joinStart(i: Int): Boolean = {
      val w = tokens(i).upper
      (JoinKindWords(w) || w == "JOIN") && !JoinKindWords(prevWord(i))
    }
    def starts(i: Int): Boolean = tokens(i).kind == Kind.Word && (TailStarters(tokens(i).upper) || joinStart(i))
    def ends(i: Int): Boolean = starts(i) || (tokens(i).kind == Kind.Word && QueryWords(tokens(i).upper))
    tokens.indices.filter(starts).map { s =>
      val d = depth(s)
      var e = s + 1
      while (e < tokens.length && depth(e) >= d && tokens(e).text != ";" && !(depth(e) == d && ends(e))) e += 1
      (s, e)
    }.toVector
  }

  // ===========================================================================
  // Any text, for the clean-rejection property
  // ===========================================================================

  /** A SQL text from any mode, the tables it runs over, where it came from
    * and what was done to it. */
  final case class TextCase(tables: Tables, sql: String, origin: String, detail: String) {
    override def toString: String = s"TextCase($origin: $detail)\n  sql = ${clip(sql)}\n  $tables"
  }

  /** Mostly mutations, since how a query fails is what the property is
    * about; the rest unmutated text from the other three modes, typing
    * queries and casts of text, which mostly run or fail with a rejection or
    * on a value. */
  def genTextCase(rng: Rng): TextCase = rng.weighted[() => TextCase](
    (14, () => {
      val m = genMutationCase(rng)
      TextCase(m.tables, m.sql, s"mutation/${m.origin}", m.mutation.toString)
    }),
    (2, () => {
      val c = rng.oneOf(AllClauseCases)
      TextCase(genTables(rng.split()), c.sql, "unsupported-clause", s"${c.entry.clause} at ${c.position} in ${c.context}")
    }),
    (2, () => {
      val s = genSpellingCase(rng)
      TextCase(s.tables, if (rng.bool()) s.left else s.right, "spelling", s.shape)
    }),
    (2, () => {
      val h = genHonouredCase(rng)
      TextCase(h.tables, h.contextSql, "honoured-clause", s"${h.honoured.key} in ${h.context}")
    }),
    (3, () => {
      val (tables, origin, sql) = genTyping(rng)
      TextCase(tables, sql, origin, "unmutated")
    }),
    (2, () => {
      val (tables, origin, sql) = genCasts(rng)
      TextCase(tables, sql, origin, "unmutated")
    }))()
}
