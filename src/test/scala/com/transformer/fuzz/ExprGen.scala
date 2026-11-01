package com.transformer.fuzz

import com.transformer.core._
import com.transformer.sql.plan._

/** Generator of a random [[ColumnarBatch]] plus a typed [[Expr]] over it, for
  * the `eval` vs `evalVec` parity fuzzer.
  *
  * Two invariants make the output a fair oracle input:
  *
  *   - '''Type-correct by construction.''' Every node is built to a requested
  *     target [[DataType]] and [[generate]] asserts the produced tree's
  *     `dataType` equals the target, so a generator bug surfaces as its own
  *     error rather than masquerading as an engine divergence.
  *
  *   - '''Total by construction.''' The generator never builds a tree that can
  *     throw for in-range, type-correct input, so the property reduces to
  *     "every row agrees, and neither path throws" (the precedent
  *     `ExprBatchTest` is likewise total). Concretely it avoids the engine's
  *     partial shapes: `RAND()` (no parity meaning — excluded entirely), casts
  *     that parse (`String ->` numeric/boolean/date/timestamp, `Boolean ->`
  *     float/double), and a cast from FLOAT or DOUBLE to DECIMAL (`NaN` and the
  *     infinities have no decimal, and the cast refuses them), with one
  *     exception that is the point of it: a CAST of a batch's guard column
  *     ([[GuardPairProb]]) to INT, which fails on some of its rows, built only
  *     under a `CASE`, `IF`, `COALESCE`, `AND`, `OR` or `IN` whose guard
  *     excludes exactly those rows ([[genGuarded]]). Such a tree is total if
  *     and only if each conditional evaluates an operand only on the rows that
  *     need it, which the vector path did not (plans/robustness/17). A
  *     `ROUND` or `TRUNC` scale and a `SUBSTRING` position or length are any
  *     INT or BIGINT, NULL included, since NULL there answers NULL.
  *
  * Columns and expressions take every one of the engine's ten value types
  * ([[GenTypes]]). A type takes part in what the engine gives it and nothing
  * else, so a tree the generator builds is one the engine has an answer for:
  * a DATE or TIMESTAMP is compared only with its own type (the binder refuses
  * any other pairing), only a STRING is read as text (`||`, `CONCAT` and the
  * string functions refuse any other type, which reaches them through a CAST
  * to STRING, from every type but BINARY), and there is no arithmetic on a
  * DATE, a TIMESTAMP or a BINARY, which the engine has none of. Decimal
  * arithmetic is built the way the binder builds it ([[genDecimalArith]]),
  * over sides whose answers its declared type always holds, so it never
  * overflows. Every other shape takes all four: comparisons and `IN` (a
  * DECIMAL against every number, FLOAT and DOUBLE included), `IS NULL`,
  * `COALESCE` / `IF` / `NULLIF` / `CASE`, the CASTs that never throw, and
  * `ABS` and negation of a DECIMAL.
  *
  * Float and double leaves draw `NaN`, `±Infinity` and `-0.0` at a low rate
  * ([[SpecialFloatProb]]), and those operands reach ordering comparisons and
  * `IN` lists like any other. That is deliberate and load-bearing: the two
  * shapes AND the values were all held out while `eval` and `evalVec`
  * disagreed on them (the row path ordered via `Double.compare` while
  * `VecOps` compared IEEE, and `IN`'s numeric `HashSet` keyed on
  * `Double.equals`). The engine now agrees on both, so the fuzzer guards the
  * shapes instead of dodging them, and
  * `ExprParityFuzzTest.generatorReachesIeeeSpecialValues` asserts — via
  * [[specialValueCoverage]] — that the corpus really carries `NaN` and `-0.0`
  * into each, since re-enabling a shape over a corpus that lacks the values
  * would be green and worthless.
  */
object ExprGen {

  /** A generated batch + expression, with a compact description for failure
    * messages. The seed reproduces both exactly. `generated` says the
    * generator built the tree, so it is total (see the object's note); a
    * shrinker's candidate is not, since dropping a guard leaves its CAST
    * bare. */
  final class ExprCase(val batch: ColumnarBatch, val expr: Expr, val generated: Boolean = false) {
    override def toString: String = {
      val cols = batch.schema.fields.iterator.zipWithIndex.map { case (f, i) =>
        var nulls = 0
        var r = 0
        while (r < batch.numRows) { if (batch.column(i).isNull(r)) nulls += 1; r += 1 }
        s"${f.name}:${f.dataType}(nulls=$nulls)"
      }.mkString("[", ", ", "]")
      s"ExprCase(numRows=${batch.numRows}, cap=${batch.capacity}, schema=$cols, expr=${render(expr)})"
    }
  }

  /** `e` as its case-class `toString` spells it, except that a byte-array
    * literal shows its bytes rather than its identity. */
  private def render(e: Expr): String = e match {
    case LitExpr(v, dt) => s"LitExpr(${RowOracle.show(v)},$dt)"
    case ColRefExpr(_, _, _) => e.toString
    case CastExpr(c, t) => s"CastExpr(${render(c)},$t)"
    case UnaryOpExpr(op, c, dt) => s"UnaryOpExpr($op,${render(c)},$dt)"
    case BinOpExpr(op, l, r, dt) => s"BinOpExpr($op,${render(l)},${render(r)},$dt)"
    case FuncExpr(name, args, dt) => s"FuncExpr($name,${args.map(render).mkString("List(", ", ", ")")},$dt)"
    case CaseExpr(branches, elseExpr, dt) =>
      val bs = branches.map { case (w, t) => s"(${render(w)},${render(t)})" }.mkString("List(", ", ", ")")
      s"CaseExpr($bs,${elseExpr.map(render)},$dt)"
    case IsNullExpr(c, neg) => s"IsNullExpr(${render(c)},$neg)"
    case InListExpr(c, items, neg) => s"InListExpr(${render(c)},${items.map(render).mkString("List(", ", ", ")")},$neg)"
    case LikeExpr(c, p, neg, syntax) => s"LikeExpr(${render(c)},${render(p)},$neg,$syntax)"
    case SwitchCaseExpr(operand, branches, elseExpr, dt) =>
      val bs = branches.map(b => s"(${b.compareAs},${render(b.when)},${render(b.value)})").mkString("List(", ", ", ")")
      s"SwitchCaseExpr(${render(operand)},$bs,${elseExpr.map(render)},$dt)"
    case BetweenExpr(c, low, high) => s"BetweenExpr(${render(c)},${render(low)},${render(high)})"
  }

  // ---- type sets used throughout ----

  /** The declared types a DECIMAL takes: one per parquet layout a decimal is
    * written in (an INT32 up to 9 digits, an INT64 up to 18, a fixed-length
    * byte array beyond), so a decimal that spills goes through each. */
  private[fuzz] val DecimalTypes: Seq[DataType] =
    Seq(DataType.DecimalType(9, 2), DataType.DecimalType(18, 4), DataType.DecimalType(38, 18))

  /** Every type a column or an expression can have: the engine's ten value
    * types, DECIMAL at each of [[DecimalTypes]]. Draw from it with [[pick]],
    * which weighs DECIMAL as one type rather than three. */
  private[fuzz] val GenTypes: Seq[DataType] =
    Seq(DataType.IntType, DataType.LongType, DataType.FloatType, DataType.DoubleType,
        DataType.BooleanType, DataType.StringType, DataType.DateType, DataType.TimestampType,
        DataType.BinaryType) ++ DecimalTypes
  /** The types arithmetic takes. */
  private val Arithmetic: Seq[DataType] =
    Seq(DataType.IntType, DataType.LongType, DataType.FloatType, DataType.DoubleType)
  /** The types compared as numbers: the arithmetic ones and every decimal. */
  private val Numerics: Seq[DataType] = Arithmetic ++ DecimalTypes
  private val Floatings: Seq[DataType] = Seq(DataType.FloatType, DataType.DoubleType)
  private val Temporals: Seq[DataType] = Seq(DataType.DateType, DataType.TimestampType)
  /** Every type but BINARY: the types a CAST to STRING reads. */
  private val TextTypes: Seq[DataType] = GenTypes.filterNot(_ == DataType.BinaryType)

