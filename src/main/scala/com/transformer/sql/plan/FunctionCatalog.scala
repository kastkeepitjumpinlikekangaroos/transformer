package com.transformer.sql.plan

import com.transformer.core.DataType

import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.Locale
import scala.util.matching.Regex

/** Every function a query can call, one [[Entry]] each: its name and
  * aliases, its kind, the arities it takes and what each argument must be,
  * the rule its result's type follows, what a NULL argument does, the
  * arguments outside its domain that answer NULL, the errors it can raise
  * while running, whether it reads a fresh value each time (`RAND()`), and
  * whether it has a vector path, runs as a window function and honours
  * `DISTINCT`. A rule that answers otherwise than other engines do names its
  * id in the ledger in `docs/gotchas.md` ("Answers that differ from other
  * engines by design").
  *
  * The binder looks every name up here and matches none itself:
  * `LogicalBuilder` binds a call by its entry's kind and parameters
  * ([[Funcs.signature]] is a lookup here), `UnsupportedClauses` reads which
  * functions take `DISTINCT`, an argument or an offset, and
  * [[Expr.readsRandom]] which read a fresh value. `Funcs.apply` and
  * `Funcs.applyVec` compute each scalar function under the names given here.
  *
  * `function_catalog_test` holds README.md's lists of functions and CAST
  * types to this table, both ways, and every entry to the arities it
  * declares; the fuzz generators draw their functions from it; and the
  * semantics tables under `src/test/semantics` sweep every entry over every
  * kind of argument, each NULL and each error explained by its entry. */
object FunctionCatalog {

  import Funcs.Param
  import Funcs.Param._

  /** What kind of function an entry is, which says how a call binds. */
  sealed abstract class Kind(val name: String) { override def toString: String = name }
  object Kind {
    /** A function of its arguments' values, bound as a [[FuncExpr]]. */
    case object Scalar extends Kind("scalar")
    /** A function that answers with one of its arguments or NULL, as a
      * conditional does: its arguments meet as a CASE's values do, or are
      * compared as NULLIF's are. Bound as a [[FuncExpr]]. */
    case object Flow extends Kind("flow")
    /** An operator by another name, bound as the operator, so the two cannot
      * be typed differently: `MOD(a, b)` is `a % b`. */
    case object Operator extends Kind("operator")
    /** A clock keyword, bound to a literal of the instant the statement
      * started, the same on every row. */
    case object Clock extends Kind("clock")
    /** An aggregate, over a group's rows or, where the entry says, a window's
      * frame. */
    case object Aggregate extends Kind("aggregate")
    /** A window function that numbers the rows of a partition by its order. */
    case object Ranking extends Kind("ranking")
    /** A window function that reads another row of the partition. */
    case object Offset extends Kind("offset")

    val All: Seq[Kind] = Seq(Scalar, Flow, Operator, Clock, Aggregate, Ranking, Offset)
  }

  /** How many arguments an entry takes: `min` to `max`, or any number from
    * `min` on. */
  final case class Arity(min: Int, max: Option[Int]) {
    def admits(n: Int): Boolean = n >= min && max.forall(n <= _)
    override def toString: String = max match {
      case Some(m) if m == min => s"$min"
      case Some(m) => s"$min to $m"
      case None => s"$min or more"
    }
  }
  object Arity {
    def exactly(n: Int): Arity = Arity(n, Some(n))
    def between(min: Int, max: Int): Arity = Arity(min, Some(max))
    def atLeast(n: Int): Arity = Arity(n, None)
  }

