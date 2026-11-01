package com.transformer.gui

import com.transformer.core.{ColumnVector, ColumnarBatch, DataType, Schema}

import java.time.{Instant, LocalDate}
import java.util.Locale
import scala.collection.mutable

/** A kind of chart a query's result can be drawn as. */
sealed abstract class ChartType(val title: String)

object ChartType {
  case object Bar extends ChartType("Bar")
  case object Line extends ChartType("Line")
  case object Area extends ChartType("Area")
  case object Scatter extends ChartType("Scatter")
  case object Pie extends ChartType("Pie")

  val all: Seq[ChartType] = Seq(Bar, Line, Area, Scatter, Pie)
}

/** How the rows that share an X, in one series, make one value: as SQL's
  * aggregate of that name makes it, NULLs left out. */
sealed abstract class Aggregation(val title: String, val sql: String)

object Aggregation {
  case object Sum extends Aggregation("Sum", "SUM")
  case object Average extends Aggregation("Average", "AVG")
  case object Min extends Aggregation("Min", "MIN")
  case object Max extends Aggregation("Max", "MAX")
  case object Count extends Aggregation("Count", "COUNT")

  val all: Seq[Aggregation] = Seq(Sum, Average, Min, Max, Count)
}

/** What to draw from a result: the type, the column along X, the columns
  * of numbers up Y, how rows sharing an X combine, and a column whose values
  * split the rows into a series each. Columns are named, so a spec outlives
  * the result it was made for ([[ChartSpec.fit]]). */
final case class ChartSpec(
    chartType: ChartType,
    x: String,
    y: Seq[String],
    aggregation: Aggregation,
    split: Option[String]) {

  /** Whether one Y column is all it draws: a pie's slices are one column's,
    * and a split's series are one column's values split. */
  def singleY: Boolean = chartType == ChartType.Pie || split.isDefined

  /** The Y columns drawn: one when [[singleY]], else as many as there are
    * colours for series. */
  def measures: Seq[String] = y.take(if (singleY) 1 else ChartData.MaxSeries)

  /** The column the series are split by; a pie splits nothing. */
  def splitDrawn: Option[String] = split.filter(_ => chartType != ChartType.Pie)
}

object ChartSpec {

  /** Whether a column of type `t` can run along X, or split the rows into
    * series: anything but bytes. */
  def canBeX(t: DataType): Boolean = t != DataType.BinaryType && t != DataType.NullType

  /** Whether a column of type `t` can be drawn up Y: a number. */
  def canBeY(t: DataType): Boolean = DataType.isNumeric(t)

  /** `spec` made to fit `schema`, or the spec `schema` suggests when there is
    * none: what still names a column of a type it can draw is kept, by name
    * in any case, and the rest is chosen as for a new result; the type is
    * kept while X is, and goes with X when X is chosen again. None when
    * `schema` has no column of numbers to draw along another column.
    *
    * A new result's X is its first date or timestamp, else its first text,
    * else its first true/false, else its first number; its Y the first number
    * after X (else before it); its type a line along a date, a time or a
    * number, else a bar per value; its rows sharing an X summed. */
  def fit(spec: Option[ChartSpec], schema: Schema): Option[ChartSpec] = {
    val types = schema.fields.map(_.dataType)
    def column(name: String, ok: DataType => Boolean): Option[Int] =
      Some(schema.indexOf(name)).filter(i => i >= 0 && ok(types(i)))
    val numbers = types.indices.filter(i => canBeY(types(i)))
    val alongX = types.indices.filter(i => canBeX(types(i)))
    if (!alongX.exists(x => numbers.exists(_ != x))) None
    else {
      def first(p: DataType => Boolean): Option[Int] = types.indices.find(i => p(types(i)))
      val suggestedX = first(t => t == DataType.DateType || t == DataType.TimestampType)
        .orElse(first(_ == DataType.StringType))
        .orElse(first(_ == DataType.BooleanType))
        .getOrElse(numbers.head)
      val keptX = spec.flatMap(s => column(s.x, canBeX))
      val x = keptX.getOrElse(suggestedX)
      val kept = spec.toSeq.flatMap(_.y).flatMap(column(_, canBeY)).filter(_ != x).distinct
      val y = if (kept.nonEmpty) kept else numbers.find(_ > x).orElse(numbers.find(_ != x)).toSeq
      val split = spec.flatMap(_.split).flatMap(column(_, canBeX)).filter(s => s != x && !y.contains(s))
      val chartType = spec.filter(_ => keptX.isDefined).map(_.chartType).getOrElse(types(x) match {
        case DataType.StringType | DataType.BooleanType => ChartType.Bar
        case _ => ChartType.Line
      })
      def name(i: Int) = schema.fields(i).name
      Some(ChartSpec(chartType, name(x), y.map(name), spec.map(_.aggregation).getOrElse(Aggregation.Sum),
        split.map(name)))
    }
  }
}

