package com.transformer.gui

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import scala.util.control.NonFatal

/** Launches the user's text editor on a given file.
  *
  * Resolution:
  *   1. `TRANSFORMER_EDITOR` env var. Naively split on whitespace; the file
  *      path is appended as the final argument. Works for GUI editors that
  *      take a single path (`code`, `subl`, `mvim`) or a wrapper script that
  *      handles its own terminal.
  *   2. Default — on macOS open Terminal.app and run `nvim <file>` in it.
  *      Other platforms have no good no-config default; throw with a hint to
  *      set `TRANSFORMER_EDITOR`.
  *
  * Two failure modes:
  *   * Synchronous: the launcher process can't start (binary missing, args
  *     malformed). [[open]] throws RuntimeException — surface via Alert.
  *   * Asynchronous: the launcher starts but exits non-zero shortly after
  *     (e.g. osascript denied Apple Events authorization, or nvim missing
  *     from Terminal's PATH). Use [[openWithCallback]] to receive a callback
  *     on a background thread when that happens — the synchronous [[open]]
  *     can't detect this without blocking the caller.
  */
object ExternalEditor {

  /** Convenience: fires + forgets and logs any async failure to stderr. */
  def open(file: Path): Unit =
    openWithCallback(file, t => System.err.println(s"[ExternalEditor] $t"))

  /** Launch + register a failure callback for async exits.
    *
    * `onAsyncFailure` is invoked from a daemon watcher thread iff the editor
    * exits with a non-zero status within a few seconds. Callers that want to
    * pop a JavaFX Alert must marshal back onto the FX thread themselves.
    *
    * Throws RuntimeException if the launcher process can't even be started.
    */
  def openWithCallback(file: Path, onAsyncFailure: Throwable => Unit): Unit = {
    val abs = file.toAbsolutePath.toString
    val cmd = sys.env.get("TRANSFORMER_EDITOR").map(_.trim).filter(_.nonEmpty) match {
      case Some(custom) => custom.split("\\s+").toSeq :+ abs
      case None         => defaultCommand(abs)
    }
    System.err.println(s"[ExternalEditor] launching: ${cmd.mkString(" ")}")
    val pb = new ProcessBuilder(cmd: _*)
    pb.redirectErrorStream(true)
    pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
    val proc =
      try pb.start()
      catch {
        case e: IOException =>
          throw new RuntimeException(
            s"could not start editor command '${cmd.head}': ${e.getMessage}", e)
        case NonFatal(e) =>
          throw new RuntimeException(
            s"could not start editor (${cmd.mkString(" ")}): ${e.getMessage}", e)
      }
    val watcher = new Thread(new Runnable {
      def run(): Unit = {
        try {
          val finished = proc.waitFor(3, TimeUnit.SECONDS)
          if (finished && proc.exitValue() != 0) {
            val out = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
            val tail = if (out.isEmpty) "" else s":\n$out"
            onAsyncFailure(new RuntimeException(
              s"editor exited with code ${proc.exitValue()}$tail"))
          }
        } catch { case NonFatal(t) => onAsyncFailure(t) }
      }
    }, s"transformer-editor-watcher-${proc.pid()}")
    watcher.setDaemon(true)
    watcher.start()
  }

  /** True iff the current platform has a built-in default editor command,
    * i.e. opening will work without `TRANSFORMER_EDITOR` being configured.
    */
  def hasDefault: Boolean = isMac || sys.env.contains("TRANSFORMER_EDITOR")

  /** Human-readable description of the editor that will be invoked next time
    * [[open]] is called. Used by tooltips.
    */
  def describe: String = sys.env.get("TRANSFORMER_EDITOR").map(_.trim).filter(_.nonEmpty) match {
    case Some(custom)  => s"TRANSFORMER_EDITOR: $custom"
    case None if isMac => "nvim in Terminal.app (set TRANSFORMER_EDITOR to override)"
    case None          => "(no default — set TRANSFORMER_EDITOR to a GUI editor command)"
  }

  private def defaultCommand(absPath: String): Seq[String] = {
    if (isMac) {
      val applePath = absPath.replace("\\", "\\\\").replace("\"", "\\\"")
      Seq(
        "osascript",
        "-e", """tell application "Terminal" to activate""",
        "-e", s"""tell application "Terminal" to do script "nvim \"$applePath\""""
      )
    } else {
      throw new RuntimeException(
        "No built-in editor on this platform — set TRANSFORMER_EDITOR " +
          "(e.g. 'code', 'subl', 'gvim') to a command that opens a file."
      )
    }
  }

  private def isMac: Boolean = {
    val os = sys.props.getOrElse("os.name", "").toLowerCase
    os.contains("mac") || os.contains("darwin")
  }
}