  /** One of `types`, each DECIMAL weighed a third of any other type, so the
    * three declared decimal types together are drawn as often as one type. */
  private[fuzz] def pick(r: Rng, types: Seq[DataType]): DataType =
    r.weighted(types.map(t => (if (isDecimal(t)) 1 else DecimalTypes.length, t)): _*)

  private def isDecimal(t: DataType): Boolean = t.isInstanceOf[DataType.DecimalType]

  /** What a coverage tally counts a type as: any DECIMAL as `decimal`, every
    * other type by its name. */
  private[fuzz] def kind(t: DataType): String = if (isDecimal(t)) "decimal" else t.name

  private val ArithOps = Seq("+", "-", "*", "/", "%")
  private val EqOps = Seq("=", "==", "<>", "!=")
  private val OrdOps = Seq("<", "<=", ">", ">=")

  private val NullLitProb = 0.12
  private val ColRefProb = 0.7

  private val StringPool = Seq(
    "", "a", "ab", "abc", "foo", "bar", "Foo", "BAR", "baz", "  pad  ",
    "hello", "world", "x", "y", "a_c", "123", "45", "start-1-end", "FooBar")
  private val LikePatternPool = Seq(
    "%", "_", "a%", "%a", "%a%", "foo%", "%bar", "a_c", "%o%", "ab%",
    "", "Foo%", "%BAR", "h_llo", "%pad%", "ba_", "a!_c", "!%a%", "ab!")

  /** Build one case from a single seed. Splits into independent sub-streams so a
    * change to expression generation does not perturb the batch bytes (and vice
    * versa) for a given seed. */
  def generate(rng: Rng): ExprCase = {
    val structRng = rng.split()
    val dataRng = rng.split()
    val exprRng = rng.split()
    // Split after the other three, so a seed whose batch has no guard pair
    // draws the tree it drew before guarded shapes existed.
    val guardRng = rng.split()
    val (schema, batch) = genBatch(structRng, dataRng)
    val (target, expr) =
      if (schema.indexOf(GuardText) >= 0 && guardRng.bool(GuardedRootProb)) {
        val t = guardRng.oneOf(Seq(DataType.IntType, DataType.BooleanType))
        (t, genGuarded(guardRng, schema, t, guardRng.between(1, 3)))
      } else {
        val t = pick(exprRng, GenTypes)
        (t, genTyped(exprRng, schema, t, exprRng.between(1, 4)))
      }
    require(
      expr.dataType == target,
      s"ExprGen type invariant violated: produced ${expr.dataType} for target $target -> $expr")
    new ExprCase(batch, expr, generated = true)
  }

  // ---- exact integer arithmetic ------------------------------------------

  /** INTs and BIGINTs at the edges arithmetic overflows past: zero and one
    * either way, the square roots a product overflows at, each type's limits
    * and the values one step inside them. */
  private[fuzz] val EdgeInts: Seq[Int] = Seq(0, 1, -1, 2, -2, 7, -7, 46340, -46340, 46341, -46341,
    65536, -65536, 1073741824, -1073741824, Int.MaxValue, Int.MinValue, Int.MaxValue - 1, Int.MinValue + 1)
  private[fuzz] val EdgeLongs: Seq[Long] = Seq(0L, 1L, -1L, 2L, -2L, 7L, -7L, 3037000499L, -3037000499L,
    3037000500L, -3037000500L, 4294967296L, -4294967296L, Int.MaxValue + 1L, Int.MinValue - 1L,
    4611686018427387904L, -4611686018427387904L, Long.MaxValue, Long.MinValue, Long.MaxValue - 1, Long.MinValue + 1)
  /** DOUBLEs a CAST to an integer truncates, rounds past, or has no integer
    * for. */
  private val EdgeDoubles: Seq[Double] = Seq(0.0, -0.0, 2.5, -2.5, 2147483647.5, 2147483648.0, -2147483648.9,
    -2147483649.0, 9.2233720368547748E18, 9.223372036854775807E18, -9.223372036854775808E18, 1e19,
    Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity)
  private val EdgeDecimalType = DataType.DecimalType(38, 2)
  private val EdgeDecimals: Seq[java.math.BigDecimal] = Seq("0", "2.50", "-2.50", "2147483647.99", "2147483648.00",
    "-2147483648.50", "-2147483649.00", "9223372036854775807.99", "9223372036854775808.00",
    "-9223372036854775808.99", "-9223372036854775809.00").map(new java.math.BigDecimal(_))

  /** A batch of INT, BIGINT, DOUBLE and DECIMAL columns of [[EdgeInts]] and
    * friends, and an INT or BIGINT tree over it as the binder builds one: each
    * operator over operands of its own type, an INT widened by a CAST where a
    * BIGINT is wanted, and narrowing CASTs from a BIGINT, a DOUBLE or a
    * DECIMAL. For `oracle/ExactIntegers`, which works the answer out exactly. */
  def generateIntegerArith(rng: Rng): ExprCase = {
    val dataRng = rng.split()
    val exprRng = rng.split()
    val n = dataRng.between(1, 40)
    val schema = Schema(Vector(Field("i", DataType.IntType), Field("l", DataType.LongType),
      Field("d", DataType.DoubleType), Field("m", EdgeDecimalType)))
    def column(dt: DataType, values: Seq[Any]): ColumnVector = {
      val col = ColumnVector.allocate(dt, math.max(1, n))
      (0 until n).foreach(r => if (dataRng.bool(0.1)) col.setNull(r) else col.setBoxed(r, dataRng.oneOf(values)))
      col
    }
    val cols = Array[ColumnVector](column(DataType.IntType, EdgeInts), column(DataType.LongType, EdgeLongs),
      column(DataType.DoubleType, EdgeDoubles), column(EdgeDecimalType, EdgeDecimals))
    val batch = ColumnarBatch.fromColumns(schema, cols, n)
    val target = exprRng.oneOf(Seq(DataType.IntType, DataType.LongType))
    new ExprCase(batch, genInteger(exprRng, target, exprRng.between(1, 4)))
  }

  private def genInteger(r: Rng, target: DataType, depth: Int): Expr = {
    def sub(t: DataType): Expr = genInteger(r, t, depth - 1)
    def leaf(t: DataType): Expr = {
      val (idx, name, pool) = t match {
        case DataType.IntType => (0, "i", EdgeInts)
        case DataType.LongType => (1, "l", EdgeLongs)
        case DataType.DoubleType => (2, "d", EdgeDoubles)
        case _ => (3, "m", EdgeDecimals)
      }
      if (r.bool(0.08)) LitExpr(null, t)
      else if (r.bool(0.6)) ColRefExpr(idx, name, t)
      else LitExpr(r.oneOf(pool), t)
    }
    val other = if (target == DataType.IntType) DataType.LongType else DataType.IntType
    if (depth <= 0 || r.bool(0.25)) leaf(target)
    else r.weighted[() => Expr](
      6 -> (() => BinOpExpr(r.oneOf(ArithOps), sub(target), sub(target), target)),
      1 -> (() => UnaryOpExpr("-", sub(target), target)),
      1 -> (() => FuncExpr("ABS", Seq(sub(target)), target)),
      2 -> (() => CastExpr(sub(other), target)),
      1 -> (() => CastExpr(leaf(r.oneOf(Seq(DataType.DoubleType, EdgeDecimalType))), target)),
      // An integer keeps its type; a negative scale rounds it to tens,
      // hundreds, ..., which can carry it past its type.
      1 -> (() => FuncExpr(r.oneOf(Seq("ROUND", "TRUNC")), Seq(sub(target), LitExpr(r.between(-20, 2), DataType.IntType)), target)),
      1 -> (() => FuncExpr(r.oneOf(Seq("FLOOR", "CEIL", "ROUND", "TRUNC")), Seq(sub(target)), target))
    )()
  }

  // ---- coverage reporting -------------------------------------------------