  /** The rule a call's result type follows. */
  sealed abstract class Result(val describe: String) { override def toString: String = describe }
  object Result {
    final case class Fixed(t: DataType) extends Result(DataType.sqlName(t))
    /** The first argument's type: `ABS`, `NULLIF`, `MIN`, `MAX`. */
    case object FirstArgument extends Result("the first argument's type")
    /** The type the values that become the result meet as (the
      * [[Funcs.Param.Branch]] arguments, through `Analyzer.branches`). */
    case object Branches extends Result("the type its values meet as, as a CASE's do")
    /** [[Rounding.resultType]]: an INT, a BIGINT or a DECIMAL keeps its type,
      * a DECIMAL at the scale written, one more integer digit kept for a
      * carry where `carry` says; anything else is a DOUBLE. */
    final case class Rounded(carry: Boolean) extends Result(
      "an INT, BIGINT or DECIMAL argument's own type (a DECIMAL at the scale written" +
        (if (carry) ", one more integer digit for the carry" else "") + "); else DOUBLE")
    /** The type the operator's arithmetic declares ([[Analyzer.arithmetic]]). */
    final case class Arithmetic(op: String) extends Result(s"the type `$op` declares")
    /** `SUM`'s: a BIGINT over integers, a DOUBLE over floats, and over a
      * DECIMAL(p,s) a DECIMAL(38,s) ([[Decimals.sumType]]). */
    case object Sum extends Result("BIGINT over INT or BIGINT, DOUBLE over FLOAT or DOUBLE, DECIMAL(38,s) over DECIMAL(p,s)") {
      def of(t: DataType): DataType = t match {
        case DataType.IntType | DataType.LongType => DataType.LongType
        case DataType.FloatType | DataType.DoubleType => DataType.DoubleType
        case d: DataType.DecimalType => Decimals.sumType(d)
        case other => other
      }
    }
    /** `AVG`'s: a DOUBLE, but over a DECIMAL(p,s) a DECIMAL with four more
      * fractional digits ([[Decimals.avgType]]). */
    case object Avg extends Result("DOUBLE, DECIMAL(p+4,s+4) over DECIMAL(p,s)") {
      def of(t: DataType): DataType = t match {
        case d: DataType.DecimalType => Decimals.avgType(d)
        case _ => DataType.DoubleType
      }
    }
  }

  /** What a NULL does to a call. */
  sealed abstract class Nulls(val describe: String) { override def toString: String = describe }
  object Nulls {
    /** NULL in any argument gives NULL, a scale, position or seed included. */
    case object Propagate extends Nulls("NULL in any argument gives NULL")
    /** NULL arguments (or values) are skipped, and the answer is NULL when
      * none is left: `GREATEST`, `LEAST` and the aggregates. */
    case object Skip extends Nulls("NULLs are skipped; NULL when none is left")
    /** NULLs are not counted, and none counts 0: `COUNT`, `COUNT_IF`. */
    case object Count extends Nulls("NULLs are not counted; 0 when none is left")
    /** `COALESCE`: the first argument that is not NULL. */
    case object FirstNonNull extends Nulls("the first argument that is not NULL; NULL when every one is")
    /** `IF`: the value its condition chooses, the third where it is NULL. */
    case object Chosen extends Nulls("the value its condition chooses, the third where the condition is NULL")
    /** `NULLIF`: NULL where the first is NULL or equals the second. */
    case object Equal extends Nulls("NULL where the first is NULL or equals the second")
    /** `LAG` and `LEAD`: the value of the row that far away, or the default
      * where there is none. */
    case object Offset extends Nulls("the value that far away, or the default where no row is")
    /** It takes no argument. */
    case object NoArgument extends Nulls("takes no argument")
  }

  /** Non-NULL arguments a function answers NULL for, since no value answers
    * it (`rule` names the ledger's row). `applies` reads a scalar function's
    * arguments, or an aggregate's rows whose arguments are all non-NULL,
    * each the sequence of its arguments. */
  final case class Domain(describe: String, rule: Option[String], applies: Seq[Any] => Boolean) {
    override def toString: String = describe
  }

  /** An error a call can raise while running, on a value, and its message. */
  final case class RuntimeError(describe: String, message: Regex, rule: Option[String] = None) {
    def matches(m: String): Boolean = message.findFirstIn(m).isDefined
    override def toString: String = describe
  }

  /** What `DISTINCT` inside a call does. */
  sealed abstract class DistinctUse(val describe: String) { override def toString: String = describe }
  object DistinctUse {
    case object Honoured extends DistinctUse("honoured")
    /** Accepted, and cannot change the answer: `MIN`, `MAX`. */
    case object NoEffect extends DistinctUse("accepted, and changes nothing")
    case object Refused extends DistinctUse("refused")
  }

