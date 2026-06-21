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
  *     "every row agrees" (the precedent `ExprBatchTest` is likewise total).
  *     Concretely it avoids the engine's only partial shapes: `RAND()` (no
  *     parity meaning — excluded entirely), casts that parse (`String ->`
  *     numeric/date, `Boolean ->` float/double), and `ROUND`/`SUBSTRING` with
  *     NULL numeric arguments (NPE). It also keeps float/double operands out of
  *     ordering comparisons and `IN` lists: the row path orders via
  *     `Double.compare` (a total order) while the vector path uses primitive
  *     IEEE comparisons, and `IN`'s numeric `HashSet` uses `Double.equals` —
  *     both disagree on `NaN`/`-0.0`, a real pre-existing divergence a
  *     test-only harness must not trip. Equality (`= <>`) over floats is fine
  *     (it agrees on those values) and is generated.
  */
object ExprGen {

  /** A generated batch + expression, with a compact description for failure
    * messages. The seed reproduces both exactly. */
  final class ExprCase(val batch: ColumnarBatch, val expr: Expr) {
    override def toString: String = {
      val cols = batch.schema.fields.iterator.zipWithIndex.map { case (f, i) =>
        var nulls = 0
        var r = 0
        while (r < batch.numRows) { if (batch.column(i).isNull(r)) nulls += 1; r += 1 }
        s"${f.name}:${f.dataType}(nulls=$nulls)"
      }.mkString("[", ", ", "]")
      s"ExprCase(numRows=${batch.numRows}, cap=${batch.capacity}, schema=$cols, expr=$expr)"
    }
  }

