package com.transformer.tools.mutation

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** A group of test targets a mutant runs against, in order: a mutant is killed
  * at the first stage in which a test fails, and the later stages do not run.
  * `flags` are added to `bazel test` (a whole-suite configuration).
  * `timeoutSeconds` bounds each target: three times the slowest one's baseline. */
final case class Stage(number: Int, name: String, targets: Vector[String], flags: Vector[String], timeoutSeconds: Long)

/** What one `bazel test` gave: each target's status (`PASSED`, `FAILED`,
  * `TIMEOUT`, `FAILED_TO_BUILD`, ...) and, for a failed one, the tests that
  * failed. A target with no status did not run to the end. */
final case class TestRun(statuses: Map[String, String], failedTests: Map[String, Vector[String]]) {
  def status(target: String): String = statuses.getOrElse(target, "NO_STATUS")
}

/** Where a campaign runs its builds and tests: Bazel in a worktree, or a
  * script in the runner's own test. */
trait Executor {
  /** Builds the targets that compile the mutated file: `None` when they
    * build, else the first error the compiler gave. */
  def build(targets: Seq[String]): Option[String]

  /** Runs the targets' tests; `cached` false runs them even when Bazel holds
    * a result for their inputs. */
  def test(targets: Seq[String], flags: Seq[String], timeoutSeconds: Long, cached: Boolean): TestRun
}

/** The sources a campaign mutates: the files of a worktree, or of the
  * runner's own test. */
trait Workspace {
  def read(file: String): String
  def write(file: String, text: String): Unit
}

final class DirectoryWorkspace(root: Path) extends Workspace {
  def read(file: String): String = Files.readString(root.resolve(file), StandardCharsets.UTF_8)
  def write(file: String, text: String): Unit = {
    val _ = Files.writeString(root.resolve(file), text, StandardCharsets.UTF_8)
  }
}

/** What became of one mutant: `killed` (by `target`'s `tests`, at `stage`),
  * `timeout` (`target` ran past its stage's timeout), `survived` or
  * `stillborn` (it did not compile: `note` holds the compiler's error). A
  * target that also failed on the unmutated file is no kill: `note` names it. */
final case class Result(mutant: Mutant, outcome: String, stage: Int, target: String, tests: Vector[String], seconds: Long, note: String)

object Result {
  val Outcomes: Vector[String] = Vector("killed", "timeout", "survived", "stillborn")

  val Header: String =
    Seq("id", "file", "line", "column", "operator", "original", "mutated", "outcome", "stage", "target", "test", "seconds", "note")
      .mkString("\t")

  def row(r: Result): String = Seq(
    r.mutant.id, r.mutant.file, r.mutant.site.line.toString, r.mutant.site.column.toString, r.mutant.site.operator,
    Site.shown(r.mutant.site.original), Site.shown(r.mutant.site.replacement), r.outcome,
    if (r.stage > 0) r.stage.toString else "", r.target, r.tests.mkString(" "), r.seconds.toString,
    r.note.replaceAll("\\s+", " ")).mkString("\t")
}

/** Runs each mutant: writes it into its file, builds the file's own targets
  * (a mutant that does not compile is stillborn), runs the stages in order and
  * puts the file back, whatever happened. A target that fails a stage is run
  * once more on the unmutated file, without Bazel's cached result: a target
  * that fails there too is flaky and kills nothing, and the stages go on. */
final class Runner(workspace: Workspace, executor: Executor, log: String => Unit) {

  def run(mutant: Mutant, libraries: Seq[String], stages: Seq[Stage]): Result = {
    val started = System.nanoTime()
    def seconds: Long = (System.nanoTime() - started) / 1000000000L
    val original = workspace.read(mutant.file)
    val mutated = mutant.site.applyTo(original)
    if (Site.shown(original.substring(mutant.site.start, mutant.site.end)) != Site.shown(mutant.site.original))
      throw new IllegalStateException(s"${mutant.id}: ${mutant.file} no longer holds '${mutant.site.original}' at ${mutant.site.line}:${mutant.site.column}")
    workspace.write(mutant.file, mutated)
    try {
      executor.build(libraries) match {
        case Some(error) => Result(mutant, "stillborn", 0, "", Vector.empty, seconds, error)
        case None =>
          val notes = Vector.newBuilder[String]
          var result: Option[Result] = None
          val remaining = stages.iterator
          while (result.isEmpty && remaining.hasNext) {
            val stage = remaining.next()
            log(s"${mutant.id} stage ${stage.number} (${stage.name}): ${stage.targets.size} targets")
            val run = executor.test(stage.targets, stage.flags, stage.timeoutSeconds, cached = true)
            val unbuilt = stage.targets.filter(run.status(_) == "FAILED_TO_BUILD")
            val failing = stage.targets.filter(t => run.status(t) == "FAILED" || run.status(t) == "TIMEOUT")
            val missing = stage.targets.filter(t => run.status(t) == "NO_STATUS" || run.status(t) == "INCOMPLETE")
            if (unbuilt.nonEmpty)
              result = Some(Result(mutant, "stillborn", stage.number, unbuilt.head, Vector.empty, seconds,
                s"${unbuilt.mkString(" ")} did not build"))
            else if (failing.nonEmpty) {
              workspace.write(mutant.file, original)
              val again = executor.test(failing, stage.flags, stage.timeoutSeconds, cached = false)
              workspace.write(mutant.file, mutated)
              val (confirmed, flaky) = failing.partition(again.status(_) == "PASSED")
              if (flaky.nonEmpty) notes += s"stage ${stage.number}: ${flaky.mkString(" ")} failed on the unmutated file too, no kill"
              val killer = confirmed.find(run.status(_) == "FAILED").orElse(confirmed.headOption)
              killer.foreach { t =>
                val outcome = if (run.status(t) == "TIMEOUT") "timeout" else "killed"
                result = Some(Result(mutant, outcome, stage.number, t, run.failedTests.getOrElse(t, Vector.empty), seconds,
                  notes.result().mkString("; ")))
              }
            } else if (missing.nonEmpty)
              notes += s"stage ${stage.number}: ${missing.mkString(" ")} gave no status"
          }
          result.getOrElse(Result(mutant, "survived", 0, "", Vector.empty, seconds, notes.result().mkString("; ")))
      }
    } finally workspace.write(mutant.file, original)
  }
}
