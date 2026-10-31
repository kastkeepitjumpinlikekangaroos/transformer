package com.transformer.job

import org.junit.Assert._
import org.junit.Test

class JsonTest {

  @Test def parsesScalars(): Unit = {
    assertEquals(JsonString("hello"), Json.parse(""""hello""""))
    assertEquals(JsonBool(true), Json.parse("true"))
    assertEquals(JsonBool(false), Json.parse("false"))
    assertEquals(JsonNull, Json.parse("null"))
    assertEquals(JsonNumber(42.0), Json.parse("42"))
    assertEquals(JsonNumber(-3.5), Json.parse("-3.5"))
    assertEquals(JsonNumber(1.5e3), Json.parse("1.5e3"))
  }

  @Test def parsesStringEscapes(): Unit = {
    assertEquals(JsonString("a\"b"), Json.parse(""""a\"b""""))
    assertEquals(JsonString("a\nb"), Json.parse(""""a\nb""""))
    assertEquals(JsonString("a\\b"), Json.parse(""""a\\b""""))
    assertEquals(JsonString("A"), Json.parse(""""A""""))
  }

  /** `Json.escape` writes any string as JSON text a UTF-8 file holds, which
    * `parse` reads back unchanged: every escape, every control character, a
    * surrogate pair as itself, and half of one — or a pair in the wrong order —
    * as `\\u` escapes. Written raw, half a pair is a character UTF-8 cannot
    * encode, so a record holding one was never written. */
  @Test def escapedTextIsUtf8AndParsesBackUnchanged(): Unit = {
    val high = 0xD800.toChar.toString
    val low = 0xDC00.toChar.toString
    val strings = Seq(
      "", "plain", "\"quoted\" \\ /", "a\nb\r\nc\td",
      (0 until 32).map(_.toChar).mkString + 127.toChar,
      new String(Character.toChars(0x1F600)),
      "half " + high + " of " + low + " a pair",
      low + high,
      high)
    strings.foreach { s =>
      val text = "\"" + Json.escape(s) + "\""
      assertTrue(s"escaped text UTF-8 cannot encode: ${text.map(_.toInt)}",
        java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(text))
      assertEquals(JsonString(s), Json.parse(text))
    }
  }

  @Test def parsesEmptyContainers(): Unit = {
    assertEquals(JsonObject(Map.empty), Json.parse("{}"))
    assertEquals(JsonArray(Vector.empty), Json.parse("[]"))
  }

  @Test def parsesNestedObject(): Unit = {
    val j = Json.parse(
      """{
        |  "path": "data/x.csv",
        |  "options": {"delimiter": ",", "header": true, "batchSize": 1000},
        |  "cache": false
        |}""".stripMargin)
    val obj = j.asObject("test")
    assertEquals("data/x.csv", obj.requiredString("path", "test"))
    assertEquals(Some(false), obj.optBool("cache", "test"))
    val opts = obj.optStringMap("options", "test").get
    assertEquals("," , opts("delimiter"))
    assertEquals("true", opts("header"))
    assertEquals("1000", opts("batchSize"))
  }

  @Test def parsesArrays(): Unit = {
    val j = Json.parse("""[1, "two", true, null]""")
    j match {
      case JsonArray(vs) =>
        assertEquals(4, vs.length)
        assertEquals(JsonNumber(1.0), vs(0))
        assertEquals(JsonString("two"), vs(1))
        assertEquals(JsonBool(true), vs(2))
        assertEquals(JsonNull, vs(3))
      case other => fail(s"Expected array, got $other")
    }
  }

  @Test def errorsOnMissingRequiredField(): Unit = {
    val obj = Json.parse("""{"foo": "bar"}""").asObject("ctx")
    val ex = assertThrows(classOf[IllegalArgumentException],
      () => obj.requiredString("baz", "ctx"))
    assertTrue(ex.getMessage.contains("missing required field 'baz'"))
  }

  @Test def errorsOnTypeMismatch(): Unit = {
    val obj = Json.parse("""{"x": 5}""").asObject("ctx")
    val ex = assertThrows(classOf[IllegalArgumentException],
      () => obj.requiredString("x", "ctx"))
    assertTrue(ex.getMessage.contains("expected string for 'x'"))
  }

  @Test def errorsOnTrailingContent(): Unit = {
    val ex = assertThrows(classOf[IllegalArgumentException],
      () => Json.parse("""{"a": 1} junk"""))
    assertTrue(ex.getMessage.contains("trailing content"))
  }

  @Test def errorsOnUnterminatedString(): Unit = {
    val ex = assertThrows(classOf[IllegalArgumentException],
      () => Json.parse(""""abc"""))
    assertTrue(ex.getMessage.contains("Unterminated string"))
  }

  /** A key written twice in one object is an error naming it; the second
    * value used to replace the first unsaid (plans/robustness/27). The same
    * key in two objects is two keys. */
  @Test def errorsOnAKeyWrittenTwice(): Unit = {
    val ex = assertThrows(classOf[IllegalArgumentException],
      () => Json.parse("""{"path": "t.csv", "path": "missing.csv"}"""))
    assertEquals("JSON parse error at position 18: the key \"path\" is written twice in one object", ex.getMessage)
    val nested = assertThrows(classOf[IllegalArgumentException],
      () => Json.parse("""{"options": {"header": true, "header": false}}"""))
    assertTrue(nested.getMessage, nested.getMessage.contains("the key \"header\" is written twice"))
    assertEquals(JsonString("x"), Json.parse("""{"a": {"k": 1}, "b": {"k": "x"}}""").asObject("ctx")
      .get("b").get.asObject("ctx").get("k").get)
  }

  @Test def stringMapRejectsNestedObjects(): Unit = {
    val obj = Json.parse("""{"o": {"k": {"nested": 1}}}""").asObject("ctx")
    val ex = assertThrows(classOf[IllegalArgumentException],
      () => obj.optStringMap("o", "ctx"))
    assertTrue(ex.getMessage.contains("must be a scalar"))
  }

  @Test def numberStringificationPrefersInteger(): Unit = {
    assertEquals("1000", JsonNumber(1000.0).stringValue)
    assertEquals("-7", JsonNumber(-7.0).stringValue)
    assertEquals("3.14", JsonNumber(3.14).stringValue)
  }
}
