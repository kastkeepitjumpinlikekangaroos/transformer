package com.transformer.gui

import atlantafx.base.theme.Styles
import com.transformer.core.{ColumnarBatch, MaterializedView}

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.{Button, CheckBox, ComboBox, CustomMenuItem, Label, MenuButton, ToggleButton, ToggleGroup,
  Tooltip}
import javafx.scene.image.{PixelFormat, WritableImage}
import javafx.scene.layout.{FlowPane, HBox, Priority, Region, StackPane, VBox}
import javafx.stage.{FileChooser, Stage}

import java.awt.image.BufferedImage
import java.io.{File, IOException}
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import scala.util.control.NonFatal

/** A query's result as a chart ([[ChartCanvas]]), under a row of choices:
  * the type (bar, line, area, scatter, pie), the column along X, how rows
  * sharing an X combine and the columns of numbers drawn up Y, and a column
  * that splits the rows into a series per value. Save PNG… writes the chart
  * at twice its size on screen. A note under it says how many rows it
  * draws from, and what it leaves out.
  *
  * The choices start as [[ChartSpec.fit]] suggests for the result's
  * columns and carry over to the next result wherever its columns allow.
  * The chart is worked out on a worker from every row of the result, and
  * only while it shows (`setActive`); until a new one is ready the last
  * stays.
  */
final class ChartPane(owner: () => Stage) extends VBox {

  getStyleClass.addAll("panel", "chart-pane")

  private var result: Option[MaterializedView] = None
  private var spec: Option[ChartSpec] = None
  private var active = false
  /** Whether the chart shows the result and the choices as they are now. */
  private var current = false
  /** Set while the choices are put in place, so their listeners keep still. */
  private var syncing = false
  private val token = new AtomicLong()

  // ---- the choices ----
  private val types = new ToggleGroup()
  private val typeButtons: Seq[ToggleButton] = ChartType.all.zipWithIndex.map { case (t, i) =>
    val b = new ToggleButton(null, ChartPane.icon(t))
    b.setToggleGroup(types)
    b.setUserData(t)
    b.setFocusTraversable(false)
    b.getStyleClass.add(
      if (i == 0) Styles.LEFT_PILL else if (i == ChartType.all.size - 1) Styles.RIGHT_PILL else Styles.CENTER_PILL)
    b.setTooltip(new Tooltip(s"${t.title} chart"))
    b
  }
  types.selectedToggleProperty().addListener((_, before, now) =>
    if (now == null) {
      // A type stays chosen: clicking the chosen one again keeps it.
      if (before != null) types.selectToggle(before)
    } else if (!syncing) change(_.copy(chartType = now.getUserData.asInstanceOf[ChartType])))
  private val typeGroup = new HBox(0, typeButtons: _*)

  private val xBox = new ComboBox[String]()
  xBox.getStyleClass.add(Styles.SMALL)
  xBox.setTooltip(new Tooltip("The column along X"))
  xBox.valueProperty().addListener((_, _, x) => if (!syncing && x != null) change(_.copy(x = x)))

  private val aggregationBox = new ComboBox[String]()
  aggregationBox.getStyleClass.add(Styles.SMALL)
  aggregationBox.getItems.addAll(Aggregation.all.map(_.title): _*)
  aggregationBox.setTooltip(new Tooltip("How the rows that share an X make one value"))
  aggregationBox.getSelectionModel.selectedIndexProperty().addListener((_, _, i) =>
    if (!syncing && i.intValue >= 0) change(_.copy(aggregation = Aggregation.all(i.intValue))))
  private val ofLabel = caption("of")

  private val yMenu = new MenuButton()
  yMenu.getStyleClass.add(Styles.SMALL)
  // A column's name is shown as it is: an underscore is not a shortcut key.
  yMenu.setMnemonicParsing(false)
  yMenu.setTooltip(new Tooltip("The columns of numbers drawn up Y"))

  private val splitBox = new ComboBox[String]()
  splitBox.getStyleClass.add(Styles.SMALL)
  splitBox.setTooltip(new Tooltip("A column whose values each draw a series of their own"))
  // A split draws one Y column's values, so it keeps the first Y alone.
  splitBox.getSelectionModel.selectedIndexProperty().addListener((_, _, i) =>
    if (!syncing && i.intValue >= 0) change { s =>
      val split = if (i.intValue == 0) None else Some(splitBox.getItems.get(i.intValue))
      s.copy(split = split, y = if (split.isDefined) s.y.take(1) else s.y)
    })

  private val saveButton = new Button("Save PNG…", Icons.download)
  saveButton.getStyleClass.add(Styles.SMALL)
  saveButton.setTooltip(new Tooltip("Save the chart as a PNG image, at twice its size on screen"))
  saveButton.setOnAction(_ => save())
  private val savedLabel = new Label()
  savedLabel.getStyleClass.add("muted")

