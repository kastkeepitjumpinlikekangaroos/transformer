package com.transformer.bytecode

/** The engine's one-rule-per-concept decisions that a compiled class can
  * show, as data. Each rule names what it bans, in which modules, the advice
  * its failure prints, and the methods allowed to do it anyway, each with its
  * reason. [[BytecodeRulesTest]] holds every rule over the compiled jars: a
  * banned use no entry allows fails it, and so does an entry that allows
  * nothing any more, so an exception cannot outlive its reason.
  *
  * A rule holds calls, not meaning: a banned call made through a helper in a
  * module the rule does not read is invisible to it. The behavioural guards of
  * the same rules (`fuzz/oracle/KeyEquality`, `--config=hashzero`,
  * `--config=zone`) stay. */
object BytecodeRules {

  /** A module's jar, found on the test's class path by a class file it holds. */
  final case class Module(target: String, classFile: String)

  /** What a rule bans, as its failure names it, and what to write instead
    * when one call replaces it. */
  final case class Ban(what: String, matches: Use => Boolean, instead: String = "")

  /** A method allowed to do what a rule bans, and why. `cls` is the binary
    * name (`com.transformer.core.Ops$` for an object's methods); `method` is
    * the method as written, and covers the lambdas written in it
    * (`$anonfun$<method>$1`), its local defs (`<method>$1`), its default
    * arguments (`<method>$default$1`) and its specialized copies
    * (`<method>$mcD$sp`). `*` allows the whole class: every method of it does
    * what the rule bans for the one reason. */
  final case class Allow(cls: String, method: String, reason: String)

  /** A rule. `allowed` are its decided exceptions. `held` are methods it
    * found that no decision covers: each is allowed until the user decides,
    * and says why it is held. */
  final case class Rule(
      id: String,
      title: String,
      scope: Seq[Module],
      bans: Seq[Ban],
      advice: String,
      allowed: Seq[Allow] = Nil,
      held: Seq[Allow] = Nil) {

    def entries: Seq[Allow] = allowed ++ held
  }

  // ---------------------------------------------------------------------------
  // The modules
  // ---------------------------------------------------------------------------

  private def module(target: String, classFile: String): Module =
    Module(s"//src/main/scala/com/transformer/$target", s"com/transformer/$classFile.class")

  /** The engine: what reads, plans, runs and writes a query and a job. */
  val Engine: Seq[Module] = Seq(
    module("core", "core/Catalog"),
    module("sql/parse", "sql/parse/SqlParser"),
    module("sql/plan", "sql/plan/LogicalBuilder"),
    module("sql/exec", "sql/exec/SqlEngine"),
    module("read/csv", "read/csv/CsvReader"),
    module("read/parquet", "read/parquet/ParquetReader"),
    module("write/csv", "write/csv/CsvWriter"),
    module("write/parquet", "write/parquet/ParquetWriter"),
    module("job", "job/DataJob"),
    module("temporal", "temporal/TemplateRenderer"))

  /** Every module a job, the CLI or a reopened run executes. */
  val JobModules: Seq[Module] = Engine ++ Seq(
    module("cli", "cli/Cli"),
    module("gui:session", "gui/JobSession"))

  /** The desktop GUI's and the data viewer's modules. */
  val Desktop: Seq[Module] = Seq(
    module("gui:vim", "gui/VimKey"),
    module("gui:chart", "gui/ChartData"),
    module("gui:components", "gui/SqlHighlighter"),
    module("gui", "gui/TaskDetailsPanel"),
    module("viewer:model", "viewer/ViewerSession"),
    module("viewer", "viewer/ViewerApp"))

  val AllModules: Seq[Module] = JobModules ++ Desktop

  // ---------------------------------------------------------------------------
  // What a rule can ban
  // ---------------------------------------------------------------------------

  /** A call of, or a field access to, `owner.name`: with `descriptor` when
    * one is given, with any descriptor when not. */
  def member(owner: String, name: String, descriptor: String = "", instead: String = ""): Ban =
    Ban(s"$owner.$name$descriptor", {
      case Use.Reference(r, _) => r.owner == owner && r.name == name && (descriptor.isEmpty || r.descriptor == descriptor)
      case _ => false
    }, instead)

  /** Each of `names` on `owner`, any descriptor. */
  def members(owner: String, names: String*): Seq[Ban] = names.map(member(owner, _))

