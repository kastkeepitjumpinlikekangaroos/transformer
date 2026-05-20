package com.transformer.bench

import java.nio.file.{Files, Paths}
import scala.collection.mutable

/** Compare two macro-bench result JSONs and surface per-task wall-time deltas.
  *
  * Exit code:
  *   - **0** when every task in `fresh` is within `--threshold` (default 20%)
  *     of the same task in `baseline` (including tasks that are faster).
  *   - **1** when any task regressed by more than `--threshold`.
  *   - **2** when arguments are malformed.
  *
  * # CLI surface
  *
  * {{{
  *   bazel run //benchmarks/macro:bench_diff -- \
  *       benchmarks/baseline/jaffle_shop.json /tmp/jaffle_fresh.json
  *
  *   bazel run //benchmarks/macro:bench_diff -- \
  *       benchmarks/baseline/jaffle_shop.json /tmp/jaffle_fresh.json \
  *       --threshold 0.1
  * }}}
  *
  * # Output
  *
  * For every task in the union of both files:
  *
  *   - `baseline.json` median wall time
  *   - `fresh.json`    median wall time
  *   - delta in % (positive = slower in fresh)
  *   - marker `*` when delta exceeds the threshold
  *   - marker `?` when the task is present in only one side
  *
  * # Use cases
  *
  *   - PR check: diff against the checked-in baseline; non-zero exit fails
  *     the PR's perf job.
  *   - Local: diff two ad-hoc runs to confirm an optimization actually moved
  *     the number.
  *   - Self-diff: diff a file against itself to confirm BenchDiff reports
  *     zero regressions (this is the smoke-test the regression-guard test
  *     uses).
  */
object BenchDiff {

  def main(args: Array[String]): Unit = {
    val parsed = try parseArgs(args)
    catch {
      case e: IllegalArgumentException =>
        System.err.println(s"bench_diff: ${e.getMessage}")
        System.err.println("Usage: bench_diff <baseline.json> <fresh.json> [--threshold 0.2]")
        System.exit(2); return
    }
    val baseline = parseBenchJson(Files.readString(Paths.get(parsed.baselinePath)))
    val fresh = parseBenchJson(Files.readString(Paths.get(parsed.freshPath)))
    val (rows, regressed) = diff(baseline, fresh, parsed.threshold)
    println(formatTable(rows, parsed.threshold))
    if (regressed.nonEmpty) {
      System.err.println(
        s"\nFAIL: ${regressed.size} task(s) regressed by more than ${(parsed.threshold * 100).toInt}%:")
      regressed.foreach { r =>
        val bms = r.baselineNanos / 1.0e6
        val fms = r.freshNanos / 1.0e6
        System.err.println(f"  ${r.taskName}%-32s  ${r.deltaPct}%+6.2f%%  (baseline $bms%.2f ms -> fresh $fms%.2f ms)")
      }
      System.exit(1)
    } else {
      println(s"\nOK: no task regressed by more than ${(parsed.threshold * 100).toInt}%")
    }
  }

  /** Parsed CLI args. */
  private[bench] final case class Args(baselinePath: String, freshPath: String, threshold: Double)

  /** Per-task diff row used both internally and by the regression test. */
  final case class DiffRow(
      taskName: String,
      baselineNanos: Long,
      freshNanos: Long,
      deltaPct: Double,
      regressed: Boolean,
      onlyIn: Option[String])

  /** Internal-only view of a parsed bench JSON: just what BenchDiff needs. */
  private[bench] final case class BenchRow(taskName: String, wallNanosMedian: Long)

  private[bench] def parseArgs(args: Array[String]): Args = {
    if (args.length < 2) throw new IllegalArgumentException("two positional args required: <baseline> <fresh>")
    val positional = mutable.ArrayBuffer.empty[String]
    var threshold: Double = 0.2
    var i = 0
    while (i < args.length) {
      args(i) match {
        case "--threshold" =>
          i += 1
          if (i >= args.length) throw new IllegalArgumentException("--threshold requires a value")
          threshold = args(i).toDouble
          if (threshold <= 0.0) throw new IllegalArgumentException("--threshold must be > 0")
        case other =>
          positional += other
      }
      i += 1
    }
    if (positional.size != 2) throw new IllegalArgumentException("expected exactly two positional args")
    Args(positional(0), positional(1), threshold)
  }

  /** Compare `baseline` and `fresh`; return per-task diff rows plus the
    * subset that triggered a regression. */
  def diff(baseline: Vector[BenchRow], fresh: Vector[BenchRow], threshold: Double): (Vector[DiffRow], Vector[DiffRow]) = {
    val byBaseline = baseline.iterator.map(r => r.taskName -> r).toMap
    val byFresh = fresh.iterator.map(r => r.taskName -> r).toMap
    // Preserve baseline order, then append any fresh-only tasks.
    val seen = mutable.LinkedHashSet.empty[String]
    val ordered = mutable.ArrayBuffer.empty[String]
    baseline.foreach { r => if (seen.add(r.taskName)) ordered += r.taskName }
    fresh.foreach { r => if (seen.add(r.taskName)) ordered += r.taskName }
    val rows = ordered.iterator.map { name =>
      (byBaseline.get(name), byFresh.get(name)) match {
        case (Some(b), Some(f)) =>
          val pct = if (b.wallNanosMedian == 0L) 0.0
            else ((f.wallNanosMedian - b.wallNanosMedian).toDouble / b.wallNanosMedian.toDouble) * 100.0
          DiffRow(name, b.wallNanosMedian, f.wallNanosMedian, pct, regressed = pct > threshold * 100.0, onlyIn = None)
        case (Some(b), None) =>
          DiffRow(name, b.wallNanosMedian, 0L, deltaPct = Double.NaN, regressed = false, onlyIn = Some("baseline"))
        case (None, Some(f)) =>
          DiffRow(name, 0L, f.wallNanosMedian, deltaPct = Double.NaN, regressed = false, onlyIn = Some("fresh"))
        case (None, None) =>
          // Impossible (the name came from `ordered` which is the union).
          throw new IllegalStateException(s"task '$name' in ordered set but neither map")
      }
    }.toVector
    (rows, rows.filter(_.regressed))
  }

