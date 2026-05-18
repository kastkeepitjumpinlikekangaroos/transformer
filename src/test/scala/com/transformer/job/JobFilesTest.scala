package com.transformer.job

import org.junit.Assert._
import org.junit.Test

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

class JobFilesTest {

  private def tmpDir(prefix: String): Path = Files.createTempDirectory(prefix)

  private def readString(p: Path): String = Files.readString(p)

  // ---- View-name validation --------------------------------------------------

  @Test def validateViewName_acceptsIdentifiers(): Unit = {
    assertEquals(Right(()), JobFiles.validateViewName("orders"))
    assertEquals(Right(()), JobFiles.validateViewName("raw_orders"))
    assertEquals(Right(()), JobFiles.validateViewName("_private"))
    assertEquals(Right(()), JobFiles.validateViewName("X123"))
  }

  @Test def validateViewName_rejectsBadInput(): Unit = {
    assertTrue(JobFiles.validateViewName(null).isLeft)
    assertTrue(JobFiles.validateViewName("").isLeft)
    assertTrue(JobFiles.validateViewName("  ").isLeft)
    assertTrue(JobFiles.validateViewName("1starts_with_digit").isLeft)
    assertTrue(JobFiles.validateViewName("has space").isLeft)
    assertTrue(JobFiles.validateViewName("has-dash").isLeft)
    assertTrue(JobFiles.validateViewName("has.dot").isLeft)
  }

  @Test def ensureNotInUse_detectsCollisions(): Unit = {
    val jobDir = tmpDir("jf-collide-")
    Files.createDirectories(jobDir.resolve("inputs/foo"))
    Files.createDirectories(jobDir.resolve("tables/bar"))

    assertTrue(JobFiles.ensureNotInUse(jobDir, "foo").isLeft)
    assertTrue(JobFiles.ensureNotInUse(jobDir, "bar").isLeft)
    assertEquals(Right(()), JobFiles.ensureNotInUse(jobDir, "free"))
  }

  // ---- New project skeleton --------------------------------------------------

  @Test def createNewProject_refusesExistingJobDir(): Unit = {
    val jobDir = tmpDir("jf-new-proj-existing-")
    Files.createDirectories(jobDir.resolve("tables/already_here"))
    val ex = assertThrows(classOf[IllegalArgumentException], () =>
      JobFiles.createNewProject(jobDir))
    assertTrue(ex.getMessage, ex.getMessage.contains("refusing"))
  }

  @Test def createNewProject_buildsLoadableSkeleton(): Unit = {
    val projectDir = tmpDir("jf-new-proj-")
    // Use a brand-new sub-path so we exercise the "create the directory itself"
    // branch as well.
    val target = projectDir.resolve("nested/project")
    JobFiles.createNewProject(target)

    assertTrue(Files.isRegularFile(target.resolve("data/example.csv")))
    assertTrue(Files.isRegularFile(target.resolve("inputs/example/config.json")))
    assertTrue(Files.isRegularFile(target.resolve("tables/example_summary/main.sql")))

    // Loader picks it up cleanly.
    val outputDir = tmpDir("jf-new-proj-out-")
    val job = DirectoryJobLoader.load(target, outputDir = Some(outputDir.toString))
    assertEquals(1, job.inputs.length)
    assertEquals("example", job.inputs.head.viewName)
    assertEquals(1, job.sql.length)
    assertEquals(Some("example_summary"), job.sql.head.viewName)

    // And actually runs.
    val result = job.run()
    assertTrue(result.error.getOrElse("(none)"), result.succeeded)
    assertTrue(Files.isDirectory(outputDir.resolve("example_summary")))
  }

  // ---- Inputs ----------------------------------------------------------------

