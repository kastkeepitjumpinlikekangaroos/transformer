package com.transformer.viewer

import org.junit.Assert._
import org.junit.{After, Test}

import java.nio.file.{Files, Path}

/** The lineage across a folder: within a job what each table's SQL reads,
  * across jobs an input whose path is another job's table, and where the
  * canvas draws it, the whole folder or one table's lineage. */
class LineageTest {
  import Fixtures._

  private val dirs = scala.collection.mutable.ArrayBuffer.empty[Path]
  private def temp(prefix: String): Path = { val d = tempDir(prefix); dirs += d; d }
  @After def cleanUp(): Unit = dirs.foreach(delete)

  private def table(l: Lineage, dataset: String, name: String): NodeId =
    NodeId.Table(l.folder.datasets.indexWhere(_.name == dataset), name)
  private def input(l: Lineage, dataset: String, name: String): NodeId =
    NodeId.Input(l.folder.datasets.indexWhere(_.name == dataset), name)

  @Test def edgesWithinAndAcrossJobs(): Unit = {
    val root = temp("lin-root-")
    threeJobs(root, temp("lin-outside-"))
    val l = Lineage.build(RunFolder.scan(root))
    val edges = l.edges.toSet
    // Within the shop: its input feeds customers, and customers gold.
    assertTrue(edges(input(l, "shop", "raw_customers") -> table(l, "shop", "customers")))
    assertTrue(edges(table(l, "shop", "customers") -> table(l, "shop", "gold")))
    // Across: marketing's input is the shop's customers directory, and the
    // report's a glob of marketing's segments part files.
    assertTrue(edges(table(l, "shop", "customers") -> input(l, "marketing", "shop_customers")))
    assertTrue(edges(input(l, "marketing", "shop_customers") -> table(l, "marketing", "segments")))
    assertTrue(edges(table(l, "marketing", "segments") -> input(l, "report", "segments")))
    assertEquals(InputSource.External, l.sources(input(l, "shop", "raw_customers").asInstanceOf[NodeId.Input]))
    assertEquals(6, l.edges.size)

    val ds = l.folder.datasets.map(_.name)
    assertEquals(Seq("shop"), l.upstreamDatasets(ds.indexOf("marketing")).map(ds))
    assertEquals(Seq("report"), l.downstreamDatasets(ds.indexOf("marketing")).map(ds))
    // The whole lineage of the report's summary, back to the raw CSV.
    assertEquals(Set(input(l, "report", "segments"), table(l, "marketing", "segments"),
      input(l, "marketing", "shop_customers"), table(l, "shop", "customers"), input(l, "shop", "raw_customers")),
      l.ancestors(table(l, "report", "summary")))
    assertEquals(Set(table(l, "shop", "gold"), input(l, "marketing", "shop_customers"), table(l, "marketing", "segments"),
      input(l, "report", "segments"), table(l, "report", "summary")), l.descendants(table(l, "shop", "customers")))
  }

  /** An input that reads one file of a table, or the table through a
    * symbolic link to the folder, is that table's. */
  @Test def aFileInATableOrALinkToItIsTheTable(): Unit = {
    val root = temp("lin-link-")
    shop(s"$root/shop", rawCustomers(temp("lin-raw-")))
    val link = temp("lin-links-").resolve("shop-link")
    Files.createSymbolicLink(link, root.resolve("shop"))
    marketing(s"$root/marketing", s"$link/customers")
    marketing(s"$root/file", s"$root/shop/gold/part-00000.csv")
    val l = Lineage.build(RunFolder.scan(root))
    assertTrue(l.edges.contains(table(l, "shop", "customers") -> input(l, "marketing", "shop_customers")))
    assertTrue(l.edges.contains(table(l, "shop", "gold") -> input(l, "file", "shop_customers")))
  }

  /** A job reading an older run of another job still joins that job's table,
    * and says the run it read is not the one shown. */
  @Test def readingAnOlderRun(): Unit = {
    val root = temp("lin-runs-")
    val raw = rawCustomers(temp("lin-raw-"))
    shop(s"$root/shop/day={{ today }}", raw, Day1)
    shop(s"$root/shop/day={{ today }}", raw, Day2)
    marketing(s"$root/marketing", s"$root/shop/day=20260101/customers")
    val folder = RunFolder.scan(root)
    val l = Lineage.build(folder)
    val in = input(l, "marketing", "shop_customers").asInstanceOf[NodeId.Input]
    l.sources(in) match {
      case InputSource.Tables(Seq(m)) =>
        assertEquals((false, true), (m.shown, m.inShownRun))
        assertEquals("day=20260101", m.runDir.getFileName.toString)
      case other => fail(other.toString)
    }
    assertTrue(l.edges.contains(table(l, "shop", "customers") -> in))
    // Shown at that run, it is the one read.
    val older = Lineage.build(folder, Map(folder.datasets.indexWhere(_.name == "shop") -> 1))
    older.sources(in) match {
      case InputSource.Tables(Seq(m)) => assertTrue(m.shown)
      case other => fail(other.toString)
    }
  }

