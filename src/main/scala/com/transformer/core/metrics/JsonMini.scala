package com.transformer.core.metrics

/** Tiny stdlib-only JSON reader used by [[TaskMetricsRecord]] to round-trip
  * the hand-rolled `_perf.json` output for tests and developer tooling.
  *
  * Not a public surface; not a replacement for [[com.transformer.job.Json]].
  * The `job` module already owns a richer parser, but `core/metrics` sits
  * *below* `job` in the dependency graph (operators in `sql/exec` depend on
  * `core` but not on `job`, so any code reachable from operators must avoid
  * `job` imports). Replicating just enough of the parser here keeps the
  * dependency graph one-way and keeps the parser scoped to the file format
  * `_perf.json` actually emits — strict, intentional, no recursion limit
  * arithmetic needed.
  *
  * Supports: objects, arrays, strings (with `\uXXXX` escapes), numbers
  * (parsed as Long when they fit, else Double), booleans, null. Object
  * insertion order is preserved via a `LinkedHashMap` so counter ordering in
  * `_perf.json` round-trips. */
private[metrics] object JsonMini {

  sealed trait JVal {
    def asObject: JObj = this match {
      case o: JObj => o
      case _       => throw new IllegalArgumentException(s"expected object, got ${getClass.getSimpleName}")
    }
    def asArray: JArr = this match {
      case a: JArr => a
      case _       => throw new IllegalArgumentException(s"expected array, got ${getClass.getSimpleName}")
    }
    def asString: String = this match {
      case JStr(s) => s
      case _       => throw new IllegalArgumentException(s"expected string, got ${getClass.getSimpleName}")
    }
    def asLong: Long = this match {
      case JNum(d) => d.toLong
      case _       => throw new IllegalArgumentException(s"expected number, got ${getClass.getSimpleName}")
    }
    def isNull: Boolean = this eq JNull
  }
  final case class JObj(fields: scala.collection.mutable.LinkedHashMap[String, JVal]) extends JVal {
    def field(key: String): JVal = fields.getOrElse(key,
      throw new IllegalArgumentException(s"missing required JSON field '$key'"))
    def optField(key: String): Option[JVal] = fields.get(key)
  }
  final case class JArr(items: Vector[JVal]) extends JVal {
    def iterator: Iterator[JVal] = items.iterator
  }
  final case class JStr(value: String) extends JVal
  final case class JNum(value: Double) extends JVal
  final case class JBool(value: Boolean) extends JVal
  case object JNull extends JVal

  def parse(text: String): JVal = {
    val p = new Parser(text)
    p.skipWs()
    val v = p.value()
    p.skipWs()
    if (!p.atEnd) p.fail("trailing content")
    v
  }

  private final class Parser(text: String) {
    private var pos: Int = 0
    private val len: Int = text.length

    def atEnd: Boolean = pos >= len

    def skipWs(): Unit = {
      while (pos < len) {
        val c = text.charAt(pos)
        if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos += 1
        else return
      }
    }

    def fail(msg: String): Nothing =
      throw new IllegalArgumentException(s"JsonMini at pos $pos: $msg")

    def value(): JVal = {
      skipWs()
      if (atEnd) fail("unexpected end of input")
      val c = text.charAt(pos)
      c match {
        case '{' => obj()
        case '[' => arr()
        case '"' => JStr(string())
        case 't' => literal("true"); JBool(true)
        case 'f' => literal("false"); JBool(false)
        case 'n' => literal("null"); JNull
        case d if d == '-' || (d >= '0' && d <= '9') => number()
        case other => fail(s"unexpected '$other'")
      }
    }

    private def obj(): JObj = {
      pos += 1 // '{'
      val fs = scala.collection.mutable.LinkedHashMap.empty[String, JVal]
      skipWs()
      if (pos < len && text.charAt(pos) == '}') { pos += 1; return JObj(fs) }
      var done = false
      while (!done) {
        skipWs()
        if (pos >= len || text.charAt(pos) != '"') fail("object key must be string")
        val k = string()
        skipWs()
        if (pos >= len || text.charAt(pos) != ':') fail("expected ':'")
        pos += 1
        val v = value()
        fs(k) = v
        skipWs()
        if (atEnd) fail("unterminated object")
        text.charAt(pos) match {
          case ',' => pos += 1
          case '}' => pos += 1; done = true
          case c   => fail(s"expected ',' or '}', got '$c'")
        }
      }
      JObj(fs)
    }

    private def arr(): JArr = {
      pos += 1 // '['
      val buf = scala.collection.mutable.ArrayBuffer.empty[JVal]
      skipWs()
      if (pos < len && text.charAt(pos) == ']') { pos += 1; return JArr(buf.toVector) }
      var done = false
      while (!done) {
        buf += value()
        skipWs()
        if (atEnd) fail("unterminated array")
        text.charAt(pos) match {
          case ',' => pos += 1
          case ']' => pos += 1; done = true
          case c   => fail(s"expected ',' or ']', got '$c'")
        }
      }
      JArr(buf.toVector)
    }

    private def string(): String = {
      pos += 1 // opening quote
      val sb = new java.lang.StringBuilder()
      while (pos < len) {
        val c = text.charAt(pos); pos += 1
        c match {
          case '"' => return sb.toString
          case '\\' =>
            if (atEnd) fail("trailing backslash")
            val e = text.charAt(pos); pos += 1
            e match {
              case '"'  => sb.append('"')
              case '\\' => sb.append('\\')
              case '/'  => sb.append('/')
              case 'b'  => sb.append('\b')
              case 'f'  => sb.append('\f')
              case 'n'  => sb.append('\n')
              case 'r'  => sb.append('\r')
              case 't'  => sb.append('\t')
              case 'u'  =>
                if (pos + 4 > len) fail("truncated \\u escape")
                val hex = text.substring(pos, pos + 4)
                pos += 4
                sb.append(Integer.parseInt(hex, 16).toChar)
              case other => fail(s"invalid escape \\$other")
            }
          case _ => sb.append(c)
        }
      }
      fail("unterminated string")
    }

    private def literal(s: String): Unit = {
      if (pos + s.length > len) fail(s"expected '$s'")
      var i = 0
      while (i < s.length) {
        if (text.charAt(pos + i) != s.charAt(i)) fail(s"expected '$s'")
        i += 1
      }
      pos += s.length
    }

    private def number(): JVal = {
      val start = pos
      if (text.charAt(pos) == '-') pos += 1
      var stop = false
      while (!atEnd && !stop) {
        val c = text.charAt(pos)
        if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-')
          pos += 1
        else stop = true
      }
      val s = text.substring(start, pos)
      if (s.isEmpty || s == "-") fail(s"invalid number near '$s'")
      JNum(java.lang.Double.parseDouble(s))
    }
  }
}