  @Test def addInput_writesMinimalConfig(): Unit = {
    val jobDir = tmpDir("jf-add-in-")
    val cfg = JobFiles.addInput(
      jobDir,
      viewName = "events",
      path = "data/events.csv",
      format = None,
      cache = true,
      options = Map.empty
    )
    assertEquals("config.json", cfg.getFileName.toString)
    // Default cache=true and absent format should not appear in the JSON.
    val body = readString(cfg)
    assertTrue(body, body.contains("\"path\""))
    assertFalse(body, body.contains("\"cache\""))
    assertFalse(body, body.contains("\"format\""))
    assertFalse(body, body.contains("\"options\""))
  }

  @Test def addInput_writesAllSpecifiedFields(): Unit = {
    val jobDir = tmpDir("jf-add-in-full-")
    val cfg = JobFiles.addInput(
      jobDir,
      viewName = "events",
      path = "data/events.csv",
      format = Some("csv"),
      cache = false,
      options = Map("header" -> "true", "delimiter" -> ";")
    )
    val body = readString(cfg)
    assertTrue(body, body.contains("\"format\": \"csv\""))
    assertTrue(body, body.contains("\"cache\": false"))
    assertTrue(body, body.contains("\"header\": \"true\""))
    assertTrue(body, body.contains("\"delimiter\": \";\""))
  }

  @Test def addInput_rejectsDuplicate(): Unit = {
    val jobDir = tmpDir("jf-add-in-dup-")
    JobFiles.addInput(jobDir, "events", "data/x.csv", None, cache = true, Map.empty)
    val ex = assertThrows(classOf[IllegalArgumentException], () =>
      JobFiles.addInput(jobDir, "events", "data/y.csv", None, cache = true, Map.empty))
    assertTrue(ex.getMessage, ex.getMessage.contains("already exists"))
  }

  @Test def addInput_rejectsInvalidName(): Unit = {
    val jobDir = tmpDir("jf-add-in-badname-")
    assertThrows(classOf[IllegalArgumentException], () =>
      JobFiles.addInput(jobDir, "1bad", "data/x.csv", None, cache = true, Map.empty))
  }

  @Test def addInput_rejectsEmptyPath(): Unit = {
    val jobDir = tmpDir("jf-add-in-emptypath-")
    assertThrows(classOf[IllegalArgumentException], () =>
      JobFiles.addInput(jobDir, "events", "", None, cache = true, Map.empty))
  }

  @Test def addInput_relativizesPathsUnderJobDir(): Unit = {
    val jobDir = tmpDir("jf-add-in-rel-")
    val absolute = jobDir.resolve("data/events.csv").toAbsolutePath.normalize().toString
    val cfg = JobFiles.addInput(jobDir, "events", absolute, None, cache = true, Map.empty)
    val body = readString(cfg)
    assertTrue(s"expected relative path in config, got: $body",
      body.contains("\"path\": \"data/events.csv\""))
  }

