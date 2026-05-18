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
  * One branch is INTENTIONALLY designed to fail: `mart_orderbook_quality_check`
  * carries a validation `zzz_no_observable_snapshot_latency_INTENTIONAL_FAILURE`
  * that asserts no market has snapshot latency above zero. Real-feed data has
  * nonzero latencies on virtually every market, so the validation returns rows
  * and the runner marks the task `ValidationFailed`. The downstream task
  * `mart_quality_report` selects FROM that table and is therefore Skipped, while
  * the other three mart branches (overview, high-activity, volatility) continue
  * to completion and feed `final_combined_report`.
  *
  * Build + run:
  *
  *   bazel build //examples/polymarket:polymarket_deploy.jar
  *   java -Xmx12g -jar bazel-bin/examples/polymarket/polymarket_deploy.jar \
  *       [path-to-job-dir] [path-to-output-dir] [executionTime]
  */
object PolymarketExample {

  // Force class-load so ParquetSupport's object initializer installs the
  // resolver/reader/writer hooks before any task runs. Every input is parquet,
  // every table writes parquet (see each tables/<view>/output.json).
  private val _parquetSupport: AnyRef = com.transformer.read.parquet.ParquetSupport

  def main(args: Array[String]): Unit = {
    val _ = _parquetSupport
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

    // The intentional failure is part of the design — we exit 0 if the job's
    // outcome matches our expectation:
    //   - mart_orderbook_quality_check is ValidationFailed
    //   - mart_quality_report is Skipped
    //   - everything else Succeeded
    val expectedFailed = "mart_orderbook_quality_check"
    val expectedSkipped = "mart_quality_report"
    val failedAsExpected = result.tasks.exists(t =>
      t.taskName == expectedFailed && t.status.isInstanceOf[TaskStatus.ValidationFailed])
    val skippedAsExpected = result.tasks.exists(t =>
      t.taskName == expectedSkipped && t.status.isInstanceOf[TaskStatus.Skipped])
    val unexpectedFailures = result.tasks.exists { t =>
      t.taskName != expectedFailed && t.taskName != expectedSkipped &&
        (t.status.isInstanceOf[TaskStatus.Failed] ||
          t.status.isInstanceOf[TaskStatus.ValidationFailed] ||
          t.status.isInstanceOf[TaskStatus.Skipped])
    }

    println()
    if (failedAsExpected && skippedAsExpected && !unexpectedFailures) {
      println(s"OK: intentional failure on '$expectedFailed' triggered skip of '$expectedSkipped';")
      println("    all other branches Succeeded as expected.")
      System.exit(0)
    } else {
      System.err.println("UNEXPECTED outcome:")
      if (!failedAsExpected)
        System.err.println(s"  expected $expectedFailed to be ValidationFailed")
      if (!skippedAsExpected)
        System.err.println(s"  expected $expectedSkipped to be Skipped")
      if (unexpectedFailures)
        System.err.println("  unexpected failures/skips in other branches")
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
