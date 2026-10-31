package com.transformer.cli

import com.transformer.cli.Cli.Command
import org.junit.Assert._
import org.junit.Test

import java.io.{ByteArrayOutputStream, PrintStream}
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
}
