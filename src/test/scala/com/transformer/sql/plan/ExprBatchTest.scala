package com.transformer.sql.plan

import com.transformer.core._
import org.junit.Assert._
import org.junit.Test

import java.util

/** Parity tests for [[Expr.evalVec]] overrides.
  *
  * Each test builds a [[ColumnarBatch]] with controlled values and NULL
  * placements, evaluates an [[Expr]] both row-by-row via [[Expr.eval]] and
  * batch-at-a-time via [[Expr.evalVec]], and asserts the two paths produce
  * the same value (or both NULL) at every row.
  *
  * Why this exists: the codebase used to have no such test. `evalVec`
  * overrides were validated only indirectly through [[com.transformer.sql.exec.SqlEngine]]
  * end-to-end cases — which made every new override a correctness gamble.
  * This suite is the gate: any subtype that overrides `evalVec` MUST add a
  * parity case here before merging.
  *
  * Conventions:
  *   - N=64 rows so loops are large enough to exercise pattern dispatch but
  *     small enough to inspect on failure.
  *   - NULL placements: none, every-other, all.
  *   - Result comparison normalizes to the declared `dataType` so e.g. a
  *     `Double` returned by `eval` and a `Float` stored by `evalVec` (real
  *     existing behaviour on FloatType arithmetic — `Ops.arith` widens to
  *     Double, FloatVector narrows on store) both compare to the same Float.
  */
class ExprBatchTest {

  private val N = 64

  // ---------------------------------------------------------------------------
  // Column builders. Each `nullAt: Int => Boolean` predicate decides nullness
  // per row independently from the value generator.
  // ---------------------------------------------------------------------------

  private def intCol(values: Int => Int, nullAt: Int => Boolean = _ => false): IntVector = {
    val arr = new Array[Int](N)
    val nulls = new util.BitSet(N)
    var i = 0
    while (i < N) { if (nullAt(i)) nulls.set(i) else arr(i) = values(i); i += 1 }
    new IntVector(arr, nulls, N)
  }

  private def longCol(values: Int => Long, nullAt: Int => Boolean = _ => false): LongVector = {
    val arr = new Array[Long](N)
    val nulls = new util.BitSet(N)
    var i = 0
    while (i < N) { if (nullAt(i)) nulls.set(i) else arr(i) = values(i); i += 1 }
    new LongVector(arr, nulls, N)
  }

  private def floatCol(values: Int => Float, nullAt: Int => Boolean = _ => false): FloatVector = {
    val arr = new Array[Float](N)
    val nulls = new util.BitSet(N)
    var i = 0
    while (i < N) { if (nullAt(i)) nulls.set(i) else arr(i) = values(i); i += 1 }
    new FloatVector(arr, nulls, N)
  }

  private def doubleCol(values: Int => Double, nullAt: Int => Boolean = _ => false): DoubleVector = {
    val arr = new Array[Double](N)
    val nulls = new util.BitSet(N)
    var i = 0
    while (i < N) { if (nullAt(i)) nulls.set(i) else arr(i) = values(i); i += 1 }
    new DoubleVector(arr, nulls, N)
  }

  private def boolCol(values: Int => Boolean, nullAt: Int => Boolean = _ => false): BooleanVector = {
    val arr = new Array[Boolean](N)
    val nulls = new util.BitSet(N)
    var i = 0
    while (i < N) { if (nullAt(i)) nulls.set(i) else arr(i) = values(i); i += 1 }
    new BooleanVector(arr, nulls, N)
  }

  private def stringCol(values: Int => String, nullAt: Int => Boolean = _ => false): StringVector = {
    val arr = new Array[String](N)
    var i = 0
    while (i < N) { arr(i) = if (nullAt(i)) null else values(i); i += 1 }
    new StringVector(arr, N)
  }

  /** Build a batch from a list of (fieldName, column) pairs in order. */
  private def batch(cols: (String, ColumnVector)*): ColumnarBatch = {
    val schema = Schema(cols.iterator.map { case (n, c) => Field(n, c.dataType) }.toVector)
    ColumnarBatch.fromColumns(schema, cols.iterator.map(_._2).toArray, N)
  }

  // ---------------------------------------------------------------------------
  // Null mask predicates.
  // ---------------------------------------------------------------------------

  private val NoNulls: Int => Boolean = _ => false
  private val EveryOther: Int => Boolean = _ % 2 == 0
  private val AllNulls: Int => Boolean = _ => true

  // ---------------------------------------------------------------------------
  // Parity assertion.
  //
  // `eval` returns the operand's declared boxed shape (e.g. java.lang.Long for
  // LongType, java.lang.Double for arithmetic on FloatType — `Ops.arith`
  // widens). `evalVec` returns a vector typed by `expr.dataType`, whose
  // `getBoxed` returns the type matching that schema. Normalize both to
  // `expr.dataType` before comparing so the parity check reflects the value a
  // downstream batch would actually see.
  // ---------------------------------------------------------------------------

