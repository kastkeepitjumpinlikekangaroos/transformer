package com.transformer.core

import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}

class SpillTest {

  private val originalDirProp: String = System.getProperty(Spill.SpillDirProperty)
  private var tmpRoot: Path = _

  @Before def setOverride(): Unit = {
    tmpRoot = Files.createTempDirectory("spill-test-")
    System.setProperty(Spill.SpillDirProperty, tmpRoot.resolve("transformer-spill").toString)
  }

  @After def restoreOverride(): Unit = {
    if (originalDirProp == null) System.clearProperty(Spill.SpillDirProperty)
    else System.setProperty(Spill.SpillDirProperty, originalDirProp)
    Spill.deleteRecursively(tmpRoot)
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
