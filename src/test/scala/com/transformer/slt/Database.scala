package com.transformer.slt

import com.transformer.core._

import java.util.Locale
import scala.collection.mutable

/** The tables a script builds with `statement ok`, held as rows.
  *
  * The engine runs queries only; it has no DDL or DML. A script's
  * `CREATE TABLE`, `INSERT`, `DELETE`, `UPDATE`, `CREATE VIEW` and `DROP` are
  * carried out here, and a query reads the tables through a catalog built for
  * whichever layout a verification mode asks for ([[Modes]]). An `INSERT`'s
  * values are read here when they are literals; anything else in a `VALUES`
  * list, an `INSERT ... SELECT`, a `DELETE`'s and an `UPDATE`'s condition, and
  * a view's body are evaluated by the engine, so a defect there shows up as a
  * setup failure, which the runner reports.
  *
  * Column types follow the [[Dialect]]: [[Dialect.Transformer]] reads a type
  * name as a CAST does, and [[Dialect.Sqlite]] reads SQLite's corpus as SQLite
  * stores it (`INTEGER` a 64-bit BIGINT, `FLOAT` and `REAL` a DOUBLE, `TEXT` and
  * `VARCHAR(n)` a STRING), with its runs of signs folded ([[Dialect.prepare]]).
  */
final class Database(dialect: Dialect) {

  import Database._

  private val tables = mutable.LinkedHashMap.empty[String, Table]
  /** Each index's table. An index changes no answer, so only its name is
    * kept, for the statements that name it. */
  private val indexes = mutable.HashMap.empty[String, String]
  private var nextVersion = 0L

  def allTables: Seq[Table] = tables.values.toSeq

  /** Carry out one statement. Throws [[Unsupported]] for a statement this
    * runner cannot carry out, and any other exception when the statement
    * fails (a query the engine refuses, a value the column cannot hold, a
    * table that does not exist). */
  def execute(sql: String, query: String => (Schema, Vector[Array[Any]])): Unit = {
    val text = stripSemicolon(sql.trim)
    val t = Lexer.tokens(text)
    def word(i: Int): String = t.lift(i).map(_.upper).getOrElse("")
    word(0) match {
      case "CREATE" =>
        var i = 1
        if (word(i) == "TEMP" || word(i) == "TEMPORARY") i += 1
        word(i) match {
          case "TABLE" => createTable(text, t, i + 1, query)
          case "VIEW" => createView(text, t, i + 1, query)
          case "INDEX" | "UNIQUE" => createIndex(t, i)
          case "TRIGGER" => throw new Unsupported("CREATE TRIGGER")
          case other => throw new Unsupported(s"CREATE $other")
        }
      case "DROP" =>
        val kind = word(1)
        var i = 2
        val ifExists = word(i) == "IF" && word(i + 1) == "EXISTS"
        if (ifExists) i += 2
        val name = t.lift(i).map(_.name).getOrElse(throw new IllegalArgumentException("DROP names nothing"))
        kind match {
          case "TABLE" | "VIEW" =>
            tables.get(key(name)) match {
              case Some(tb) if tb.isView == (kind == "VIEW") =>
                tables.remove(key(name))
                indexes.filterInPlace { case (_, on) => on != key(name) }
              case _ if ifExists => ()
              case _ => throw new IllegalArgumentException(s"no such ${kind.toLowerCase(Locale.ROOT)}: $name")
            }
          case "INDEX" =>
            if (indexes.remove(key(name)).isEmpty && !ifExists) throw new IllegalArgumentException(s"no such index: $name")
          case other => throw new Unsupported(s"DROP $other")
        }
      case "INSERT" => insert(text, t, query)
      case "DELETE" => delete(text, t, query)
      case "UPDATE" => update(text, t, query)
      case "SELECT" | "WITH" | "VALUES" => query(text); ()
      case "REINDEX" =>
        t.lift(1).map(_.name).foreach { n =>
          if (!indexes.contains(key(n)) && !tables.contains(key(n)))
            throw new IllegalArgumentException(s"no such index or table: $n")
        }
      case "ANALYZE" | "VACUUM" | "BEGIN" | "COMMIT" | "END" => ()
      case other => throw new Unsupported(s"$other statement")
    }
  }

