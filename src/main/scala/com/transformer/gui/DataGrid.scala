package com.transformer.gui

import atlantafx.base.theme.Styles
import com.transformer.core.{CatalogView, ColumnarBatch, DataType, Schema}

import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.collections.FXCollections
import javafx.scene.control.{ContextMenu, Label, MenuItem, SelectionMode, TableCell, TableColumn, TableView}
import javafx.scene.input.{Clipboard, ClipboardContent, KeyCode, KeyCodeCombination, KeyCombination, KeyEvent}
import javafx.scene.layout.VBox

import java.util.Arrays
import scala.collection.mutable

/** Rows in a table: a column per field, headed by its name and type. A cell
  * shows its value as [[DataGrid.format]] writes it, NULL in its own style,
  * a number to the right; sorting a column orders the values, not their
  * text. ⌘C (Ctrl+C) copies the selected rows as tab-separated text with
  * the header. The SQL console, a task's output and the data viewer's samples
  * all show rows in one. */
final class DataGrid extends TableView[Array[Any]] {

  getStyleClass.addAll("data-table", Styles.STRIPED, Styles.DENSE)
  getSelectionModel.setSelectionMode(SelectionMode.MULTIPLE)
  private var fieldNames: IndexedSeq[String] = IndexedSeq.empty

  private val copyCombo = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN)
  addEventHandler(KeyEvent.KEY_PRESSED, (e: KeyEvent) => if (copyCombo.`match`(e)) { copySelection(); e.consume() })
  private val copyItem = new MenuItem("Copy rows")
  copyItem.setOnAction(_ => copySelection())
  setContextMenu(new ContextMenu(copyItem))

  /** Show `rows`, each an array of `schema`'s values in order, each column
    * as wide as its header and the first rows' values, within limits. */
  def show(schema: Schema, rows: Seq[Array[Any]]): Unit = {
    getColumns.clear()
    fieldNames = schema.fieldNames
    schema.fields.iterator.zipWithIndex.foreach { case (field, i) =>
      val widest = rows.iterator.take(200).map(r => if (i < r.length) DataGrid.format(r(i)).length else 0)
        .foldLeft(math.max(field.name.length, DataType.sqlName(field.dataType).length))(math.max)
      val col = column(field.name, field.dataType, i)
      col.setPrefWidth(math.max(70.0, math.min(360.0, widest * 7.6 + 28)))
      getColumns.add(col)
    }
    setItems(FXCollections.observableArrayList(rows: _*))
  }

  def clear(): Unit = {
    getColumns.clear()
    getItems.clear()
    fieldNames = IndexedSeq.empty
  }

  def setPlaceholderText(text: String): Unit = {
    val l = new Label(text)
    l.getStyleClass.add("hint")
    l.setWrapText(true)
    setPlaceholder(l)
  }

  private def column(name: String, dataType: DataType, index: Int): TableColumn[Array[Any], Any] = {
    val col = new TableColumn[Array[Any], Any]()
    val title = new Label(name)
    title.getStyleClass.add("column-name")
    val typeLabel = new Label(DataType.sqlName(dataType))
    typeLabel.getStyleClass.add("column-type")
    col.setGraphic(new VBox(0, title, typeLabel))
    col.setCellValueFactory(features => {
      val row = features.getValue
      new ReadOnlyObjectWrapper[Any](if (row != null && index < row.length) row(index) else null)
    })
    val numeric = DataType.isNumeric(dataType)
    col.setCellFactory(_ => new TableCell[Array[Any], Any] {
      override def updateItem(item: Any, empty: Boolean): Unit = {
        super.updateItem(item, empty)
        getStyleClass.removeAll("null-cell", "number-cell")
        if (empty) setText(null)
        else if (item == null) {
          setText("NULL")
          getStyleClass.add("null-cell")
        } else {
          setText(DataGrid.format(item))
          if (numeric) getStyleClass.add("number-cell")
        }
      }
    })
    col.setComparator(DataGrid.ValueOrdering)
    col
  }

  private def copySelection(): Unit = {
    val rows = getSelectionModel.getSelectedItems
    if (rows.isEmpty) return
    val sb = new StringBuilder()
    sb.append(fieldNames.mkString("\t")).append('\n')
    rows.forEach { row =>
      sb.append(row.iterator.map(v => if (v == null) "" else DataGrid.format(v)).mkString("\t")).append('\n')
    }
    val content = new ClipboardContent()
    content.putString(sb.toString)
    Clipboard.getSystemClipboard.setContent(content)
    ()
  }
}

/** Up to some number of a view's rows, each an array of its values in schema
  * order; `truncated` when the view holds more. */
final case class RowSample(schema: Schema, rows: Seq[Array[Any]], truncated: Boolean)

object DataGrid {

  /** How many rows a preview of a table reads. */
  val PreviewRows: Int = 1000

  /** A cell's value as text: a decimal in full (`1E+3` as `1000`), bytes by
    * their count, anything else as itself. */
  def format(v: Any): String = v match {
    case null => ""
    case s: String => s
    case bd: java.math.BigDecimal => bd.toPlainString
    case bytes: Array[Byte] => s"<binary ${bytes.length} bytes>"
    case other => String.valueOf(other)
  }

  /** The first `max` rows of `view`, read a partition at a time, stopping as
    * soon as it has them. */
  def sample(view: CatalogView, max: Int = PreviewRows): RowSample = {
    val rows = mutable.ArrayBuffer.empty[Array[Any]]
    var truncated = false
    var p = 0
    while (p < view.numPartitions && !truncated) {
      val it = view.readPartition(p)
      while (it.hasNext && !truncated) truncated = append(it.next(), rows, max)
      if (rows.size >= max && (it.hasNext || p + 1 < view.numPartitions)) truncated = true
      p += 1
    }
    RowSample(view.schema, rows.toSeq, truncated)
  }

  /** Append `batch`'s rows to `out` up to `max`; true when some did not fit. */
  def append(batch: ColumnarBatch, out: mutable.ArrayBuffer[Array[Any]], max: Int): Boolean = {
    val width = batch.schema.length
    var r = 0
    while (r < batch.numRows && out.size < max) {
      val arr = new Array[Any](width)
      var c = 0
      while (c < width) {
        val col = batch.column(c)
        arr(c) = if (col.isNull(r)) null else col.getBoxed(r)
        c += 1
      }
      out += arr
      r += 1
    }
    r < batch.numRows
  }

  /** The order a sorted column shows its values in: NULL first, then the
    * values by their own order (a column holds one type: numbers by value,
    * dates by time, bytes unsigned), and anything else by its text. */
  val ValueOrdering: java.util.Comparator[Any] = (a: Any, b: Any) => (a, b) match {
    case (null, null) => 0
    case (null, _) => -1
    case (_, null) => 1
    case (x: Array[Byte], y: Array[Byte]) => Arrays.compareUnsigned(x, y)
    case (x: Comparable[_], y) if x.getClass == y.getClass => x.asInstanceOf[Comparable[Any]].compareTo(y)
    case _ => format(a).compareTo(format(b))
  }
}