  /** Each of `names` on `owner`, any descriptor, with what to write instead. */
  def membersInstead(owner: String, instead: String, names: String*): Seq[Ban] =
    names.map(member(owner, _, instead = instead))

  /** Any member of `owner`, its constructors too. */
  def anyMember(owner: String, instead: String = ""): Ban =
    Ban(s"$owner (any member)", {
      case Use.Reference(r, _) => r.owner == owner
      case _ => false
    }, instead)

  /** A member named `name` of any owner: an instance method is referenced
    * through its receiver's static type (`java/io/IOException.printStackTrace`). */
  def anyOwner(name: String, instead: String = ""): Ban =
    Ban(s"*.$name", {
      case Use.Reference(r, _) => r.name == name
      case _ => false
    }, instead)

  /** Two floats or doubles compared raw (`fcmpl`, `fcmpg`, `dcmpl`, `dcmpg`),
    * neither side a constant: `a < b`, `a == b` between two values. One side
    * a constant (`x > 0.0`, `x == 3`) is not this. */
  val RawFloatComparison: Ban = Ban("a raw <, <=, >, >=, == or != between two floats or doubles", {
    case Use.FloatComparison(_, false, _) => true
    case _ => false
  }, instead = "Ops.floatCompare(a, b)")

  // ---------------------------------------------------------------------------
  // The rules
  // ---------------------------------------------------------------------------

  /** The default-locale case folds. */
  private val DefaultLocaleFolds: Seq[Ban] = Seq(
    member("java/lang/String", "toLowerCase", "()Ljava/lang/String;", instead = "toLowerCase(Locale.ROOT)"),
    member("java/lang/String", "toUpperCase", "()Ljava/lang/String;", instead = "toUpperCase(Locale.ROOT)"))

  val R1Locale: Rule = Rule("R1", "locale: case folds and formats in the modules a job runs", JobModules, DefaultLocaleFolds ++ Seq(
    member("java/lang/String", "format", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;",
      instead = "String.format(Locale.ROOT, ...)"),
    member("java/lang/String", "formatted", "([Ljava/lang/Object;)Ljava/lang/String;",
      instead = "String.format(Locale.ROOT, ...)"),
    member("scala/collection/StringOps$", "format$extension",
      "(Ljava/lang/String;Lscala/collection/immutable/Seq;)Ljava/lang/String;",
      instead = "\"...\".formatLocal(Locale.ROOT, ...) (an f\"...\" interpolator formats in the default locale)"),
    member("java/time/format/DateTimeFormatter", "ofPattern", "(Ljava/lang/String;)Ljava/time/format/DateTimeFormatter;",
      instead = "DateTimeFormatter.ofPattern(pattern, Locale.ROOT)"),
    member("java/text/NumberFormat", "getInstance", "()Ljava/text/NumberFormat;", instead = "an explicit Locale"),
    member("java/text/NumberFormat", "getNumberInstance", "()Ljava/text/NumberFormat;", instead = "an explicit Locale")),
    advice = "A module a job runs folds case and formats in Locale.ROOT, never the JVM's default locale: under tr-TR " +
      "\"I\".toLowerCase() is a dotless i, so a function, a view or a CAST type named with an I stops matching, and " +
      "some locales print digits that are not ASCII (docs/conventions.md, \"Case folding and formatting name " +
      "Locale.ROOT\").")

  val R1DesktopFolds: Rule = Rule("R1 (desktop)", "locale: case folds in the desktop GUI and the data viewer", Desktop,
    DefaultLocaleFolds,
    advice = "The desktop GUI and the data viewer fold the names, keywords and keys they match in Locale.ROOT, as a job " +
      "does: under tr-TR the SQL highlighter read PARTITION as no keyword. Their display text may format a number " +
      "in the user's locale (plans/testing/04, decision 6).")

