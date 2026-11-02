package com.transformer.cli

import com.transformer.cli.Cli.{Command, JobArg, TableArg}
import org.junit.Assert._
import org.junit.Test

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Instant

class CliTest {

  private val fixedNow = Instant.parse("2026-03-04T05:06:07.890Z")

  private def parse(args: String*): Either[String, Command] = Cli.parse(args, fixedNow)

  private def writeFile(p: Path, contents: String): Unit = {
    Files.createDirectories(p.getParent)
    Files.writeString(p, contents)
  }

  /** A one-input, one-table job whose table carries `validation` when given. */
  private def jobDir(validation: Option[String] = None): Path = {
    val dir = Files.createTempDirectory("cli-job-")
    writeFile(dir.resolve("data/events.csv"), "id,value\n1,a\n2,b\n")
    writeFile(dir.resolve("inputs/events/config.json"), """{"path": "data/events.csv"}""")
    writeFile(dir.resolve("tables/passthrough/main.sql"), "SELECT id, value FROM events")
    validation.foreach(v => writeFile(dir.resolve("tables/passthrough/validations/check.sql"), v))
    dir
  }

  /** Run `cmd`, returning the exit code, stdout and stderr. */
  private def run(cmd: Command.Run): (Int, String, String) = {
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val code = Cli.run(cmd, new PrintStream(out, true, "UTF-8"), new PrintStream(err, true, "UTF-8"))
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))
  }

  @Test def noArgumentsAndHelpFlagsPrintHelp(): Unit = {
    assertEquals(Right(Command.Help), parse())
    assertEquals(Right(Command.Help), parse("help"))
    assertEquals(Right(Command.Help), parse("--help"))
    assertEquals(Right(Command.Help), parse("-h"))
    assertEquals(Right(Command.Help), parse("run", "job", "--help"))
    assertEquals(Right(Command.Help), parse("query", "-h"))
  }

  @Test def runDefaultsOutputToNoneAndTimeToNowInWholeSeconds(): Unit = {
    assertEquals(
      Right(Command.Run(Paths.get("job"), None, Instant.parse("2026-03-04T05:06:07Z"))),
      parse("run", "job")
    )
  }

  @Test def runTakesFlagsInAnyOrder(): Unit = {
    val expected = Right(Command.Run(Paths.get("job"), Some("/out/day={{ today }}"), Instant.parse("2026-01-01T00:00:00Z")))
    assertEquals(expected, parse("run", "job", "--output", "/out/day={{ today }}", "--execution-time", "2026-01-01T00:00:00Z"))
    assertEquals(expected, parse("run", "--execution-time", "2026-01-01T00:00:00Z", "--output", "/out/day={{ today }}", "job"))
  }

  @Test def runRejectsBadArguments(): Unit = {
    assertEquals(Left("run needs a job directory"), parse("run"))
    assertEquals(Left("--output needs a value"), parse("run", "job", "--output"))
    assertEquals(Left("--output given twice"), parse("run", "job", "--output", "a", "--output", "b"))
    assertEquals(Left("unknown option '--verbose'"), parse("run", "job", "--verbose"))
    assertEquals(Left("unexpected argument 'other'; run takes one job directory"), parse("run", "job", "other"))
    assertTrue(parse("run", "job", "--execution-time", "yesterday").left.exists(_.contains("not an ISO-8601 instant")))
  }

  @Test def guiTakesAnOptionalJobDirectory(): Unit = {
    assertEquals(Right(Command.Gui(None)), parse("gui"))
    assertEquals(Right(Command.Gui(Some(Paths.get("job")))), parse("gui", "job"))
    assertTrue(parse("gui", "a", "b").isLeft)
  }

  @Test def unknownCommandIsAUsageError(): Unit =
    assertEquals(Left("unknown command 'launch'"), parse("launch"))

  @Test def successfulRunExitsZeroAndWritesTemplatedOutput(): Unit = {
    val dir = jobDir()
    val out = Files.createTempDirectory("cli-out-")
    val (code, stdout, _) = run(Command.Run(dir, Some(s"$out/day={{ today }}"), Instant.parse("2026-01-02T00:00:00Z")))
    assertEquals(Cli.ExitOk, code)
    assertTrue(stdout, stdout.contains("Succeeded"))
    assertTrue(stdout, stdout.contains("Job SUCCEEDED (1 Succeeded)"))
    assertTrue(Files.isRegularFile(out.resolve("day=20260102/job.json")))
    assertTrue(Files.isRegularFile(out.resolve("day=20260102/passthrough/_run.json")))
  }

  @Test def failedValidationExitsOneAndNamesIt(): Unit = {
    val dir = jobDir(validation = Some("SELECT * FROM passthrough WHERE id = 1"))
    val out = Files.createTempDirectory("cli-out-")
    val (code, stdout, stderr) = run(Command.Run(dir, Some(out.toString), fixedNow))
    assertEquals(Cli.ExitRunFailed, code)
    assertTrue(stdout + stderr, stdout.contains("validation 'check' returned 1 row(s)"))
    assertTrue(stdout, stdout.contains("Job FAILED (1 ValidationFailed)"))
  }

  @Test def missingJobDirectoryExitsOne(): Unit = {
    val missing = Files.createTempDirectory("cli-missing-").resolve("nope")
    val (code, _, stderr) = run(Command.Run(missing, None, fixedNow))
    assertEquals(Cli.ExitRunFailed, code)
    assertTrue(stderr, stderr.contains("Job directory not found"))
  }

  @Test def unloadableJobExitsOneWithTheLoaderMessage(): Unit = {
    val dir = Files.createTempDirectory("cli-bad-")
    // An input directory with no .json config cannot be loaded.
    Files.createDirectories(dir.resolve("inputs/events"))
    val (code, _, stderr) = run(Command.Run(dir, None, fixedNow))
    assertEquals(Cli.ExitRunFailed, code)
    assertTrue(stderr, stderr.contains("Could not load job"))
  }

  // ---- query ----------------------------------------------------------------------

  /** Run `query` with `args`, standard input `stdin`, and no terminal. */
  private def query(args: String*)(stdin: String = ""): (Int, String, String) = parse("query" +: args: _*) match {
    case Right(cmd: Command.Query) =>
      val out = new ByteArrayOutputStream()
      val err = new ByteArrayOutputStream()
      val code = Cli.query(cmd, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
        new PrintStream(out, true, "UTF-8"), new PrintStream(err, true, "UTF-8"), terminal = false)
      (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))
    case other => throw new AssertionError(s"did not parse: $other")
  }

  private def csvFile(contents: String): Path = {
    val p = Files.createTempDirectory("cli-query-").resolve("data.csv")
    Files.writeString(p, contents)
  }

  @Test def queryTakesItsSqlTablesAndFlags(): Unit = {
    assertEquals(Right(Command.Query(sql = Some("SELECT 1"))), parse("query", "SELECT 1"))
    assertEquals(Right(Command.Query(
      sql = Some("SELECT * FROM a"),
      tables = Seq(TableArg("a", "x.txt", Some("csv"), Map("delimiter" -> "|", "header" -> "false")), TableArg("b", "y=z.parquet")),
      format = OutputFormat.Json,
      maxRows = 0,
      output = Some("out.parquet")
    )), parse("query", "--table", "a=x.txt", "SELECT * FROM a", "--format", "json", "--option", "a.delimiter=|",
      "--table", "b=y=z.parquet", "--option", "a.format=csv", "--option", "A.header=false", "--max-rows", "0",
      "--output", "out.parquet"))
    assertEquals(Right(Command.Query(file = Some(Paths.get("q.sql")),
      job = Some(JobArg(Paths.get("job"), Some("/out"), Instant.parse("2026-01-01T00:00:00Z"))))),
      parse("query", "-f", "q.sql", "--job", "job", "--job-output", "/out", "--execution-time", "2026-01-01T00:00:00Z"))
    // The job's execution time is now, in whole seconds, by default.
    assertEquals(Right(Command.Query(job = Some(JobArg(Paths.get("job"), None, Instant.parse("2026-03-04T05:06:07Z"))))),
      parse("query", "--job", "job"))
    // After --, SQL may start with a dash.
    assertEquals(Right(Command.Query(sql = Some("-- note\nSELECT 1"))), parse("query", "--", "-- note\nSELECT 1"))
  }

  @Test def queryRejectsBadArguments(): Unit = {
    assertEquals(Left("--table takes <name>=<path>, not 'x.csv'"), parse("query", "--table", "x.csv"))
    assertTrue(parse("query", "--table", "2x=x.csv").left.exists(_.contains("A table's name is letters, digits and underscores")))
    assertEquals(Left("--table A given twice"), parse("query", "--table", "a=x.csv", "--table", "A=y.csv"))
    assertEquals(Left("--option takes <table>.<key>=<value>, not 'a=b'"), parse("query", "--option", "a=b"))
    assertEquals(Left("--option names table 'b', which no --table names"),
      parse("query", "--table", "a=x.csv", "--option", "b.header=false"))
    assertEquals(Left("--option a.header given twice"),
      parse("query", "--table", "a=x.csv", "--option", "a.header=false", "--option", "a.header=true"))
    assertEquals(Left("--format takes table, line, csv, tsv, json, jsonl or markdown, not 'xml'"),
      parse("query", "--format", "xml"))
    assertEquals(Left("--max-rows takes a whole number, 0 for every row, not '-1'"), parse("query", "--max-rows", "-1"))
    assertEquals(Left("--job-output is for --job"), parse("query", "--job-output", "out"))
    assertEquals(Left("--execution-time is for --job"), parse("query", "--execution-time", "2026-01-01T00:00:00Z"))
    assertEquals(Left("give the SQL as an argument or with --file, not both"), parse("query", "SELECT 1", "-f", "q.sql"))
    assertEquals(Left("unexpected argument 'SELECT 2'; query takes one SQL argument, in quotes"),
      parse("query", "SELECT 1", "SELECT 2"))
    assertEquals(Left("--format given twice"), parse("query", "--format", "csv", "--format", "json"))
    assertEquals(Left("--file given twice"), parse("query", "-f", "a.sql", "--file", "b.sql"))
    assertEquals(Left("--output needs a value"), parse("query", "--output"))
    assertEquals(Left("unknown option '--verbose'"), parse("query", "--verbose"))
  }

  @Test def aQueryOverAFilePrintsATable(): Unit = {
    val data = csvFile("id,name\n1,ann\n2,bo\n")
    val (code, stdout, stderr) = query("--table", s"people=$data", "SELECT id, name FROM people ORDER BY id DESC")()
    assertEquals(stderr, Cli.ExitOk, code)
    val bar = new String(Character.toChars(0x2502))
    assertTrue(stdout, stdout.contains(s"$bar  id $bar name   $bar"))
    assertTrue(stdout, stdout.contains(s"$bar   2 $bar bo     $bar"))
    assertTrue(stdout, stdout.endsWith("2 rows\n"))
  }

  @Test def aQueryReadsItsStatementsFromAFileOrStandardInput(): Unit = {
    val data = csvFile("x|y\n1|a\n2|b\n")
    val script = data.resolveSibling("q.sql")
    Files.writeString(script, "CREATE VIEW v AS SELECT x FROM t WHERE y = 'b';\nSELECT * FROM v;\n")
    assertEquals((Cli.ExitOk, "x\n2\n", ""),
      query("--table", s"t=$data", "--option", "t.delimiter=|", "--format", "csv", "--file", script.toString)())
    assertEquals((Cli.ExitOk, "[{\"n\":2}]\n", ""),
      query("--table", s"t=$data", "--option", "t.delimiter=|", "--format", "json")("SELECT COUNT(*) AS n FROM t"))
    val (code, _, stderr) = query("--file", script.resolveSibling("absent.sql").toString)()
    assertEquals(Cli.ExitRunFailed, code)
    assertTrue(stderr, stderr.contains("No file is at"))
  }

  @Test def outputWritesTheResultToAFile(): Unit = {
    val data = csvFile("id,v\n1,1.5\n2,2.5\n")
    val target = data.resolveSibling("result.parquet")
    val (code, stdout, stderr) = query("--table", s"t=$data", "SELECT id, v * 2 AS v2 FROM t", "--output", target.toString)()
    assertEquals(stderr, Cli.ExitOk, code)
    assertEquals("", stdout)
    assertEquals(s"Wrote 2 rows to $target\n", stderr)
    assertEquals((Cli.ExitOk, "id,v2\n1,3.0\n2,5.0\n", ""),
      query("--table", s"r=$target", "--format", "csv", "SELECT * FROM r ORDER BY id")())
  }

  @Test def aJobsInputsAndTablesReadByName(): Unit = {
    val dir = jobDir()
    val out = Files.createTempDirectory("cli-out-")
    assertEquals(Cli.ExitOk, run(Command.Run(dir, Some(out.toString), fixedNow))._1)
    val (code, stdout, stderr) = query("--job", dir.toString, "--job-output", out.toString, "--format", "csv",
      "SELECT e.id, p.value FROM events e JOIN passthrough p ON e.id = p.id ORDER BY e.id")()
    assertEquals(stderr, Cli.ExitOk, code)
    assertEquals("id,value\n1,a\n2,b\n", stdout)
    // A table's output directory reads as its run wrote it, by --table too.
    assertEquals((Cli.ExitOk, "n\n2\n", ""),
      query("--table", s"p=${out.resolve("passthrough")}", "--format", "csv", "SELECT COUNT(*) AS n FROM p")())
    // A name the job has is not a table's to take.
    val (clash, _, clashErr) = query("--job", dir.toString, "--job-output", out.toString, "--table",
      s"events=${csvFile("a\n1\n")}", "SELECT 1 AS one")()
    assertEquals(Cli.ExitRunFailed, clash)
    assertTrue(clashErr, clashErr.contains("The name events is taken by the job input events"))
  }

  @Test def aJobThatHasNotRunOffersItsInputsWithAWarning(): Unit = {
    val (code, stdout, stderr) = query("--job", jobDir().toString, "--format", "csv", "SELECT COUNT(*) AS n FROM events")()
    assertEquals(stderr, Cli.ExitOk, code)
    assertEquals("n\n2\n", stdout)
    assertTrue(stderr, stderr.startsWith("warning: no run of the job is on disk under"))
  }

  @Test def aFailingStatementExitsOne(): Unit = {
    val data = csvFile("a\n1\n")
    val (code, stdout, stderr) = query("--table", s"t=$data", "--format", "csv", "SELECT a FROM t; SELECT nope FROM t")()
    assertEquals(Cli.ExitRunFailed, code)
    assertEquals("a\n1\n", stdout)
    assertTrue(stderr, stderr.startsWith("Error in the statement on line 1: Unknown column 'nope'"))
    val (missing, _, missingErr) = query("--table", s"t=${data.resolveSibling("absent.csv")}", "SELECT 1 AS one")()
    assertEquals(Cli.ExitRunFailed, missing)
    assertTrue(missingErr, missingErr.startsWith("Error: No file is at"))
    val (badOption, _, badOptionErr) = query("--table", s"t=$data", "--option", "t.delimter=;", "SELECT 1 AS one")()
    assertEquals(Cli.ExitRunFailed, badOption)
    assertTrue(badOptionErr, badOptionErr.contains("Unknown option 'delimter' for a csv input"))
  }
}
