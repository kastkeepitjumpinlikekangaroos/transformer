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

  val All: Seq[Rule] = Seq(R1Locale, R1DesktopFolds)

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
