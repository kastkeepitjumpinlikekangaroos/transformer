package com.transformer.job

import java.util.Locale

/** Tiny stdlib-only JSON parser used by [[DirectoryJobLoader]].
  *
  * Supports the standard JSON grammar — objects, arrays, strings (with `\uXXXX`
  * escapes), numbers (parsed as Double), booleans, null. Object insertion order
  * is preserved; non-string scalar values can be coerced via `stringValue`.
  */
sealed trait JsonValue {
  def typeName: String = this match {
    case _: JsonObject => "object"
    case _: JsonArray  => "array"
    case _: JsonString => "string"
    case _: JsonNumber => "number"
    case _: JsonBool   => "boolean"
    case JsonNull      => "null"
  }

  def asObject(ctx: String): JsonObject = this match {
    case o: JsonObject => o
    case _ =>
      throw new IllegalArgumentException(s"$ctx: expected JSON object, got $typeName")
  }

  /** Coerce primitive scalars to their string form. Used when populating string-
    * keyed option bags (CSV options, etc.) from JSON values that may legitimately
    * be numbers or booleans in the source file.
    */
  def stringValue: String = this match {
    case JsonString(s) => s
    case JsonBool(b)   => b.toString
    case JsonNumber(d) =>
      val asLong = d.toLong
      if (asLong.toDouble == d) asLong.toString else d.toString
    case JsonNull => ""
    case _ =>
      throw new IllegalArgumentException(s"Cannot represent $typeName as a string scalar")
  }
}

case object JsonNull extends JsonValue
final case class JsonBool(value: Boolean) extends JsonValue
final case class JsonNumber(value: Double) extends JsonValue
final case class JsonString(value: String) extends JsonValue
final case class JsonArray(values: Seq[JsonValue]) extends JsonValue
final case class JsonObject(fields: Map[String, JsonValue]) extends JsonValue {

  def get(key: String): Option[JsonValue] = fields.get(key)

  def requiredString(key: String, ctx: String): String = fields.get(key) match {
    case Some(JsonString(s)) => s
    case Some(other) =>
      throw new IllegalArgumentException(
        s"$ctx: expected string for '$key', got ${other.typeName}"
      )
    case None =>
      throw new IllegalArgumentException(s"$ctx: missing required field '$key'")
  }

  def optString(key: String, ctx: String): Option[String] = fields.get(key).flatMap {
    case JsonNull      => None
    case JsonString(s) => Some(s)
    case other =>
      throw new IllegalArgumentException(
        s"$ctx: expected string for '$key', got ${other.typeName}"
      )
  }

  def optBool(key: String, ctx: String): Option[Boolean] = fields.get(key).flatMap {
    case JsonNull    => None
    case JsonBool(b) => Some(b)
    case other =>
      throw new IllegalArgumentException(
        s"$ctx: expected boolean for '$key', got ${other.typeName}"
      )
  }

  /** Convert an object-of-scalars to a flat `Map[String, String]`. Nested objects
    * or arrays are rejected with a clear error.
    */
  def optStringMap(key: String, ctx: String): Option[Map[String, String]] =
    fields.get(key).flatMap {
      case JsonNull => None
      case JsonObject(fs) =>
        Some(fs.map { case (k, v) =>
          if (v.isInstanceOf[JsonObject] || v.isInstanceOf[JsonArray])
            throw new IllegalArgumentException(
              s"$ctx: '$key.$k' must be a scalar, got ${v.typeName}"
            )
          k -> v.stringValue
        })
      case other =>
        throw new IllegalArgumentException(
          s"$ctx: expected object for '$key', got ${other.typeName}"
        )
    }
}

object Json {
  def parse(input: String): JsonValue = {
    val parser = new JsonParser(input)
    parser.skipWs()
    val v = parser.parseValue()
    parser.skipWs()
    if (!parser.atEnd) parser.error("Unexpected trailing content")
    v
  }

  /** The text between the quotes of a JSON string holding `s`, which [[parse]]
    * reads back as `s`: quote, backslash, newline, carriage return and tab
    * escaped by name, every other control character and every surrogate not in
    * a pair as a hex escape, the rest as itself. Half a surrogate pair is a
    * character UTF-8 cannot encode, so written raw it fails the write of the
    * whole file. Every JSON file this module writes goes through here. */
  def escape(s: String): String = {
    val sb = new java.lang.StringBuilder(s.length + 8)
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      c match {
        case '"'           => sb.append("\\\"")
        case '\\'          => sb.append("\\\\")
        case '\n'          => sb.append("\\n")
        case '\r'          => sb.append("\\r")
        case '\t'          => sb.append("\\t")
        case c if c < 0x20 => hex(sb, c)
        case c if Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s.charAt(i + 1)) =>
          sb.append(c).append(s.charAt(i + 1))
          i += 1
        case c if Character.isSurrogate(c) => hex(sb, c)
        case c             => sb.append(c)
      }
      i += 1
    }
    sb.toString
  }

  private def hex(sb: java.lang.StringBuilder, c: Char): Unit =
    sb.append('\\').append('u').append("%04x".formatLocal(Locale.ROOT, c.toInt))
}

