package com.transformer.read.csv

import java.io.{PushbackReader, Reader}
import scala.collection.mutable.ArrayBuffer

/** Parses one CSV row at a time from a [[Reader]]. State-machine; supports quoted
  * fields with embedded delimiters, embedded newlines, and doubled-quote escape
  * (or a separate escape char when `escape != quote`).
  *
  * Allocates one [[StringBuilder]] per field and one [[ArrayBuffer]] per row.
  */
final class CsvRowParser(in: Reader, opts: CsvOptions) {
  private val reader = new PushbackReader(in, 2)
  private val delim = opts.delimiter
  private val quote = opts.quote
  private val esc = opts.escape

  /** Reads the next row from the input.
    *
    * @return `Some(fields)` for a row, or `None` at end of stream. Blank lines are
    *         skipped (returns the next non-blank row, or `None`).
    */
  def readRow(): Option[Array[String]] = {
    var sawAnything = false
    val fields = new ArrayBuffer[String](8)
    val cur = new java.lang.StringBuilder(64)

    // State machine
    val FIELD_START = 0
    val IN_UNQUOTED = 1
    val IN_QUOTED = 2
    val AFTER_QUOTED = 3
    var state = FIELD_START
    var done = false

    while (!done) {
      val r = reader.read()
      if (r < 0) {
        // EOF
        if (state == FIELD_START && !sawAnything) return None
        if (state == FIELD_START && sawAnything) {
          // Trailing delimiter on last line: e.g. "a,b,\n" => 3 fields, last empty.
          fields += cur.toString
        } else {
          fields += cur.toString
        }
        done = true
      } else {
        val c = r.toChar
        state match {
          case `FIELD_START` =>
            sawAnything = true
            if (c == quote) {
              state = IN_QUOTED
            } else if (c == delim) {
              fields += ""
              cur.setLength(0)
              // stay in FIELD_START
            } else if (c == '\r') {
              consumeLf()
              fields += ""
              done = true
            } else if (c == '\n') {
              fields += ""
              done = true
            } else {
              cur.append(c)
              state = IN_UNQUOTED
            }

          case `IN_UNQUOTED` =>
            if (c == delim) {
              fields += cur.toString
              cur.setLength(0)
              state = FIELD_START
            } else if (c == '\r') {
              consumeLf()
              fields += cur.toString
              done = true
            } else if (c == '\n') {
              fields += cur.toString
              done = true
            } else {
              cur.append(c)
            }

          case `IN_QUOTED` =>
            if (esc != quote && c == esc) {
              val n = reader.read()
              if (n >= 0) cur.append(n.toChar)
            } else if (c == quote) {
              // could be doubled-quote escape or end of field
              val n = reader.read()
              if (n >= 0 && n.toChar == quote) {
                cur.append(quote)
              } else {
                if (n >= 0) reader.unread(n)
                state = AFTER_QUOTED
              }
            } else {
              cur.append(c)
            }

          case `AFTER_QUOTED` =>
            if (c == delim) {
              fields += cur.toString
              cur.setLength(0)
              state = FIELD_START
            } else if (c == '\r') {
              consumeLf()
              fields += cur.toString
              done = true
            } else if (c == '\n') {
              fields += cur.toString
              done = true
            } else {
              // Lenient: garbage between closing quote and next delimiter — append it.
              cur.append(c)
            }
        }
      }
    }

    // Skip wholly-blank rows (one empty field, no newline contents seen).
    if (!sawAnything) None
    else if (fields.length == 1 && fields(0).isEmpty) readRow()
    else Some(fields.toArray)
  }

  private def consumeLf(): Unit = {
    val n = reader.read()
    if (n >= 0 && n.toChar != '\n') reader.unread(n)
  }

  def close(): Unit = reader.close()
}