  val R2FloatOrder: Rule = Rule("R2", "float ordering and equality", Engine,
    membersInstead("java/lang/Double", "Ops.floatCompare (a double slot) or Ops.cmp (boxed)", "compare", "compareTo",
      "equals") ++
      membersInstead("java/lang/Float", "Ops.floatCompare (a float slot) or Ops.cmp (boxed)", "compare", "compareTo",
        "equals") :+
      RawFloatComparison,
    advice = "Anything that orders or matches two values compares through the one total order: Ops.cmp boxed, " +
      "Ops.floatCompare on a primitive float or double slot, where NaN equals NaN and sorts above every number and " +
      "-0.0 equals 0.0. A raw < or == is false both ways on NaN, so the answer moves with the order rows arrive " +
      "in, and java.lang.Double.compare puts -0.0 below 0.0; MinMaxState's accumulator, evalVec's comparisons and " +
      "WindowExec.sameKey each broke the rule so (docs/conventions.md, \"Anything that ORDERS values\"). A " +
      "comparison with a constant (x > 0.0) is not banned.",
    allowed = Seq(
      Allow("com.transformer.sql.plan.Ops$", "floatCompare",
        "the one total order on a primitive float or double, the comparison every other site makes"),
      Allow("com.transformer.sql.exec.MinMaxState", "updateAt",
        "decided: of two zeros Ops.cmp calls equal, Double.compare orders -0.0 below 0.0, so MIN and MAX of " +
          "equal zeros do not move with row order (docs/conventions.md)"),
      Allow("com.transformer.sql.exec.MinMaxState", "updateBatchAt", "the same tie-break, a batch at a time"),
      Allow("com.transformer.sql.exec.MinMaxState", "merge", "the same tie-break, merging two partitions' states"),
      Allow("com.transformer.sql.plan.Funcs$", "pickByOrdering",
        "decided: GREATEST and LEAST break a tie between two zeros as MAX and MIN do, by Double.compare and " +
          "Float.compare, once Ops.cmp has called them equal"),
      Allow("com.transformer.sql.plan.Funcs$", "apply",
        "POWER's domain test, math.floor(y) != y: whether an exponent is whole, comparing no two SQL values"),
      Allow("com.transformer.sql.exec.PhysicalPlanner$", "shouldBuildRight",
        "compares two row estimates to choose a join's build side, no SQL value"),
      Allow("com.transformer.job.JsonValue", "stringValue",
        "whether a JSON number is whole, to print it as one; no SQL value"),
      Allow("com.transformer.job.JsonNumber", "equals", "the case class's synthesized equals of a JSON number"),
      Allow("com.transformer.core.metrics.JsonMini$JNum", "equals",
        "the case class's synthesized equals of a JSON number in the metrics' reader")),
    held = Seq(
      Allow("com.transformer.sql.plan.FunctionCatalog$", "Entries",
        "held: POWER's domain test again, math.floor(y) != y, in the catalog's description of when POWER is " +
          "NULL (plans/testing/09 wrote this copy after the plan's measurement). The same exception as " +
          "Funcs.apply's, for the user to confirm")))

  val R3FloatKeyBits: Rule = Rule("R3", "float key bits", Engine,
    membersInstead("java/lang/Double", "KeyCodec.doubleKeyBits", "doubleToLongBits", "doubleToRawLongBits") ++
      membersInstead("java/lang/Float", "KeyCodec.floatKeyBits", "floatToIntBits", "floatToRawIntBits"),
    advice = "A float key's bits are KeyCodec.floatKeyBits / doubleKeyBits: -0.0 as the 0.0 = calls it equal, and " +
      "every NaN as one. The raw bits separate two values = calls equal, so a map, a shard or a spill bucket keyed " +
      "by them splits one group in two (docs/conventions.md, \"Anything that HASHES, GROUPS or DE-DUPLICATES " +
      "values agrees with Ops.eq\").",
    allowed = Seq(
      Allow("com.transformer.core.KeyCodec$", "floatKeyBits", "the sanctioned bits of a FLOAT key, -0.0 read as 0.0"),
      Allow("com.transformer.core.KeyCodec$", "doubleKeyBits", "the sanctioned bits of a DOUBLE key, -0.0 read as 0.0"),
      Allow("com.transformer.sql.exec.HashPartitioner$", "decimalHash",
        "the bits of a decimal's doubleValue, which equal decimals share whatever their scales; no float key")))