/** Where a chart's X values sit. */
sealed trait XScale

object XScale {

  /** A band per value, in order. `lastIsOther` when the last band is every
    * value past the first seven, combined. */
  final case class Categories(labels: IndexedSeq[String], lastIsOther: Boolean = false) extends XScale

  /** Numbers along a line. */
  case object Numbers extends XScale

  /** Dates (`dateOnly`) or timestamps along a line, as milliseconds since
    * 1970, in UTC. */
  final case class Time(dateOnly: Boolean) extends XScale
}

/** A point of a series: `x` a band's index on [[XScale.Categories]], else
  * the value along X; `y` the value up Y. */
final case class ChartPoint(x: Double, y: Double)

/** A series as the legend names it, its points in X order. `other` when it
  * is every split value past the first seven, combined. */
final case class ChartSeries(name: String, points: IndexedSeq[ChartPoint], other: Boolean = false)

/** A chart worked out from a result: its type and X scale, the titles of
  * its axes, its series, how many rows the result had, and what of it is
  * not drawn, a sentence each. */
final case class ChartData(
    chartType: ChartType,
    xScale: XScale,
    xTitle: String,
    yTitle: String,
    series: IndexedSeq[ChartSeries],
    rows: Long,
    notes: Seq[String])

/** Builds a [[ChartData]] from a result's rows, read once, a batch at a time.
  *
  * A bar, a line, an area or a pie draws one value per X and series: the
  * rows sharing them combined by the spec's aggregation, over every row of
  * the result. Text along X keeps the order the result has it in (its
  * ORDER BY, if any); anything else is sorted, and NULL comes last. A bar
  * or a pie has a band per value of X, whatever its type; a line or an area
  * runs along a number, a date or a timestamp as along an axis, and a row
  * whose X is NULL is left out. A scatter draws a dot per row, uncombined.
  *
  * Up to [[MaxSeries]] series are drawn, each Y column or each value of the
  * split column one; past that the seven largest are drawn and the rest
  * combined as Other, as a pie's slices past seven are. A bar chart draws
  * at most [[MaxBars]] bands, a line or an area at most [[MaxPoints]] values
  * of X and a scatter the first [[MaxPoints]] rows, and says so in a note.
  * A value that is NaN or infinite is left out, and counted in a note. */
object ChartData {

  /** The most series a chart draws: there are eight colours for them. */
  val MaxSeries: Int = 8
  val MaxBars: Int = 500
  val MaxPoints: Int = 10000

  def build(schema: Schema, spec: ChartSpec, batches: Iterator[ColumnarBatch]): Either[String, ChartData] = {
    val xi = schema.indexOf(spec.x)
    val yis = spec.measures.map(schema.indexOf)
    val si = spec.splitDrawn.map(schema.indexOf)
    val missing = (Seq(spec.x) ++ spec.measures ++ spec.splitDrawn).find(schema.indexOf(_) < 0)
    if (yis.isEmpty) Left("Choose a column of numbers to draw up Y.")
    else if (missing.isDefined) Left(s"The result has no column ${missing.get}.")
    else new Builder(schema, spec, xi, yis, si).build(batches)
  }

  /** What the rows sharing an X in one series hold: how many rows, how many
    * values (not NULL), their sum, the least and the greatest. */
  private final class Acc {
    var rows = 0L
    var count = 0L
    var sum = 0.0
    var min = Double.PositiveInfinity
    var max = Double.NegativeInfinity

    def add(v: Double): Unit = {
      count += 1
      sum += v
      if (v < min) min = v
      if (v > max) max = v
    }

    def merge(o: Acc): Unit = {
      rows += o.rows
      count += o.count
      sum += o.sum
      min = math.min(min, o.min)
      max = math.max(max, o.max)
    }

    def value(agg: Aggregation): Option[Double] = agg match {
      case Aggregation.Count => Some(count.toDouble)
      case _ if count == 0 => None
      case Aggregation.Sum => Some(sum).filter(_.isFinite)
      case Aggregation.Average => Some(sum / count).filter(_.isFinite)
      case Aggregation.Min => Some(min)
      case Aggregation.Max => Some(max)
    }
  }

  /** NULL as a key, which sorts after every value. */
  private case object NullKey