  // ---- CREATE ----------------------------------------------------------------

  private def createTable(text: String, t: Vector[Token], start: Int, query: String => (Schema, Vector[Array[Any]])): Unit = {
    var i = start
    val ifNotExists = t.lift(i).exists(_.upper == "IF") && t.lift(i + 1).exists(_.upper == "NOT")
    if (ifNotExists) i += 3
    val name = t(i).name
    if (tables.contains(key(name))) {
      if (ifNotExists) return
      throw new IllegalArgumentException(s"table $name already exists")
    }
    i += 1
    if (t.lift(i).exists(_.upper == "AS")) {
      val (schema, rows) = query(text.substring(t(i + 1).start))
      put(Table(name, schema, rows, isView = false, version = 0))
      return
    }
    if (!t.lift(i).exists(_.text == "(")) throw new IllegalArgumentException(s"CREATE TABLE $name has no column list")
    val close = matching(t, i)
    val fields = Lexer.splitCommas(t.slice(i + 1, close)).flatMap(columnDefinition)
    if (fields.isEmpty) throw new IllegalArgumentException(s"CREATE TABLE $name has no columns")
    Schema.repeatedName(fields.map(_.name)).foreach { _ =>
      throw new IllegalArgumentException(s"CREATE TABLE $name repeats a column name")
    }
    put(Table(name, Schema(fields.toVector), Vector.empty, isView = false, version = 0))
  }

  /** A column definition's name and type, or None for a table constraint. */
  private def columnDefinition(defn: Vector[Token]): Option[Field] = {
    if (defn.isEmpty) throw new IllegalArgumentException("an empty column definition")
    if (TableConstraints.contains(defn.head.upper)) return None
    val name = defn.head.name
    val typeTokens = defn.drop(1).takeWhile(tk => !ColumnConstraints.contains(tk.upper))
    val typeText = typeTokens.map(_.text).mkString(" ").replace(" (", "(").replace("( ", "(").replace(" )", ")").replace(" ,", ",").replace(", ", ",")
    Some(Field(name, dialect.columnType(typeText)))
  }

  private def createView(text: String, t: Vector[Token], start: Int, query: String => (Schema, Vector[Array[Any]])): Unit = {
    var i = start
    val ifNotExists = t.lift(i).exists(_.upper == "IF") && t.lift(i + 1).exists(_.upper == "NOT")
    if (ifNotExists) i += 3
    val name = t(i).name
    if (tables.contains(key(name))) {
      if (ifNotExists) return
      throw new IllegalArgumentException(s"view $name already exists")
    }
    i += 1
    var columnNames: Option[Seq[String]] = None
    if (t.lift(i).exists(_.text == "(")) {
      val close = matching(t, i)
      columnNames = Some(Lexer.splitCommas(t.slice(i + 1, close)).map(_.head.name))
      i = close + 1
    }
    if (!t.lift(i).exists(_.upper == "AS")) throw new IllegalArgumentException(s"CREATE VIEW $name has no AS")
    val (schema, rows) = query(text.substring(t(i + 1).start))
    val named = columnNames match {
      case None => schema
      case Some(names) =>
        if (names.length != schema.length) throw new IllegalArgumentException(s"view $name names ${names.length} columns for ${schema.length}")
        Schema(schema.fields.zip(names).map { case (f, n) => f.copy(name = n) })
    }
    put(Table(name, named, rows, isView = true, version = 0))
  }

  /** `CREATE [UNIQUE] INDEX [IF NOT EXISTS] name ON table (...)`, from the
    * token after CREATE: the name is kept, the table must exist. */
  private def createIndex(t: Vector[Token], start: Int): Unit = {
    var i = start
    if (t.lift(i).exists(_.upper == "UNIQUE")) i += 1
    i += 1 // INDEX
    val ifNotExists = t.lift(i).exists(_.upper == "IF") && t.lift(i + 1).exists(_.upper == "NOT")
    if (ifNotExists) i += 3
    val name = t.lift(i).map(_.name).getOrElse(throw new IllegalArgumentException("CREATE INDEX names no index"))
    val on = t.indexWhere(_.upper == "ON")
    if (on < 0 || on + 1 >= t.length) throw new IllegalArgumentException("CREATE INDEX names no table")
    val table = t(on + 1).name
    if (!tables.get(key(table)).exists(!_.isView)) throw new IllegalArgumentException(s"no such table: $table")
    if (indexes.contains(key(name))) { if (!ifNotExists) throw new IllegalArgumentException(s"index $name already exists") }
    else indexes(key(name)) = key(table)
  }