  val R4StringOrder: Rule = Rule("R4", "string ordering", Engine,
    membersInstead("java/lang/String", "Ops.cmp(a, b)", "compareTo", "compareToIgnoreCase") ++
      membersInstead("scala/collection/StringOps$", "Ops.cmp(a, b)", "compare$extension", "$less$extension",
        "$less$eq$extension", "$greater$extension", "$greater$eq$extension"),
    advice = "Two strings order through Ops.cmp, the one total order ORDER BY, every comparison, MIN and MAX, a " +
      "window's peers and every sorted key follow; a second string order beside it lets two operators disagree " +
      "on which of two strings comes first (docs/conventions.md, \"Anything that ORDERS values\").",
    allowed = Seq(Allow("com.transformer.sql.plan.Ops$", "cmp", "the one total order, where two strings compare")))

  val R7Quiet: Rule = Rule("R7", "library code does not print, exit or sleep", Engine,
    Seq(
      member("java/lang/System", "out", instead = "a warning in the run's result, or an exception"),
      member("java/lang/System", "err", instead = "a warning in the run's result, or an exception"),
      anyOwner("printStackTrace", instead = "the exception, thrown or in the task's status"),
      member("java/lang/System", "exit", instead = "an exception"),
      member("java/lang/Runtime", "exit", instead = "an exception"),
      member("java/lang/Runtime", "halt", instead = "an exception"),
      member("scala/sys/package$", "exit", instead = "an exception"),
      member("java/lang/Thread", "sleep", instead = "a wait on what is awaited"),
      member("java/util/concurrent/TimeUnit", "sleep", instead = "a wait on what is awaited")) ++
      membersInstead("scala/Console$", "a warning in the run's result", "println", "print", "printf", "out", "err") ++
      membersInstead("scala/Predef$", "a warning in the run's result", "println", "print", "printf"),
    advice = "The engine is a library: what a run meets is a warning in its result and its job.json, or an exception, " +
      "never a line on standard output or error, which a program embedding it can neither see nor silence " +
      "(plans/robustness/08: a warning never fails a run). It never ends the JVM, and never sleeps to wait. The CLI " +
      "and the examples print and exit.",
    allowed = Seq(
      Allow("com.transformer.core.Faults$", "fire",
        "plans/testing/06's fault hook: an armed halt says where it halts and ends the JVM, the crash it injects, " +
          "only where a test armed it")),
    held = Seq(
      Allow("com.transformer.core.LeakAudit$", "exit",
        "held: plans/testing/17's exit audit, on only under -Dtransformer.audit_leaks, prints what a JVM left open " +
          "and halts it with status 117 so Bazel fails the target. Written after this rule's plan, so no decision " +
          "names it")))

  val R9DateDoors: Rule = Rule("R9", "dates enter vectors through one door", Engine, Seq(
    member("java/time/LocalDate", "toEpochDay", instead = "DateVector.epochDay(d)"),
    member("java/time/Instant", "getEpochSecond", instead = "TimestampVector.epochMicros(i)"),
    member("java/time/Instant", "getNano", instead = "TimestampVector.epochMicros(i)"),
    member("java/time/temporal/ChronoUnit", "between", instead = "DateVector.epochDay / TimestampVector.epochMicros")),
    advice = "A date or a timestamp enters a vector through DateVector.epochDay and TimestampVector.epochMicros, " +
      "which give the INT of days or the BIGINT of microseconds it is stored as and an error past it; " +
      "toEpochDay.toInt and getEpochSecond * 1000000L wrap it round to another date (docs/conventions.md, \"A " +
      "date or a timestamp enters a vector\"; plans/robustness/25). Out of a vector, or into a duration, is not " +
      "banned.",
    allowed = Seq(
      Allow("com.transformer.core.DateVector$", "epochDay", "the one door a date enters a vector by"),
      Allow("com.transformer.core.TimestampVector$", "epochMicros", "the one door a timestamp enters a vector by")))

