package com.transformer.job

import java.nio.file.{Files, Paths}

/** One SQL transform inside a [[DataJob]].
  *
  * Exactly one of `sqlString` / `sqlFile` must be set. `outputFile`, when present,
  * persists the task's result; if absent the result is materialized in memory and
  * discarded (useful for tasks whose only purpose is to feed downstream views).
  *
  * If `viewName` is set, the result is also registered in the catalog so subsequent
  * SQLTasks can reference it.
  */
final case class SQLTask(
    sqlString: Option[String] = None,
    sqlFile: Option[String] = None,
    outputFile: Option[OutputFilePath] = None,
    validations: Seq[Validation] = Nil,
    viewName: Option[String] = None,
    name: Option[String] = None
) {
  require(
    sqlString.isDefined ^ sqlFile.isDefined,
    "Exactly one of sqlString or sqlFile must be set on SQLTask"
  )

  def loadSql(): String = sqlString.getOrElse(Files.readString(Paths.get(sqlFile.get)))

  def displayName: String =
    name.orElse(viewName).orElse(outputFile.map(_.path)).getOrElse(loadSql().take(40))
}

/** DBT-style data quality check. The query runs against the catalog (including the
  * just-produced task result, available under `viewName` if set). A result with
  * > 0 rows is a validation failure.
  */
final case class Validation(
    name: String,
    sqlString: Option[String] = None,
    sqlFile: Option[String] = None
) {
  require(
    sqlString.isDefined ^ sqlFile.isDefined,
    s"Validation '$name': exactly one of sqlString or sqlFile must be set"
  )

  def loadSql(): String = sqlString.getOrElse(Files.readString(Paths.get(sqlFile.get)))
}