  private def assertParity(expr: Expr, b: ColumnarBatch): Unit = {
    val vec = expr.evalVec(b)
    assertEquals(s"evalVec.dataType for $expr", expr.dataType, vec.dataType)
    val n = b.numRows
    var i = 0
    while (i < n) {
      val byEval: Any = expr.eval(b, i)
      val byVec: Any = if (vec.isNull(i)) null else vec.getBoxed(i)
      val normEval = normalize(byEval, expr.dataType)
      val normVec = normalize(byVec, expr.dataType)
      val ok = (normEval == null && normVec == null) ||
        (normEval != null && normVec != null && normEval == normVec)
      if (!ok) {
        fail(
          s"parity mismatch at row $i for $expr (dt=${expr.dataType}): " +
            s"eval=$byEval (norm=$normEval, ${classOf(byEval)}) vs " +
            s"vec=$byVec (norm=$normVec, ${classOf(byVec)})"
        )
      }
      i += 1
    }
  }

  private def classOf(v: Any): String = if (v == null) "null" else v.getClass.getName

  private def normalize(v: Any, dt: DataType): Any = {
    if (v == null) return null
    dt match {
      case DataType.IntType => v.asInstanceOf[Number].intValue
      case DataType.LongType => v.asInstanceOf[Number].longValue
      case DataType.FloatType => v.asInstanceOf[Number].floatValue
      case DataType.DoubleType => v.asInstanceOf[Number].doubleValue
      case DataType.BooleanType =>
        v match {
          case b: Boolean => b
          case b: java.lang.Boolean => b.booleanValue
          case _ => v
        }
      case DataType.DateType =>
        v match {
          case d: java.time.LocalDate => d.toEpochDay.toInt
          case n: Number => n.intValue
          case _ => v
        }
      case DataType.TimestampType =>
        v match {
          case i: java.time.Instant => i.getEpochSecond * 1000000L + i.getNano / 1000L
          case n: Number => n.longValue
          case _ => v
        }
      case _ => v
    }
  }

  // ===========================================================================
  // LitExpr
  // ===========================================================================

  @Test def litExprIntPrimitive(): Unit = {
    val b = batch("x" -> intCol(_ => 0))
    assertParity(LitExpr(42, DataType.IntType), b)
  }

  @Test def litExprLongPrimitive(): Unit = {
    val b = batch("x" -> intCol(_ => 0))
    assertParity(LitExpr(Long.MaxValue, DataType.LongType), b)
  }

  @Test def litExprFloatPrimitive(): Unit = {
    val b = batch("x" -> intCol(_ => 0))
    assertParity(LitExpr(3.14f, DataType.FloatType), b)
  }

  @Test def litExprDoublePrimitive(): Unit = {
    val b = batch("x" -> intCol(_ => 0))
    assertParity(LitExpr(2.71828, DataType.DoubleType), b)
  }

  @Test def litExprBoolPrimitive(): Unit = {
    val b = batch("x" -> intCol(_ => 0))
    assertParity(LitExpr(true, DataType.BooleanType), b)
    assertParity(LitExpr(false, DataType.BooleanType), b)
  }

  @Test def litExprString(): Unit = {
    val b = batch("x" -> intCol(_ => 0))
    assertParity(LitExpr("hello", DataType.StringType), b)
    assertParity(LitExpr("", DataType.StringType), b)
  }

  @Test def litExprNullOfEachType(): Unit = {
    val b = batch("x" -> intCol(_ => 0))
    Seq(DataType.IntType, DataType.LongType, DataType.FloatType, DataType.DoubleType,
        DataType.BooleanType, DataType.StringType, DataType.DateType).foreach { dt =>
      assertParity(LitExpr(null, dt), b)
    }
  }

  // ===========================================================================
  // ColRefExpr — each column type, with/without nulls
  // ===========================================================================

  @Test def colRefIntNoNulls(): Unit = {
    val b = batch("x" -> intCol(i => i * 2))
    assertParity(ColRefExpr(0, "x", DataType.IntType), b)
  }

  @Test def colRefIntMixedNulls(): Unit = {
    val b = batch("x" -> intCol(i => i * 2, EveryOther))
    assertParity(ColRefExpr(0, "x", DataType.IntType), b)
  }

  @Test def colRefLongMixedNulls(): Unit = {
    val b = batch("x" -> longCol(i => i.toLong * 100L, EveryOther))
    assertParity(ColRefExpr(0, "x", DataType.LongType), b)
  }

  @Test def colRefFloatMixedNulls(): Unit = {
    val b = batch("x" -> floatCol(i => i * 1.5f, EveryOther))
    assertParity(ColRefExpr(0, "x", DataType.FloatType), b)
  }

  @Test def colRefDoubleMixedNulls(): Unit = {
    val b = batch("x" -> doubleCol(i => i * 1.5, EveryOther))
    assertParity(ColRefExpr(0, "x", DataType.DoubleType), b)
  }

  @Test def colRefBoolMixedNulls(): Unit = {
    val b = batch("x" -> boolCol(i => i % 3 == 0, EveryOther))
    assertParity(ColRefExpr(0, "x", DataType.BooleanType), b)
  }

  @Test def colRefStringMixedNulls(): Unit = {
    val b = batch("x" -> stringCol(i => s"row$i", EveryOther))
    assertParity(ColRefExpr(0, "x", DataType.StringType), b)
  }

  @Test def colRefAllNulls(): Unit = {
    val b = batch("x" -> intCol(_ => 0, AllNulls))
    assertParity(ColRefExpr(0, "x", DataType.IntType), b)
  }

