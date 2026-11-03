package com.transformer.sql.plan

import com.transformer.core.DataType
import org.junit.Assert._
import org.junit.Test

/** The Analyzer's typing helpers called directly, for what no query reaches
  * through the binder: each of the binder's callers refuses such a case first,
  * or hands the helper its operands in another form, and the helper keeps its
  * own contract for the next caller. */
class AnalyzerTest {

  /** A BINARY casts to nothing else: an implicit cast of one to another type
    * is refused, never its bytes read as that type. LIKE refuses a BINARY as
    * no text, and a comparison or a CASE as a type the others do not meet,
    * before they ask for the cast. */
  @Test def anImplicitCastOfABinaryToAnotherTypeIsRefused(): Unit = {
    val bytes = LitExpr(Array[Byte](1, 2), DataType.BinaryType)
    Seq(DataType.StringType, DataType.IntType, DataType.BooleanType, DataType.DateType).foreach { t =>
      val e = assertThrows(classOf[IllegalArgumentException], () => { val _ = Analyzer.implicitCast(bytes, t) })
      assertTrue(e.getMessage, e.getMessage.startsWith("Cannot cast BINARY to "))
    }
    assertSame("a BINARY to a BINARY is no cast", bytes, Analyzer.implicitCast(bytes, DataType.BinaryType))
    val nul = LitExpr(null, DataType.NullType)
    assertEquals("a NULL casts to a BINARY", CastExpr(nul, DataType.BinaryType), Analyzer.implicitCast(nul, DataType.BinaryType))
  }

  /** A number written beside a DECIMAL is the decimal it is written as, so
    * `COALESCE(price, 1)` is `price`'s type and `COALESCE(price, -1.5)` too.
    * The binder hands `branches` such numbers as decimals already
    * (`LogicalBuilder.besideDecimals`); given them as bound alone, an INT and
    * the negation of a DOUBLE, it reads them the same. */
  @Test def aNumberBesideADecimalIsTheDecimalItIsWrittenAs(): Unit = {
    val price = ColRefExpr(0, "price", DataType.DecimalType(5, 2))
    assertEquals(DataType.DecimalType(5, 2), Analyzer.branches("COALESCE", Seq(price, LitExpr(1, DataType.IntType)))._2)
    val minus = UnaryOpExpr("-", LitExpr(1.5, DataType.DoubleType), DataType.DoubleType)
    assertEquals(DataType.DecimalType(5, 2), Analyzer.branches("COALESCE", Seq(price, minus))._2)
  }
}
