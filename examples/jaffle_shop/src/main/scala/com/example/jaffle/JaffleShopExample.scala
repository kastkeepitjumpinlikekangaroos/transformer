package com.example.jaffle

import com.transformer.job.DirectoryJobLoader
import com.transformer.temporal.TemporalVariables

import java.nio.file.Paths
import java.time.Instant

/** Port of the [dbt-labs/jaffle-shop](https://github.com/dbt-labs/jaffle-shop)
  * reference project to transformer's directory-driven job format.
  *
  * Layout under `examples/jaffle_shop/job/`:
  *
  * {{{
  *   job/
  *     data/                                  // CSV seeds (the same files DBT loads via seeds/)
  *       raw_customers.csv  raw_items.csv  raw_orders.csv
  *       raw_products.csv   raw_stores.csv raw_supplies.csv
  *     inputs/<view>/config.json              // one per seed
  *     tables/
  *       stg_customers/   stg_locations/   stg_order_items/
  *       stg_orders/      stg_products/    stg_supplies/
  *       order_supplies_summary/ order_items/ order_items_summary/
  *       orders/ customer_orders_summary/ customers/
  *       locations/ products/ supplies/
  *         main.sql
  *         validations/'*'.sql                // DBT data_tests ported as zero-row queries
  * }}}
  *
  * DBT models that use multiple CTEs are split into multiple SQLTasks here. The runner
  * derives the DAG from each task's SQL references, so independent staging tables run
  * in parallel and downstream marts wait for their inputs.
  *
  * Build + run:
  *
  *   bazel build //examples/jaffle_shop:jaffle_shop_deploy.jar
  *   java -jar bazel-bin/examples/jaffle_shop/jaffle_shop_deploy.jar \
  *       [path-to-job-dir] [path-to-output-dir] [executionTime]
  */
object JaffleShopExample {

  def main(args: Array[String]): Unit = {
    val jobDir = Paths.get(args.headOption.getOrElse(autoDetectJobDir()))
    val outputDir =
      args.lift(1).getOrElse(System.getProperty("java.io.tmpdir") + "/transformer-jaffle-out")
    val executionTime =
      args.lift(2).map(Instant.parse).getOrElse(Instant.parse("2026-01-01T00:00:00Z"))

    println(s"Loading jaffle shop job from: $jobDir")
    println(s"Writing to:                   $outputDir")
    println(s"Execution time:               $executionTime")

    val job = DirectoryJobLoader.load(
      jobDir = jobDir,
      outputDir = Some(outputDir),
      temporalVariables = Some(TemporalVariables(executionTime))
    )

    println(s"Loaded ${job.inputs.size} input(s), ${job.sql.size} table(s).")
    job.inputs.foreach(in => println(s"  input  '${in.viewName}'  <- ${in.path}"))
    job.sql.foreach { t =>
      val out = t.outputFile.map(_.path).getOrElse("(no output)")
      val v = if (t.validations.nonEmpty)
        s"  [${t.validations.size} validation${if (t.validations.size > 1) "s" else ""}]"
      else ""
      println(s"  table  '${t.viewName.getOrElse("?")}'  -> $out$v")
    }

    val result = job.run()
    println()
    println(s"Job ${if (result.succeeded) "SUCCEEDED" else "FAILED"}")
    result.tasks.foreach { t =>
      println(f"  ${t.taskName}%-26s  rows=${t.rowsProduced}%7d  ${t.durationMillis}%5d ms  status=${t.status}")
    }
    if (!result.succeeded) {
      result.error.foreach(e => System.err.println(s"Error: $e"))
      System.exit(1)
    }
  }

  private def autoDetectJobDir(): String = {
    val candidates = Seq(
      "examples/jaffle_shop/job",
      "job",
      "../examples/jaffle_shop/job"
    )
    candidates
      .find(p => new java.io.File(p).isDirectory)
      .getOrElse(
        throw new RuntimeException(
          "Could not auto-detect job dir; pass it as the first argument. " +
            "Tried: " + candidates.mkString(", ")
        )
      )
  }
}
