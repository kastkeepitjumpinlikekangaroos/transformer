package com.example.directory

import com.transformer.job.DirectoryJobLoader
import com.transformer.temporal.TemporalVariables

import java.nio.file.Paths
import java.time.Instant

/** Example ETL job assembled from a directory tree rather than Scala code.
  *
  * The job directory under `examples/directory_app/job/` contains:
  *
  * {{{
  *   job/
  *     inputs/
  *       events/config.json     // points at job/data/events.csv
  *       users/config.json      // points at job/data/users.csv
  *     tables/
  *       enriched_events/main.sql              // join + templated execution_time
  *       spend_by_tier/main.sql                // aggregate + templated execution_date
  *       spend_by_tier/validations/
  *         no_null_tiers.sql                   // DBT-style data-quality check
  *     data/
  *       events.csv, users.csv
  * }}}
  *
  * Build + run:
  *
  *   bazel build //examples/directory_app:directory_example_deploy.jar
  *   java -jar bazel-bin/examples/directory_app/directory_example_deploy.jar \
  *     [path-to-job-dir] [path-to-output-dir]
  */
object DirectoryJobExample {
  def main(args: Array[String]): Unit = {
    val jobDir = Paths.get(args.headOption.getOrElse(autoDetectJobDir()))
    val outputDir =
      args.lift(1).getOrElse(System.getProperty("java.io.tmpdir") + "/transformer-directory-out")
    val executionTime = Instant.parse("2026-01-01T05:30:21Z")

    println(s"Loading job from: $jobDir")
    println(s"Writing to:       $outputDir (templated)")

    val job = DirectoryJobLoader.load(
      jobDir = jobDir,
      outputDir = Some(outputDir + "/day={{ today }}"),
      temporalVariables = Some(TemporalVariables(executionTime))
    )

    println(s"Loaded ${job.inputs.size} input(s), ${job.sql.size} table(s):")
    job.inputs.foreach(in => println(s"  input  '${in.viewName}'  <- ${in.path}"))
    job.sql.foreach { t =>
      val out = t.outputFile.map(_.path).getOrElse("(no output)")
      val v = if (t.validations.nonEmpty) s"  validations=${t.validations.map(_.name).mkString(",")}" else ""
      println(s"  table  '${t.viewName.getOrElse("?")}'  -> $out$v")
    }

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

  private def autoDetectJobDir(): String = {
    val candidates = Seq(
      "examples/directory_app/job",
      "job",
      "../examples/directory_app/job"
    )
    candidates.find(p => new java.io.File(p).isDirectory)
      .getOrElse(throw new RuntimeException(
        "Could not auto-detect job dir; pass it as the first argument. " +
          "Tried: " + candidates.mkString(", ")
      ))
  }
}
