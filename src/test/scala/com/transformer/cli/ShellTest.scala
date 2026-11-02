package com.transformer.cli

import com.transformer.core._
import org.jline.terminal.Terminal
import org.jline.terminal.impl.DumbTerminal
import org.junit.Assert._
import org.junit.Test

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream, PipedInputStream,
  PipedOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.{CountDownLatch, TimeUnit}

class ShellTest {

  private val dir = Files.createTempDirectory("shell-")

  /** A dumb terminal reading `in` and writing `out`, as a pipe is: no echo
    * and no line editing. */
  private def terminal(in: InputStream, out: OutputStream): Terminal =
    new DumbTerminal("test", Terminal.TYPE_DUMB, in, out, UTF_8)

  private def bar: String = new String(Character.toChars(0x2502))

  @Test def aStatementOverSeveralLinesIsOneInputAndOneEntryOfTheHistory(): Unit = {
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val history = dir.resolve("history")
    val input = "SELECT 1 AS a,\n  2 AS b;\n.mode csv\nSELECT 3 AS c; SELECT 'x\\ty' AS d, 2 != 1 AS e;\n.quit\n"
    val t = terminal(new ByteArrayInputStream(input.getBytes(UTF_8)), out)
    try Shell.run(new QuerySession, t, new PrintStream(err, true, "UTF-8"), Some(history)) finally t.close()
    val shown = out.toString(UTF_8)
    assertEquals("", err.toString(UTF_8))
    assertTrue(shown, shown.startsWith(Shell.Banner))
    assertTrue(shown, shown.contains(s"$bar   a $bar   b $bar"))
    // A backslash and != reach SQL as written.
    assertTrue(shown, shown.contains("c\n3\nd,e\nx\\ty,true\n"))
    val entries = Files.readAllLines(history).toArray.map(_.toString.dropWhile(_ != ':').drop(1)).toSeq
    assertEquals(Seq("SELECT 1 AS a,\\n  2 AS b;", ".mode csv", "SELECT 3 AS c; SELECT 'x\\\\ty' AS d, 2 != 1 AS e;", ".quit"),
      entries)
  }

  @Test def anErrorIsSaidAndTheShellReadsOn(): Unit = {
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val input = "SELECT nope;\n.mode jsonl\nSELECT 1 AS one;\n"
    val t = terminal(new ByteArrayInputStream(input.getBytes(UTF_8)), out)
    try Shell.run(new QuerySession, t, new PrintStream(err, true, "UTF-8"), None) finally t.close()
    val said = err.toString(UTF_8)
    assertTrue(said, said.startsWith("Error: ") && said.count(_ == '\n') == 1)
    assertTrue(out.toString(UTF_8).contains("{\"one\":1}\n"))
  }

  @Test def ctrlCStopsTheRunningStatementAndTheShellReadsOn(): Unit = {
    val session = new QuerySession
    val started = new CountDownLatch(1)
    val rows = Schema(Vector(Field("x", DataType.IntType)))
    session.addTable("endless", new CatalogView {
      def schema: Schema = rows
      def numPartitions: Int = 2
      def readPartition(p: Int): Iterator[ColumnarBatch] = Iterator.continually {
        started.countDown()
        val b = new ColumnarBatch(rows, 1)
        b.column(0).setBoxed(0, p)
        b.setNumRows(1)
        b
      }
    }, "a test")
    val toShell = new PipedOutputStream()
    val in = new PipedInputStream(toShell)
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val t = terminal(in, out)
    val shell = new Thread(() => Shell.run(session, t, new PrintStream(err, true, "UTF-8"), None))
    shell.start()
    toShell.write("SELECT x, COUNT(*) AS n FROM endless GROUP BY x;\n".getBytes(UTF_8))
    toShell.flush()
    assertTrue("the statement never read", started.await(30, TimeUnit.SECONDS))
    t.raise(Terminal.Signal.INT)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (!err.toString(UTF_8).contains("Cancelled") && System.nanoTime() < deadline) Thread.sleep(20)
    assertEquals("Cancelled\n", err.toString(UTF_8))
    toShell.write("SELECT 'next' AS x;\n.quit\n".getBytes(UTF_8))
    toShell.flush()
    shell.join(30000)
    t.close()
    assertFalse("the shell did not quit", shell.isAlive)
    assertTrue(out.toString(UTF_8), out.toString(UTF_8).contains(s"$bar next   $bar"))
  }
}