  /** Render the diff as a human-readable table. */
  private[bench] def formatTable(rows: Vector[DiffRow], threshold: Double): String = {
    val sb = new java.lang.StringBuilder
    sb.append(f"${"task"}%-32s  ${"baseline"}%10s  ${"fresh"}%10s  ${"delta"}%8s  marker\n")
    sb.append("-" * 78).append('\n')
    rows.foreach { r =>
      val bms = if (r.baselineNanos == 0L) "--" else f"${r.baselineNanos / 1.0e6}%.2f ms"
      val fms = if (r.freshNanos == 0L) "--" else f"${r.freshNanos / 1.0e6}%.2f ms"
      val deltaStr =
        if (r.onlyIn.isDefined) "--"
        else f"${r.deltaPct}%+6.2f%%"
      val marker = r.onlyIn match {
        case Some(side) => s"? (only in $side)"
        case None => if (r.regressed) s"* (> ${(threshold * 100).toInt}%)" else ""
      }
      sb.append(f"${r.taskName}%-32s  $bms%10s  $fms%10s  $deltaStr%8s  $marker\n")
    }
    sb.toString
  }

  /** Pull the `tasks` array out of a macro-bench result JSON, returning one
    * row per task. We hand-roll because the schema is small and re-using
    * `JsonMini` would force a `core/metrics`-internal API into the public
    * surface. */
  private[bench] def parseBenchJson(text: String): Vector[BenchRow] = {
    // Find "tasks": [ ... ] then walk objects looking for taskName + wallNanosMedian.
    val key = "\"tasks\""
    val keyPos = text.indexOf(key)
    if (keyPos < 0) throw new IllegalArgumentException("input missing 'tasks' field")
    var i = keyPos + key.length
    while (i < text.length && text.charAt(i) != '[') i += 1
    if (i >= text.length) return Vector.empty
    i += 1 // past '['
    val out = mutable.ArrayBuffer.empty[BenchRow]
    var depth = 0
    var inString = false
    var escape = false
    var objStart = -1
    while (i < text.length) {
      val c = text.charAt(i)
      if (inString) {
        if (escape) escape = false
        else if (c == '\\') escape = true
        else if (c == '"') inString = false
      } else {
        c match {
          case '"' => inString = true
          case '{' => if (depth == 0) objStart = i; depth += 1
          case '}' =>
            depth -= 1
            if (depth == 0 && objStart >= 0) {
              val obj = text.substring(objStart, i + 1)
              val name = extractStringField(obj, "taskName")
              val wall = extractLongField(obj, "wallNanosMedian")
              (name, wall) match {
                case (Some(n), Some(w)) => out += BenchRow(n, w)
                case _ => // skip malformed
              }
              objStart = -1
            }
          case ']' if depth == 0 => return out.toVector
          case _ => ()
        }
      }
      i += 1
    }
    out.toVector
  }

  /** Extract a string-valued JSON field from a single object literal. Naive
    * but works for the macro-bench shape (we control the writer; we control
    * the parser). */
  private[bench] def extractStringField(obj: String, name: String): Option[String] = {
    val key = "\"" + name + "\""
    val p = obj.indexOf(key)
    if (p < 0) return None
    var i = p + key.length
    while (i < obj.length && obj.charAt(i) != ':') i += 1
    i += 1
    while (i < obj.length && (obj.charAt(i) == ' ' || obj.charAt(i) == '\t')) i += 1
    if (i >= obj.length || obj.charAt(i) != '"') return None
    i += 1
    val sb = new java.lang.StringBuilder
    while (i < obj.length && obj.charAt(i) != '"') {
      val c = obj.charAt(i)
      if (c == '\\' && i + 1 < obj.length) {
        obj.charAt(i + 1) match {
          case '"'  => sb.append('"');  i += 2
          case '\\' => sb.append('\\'); i += 2
          case other => sb.append(c); sb.append(other); i += 2
        }
      } else {
        sb.append(c); i += 1
      }
    }
    Some(sb.toString)
  }

  /** Extract a long-valued field. Same caveats as [[extractStringField]]. */
  private[bench] def extractLongField(obj: String, name: String): Option[Long] = {
    val key = "\"" + name + "\""
    val p = obj.indexOf(key)
    if (p < 0) return None
    var i = p + key.length
    while (i < obj.length && obj.charAt(i) != ':') i += 1
    i += 1
    while (i < obj.length && (obj.charAt(i) == ' ' || obj.charAt(i) == '\t')) i += 1
    val start = i
    if (i < obj.length && obj.charAt(i) == '-') i += 1
    while (i < obj.length && obj.charAt(i).isDigit) i += 1
    if (i == start) return None
    try Some(obj.substring(start, i).toLong)
    catch { case _: NumberFormatException => None }
  }
}