  // ---- INSERT ----------------------------------------------------------------

  private def insert(text: String, t: Vector[Token], query: String => (Schema, Vector[Array[Any]])): Unit = {
    var i = 1
    if (t.lift(i).exists(_.upper == "OR")) throw new Unsupported(s"INSERT OR ${t.lift(i + 1).map(_.upper).getOrElse("")}")
    if (!t.lift(i).exists(_.upper == "INTO")) throw new IllegalArgumentException("INSERT without INTO")
    i += 1
    val tb = writable(t(i).name)
    i += 1
    val targets: IndexedSeq[Int] =
      if (t.lift(i).exists(_.text == "(")) {
        val close = matching(t, i)
        val names = Lexer.splitCommas(t.slice(i + 1, close)).map(_.head.name)
        i = close + 1
        names.map { n =>
          val idx = tb.schema.indexOf(n)
          if (idx < 0) throw new IllegalArgumentException(s"table ${tb.name} has no column named $n")
          idx
        }.toIndexedSeq
      } else tb.schema.fields.indices
    if (targets.distinct.length != targets.length) throw new IllegalArgumentException("INSERT names a column twice")
    val incoming: Vector[Vector[Any]] =
      if (t.lift(i).exists(_.upper == "VALUES")) {
        val rows = Vector.newBuilder[Vector[Any]]
        var j = i + 1
        while (j < t.length) {
          if (t(j).text != "(") throw new IllegalArgumentException(s"VALUES expects '(' at '${t(j).text}'")
          val close = matching(t, j)
          val items = Lexer.splitCommas(t.slice(j + 1, close))
          if (items.length != targets.length)
            throw new IllegalArgumentException(s"${items.length} values for ${targets.length} columns")
          rows += items.zip(targets).map { case (item, c) =>
            literal(text, item, tb.schema.fields(c).dataType, query)
          }.toVector
          j = close + 1
          if (j < t.length) {
            if (t(j).text != ",") throw new IllegalArgumentException(s"unexpected '${t(j).text}' after a VALUES row")
            j += 1
          }
        }
        rows.result()
      } else if (t.lift(i).exists(tk => tk.upper == "SELECT" || tk.upper == "WITH")) {
        val (schema, rows) = query(text.substring(t(i).start))
        if (schema.length != targets.length)
          throw new IllegalArgumentException(s"${schema.length} values for ${targets.length} columns")
        rows.map(r => targets.indices.map(k => convert(r(k), schema.fields(k).dataType, tb.schema.fields(targets(k)).dataType)).toVector)
      } else throw new Unsupported("INSERT without VALUES or SELECT")
    val added = incoming.map { vals =>
      val row = new Array[Any](tb.schema.length)
      targets.indices.foreach(k => row(targets(k)) = vals(k))
      row
    }
    put(tb.copy(rows = tb.rows ++ added))
  }

  /** A VALUES item as a value of column type `to`. A literal is read here; an
    * expression is evaluated by the engine. */
  private def literal(text: String, item: Vector[Token], to: DataType,
      query: String => (Schema, Vector[Array[Any]])): Any = {
    Lexer.literal(item) match {
      case Some(v) => convert(v, Lexer.literalType(v), to)
      case None =>
        val exprText = text.substring(item.head.start, item.last.end)
        val (schema, rows) = query(s"SELECT $exprText AS v")
        convert(rows.head(0), schema.fields(0).dataType, to)
    }
  }

  /** `v`, a value of type `from`, stored in a column of type `to`. */
  def convert(v: Any, from: DataType, to: DataType): Any =
    if (v == null) null
    else dialect.store(v, from, to)

  // ---- DELETE / UPDATE ---------------------------------------------------------