  /** One function. `name` is the canonical name, and `aliases` the other
    * names a call may use; `params` are what each argument must be, the last
    * repeating for a variadic entry ([[paramsAt]]); `refusedArities` an
    * arity the entry refuses with a message of its own; `random` the
    * arities at which a call reads a fresh value each time; `bindCheck` a
    * check of the constant arguments at bind time, given each one's value
    * when it is a constant. The last five build what a call binds to, for
    * the kinds that are no [[FuncExpr]]. */
  final case class Entry(
      name: String,
      aliases: Seq[String] = Nil,
      kind: Kind,
      arity: Arity,
      params: Seq[Param] = Nil,
      result: Result,
      nulls: Nulls,
      domain: Seq[Domain] = Nil,
      errors: Seq[RuntimeError] = Nil,
      random: Set[Int] = Set.empty,
      vectorPath: Boolean = false,
      window: Boolean = false,
      distinct: DistinctUse = DistinctUse.Refused,
      windowDistinct: DistinctUse = DistinctUse.Refused,
      star: Boolean = false,
      refusedArities: Map[Int, String] = Map.empty,
      rules: Seq[String] = Nil,
      bindCheck: Option[(Int => Option[Any]) => Unit] = None,
      aggregate: Option[(Seq[Expr], Boolean) => AggExpr] = None,
      ranking: Option[() => WindowFn] = None,
      offset: Option[(Expr, Int, Option[Expr]) => WindowFn] = None,
      operator: Option[String] = None,
      clock: Option[Instant => LitExpr] = None) {

    def names: Seq[String] = name +: aliases

    /** What each of `n` arguments must be. */
    def paramsAt(n: Int): Seq[Param] =
      if (n <= params.size) params.take(n) else params ++ Seq.fill(n - params.size)(params.last)

    /** Whether a call with `n` arguments reads a fresh value each time. */
    def readsRandom(n: Int): Boolean = random(n)

    /** The type a call over `args` returns, which the entry admits. */
    def resultType(args: Seq[Expr]): DataType = result match {
      case Result.Fixed(t) => t
      case Result.FirstArgument => args.head.dataType
      case Result.Branches =>
        oneType(paramsAt(args.size).zip(args).collect { case (Branch, a) => a.dataType })
      case Result.Rounded(carry) => Rounding.resultType(args, carry)
      case Result.Arithmetic(op) => Analyzer.arithmetic(op, args(0), args(1)).dataType
      case Result.Sum => Result.Sum.of(args.head.dataType)
      case Result.Avg => Result.Avg.of(args.head.dataType)
    }

    /** Why a call over the non-NULL-or-NULL values `args` answers NULL, by
      * its NULL rule or its domain, or None when neither says it does. */
    def explainsNull(args: Seq[Any]): Option[String] = {
      def chosen: Any = args(0) match {
        case b: java.lang.Boolean if b.booleanValue => args(1)
        case _ => args(2)
      }
      val byNulls = nulls match {
        case Nulls.Propagate => args.contains(null)
        case Nulls.Skip | Nulls.FirstNonNull => args.forall(_ == null)
        case Nulls.Chosen => chosen == null
        case Nulls.Equal => args(0) == null || (args(1) != null && Ops.eq(args(0), args(1)))
        case Nulls.Count | Nulls.Offset | Nulls.NoArgument => false
      }
      if (byNulls) Some(nulls.describe)
      else if (args.contains(null) && nulls != Nulls.Propagate) None
      else domain.find(_.applies(args)).map(_.describe)
    }

    /** Why an aggregate over rows whose non-NULL arguments are `rows` (each
      * the sequence of its arguments) answers NULL, or None. */
    def explainsNullOver(rows: Seq[Seq[Any]]): Option[String] =
      if (rows.isEmpty && nulls == Nulls.Skip) Some(nulls.describe)
      else domain.find(_.applies(rows)).map(_.describe)
  }

  /** The type of arguments the binder has given one type: theirs, or NULL
    * when every one is an untyped NULL. */
  private def oneType(types: Seq[DataType]): DataType =
    types.find(_ != DataType.NullType).getOrElse(DataType.NullType)

  // ---------------------------------------------------------------------------
  // Rules shared by several entries
  // ---------------------------------------------------------------------------

  private def number(args: Seq[Any], i: Int): Double = args(i).asInstanceOf[java.lang.Number].doubleValue

  private val MathDomain = Some("math-domain-null")
  private val belowZero = Domain("a number below zero", MathDomain, a => number(a, 0) < 0)
  private val zeroOrBelow = Domain("zero or a number below it", MathDomain, a => number(a, 0) <= 0)
  private val infinite = Domain("an infinity", MathDomain, a => number(a, 0).isInfinite)
  private val pastOne = Domain("a number past -1 or 1", MathDomain, a => math.abs(number(a, 0)) > 1)

  private val integerOverflow = RuntimeError("an INT or BIGINT answer past its type",
    """^Integer overflow: .+ does not fit (?:INT|BIGINT)$""".r, Some("int-is-int"))
  private val decimalOverflow = RuntimeError("a DECIMAL answer past its type's digits",
    """^Decimal overflow: \S+ does not fit decimal\(\d+,\d+\)$""".r)