  /** `e` on row `row`, or NULL where it fails. A tally evaluates a subtree
    * outside the conditional around it, which for a guarded CAST
    * ([[genGuarded]]) means on the rows its guard keeps it off. */
  private def valueAt(e: Expr, batch: ColumnarBatch, row: Int): Any =
    try e.eval(batch, row) catch { case scala.util.control.NonFatal(_) => null }

  /** Which IEEE special values a generated case actually feeds to the two
    * operations that once disagreed between `eval` and `evalVec`. Reported
    * without running either path over the whole tree, so the coverage guard in
    * `ExprParityFuzzTest` stays a generation-only check.
    *
    * `shape` flags say the node exists with a float/double operand; the `nan` /
    * `negZero` flags say a value of that kind actually reaches it on some row.
    * Both halves matter: the shapes were held out of this generator while the
    * engine diverged, and so were the values, so a guard on either alone can be
    * satisfied while the corpus tests nothing. */
  final case class SpecialValueCoverage(
      orderingShape: Boolean,
      orderingNan: Boolean,
      orderingNegZero: Boolean,
      inListShape: Boolean,
      inListNan: Boolean,
      inListNegZero: Boolean)

  def specialValueCoverage(c: ExprCase): SpecialValueCoverage = {
    var ordShape, ordNan, ordNegZero, inShape, inNan, inNegZero = false

    // Does any row of `e` evaluate to NaN / to -0.0? `-0.0` is exactly the
    // value that is `== 0.0` yet sorts below it in the total order both paths
    // now compare by.
    def sawNan(e: Expr): Boolean = anyRow(e)(java.lang.Double.isNaN)
    def sawNegZero(e: Expr): Boolean =
      anyRow(e)(d => d == 0.0 && java.lang.Double.compare(d, 0.0) < 0)

    def anyRow(e: Expr)(p: Double => Boolean): Boolean = {
      var r = 0
      while (r < c.batch.numRows) {
        valueAt(e, c.batch, r) match {
          case n: java.lang.Number => if (p(n.doubleValue)) return true
          case _ => ()
        }
        r += 1
      }
      false
    }

    def walk(e: Expr): Unit = {
      e match {
        case BinOpExpr(op, l, r, _) if OrdOps.contains(op) &&
          DataType.isNumeric(l.dataType) && DataType.isNumeric(r.dataType) =>
          if (Floatings.contains(l.dataType) || Floatings.contains(r.dataType)) ordShape = true
          if (!ordNan) ordNan = sawNan(l) || sawNan(r)
          if (!ordNegZero) ordNegZero = sawNegZero(l) || sawNegZero(r)
        case InListExpr(child, items, _) if DataType.isNumeric(child.dataType) =>
          if (Floatings.contains(child.dataType)) inShape = true
          val operands = child +: items
          if (!inNan) inNan = operands.exists(sawNan)
          if (!inNegZero) inNegZero = operands.exists(sawNegZero)
        case _ => ()
      }
      Shrinker.childrenOf(e).foreach(walk)
    }

    walk(c.expr)
    SpecialValueCoverage(ordShape, ordNan, ordNegZero, inShape, inNan, inNegZero)
  }

  /** Which of DATE, TIMESTAMP, BINARY and DECIMAL a generated case compares,
    * and whether the values a comparison of each gets wrong actually meet in
    * one. Reported without running `evalVec`, like [[specialValueCoverage]].
    *
    * `compared` and `inList` name the [[kind]]s that reach a comparison (both
    * operands of the kind) and an `IN` (the child of the kind) — the shape half.
    * `values` is the value half: the `DataGen.ValueTag`s of the pairs a
    * comparison or an `IN` meets on some row, each the input on which a
    * comparison that took a shortcut (identity, signed bytes,
    * `BigDecimal.equals`, `doubleValue`, milliseconds) answers wrong. */
  final case class TypeCoverage(compared: Set[String], inList: Set[String], values: Set[DataGen.ValueTag])

  /** The kinds [[typeCoverage]] reports on. */
  private[fuzz] val ReportedKinds: Set[String] = Set("date", "timestamp", "binary", "decimal")

  def typeCoverage(c: ExprCase): TypeCoverage = {
    val compared, inList = scala.collection.mutable.Set.empty[String]
    val values = scala.collection.mutable.Set.empty[DataGen.ValueTag]

    def meet(a: Any, b: Any): Unit = values ++= DataGen.pairTags(a, b)

    // Evaluate `left` against each of `rights` on every row, as the comparison
    // or the IN does.
    def meetOnEveryRow(left: Expr, rights: Seq[Expr]): Unit = {
      var r = 0
      while (r < c.batch.numRows) {
        val lv = valueAt(left, c.batch, r)
        if (lv != null) rights.foreach { e =>
          val rv = valueAt(e, c.batch, r)
          if (rv != null) meet(lv, rv)
        }
        r += 1
      }
    }

    def walk(e: Expr): Unit = {
      e match {
        case BinOpExpr(op, l, r, _) if (EqOps ++ OrdOps).contains(op) &&
          kind(l.dataType) == kind(r.dataType) && ReportedKinds(kind(l.dataType)) =>
          compared += kind(l.dataType)
          meetOnEveryRow(l, Seq(r))
        case InListExpr(child, items, _) if ReportedKinds(kind(child.dataType)) =>
          inList += kind(child.dataType)
          meetOnEveryRow(child, items)
        case _ => ()
      }
      Shrinker.childrenOf(e).foreach(walk)
    }

    walk(c.expr)
    TypeCoverage(compared.toSet, inList.toSet, values.toSet)
  }

  // ---- batch generation ---------------------------------------------------

  private def genBatch(structRng: Rng, dataRng: Rng): (Schema, ColumnarBatch) = {
    val numCols = structRng.between(1, 4)
    val numRows = structRng.weighted(
      (2, 0), // 1-row boundary
      (6, 1), // tiny
      (6, 2), // small/medium (the precedent used 64)
      (2, 3), // medium
      (1, 4) // DefaultCapacity boundary
    ) match {
      case 0 => 1
      case 1 => structRng.between(2, 16)
      case 2 => structRng.between(20, 90)
      case 3 => structRng.between(200, 1000)
      case _ => ColumnarBatch.DefaultCapacity
    }
    val fields = (0 until numCols).map(i => Field(s"c$i", pick(structRng, GenTypes))).toVector
    val cols = fields.iterator.map(f => genColumn(structRng, dataRng, f.dataType, numRows)).toArray
    // Drawn after every other column, so a seed's other columns are what they
    // were before the guard pair existed.
    if (structRng.bool(GuardPairProb)) {
      val (text, flag) = genGuardPair(dataRng, numRows)
      val schema = Schema(fields ++ Vector(Field(GuardText, DataType.StringType), Field(GuardFlag, DataType.BooleanType)))
      (schema, ColumnarBatch.fromColumns(schema, cols ++ Array[ColumnVector](text, flag), numRows))
    } else {
      val schema = Schema(fields)
      (schema, ColumnarBatch.fromColumns(schema, cols, numRows))
    }
  }

  /** How often a batch carries a guard pair: a STRING column whose text an INT
    * reads on some rows and not on others, and a BOOLEAN column that is TRUE
    * or NULL only on rows whose text reads (or is NULL), and FALSE on every
    * row whose text does not. */
  private val GuardPairProb = 0.3
  /** How often a batch with a guard pair is given a guarded shape as its
    * tree's root ([[genGuarded]]), whose operands are any other tree. */
  private val GuardedRootProb = 0.6
  private[fuzz] val GuardText = "g"
  private[fuzz] val GuardFlag = "ok"
  private val IntTexts = Seq("0", "7", "-12", "+3", "2147483647", "-2147483648")
  private[fuzz] val NonIntTexts = Seq("x", "", "1.5", " 7", "12a", "2147483648", "1e3")