  private def delete(text: String, t: Vector[Token], query: String => (Schema, Vector[Array[Any]])): Unit = {
    if (!t.lift(1).exists(_.upper == "FROM")) throw new IllegalArgumentException("DELETE without FROM")
    val tb = writable(t(2).name)
    val where = t.indexWhere(_.upper == "WHERE")
    if (where < 0) { put(tb.copy(rows = Vector.empty)); return }
    val cond = text.substring(t(where + 1).start)
    // A CASE rather than COALESCE(cond, FALSE): JSqlParser cannot parse four
    // parentheses deep inside a call, and the corpus writes `((col0 > 19))`.
    put(tb.copy(rows = keep(tb, s"CASE WHEN $cond THEN FALSE ELSE TRUE END", query)))
  }

  private def update(text: String, t: Vector[Token], query: String => (Schema, Vector[Array[Any]])): Unit = {
    val tb = writable(t(1).name)
    if (!t.lift(2).exists(_.upper == "SET")) throw new IllegalArgumentException("UPDATE without SET")
    val where = t.indexWhere(_.upper == "WHERE")
    val setTokens = t.slice(3, if (where < 0) t.length else where)
    val assignments = Lexer.splitCommas(setTokens).map { a =>
      if (a.length < 3 || a(1).text != "=") throw new IllegalArgumentException("SET expects column = value")
      val c = tb.schema.indexOf(a.head.name)
      if (c < 0) throw new IllegalArgumentException(s"table ${tb.name} has no column named ${a.head.name}")
      c -> text.substring(a(2).start, a.last.end)
    }
    val cond = if (where < 0) "TRUE" else text.substring(t(where + 1).start)
    // One query reads every row, the condition and each new value, so each
    // value is computed from the row as it was, as SQL's UPDATE does.
    val cols = tb.schema.fields.map(f => Lexer.quote(f.name)).mkString(", ")
    val newValues = assignments.map { case (_, e) => s"($e)" }.mkString(", ")
    val (schema, rows) = query(s"SELECT $cols, CASE WHEN $cond THEN TRUE ELSE FALSE END, $newValues FROM ${Lexer.quote(tb.name)}")
    val n = tb.schema.length
    val updated = rows.map { r =>
      val row = r.take(n).clone()
      if (r(n) == java.lang.Boolean.TRUE) assignments.zipWithIndex.foreach { case ((c, _), k) =>
        row(c) = convert(r(n + 1 + k), schema.fields(n + 1 + k).dataType, tb.schema.fields(c).dataType)
      }
      row
    }
    put(tb.copy(rows = updated))
  }

  /** The rows of `tb` that `predicate` keeps, in their order. */
  private def keep(tb: Table, predicate: String, query: String => (Schema, Vector[Array[Any]])): Vector[Array[Any]] = {
    val cols = tb.schema.fields.map(f => Lexer.quote(f.name)).mkString(", ")
    val (_, rows) = query(s"SELECT $cols, $predicate FROM ${Lexer.quote(tb.name)}")
    rows.filter(r => r(tb.schema.length) == java.lang.Boolean.TRUE).map(_.take(tb.schema.length))
  }

  // ---- helpers -----------------------------------------------------------------

  private def writable(name: String): Table = tables.get(key(name)) match {
    case Some(tb) if !tb.isView => tb
    case Some(_) => throw new IllegalArgumentException(s"cannot modify view $name")
    case None => throw new IllegalArgumentException(s"no such table: $name")
  }

  private def put(tb: Table): Unit = {
    nextVersion += 1
    tables(key(tb.name)) = tb.copy(version = nextVersion)
  }

  private def key(name: String): String = name.toLowerCase(Locale.ROOT)

  private def stripSemicolon(s: String): String = if (s.endsWith(";")) s.dropRight(1).trim else s

  private def matching(t: Vector[Token], open: Int): Int = {
    var depth = 0
    var i = open
    while (i < t.length) {
      if (t(i).text == "(") depth += 1
      else if (t(i).text == ")") { depth -= 1; if (depth == 0) return i }
      i += 1
    }
    throw new IllegalArgumentException("unbalanced parentheses")
  }
}

object Database {

  /** One table (or a view, held as the rows its body returned when it was
    * created). `version` changes whenever the rows do, so a mode can cache the
    * layout it built for one version. */
  final case class Table(name: String, schema: Schema, rows: Vector[Array[Any]], isView: Boolean, version: Long)