  /** A function of one number that computes in DOUBLE, a DECIMAL's too;
    * `overflows` when its answer can pass a DOUBLE's range, which is an
    * infinity. */
  private def math1(name: String, domain: Domain*): Entry = Entry(name, kind = Kind.Scalar,
    arity = Arity.exactly(1), params = Seq(Number), result = Result.Fixed(DataType.DoubleType),
    nulls = Nulls.Propagate, domain = domain,
    rules = if (Overflowing(name)) Seq("double-precision", "double-range") else Seq("double-precision"))

  /** The functions of one number whose answer can pass a DOUBLE's range. */
  private val Overflowing = Set("EXP", "SINH", "COSH")

  private def rounding(name: String, aliases: Seq[String], scale: Boolean, carry: Boolean): Entry =
    Entry(name, aliases, Kind.Scalar, if (scale) Arity.between(1, 2) else Arity.exactly(1),
      params = Seq(Number, Integer("scale")), result = Result.Rounded(carry), nulls = Nulls.Propagate,
      errors = Seq(integerOverflow, decimalOverflow), vectorPath = true)

  private def text1(name: String, aliases: Seq[String], returns: DataType): Entry =
    Entry(name, aliases, Kind.Scalar, Arity.exactly(1), Seq(Text), Result.Fixed(returns), Nulls.Propagate,
      vectorPath = true)

  /** An aggregate of one number that computes in DOUBLE, and runs as a
    * window function. */
  private def statistic(name: String, aliases: Seq[String], fewest: Int, build: Expr => AggExpr): Entry =
    Entry(name, aliases, Kind.Aggregate, Arity.exactly(1), Seq(Number), Result.Fixed(DataType.DoubleType),
      Nulls.Skip, domain = tooFew(fewest), window = true, rules = Seq("double-precision"),
      aggregate = Some((args, _) => build(args.head)))

  /** A sample statistic has no value over fewer than two rows. */
  private def tooFew(fewest: Int): Seq[Domain] =
    if (fewest < 2) Nil else Seq(Domain("fewer than two values", None, rows => rows.size < 2))

  private def bivariate(name: String, fewest: Int, build: (Expr, Expr) => AggExpr, extra: Domain*): Entry =
    Entry(name, kind = Kind.Aggregate, arity = Arity.exactly(2), params = Seq(Number, Number),
      result = Result.Fixed(DataType.DoubleType), nulls = Nulls.Skip, domain = tooFew(fewest) ++ extra,
      rules = Seq("double-precision"), aggregate = Some((args, _) => build(args(0), args(1))))

  private def withDistinct(distinct: Boolean, agg: AggExpr): AggExpr =
    if (distinct) AggExprDistinct(agg) else agg

  // ---------------------------------------------------------------------------
  // The entries
  // ---------------------------------------------------------------------------