  private def genGuardPair(r: Rng, numRows: Int): (StringVector, BooleanVector) = {
    val cap = math.max(1, numRows)
    val text = new StringVector(new Array[String](cap), cap)
    val flag = new BooleanVector(new Array[Boolean](cap), new java.util.BitSet(cap), cap)
    var i = 0
    while (i < numRows) {
      r.weighted((1, 0), (5, 1), (4, 2)) match {
        case 0 => // NULL text, which a CAST reads as NULL whatever the flag says
          text.setNull(i)
          r.weighted((1, 0), (1, 1), (1, 2)) match {
            case 0 => flag.setNull(i)
            case k => flag.set(i, k == 1)
          }
        case 1 =>
          text.set(i, r.oneOf(IntTexts))
          if (r.bool(0.15)) flag.setNull(i) else flag.set(i, true)
        case _ =>
          text.set(i, r.oneOf(NonIntTexts))
          flag.set(i, false)
      }
      i += 1
    }
    (text, flag)
  }

  private def genColumn(structRng: Rng, dataRng: Rng, dt: DataType, numRows: Int): ColumnVector = {
    // Null placement: none / all / every-other / random-p — covers the
    // empty-bitset fast path, the all-null edge, and mixed masks.
    val strategy = structRng.weighted((4, 0), (2, 1), (3, 2), (4, 3))
    val p = structRng.oneOf(Seq(0.1, 0.25, 0.5, 0.75))
    val nullMask = new Array[Boolean](numRows)
    var i = 0
    while (i < numRows) {
      nullMask(i) = strategy match {
        case 0 => false
        case 1 => true
        case 2 => i % 2 == 0
        case _ => dataRng.bool(p)
      }
      i += 1
    }
    // Occasionally allocate capacity beyond numRows and fill the tail with
    // non-null sentinels, exercising the [0, numRows) read contract — overrides
    // must size and loop by numRows, never capacity.
    val pad = if (structRng.bool(0.15)) structRng.between(1, 8) else 0
    val cap = math.max(1, numRows + pad)
    val col = ColumnVector.allocate(dt, cap)
    i = 0
    while (i < numRows) {
      if (nullMask(i)) col.setNull(i) else col.setBoxed(i, genValue(dataRng, dt))
      i += 1
    }
    while (i < cap) { col.setBoxed(i, sentinel(dt)); i += 1 }
    col
  }

  /** A boxed value of `dt`, drawn from small domains so equality / `IN` /
    * `LIKE` actually hit. Float and double additionally draw an IEEE special
    * value ([[SpecialFloatProb]] of the time) — the operands on which a naive
    * vectorized ordering or membership test drifts from the row path. */
  private def genValue(r: Rng, dt: DataType): Any = dt match {
    case DataType.IntType =>
      val k = r.between(0, 9)
      if (k < 4) r.between(-9, 9)
      else if (k < 7) r.between(-100, 100)
      else if (k < 9) r.between(-100000, 100000)
      else r.oneOf(Seq(0, 1, -1, Int.MaxValue, Int.MinValue, Int.MaxValue - 1))
    case DataType.LongType =>
      val k = r.between(0, 9)
      if (k < 4) r.between(-9, 9).toLong
      else if (k < 7) r.between(-1000, 1000).toLong
      else if (k < 9) r.nextLong() / 1024L
      else r.oneOf(Seq(0L, 1L, -1L, Long.MaxValue, Long.MinValue))
    case DataType.FloatType =>
      if (r.bool(SpecialFloatProb)) r.oneOf(SpecialFloats) else genDouble(r).toFloat
    case DataType.DoubleType =>
      if (r.bool(SpecialFloatProb)) r.oneOf(SpecialDoubles) else genDouble(r)
    case DataType.BooleanType => r.bool()
    case DataType.StringType => r.oneOf(StringPool)
    case DataType.DateType => r.oneOf(DatePool)
    case DataType.TimestampType => r.oneOf(TimestampPool)
    // A fresh array per draw: two cells with one content are two objects, as
    // they are in every vector the engine reads, so a comparison by identity
    // cannot pass for one by content.
    case DataType.BinaryType => r.oneOf(BinaryPool).clone()
    case d: DataType.DecimalType => r.oneOf(DecimalPools(d))
    case other => throw new IllegalArgumentException(s"genValue: unsupported $other")
  }

  /** Dates around the US daylight-saving changes of 2024 and the leap day
    * before them, either side of the epoch. Every time in the engine is UTC, so
    * a path that reached for the system zone would move a value across one of
    * these. */
  private[fuzz] val DatePool: Seq[java.time.LocalDate] = Seq(
    "1969-12-31", "1970-01-01", "2024-02-28", "2024-02-29", "2024-03-01",
    "2024-03-09", "2024-03-10", "2024-03-11", "2024-11-03").map(java.time.LocalDate.parse)

  /** Instants either side of the epoch and around the 2024 spring-forward
    * instant, a microsecond and less than a millisecond apart: a path that kept
    * milliseconds would merge them. Whole microseconds only, which is all a
    * `TimestampVector` holds and what every TIMESTAMP literal and CAST
    * truncates to. */
  private[fuzz] val TimestampPool: Seq[java.time.Instant] = Seq(
    "1969-12-31T23:59:59.999999Z", "1970-01-01T00:00:00Z", "1970-01-01T00:00:00.000001Z",
    "2024-02-29T12:00:00Z", "2024-03-10T06:59:59.999999Z", "2024-03-10T07:00:00Z",
    "2024-03-10T07:00:00.000001Z", "2024-03-10T07:00:00.000999Z", "2024-03-10T07:00:00.001Z"
  ).map(java.time.Instant.parse)

  /** Byte strings ordered so neighbours are the pairs a byte-array comparison
    * gets wrong: two empty arrays and two `01 02`s with one content (the
    * identity-equality bug), `01` against `81` and `01 02` against `01 82`, which
    * differ only in a high bit and so order oppositely signed and unsigned, and
    * `01 02` as a prefix of `01 02 00`. Each draw is cloned, so even the pairs
    * listed once meet as distinct objects. */
  private[fuzz] val BinaryPool: Seq[Array[Byte]] = Seq(
    Array[Int](), Array[Int](), Array(0x00), Array(0x01), Array(0x81), Array(0x7f), Array(0x80),
    Array(0x01, 0x02), Array(0x01, 0x02), Array(0x01, 0x82), Array(0x01, 0x02, 0x00)
  ).map(_.map(_.toByte))

  /** Decimals for each of [[DecimalTypes]], every one within its declared
    * precision and scale. Each pool holds one value at several scales (`1`,
    * `1.0`, `1.00`), which `=` calls equal, and a negative; the two wider ones
    * also hold pairs that are different values but one double, the inputs on
    * which a comparison through `doubleValue` answers wrong. Nothing here sums
    * or spills, so these run to the edge of their precision. */
  private[fuzz] val DecimalPools: Map[DataType, Seq[java.math.BigDecimal]] = Map[DataType, Seq[String]](
    DataType.DecimalType(9, 2) ->
      Seq("0", "0.00", "1", "1.0", "1.00", "-2.50", "0.25", "1234567.89", "-1234567.89", "9999999.99"),
    DataType.DecimalType(18, 4) ->
      Seq("0", "1", "1.0", "1.0000", "-2.5", "-0.0001", "12345678901234.5678", "12345678901234.5679",
        "99999999999999.9999"),
    DataType.DecimalType(38, 18) ->
      Seq("0", "1", "1.0", "1.000000000000000000", "1.000000000000000001", "1.000000000000000002", "-2.5",
        "9007199254740992", "9007199254740993", "12345678901234567890.123456789012345678",
        "12345678901234567890.123456789012345679")
  ).map { case (t, xs) => t -> xs.map(new java.math.BigDecimal(_)) }

  private val FloatPool = Seq(
    0.0, 1.0, -1.0, 0.5, -0.5, 0.25, -0.25, 2.5, -2.5, 3.14159, -3.14159,
    100.0, -100.0, 0.1, -0.1, 1234.5, -1234.5, 42.0)

  private def genDouble(r: Rng): Double =
    if (r.bool(0.5)) r.oneOf(FloatPool)
    else r.between(-100000, 100000).toDouble / r.oneOf(Seq(1, 2, 4, 8, 100, 1000))