  // ===========================================================================
  // CastExpr — representative pairs from each numeric/string axis
  // ===========================================================================

  @Test def castIntToLong(): Unit = {
    val b = batch("x" -> intCol(i => i, EveryOther))
    assertParity(CastExpr(ColRefExpr(0, "x", DataType.IntType), DataType.LongType), b)
  }

  @Test def castIntToDouble(): Unit = {
    val b = batch("x" -> intCol(i => i))
    assertParity(CastExpr(ColRefExpr(0, "x", DataType.IntType), DataType.DoubleType), b)
  }

  @Test def castLongToInt(): Unit = {
    val b = batch("x" -> longCol(i => i.toLong, EveryOther))
    assertParity(CastExpr(ColRefExpr(0, "x", DataType.LongType), DataType.IntType), b)
  }

  @Test def castDoubleToFloat(): Unit = {
    val b = batch("x" -> doubleCol(i => i * 0.5, EveryOther))
    assertParity(CastExpr(ColRefExpr(0, "x", DataType.DoubleType), DataType.FloatType), b)
  }

  @Test def castDoubleToLong(): Unit = {
    val b = batch("x" -> doubleCol(i => i * 1.7))
    assertParity(CastExpr(ColRefExpr(0, "x", DataType.DoubleType), DataType.LongType), b)
  }

  @Test def castIntToString(): Unit = {
    val b = batch("x" -> intCol(i => i, EveryOther))
    assertParity(CastExpr(ColRefExpr(0, "x", DataType.IntType), DataType.StringType), b)
  }

  @Test def castBoolToInt(): Unit = {
    val b = batch("x" -> boolCol(i => i % 2 == 0))
    assertParity(CastExpr(ColRefExpr(0, "x", DataType.BooleanType), DataType.IntType), b)
  }

  @Test def castSameTypeIdentity(): Unit = {
    val b = batch("x" -> intCol(i => i, EveryOther))
    assertParity(CastExpr(ColRefExpr(0, "x", DataType.IntType), DataType.IntType), b)
  }

  // ===========================================================================
  // UnaryOpExpr: +, -, NOT
  // ===========================================================================

  @Test def unaryPlusLong(): Unit = {
    val b = batch("x" -> longCol(i => i.toLong * 10L, EveryOther))
    assertParity(UnaryOpExpr("+", ColRefExpr(0, "x", DataType.LongType), DataType.LongType), b)
  }

  @Test def unaryNegateInt(): Unit = {
    val b = batch("x" -> intCol(i => i - 32, EveryOther))
    assertParity(UnaryOpExpr("-", ColRefExpr(0, "x", DataType.IntType), DataType.IntType), b)
  }

  @Test def unaryNegateLong(): Unit = {
    val b = batch("x" -> longCol(i => i.toLong * 1000L - 12345L))
    assertParity(UnaryOpExpr("-", ColRefExpr(0, "x", DataType.LongType), DataType.LongType), b)
  }

  @Test def unaryNegateFloat(): Unit = {
    val b = batch("x" -> floatCol(i => i.toFloat - 8f, EveryOther))
    assertParity(UnaryOpExpr("-", ColRefExpr(0, "x", DataType.FloatType), DataType.FloatType), b)
  }

  @Test def unaryNegateDouble(): Unit = {
    val b = batch("x" -> doubleCol(i => math.sin(i.toDouble), EveryOther))
    assertParity(UnaryOpExpr("-", ColRefExpr(0, "x", DataType.DoubleType), DataType.DoubleType), b)
  }

  @Test def unaryNotBoolWithNulls(): Unit = {
    val b = batch("x" -> boolCol(i => i % 3 == 0, EveryOther))
    assertParity(UnaryOpExpr("NOT", ColRefExpr(0, "x", DataType.BooleanType), DataType.BooleanType), b)
  }

  @Test def unaryNotAllNulls(): Unit = {
    val b = batch("x" -> boolCol(_ => true, AllNulls))
    assertParity(UnaryOpExpr("NOT", ColRefExpr(0, "x", DataType.BooleanType), DataType.BooleanType), b)
  }

  // ===========================================================================
  // BinOpExpr: AND/OR three-valued logic
  // ===========================================================================

  /** Build a 64-row batch covering every (left, right) ∈ {T,F,NULL}² truth
    * combination. The 9 combinations repeat through the batch. */
  private def threeValuedBatch(): ColumnarBatch = {
    val ls = new Array[Boolean](N)
    val rs = new Array[Boolean](N)
    val ln = new util.BitSet(N)
    val rn = new util.BitSet(N)
    var i = 0
    while (i < N) {
      // 0=T, 1=F, 2=NULL
      val li = i % 3
      val ri = (i / 3) % 3
      li match {
        case 0 => ls(i) = true
        case 1 => ls(i) = false
        case 2 => ln.set(i)
      }
      ri match {
        case 0 => rs(i) = true
        case 1 => rs(i) = false
        case 2 => rn.set(i)
      }
      i += 1
    }
    val lCol = new BooleanVector(ls, ln, N)
    val rCol = new BooleanVector(rs, rn, N)
    batch("l" -> lCol, "r" -> rCol)
  }

