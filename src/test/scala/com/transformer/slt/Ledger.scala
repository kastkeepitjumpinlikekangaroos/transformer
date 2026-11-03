package com.transformer.slt

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** The rules by which the engine answers otherwise than other engines, on
  * purpose: the table under [[Ledger.Heading]] in `docs/gotchas.md`, a rule
  * a row. A script cites a rule by its id, in `skipif transformer <id>` before
  * a record of another engine's answer the engine does not give, and in
  * `onlyif transformer <id>` before a record of the engine's own answer under
  * the rule (see [[Script]]).
  *
  * {{{
  *   | id | rule | refusal | stated in | decided in | pinned by |
  *   |---|---|---|---|---|---|
  *   | `div-zero-null` | A division or `%` by zero is NULL. | | README ... | ... | `types/float_equality.test` |
  * }}}
  *
  * The columns are fixed and the parse is strict, since a reader and the
  * build read one table: an id is lower-case words joined by `-`; a rule whose
  * id starts `refused-` is a construct the engine refuses, and gives in
  * `refusal` the text its refusal must contain, which no other rule gives; and
  * `pinned by` names the script, under `src/test/slt`, holding a record of the
  * engine's own answer under the rule, or, for a rule about files rather than
  * SQL, the fixture manifest under `src/test/data` holding a file read or
  * refused under it (`by-design <id>`). A `|` inside a cell is written `\|`.
  */
final case class Ledger(rules: Vector[Ledger.Rule]) {

  private val byId: Map[String, Ledger.Rule] = rules.iterator.map(r => r.id -> r).toMap

  def rule(id: String): Option[Ledger.Rule] = byId.get(id)

  def ids: Vector[String] = rules.map(_.id).sorted

  /** The id nearest `written` by edit distance, to name in the error for an
    * id the ledger does not have. */
  def nearest(written: String): String = rules.minBy(r => Ledger.editDistance(written, r.id)).id

  /** The id of the rule whose id, sentence and refusal share the most words
    * with `text`, a condition's comment, to name in the error for a condition
    * that cites none; None when no rule shares a word. */
  def suggest(text: String): Option[String] = {
    val words = Ledger.words(text)
    val (best, shared) = rules.map(r => r -> Ledger.words(s"${r.id} ${r.rule} ${r.refusal.getOrElse("")}")
      .intersect(words).size).maxBy(_._2)
    if (shared > 0) Some(best.id) else None
  }
}

object Ledger {

  /** One row: `refusal` is the text a refusal under the rule must contain,
    * for a rule whose id starts `refused-`; `line` is the row's line in the
    * file. */
  final case class Rule(id: String, rule: String, refusal: Option[String], statedIn: String, decidedIn: String,
      pinnedBy: String, line: Int) {
    def refuses: Boolean = refusal.isDefined
    /** Whether a fixture manifest pins the rule, rather than a script. */
    def pinnedByManifest: Boolean = pinnedBy.endsWith(ManifestName)
  }

  /** The name of a fixture manifest: a line a file of the fixture directory
    * it is in, and what the engine does with the file. */
  val ManifestName: String = "MANIFEST"

  /** The ids a fixture manifest's text cites, `by-design <id>` in a line. */
  def manifestCitations(text: String): Set[String] =
    text.split("\n").iterator.map(_.takeWhile(_ != '#').trim.split("\\s+").toSeq).flatMap { words =>
      words.sliding(2).collect { case Seq("by-design", id) => id }
    }.toSet

  val Heading: String = "## Answers that differ from other engines by design"

  val Columns: Vector[String] = Vector("id", "rule", "refusal", "stated in", "decided in", "pinned by")

  /** Where the tests read it: the runfiles tree under Bazel (the root
    * package exports the file), the source tree otherwise. */
  val DefaultPath: Path = Paths.get("docs/gotchas.md")

  private val IdCell = """`([a-z0-9]+(?:-[a-z0-9]+)*)`""".r
  private val CodeCell = """`([^`]+)`""".r
  private val ScriptCell = """`([^`]+\.test|src/test/data/[^`]+/MANIFEST)`""".r
  private val Separator = """:?-{3,}:?""".r

  def load(path: Path): Ledger =
    parse(path.toString, new String(Files.readAllBytes(path), StandardCharsets.UTF_8))