  /** How often a float/double leaf draws an IEEE special value instead of a
    * finite one. Most float leaves are LITERALS — a schema of 1-3 columns holds
    * one of the requested float type only about a third of the time — and a
    * literal gets a single draw, so this rate, not the per-column density, is
    * what decides whether `NaN` and `-0.0` reach a comparison at all. High
    * enough for that; low enough that the finite domains still dominate and
    * keep colliding, which is what makes equality and `IN` interesting.
    * `generatorReachesIeeeSpecialValues` measures the resulting rate rather
    * than assuming it. */
  private val SpecialFloatProb = 0.25

  /** The values the finite generators cannot produce. `+0.0` is deliberately
    * absent — [[FloatPool]] already carries it, so spending pool weight on it
    * would only dilute `NaN` and `-0.0`. */
  private val SpecialDoubles: Seq[Double] = Seq(
    Double.NaN, -0.0, Double.PositiveInfinity, Double.NegativeInfinity)
  private val SpecialFloats: Seq[Float] = Seq(
    Float.NaN, -0.0f, Float.PositiveInfinity, Float.NegativeInfinity)

  private def sentinel(dt: DataType): Any = dt match {
    case DataType.IntType => 987654321
    case DataType.LongType => 987654321987L
    case DataType.FloatType => 99999.0f
    case DataType.DoubleType => 99999.0
    case DataType.BooleanType => true
    case DataType.StringType => "GARBAGE"
    case DataType.DateType => java.time.LocalDate.of(9999, 12, 31)
    case DataType.TimestampType => java.time.Instant.parse("9999-12-31T23:59:59.999999Z")
    case DataType.BinaryType => Array[Byte](0xee.toByte, 0xee.toByte)
    case _: DataType.DecimalType => new java.math.BigDecimal("-99.99")
    case other => throw new IllegalArgumentException(s"sentinel: unsupported $other")
  }

  // ---- expression generation ----------------------------------------------

  // Probability of stopping at a leaf even though depth remains. Higher near the
  // depth floor so trees stay modestly sized; depth itself guarantees
  // termination (a depth-0 call is always a leaf).
  private def leafProb(depth: Int): Double = if (depth <= 1) 0.45 else 0.2

  private def genTyped(r: Rng, schema: Schema, target: DataType, depth: Int): Expr = {
    if (depth <= 0 || r.bool(leafProb(depth))) genLeaf(r, schema, target)
    else target match {
      case DataType.BooleanType => genBoolean(r, schema, depth)
      case DataType.StringType => genString(r, schema, depth)
      case t if Arithmetic.contains(t) => genNumeric(r, schema, t, depth)
      case t => genOther(r, schema, t, depth)
    }
  }

  private def genLeaf(r: Rng, schema: Schema, target: DataType): Expr = {
    val cols = schema.fields.zipWithIndex.filter(_._1.dataType == target)
    if (r.bool(NullLitProb)) LitExpr(null, target)
    else if (cols.nonEmpty && r.bool(ColRefProb)) {
      val (f, idx) = r.oneOf(cols)
      ColRefExpr(idx, f.name, target)
    } else LitExpr(genValue(r, target), target)
  }

  private def genBoolean(r: Rng, schema: Schema, depth: Int): Expr = {
    def sub(t: DataType): Expr = genTyped(r, schema, t, depth - 1)
    val producers: Seq[(Int, () => Expr)] = Seq(
      3 -> (() => BinOpExpr("AND", sub(DataType.BooleanType), sub(DataType.BooleanType), DataType.BooleanType)),
      3 -> (() => BinOpExpr("OR", sub(DataType.BooleanType), sub(DataType.BooleanType), DataType.BooleanType)),
      2 -> (() => UnaryOpExpr("NOT", sub(DataType.BooleanType), DataType.BooleanType)),
      // Comparisons are the densest source of the shapes this fuzzer exists
      // for: three-valued results, cross-numeric promotion, and the one
      // operator family whose semantics depend on the operand type.
      8 -> (() => genComparison(r, schema, depth)),
      2 -> (() => IsNullExpr(sub(pick(r, GenTypes)), r.bool())),
      3 -> (() => genInList(r, schema, depth)),
      2 -> (() => genLike(r, schema, depth)),
      1 -> (() => genCaseExpr(r, schema, DataType.BooleanType, depth)),
      1 -> (() => genBoolFunc(r, schema, depth)),
      1 -> (() => genCastTo(r, schema, DataType.BooleanType, depth)))
    r.weighted(producers: _*)()
  }

  private def genComparison(r: Rng, schema: Schema, depth: Int): Expr = {
    def sub(t: DataType): Expr = genTyped(r, schema, t, depth - 1)
    // numEq / numOrd: equality and ordering over any numerics, float and double
    // included.
    // floatOrd: ordering with at least one floating operand — its own case
    // because ordering is where the operand type changes the semantics
    // (`< <= > >=` follow `Double.compare`'s total order, so `NaN` and `-0.0`
    // answer differently from IEEE), and drawing operand types uniformly leaves
    // that shape too rare for the property to reach it often.
    // strCmp / boolEq: string ordering + equality, boolean equality.
    // temporal / binary: a DATE or TIMESTAMP against its own type, a BINARY
    // against a BINARY — the only pairings the binder allows them.
    // decimal: two decimals, of one declared type or two. Numerics include every
    // decimal too, so a DECIMAL also meets every other number through numEq /
    // numOrd / floatOrd, but two independent draws pair two decimals too rarely
    // for one value at two scales to meet itself.
    r.weighted(
      (4, 0), (4, 1), (4, 2), (3, 3), (2, 4), (3, 5), (2, 6), (2, 7)) match {
      case 0 =>
        BinOpExpr(r.oneOf(EqOps), sub(pick(r, Numerics)), sub(pick(r, Numerics)), DataType.BooleanType)
      case 1 =>
        BinOpExpr(r.oneOf(OrdOps), sub(pick(r, Numerics)), sub(pick(r, Numerics)), DataType.BooleanType)
      case 2 =>
        val (lt, rt) =
          if (r.bool()) (r.oneOf(Floatings), pick(r, Numerics))
          else (pick(r, Numerics), r.oneOf(Floatings))
        BinOpExpr(r.oneOf(OrdOps), sub(lt), sub(rt), DataType.BooleanType)
      case 3 =>
        BinOpExpr(r.oneOf(EqOps ++ OrdOps), sub(DataType.StringType), sub(DataType.StringType), DataType.BooleanType)
      case 4 =>
        BinOpExpr(r.oneOf(EqOps), sub(DataType.BooleanType), sub(DataType.BooleanType), DataType.BooleanType)
      case 5 =>
        val t = r.oneOf(Temporals)
        BinOpExpr(r.oneOf(EqOps ++ OrdOps), sub(t), sub(t), DataType.BooleanType)
      case 6 =>
        BinOpExpr(r.oneOf(EqOps ++ OrdOps), sub(DataType.BinaryType), sub(DataType.BinaryType), DataType.BooleanType)
      case _ =>
        BinOpExpr(r.oneOf(EqOps ++ OrdOps), sub(r.oneOf(DecimalTypes)), sub(r.oneOf(DecimalTypes)), DataType.BooleanType)
    }
  }