  @Test def binOpAndThreeValued(): Unit = {
    val b = threeValuedBatch()
    assertParity(
      BinOpExpr("AND",
        ColRefExpr(0, "l", DataType.BooleanType),
        ColRefExpr(1, "r", DataType.BooleanType),
        DataType.BooleanType),
      b)
  }

  @Test def binOpOrThreeValued(): Unit = {
    val b = threeValuedBatch()
    assertParity(
      BinOpExpr("OR",
        ColRefExpr(0, "l", DataType.BooleanType),
        ColRefExpr(1, "r", DataType.BooleanType),
        DataType.BooleanType),
      b)
  }

  // ===========================================================================
  // BinOpExpr: arithmetic (+, -, *, /, %) — both sides null, one side null,
  // neither side null. Includes divide-by-zero (returns NULL).
  // ===========================================================================

  @Test def binOpAddLong(): Unit = {
    val b = batch(
      "l" -> longCol(i => i.toLong * 7L, i => i % 4 == 0),
      "r" -> longCol(i => i.toLong * 11L, i => i % 5 == 0)
    )
    assertParity(
      BinOpExpr("+",
        ColRefExpr(0, "l", DataType.LongType),
        ColRefExpr(1, "r", DataType.LongType),
        DataType.LongType),
      b)
  }

  @Test def binOpSubInt(): Unit = {
    val b = batch(
      "l" -> intCol(i => i * 3, i => i % 6 == 0),
      "r" -> intCol(i => i - 5)
    )
    assertParity(
      BinOpExpr("-",
        ColRefExpr(0, "l", DataType.IntType),
        ColRefExpr(1, "r", DataType.IntType),
        DataType.IntType),
      b)
  }

  @Test def binOpMulFloat(): Unit = {
    val b = batch(
      "l" -> floatCol(i => i * 0.25f, EveryOther),
      "r" -> floatCol(i => (i + 1) * 0.5f)
    )
    assertParity(
      BinOpExpr("*",
        ColRefExpr(0, "l", DataType.FloatType),
        ColRefExpr(1, "r", DataType.FloatType),
        DataType.FloatType),
      b)
  }

  @Test def binOpDivLongWithZeros(): Unit = {
    // Force some divisors to zero — both paths must produce NULL.
    val b = batch(
      "l" -> longCol(i => (i.toLong + 1L) * 100L),
      "r" -> longCol(i => if (i % 7 == 0) 0L else i.toLong + 1L)
    )
    assertParity(
      BinOpExpr("/",
        ColRefExpr(0, "l", DataType.LongType),
        ColRefExpr(1, "r", DataType.LongType),
        DataType.LongType),
      b)
  }

  @Test def binOpModIntWithZeros(): Unit = {
    val b = batch(
      "l" -> intCol(i => i * 13),
      "r" -> intCol(i => if (i % 9 == 0) 0 else i + 1)
    )
    assertParity(
      BinOpExpr("%",
        ColRefExpr(0, "l", DataType.IntType),
        ColRefExpr(1, "r", DataType.IntType),
        DataType.IntType),
      b)
  }

  @Test def binOpDivDouble(): Unit = {
    val b = batch(
      "l" -> doubleCol(i => i * 1.0, EveryOther),
      "r" -> doubleCol(i => (i + 1) * 0.5)
    )
    assertParity(
      BinOpExpr("/",
        ColRefExpr(0, "l", DataType.DoubleType),
        ColRefExpr(1, "r", DataType.DoubleType),
        DataType.DoubleType),
      b)
  }

  @Test def binOpAddBothSidesAllNull(): Unit = {
    val b = batch(
      "l" -> longCol(_ => 0L, AllNulls),
      "r" -> longCol(_ => 0L, AllNulls)
    )
    assertParity(
      BinOpExpr("+",
        ColRefExpr(0, "l", DataType.LongType),
        ColRefExpr(1, "r", DataType.LongType),
        DataType.LongType),
      b)
  }

  // ===========================================================================
  // BinOpExpr: comparison (=, !=, <, <=, >, >=) — numeric + string
  // ===========================================================================

  @Test def binOpEqLong(): Unit = {
    val b = batch(
      "l" -> longCol(i => (i % 4).toLong, EveryOther),
      "r" -> longCol(i => (i % 4).toLong)
    )
    Seq("=", "==", "<>", "!=", "<", "<=", ">", ">=").foreach { op =>
      assertParity(
        BinOpExpr(op,
          ColRefExpr(0, "l", DataType.LongType),
          ColRefExpr(1, "r", DataType.LongType),
          DataType.BooleanType),
        b)
    }
  }

  @Test def binOpCompareDouble(): Unit = {
    val b = batch(
      "l" -> doubleCol(i => i * 0.1, EveryOther),
      "r" -> doubleCol(i => (i + 1) * 0.1, i => i % 5 == 0)
    )
    Seq("=", "<>", "<", "<=", ">", ">=").foreach { op =>
      assertParity(
        BinOpExpr(op,
          ColRefExpr(0, "l", DataType.DoubleType),
          ColRefExpr(1, "r", DataType.DoubleType),
          DataType.BooleanType),
        b)
    }
  }