  /** The table under [[Heading]] in `text`, the markdown of `file`. Fails
    * with the line it could not read. */
  def parse(file: String, text: String): Ledger = {
    val lines = text.split("\n", -1).toVector.map(l => if (l.endsWith("\r")) l.dropRight(1) else l)
    def fail(line: Int, msg: String): Nothing = throw new Script.ParseError(file, line, msg)
    val start = lines.indexWhere(_.trim == Heading)
    if (start < 0) fail(1, s"no section '$Heading'")
    val end = lines.indexWhere(_.startsWith("## "), start + 1) match { case -1 => lines.length; case k => k }
    val header = lines.indexWhere(_.trim.startsWith("|"), start + 1)
    if (header < 0 || header >= end) fail(start + 1, "the section holds no table")
    if (cells(lines(header)).map(_.trim) != Columns)
      fail(header + 1, s"the table's columns are ${Columns.mkString(" | ")}")
    val separator = if (header + 1 < end) cells(lines(header + 1)).map(_.trim) else Vector.empty
    if (separator.length != Columns.length || !separator.forall(c => Separator.matches(c)))
      fail(header + 2, "the header is followed by a line of |---| cells, one a column")
    val out = Vector.newBuilder[Rule]
    val seen = scala.collection.mutable.HashMap.empty[String, Int]
    var k = header + 2
    while (k < end && lines(k).trim.startsWith("|")) {
      val line = k + 1
      val row = cells(lines(k)).map(_.trim)
      if (row.length != Columns.length) fail(line, s"a row has ${Columns.length} cells, not ${row.length}")
      val id = row(0) match {
        case IdCell(i) => i
        case other => fail(line, s"an id is lower-case words joined by - in backquotes, not '$other'")
      }
      seen.get(id).foreach(prev => fail(line, s"'$id' is the id of line $prev too"))
      seen(id) = line
      if (row(1).isEmpty) fail(line, s"'$id' states no rule")
      val refusal = row(2) match {
        case "" => None
        case CodeCell(t) => Some(t.replace("\\|", "|"))
        case other => fail(line, s"a refusal is the text it must contain, in backquotes, not '$other'")
      }
      if (id.startsWith("refused-") && refusal.isEmpty)
        fail(line, s"'$id' is a refusal, so it gives the text its refusal must contain")
      if (!id.startsWith("refused-") && refusal.nonEmpty)
        fail(line, s"only a refused- rule gives a refusal's text, and '$id' is none")
      if (row(3).isEmpty) fail(line, s"'$id' names no doc that states it")
      if (row(4).isEmpty) fail(line, s"'$id' names no decision")
      val pin = row(5) match {
        case ScriptCell(p) => p
        case other => fail(line, s"'$id' is pinned by a script under src/test/slt or a fixture manifest under " +
          s"src/test/data, in backquotes, not '$other'")
      }
      out += Rule(id, row(1), refusal, row(3), row(4), pin, line)
      k += 1
    }
    val rules = out.result()
    if (rules.isEmpty) fail(header + 3, "the table has no rows")
    Ledger(rules)
  }

  /** A table row's cells: the text between its `|`s, a `\|` kept in its cell. */
  private def cells(row: String): Vector[String] = {
    val t = row.trim
    if (!t.startsWith("|") || !t.endsWith("|") || t.length < 2) return Vector.empty
    val out = Vector.newBuilder[String]
    val cur = new StringBuilder
    var i = 1
    while (i < t.length - 1) {
      val c = t(i)
      if (c == '\\' && t(i + 1) == '|') { cur ++= "\\|"; i += 2 }
      else if (c == '|') { out += cur.result(); cur.clear(); i += 1 }
      else { cur += c; i += 1 }
    }
    out += cur.result()
    out.result()
  }

  /** The lower-case words of three letters or more in `text`. */
  private def words(text: String): Set[String] =
    text.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+").iterator.filter(_.length >= 3).toSet

  private[slt] def editDistance(a: String, b: String): Int = {
    val prev = Array.tabulate(b.length + 1)(identity)
    val cur = new Array[Int](b.length + 1)
    var i = 1
    while (i <= a.length) {
      cur(0) = i
      var j = 1
      while (j <= b.length) {
        val cost = if (a(i - 1) == b(j - 1)) 0 else 1
        cur(j) = math.min(math.min(cur(j - 1) + 1, prev(j) + 1), prev(j - 1) + cost)
        j += 1
      }
      System.arraycopy(cur, 0, prev, 0, cur.length)
      i += 1
    }
    prev(b.length)
  }
}
