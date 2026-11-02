package com.transformer.slt

import com.transformer.core._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Locale
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** The tables a script builds with `statement ok`, held as rows.
  *
  * The engine runs queries only; it has no DDL or DML. A script's
  * `CREATE TABLE`, `INSERT`, `COPY`, `DELETE`, `UPDATE`, `CREATE VIEW` and
  * `DROP` are carried out here, and a query reads the tables through a catalog
  * built for whichever layout a verification mode asks for ([[Modes]]). An
  * `INSERT`'s values are read here when they are literals; anything else in a
  * `VALUES` list, an `INSERT ... SELECT`, a `DELETE`'s and an `UPDATE`'s
  * condition, and a view's body are evaluated by the engine (a view's again
  * whenever a table changed since: [[refreshViews]]), so a defect there
  * shows up as a setup failure, which the runner reports. A `COPY` reads a
  * file in the script's directory, `dir`.
  *
  * Column types follow the [[Dialect]]: [[Dialect.Transformer]] reads a type
  * name as a CAST does, and [[Dialect.Sqlite]] reads SQLite's corpus as SQLite
  * stores it (`INTEGER` a 64-bit BIGINT, `FLOAT` and `REAL` a DOUBLE, `TEXT` and
  * `VARCHAR(n)` a STRING), with its runs of signs folded ([[Dialect.prepare]]).
  */
final class Database(dialect: Dialect, dir: Path) {

  import Database._

  private val tables = mutable.LinkedHashMap.empty[String, Table]
  /** Each table's columns as its CREATE TABLE declared them, a column of a
    * type the engine has not ([[Dialect.lacks]]) among them: an INSERT or an
    * UPDATE of it is dropped with it. Absent for a table a query made. */
  private val declared = mutable.HashMap.empty[String, Vector[ColumnSpec]]
  /** Each view's body and column names, and the version of the tables its
    * rows were computed at: a view reads its tables as they are when a query
    * reads it ([[refreshViews]]). */
  private val viewBodies = mutable.LinkedHashMap.empty[String, (String, Option[Seq[String]])]
  private val viewComputedAt = mutable.HashMap.empty[String, Long]
  /** Bumped whenever a table's rows or a table change. */
  private var tablesVersion = 0L
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
    refreshViews(query)
    val text = stripSemicolon(sql.trim)
    val t = Lexer.tokens(text)
    def word(i: Int): String = t.lift(i).map(_.upper).getOrElse("")
    if (dialect.setsAside(t)) return
    word(0) match {
      case "CREATE" =>
        var i = 1
        val replace = word(i) == "OR" && word(i + 1) == "REPLACE" && dialect.format != Script.Format.Sqlite
        if (replace) i += 2
        if (word(i) == "TEMP" || word(i) == "TEMPORARY") i += 1
        word(i) match {
          case "TABLE" =>
            if (replace) t.lift(i + 1).foreach(n => drop(n.name))
            createTable(text, t, i + 1, query)
          case "VIEW" =>
            if (replace) t.lift(i + 1).foreach(n => drop(n.name))
            createView(text, t, i + 1, query)
          case "EXTERNAL" | "UNBOUNDED" if dialect.format == Script.Format.DataFusion => createExternal(text, t, i)
          case "INDEX" | "UNIQUE" => createIndex(t, i)
          case "TRIGGER" => throw new Unsupported("CREATE TRIGGER")
          case other => throw new Unsupported(s"CREATE $other")
        }
      case "DROP" =>
        val kind = word(1)
        var i = 2
        val ifExists = word(i) == "IF" && word(i + 1) == "EXISTS"
        if (ifExists) i += 2
        if (!t.lift(i).exists(tk => tk.kind == Token.Word || tk.kind == Token.QuotedName))
          throw new IllegalArgumentException("DROP names nothing")
        // CockroachDB and DataFusion drop a list of names, and may end it
        // with CASCADE or RESTRICT, which change nothing here.
        val names = Lexer.splitCommas(t.drop(i)).map { item =>
          val words = if (dialect.format != Script.Format.Sqlite && item.length == 2 &&
              (item(1).upper == "CASCADE" || item(1).upper == "RESTRICT")) item.take(1) else item
          if (words.length != 1) throw new Unsupported(s"DROP ${t.drop(i).map(_.text).mkString(" ")}")
          words.head.name
        }
        names.foreach { name =>
          kind match {
            case "TABLE" | "VIEW" =>
              tables.get(key(name)) match {
                case Some(tb) if tb.isView == (kind == "VIEW") => drop(name)
                case _ if ifExists => ()
                case _ => throw new IllegalArgumentException(s"no such ${kind.toLowerCase(Locale.ROOT)}: $name")
              }
            case "INDEX" =>
              if (indexes.remove(key(name)).isEmpty && !ifExists) throw new IllegalArgumentException(s"no such index: $name")
            case other => throw new Unsupported(s"DROP $other")
          }
        }
      case "TRUNCATE" if dialect.format != Script.Format.Sqlite =>
        var i = 1
        if (word(i) == "TABLE") i += 1
        Lexer.splitCommas(t.drop(i)).foreach { item =>
          if (item.length != 1) throw new Unsupported(s"TRUNCATE ${t.drop(i).map(_.text).mkString(" ")}")
          val tb = writable(item.head.name)
          put(tb.copy(rows = Vector.empty))
        }
      case "INSERT" => insert(text, t, query)
      case "COPY" => copy(t)
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
    // A column list, then AS VALUES (CockroachDB's and DataFusion's), or AS a
    // query, whose columns a list of bare names renames.
    val columnList: Option[Vector[ColumnSpec]] =
      if (t.lift(i).exists(_.text == "(")) {
        val close = matching(t, i)
        val cols = Lexer.splitCommas(t.slice(i + 1, close)).flatMap(columnDefinition)
        i = close + 1
        Some(cols)
      } else None
    if (t.lift(i).exists(_.upper == "AS")) {
      if (t.lift(i + 1).exists(_.upper == "VALUES") && dialect.format != Script.Format.Sqlite) {
        createFromValues(name, text, t, i + 2, columnList, query)
        return
      }
      val (schema, rows) = query(text.substring(t(i + 1).start))
      val named = columnList match {
        case None => schema
        case Some(names) if dialect.format != Script.Format.Sqlite && names.forall(!_.typed) =>
          if (names.length != schema.length) throw new IllegalArgumentException(s"${names.length} column names for ${schema.length} columns")
          Schema(schema.fields.zip(names).map { case (f, c) => f.copy(name = c.name) })
        case Some(_) => throw new Unsupported("CREATE TABLE with a column list AS a query")
      }
      put(Table(name, named, rows, isView = false, version = 0))
      return
    }
    if (i < t.length && dialect.format != Script.Format.Sqlite)
      throw new Unsupported(s"CREATE TABLE ... ${t.drop(i).map(_.text).mkString(" ")}")
    val cols = columnList.getOrElse(throw new IllegalArgumentException(s"CREATE TABLE $name has no column list"))
    if (cols.exists(!_.typed)) throw new IllegalArgumentException(s"CREATE TABLE $name names a column with no type")
    val fields = cols.collect { case ColumnSpec(n, Some(dt), _, _) => Field(n, dt) }
    if (cols.isEmpty) throw new IllegalArgumentException(s"CREATE TABLE $name has no columns")
    if (fields.isEmpty)
      throw new Unsupported(s"CREATE TABLE $name, whose every column is of a type the engine has not")
    Schema.repeatedName(cols.map(_.name)).foreach { _ =>
      throw new IllegalArgumentException(s"CREATE TABLE $name repeats a column name")
    }
    declared(key(name)) = cols
    put(Table(name, Schema(fields), Vector.empty, isView = false, version = 0))
  }