  @Test def binOpCompareString(): Unit = {
    val b = batch(
      "l" -> stringCol(i => f"a$i%03d", EveryOther),
      "r" -> stringCol(i => f"a${i % 4}%03d")
    )
    Seq("=", "<>", "<", "<=", ">", ">=").foreach { op =>
      assertParity(
        BinOpExpr(op,
          ColRefExpr(0, "l", DataType.StringType),
          ColRefExpr(1, "r", DataType.StringType),
          DataType.BooleanType),
        b)
    }
  }

  /** Cross-numeric comparison (Int vs Long). The optimizer doesn't always
    * normalize this through a CAST, so VecOps.compare falls back to its
    * generic accessor path — verify it still matches `eval`. */
  @Test def binOpCompareCrossNumeric(): Unit = {
    val b = batch(
      "l" -> intCol(i => i, EveryOther),
      "r" -> longCol(i => i.toLong - 1L)
    )
    assertParity(
      BinOpExpr(">",
        ColRefExpr(0, "l", DataType.IntType),
        ColRefExpr(1, "r", DataType.LongType),
        DataType.BooleanType),
      b)
  }

  // ===========================================================================
  // BinOpExpr: || string concatenation
  // ===========================================================================

  @Test def binOpConcatStringsWithNulls(): Unit = {
    val b = batch(
      "l" -> stringCol(i => s"left$i", i => i % 3 == 0),
      "r" -> stringCol(i => s"right$i", i => i % 5 == 0)
    )
    assertParity(
      BinOpExpr("||",
        ColRefExpr(0, "l", DataType.StringType),
        ColRefExpr(1, "r", DataType.StringType),
        DataType.StringType),
      b)
  }

  // ===========================================================================
  // IsNullExpr — all-null / no-null / mixed-null input, both IS NULL / IS NOT
  // NULL polarities.
  // ===========================================================================

  @Test def isNullMixedInput(): Unit = {
    val b = batch("x" -> intCol(i => i, EveryOther))
    assertParity(IsNullExpr(ColRefExpr(0, "x", DataType.IntType), negated = false), b)
    assertParity(IsNullExpr(ColRefExpr(0, "x", DataType.IntType), negated = true), b)
  }

  @Test def isNullNoNullInput(): Unit = {
    val b = batch("x" -> longCol(i => i.toLong))
    assertParity(IsNullExpr(ColRefExpr(0, "x", DataType.LongType), negated = false), b)
    assertParity(IsNullExpr(ColRefExpr(0, "x", DataType.LongType), negated = true), b)
  }

  @Test def isNullAllNullInput(): Unit = {
    val b = batch("x" -> stringCol(i => s"v$i", AllNulls))
    assertParity(IsNullExpr(ColRefExpr(0, "x", DataType.StringType), negated = false), b)
    assertParity(IsNullExpr(ColRefExpr(0, "x", DataType.StringType), negated = true), b)
  }

  @Test def isNullOverComputedExpr(): Unit = {
    // Wrap a binary op so the input to IsNullExpr is a computed column with
    // NULL holes from divide-by-zero rather than a raw column.
    val b = batch(
      "l" -> longCol(i => i.toLong),
      "r" -> longCol(i => if (i % 4 == 0) 0L else i.toLong)
    )
    val divExpr = BinOpExpr("/",
      ColRefExpr(0, "l", DataType.LongType),
      ColRefExpr(1, "r", DataType.LongType),
      DataType.LongType)
    assertParity(IsNullExpr(divExpr, negated = false), b)
    assertParity(IsNullExpr(divExpr, negated = true), b)
  }

  // ===========================================================================
  // Nested expressions — verify overrides compose. A common shape:
  //   (a + b) > 0 AND NOT(c IS NULL)
  // exercises BinOp arithmetic + comparison + AND + UnaryOp NOT + IsNull.
  // ===========================================================================

  @Test def nestedAndChain(): Unit = {
    val b = batch(
      "a" -> longCol(i => i.toLong - 32L, i => i % 7 == 0),
      "b" -> longCol(i => i.toLong * 2L, i => i % 11 == 0),
      "c" -> stringCol(i => s"s$i", EveryOther)
    )
    val sum = BinOpExpr("+",
      ColRefExpr(0, "a", DataType.LongType),
      ColRefExpr(1, "b", DataType.LongType),
      DataType.LongType)
    val gt = BinOpExpr(">",
      sum, LitExpr(0L, DataType.LongType), DataType.BooleanType)
    val notNull = UnaryOpExpr("NOT",
      IsNullExpr(ColRefExpr(2, "c", DataType.StringType), negated = false),
      DataType.BooleanType)
    val and = BinOpExpr("AND", gt, notNull, DataType.BooleanType)
    assertParity(and, b)
  }

  // ===========================================================================
  // FuncExpr (Phase 5)
  // ===========================================================================

  @Test def funcCoalesceTwoLong(): Unit = {
    val b = batch(
      "a" -> longCol(i => i.toLong, i => i % 3 == 0),
      "b" -> longCol(i => i.toLong * 10L, i => i % 5 == 0)
    )
    assertParity(
      FuncExpr("COALESCE",
        Seq(ColRefExpr(0, "a", DataType.LongType), ColRefExpr(1, "b", DataType.LongType)),
        DataType.LongType),
      b)
  }

  @Test def funcCoalesceWithLiteralFallback(): Unit = {
    val b = batch("a" -> longCol(i => i.toLong, EveryOther))
    assertParity(
      FuncExpr("COALESCE",
        Seq(ColRefExpr(0, "a", DataType.LongType), LitExpr(-1L, DataType.LongType)),
        DataType.LongType),
      b)
  }