  private def caption(text: String): Label = {
    val l = new Label(text)
    l.getStyleClass.add("chart-caption")
    l.setMinWidth(Region.USE_PREF_SIZE)
    l
  }
  private def group(nodes: Node*): HBox = {
    val g = new HBox(6, nodes: _*)
    g.setAlignment(Pos.CENTER_LEFT)
    g
  }
  private val yGroup = group(caption("Y"), aggregationBox, ofLabel, yMenu)
  private val splitGroup = group(caption("Split by"), splitBox)
  private val choices = new FlowPane(18, 8, typeGroup, group(caption("X"), xBox), yGroup, splitGroup)
  choices.setAlignment(Pos.CENTER_LEFT)
  HBox.setHgrow(choices, Priority.ALWAYS)
  private val saveGroup = group(savedLabel, saveButton)
  saveGroup.setMinWidth(Region.USE_PREF_SIZE)
  private val toolbar = new HBox(12, choices, saveGroup)
  toolbar.setAlignment(Pos.TOP_LEFT)
  toolbar.setFillHeight(false)
  toolbar.getStyleClass.add("chart-toolbar")

  // ---- the chart ----
  private val canvas = new ChartCanvas()
  private val canvasBox = new StackPane(canvas)
  // The canvas is as large as its box, never what makes the box larger.
  canvasBox.setMinSize(0, 0)
  canvas.widthProperty().bind(canvasBox.widthProperty())
  canvas.heightProperty().bind(canvasBox.heightProperty())
  VBox.setVgrow(canvasBox, Priority.ALWAYS)

  private val notes = new Label()
  notes.getStyleClass.add("muted")
  notes.setWrapText(true)
  notes.setMinHeight(Region.USE_PREF_SIZE)

  getChildren.addAll(toolbar, canvasBox, notes)
  sync()
  draw()

  /** Chart `r`, the result of the query just run, or nothing (none ran, or
    * it failed). */
  def setResult(r: Option[MaterializedView]): Unit = {
    result = r
    spec = r.flatMap(mv => ChartSpec.fit(spec, mv.schema))
    savedLabel.setText("")
    sync()
    current = false
    if (active) draw()
  }

  /** Whether the chart shows; it is worked out only while it does. */
  def setActive(showing: Boolean): Unit = {
    active = showing
    if (active && !current) draw()
  }

  /** The choices changed by `f`, as the result's columns allow. */
  private def change(f: ChartSpec => ChartSpec): Unit = for (s <- spec; mv <- result) {
    spec = ChartSpec.fit(Some(f(s)), mv.schema)
    sync()
    current = false
    if (active) draw()
  }

  /** Put the choices where the spec says, or switch them off when there is
    * nothing to chart. */
  private def sync(): Unit = {
    syncing = true
    try {
      val schema = result.map(_.schema)
      val disabled = spec.isEmpty
      Seq(typeGroup, xBox, aggregationBox, yMenu, splitBox).foreach(_.setDisable(disabled))
      if (disabled) {
        xBox.getItems.clear()
        yMenu.getItems.clear()
        yMenu.setText("")
        splitBox.getItems.clear()
      }
      spec.foreach { s =>
        val fields = schema.get.fields
        typeButtons.find(_.getUserData == s.chartType).foreach(_.setSelected(true))
        xBox.getItems.setAll(fields.filter(f => ChartSpec.canBeX(f.dataType)).map(_.name): _*)
        xBox.setValue(s.x)
        aggregationBox.getSelectionModel.select(Aggregation.all.indexOf(s.aggregation))
        // A scatter draws every row as it is: nothing combines.
        val combines = s.chartType != ChartType.Scatter
        Seq(aggregationBox, ofLabel).foreach { n =>
          n.setVisible(combines)
          n.setManaged(combines)
        }
        val numbers = fields.filter(f => ChartSpec.canBeY(f.dataType) && !f.name.equalsIgnoreCase(s.x)).map(_.name)
        yMenu.getItems.setAll(numbers.map(n => yItem(n, s)): _*)
        yMenu.setText(if (s.measures.isEmpty) "Choose…" else s.measures.mkString(", "))
        val splits = fields.filter(f => ChartSpec.canBeX(f.dataType) && !f.name.equalsIgnoreCase(s.x) &&
          !s.y.exists(_.equalsIgnoreCase(f.name))).map(_.name)
        splitBox.getItems.setAll(("None" +: splits): _*)
        splitBox.getSelectionModel.select(s.split.map(sp => splits.indexOf(sp) + 1).getOrElse(0))
        splitGroup.setDisable(s.chartType == ChartType.Pie)
      }
    } finally syncing = false
  }

  /** A Y column's box in the menu. Checking one adds it, or, where one
    * column is all that is drawn (a pie, or a split), takes its place; the
    * last one stays checked. */
  private def yItem(name: String, s: ChartSpec): CustomMenuItem = {
    val box = new CheckBox(name)
    box.setMnemonicParsing(false)
    box.setSelected(s.measures.contains(name))
    box.setDisable(!box.isSelected && !s.singleY && s.measures.size >= ChartData.MaxSeries)
    box.setOnAction(_ => {
      val now =
        if (!box.isSelected) s.measures.filterNot(_ == name)
        else if (s.singleY) Seq(name)
        else s.measures :+ name
      if (now.isEmpty) box.setSelected(true) else change(_.copy(y = now))
    })
    val item = new CustomMenuItem(box)
    item.setHideOnClick(false)
    item
  }

