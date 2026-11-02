package com.transformer.core

import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}

class SpillTest {

  // A base of the test's own for the stale sweep. This JVM's spill directory
  // is read once (`Spill.baseDir`, under java.io.tmpdir, which Bazel sets to
  // the target's own TEST_TMPDIR), so a test cannot move it; each leaves no
  // operator directory in it (plans/testing/17).
  private var tmpRoot: Path = _

  @Before def makeTmpRoot(): Unit = {
    tmpRoot = Files.createTempDirectory("spill-test-")
  }

  @After def leavesNoSpillDirectory(): Unit = {
    Spill.deleteRecursively(tmpRoot)
    assertEquals("spill directories left", Nil, Spill.operatorDirsLeft)
  }

  @Test def operatorDirCreatesAndCleansUp(): Unit = {
    val opDir = Spill.openOperatorDir("agg")
    assertTrue("subdir created on open", Files.isDirectory(opDir.dir))
    val f1 = opDir.newSpillFile(".parquet")
    val f2 = opDir.newSpillFile(".parquet")
    assertNotEquals(f1, f2)
    Files.write(f1, Array[Byte](1, 2, 3))
    Files.write(f2, Array[Byte](4, 5, 6))
    assertTrue(Files.exists(f1) && Files.exists(f2))
    opDir.close()
    assertFalse("dir wiped on close", Files.exists(opDir.dir))
  }

  /** The sweep deletes what a JVM that is no longer running left in the spill
    * base, and nothing else: not a running JVM's dir, not a dir of another
    * name in the base, and nothing beside the base. It deleted every entry
    * beside the base whose name began as the base's did up to its first `-`,
    * so a spill deleted `transformer-jaffle-out` in the temp directory. */
  @Test def theStaleSweepDeletesOnlyDeadJvmsDirsInTheBase(): Unit = {
    val base = tmpRoot.resolve("transformer-spill")
    Files.createDirectories(base)
    val exited = new ProcessBuilder("true").start()
    exited.waitFor()
    val deadPid = exited.pid()
    val livePid = ProcessHandle.current().pid()
    val dead = Files.createDirectories(base.resolve(s"jvm-$deadPid-abc123"))
    Files.write(dead.resolve("run-1.parquet"), Array[Byte](1))
    val deadOperator = Files.createDirectories(base.resolve(s"agg-$deadPid-7-beef"))
    val live = Files.createDirectories(base.resolve(s"jvm-$livePid-abc123"))
    val other = Files.createDirectories(base.resolve("notes"))
    val beside = Files.createDirectories(tmpRoot.resolve("transformer-jaffle-out"))
    Files.write(beside.resolve("part-00000.csv"), Array[Byte](1))
    Spill.cleanupStaleRoots(base, s"jvm-$livePid-current")
    assertFalse("a dead JVM's dir is swept", Files.exists(dead))
    assertFalse("a dead JVM's operator dir is swept", Files.exists(deadOperator))
    assertTrue("a running JVM's dir is kept", Files.isDirectory(live))
    assertTrue("a dir of another name is kept", Files.isDirectory(other))
    assertTrue("nothing beside the base is touched", Files.exists(beside.resolve("part-00000.csv")))
  }

  /** Each JVM spills under a dir of its own in the base, which its exit
    * deletes, so a JVM that exits leaves another's files alone. */
  @Test def eachJvmSpillsInADirOfItsOwn(): Unit = {
    val opDir = Spill.openOperatorDir("sort")
    assertEquals(Spill.rootDir, opDir.dir.getParent)
    assertEquals(Spill.baseDir, Spill.rootDir.getParent)
    assertTrue(Spill.rootDir.getFileName.toString.startsWith(s"jvm-${ProcessHandle.current().pid()}-"))
    opDir.close()
  }

  @Test def operatorDirCloseIsIdempotent(): Unit = {
    val opDir = Spill.openOperatorDir("sort")
    opDir.close()
    opDir.close() // no throw
  }

  @Test def newSpillFileAfterCloseThrows(): Unit = {
    val opDir = Spill.openOperatorDir("sort")
    opDir.close()
    try { opDir.newSpillFile(); fail("expected IllegalStateException") }
    catch { case _: IllegalStateException => () }
  }

  @Test def operatorTagSanitized(): Unit = {
    val opDir = Spill.openOperatorDir("agg/with bad chars!")
    val name = opDir.dir.getFileName.toString
    assertTrue(s"name should start with sanitized tag: $name",
      name.startsWith("agg_with_bad_chars_"))
    opDir.close()
  }

  /** A spill file names columns by position and stores each at its own type,
    * but a NULL-typed column, which parquet has no type for, as an all-null
    * BOOLEAN. */
  @Test def positionalSchemaStoresANullColumnAsBoolean(): Unit = {
    val schema = Schema(Field("k", DataType.IntType), Field("x", DataType.NullType),
      Field("d", DataType.DecimalType(9, 2)))
    assertEquals(
      Schema(Field("_c0", DataType.IntType), Field("_c1", DataType.BooleanType),
        Field("_c2", DataType.DecimalType(9, 2))),
      Spill.positionalSchema(schema))
  }

  @Test def estimateBytesScalesWithRows(): Unit = {
    val schema = Schema(Field("x", DataType.LongType), Field("s", DataType.StringType))
    val small = new ColumnarBatch(schema, 4)
    small.column(0).asInstanceOf[LongVector].set(0, 1L)
    small.column(1).asInstanceOf[StringVector].set(0, "ab")
    small.setNumRows(1)

    val big = new ColumnarBatch(schema, 1024)
    var r = 0
    while (r < 1024) {
      big.column(0).asInstanceOf[LongVector].set(r, r.toLong)
      big.column(1).asInstanceOf[StringVector].set(r, "x" * 16)
      r += 1
    }
    big.setNumRows(1024)

    val s = Spill.estimateBytes(small)
    val b = Spill.estimateBytes(big)
    assertTrue(s"big estimate ($b) should exceed small estimate ($s) by 100x+", b >= 100L * s)
  }

  @Test def estimateBytesNeverNegativeOnAllNulls(): Unit = {
    val schema = Schema(Field("s", DataType.StringType))
    val b = new ColumnarBatch(schema, 8)
    var r = 0
    while (r < 8) { b.column(0).setNull(r); r += 1 }
    b.setNumRows(8)
    assertTrue(Spill.estimateBytes(b) >= 0L)
  }

  @Test def effectiveThresholdHonorsOverride(): Unit = {
    val opts = ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(123456L))
    assertEquals(123456L, Spill.effectiveThresholdBytes(opts, 8))
  }

  @Test def effectiveThresholdComputesDefaultWhenAbsent(): Unit = {
    val opts = ExecutionOptions(spillEnabled = true, spillThresholdBytes = None)
    val v = Spill.effectiveThresholdBytes(opts, 8)
    assertTrue(s"default should be positive: $v", v > 0L)
    assertTrue(s"default should not exceed 1 GiB: $v", v <= (1L << 30))
  }
}
