package com.transformer.gui

import atlantafx.base.theme.PrimerDark
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.control.Dialog
import javafx.scene.paint.Color
import javafx.scene.text.Font

import java.nio.charset.StandardCharsets
import java.util.Base64

/** The GUI's look. AtlantaFX's Primer Dark theme styles every standard
  * control, dialogs and popups included; `transformer.css` styles the GUI's own
  * pieces with the theme's colours; [[Palette]] holds the same colours for the
  * DAG canvas, which paints rather than styles.
  *
  * [[install]] runs once on the FX thread before any control is built, and
  * every scene and dialog the GUI makes goes through [[style]].
  */
object Theme {

  /** The theme's colours, for code that paints with them: Primer Dark's. */
  object Palette {
    val Background: Color = Color.web("#0d1117")
    val Surface: Color = Color.web("#161b22")
    val SurfaceRaised: Color = Color.web("#1c2128")
    val Border: Color = Color.web("#30363d")
    val BorderMuted: Color = Color.web("#21262d")
    /** The canvas's dot grid: a shade above the background. */
    val Grid: Color = Color.web("#1f262f")
    val Text: Color = Color.web("#e6edf3")
    val TextMuted: Color = Color.web("#8b949e")
    val TextSubtle: Color = Color.web("#6e7681")
    val Accent: Color = Color.web("#58a6ff")
    val Success: Color = Color.web("#3fb950")
    val Warning: Color = Color.web("#d29922")
    val Danger: Color = Color.web("#f85149")
    val Done: Color = Color.web("#a371f7")
    /** Inputs: the data a job reads, in a colour of their own. */
    val Input: Color = Color.web("#39c5cf")
  }

  /** Monospace families in the order they are preferred. CSS has no font
    * fallback list, so [[monoFamily]] picks the first one installed. */
  private val MonoFamilies = Seq(
    "JetBrains Mono", "SF Mono", "Fira Code", "Cascadia Mono", "Menlo",
    "Consolas", "DejaVu Sans Mono", "Monaco", "Courier New"
  )

  /** The monospace family code and data cells are set in. */
  lazy val monoFamily: String = {
    val installed = Font.getFamilies
    MonoFamilies.find(installed.contains).getOrElse("Monospaced")
  }

  /** The size code is set in, in pixels. */
  val CodeFontSize: Double = 13.0

  def codeFont: Font = Font.font(monoFamily, CodeFontSize)

  def install(): Unit = Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet)

  private lazy val stylesheets: Seq[String] = Seq(
    getClass.getResource("transformer.css").toExternalForm,
    fontSheet
  )

  /** A stylesheet naming [[monoFamily]] for everything set in monospace. */
  private lazy val fontSheet: String = {
    val css =
      s""".code-area, .mono, .data-table .table-cell, .log-view, .log-view .content, .path-label {
         |  -fx-font-family: "$monoFamily";
         |}
         |""".stripMargin
    "data:text/css;base64," + Base64.getEncoder.encodeToString(css.getBytes(StandardCharsets.UTF_8))
  }

  def style(scene: Scene): Unit = scene.getStylesheets.addAll(stylesheets: _*)

  def style(dialog: Dialog[_]): Unit = dialog.getDialogPane.getStylesheets.addAll(stylesheets: _*)
}

/** The colour a status pill or chip takes: a `transformer.css` class. */
sealed abstract class Tone(val css: String)
object Tone {
  case object Neutral extends Tone("neutral")
  case object Running extends Tone("running")
  case object Success extends Tone("success")
  case object Warning extends Tone("warning")
  case object Danger  extends Tone("danger")
  case object Input   extends Tone("input")
}
