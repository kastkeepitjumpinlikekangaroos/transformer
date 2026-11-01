package com.transformer.viewer

import org.junit.Assert._
import org.junit.{After, Test}

import java.nio.file.Path

/** An inspector tab's Back and Forward, and the views each subject has:
  * clicking through tables keeps the view it is on, and reading the folder
  * again keeps the way back. */
class InspectorHistoryTest {
  import Fixtures._

  private val dirs = scala.collection.mutable.ArrayBuffer.empty[Path]
  private def temp(prefix: String): Path = { val d = tempDir(prefix); dirs += d; d }
  @After def cleanUp(): Unit = dirs.foreach(delete)

  private def table(d: Int, name: String): ViewerSelection = ViewerSelection.OfNode(NodeId.Table(d, name))

  @Test def backAndForward(): Unit = {
    val h = new InspectorHistory
    assertEquals(None, h.current)
    assertFalse(h.canGoBack)
    h.show(table(0, "a"), InspectorView.Data)
    h.show(table(0, "b"), InspectorView.Data)
    h.turn(InspectorView.Lineage)
    h.show(table(0, "c"), InspectorView.Lineage)
    assertEquals(Some((table(0, "b"), InspectorView.Lineage)), h.back())
    assertEquals(Some((table(0, "a"), InspectorView.Data)), h.back())
    assertEquals(None, h.back())
    assertTrue(h.canGoForward)
    assertEquals(Some((table(0, "b"), InspectorView.Lineage)), h.forward())
    // A new step from the middle drops what Forward would have reached.
    h.show(table(0, "d"), InspectorView.Columns)
    assertFalse(h.canGoForward)
    assertEquals(Some((table(0, "b"), InspectorView.Lineage)), h.back())
    assertEquals(Some((table(0, "d"), InspectorView.Columns)), h.forward())
  }

  /** The steps follow their subjects to where the session finds them, and
    * those it cannot find are dropped; the tab closes when the subject
    * shown is gone. */
  @Test def updateMovesAndDropsSteps(): Unit = {
    val h = new InspectorHistory
    h.show(table(0, "a"), InspectorView.Data)
    h.show(table(0, "gone"), InspectorView.Details)
    h.show(table(0, "b"), InspectorView.Columns)
    h.show(table(0, "c"), InspectorView.Data)
    h.back()
    val move: ViewerSelection => Option[ViewerSelection] = {
      case ViewerSelection.OfNode(NodeId.Table(_, "gone")) => None
      case ViewerSelection.OfNode(NodeId.Table(_, n)) => Some(table(1, n))
      case other => Some(other)
    }
    assertTrue(h.update(move))
    assertEquals(Some((table(1, "b"), InspectorView.Columns)), h.current)
    assertEquals(Some((table(1, "a"), InspectorView.Data)), h.back())
    assertEquals(None, h.back())
    assertEquals(Some((table(1, "b"), InspectorView.Columns)), h.forward())
    assertEquals(Some((table(1, "c"), InspectorView.Data)), h.forward())

    assertFalse(h.update(_ => None))
    assertEquals(Some((table(1, "c"), InspectorView.Data)), h.current)
  }

  /** A table and an outside input have rows and columns; an input that is
    * another dataset's table has that table's, so only its details and
    * lineage; a dataset has its tables. */
  @Test def theViewsOfEachSubject(): Unit = {
    val root = temp("ih-views-")
    threeJobs(root, temp("ih-outside-"))
    val s = new ViewerSession
    s.open(RunFolder.scan(root))
    val ds = s.datasets.map(_.name)
    import InspectorView._
    assertEquals(Seq(Data, Columns, Details, Lineage),
      InspectorView.of(s, table(ds.indexOf("shop"), "customers")))
    assertEquals(Seq(Data, Columns, Details, Lineage),
      InspectorView.of(s, ViewerSelection.OfNode(NodeId.Input(ds.indexOf("shop"), "raw_customers"))))
    assertEquals(Seq(Details, Lineage),
      InspectorView.of(s, ViewerSelection.OfNode(NodeId.Input(ds.indexOf("marketing"), "shop_customers"))))
    assertEquals(Seq(Tables, Details, Lineage), InspectorView.of(s, ViewerSelection.OfDataset(ds.indexOf("report"))))
  }
}
