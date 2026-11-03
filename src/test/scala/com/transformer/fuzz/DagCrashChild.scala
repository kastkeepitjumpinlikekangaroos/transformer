package com.transformer.fuzz

import com.transformer.core.SqlExecutorRegistry
import com.transformer.job.TaskProgressListener
import com.transformer.sql.exec.SqlEngine

import java.io.ObjectInputStream
import java.nio.file.{Files, Paths}

/** The child JVM the crash campaign stops a generated job's run in
  * (`oracle.DagSchedule.run` with `crash`): armed with
  * `-Dtransformer.fault=<point>:<n>:halt`, it runs run `r` of the case in
  * `caseFile` (a `JobCase`, serialized) under `root`, as the property's own
  * JVM would, and halts at the point. A halt must never end the property's
  * JVM, so it ends this one.
  *
  * `DagCrashChild <caseFile> <root> <r>`; exits 0 when the run ended without
  * reaching the point, whatever the job's verdict.
  */
object DagCrashChild {
  def main(args: Array[String]): Unit = {
    val in = new ObjectInputStream(Files.newInputStream(Paths.get(args(0))))
    val jc = try in.readObject().asInstanceOf[JobDagGen.JobCase] finally in.close()
    val time = jc.layout.executionTimes(args(2).toInt)
    SqlEngine.init()
    jc.build(Paths.get(args(1)), time).run(jc.executor(SqlExecutorRegistry.get), TaskProgressListener.NoOp)
    System.exit(0)
  }
}