  @Test def funcCoalesceAllNull(): Unit = {
    val b = batch(
      "a" -> longCol(_ => 0L, AllNulls),
      "b" -> longCol(_ => 0L, AllNulls)
    )
    assertParity(
      FuncExpr("COALESCE",
        Seq(ColRefExpr(0, "a", DataType.LongType), ColRefExpr(1, "b", DataType.LongType)),
        DataType.LongType),
      b)
  }

  @Test def funcCoalesceString(): Unit = {
    val b = batch(
      "a" -> stringCol(i => s"a$i", i => i % 4 == 0),
      "b" -> stringCol(i => s"b$i", i => i % 6 == 0)
    )
    assertParity(
      FuncExpr("COALESCE",
        Seq(ColRefExpr(0, "a", DataType.StringType), ColRefExpr(1, "b", DataType.StringType)),
        DataType.StringType),
      b)
  }

  @Test def funcLength(): Unit = {
    val b = batch("s" -> stringCol(i => "x" * (i % 7), EveryOther))
    assertParity(
      FuncExpr("LENGTH", Seq(ColRefExpr(0, "s", DataType.StringType)), DataType.IntType),
      b)
  }

  @Test def funcUpperLower(): Unit = {
    val b = batch("s" -> stringCol(i => s"MixedCase$i", EveryOther))
    assertParity(
      FuncExpr("UPPER", Seq(ColRefExpr(0, "s", DataType.StringType)), DataType.StringType),
      b)
    assertParity(
      FuncExpr("LOWER", Seq(ColRefExpr(0, "s", DataType.StringType)), DataType.StringType),
      b)
  }

  @Test def funcTrim(): Unit = {
    val b = batch("s" -> stringCol(i => s"   pad$i   ", EveryOther))
    assertParity(
      FuncExpr("TRIM", Seq(ColRefExpr(0, "s", DataType.StringType)), DataType.StringType),
      b)
  }

  @Test def funcConcatNullPropagating(): Unit = {
    val b = batch(
      "a" -> stringCol(i => s"a$i", i => i % 3 == 0),
      "b" -> stringCol(i => s"b$i", i => i % 5 == 0)
    )
    assertParity(
      FuncExpr("CONCAT",
        Seq(ColRefExpr(0, "a", DataType.StringType), ColRefExpr(1, "b", DataType.StringType)),
        DataType.StringType),
      b)
  }

  @Test def funcConcatThreeArgs(): Unit = {
    val b = batch(
      "a" -> stringCol(i => s"a$i"),
      "b" -> stringCol(i => "-"),
      "c" -> stringCol(i => s"c$i", EveryOther)
    )
    assertParity(
      FuncExpr("CONCAT",
        Seq(
          ColRefExpr(0, "a", DataType.StringType),
          ColRefExpr(1, "b", DataType.StringType),
          ColRefExpr(2, "c", DataType.StringType)),
        DataType.StringType),
      b)
  }

  @Test def funcSubstringTwoArg(): Unit = {
    val b = batch("s" -> stringCol(i => s"prefix-$i-suffix", EveryOther))
    assertParity(
      FuncExpr("SUBSTRING",
        Seq(ColRefExpr(0, "s", DataType.StringType), LitExpr(3, DataType.IntType)),
        DataType.StringType),
      b)
  }

  @Test def funcSubstringThreeArg(): Unit = {
    val b = batch("s" -> stringCol(i => s"abcdefghij$i", EveryOther))
    assertParity(
      FuncExpr("SUBSTRING",
        Seq(
          ColRefExpr(0, "s", DataType.StringType),
          LitExpr(2, DataType.IntType),
          LitExpr(4, DataType.IntType)),
        DataType.StringType),
      b)
  }

  @Test def funcAbsInt(): Unit = {
    val b = batch("x" -> intCol(i => i - 32, EveryOther))
    assertParity(
      FuncExpr("ABS", Seq(ColRefExpr(0, "x", DataType.IntType)), DataType.IntType),
      b)
  }

  @Test def funcAbsDouble(): Unit = {
    val b = batch("x" -> doubleCol(i => (i - 32).toDouble * 0.5, EveryOther))
    assertParity(
      FuncExpr("ABS", Seq(ColRefExpr(0, "x", DataType.DoubleType)), DataType.DoubleType),
      b)
  }

  @Test def funcFloorCeil(): Unit = {
    val b = batch("x" -> doubleCol(i => (i - 16) * 0.37, EveryOther))
    assertParity(
      FuncExpr("FLOOR", Seq(ColRefExpr(0, "x", DataType.DoubleType)), DataType.DoubleType),
      b)
    assertParity(
      FuncExpr("CEIL", Seq(ColRefExpr(0, "x", DataType.DoubleType)), DataType.DoubleType),
      b)
    assertParity(
      FuncExpr("CEILING", Seq(ColRefExpr(0, "x", DataType.DoubleType)), DataType.DoubleType),
      b)
  }

  @Test def funcRoundOneArg(): Unit = {
    val b = batch("x" -> doubleCol(i => i * 0.12345, EveryOther))
    assertParity(
      FuncExpr("ROUND", Seq(ColRefExpr(0, "x", DataType.DoubleType)), DataType.DoubleType),
      b)
  }