  /** One chart's working: the rows read once into what each X and series
    * hold, then those made into series. `xi`, `yis` and `si` are the
    * positions of the X, Y and split columns. */
  private final class Builder(schema: Schema, spec: ChartSpec, xi: Int, yis: Seq[Int], si: Option[Int]) {
    private val xType = schema.fields(xi).dataType
    private val xName = schema.fields(xi).name
    private val yNames = yis.map(schema.fields(_).name)
    private val splitName = si.map(schema.fields(_).name)
    private val alongAxis = spec.chartType match {
      case ChartType.Bar | ChartType.Pie => false
      case _ => DataType.isNumeric(xType) || xType == DataType.DateType || xType == DataType.TimestampType
    }

    private var rows = 0L
    private var nullX = 0L
    private var notFinite = 0L

    // Values of X, and of the split column, in the order they first come.
    private val xKeys = mutable.LinkedHashMap.empty[Any, Int]
    private val splitKeys = mutable.LinkedHashMap.empty[Any, Int]
    // (X, series) -> what their rows hold; a series is a Y column's position,
    // or a split value's.
    private val accs = mutable.HashMap.empty[(Int, Int), Acc]
    // A scatter's dots: (X, series, value), the first MaxPoints rows' only.
    private val dots = mutable.ArrayBuffer.empty[(Int, Int, Double)]
    /** Rows read into the chart, which a scatter stops at MaxPoints. */
    private var taken = 0L

    def build(batches: Iterator[ColumnarBatch]): Either[String, ChartData] = {
      batches.foreach(read)
      if (rows == 0) Left("The query returned no rows.")
      else if (spec.chartType == ChartType.Scatter) scatter()
      else combined()
    }

    private def read(b: ColumnarBatch): Unit = {
      val xc = b.column(xi)
      val ycs = yis.map(b.column)
      val sc = si.map(b.column)
      var r = 0
      while (r < b.numRows) {
        rows += 1
        val x = key(value(xc, r))
        if (alongAxis && x == NullKey) nullX += 1
        else if (alongAxis && !position(x).isFinite) notFinite += 1
        else if (spec.chartType != ChartType.Scatter || taken < MaxPoints) {
          val xIndex = xKeys.getOrElseUpdate(x, xKeys.size)
          val splitIndex = sc.map(c => splitKeys.getOrElseUpdate(key(value(c, r)), splitKeys.size))
          taken += 1
          var m = 0
          while (m < ycs.length) {
            val series = splitIndex.getOrElse(m)
            val y = ycs(m)
            val v = Option(value(y, r)).map(_.asInstanceOf[Number].doubleValue)
            v.foreach(d => if (!d.isFinite) notFinite += 1)
            if (spec.chartType == ChartType.Scatter) v.filter(_.isFinite).foreach(d => dots += ((xIndex, series, d)))
            else {
              val acc = accs.getOrElseUpdate((xIndex, series), new Acc)
              acc.rows += 1
              v.filter(_.isFinite).foreach(acc.add)
            }
            m += 1
          }
        }
        r += 1
      }
    }

    private def value(c: ColumnVector, r: Int): Any = if (c.isNull(r)) null else c.getBoxed(r)

    /** A value as a key: NULL as [[NullKey]], -0.0 as 0.0 and a decimal
      * without trailing zeros, so values that are equal in SQL are one. */
    private def key(v: Any): Any = v match {
      case null => NullKey
      case d: java.lang.Double if d.doubleValue == 0.0 => java.lang.Double.valueOf(0.0)
      case f: java.lang.Float if f.floatValue == 0.0f => java.lang.Float.valueOf(0.0f)
      case d: java.math.BigDecimal => d.stripTrailingZeros
      case other => other
    }

    /** Where a value of X sits along an axis: a number as itself, a date or a
      * timestamp as milliseconds since 1970. */
    private def position(x: Any): Double = x match {
      case d: LocalDate => d.toEpochDay * 86400000.0
      case i: Instant => i.getEpochSecond * 1000.0 + i.getNano / 1e6
      case d: java.math.BigDecimal => d.doubleValue
      case n: Number => n.doubleValue
      case _ => Double.NaN
    }

    private def label(v: Any): String = v match {
      case NullKey => "NULL"
      case "" => "(empty)"
      case s: String => s
      case d: java.math.BigDecimal => d.toPlainString
      case other => String.valueOf(other)
    }