  val Entries: Seq[Entry] = Seq(
    // ---- strings ----
    text1("LENGTH", Seq("LEN"), DataType.IntType),
    text1("UPPER", Nil, DataType.StringType),
    text1("LOWER", Nil, DataType.StringType),
    // JSqlParser gives TRIM a node of its own, for its extended forms, which
    // the binder refuses as it refuses another arity.
    text1("TRIM", Nil, DataType.StringType).copy(refusedArities = Map(0 -> OnlySingleArgumentTrim,
      2 -> OnlySingleArgumentTrim)),
    Entry("SUBSTRING", kind = Kind.Scalar, arity = Arity.between(2, 3),
      params = Seq(Text, Integer("position"), Integer("length")), result = Result.Fixed(DataType.StringType),
      nulls = Nulls.Propagate, vectorPath = true,
      errors = Seq(RuntimeError("a negative length", """^SUBSTRING's length -\d+ is negative$""".r)),
      // A negative length written as a constant is refused before any row is read.
      bindCheck = Some(constant => constant(2).foreach {
        case n: java.lang.Number if n.longValue < 0 => throw Funcs.negativeLength(n.longValue)
        case _ => ()
      })),
    Entry("CONCAT", kind = Kind.Scalar, arity = Arity.atLeast(0), params = Seq(Text),
      result = Result.Fixed(DataType.StringType), nulls = Nulls.Propagate, vectorPath = true),

    // ---- flow ----
    Entry("COALESCE", kind = Kind.Flow, arity = Arity.atLeast(1), params = Seq(Branch), result = Result.Branches,
      nulls = Nulls.FirstNonNull, vectorPath = true, rules = Seq("branch-double")),
    Entry("IF", kind = Kind.Flow, arity = Arity.exactly(3), params = Seq(Condition, Branch, Branch),
      result = Result.Branches, nulls = Nulls.Chosen, vectorPath = true, rules = Seq("branch-double")),
    Entry("NULLIF", kind = Kind.Flow, arity = Arity.exactly(2), params = Seq(Compared, Compared),
      result = Result.FirstArgument, nulls = Nulls.Equal, vectorPath = true),
    Entry("GREATEST", kind = Kind.Flow, arity = Arity.atLeast(1), params = Seq(Branch), result = Result.Branches,
      nulls = Nulls.Skip, rules = Seq("branch-double")),
    Entry("LEAST", kind = Kind.Flow, arity = Arity.atLeast(1), params = Seq(Branch), result = Result.Branches,
      nulls = Nulls.Skip, rules = Seq("branch-double")),

    // ---- numbers ----
    Entry("ABS", kind = Kind.Scalar, arity = Arity.exactly(1), params = Seq(Number), result = Result.FirstArgument,
      nulls = Nulls.Propagate, errors = Seq(integerOverflow), vectorPath = true),
    rounding("ROUND", Nil, scale = true, carry = true),
    rounding("TRUNC", Seq("TRUNCATE"), scale = true, carry = false),
    rounding("FLOOR", Nil, scale = false, carry = true),
    rounding("CEIL", Seq("CEILING"), scale = false, carry = true),
    Entry("MOD", kind = Kind.Operator, arity = Arity.exactly(2), params = Seq(Operand, Operand),
      result = Result.Arithmetic("%"), nulls = Nulls.Propagate, operator = Some("%"),
      domain = Seq(Domain("a divisor of zero", Some("div-zero-null"), a => number(a, 1) == 0.0))),
    Entry("POWER", Seq("POW"), Kind.Scalar, Arity.exactly(2), Seq(Number, Number), Result.Fixed(DataType.DoubleType),
      Nulls.Propagate, rules = Seq("double-precision", "double-range"), domain = Seq(
        Domain("zero to a negative power", MathDomain, a => number(a, 0) == 0 && number(a, 1) < 0),
        Domain("a number below zero to a finite power that is not whole", MathDomain, { a =>
          val (x, y) = (number(a, 0), number(a, 1))
          x < 0 && math.floor(y) != y && !y.isInfinite && !y.isNaN
        }))),
    Entry("SIGN", kind = Kind.Scalar, arity = Arity.exactly(1), params = Seq(Number),
      result = Result.Fixed(DataType.IntType), nulls = Nulls.Propagate, errors = Seq(RuntimeError("NaN",
        """^SIGN of NaN is not defined; NaN is neither positive, negative nor zero$""".r))),

    // ---- math ----
    math1("SQRT", belowZero),
    math1("CBRT"),
    math1("EXP"),
    math1("LN", zeroOrBelow),
    Entry("LOG", kind = Kind.Scalar, arity = Arity.exactly(2), params = Seq(Number, Number),
      result = Result.Fixed(DataType.DoubleType), nulls = Nulls.Propagate, rules = Seq("double-precision"),
      domain = Seq(
        Domain("a base of zero or below it, or of 1", MathDomain, a => number(a, 0) <= 0 || number(a, 0) == 1),
        Domain("a logarithm of zero or a number below it", MathDomain, a => number(a, 1) <= 0)),
      // Its base is e in MySQL and Spark and 10 in Postgres and SQLite.
      refusedArities = Map(1 -> ("LOG(x) with one argument is not supported, as engines disagree on its base; " +
        "write LN(x) for base e, LOG10(x) for base 10, or LOG(base, x)"))),
    math1("LOG10", zeroOrBelow),
    math1("LOG2", zeroOrBelow),
    math1("SIN", infinite),
    math1("COS", infinite),
    math1("TAN", infinite),
    math1("ASIN", pastOne),
    math1("ACOS", pastOne),
    math1("ATAN"),
    math1("SINH"),
    math1("COSH"),
    math1("TANH"),
    Entry("ATAN2", kind = Kind.Scalar, arity = Arity.exactly(2), params = Seq(Number, Number),
      result = Result.Fixed(DataType.DoubleType), nulls = Nulls.Propagate, rules = Seq("double-precision")),
    math1("DEGREES"),
    math1("RADIANS"),
    Entry("PI", kind = Kind.Scalar, arity = Arity.exactly(0), result = Result.Fixed(DataType.DoubleType),
      nulls = Nulls.NoArgument),
    Entry("E", kind = Kind.Scalar, arity = Arity.exactly(0), result = Result.Fixed(DataType.DoubleType),
      nulls = Nulls.NoArgument),
    // RAND() reads a fresh value on every row; RAND(seed) is one value.
    Entry("RAND", Seq("RANDOM"), Kind.Scalar, Arity.between(0, 1), Seq(Integer("seed")),
      Result.Fixed(DataType.DoubleType), Nulls.Propagate, random = Set(0)),

    // ---- clock ----
    Entry("CURRENT_DATE", kind = Kind.Clock, arity = Arity.exactly(0), result = Result.Fixed(DataType.DateType),
      nulls = Nulls.NoArgument, clock = Some(now => LitExpr(LocalDate.ofInstant(now, ZoneOffset.UTC), DataType.DateType))),
    Entry("CURRENT_TIMESTAMP", kind = Kind.Clock, arity = Arity.exactly(0),
      result = Result.Fixed(DataType.TimestampType), nulls = Nulls.NoArgument,
      clock = Some(now => LitExpr(now, DataType.TimestampType))),

    // ---- aggregates ----
    Entry("COUNT", kind = Kind.Aggregate, arity = Arity.exactly(1), params = Seq(AnyValue),
      result = Result.Fixed(DataType.LongType), nulls = Nulls.Count, window = true, star = true,
      distinct = DistinctUse.Honoured, windowDistinct = DistinctUse.Honoured,
      // SQLite and DuckDB read COUNT() as COUNT(*) and Postgres refuses it:
      // the query says which count it means.
      refusedArities = Map(0 ->
        "COUNT() needs an argument: COUNT(*) counts the rows, COUNT(x) the rows where x is not NULL"),
      aggregate = Some((args, distinct) =>
        if (args.isEmpty) AggExprCountStar() else AggExprCount(args.head, distinct))),
    Entry("SUM", kind = Kind.Aggregate, arity = Arity.exactly(1), params = Seq(Number), result = Result.Sum,
      nulls = Nulls.Skip, window = true, distinct = DistinctUse.Honoured,
      errors = Seq(integerOverflow, decimalOverflow), rules = Seq("decimal-result-type"),
      aggregate = Some((args, distinct) => withDistinct(distinct, AggExprSum(args.head)))),
    Entry("AVG", kind = Kind.Aggregate, arity = Arity.exactly(1), params = Seq(Number), result = Result.Avg,
      nulls = Nulls.Skip, window = true, distinct = DistinctUse.Honoured, errors = Seq(decimalOverflow),
      rules = Seq("decimal-result-type", "double-precision"),
      aggregate = Some((args, distinct) => withDistinct(distinct, AggExprAvg(args.head)))),
    // DISTINCT cannot change an extreme, so MIN and MAX accept and ignore it.
    Entry("MIN", kind = Kind.Aggregate, arity = Arity.exactly(1), params = Seq(AnyValue),
      result = Result.FirstArgument, nulls = Nulls.Skip, window = true, distinct = DistinctUse.NoEffect,
      aggregate = Some((args, _) => AggExprMin(args.head))),
    Entry("MAX", kind = Kind.Aggregate, arity = Arity.exactly(1), params = Seq(AnyValue),
      result = Result.FirstArgument, nulls = Nulls.Skip, window = true, distinct = DistinctUse.NoEffect,
      aggregate = Some((args, _) => AggExprMax(args.head))),
    Entry("COUNT_IF", kind = Kind.Aggregate, arity = Arity.exactly(1), params = Seq(Condition),
      result = Result.Fixed(DataType.LongType), nulls = Nulls.Count, window = true,
      aggregate = Some((args, _) => AggExprCountIf(args.head))),
    statistic("STDDEV_SAMP", Seq("STDDEV", "STDEV"), 2, AggExprStddev(_, sample = true)),
    statistic("STDDEV_POP", Seq("STDEVP"), 1, AggExprStddev(_, sample = false)),
    statistic("VAR_SAMP", Seq("VARIANCE", "VAR"), 2, AggExprVariance(_, sample = true)),
    statistic("VAR_POP", Seq("VARP"), 1, AggExprVariance(_, sample = false)),
    bivariate("COVAR_SAMP", 2, AggExprCovar(_, _, sample = true)),
    bivariate("COVAR_POP", 1, AggExprCovar(_, _, sample = false)),
    bivariate("CORR", 2, AggExprCorr(_, _),
      Domain("a variance of zero, every x or every y alike", None, { rows =>
        def constant(i: Int) = rows.map(r => r.asInstanceOf[Seq[Any]](i).asInstanceOf[Number].doubleValue).distinct.size <= 1
        constant(0) || constant(1)
      })),

    // ---- window functions ----
    Entry("ROW_NUMBER", kind = Kind.Ranking, arity = Arity.exactly(0), result = Result.Fixed(DataType.LongType),
      nulls = Nulls.NoArgument, window = true, ranking = Some(() => WindowFnRowNumber())),
    Entry("RANK", kind = Kind.Ranking, arity = Arity.exactly(0), result = Result.Fixed(DataType.LongType),
      nulls = Nulls.NoArgument, window = true, ranking = Some(() => WindowFnRank())),
    Entry("DENSE_RANK", kind = Kind.Ranking, arity = Arity.exactly(0), result = Result.Fixed(DataType.LongType),
      nulls = Nulls.NoArgument, window = true, ranking = Some(() => WindowFnDenseRank())),
    Entry("LAG", kind = Kind.Offset, arity = Arity.between(1, 3), params = Seq(Branch, Integer("offset"), Branch),
      result = Result.Branches, nulls = Nulls.Offset, window = true, rules = Seq("branch-double"),
      offset = Some((value, n, default) => WindowFnLag(value, n, default))),
    Entry("LEAD", kind = Kind.Offset, arity = Arity.between(1, 3), params = Seq(Branch, Integer("offset"), Branch),
      result = Result.Branches, nulls = Nulls.Offset, window = true, rules = Seq("branch-double"),
      offset = Some((value, n, default) => WindowFnLead(value, n, default))))