  /** A statement this runner does not carry out. */
  final class Unsupported(what: String) extends RuntimeException(s"unsupported statement: $what")

  private val TableConstraints = Set("PRIMARY", "UNIQUE", "CHECK", "FOREIGN", "CONSTRAINT")
  private val ColumnConstraints =
    Set("PRIMARY", "NOT", "NULL", "UNIQUE", "DEFAULT", "CHECK", "REFERENCES", "COLLATE", "CONSTRAINT", "AUTOINCREMENT")
}

/** How a script's column type names map to the engine's types, and how a
  * value is stored in a column. */
sealed trait Dialect {
  def columnType(name: String): DataType
  def store(v: Any, from: DataType, to: DataType): Any
  /** The SQL the engine is given for a record's text. */
  def prepare(sql: String): String = sql
}

object Dialect {

  /** This engine's own: a type name as a CAST reads it (plus `DECIMAL(p,s)`
    * and `BINARY`, which a CAST cannot name), and a value stored only in a
    * column that holds it exactly: an integer in a column of its range, any
    * number in a FLOAT or DOUBLE, a number in a DECIMAL column of the scale it
    * fits without rounding, text in a DATE or TIMESTAMP column when it reads
    * as one. */
  case object Transformer extends Dialect {
    def columnType(name: String): DataType = {
      val upper = name.toUpperCase(Locale.ROOT)
      upper.takeWhile(_ != '(') match {
        case "VARCHAR" | "CHAR" => return DataType.StringType
        case _ => ()
      }
      upper match {
        case "INTEGER" => DataType.IntType
        case "REAL" => DataType.FloatType
        case "VARCHAR" | "TEXT" | "CHAR" => DataType.StringType
        case "BOOL" => DataType.BooleanType
        case "LONG" => DataType.LongType
        case "DOUBLE PRECISION" => DataType.DoubleType
        case _ => Script.exactType(upper).getOrElse(throw new IllegalArgumentException(s"unknown column type '$name'"))
      }
    }

    def store(v: Any, from: DataType, to: DataType): Any = (v, to) match {
      case (n: Lexer.NumberText, DataType.DoubleType) => java.lang.Double.valueOf(n.double)
      case (n: Lexer.NumberText, DataType.FloatType) => java.lang.Float.valueOf(n.float)
      case (n: Lexer.NumberText, _) => store(n.decimal, from, to)
      case (n: java.lang.Number, DataType.IntType) if integral(n) =>
        val l = longOf(n)
        if (l < Int.MinValue || l > Int.MaxValue) throw new IllegalArgumentException(s"$v does not fit an INT column")
        java.lang.Integer.valueOf(l.toInt)
      case (n: java.lang.Number, DataType.LongType) if integral(n) => java.lang.Long.valueOf(longOf(n))
      case (d: java.lang.Double, DataType.DoubleType) => d
      case (f: java.lang.Float, DataType.DoubleType) => java.lang.Double.valueOf(f.doubleValue)
      case (n: java.lang.Number, DataType.DoubleType) => java.lang.Double.valueOf(decimalOf(n).map(_.doubleValue).getOrElse(n.doubleValue))
      case (f: java.lang.Float, DataType.FloatType) => f
      case (n: java.lang.Number, DataType.FloatType) => java.lang.Float.valueOf(decimalOf(n).map(_.floatValue).getOrElse(n.floatValue))
      case (n: java.lang.Number, d: DataType.DecimalType) =>
        val exact = decimalOf(n).getOrElse(throw new IllegalArgumentException(s"$v is no DECIMAL"))
        val scaled =
          try exact.setScale(d.scale, java.math.RoundingMode.UNNECESSARY)
          catch { case _: ArithmeticException => throw new IllegalArgumentException(s"$v does not fit ${Script.typeName(d)} without rounding") }
        if (scaled.precision - scaled.scale > d.precision - d.scale && scaled.signum != 0)
          throw new IllegalArgumentException(s"$v does not fit ${Script.typeName(d)}")
        scaled
      case (s: String, DataType.StringType) => s
      case (s: String, DataType.DateType) => java.time.LocalDate.parse(s)
      case (s: String, DataType.TimestampType) => Lexer.instant(s)
      case (b: java.lang.Boolean, DataType.BooleanType) => b
      case (d: java.time.LocalDate, DataType.DateType) => d
      case (i: java.time.Instant, DataType.TimestampType) => i
      case (b: Array[Byte], DataType.BinaryType) => b
      case _ => throw new IllegalArgumentException(s"a ${Script.typeName(from)} value ${Values.exact(v)} cannot be stored in a ${Script.typeName(to)} column")
    }
  }

