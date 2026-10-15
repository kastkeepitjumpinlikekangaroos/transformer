package com.transformer.read.csv

import java.io.{PushbackReader, Reader}
import java.nio.charset.CharacterCodingException
import scala.collection.mutable.ArrayBuffer

/** Parses one CSV row at a time from a [[Reader]]. State-machine; supports quoted
  * fields with embedded delimiters, embedded newlines, and doubled-quote escape
  * (or a separate escape char when `escape != quote`, which escapes the
  * character after it, itself included).
  *
  * A field is NULL (`null` in the returned array) when it is `opts.nullValue`
  * written without quotes; a quoted field is always its text, so `""` is the
  * empty string even where the null string is empty. Text after a closing
  * quote joins the field (`"a"b` is `ab`), and a quote inside an unquoted
  * field is a character of it.
  *
  * A blank line is returned as a row of one unquoted empty field, with
  * [[blank]] set: whether it is a row depends on how many columns the file has,
  * which the parser does not know (see [[CsvReader]]). A byte-order mark
  * before the first row is not text. An unclosed quote fails naming `source`
  * and the line it opens on; bytes that are no text in the reader's charset
  * fail naming `source`.
  *
  * Allocates one [[StringBuilder]] per field and one [[ArrayBuffer]] per row.
  */
final class CsvRowParser(in: Reader, opts: CsvOptions, source: String = "<input>") {
  private val reader = new PushbackReader(in, 2)
  private val delim = opts.delimiter
  private val quote = opts.quote
  private val esc = opts.escape
  private val nullValue = opts.nullValue

  /** The line the next character is on, counting from 1. A line ends at
    * `\n`, `\r\n` or a lone `\r`, inside a quoted field as well. */
  private var currentLine: Long = 1L
  private var rowLine: Long = 1L
  private var rowBlank: Boolean = false

  /** The line the row last returned by [[readRow]] starts on. */
  def line: Long = rowLine

  /** Whether the row last returned by [[readRow]] was a blank line. */
  def blank: Boolean = rowBlank

  // A byte-order mark is not text; Java's UTF-8 decoder keeps one.
  locally {
    val first = read()
    if (first >= 0 && first != CsvRowParser.ByteOrderMark) reader.unread(first)
  }

  /** Reads the next row from the input.
    *
    * @return `Some(fields)` for a row, or `None` at end of stream.
    */
  def readRow(): Option[Array[String]] = {
    var sawAnything = false
    val fields = new ArrayBuffer[String](8)
    val cur = new java.lang.StringBuilder(64)
    rowLine = currentLine
    rowBlank = false

    // State machine
    val FIELD_START = 0
    val IN_UNQUOTED = 1
    val IN_QUOTED = 2
    val AFTER_QUOTED = 3
    var state = FIELD_START
    var done = false
    var quoteOpenedOn = 0L
    quotedField = false

    while (!done) {
      val r = read()
      if (r < 0) {
        // EOF
        if (state == FIELD_START && !sawAnything) return None
        if (state == IN_QUOTED) throw new IllegalArgumentException(
          s"CSV file '$source': the quoted field that opens on line $quoteOpenedOn is never closed")
        // Flush the in-progress field as the final one: in FIELD_START it's the
        // empty field left by a trailing delimiter at EOF ("a,b," => 3 fields,
        // last empty); in the other states it's the accumulated field content.
        endField(fields, cur)
        done = true
      } else {
        val c = r.toChar
        state match {
          case `FIELD_START` =>
            sawAnything = true
            if (c == quote) {
              state = IN_QUOTED
              quotedField = true
              quoteOpenedOn = currentLine
            } else if (c == delim) {
              endField(fields, cur)
              // stay in FIELD_START
            } else if (c == '\r') {
              consumeLf()
              rowBlank = fields.isEmpty
              endField(fields, cur)
              done = true
            } else if (c == '\n') {
              newLine()
              rowBlank = fields.isEmpty
              endField(fields, cur)
              done = true
            } else {
              cur.append(c)
              state = IN_UNQUOTED
            }

          case `IN_UNQUOTED` =>
            if (c == delim) {
              endField(fields, cur)
              state = FIELD_START
            } else if (c == '\r') {
              consumeLf()
              endField(fields, cur)
              done = true
            } else if (c == '\n') {
              newLine()
              endField(fields, cur)
              done = true
            } else {
              cur.append(c)
            }

          case `IN_QUOTED` =>
            if (esc != quote && c == esc) {
              val n = read()
              if (n >= 0) { cur.append(n.toChar); countLine(n) }
            } else if (c == quote) {
              // could be doubled-quote escape or end of field
              val n = read()
              if (n >= 0 && n.toChar == quote) {
                cur.append(quote)
              } else {
                if (n >= 0) reader.unread(n)
                state = AFTER_QUOTED
              }
            } else {
              cur.append(c)
              countLine(c)
            }

          case `AFTER_QUOTED` =>
            if (c == delim) {
              endField(fields, cur)
              state = FIELD_START
            } else if (c == '\r') {
              consumeLf()
              endField(fields, cur)
              done = true
            } else if (c == '\n') {
              newLine()
              endField(fields, cur)
              done = true
            } else {
              // Lenient: garbage between closing quote and next delimiter — append it.
              cur.append(c)
            }
        }
      }
    }

    Some(fields.toArray)
  }

  /** Whether the field being read opened with a quote: a quoted field is its
    * text even when that is the null string. */
  private var quotedField: Boolean = false

  private def endField(fields: ArrayBuffer[String], cur: java.lang.StringBuilder): Unit = {
    val text = cur.toString
    fields += (if (!quotedField && text == nullValue) null else text)
    cur.setLength(0)
    quotedField = false
  }

  /** A carriage return ends the line, with the line feed after it if any. */
  private def consumeLf(): Unit = {
    val n = read()
    if (n >= 0 && n.toChar != '\n') reader.unread(n)
    newLine()
  }

  /** Count a line break inside a quoted field: `\n`, or a `\r` not followed
    * by `\n` (that `\n` counts instead). */
  private def countLine(c: Int): Unit =
    if (c == '\n') newLine()
    else if (c == '\r') {
      val n = read()
      if (n >= 0) reader.unread(n)
      if (n != '\n') newLine()
    }

  private def newLine(): Unit = currentLine += 1

  /** The next character, or -1 at the end. Bytes that are no text in the
    * charset fail, naming the source but not the line: a buffered reader
    * decodes ahead of the row the parser is on (see `CsvReader`, which finds
    * the line). */
  private def read(): Int =
    try reader.read()
    catch {
      case _: CharacterCodingException => throw new CsvRowParser.InvalidText(
        s"CSV file '$source' is not valid ${opts.charset.name}")
    }

  def close(): Unit = reader.close()
}

object CsvRowParser {
  private val ByteOrderMark: Int = 0xfeff

  /** Bytes that are no text in the reader's charset. */
  final class InvalidText(message: String) extends IllegalArgumentException(message)
}
