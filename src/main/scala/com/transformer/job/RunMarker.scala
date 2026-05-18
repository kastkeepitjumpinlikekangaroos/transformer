package com.transformer.job

import java.nio.file.{FileSystems, Files, Path, Paths, StandardCopyOption}
import java.time.Instant
import scala.collection.mutable
import scala.util.control.NonFatal
import scala.jdk.CollectionConverters._

/** Per-task success marker written into a task's output directory after a
  * successful run. Follows the Spark/Hadoop `_SUCCESS` convention — the
  * underscore prefix means [[com.transformer.read.csv.PathGlob]] and friends
  * skip it when re-reading the directory as data.
  *
  * Contents:
  *   * `executionTime` — the [[com.transformer.temporal.TemporalVariables]]
  *     reference time the producing run used. Lets the GUI tell the user
  *     "this output was produced by execution-time T" even after the GUI is
  *     reopened against a different default time.
  *   * `writtenAt` — wall clock at marker-write time. Useful as a freshness
  *     hint distinct from `executionTime` (the user can run the same logical
  *     job at the same execution time on different days).
  *   * `rowsProduced` — for display.
  *   * `format` — `"csv"` / `"parquet"`. The GUI doesn't strictly need this
  *     (the path itself tells us) but it's cheap to record.
  *   * `outputFiles` — bare file names of the `part-*` files produced.
  *
  * The on-disk format is hand-rolled JSON (we already depend on a small
  * parser for [[Json]]; adding a writer for one stable schema keeps the
  * surface small).
  */
final case class RunMarker(
    executionTime: Instant,
    writtenAt: Instant,
    rowsProduced: Long,
    format: String,
    outputFiles: Seq[String]
)

object RunMarker {
  val FileName: String = "_SUCCESS"

