package com.transformer.gui

import javafx.scene.Node
import javafx.scene.layout.StackPane
import javafx.scene.shape.SVGPath

/** The few line icons the GUI's buttons carry, drawn on a 16px grid.
  * `transformer.css` colours them (`.icon` strokes, `.icon.solid` fills). */
object Icons {

  def play: Node = icon("M5 3.5 L12.5 8 L5 12.5 Z", solid = true)
  def folder: Node = icon("M2 4.5 Q2 3 3.5 3 H6.2 L7.7 4.5 H12.5 Q14 4.5 14 6 V11.5 Q14 13 12.5 13 H3.5 Q2 13 2 11.5 Z")
  def plus: Node = icon("M8 3.5 V12.5 M3.5 8 H12.5")
  def minus: Node = icon("M3.5 8 H12.5")
  def fit: Node = icon("M2.5 6 V2.5 H6 M10 2.5 H13.5 V6 M13.5 10 V13.5 H10 M6 13.5 H2.5 V10")
  def copy: Node = icon("M5.5 5.5 H12.5 V12.5 H5.5 Z M3.5 10.5 V3.5 H10.5")
  def refresh: Node = icon("M13 8 A5 5 0 1 1 11.2 4.2 M11.5 1.8 V4.5 H8.8")
  def edit: Node = icon("M10.5 2.8 L13.2 5.5 L6 12.7 H3.3 V10 Z")
  def clock: Node = icon("M8 2.5 A5.5 5.5 0 1 1 7.99 2.5 Z M8 5 V8 L10 9.5")
  /** Data read from a file: a cylinder. */
  def database: Node = icon("M3 4.5 C3 2.5 13 2.5 13 4.5 C13 6.5 3 6.5 3 4.5 V11.5 C3 13.5 13 13.5 13 11.5 V4.5")
  def table: Node = icon("M2.5 3.5 H13.5 V12.5 H2.5 Z M2.5 6.5 H13.5 M6.5 6.5 V12.5")
  def search: Node = icon("M7 2.5 A4.5 4.5 0 1 1 6.99 2.5 Z M10.3 10.3 L13.5 13.5")

  private def icon(path: String, solid: Boolean = false): Node = {
    val p = new SVGPath()
    p.setContent(path)
    p.getStyleClass.add("icon")
    if (solid) p.getStyleClass.add("solid")
    // A fixed 16px box, so icons of different shapes line up in a row.
    val box = new StackPane(p)
    box.getStyleClass.add("icon-box")
    box.setMinSize(16, 16)
    box.setPrefSize(16, 16)
    box.setMaxSize(16, 16)
    box
  }
}