  /** Work the chart out again, or say why there is none. */
  private def draw(): Unit = {
    current = true
    val t = token.incrementAndGet()
    (result, spec) match {
      case (None, _) =>
        canvas.showMessage("No result yet", "Run a query, and its rows are drawn here.")
        showNotes("", saveable = false)
      case (Some(_), None) =>
        canvas.showMessage("Nothing to chart",
          "A chart draws a column of numbers along another column, and this result has no such pair.",
          "Add one to the query, such as COUNT(*) with a GROUP BY.")
        showNotes("", saveable = false)
      case (Some(mv), Some(s)) =>
        val worker = new Thread(() => {
          val built =
            try ChartData.build(mv.schema, s, ChartPane.batches(mv))
            catch { case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.toString)) }
          FxHelpers.onFx {
            if (token.get() == t) built match {
              case Right(d) =>
                canvas.show(d)
                showNotes((s"${ChartScale.value(d.rows.toDouble)} ${if (d.rows == 1) "row" else "rows"}" +: d.notes)
                  .mkString(" · "), saveable = true)
              case Left(err) =>
                canvas.showMessage("Nothing to draw", err)
                showNotes("", saveable = false)
            }
          }
        }, "transformer-gui-chart")
        worker.setDaemon(true)
        worker.start()
    }
  }

  private def showNotes(text: String, saveable: Boolean): Unit = {
    notes.setText(text)
    notes.setVisible(text.nonEmpty)
    notes.setManaged(text.nonEmpty)
    saveButton.setDisable(!saveable)
  }

  // ==================== saving ====================

  private def save(): Unit = canvas.chart.foreach { d =>
    val chooser = new FileChooser()
    chooser.setTitle("Save the chart as a PNG")
    chooser.getExtensionFilters.add(new FileChooser.ExtensionFilter("PNG image", "*.png"))
    chooser.setInitialFileName(ChartPane.fileName(d))
    ChartPane.lastDirectory.filter(_.isDirectory).foreach(chooser.setInitialDirectory)
    Option(chooser.showSaveDialog(owner())).foreach { chosen =>
      val file =
        if (chosen.getName.toLowerCase(Locale.ROOT).endsWith(".png")) chosen
        else new File(chosen.getParentFile, chosen.getName + ".png")
      ChartPane.lastDirectory = Option(file.getParentFile)
      val image = ChartPane.Pixels(canvas.image(2.0))
      savedLabel.setText("Saving…")
      val worker = new Thread(() => {
        val outcome =
          try Right(ChartPane.writePng(image, file))
          catch { case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.toString)) }
        FxHelpers.onFx(outcome match {
          case Right(_) =>
            savedLabel.setText(s"Saved ${file.getName}")
            savedLabel.setTooltip(new Tooltip(file.getAbsolutePath))
          case Left(err) =>
            savedLabel.setText("")
            FxHelpers.showError(owner(), "Could not save the chart", s"$file: $err")
        })
      }, "transformer-gui-chart-save")
      worker.setDaemon(true)
      worker.start()
    }
  }
}

object ChartPane {

  /** The folder the last PNG went to, where the next one's chooser opens. */
  private var lastDirectory: Option[File] = None

  private def batches(mv: MaterializedView): Iterator[ColumnarBatch] =
    Iterator.range(0, mv.numPartitions).flatMap(mv.readPartition)

  /** A file name for a chart, after what it draws: `revenue_by_month.png`. */
  def fileName(d: ChartData): String = {
    val what = if (d.yTitle.nonEmpty) d.yTitle else d.series.map(_.name).mkString("_")
    val name = s"${what}_by_${d.xTitle}".replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("_+", "_")
      .stripPrefix("_").stripSuffix("_")
    s"${if (name.isEmpty) "chart" else name.take(80)}.png"
  }

  /** An image's pixels, a row after another, each as ARGB. */
  final case class Pixels(width: Int, height: Int, argb: Array[Int])

  object Pixels {
    /** `image`'s pixels, read on the FX thread. */
    def apply(image: WritableImage): Pixels = {
      val w = image.getWidth.toInt
      val h = image.getHeight.toInt
      val argb = new Array[Int](w * h)
      image.getPixelReader.getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance, argb, 0, w)
      Pixels(w, h, argb)
    }
  }

  /** Write `image` to `file` as a PNG, on any thread. */
  def writePng(image: Pixels, file: File): Unit = {
    val out = new BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
    out.setRGB(0, 0, image.width, image.height, image.argb, 0, image.width)
    if (!ImageIO.write(out, "png", file)) throw new IOException("this Java has no PNG writer")
  }

  private def icon(t: ChartType): Node = t match {
    case ChartType.Bar => Icons.barChart
    case ChartType.Line => Icons.lineChart
    case ChartType.Area => Icons.areaChart
    case ChartType.Scatter => Icons.scatterChart
    case ChartType.Pie => Icons.pieChart
  }
}
