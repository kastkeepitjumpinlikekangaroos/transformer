package com.transformer.tools.mutation

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/** One test attempt Bazel reported: a target, the shard (0 when the target
  * has one), its status (`PASSED`, `FAILED`, `TIMEOUT`, ...), how long it ran,
  * and where its JUnit XML is. */
final case class TestAttempt(label: String, shard: Int, status: String, millis: Long, cached: Boolean, xml: Option[Path])

/** What one `bazel test` reported in its build event protocol file
  * (`--build_event_json_file`): each test attempt, and each target that did not
  * build. */
final case class BuildEvents(attempts: Vector[TestAttempt], unbuilt: Vector[String]) {
  /** Each target's status: its worst attempt's, so one failing shard fails
    * the target. */
  def statuses: Map[String, String] = attempts.groupBy(_.label).map { case (label, as) =>
    label -> as.map(_.status).maxBy(BuildEvents.severity)
  } ++ unbuilt.map(_ -> "FAILED_TO_BUILD")

  /** Each target's longest attempt, in milliseconds. */
  def millis: Map[String, Long] = attempts.groupBy(_.label).map { case (label, as) => label -> as.map(_.millis).max }
}

object BuildEvents {
  private def severity(status: String): Int = status match {
    case "PASSED" => 0
    case "FLAKY" => 1
    case "FAILED" => 3
    case "TIMEOUT" => 2
    case _ => 4
  }

  def read(file: Path): BuildEvents = {
    val attempts = Vector.newBuilder[TestAttempt]
    val unbuilt = Vector.newBuilder[String]
    if (Files.exists(file)) Files.readAllLines(file).asScala.foreach { line =>
      // A line cut short (a command stopped as it wrote) is no event.
      val parsed =
        if (line.contains("\"testResult\"") || line.contains("\"targetCompleted\""))
          try Some(MiniJson.parse(line)) catch { case _: IllegalArgumentException | _: StringIndexOutOfBoundsException => None }
        else None
      parsed.foreach { event =>
        val id = event.field("id")
        id.field("testResult") match {
          case MiniJson.Obj(_) =>
            val ident = id.field("testResult")
            val result = event.field("testResult")
            val xml = result.field("testActionOutput").elements.collectFirst {
              case o if o.field("name").string == "test.xml" => o.field("uri").string
            }.filter(_.startsWith("file://")).map(u => Paths.get(java.net.URI.create(u)))
            attempts += TestAttempt(
              ident.field("label").string,
              ident.field("shard").number.toInt,
              result.field("status").string,
              result.field("testAttemptDurationMillis").number.toLong,
              result.field("cachedLocally").boolean,
              xml)
          case _ =>
        }
        id.field("targetCompleted") match {
          case MiniJson.Obj(_) =>
            val completed = event.field("completed")
            val failed = completed.field("success") != MiniJson.True || event.field("aborted") != MiniJson.Null
            if (failed) unbuilt += id.field("targetCompleted").field("label").string
          case _ =>
        }
      }
    }
    BuildEvents(attempts.result(), unbuilt.result().distinct)
  }

  /** The tests a JUnit XML file reports failed or in error, as
    * `class.method`. */
  def failedTests(xml: Path): Vector[String] =
    if (!Files.isRegularFile(xml)) Vector.empty
    else {
      val text = Files.readString(xml)
      val testcase = """<testcase\s+name='([^']*)'\s+classname='([^']*)'[^>]*?(/?)>""".r
      testcase.findAllMatchIn(text).flatMap { m =>
        val selfClosed = m.group(3) == "/"
        val body = if (selfClosed) "" else text.substring(m.end, math.max(m.end, text.indexOf("</testcase>", m.end)))
        if (body.contains("<failure") || body.contains("<error")) Some(s"${unescape(m.group(2))}.${unescape(m.group(1))}")
        else None
      }.toVector
    }

  private def unescape(s: String): String =
    s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}