  @Test def funcRoundTwoArg(): Unit = {
    val b = batch("x" -> doubleCol(i => i * 0.12345))
    assertParity(
      FuncExpr("ROUND",
        Seq(ColRefExpr(0, "x", DataType.DoubleType), LitExpr(2, DataType.IntType)),
        DataType.DoubleType),
      b)
  }

  @Test def funcTruncWithNullScalePropagates(): Unit = {
    // TRUNC eval returns NULL when scale arg is NULL — verify vector matches.
    val b = batch(
      "x" -> doubleCol(i => i * 1.5),
      "scale" -> intCol(_ => 1, EveryOther)
    )
    assertParity(
      FuncExpr("TRUNC",
        Seq(ColRefExpr(0, "x", DataType.DoubleType), ColRefExpr(1, "scale", DataType.IntType)),
        DataType.DoubleType),
      b)
  }

  @Test def funcIfBranch(): Unit = {
    val b = batch(
      "cond" -> boolCol(i => i % 3 == 0, i => i % 7 == 0),
      "a" -> longCol(i => i.toLong),
      "b" -> longCol(i => -i.toLong, i => i % 5 == 0)
    )
    assertParity(
      FuncExpr("IF",
        Seq(
          ColRefExpr(0, "cond", DataType.BooleanType),
          ColRefExpr(1, "a", DataType.LongType),
          ColRefExpr(2, "b", DataType.LongType)),
        DataType.LongType),
      b)
  }

  @Test def funcNullIf(): Unit = {
    val b = batch(
      "a" -> longCol(i => (i % 4).toLong, EveryOther),
      "b" -> longCol(i => (i % 4).toLong, i => i % 6 == 0)
    )
    assertParity(
      FuncExpr("NULLIF",
        Seq(ColRefExpr(0, "a", DataType.LongType), ColRefExpr(1, "b", DataType.LongType)),
        DataType.LongType),
      b)
  }

  /** Unknown function falls through to the default boxed loop. Verify the
    * fallback path still produces the same answer. SQRT isn't in
    * [[Funcs.applyVec]]'s switch — so this exercises the default. */
  @Test def funcUnknownFallsThroughToDefault(): Unit = {
    val b = batch("x" -> doubleCol(i => i.toDouble + 1.0, EveryOther))
    assertParity(
      FuncExpr("SQRT", Seq(ColRefExpr(0, "x", DataType.DoubleType)), DataType.DoubleType),
      b)
  }

  // ===========================================================================
  // CaseExpr (Phase 5)
  // ===========================================================================

  @Test def caseSimpleTwoBranchWithElse(): Unit = {
    val b = batch("x" -> intCol(i => i - 32, EveryOther))
    val x = ColRefExpr(0, "x", DataType.IntType)
    val expr = CaseExpr(
      Seq(
        BinOpExpr("<", x, LitExpr(0, DataType.IntType), DataType.BooleanType) ->
          LitExpr("neg", DataType.StringType),
        BinOpExpr("=", x, LitExpr(0, DataType.IntType), DataType.BooleanType) ->
          LitExpr("zero", DataType.StringType)
      ),
      Some(LitExpr("pos", DataType.StringType)),
      DataType.StringType)
    assertParity(expr, b)
  }

  @Test def caseWithoutElseDefaultsNull(): Unit = {
    val b = batch("x" -> intCol(i => i % 5, EveryOther))
    val x = ColRefExpr(0, "x", DataType.IntType)
    val expr = CaseExpr(
      Seq(
        BinOpExpr("=", x, LitExpr(0, DataType.IntType), DataType.BooleanType) ->
          LitExpr("zero", DataType.StringType),
        BinOpExpr("=", x, LitExpr(1, DataType.IntType), DataType.BooleanType) ->
          LitExpr("one", DataType.StringType)
      ),
      None,
      DataType.StringType)
    assertParity(expr, b)
  }

  @Test def caseAllRowsTakenShortCircuits(): Unit = {
    // The first branch matches every row → no later branches contribute. The
    // override's `remaining > 0` short-circuit should kick in but parity must
    // hold.
    val b = batch("x" -> intCol(i => i))
    val expr = CaseExpr(
      Seq(
        LitExpr(true, DataType.BooleanType) -> LitExpr(1L, DataType.LongType),
        LitExpr(true, DataType.BooleanType) -> LitExpr(2L, DataType.LongType)
      ),
      Some(LitExpr(3L, DataType.LongType)),
      DataType.LongType)
    assertParity(expr, b)
  }

  @Test def caseWithNullValueInBranch(): Unit = {
    val b = batch("x" -> intCol(i => i % 3))
    val x = ColRefExpr(0, "x", DataType.IntType)
    val expr = CaseExpr(
      Seq(
        BinOpExpr("=", x, LitExpr(0, DataType.IntType), DataType.BooleanType) ->
          LitExpr(null, DataType.LongType),
        BinOpExpr("=", x, LitExpr(1, DataType.IntType), DataType.BooleanType) ->
          LitExpr(10L, DataType.LongType)
      ),
      Some(LitExpr(99L, DataType.LongType)),
      DataType.LongType)
    assertParity(expr, b)
  }

