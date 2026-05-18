package com.example

import com.transformer.job._
import com.transformer.temporal.TemporalVariables

import java.time.Instant

/** Example ETL job built with the transformer library.
  *
  * Reads two CSVs from a local folder, joins them, aggregates per user-tier,
  * runs a data-quality validation, and writes the result to a templated path.
  *
  * Build + run:
  *
  *   bazel build //examples/scala_app:example_job_deploy.jar
  *   java -jar bazel-bin/examples/scala_app/example_job_deploy.jar \
  *     [path-to-data-input] [path-to-output-dir]
  *
  * When run via `bazel run` the data files are placed under the runfiles tree at
  * `examples/scala_app/data/input/`; the binary auto-detects this if no args are
  * supplied.
  */
object ExampleJob {
  def main(args: Array[String]): Unit = {
    val inputDir = args.headOption.getOrElse(autoDetectInputDir())
    val outputDir = args.lift(1).getOrElse(System.getProperty("java.io.tmpdir") + "/transformer-example-out")
    val executionTime = Instant.parse("2026-01-01T05:30:21Z")
    println(s"Reading from: $inputDir")
    println(s"Writing to:   $outputDir")

    val job = DataJob(
      temporalVariables = Some(TemporalVariables(executionTime)),
      inputs = Seq(
        InputFilePath(s"$inputDir/events.csv", viewName = "events"),
        InputFilePath(s"$inputDir/users.csv", viewName = "users")
      ),
      sql = Seq(
        // Task 1: join events with users, persist enriched stream.
        SQLTask(
          name = Some("enrich_events"),
          sqlString = Some(
            """SELECT e.user_id, u.name, u.tier, e.event, e.amount
              |FROM events e
              |LEFT JOIN users u ON e.user_id = u.user_id
              |""".stripMargin),
          outputFile = Some(OutputFilePath(s"$outputDir/day={{ today }}/enriched", format = Some("csv"))),
          viewName = Some("enriched")
        ),
        // Task 2: aggregate spend per tier with a DBT-style validation.
        SQLTask(
          name = Some("spend_by_tier"),
          sqlString = Some(
            """SELECT tier, COUNT(*) AS event_count, SUM(amount) AS total_spend
              |FROM enriched
              |WHERE event = 'buy'
              |GROUP BY tier
              |ORDER BY tier
              |""".stripMargin),
          outputFile = Some(OutputFilePath(s"$outputDir/day={{ today }}/spend_by_tier", format = Some("csv"))),
          viewName = Some("spend_by_tier"),
          validations = Seq(
            Validation(
              name = "no_null_tiers",
              sqlString = Some("SELECT * FROM spend_by_tier WHERE tier IS NULL")
            )
          )
        )
      )
    )

    val result = job.run()
    println(s"Job ${if (result.succeeded) "SUCCEEDED" else "FAILED"}")
    result.tasks.foreach { t =>
      println(f"  ${t.taskName}%-30s  rows=${t.rowsProduced}%6d  ${t.durationMillis}%5d ms  status=${t.status}")
    }
    if (!result.succeeded) {
      result.error.foreach(e => System.err.println(s"Error: $e"))
      System.exit(1)
    }
  }

  private def autoDetectInputDir(): String = {
    val candidates = Seq(
      "examples/scala_app/data/input",
      "data/input",
      "../examples/scala_app/data/input"
    )
    candidates.find(p => new java.io.File(p).isDirectory)
      .getOrElse(throw new RuntimeException(
        "Could not auto-detect input dir; pass it as the first argument. " +
          "Tried: " + candidates.mkString(", ")
      ))
  }
}
