# Gotchas and intentional non-features

The library/JVM/Bazel/JSqlParser footguns we've already stepped on, plus the
list of features deliberately left out of v1. Add to either list when you
ship a fix or move something from "not done" to "done".

## Known gotchas

- **JSqlParser 5.2 parses TRUE and FALSE as a `BooleanValue` in every
  position**, where 5.0 parsed them as column names and the binder read a
  column named `true` before the literal. Such a column is written in quotes,
  `"true"`, and `t.true` is a parse error.
- **No JSqlParser version parses two signs in a row**, `- -3` or `5 * - + 2`.
  `SqlParser.parseFailure` refuses the text naming the run when writing each
  run as one sign would parse; `5 - -3` (an operator, then one sign) parses
  as it is.
- **JSqlParser 5.2 cannot read three nested parentheses around a `BETWEEN`
  bound**: `a BETWEEN ( ( ( 0 ) ) ) AND 2`, `a BETWEEN 0 AND ( ( ( 2 ) ) )` and
  the `NOT BETWEEN` forms are `SQL parse error: Encountered unexpected token:
  "(" "("`. Two parse, as do any number around the tested value, after `LIKE`
  or beside `=`. SQLite's corpus writes the shape 13 times in 5.3 million
  records; write fewer parentheses.
- **No JSqlParser 5.2 literal holds a backslash before a quote.** In
  `'a\''b'` the parser reads `'a\'` as the literal and `'b'` as an alias, and
  `'a\'b'` does not parse at all, so text holding `\'` cannot be written as a
  literal; a backslash anywhere else, at the end included (`'a\'`), is itself.
  `PlanSql` refuses to print a plan with such a value
  (`UnprintablePlanException`); no literal written to the binder can make one.
- **A word no JSqlParser keyword list holds can still be read as one.**
  `FROM t CURRENT_TIMESTAMP` aliases `t` so, but `CURRENT_TIMESTAMP.c1` is
  the clock and a parse error; the reserved list (`ParserKeywordsUtils`) does
  not have it. `PlanSql.identifier` writes a plain word bare only if a test
  statement using it as a CTE, a table, an alias, a qualifier and a column
  reads it back as each, once a word, and quotes it otherwise.
- **The binder's casts to DECIMAL and BINARY print as their operand.** The
  binder inserts both (an INT beside a DECIMAL, a NULL beside a BINARY), and
  `PlanSql` prints such a cast as its operand, which the binder casts again in
  the same place, since the operand's types are the same. SQL cannot write a
  cast to BINARY, and the cast to DECIMAL it writes, `CAST(x AS DECIMAL(p,s))`,
  is another node (`DecimalCastExpr`): it rounds its value to the type and
  fails past its digits, where the binder's reads a number exactly, so printing
  the binder's as a written one would change what it answers. A string literal
  under a written one stays the text it is in the plan, so it prints back as
  the cast it was. A plan with a DECIMAL literal where no DECIMAL stands beside
  it, which the binder never makes, would print as a DOUBLE.
- **An INT or BIGINT beside a FLOAT meets it as a DOUBLE** (plans/testing/10,
  decision 2, taken overnight as recommended, not yet confirmed by the user).
  `DataType.widerNumeric` ranked a FLOAT above a BIGINT, so all three
  families (a comparison, a result both become, arithmetic) made such a pair a
  FLOAT, which holds no odd integer past 16777216:
  `CAST(16777217 AS INT) = CAST(16777216 AS FLOAT)` was TRUE, and
  `COALESCE(CAST(NULL AS FLOAT), 2147483647)` was 2.1474836E9. A DOUBLE holds
  every INT, and it is Spark's type for a comparison and a branch of the two.
  It changed the type of queries that ran before: `int_col * float_col`, and
  a FLOAT column times an integer literal, are DOUBLEs, and so is an output
  column that holds one. A BIGINT past 2^53 still loses its last digits beside
  a FLOAT or a DOUBLE, as in every engine. Pinned by `types/float_equality.test`
  and the coercion tables.
- **A CAST to DECIMAL fails two ways, by what it reads.** A number past the
  type is `Decimal overflow: ... does not fit decimal(p,s)`, an
  `ArithmeticException` while the query runs, as arithmetic's is (a FLOAT or
  DOUBLE named as it prints, `1.0E300`, not by every digit of its decimal).
  Text past the type is `Cannot cast '...' to decimal(p,s)`, an
  `IllegalArgumentException`, as text past an INT's range is, because a string
  literal under the cast is read when the query is bound, where an
  `ArithmeticException` would be no clean refusal. NaN and the infinities are
  `Cannot cast NaN to decimal(p,s)`, the binder's own cast's words for them.
  A number written in the query is read as the decimal it is written as
  (`decimalConstant`), so `CAST(1.0000000000000000001 AS DECIMAL(20,19))`
  keeps its last digit, which a DOUBLE would round away; one no DECIMAL holds
  (`1e-400`) stays the DOUBLE it binds as alone.
- **`EXPLAIN` is read off the text before JSqlParser sees it.**
  `SqlParser.explained` finds the `EXPLAIN` token and `VERBOSE` after it, and
  the query is the text that follows, bound as any query is, so every check the
  parser and binder make of a query holds for it too; JSqlParser's own
  `ExplainStatement`, which would carry the query past `refuseLostTailClauses`
  and the rejection pass, is never built. Anything that reads a task's SQL for
  its views must unwrap an EXPLAIN first, as `TaskDag.viewsRead` does.
- **A printed plan nests deeper than its query.** `PlanSql` writes the casts
  the binder inserted and parentheses around operands, so a query within
  `SqlParser.MaxNesting` can print as text past it. `PlanRoundTrip` reports
  that as a verdict of its own (`TooDeep`), which the slt `reprinted` mode
  runs as written and the fuzz guard counts.
- **The slt spill modes write a run a batch, with no limit.** At a one-byte
  threshold every batch an operator holds is a run, and `Modes.Spill` lifts
  `spill_max_runs` so tenk1's 10,000 rows fit (about 1,700 runs a partition in
  two-row batches, 10,000 at `--config=batch1`). An aggregate and a window read
  their runs one at a time, but `SortExec` merges every run at once, a file
  open for each: a script that sorts thousands of rows in a spill mode holds
  that many files open, past a 1,024-file `ulimit -n`.
- **JSqlParser's `AllTableColumns` is a subtype of `AllColumns`.** Pattern-match
  `AllTableColumns` first or you'll never reach the `table.*` branch.
- **JSqlParser's `SelectItem<? extends Expression>` generics confuse Scala
  inference.** `LogicalBuilder.buildSelect` annotates the list as
  `Seq[SelectItem[_ <: Expression]]` to keep `getExpression` typed.
- **`COUNT(*)` in JSqlParser is a `Function` with `AllColumns` in its
  parameter list, not an empty parameter list — and `Function.isAllColumns` is
  not the star.** JSqlParser sets `isAllColumns` for the `ALL` quantifier:
  `COUNT(ALL a)` arrives with `isAllColumns = true` and `a` in its parameters,
  while `COUNT(*)` leaves it false. `ALL` is the default quantifier, so the binder
  never reads the flag (`UnsupportedClauses` lists it as inert) and finds the
  star only as an `AllColumns` parameter. It used to read the flag as the star,
  which made `COUNT(ALL a)` count NULLs.
- **JSqlParser parses `(a - b)` as a `ParenthesedExpressionList`, not a
  parenthesized binary expression.** There is no parenthesis node in the 5.2 AST
  at all: the deprecated `Parenthesis` class still ships but nothing in the jar
  constructs it, so `(expr)` reaches the binder as a one-element list and
  `(a, b)` as a two-element one. Anything in `LogicalBuilder` that walks the AST
  therefore has to see through the one-element case or a paren the user wrote
  for grouping changes what the query means. `LogicalBuilder.Grouped` is that
  one rule, and every walk matches on it — see "grouping parentheses" below for
  the five sites and what happens if you add a sixth walk without it. A list of
  two or more elements is a row-value constructor (`(a, b) = (c, d)`), which the
  binder does not support and refuses as one (`A row value (a, b, ...) is not
  supported`).
- **Aggregate inside HAVING / ORDER BY** must be rebound against the aggregate
  output, not the source schema. `LogicalBuilder.bindExprWithAggs` takes an
  `aggResolver: Function => Option[Expr]` that returns a `ColRefExpr` into the
  aggregate output for known aggregates. The plain `bindExpr` passes
  `_ => None` and fails on any agg. "Known" means collected first:
  `buildAggregation` collects aggregates from the SELECT list, HAVING **and
  ORDER BY**. ORDER BY was missing from that list, so an aggregate named only
  there (`SELECT g ... GROUP BY g ORDER BY SUM(a)`) had no slot and binding died
  with a `NoSuchElementException`. The same parameter pattern is used for
  window functions via `windowResolver: AnalyticExpression => Option[Expr]`.
- **A `HAVING` decides the path, not just the filter.** `buildSelect`
  takes the aggregate path when there is a `GROUP BY`, an aggregate in the
  SELECT list, **or a `HAVING`**, and only that path reads `getHaving`. The third
  term was missing, so `SELECT 1 AS x FROM t HAVING COUNT(*) > 10` took the plain
  path and returned every row with the HAVING silently dropped — a getter read in
  one context and ignored in another, which the reflection test cannot see (see
  the plans/robustness/01 entry below). With no `GROUP BY` the aggregate has no
  keys, so it is one group even over an empty input, and a column outside an
  aggregate fails to resolve.
- **`AnalyticExpression` is its own JSqlParser node, NOT a `Function`.** So
  `SUM(x) OVER (...)` won't match `case f: Function` in `containsAggregate`
  / `childExpressions`. Both helpers explicitly short-circuit on
  `AnalyticExpression` so the window's inner expressions don't leak into
  outer-scope aggregate collection.
- **`COUNT(*) OVER ()` parses with `getExpression` returning an
  `AllColumns`, and `isAllColumns` false.** As on `Function` (above),
  `AnalyticExpression.isAllColumns` is the `ALL` quantifier, so
  `LogicalBuilder.bindAnalytic` finds the star as `getExpression instanceof
  AllColumns` alone and never reads the flag.
- **JSqlParser attaches a UNION's trailing `ORDER BY` / `LIMIT` / `OFFSET` /
  `FETCH` inconsistently.** When the tail starts with `ORDER BY` it lands on the
  `SetOperationList`; without one, `LIMIT` and `OFFSET` land on the last arm's
  `PlainSelect`; `FETCH` always lands on the list, so `OFFSET 1 ROWS FETCH NEXT 2
  ROWS ONLY` splits — the OFFSET on the arm, the FETCH on the list. In standard SQL
  every one of them applies to the whole union, so
  `LogicalBuilder.buildSetOperationList` hoists ORDER BY, LIMIT and OFFSET off a
  bare last arm. A clause the arm and the list both carry was written twice
  after the last arm (`... SELECT a FROM u LIMIT 1 LIMIT 2` gives the arm
  `LIMIT 1` and the list `LIMIT 2`), and is refused. A parenthesized last arm
  keeps its own (it parses as a `ParenthesedSelect`, which the hoist skips), and
  so does a `TOP n`, which is part of its arm's SELECT and limits that arm alone.