  private val byName: Map[String, Entry] = Entries.flatMap(e => e.names.map(_ -> e)).toMap
  require(byName.size == Entries.map(_.names.size).sum, "a name belongs to two entries")

  /** TRIM, which JSqlParser parses into a node of its own rather than a
    * call by name. */
  val Trim: Entry = byName("TRIM")

  /** The entry `name` names, in any case. */
  def lookup(name: String): Option[Entry] = byName.get(name.toUpperCase(Locale.ROOT))

  /** The entry a call written as `name(...)` outside an `OVER` binds through:
    * a scalar, flow, operator or clock entry. */
  def call(name: String): Option[Entry] = lookup(name).filter(e => CallKinds(e.kind))
  private val CallKinds: Set[Kind] = Set(Kind.Scalar, Kind.Flow, Kind.Operator, Kind.Clock)

  /** The aggregate `name` names. */
  def aggregate(name: String): Option[Entry] = lookup(name).filter(_.kind == Kind.Aggregate)

  def isAggregate(name: String): Boolean = aggregate(name).isDefined

  /** The window function `name` names: a ranking or an offset function, or
    * an aggregate that runs as one. */
  def window(name: String): Option[Entry] = lookup(name).filter(_.window)

  def isKind(name: String, kind: Kind): Boolean = lookup(name).exists(_.kind == kind)