    /** `keys` (with their first-come indices) in the order a chart shows
      * them: text as it came, anything else sorted, NULL last. */
    private def ordered(keys: mutable.LinkedHashMap[Any, Int], t: DataType): IndexedSeq[(Any, Int)] = {
      val all = keys.toIndexedSeq
      val (nulls, values) = all.partition(_._1 == NullKey)
      val sorted = if (t == DataType.StringType) values else values.sortWith { case ((a, _), (b, _)) => before(a, b) }
      sorted ++ nulls
    }

    private def before(a: Any, b: Any): Boolean = (a, b) match {
      case (x: Comparable[_], y) if x.getClass == y.getClass => x.asInstanceOf[Comparable[Any]].compareTo(y) < 0
      case _ => label(a) < label(b)
    }

    private def baseNotes: Seq[String] =
      (if (nullX > 0) Seq(s"${count(nullX)} ${if (nullX == 1) "row whose" else "rows whose"} $xName is NULL " +
        s"${if (nullX == 1) "is" else "are"} left out") else Nil) ++
        (if (notFinite > 0) Seq(s"${count(notFinite)} ${if (notFinite == 1) "value that is" else "values that are"} " +
          s"NaN or infinite ${if (notFinite == 1) "is" else "are"} left out") else Nil)

    /** A bar, a line, an area or a pie: one value per X and series. */
    private def combined(): Either[String, ChartData] = {
      val agg = spec.aggregation
      val notes = mutable.ArrayBuffer.empty[String] ++= baseNotes
      val most = spec.chartType match {
        case ChartType.Bar => MaxBars
        case ChartType.Pie => Int.MaxValue
        case _ => MaxPoints
      }
      val allXs = ordered(xKeys, xType)
      if (allXs.size > most)
        notes += s"Only the first ${count(most)} of ${count(allXs.size)} values of $xName are drawn"
      val xs = allXs.take(most)
      val wasCombined = accs.valuesIterator.exists(_.rows > 1)
      def measureTitle(name: String) =
        if (wasCombined || agg == Aggregation.Count) s"${agg.sql}($name)" else name

      val keys = seriesKeys(measureTitle)
      // A series' value over all its rows ranks it, for a fold into Other.
      val totals = mutable.HashMap.empty[Int, Acc]
      accs.foreach { case ((_, s), a) => totals.getOrElseUpdate(s, new Acc).merge(a) }
      val series = fold(keys, ks => {
        val acc = new Acc
        ks.foreach(s => totals.get(s).foreach(acc.merge))
        acc.value(agg).map(math.abs).getOrElse(0.0)
      })
      if (series.size < keys.size)
        notes += s"${splitName.get} has ${count(keys.size)} values: the ${MaxSeries - 1} largest are drawn, " +
          "the rest as Other"

      def acc(x: Int, s: IndexedSeq[Int]): Acc =
        if (s.size == 1) accs.getOrElse((x, s.head), new Acc)
        else {
          val a = new Acc
          s.foreach(i => accs.get((x, i)).foreach(a.merge))
          a
        }

      if (spec.chartType == ChartType.Pie) {
        // A pie's one series is its one Y column, series 0.
        val title = measureTitle(yNames.head)
        val slices = fold(xs.map { case (k, i) => label(k) -> IndexedSeq(i) },
          ks => ks.flatMap(x => acc(x, IndexedSeq(0)).value(agg)).sum.abs)
        if (slices.size < xs.size)
          notes += s"$xName has ${count(xs.size)} values: the ${MaxSeries - 1} largest are drawn, the rest as Other"
        val values = slices.map { case (name, ks, _) =>
          val a = new Acc
          ks.foreach(x => a.merge(acc(x, IndexedSeq(0))))
          name -> a.value(agg)
        }
        val below = values.count(_._2.exists(_ < 0))
        if (below > 0)
          Left(s"A pie can't draw values below 0, and $title is below 0 for $below of the ${values.size} values of " +
            s"$xName. A bar chart can.")
        else {
          val drawn = values.zipWithIndex.collect { case ((name, Some(v)), i) if v > 0 => (name, v, i) }
          if (drawn.isEmpty) Left(s"Nothing to draw: $title is 0 or NULL for every value of $xName.")
          else {
            val other = slices.last._3 && drawn.last._3 == slices.size - 1
            Right(ChartData(ChartType.Pie, XScale.Categories(drawn.map(_._1), lastIsOther = other), xName, title,
              IndexedSeq(ChartSeries(title, drawn.indices.map(i => ChartPoint(i, drawn(i)._2)))), rows, notes.toSeq))
          }
        }
      } else {
        val drawn = series.map { case (name, ks, other) =>
          val points = xs.indices.flatMap { p =>
            val (k, x) = xs(p)
            acc(x, ks).value(agg).map(v => ChartPoint(if (alongAxis) position(k) else p, v))
          }
          ChartSeries(name, points, other)
        }
        if (drawn.forall(_.points.isEmpty)) Left(nothingToDraw)
        else Right(ChartData(spec.chartType, scale(xs), xName, yTitle(measureTitle), drawn, rows, notes.toSeq))
      }
    }

