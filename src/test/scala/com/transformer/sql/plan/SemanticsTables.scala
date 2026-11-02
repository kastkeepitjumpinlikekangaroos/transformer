package com.transformer.sql.plan

import com.transformer.sql.plan.Semantics.Generated

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** The engine's rules as tables, the files under `src/test/semantics`: each
  * one generated here, compared with its file by `semantics_tables_test`, and
  * written back by
  * {{{
  *   bazel run //src/test/scala/com/transformer/sql/plan:semantics_tables -- --update
  * }}}
  * in the commit that changes a rule, whose diff is then every cell the rule
  * reaches. Without `--update` it prints each table's diff, if any. */
object SemanticsTables {

  /** Each table's file name and how to generate it. */
  val Tables: Seq[(String, () => Generated)] = Seq(
    CastMatrix.File -> (() => CastMatrix.generate()))

  /** Where the files are: the runfiles tree under Bazel, the source tree
    * otherwise. */
  val Dir: Path = Paths.get("src/test/semantics")

  def read(dir: Path, file: String): Vector[String] = {
    val path = dir.resolve(file)
    if (!Files.exists(path)) Vector.empty
    else new String(Files.readAllBytes(path), StandardCharsets.UTF_8).split("\n", -1).toVector.dropRight(1)
  }

  def write(dir: Path, file: String, lines: Seq[String]): Unit =
    Files.write(dir.resolve(file), lines.mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8))

  def main(args: Array[String]): Unit = {
    val update = args.contains("--update")
    // Under `bazel run` the workspace is where the files are written.
    val dir = Option(System.getenv("BUILD_WORKSPACE_DIRECTORY")).map(Paths.get(_).resolve(Dir)).getOrElse(Dir)
    Tables.foreach { case (file, generate) =>
      val generated = generate()
      val before = read(dir, file)
      if (update) {
        write(dir, file, generated.lines)
        println(s"$file: ${generated.lines.size} lines" + (if (before == generated.lines) ", unchanged" else ", written"))
      } else Semantics.diff(file, before, generated.lines) match {
        case None => println(s"$file: ${generated.lines.size} lines, as generated")
        case Some(d) => println(d)
      }
      generated.diverged.foreach(l => println(s"$file: the paths disagree: $l"))
    }
  }
}
