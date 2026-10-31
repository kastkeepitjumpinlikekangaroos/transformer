package com.transformer.cli

import com.transformer.gui.GuiApp
import com.transformer.sql.exec.SqlEngine

/** Entry point of the `transformer` deploy jar. `gui` opens the JavaFX app;
  * every other command is [[Cli]]'s. */
object TransformerMain {

  def main(args: Array[String]): Unit = {
    Cli.parse(args.toSeq) match {
      case Left(msg) =>
        System.err.println(s"transformer: $msg")
        System.err.println()
        System.err.print(Cli.Usage)
        System.exit(Cli.ExitUsage)
      case Right(Cli.Command.Help) =>
        print(Cli.Usage)
      case Right(Cli.Command.Gui(jobDir)) =>
        SqlEngine.init()
        GuiApp.main(jobDir.map(_.toString).toArray)
      case Right(cmd: Cli.Command.Run) =>
        System.exit(Cli.run(cmd, System.out, System.err))
    }
  }
}
