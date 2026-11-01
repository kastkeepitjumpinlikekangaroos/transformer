package com.transformer.viewer

import com.transformer.core.SqlExecutorRegistry
import com.transformer.sql.exec.SqlEngine
import org.junit.Assert._
import org.junit.{After, Test}

import java.nio.file.Path

/** Queries in the viewer: each table is `<dataset>__<table>`, a query joins
  * tables of different jobs, and a name the folder lacks says what it has;
  * and the session keeps its place as runs change and the folder is read
  * again. */
class QueryCatalogTest {
  import Fixtures._

  private val dirs = scala.collection.mutable.ArrayBuffer.empty[Path]
  private def temp(prefix: String): Path = { val d = tempDir(prefix); dirs += d; d }
  @After def cleanUp(): Unit = dirs.foreach(delete)

  private def session(root: Path): ViewerSession = {
    val s = new ViewerSession
    s.open(RunFolder.scan(root))
    s
  }

  private def rows(cat: QueryCatalog, sql: String): Seq[Seq[Any]] = {
    SqlEngine.init()
    val q = SqlExecutorRegistry.get.execute(sql, cat.catalogFor(sql))
    q.batches.toSeq.flatMap(b => (0 until b.numRows).map(r => (0 until b.schema.length).map(c => b.column(c).getBoxed(r))))
  }

  private def error(cat: QueryCatalog, sql: String): String =
    try { cat.catalogFor(sql); fail(s"expected an error for $sql"); "" }
    catch { case e: IllegalArgumentException => e.getMessage }

  @Test def eachTableAndOutsideInputHasOneName(): Unit = {
    val root = temp("qc-names-")
    threeJobs(root, temp("qc-outside-"))
    val cat = session(root).queryCatalog
    assertEquals(Vector("marketing__segments", "report__summary", "shop__customers", "shop__gold", "shop__raw_customers"),
      cat.entries.map(_.sqlName).sorted)
  }

  /** A query reads tables of different jobs together, each as its run wrote
    * it, and an outside input as its run read it. */
  @Test def aQueryJoinsTablesOfDifferentJobs(): Unit = {
    val root = temp("qc-join-")
    threeJobs(root, temp("qc-outside-"))
    val cat = session(root).queryCatalog
    assertEquals(Seq(Seq("gold", 2L, 2L), Seq("silver", 1L, 0L)), rows(cat,
      """SELECT s.tier, s.customers, COUNT(g.id) AS gold
        |FROM marketing__segments s
        |LEFT JOIN shop__customers c ON c.tier = s.tier
        |LEFT JOIN shop__gold g ON g.id = c.id
        |GROUP BY s.tier, s.customers
        |ORDER BY s.tier""".stripMargin))
    assertEquals(Seq(Seq(3L)), rows(cat, "SELECT COUNT(*) FROM shop__raw_customers"))
    assertEquals(Seq(Seq(3L)), rows(cat, "WITH t AS (SELECT * FROM report__summary) SELECT total FROM T"))
  }

  @Test def aNameTheFolderLacksSaysWhatItHas(): Unit = {
    val root = temp("qc-unknown-")
    threeJobs(root, temp("qc-outside-"))
    val cat = session(root).queryCatalog
    assertEquals("Write shop__customers for shop.customers: the viewer names a dataset's table <dataset>__<table>",
      error(cat, "SELECT * FROM shop.customers"))
    assertEquals("No table named 'customers' in this folder; did you mean shop__customers?",
      error(cat, "SELECT * FROM customers"))
  }

  /** A table whose task failed has no rows to read, and the query says so. */
  @Test def aFailedTableHasNoRows(): Unit = {
    val root = temp("qc-failed-")
    run(s"$root/broken", Nil, Seq("bad" -> "SELECT no_such_column FROM (SELECT 1 AS x) t"))
    val cat = session(root).queryCatalog
    assertEquals("broken__bad has no rows on disk: its task Failed in the run shown", error(cat, "SELECT * FROM broken__bad"))
    // The inspector's Data says the same, though the table has no SQL name.
    assertEquals(None, cat.sqlName(NodeId.Table(0, "bad")))
    assertEquals(Some("broken__bad has no rows on disk: its task Failed in the run shown"),
      cat.unreadable(NodeId.Table(0, "bad")))
  }

  /** Showing another run of a dataset reads that run's tables. */
  @Test def showingAnotherRun(): Unit = {
    val root = temp("qc-runs-")
    val raw = rawCustomers(temp("qc-raw-"))
    shop(s"$root/shop/day={{ today }}", raw, Day1)
    shop(s"$root/shop/day={{ today }}", raw, Day2)
    val s = session(root)
    val newest = s.queryCatalog.entry(NodeId.Table(0, "customers")).get.asInstanceOf[QueryEntry.Table]
    assertEquals("day=20260102", newest.dir.getParent.getFileName.toString)
    s.select(Some(ViewerSelection.OfNode(NodeId.Table(0, "gold"))))
    s.showRun(0, 1)
    val older = s.queryCatalog.entry(NodeId.Table(0, "customers")).get.asInstanceOf[QueryEntry.Table]
    assertEquals("day=20260101", older.dir.getParent.getFileName.toString)
    assertEquals(Some(ViewerSelection.OfNode(NodeId.Table(0, "gold"))), s.selection)
  }

  /** Reading the folder again keeps the selection on the same table, though
    * a dataset added before it moves its index; what is gone is dropped. */
  @Test def readingTheFolderAgainKeepsTheSelection(): Unit = {
    val root = temp("qc-reload-")
    shop(s"$root/shop", rawCustomers(temp("qc-raw-")))
    val s = session(root)
    val before = s.folder.get
    s.select(Some(ViewerSelection.OfNode(NodeId.Table(0, "gold"))))
    marketing(s"$root/marketing", s"$root/shop/customers")
    s.open(RunFolder.scan(root))
    assertEquals(Vector("marketing", "shop"), s.datasets.map(_.name))
    assertEquals(Some(ViewerSelection.OfNode(NodeId.Table(1, "gold"))), s.selection)
    assertEquals(Some(ViewerSelection.OfDataset(1)), s.relocate(ViewerSelection.OfDataset(0), before))
    assertEquals(Some(ViewerSelection.OfNode(NodeId.Input(1, "raw_customers"))),
      s.relocate(ViewerSelection.OfNode(NodeId.Input(0, "raw_customers")), before))
    assertEquals(None, s.relocate(ViewerSelection.OfNode(NodeId.Table(0, "no_such_table")), before))
    assertTrue(s.queryCatalog.sqlName(NodeId.Table(1, "gold")).contains("shop__gold"))

    // A folder without the dataset drops the selection.
    val other = temp("qc-other-")
    run(s"$other/finance", Nil, Seq("totals" -> "SELECT 1 AS one"))
    s.open(RunFolder.scan(other))
    assertEquals(None, s.selection)
  }
}