private final class JsonParser(input: String) {
  private var pos: Int = 0

  def atEnd: Boolean = pos >= input.length

  def parseValue(): JsonValue = {
    skipWs()
    if (atEnd) error("Unexpected end of input")
    input.charAt(pos) match {
      case '{' => parseObject()
      case '[' => parseArray()
      case '"' => JsonString(parseString())
      case 't' => parseLiteral("true"); JsonBool(true)
      case 'f' => parseLiteral("false"); JsonBool(false)
      case 'n' => parseLiteral("null"); JsonNull
      case c if c == '-' || (c >= '0' && c <= '9') => parseNumber()
      case c => error(s"Unexpected character '$c'")
    }
  }

  private def parseObject(): JsonObject = {
    expect('{')
    val fields = scala.collection.mutable.LinkedHashMap.empty[String, JsonValue]
    skipWs()
    if (peek == '}') { pos += 1; return JsonObject(fields.toMap) }
    var done = false
    while (!done) {
      skipWs()
      if (peek != '"') error("Object key must be a string")
      val key = parseString()
      skipWs()
      expect(':')
      val v = parseValue()
      fields(key) = v
      skipWs()
      consume() match {
        case ','   => // continue
        case '}'   => done = true
        case other => error(s"Expected ',' or '}', got '$other'")
      }
    }
    JsonObject(fields.toMap)
  }

  private def parseArray(): JsonArray = {
    expect('[')
    val values = scala.collection.mutable.ArrayBuffer.empty[JsonValue]
    skipWs()
    if (peek == ']') { pos += 1; return JsonArray(values.toSeq) }
    var done = false
    while (!done) {
      values += parseValue()
      skipWs()
      consume() match {
        case ','   => // continue
        case ']'   => done = true
        case other => error(s"Expected ',' or ']', got '$other'")
      }
    }
    JsonArray(values.toSeq)
  }

  private def parseString(): String = {
    expect('"')
    val sb = new java.lang.StringBuilder()
    var done = false
    while (!done) {
      if (atEnd) error("Unterminated string")
      val c = input.charAt(pos); pos += 1
      c match {
        case '"'  => done = true
        case '\\' =>
          if (atEnd) error("Trailing backslash in string")
          val esc = input.charAt(pos); pos += 1
          esc match {
            case '"'  => sb.append('"')
            case '\\' => sb.append('\\')
            case '/'  => sb.append('/')
            case 'b'  => sb.append('\b')
            case 'f'  => sb.append('\f')
            case 'n'  => sb.append('\n')
            case 'r'  => sb.append('\r')
            case 't'  => sb.append('\t')
            case 'u'  =>
              if (pos + 4 > input.length) error("Truncated \\u escape")
              val hex = input.substring(pos, pos + 4)
              pos += 4
              sb.append(Integer.parseInt(hex, 16).toChar)
            case other => error(s"Invalid escape \\$other")
          }
        case _ => sb.append(c)
      }
    }
    sb.toString
  }

  private def parseNumber(): JsonNumber = {
    val start = pos
    if (peek == '-') pos += 1
    var stop = false
    while (!atEnd && !stop) {
      val c = input.charAt(pos)
      if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-')
        pos += 1
      else
        stop = true
    }
    val text = input.substring(start, pos)
    if (text.isEmpty || text == "-") error(s"Invalid number near '$text'")
    JsonNumber(java.lang.Double.parseDouble(text))
  }

  private def parseLiteral(text: String): Unit = {
    if (!input.regionMatches(pos, text, 0, text.length))
      error(s"Expected '$text'")
    pos += text.length
  }

  def peek: Char = if (atEnd) 0.toChar else input.charAt(pos)
  def consume(): Char = { val c = peek; pos += 1; c }
  def expect(c: Char): Unit = {
    if (atEnd || input.charAt(pos) != c) error(s"Expected '$c'")
    pos += 1
  }
  def skipWs(): Unit = {
    var run = true
    while (run && !atEnd) {
      input.charAt(pos) match {
        case ' ' | '\t' | '\n' | '\r' => pos += 1
        case _                         => run = false
      }
    }
  }
  def error(msg: String): Nothing =
    throw new IllegalArgumentException(s"JSON parse error at position $pos: $msg")
}