  /** A column definition, its type None for a type the engine has not
    * ([[Dialect.lacks]]); nothing for a table constraint. A definition of a
    * name alone is untyped, which a column list before AS is. */
  private def columnDefinition(defn: Vector[Token]): Option[ColumnSpec] = {
    if (defn.isEmpty) throw new IllegalArgumentException("an empty column definition")
    if (TableConstraints.contains(defn.head.upper) || dialect.tableClauses.contains(defn.head.upper)) return None
    val name = defn.head.name
    val typeTokens = defn.drop(1).takeWhile(tk => !ColumnConstraints.contains(tk.upper) && !dialect.columnClauses.contains(tk.upper))
    val clauses = defn.drop(1 + typeTokens.length)
    // A computed column (AS (expr) STORED) holds values no INSERT writes.
    if (clauses.exists(tk => tk.upper == "AS" || tk.upper == "GENERATED"))
      throw new Unsupported(s"the computed column $name")
    val default = clauses.indexWhere(_.upper == "DEFAULT") match {
      case -1 => None
      case k =>
        val rest = clauses.drop(k + 1)
        if (rest.headOption.exists(_.upper == "NULL")) Some(rest.take(1))
        else Some(rest.takeWhile(tk => !ColumnConstraints.contains(tk.upper) && !dialect.columnClauses.contains(tk.upper)))
    }
    // `INT[]` reads as a word and a quoted name of nothing: `[` quotes a
    // name in SQL Server's dialect.
    val typeText = typeTokens.map(tk => if (tk.kind == Token.QuotedName && tk.name.isEmpty) "[]" else tk.text)
      .mkString(" ").replace(" (", "(").replace("( ", "(").replace(" )", ")").replace(" ,", ",").replace(", ", ",")
      .replace(" []", "[]")
    if (typeText.isEmpty && dialect.format != Script.Format.Sqlite) Some(ColumnSpec(name, None, typed = false, None))
    else if (dialect.lacks(typeText).isDefined) Some(ColumnSpec(name, None, typed = true, default))
    else Some(ColumnSpec(name, Some(dialect.columnType(typeText)), typed = true, default))
  }

  /** `CREATE TABLE name [(columns)] AS VALUES (...), ...`, DataFusion's, from
    * the token after VALUES: each item as a VALUES item of an INSERT is read
    * ([[literal]]), into the columns given or, with none, into columns
    * `column1`, `column2`, ... each of the type DataFusion gives its values
    * ([[Dialect.valuesType]]). */
  private def createFromValues(name: String, text: String, t: Vector[Token], start: Int,
      columnList: Option[Vector[ColumnSpec]], query: String => (Schema, Vector[Array[Any]])): Unit = {
    val rows = valuesRows(t, start)
    if (rows.isEmpty) throw new IllegalArgumentException(s"CREATE TABLE $name AS VALUES has no rows")
    val width = rows.head.length
    if (rows.exists(_.length != width)) throw new IllegalArgumentException("VALUES rows of different lengths")
    columnList match {
      case Some(cols) if cols.forall(_.typed) =>
        if (cols.length != width) throw new IllegalArgumentException(s"${cols.length} columns for $width values")
        if (cols.exists(_.dataType.isEmpty)) throw new Unsupported(s"CREATE TABLE $name AS VALUES with a column of a type the engine has not")
        val schema = Schema(cols.map(c => Field(c.name, c.dataType.get)))
        val out = rows.map(r => Array.tabulate[Any](width)(c => literal(text, r(c), schema.fields(c).dataType, query)))
        declared(key(name)) = cols
        put(Table(name, schema, out, isView = false, version = 0))
      case _ =>
        val names = columnList.map(_.map(_.name)).getOrElse((1 to width).map(c => s"column$c").toVector)
        if (names.length != width) throw new IllegalArgumentException(s"${names.length} column names for $width values")
        // Each value as it reads alone, then the column's type from them all.
        val read: Vector[Vector[(Any, DataType)]] = rows.map(_.map { item =>
          Lexer.literal(item) match {
            case Some(v) => v -> dialect.valuesType(Lexer.literalType(v), v)
            case None =>
              val (schema, rs) = query(s"SELECT ${text.substring(item.head.start, item.last.end)} AS v")
              rs.head(0) -> schema.fields(0).dataType
          }
        })
        val types = (0 until width).map { c =>
          read.map(_(c)._2).reduce((a, b) => dialect.valuesUnion(a, b)
            .getOrElse(throw new IllegalArgumentException(s"VALUES column ${c + 1} holds ${Script.typeName(a)} and ${Script.typeName(b)}")))
        }
        val schema = Schema((0 until width).map(c => Field(names(c), types(c))).toVector)
        val out = read.map(r => Array.tabulate[Any](width)(c => convert(r(c)._1, r(c)._2, types(c))))
        put(Table(name, schema, out, isView = false, version = 0))
    }
  }

