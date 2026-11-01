package com.transformer.viewer

import com.transformer.job.{JobRunRecord, TaskRunStatus}
import org.junit.Assert._
import org.junit.{After, Test}

import java.nio.file.{Files, Path}

/** Finding a folder's datasets: every job.json under it is a run, the runs of
  * one templated output directory are one dataset, and each dataset has a
  * name for the window and one for SQL. */
class RunFolderTest {
  import Fixtures._

  private val dirs = scala.collection.mutable.ArrayBuffer.empty[Path]
  private def temp(prefix: String): Path = { val d = tempDir(prefix); dirs += d; d }
  @After def cleanUp(): Unit = dirs.foreach(delete)

  @Test def everyJobOutputUnderTheFolderIsADataset(): Unit = {
    val root = temp("rf-root-")
    threeJobs(root, temp("rf-outside-"))
    val folder = RunFolder.scan(root)
    assertEquals(Vector("marketing", "report", "shop"), folder.datasets.map(_.name))
    assertEquals(Vector("marketing", "report", "shop"), folder.datasets.map(_.sqlName))
    assertEquals(Vector.empty, folder.warnings)
    val shop = folder.datasets(2)
    assertEquals(1, shop.runs.size)
    assertEquals(Vector("customers", "gold"), shop.runs.head.tables.map(_.name))
    shop.runs.head.tables.foreach { t =>
      assertEquals(t.name, Some(root.resolve("shop").resolve(t.name)), t.dir)
      assertEquals(TaskRunStatus.Succeeded, t.summary.status)
    }
  }

  /** A job writing to `day={{ today }}` leaves a run per day: one dataset,
    * named without the part the template fills in, newest run first. */
  @Test def theRunsOfATemplatedOutputAreOneDataset(): Unit = {
    val root = temp("rf-templated-")
    val raw = rawCustomers(temp("rf-raw-"))
    shop(s"$root/shop/day={{ today }}", raw, Day1)
    shop(s"$root/shop/day={{ today }}", raw, Day2)
    val folder = RunFolder.scan(root)
    assertEquals(Vector("shop"), folder.datasets.map(_.name))
    assertEquals(Vector("day=20260102", "day=20260101"), folder.datasets.head.runs.map(_.dir.getFileName.toString))
  }

  /** The template's filled-in part can come first, a run per day holding
    * every job: each job is still a dataset of its own. */
  @Test def aDatePartBeforeTheJobsNameIsLeftOut(): Unit = {
    val root = temp("rf-date-first-")
    val raw = rawCustomers(temp("rf-raw-"))
    shop(s"$root/{{ today }}/shop", raw, Day1)
    shop(s"$root/{{ today }}/shop", raw, Day2)
    marketing(s"$root/{{ today }}/marketing", s"$root/20260101/shop/customers", Day1)
    val folder = RunFolder.scan(root)
    assertEquals(Vector("marketing", "shop"), folder.datasets.map(_.name))
    assertEquals(2, folder.datasets(1).runs.size)
  }

  /** Opening one job's output directory shows it under the directory's name;
    * a name SQL cannot read unquoted becomes one it can. */
  @Test def aFolderThatIsOneRunIsNamedAfterItself(): Unit = {
    val root = temp("rf-single-").resolve("jaffle-out 2")
    shop(root.toString, rawCustomers(temp("rf-raw-")))
    val folder = RunFolder.scan(root)
    assertEquals(Vector("jaffle-out 2"), folder.datasets.map(_.name))
    assertEquals(Vector("jaffle_out_2"), folder.datasets.map(_.sqlName))
  }

  @Test def sqlNamesAreIdentifiersAndUnique(): Unit = {
    assertEquals("day_20260101", RunFolder.sqlIdentifier("day=20260101"))
    assertEquals("_2026_01_01", RunFolder.sqlIdentifier("2026-01-01"))
    assertEquals("team_jaffle", RunFolder.sqlIdentifier("team/jaffle"))
    assertEquals("dataset", RunFolder.sqlIdentifier("==="))
    assertEquals(Seq("a", "A_2", "a_3", "b"), RunFolder.uniqueNames(Seq("a", "A", "a", "b")))
  }

  /** A job.json from before records carried an output template is a dataset
    * of its own, and one the viewer cannot read is a warning, not a stop. */
  @Test def olderAndUnreadableRecords(): Unit = {
    val root = temp("rf-old-")
    shop(s"$root/shop", rawCustomers(temp("rf-raw-")))
    val manifest = root.resolve("shop/job.json")
    val rec = JobRunRecord.read(manifest).get
    JobRunRecord.write(manifest, rec.copy(outputTemplate = None, inputs = None))
    Files.createDirectories(root.resolve("broken"))
    Files.writeString(root.resolve("broken/job.json"), "{ not json")
    val folder = RunFolder.scan(root)
    assertEquals(Vector("shop"), folder.datasets.map(_.name))
    assertFalse(folder.datasets.head.runs.head.hasLineage)
    assertEquals(1, folder.warnings.size)
    assertTrue(folder.warnings.head, folder.warnings.head.contains("broken"))
  }

  /** A folder copied elsewhere still finds its tables: a recorded path that
    * is gone is looked for under the run's directory now. */
  @Test def aMovedFolderStillFindsItsTables(): Unit = {
    val root = temp("rf-moved-")
    shop(s"$root/shop/day={{ today }}", rawCustomers(temp("rf-raw-")))
    val moved = temp("rf-moved-to-").resolve("copy")
    Files.move(root, moved)
    val folder = RunFolder.scan(moved)
    val table = folder.datasets.head.runs.head.table("customers").get
    assertEquals(Some(moved.resolve("shop/day=20260101/customers")), table.dir)
    assertEquals(Some(root.resolve("shop/day=20260101/customers").toString), table.recordedDir)
  }
}