  /** Whether `name(DISTINCT ...)` binds as an aggregate. */
  def acceptsDistinct(name: String): Boolean = aggregate(name).exists(_.distinct != DistinctUse.Refused)

  /** Whether `name(DISTINCT ...) OVER (...)` binds. */
  def acceptsDistinctInWindow(name: String): Boolean = window(name).exists(_.windowDistinct != DistinctUse.Refused)

  /** Whether a call of `name` over `n` arguments reads a fresh value each
    * time it is evaluated. */
  def readsRandom(name: String, n: Int): Boolean = call(name).exists(_.readsRandom(n))

  /** The signature of the scalar or flow function `name` over `args`, or
    * None if no such function takes that many arguments. */
  def signature(name: String, args: Seq[Expr]): Option[Funcs.Signature] =
    call(name).filter(e => (e.kind == Kind.Scalar || e.kind == Kind.Flow) && e.arity.admits(args.size))
      .map(e => Funcs.Signature(e.paramsAt(args.size), e.resultType(args)))

  /** What each of a call's `n` arguments must be, for a scalar or flow
    * function that takes `n`; empty otherwise. */
  def paramsOf(name: String, n: Int): Seq[Param] =
    call(name).filter(_.arity.admits(n)).map(_.paramsAt(n)).getOrElse(Nil)

  // ---------------------------------------------------------------------------
  // The binder's refusals of a call
  // ---------------------------------------------------------------------------