  /** The rows of a VALUES list from its first `(`, each row its items. */
  private def valuesRows(t: Vector[Token], start: Int): Vector[Vector[Vector[Token]]] = {
    val rows = Vector.newBuilder[Vector[Vector[Token]]]
    var j = start
    while (j < t.length) {
      if (t(j).text != "(") throw new IllegalArgumentException(s"VALUES expects '(' at '${t(j).text}'")
      val close = matching(t, j)
      rows += Lexer.splitCommas(t.slice(j + 1, close))
      j = close + 1
      if (j < t.length) {
        if (t(j).text != ",") throw new IllegalArgumentException(s"unexpected '${t(j).text}' after a VALUES row")
        j += 1
      }
    }
    rows.result()
  }

  /** DataFusion's `CREATE [UNBOUNDED] EXTERNAL TABLE [IF NOT EXISTS] name
    * [(columns)] STORED AS CSV LOCATION 'file' [OPTIONS (...)]`, from the token
    * after CREATE: the file is read by the engine's CSV reader from the
    * `data/` directory beside the script, under its name, with the columns
    * given or the types the reader infers. Any other format, a directory, a
    * partitioning or an option the reader does not take is a statement this
    * runner does not carry out. */
  private def createExternal(text: String, t: Vector[Token], start: Int): Unit = {
    var i = start
    if (t(i).upper == "UNBOUNDED") throw new Unsupported("CREATE UNBOUNDED EXTERNAL TABLE")
    i += 2 // EXTERNAL TABLE
    val ifNotExists = t.lift(i).exists(_.upper == "IF") && t.lift(i + 1).exists(_.upper == "NOT")
    if (ifNotExists) i += 3
    val name = t(i).name
    if (tables.contains(key(name))) {
      if (ifNotExists) return
      throw new IllegalArgumentException(s"table $name already exists")
    }
    i += 1
    val columns: Option[Vector[ColumnSpec]] =
      if (t.lift(i).exists(_.text == "(")) {
        val close = matching(t, i)
        val cols = Lexer.splitCommas(t.slice(i + 1, close)).flatMap(columnDefinition)
        i = close + 1
        Some(cols)
      } else None
    var location: Option[String] = None
    var format: Option[String] = None
    var header = true
    var delimiter = ','
    while (i < t.length) {
      t(i).upper match {
        case "STORED" if t.lift(i + 1).exists(_.upper == "AS") => format = t.lift(i + 2).map(_.upper); i += 3
        case "LOCATION" =>
          location = t.lift(i + 1).flatMap(tk => Lexer.literal(Vector(tk))).collect { case s: String => s }
          i += 2
        case "OPTIONS" if t.lift(i + 1).exists(_.text == "(") =>
          val close = matching(t, i + 1)
          Lexer.splitCommas(t.slice(i + 2, close)).foreach { kv =>
            val parts = kv.flatMap(tk => Lexer.literal(Vector(tk)).collect { case s: String => s })
            (parts.headOption.map(_.toLowerCase(Locale.ROOT)), parts.lift(1)) match {
              case (Some("format.has_header" | "has_header"), Some(v)) => header = v.equalsIgnoreCase("true")
              case (Some("format.delimiter" | "delimiter"), Some(v)) if v.length == 1 => delimiter = v.head
              case _ => throw new Unsupported(s"the external table option ${kv.map(_.text).mkString(" ")}")
            }
          }
          i = close + 1
        case "WITH" if t.lift(i + 1).exists(_.upper == "HEADER") => header = true; i += 3
        case other => throw new Unsupported(s"CREATE EXTERNAL TABLE ... $other")
      }
    }
    if (!format.contains("CSV")) throw new Unsupported(s"an external table STORED AS ${format.getOrElse("nothing")}")
    val path = location.getOrElse(throw new IllegalArgumentException("CREATE EXTERNAL TABLE names no LOCATION"))
    if (path.endsWith("/")) throw new Unsupported(s"an external table over the directory $path")
    val file = dir.resolve("data").resolve(java.nio.file.Paths.get(path).getFileName.toString)
    if (!Files.isRegularFile(file)) throw new Unsupported(s"an external table over $path, which is not vendored as $file")
    if (columns.exists(_.exists(_.dataType.isEmpty))) throw new Unsupported("an external table with a column of a type the engine has not")
    val fields = columns.map(_.map(c => Field(c.name, c.dataType.get)))
    val reader = new com.transformer.read.csv.CsvReader(Vector(file), com.transformer.read.csv.CsvOptions(
      inferSchema = fields.isEmpty, header = header, delimiter = delimiter, columns = fields))
    val rows = (0 until reader.numPartitions).iterator.flatMap(p => reader.readPartition(p)).flatMap { b =>
      (0 until b.numRows).iterator.map { r =>
        Array.tabulate[Any](reader.schema.length)(c => if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r))
      }
    }.toVector
    columns.foreach(cols => declared(key(name)) = cols)
    put(Table(name, reader.schema, rows, isView = false, version = 0))
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
    val body = text.substring(t(i + 1).start)
    put(viewTable(name, body, columnNames, query))
    viewBodies(key(name)) = (body, columnNames)
    viewComputedAt(key(name)) = tablesVersion
  }

  /** The view `name` as its body returns it now. */
  private def viewTable(name: String, body: String, columnNames: Option[Seq[String]],
      query: String => (Schema, Vector[Array[Any]])): Table = {
    val (schema, rows) = query(body)
    val named = columnNames match {
      case None => schema
      case Some(names) =>
        if (names.length != schema.length) throw new IllegalArgumentException(s"view $name names ${names.length} columns for ${schema.length}")
        Schema(schema.fields.zip(names).map { case (f, n) => f.copy(name = n) })
    }
    Table(name, named, rows, isView = true, version = 0)
  }

  /** Recompute, in the order they were made, the views whose tables changed
    * since their rows were computed, so a view reads its tables as they are,
    * as a view does. A body that no longer runs (a table it read is gone)
    * keeps the rows it had. */
  def refreshViews(query: String => (Schema, Vector[Array[Any]])): Unit = {
    val stale = viewBodies.keys.filter(k => viewComputedAt.get(k).exists(_ < tablesVersion)).toVector
    stale.foreach(k => viewComputedAt(k) = tablesVersion)
    stale.foreach { k =>
      val (body, names) = viewBodies(k)
      val name = tables.get(k).map(_.name).getOrElse(k)
      try put(viewTable(name, body, names, query))
      catch { case scala.util.control.NonFatal(_) => () }
    }
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
    // The columns written, each as its index in the table, or None for one
    // of a type the engine has not, whose value is dropped unread.
    val columns = declared.getOrElse(key(tb.name), tb.schema.fields.map(f => ColumnSpec(f.name, Some(f.dataType), typed = true, None)))
    val listed = t.lift(i).exists(_.text == "(")
    val names: IndexedSeq[String] =
      if (listed) {
        val close = matching(t, i)
        val written = Lexer.splitCommas(t.slice(i + 1, close)).map(_.head.name)
        i = close + 1
        written
      } else columns.map(_.name)
    val targets: IndexedSeq[Option[Int]] = names.map { n =>
      columns.find(_.name.equalsIgnoreCase(n)) match {
        case None => throw new IllegalArgumentException(s"table ${tb.name} has no column named $n")
        case Some(c) if c.dataType.isEmpty => None
        case Some(_) =>
          val idx = tb.schema.indexOf(n)
          if (idx < 0) throw new IllegalArgumentException(s"table ${tb.name} has no column named $n")
          Some(idx)
      }
    }
    // CockroachDB and Postgres give a column no value names its DEFAULT, or
    // NULL; this runner keeps a DEFAULT that is a literal.
    val upstream = dialect.format != Script.Format.Sqlite
    def defaultOf(c: Int): Any = {
      val field = tb.schema.fields(c)
      columns.find(_.name.equalsIgnoreCase(field.name)).flatMap(_.default) match {
        case None => null
        case Some(d) => Lexer.literal(d) match {
          case Some(v) => convert(v, Lexer.literalType(v), field.dataType)
          case None => throw new Unsupported(s"the DEFAULT ${d.map(_.text).mkString(" ")} of ${field.name}")
        }
      }
    }
    if (names.map(_.toLowerCase(Locale.ROOT)).distinct.length != names.length)
      throw new IllegalArgumentException("INSERT names a column twice")
    val incoming: Vector[Vector[Any]] =
      if (t.lift(i).exists(_.upper == "VALUES")) {
        val rows = Vector.newBuilder[Vector[Any]]
        var j = i + 1
        while (j < t.length) {
          if (t(j).text != "(") {
            if (dialect.format != Script.Format.Sqlite) throw new Unsupported(s"INSERT ... ${t(j).text}")
            throw new IllegalArgumentException(s"VALUES expects '(' at '${t(j).text}'")
          }
          val close = matching(t, j)
          val items = Lexer.splitCommas(t.slice(j + 1, close))
          if (items.length != targets.length && !(upstream && !listed && items.length < targets.length))
            throw new IllegalArgumentException(s"${items.length} values for ${targets.length} columns")
          rows += items.zip(targets).map {
            case (Vector(w), Some(c)) if upstream && w.kind == Token.Word && w.upper == "DEFAULT" => defaultOf(c)
            case (item, Some(c)) => literal(text, item, tb.schema.fields(c).dataType, query)
            case (_, None) => null
          }.toVector
          j = close + 1
          if (j < t.length) {
            if (t(j).text != ",") {
              if (dialect.format != Script.Format.Sqlite) throw new Unsupported(s"INSERT ... ${t(j).text}")
              throw new IllegalArgumentException(s"unexpected '${t(j).text}' after a VALUES row")
            }
            j += 1
          }
        }
        rows.result()
      } else if (t.lift(i).exists(tk => tk.upper == "SELECT" || tk.upper == "WITH")) {
        val (schema, rows) = query(text.substring(t(i).start))
        if (schema.length != targets.length)
          throw new IllegalArgumentException(s"${schema.length} values for ${targets.length} columns")
        rows.map(r => targets.indices.map(k => targets(k) match {
          case Some(c) => convert(r(k), schema.fields(k).dataType, tb.schema.fields(c).dataType)
          case None => null
        }).toVector)
      } else throw new Unsupported("INSERT without VALUES or SELECT")
    val added = incoming.map { vals =>
      val row = new Array[Any](tb.schema.length)
      val written = targets.indices.take(vals.length).flatMap(k => targets(k).map(c => c -> vals(k))).toMap
      tb.schema.fields.indices.foreach(c => row(c) = written.getOrElse(c, if (upstream) defaultOf(c) else null))
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

  // ---- COPY ------------------------------------------------------------------

  /** `COPY table FROM 'file'`, as Postgres's test_setup.sql fills tenk1 from
    * data/tenk.data: the file, named relative to the script, holds a row a
    * line in Postgres's text format, its values separated by tabs. */
  private def copy(t: Vector[Token]): Unit = {
    val file = (t.lift(2).map(_.upper), t.lift(3).flatMap(f => Lexer.literal(Vector(f)))) match {
      case (Some("FROM"), Some(name: String)) if t.length == 4 => dir.resolve(name)
      case _ => throw new Unsupported("COPY other than COPY table FROM 'file'")
    }
    val tb = writable(t(1).name)
    val added = Files.readAllLines(file, StandardCharsets.UTF_8).asScala.toVector.map { line =>
      val values = line.split("\t", -1)
      if (values.length != tb.schema.length)
        throw new IllegalArgumentException(s"a line of $file has ${values.length} values for ${tb.schema.length} columns")
      Array.tabulate[Any](values.length)(c => copied(values(c), tb.schema.fields(c).dataType))
    }
    put(tb.copy(rows = tb.rows ++ added))
  }

  /** A value of a COPY file as one of type `to`: `\N` is NULL, a number is
    * read as a literal of its digits is, `t` and `f` are TRUE and FALSE, and
    * anything else is text, stored as a string literal is. Postgres's other
    * backslash escapes are not read. */
  private def copied(value: String, to: DataType): Any =
    if (value == "\\N") null
    else if (value.contains('\\')) throw new Unsupported(s"the backslash escape in COPY's value $value")
    else if (to == DataType.BooleanType) value match {
      case "t" => java.lang.Boolean.TRUE
      case "f" => java.lang.Boolean.FALSE
      case _ => throw new IllegalArgumentException(s"$value is no BOOLEAN")
    }
    else if (DataType.isNumeric(to)) Lexer.literal(Lexer.tokens(value)) match {
      case Some(n: Lexer.NumberText) => convert(n, Lexer.literalType(n), to)
      case _ => throw new IllegalArgumentException(s"$value is no number")
    }
    else convert(value, DataType.StringType, to)

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
    val dropped = declared.get(key(tb.name)).toVector.flatten.collect { case c if c.dataType.isEmpty => c.name.toLowerCase(Locale.ROOT) }.toSet
    val assignments = Lexer.splitCommas(setTokens).filterNot(a => a.nonEmpty && dropped(a.head.name.toLowerCase(Locale.ROOT))).map { a =>
      if (a.length < 3 || a(1).text != "=") throw new IllegalArgumentException("SET expects column = value")
      val c = tb.schema.indexOf(a.head.name)
      if (c < 0) throw new IllegalArgumentException(s"table ${tb.name} has no column named ${a.head.name}")
      c -> text.substring(a(2).start, a.last.end)
    }
    if (assignments.isEmpty) return
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

  /** Forget the table or view `name`, its declared columns and its indexes. */
  private def drop(name: String): Unit = {
    tables.remove(key(name))
    declared.remove(key(name))
    viewBodies.remove(key(name))
    viewComputedAt.remove(key(name))
    tablesVersion += 1
    indexes.filterInPlace { case (_, on) => on != key(name) }
  }

  private def put(tb: Table): Unit = {
    if (!tb.isView) tablesVersion += 1
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

  /** A column a CREATE TABLE declares: its type, None for a type the engine
    * has not, the column left out; `typed` false for a bare name, which a
    * column list before AS gives; `default` its DEFAULT's tokens. */
  private final case class ColumnSpec(name: String, dataType: Option[DataType], typed: Boolean,
      default: Option[Vector[Token]])

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
  /** How the dialect's scripts write their records ([[Script.Format]]). */
  def format: Script.Format = Script.Format.Sqlite
  /** The type the engine has not that a column type name names, by which
    * the column is left out of its table (`INTERVAL`, an array), or None. */
  def lacks(name: String): Option[String] = None
  /** Whether a statement, by its tokens, is one the dialect carries out by
    * doing nothing: a setting that changes no answer. */
  def setsAside(t: Vector[Token]): Boolean = false
  /** Words that start a table's clause, not a column, in CREATE TABLE. */
  def tableClauses: Set[String] = Set.empty
  /** Words that end a column's type in its definition. */
  def columnClauses: Set[String] = Set.empty
  /** The type a VALUES table gives a literal of type `t` (DataFusion's). */
  def valuesType(t: DataType, v: Any): DataType = t
  /** The type a VALUES column of values of types `a` and `b` has, or None. */
  def valuesUnion(a: DataType, b: DataType): Option[DataType] =
    if (a == b) Some(a) else if (a == DataType.NullType) Some(b) else if (b == DataType.NullType) Some(a) else None
  /** The dialect for a script whose text is `text`, which may say in its
    * header what an unconstrained DECIMAL is ([[Dialect.unconstrainedDecimal]]). */
  def forScript(text: String): Dialect = this
  /** Whether `sql` holds syntax of the dialect's engine the parser cannot
    * read, which it may take seconds to refuse: a record held for it is not
    * run to check its hold. */
  def unreadable(sql: String): Boolean = false
}

object Dialect {

  /** The header line by which a script of CockroachDB's or sqllogictest-rs's
    * says what its unconstrained `DECIMAL` or `NUMERIC` is: `# transformer:
    * an unconstrained DECIMAL is DECIMAL(38,10)`. */
  private val UnconstrainedDecimal = """(?m)^# transformer: an unconstrained DECIMAL is DECIMAL\((\d+),(\d+)\)\s*$""".r

  def unconstrainedDecimal(text: String): Option[DataType.DecimalType] =
    UnconstrainedDecimal.findFirstMatchIn(text).map(m => DataType.DecimalType(m.group(1).toInt, m.group(2).toInt))

  /** The dialect a script under `src/test/slt` is read in, by its directory:
    * `sqlite/` SQLite's corpus, `cockroach/` CockroachDB's logic tests,
    * `datafusion/` DataFusion's, `tpch/` RisingWave's TPC-H, and every other
    * this repo's own. */
  def forPath(name: String): Dialect = name.takeWhile(_ != '/') match {
    case "sqlite" => Sqlite
    case "cockroach" => Cockroach()
    case "datafusion" => DataFusion()
    case "tpch" => RisingWave()
    case _ => Transformer
  }

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

  /** A type name in a script of CockroachDB's, sqllogictest-rs's or
    * Postgres's: its words upper-cased, one space between, no spaces in its
    * parentheses. */
  private def normalized(name: String): String =
    name.toUpperCase(Locale.ROOT).trim.replaceAll("\\s+", " ").replace(" (", "(").replace("( ", "(").replace(" )", ")")
      .replace(" ,", ",").replace(", ", ",")

  private val Sized = """(DECIMAL|NUMERIC)\((\d+)(?:,(\d+))?\)""".r

  /** `DECIMAL(p,s)`, `DECIMAL(p)` (a scale of 0) or an unconstrained DECIMAL,
    * which is `decimal` where the script's header says what it is. */
  private def decimalType(upper: String, decimal: Option[DataType.DecimalType]): Option[DataType] = upper match {
    case Sized(_, p, s) => Some(DataType.DecimalType(p.toInt, Option(s).map(_.toInt).getOrElse(0)))
    case "DECIMAL" | "NUMERIC" | "DEC" => Some(decimal.getOrElse(
      throw new Database.Unsupported(s"an unconstrained $upper, which the script's header does not size")))
    case _ => None
  }

  /** CockroachDB's v23.2 logic tests, read as CockroachDB stores their
    * values: `INT`, `INT8`, `INTEGER` and `BIGINT` a BIGINT (CockroachDB's
    * integers are 64-bit), `INT4` an INT, `INT2` and `SMALLINT` an INT; `FLOAT`,
    * `FLOAT8` and `DOUBLE PRECISION` a DOUBLE, `FLOAT4` and `REAL` a FLOAT;
    * `STRING`, `TEXT`, `VARCHAR` and `CHAR` a STRING; `DECIMAL(p,s)` as
    * written, and an unconstrained `DECIMAL` as the script's header says. A
    * column of `INTERVAL`, `TIMESTAMPTZ`, `TIME`, `BYTES`, `UUID`, `JSONB`,
    * `INET`, `BIT` or an array is left out of its table (plans/testing/12,
    * decision 2). `INDEX`, `FAMILY` and `STORING` clauses change no answer and
    * are skipped. A cast's type name is rewritten to the engine's name for
    * the same type (`::INT` as `::BIGINT`, `::FLOAT` as `::DOUBLE`), since the
    * engine's `INT` and `FLOAT` are 32 bits. Values are stored as
    * [[Transformer]] stores them. */
  final case class Cockroach(decimal: Option[DataType.DecimalType] = None) extends Dialect {
    override def format: Script.Format = Script.Format.Cockroach
    override def forScript(text: String): Dialect = copy(decimal = unconstrainedDecimal(text).orElse(decimal))

    def columnType(name: String): DataType = engineType(normalized(name)).getOrElse(
      throw new Database.Unsupported(s"the column type '$name'"))

    /** CockroachDB's type name as the engine's type. */
    private def engineType(upper: String): Option[DataType] = upper match {
      case "INT" | "INT8" | "INT64" | "INTEGER" | "BIGINT" => Some(DataType.LongType)
      case "INT4" | "INT2" | "SMALLINT" => Some(DataType.IntType)
      case "FLOAT" | "FLOAT8" | "DOUBLE PRECISION" => Some(DataType.DoubleType)
      case "FLOAT4" | "REAL" => Some(DataType.FloatType)
      case "STRING" | "TEXT" | "VARCHAR" | "CHAR" | "CHARACTER" | "CHARACTER VARYING" => Some(DataType.StringType)
      case u if u.matches("(STRING|VARCHAR|CHAR|CHARACTER|CHARACTER VARYING)\\(\\d+\\)") => Some(DataType.StringType)
      case "BOOL" | "BOOLEAN" => Some(DataType.BooleanType)
      case "DATE" => Some(DataType.DateType)
      case "TIMESTAMP" | "TIMESTAMP WITHOUT TIME ZONE" => Some(DataType.TimestampType)
      case u => decimalType(u, decimal)
    }

    override def lacks(name: String): Option[String] = cockroachLacks(normalized(name))

    override def tableClauses: Set[String] = Set("INDEX", "INVERTED", "FAMILY")
    override def columnClauses: Set[String] = Set("FAMILY", "AS", "GENERATED", "ON", "CREATE")

    override def prepare(sql: String): String =
      renameCastTypes(sql, upper => scala.util.Try(engineType(upper)).toOption.flatten.map(Script.typeName))

    /** CockroachDB's type annotation, `1:::INT8`, which JSqlParser backtracks
      * over for seconds before it refuses it. */
    override def unreadable(sql: String): Boolean = sql.contains(":::")

    /** A VALUES table's whole number is an INT8, and a number with a point a
      * DECIMAL, which is the header's. */
    override def valuesType(t: DataType, v: Any): DataType = t match {
      case _: DataType.DecimalType => decimal.getOrElse(throw new Database.Unsupported("a VALUES table of decimals, which the script's header does not size"))
      case other => other
    }

    override def valuesUnion(a: DataType, b: DataType): Option[DataType] = (a, b) match {
      case (DataType.IntType | DataType.LongType, DataType.IntType | DataType.LongType) => Some(DataType.LongType)
      case _ => super.valuesUnion(a, b)
    }

    /** A setting that changes no answer the engine gives: how CockroachDB
      * plans and runs a query, and the time zone, which a DATE and a
      * TIMESTAMP do not read (only a TIMESTAMPTZ, which no table keeps). */
    override def setsAside(t: Vector[Token]): Boolean = {
      val words = t.map(_.text.toLowerCase(Locale.ROOT))
      def setting(k: Int): Boolean = words.lift(k).exists(w => CockroachSettings(w)) ||
        (words.lift(k).contains("time") && words.lift(k + 1).contains("zone"))
      words.headOption match {
        case Some("set") =>
          if (words.lift(1).contains("default_int_size")) words.lastOption.contains("8") else setting(1)
        case Some("reset") => setting(1)
        case _ => false
      }
    }

    /** Text stored in a TIMESTAMP column as CockroachDB stores it: an offset
      * or zone after the time is dropped, and a date alone is its midnight. */
    def store(v: Any, from: DataType, to: DataType): Any = (v, to) match {
      case (s: String, DataType.TimestampType) => cockroachTimestamp(s)
      case _ => Transformer.store(v, from, to)
    }
  }

  /** The settings [[Cockroach.setsAside]] sets aside. */
  private val CockroachSettings = Set("vectorize", "distsql", "testing_optimizer_random_seed",
    "testing_optimizer_disable_rule_probability", "testing_optimizer_cost_perturbation", "large_full_scan_rows", "reorder_joins_limit", "optimizer_use_histograms", "optimizer_use_multicol_stats",
    "enable_zigzag_join", "tracing", "timezone", "statement_timeout", "experimental_distsql_planning",
    "prefer_lookup_joins_for_fks", "enable_insert_fast_path", "unconstrained_non_covering_index_scan_enabled")

  private val CockroachTimestamp =
    """(\d{4}-\d{2}-\d{2})(?:[ T](\d{1,2}:\d{2}(?::\d{2}(?:\.\d+)?)?))?\s*(?:Z|UTC|[+-]\d{1,2}(?::?\d{2})?)?""".r

  private def cockroachTimestamp(s: String): java.time.Instant = s.trim match {
    case CockroachTimestamp(date, time) =>
      val t = Option(time).getOrElse("00:00:00")
      val full = if (t.count(_ == ':') == 1) t + ":00" else t
      java.time.LocalDateTime.parse(s"${date}T${if (full.indexOf(':') == 1) "0" + full else full}")
        .toInstant(java.time.ZoneOffset.UTC)
    case _ => throw new IllegalArgumentException(s"$s is no TIMESTAMP")
  }

  /** The types CockroachDB has and the engine has not, by name. */
  private def cockroachLacks(upper: String): Option[String] = {
    val base = upper.takeWhile(_ != '(')
    if (upper.endsWith("[]") || upper.startsWith("ARRAY")) Some("ARRAY")
    else base match {
      case "INTERVAL" | "TIMESTAMPTZ" | "TIMESTAMP WITH TIME ZONE" | "TIME" | "TIMETZ" | "TIME WITH TIME ZONE" |
          "TIME WITHOUT TIME ZONE" | "BYTES" | "BYTEA" | "BLOB" | "UUID" | "JSON" | "JSONB" | "INET" | "BIT" |
          "VARBIT" | "BIT VARYING" | "OID" | "REGCLASS" | "GEOMETRY" | "GEOGRAPHY" | "TSVECTOR" | "TSQUERY" |
          "BOX2D" | "NAME" => Some(base)
      case _ => None
    }
  }

  /** The words after a type's first that continue its name. */
  private val TypeWords = Set("PRECISION", "VARYING", "WITH", "WITHOUT", "TIME", "ZONE")

  /** `sql` with the type name after each `::` and each `CAST(x AS ...)`
    * rewritten by `rename` (given the name upper-cased, as [[normalized]]
    * writes it) where it gives a name; the rest of the text as written. */
  private def renameCastTypes(sql: String, rename: String => Option[String]): String = {
    val t = Lexer.tokens(sql)
    val out = new StringBuilder
    var copied = 0
    // The index just past a type name starting at token `k`: its words, then
    // one parenthesized list of numbers.
    def typeEnd(k: Int): Int = {
      var j = k
      while (j < t.length && t(j).kind == Token.Word && (j == k || TypeWords(t(j).upper))) j += 1
      if (j > k && j < t.length && t(j).text == "(") {
        var m = j + 1
        while (m < t.length && (t(m).kind == Token.Number || t(m).text == ",")) m += 1
        if (m < t.length && t(m).text == ")") j = m + 1
      }
      j
    }
    def rewrite(k: Int): Unit = {
      val end = typeEnd(k)
      // An array type is no type the engine has: the engine refuses it.
      if (end > k && !(end < t.length && t(end).text == "[")) {
        rename(normalized(sql.substring(t(k).start, t(end - 1).end))).foreach { name =>
          out ++= sql.substring(copied, t(k).start)
          out ++= name
          copied = t(end - 1).end
        }
      }
    }
    var casts = List.empty[Int]
    var depth = 0
    var i = 0
    while (i < t.length) {
      val tk = t(i)
      if (tk.text == ":" && t.lift(i + 1).exists(n => n.text == ":" && n.start == tk.end)) {
        rewrite(i + 2)
        i += 2
      } else {
        if (tk.text == "(") {
          depth += 1
          if (i > 0 && t(i - 1).kind == Token.Word && (t(i - 1).upper == "CAST" || t(i - 1).upper == "TRY_CAST"))
            casts = depth :: casts
        } else if (tk.text == ")") {
          if (casts.headOption.contains(depth)) casts = casts.tail
          depth -= 1
        } else if (tk.kind == Token.Word && tk.upper == "AS" && casts.headOption.contains(depth)) rewrite(i + 1)
        i += 1
      }
    }
    out ++= sql.substring(copied)
    out.result()
  }

  /** DataFusion's sqllogictest files, read as DataFusion stores their
    * values: `INT` and `INTEGER` an INT, `BIGINT` a BIGINT, `TINYINT` and
    * `SMALLINT` an INT (and unsigned, an INT for a byte, a BIGINT for 16 or
    * 32 bits, the `DECIMAL(20,0)` that holds each value for 64), `FLOAT` and
    * `REAL` a FLOAT, `DOUBLE` a DOUBLE, `VARCHAR`, `TEXT`, `STRING` and `CHAR`
    * a STRING, `DECIMAL(p,s)` as written; an `INTERVAL`, a `BYTEA` or an
    * array column is left out of its table. A VALUES table types a whole number a BIGINT and a
    * number with a point a DOUBLE, as DataFusion does. A `SET datafusion.`
    * setting of execution, the optimizer or EXPLAIN changes no answer and is
    * set aside. */
  final case class DataFusion(decimal: Option[DataType.DecimalType] = None) extends Dialect {
    override def format: Script.Format = Script.Format.DataFusion
    override def forScript(text: String): Dialect = copy(decimal = unconstrainedDecimal(text).orElse(decimal))

    def columnType(name: String): DataType = normalized(name) match {
      case "INT" | "INTEGER" | "INT4" | "TINYINT" | "SMALLINT" | "INT2" | "TINYINT UNSIGNED" => DataType.IntType
      case "BIGINT" | "INT8" | "INT UNSIGNED" | "INTEGER UNSIGNED" | "SMALLINT UNSIGNED" => DataType.LongType
      // No BIGINT holds an unsigned 64-bit value past 2^63; the DECIMAL of
      // its 20 digits holds each one.
      case "BIGINT UNSIGNED" => DataType.DecimalType(20, 0)
      case "FLOAT" | "REAL" | "FLOAT4" => DataType.FloatType
      case "DOUBLE" | "DOUBLE PRECISION" | "FLOAT8" => DataType.DoubleType
      case "VARCHAR" | "TEXT" | "STRING" | "CHAR" | "CHARACTER VARYING" => DataType.StringType
      case "BOOLEAN" | "BOOL" => DataType.BooleanType
      case "DATE" => DataType.DateType
      case "TIMESTAMP" => DataType.TimestampType
      case u if u.matches("(VARCHAR|CHAR)\\(\\d+\\)") => DataType.StringType
      case u => decimalType(u, decimal).getOrElse(throw new Database.Unsupported(s"the column type '$name'"))
    }

    override def lacks(name: String): Option[String] = {
      val u = normalized(name)
      cockroachLacks(u).orElse(if (u.startsWith("TIMESTAMP(") || u.contains("TIME ZONE")) Some(u) else None)
    }

    override def valuesType(t: DataType, v: Any): DataType = t match {
      case _: DataType.DecimalType => DataType.DoubleType
      case other => other
    }

    override def valuesUnion(a: DataType, b: DataType): Option[DataType] = (a, b) match {
      case (x, y) if x == y => Some(x)
      case (DataType.NullType, y) => Some(y)
      case (x, DataType.NullType) => Some(x)
      case (x, y) if Numbers(x) && Numbers(y) =>
        Some(if (x == DataType.DoubleType || y == DataType.DoubleType) DataType.DoubleType else DataType.LongType)
      case _ => None
    }

    private val Numbers = Set[DataType](DataType.LongType, DataType.IntType, DataType.DoubleType)

    override def setsAside(t: Vector[Token]): Boolean =
      t.headOption.exists(_.upper == "SET") && t.lift(1).exists(_.text.equalsIgnoreCase("datafusion")) &&
        t.lift(2).exists(_.text == ".") &&
        t.lift(3).exists(w => Set("execution", "optimizer", "explain")(w.text.toLowerCase(Locale.ROOT)))

    def store(v: Any, from: DataType, to: DataType): Any = Transformer.store(v, from, to)
  }

  /** RisingWave's TPC-H tables (Postgres's type names): `INTEGER` an INT,
    * `VARCHAR` and `CHAR(n)` a STRING, `DATE` a DATE, and an unconstrained
    * `NUMERIC` as the script's header says. Values are stored as
    * [[Transformer]] stores them. */
  final case class RisingWave(decimal: Option[DataType.DecimalType] = None) extends Dialect {
    override def format: Script.Format = Script.Format.RisingWave
    override def forScript(text: String): Dialect = copy(decimal = unconstrainedDecimal(text).orElse(decimal))

    def columnType(name: String): DataType = normalized(name) match {
      case "INTEGER" | "INT" | "INT4" => DataType.IntType
      case "BIGINT" | "INT8" => DataType.LongType
      case "VARCHAR" | "TEXT" | "CHAR" => DataType.StringType
      case u if u.matches("(VARCHAR|CHAR)\\(\\d+\\)") => DataType.StringType
      case "DATE" => DataType.DateType
      case "DOUBLE PRECISION" | "FLOAT8" => DataType.DoubleType
      case u => decimalType(u, decimal).getOrElse(throw new Database.Unsupported(s"the column type '$name'"))
    }

    def store(v: Any, from: DataType, to: DataType): Any = Transformer.store(v, from, to)
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