  private def genNumeric(r: Rng, schema: Schema, target: DataType, depth: Int): Expr = {
    def sub(t: DataType): Expr = genTyped(r, schema, t, depth - 1)
    val base: Seq[(Int, () => Expr)] = Seq(
      5 -> (() => BinOpExpr(r.oneOf(ArithOps), sub(r.oneOf(Arithmetic)), sub(r.oneOf(Arithmetic)), target)),
      2 -> (() => UnaryOpExpr("-", sub(target), target)),
      1 -> (() => UnaryOpExpr("+", sub(target), target)),
      2 -> (() => FuncExpr("ABS", Seq(sub(target)), target)),
      2 -> (() => FuncExpr("COALESCE", Seq.fill(r.between(1, 3))(sub(target)), target)),
      2 -> (() => FuncExpr("IF", Seq(sub(DataType.BooleanType), sub(target), sub(target)), target)),
      1 -> (() => FuncExpr("NULLIF", Seq(sub(target), sub(target)), target)),
      1 -> (() => genCaseExpr(r, schema, target, depth)),
      1 -> (() => genCastTo(r, schema, target, depth)))
    val extra: Seq[(Int, () => Expr)] = target match {
      case DataType.IntType =>
        Seq(2 -> (() => FuncExpr("LENGTH", Seq(sub(DataType.StringType)), DataType.IntType)),
          1 -> (() => genRounding(r, target, sub)))
      case DataType.LongType => Seq(1 -> (() => genRounding(r, target, sub)))
      case DataType.DoubleType =>
        Seq(
          // FLOOR, CEIL, ROUND and TRUNC of a FLOAT or DOUBLE are a DOUBLE; of
          // an integer or a DECIMAL, their argument's type (genRounding).
          1 -> (() => FuncExpr(r.oneOf(Seq("FLOOR", "CEIL", "CEILING")), Seq(sub(r.oneOf(Floatings))), DataType.DoubleType)),
          1 -> (() => FuncExpr("ROUND", roundlikeArgs(r, sub(r.oneOf(Floatings)), sub), DataType.DoubleType)),
          1 -> (() => FuncExpr(r.oneOf(Seq("TRUNC", "TRUNCATE")), roundlikeArgs(r, sub(r.oneOf(Floatings)), sub), DataType.DoubleType)),
          // SQRT/LN/EXP are not in Funcs.applyVec — they exercise the default
          // evalVec row loop (which routes through eval, so it cannot diverge,
          // but it proves the fallback path stays correct).
          1 -> (() => FuncExpr(r.oneOf(Seq("SQRT", "LN", "EXP")), Seq(sub(pick(r, Numerics))), DataType.DoubleType)))
      case _ => Seq.empty
    }
    r.weighted(base ++ extra: _*)()
  }

  /** ROUND/TRUNC args: the value, and most of the time a scale. */
  /** `FLOOR`, `CEIL`, `ROUND` or `TRUNC` of a `target`-typed value (an INT, a
    * BIGINT or a DECIMAL), typed as the binder types it
    * (`Funcs.signature`), under a CAST to `target` where that gives a DECIMAL
    * another scale. */
  private def genRounding(r: Rng, target: DataType, sub: DataType => Expr): Expr = {
    val fn = r.oneOf(Seq("FLOOR", "CEIL", "CEILING", "ROUND", "TRUNC", "TRUNCATE"))
    val value = sub(target)
    val args = if (fn.startsWith("ROUND") || fn.startsWith("TRUNC")) roundlikeArgs(r, value, sub) else Seq(value)
    val call = FuncExpr(fn, args, Funcs.signature(fn, args).get.returnType)
    if (call.dataType == target) call else CastExpr(call, target)
  }

  private def roundlikeArgs(r: Rng, value: Expr, sub: DataType => Expr): Seq[Expr] =
    if (r.bool(0.6)) Seq(value, integerArg(r, r.between(-2, 4), sub)) else Seq(value)

  /** A scale, a position or a length: mostly `small`, else a NULL, which
    * answers NULL, or an INT or BIGINT subtree, which can be NULL on some rows
    * and lie past any string's end or any double's digits. */
  private def integerArg(r: Rng, small: Int, sub: DataType => Expr): Expr =
    r.weighted[() => Expr](
      (6, () => LitExpr(small, DataType.IntType)),
      (2, () => LitExpr(null, DataType.IntType)),
      (2, () => sub(r.oneOf(Seq(DataType.IntType, DataType.LongType)))))()

  private def genString(r: Rng, schema: Schema, depth: Int): Expr = {
    def sub(t: DataType): Expr = genTyped(r, schema, t, depth - 1)
    val producers: Seq[(Int, () => Expr)] = Seq(
      4 -> (() => BinOpExpr("||", sub(DataType.StringType), sub(DataType.StringType), DataType.StringType)),
      2 -> (() => CastExpr(sub(pick(r, TextTypes)), DataType.StringType)),
      3 -> (() => FuncExpr(r.oneOf(Seq("UPPER", "LOWER", "TRIM")), Seq(sub(DataType.StringType)), DataType.StringType)),
      3 -> (() => FuncExpr("CONCAT", Seq.fill(r.between(1, 3))(sub(DataType.StringType)), DataType.StringType)),
      2 -> (() => FuncExpr("SUBSTRING", substringArgs(r, sub(DataType.StringType), sub), DataType.StringType)),
      2 -> (() => FuncExpr("COALESCE", Seq.fill(r.between(1, 3))(sub(DataType.StringType)), DataType.StringType)),
      2 -> (() => FuncExpr("IF", Seq(sub(DataType.BooleanType), sub(DataType.StringType), sub(DataType.StringType)), DataType.StringType)),
      1 -> (() => FuncExpr("NULLIF", Seq(sub(DataType.StringType), sub(DataType.StringType)), DataType.StringType)),
      1 -> (() => genCaseExpr(r, schema, DataType.StringType, depth)))
    r.weighted(producers: _*)()
  }

  /** SUBSTRING args: the string, a position, and half the time a length. */
  private def substringArgs(r: Rng, s: Expr, sub: DataType => Expr): Seq[Expr] = {
    val start = integerArg(r, r.between(-2, 8), sub)
    if (r.bool(0.5)) Seq(s, start, integerArg(r, r.between(0, 8), sub)) else Seq(s, start)
  }

  /** A CAST of the guard text to INT under a conditional that evaluates it
    * only where the guard flag is TRUE or NULL, which are the rows it reads.
    * Each shape stands for one conditional's laziness: a `CASE`'s `THEN`, a
    * later `WHEN` and its `ELSE`, `IF`'s two values, a `COALESCE` argument
    * after one that is not NULL, the right side of `AND` and `OR`, and an `IN`
    * item after one that matched. */
  private def genGuarded(r: Rng, schema: Schema, target: DataType, depth: Int): Expr = {
    def sub(t: DataType): Expr = genTyped(r, schema, t, depth - 1)
    val text = ColRefExpr(schema.indexOf(GuardText), GuardText, DataType.StringType)
    val ok = ColRefExpr(schema.indexOf(GuardFlag), GuardFlag, DataType.BooleanType)
    val notOk = UnaryOpExpr("NOT", ok, DataType.BooleanType)
    val cast = CastExpr(text, DataType.IntType)
    def int(v: Int) = LitExpr(v, DataType.IntType)
    def maybe(e: => Expr): Option[Expr] = if (r.bool()) Some(e) else None
    if (target == DataType.IntType) r.weighted[() => Expr](
      2 -> (() => CaseExpr(Seq(ok -> cast), maybe(sub(DataType.IntType)), DataType.IntType)),
      1 -> (() => CaseExpr(Seq(notOk -> sub(DataType.IntType), sub(DataType.BooleanType) -> cast),
        maybe(sub(DataType.IntType)), DataType.IntType)),
      1 -> (() => CaseExpr(Seq(notOk -> sub(DataType.IntType)), Some(cast), DataType.IntType)),
      2 -> (() => FuncExpr("IF", Seq(ok, cast, sub(DataType.IntType)), DataType.IntType)),
      1 -> (() => FuncExpr("IF", Seq(notOk, sub(DataType.IntType), cast), DataType.IntType)),
      2 -> (() => FuncExpr("COALESCE",
        Seq(FuncExpr("IF", Seq(ok, LitExpr(null, DataType.IntType), int(r.between(-9, 9))), DataType.IntType), cast),
        DataType.IntType))
    )()
    else {
      def compared = BinOpExpr(r.oneOf(EqOps ++ OrdOps), cast, sub(DataType.IntType), DataType.BooleanType)
      r.weighted[() => Expr](
        2 -> (() => BinOpExpr("AND", ok, compared, DataType.BooleanType)),
        2 -> (() => BinOpExpr("OR", notOk, compared, DataType.BooleanType)),
        1 -> (() => InListExpr(FuncExpr("IF", Seq(ok, int(-1000001), int(2)), DataType.IntType),
          Seq(int(2), cast), r.bool())),
        1 -> (() => CaseExpr(Seq(ok -> compared), maybe(sub(DataType.BooleanType)), DataType.BooleanType))
      )()
    }
  }

