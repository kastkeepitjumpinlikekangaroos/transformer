package com.transformer.viewer

import com.transformer.job.{DataJob, InputFilePath, OutputFilePath, SQLTask, Validation}
import com.transformer.temporal.TemporalVariables

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Comparator
import scala.jdk.CollectionConverters._

/** Runs of real jobs into a folder, as the viewer finds them. */
object Fixtures {

  val Day1: Instant = Instant.parse("2026-01-01T06:00:00Z")
  val Day2: Instant = Instant.parse("2026-01-02T06:00:00Z")

  def tempDir(prefix: String): Path = Files.createTempDirectory(prefix).toRealPath()

  def delete(dir: Path): Unit =
    if (dir != null && Files.exists(dir))
      Files.walk(dir).sorted(Comparator.reverseOrder()).iterator().asScala.foreach(Files.deleteIfExists(_))

  /** A raw CSV outside the folder, the shop's source. */
  def rawCustomers(dir: Path): Path = {
    val p = dir.resolve("raw_customers.csv")
    Files.writeString(p, "id,name,tier\n1,Ada,gold\n2,Bo,silver\n3,Cy,gold\n")
    p
  }

  /** Run `tasks` reading `inputs`, writing each task to `<output>/<view>` and
    * the record to `<output>/job.json`; `output` may hold templates. */
  def run(output: String, inputs: Seq[InputFilePath], tasks: Seq[(String, String)], at: Instant = Day1,
      validations: Map[String, String] = Map.empty): Unit = {
    val job = DataJob(
      inputs = inputs,
      sql = tasks.map { case (view, sql) =>
        SQLTask(name = Some(view), viewName = Some(view), sqlString = Some(sql),
          outputFile = Some(OutputFilePath(s"$output/$view", format = Some("csv"))),
          validations = validations.get(view).map(v => Validation("check", sqlString = Some(v))).toSeq)
      },
      temporalVariables = Some(TemporalVariables(at)),
      jobRunOutput = Some(OutputFilePath(s"$output/job.json")))
    job.run()
    ()
  }

  /** The shop: a table of customers from the raw CSV, and the gold ones from
    * those. */
  def shop(output: String, raw: Path, at: Instant = Day1): Unit =
    run(output, Seq(InputFilePath(raw.toString, "raw_customers")), Seq(
      "customers" -> "SELECT id, name, tier FROM raw_customers",
      "gold" -> "SELECT id, name FROM customers WHERE tier = 'gold'"
    ), at)

  /** Marketing: reads the shop's customers table as its input `shop_customers`. */
  def marketing(output: String, shopCustomers: String, at: Instant = Day1): Unit =
    run(output, Seq(InputFilePath(shopCustomers, "shop_customers", format = Some("csv"))), Seq(
      "segments" -> "SELECT tier, COUNT(*) AS customers FROM shop_customers GROUP BY tier"
    ), at)

  /** A report reading marketing's segments through a glob of its part files. */
  def report(output: String, segmentsGlob: String): Unit =
    run(output, Seq(InputFilePath(segmentsGlob, "segments", format = Some("csv"))), Seq(
      "summary" -> "SELECT SUM(customers) AS total FROM segments"
    ))

  /** A folder of three jobs, each reading the one before it:
    * `shop` (from a CSV outside the folder), `marketing` (the shop's
    * customers), `report` (marketing's segments, through a glob). */
  def threeJobs(root: Path, outside: Path): Unit = {
    shop(s"$root/shop", rawCustomers(outside))
    marketing(s"$root/marketing", s"$root/shop/customers")
    report(s"$root/report", s"$root/marketing/segments/*.csv")
  }
}
