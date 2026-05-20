package com.example.polymarket

import com.transformer.job.DirectoryJobLoader
import com.transformer.job.TaskStatus
import com.transformer.temporal.TemporalVariables

import java.nio.file.Paths
import java.time.Instant

/** A heavy-load directory-driven pipeline over the Polymarket tick-level
  * orderbook dataset (Kaggle: marvingozo/polymarket-tick-level-orderbook-dataset).
  *
  * The job reads:
  *   - one daily orderbook parquet (~130M rows, ~900MB compressed)
  *   - 21 daily L2 snapshot parquets (~17M rows, ~3.9GB compressed)
  *   - the pre-built 1-minute feature parquet (~5.6M rows)
  *   - the full trades parquet (~4.1M rows)
  *   - the market metadata parquet (~124K rows)
  *
  * and produces 17 dependent parquet outputs across staging / intermediate /
  * mart / final layers. Every table carries 3-5 DBT-style validations.
  *
  * TWO branches are EXPECTED to ValidationFail on real-feed data:
  *
  *   1. `mart_orderbook_quality_check` carries an INTENTIONAL validation
  *      `zzz_no_observable_snapshot_latency_INTENTIONAL_FAILURE` that
  *      asserts no market has snapshot latency above zero. Real data has
  *      nonzero latencies on virtually every market. Its downstream
  *      `mart_quality_report` selects FROM it and is therefore Skipped.
  *   2. `final_combined_report`'s `category_not_null` validation. The
  *      report groups by `category_normalized`, which originates in
  *      `int_markets_categorized` (joined into `mart_market_overview`
  *      via LEFT JOIN). Markets whose `condition_id` doesn't match a
  *      row in `int_markets_categorized` propagate a NULL category
  *      through the LEFT JOIN — the validation surfaces that data-
  *      quality fact rather than papering over it with COALESCE.
  *
  * The other three mart branches (overview, high-activity, volatility) all
  * succeed.
  *
  * Build + run:
  *
  *   bazel build //examples/polymarket:polymarket_deploy.jar
  *   java -Xmx12g -jar bazel-bin/examples/polymarket/polymarket_deploy.jar \
  *       [path-to-job-dir] [path-to-output-dir] [executionTime]
  */
object PolymarketExample {

  def main(args: Array[String]): Unit = {
    val jobDir = Paths.get(args.headOption.getOrElse(autoDetectJobDir()))
    val outputDir =
      args.lift(1).getOrElse(System.getProperty("java.io.tmpdir") + "/transformer-polymarket-out")
    val executionTime =
      args.lift(2).map(Instant.parse).getOrElse(Instant.parse("2026-03-26T00:00:00Z"))

    println(s"Loading polymarket job from: $jobDir")
    println(s"Writing to:                  $outputDir")
    println(s"Execution time:              $executionTime")

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

    val startedAt = System.currentTimeMillis()
    val result = job.run()
    val wallMs = System.currentTimeMillis() - startedAt

    println()
    println(s"Job finished in ${wallMs} ms")
    val succeeded = result.tasks.count(_.status == TaskStatus.Succeeded)
    val failed = result.tasks.count(_.status.isInstanceOf[TaskStatus.Failed])
    val validationFailed = result.tasks.count(_.status.isInstanceOf[TaskStatus.ValidationFailed])
    val skipped = result.tasks.count(_.status.isInstanceOf[TaskStatus.Skipped])
    println(s"Tasks: ${succeeded} Succeeded, ${validationFailed} ValidationFailed, " +
      s"${failed} Failed, ${skipped} Skipped (total ${result.tasks.size})")
    println()

    result.tasks.foreach { t =>
      println(f"  ${t.taskName}%-36s  rows=${t.rowsProduced}%9d  ${t.durationMillis}%6d ms  status=${t.status}")
    }

    // The two expected failures (see class comment) are part of the design —
    // we exit 0 iff the job's outcome matches exactly:
    //   - mart_orderbook_quality_check is ValidationFailed (INTENTIONAL)
    //   - final_combined_report is ValidationFailed (category_not_null
    //     surfaces NULLs from the LEFT JOIN in mart_market_overview)
    //   - mart_quality_report is Skipped (downstream of the intentional fail)
    //   - everything else Succeeded
    val expectedValidationFailed: Set[String] =
      Set("mart_orderbook_quality_check", "final_combined_report")
    val expectedSkipped: Set[String] = Set("mart_quality_report")
    val actualValidationFailed: Set[String] =
      result.tasks.filter(_.status.isInstanceOf[TaskStatus.ValidationFailed]).map(_.taskName).toSet
    val actualSkipped: Set[String] =
      result.tasks.filter(_.status.isInstanceOf[TaskStatus.Skipped]).map(_.taskName).toSet
    val actualFailed: Set[String] =
      result.tasks.filter(_.status.isInstanceOf[TaskStatus.Failed]).map(_.taskName).toSet
    val validationFailedMatches = actualValidationFailed == expectedValidationFailed
    val skippedMatches = actualSkipped == expectedSkipped
    val noUnexpectedHardFailures = actualFailed.isEmpty

    println()
    if (validationFailedMatches && skippedMatches && noUnexpectedHardFailures) {
      println("OK: expected ValidationFailed tasks " +
        expectedValidationFailed.toSeq.sorted.mkString("{", ", ", "}") +
        s" triggered skip of ${expectedSkipped.toSeq.sorted.mkString("{", ", ", "}")};")
      println("    all other branches Succeeded as expected.")
      System.exit(0)
    } else {
      System.err.println("UNEXPECTED outcome:")
      if (!validationFailedMatches) {
        val missing = expectedValidationFailed -- actualValidationFailed
        val extra   = actualValidationFailed -- expectedValidationFailed
        if (missing.nonEmpty) System.err.println(s"  missing ValidationFailed: $missing")
        if (extra.nonEmpty)   System.err.println(s"  unexpected ValidationFailed: $extra")
      }
      if (!skippedMatches) {
        val missing = expectedSkipped -- actualSkipped
        val extra   = actualSkipped -- expectedSkipped
        if (missing.nonEmpty) System.err.println(s"  missing Skipped: $missing")
        if (extra.nonEmpty)   System.err.println(s"  unexpected Skipped: $extra")
      }
      if (actualFailed.nonEmpty)
        System.err.println(s"  hard-Failed tasks: $actualFailed")
      result.error.foreach(e => System.err.println(s"Job error: $e"))
      System.exit(1)
    }
  }

  private def autoDetectJobDir(): String = {
    val candidates = Seq(
      "examples/polymarket/job",
      "job",
      "../examples/polymarket/job"
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