- **JSqlParser drops text and leaves no trace of it in the AST**, so the parser
  façade and the binder look for each way it does (plans/robustness/12):
  - `CCJSqlParserUtil.parse` parses one statement and stops at the `;` after
    it, so `SELECT a FROM t; DROP TABLE t` parsed as its first half.
    `SqlParser.parse` lexes the text with the parser's own token manager and
    refuses anything after that `;` but more of them and comments. Lex through
    `CCJSqlParserUtil.newParser(text).getNextToken()`, not a bare
    `CCJSqlParserTokenManager`: the token manager reads the parser's feature
    configuration for a string holding `\'`, which a bare one does not have.
  - A query's ORDER BY, LIMIT, OFFSET and FETCH are parsed by more than one
    production (`PlainSelect`, `SetOperationList`, and the `Select` around
    either), and a clause written twice sets the same field twice, so the AST
    keeps the last. `SqlParser.refuseLostTailClauses` counts each clause's
    keyword token in the text and in the statement rendered back with
    `toString`, and refuses the clause the rendering has fewer of. A token scan
    of the text alone cannot do it: JSqlParser takes `limit`, `offset` and
    `order` as column names, which appear in both counts. After a bare UNION
    arm the second LIMIT or OFFSET is split onto the arm instead (above).
  - Any number of ONs on any join: `t JOIN u` has none (it ran as a cross
    join), `t CROSS JOIN u ON c` and `t, u ON c` have one (each ran as an
    inner join), `t JOIN u ON c1 ON c2` has two (they were ANDed), and the
    standard's nested `t JOIN u JOIN v ON c1 ON c2` arrives as a join with none
    and one with two. `LogicalBuilder.joinConditions` refuses each.
  - A `NOT` hung on an operator's left side: `NOT NOT a = 1` parses as
    `NOT ((NOT a) = 1)`. JSqlParser 5.0 parsed a lone `NOT a = 1` so too as the
    argument of a call that takes one (`COUNT_IF`, `MAX`, `UPPER`, a window
    function's); 5.2 reads it as SQL does. Parentheses always reach the AST,
    so a `NotExpression` that is an operator's left operand was written without
    them, and `LogicalBuilder.leftSide` refuses it. Three NOTs in a row do not
    parse at all.
  - A WITH that defines a name twice kept the second in the scope map.
- **JSqlParser keeps a literal's text and a name's quotes, not what they mean**
  (plans/robustness/13), so the binder reads past each getter:
  - `StringValue.getValue` is the text between the quotes as written, each
    quote in it doubled (`it''s`); `getNotExcapedValue`, spelled so, is the
    value (`it's`). The audit once listed the second as inert with the reason
    the wrong way round, and `LENGTH('it''s')` was 5. A backslash is no escape
    in JSqlParser's default lexer: `'it\'s'` is a lexical error.
  - Every identifier getter keeps the quotes: `Column.getColumnName`,
    `Table.getName` and `Alias.getName` return `"a"` and `` `a` `` as written,
    and `Table.getNameParts` each part with its quotes. Every place the binder
    matches a name reads it through `SqlParser.unquote`; a name it builds and
    must read back exactly (a `*` expanded into its columns) goes through
    `SqlParser.quote`. An `@` inside quotes is part of a name, not a database
    link. `TablesNamesFinder`, which `TaskDag` reads a task's tables with,
    keeps the quotes too, and leaves a CTE out of the tables only where the
    reference is spelled exactly as the CTE (`WITH Q ... FROM q` and `WITH "q"
    ... FROM q` named `q` a table, and the job failed as an unknown view), so
    `TaskDag` unquotes what it returns and takes the CTE names out itself.
  - An `Alias` can be a string literal: `SELECT 'a' 'b'` parses as `'a' AS
    'b'`, and `FROM t AS 'x'` and `SELECT a AS 'x y'` parse too, `getName`
    returning the quotes. The binder refuses each.
  - `LongValue.getValue` is a `long`, and throws `NumberFormatException` past
    BIGINT; `getBigIntegerValue` holds every literal. A minus is never part of
    the literal, so `-9223372036854775808` arrives as a minus over
    9223372036854775808, which no BIGINT holds: `LogicalBuilder.IntegerLiteral`
    folds that one minus in.
  - `DoubleValue.getValue` is the DOUBLE the digits round to: `1e400` is
    `Infinity`, `1E-400` is 0, `1.0000000000000000001` is 1.0. Its `toString`
    returns the digits as written, which is where `LogicalBuilder` reads a
    number beside a DECIMAL from.
  - `toString` renders a node as written. The binder matches a SELECT item
    to a GROUP BY key by that text, and lower-cased all of it, which made
    `s || 'a'` the key `s || 'A'`; `LogicalBuilder.canonicalText` lower-cases
    only what is no literal and unquotes the names.
- **Every walk over a parsed statement recurses, so a query nests at most
  `SqlParser.MaxNesting` (256) levels** (plans/robustness/14). JSqlParser's
  parser, its `toString` (which `SqlParser` renders a statement with),
  `TablesNamesFinder` (which `TaskDag` reads a task's tables with),
  `UnsupportedClauses.reject`, the binder, the optimizer's passes and
  expression evaluation all recurse a level of the tree at a time, and past a
  few hundred levels one of them overflows its stack. Where it did moved with
  the machine: a thread gets 1 MB of stack on Linux and 2 MB on macOS, the
  JIT shrinks frames as it compiles, and the shallowest overflow measured
  (`-Xss1m`, cold) was at about 600 levels, 300 nested CASEs or 300 levels of
  `a + (...)`. So `SqlParser.parse` measures the tree first, iteratively, and
  refuses one deeper than 256, which every walk clears on any machine. Two
  long shapes are regrouped instead, since each is associative: an AND or OR
  chain into a balanced tree of its operator (`SqlParser.regroupChains`,
  before the depth is measured), and the arms of a UNION into a balanced tree
  per run of one kind (`LogicalBuilder.balancedUnion`). A pass that splits a
  predicate into its conjuncts and joins what it keeps must rebuild a balanced
  tree too (`JoinSideAnalysis.conjunction`): `FilterPushdown` and the
  planner's join residual folded them left-deep, which undid the regrouping
  and overflowed `ColumnProjectionPushdown` on a 5000-term WHERE. A new walk
  that recurses more per level than these, or a new rewrite that rebuilds a
  long list left-deep, needs measuring at `-Xss1m`.
- **JSqlParser backtracks, and some nesting costs it seconds.** Unclosed
  parentheses did: `SELECT (((a +` took seven seconds to fail and `((((a +`
  ran it past its time limit (`Time out occurred.`), so `SqlParser` checks the
  text's parentheses before the parser sees them, lexing it with the parser's
  own token manager so a parenthesis in a string, a quoted name or a comment
  does not count. Balanced nesting can still be slow: 25 nested CASTs time
  out, 10 nested IFs take three seconds, 200 nested SUBSTRINGs four. Nothing
  outside the parser can make that faster; the timeout's message says what
  causes it. The parser's thread also keeps working for a few seconds after
  its time limit fails the parse.
- **`Fetch.getRowCount` throws unless the count is an integer literal.** It
  unboxes the `LongValue` behind `getExpression`, so `FETCH FIRST ROW ONLY` (no
  count), `FETCH FIRST ? ROWS ONLY` and `FETCH FIRST -1 ROWS ONLY` all throw a
  `NullPointerException` from it. The binder reads `getExpression` (a missing
  count means one) and the audit lists `getRowCount` as inert, so the rejection
  walk never calls it either.
- **A DATE boxes to `LocalDate` and a TIMESTAMP to `Instant`, on both
  evaluation paths.** That is what `DateVector.getBoxed` and
  `TimestampVector.getBoxed` return, so every row-path value of those types must
  be the same class, or `Ops.eq` calls it unequal to a column's value and
  `Ops.cmp` throws. `Casts.cast` returned an epoch-day `Int` for DATE, which the
  vector path absorbed (`setBoxed` takes an `Int`) and `ExprBatchTest`'s
  `normalize` hid; `{ts '...'}` bound to a `LocalDateTime`, which
  `TimestampVector.setBoxed` rejects. Both box canonically now, and
  `castToDateAndTimestampBoxLikeTheVector` compares the classes, not just the
  normalized values.
- **Values of different types meet only as numbers, or through a string
  literal.** `Analyzer.unify` types every set of operands that meet: a
  comparison, arithmetic and a `CASE` switch (through `promotePair`, its
  two-operand case), a `BETWEEN`'s three, an IN list and NULLIF's two. Numbers widen to the
  widest among them, a string literal beside another type is read as that type
  at bind time through `Casts`, and every other mix is an error, naming the CAST
  to write where one would say what is meant. The rule it replaced coerced the
  other side of any comparison with a string to text, so `9 < '10'` was false
  (`'9'` sorts after `'1'`), `1.0 = '1'` was false, and `2026-01-01T05:30:21Z`
  met `'2026-01-01 06:00:00'` character by character, its `T` meeting a space at
  index 10. IN lists had a rule of their own that cast every item to the left
  side's type, so `i IN (9.5)` matched a 9, and so did `i IN (4294967305)`, by
  overflow. Promoting a DATE beside a TIMESTAMP to its midnight, as some engines
  do, would make `d = ts` match only midnight, and a boolean beside a number
  would have to mean 1 and 0, which is why those pairs need a CAST too. The same
  strictness runs through `CAST`: a string literal under one is read at bind
  time (folded, or under a CAST to DECIMAL checked and kept as its text), text
  that is no number fails as `Cannot cast 'x' to int` rather than
  Java's `NumberFormatException`, BOOLEAN reads only `true` and `false` (Java's
  `parseBoolean` read every other text as false, and `WHERE NOT s` returned
  every row), `Analyzer.checkCast` refuses numbers and booleans to or from
  either temporal type, and a DATE reads only an ISO date.
  `Analyzer.checkArithmetic` makes arithmetic on anything but a number a bind
  error, unary minus included, where it used to throw `IllegalStateException`
  mid-drain. A `DECIMAL` is the widest number, so the rest widen to it through
  `Casts`: an integer exactly, a `FLOAT` or `DOUBLE` as the decimal it prints as
  (`Casts.toDecimal`; `0.1` is `0.1`, not the binary fraction nearest it), and
  `NaN` or an infinity is an error naming it. That cast used to be missing, so
  `price = 1` threw `CAST to decimal(10,3) not implemented` mid-drain.
  Arithmetic is the one place a `DECIMAL` meets the rest differently
  (`Analyzer.arithmetic`): its result's type is built from both sides' digits,
  so an integer is read as the `DECIMAL` its every value fits rather than as the
  other side's, and a `FLOAT` or `DOUBLE` that is not written in the query is a
  bind error, since no `DECIMAL` it could be read as says how much of it the
  result keeps (a number written in the query is the decimal of its digits, and
  arithmetic on numbers alone is computed in `DECIMAL` there, rebound from the
  parse tree by `LogicalBuilder.decimalConstant`, since a bound `1 + 0.05` is a
  `DOUBLE` whose implicit CASTs look like a written one). A
  `BINARY` casts to nothing else, explicitly or implicitly (`checkCast`, and
  `implicitCast` and `branches` beside any other type), so `Analyzer.checkText`
  keeps it off `||`, `LIKE` and the string functions and `Analyzer.condition`
  out of a condition, as they keep off every type but their own: each read the
  array's identity hash as text. When a new type meets these rules, extend them;
  do not loosen them (see the strictness entry in [conventions.md](conventions.md)).
- **Values that become one result meet through `Analyzer.branches`.** The
  `THEN` and `ELSE` values of a `CASE`, the arguments of `COALESCE`, `GREATEST`
  and `LEAST`, `IF`'s two values, `LAG`'s or `LEAD`'s value and default, and
  each column across a `UNION`'s arms (`LogicalBuilder.unifyArms`) meet by
  `unify`'s rule, through the same `meet`, but for a `DECIMAL`. A comparison
  only reads its operands; these values are kept, under the type found, so it
  must hold each of them. Two decimals meet as `Decimals.holding`, the longer
  integer part and the larger scale (Spark's `widerDecimalType`), given way
  past 38 digits as `Decimals.resultType` does; an integer as the `DECIMAL` its
  every value fits; a number written in the query as the decimal it is
  (`writtenDecimal`, with a constant rebound through `decimalConstant` by
  `LogicalBuilder.bindBranches`); and a `FLOAT` or `DOUBLE` that is not written
  in the query is a bind error, as in arithmetic. Each used to take its first
  value's type (plans/robustness/09): a cast between decimals kept the value,
  so a later branch with more digits sat under a type that could not hold it
  and the parquet writer refused it when the result was written or spilled;
  `THEN 1 ELSE 2.5` was the `INT` 2; `COALESCE` and `IF` cast nothing, so
  `IF(p, TRUE, 0)` and `COALESCE(d, ts)` failed mid-drain setting the first
  type's vector; and a `UNION` declared its first arm's types over the other
  arms' values as they were. A CAST between two DECIMALs is now a value of the
  target (`Decimals.fit`: rounded half up, an error past its digits), so a
  comparison of two decimals reads both as `Decimals.wider`, the holding type
  with no bound on its digits: a comparison's operands are never stored, and a
  bounded type would round one of them. Any other number the binder casts to a
  `DECIMAL` is still read exactly (an integer at the target's scale), since a
  comparison reads an `INT` beside a `DECIMAL(5,2)` as it is; a query's own
  `CAST(x AS DECIMAL(p,s))` is another node, which fits every value
  (plans/testing/10). A `UNION` arm that ends in
  its SELECT list is retyped in place, so a literal there is read as the type it
  meets; any other arm is wrapped in a casting projection. NULLIF compares
  through `unify` and returns its first operand as it is, the one place the
  binder leaves `Ops.decimalCompare` a mixed pair.
- **A condition is a BOOLEAN, and an argument is the kind its function
  declares.** Every place a truth value is read (WHERE, HAVING, ON, CASE WHEN,
  IF's first argument, COUNT_IF as an aggregate and as a window, each side of
  AND, OR and NOT) binds it through `Analyzer.condition`: a BOOLEAN as it is,
  NULL cast to one, and anything else a bind error naming the comparison or the
  `CAST(x AS BOOLEAN)` to write. Most of those sites used to take
  `implicitCast(_, BooleanType)`, so a number was TRUE where it was not 0 (NaN
  included), text was parsed on every row, and a DATE or TIMESTAMP went through
  `Casts`' fallback, its text compared with `"true"`, so `WHERE d` kept no rows;
  a join's single ON condition and IF's condition took no cast at all and died
  on their first row with a `ClassCastException` (`IF(NULL, 1, 2)` too, on the
  vector path, where `VecFuncs.iff` casts its condition's vector). A function
  declares what each argument must be beside its arity, in its
  `FunctionCatalog` entry (`Param.Number`, `Param.Integer` for a scale, a
  position, a length or a seed, `Param.Text`, `Param.Condition` for a truth
  value, and for the flow functions `Param.Branch` and `Param.Compared`, which
  `branches` and `unify` type), and `LogicalBuilder` checks each argument
  against it through `Analyzer.checkArgument`; the aggregates that add their
  values up check theirs in `bindAgg` and `bindAnalytic`, by the same entries.
  `Funcs.signature` replaced a `returnType` that matched on the argument count
  alone, so `SUM(s)`
  and the math functions over a date bound and threw `ClassCastException`
  inside the worker, `ABS` of a date fell through to `case _ => null` and
  answered NULL on every row, `ROUND(x, 1.5)` cut its scale with `intValue`,
  `SUBSTRING(s, 3000000000)` wrapped its position to a negative INT and answered
  the whole string, and `ROUND(x, 1, 2)` ignored its third argument. A NULL
  scale, position, length or seed answers NULL on both paths, where `ROUND`'s,
  `SUBSTRING`'s and `RAND`'s threw `NullPointerException`. `ROUND` and `TRUNC`
  are `Rounding.apply` on both paths, exact: a number is rounded as the decimal
  it is (a DOUBLE as the decimal it prints as), and NaN and the infinities are
  their own answer, where the old double arithmetic's `math.round` of the
  scaled value clamped to a long past 2^63 (`ROUND(1e10, 10)` was
  922337203.6854776) and read NaN as 0, and `math.pow(10, scale)` overflowed to
  an infinity or underflowed to 0 for a scale of 400 or -400.
- **The clock keywords are a `TimeKeyExpression`, spelled as written.**
  `current_date`, `CURRENT_DATE()` and DB2's `CURRENT DATE` arrive as three
  different strings, and `CURRENT_DATE ()`, with a space, arrives as a
  zero-argument `Function` instead. `UnsupportedClauses.clockKeyword` folds the
  spellings for the audit and the binder alike, and the binder sends the call
  form to the same place. That place is `LogicalBuilder.clockLiteral`, which
  builds the literal by the keyword's `FunctionCatalog` entry (kind `Clock`),
  not `Funcs`: `build` captures one instant per statement and both keywords
  bind to a `LitExpr` of it, which is what keeps them equal across rows, partitions and
  CTE references. A `Funcs` entry would read the clock per row and stamp a large
  result with many instants. The instant is truncated to microseconds, the unit
  a `TimestampVector` holds, so the literal also equals a stored copy of itself
  on the row path.
- **Parquet `MessageType` constructor takes `java.util.List[Type]`, but a
  `Vector[PrimitiveType].asJava` gives `java.util.List[PrimitiveType]`.**
  Scala won't unify because of Java type invariance. `ParquetSchema.toMessageType`
  maps `_.asInstanceOf[Type]` first.
- **No circular module deps.** `read/parquet` and `write/parquet` do not
  depend on each other, and `job/` depends on both. Each holds its own half of
  the type mapping on purpose: `ParquetSchema` is what transformer writes, and
  `ParquetColumns` reads whatever the file says, which for a file another tool
  wrote is often something `ParquetSchema` would never produce (see "A
  parquet scan reads each file's columns as that file stores them" below).
- **`CsvWriter`'s encoders must be initialized before the header is written.**
  Class-body order matters: `checker` and `out` sit above the
  `if (options.header)` block, whose `formatField` calls use both.
- **Output paths are directories, not files.** Anything Spark/Hive-y you might
  expect (`output/foo.csv`) is now a directory of `part-NNNNN.csv` files; older
  examples that wrote a single named file have been renamed. Path-based format
  detection still looks for `.csv` / `.parquet` substrings, so an ext-less
  directory path (e.g. `output/totals`) needs `format = Some("parquet")` or
  the default (CSV) wins. `DirectoryJobLoader` sets `format` explicitly to keep
  the per-table dir name clean (`output/<view>/...` rather than `output/<view>.csv/...`).
  The tasks after a task read exactly the part files its run wrote, by name,
  and a directory holding part files no `_run.json` names is refused before
  a write: such files used to join the output, so a stray `part-00007.csv`
  added its row to a downstream count (plans/robustness/29).
- **A failed run left part files and no record, and its rerun refused them.**
  Each part was renamed into place the moment its own partition ended, a
  failure elsewhere deleted only temp files, a validation that threw or a
  final record that could not be written (swallowed, so the task said
  `Succeeded`) came after every part was in place, and `clearIfMarked`
  deleted the record before the files. So a later partition overflowing, a
  validation failing to run, a full disk or a kill left `part-*` files that no
  `_run.json` names, which the next run refused as someone else's until a
  person deleted them, and a reader outside the job read the partial table.
  A run now marks the directory `Running` first, stages every part and
  renames them together, writes the final record last and fails the task when
  it cannot, and clears a failed run's files under its record
  (`OutputDirectory`, architecture.md §3a; plans/testing/06). The GUI's Persist
  had its own copy of the old steps and wrote over stray parts, naming them
  all in its record; it goes through the same protocol now. The fault
  matrices then found the order a skipped task (or one failing before it
  wrote) used, its record first and the earlier parts deleted after, leaving
  a `Skipped` or `Failed` record beside parts when the clear stopped part
  way; such a run marks the directory `Running` first now, as decision 1
  says of any run that deletes a file there.
- **A kill as a run creates a task's directory leaves it with no record.** A
  run creates a fresh output directory as it writes the first record there
  (`TaskRunRecord.write`: the directory, then `_run.json.tmp`, then the
  rename); a halt in between, in another thread than the one a fault halted,
  leaves the directory empty or holding only `_run.json.tmp`. That holds
  nothing a reader takes for output and nothing the next run refuses (no
  part file), and its mark clears the temp file; closing the window would
  need the directory and its record created in one rename. The crash
  campaign found it, and its directory invariant allows exactly that.
- **Two runs writing one output directory at once are unsupported.** A
  `Running` record from a live run in another process looks the same as one a
  killed run left, so a second run started meanwhile clears the first's
  files. Schedule one run per output directory at a time.
- **An outside reader of an output directory checks its `_run.json`.** No part
  file appears before the run commits, and staged parts and records start with
  `.` or `_`, which Spark, DuckDB and the engine's own `PathGlob` skip; but a
  crash during the commit's renames leaves some parts under a `Running`
  record. Read a directory when its record says `Succeeded` or
  `ValidationFailed`, and then the files it names.
- **A build from before `Running` reads that record as no record.** Its
  `TaskRunRecord.read` returns None for a status it does not know, as for any
  record it cannot read, so it shows the task as not run. `isMarked` asks only
  whether `_run.json` exists, so its next run still clears the directory
  rather than refusing it.
- **Hadoop-common pre-3.4.3 breaks on JDK 24+.** `UserGroupInformation.getCurrentUser()`
  in 3.3.x/3.4.0/.1/.2 calls `Subject.getSubject(AccessControlContext)`, which
  throws `UnsupportedOperationException` once JEP 486 (Security Manager
  permanently disabled) lands — default in JDK 24+, hard-fails in JDK 25.
  3.4.3 switches to `SubjectUtil.current()` via HADOOP-18583. Every parquet
  read/write goes through `Path.getFileSystem` → `FileSystem.Cache.Key` → UGI,
  so the whole parquet path blows up on older Hadoop + new JDKs. If you need
  to pin Hadoop lower than 3.4.3, hold the runtime to JDK 23.
- **Hadoop's `LocalFileSystem` writes hidden `.crc` sidecars** alongside any
  Parquet temp file; the atomic rename leaves them stranded inside the output
  directory. A task's own re-read names its part files and never sees them;
  `PathGlob.expand`, which an input reading such a directory goes through,
  skips dotfiles and `_`-prefixed files so those reads ignore them too (and
  macOS `.DS_Store`, and the `_run.json` records + per-task
  `_validation-<slug>.csv` failure samples we stamp per task — see
  [architecture.md §4](architecture.md#4-run-records-and-historical-run-discovery)).
- **JavaFX 21+ ships classes only in platform-classifier jars.** The bare
  `org.openjfx:javafx-{base,controls,graphics}` artifacts are metadata-only —
  depending on them alone gives "not found: type Stage" at compile time. The
  `gui/` BUILD lists the `mac-aarch64` classifier jars and not the bare ones,
  which hold no class, so the unused-dependency checker refuses them. Add
  other platforms (`win`, Linux) the same way when building
  cross-platform binaries. The product jar (`//:transformer`) lists the
  `mac-aarch64` and `win` classifiers, so `transformer gui` runs on Apple
  Silicon macOS and Windows only. On Linux and Intel macOS the GUI fails to
  start, but `transformer run` (which never touches JavaFX) works.
- **A jar's main class must not be a JavaFX `Application`.** When the class
  `java -jar` starts extends `javafx.application.Application`, the launcher
  insists on JavaFX being on the module path and stops with "JavaFX runtime
  components are missing", though every JavaFX class is in the jar. So each
  jar starts a plain object that calls `Application.launch`:
  `TransformerMain` for `//:transformer`, `ViewerMain` for
  `//:transformer_viewer`, `GuiAppLauncher` for `//examples/gui_app`.
- **The data viewer names a table `<dataset>__<table>` because the catalog is
  flat.** The binder refuses a qualified name (`FROM other.t` is "A qualified
  table name" in `UnsupportedClauses`), and a quoted `"a.b"` would read as a
  dataset and a table where it is one name. `QueryCatalog.catalogFor` turns
  `jaffle_shop.customers` into an error naming `jaffle_shop__customers` before
  the engine sees it. Schema-qualified names would be an engine change, in
  the binder and in its audit.
- **The viewer draws lineage only from records that carry it.** A `job.json`
  written before runs recorded `inputs`, `readsInputs` and `readsTasks` opens
  with its tables and no edges, so a table's Lineage view is the table alone;
  the view, its Details and its box say the lineage was not recorded
  (`LineageCanvas.notRecorded`), since a lone card reads as a table with no
  upstreams. Before then `runFile` was
  as relative as the run's `--output`, so `RunFolder.locateRunFile` looks for a
  relative one under the run's directory and each directory above it. A
  folder moved since its runs keeps its records' old absolute paths: the
  viewer finds each table under the run's directory now, and matches inputs
  against the paths as recorded, so lineage survives the move. A path through
  a symbolic link (`/tmp` is `/private/tmp` on macOS) matches the path it
  leads to.
- **JavaFX fires a scene accelerator only for a key no node consumed.**
  `KeyboardShortcutsHandler` runs accelerators in the bubbling phase, after
  the focused node. So the SQL editor's vim mode, which consumes every key it
  reads, keeps them from the window: Esc never presses a dialog's Cancel while
  an editable editor has focus (by design), and on Windows, where Ctrl is the
  shortcut key, Ctrl+R in an editor is vim's redo rather than Run. On macOS
  vim reads Ctrl and the window keeps Cmd. A key the editor should leave to
  the window is `Pressed.PassThrough` in `VimKeyEvents`.
- **CSS names one font family, with no fallback list.** A family that is not
  installed falls back to the default UI font, so `-fx-font-family:
  "JetBrains Mono"` in a stylesheet sets code in a proportional font on a
  machine without it. `Theme.monoFamily` picks the first installed family of a
  preferred list, and `Theme.style` adds a generated stylesheet naming it.
- **A stylesheet added to the main scene does not reach a dialog.** Each
  `Dialog` and `Alert` has a scene of its own. The theme reaches them as the
  user-agent stylesheet, but `transformer.css` (the editor's colours, the
  chips, the cards) only through `Theme.style(dialog)`.
- **AtlantaFX is pinned at 2.0.1.** 2.1.0 declares `javafx-controls` 23, and
  rules_jvm_external's resolution takes the highest version, which would lift
  the whole GUI off JavaFX 21.0.1; 3.0.0 is Java 25 bytecode. Check
  `bazel query 'deps(@maven//:io_github_mkpaz_atlantafx_base)' --output build`
  for the JavaFX version before moving it.
- **RichTextFX's line highlighter can stay drawn after it is turned off.** A
  `CodeArea` made editable and then read-only kept a highlighted band on a
  line, so the editor marks the current line in the gutter instead.
- **An `HBox` too narrow for its children cuts a button's label to an
  ellipsis.** It takes the shortfall from every child in equal shares, down to
  each one's minimum width, and a `Button`'s minimum is its ellipsis; `hgrow`
  only shares out extra space. A `FlowPane` prefers at least its
  `prefWrapLength` (400px) however few children it holds, so the "Inspect
  validations" button beside the validation chips showed "Inspect v...". A
  button whose label must stay whole gets `setMinWidth(Region.USE_PREF_SIZE)`
  (as `ControlsPanel`'s do) and a row where nothing wide competes with it.
- **A button's text loses its first underscore.** `Button`, `MenuButton`,
  `CheckBox` and the other `ButtonBase` controls read `_` as the mark before a
  mnemonic key and drop it, so a column named `count_lifetime_orders` showed
  as `countlifetime_orders` in the chart's Y menu. Text that is data (a column
  or table name) goes into such a control with `setMnemonicParsing(false)`; a
  `Label`, a `ComboBox`'s cells and a canvas show it as it is.
- **A snapshot of a canvas scaled up is blurred.** A `Canvas` is a bitmap the
  size of its node, so `snapshot` with a scale transform enlarges the pixels.
  `ChartCanvas.image` paints the chart again into a canvas twice the size,
  under `gc.scale(2, 2)`, and snapshots that for Save PNG…. A canvas's
  `GraphicsContext` also keeps what the last paint set (text alignment,
  baseline, line width, caps), so each `ChartCanvas` paint sets them back
  before it starts.
- **Bazel prints a compiler warning only when its target compiles.** A cached
  target prints nothing, so a warning that does not fail the build is seen
  once, by whoever changed the file, if they look. That is why every warning
  is an error here ([conventions.md](conventions.md)); the ones the toolchain
  leaves off print with `--extra_toolchains=//:scala_toolchain_all_warnings`,
  which changes every compile action and so recompiles every target.
- **A `-Wconf:src=` filter matches a source's real path.** scalac reads the
  canonical path, which resolves Bazel's sandbox and execroot symlinks to the
  file in the workspace (or under the output base's `external/` for another
  repository's source), and with no `-rootdir` the regex need only match a
  suffix that starts at a path segment. So `src/test/scala/.*` matches every
  test source wherever the checkout lives, and `execroot/_main/src/.*`
  matches nothing (Bazel 7.4.1, rules_scala 7.0.0).
- **The root module's Scala toolchain compiles rules_scala's sources too.**
  `register_toolchains("//:scala_toolchain")` in MODULE.bazel is tried before
  the toolchains rules_scala registers, so ours wins resolution, and
  rules_scala's own Scala targets (the JUnit runner's test discovery, the
  dependency analyzer) compile with it: its Stream and JavaConverters
  deprecations would fail the build, hence the toolchain's
  `-Wconf:src=external/rules_scala[^/]*/.*:s`.
  `bazel cquery <target> --toolchain_resolution_debug='@@rules_scala~//scala:toolchain_type'`
  shows the choice (`Selected //:scala_toolchain_impl`); if rules_scala's
  default won, the options would do nothing and nothing would fail.
- **`java -jar` ignores a `scala_binary`'s `jvm_flags`.** `//:transformer`
  sets `-XX:MaxRAMPercentage=75.0`, but that only applies to the
  `bazel-bin/transformer` wrapper script. Users running
  `transformer_deploy.jar` get the JVM default heap (often 25% of RAM) unless
  they pass `-Xmx`. README and `site/setup.html` say so.
- **Airflow renders `{{ ... }}` in a `bash_command` before transformer sees
  it.** An `--output` path holding transformer's `{{ today }}` arrives with
  Airflow's value instead (or empty, since Airflow has no `today`). Use
  Airflow's own `{{ ds_nodash }}` in the command line. Templates inside the
  job directory (`config.json`, `main.sql`, `output.json`) are never seen by
  Airflow, so they are unaffected.
- **`TaskDag` / `TaskDagNode` are public** despite living next to runner
  internals — the GUI needs them to render structure without re-implementing
  the analyzer. Don't accidentally narrow visibility when refactoring.
- **Parquet write parallelism is heap-bounded, not `cores`-bounded.**
  Each in-flight `ParquetWriter` pins a 32MB row-group buffer plus per-column
  dictionary pages (≈1MB × column count). `defaultWriteParallelism = min(cores,
  maxHeap / 256MB)` — on a 2GB heap that's 8; on a 12GB heap (the GUI default
  via `-XX:MaxRAMPercentage=75.0` on a 16GB box) it's `min(cores, 48)`.
  Override per-task via `options("parquet_write_parallelism")` and
  `options("parquet_row_group_size")` if the schema is narrow enough that you
  can push harder.
- **Default parquet write compression is `snappy`** (matches Spark / pyarrow
  / DuckDB). The intuition "snappy is just CPU overhead, intermediate parquet
  on SSD is fine uncompressed" is wrong for high-cardinality string columns:
  dictionary encoding (always on) makes the dictionary pages dominate the
  on-disk bytes, and snappy compresses those 30-50% — net write time goes
  DOWN under snappy on snapshots-shaped data (`market_id`-style 66-char hex
  strings repeated millions of times). Benchmark from one ~5M-row write:
  uncompressed 9.1s/86MB vs snappy 7.3s/54MB. For genuinely narrow-numeric
  tables (orderbook prices + timestamps) override per-table via
  `output.json`'s `options.compression = "uncompressed"`; the legacy alias
  `NONE` is accepted for symmetry with Spark.
- **Parquet decode is vectorized**, not row-at-a-time. `ParquetPartitionIterator`
  uses `ColumnReadStoreImpl` to expose one `ColumnReader` per column per row
  group and copies values straight into `ColumnarBatch` primitive vectors —
  no `Group`/`SimpleGroup` allocations, no `Integer`/`Long` boxing per cell.
  To read a new kind of column, add a `ParquetColumns.Decoding` case, the
  annotation that selects it in `ParquetColumns.decoding`, and its loop in
  `decodeColumn`; to write a new `DataType`, extend `ParquetSchema.toMessageType`
  and `BatchWriteSupport.write`. The decode match is over a sealed type, so a
  missing loop is a compiler warning; a missing write case is a runtime
  `UnsupportedOperationException`.
- **A parquet scan reads each file's columns as that file stores them.**
  `ParquetPartitionIterator` asks parquet-mr for the view's columns with
  `setRequestedSchema`, and parquet-mr decodes each column chunk as whatever
  type the request names, not as the file stores it. The request used to be
  rebuilt from the view's engine types with the writer's mapping
  (`ParquetSchema.toMessageType`), so a projected scan — any query that reads
  some of a file's columns — decoded an `INT96` timestamp as `INT64` (dates
  thousands of years off) and threw on any `FIXED_LEN_BYTE_ARRAY` column it
  requested as `BINARY`, while scans of the whole file, which skipped the
  request, were fine. And with no request at all, a whole-file scan decoded
  columns by position, so a glob whose second file put two `INT32` columns in
  the other order swapped their values silently. Every file written by
  transformer's own writer hid both: they store exactly what the mapping
  says, in the first file's order. `ParquetColumns.resolve` now builds the
  request from the file's own column types, matched to the view's by name,
  and `parquet_external_format_test` pins each case against files parquet-mr
  wrote.
- **A self-consistent round-trip cannot catch a wrong reading of an external
  format.** The parquet reader never read a `TIMESTAMP`'s unit: every `INT64`
  timestamp went straight into a `TimestampVector`, which holds microseconds,
  so a `MILLIS` file (Spark writes one under
  `spark.sql.parquet.outputTimestampType=TIMESTAMP_MILLIS`) read 1000x too
  small, `2024-03-15` landing in January 1970, and a `NANOS` file (recent
  pyarrow) 1000x too large. `parquet_roundtrip_test` passed throughout, because `ParquetWriter`
  writes `MICROS`: a writer and reader that share an assumption agree with
  each other however wrong it is. So a reader of a format someone else
  writes needs fixtures someone else wrote; `read/parquet:external_parquet`
  writes them with parquet-mr itself. The unit now travels with the column
  (`ParquetColumns.Decoding.Timestamps`, decided per file, since one glob can
  mix units). `NANOS` truncates with `Math.floorDiv`, not `/`: the two differ
  before 1970, and only `floorDiv` agrees with how every other path into the
  engine drops sub-microsecond digits (`Instant.truncatedTo` for CAST and
  literals, `getNano / 1000` for CSV and `TimestampVector.setBoxed`), and
  with the `INT96` decoder.

  Plan 06 made the same rule a property in both directions. `fuzz/parquet_fuzz_test`
  writes every file with parquet-mr and, before consulting our reader, asks
  parquet-mr to read each one back: a generator's reading of the spec that our
  reader shared would agree with the reader and still disagree with parquet-mr's
  stringifier. `fuzz/csv_fuzz_test` writes CSV text itself, from fields it knows,
  rather than taking the text from `CsvWriter`.

  Decimals had no mapping at all. A parquet `DECIMAL` fell through to its
  physical type, so `123.45` stored as `INT32` read as the `INT` `12345`, and
  one stored as `FIXED_LEN_BYTE_ARRAY` or `BINARY` as raw two's-complement
  bytes; and `ParquetWriter` could not write a `DecimalType`, so no operator
  could spill a `DECIMAL` column (sort, aggregate key, DISTINCT, grace-hash
  join and window spill all threw). `ParquetColumns` now reads all four
  layouts as `DECIMAL(p,s)`, and `ParquetWriter` writes the spec's layout by
  precision. A decimal that spills reads back at its column's declared scale,
  like a GROUP BY key.
- **Every parquet annotation is decided; none falls through to its physical
  type.** The reader used to map a few annotations and read everything else
  as its physical type, so the annotation that says what the bits mean was
  dropped: an unsigned `INT32` or `INT64` read as signed, so its upper half
  came back negative; `JSON` text read as `BINARY`; a `TIME` as a bare `INT`
  or `BIGINT`; `FLOAT16` and `INTERVAL` as raw bytes; and a `REPEATED` column
  (a list per row) as one value per row, misaligning its rows.
  `ParquetColumns.decoding` now lists every pairing it reads and fails,
  naming the column, on anything else. An unsigned `INT32` widens to `BIGINT`
  and an unsigned `INT64` to `DECIMAL(20,0)`, Spark's mapping, since both hold
  every value exactly; `JSON` and `ENUM` read as `STRING`; `FLOAT16` widens to
  `FLOAT` (`Float.float16ToFloat`, exact). `BSON` and `UUID` stay `BINARY` by
  decision: a BSON document is binary, not text, so reading it as UTF-8 would
  mangle it. `TIME`, `INTERVAL` and `REPEATED` columns have no engine type and
  make the file unreadable, even to a query that does not name them, because
  the view's schema is the file's. Where a decode changes the stored value,
  the column's row-group statistics bound the stored bits rather than the
  engine's values, so `ParquetFilterTranslator` pushes no comparison on it
  (`Decoding.pushesComparisons` is false; see the next item for the other way
  it can be false). Without that rule the
  widened columns would push a `Long` bound against `INT32` statistics, and
  stay correct only because parquet-mr throws a `ClassCastException`
  comparing the two, which the iterator's stats check swallows.
- **A parquet file orders its statistics in its own way, which is not always
  the engine's.** parquet-mr judges a pushed comparison against a column's
  minimum and maximum in the column's order, and two orders differed from the
  engine's (both found by `fuzz/parquet_fuzz_test`, both wrong answers):
  - A string column is ordered by its UTF-8 bytes, which is code point order,
    and the engine orders strings by UTF-16 code unit (`String.compareTo`). They
    disagree only where a character from U+E000 to U+FFFF meets one past
    U+FFFF, which UTF-16 stores as a surrogate pair below U+E000: an emoji sorts
    below U+FFFD for the engine and above it in the file, so `s < '\uFFFD'`
    skipped the row group holding the emoji. A range now pushes only against a
    literal with every character below U+D800, which decides each comparison
    at a character on which both orders agree (`ParquetFilterTranslator.ordersAlike`);
    equality and IN push on any literal, since a group holding the literal has
    it between its bounds in either order.
  - An unsigned 8- or 16-bit integer reads as the `INT` it is, but its file
    orders it unsigned, so parquet-mr reads a negative bound as a huge one, and
    `u8 > '-1'` skipped every group (the binder reads the quoted `'-1'` as the
    INT -1; a bare `-1` is a negation, which is not pushed). Its decoding
    (`UnsignedNarrowInts`) pushes no comparison.
  Floats were probed too and are right: a group holding `NaN` is kept for
  `d > x`, as the engine's total order (`NaN` above everything) needs, and a
  group holding one signed zero is kept for a bound at the other.
- **Predicate pushdown into parquet is best-effort partial.** The translator
  in `ParquetFilterTranslator` handles `c <op> lit` and its swapped form,
  `c IS NULL` / `c IS NOT NULL`, `c IN (lit, lit, …)` / `c NOT IN (…)` over
  homogeneous literal lists, `c BETWEEN lo AND hi` (because LogicalBuilder
  already lowers it to `c >= lo AND c <= hi`), and `NOT`/`AND`/`OR` over
  those. `IN` with a NULL literal in the list bails out — SQL three-valued
  logic on NULL-in-list interacts with row-group skipping in a way the
  current translator can't safely approximate; let the residual FilterExec
  handle it. Computed expressions (`a - b > 0`), two-column predicates
  (`a > b`), `LIKE`, decimal columns, `IN` with non-literal items and a float
  comparison with a zero or `NaN` literal (a file orders `-0.0` below `0.0`,
  which the engine calls equal, and may leave `NaN` out of its statistics) are
  also dropped from the AND-chain, and so is any comparison on a timestamp
  column, whatever the literal (an `Instant` from `CURRENT_TIMESTAMP` or a
  count): a parquet TIMESTAMP's statistics are in the unit its file stores,
  which varies by file, and a legacy INT96 is no INT64 at all, so a skip
  decided in microseconds could over-prune. `ts IS NULL` still pushes, since
  null counts have no unit. For the same reason no comparison pushes on a
  column some file stores as other bits than the engine reads it as (an
  unsigned `INT32` read as `BIGINT`, a `FLOAT16` read as `FLOAT`):
  `ParquetReader` passes the translator the columns every file stores as
  their engine values. A date literal arrives as a `LocalDate`
  (`CURRENT_DATE`, `DATE '...'`, `{d '...'}`, or a string literal compared with a
  date, all read at bind time) and is pushed as INT32 epoch days, which every
  parquet DATE is. A string literal beside a number is read as that number at
  bind time too, so `i = '9'` pushes exactly as `i = 9` does. The `FilterExec`
  always stays above the scan to catch per-row precision — stats can prove a
  row group doesn't match but can't prove it does. Don't add a "predicate is
  fully pushed, skip the FilterExec" optimization without proving the
  translator handles every conjunct shape; missing one means rows that don't
  match the SQL filter sneak through.
- **The planner may swap join build sides relative to how the SQL is
  written.** `PhysicalPlanner` consults `LogicalPlanCardinality.estimate`
  and the join kind to pick which side ends up as the build side of the
  resulting `HashJoinExec`. INNER joins with a left side significantly
  smaller than the right (`leftEst × 2 ≤ rightEst`) swap so the smaller
  side is built; RIGHT outer joins always swap (matched-build tracking
  preserves right-side rows); LEFT outer and FULL outer never swap.
  `HashJoinExec.buildRight` carries the decision; output schema and
  column order are always `left ++ right` regardless of swap. Downstream
  operators don't see the swap, but EXPLAIN-style debug code that peeks
  at a join's `buildRight` flag (test fixtures, future planner tracing)
  will notice that the build side is no longer 1:1 with how the user
  wrote `FROM a JOIN b`. See
  [architecture.md §8](architecture.md#8-hash-join-build-side-selection).
- **Non-equi joins are refused over large known inputs.** When a join's
  ON clause has no equality conjunct, `HashJoinExec` runs as a
  degenerate hash (one bucket = whole build side) — correct nested-loop
  semantics but O(N*M). `PhysicalPlanner.enforceNestedLoopGuard` throws the
  `IllegalArgumentException` every refusal is (`A join without an equality
  between its two sides in ON is refused when both sides hold more than 5000
  rows (left=…, right=…); add one to the ON condition`) when both sides expose a
  `CatalogView.exactRowCount` and the smaller of the two exceeds 5000
  rows. It also refuses one over the output of another such join, directly
  or through joins on an equality, when the two would pair more than 25
  million rows, since a chain multiplies where each side alone looks small
  (a join's cardinality estimate is its larger side): three 300-row tables
  paired 27 million rows and ran a 1.2 GB heap out (plans/robustness/14). A
  join on an equality is sized by that estimate over its sides' bounds, so a
  pairing below it still counts (plans/testing/04). A single small side
  against a large one, a range join of buckets against a fact table, still
  runs. CSV-rooted joins
  (no exactRowCount) keep working because the guard refuses to refuse
  without evidence. If you hit this on a
  workload that genuinely needs the cartesian, either add a
  redundant-but-cheap equality conjunct (`ON 1 = 1` won't help — it has
  no side info) or restructure the predicate so the planner can extract
  one. There's deliberately no per-task escape hatch — the threshold is
  the contract. Below the bound a pairing costs time, not memory: it pairs
  a wave at a time (`HashJoinExec.executePairing`), holding the build side
  and one wave's pairs; it held every pair before its first batch, and 25
  million wide ones filled a heap of a few GB (plans/testing/05).
- **Hadoop's local file system runs `chmod` in a process of its own for each
  file it creates**, since Hadoop's native library is never loaded here
  (`RawLocalFileSystem.setPermission` falls back to a shell): 2.5 ms a file,
  nearly all of what writing a spill file or a small part file cost.
  `ParquetWriter` writes through `java.nio` (parquet-mr's `LocalOutputFile`)
  instead, which writes no `.crc` sidecar either. Reading through Hadoop's
  `RawLocalFileSystem` is still cheap: it sets no permissions.
- **parquet-mr's `ParquetFileReader.open(file)` builds a fresh Hadoop
  `Configuration`** for its read options (`ParquetReadOptions.builder()`),
  which parses Hadoop's default resources: 2.2 ms an open, and a parquet
  view opens each file twice (its footer, then each partition). Open through
  `ParquetReader.open`, which builds the options from the configuration the
  file was opened with; build those per reader, not once, since the codec
  factory they hold is not safe to share. A new `Configuration()` costs the
  same, so the reader and the writer each copy one built once.
- **Spill files use positional column names, never the logical schema's.**
  Every spill-capable breaker (`HashJoinExec`, `HashAggregateExec`, `SortExec`,
  `DistinctExec`, `WindowExec`) writes its working set to temp parquet and reads
  it back by column INDEX. Parquet addresses columns by name-path, so a spilled
  schema with two equal field names — a join output like `[k, v, k, v]`, or
  name-colliding GROUP BY keys (`GROUP BY t0.k, t1.k` → `[k, k, ...]`) — used to
  write a file the reader couldn't disambiguate: the second column's pages came
  back null and `ParquetPartitionIterator.loadNextGroup` NPE'd dereferencing a
  null `DataPage`. This crashed on the repro below (`spill_threshold_bytes = 1`)
  and was the bug the metamorphic fuzzer gated around:

  ```
  a(k,v) = {(1,1)},  b(k,v) = {(1,9)}
  SELECT t2.v FROM a t0 JOIN b t1 ON t0.k = t1.k JOIN b t2 ON t1.k = t2.k
  ```

  Fixed by writing spill files through `Spill.positionalSchema` (`_c0`, `_c1`,
  ...); `AggSpiller.spillSchema` renames the group-key columns to `_k0`, `_k1`,
  ... too. **If you add a spill write site, wrap its schema the same way** — the
  read paths are all index-only, so names carry nothing and duplicates break the
  round-trip. This is spill-only: real (non-spill) `ParquetWriter` output keeps
  its real column names. Regression coverage is a duplicate-named spill case in
  each operator's `*SpillTest`, the end-to-end repro in `SqlEngineTest`, and the
  metamorphic + sharded fuzzers, which now run the spill mode on join queries
  (`MetaModeDifferential` no longer skips `hasAnyJoin`).

  **A NULL-typed column has no parquet type either, and used to fail every
  spill — fixed.** An untyped `NULL` is NULL-typed, and so is an aggregate over
  one (`SUM(NULL)`, `MIN(NULL)`), and `ParquetSchema` has no mapping for the
  type, so a sort over `SUM(NULL)`, a `GROUP BY` or `DISTINCT` with a NULL key,
  and a grace-hash join or `UNION` carrying a NULL column all threw `Writing null
  to Parquet not supported in v1` under spill while the same query ran without
  it. Every spill schema now goes through `Spill.storedType`, which stores a
  NULL-typed column as a BOOLEAN with no values: the writer skips a NULL cell
  whatever the column's type, and every read path rebuilds rows cell by cell into
  the operator's own schema. `positionalSchema` applies it, so a new write site
  that wraps its schema as above is covered. The six-type fuzz corpora never
  wrote an untyped `NULL` (each typed NULL was a `CAST`), so the defect was
  unreachable to them; it is the only NULL a BINARY has, since nothing casts to
  one, and the only one the generators write for a DECIMAL (a query may write
  `CAST(NULL AS DECIMAL(p,s))` since plans/testing/10), and widening the mode-differential
  corpus to every type
  ([plan 04](../plans/robustness/04-widen-fuzz-corpus-to-every-type.md)) found it
  at seed 1796. Pinned by `SqlEngineTest.nullTypedColumnsSpillEndToEnd` (every
  spilling operator, with and without spill) and `SpillTest`.
- **`SELECT COUNT(*) FROM <view>` short-circuits to footer metadata** when
  the view's `CatalogView.exactRowCount` is defined (parquet + in-memory).
  The planner emits `CountStarMetadataExec` directly; no scan happens. The
  pattern is strict — any WHERE / GROUP BY / HAVING / extra agg sends it back
  through `HashAggregateExec`. Adding new readers? Implementing
  `exactRowCount` is "free" when you already know the row count, "expensive"
  if you'd have to count rows yourself — return None and the slow path runs.
- **Column projection push-down rewrites `ColRefExpr` indices.** Before
  `PhysicalPlanner.plan`, `ColumnProjectionPushdown` walks the logical tree
  to figure out which scan columns each ancestor actually references, asks
  the view for a pruned variant via `CatalogView.withProjectedColumns`,
  and remaps every column-ref index above the new scan. The remap is local —
  Project and Aggregate emit their own schemas, so above them positions are
  identity. Joins prune through too: parent-needed names get split by side,
  the join condition's own ColRef indices are remapped, and the parent
  receives a combined remap covering both halves. Self-joins (both sides
  sharing column names) keep both sides' refs pessimistically — name-based
  filtering can't distinguish `l.x` from `r.x`, so we keep them all; the
  result stays correct, just with one extra column per name-collision.
  Pruning is still skipped under unions and window operators (their output
  positions interact with sibling schemas / synthetic `_winN` columns).
  Parquet pushes the projection via `setRequestedSchema`; CSV (and any
  other view leaving the default `None`) is a no-op. The shaved decode
  cost is 5–20× on wide schemas with one big unused column — the snapshots
  dataset's `data` blob is the canonical case. When the consumer references
  *zero* columns (e.g. `COUNT(1) FROM t`, `SELECT 1 FROM t LIMIT n`), the
  pushdown still projects to a single column picked by `narrowestColumn` —
  fixed-size primitives win over strings/binaries. The scan has to drive
  batches forward to feed row counts; decoding one Long instead of a
  multi-MB JSON blob makes `COUNT(1)` 20–25× faster on snapshots-shaped
  data. `ParquetReader.withProjectedColumns` reuses the parent's
  footer-derived partition layout so the pruned variant doesn't re-open
  every file. The pass guards itself with a plan-time `verify` that
  asserts every rewritten `ColRefExpr` lines up with its child's schema
  by index range and `dataType` — index bugs surface as targeted
  `IllegalStateException`s at plan time instead of `ArrayIndexOutOfBoundsException`s
  deep inside the executor.
- **Filter push-down sinks WHERE conjuncts through joins.** Before
  projection pruning, `FilterPushdown` walks the logical tree, splits any
  `LogicalFilter(LogicalJoin(...), pred)` by `JoinSideAnalysis.sideOf`,
  and pushes each conjunct under the matching child where the join kind
  allows. Inner joins push both sides; LEFT outer pushes left-only
  conjuncts (right-side ones must NOT be pushed — they'd kill the
  null-extended rows that LEFT JOIN preserves when the right side has
  no match); RIGHT outer is symmetric; FULL outer pushes nothing.
  Conjuncts touching both sides join an inner join's condition (so a comma
  join's WHERE equality is its hash key) and stay above an outer join.
  Stacked Filter(Filter(...))
  is flattened before the split, and a conjunct that lands above a deeper
  join cascades further on the recursive call. The win compounds with
  parquet predicate pushdown — a filter that sinks all the way to a
  parquet scan often triggers row-group skipping in
  `ParquetReader.withPushdownFilter`. **Do not extend the pass to push
  filters on the null-extended side of an outer join.** A `LEFT JOIN`
  with `WHERE r.x IS NULL` is a classic anti-join shape and depends on
  the post-join filter seeing null-extended rows; pushing the IS NULL
  into the right child destroys the semantics. The pass is conservative
  by construction — if you find yourself trying to push on the outer
  null-extended side, stop.
- **Don't do I/O on the FX thread during a run.** Every parallel call in this
  library funnels through `Scheduler.pool` (a `2 × cores`-sized `ForkJoinPool`
  by default; configurable via `transformer.scheduler.parallelism`). When
  a job is running, those workers are tied up scanning + writing partitions.
  An FX-thread caller that submits to the pool via
  `Scheduler.submitAndAwaitAll` (e.g. `ParquetReader.fromPath` reading footers
  for a schema chip) will block on `.get()` waiting for a worker — and since
  the FX thread is NOT a `ForkJoinWorkerThread`, FJP compensation doesn't
  apply. The whole GUI freezes for as long as the pool is busy. The pattern
  this came from was `SqlConsolePanel.refreshViewsListing` rebuilding the
  catalog (which opens every input + output to read schemas) on every
  `notifyListeners` fire — 60+ rebuilds per run, each blocking FX. Fix: guard
  with `if (session.isRunning) return` so listener-driven catalog rebuilds
  wait until `endRun` fires its single post-run `notifyListeners`. Same rule
  applies to any future panel that wants to do I/O off a session listener —
  either gate on `isRunning` or spawn a background thread and marshal results
  back via `FxHelpers.onFx`.

- **Sharded execution at K>1 used to hard-deadlock the shared pool — FIXED
  for engine execution by bottom-up exchange pre-materialization
  (plans/bugfixes/02f); sharding stays off by default; never `managedBlock`
  these waits; never drain lazily-nested exchanges from pool tasks.** (Hazard
  surfaced by the metamorphic fuzzer; investigated in plans/bugfixes/02a,
  which refuted it at small fuzz budgets; a compensation "fix" attempted in
  02b was reverted; the exchange monitor was removed in 02d — whose pre-change
  baseline campaign then REPRODUCED the hang, see plans/bugfixes/02e; 02f then
  removed the wait the cycle needs.) Sharding is off by default
  (`MinShardableSize = Long.MaxValue`, `BroadcastBuildThreshold = 1M`), so nothing
  users run reaches this. With sharding forced on, every breaker (`ExchangeExec`,
  `HashJoinExec`, `DistinctExec`, `HashAggregateExec`) materialises by blocking on
  the shared bounded `Scheduler.pool` while awaiting per-partition sub-tasks, and
  sharded plans nest breakers deeply (exchange over join over exchange over
  distinct ...). The historical "14 workers parked on two exchange monitors, 0%
  CPU" reading was first refuted on JDK 21 at default/1500-seed fuzz budgets,
  then confirmed REAL at 20000-seed campaign volume (2026-07-23: permanent
  wedge ~3 minutes in, 0% CPU, identical wait set across thread dumps 90s
  apart). Three mechanisms:
  - **Monitor across a pool-blocking call — the throughput cliff, FIXED
    (plans/bugfixes/02d).** `ExchangeExec.ensureMaterialized` used to be
    `synchronized`, holding the instance monitor across its pool-driven
    `materialize()`: one consumer held the monitor + blocked inside
    `materialize()` while the rest blocked *entering* the monitor (invisible to
    the pool) — serialising concurrent shard-readers of one exchange, and a
    standing violation of the "no JVM monitor across a `Scheduler.pool`-blocking
    call" convention. It is now a CAS claim + published `CountDownLatch`: exactly
    one winner runs `materialize()` with no monitor held, losers park on a plain
    **uncompensated** `ready.await()` and wake together on `countDown`, and a
    materialization failure latches and is thrown to every caller as itself
    (`Scheduler.rethrowable`), never retried. The loser-wait is deliberately NOT
    `managedBlock` — that was 02b's regression.
  - **Nested blocking on a bounded pool — rescued by work-helping, for
    TREES.** `Scheduler.submitAndAwaitAll` waits via `ForkJoinTask.get()`. From a
    pool worker `.get()` does not merely park — it work-*helps*, stealing and
    running the very sub-tasks it awaits, descending the breaker tree depth-first
    on one worker's stack, so a single worker alone materialises an arbitrarily
    deep *tree* of exchanges. Workers that block on I/O (e.g. parquet spill)
    rather than on a pool task ARE compensated by the FJP. The external top-level
    drain thread parks without consuming a pool worker.
  - **`helpJoin` breaks stack discipline — the real deadlock (FIXED by 02f:
    engine plans no longer contain the wait the cycle needs).** Helping
    is not restricted to descendants of the awaited task: `ForkJoinTask.get()`'s
    helpJoin path runs tasks from stealers' queues wholesale. In the captured
    wedge, an exchange's materializing winner awaited one of its shard tasks on
    a worker whose own nested `.get()` (a join build fan-out inside the shard
    task) had inlined a *consumer* of that same exchange — a sibling probe task
    of the join above it — beneath the shard-task frame; the guest then waited
    for the exchange. Cycle: winner → shard task → inlined consumer → exchange →
    winner. The cycle is monitor-agnostic: under the old `synchronized` DCL the
    guest blocks entering the monitor, under 02d's CAS+latch it parks on the
    `ready` latch — either way the winner can never finish. This is why 02a's
    "acyclic plan topology ⇒ no cycle" argument fails: helping can interleave
    two plan paths on one stack, so lock/latch acquisition does not follow the
    plan topology. (Descendant helping participates too: a winner help-running
    its own shard task can walk into a *nested* exchange and park there as a
    loser — the post-02d probe dump shows both edge types feeding one wedge.)
    The same inlining can also land the consumer on the
    winner's OWN stack — there the old reentrant monitor silently
    double-materialized (live), and a naive CAS+latch self-deadlocks against
    its own latch (this bit 02d's first cut: the default-seed fuzzer, green
    for months, timed out). `ensureMaterialized` therefore detects
    winner-reentrancy via a `claimer` thread check and materializes a private
    unpublished copy (duplicate work, live). The cross-thread variant admits
    no such point escape (a cross-exchange pair of hosts evades any
    "am I above a producer of THIS exchange" check), which is why the fix
    removes the wait instead: every deadlock cycle must pass through an
    exchange-readiness wait — the one wait that does not follow the
    task-creation tree — and `PhysicalPlanner.preMaterializeExchanges`
    publishes every exchange bottom-up at the drain choke points
    (`SqlEngine`'s two execute paths, `CteResolver.materialize`) before any
    consumer task exists, so no worker ever performs that wait
    (plans/bugfixes/02f).

  Investigation (plan 02a; JDK 21, 8-core): the sharded fuzzer runs GREEN at
  multi-shard K for small budgets — the `shard_count=4` pin, default K
  (= `Scheduler.parallelism` = 16), and the worst realistic regime
  (`parallelism=2`, `shard_count=8`, 1500 seeds of joins / self-joins /
  CTEs-referenced-twice / windows). Direct probes — a 6-deep sharded plan
  drained by K concurrent *external* threads, and a diamond where two breakers
  share one `ExchangeExec` instance — also complete, and the same nested
  fan-out with a NON-helping wait (`CountDownLatch.await()` in place of
  `.get()`) deadlocks a 2-thread pool immediately, so `.get()` work-helping is
  load-bearing. Those measurements stand, but the conclusion drawn from them
  ("no hard deadlock for the shapes we generate") was a sampling artifact:
  the 02d pre-change baseline campaign (`sharded_mode_fuzz_campaign`,
  `FUZZ_SEEDS=20000`, K=4, default pool) wedged permanently ~3 minutes in —
  the helpJoin cycle above needs campaign-scale volume to hit the right steal
  interleaving. Full dump analysis and repro: plans/bugfixes/02e.

  **A compensation "fix" (02b) was attempted and reverted.** 02b tried to (a) wrap
  every pool wait in a `ForkJoinPool.ManagedBlocker` and (b) replace the exchange
  monitor with a CAS-claim + `CountDownLatch`, on the theory that managed blocking
  is safer insurance than the work-helping implementation detail. In practice it
  REGRESSED K>1 from green (4.3s) into a hard hang: under deep K-shard nesting the
  `managedBlock` compensation spawned a spare-thread storm that wedged the pool (a
  timeout thread dump showed every worker idle in `awaitWork` while the external
  submitter parked on an un-run task). Bisection (plans/bugfixes/02c): reverting
  only the managed-blocking waits — back to plain `.get()` / `latch.await()` —
  restores green, isolating `managedBlock` as the sole culprit. 02b was reverted in
  full to reach a known-good baseline; the monitor-free exchange half (B) was later
  re-landed alone as plans/bugfixes/02d, with the loser-wait a plain uncompensated
  `ready.await()`. Lesson: for THIS workload work-helping is not merely adequate,
  it is *better* than compensation — `managedBlock` turns a bounded nested fan-out
  into unbounded thread creation.

  Net: 02d's CAS+latch removed the monitor-serialisation cliff (losers wake
  together on `countDown`; no JVM monitor is held across a pool wait anywhere
  in the tree); 02f removed the deadlock itself for engine execution by making
  the exchange-readiness wait unreachable — the pre-materialization pass runs
  at both drain choke points, and plans with zero exchanges (the shipping
  default) walk and find nothing. The 20000-seed K=4 campaign that wedged
  HEAD in ~1-3 minutes completes under 02f. Guards:
  `//src/test/scala/com/transformer/core:scheduler_test` pins the tree-shaped
  work-helping mechanism (the same depth-5/fan-4 fan-out completes on a
  2-thread pool via `submitAndAwaitAll`'s `.get()` and wedges through a
  non-helping `CountDownLatch`); `exchange_exec_test` pins exactly-once
  materialization + failure propagation under racing readers plus the pass
  itself (diamond-shared exchange publishes once; `MeteredPlan` wrappers are
  transparent); `sql/exec:exchange_deadlock_stress_test` re-drives the exact
  captured wedge topology through the pass under heavy steal churn (green
  ~2s in the default suite; `STRESS_LAZY=1` skips the pass and reproduces the
  historical wedge in seconds as a manual red instrument). RESIDUAL: the lazy
  `ensureMaterialized` fallback still exists for directly-constructed
  exchanges, and draining lazily-NESTED exchanges from pool tasks without the
  pass can still wedge — don't do that; run
  `PhysicalPlanner.preMaterializeExchanges` first like the engine does. The
  general rules stand: never hold a JVM monitor across a
  `Scheduler.pool`-blocking call (`ExchangeExec.ensureMaterialized` is the
  in-tree exemplar of the compliant pattern), and do not reach for
  `ForkJoinPool.managedBlock` as a blanket wrapper on pool-task waits (02b's
  mistake — it wedges the pool even faster).
- **Per-thread allocation accounting is HotSpot-specific.** The
  instrumentation framework reads
  `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` to surface
  `allocBytes` in `_perf.json`. That class is an Oracle / OpenJDK
  extension to the standard `java.lang.management.ThreadMXBean`. On
  Oracle / OpenJDK / Temurin (i.e. every JVM we test against) it works;
  on a hypothetical non-HotSpot JVM the `isInstanceOf` guard in
  `MetricsCollector.currentAllocatedBytes` falls back to `-1` so
  `allocBytes: -1` in `_perf.json` is the documented "unsupported" sentinel
  rather than a measurement bug. Other fields in `_perf.json` remain
  accurate. Same story for `cpuNanos`: we enable
  `setThreadCpuTimeEnabled(true)` once at class load; if the JVM refuses,
  the field is `-1`.
- **Metrics opt-in is per-task by default, with sysprop / env-var
  override.** `options.metrics = "true"` on a task's `output.json` wins
  over `transformer.metrics.enabled` / `TRANSFORMER_METRICS_ENABLED`.
  An explicit `options.metrics = "false"` overrides the global default;
  unset falls through to the global default. The override pattern
  matches what `options.spill` does — same parser, same semantics. The
  same precedence rule applies to the macro bench's
  `transformer.macro.force_spill` sysprop, which is a global override
  that flips `spillEnabled = true` on every task regardless of the
  per-task config. See [docs/benchmarking.md](benchmarking.md) for the
  full enablement matrix.
- **`eval` and `evalVec` used to disagree on `NaN` / `-0.0` — fixed, and the
  two operator families deliberately follow different rules.** Numeric
  *ordering* (`< <= > >=`) and `IN`-list membership each took different code in
  the two paths and diverged, in opposite directions, on exactly the operands a
  float/double column can carry but the finite generators never produced
  (`0.0 / 0.0`, `-1.0 * 0.0`):

  - **Ordering.** The row path (`Ops.cmp`) uses `java.lang.Double.compare` — a
    total order, `NaN` above everything and equal to itself, `-0.0 < 0.0` —
    while `VecOps.numericCompare` compared raw IEEE (`NaN` false everywhere,
    `-0.0 == 0.0`). The vector path now goes through `Double.compare` too. The
    direction was forced: `Ops.cmp` also backs the sort comparators, and IEEE
    comparison is not a total order, so it cannot satisfy the `Comparator`
    contract. `Double.compare` takes primitive `double`s, so this costs no
    boxing. `Float` has no branch of its own — `VecOps.readDouble` widens it
    exactly as `Number.doubleValue` does on the row side.
  - **`IN`-list membership.** The row path calls `Ops.eq` (primitive `==`:
    `NaN` matches nothing, `-0.0 == 0.0`), while `InListExpr.evalVec`'s
    literal-hoisting fast path boxed into a `HashSet[java.lang.Double]` and so
    inherited `Double.equals` — bit-pattern equality, the opposite on *both*
    counts. The set now keys through `InListExpr.setKey`, which collapses
    `-0.0` to `0.0`, and a `NaN` probe skips the set entirely. Skipping (rather
    than answering FALSE) is what keeps three-valued logic intact: a `NaN` that
    matches nothing still falls into the NULL-item branch, so
    `NaN IN (1.0, NULL)` is NULL, not FALSE.

  That fix left equality and ordering answering differently on the same
  operands by design (`NaN = NaN` FALSE while `NaN <= NaN` TRUE), and the keys a
  third way. Since plans/robustness/21 all three follow `Ops.floatCompare`, as
  Postgres and Spark do: `NaN` equals `NaN` and `-0.0` equals `0.0` (see
  "Floats used to have three equalities" below).

  `ExprParityFuzzTest` used to hold out three things at once: float/double
  ordering, float/double `IN`, *and* every `NaN`/`Inf`/`-0.0` value (normalized
  to `0.0` in `ExprGen.genValue`). All three are gone, and
  `generatorReachesIeeeSpecialValues` asserts both the shapes and the values are
  reached — removing the shape gates alone would have left the fuzzer green over
  a corpus that could not break them.

- **`MIN`/`MAX` over a `NaN`-carrying float column used to be order-dependent —
  fixed.** `MinMaxState` kept a primitive `doubleCur` accumulator for
  `Float`/`Double` and advanced it with raw IEEE `v < doubleCur` /
  `v > doubleCur`, in `updateAt`, `updateBatchAt` *and* `merge`
  (`AggregateExec.scala`). Every `NaN` comparison is false, so the result
  depended on where the `NaN` landed: seen **first** it became the accumulator
  and stuck forever (nothing could beat it), seen **later** it was skipped
  entirely. `MAX(x)` over `[NaN, 5.0]` was `NaN`; over `[5.0, NaN]` it was
  `5.0`. Because `merge` had the same shape, the answer also moved with
  partition fan-out and spill flush order, not just row order within a batch.

  All three sites now advance through `java.lang.Double.compare`, the total
  order the class's own reference-type branch already used (`Ops.cmp`), and the
  one `ORDER BY` (`SortExec.rowOrdering`) and `GREATEST`/`LEAST`
  (`Funcs.pickByOrdering`) follow. So **`MAX` of a column containing `NaN` is
  `NaN` and `MIN` of one is the smallest non-`NaN`**, agreeing with
  `ORDER BY x DESC LIMIT 1` and with Postgres; `-0.0` sorts below `0.0` on the
  same order, so `MIN` over signed zeros is `-0.0` rather than whichever arrived
  first. `compare` takes primitive doubles, so the fix costs no boxing in loops
  the primitive slots exist to keep allocation-free — `Ops.cmp(a: Any, b: Any)`
  would have. This is a user-visible semantic change to an aggregate, not just a
  determinism fix.

  It was **not** an `eval`/`evalVec` parity bug — both paths use the same
  accumulator and agreed with each other — which is why none of the
  `ExprBatchTest` / `ExprParityFuzzTest` gates reached it. It is a
  layout-dependence bug of exactly the kind the mode-differential and
  metamorphic fuzzers exist to catch, and they missed it only because the
  corpora were all-finite. Fixing the comparison was therefore half the work:
  `DataGen` (the mode-differential corpus) and `MetaQueryGen.payloadDomain` (the
  metamorphic and sharded corpora, which build their relations from their own
  pool and do **not** call `DataGen.generate`) now draw `NaN`, `-0.0` and
  `±Infinity` into a quarter of float columns through the shared
  `DataGen.withSpecialDoubles`, and two coverage guards
  (`generatorReachesMinMaxOverIeeeSpecialValues`,
  `aggDecompositionReachesIeeeSpecialValues`) assert the values really reach a
  `MIN`/`MAX`. Deterministic regressions in `hash_aggregate_spill_test` pin row
  order, batch shape, partition split and spill flush order. One residue worth
  knowing: `RowOracle`'s tolerant comparator treats `+0.0` and `-0.0` as equal
  (it must — reordering a `SUM` over signed zeros legitimately flips the sign of
  a zero), so a signed-zero `MIN`/`MAX` divergence is invisible to the
  layout-differential properties and is pinned by the deterministic regression
  instead.

- **`RANK`/`DENSE_RANK` used to rank tied rows separately when the ORDER BY key
  was `NaN` — fixed.** `WindowExec.sameKey` decides whether two rows are ORDER BY
  peers and compared them with `Ops.eq`, but the rows were sorted with `Ops.cmp`
  (`sortPartition`). The two disagree on exactly the values the rest of the
  engine reconciles: `Ops.eq(NaN, NaN)` is FALSE where the sort ties them, so
  tied rows got different ranks; `Ops.eq(-0.0, 0.0)` is TRUE where the sort
  separates them, so strictly-ordered rows shared a rank. The `NaN` direction was
  a plainly wrong answer — `DENSE_RANK()` over a partition whose rows all tie
  must be `1` for every row, and it was not; the rank then moved with the order
  rows reached the window in, which is how a join-commutativity flip surfaced it.

  `sameKey` now compares with `Ops.cmp(av, bv) == 0`, so the peer test IS the
  tie predicate of the sort that produced the order (the comparator's ASC/DESC
  flip negates the sign, which leaves `== 0` alone). Two nulls stay peers,
  matching the comparator's `(null, null) => 0`. The `-0.0` half of that fix
  (`-0.0` and `0.0` ranked 1 and 2, as the sort then ordered them) was reversed
  by plans/robustness/21: the sort now ties them, so they share a rank again,
  as they share a GROUP BY group and `=`.
  Only `RANK` and `DENSE_RANK` call `sameKey`; `ROW_NUMBER`, `LAG`/`LEAD` and
  every frame bound are positional, and `PARTITION BY` goes through the key
  codecs (see [architecture.md](architecture.md) §2a) — so one operator
  deliberately applies two different float rules, identity for partitioning and
  the total order for peers.

  Third instance of one family, after the `evalVec` ordering comparisons and
  `MinMaxState`'s accumulator: an ordering site that reached for raw IEEE
  instead of the engine's one total order. Found by `metamorphic_fuzz_campaign`
  and `sharded_mode_fuzz_campaign`, both red at seed 16502 immediately after the
  `MIN`/`MAX` fix above widened the corpora to carry `NaN` — the bug was older
  than that widening, which only made it reachable. Pinned by three regressions
  in `window_exec_spill_test` and by
  `MetamorphicFuzzTest.rankWindowsReachIeeeSpecialOrderKeys`, which measures the
  thinnest chain in the corpus: a rank window lands on ~2% of seeds, a float
  ORDER BY key on ~0.3%, and one carrying a `NaN` on ~0.04%. See
  [plan 06](../plans/followups/06-window-rank-peer-total-order.md).

- **Byte arrays, decimals and BIGINTs past 2^53 used to compare wrongly, and
  hashing disagreed with `=` — fixed.** `Ops.eq` / `Ops.cmp` dispatched on the
  boxed value's class. A byte array is neither a `Number` nor `Comparable`, so
  `=` was reference equality (two equal-content arrays were unequal) and
  `ORDER BY` compared `toString` of the identity hash, an order that changed
  run to run. A `BigDecimal` and a `Long` are both `Number`s, so both went
  through `doubleValue` and lost every digit past double's 53-bit mantissa:
  `10000000000000000001 = 10000000000000000002` and `9007199254740993 =
  9007199254740992` were TRUE, `<` between them FALSE, and `MIN`/`MAX` over
  decimals that collide as doubles kept whichever arrived first, so the answer
  moved with row order, partition fan-out and spill flush order (the bug-06
  shape). The hash side disagreed with the comparison side in both directions:
  `HashKeys` compared byte arrays by content but decimals with
  `BigDecimal.equals`, which includes the scale, so `GROUP BY` collapsed rows
  `=` called different and split `1.0` from `1.00`, which `=` called equal.
  An equi-join written as a join key and the same join written as a residual
  predicate gave different answers, for all three types. Behind the codec were
  three more sites with the same defect: `CountDistinctState` and
  `DistinctState` kept a raw `HashSet` (identity for arrays, scale for
  decimals), `HashPartitioner.hashCell` routed `1.0` and `1.00` to different
  shards, and `HashJoinExec.columnHashCode`, which routes grace-hash join and
  window spill buckets, hashed a byte array by identity, so two equal keys
  landed in different buckets and never met.

  Now every comparison is by value. `Ops` compares two integers as longs, a
  decimal against any number with `compareTo` (exact, scale-blind) and byte
  arrays with `Arrays.equals` / `Arrays.compareUnsigned`; pairs with a `FLOAT`
  or `DOUBLE` in them are untouched, NaN rules included. The vectorized twin
  moves with it: `VecOps.compare` sends two integers to `integralCompare`
  (`readLong`) and any pair with a decimal to the generic path, and
  `InListExpr`'s hoisted literal set keys integers on `longValue` and leaves a
  decimal to the `Ops.eq` scan (its old `Double` set held `10000000000000000002`
  for `10000000000000000001`). Every hash agrees: the codecs store a decimal as
  `KeyCodec.decimalKey` (the value at its column's declared scale when it can be
  written there exactly, else without trailing zeros, so every spelling of a
  value is one key), the DISTINCT sets hold `DistinctElement`s (a byte array in
  a `BytesKey`), and both routing hashes hash a byte array's content and a
  decimal's nearest double (`HashPartitioner.decimalHash`, which is
  scale-free, needs no declared scale, and costs only balance on decimals a
  double cannot tell apart). A decimal key reads back at the declared scale,
  and `MinMaxState.finish` spells a decimal extremum the same way, so neither
  depends on which spelling arrived first; a plain projection still shows the
  stored spelling, since the column's declared scale is not enforced on what
  it holds. The fourth instance of one family (after the three float bugs
  above), widened from "an ordering site" to "an equality, hash or dedup site
  that does not agree with `=`"; see [conventions.md](conventions.md). Found
  by a hand audit of the four `DataType`s the fuzz corpora never draw
  ([plan 02](../plans/robustness/02-total-order-for-decimal-and-binary.md));
  BIGINT turned up while checking the codec's equality claim against `Ops.eq`.
  Deterministic regressions in `sql_engine_test`, `hash_aggregate_spill_test`
  (row order, partition split, spill), `agg_state_serde_test`,
  `exchange_exec_test`, `hash_join_spill_test`, `window_exec_spill_test`,
  `hash_keys_test` and `expr_batch_test`, and every fuzz corpus now draws
  decimal and binary columns, as join keys too
  ([plan 04](../plans/robustness/04-widen-fuzz-corpus-to-every-type.md)), with a
  guard per type asserting the pairs above (equal content, one value at two
  scales, two values of one double) reach every site.

- **`ABS` of a `DECIMAL` used to compute in `DOUBLE` — fixed.** `Funcs.apply`
  took a decimal's `doubleValue` like any other number's, and `VecFuncs.abs`
  wrote the result into a `DoubleVector` whatever the declared type, so
  `ABS(k)` over a `DECIMAL(38,18)` lost every digit past double's precision
  (`1.000000000000000001` came back `1.0`) and read back as a `Double` in a
  `DECIMAL` column. The vector's class was the worse half: `MIN` / `MAX` picks
  its accumulator slot from the declared type and the vector picks which slot
  it fills, so `MAX(ABS(k))` filled the double slot, finished from the empty
  decimal one, and answered NULL. `ABS` of a decimal is now `BigDecimal.abs` on
  both paths, at the value's own scale. It was `SUM`'s defect (a function that
  keeps its input's type while computing in another) in a function small
  enough to fix first; `SUM` is fixed too, in the next entry. Found when
  [plan 04](../plans/robustness/04-widen-fuzz-corpus-to-every-type.md) widened
  the expression corpus to every type: `expr_parity_fuzz_test` reached it at
  seed 98, as an `evalVec` vector of the wrong type. Pinned by
  `ExprBatchTest.funcAbsDecimal` and `SqlEngineTest.absOfADecimalIsExact`.

- **`DECIMAL` arithmetic used to throw, and `SUM` / `AVG` over one to compute
  in `DOUBLE` — fixed.** `+ - * / %` and unary minus on a `DECIMAL` threw
  `IllegalStateException: Arithmetic on decimal(5,2) not supported` from
  `Ops.arith` / `VecOps.arith` / `UnaryOpExpr` while the query ran, an internal
  exception class for a missing feature. `SumState.init` picked
  `DoubleSumState` for anything but an integer, so `SUM` over two
  `10000000000000000001`s came back `20000000000000000000`, and since
  `AggExprSum.resultType` kept its input's `DECIMAL(p,s)` the sum also held
  digits that type does not (`0.30000000000000004`, or `19.98` in a
  `DECIMAL(3,2)`), which `ParquetWriter` refuses rather than rounds, so writing
  or spilling a decimal `SUM` failed. `AVG` was a `DOUBLE`. Now each result has
  a declared type that holds it (`Decimals`, Spark's and SQL Server's rules,
  see [architecture §5](architecture.md#5-expression-evaluation)), the value is
  computed exactly and fitted to that type (rounded half up; an integer part
  too long for it is an `ArithmeticException` naming the value, never a NULL or
  a wrapped number), `SUM` of a `DECIMAL(p,s)` is a `DECIMAL(38,s)` and `AVG` a
  `DECIMAL(p+4,s+4)`. Both aggregates add `BigDecimal`s, so the answer no
  longer moves with row order, partition count or spill, which is what let the
  fuzz corpora draw decimals a `DOUBLE` cannot add. Two choices worth knowing:
  a `FLOAT` or `DOUBLE` column beside a `DECIMAL` in arithmetic is a bind error
  (CAST the `DECIMAL` to `DOUBLE`), where comparisons widen it; and division
  or remainder by zero is NULL, as for integers. `AVG` keeps its four extra
  digits by giving way on scale past 38 digits rather than overflowing, where
  Spark's `DECIMAL(min(38,p+4), min(38,s+4))` would overflow an average of
  `DECIMAL(38,18)` values with 17 or more integer digits. Pinned by
  `ExprBatchTest.binOpArithDecimal*` and `unaryMinusDecimal`,
  `AggStateSerdeTest.decimalSumAndAvgRoundtrip`, and `SqlEngineTest`'s
  `decimalSumAndAvgAreExact`, `decimalArithmeticIsExactAndTyped`,
  `decimalOverflowIsAnError` and `decimalResultsSpill`.

- **Arithmetic on NULL alone used to throw — fixed.** `NULL + NULL` binds with
  the NULL type, and `VecOps.arith` and `VecOps.negate`, which a projection, a
  WHERE and an aggregate's argument run on, had no case for it: `Arithmetic on
  null not supported` and `Unary - on null`, internal `IllegalStateException`s,
  where the row path answered NULL. Both now return an all-NULL vector. An
  untyped NULL beside a `DECIMAL` takes its type, so decimal arithmetic in a
  query reaches `NULL * NULL` as soon as both sides are an untyped NULL. Pinned by `ExprBatchTest.arithOnNullAloneIsNull` and
  `SqlEngineTest.arithmeticOnNullAloneIsNull`.

- **Mode-differential comparison is a multiset with a floating-point tolerance.**
  The `mode_differential_fuzz_test` (and the metamorphic `MetaModeDifferential`)
  assert one `(data, query)` gives the same result across spill on/off, metrics
  on/off, and partition/batch layouts. Two comparison gotchas, both in
  `RowOracle.multisetEquals`:
  - **Multiset, not positional.** The K-way merge in `SortExec` is not stable
    (`sort_exec_test`) and the collapsing aggregate emits groups in a
    layout-dependent order, so equality is over multisets, never row positions.
    For an `ORDER BY` query the oracle additionally checks each run is *sorted*
    (the multiset check alone would not catch an unsorted result).
  - **Floating-point (`Double` and `Float`) columns use a `NaN`-aware tolerant
    comparator; every other column compares exactly.** The order-dependent
    aggregates (`SUM`/`AVG`/`STDDEV`/`VAR`/`COVAR`/`CORR`) accumulate in `double`,
    so a different reduction order (a different layout or spill flush order)
    yields a `Double` result that differs by rounding — those columns compare
    within a small relative+absolute tolerance. `Float` columns do NOT reorder (a
    `Float` reduction stays bit-identical across modes), but a *computed* `Float`
    projection can produce `NaN` (a float divide-by-zero), and a boxed `NaN`
    cannot be an exact grouping key — Scala's `==` on a boxed `NaN` is `false`, so
    two identical `NaN` rows would fail to match themselves. So `Float` is routed
    through the same comparator purely for `NaN`-awareness; the tolerance itself
    is never exercised for it (a bit-identical `Float` still compares equal).
    Treating `Float` as an exact key was a latent harness bug the metamorphic
    fuzzer surfaced; `Float` now shares the `Double` path. Every
    non-floating-point column (integer `SUM` in `Long`, `COUNT`, `MIN`/`MAX`,
    projected/group-key values) is bit-identical and compares exactly, so the
    tolerance can never mask a wrong group or a dropped row. A DISTINCT aggregate
    (`COUNT`, `SUM` or `AVG(DISTINCT …)`) silently disables spill (it is not
    spillable), so the spill-on run takes the in-memory path — parity still holds,
    and the fuzzer does not special-case it.
  - **"Exactly" is the engine's `=`, not the JVM's.** A byte array is compared
    and hashed by content: Scala's `==` on two arrays is reference equality and
    their `hashCode` is their identity, so every pair of BINARY cells would read
    as different. A decimal is compared by value whatever its scale, the
    `compareTo` that `Ops.eq` uses, because the engine does not keep a value's
    spelling: a spilled decimal reads back at its column's declared scale while
    the in-memory run keeps `1.5`, and a `LIMIT` cutoff between tied `1.0` and
    `1.00` keeps either. A DATE and a TIMESTAMP compare as the `LocalDate` and
    `Instant` both paths box, with no conversion from an epoch count, because
    `Ops.eq` calls an epoch-day `Int` unequal to the date it counts. All three are
    pinned by `fuzz/row_oracle_test`. A decimal `SUM`, `AVG` or arithmetic
    result compares exactly too: each is computed exactly and fitted to its
    declared type, so none moves with the layout, as a `DOUBLE` sum of the same
    values would, and the comparison needs no tolerance. The SQL corpora's
    decimal pools (`DataGen.DecimalPools`) hold values a `DOUBLE` adds wrong
    (`0.1` and `0.2`), so an answer computed through `doubleValue` would show.
    Decimal arithmetic reaches the values of `COALESCE`, `IF` and `CASE` too,
    beside a value of another `DECIMAL` type, the shape that took its first
    value's type and kept a wider decimal there unrounded until
    [plans/robustness/09](../plans/robustness/09-branch-result-types.md),
    which a spill could not write. Drawn independently, the two meet on a few
    seeds in a thousand and mostly answer with the first, so the generators
    draw it on purpose (`genDecimalBranches`): a product or a quotient beside
    the narrowest decimal column, half the time a column by a number whose
    digits do not cancel under a condition that takes it wherever the column
    has a value. A `COALESCE(c, c * x)` alone would never answer with the
    product: it reaches it only where `c` is NULL. Run against the plan's base
    commit, the mode-differential property fails within its first 1300 seeds.

- **The metamorphic TLP oracle partitions the query OUTPUT, never decomposes an
  aggregate.** `Tlp` checks `Q ≡ (Q WHERE p) ⊎ (Q WHERE NOT p) ⊎ (Q WHERE p IS
  NULL)`. Pushing that partition into the *input* of an aggregate (the
  "aggregate-TLP" form — split the input three ways, aggregate each, recombine)
  is UNSOUND for non-distributive aggregates (`COUNT(DISTINCT)`, `AVG`, `STDDEV`,
  and others do not recombine by union). So the oracle wraps the whole query and
  partitions its OUTPUT — `WITH q AS (<body>) SELECT * FROM q WHERE <partition>`
  (`MetaQuery.tlpBase`) — which is sound for every shape (aggregate, window,
  union, join) because it splits a finished result multiset with no aggregate
  algebra. Input-level and aggregate-decomposition TLP are deliberately NOT used.
  The same wrap could be a derived-table subquery in FROM, but the binder rejects
  those (see "No subqueries" below), so the CTE form is the only route. The
  partition column is also restricted to type-reliable non-float output columns:
  a computed `Int`-labelled column can reparse to `Double` (`-1.0 % c`) and a
  `NaN` there falls into none of the three partitions, breaking the reassembly
  through no engine fault.

- **The sharding gates are frozen at class load, so testing the sharded path
  needs a separate JVM.** `LogicalPlanCardinality.MinShardableSize`
  (aggregate/distinct exchange) and `BroadcastBuildThreshold`
  (broadcast-vs-shuffle join) are `val`s resolved once from system properties /
  env vars at class load. A test cannot flip them mid-JVM to compare
  collapsing-vs-sharded plans — the `val` is already bound, and a
  `System.setProperty` in `@Before` runs too late. So the sharded path gets its
  own target, `sharded_mode_fuzz_test`, with `jvm_flags` setting both to `1`; its
  `shardingGateIsActive` test asserts the flags took effect. This is the same gate
  `ModeDifferential` notes it cannot toggle as an in-JVM mode. See
  [testing.md](testing.md#property-based-testing-fuzz).

- **The SQL frontend used to reject grouping parentheses and `TRIM(x)` —
  fixed.** `SELECT (a + b) * c`, `WHERE (x = y)`, `ON (a = b)`,
  `HAVING (COUNT(*) > 1)` and `TRIM(name)` all bind now. Two things are worth
  knowing about the shape of that fix.

  **A grouping paren is transparent at five walks, not one.** Adding the
  `ParenthesedExpressionList` case to `bindExprWithAggs` alone leaves the engine
  reporting a *misleading* error rather than the honest one: `childExpressions`
  is a second walk over the same AST (it drives `containsAggregate`, the
  aggregate `collect`, and `collectWindows`), and with no paren case there it
  returns `Nil` silently — so `SELECT (SUM(x)) FROM t` never collects its
  aggregate and dies with "Aggregate 'SUM' used in non-aggregating position",
  and `SELECT (ROW_NUMBER() OVER (ORDER BY x))` with "Window function used in
  non-window position". Three more walks matter: the post-aggregation `rebind`
  in `buildAggregation` matches a projection to a group key by canonical text,
  so without a paren case `SELECT (a + b) ... GROUP BY a + b` fails with
  "Unknown column 'a'. Available: [a + b]"; `deriveAlias` decides the output
  column name, so `SELECT (a)` would be named `(a)` rather than `a`; and
  `selectListOrdinal` decides what counts as a position, so `ORDER BY (1)` would
  quietly become a constant sort key. All five match `LogicalBuilder.Grouped`;
  a new walk over the AST must too.

  **Only single-argument `TRIM(x)` is supported, and the rejection is
  deliberate.** JSqlParser gives TRIM its own `TrimFunction` node (not the
  generic `Function` node `UPPER`/`LOWER` get) so it can carry
  `TRIM([LEADING|TRAILING|BOTH] [remstr] FROM str)` and MySQL's
  `TRIM(str, remstr)`. Its accessors are a trap: `getExpression` is the
  **remstr**, not the string being trimmed, in every extended form —
  `TRIM(BOTH ' ' FROM x)` gives `getExpression=' '`, `getFromExpression=x`, and
  `TRIM(LEADING FROM x)` gives `getExpression=null`. Binding `getExpression`
  blindly (the obvious-looking implementation) trims the wrong operand
  silently: `TRIM(BOTH 'x' FROM 'xxaxx')` returns `x`. `TRIM(x, 'a')` is worse,
  because it sets neither the specification nor the FROM keyword — the remstr
  just lands in `getFromExpression` and gets dropped. So `Funcs` implements the
  single-argument whitespace trim (TRIM's `FunctionCatalog` entry takes one
  argument, and `applyVec` gates it on one too) and the binder rejects every
  other form with "Only the single-argument TRIM(x) is supported", the message
  its entry gives the arities it refuses. `LTRIM`/`RTRIM` parse as ordinary `Function`s and are simply
  unknown functions.

  `QueryGen` / `MetaQueryGen` still render paren-free but for decimal
  arithmetic — that is now an unused widening opportunity rather than a
  constraint (see [testing.md](testing.md#property-based-testing-fuzz)).
  Decimal arithmetic takes the parentheses its shape needs and no more,
  because JSqlParser rejects some statements with three stacked: `SELECT
  IF(c0 LIKE '%', 1, 2) AS p0, ABS((((c1 - c1) * c1) % 1.0)) AS p1 FROM t`
  fails to parse, while either select item alone, or the same item with one
  pair fewer, parses (`SqlRender.decimalSide`).

- **Keys and values that could not change the answer used to run — fixed.** A
  sort, group or partition key that reads no column orders, groups or
  partitions nothing, and the binder took one: `ORDER BY 'b'` (what a query
  that meant the column `"b"` says, a MySQL habit) and `ORDER BY +1`, `NULL`,
  `1.5` or `1 + 0` kept the rows' order, `GROUP BY 1.5` or `CURRENT_DATE` made
  one group, and `PARTITION BY 'a'` one partition. `LogicalBuilder.isConstant`
  (no column, no `RAND()`; `RAND(seed)` is one value) decides, and
  `refuseConstantKey` refuses one at every key site, naming it and the
  spellings of a column and a position; a position, and a name of an output
  column, read a column whatever it holds. Beside them, a LIKE pattern that
  ends in its escape character escaped nothing and matched the character
  itself (`'a!' ESCAPE '!'`), and `SUBSTRING(s, 2, -1)` was the empty string:
  each is now an error, as in Postgres, at bind time when it is a constant
  (`constantValue` computes one there) and on the row otherwise
  (plans/robustness/23).

- **`ORDER BY` used to read every name in the input — fixed.** Without a
  `DISTINCT` the sort sat below the projection and bound its keys there, so a
  name was the input column of that name even where an output column had it:
  `SELECT -b AS b FROM t ORDER BY b` sorted by the input `b`, `SELECT b AS a FROM
  t ORDER BY a` by the input `a`, `SELECT a, b AS a FROM t ORDER BY a`, which SQL
  calls ambiguous, by the input `a`, and an alias the input lacks (`ORDER BY n`
  for `COUNT(*) AS n`, `ORDER BY total` for `a + b AS total`) was an unknown
  column or an ungrouped one. Only an ordinal reached the output, by binding the
  item's expression a second time below the projection, so `SELECT RAND() AS r
  ... ORDER BY 1` sorted by another draw. `LogicalBuilder.OrderScope` reads a
  bare name as the output column first, refuses two output columns of the name
  unless they are one column, and refuses, inside an expression, a name that is
  an input column and the alias of something else, which Postgres and Spark read
  differently; `sortOverProjection` sorts over the projection, which computes a
  key that is no output column beside the output columns, and drops it above the
  sort, so a key that names an output column sorts by the values returned. Past
  a `DISTINCT` or a `UNION` the scope is the output alone, as it was.
  `MetaQueryGen.totalOrderKeys` still orders a top-n query by its source
  columns, which mean the same, and emits no `LIMIT` for a `DISTINCT`, windowed,
  aggregate or `UNION` shape; ordering those by output names or positions would
  give them a total order too, an open widening opportunity (plans/robustness/22).

- **Generator-rendered SQL covers only the supported grammar; the frontend
  needs a text corpus.** `QueryGen` and `MetaQueryGen` build a typed AST and
  render it, so they can never write a clause the engine does not implement, a
  second spelling of one it does, or a query that does not parse — and every
  oracle over them asks whether the answer is right, never how a query fails.
  Three hand-found frontend bug families (`ORDER BY <ordinal>`, grouping
  parentheses, plan 01's eighteen dropped clauses) hid there. `SqlTextGen`
  writes text: the clause table spliced into every context and position,
  honoured clauses with their clause-free twins, equivalent spellings, and
  mutations of every generator's output. Widening an AST generator cannot
  replace it, since anything an AST can say is already grammar the generator
  knows; when you add a construct to the engine, add its spellings and
  positions to `SqlTextGen` as well as to the AST generators.

- **A hot implicit exception has no message, and `ForkJoinTask.get` throws a
  copy.** HotSpot throws a `ClassCastException`, `NullPointerException` or
  `ArithmeticException` that one site throws often as a preallocated instance
  with no message and no stack (`-XX:+OmitStackTraceInFastThrow`, on by default),
  so an oracle that recognizes a failure by such a message misses it after
  warm-up; `oracle/CleanRejection` recognizes by class and bound plan. And
  `ForkJoinTask.get` rethrows a task's exception as a copy made on the waiting
  thread (the same class, the original as its cause, the original's `toString`
  as the message, or no message when the class has no cause constructor) inside
  an `ExecutionException`, and a nested fan-out adds a `RuntimeException` around
  a task's checked exception. That chain of class names was what a failing
  query reported and what a job recorded in `_run.json` (`ExecutionException:
  java.lang.ArithmeticException` for a decimal overflow under a sort) until
  `Scheduler.Submitted.await`, which `submitAndAwaitAll` uses too, began
  rethrowing the task's own exception, with the waiting thread's stack as a
  suppressed one ([plans/robustness/10](../plans/robustness/10-error-messages-name-internals.md)).
  Wait for pool work through those, never through `ForkJoinTask.get` directly;
  `CleanRejection` calls a wrapped failure a finding. A fatal error stays
  wrapped in an `ExecutionException` there, so a caller that catches only
  `NonFatal`, as `DataJob`'s task runner does, still catches it. A
  `StackOverflowError` is not `NonFatal`: catch it explicitly where a deep
  expression can raise it.

- **A clause the binder does not read used to vanish — every one is now
  rejected, and a test keeps it that way.** `LogicalBuilder` binds the
  JSqlParser AST by calling the getters it knows about, so a clause carried by
  any other getter never errored: it was dropped, and the query answered a
  different question from the one asked. The 2026-09-10 audit
  ([plans/robustness/01](../plans/robustness/01-reject-or-honor-every-clause.md))
  found eighteen, five of them badly wrong: `JOIN ... USING` and `NATURAL JOIN`
  ran as cartesian products, `LEFT SEMI JOIN` as a left outer join,
  `SUM(DISTINCT x)` ignored `DISTINCT`, and `agg FILTER (WHERE p)` dropped the
  predicate and stopped the aggregate collapsing. Classifying every getter
  found many more: a bare `OUTER JOIN` and Oracle's `a = b(+)` ran as INNER
  joins, `x NOTNULL` ran as `x IS NULL`, `RLIKE` / `REGEXP` ran as `LIKE`,
  `LIMIT 1, 2` dropped its offset, `ORDER BY` / `LIMIT` after a parenthesized
  query were dropped, `COUNT(UNIQUE x)` ignored `UNIQUE`, `ROW_NUMBER(a)` and
  `SUM(a, 2) OVER ()` ignored arguments, `SUM(a) WITHIN GROUP (...)` computed a
  running sum, and `CAST(x AS INT[])`, `t.s[1]`, `* EXCEPT (c)` and
  `FROM other.t` dropped the array type, the subscript, the exclusion and the
  schema.

  `sql/plan/UnsupportedClauses.scala` is the fix. For every AST class the binder
  reaches, each getter the binder does not read is listed either as a rejection
  (the clause, advice, and SQL that reaches it) or as inert (a spelling such as
  `!` for `NOT`, a structural accessor, another view of a value the binder
  reads), with the reason. `LogicalBuilder.build` runs `UnsupportedClauses.reject`
  before binding. It walks the statement reflectively and descends only through
  the getters the binder reads, so it inspects exactly the nodes binding would
  and in every position they occur — `NULLS LAST` inside `OVER (...)` meets the
  same entry as in the outer `ORDER BY`. A `SELECT` with no `FROM` (an internal
  `NullPointerException` before) reads one row with no columns now, and
  `GROUPING SETS` no longer leaks the `_agg0` slot name into its error.

  **The guard is `sql/plan:unsupported_clauses_test`, and it is mechanical.** It
  reads which getters the binder calls out of `LogicalBuilder`'s compiled class
  files — the constant pool names every class the binder matches on and every
  method it calls — and requires every public getter and field of every audited
  class to be read, rejected or inert, exactly once; every class the binder
  reaches, and every JSqlParser subclass of an audited class, to be audited; and
  every rejection's example to reach that rejection. A JSqlParser upgrade that
  adds a getter fails it until someone decides what the getter means, and so
  does a binder change that starts reading a rejected getter without deleting
  the rejection. **What it cannot see is a getter the binder reads in one
  context and ignores in another**: `DISTINCT` is honoured in `COUNT` and was
  dropped in `SUM`; an argument is read for `LAG` and was ignored for
  `ROW_NUMBER`. Those are *partial* rejections, found by reading the binder. When
  you teach the binder to read a getter for one function or one position, check
  what it does in the others. `fuzz/frontend_fuzz_test` is the generative net
  over that blind spot: its NoSilentDrop property splices every entry into every
  context and position and requires it refused, and checks that every clause
  plan 01 honoured keeps its meaning in every context. It also found what no
  getter carries at all (see "Generator-rendered SQL covers only the supported
  grammar" below), and that one inert reason was backwards:
  `StringValue.getNotExcapedValue`, listed as "the literal before quote
  unescaping", is the unescaped value, and the binder read the escaped one, so
  `'it''s'` was five characters. It reads the value now
  ([plans/robustness/13](../plans/robustness/13-literals-are-read-as-written.md);
  see "JSqlParser keeps a literal's text" above). An inert reason is a claim
  the test cannot check: read the getter before writing one.

  Three JSqlParser traps the audit stepped on: `Table.getDBLinkName` returns the
  table's own name when there is no `@link` and `getDatabase` is a non-null empty
  object for an unqualified table, so "the getter returned something" is not
  presence for either; `Function.getAttributeName` throws on every call without
  an attribute; and `TRY_CAST` / `SAFE_CAST` differ from `CAST` only in
  `CastExpression.keyword`, a public field with no getter, which is why the test
  audits public fields as well as getters. A fourth, found by the frontend
  fuzzer: `CastExpression.toString` throws for a ROW-type CAST, and the binder's
  catch-all rendered the node it refused, so a ROW cast under a node the pass
  does not audit (`CAST(a AS ROW(x INT)) IS FALSE`) was a
  `NullPointerException`; the same `toString` keys the text that matches a
  HAVING or a SELECT item to its GROUP BY key. The binder now refuses a
  construct by the name `UnsupportedClauses.refusal` gives it (`IS FALSE is not
  supported`), never by its class or its `toString`, and that matching text
  falls back to matching only the node itself when `toString` throws
  ([plans/robustness/10](../plans/robustness/10-error-messages-name-internals.md)).
  `sql/plan:unsupported_clauses_test` requires a name for every JSqlParser
  expression, FROM item and set operation the audit does not cover.

- **`ORDER BY <ordinal>` used to parse and then silently not sort — fixed.**
  `ORDER BY 2 DESC` returned scan order, because the ordinal bound as an integer
  literal (a constant sort key, which the comparator can never use to separate
  two rows) rather than as the second output column. `GROUP BY <ordinal>` always
  resolved positionally; ORDER BY never got the equivalent treatment at any of
  its three binding sites — the plain sort (then below a `Project`), above a
  `Distinct`, and above a `Union`. All three now resolve through `LogicalBuilder.selectListOrdinal`, and
  an out-of-range position is a hard error (`ORDER BY position 5 is not in select
  list (size 2)`) instead of a no-op. Two shape rules the resolver inherits from
  `GROUP BY`, and one that differs: only a **bare** integer literal is an
  ordinal, so `ORDER BY +1` (a JSqlParser `SignedExpression`) is no position but
  a constant, which plans/robustness/23 refuses; an ORDER BY position **may** point at an aggregate
  (`SELECT a, SUM(b) ... ORDER BY 2` is standard SQL) where a GROUP BY position
  may not; and parens used to be clause-dependent, because JSqlParser drops them
  in `GROUP BY (1)` (a bare `LongValue`, so it *does* resolve positionally,
  despite what the old doc comment claimed) but keeps `ORDER BY (1)` as a
  `ParenthesedExpressionList`. Now that the binder accepts grouping parens the
  resolver strips them first, so both clauses agree and `ORDER BY (1)` is
  position 1 — which is also what Postgres and MySQL do, since their grammars
  drop a redundant paren before the positional check ever runs. Leaving the
  paren opaque there would have made `ORDER BY (1)` a constant sort key: a
  silent no-op, the exact bug this entry records fixing.

- **Layout invariance is a property of the window FUNCTION, not of "has a
  window".** The mode-agreement oracles compare result multisets across
  partition/batch layouts, so they may only run a query whose output multiset is
  layout-independent. `MetaQueryGen` used to exclude every windowed query from
  them, which left `WindowExec` — and the sharded window path, where the planner
  puts an `ExchangeExec` under it — checked by `Tlp` on a single fixed layout
  only. The distinction that matters is whether two rows TIED on the window's
  `ORDER BY` can get different values: `ROW_NUMBER`, `LAG`/`LEAD` and an
  aggregate over a `ROWS` frame can, while `RANK`/`DENSE_RANK` cannot (peers share
  a rank, and a rank depends only on the multiset of order-key values in the
  partition), and neither can an aggregate window with no `ORDER BY` (its frame is
  the whole partition) or one over a `RANGE` frame, the default with an `ORDER BY`
  included, whose frame takes every peer (until plans/robustness/20 RANGE ran as
  ROWS, and every running aggregate was tie-sensitive). `WinItem.tieSensitive` carries the classification and
  `MetaQuery.isLayoutInvariant` reads it, so the tie-insensitive shapes now flow
  through mode agreement and join commutativity. The `invariantWindows` coverage
  guard in `generatorCoversShapes` is what stops the blind spot returning.

  The classification is only as sound as the engine's peer test: while
  `WindowExec.sameKey` compared ORDER BY peers with IEEE equality, `RANK` over a
  `NaN` key WAS tie-sensitive, and the relations duly reported the disagreement
  (the `sameKey` entry above). Classifying a function as tie-insensitive is
  therefore a claim about the engine, not only about SQL.

- **The job runner's event loop waits for an event per task and per input,
  so every worker posts one whatever its work throws.** Each worker posted its
  event only after its work returned, and caught `NonFatal` throwables alone,
  so an `Error` (a `StackOverflowError` from a deep query, a `LinkageError`,
  an `OutOfMemoryError` on the task's own thread) ended it without one:
  `job.run()` waited in `LinkedBlockingQueue.take` for ever, with idle
  workers and nothing logged (plans/robustness/14). Now a worker catches
  every `Throwable` and posts in a `finally`. A stack overflow or any other
  `Error` fails its task; a failure of the JVM itself, any
  `VirtualMachineError` but a stack overflow, stops the run, since it can
  leave shared state half-written: no task starts after it (a worker reads
  the stop before it starts its task), each that had not started is
  `Skipped` saying so, and `JobResult.error` and `job.json`'s `errorMessage`
  name it. An input that runs out of memory loading is a failed input, as
  any other failure to load is (its warning says to stream it with
  `cache: false`). `oracle/DagSchedule`'s corpus throws a `LinkageError` from
  half its injected failures, so the property holds the loop to this.
- **`job.json`'s `runFile` follows the task's CONFIG, not its result.** The
  aggregate manifest indexes each task's full `_run.json`, and `JobSession`
  hydration follows that pointer to rebuild a finished run (timestamps, per-
  validation records, the output directory) — falling back to the compact
  summary when it is absent. `DataJob.writeJobRecord` used to derive the pointer
  from `TaskResult.outputPath`, which is `None` for a `Failed` or `Skipped`
  task even when that task has an `outputFile` and the runner stamped a record
  there (`writeFailedRecord` / `writeSkippedRecord` both do). So those records
  sat on disk unreachable from the manifest that exists to index them,
  contradicting `JobTaskSummary`'s own scaladoc. The pointer is now derived from
  `SQLTask.outputFile` (rendered, cloud paths excluded — those write no record),
  so it is present for every terminal status. Found by
  `fuzz/dag_scheduler_fuzz_test`.

- **A reopened task is the `TaskResult` its run returned, field for field.**
  `JobSession.stateFromRecord` rebuilds each task from its `_run.json`
  when a job directory is opened, and it used to get two fields wrong for every
  reopened run. It dropped the record's `enqueuedAt`, so the task came back
  enqueued at the epoch and `queueWaitMillis` was the start time in epoch
  milliseconds: the DAG canvas and the details panel showed every task of a
  reopened run "queued" for fifty-six years. And it gave a `Failed` or
  `Skipped` task its record's directory as `outputPath`, which the run reports
  as `None` (nothing was written), so the details panel said "Wrote to" a
  directory holding only the record, where the live run had said "Will write
  to". The output path now follows the record's status, as the runner sets it.
  A record from before records carried `enqueuedAt` still reopens at the epoch,
  and `TaskResult.queueWaitMillis` now reads the epoch as unknown (0), which its
  scaladoc already promised. Found by the `Hydration` oracle in
  `fuzz/dag_scheduler_fuzz_test`; regressions in `gui/job_session_test`.

- **"Newest first" compares whole instants.** `JobOutputLayout.detect` orders
  a multi-run directory by each `job.json`'s `finishedAt`, and
  `TaskRunRecord.discover` orders a task's partitions by each record's
  `writtenAt`. Both sorted on `toEpochMilli`, so two runs or two partitions
  inside one millisecond tied, and the tie fell to the directory listing's
  order — on APFS a hash order, not the name. The GUI's run picker then listed
  an older run first, and a task the manifest did not cover reopened from an
  older partition than the newest (`JobSession` takes the head of `discover`).
  A small job run twice in a row finishes both runs inside one millisecond,
  which is how the `Hydration` oracle found it. Both sorts now compare the
  `Instant`s themselves, which carry microseconds; regressions in
  `job/job_output_layout_test` and `job/data_job_test`.

- **A run record holds any string, half a surrogate pair included.** Every
  record is hand-written JSON, and the writers escaped the quote, the backslash
  and the control characters and put everything else in raw. Half a surrogate
  pair (what `SUBSTRING` leaves of a character past the Basic Multilingual
  Plane, and a task name or failure message can carry) is a character UTF-8
  cannot encode, so `Files.writeString` threw — and since a record write never
  fails a run, the task's `_run.json`, the job's `job.json` and the task's
  `_perf.json` were silently never written. The five copies of the escape (the
  two records and `JobFiles`' config writer in `job`, the perf record and its
  operator tree in `core/metrics`) are now one per JSON reader, `Json.escape`
  and `JsonMini.escape`, which write such a character as a `\u` hex escape that
  both readers decode back. Found by the `Hydration` oracle's adversarial task
  names; regressions in `job/json_test`, `job/data_job_test` and
  `core/metrics/metrics_collector_test`.

- **A failed validation's sample rows have a file of their own.** The runner
  names a sample `_validation-<slug>.csv` after the validation, and two
  validations whose names reduce to one slug (`a b` and `a.b`, or any two made
  of punctuation, which fall back to `validation`) wrote the same file: the
  second sample overwrote the first, so the first failure reopened with the
  second's rows. And a long name made a file name past the 255 bytes a file
  system takes, so the sample was never written and the record said there was
  none. The slug now keeps its first 100 characters, and one already taken by
  another failed validation of the task gets `-2`, `-3`, ... (the record's
  `sampleFile` names the file, so readers are unaffected). Found by the
  `Hydration` oracle's adversarial validation names; regressions in
  `job/data_job_test`.

- **A reopened task without its own record shows what survives, and says
  what does not.** `JobSession` used to rebuild a task whose `_run.json` was
  missing or unreadable — or that had none, a memory-only task — from
  `job.json`'s compact entry, which held no timestamps and no failed validation
  names: every timestamp came back at the epoch (a 0 ms duration) and a
  `ValidationFailed` task listed no failure, so every validation card read
  PASSED beside a VALIDATION FAILED status. It trusted a record that named
  another task (two swapped records showed each task as the other), preferred
  the entry to the task's own record when the pointer led nowhere, and said
  nothing in any of these cases. The user decided what it should do
  ([plans/robustness/15](../plans/robustness/15-what-a-reopened-run-shows-without-its-record.md)):
  `job.json`'s entries now carry the three timestamps and each failed
  validation's name and row count, so an entry rebuilds everything but the
  failing rows; only a record that names a task is that task's; a pointer that
  fails falls back to the task's own record of the same run; and the run log
  names every record that could not be used, and a run directory with no
  readable `job.json`. A `job.json` from before carries neither field, and a
  task rebuilt from it shows its duration and failed validations as unknown.
  Found by the `Hydration` oracle's corruption mode; regressions in
  `gui/job_session_test`.

- **Every input that fails to load is a warning, even one no task reads.**
  The scheduler fans an input's load failure out to the tasks that read it,
  which it skips, and to nothing else. An input no task reads (left declared
  after its last reader went, or a typo in an input nothing uses yet) has no
  one to fan out to, so its failure used to vanish: the run said `succeeded`,
  with no warning and nothing in `job.json`, though `onInputFinish` had seen
  the failure. The user decided
  ([plans/robustness/08](../plans/robustness/08-unconsumed-input-failure-is-swallowed.md))
  that every input that fails to load is a warning in `JobResult.warnings` and
  `job.json`, in declared order, whether or not a task reads it (a task
  skipped by two failed inputs names only the first in its reason, so the
  warnings are the one place both appear), and that a warning never fails a
  run: an unread input's failure leaves the run green, and its warning says no
  task reads it. Found by the DAG-scheduler fuzzer, whose generator then gave
  every input a reader, since an unread one's failure was invisible to its
  oracle; it now also declares an input no task reads, and `DagSchedule` holds
  the warnings to one per failed input. Regressions in `job/data_job_test`.

- **A task with no `outputFile` can feed downstream views and validations.**
  It could not until recently: `DataJob.materializeIfNeeded` threw
  `UnsupportedOperationException("v1 requires an outputFile ...")` whenever a
  memory-only task had a DAG dependent or a validation, even though `SQLTask`'s
  own scaladoc advertised memory-only feeder tasks. Worse, it threw at RUN time,
  from inside the worker, so half the DAG had already executed and every
  downstream task cascaded to `Skipped` — a structural mistake surfacing as a
  mid-run failure rather than at `DataJob.buildDag()` like every other one
  (cycles, unknown views, duplicate output paths). The limitation is gone: such a
  task's result is drained into a `MaterializedView` via
  `MaterializedView.fromQuery` and published like any other view. The trade is
  explicit — the whole result stays in heap for the rest of the run, the same
  deal `cache = true` inputs and materialized CTEs make — so a task that writes
  an `outputFile` still publishes by re-reading its own output rather than
  holding it twice. Found by `fuzz/dag_scheduler_fuzz_test`.

- **An `AggState`'s accumulator slot must match its spill format, per type.**
  `MinMaxState` keeps a typed extremum in one of three slots — `longCur`,
  `doubleCur`, or `currentBoxed` — chosen from the result `DataType`. `MIN`/`MAX`
  over a `BooleanType` column used to return NULL under spill (and the right
  answer off-spill) because `updateAt` stored the boolean boxed while
  `writeSelf`/`readSelf` wrote and read `longCur`: the restored state had
  `hasValue = true` and a null boxed value. Booleans now ride `longCur` as 0/1 on
  every path (update / merge / finish / serde), which also orders them the SQL
  way, FALSE < TRUE. Found by `mode_differential_fuzz_test`. When adding a type
  to any `AggState`, change all four paths together — the on-disk format is the
  only one a global aggregate never exercises, so the mismatch is invisible until
  a grouped query spills. `fuzz/metamorphic_fuzz_test`'s `agg-decomposition`
  relation is the general guard.

- **A CSV decoder reads ahead of the parser.** A `BufferedReader` over a
  decoder fills 8192 characters at a time, so bytes that are no text in the
  charset fail the read while the parser is still several lines before them.
  The parser therefore names only the file (`CsvRowParser.InvalidText`), and
  `CsvReader` finds the line by decoding the file again, chunk by chunk,
  counting line breaks up to the bytes that failed.
- **A one-column CSV file writes a NULL as a blank line.** With the default
  null string a NULL is an empty field, and a row of one empty field is a blank
  line, so a reader that skips blank lines loses the row. Blank lines are rows
  in a one-column file and skipped in a wider one (where no written row is
  blank), and a column name that is empty is quoted, so a header is never a
  blank line. Whether a line is blank is the parser's to say (`blank`), whether
  it is a row the reader's, which knows how many columns the file has.
- **A malformed template expression used to stay in the text — fixed.**
  `TemplateRenderer` substituted what its pattern matched and left every other
  `{{ ... }}` as written, so a task filtering `WHERE CAST(day AS STRING) =
  '{{ today - 1d }}'` Succeeded with 0 rows, comparing against the template's
  own text, and an output path made a directory named `{{ today - 1d }}`; `{{
  today 5 }}` was read as `+ 5` "defensively", and a huge offset threw
  `NumberFormatException`. Now any `{{` left after substitution is an error
  naming the expression and the grammar, and an offset past a BIGINT or one
  that moves the time past what java.time represents is a clean error
  (plans/robustness/28). A `}}` alone stays text: nested JSON in a string
  literal ends that way, while `{{` never appears in JSON.
- **Job configs used to skip the keys they did not know — fixed.**
  `DirectoryJobLoader` read the keys it knew, each options reader read its own
  from one shared map, `ExecutionOptions` documented that "typos should fall
  back to the safe default", and the JSON parser kept a repeated key's last
  value. So a job with a typo in every config file Succeeded: `"formatt"` and
  `"partiton_by"` were skipped, `"delimeter": ";"` read `a;b` as one column,
  `"spill": "yes"` ran without spill and `"64MB"` as a threshold was ignored.
  Now an unknown key, a repeated one, an option its format does not read
  (`InputFilePath.optionKeys`, `OutputFilePath.optionKeys`), a value that is
  none of its option's (`OptionValues`: `true` or `false`, a whole number of
  1 or more, a charset the JVM has) and a format other than csv or parquet
  fail the load, naming the file, the key and what it takes. The checks run
  where an input or output is declared, so a job built in code, and the GUI
  through `JobFiles`, refuse the same (plans/robustness/27). The global
  metrics switch (`transformer.metrics.enabled` / `TRANSFORMER_METRICS_ENABLED`) is no config
  file and keeps its own reading.
- **A task's output and a CSV header used to repeat a column name — fixed.**
  Names match regardless of case, so `a` and `A` are one name, yet a task
  returning `SELECT a, a, b AS A` Succeeded and wrote three columns named a,
  and the task reading it failed with `Ambiguous column reference 'a' matches 3
  columns`, naming neither the task nor its columns; a CSV header `a,a,,B,b`
  loaded too. `Schema.repeatedName` finds such a name, and `DataJob` fails a
  task whose result has one before it writes, while `CsvReader.checkHeader`
  fails an inferred input's load on a repeated or empty header name; Spark and
  DuckDB rename them, this engine refuses rather than guess
  (plans/robustness/26). A query that is no task's result, a CTE included, may
  repeat names as SQL allows, and a reference to one is the ambiguity error.
- **A date or a timestamp past its column's range used to wrap — fixed.** A
  DATE is stored as an INT of days and a TIMESTAMP as a BIGINT of
  microseconds, but text is read into a `LocalDate` or an `Instant`, which
  reach much further, and narrowed with `toEpochDay.toInt` and
  `getEpochSecond * 1000000L`: `CAST('+999999999-12-31' AS DATE)` was
  `+466210-05-14` in a vector and the date itself on the row path, and an
  INT96 parquet day past the range another instant. Every narrowing now goes
  through `DateVector.epochDay` / `TimestampVector.epochMicros`, which fail
  naming the value, and a CAST checks its result (plans/robustness/25). The
  CSV reader already checked, but its timestamp product overflowed before the
  fraction brought the earliest microsecond back into range; the shared
  conversion borrows the second.
- **SQL's CAST used to read numbers with Java's grammar and CSV with a plain
  one — fixed.** CSV moved to plain decimals in plan 06; CAST kept
  `java.lang.Double.parseDouble` and its kin, so `CAST(' 5' AS DOUBLE)` and
  `CAST('5d' AS DOUBLE)` were 5.0, `'0x1p3'` was 8.0, Arabic-Indic digits an
  `INT`, and a string literal beside a number read the same way. Neither
  grammar checked a float's range: `'1e400'` was an infinity and `'1e-400'` 0,
  in a CAST and in a CSV column alike. `core/NumberText` is now the one grammar
  both read through, and a number the type cannot hold is none of its values
  (plans/robustness/24); `csv_fuzz_test`'s cast property holds the two readers
  to one answer, so a third grammar fails it.
- **Names, functions and `UPPER` / `LOWER` used to depend on the JVM's locale
  — fixed.** Every name the engine matches without regard to case went through
  `String.toLowerCase()` or `toUpperCase()`, which read the default locale; under
  a Turkish or Azerbaijani one, `I` lowers to a dotless i and `i` uppers to a
  dotted I. So on such a machine `SELECT id FROM items` failed (`View 'items' is
  not registered`: the catalog had stored `Items` under a dotless i), as did `min(...)`, `sin(...)`, `CAST(x
  AS int)`, a template's `{{ ISO_DATE }}`, a task reading `Indexed` as `indexed`
  and parquet's `"compression": "gzip"`, while `UPPER` and `LOWER` answered
  differently. And `"part-%05d"`-style formats print a locale's own digits, so a
  locale with non-ASCII numerals named part files and `{{ current_month_pad }}`
  in them. Every such call now names `Locale.ROOT`, and
  `job/locale_independence_test` runs under tr-TR (six of its tests failed at
  the code before) and scans every module's compiled classes for the
  default-locale forms, so a new one fails the suite. The desktop GUI and the
  data viewer are scanned for case folding only, since their display text may
  format a number in the user's locale; they folded keywords and input names in
  the default locale until plans/testing/04 (below). See
  [plans/robustness/16](../plans/robustness/16-locale-dependent-names.md).
- **A `CASE`, `IF` or `COALESCE` used to evaluate every branch on every row
  — fixed.** `CaseExpr.evalVec` evaluated each branch's value over the whole
  batch before choosing per row, on the grounds that every `Expr` is side-effect
  free; but an expression can throw, so `CASE WHEN s = 'q' THEN CAST(s AS INT)
  ELSE 0 END` failed with `Cannot cast 'x' to int` though no row is `'q'`, and
  a decimal overflow on a row the `WHEN` excluded failed the query. `IF` and
  `COALESCE` evaluated every argument on both paths, `AND`, `OR` and a
  non-literal `IN` on the vector path. Each now evaluates an operand only where
  the answer needs it (`Operand.evalVec`, architecture §5a). The expression
  fuzzer missed it because its generator was total by construction, building no
  subexpression that fails on some rows and not others; it now builds exactly
  that, a CAST of a guard column under a conditional that keeps it off the rows
  it fails on, and requires a tree it built never to throw. Against the code
  before, it failed at seed 21. See
  [plans/robustness/17](../plans/robustness/17-case-evaluates-every-branch.md).
- **Integer arithmetic, `SUM` and narrowing CASTs used to wrap — fixed.**
  `2147483647 + 1` was -2147483648, `ABS` and unary minus of the smallest INT
  were negative, a `SUM` of large BIGINTs went negative, `CAST(3000000000 AS
  INT)` was -1294967296, and a CAST of a DOUBLE saturated at the type's limit
  with NaN read as 0: plain JVM arithmetic and `Number.intValue`. Each is now an
  `ArithmeticException` naming the operation and the type (`Integers` in
  `Ops.scala`), as a DECIMAL's overflow was. `SUM` keeps a 128-bit running sum
  and checks the true sum once, at the end: checking each partial sum would make
  `Long.MaxValue + 1 - 1` fail in one row order or partition layout and not in
  another. No fuzz property could see the wrap, since every mode and both
  evaluation paths wrapped alike; `ExactIntegers` now holds integer trees and
  SUMs over edge values to the exact answer worked out in `BigInt`, and failed
  at seed 0 at the code before. See
  [plans/robustness/18](../plans/robustness/18-integer-overflow-wraps.md).
- **RANGE frames, the default frame among them, used to run as ROWS —
  fixed.** `WindowExec.frameBoundsFor` mapped every bound to a row offset
  whatever the frame type, so `SUM(x) OVER (PARTITION BY g ORDER BY k)`, whose
  default frame is RANGE UNBOUNDED PRECEDING to CURRENT ROW, gave rows tied on
  `k` different running totals, in the order they happened to arrive, and
  `RANGE BETWEEN 1 PRECEDING AND 1 FOLLOWING` took one row each side, not the
  values within 1. The docs said RANGE "differs only when the ORDER BY has
  ties", which was false for offsets. Frames that select nothing by the kinds
  of their bounds answered NULL on every row, a frame on ROW_NUMBER was
  ignored, and `CURRENT ROW AND 2147483647 FOLLOWING` overflowed `r + k`: the
  first row's frame ran for minutes, every later one's was empty. Now RANGE
  takes peers and measures values (`frameBounds`), ROWS bounds are clamped long
  arithmetic, a running frame is aggregated in one pass, and the impossible
  frames are refused. The window property in `mode_differential_fuzz_test`
  works out each frame by SQL's definition in every layout; against the code
  before it failed at seed 2. See
  [plans/robustness/20](../plans/robustness/20-range-frames-run-as-rows.md).
- **Floats used to have three equalities — fixed.** `=` followed IEEE (`NaN`
  unequal to itself, `-0.0` equal to `0.0`), the sort and `RANK` followed
  `Double.compare` (`NaN` tied with `NaN`, `-0.0` below `0.0`), and GROUP BY,
  DISTINCT, PARTITION BY and join keys went by bit pattern (`NaN` with `NaN`,
  `-0.0` apart from `0.0`). So `fl JOIN fr ON fl.k = fr.k` matched four `NaN`
  pairs that `fl, fr WHERE fl.k = fr.k` rejected and missed two zero pairs it
  kept: one predicate, two answers, by which join the planner chose. The user
  chose Postgres's and Spark's rule: `Ops.floatCompare` (`NaN` equal to `NaN`
  and highest, `-0.0` equal to `0.0`) behind `=`, `IN`, ordering and the peers,
  and keys and hashes through `KeyCodec.floatKeyBits` / `floatKeyValue`.
  Parquet pushdown no longer pushes a float comparison with a zero or `NaN`
  literal, which a file's statistics order differently. No property saw it,
  since every join relation compared a hash join with another;
  `mode_differential_fuzz_test`'s key-equality property holds joins, groups,
  DISTINCT, UNION, PARTITION BY and RANK to `=`, and failed at seed 1 at the
  code before. See
  [plans/robustness/21](../plans/robustness/21-two-float-equalities.md).
- **JLine rewrites a line of SQL unless told not to.** Its `DefaultParser`
  takes `\` for an escape, and `LineReader` drops each one from the line it
  returns, so `'x\ty'` reached the engine as `'xty'`; and its history
  expansion reads `!` as an event, which `a != 1` is not. The shell's
  `StatementParser` escapes nothing (`isEscapeChar` false) and
  `DISABLE_EVENT_EXPANSION` is on. `ShellTest` sends both through.
- **`TerminalBuilder.streams(in, out)` makes a pty, not a dumb terminal.** JLine
  4 builds a `PosixPtyTerminal` that pumps the input through a real pty, echoing
  it, and a short input ends before the reader has read a line of it, so the
  shell saw only end of input. A test of the shell builds a `DumbTerminal`
  over its streams directly (`ShellTest.terminal`).
- **A pty of no size elides JLine's prompt.** Under a pty whose window size was
  never set (columns 0), JLine draws `transformer> ` as `>....`; a terminal
  always has a size, a test pty may not, so a driver sets one (`TIOCSWINSZ`).
- **Java's `user.home` is the account's home, not `$HOME`.** A run of the
  shell with `HOME` pointed at a scratch directory still writes
  `~/.transformer_history`; pass `-Duser.home=...`, or `Shell.run`'s `history`.
- **JLine's native terminal providers warn on newer JDKs.** Its `jni` and `ffm`
  providers call restricted methods, which JDK 24 (JNI) and 22 (FFM) warn of
  without `--enable-native-access`; `Shell.systemTerminal` asks for `exec`
  first on macOS and Linux, which runs `stty` and loads no native code, so only
  Windows loads JLine's native library.
- **A `PrintStream` swallows a closed pipe.** Java ignores SIGPIPE, and a
  `PrintStream` catches the `IOException` a write into a pipe whose reader has
  gone (`| head`) raises, keeping only `checkError`'s flag; so `transformer
  query --format csv "SELECT * FROM big" | head` went on reading every row,
  minutes for polymarket's 326M-row day. `Io.printing` asks `checkError` before
  each batch it prints and stops the statement as Ctrl-C does.
- **A parquet string column's intern cache had no bound.** Its keys are slices
  of page buffers, so a column whose values never repeat (a JSON document per
  row) kept every value of a row group and every page of it: polymarket's
  snapshots ran out of a 4 GB heap on a three-group aggregate. It now caches 4
  MB of values a row group (architecture §3c).
- **A stopped query finishes the work that holds its rows.** Ctrl-C in
  `transformer query`'s shell sets `ExecutionOptions.stopped`, which every scan
  asks before each batch (architecture §3g); a sort merging what it read, or a
  probe of a batch already read, runs to its end first. Stopping only the
  thread that prints, as the shell first did, left the query's pool tasks
  running after the prompt returned: a three-way self-join ran the JVM out of
  heap a few seconds after Ctrl-C and took the shell with it.

## Wrong answers the I/O properties found (2026-10-14, all fixed)

Plan 06 put the file formats under property-based tests (`fuzz/parquet_fuzz_test`,
`fuzz/csv_fuzz_test`), and what they and the probing around them found is fixed
and pinned by named regressions (see [testing.md](testing.md#test-inventory)):

- **Parquet pushdown dropped rows** the predicate kept: a string range against
  a literal past U+D800, and a negative bound on an unsigned 8- or 16-bit column
  (see "A parquet file orders its statistics in its own way" above).
- **A NULL in a one-column CSV table vanished**, written as a blank line the
  reader skipped, in a job's re-read for its downstream tasks too.
- **An empty string read back as NULL**, and so did a string equal to a custom
  null string: both wrote as the bare null string. NULL is now only the
  unquoted null string, and the writer quotes a value that equals it.
- **A BINARY column could not be read back** from the Base64 the writer writes.
- **The writer did not escape a separate escape character**, so `a\b` came back
  `ab` and a quoted value ending in the escape ran into the next field.
- **A delimiter or quote past ASCII crashed the writer**
  (`ArrayIndexOutOfBoundsException` from a 128-entry quoting table).
- **A job re-read its CSV output without the `escape` option**, and **a CSV
  output of no partitions wrote no file**, so the task feeding a downstream one
  failed with `No CSV files matched`.
- **Malformed CSV read silently**: an unclosed quote took the rest of the file
  into one field, a long row lost its extra fields and a short one was padded
  with NULLs. Each now fails naming the file and line.
- **Characters were replaced silently**: the writer printed `?` for one its
  charset lacks, and the reader U+FFFD for bytes that are no text. Both fail.
- **A byte-order mark began the first column's name.**
- **CSV inference typed `1.5` and `true` as a BOOLEAN** and read 1.5 as false:
  its ladder widened past types that cannot read each other's values. And a
  later value that did not fit (past the 1000-row sample, or in a later file)
  threw a bare `NumberFormatException`, or in a BOOLEAN column read as false.
- **A declared DECIMAL kept CSV text's own scale**, so a column held values
  wider than its type, which a parquet write then refused.

## Known wrong answers from the 2026-09-10 audit (all fixed)

An audit of the surfaces the fuzzers cannot reach, executed against
`SqlEngine` at commit `d4bb409` rather than inferred, found three clusters of
wrong answers. All three are fixed, each described above: SQL clauses the
binder silently dropped (see "A clause the binder does not read used to
vanish"), byte-array and decimal comparison (see "Byte arrays, decimals and
BIGINTs past 2^53 used to compare wrongly"), and the parquet reader's
timestamp units, decimals and other annotations (see "A self-consistent
round-trip cannot catch a wrong reading of an external format" and "Every
parquet annotation is decided"). Plans, evidence and repro SQL are in
[`plans/robustness/`](../plans/robustness/README.md).

All three clusters hid for the same reason, and it is the lesson to carry: the
fuzz generators render SQL from their own typed AST (so the corpus is exactly the
supported grammar), `DataGen.ColumnTypes` and `ExprGen.GenTypes` listed six of
the engine's ten `DataType`s, and every file-format fixture was written by our
own writer. Plans 04-07 in that directory close those three holes; plan 05's
frontend fuzzer closed the first, and found the defects in the next section.

## Defects the frontend fuzzer found (2026-10-04, all fixed)

`fuzz/frontend_fuzz_test` writes SQL text rather than rendering an AST, which is
the only way a corpus reaches the frontend's failure paths (see "Generator-
rendered SQL covers only the supported grammar"). Its campaigns and the probing
around them found five families, each recorded in a plan and named in the
fuzzer (a `CleanRejection.Defect`, a `SyntaxEquivalence` known disagreement, or
a `knownPlan` in `SqlTextGen.BeyondTheTable`) with a repro that fails the suite
once the defect is gone. Messages that named the engine's internals
([plans/robustness/10](../plans/robustness/10-error-messages-name-internals.md)),
functions and conditions that bound over types they cannot take
([plans/robustness/11](../plans/robustness/11-functions-bind-over-arguments-they-cannot-take.md),
see "A condition is a BOOLEAN" above), and text the parser accepted and the
binder never saw
([plans/robustness/12](../plans/robustness/12-text-the-binder-never-sees.md),
see "JSqlParser drops text" above), and literals and names read as JSqlParser
keeps their text (`'it''s'` was five characters, `"a"` named no column, an
integer past BIGINT was a raw `NumberFormatException`, `1e400` was `Infinity`;
[plans/robustness/13](../plans/robustness/13-literals-are-read-as-written.md),
see "JSqlParser keeps a literal's text" above), and deep or unbalanced SQL,
whose worst case hung a job for ever with no error (a ~2500-term expression
overflowed the binder's stack, and the job runner waited for the `Error`'s
task; three unclosed parentheses made a parse error take seconds and four ran
the parser into its time limit;
[plans/robustness/14](../plans/robustness/14-pathological-nesting.md), see
"Every walk over a parsed statement recurses" and "The job runner's event loop"
above) are all fixed. `CleanRejection.Defect` and
`SyntaxEquivalence.KnownDefects` are empty, and `BeyondTheTable` holds no
`knownPlan`.

## Defects the third audit found (2026-10-31, all fixed)

The third audit ran the engine against SQLite on generated queries in the
dialect both share (a deterministic wrong answer agrees with itself in every
mode, so only another engine sees it), in default, spill and sharded modes,
and probed windows, aggregates, joins and scalar expressions by hand. The
operators themselves came out clean; the defects were in the binder, the
planner, a few functions and the spill directory. Details and repros are in
[plans/robustness/30](../plans/robustness/30-third-audit.md). The pitfalls
worth carrying:

- **A name the binder gives a slot must not be a name a query can write.** The
  aggregate output named its aggregates `_agg0`, `_agg1`, ... and found each by
  that name, so a column of the input named `_agg0` was taken for the first
  (`SUM(x)` over `GROUP BY _agg0` returned the key), and `HAVING _agg0 > 1` read
  the hidden SUM. Slots are addressed by position now, the names avoid every
  GROUP BY key's, and a column in an aggregate query resolves in the input.
- **A GROUP BY key matched by its text misses its other spellings.** Matched
  as a whole SELECT item by `canonicalSql`, a key written inside a larger
  expression (`UPPER(s) || '!'` over `GROUP BY UPPER(s)`, `HAVING a % 2 = 1`),
  with its table (`t.a + 1` over `a + 1`), or beside another key in
  parentheses (which JSqlParser keeps only then) was refused. The binder now
  asks at every node whether the bound expression is a key.
- **An operator that reads an expression twice must not be given one that
  reads `RAND()`.** An exchange read a GROUP BY or PARTITION BY key to route a
  row and the breaker read it again in the shard; grace-hash and window spill
  read a key to choose a bucket and again inside it; a simple CASE read its
  operand once per WHEN and BETWEEN read its operand twice. `Expr.readsRandom`
  keeps such keys off those paths, and `SwitchCaseExpr` / `BetweenExpr` read a
  RAND() operand once.
- **Java counts UTF-16 chars, SQL counts characters.** `String.length`,
  `substring` and `trim` (which also strips every char up to U+0020) are not
  SQL's LENGTH, SUBSTRING and TRIM; half of a surrogate pair is no text, and a
  spill or a parquet or CSV file writes it as `?`; `Pattern.quote` of half a
  pair matches nothing, so a LIKE pattern holding an emoji matched no string.
  String ORDERING is still UTF-16 (see the parquet pushdown entry above).
- **JSqlParser reads `{d '...'}` through `java.sql.Date.valueOf`, which rolls
  `2026-02-30` over to March.** `SqlParser.escapeLiteralText` reads the
  literal's token text, which goes through the same strict CAST `DATE '...'`
  does.
- **A guard that decides on estimates states guesses as facts.** The nested-loop
  guard refused two one-row filters over 60,000-row tables as joining "6000
  rows to 6000"; its first rule now reads proven counts only. A comma join's
  WHERE equality stayed above the join, so the join was a nested loop the
  guard refused, asking for an ON the comma cannot take.
- **The spill sweep deleted by name prefix beside its own directory.** It
  removed every entry of `java.io.tmpdir` starting `transformer` but its root,
  the jaffle example's output directory among them, and each JVM's exit
  deleted the shared root under any other JVM spilling into it.

## Defects the SQL logic tests found (2026-11-01, all fixed)

The sqllogictest runner (`slt/slt_test`, [testing.md](testing.md#sql-logic-tests))
replays every query of a script under its verification modes (eleven when
these were found, thirteen since plan 05 added the printed plan), and the
[whole-suite configurations](testing.md#whole-suite-configurations) rerun every
test at a batch capacity of 1 and 2 rows, sharded, on one thread and in a
hostile time zone and locale. SQLite's sqllogictest corpus, whose answers five
engines agreed on, returned no wrong answer; the defects were in spill, CSV
globs, the parser, the nested-loop guard and the desktop GUI. Details are in
[plans/testing/04](../plans/testing/04-sql-logic-tests-and-whole-suite-configurations.md).

- **An aggregate with no GROUP BY keys spilled.** Its partial aggregate checked
  the spill threshold like a grouped one, so it wrote its one state to disk and
  read it back, and with no aggregates either (`SELECT 1 FROM t GROUP BY ()`)
  that state had no columns, which parquet cannot hold: every spill failed with
  `Cannot write a schema with an empty group`. Such an aggregate never spills
  now. Found by `slt_test`'s spill mode.
- **CSV inference read the glob's first file alone.** A first file holding a
  header and no rows, which is what a writer leaves for an output partition
  that held none (a sharded GROUP BY of two groups across four shards writes
  two), typed every column STRING, so the next job's `SUM(customers)` was
  refused and an `ORDER BY` of the column would have sorted numbers as text; a
  two-row first file decided every later file's types. The sample is the first
  rows of the files in glob order (the user's choice). Found by the `sharded`
  configuration, through the data viewer's three-job fixture.
- **A later CSV file's header was dropped unchecked**, and its fields read in
  the first file's order, so a file that wrote its columns in another order
  read each value into the other column, silently when the two had one type.
  Each file is read by its own header's names, as parquet globs are, and one
  naming other columns fails (the user's choice).
- **An empty IN list was accepted.** JSqlParser reads `x IN ()`, which standard
  SQL and Postgres refuse, and it ran: `1 NOT IN ()` TRUE but `NULL NOT IN ()`
  NULL, though a list of nothing holds neither. It is refused (the user's
  choice). Found by SQLite's corpus, which allows the empty list.
- **JSqlParser 5.0 could not parse four parentheses deep inside a call**
  (`COALESCE((((a > 1))), FALSE)`), which generated SQL writes; 5.2 can (the
  user's choice to upgrade). It also parses TRUE and FALSE as literals
  everywhere, reads the NOT of a one-argument call's argument as SQL does
  (`COUNT_IF(NOT f > TRUE)`, which 5.0 misread and the binder refused), and
  adds clauses the rejection pass refuses (`PREFERRING`, `TABLESAMPLE` after a
  query, `AS MATERIALIZED`, a CTE that changes a table, `ON OVERFLOW
  TRUNCATE`, `TABLE` before an argument, `IS UNKNOWN`). Not 5.3: it parses
  deep CAST and IF nesting a hundred times faster, but refuses seventeen
  nested parentheses around a value, which 5.2 reads at any depth the
  engine allows.
- **No JSqlParser version parses two signs in a row** (`SELECT - -3`,
  `20 / - - 96`), and it said `Encountered unexpected token: "SELECT"`. The
  refusal now names the signs and says to write `-(-3)`. A sign after an
  operator's, `5 - -3`, is one sign and was always read.
- **The nested-loop guard's bound stopped at a join on an equality.** The
  guard sized such a join with the cardinality estimator, which sizes a
  pairing below it as the pairing's larger side, so `FROM a, b, c, d WHERE
  a.k = c.k` over four 1000-row tables passed: `a` with `b` pairs a million
  rows, the join with `c` keeps them, and `d` makes a billion. A join on an
  equality is sized from its sides' bounds now. Found by SQLite's
  `select5.test`, joins of 4 to 64 ten-row tables each written in three
  orders: 308 of its 732 queries were let through to pair more than 25
  million rows, up to 10^23, and the file never finished; 521 are refused
  now, none of the rest pairs more than ten million, and the file runs in two
  and a half minutes, though two joins of 21 and 25 tables still run a 2 GB
  heap out at ten million wide pairs (the collapsing path held them all,
  which the pairing path no longer does).
  Plan 05 then ordered joins by their equalities (`JoinOrder`), and all 732
  run, in under six seconds.
- **The desktop GUI folded case in the JVM's locale.** Under a Turkish one,
  where `I` lowers to a dotless i, the SQL highlighter read `PARTITION` and
  `INT` as `partıtıon` and `ınt`, no keyword and no type; the task panel and
  the DAG matched an input's name against the job's lowered names in it, so an
  input named with an I showed no task using it; vim's Ctrl-I arrived as
  Ctrl-ı; and the block caret's CSS printed `1,50px`, which CSS cannot read.
  Every case fold names `Locale.ROOT` and the CSS is formatted in it, and
  `job/locale_independence_test` scans the GUI's and the viewer's jars for case
  folding. Found by `--config=zone`, through the highlighter's test, with two
  tests that folded or printed a parquet type name in the default locale.

## Defects the plan round trip found (2026-11-01, all fixed)

Printing every plan as SQL and binding it again (`PlanSql`, `PlanRoundTrip`,
[architecture.md](architecture.md#plans-as-sql)) holds the binder to its own
output, over every slt script and every query generator. Details are in
[plans/testing/05](../plans/testing/05-plans-as-sql.md).

- **The binder kept an aggregate per spelling.** It keyed each aggregate and
  window function by its text, so `COUNT()` and `COUNT(*)`, `SUM(a)` and
  `SUM(t.a)`, or a window written twice with and without a table, were a slot
  each and computed twice. The answers were right, but the printer writes one
  spelling of each, so the printed query bound to a plan with one slot fewer.
  Each is now computed once, its spellings reading one slot by their text.
  Found by `plan_roundtrip_fuzz_campaign` on a frontend mutation, `SELECT s,
  COUNT() FROM t GROUP BY s ORDER BY COUNT(*), s` (`COUNT()` is now refused,
  below).

## Answers the PostgreSQL port settled (2026-11-01, all decided)

Porting Postgres's regression tests (`src/test/slt/postgres/`, with
Postgres's answers) found five answers that no decision covered, four in the
first four files and one in float8.sql. Their records were held out of the
scripts until each was decided; they are in the scripts now, a logarithm's
and an underflow's as `skipif transformer` with the id of its rule in the
[ledger below](#answers-that-differ-from-other-engines-by-design). Details are
in [plans/testing/05](../plans/testing/05-plans-as-sql.md).

- **`COUNT()` with no argument was `COUNT(*)`**, as SQLite and DuckDB read
  it, on purpose in `LogicalBuilder` and in no doc. Postgres refuses it, and so
  does the engine now, as an aggregate and in a window, naming `COUNT(*)` and
  `COUNT(x)`.
- **A ROWS frame offset past INT was refused**, where a RANGE offset of any
  size ran. A ROWS offset is now any `BIGINT`, and `WindowExec.rowsBound`
  stops a row's position plus its `FOLLOWING` offset at `Long.MaxValue`, past
  which the sum wrapped to a frame that ended before its row.
- **A square root or a logarithm outside its domain was `NaN`**, or
  `-Infinity` at zero: `SQRT` of less than zero, `LN`, `LOG10` and `LOG2` of
  zero or less, and `LOG` to a base of zero or less or of 1, which Postgres
  refuses. Each is now NULL, as a division by zero is; `NaN` stays `NaN`.
  After the round the rule took in every math function Postgres refuses an
  argument of: `ASIN` and `ACOS` past -1 or 1, `SIN`, `COS` and `TAN` of an
  infinity, and `POWER` of zero to a negative power and of a negative number
  to a finite power that is not whole (Java's `NaN` or infinity). `POWER` is
  otherwise C's `pow`: 1 to any power, and -1 to an infinite one, is 1, as
  Postgres, DuckDB and SQLite answer, where Java's `pow` gave `NaN`.
- **One-argument `LOG(x)` was the natural logarithm**, as in MySQL, Spark and
  SQL Server, where Postgres, SQLite and DuckDB read base 10. It is now
  refused, naming `LN(x)`, `LOG10(x)` and `LOG(base, x)`.
- **A `DOUBLE` (or `FLOAT`) result too small to hold was 0, and still is**:
  `EXP(-1000.0)`, `POWER(0.5, 2000.0)` and `1e-200 * 1e-200` are 0.0, -0.0
  when negative, where Postgres raises `value out of range: underflow`
  (float8.sql:188 and 196). No rule said so. It stays IEEE's answer, as in
  Java, Spark, DuckDB, SQLite and MySQL: the counterpart of a result past the
  range being an infinity (`1e308 * 10`), so neither end of the range is an
  error. A subnormal result is kept, as in Postgres, and text or a literal
  too small for a `DOUBLE` (`'1e-400'`, `1e-400`) is still refused, as is a
  narrowing `CAST` to `FLOAT` that would be 0. `types/float_equality.test`
  pins it.

## Answers that differ from other engines by design

The engine answers some queries otherwise than another engine does, on
purpose: it refuses a construct the other engine runs, or answers by a rule of
its own. Each such rule is a row of the table below, the ledger, and has an id
that the [SQL logic tests](testing.md#sql-logic-tests) cite. A record of
another engine's answer that the engine does not give is `skipif transformer
<id>` in a script, and `slt_test` still runs it, once, and fails when it no
longer diverges as its rule says, so a skip cannot outlive its reason. A rule
whose id starts `refused-` is a construct the engine refuses, and `refusal`
is the text its refusal must contain. Under any other rule a skipped record is
followed by its twin, `onlyif transformer <id>` before the same SQL with the
engine's own answer. `pinned by` names the script of this repo's own that
holds a record of the engine's answer under the rule, `onlyif transformer
<id>` before it.

The table is the only list of these rules. The build reads it
(`src/test/scala/com/transformer/slt/Ledger.scala`), so its columns are fixed
and a row it cannot read fails the tests, naming the line; a `|` in a cell is
written `\|`. A new rule gets a row and a pin in the commit that decides it.
In `decided in`, "the user" marks a rule the user chose, "Claude, reported" one
Claude chose by the same principles and reported to the user, who did not
choose it, and "since v1" one the engine has had from the start.

| id | rule | refusal | stated in | decided in | pinned by |
|---|---|---|---|---|---|
| `refused-subquery` | A subquery is refused, in `IN` or `EXISTS`, in `FROM` or as a value: a `WITH` names it instead. | `subquery` | [README](../README.md#sql-features), "Subqueries"; [not done](#whats-intentionally-not-done), "No subqueries" | Not implemented, by design since v1 ([robustness index](../plans/robustness/README.md), "deliberately NOT planned"); each refusal named by [robustness/10](../plans/robustness/10-error-messages-name-internals.md), 2026-10-17 | `refused/unsupported.test` |
| `refused-except-intersect` | `EXCEPT` and `INTERSECT` are refused: only `UNION` and `UNION ALL` combine queries. | `only UNION and UNION ALL are supported` | [README](../README.md#sql-features), "SQL outside this grammar is rejected, not ignored" | Not implemented, by design since v1 ([robustness index](../plans/robustness/README.md), "deliberately NOT planned"); the refusal named by [robustness/10](../plans/robustness/10-error-messages-name-internals.md), 2026-10-17 | `refused/unsupported.test` |
| `refused-text-arithmetic` | Arithmetic is on numbers only, so a `STRING` operand is refused, where SQLite reads text as a number. | `Arithmetic on STRING is not supported` | [README](../README.md#sql-features), "Values of different types meet only as numbers, or through a string literal" | The user, 2026-10-03: no text is read as a number unasked (584995f) | `refused/messages.test` |
| `refused-empty-in` | An `IN` or `NOT IN` list holds at least one value, as in standard SQL, so SQLite's `x IN ()` is refused. | `needs at least one value in its list` | [README](../README.md#sql-features), "Operators" | The user, 2026-11-01 ([testing/04](../plans/testing/04-sql-logic-tests-and-whole-suite-configurations.md)) | `refused/messages.test` |
| `refused-hex-literal` | A hexadecimal literal, `x'303132'`, is refused. | `A hexadecimal literal is not supported` | [not done](#whats-intentionally-not-done), "No SQL beyond the README's grammar", whose `UnsupportedClauses.scala` lists it | Outside the grammar, by design ([robustness/01](../plans/robustness/01-reject-or-honor-every-clause.md), 2026-10-01); the refusal named by [robustness/10](../plans/robustness/10-error-messages-name-internals.md), 2026-10-17 | `refused/messages.test` |
| `refused-parenthesized-join` | A join written in parentheses in `FROM` is refused: the joins are written without them. | `A parenthesized join in FROM is not supported` | [not done](#whats-intentionally-not-done), "No SQL beyond the README's grammar", whose `UnsupportedClauses.scala` lists it | Outside the grammar, by design ([robustness/01](../plans/robustness/01-reject-or-honor-every-clause.md), 2026-10-01); the refusal named by [robustness/10](../plans/robustness/10-error-messages-name-internals.md), 2026-10-17 | `refused/messages.test` |
| `div-zero-null` | A division or `%` by zero is NULL, for every number type, where Postgres raises an error. |  | [README](../README.md#sql-features), "`INT` and `BIGINT` arithmetic is exact, or an error" and "`DECIMAL` arithmetic is exact" | Integers since v1; `DECIMAL` by the user, 2026-10-04 ([robustness/04](../plans/robustness/04-widen-fuzz-corpus-to-every-type.md)); `FLOAT` and `DOUBLE` by the user, 2026-10-31 ([robustness/30](../plans/robustness/30-third-audit.md)) | `types/float_equality.test` |
| `math-domain-null` | A math function of a number outside its domain is NULL, as a division by zero is: a square root of less than zero, a logarithm of zero or less or to a base of zero or less or of 1, `ASIN` or `ACOS` past 1, the sine, cosine or tangent of an infinity, zero to a negative power, and a negative number to a finite power that is not whole. |  | [README](../README.md#sql-features), "Scalar functions"; [the PostgreSQL port](#answers-the-postgresql-port-settled-2026-11-01-all-decided) | Square roots and logarithms by the user, 2026-11-01 ([testing/05](../plans/testing/05-plans-as-sql.md), W9); the rest by Claude the same day, reported (28a51a5) | `types/float_equality.test` |
| `cast-truncates` | A `CAST` of a number to `INT` or `BIGINT` truncates its fraction toward zero, where Postgres rounds. |  | [README](../README.md#sql-features), "Types in CAST" | Kept and documented with the overflow rule, by the user, 2026-10-31 ([robustness/18](../plans/robustness/18-integer-overflow-wraps.md)) | `types/integer_overflow.test` |
| `bool-text` | Text casts to `BOOLEAN` from `true` and `false` alone, in any case and with no spaces around them, where Postgres also reads `t`, `yes`, `on`, `1` and the rest. |  | [README](../README.md#sql-features), "Types in CAST" | The user, 2026-10-03: a CAST reads its text strictly ([robustness/05](../plans/robustness/05-frontend-robustness-fuzzer.md)) | `types/casts.test` |
| `double-text` | Text casts to `FLOAT` or `DOUBLE` by CSV's number grammar: `NaN`, `Infinity` and `-Infinity` as written, and no spaces around a number. |  | [README](../README.md#sql-features), "Types in CAST" | Claude, reported, 2026-10-31 ([robustness/24](../plans/robustness/24-cast-reads-javas-number-grammar.md)), on the grammar the user chose for CSV, 2026-10-14 ([robustness/06](../plans/robustness/06-io-roundtrip-and-external-fixtures.md)) | `types/casts.test` |
| `double-range` | A `FLOAT` or `DOUBLE` result past the type's range is an infinity, and one too small for it 0, not an error. |  | [README](../README.md#sql-features), "`INT` and `BIGINT` arithmetic is exact, or an error"; [the PostgreSQL port](#answers-the-postgresql-port-settled-2026-11-01-all-decided) | Past the range by the user, 2026-10-31 ([robustness/30](../plans/robustness/30-third-audit.md)); too small by the user, 2026-11-01 ([testing/05](../plans/testing/05-plans-as-sql.md)) | `types/float_equality.test` |
| `double-literal` | A number written with a point or an exponent is a `DOUBLE`, and beside a `DECIMAL` the decimal it is written as, where Postgres reads a numeric. |  | [README](../README.md#sql-features), "Literals and names mean what SQL writes" | Since v1; the decimal beside a `DECIMAL` by the user, 2026-10-30 ([robustness/13](../plans/robustness/13-literals-are-read-as-written.md)) | `types/decimal.test` |
| `double-precision` | The math functions and the statistics compute in `DOUBLE`, over a `DECIMAL` too, and a `DOUBLE` holds 15 to 17 significant digits, where Postgres computes them in numeric. |  | [README](../README.md#limitations), "`DECIMAL` math functions and statistics compute in `DOUBLE`"; [not done](#whats-intentionally-not-done) | Since v1, and left so by [robustness/19](../plans/robustness/19-rounding-through-double.md), 2026-10-31, which made the rounding functions exact | `types/decimal.test` |
| `decimal-result-type` | `DECIMAL` arithmetic takes Spark's and SQL Server's result types, so a quotient has `max(6, s1 + p2 + 1)` places, where Postgres keeps as many as each value needs. |  | [README](../README.md#sql-features), "`DECIMAL` arithmetic is exact, and declares a type that holds its result" | The user, 2026-10-04 ([robustness/04](../plans/robustness/04-widen-fuzz-corpus-to-every-type.md)) | `types/decimal.test` |
| `window-constant-key` | A window's `ORDER BY` or `PARTITION BY` key that reads no column is refused, as such a key of a query's `ORDER BY` or `GROUP BY` is. |  | [README](../README.md#sql-features), "A key that reads no column orders, groups and partitions nothing" | Claude, reported, 2026-10-31 ([robustness/23](../plans/robustness/23-clauses-that-cannot-change-the-answer.md)) | `refused/messages.test` |
| `nulls-sort-low` | A NULL sorts as the smallest value, first under `ASC` and last under `DESC`, where Postgres, DataFusion and CockroachDB sort it last under `ASC`. |  | [README](../README.md#sql-features), "A NULL sorts as the smallest value unless the key says otherwise" | Since v1, kept when `NULLS FIRST` and `NULLS LAST` landed ([robustness/01](../plans/robustness/01-reject-or-honor-every-clause.md), 2026-10-01) | `select/order_by.test` |
| `int-is-int` | A whole number an `INT` holds is an `INT`, and `INT` arithmetic past its range is an error, where SQLite's integers are 64-bit. |  | [README](../README.md#sql-features), "Literals and names mean what SQL writes" and "`INT` and `BIGINT` arithmetic is exact, or an error" | Since v1; an integer past `BIGINT` a `DECIMAL` by the user, 2026-10-30 ([robustness/13](../plans/robustness/13-literals-are-read-as-written.md)), and overflow an error by the user, 2026-10-31 ([robustness/18](../plans/robustness/18-integer-overflow-wraps.md)) | `types/integer_overflow.test` |
| `branch-double` | The values that become one result take a type that holds each of them, so a `CASE` or `COALESCE` with a `DOUBLE` branch is a `DOUBLE`, where SQLite keeps each value's own type. |  | [README](../README.md#sql-features), "Values that become one result take a type that holds each of them" | The user, 2026-10-16 ([robustness/09](../plans/robustness/09-branch-result-types.md)) | `conditional/case.test` |

## What's intentionally NOT done

- **Join order has no cost model.** `JoinOrder` keeps the written order but
  where the next table has no equality with those joined so far, so it never
  weighs one keyed order against another by the tables' sizes, starts from
  the first written table even when another would make a smaller first join,
  and does not move a table that nothing connects to (`FROM a, b, c WHERE b.k
  = c.k` still pairs `a` with `b` first). Reordering changes which rows a
  condition is evaluated on, never the answer, so a condition that fails on a
  row (an integer overflow, a CAST of text that is no number) may fail in one
  order and not in another.
- **Spill-to-disk** is implemented for `SortExec`, `HashAggregateExec`,
  `DistinctExec`, `HashJoinExec` (grace hash, equi-join only), and
  `WindowExec` (bucketed by PARTITION BY) but **disabled by default**.
  Opt in per-task via `output.json`'s `options.spill = "true"` (with
  optional `spill_threshold_bytes` and `spill_max_runs`). See
  `plans/perf/09-spill-to-disk.md` for the full design. Caveats:
  - Non-equi joins (cartesian-shaped) ignore the spill option — they're
    rejected above [[PhysicalPlanner.NestedLoopMaxRows]] anyway.
  - DISTINCT aggregates (`COUNT`, `SUM` and `AVG(DISTINCT …)`) fall back to
    the in-memory path even when spill is enabled — the underlying HashSet
    state doesn't round-trip through a typed schema in v1, so a query mixing
    one with other aggregates silently disables spill for that operator.
    Document the heap limit if a workload is hitting it.
  - `WindowExec` spill requires a non-empty PARTITION BY that's
    consistent across every window spec in the projection. Empty
    PARTITION BY (whole-result window) or mixed PARTITION BYs silently
    fall back to the in-memory path — there's no key set to bucket by.
  - Grace hash and window spill both use a fixed bucket count of 16
    (no recursive bucketing). Hot keys whose bucket exceeds heap will
    still OOM at probe/process time; that's a v1.1 issue.
- **A `DECIMAL` is exact in arithmetic, `SUM`, `AVG`, `ABS` and rounding, but
  not in the other math functions or the statistics.** `ROUND`, `TRUNC`,
  `FLOOR` and `CEIL` are exact over a `DECIMAL` and an integer and keep the
  type (`Rounding` in `Funcs.scala`, plans/robustness/19; they computed in
  `DOUBLE`, so `ROUND` of a `DECIMAL` 1.005 to two places was 1.0). `SQRT`,
  `POWER` and the rest, and `STDDEV` / `VARIANCE`, take a decimal's
  `doubleValue` and return a `DOUBLE`, so their type says what they kept. There
  is no `CAST` to a `DECIMAL` either (`parseTypeName` reads no `DECIMAL(p,s)`),
  so a query cannot choose a decimal result's type; `Decimals.resultType`
  chooses it.
- **No whole-stage codegen**, no Janino, no LLVM. The closest analogue is
  `Expr.evalVec` (see [architecture §5a](architecture.md#5a-vectorized-expression-evaluation-evalvec))
  — one call per Expr per batch, primitive-array inner loops, no codegen step.
  `ProjectExec` and `FilterExec` use it; everything else (sort/join/window
  per-row callbacks, aggregate state updates over `RowBuf`) still uses boxed
  `eval(batch, row)` because those are 1-row paths where vectorization gives
  nothing back.
- **No multi-statement SQL.** `SqlParser.parse` takes one statement: a trailing
  `;` is fine, and anything after it is refused (`Only one statement is
  supported; remove the text after ';'`), in a task's SQL when the job builds
  its DAG. `SqlParser.parseSelect` then requires that statement to be a SELECT.
- **CTEs (`WITH`) are supported and non-recursive; multiply-referenced ones are
  materialized.** `LogicalBuilder` emits a placeholder `PendingCteView` scan per
  outermost-scope CTE and `CteResolver` (a pass between build and optimize)
  resolves each reference: a CTE referenced **&ge; 2 times** is executed once
  into an in-memory `MaterializedView` that every reference scans (compute-once
  + an optimization fence for non-deterministic bodies); a CTE referenced
  **&le; 1 time** is inlined, exactly as before (a declared-but-unused CTE is
  never executed). The optimizer, physical planner, and pushdowns are untouched
  — they only ever see an inlined body subtree or a `LogicalScan` over a
  `MaterializedView`. Caveats: materialization **buffers the whole body in heap**
  (no spill yet — see [plan 09](../plans/perf/09-spill-to-disk.md)); only
  **outermost-scope** CTEs are materialization candidates, so a CTE declared
  *inside* another CTE body is always inlined regardless of its reference count;
  and there is still no result caching *across* SQL statements (use `viewName`
  chaining for that). `WITH RECURSIVE` is rejected with a clear error. See
  [architecture §6b](architecture.md#6b-cte-resolution-inline-vs-materialize).
- **No subqueries** (scalar, IN, EXISTS, derived tables in FROM). A derived
  table in FROM still throws from `LogicalBuilder.fromItem`; reach for a `WITH`
  CTE where the shape allows.
- **No SQL beyond the README's grammar.** `QUALIFY`, `GROUPING SETS`,
  `FILTER (WHERE ...)`, `PIVOT`, `USING` joins and the rest are rejected with an
  error naming the clause, never ignored; `sql/plan/UnsupportedClauses.scala`
  lists every one with its reason.
- **Window frames: ROWS and RANGE, no GROUPS or EXCLUDE.** A RANGE offset
  needs one numeric ORDER BY key (a DATE would need an INTERVAL). Supported
  window functions: ROW_NUMBER, RANK,
  DENSE_RANK, LAG, LEAD, plus aggregates SUM/AVG/MIN/MAX/COUNT(*)/COUNT(expr)/COUNT_IF(pred)
  and the univariate stats STDDEV/STDDEV_SAMP/STDDEV_POP/VARIANCE/VAR_SAMP/VAR_POP.
  No FIRST_VALUE/LAST_VALUE/NTH_VALUE/PERCENT_RANK/CUME_DIST/NTILE yet, and
  COVAR_SAMP/COVAR_POP/CORR are GROUP BY only (no OVER form yet — JSqlParser's
  `AnalyticExpression` exposes a single argument slot).
- **No dynamic column-value partitioning.** The multi-file output we *do*
  support is along the executor's partition axis (file-per-input-file for
  CSV; file-per-row-group for Parquet), capped by `OutputFilePath.maxPartitions`.
  Path-template partitioning per task IS supported (`DirectoryJobLoader`'s
  per-table `output.json` `partitionBy` field, or the user templating the
  path themselves) — but the partition value is fixed for the whole job
  (it's the run's executionTime), not bucketed per row by a column value
  like Spark's `partitionBy("col")`.
- **No INFORMATION_SCHEMA / catalog introspection.** Views are
  programmatically registered via `DataJob.inputs`. `transformer query` lists
  its tables and their columns with `.tables` and `.schema`, not in SQL; no
  `DESCRIBE` or `SHOW TABLES`.
- **No file paths in SQL.** `transformer query` reads a file only under the name
  `--table` (or `.table`) gives it; `FROM 'data.csv'` and `read_csv(...)`, which
  JSqlParser parses as DuckDB writes them, stay refused, in a job and in the
  CLI alike, so a job's DAG and lineage name every file it reads. The CLI's own
  statements (`CREATE VIEW`, `DROP VIEW`, `COPY ... TO`) wrap a query rather
  than extend the dialect: there is no `COPY ... FROM`, `CREATE TABLE`, or
  `INSERT`, and `COPY` writes one file, not a directory of part files with a
  `_run.json`.
- **No bytecode-level optimizations.** Hot loops use `while` and indexed
  arrays. That's enough.
- **Heap is not fully exercised under the default config.** Each in-flight
  parquet writer pins ~32MB row group buffer + a few MB of column dictionaries;
  with the default fan-out of `min(cores, heap/256MB)` only ~`cores × 50MB` of
  heap is in flight at any moment. For huge-heap boxes you can bump the row
  group cap via `options("parquet_row_group_size")` (per-task in `output.json`),
  but there's no automatic "use all the heap" mode yet.
