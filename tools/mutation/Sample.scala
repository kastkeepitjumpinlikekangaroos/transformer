package com.transformer.tools.mutation

import java.nio.file.{Files, Path}
import java.util.Locale
import java.util.SplittableRandom
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** A mutant to run: the source file (relative to the repository's root) and
  * its site there. `id` names it in the results. */
final case class Mutant(id: String, file: String, site: Site)

/** Which mutants a campaign runs. Stratified by operator: each file's quota is
  * dealt out an operator at a time, in [[Mutator.Operators]]' order, each turn
  * taking one site of that operator at random, until the quota is filled or the
  * file has no site left. A file with few sites of an operator still gets them;
  * one with many does not crowd the others out. The seed and the file's name
  * fix the draw. */
object Sample {
  def stratified(file: String, sites: Vector[Site], quota: Int, seed: Long): Vector[Site] = {
    val random = new SplittableRandom(seed ^ file.hashCode.toLong)
    val pools: Map[String, mutable.ArrayBuffer[Site]] =
      sites.groupBy(_.operator).map { case (op, ss) => op -> mutable.ArrayBuffer.from(ss) }
    val order = Mutator.Operators.filter(pools.contains)
    val taken = Vector.newBuilder[Site]
    var count = 0
    var progress = true
    while (count < quota && progress) {
      progress = false
      order.foreach { op =>
        val pool = pools(op)
        if (count < quota && pool.nonEmpty) {
          val i = random.nextInt(pool.size)
          taken += pool(i)
          pool(i) = pool.last
          pool.dropRightInPlace(1)
          count += 1
          progress = true
        }
      }
    }
    taken.result().sortBy(_.start)
  }

  /** The mutants of `files`, numbered from `m001` in the files' order:
    * every site that the ledger does not cover, or a stratified sample of
    * `quota` of them. */
  def mutants(files: Seq[(String, String)], quota: Option[Int], seed: Long, ledger: Seq[Equivalent]): Vector[Mutant] = {
    val chosen = files.flatMap { case (file, src) =>
      val open = Mutator.sites(src).filterNot(s => ledger.exists(_.covers(file, s, Ledger.lineText(src, s))))
      quota.fold(open)(q => stratified(file, open, q, seed)).map(file -> _)
    }
    chosen.zipWithIndex.map { case ((file, site), i) => Mutant("m%03d".formatLocal(Locale.ROOT, i + 1), file, site) }.toVector
  }

  /** A list of mutants is a TSV file of these columns: the mutant's id, its
    * file, the line and column it starts at, its operator and its change. */
  val MutantHeader: String = Seq("id", "file", "line", "column", "operator", "original", "mutated").mkString("\t")

  def mutantRow(m: Mutant): String = Seq(
    m.id, m.file, m.site.line.toString, m.site.column.toString, m.site.operator,
    Site.shown(m.site.original), Site.shown(m.site.replacement)).mkString("\t")

  /** The mutants a list names, each found again among its file's sites (from
    * `sources`, a file's text by its name): a list made at another commit
    * fails here, naming the mutant, rather than mutating the wrong place. */
  def readMutants(path: Path, sources: String => String): Vector[Mutant] = {
    val bySource = mutable.Map.empty[String, Vector[Site]]
    readTsv(path).map { row =>
      val file = row("file")
      val sites = bySource.getOrElseUpdate(file, Mutator.sites(sources(file)))
      val site = sites.find(s =>
        s.line.toString == row("line") && s.column.toString == row("column") && s.operator == row("operator") &&
          Site.shown(s.replacement) == row("mutated")).getOrElse(
        throw new IllegalArgumentException(s"${row("id")}: no ${row("operator")} site at $file:${row("line")}:${row("column")}"))
      Mutant(row("id"), file, site)
    }
  }

  /** A TSV file with a header line, each line after it as a map from the
    * header's names; none when the file does not exist. */
  def readTsv(path: Path): Vector[Map[String, String]] =
    if (!Files.exists(path)) Vector.empty
    else {
      val lines = Files.readAllLines(path).asScala.toVector.filter(_.nonEmpty)
      val header = lines.headOption.map(_.split("\t", -1).toVector).getOrElse(Vector.empty)
      lines.drop(1).map(l => header.zip(l.split("\t", -1)).toMap)
    }
}
