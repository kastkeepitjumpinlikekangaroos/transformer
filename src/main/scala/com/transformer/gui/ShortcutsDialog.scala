package com.transformer.gui

import javafx.scene.control.{ButtonType, Dialog, Label, ScrollPane}
import javafx.scene.layout.GridPane
import javafx.stage.Stage

/** Help > Keyboard Shortcuts: the window's shortcuts, which each app passes,
  * then the SQL console's and the vim commands the SQL editors take while
  * View > Vim Keybindings is on. */
object ShortcutsDialog {

  private val IsMac = System.getProperty("os.name", "").toLowerCase.contains("mac")
  /** The shortcut modifier as a key list writes it: ⌘ on macOS, Ctrl+ elsewhere. */
  val Cmd: String = if (IsMac) "⌘" else "Ctrl+"
  val Shift: String = if (IsMac) "⇧" else "Shift+"

  private def groups: Seq[(String, Seq[(String, String)])] = Seq(
    "SQL CONSOLE" -> Seq(
      s"$Cmd${if (IsMac) "⏎" else "Enter"}" -> "Run the query"
    ),
    "VIM: MODES" -> Seq(
      "i a I A o O" -> "Insert (before, after, line start, line end, new line below, above)",
      "v V" -> "Visual, by character or by line",
      "R" -> "Replace",
      ": / ?" -> "Command line, search forward, search back",
      "<Esc>" -> "Back to normal mode"
    ),
    "VIM: MOTIONS" -> Seq(
      "h j k l" -> "Left, down, up, right",
      "w b e ge  W B E gE" -> "Words, and WORDs (anything between blanks)",
      "0 ^ $ g_" -> "Line start, first non-blank, line end, last non-blank",
      "gg G  {n}G  :{n}" -> "First line, last line, line n",
      "f t F T ; ," -> "To a character on the line, and again",
      "% { }" -> "Matching bracket, paragraph back and forward",
      "H M L" -> "Top, middle, bottom of the window",
      "<C-d> <C-u> <C-f> <C-b>" -> "Half a page and a page, down and up"
    ),
    "VIM: CHANGES" -> Seq(
      "d c y > < g~ gu gU" -> "Operators: with a motion, a text object, or doubled for lines (dd, cc, yy, >>)",
      "iw aw ip ap i( a( i[ i{ i\" i'" -> "Text objects: word, paragraph, brackets, quotes",
      "x X s S D C Y" -> "Delete, change and yank shorthands",
      "p P gp gP" -> "Put after, before; and leave the cursor after it",
      "r J gJ ~" -> "Replace a character, join lines, toggle case",
      "<C-a> <C-x>" -> "Add to, subtract from the number under the cursor",
      "u <C-r> ." -> "Undo, redo, repeat the last change",
      "q{a-z} @{a-z} @@ @:" -> "Record a macro, play it, again, repeat the last : command",
      "\"{a-z} \"+ \"0 \"_" -> "Registers: named, the clipboard, the last yank, discard"
    ),
    "VIM: SEARCH AND COMMANDS" -> Seq(
      "/ ? n N * #" -> "Search (vim's regex; \\c ignores case), next, previous, the word under the cursor",
      ":s/a/b/g  :%s  :'<,'>s" -> "Substitute on the line, the file, the selection",
      ":d :y :j :> :<" -> "Delete, yank, join, shift lines",
      ":w  :q  :q!  :wq  ZZ" -> "Save the edit, close it (! drops changes), both"
    ),
    "VIM: INSERT MODE" -> Seq(
      "<C-w> <C-u>" -> "Delete the word, the line, before the cursor",
      "<C-t> <C-d> <Tab>" -> "Indent, dedent the line; indent to the next 2 columns",
      "<C-r>{reg}" -> "Put a register's text"
    ),
    "VIM: ALSO" -> Seq(
      s"${Cmd}Z $Shift${Cmd}Z ${Cmd}C ${Cmd}X ${Cmd}V ${Cmd}A" -> "Undo, redo, copy, cut, paste and select all, as everywhere",
      "Mouse" -> "A click moves the cursor; a drag selects in visual mode"
    )
  )

  /** Show the sheet, `window` (the app's own shortcuts, keys and what they
    * do) first. */
  def show(owner: Stage, window: Seq[(String, String)]): Unit = {
    val grid = new GridPane()
    grid.getStyleClass.add("shortcut-table")
    var row = 0
    (("WINDOW" -> window) +: groups).foreach { case (group, entries) =>
      val g = new Label(group)
      g.getStyleClass.add("group")
      grid.add(g, 0, row, 2, 1)
      row += 1
      entries.foreach { case (keys, what) =>
        val k = new Label(keys)
        k.getStyleClass.addAll("keys", "mono")
        val w = new Label(what)
        w.getStyleClass.add("what")
        w.setWrapText(true)
        grid.add(k, 0, row)
        grid.add(w, 1, row)
        row += 1
      }
    }
    val scroll = new ScrollPane(grid)
    scroll.setFitToWidth(true)
    scroll.setPrefSize(720, 560)

    val dialog = new Dialog[ButtonType]()
    dialog.initOwner(owner)
    dialog.setTitle("Keyboard shortcuts")
    dialog.setHeaderText(
      "Vim keybindings are on in every SQL editor while View > Vim Keybindings is checked; " +
        "the VIM button in an editor's status line turns them off and on too."
    )
    dialog.getDialogPane.setContent(scroll)
    dialog.getDialogPane.getButtonTypes.add(ButtonType.CLOSE)
    dialog.setResizable(true)
    Theme.style(dialog)
    dialog.showAndWait()
    ()
  }
}