  // ---- type sets used throughout ----
  private val GenTypes: Seq[DataType] =
    Seq(DataType.IntType, DataType.LongType, DataType.FloatType, DataType.DoubleType,
        DataType.BooleanType, DataType.StringType)
  private val Numerics: Seq[DataType] =
    Seq(DataType.IntType, DataType.LongType, DataType.FloatType, DataType.DoubleType)
  private val Integrals: Seq[DataType] = Seq(DataType.IntType, DataType.LongType)

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
    "", "Foo%", "%BAR", "h_llo", "%pad%", "ba_")

  /** Build one case from a single seed. Splits into independent sub-streams so a
    * change to expression generation does not perturb the batch bytes (and vice
    * versa) for a given seed. */
  def generate(rng: Rng): ExprCase = {
    val structRng = rng.split()
    val dataRng = rng.split()
    val exprRng = rng.split()
    val (schema, batch) = genBatch(structRng, dataRng)
    val target = exprRng.oneOf(GenTypes)
    val depth = exprRng.between(1, 4)
    val expr = genTyped(exprRng, schema, target, depth)
    require(
      expr.dataType == target,
      s"ExprGen type invariant violated: produced ${expr.dataType} for target $target -> $expr")
    new ExprCase(batch, expr)
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
    val fields = (0 until numCols).map(i => Field(s"c$i", structRng.oneOf(GenTypes))).toVector
    val schema = Schema(fields)
    val cols = fields.iterator.map(f => genColumn(structRng, dataRng, f.dataType, numRows)).toArray
    (schema, ColumnarBatch.fromColumns(schema, cols, numRows))
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

  /** A divergence-safe boxed value of `dt`: finite numerics, no `NaN`/`Inf`/
    * `-0.0`, drawn from small domains so equality / `IN` / `LIKE` actually hit. */
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
    case DataType.FloatType => cleanFloat(genDouble(r).toFloat)
    case DataType.DoubleType => cleanDouble(genDouble(r))
    case DataType.BooleanType => r.bool()
    case DataType.StringType => r.oneOf(StringPool)
    case other => throw new IllegalArgumentException(s"genValue: unsupported $other")
  }

  private val FloatPool = Seq(
    0.0, 1.0, -1.0, 0.5, -0.5, 0.25, -0.25, 2.5, -2.5, 3.14159, -3.14159,
    100.0, -100.0, 0.1, -0.1, 1234.5, -1234.5, 42.0)

  private def genDouble(r: Rng): Double =
    if (r.bool(0.5)) r.oneOf(FloatPool)
    else r.between(-100000, 100000).toDouble / r.oneOf(Seq(1, 2, 4, 8, 100, 1000))

  // Normalize away -0.0 (and guard the impossible NaN/Inf) so generated values
  // never feed the row-vs-vector ordering divergence on those inputs.
  private def cleanDouble(d: Double): Double = if (d == 0.0 || d.isNaN || d.isInfinite) 0.0 else d
  private def cleanFloat(f: Float): Float = if (f == 0.0f || f.isNaN || f.isInfinite) 0.0f else f

  private def sentinel(dt: DataType): Any = dt match {
    case DataType.IntType => 987654321
    case DataType.LongType => 987654321987L
    case DataType.FloatType => 99999.0f
    case DataType.DoubleType => 99999.0
    case DataType.BooleanType => true
    case DataType.StringType => "GARBAGE"
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
      case t if DataType.isNumeric(t) => genNumeric(r, schema, t, depth)
      case other => genLeaf(r, schema, other)
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
      5 -> (() => genComparison(r, schema, depth)),
      2 -> (() => IsNullExpr(sub(r.oneOf(GenTypes)), r.bool())),
      2 -> (() => genInList(r, schema, depth)),
      2 -> (() => genLike(r, schema, depth)),
      1 -> (() => genCaseExpr(r, schema, DataType.BooleanType, depth)),
      1 -> (() => genBoolFunc(r, schema, depth)),
      1 -> (() => genCastTo(r, schema, DataType.BooleanType, depth)))
    r.weighted(producers: _*)()
  }

  private def genComparison(r: Rng, schema: Schema, depth: Int): Expr = {
    def sub(t: DataType): Expr = genTyped(r, schema, t, depth - 1)
    // numEq: equality over any numerics (agrees on the excluded NaN/-0).
    // intOrd: ordering over integrals only (no float/double ordering).
    // strCmp / boolEq: string ordering + equality, boolean equality.
    r.weighted(
      (4, 0), (4, 1), (3, 2), (2, 3)) match {
      case 0 =>
        BinOpExpr(r.oneOf(EqOps), sub(r.oneOf(Numerics)), sub(r.oneOf(Numerics)), DataType.BooleanType)
      case 1 =>
        BinOpExpr(r.oneOf(OrdOps), sub(r.oneOf(Integrals)), sub(r.oneOf(Integrals)), DataType.BooleanType)
      case 2 =>
        BinOpExpr(r.oneOf(EqOps ++ OrdOps), sub(DataType.StringType), sub(DataType.StringType), DataType.BooleanType)
      case _ =>
        BinOpExpr(r.oneOf(EqOps), sub(DataType.BooleanType), sub(DataType.BooleanType), DataType.BooleanType)
    }
  }

  private def genNumeric(r: Rng, schema: Schema, target: DataType, depth: Int): Expr = {
    def sub(t: DataType): Expr = genTyped(r, schema, t, depth - 1)
    val base: Seq[(Int, () => Expr)] = Seq(
      5 -> (() => BinOpExpr(r.oneOf(ArithOps), sub(r.oneOf(Numerics)), sub(r.oneOf(Numerics)), target)),
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
        Seq(2 -> (() => FuncExpr("LENGTH", Seq(sub(DataType.StringType)), DataType.IntType)))
      case DataType.DoubleType =>
        Seq(
          1 -> (() => FuncExpr(r.oneOf(Seq("FLOOR", "CEIL", "CEILING")), Seq(sub(r.oneOf(Numerics))), DataType.DoubleType)),
          1 -> (() => FuncExpr("ROUND", roundlikeArgs(r, sub(r.oneOf(Numerics))), DataType.DoubleType)),
          1 -> (() => FuncExpr(r.oneOf(Seq("TRUNC", "TRUNCATE")), roundlikeArgs(r, sub(r.oneOf(Numerics))), DataType.DoubleType)),
          // SQRT/LN/EXP are not in Funcs.applyVec — they exercise the default
          // evalVec row loop (which routes through eval, so it cannot diverge,
          // but it proves the fallback path stays correct).
          1 -> (() => FuncExpr(r.oneOf(Seq("SQRT", "LN", "EXP")), Seq(sub(r.oneOf(Numerics))), DataType.DoubleType)))
      case _ => Seq.empty
    }
    r.weighted(base ++ extra: _*)()
  }

  /** ROUND/TRUNC args: the value, plus an optional NON-NULL integer-literal
    * scale (a NULL scale NPEs in ROUND — kept out to preserve totality). */
  private def roundlikeArgs(r: Rng, value: Expr): Seq[Expr] =
    if (r.bool(0.6)) Seq(value, LitExpr(r.between(-2, 4), DataType.IntType)) else Seq(value)

  private def genString(r: Rng, schema: Schema, depth: Int): Expr = {
    def sub(t: DataType): Expr = genTyped(r, schema, t, depth - 1)
    val producers: Seq[(Int, () => Expr)] = Seq(
      4 -> (() => BinOpExpr("||", sub(r.oneOf(GenTypes)), sub(r.oneOf(GenTypes)), DataType.StringType)),
      2 -> (() => CastExpr(sub(r.oneOf(GenTypes)), DataType.StringType)),
      3 -> (() => FuncExpr(r.oneOf(Seq("UPPER", "LOWER", "TRIM")), Seq(sub(DataType.StringType)), DataType.StringType)),
      3 -> (() => FuncExpr("CONCAT", Seq.fill(r.between(1, 3))(sub(r.oneOf(GenTypes))), DataType.StringType)),
      2 -> (() => FuncExpr("SUBSTRING", substringArgs(r, sub(DataType.StringType)), DataType.StringType)),
      2 -> (() => FuncExpr("COALESCE", Seq.fill(r.between(1, 3))(sub(DataType.StringType)), DataType.StringType)),
      2 -> (() => FuncExpr("IF", Seq(sub(DataType.BooleanType), sub(DataType.StringType), sub(DataType.StringType)), DataType.StringType)),
      1 -> (() => FuncExpr("NULLIF", Seq(sub(DataType.StringType), sub(DataType.StringType)), DataType.StringType)),
      1 -> (() => genCaseExpr(r, schema, DataType.StringType, depth)))
    r.weighted(producers: _*)()
  }

  /** SUBSTRING args: the string, a NON-NULL integer-literal start, and an
    * optional NON-NULL integer-literal length (NULL start/length NPEs). */
  private def substringArgs(r: Rng, s: Expr): Seq[Expr] = {
    val start = LitExpr(r.between(-2, 8), DataType.IntType)
    if (r.bool(0.5)) Seq(s, start, LitExpr(r.between(0, 8), DataType.IntType)) else Seq(s, start)
  }

  private def genCaseExpr(r: Rng, schema: Schema, target: DataType, depth: Int): Expr = {
    def sub(t: DataType): Expr = genTyped(r, schema, t, depth - 1)
    val nBranches = r.between(1, 3)
    val branches = (0 until nBranches).map(_ => (sub(DataType.BooleanType), sub(target)))
    val elseExpr = if (r.bool(0.6)) Some(sub(target)) else None
    CaseExpr(branches, elseExpr, target)
  }

  private def genInList(r: Rng, schema: Schema, depth: Int): Expr = {
    // Restricted to Int/Long/String/Boolean: float/double IN lists would feed
    // the numeric HashSet (Double.equals), which disagrees with eval's Ops.eq
    // on NaN/-0.0.
    val childType = r.oneOf(Seq(DataType.IntType, DataType.LongType, DataType.StringType, DataType.BooleanType))
    val child = genTyped(r, schema, childType, depth - 1)
    val nItems = r.between(1, 4)
    val items = (0 until nItems).map(_ => genInListItem(r, schema, childType, depth))
    InListExpr(child, items.toSeq, r.bool())
  }

  private def genInListItem(r: Rng, schema: Schema, childType: DataType, depth: Int): Expr = {
    val k = r.between(0, 9)
    if (k < 5) LitExpr(genValue(r, childType), childType) // literal, fast path
    else if (k < 6) LitExpr(null, childType) // NULL item -> 3VL
    else if (k < 8 && Integrals.contains(childType)) { // cross-numeric literal
      val other = if (childType == DataType.IntType) DataType.LongType else DataType.IntType
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
    LikeExpr(child, pattern, r.bool())
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
    CastExpr(genTyped(r, schema, r.oneOf(totalCastSources(target)), depth - 1), target)

  /** Source types that cast to `target` without ever throwing (see the totality
    * note on the object). */
  private def totalCastSources(target: DataType): Seq[DataType] = target match {
    case DataType.IntType | DataType.LongType => Numerics :+ DataType.BooleanType
    case DataType.FloatType | DataType.DoubleType => Numerics
    case DataType.StringType => GenTypes
    case DataType.BooleanType => Numerics ++ Seq(DataType.StringType, DataType.BooleanType)
    case other => Seq(other)
  }
}