  /** TRIM in any form but `TRIM(x)`: `TRIM(x, chars)`, `TRIM(LEADING FROM
    * x)` and the rest of its extended grammar. */
  def OnlySingleArgumentTrim: String = "Only the single-argument TRIM(x) is supported"

  /** A call `written` as it was, over arguments of `argTypes`, that no
    * function of that name takes. */
  def unknownFunction(written: String, argTypes: Seq[DataType]): String =
    s"Unknown function '$written(${argTypes.mkString(",")})'"

  /** An aggregate `written` as it was, with `n` arguments it does not take. */
  def unsupportedAggregate(written: String, n: Int): String = s"Unsupported aggregate: $written($n args)"

  /** `OVER` after a call of `upper` (its name in upper case), which is no
    * window function. */
  def unsupportedWindow(upper: String): String = s"Unsupported window function: $upper"

  /** A window function `upper` with no argument where it needs one. */
  def requiresArgument(upper: String): String = s"$upper requires an argument"

  /** The aggregate `written` where no aggregate can be. */
  def aggregateOutOfPlace(written: String): String = s"Aggregate '$written' used in non-aggregating position"

  // ---------------------------------------------------------------------------
  // CAST's target types
  // ---------------------------------------------------------------------------

  /** Each type a CAST can name by a name alone, and the names it goes by. A
    * DECIMAL is named with its precision and scale ([[DecimalCastNames]]),
    * and a BINARY is no CAST target. */
  val CastTargets: Seq[(DataType, Seq[String])] = Seq(
    DataType.IntType -> Seq("INT", "INTEGER"),
    DataType.LongType -> Seq("BIGINT", "LONG"),
    DataType.FloatType -> Seq("FLOAT", "REAL"),
    DataType.DoubleType -> Seq("DOUBLE", "DOUBLE PRECISION"),
    DataType.StringType -> Seq("STRING", "VARCHAR", "TEXT", "CHAR"),
    DataType.BooleanType -> Seq("BOOLEAN", "BOOL"),
    DataType.DateType -> Seq("DATE"),
    DataType.TimestampType -> Seq("TIMESTAMP"))

  /** The names of a CAST to a DECIMAL, each written with the type's precision
    * and scale, `DECIMAL(p,s)`: p digits in all, 1 to [[Decimals.MaxPrecision]],
    * and s of them after the point, 0 to p. */
  val DecimalCastNames: Seq[String] = Seq("DECIMAL", "NUMERIC")

  private val castTargetByName: Map[String, DataType] =
    CastTargets.flatMap { case (t, names) => names.map(_ -> t) }.toMap

  /** A DECIMAL's name as the parser renders it, `DECIMAL (10, 2)`, with none,
    * one or both of its numbers. */
  private val WrittenDecimal: Regex = """(?i)(DECIMAL|NUMERIC)\s*(?:\(\s*(\d+)\s*(?:,\s*(\d+)\s*)?\))?""".r

  /** Whether `p` and `s`, as written, are a precision and a scale a DECIMAL
    * has. */
  private def isDecimalType(p: String, s: String): Boolean = {
    val (precision, scale) = (BigInt(p), BigInt(s))
    precision >= 1 && precision <= Decimals.MaxPrecision && scale >= 0 && scale <= precision
  }

  /** The type a CAST names as `written`, in any case: a name of
    * [[CastTargets]], or a DECIMAL with its precision and scale. */
  def castTarget(written: String): Option[DataType] = written match {
    case WrittenDecimal(_, p, s) if p != null && s != null && isDecimalType(p, s) =>
      Some(DataType.DecimalType(p.toInt, s.toInt))
    case _ => castTargetByName.get(written.toUpperCase(Locale.ROOT))
  }

  /** A CAST to a type no CAST takes: a DECIMAL short of its precision or its
    * scale or past their range, or another type. */
  def unsupportedCastTarget(written: String): String = written match {
    case WrittenDecimal(name, p, s) =>
      val upper = name.toUpperCase(Locale.ROOT)
      if (p == null || s == null)
        s"A CAST to $upper names its precision and scale: write $upper(p,s), with p digits in all " +
          s"(1 to ${Decimals.MaxPrecision}) and s of them after the point (0 to p)"
      else
        s"$upper($p,$s) is no DECIMAL: one has 1 to ${Decimals.MaxPrecision} digits in all, its precision, " +
          "and 0 to that many after the point, its scale"
    case _ => s"Unsupported CAST target type: ${written.toUpperCase(Locale.ROOT)}"
  }
}