  /** Whether a case casts its guard text (the shape), and whether its batch
    * holds a row whose text no INT reads, which the guard must keep the CAST
    * off (the value). Read without evaluating anything. */
  final case class GuardCoverage(shape: Boolean, excludesAFailure: Boolean)

  def guardCoverage(c: ExprCase): GuardCoverage = {
    def casts(e: Expr): Boolean = e match {
      case CastExpr(ColRefExpr(_, GuardText, DataType.StringType), DataType.IntType) => true
      case other => Shrinker.childrenOf(other).exists(casts)
    }
    val ti = c.batch.schema.indexOf(GuardText)
    val failing = ti >= 0 && (0 until c.batch.numRows).exists { i =>
      !c.batch.column(ti).isNull(i) && NonIntTexts.contains(c.batch.column(ti).getBoxed(i))
    }
    GuardCoverage(casts(c.expr), failing)
  }

  private def genCaseExpr(r: Rng, schema: Schema, target: DataType, depth: Int): Expr = {
    def sub(t: DataType): Expr = genTyped(r, schema, t, depth - 1)
    val nBranches = r.between(1, 3)
    val branches = (0 until nBranches).map(_ => (sub(DataType.BooleanType), sub(target)))
    val elseExpr = if (r.bool(0.6)) Some(sub(target)) else None
    CaseExpr(branches, elseExpr, target)
  }

  /** `x IN (…)` over any generated type. A float/double child routes the
    * all-literal case through the hoisted numeric `HashSet`, which is the shape
    * that used to key on `Double.equals` and disagree with `eval`'s `Ops.eq`. */
  private def genInList(r: Rng, schema: Schema, depth: Int): Expr = {
    val childType = pick(r, GenTypes)
    val child = genTyped(r, schema, childType, depth - 1)
    val nItems = r.between(1, 4)
    val items = (0 until nItems).map(_ => genInListItem(r, schema, childType, depth))
    InListExpr(child, items.toSeq, r.bool())
  }

  private def genInListItem(r: Rng, schema: Schema, childType: DataType, depth: Int): Expr = {
    val k = r.between(0, 9)
    if (k < 5) LitExpr(genValue(r, childType), childType) // literal, fast path
    else if (k < 6) LitExpr(null, childType) // NULL item -> 3VL
    else if (k < 8 && Numerics.contains(childType)) { // cross-numeric literal
      val other = pick(r, Numerics.filter(_ != childType))
      LitExpr(genValue(r, other), other)
    } else genTyped(r, schema, childType, depth - 1) // subtree -> general path
  }

  private def genLike(r: Rng, schema: Schema, depth: Int): Expr = {
    def sub(t: DataType): Expr = genTyped(r, schema, t, depth - 1)
    val child = sub(DataType.StringType)
    val pattern = r.weighted(
      (5, () => LitExpr(r.oneOf(LikePatternPool), DataType.StringType)), // literal fast path
      (1, () => LitExpr(null, DataType.StringType)), // NULL pattern -> all NULL
      (3, () => sub(DataType.StringType)) // column/computed pattern -> slow path
    )()
    // `!` as the escape character makes the `!` patterns above mean what they
    // say; under the default backslash they are ordinary characters.
    LikeExpr(child, pattern, r.bool(),
      LikeSyntax(caseInsensitive = r.bool(0.3), escape = r.weighted((6, Some('\\')), (3, Some('!')), (1, None))))
  }

  private def genBoolFunc(r: Rng, schema: Schema, depth: Int): Expr = {
    def sub(t: DataType): Expr = genTyped(r, schema, t, depth - 1)
    r.weighted(
      (2, () => FuncExpr("IF", Seq(sub(DataType.BooleanType), sub(DataType.BooleanType), sub(DataType.BooleanType)), DataType.BooleanType)),
      (2, () => FuncExpr("COALESCE", Seq.fill(r.between(1, 3))(sub(DataType.BooleanType)), DataType.BooleanType)),
      (1, () => FuncExpr("NULLIF", Seq(sub(DataType.BooleanType), sub(DataType.BooleanType)), DataType.BooleanType))
    )()
  }

  private def genCastTo(r: Rng, schema: Schema, target: DataType, depth: Int): Expr =
    CastExpr(genTyped(r, schema, pick(r, totalCastSources(target)), depth - 1), target)

  /** Source types that cast to `target` without ever throwing (see the totality
    * note on the object). Into a DECIMAL, the integers and the decimals whose
    * integer part it holds: a decimal is rounded to the target's scale and is
    * an error past its integer digits, and an integer, which is read as it
    * is, would leave a DECIMAL-typed value that a later CAST could not fit. A
    * FLOAT or DOUBLE is not a total source for a DECIMAL either: `NaN` and the
    * infinities have none. A DATE and a TIMESTAMP cast to each other (midnight
    * UTC, the date in UTC); text into either can fail to parse. A BINARY casts
    * to nothing else and nothing else to it. */
  private def totalCastSources(target: DataType): Seq[DataType] = target match {
    case DataType.IntType | DataType.LongType => Numerics :+ DataType.BooleanType
    case DataType.FloatType | DataType.DoubleType => Numerics
    case DataType.StringType => TextTypes
    case DataType.BooleanType => Numerics :+ DataType.BooleanType
    case d: DataType.DecimalType =>
      def asDecimal(t: DataType) = t match {
        case dt: DataType.DecimalType => dt
        case it => Decimals.ofIntegral(it)
      }
      (Seq(DataType.IntType, DataType.LongType) ++ DecimalTypes).filter(t => integerDigits(asDecimal(t)) <= integerDigits(d))
    case DataType.DateType | DataType.TimestampType => Temporals
    case other => Seq(other)
  }

  private def integerDigits(t: DataType.DecimalType): Int = t.precision - t.scale

  /** A DECIMAL, DATE, TIMESTAMP or BINARY expression: the conditional forms
    * every type has, and a CAST in from the types that cast to `target` without
    * throwing. A DECIMAL also takes arithmetic, unary `+` and `-`, and `ABS`. */
  private def genOther(r: Rng, schema: Schema, target: DataType, depth: Int): Expr = {
    def sub(t: DataType): Expr = genTyped(r, schema, t, depth - 1)
    val base: Seq[(Int, () => Expr)] = Seq(
      2 -> (() => FuncExpr("COALESCE", Seq.fill(r.between(1, 3))(sub(target)), target)),
      2 -> (() => FuncExpr("IF", Seq(sub(DataType.BooleanType), sub(target), sub(target)), target)),
      1 -> (() => FuncExpr("NULLIF", Seq(sub(target), sub(target)), target)),
      1 -> (() => genCaseExpr(r, schema, target, depth)))
    val extra: Seq[(Int, () => Expr)] = target match {
      case _: DataType.DecimalType => Seq(
        4 -> (() => genDecimalArith(r, schema, target)),
        1 -> (() => genRounding(r, target, sub)),
        1 -> (() => genCastTo(r, schema, target, depth)),
        1 -> (() => UnaryOpExpr(r.oneOf(Seq("+", "-")), sub(target), target)),
        1 -> (() => FuncExpr("ABS", Seq(sub(target)), target)))
      case DataType.DateType | DataType.TimestampType => Seq(1 -> (() => genCastTo(r, schema, target, depth)))
      case _ => Seq.empty
    }
    r.weighted(base ++ extra: _*)()
  }

  // ---- decimal arithmetic -------------------------------------------------