  val R5Clock: Rule = Rule("R5", "clock", JobModules, Seq(
    member("java/lang/System", "currentTimeMillis"),
    Ban("java/time/*.now", {
      case Use.Reference(r, _) => r.owner.startsWith("java/time/") && r.name == "now"
      case _ => false
    })) ++ members("java/time/Clock", "systemUTC", "systemDefaultZone", "system"),
    advice = "No module a job runs reads the wall clock but where a decision says: a clock read inside an operator " +
      "or a function makes a query's answer move with the time it runs. CURRENT_DATE and CURRENT_TIMESTAMP read " +
      "the statement's start once, as it binds (README, \"SQL features\"), a job's templates read its execution " +
      "time, and a run's records stamp when things happened. System.nanoTime, for a duration, is not banned.",
    allowed = Seq(
      Allow("com.transformer.sql.plan.LogicalBuilder$", "build",
        "decided: CURRENT_DATE and CURRENT_TIMESTAMP read the wall clock once, at the statement's start"),
      Allow("com.transformer.temporal.TemporalVariables$", "*",
        "decided: a job's execution time defaults to now (the default of the constructor's and apply's argument)"),
      Allow("com.transformer.job.DataJob", "*",
        "a run's records stamp when the run, each task and each record began and ended; no answer reads them"),
      Allow("com.transformer.job.DataJob$$anon$2", "call",
        "the same stamps, in the task the runner submits for each SQL task"),
      Allow("com.transformer.cli.Cli$", "parse", "`transformer run`'s execution time defaults to now"),
      Allow("com.transformer.gui.JobSession", "<init>", "a session's execution time starts as now, as the GUI shows it")))

  val R6Random: Rule = Rule("R6", "randomness", JobModules, Seq(
    anyMember("java/util/Random"),
    anyMember("java/util/concurrent/ThreadLocalRandom"),
    anyMember("java/util/SplittableRandom"),
    anyMember("scala/util/Random"),
    anyMember("scala/util/Random$"),
    member("java/lang/Math", "random"),
    member("java/lang/StrictMath", "random"),
    member("scala/math/package$", "random"),
    member("java/util/UUID", "randomUUID")),
    advice = "RAND() is the engine's one random source, and the planner keeps it off the paths that read an " +
      "expression twice, an exchange's or a spill bucket's key (Expr.readsRandom; docs/conventions.md, \"An " +
      "operator that reads an expression twice\"). A second source would not be kept off them, and its answers " +
      "would not repeat.",
    allowed = Seq(
      Allow("com.transformer.sql.plan.Funcs$", "apply", "decided: RAND() and RAND(seed), the one random source"),
      Allow("com.transformer.sql.plan.Funcs$$anon$1", "initialValue", "RAND()'s generator, one per thread")),
    held = Seq(
      Allow("com.transformer.core.Chaos$", "draw",
        "held: plans/testing/17's chaos points draw nothing, a yield or a sleep from a generator seeded per thread " +
          "from -Dtransformer.chaos; they move when a thread runs, never a value, and are off unless the setting is " +
          "set. Written after this rule's plan, so no decision names it"),
      Allow("com.transformer.core.Chaos$ThreadState", "<init>", "held: the chaos points' generator, made per thread")))

  val R10Defaults: Rule = Rule("R10", "default charset and zone", AllModules, Seq(
    member("java/lang/String", "getBytes", "()[B", instead = "getBytes(StandardCharsets.UTF_8)"),
    member("java/lang/String", "<init>", "([B)V", instead = "new String(bytes, StandardCharsets.UTF_8)"),
    member("java/lang/String", "<init>", "([BII)V", instead = "new String(bytes, offset, length, StandardCharsets.UTF_8)"),
    member("java/io/InputStreamReader", "<init>", "(Ljava/io/InputStream;)V", instead = "a charset argument"),
    member("java/io/OutputStreamWriter", "<init>", "(Ljava/io/OutputStream;)V", instead = "a charset argument"),
    member("java/io/FileReader", "<init>", "(Ljava/lang/String;)V", instead = "Files.newBufferedReader(path, charset)"),
    member("java/io/FileReader", "<init>", "(Ljava/io/File;)V", instead = "Files.newBufferedReader(path, charset)"),
    member("java/io/FileReader", "<init>", "(Ljava/io/FileDescriptor;)V", instead = "a charset argument"),
    member("java/io/FileWriter", "<init>", "(Ljava/lang/String;)V", instead = "Files.newBufferedWriter(path, charset)"),
    member("java/io/FileWriter", "<init>", "(Ljava/lang/String;Z)V", instead = "Files.newBufferedWriter(path, charset)"),
    member("java/io/FileWriter", "<init>", "(Ljava/io/File;)V", instead = "Files.newBufferedWriter(path, charset)"),
    member("java/io/FileWriter", "<init>", "(Ljava/io/File;Z)V", instead = "Files.newBufferedWriter(path, charset)"),
    member("java/io/FileWriter", "<init>", "(Ljava/io/FileDescriptor;)V", instead = "a charset argument"),
    member("java/io/PrintStream", "<init>", "(Ljava/io/OutputStream;)V", instead = "a charset argument"),
    member("java/io/PrintStream", "<init>", "(Ljava/io/OutputStream;Z)V", instead = "a charset argument"),
    member("java/io/PrintStream", "<init>", "(Ljava/lang/String;)V", instead = "a charset argument"),
    member("java/io/PrintStream", "<init>", "(Ljava/io/File;)V", instead = "a charset argument"),
    member("java/nio/charset/Charset", "defaultCharset", instead = "StandardCharsets.UTF_8"),
    member("java/util/TimeZone", "getDefault", instead = "an explicit zone, ZoneOffset.UTC"),
    member("java/time/ZoneId", "systemDefault", instead = "an explicit zone, ZoneOffset.UTC"),
    member("java/time/Clock", "systemDefaultZone", instead = "an explicit zone, ZoneOffset.UTC")),
    advice = "Text is read and written in a charset the code names, and a time is placed in a zone the code names: " +
      "the JVM's default charset and zone are the machine's, so a file or a value would change with where it runs. " +
      "Every template variable is UTC (TemporalVariables); --config=zone runs the suite in a half-hour zone, but " +
      "only where a test reaches.")