    /** A scatter: a dot per row and Y column, uncombined. */
    private def scatter(): Either[String, ChartData] = {
      val notes = mutable.ArrayBuffer.empty[String] ++= baseNotes
      val counted = rows - nullX
      if (counted > MaxPoints) notes += s"Only the first ${count(MaxPoints)} of ${count(counted)} rows are drawn"
      // Text along X takes a band per value, in the order they come; the
      // dots' X are their bands' places in that order.
      val xs = if (alongAxis) IndexedSeq.empty else ordered(xKeys, xType)
      val xPositions: Map[Int, Double] =
        if (alongAxis) xKeys.iterator.map { case (k, i) => i -> position(k) }.toMap
        else xs.indices.map(p => xs(p)._2 -> p.toDouble).toMap
      val keys = seriesKeys(identity)
      val counts = dots.groupMapReduce(_._2)(_ => 1L)(_ + _)
      val series = fold(keys, ks => ks.map(counts.getOrElse(_, 0L)).sum.toDouble)
      if (series.size < keys.size)
        notes += s"${splitName.get} has ${count(keys.size)} values: the ${MaxSeries - 1} with the most rows " +
          "are drawn, the rest as Other"
      val seriesOf = series.indices.flatMap(i => series(i)._2.map(_ -> i)).toMap
      val points = Array.fill(series.size)(IndexedSeq.newBuilder[ChartPoint])
      dots.foreach { case (x, s, v) => points(seriesOf(s)) += ChartPoint(xPositions(x), v) }
      val drawn = series.indices.map(i => ChartSeries(series(i)._1, points(i).result(), series(i)._3))
      if (drawn.forall(_.points.isEmpty)) Left(nothingToDraw)
      else Right(ChartData(ChartType.Scatter, scale(xs), xName, yTitle(identity), drawn, rows, notes.toSeq))
    }

    /** The series as they come: each Y column, titled by `title`, or each
      * value of the split column. Each is its name and its series index. */
    private def seriesKeys(title: String => String): IndexedSeq[(String, IndexedSeq[Int])] = si match {
      case None => yNames.indices.map(m => title(yNames(m)) -> IndexedSeq(m))
      case Some(s) => ordered(splitKeys, schema.fields(s).dataType).map { case (k, i) => label(k) -> IndexedSeq(i) }
    }

    /** X's scale: a band per value of `xs` off an axis, else the axis. */
    private def scale(xs: IndexedSeq[(Any, Int)]): XScale =
      if (!alongAxis) XScale.Categories(xs.map(k => label(k._1)))
      else if (DataType.isNumeric(xType)) XScale.Numbers
      else XScale.Time(dateOnly = xType == DataType.DateType)

    /** A count as a note writes it, the same in every locale: `10,000`. */
    private def count(n: Long): String = String.format(Locale.ROOT, "%,d", Long.box(n))

    private def nothingToDraw: String =
      s"Nothing to draw: in every row, ${yNames.mkString(" and ")} ${if (yNames.size == 1) "is" else "are"} " +
        s"NULL or not a number${if (alongAxis && nullX > 0) s", or $xName is NULL" else ""}."

    /** Y's title: its one column's, titled by `title`; none when the legend
      * names the columns. */
    private def yTitle(title: String => String): String = if (yNames.size == 1) title(yNames.head) else ""

    /** `keys` as drawn: all of them when there are [[MaxSeries]] or fewer,
      * else the seven that `size` ranks largest, in their order, then the
      * rest as Other. Each is (name, its indices, whether it is Other). */
    private def fold(keys: IndexedSeq[(String, IndexedSeq[Int])],
        size: IndexedSeq[Int] => Double): IndexedSeq[(String, IndexedSeq[Int], Boolean)] =
      if (keys.size <= MaxSeries) keys.map { case (n, ks) => (n, ks, false) }
      else {
        val largest = keys.indices.sortBy(i => -size(keys(i)._2)).take(MaxSeries - 1).toSet
        val (kept, rest) = keys.indices.partition(largest)
        kept.map(i => (keys(i)._1, keys(i)._2, false)) :+ (("Other", rest.flatMap(keys(_)._2), true))
      }
  }
}