  /** Write `marker` as `<dir>/_SUCCESS`. Uses an atomic temp-then-rename so a
    * partially-written marker can never be observed.
    */
  def write(dir: Path, marker: RunMarker): Unit = {
    if (!Files.isDirectory(dir)) Files.createDirectories(dir)
    val text = serialize(marker)
    val target = dir.resolve(FileName)
    val tmp = dir.resolve(FileName + ".tmp")
    Files.writeString(tmp, text)
    try {
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch {
      case _: java.nio.file.AtomicMoveNotSupportedException =>
        // Fall back to plain replace; the temp+swap still gives us "all or nothing"
        // on every filesystem we expect to encounter locally.
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }
  }

  /** Read `<dir>/_SUCCESS`. Returns None if the file doesn't exist or is
    * malformed — callers should treat both as "no cached run available". A
    * malformed marker shouldn't be loud: the GUI will just show the task as
    * pending rather than crash on a stale file.
    */
  def read(dir: Path): Option[RunMarker] = {
    val p = dir.resolve(FileName)
    if (!Files.isRegularFile(p)) return None
    try {
      val obj = Json.parse(Files.readString(p)).asObject(s"_SUCCESS at $p")
      val files = obj.get("outputFiles") match {
        case Some(JsonArray(items)) => items.iterator.map(_.stringValue).toVector
        case _                       => Vector.empty
      }
      Some(RunMarker(
        executionTime = Instant.parse(obj.requiredString("executionTime", s"_SUCCESS at $p")),
        writtenAt = Instant.parse(obj.requiredString("writtenAt", s"_SUCCESS at $p")),
        rowsProduced = obj.get("rowsProduced").map(_.stringValue.toLong).getOrElse(0L),
        format = obj.optString("format", s"_SUCCESS at $p").getOrElse("csv"),
        outputFiles = files
      ))
    } catch {
      case NonFatal(_) => None
    }
  }

  /** Glob-walk the filesystem to find every directory that matches
    * `templatedPattern` AND contains a `_SUCCESS` marker. Each `{{...}}`
    * fragment in the pattern is treated as a `*` wildcard (matching one path
    * segment), so a task whose output path is
    * `out/day={{today}}/spend_by_tier` discovers every historical
    * `out/day=YYYYMMDD/spend_by_tier` partition on disk.
    *
    * The walk starts at the longest static prefix of the pattern (everything
    * up to the directory before the first template variable), so a query
    * never scans more of the filesystem than the user asked for.
    *
    * Results are sorted by `writtenAt` descending — newest run first.
    */
  def discover(templatedPattern: String): Seq[(Path, RunMarker)] = {
    val glob = templatedPattern.replaceAll("\\{\\{[^}]*\\}\\}", "*")
    val (rootStr, _) = splitAtFirstWildcard(glob)
    val root = Paths.get(rootStr).toAbsolutePath.normalize()
    if (!Files.isDirectory(root)) return Nil

    // Resolve the glob to an absolute pattern so the matcher comparison uses
    // the same form on both sides.
    val absoluteGlob = Paths.get(glob).toAbsolutePath.normalize().toString
    val matcher = FileSystems.getDefault.getPathMatcher(s"glob:$absoluteGlob")

    val results = mutable.ArrayBuffer.empty[(Path, RunMarker)]
    // Cap walk depth at the number of segments after the root so we don't
    // descend further than the pattern allows. Add a small slack for safety.
    val maxDepth = math.max(1, segmentCount(absoluteGlob) - segmentCount(root.toString) + 1)
    val stream = Files.walk(root, maxDepth)
    try {
      stream.iterator().asScala.foreach { p =>
        if (Files.isDirectory(p) && matcher.matches(p)) {
          RunMarker.read(p).foreach(m => results += p -> m)
        }
      }
    } finally stream.close()

    results.sortBy(-_._2.writtenAt.toEpochMilli).toVector
  }

  /** Split `pattern` at the directory boundary immediately before the first
    * `*` (or, if none, return the whole pattern). The "before" portion is the
    * static prefix — safe to use as the walk root.
    */
  private def splitAtFirstWildcard(pattern: String): (String, String) = {
    val star = pattern.indexOf('*')
    if (star < 0) return (pattern, "")
    val before = pattern.substring(0, star)
    val lastSep = math.max(before.lastIndexOf('/'), before.lastIndexOf(java.io.File.separatorChar))
    if (lastSep < 0) (".", pattern)
    else (before.substring(0, lastSep), pattern.substring(lastSep + 1))
  }

  private def segmentCount(p: String): Int =
    p.split("[/\\\\]").count(_.nonEmpty)

  /** If `dir` contains a [[FileName]] marker, delete every regular file at the
    * top level of `dir` (including the marker itself) and return true.
    * Returns false when no marker is present — the directory is left untouched.
    *
    * Why: the marker signals "this directory holds the output of a prior
    * successful run." Without clearing, a rerun that changes output format
    * (CSV → Parquet) leaves stale `part-NNNNN.csv` files alongside the new
    * `part-NNNNN.parquet` files, which breaks any subsequent read of the dir
    * as a single dataset (the parquet reader trips on the leftover CSV).
    *
    * Subdirectories are deliberately left alone — our output layout is flat
    * (part files + marker), so any nested directory is not "ours" to wipe.
    * The marker is deleted first so an interrupted clear leaves a directory
    * that no longer claims success.
    */
  def clearIfMarked(dir: Path): Boolean = {
    if (!Files.isDirectory(dir)) return false
    val markerPath = dir.resolve(FileName)
    if (!Files.isRegularFile(markerPath)) return false
    Files.deleteIfExists(markerPath)
    val stream = Files.list(dir)
    try {
      val it = stream.iterator()
      while (it.hasNext) {
        val p = it.next()
        if (Files.isRegularFile(p)) Files.deleteIfExists(p)
      }
    } finally stream.close()
    true
  }

  /** Convenience: enumerate the bare `part-*` file names in `dir`. Used by the
    * runner to fill in [[RunMarker.outputFiles]] before writing the marker.
    */
  def listPartFiles(dir: Path): Seq[String] = {
    if (!Files.isDirectory(dir)) return Nil
    val stream = Files.list(dir)
    try {
      val names = stream.iterator()
      val out = mutable.ArrayBuffer.empty[String]
      while (names.hasNext) {
        val p = names.next()
        val n = p.getFileName.toString
        if (Files.isRegularFile(p) && n.startsWith("part-")) out += n
      }
      out.sortInPlace()
      out.toVector
    } finally stream.close()
  }

  private def serialize(m: RunMarker): String = {
    val sb = new java.lang.StringBuilder()
    sb.append("{\n")
    sb.append("  \"executionTime\": \"").append(escape(m.executionTime.toString)).append("\",\n")
    sb.append("  \"writtenAt\": \"").append(escape(m.writtenAt.toString)).append("\",\n")
    sb.append("  \"rowsProduced\": ").append(m.rowsProduced).append(",\n")
    sb.append("  \"format\": \"").append(escape(m.format)).append("\",\n")
    sb.append("  \"outputFiles\": [")
    var i = 0
    while (i < m.outputFiles.size) {
      if (i > 0) sb.append(", ")
      sb.append('"').append(escape(m.outputFiles(i))).append('"')
      i += 1
    }
    sb.append("]\n")
    sb.append("}\n")
    sb.toString
  }

  private def escape(s: String): String = {
    val sb = new java.lang.StringBuilder(s.length + 8)
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      c match {
        case '"'           => sb.append("\\\"")
        case '\\'          => sb.append("\\\\")
        case '\n'          => sb.append("\\n")
        case '\r'          => sb.append("\\r")
        case '\t'          => sb.append("\\t")
        case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
        case c             => sb.append(c)
      }
      i += 1
    }
    sb.toString
  }
}
