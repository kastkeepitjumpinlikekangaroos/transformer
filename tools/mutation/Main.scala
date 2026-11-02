package com.transformer.tools.mutation

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** The mutation tool's command line. See docs/testing.md, "Mutation testing".
  *
  * {{{
  * mutate sites <file>...                       every site of every operator
  * mutate sample [--per-file N] [--seed S] [--ledger F] [--repo D] <file>...
  *                                              the mutants to run, as TSV
  * mutate run --repo D --output-base D --disk-cache D --out D --mutants F
  *            [--sharded REGEX] [--exclude-tags REGEX]
  *                                              run them in a worktree
  * mutate report <results.tsv>                  the tables, as Markdown
  * mutate apply --repo D --mutants F --id ID    write one mutant into its file
  * }}}
  *
  * Files are named relative to the repository's root (`--repo`, by default
  * the working directory).
  */
object Main {
  def main(args: Array[String]): Unit = args.toList match {
    case "sites" :: files if files.nonEmpty => sites(files)
    case "sample" :: rest => sample(rest)
    case "run" :: rest => run(rest)
    case "report" :: results :: Nil => print(Report.markdown(Sample.readTsv(Paths.get(results))))
    case "apply" :: rest => applyOne(rest)
    case _ =>
      System.err.println("usage: mutate sites|sample|run|report|apply ... (see docs/testing.md, \"Mutation testing\")")
      sys.exit(2)
  }

  /** `--name value` options, and what is left. */
  private def options(args: List[String]): (Map[String, String], List[String]) = args match {
    case name :: value :: rest if name.startsWith("--") =>
      val (more, left) = options(rest)
      (more + (name.drop(2) -> value), left)
    case other => (Map.empty, other)
  }

  private def read(repo: Path, file: String): String = Files.readString(repo.resolve(file), StandardCharsets.UTF_8)

  private def sites(files: List[String]): Unit = files.foreach { f =>
    val src = Files.readString(Paths.get(f), StandardCharsets.UTF_8)
    Mutator.sites(src).foreach(s => println(s"$f:${s.describe}\t${Ledger.lineText(src, s)}"))
  }

  private def sample(args: List[String]): Unit = {
    val (opts, files) = options(args)
    val repo = Paths.get(opts.getOrElse("repo", "."))
    val ledger = Ledger.read(repo.resolve(opts.getOrElse("ledger", "tools/mutation/equivalent.tsv")))
    val quota = opts.get("per-file").map(_.toInt)
    val seed = opts.get("seed").map(_.toLong).getOrElse(19L)
    val sources = files.map(f => f -> read(repo, f))
    Ledger.stale(ledger, sources).foreach(e =>
      System.err.println(s"stale ledger entry, it covers no site of ${e.file}: ${e.operator} '${e.original}' on '${e.lineText}'"))
    val mutants = Sample.mutants(sources, quota, seed, ledger)
    println(Sample.MutantHeader)
    mutants.foreach(m => println(Sample.mutantRow(m)))
  }

  private def run(args: List[String]): Unit = {
    val (opts, _) = options(args)
    def path(name: String) = Paths.get(opts.getOrElse(name, throw new IllegalArgumentException(s"--$name is required"))).toAbsolutePath
    val repo = path("repo")
    val mutants = Sample.readMutants(path("mutants"), read(repo, _))
    val sharded = opts.get("sharded").map(_.r)
    Campaign.run(repo, path("output-base"), path("disk-cache"), path("out"), mutants,
      f => sharded.exists(_.findFirstIn(f).isDefined), opts.getOrElse("exclude-tags", Campaign.ExcludedTags))
  }

  private def applyOne(args: List[String]): Unit = {
    val (opts, _) = options(args)
    val repo = Paths.get(opts.getOrElse("repo", "."))
    val id = opts.getOrElse("id", throw new IllegalArgumentException("--id is required"))
    val mutants = Sample.readMutants(Paths.get(opts.getOrElse("mutants", "mutants.tsv")), read(repo, _))
    val m = mutants.find(_.id == id).getOrElse(throw new IllegalArgumentException(s"no mutant $id"))
    val src = read(repo, m.file)
    val _ = Files.writeString(repo.resolve(m.file), m.site.applyTo(src), StandardCharsets.UTF_8)
    println(s"${m.file}:${m.site.describe}")
    println(s"- ${Ledger.lineText(src, m.site)}")
    println(s"+ ${Ledger.lineText(m.site.applyTo(src), m.site.copy(end = m.site.start + m.site.replacement.length))}")
  }
}