/** A JSON reader for the build event protocol's lines: objects, arrays,
  * strings, numbers, booleans and null. The mutation tool links nothing of the
  * engine it mutates, so it does not use `job.Json`. A missing field reads as
  * [[MiniJson.Null]], whose string is empty and whose number is 0. */
object MiniJson {
  sealed trait Value {
    def field(name: String): Value = this match {
      case Obj(fields) => fields.getOrElse(name, Null)
      case _ => Null
    }
    def elements: Vector[Value] = this match {
      case Arr(values) => values
      case _ => Vector.empty
    }
    def string: String = this match {
      case Str(s) => s
      case Num(d) => d.toString
      case _ => ""
    }
    /** A number, or a number written as a string (the protocol's 64-bit
      * fields). */
    def number: Double = this match {
      case Num(d) => d
      case Str(s) => s.toDoubleOption.getOrElse(0.0)
      case _ => 0.0
    }
    def boolean: Boolean = this == True
  }
  final case class Obj(fields: Map[String, Value]) extends Value
  final case class Arr(values: Vector[Value]) extends Value
  final case class Str(value: String) extends Value
  final case class Num(value: Double) extends Value
  case object True extends Value
  case object False extends Value
  case object Null extends Value

  def parse(text: String): Value = {
    val p = new Parser(text)
    val v = p.value()
    p.space()
    if (!p.atEnd) throw new IllegalArgumentException(s"JSON: text after the value at ${p.pos}")
    v
  }

  private final class Parser(s: String) {
    var pos = 0
    def atEnd: Boolean = pos >= s.length
    private def peek: Char = if (atEnd) '\u0000' else s.charAt(pos)
    def space(): Unit = while (!atEnd && Character.isWhitespace(s.charAt(pos))) pos += 1
    private def expect(c: Char): Unit = {
      if (peek != c) throw new IllegalArgumentException(s"JSON: expected '$c' at $pos")
      pos += 1
    }

    def value(): Value = {
      space()
      peek match {
        case '{' =>
          pos += 1
          val fields = Map.newBuilder[String, Value]
          space()
          if (peek == '}') pos += 1
          else {
            var more = true
            while (more) {
              space()
              val key = string()
              space()
              expect(':')
              fields += key -> value()
              space()
              if (peek == ',') pos += 1 else { expect('}'); more = false }
            }
          }
          Obj(fields.result())
        case '[' =>
          pos += 1
          val values = Vector.newBuilder[Value]
          space()
          if (peek == ']') pos += 1
          else {
            var more = true
            while (more) {
              values += value()
              space()
              if (peek == ',') pos += 1 else { expect(']'); more = false }
            }
          }
          Arr(values.result())
        case '"' => Str(string())
        case 't' => word("true", True)
        case 'f' => word("false", False)
        case 'n' => word("null", Null)
        case _ =>
          val start = pos
          while (!atEnd && "+-0123456789.eE".indexOf(peek.toInt) >= 0) pos += 1
          if (start == pos) throw new IllegalArgumentException(s"JSON: unexpected '$peek' at $pos")
          Num(s.substring(start, pos).toDouble)
      }
    }

    private def word(w: String, v: Value): Value = {
      if (!s.startsWith(w, pos)) throw new IllegalArgumentException(s"JSON: expected $w at $pos")
      pos += w.length
      v
    }

    private def string(): String = {
      expect('"')
      val sb = new java.lang.StringBuilder
      while (peek != '"') {
        if (atEnd) throw new IllegalArgumentException("JSON: an unclosed string")
        val c = s.charAt(pos)
        if (c == '\\') {
          val e = s.charAt(pos + 1)
          pos += 2
          e match {
            case 'n' => sb.append('\n')
            case 't' => sb.append('\t')
            case 'r' => sb.append('\r')
            case 'b' => sb.append('\b')
            case 'f' => sb.append('\f')
            case 'u' =>
              sb.append(Integer.parseInt(s.substring(pos, pos + 4), 16).toChar)
              pos += 4
            case other => sb.append(other)
          }
        } else {
          sb.append(c)
          pos += 1
        }
      }
      pos += 1
      sb.toString
    }
  }
}