  @Test def addInput_keepsAbsolutePathsOutsideJobDir(): Unit = {
    val jobDir = tmpDir("jf-add-in-abs-")
    val outside = tmpDir("jf-add-in-other-").resolve("d.csv").toAbsolutePath.normalize().toString
    val cfg = JobFiles.addInput(jobDir, "events", outside, None, cache = true, Map.empty)
    val body = readString(cfg)
    assertTrue(body, body.contains(s""""path": "$outside""""))
  }

  @Test def addInput_keepsCloudPaths(): Unit = {
    val jobDir = tmpDir("jf-add-in-gs-")
    val cfg = JobFiles.addInput(jobDir, "events", "gs://bucket/foo.csv", None, cache = true, Map.empty)
    assertTrue(readString(cfg).contains("\"path\": \"gs://bucket/foo.csv\""))
  }

  @Test def updateInputConfig_overwritesExisting(): Unit = {
    val jobDir = tmpDir("jf-upd-in-")
    JobFiles.addInput(jobDir, "events", "data/x.csv", None, cache = true, Map.empty)
    val newCfg = JobFiles.updateInputConfig(
      jobDir, "events", "data/y.csv",
      format = Some("parquet"), cache = false, options = Map("k" -> "v")
    )
    val body = readString(newCfg)
    assertTrue(body, body.contains("\"path\": \"data/y.csv\""))
    assertTrue(body, body.contains("\"format\": \"parquet\""))
    assertTrue(body, body.contains("\"cache\": false"))
  }

  @Test def updateInputConfig_reusesExistingFileName(): Unit = {
    val jobDir = tmpDir("jf-upd-in-name-")
    Files.createDirectories(jobDir.resolve("inputs/events"))
    Files.writeString(jobDir.resolve("inputs/events/cfg.json"),
      """{"path":"data/x.csv"}""")
    val cfg = JobFiles.updateInputConfig(jobDir, "events", "data/y.csv",
      format = None, cache = true, options = Map.empty)
    assertEquals("cfg.json", cfg.getFileName.toString)
    assertFalse(Files.exists(jobDir.resolve("inputs/events/config.json")))
  }

  @Test def deleteInput_removesDirectoryRecursively(): Unit = {
    val jobDir = tmpDir("jf-del-in-")
    JobFiles.addInput(jobDir, "events", "data/x.csv", None, cache = true, Map.empty)
    Files.createDirectories(jobDir.resolve("inputs/events/nested/deeper"))
    Files.writeString(jobDir.resolve("inputs/events/nested/deeper/extra.txt"), "x")
    JobFiles.deleteInput(jobDir, "events")
    assertFalse(Files.exists(jobDir.resolve("inputs/events")))
  }

  @Test def deleteInput_silentOnMissingDirectory(): Unit = {
    val jobDir = tmpDir("jf-del-missing-in-")
    // Should not throw.
    JobFiles.deleteInput(jobDir, "no_such_input")
  }

  // ---- Tables ----------------------------------------------------------------

  @Test def addTable_writesMainSqlOnly_whenAllDefaults(): Unit = {
    val jobDir = tmpDir("jf-add-tbl-")
    val main = JobFiles.addTable(
      jobDir, "summary",
      sql = "SELECT * FROM events",
      format = None, partitionBy = None, maxPartitions = None
    )
    assertTrue(Files.isRegularFile(main))
    assertFalse(Files.exists(jobDir.resolve("tables/summary/output.json")))
    assertTrue(readString(main).endsWith("\n"))  // trailing newline added
  }

  @Test def addTable_explicitCsvIsTreatedAsDefault(): Unit = {
    // "csv" is the directory loader's default — writing it to disk would just
    // be noise; verify we omit it.
    val jobDir = tmpDir("jf-add-tbl-csv-")
    JobFiles.addTable(
      jobDir, "summary", "SELECT 1",
      format = Some("csv"), partitionBy = None, maxPartitions = None
    )
    assertFalse(Files.exists(jobDir.resolve("tables/summary/output.json")))
  }

  @Test def addTable_writesOutputJson_whenNonDefault(): Unit = {
    val jobDir = tmpDir("jf-add-tbl-out-")
    JobFiles.addTable(
      jobDir, "summary", "SELECT 1",
      format = Some("parquet"), partitionBy = Some("day={{today}}"), maxPartitions = Some(4)
    )
    val out = jobDir.resolve("tables/summary/output.json")
    assertTrue(Files.isRegularFile(out))
    val body = readString(out)
    assertTrue(body, body.contains("\"format\": \"parquet\""))
    assertTrue(body, body.contains("\"partitionBy\": \"day={{today}}\""))
    assertTrue(body, body.contains("\"maxPartitions\": 4"))
  }

  @Test def addTable_rejectsInvalidName(): Unit = {
    val jobDir = tmpDir("jf-add-tbl-bad-")
    assertThrows(classOf[IllegalArgumentException], () =>
      JobFiles.addTable(jobDir, "1bad", "SELECT 1", None, None, None))
  }

  @Test def addTable_rejectsNonPositiveMaxPartitions(): Unit = {
    val jobDir = tmpDir("jf-add-tbl-maxp-")
    assertThrows(classOf[IllegalArgumentException], () =>
      JobFiles.addTable(jobDir, "summary", "SELECT 1", None, None, Some(0)))
  }

  @Test def addTable_rejectsDuplicate(): Unit = {
    val jobDir = tmpDir("jf-add-tbl-dup-")
    JobFiles.addTable(jobDir, "summary", "SELECT 1", None, None, None)
    val ex = assertThrows(classOf[IllegalArgumentException], () =>
      JobFiles.addTable(jobDir, "summary", "SELECT 2", None, None, None))
    assertTrue(ex.getMessage, ex.getMessage.contains("already exists"))
  }

  @Test def writeMainSql_replacesSqlFile(): Unit = {
    val jobDir = tmpDir("jf-write-sql-")
    JobFiles.addTable(jobDir, "summary", "SELECT 1", None, None, None)
    JobFiles.writeMainSql(jobDir, "summary", "SELECT 2 AS x FROM events\n")
    val body = readString(jobDir.resolve("tables/summary/main.sql"))
    assertEquals("SELECT 2 AS x FROM events\n", body)
  }

  @Test def writeMainSql_rejectsMissingTable(): Unit = {
    val jobDir = tmpDir("jf-write-sql-missing-")
    assertThrows(classOf[IllegalArgumentException], () =>
      JobFiles.writeMainSql(jobDir, "summary", "SELECT 1"))
  }

  @Test def updateTableConfig_writesAndDeletesOutputJson(): Unit = {
    val jobDir = tmpDir("jf-upd-tbl-")
    JobFiles.addTable(jobDir, "summary", "SELECT 1", None, None, None)
    val out = jobDir.resolve("tables/summary/output.json")
    assertFalse(Files.exists(out))

    // Set a non-default → file is created.
    JobFiles.updateTableConfig(jobDir, "summary", format = Some("parquet"), partitionBy = None, maxPartitions = None)
    assertTrue(Files.isRegularFile(out))
    assertTrue(readString(out).contains("parquet"))

    // Revert to defaults → file is removed.
    JobFiles.updateTableConfig(jobDir, "summary", format = None, partitionBy = None, maxPartitions = None)
    assertFalse(Files.exists(out))
  }

  @Test def deleteTable_removesDirectory(): Unit = {
    val jobDir = tmpDir("jf-del-tbl-")
    JobFiles.addTable(jobDir, "summary", "SELECT 1", None, None, None)
    JobFiles.addValidation(jobDir, "summary", "not_empty", "SELECT 1 WHERE 1=0")
    JobFiles.deleteTable(jobDir, "summary")
    assertFalse(Files.exists(jobDir.resolve("tables/summary")))
  }

  // ---- Validations -----------------------------------------------------------

  @Test def addValidation_writesValidationFile(): Unit = {
    val jobDir = tmpDir("jf-add-val-")
    JobFiles.addTable(jobDir, "summary", "SELECT 1", None, None, None)
    val v = JobFiles.addValidation(jobDir, "summary", "not_negative",
      "SELECT * FROM summary WHERE n < 0")
    assertEquals("not_negative.sql", v.getFileName.toString)
    assertTrue(readString(v).startsWith("SELECT * FROM summary"))
  }

  @Test def addValidation_rejectsDuplicate(): Unit = {
    val jobDir = tmpDir("jf-add-val-dup-")
    JobFiles.addTable(jobDir, "summary", "SELECT 1", None, None, None)
    JobFiles.addValidation(jobDir, "summary", "check", "SELECT 1")
    assertThrows(classOf[IllegalArgumentException], () =>
      JobFiles.addValidation(jobDir, "summary", "check", "SELECT 2"))
  }

  @Test def addValidation_rejectsMissingTable(): Unit = {
    val jobDir = tmpDir("jf-add-val-missing-")
    assertThrows(classOf[IllegalArgumentException], () =>
      JobFiles.addValidation(jobDir, "ghost", "check", "SELECT 1"))
  }

  @Test def writeValidationSql_replacesExisting(): Unit = {
    val jobDir = tmpDir("jf-write-val-")
    JobFiles.addTable(jobDir, "summary", "SELECT 1", None, None, None)
    JobFiles.addValidation(jobDir, "summary", "check", "SELECT 1")
    JobFiles.writeValidationSql(jobDir, "summary", "check", "SELECT 2")
    val body = readString(jobDir.resolve("tables/summary/validations/check.sql"))
    assertEquals("SELECT 2\n", body)
  }

  @Test def deleteValidation_removesFileAndEmptyDir(): Unit = {
    val jobDir = tmpDir("jf-del-val-")
    JobFiles.addTable(jobDir, "summary", "SELECT 1", None, None, None)
    JobFiles.addValidation(jobDir, "summary", "check", "SELECT 1")
    JobFiles.deleteValidation(jobDir, "summary", "check")
    assertFalse(Files.exists(jobDir.resolve("tables/summary/validations/check.sql")))
    // The (now-empty) validations directory should be cleaned up too.
    assertFalse(Files.exists(jobDir.resolve("tables/summary/validations")))
  }

  @Test def deleteValidation_keepsValidationsDirIfStillUsed(): Unit = {
    val jobDir = tmpDir("jf-del-val-keep-")
    JobFiles.addTable(jobDir, "summary", "SELECT 1", None, None, None)
    JobFiles.addValidation(jobDir, "summary", "a", "SELECT 1")
    JobFiles.addValidation(jobDir, "summary", "b", "SELECT 1")
    JobFiles.deleteValidation(jobDir, "summary", "a")
    assertTrue(Files.exists(jobDir.resolve("tables/summary/validations")))
    assertTrue(Files.exists(jobDir.resolve("tables/summary/validations/b.sql")))
  }

  // ---- End-to-end round-trip -------------------------------------------------

  @Test def roundTrip_writeThenLoadProducesMatchingJob(): Unit = {
    val jobDir = tmpDir("jf-rt-")
    Files.createDirectories(jobDir.resolve("data"))
    Files.writeString(jobDir.resolve("data/events.csv"), "id\n1\n2\n")

    JobFiles.addInput(jobDir, "events", "data/events.csv",
      format = None, cache = true, options = Map.empty)
    JobFiles.addTable(jobDir, "summary",
      sql = "SELECT id FROM events",
      format = Some("parquet"),
      partitionBy = Some("day={{today}}"),
      maxPartitions = Some(2))
    JobFiles.addValidation(jobDir, "summary", "non_empty",
      "SELECT * FROM summary WHERE 1=0")

    val job = DirectoryJobLoader.load(jobDir, outputDir = Some(tmpDir("jf-rt-out-").toString))
    assertEquals(1, job.inputs.length)
    val in = job.inputs.head
    assertEquals("events", in.viewName)
    assertTrue(in.cache)

    assertEquals(1, job.sql.length)
    val tbl = job.sql.head
    assertEquals(Some("summary"), tbl.viewName)
    assertEquals(1, tbl.validations.size)
    assertEquals("non_empty", tbl.validations.head.name)
    val out = tbl.outputFile.get
    assertEquals(Some("parquet"), out.format)
    assertEquals(Some(2), out.maxPartitions)
    assertTrue(s"path should end with /summary/day={{today}}, got '${out.path}'",
      out.path.endsWith("/summary/day={{today}}"))
  }

  @Test def renderingProducesParseableJson(): Unit = {
    // Sanity check that what we write is itself valid JSON our parser accepts —
    // tripping on a stray backslash or quoting issue is easy in hand-rolled
    // renderers, so cover special chars explicitly.
    val jobDir = tmpDir("jf-json-")
    JobFiles.addInput(
      jobDir,
      viewName = "in_special",
      path = """data\sub/"quoted".csv""",
      format = None,
      cache = true,
      options = Map("delim\tkey" -> "val\nwith\\backslash")
    )
    val body = readString(jobDir.resolve("inputs/in_special/config.json"))
    // Roundtrip through the project's Json parser; it'll throw on any malformed input.
    val parsed = Json.parse(body).asObject("test").fields
    assertEquals("""data\sub/"quoted".csv""", parsed("path").stringValue)
  }
}
