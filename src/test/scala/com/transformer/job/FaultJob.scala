package com.transformer.job

import java.nio.file.{Files, Path, Paths}

/** The job `DataJobTest`'s fault matrices run, which reaches every fault point
  * ([[com.transformer.core.Faults]]) in one clean run over an earlier run's
  * output: two input partitions; `t`, parquet, with metrics on and a passing
  * validation; `d`, reading `t`; `v`, whose validation fails, so its sample
  * rows are written; `f`, which fails before it writes; and `s`, skipped
  * after `f`. `broken`, `t` fails before it writes too, so it and `d` end
  * without output over the parts the earlier run left them.
  *
  * Also the main a child JVM runs the job through, armed with
  * `-Dtransformer.fault=<point>:<n>:halt`, so a halt stops that JVM and never
  * the test's: `FaultJob <input dir> <output dir> [broken]`. It exits 0 when
  * the job ran to its end, whatever the job's verdict.
  */
object FaultJob {

  /** Writes the inputs into `in`, two files the glob reads as two
    * partitions. */
  def writeInputs(in: Path): Unit = {
    Files.createDirectories(in)
    Files.writeString(in.resolve("a.csv"), "x\n1\n2\n")
    Files.writeString(in.resolve("b.csv"), "x\n3\n4\n")
  }

  /** The tasks, each writing `<out>/<name>`, and `<out>/job.json`. */
  def job(in: Path, out: Path, broken: Boolean = false): DataJob = {
    def output(name: String, format: String, options: Map[String, String] = Map.empty) =
      Some(OutputFilePath(out.resolve(name).toString, options = options, format = Some(format)))
    DataJob(
      inputs = Seq(InputFilePath(in.toString + "/*.csv", viewName = "src")),
      sql = Seq(
        SQLTask(name = Some("t"), viewName = Some("t"),
          sqlString = Some(if (broken) "SELECT x, no_such_column AS y FROM src" else "SELECT x, x * 10 AS y FROM src"),
          outputFile = output("t", "parquet", Map("metrics" -> "true")),
          validations = Seq(Validation("positive", sqlString = Some("SELECT x FROM t WHERE x <= 0")))),
        SQLTask(name = Some("d"), viewName = Some("d"), sqlString = Some("SELECT COUNT(*) AS n FROM t"),
          outputFile = output("d", "csv")),
        SQLTask(name = Some("v"), viewName = Some("v"), sqlString = Some("SELECT x FROM src"),
          outputFile = output("v", "csv"),
          validations = Seq(Validation("small", sqlString = Some("SELECT x FROM v WHERE x > 2")))),
        SQLTask(name = Some("f"), viewName = Some("f"), sqlString = Some("SELECT no_such_column FROM src"),
          outputFile = output("f", "csv")),
        SQLTask(name = Some("s"), viewName = Some("s"), sqlString = Some("SELECT * FROM f"),
          outputFile = output("s", "csv"))),
      jobRunOutput = Some(OutputFilePath(out.resolve("job.json").toString)))
  }

  /** Each task and the status a clean run gives it. */
  def statuses(broken: Boolean = false): Seq[(String, String)] =
    Seq("t" -> (if (broken) "Failed" else "Succeeded"), "d" -> (if (broken) "Skipped" else "Succeeded"),
      "v" -> "ValidationFailed", "f" -> "Failed", "s" -> "Skipped")

  def main(args: Array[String]): Unit = {
    job(Paths.get(args(0)), Paths.get(args(1)), broken = args.lift(2).contains("broken")).run()
    System.exit(0)
  }
}