  /** `l op r` over decimals, built as the binder builds it
    * (`Analyzer.arithmetic`): each side a DECIMAL, an INT or BIGINT under a
    * CAST to the DECIMAL its every value fits, or a number written as the
    * DECIMAL of its digits (`Decimals.literal`), and the node declared as
    * `Decimals.resultType` says. Under a CAST to `target` when that is another
    * DECIMAL, which rounds the answer to `target`'s scale, as a CASE or a UNION
    * whose type gave way past 38 digits does.
    *
    * The sides are leaves, whose values fit the types they are declared as,
    * and they are drawn for the operator, so that its type keeps every integer
    * digit its answer can need ([[holdsEveryAnswer]]), and so does `target`
    * when the answer is cast to it: no answer overflows and the generator
    * stays total. The pairs that fail it are the widest products and
    * quotients, whose types give way on integer digits past 38 digits; their
    * overflow is pinned by `ExprBatchTest`. Sides that do not fit are drawn
    * again, and after a few tries they are two small numbers, which fit every
    * operator and every one of [[DecimalTypes]]. */
  private def genDecimalArith(r: Rng, schema: Schema, target: DataType): Expr = {
    val d = target.asInstanceOf[DataType.DecimalType]
    val op = r.oneOf(ArithOps)
    def fits(lt: DataType.DecimalType, rt: DataType.DecimalType): Boolean =
      holdsEveryAnswer(op, lt, rt) &&
        (Decimals.resultType(op, lt, rt) == d || integerDigitsNeeded(op, lt, rt) <= integerDigits(d))
    val ((l, lt), (rr, rt)) = Iterator.continually((decimalSide(r, schema), decimalSide(r, schema))).take(4)
      .find { case ((_, a), (_, b)) => fits(a, b) }
      .getOrElse((writtenSide(r.oneOf(SmallNumbers)), writtenSide(r.oneOf(SmallNumbers))))
    val t = Decimals.resultType(op, lt, rt)
    val node = BinOpExpr(op, l, rr, t)
    if (t == target) node else CastExpr(node, target)
  }

  /** One side of decimal arithmetic, and the DECIMAL it is declared as. */
  private def decimalSide(r: Rng, schema: Schema): (Expr, DataType.DecimalType) =
    r.weighted(
      (4, () => {
        val t = r.oneOf(DecimalTypes)
        (genLeaf(r, schema, t), t.asInstanceOf[DataType.DecimalType])
      }),
      (2, () => {
        val it = r.oneOf(Seq(DataType.IntType, DataType.LongType))
        genLeaf(r, schema, it) match {
          case LitExpr(n: Number, _) => writtenSide(n)
          case leaf => (CastExpr(leaf, Decimals.ofIntegral(it)), Decimals.ofIntegral(it))
        }
      }),
      (1, () => writtenSide(r.oneOf(DecimalLiteralNumbers)))
    )()

  /** A number written in the query, as the DECIMAL of its digits. */
  private def writtenSide(n: Number): (Expr, DataType.DecimalType) = {
    val lit = Decimals.literal(n)
    (lit, lit.dataType.asInstanceOf[DataType.DecimalType])
  }

  /** Numbers a query writes beside a DECIMAL, read as the decimals of their
    * digits: a `0.1` no double holds, a divisor that leaves a remainder or a
    * quotient that does not end (`3`, `7`), and zero. */
  private val DecimalLiteralNumbers: Seq[Number] = Seq[Number](
    0, 1, 3, 7, 100, -2, 0.1, 0.5, 1.25, -0.03, 1000000.0)

  /** The [[DecimalLiteralNumbers]] of three integer digits or fewer: any two,
    * under any operator, need at most seven integer digits, which every one
    * of [[DecimalTypes]] holds. */
  private val SmallNumbers: Seq[Number] = DecimalLiteralNumbers.filter(n => math.abs(n.doubleValue) < 1000)

  /** Whether `l op r`'s declared type keeps every integer digit its answer can
    * need, for sides whose values fit their types: true of each of Spark's
    * types until it passes 38 digits, where `Decimals.resultType` gives way on
    * scale down to six digits and then on integer digits. */
  private def holdsEveryAnswer(op: String, l: DataType.DecimalType, r: DataType.DecimalType): Boolean =
    integerDigits(Decimals.resultType(op, l, r)) >= integerDigitsNeeded(op, l, r)

  /** The integer digits `l op r` can need, for sides whose values fit their
    * types. */
  private def integerDigitsNeeded(op: String, l: DataType.DecimalType, r: DataType.DecimalType): Int = {
    val (i1, i2) = (integerDigits(l), integerDigits(r))
    op match {
      case "+" | "-" => math.max(i1, i2) + 1
      case "*" => i1 + i2 + 1
      case "/" => i1 + r.scale
      case _ => math.min(i1, i2)
    }
  }

  /** Which arithmetic a generated case applies to DECIMALs (`ops`: `+ - * / %`
    * and `negate`), and whether its answers did the two things decimal
    * arithmetic does besides adding up: round to the declared scale (a
    * quotient that does not end, or a type that gave way past 38 digits), and
    * meet a zero divisor (NULL). Read off every row, like [[typeCoverage]]. */
  final case class DecimalArithCoverage(ops: Set[String], rounded: Boolean, byZero: Boolean)

  def decimalArithCoverage(c: ExprCase): DecimalArithCoverage = {
    val ops = scala.collection.mutable.Set.empty[String]
    var rounded, byZero = false

    def walk(e: Expr): Unit = {
      e match {
        case BinOpExpr(op, l, r, t: DataType.DecimalType) if ArithOps.contains(op) =>
          ops += op
          var row = 0
          while (row < c.batch.numRows) {
            (valueAt(l, c.batch, row), valueAt(r, c.batch, row)) match {
              case (a: Number, b: Number) =>
                val (x, y) = (Casts.toDecimal(a), Casts.toDecimal(b))
                if ((op == "/" || op == "%") && y.signum == 0) byZero = true
                else {
                  val exact = op match {
                    case "+" => Some(x.add(y))
                    case "-" => Some(x.subtract(y))
                    case "*" => Some(x.multiply(y))
                    case "%" => Some(x.remainder(y))
                    case _ => scala.util.Try(x.divide(y)).toOption // None: it does not end
                  }
                  if (exact.forall(v => v.compareTo(v.setScale(t.scale, java.math.RoundingMode.HALF_UP)) != 0))
                    rounded = true
                }
              case _ => ()
            }
            row += 1
          }
        case UnaryOpExpr("-", _, _: DataType.DecimalType) => ops += "negate"
        case _ => ()
      }
      Shrinker.childrenOf(e).foreach(walk)
    }

    walk(c.expr)
    DecimalArithCoverage(ops.toSet, rounded, byZero)
  }

  /** What a case's `ROUND`, `TRUNC` and `SUBSTRING` calls give their scale,
    * position or length: whether one has one (the shape), whether on some row
    * it is NULL while the value it applies to is not, and whether on some row
    * it is past an edge: a scale of 16 places or more either way, where a
    * double's digits or 10^scale's range run out, or a position or length past
    * an INT's range. */
  final case class IntegerArgCoverage(shape: Boolean, nullArg: Boolean, edge: Boolean)

  def integerArgCoverage(c: ExprCase): IntegerArgCoverage = {
    var shape, nullArg, edge = false

    def walk(e: Expr): Unit = {
      e match {
        case FuncExpr(name, value +: integers, _) if integers.nonEmpty &&
            Set("ROUND", "TRUNC", "TRUNCATE", "SUBSTRING")(name) =>
          shape = true
          var row = 0
          while (row < c.batch.numRows) {
            if (valueAt(value, c.batch, row) != null) integers.foreach { arg =>
              valueAt(arg, c.batch, row) match {
                case null => nullArg = true
                case n: Number =>
                  val k = n.longValue
                  if (name == "SUBSTRING") { if (k > Int.MaxValue || k < Int.MinValue) edge = true }
                  else if (k >= 16 || k <= -16) edge = true
                case _ => ()
              }
            }
            row += 1
          }
        case _ => ()
      }
      Shrinker.childrenOf(e).foreach(walk)
    }

    walk(c.expr)
    IntegerArgCoverage(shape, nullArg, edge)
  }
}
