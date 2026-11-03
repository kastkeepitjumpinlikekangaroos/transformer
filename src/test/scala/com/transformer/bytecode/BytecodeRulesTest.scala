package com.transformer.bytecode

import com.transformer.bytecode.BytecodeRules._
import org.junit.Assert._
import org.junit.Test

/** Every rule of [[BytecodeRules]], held over the compiled classes of every
  * module a job runs and of the desktop GUI and the data viewer, which this
  * target reads at run time only (BUILD's `runtime_deps`). Runs under tr-TR,
  * as `job:locale_independence_test` does. */
class BytecodeRulesTest {
  import BytecodeRulesTest._

  @Test def runsUnderATurkishLocale(): Unit =
    assertEquals("the BUILD jvm_flags must set tr-TR", "tr", java.util.Locale.getDefault.getLanguage)

  /** Each module is its own jar, and holds classes. */
  @Test def readsEveryModule(): Unit = {
    val jars = AllModules.map(m => ClassFiles.locationOf(m.classFile, loader))
    assertEquals(s"each module a jar of its own: $jars", AllModules.size, jars.distinct.size)
    AllModules.foreach(m => assertTrue(s"${m.target} holds no classes", classes(m).nonEmpty))
  }

  /** A class a scanned class names is in a scanned module, so a new module
    * that a job, the GUI or the viewer uses cannot go unread: add it to
    * `BytecodeRules`' modules and to this target's `runtime_deps`. */
  @Test def everyTransformerClassAModuleNamesIsInAModuleTheRulesRead(): Unit = {
    val read = AllModules.iterator.flatMap(classes).map(_.name).toSet
    val missing = for {
      m <- AllModules
      c <- classes(m)
      named <- c.classes.map(n => n.stripPrefix("[").dropWhile(_ == '[').stripPrefix("L").stripSuffix(";"))
      if named.startsWith("com/transformer/") && !read.contains(named)
    } yield s"${c.name} (${m.target}) names $named"
    assertTrue(missing.distinct.mkString("\n"), missing.isEmpty)
  }

  /** The rules read methods, so a reference the pool holds that no method's
    * code reaches would be one no rule sees. */
  @Test def everyReferenceIsFoundInAMethod(): Unit = {
    val unattributed = AllModules.flatMap(classes).filter(_.unattributed.nonEmpty)
      .map(c => s"${c.name}: ${c.unattributed.mkString(", ")}")
    assertTrue(unattributed.mkString("\n"), unattributed.isEmpty)
  }

  /** An entry covers its method and the lambdas written in it; a site no
    * entry covers fails; an entry whose method does nothing the rule bans
    * fails as stale; `*` covers a whole class. */
  @Test def allowlistEntriesCoverTheirMethodsAndGoStale(): Unit = {
    val path = "com/transformer/bytecode/RuleFixture$.class"
    val fixture = ClassFiles.in(ClassFiles.locationOf(path, loader)).collect { case (`path`, b) => ClassFile.read(b) }
    val cls = "com.transformer.bytecode.RuleFixture$"
    def rule(entries: Allow*): Rule =
      Rule("T", "test", Nil, Seq(member("java/lang/Double", "compare")), "advice", allowed = entries)
    def found(r: Rule): (Seq[String], Seq[String]) = {
      val (breaking, stale) = check(r, fixture)
      (breaking.map(_.method).sorted, stale.map(_.method))
    }
    assertEquals((Seq("$anonfun$inALambda$1", "direct"), Nil), found(rule()))
    assertEquals((Nil, Nil), found(rule(Allow(cls, "direct", "r"), Allow(cls, "inALambda", "r"))))
    assertEquals((Seq("$anonfun$inALambda$1"), Seq("unrelated")),
      found(rule(Allow(cls, "direct", "r"), Allow(cls, "unrelated", "r"))))
    assertEquals((Nil, Nil), found(rule(Allow(cls, "*", "r"))))
    assertTrue(failure(rule(Allow(cls, "unrelated", "r")), check(rule(), fixture)._1, Seq(Allow(cls, "unrelated", "r")))
      .exists(f => f.contains("(BytecodeRulesTest.scala:") && f.contains("remove each") && f.contains("Why: advice")))
  }

  /** Every rule of `BytecodeRules.All`, each failure naming its rule, the
    * sites it found with their source lines, and its advice. */
  @Test def everyRuleHolds(): Unit = {
    val failures = All.flatMap { rule =>
      val (breaking, stale) = check(rule, rule.scope.flatMap(classes))
      failure(rule, breaking, stale)
    }
    assertTrue(failures.mkString("\n\n"), failures.isEmpty)
  }
}

object BytecodeRulesTest {

  private val loader: ClassLoader = classOf[BytecodeRulesTest].getClassLoader

  /** Each module's classes, read once per JVM. */
  private lazy val classes: Map[Module, Vector[ClassFile]] =
    AllModules.map(m => m -> ClassFiles.read(ClassFiles.locationOf(m.classFile, loader))).toMap
}

/** What [[BytecodeRulesTest.allowlistEntriesCoverTheirMethodsAndGoStale]]
  * holds a rule against. Never called. */
object RuleFixture {
  def direct(a: Double, b: Double): Int = java.lang.Double.compare(a, b)

  def inALambda(xs: Seq[Double]): Seq[Double] = xs.sortWith((a, b) => java.lang.Double.compare(a, b) < 0)

  def unrelated(a: Double): Double = a + 1
}
