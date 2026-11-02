package com.transformer.tools.mutation

/** A campaign's results as Markdown: killed, timed out, survived and
  * stillborn per file and per operator, and each survivor with its change. */
object Report {
  def markdown(rows: Vector[Map[String, String]]): String = {
    val sb = new StringBuilder
    def table(title: String, key: Map[String, String] => String): Unit = {
      sb ++= s"| $title | mutants | killed | timed out | survived | stillborn | killed or timed out, of those that compiled |\n"
      sb ++= "|---|---:|---:|---:|---:|---:|---:|\n"
      val groups = rows.groupBy(key).toVector.sortBy(_._1)
      (groups :+ ("all" -> rows)).foreach { case (name, rs) =>
        def count(o: String) = rs.count(_("outcome") == o)
        val compiled = rs.size - count("stillborn")
        val caught = count("killed") + count("timeout")
        val rate = if (compiled == 0) "-" else s"${(caught * 100 + compiled / 2) / compiled}%"
        sb ++= s"| ${if (name == "all") "**all**" else s"`$name`"} | ${rs.size} | ${count("killed")} | ${count("timeout")} | " +
          s"${count("survived")} | ${count("stillborn")} | $rate |\n"
      }
      sb ++= "\n"
    }
    table("file", _("file").stripPrefix("src/main/scala/com/transformer/"))
    table("operator", _("operator"))
    val survivors = rows.filter(_("outcome") == "survived")
    if (survivors.nonEmpty) {
      sb ++= "| survivor | where | operator | change |\n|---|---|---|---|\n"
      survivors.foreach { r =>
        sb ++= s"| ${r("id")} | `${r("file").stripPrefix("src/main/scala/com/transformer/")}:${r("line")}` | ${r("operator")} | " +
          s"`${cell(r("original"))}` to `${cell(r("mutated"))}` |\n"
      }
    }
    sb.result()
  }

  private def cell(s: String): String = if (s.isEmpty) "(nothing)" else s.replace("|", "\\|")
}
