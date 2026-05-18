package com.example.gui

import com.transformer.gui.GuiApp

/** Launcher for the transformer GUI. */
object GuiAppLauncher {

  def main(args: Array[String]): Unit = {
    com.transformer.sql.exec.SqlEngine.init()
    GuiApp.main(args)
  }
}
