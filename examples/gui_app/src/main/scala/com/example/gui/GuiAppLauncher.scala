package com.example.gui

import com.transformer.gui.GuiApp

/** Launcher for the transformer GUI.
  *
  * Touching this object loads parquet read/write modules at class load time
  * (via the imports below), so the parquet hooks self-install before the GUI
  * starts. With the hooks in place, the GUI can preview parquet outputs and run
  * jobs with parquet inputs.
  */
object GuiAppLauncher {

  // Force class-load so the hooks installed by these modules' object initializers
  // are wired up before GuiApp starts running and the user opens a job.
  private val _parquetSupport: AnyRef = com.transformer.read.parquet.ParquetSupport
  private val _sqlEngine: Unit = com.transformer.sql.exec.SqlEngine.init()

  def main(args: Array[String]): Unit = {
    // No-op references so the eager vals above aren't eliminated.
    val _ = (_parquetSupport, _sqlEngine)
    GuiApp.main(args)
  }
}