  val All: Seq[Rule] = Seq(R1Locale, R1DesktopFolds, R2FloatOrder, R3FloatKeyBits, R4StringOrder, R5Clock, R6Random,
    R7Quiet, R9DateDoors, R10Defaults)

  // ---------------------------------------------------------------------------
  // Holding a rule
  // ---------------------------------------------------------------------------

  /** A use a rule bans, and where: the class's binary name, the method as
    * compiled, and the source file and line. */
  final case class Site(cls: String, method: String, source: String, ban: Ban) {
    def describe: String =
      s"$cls.$method ($source): ${ban.what}" + (if (ban.instead.isEmpty) "" else s" [write ${ban.instead}]")
  }

  /** Whether `entry` allows `method` of `cls`: the method itself, a lambda
    * written in it, a local def, a default argument or a specialized copy. */
  def covers(entry: Allow, cls: String, method: String): Boolean =
    entry.cls == cls && (entry.method == "*" || method == entry.method || method.startsWith(entry.method + "$") ||
      method.startsWith("$anonfun$" + entry.method + "$"))

  /** Every use in `classes` that a ban of `rule` matches. */
  def sites(rule: Rule, classes: Seq[ClassFile]): Seq[Site] =
    for {
      c <- classes
      m <- c.methods
      u <- m.uses
      ban <- rule.bans.find(_.matches(u))
    } yield Site(c.name.replace('/', '.'), m.name, c.sourceFile.getOrElse("?") + m.line(u.offset).fold("")(l => s":$l"), ban)

  /** What holding `rule` over `classes` finds: each site no entry allows, and
    * each entry that allows no site. */
  def check(rule: Rule, classes: Seq[ClassFile]): (Seq[Site], Seq[Allow]) = {
    val found = sites(rule, classes)
    val breaking = found.filterNot(s => rule.entries.exists(covers(_, s.cls, s.method)))
    val stale = rule.entries.filterNot(e => found.exists(s => covers(e, s.cls, s.method)))
    (breaking.distinct, stale)
  }

  /** The failure `check` gives, or None when the rule holds. */
  def failure(rule: Rule, breaking: Seq[Site], stale: Seq[Allow]): Option[String] =
    if (breaking.isEmpty && stale.isEmpty) None
    else {
      val uses =
        if (breaking.isEmpty) Nil
        else Seq(s"${count(breaking.size, "use")} the rule bans:") ++ breaking.map("  " + _.describe) ++ Seq(
          s"Why: ${rule.advice}",
          s"An exception the user decided is an entry in the allowlist of rule ${rule.id} in " +
            "src/test/scala/com/transformer/bytecode/BytecodeRules.scala: the class, the method and the reason.")
      val entries =
        if (stale.isEmpty) Nil
        else "Allowlist entries whose method no longer does what the rule bans (remove each):" +:
          stale.map(e => s"  ${e.cls}.${e.method}: ${e.reason}")
      Some((s"Rule ${rule.id}, ${rule.title}:" +: (uses ++ entries)).mkString("\n"))
    }

  private def count(n: Int, noun: String): String = if (n == 1) s"1 $noun" else s"$n ${noun}s"
}
