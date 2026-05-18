package com.transformer.read.csv

import java.nio.file.{FileSystems, Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Expands a glob pattern like `data/'*'.csv` or `dir/'**'/'*'.csv` into a sorted list
  * of absolute file paths. Plain paths (no glob characters) resolve to themselves.
  */
object PathGlob {

  def expand(pathOrGlob: String): Seq[Path] = {
    if (!hasGlobChars(pathOrGlob)) {
      val p = Paths.get(pathOrGlob).toAbsolutePath.normalize()
      if (Files.isDirectory(p)) {
        // Bare directory → all visible regular files directly inside (single level).
        // Skip dotfiles (e.g. macOS '.DS_Store', Hadoop CRC sidecars '.part-...crc')
        // to match Spark/Hadoop conventions.
        val stream = Files.list(p)
        try stream.iterator().asScala
          .filter(Files.isRegularFile(_))
          .filterNot(isHidden)
          .toVector
          .sortBy(_.toString)
        finally stream.close()
      } else if (Files.exists(p)) Seq(p)
      else Seq.empty
    } else {
      val (base, pattern) = splitBase(pathOrGlob)
      val matcher = FileSystems.getDefault.getPathMatcher(s"glob:$pattern")
      val basePath = Paths.get(base).toAbsolutePath.normalize()
      if (!Files.exists(basePath)) return Seq.empty
      val results = mutable.ArrayBuffer.empty[Path]
      val stream = Files.walk(basePath)
      try {
        val it = stream.iterator()
        while (it.hasNext) {
          val p = it.next()
          if (Files.isRegularFile(p) && !isHidden(p)) {
            val rel = basePath.relativize(p)
            if (matcher.matches(rel)) results += p
          }
        }
      } finally stream.close()
      results.sortBy(_.toString).toVector
    }
  }

  private def isHidden(p: Path): Boolean = {
    val name = p.getFileName.toString
    name.startsWith(".") || name.startsWith("_")
  }

  private def hasGlobChars(s: String): Boolean =
    s.indexOf('*') >= 0 || s.indexOf('?') >= 0 || s.indexOf('[') >= 0 || s.indexOf('{') >= 0

  /** Splits the leading literal directory from the trailing glob portion. */
  private def splitBase(pat: String): (String, String) = {
    val segments = pat.split('/')
    val firstGlob = segments.indexWhere(hasGlobChars)
    if (firstGlob <= 0) (".", pat)
    else {
      val base = segments.take(firstGlob).mkString("/")
      val rest = segments.drop(firstGlob).mkString("/")
      (if (base.isEmpty) "/" else base, rest)
    }
  }
}
