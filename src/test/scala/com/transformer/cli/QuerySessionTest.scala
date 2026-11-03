package com.transformer.cli

import com.transformer.core._
import org.junit.Assert._
import org.junit.Test

import java.io.{ByteArrayOutputStream, IOException, OutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger

class QuerySessionTest {

  private val dir: Path = Files.createTempDirectory("query-session-")

  private def file(name: String, contents: String): Path = {
    val p = dir.resolve(name)
    Files.createDirectories(p.getParent)
    Files.writeString(p, contents)
  }

  /** A session printing CSV, with `t` (a, b: 1..3) and `u` (a, s). */
  private def session(): QuerySession = {
    val s = new QuerySession
    s.format = OutputFormat.Csv
    s.addTable("t", file("t.csv", "a,b\n1,10\n2,20\n3,30\n").toString, None, Map.empty)
    s.addTable("u", file("u.csv", "a,s\n1,one\n3,three\n").toString, None, Map.empty)
    s
  }

  private final class Ran(val ok: Boolean, val out: String, val err: String)

  private def run(s: QuerySession, script: String, into: Option[String] = None, cancel: Cancel = new Cancel): Ran = {
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val ok = s.runScript(script, new Io(new PrintStream(out, true, "UTF-8"), new PrintStream(err, true, "UTF-8"), cancel), into)
    new Ran(ok, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))
  }

  private def lines(s: String*): String = s.mkString("", "\n", "\n")

  private def ok(s: QuerySession, script: String): String = {
    val r = run(s, script)
    assertTrue(s"failed: ${r.err}", r.ok)
    r.out
  }

  private def error(s: QuerySession, script: String): String = {
    val r = run(s, script)
    assertFalse(s"ran: ${r.out}", r.ok)
    r.err
  }

  // ---- views --------------------------------------------------------------------

  @Test def aViewIsDefinedAheadOfEachStatementThatReadsIt(): Unit = {
    val s = session()
    assertEquals(lines("n", "2", "a", "2", "3"), ok(s, """
      CREATE VIEW big AS SELECT a, b FROM t WHERE b > 15;
      CREATE VIEW total (n) AS SELECT COUNT(*) FROM big;
      SELECT * FROM total;
      SELECT a FROM big ORDER BY a"""))
    // In a statement's own WITH, in a join, and under EXPLAIN.
    assertEquals(lines("n", "2"), ok(s, "WITH q AS (SELECT a FROM big) SELECT COUNT(*) AS n FROM q"))
    assertEquals(lines("s", "three"), ok(s, "SELECT u.s FROM big JOIN u ON big.a = u.a"))
    s.format = OutputFormat.Table
    assertTrue(ok(s, "EXPLAIN SELECT * FROM total").contains("Scan t"))
  }

  @Test def aViewWithItsOwnWithAndAColumnListBindsAsWritten(): Unit = {
    val s = session()
    ok(s, "CREATE VIEW v (x, y) AS WITH q AS (SELECT a, b FROM t) SELECT a * 2, b FROM q -- doubled")
    assertEquals(lines("x,y", "6,30"), ok(s, "SELECT x, y FROM v WHERE y = 30"))
  }

  @Test def aViewReadsTheFilesAsTheyAreNow(): Unit = {
    val s = session()
    ok(s, "CREATE VIEW v AS SELECT SUM(a) AS total FROM t")
    assertEquals(lines("total", "6"), ok(s, "SELECT total FROM v"))
    ok(s, s".table t ${file("t2.csv", "a\n100\n200\n")}")
    assertEquals(lines("total", "300"), ok(s, "SELECT total FROM v"))
  }

  @Test def createViewRefusesWhatWouldNotRead(): Unit = {
    val s = session()
    ok(s, "CREATE VIEW v AS SELECT a FROM t")
    assertTrue(error(s, "CREATE VIEW v AS SELECT b FROM t").contains("View v already exists; CREATE OR REPLACE VIEW replaces it"))
    ok(s, "CREATE OR REPLACE VIEW v AS SELECT b FROM t")
    assertEquals(lines("b", "10", "20", "30"), ok(s, "SELECT * FROM v"))
    assertTrue(error(s, "CREATE VIEW t AS SELECT 1 AS one").contains("The name t is taken by the table t"))
    assertTrue(error(s, "CREATE OR REPLACE VIEW v AS SELECT * FROM v").contains("View v would read itself"))
    ok(s, "CREATE VIEW w AS SELECT b FROM v")
    assertTrue(error(s, "CREATE OR REPLACE VIEW v AS SELECT b FROM w").contains("View v would read itself"))
    assertTrue(error(s, "CREATE VIEW twice AS SELECT a, a FROM t").contains("returns more than one column named 'a'"))
    assertTrue(error(s, "CREATE VIEW bad AS SELECT nope FROM t").contains("Unknown column 'nope'"))
    assertTrue(error(s, "CREATE VIEW none AS SELECT a FROM missing").contains("missing"))
    // A refused view is not created.
    assertTrue(error(s, "SELECT * FROM bad").contains("bad"))
  }

  @Test def dropViewDropsOnlyAView(): Unit = {
    val s = session()
    ok(s, "CREATE VIEW v AS SELECT a FROM t; DROP VIEW v")
    assertTrue(error(s, "SELECT * FROM v").contains("'v'"))
    ok(s, "DROP VIEW IF EXISTS v")
    assertTrue(error(s, "DROP VIEW v").contains("There is no view v"))
    assertTrue(error(s, "DROP VIEW t").contains("t is the table t"))
  }

  // ---- COPY -----------------------------------------------------------------------

  @Test def copyWritesOneFileInTheFormatItsNameSays(): Unit = {
    val s = session()
    val csv = dir.resolve("out/pipes.csv")
    val r = run(s, s"COPY (SELECT a, b FROM t WHERE a > 1 ORDER BY a) TO '$csv' (delimiter '|', HEADER false)")
    assertTrue(r.err, r.ok)
    assertEquals(lines(s"Wrote 2 rows to $csv"), r.err)
    assertEquals("2|20\n3|30\n", Files.readString(csv))

    val parquet = dir.resolve("out/t.parquet")
    ok(s, s"CREATE VIEW v AS SELECT a, b * 2 AS b2 FROM t; COPY v TO '$parquet' (compression gzip)")
    ok(s, s".table back $parquet")
    assertEquals(lines("a,b2", "3,60"), ok(s, "SELECT * FROM back WHERE a = 3"))

    // FORMAT names the format whatever the name says.
    val txt = dir.resolve("out/t.txt")
    ok(s, s"COPY t TO '$txt' (FORMAT csv)")
    assertTrue(Files.readString(txt).startsWith("a,b\n"))
  }

  @Test def copyRefusesWhatItCannotWrite(): Unit = {
    val s = session()
    assertTrue(error(s, s"COPY t TO '${dir.resolve("x.txt")}'").contains("Cannot tell the format of"))
    assertTrue(error(s, s"COPY t TO '${dir.resolve("x.csv")}' (delimitr ';')")
      .contains("COPY TO a csv file takes FORMAT and header, delimiter, quote, escape, nullValue, charset, lineEnding, not delimitr"))
    assertTrue(error(s, s"COPY t TO '${dir.resolve("x.parquet")}' (parquet_write_parallelism 2)")
      .contains("takes parquet_write_parallelism only with PER_PARTITION_OUTPUT true"))
    assertTrue(error(s, s"COPY t TO '${dir.resolve("x.parquet")}' (rowgroups 2)")
      .contains("takes FORMAT and compression, parquet_row_group_size, PER_PARTITION_OUTPUT, not rowgroups"))
    assertTrue(error(s, s"COPY t TO '${dir.resolve("x.csv")}' (header false, HEADER true)").contains("names the option header twice"))
    assertTrue(error(s, s"COPY t TO '${dir.resolve("x.csv")}' (FORMAT xml)").contains("FORMAT csv or FORMAT parquet, not xml"))
    assertTrue(error(s, s"COPY t TO '${dir.resolve("x.csv")}' (header maybe)").contains("must be true or false"))
    assertTrue(error(s, s"COPY t TO '$dir'").contains("is a directory"))
    assertTrue(error(s, s"COPY (SELECT a, a FROM t) TO '${dir.resolve("x.csv")}'").contains("more than one column named 'a'"))
    assertFalse(Files.exists(dir.resolve("x.csv")))
  }

  // ---- dot commands ---------------------------------------------------------------

  @Test def tablesAndSchemaListWhatSqlCanRead(): Unit = {
    val s = session()
    ok(s, "CREATE VIEW v AS SELECT a,\n  s FROM u")
    assertEquals(lines("name,kind,source", s"t,table,${dir.resolve("t.csv")}", s"u,table,${dir.resolve("u.csv")}",
      "v,view,\"SELECT a, s FROM u\""), ok(s, ".tables"))
    assertEquals(lines("column_name,column_type", "a,INT", "s,STRING"), ok(s, ".schema v"))
    assertEquals(lines("name,column_name,column_type", "t,a,INT", "t,b,INT", "u,a,INT", "u,s,STRING", "v,a,INT",
      "v,s,STRING"), ok(s, ".schema"))
    assertTrue(error(s, ".schema nope").contains("No table or view is named nope; .tables lists them"))
    assertTrue(error(s, ".schema a b").contains("Usage: .schema [name]"))
  }

  @Test def dotTableNamesAFileWithItsOptions(): Unit = {
    val s = session()
    ok(s, s".table p '${file("pipes data.txt", "x|y\n1|a\n")}' delimiter=| header=true")
    assertEquals(lines("x,y", "1,a"), ok(s, "SELECT * FROM p"))
    ok(s, s"COPY t TO '${dir.resolve("parts/one.parquet")}'; COPY t TO '${dir.resolve("parts/two.parquet")}'")
    // A directory of parquet files reads as parquet; format= says so of any path.
    ok(s, s".table parts ${dir.resolve("parts")}")
    assertEquals(lines("n", "6"), ok(s, "SELECT COUNT(*) AS n FROM parts"))
    ok(s, s".table named ${dir.resolve("parts/one.parquet")} format=parquet")
    assertTrue(error(s, s".table q ${dir.resolve("t.csv")} delimter=;").contains("Unknown option 'delimter' for a csv input"))
    assertTrue(error(s, s".table q ${dir.resolve("t.csv")} nokey").contains("An option of .table is key=value, not 'nokey'"))
    assertTrue(error(s, s".table 9q ${dir.resolve("t.csv")}").contains("A table's name is letters, digits and underscores"))
    assertTrue(error(s, s".table q ${dir.resolve("absent.csv")}").contains("No file is at"))
    ok(s, "CREATE VIEW v AS SELECT 1 AS one")
    assertTrue(error(s, s".table v ${dir.resolve("t.csv")}").contains("The name v is taken by the view v"))
    assertTrue(error(s, ".table only").contains("Usage: .table name path [key=value ...]"))
  }

  @Test def modeMaxRowsAndTimerSetHowResultsPrint(): Unit = {
    val s = session()
    assertEquals(lines("csv"), ok(s, ".mode"))
    assertEquals(lines("[{\"a\":1}]"), ok(s, ".mode json\nSELECT a FROM t WHERE a = 1"))
    assertTrue(error(s, ".mode xml").contains(".mode takes table, line, csv, tsv, json, jsonl or markdown, not xml"))
    s.format = OutputFormat.Table
    assertTrue(ok(s, ".maxrows 2\nSELECT a FROM t").contains("3 rows (2 shown)"))
    assertEquals(lines("2"), ok(s, ".maxrows"))
    assertTrue(error(s, ".maxrows -1").contains("a whole number, 0 for every row"))
    val timed = run(s, ".timer on\nSELECT 1 AS one")
    assertTrue(timed.err, timed.err.matches("(?s).*Run time: \\d+\\.\\d{3} s\n"))
    assertTrue(error(s, ".timer maybe").contains("Usage: .timer [on|off]"))
    // Spill reaches the options each statement runs with.
    assertEquals(lines("off"), ok(s, ".spill"))
    assertFalse(s.optionsFor(new Cancel).spillEnabled)
    ok(s, ".spill on")
    assertTrue(s.optionsFor(new Cancel).spillEnabled)
    assertTrue(ok(s, "SELECT a FROM t ORDER BY b DESC").contains("3 rows"))
    assertTrue(error(s, ".spill sometimes").contains("Usage: .spill [on|off]"))
  }

  @Test def readRunsAScriptAndStopsWhereItFails(): Unit = {
    val s = session()
    file("inner.sql", "SELECT 1 AS one;\nSELECT nope FROM t;\nSELECT 3 AS three;\n")
    file("outer.sql", s".read ${dir.resolve("inner.sql")}\nSELECT 4 AS four;\n")
    val r = run(s, s".read ${dir.resolve("outer.sql")}\nSELECT 5 AS five")
    assertFalse(r.ok)
    assertEquals(lines("one", "1"), r.out)
    assertEquals(lines("Error in the statement on line 2: Unknown column 'nope'. Available: [t.a, t.b]"), r.err)
    file("self.sql", s".read ${dir.resolve("self.sql")}\n")
    assertTrue(error(s, s".read ${dir.resolve("self.sql")}").contains("at most 16 deep"))
    assertTrue(error(s, s".read ${dir.resolve("absent.sql")}").contains("No file is at"))
  }

  @Test def quitStopsTheScriptAndUnknownCommandsAreRefused(): Unit = {
    val s = session()
    assertEquals(lines("one", "1"), ok(s, "SELECT 1 AS one;\n.quit\nSELECT 2 AS two"))
    assertTrue(s.quit)
    assertTrue(error(new QuerySession, ".frobnicate").contains("Unknown command .frobnicate; .help lists the commands"))
    assertTrue(ok(new QuerySession, ".help").contains(".table name path [key=value ...]"))
  }

  // ---- how a script runs -------------------------------------------------------------

  @Test def theFirstStatementThatFailsStopsTheScriptAndSaysWhere(): Unit = {
    val r = run(session(), "SELECT 1 AS one;\nSELECT nope\n  FROM t;\nSELECT 3 AS three")
    assertFalse(r.ok)
    assertEquals(lines("one", "1"), r.out)
    assertEquals(lines("Error in the statement on line 2: Unknown column 'nope'. Available: [t.a, t.b]"), r.err)
    // One statement alone says no line.
    assertEquals(lines("Error: Unknown column 'nope'. Available: [t.a, t.b]"), run(session(), "SELECT nope FROM t").err)
  }

  @Test def tablesPrintedOneAfterAnotherAreSetApart(): Unit = {
    val s = session()
    s.format = OutputFormat.Table
    assertTrue(ok(s, "SELECT 1 AS one; SELECT 2 AS two").contains("1 row\n\n"))
  }

  @Test def intoWritesTheScriptsOneQuery(): Unit = {
    val s = session()
    val target = dir.resolve("into.csv")
    val r = run(s, "CREATE VIEW v AS SELECT a FROM t WHERE a < 3;\nSELECT * FROM v", into = Some(target.toString))
    assertTrue(r.err, r.ok)
    assertEquals("", r.out)
    assertEquals("a\n1\n2\n", Files.readString(target))
    val two = run(s, "SELECT 1 AS one; SELECT 2 AS two", into = Some(target.toString))
    assertFalse(two.ok)
    assertTrue(two.err.contains("--output writes the result of one query, and the script holds 2"))
  }

  @Test def aCancelledStatementPrintsNothingAndWritesNoFile(): Unit = {
    val s = session()
    val cancel = new Cancel
    cancel.cancel()
    val target = dir.resolve("cancelled.csv")
    val r = run(s, s"SELECT * FROM t; COPY t TO '$target'", cancel = cancel)
    assertFalse(r.ok)
    assertEquals("", r.out)
    assertEquals("", r.err)
    assertFalse(Files.exists(target))
  }

  /** A table `endless` of `parts` partitions of 4-row batches without end,
    * calling `served` with the count of batches served after each. */
  private def withEndless(s: QuerySession, parts: Int)(served: Int => Unit): Unit = {
    val count = new AtomicInteger
    val rowSchema = Schema(Vector(Field("x", DataType.IntType)))
    s.addTable("endless", new CatalogView {
      def schema: Schema = rowSchema
      def numPartitions: Int = parts
      def readPartition(p: Int): Iterator[ColumnarBatch] = Iterator.continually {
        val b = new ColumnarBatch(rowSchema, 4)
        (0 until 4).foreach(r => b.column(0).setBoxed(r, r))
        b.setNumRows(4)
        served(count.incrementAndGet())
        b
      }
    }, "a test")
  }

  @Test def cancellingStopsTheEnginesScansAtTheirNextBatch(): Unit = {
    val s = new QuerySession
    val cancel = new Cancel
    var served = 0
    // The fifth batch served cancels.
    withEndless(s, parts = 3) { n => served = math.max(served, n); if (n == 5) cancel.cancel() }
    val r = run(s, "SELECT x, COUNT(*) AS n FROM endless GROUP BY x", cancel = cancel)
    assertFalse(r.ok)
    assertEquals("", r.err)
    assertTrue(s"served $served batches", served <= 5 + 3)
  }

  /** `m` (x, label): the endless table's four values, a row each. */
  private def withSmall(s: QuerySession): Unit =
    s.addTable("m", file("m.csv", "x,label\n0,zero\n1,one\n2,two\n3,three\n").toString, None, Map.empty)

  /** A LIMIT over a join of an endless table returns once it has its rows: the
    * join probes as it is read, an output batch at a time, where it probed
    * the whole of its probe side before its first row, which never came.
    * Each probe batch of four rows joins four rows, so one output batch is a
    * quarter as many probe batches as it holds rows. */
  @Test def aLimitOverAJoinOfAnEndlessTableReturns(): Unit = {
    val s = new QuerySession
    s.format = OutputFormat.Csv
    val served = new AtomicInteger
    withEndless(s, parts = 3)(n => { val _ = served.accumulateAndGet(n, (a, b) => math.max(a, b)) })
    withSmall(s)
    val r = run(s, "SELECT e.x, m.label FROM endless e JOIN m ON e.x = m.x LIMIT 5")
    assertTrue(r.err, r.ok)
    assertEquals(r.out, 6, r.out.linesIterator.size)
    val oneOutputBatch = (ColumnarBatch.DefaultCapacity + 3) / 4
    assertTrue(s"served ${served.get} batches", served.get <= math.max(oneOutputBatch, 2) + 1)
  }

  /** Cancelling stops a join's probe, read on the pool under an aggregate,
    * at its next batch. */
  @Test def cancellingStopsAJoinsProbeAtItsNextBatch(): Unit = {
    val s = new QuerySession
    val cancel = new Cancel
    val served = new AtomicInteger
    withEndless(s, parts = 3) { n =>
      val _ = served.accumulateAndGet(n, (a, b) => math.max(a, b))
      if (n == 5) cancel.cancel()
    }
    withSmall(s)
    val r = run(s, "SELECT m.label, COUNT(*) AS n FROM endless e JOIN m ON e.x = m.x GROUP BY m.label", cancel = cancel)
    assertFalse(r.ok)
    assertEquals("", r.err)
    assertTrue(s"served ${served.get} batches", served.get <= 5 + 3)
  }

  /** A table `name` of `parts` partitions of 4-row batches, calling `served`
    * with the count of batches served, and `fail(p, k)` before partition
    * `p` serves its k-th batch (from 1). */
  private def withParts(s: QuerySession, name: String, parts: Int, served: AtomicInteger,
      fail: (Int, Int) => Unit = (_, _) => ()): Unit = {
    val rowSchema = Schema(Vector(Field("x", DataType.IntType)))
    s.addTable(name, new CatalogView {
      def schema: Schema = rowSchema
      def numPartitions: Int = parts
      def readPartition(p: Int): Iterator[ColumnarBatch] = {
        var k = 0
        Iterator.continually {
          k += 1
          fail(p, k)
          val b = new ColumnarBatch(rowSchema, 4)
          (0 until 4).foreach(r => b.column(0).setBoxed(r, p * 1000 + r))
          b.setNumRows(4)
          val _ = served.incrementAndGet()
          b
        }
      }
    }, "a test")
  }

  /** The batches served once they stop growing, sampled 200 ms apart:
    * nothing reads after the statement returned. */
  private def settledAfterReturn(served: AtomicInteger): Int = {
    val first = served.get
    Thread.sleep(200)
    assertEquals("a partition was read after the statement returned", first, served.get)
    first
  }

  /** Cancelling stops a table and CSV over four partitions read at once:
    * every partition stops at its next batch, and the statement says
    * nothing. */
  @Test(timeout = 60000) def cancellingStopsEveryPartitionOfATableAndOfCsv(): Unit =
    for (format <- Seq(OutputFormat.Table, OutputFormat.Csv)) {
      val s = new QuerySession
      s.format = format
      val cancel = new Cancel
      val served = new AtomicInteger
      withParts(s, "endless", 4, served, (_, _) => if (served.get >= 20) cancel.cancel())
      val r = run(s, "SELECT x FROM endless", cancel = cancel)
      assertFalse(r.ok)
      assertEquals("", r.err)
      val n = settledAfterReturn(served)
      // Each partition read at most a batch past the stop, CSV its chunks ahead too.
      assertTrue(s"$format: served $n batches", n <= 20 + 4 + 5 * ParallelDrain.ChunkBatches)
    }

  @Test(timeout = 30000) def aClosingOutputStopsCsvOverSeveralPartitions(): Unit = {
    val s = new QuerySession
    s.format = OutputFormat.Csv
    val served = new AtomicInteger
    withParts(s, "endless", 4, served)
    val closing = new OutputStream {
      private var written = 0
      def write(b: Int): Unit = write(Array(b.toByte), 0, 1)
      override def write(b: Array[Byte], off: Int, len: Int): Unit = {
        written += len
        if (written > 200) throw new IOException("Broken pipe")
      }
    }
    val err = new ByteArrayOutputStream()
    assertFalse(s.runScript("SELECT x FROM endless", new Io(new PrintStream(closing, true, "UTF-8"),
      new PrintStream(err, true, "UTF-8"))))
    assertEquals("", err.toString(StandardCharsets.UTF_8))
    val n = settledAfterReturn(served)
    assertTrue(s"served $n batches", n < 100)
  }

  /** Partition 0 throws at its third batch: the statement fails with its
    * message, the other partitions, without end, stop, and nothing reads
    * after the script returns. (The first, so that a pool of one thread,
    * reading a partition at a time, reaches it.) */
  @Test(timeout = 60000) def aFailingPartitionFailsTheStatementAndStopsTheRest(): Unit =
    for (format <- Seq(OutputFormat.Table, OutputFormat.Csv, OutputFormat.Markdown)) {
      val s = new QuerySession
      s.format = format
      val served = new AtomicInteger
      withParts(s, "endless", 4, served,
        (p, k) => if (p == 0 && k == 3) throw new IllegalStateException("partition 0 holds a bad value"))
      val r = run(s, "SELECT x FROM endless")
      assertFalse(r.ok)
      assertEquals(s"$format", lines("Error: partition 0 holds a bad value"), r.err)
      val _ = settledAfterReturn(served)
    }

  /** A table prints once its partitions are read, so after a statement
    * whose output closed it reads nothing: the script stops there. */
  @Test(timeout = 30000) def aTableAfterAnOutputClosedReadsNothing(): Unit = {
    val s = session()
    s.format = OutputFormat.Table
    val served = new AtomicInteger
    withParts(s, "endless", 4, served)
    val closing = new OutputStream {
      private var written = 0
      def write(b: Int): Unit = write(Array(b.toByte), 0, 1)
      override def write(b: Array[Byte], off: Int, len: Int): Unit = {
        written += len
        if (written > 20) throw new IOException("Broken pipe")
      }
    }
    val err = new ByteArrayOutputStream()
    assertFalse(s.runScript("SELECT * FROM t; SELECT x FROM endless", new Io(new PrintStream(closing, true, "UTF-8"),
      new PrintStream(err, true, "UTF-8"))))
    assertEquals("", err.toString(StandardCharsets.UTF_8))
    assertEquals(0, served.get)
  }

  /** A LIMIT is one partition, read on the statement's thread: it returns
    * after a batch or two, whatever the partitions without end. */
  @Test(timeout = 30000) def aLimitReadsABatchOrTwo(): Unit = {
    val s = new QuerySession
    s.format = OutputFormat.Csv
    val served = new AtomicInteger
    withParts(s, "endless", 4, served)
    val r = run(s, "SELECT x FROM endless LIMIT 5")
    assertTrue(r.err, r.ok)
    assertEquals(lines("x", "0", "1", "2", "3", "0"), r.out)
    assertTrue(s"served ${served.get} batches", served.get <= 3)
  }

  /** Three CSV files of 20,000 rows, a partition each of several batches. */
  private def threeFiles(): QuerySession = {
    val s = session()
    (0 until 3).foreach { f =>
      file(s"many/part$f.csv", (0 until 20000).map(i => s"${f * 20000 + i},v${f * 20000 + i}").mkString("a,s\n", "\n", "\n"))
    }
    ok(s, s".table many ${dir.resolve("many")}/*.csv")
    s
  }

  private val threeFilesCsv: String = (0 until 60000).map(i => s"$i,v$i").mkString("a,s\n", "\n", "\n")

  /** COPY and --output of several partitions write the text a write on one
    * thread writes, in partition order, and parquet that reads back in it. */
  @Test(timeout = 120000) def copyOfSeveralPartitionsWritesThemInOrder(): Unit = {
    val s = threeFiles()
    val csv = dir.resolve("many-out/copy.csv")
    ok(s, s"COPY many TO '$csv'")
    assertEquals(threeFilesCsv, Files.readString(csv))
    val into = dir.resolve("many-out/into.csv")
    val r = run(s, "SELECT * FROM many", into = Some(into.toString))
    assertTrue(r.err, r.ok)
    assertEquals(threeFilesCsv, Files.readString(into))
    val parquet = dir.resolve("many-out/copy.parquet")
    ok(s, s"COPY many TO '$parquet'")
    ok(s, s".table back $parquet")
    assertEquals(threeFilesCsv, ok(s, "SELECT * FROM back"))
  }

  // ---- COPY ... (FORMAT parquet, PER_PARTITION_OUTPUT true) -----------------------------

  /** A directory of a parquet file a partition, as a job writes a table:
    * an empty partition writes none, the files read back in partition
    * order, and no `_run.json`. */
  @Test(timeout = 120000) def copyWritesAPartitionAFileWithPerPartitionOutput(): Unit = {
    val s = threeFiles()
    file("many/part1b.csv", "a,s\n") // an empty partition, between parts 1 and 2
    ok(s, s".table many ${dir.resolve("many")}/*.csv")
    val out = dir.resolve("parts-out/day=1")
    val r = run(s, s"COPY many TO '$out' (FORMAT parquet, per_partition_output true, compression gzip, " +
      "parquet_write_parallelism 2)")
    assertTrue(r.err, r.ok)
    assertEquals(lines(s"Wrote 60000 rows to $out (3 files)"), r.err)
    val names = Files.list(out).toArray.map(_.asInstanceOf[Path].getFileName.toString).sorted.toSeq
    assertEquals(Seq("part-00000.parquet", "part-00001.parquet", "part-00003.parquet"), names)
    ok(s, s".table back $out")
    assertEquals(threeFilesCsv, ok(s, "SELECT * FROM back"))
    // An aggregate is one partition, and one file.
    val one = dir.resolve("parts-out/one")
    assertEquals(lines(s"Wrote 1 row to $one (1 file)"), run(s,
      s"COPY (SELECT COUNT(*) AS n FROM many) TO '$one' (FORMAT parquet, PER_PARTITION_OUTPUT true)").err)
  }

  @Test def perPartitionOutputRefusesWhatItCannotWrite(): Unit = {
    val s = session()
    val full = dir.resolve("full")
    file("full/x.txt", "taken")
    assertTrue(error(s, s"COPY t TO '$full' (FORMAT parquet, PER_PARTITION_OUTPUT true)")
      .contains("is a directory that holds files"))
    assertTrue(error(s, s"COPY t TO '${dir.resolve("full/x.txt")}' (FORMAT parquet, PER_PARTITION_OUTPUT true)")
      .contains("is a file; COPY TO with PER_PARTITION_OUTPUT true writes a directory"))
    assertTrue(error(s, s"COPY t TO '${dir.resolve("csvparts")}' (FORMAT csv, PER_PARTITION_OUTPUT true)")
      .contains("writes parquet files; a CSV result is written as one file"))
    assertTrue(error(s, s"COPY t TO '${dir.resolve("p")}' (FORMAT parquet, PER_PARTITION_OUTPUT maybe)")
      .contains("must be true or false"))
    assertTrue(error(s, s"COPY t TO '$dir'").contains(
      "is a directory; COPY TO writes one file, or a directory of parquet files with (FORMAT parquet, PER_PARTITION_OUTPUT true)"))
    assertFalse(Files.exists(dir.resolve("csvparts")))
  }

  /** A partition that fails stops the others and leaves no part file, nor
    * the directory the COPY made; an empty directory that was there stays,
    * empty. A cancelled COPY writes nothing either. */
  @Test(timeout = 60000) def aFailedOrStoppedPerPartitionCopyLeavesNothing(): Unit = {
    val s = new QuerySession
    val served = new AtomicInteger
    withParts(s, "failing", 4, served,
      (p, k) => if (p == 0 && k == 3) throw new IllegalStateException("partition 0 holds a bad value"))
    val made = dir.resolve("made/parts")
    val r = run(s, s"COPY failing TO '$made' (FORMAT parquet, PER_PARTITION_OUTPUT true)")
    assertFalse(r.ok)
    assertEquals(lines("Error: partition 0 holds a bad value"), r.err)
    assertFalse(Files.exists(dir.resolve("made")))
    val _ = settledAfterReturn(served)
    val empty = Files.createDirectories(dir.resolve("empty"))
    assertFalse(run(s, s"COPY failing TO '$empty' (FORMAT parquet, PER_PARTITION_OUTPUT true)").ok)
    assertTrue(Files.isDirectory(empty))
    assertEquals(0L, Files.list(empty).count())
    // Stopped: every partition without end, and the run cancelled part way.
    val cancel = new Cancel
    val stopped = new AtomicInteger
    withParts(s, "endless", 4, stopped, (_, _) => if (stopped.get >= 30) cancel.cancel())
    val target = dir.resolve("stopped")
    val c = run(s, s"COPY endless TO '$target' (FORMAT parquet, PER_PARTITION_OUTPUT true)", cancel = cancel)
    assertFalse(c.ok)
    assertEquals("", c.err)
    assertFalse(Files.exists(target))
  }

  @Test(timeout = 30000) def aStatementStopsWhenItsOutputIsClosed(): Unit = {
    val s = new QuerySession
    s.format = OutputFormat.Csv
    var served = 0
    withEndless(s, parts = 1)(n => served = n)
    // A reader that leaves after 200 bytes, as `head` does.
    val closing = new OutputStream {
      private var written = 0
      def write(b: Int): Unit = write(Array(b.toByte), 0, 1)
      override def write(b: Array[Byte], off: Int, len: Int): Unit = {
        written += len
        if (written > 200) throw new IOException("Broken pipe")
      }
    }
    val err = new ByteArrayOutputStream()
    assertFalse(s.runScript("SELECT x FROM endless", new Io(new PrintStream(closing, true, "UTF-8"),
      new PrintStream(err, true, "UTF-8"))))
    assertEquals("", err.toString(StandardCharsets.UTF_8))
    assertTrue(s"served $served batches", served < 100)
  }
}
