package com.transformer.tools.mutation

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

/** One equivalent mutant: a mutant that changes no behaviour, so no test can
  * kill it. It is found by its file, its operator, its change and the text of
  * the line it is on, not the line's number, so the entry survives edits
  * elsewhere in the file. An entry covers every site of that change on a line
  * of that text. */
final case class Equivalent(file: String, operator: String, original: String, mutated: String, lineText: String, reason: String) {
  def covers(file: String, site: Site, lineText: String): Boolean =
    this.file == file && operator == site.operator && original == Site.shown(site.original) &&
      mutated == Site.shown(site.replacement) && this.lineText == lineText.trim
}

/** The ledger of equivalent mutants, `tools/mutation/equivalent.tsv`: a header
  * line, then one mutant a line, its fields `file`, `operator`, `original`,
  * `mutated`, `line` and `reason` separated by tabs. A line starting with `#`
  * is a comment. Sampling leaves these mutants out. */
object Ledger {
  val Header: String = Seq("file", "operator", "original", "mutated", "line", "reason").mkString("\t")

  def read(path: Path): Vector[Equivalent] =
    if (!Files.exists(path)) Vector.empty
    else
      Files.readAllLines(path).asScala.toVector.zipWithIndex.flatMap { case (line, i) =>
        if (i == 0 || line.isEmpty || line.startsWith("#")) None
        else
          line.split("\t", -1) match {
            case Array(file, operator, original, mutated, text, reason) =>
              Some(Equivalent(file, operator, original, mutated, text.trim, reason))
            case _ => throw new IllegalArgumentException(s"$path:${i + 1}: expected 6 fields separated by tabs")
          }
      }

  /** The text of the line `site` starts on. */
  def lineText(src: String, site: Site): String = {
    val from = src.lastIndexOf('\n', site.start - 1) + 1
    val to = src.indexOf('\n', site.start)
    src.substring(from, if (to < 0) src.length else to).trim
  }
}