  @Test def caseColumnExprValues(): Unit = {
    val b = batch(
      "x" -> intCol(i => i),
      "a" -> longCol(i => i.toLong * 10L, EveryOther),
      "c" -> longCol(i => i.toLong * -1L, i => i % 3 == 0)
    )
    val x = ColRefExpr(0, "x", DataType.IntType)
    val expr = CaseExpr(
      Seq(
        BinOpExpr(">", x, LitExpr(40, DataType.IntType), DataType.BooleanType) ->
          ColRefExpr(1, "a", DataType.LongType)
      ),
      Some(ColRefExpr(2, "c", DataType.LongType)),
      DataType.LongType)
    assertParity(expr, b)
  }

  // ===========================================================================
  // InListExpr (Phase 5)
  // ===========================================================================

  @Test def inListIntLiterals(): Unit = {
    val b = batch("x" -> intCol(i => i % 7, EveryOther))
    val items = Seq(1, 3, 5).map(v => LitExpr(v, DataType.IntType))
    assertParity(
      InListExpr(ColRefExpr(0, "x", DataType.IntType), items, negated = false), b)
    assertParity(
      InListExpr(ColRefExpr(0, "x", DataType.IntType), items, negated = true), b)
  }

  @Test def inListWithNullItemThreeValued(): Unit = {
    // SQL: NULL in items + non-matching value → NULL (not false).
    val b = batch("x" -> intCol(i => i % 5))
    val items = Seq(
      LitExpr(1, DataType.IntType),
      LitExpr(null, DataType.IntType),
      LitExpr(3, DataType.IntType))
    assertParity(
      InListExpr(ColRefExpr(0, "x", DataType.IntType), items, negated = false), b)
    assertParity(
      InListExpr(ColRefExpr(0, "x", DataType.IntType), items, negated = true), b)
  }

  @Test def inListStringLiterals(): Unit = {
    val b = batch("s" -> stringCol(i => Seq("apple", "banana", "cherry")(i % 3), EveryOther))
    val items = Seq("apple", "cherry").map(LitExpr(_, DataType.StringType))
    assertParity(
      InListExpr(ColRefExpr(0, "s", DataType.StringType), items, negated = false), b)
  }

  @Test def inListCrossNumericLiterals(): Unit = {
    // x is Int, items are Longs. Ops.eq cross-numeric via doubleValue should
    // make this work; the typed numeric HashSet path normalizes to Double.
    val b = batch("x" -> intCol(i => i % 4))
    val items = Seq(1L, 3L).map(v => LitExpr(v, DataType.LongType))
    assertParity(
      InListExpr(ColRefExpr(0, "x", DataType.IntType), items, negated = false), b)
  }

  @Test def inListWithColumnItemsGeneralPath(): Unit = {
    // Non-literal items force the general per-row scan path.
    val b = batch(
      "x" -> intCol(i => i % 3, i => i % 9 == 0),
      "y" -> intCol(i => (i + 1) % 4)
    )
    val items = Seq(
      ColRefExpr(1, "y", DataType.IntType),
      LitExpr(2, DataType.IntType))
    assertParity(
      InListExpr(ColRefExpr(0, "x", DataType.IntType), items, negated = false), b)
  }

  // ===========================================================================
  // LikeExpr (Phase 5)
  // ===========================================================================

  @Test def likeLiteralPercentSuffix(): Unit = {
    val b = batch("s" -> stringCol(i => if (i % 2 == 0) s"foo$i" else s"bar$i", EveryOther))
    assertParity(
      LikeExpr(ColRefExpr(0, "s", DataType.StringType), LitExpr("foo%", DataType.StringType), negated = false),
      b)
    assertParity(
      LikeExpr(ColRefExpr(0, "s", DataType.StringType), LitExpr("foo%", DataType.StringType), negated = true),
      b)
  }

  @Test def likeLiteralUnderscoreSingleChar(): Unit = {
    val b = batch("s" -> stringCol(i => s"a${('a' + (i % 5)).toChar}c"))
    assertParity(
      LikeExpr(ColRefExpr(0, "s", DataType.StringType), LitExpr("a_c", DataType.StringType), negated = false),
      b)
  }

  @Test def likeLiteralPercentMiddle(): Unit = {
    val b = batch("s" -> stringCol(i => s"start-$i-end", EveryOther))
    assertParity(
      LikeExpr(ColRefExpr(0, "s", DataType.StringType), LitExpr("start%end", DataType.StringType), negated = false),
      b)
  }

  @Test def likeNullPatternAlwaysNull(): Unit = {
    val b = batch("s" -> stringCol(i => s"v$i", EveryOther))
    assertParity(
      LikeExpr(ColRefExpr(0, "s", DataType.StringType), LitExpr(null, DataType.StringType), negated = false),
      b)
  }

  @Test def likeColumnPattern(): Unit = {
    val b = batch(
      "s" -> stringCol(i => s"item$i", EveryOther),
      "p" -> stringCol(i => if (i % 2 == 0) "item%" else "%no-match%", i => i % 6 == 0)
    )
    assertParity(
      LikeExpr(
        ColRefExpr(0, "s", DataType.StringType),
        ColRefExpr(1, "p", DataType.StringType),
        negated = false),
      b)
  }
}