  /** SQLite's corpus as SQLite stores it. Integers are 64-bit, reals are
    * doubles, and a value converts to its column's type as SQLite's type
    * affinity converts it where the result is exact (an integral real in an
    * INTEGER column, a number in a TEXT column); a value SQLite would store
    * under another type (a fraction in an INTEGER column, text in a numeric
    * one) cannot be held, and the file is reported unsupported. */
  case object Sqlite extends Dialect {
    /** Runs of signs folded ([[Lexer.foldSigns]]), which JSqlParser cannot
      * parse and the corpus writes everywhere. */
    override def prepare(sql: String): String = Lexer.foldSigns(sql)

    def columnType(name: String): DataType = {
      val upper = name.toUpperCase(Locale.ROOT)
      val base = upper.takeWhile(_ != '(').trim
      if (base.contains("INT")) DataType.LongType
      else if (base.contains("CHAR") || base.contains("CLOB") || base.contains("TEXT")) DataType.StringType
      else if (base.contains("REAL") || base.contains("FLOA") || base.contains("DOUB")) DataType.DoubleType
      else throw new Database.Unsupported(s"column type '$name'")
    }

    def store(v: Any, from: DataType, to: DataType): Any = (v, to) match {
      case (n: Lexer.NumberText, DataType.DoubleType) => java.lang.Double.valueOf(n.double)
      case (n: Lexer.NumberText, DataType.StringType) =>
        // SQLite stores a number in a TEXT column as the text of its value.
        if (n.isWhole) n.decimal.toBigInteger.toString
        else Values.format(java.lang.Double.valueOf(n.double), Script.Column.Text)
      case (n: Lexer.NumberText, _) => store(n.decimal, from, to)
      case (n: java.lang.Number, DataType.LongType) if integral(n) => java.lang.Long.valueOf(longOf(n))
      case (n: java.lang.Number, DataType.DoubleType) => java.lang.Double.valueOf(n.doubleValue)
      case (n: java.lang.Number, DataType.StringType) => Values.format(n, Script.Column.Text)
      case (s: String, DataType.StringType) => s
      case (b: java.lang.Boolean, DataType.LongType) => java.lang.Long.valueOf(if (b.booleanValue) 1L else 0L)
      case _ => throw new Database.Unsupported(s"a ${Script.typeName(from)} value ${Values.exact(v)} in a ${Script.typeName(to)} column")
    }
  }

  private def integral(n: java.lang.Number): Boolean = n match {
    case _: java.lang.Integer | _: java.lang.Long => true
    case d: java.math.BigDecimal => try { d.longValueExact; true } catch { case _: ArithmeticException => false }
    case d: java.lang.Double => !d.isNaN && !d.isInfinite && d.doubleValue == math.rint(d.doubleValue) && math.abs(d.doubleValue) < 9.2e18
    case f: java.lang.Float => !f.isNaN && !f.isInfinite && f.doubleValue == math.rint(f.doubleValue) && math.abs(f.doubleValue) < 9.2e18
    case _ => false
  }

  private def longOf(n: java.lang.Number): Long = n match {
    case d: java.math.BigDecimal => d.longValueExact
    case other => other.longValue
  }

  private def decimalOf(n: java.lang.Number): Option[java.math.BigDecimal] = n match {
    case d: java.math.BigDecimal => Some(d)
    case i: java.lang.Integer => Some(java.math.BigDecimal.valueOf(i.longValue))
    case l: java.lang.Long => Some(java.math.BigDecimal.valueOf(l.longValue))
    case d: java.lang.Double if !d.isNaN && !d.isInfinite => Some(new java.math.BigDecimal(java.lang.Double.toString(d.doubleValue)))
    case _ => None
  }
}