  /** Datasets stand in columns by what they read, a dataset's nodes in
    * columns inside its box, inputs first; nothing overlaps. */
  @Test def layout(): Unit = {
    val root = temp("lin-layout-")
    threeJobs(root, temp("lin-outside-"))
    val l = Lineage.build(RunFolder.scan(root))
    val layout = LineageLayout.compute(l)
    val ds = l.folder.datasets.map(_.name)
    val box = ds.map(n => n -> layout.datasetBoxes(ds.indexOf(n))).toMap
    assertTrue(box("shop").right < box("marketing").x)
    assertTrue(box("marketing").right < box("report").x)
    l.nodes.foreach { n =>
      val b = layout.nodeBoxes(n)
      val d = layout.datasetBoxes(n.dataset)
      assertTrue(s"$n inside its dataset", b.x >= d.x && b.right <= d.right && b.y >= d.y && b.bottom <= d.bottom)
    }
    val customers = layout.nodeBoxes(table(l, "shop", "customers"))
    assertTrue(layout.nodeBoxes(input(l, "shop", "raw_customers")).right < customers.x)
    assertTrue(customers.right < layout.nodeBoxes(table(l, "shop", "gold")).x)
    val boxes = layout.nodeBoxes.values.toSeq
    for (a <- boxes; b <- boxes if a ne b)
      assertFalse(s"$a overlaps $b", a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom)
    assertEquals(Some(Right(table(l, "shop", "gold"))),
      layout.hitTest(layout.nodeBoxes(table(l, "shop", "gold")).centerX, layout.nodeBoxes(table(l, "shop", "gold")).centerY))
    assertEquals(Some(Left(ds.indexOf("shop"))), layout.hitTest(box("shop").x + 4, box("shop").y + 4))
    assertTrue(layout.width >= box("report").right && layout.height >= box("shop").bottom)
  }

  /** One table's lineage draws only what it is made from and what is made
    * from it, and a box only for the datasets those are in. */
  @Test def aTablesLineageAlone(): Unit = {
    val root = temp("lin-around-")
    threeJobs(root, temp("lin-outside-"))
    val l = Lineage.build(RunFolder.scan(root))
    val ds = l.folder.datasets.map(_.name)

    // The shop's gold table: the raw CSV and customers before it, nothing after.
    val gold = table(l, "shop", "gold")
    assertEquals(Set(input(l, "shop", "raw_customers"), table(l, "shop", "customers"), gold), l.around(gold))
    val alone = LineageLayout.compute(l, Some(l.around(gold)))
    assertEquals(l.around(gold), alone.nodeBoxes.keySet)
    assertEquals(Set(ds.indexOf("shop")), alone.datasetBoxes.keySet)
    assertTrue(alone.nodeBoxes(input(l, "shop", "raw_customers")).right < alone.nodeBoxes(table(l, "shop", "customers")).x)
    assertTrue(alone.width < LineageLayout.compute(l).width)

    // Marketing's segments: the shop before it and the report after, each
    // box holding only the nodes in that lineage, in columns as before.
    val segments = table(l, "marketing", "segments")
    val across = LineageLayout.compute(l, Some(l.around(segments)))
    assertEquals(Set("shop", "marketing", "report").map(ds.indexOf), across.datasetBoxes.keySet)
    assertFalse(across.nodeBoxes.contains(table(l, "shop", "gold")))
    val box = across.datasetBoxes
    assertTrue(box(ds.indexOf("shop")).right < box(ds.indexOf("marketing")).x)
    assertTrue(box(ds.indexOf("marketing")).right < box(ds.indexOf("report")).x)
    across.nodeBoxes.foreach { case (n, b) =>
      val d = box(n.dataset)
      assertTrue(s"$n inside its dataset", b.x >= d.x && b.right <= d.right && b.y >= d.y && b.bottom <= d.bottom)
    }

    // A dataset's lineage: every node of it, and their lineages.
    assertEquals(l.nodes.filter(_.dataset == ds.indexOf("report")).toSet ++
      l.around(table(l, "report", "summary")), l.aroundDataset(ds.indexOf("report")))
  }
}
