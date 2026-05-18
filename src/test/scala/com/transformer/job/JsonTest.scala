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
