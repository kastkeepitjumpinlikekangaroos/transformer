package com.transformer.bytecode

import org.junit.Assert._
import org.junit.Test

import java.net.URI
import java.nio.file.{FileSystems, Files}
import scala.jdk.CollectionConverters._

/** The class-file reader: each thing a rule looks for is found in the method
  * that holds it, in a fixture compiled with this target; the decoder walks
  * hand-assembled code, and every method of the Scala library and of the
  * JDK's `java.base`, to the last byte. */
class ClassFileTest {

  private lazy val fixture: ClassFile = {
    val path = "com/transformer/bytecode/ClassFileFixture$.class"
    val location = ClassFiles.locationOf(path, getClass.getClassLoader)
    ClassFiles.in(location).collectFirst { case (`path`, bytes) => ClassFile.read(bytes) }
      .getOrElse(throw new AssertionError(s"no $path in $location"))
  }

  private def method(name: String): Method =
    fixture.methods.find(_.name == name).getOrElse(throw new AssertionError(
      s"ClassFileFixture has no method $name; it has ${fixture.methods.map(_.name).mkString(", ")}"))

  private val DoubleCompare = MemberRef("java/lang/Double", "compare", "(DD)I")

  @Test def readsTheClassItself(): Unit = {
    assertEquals("com/transformer/bytecode/ClassFileFixture$", fixture.name)
    assertEquals(Some("ClassFileFixture.scala"), fixture.sourceFile)
    assertTrue(fixture.classes.contains("java/lang/Double"))
    assertTrue(fixture.strings.contains("a string constant"))
  }

  @Test def aCallIsFoundInTheMethodThatMakesIt(): Unit = {
    assertEquals(Vector(DoubleCompare), method("callsCompare").references.filter(_ == DoubleCompare))
    val holders = fixture.methods.filter(_.references.contains(DoubleCompare)).map(_.name)
    assertEquals(Seq("$anonfun$sortsInALambda$1", "callsCompare"), holders.sorted)
  }

  @Test def aCallInALambdaIsFoundInTheLambdasMethod(): Unit = {
    assertFalse(method("sortsInALambda").references.contains(DoubleCompare))
    // The method that makes the lambda holds the handle to its body.
    assertTrue(method("sortsInALambda").references.exists(_.name.startsWith("$anonfun$sortsInALambda$1")))
  }

  @Test def aRawComparisonOfTwoValuesIsNotAgainstAConstant(): Unit =
    assertEquals(Vector(false), method("comparesTwoValues").floatComparisons.map(_.againstConstant))

  @Test def aComparisonWithAConstantIsSeenOnEitherSide(): Unit = {
    assertEquals(Vector(true), method("comparesWithZero").floatComparisons.map(_.againstConstant))
    assertEquals(Vector(true), method("comparesWithZeroOnTheLeft").floatComparisons.map(_.againstConstant))
  }

  @Test def anIntConstantWidenedByI2dIsAConstant(): Unit = {
    val m = method("comparesWithAWidenedInt")
    assertEquals(Vector(true), m.floatComparisons.map(_.againstConstant))
    val code = fixtureCode("comparesWithAWidenedInt")
    val at = code.indexWhere(i => i.opcode == ClassFile.Op.Dcmpl || i.opcode == ClassFile.Op.Dcmpg)
    assertEquals("iconst_3 then i2d before the comparison", Seq(0x06, 0x87), code.slice(at - 2, at).map(_.opcode))
  }

  @Test def aStringMatchIsASwitchOnHashCode(): Unit = {
    val m = method("matchesAString")
    assertTrue(m.references.contains(MemberRef("java/lang/String", "hashCode", "()I")))
    assertEquals(Seq("alpha", "beta", "gamma"), m.strings.distinct.sorted)
    assertTrue("a switch, padded to four bytes, decoded",
      fixtureCode("matchesAString").exists(i => i.opcode == ClassFile.Op.LookupSwitch || i.opcode == ClassFile.Op.TableSwitch))
  }

  @Test def aFieldReadAndAStringConstantAreUses(): Unit = {
    assertEquals(Vector(MemberRef("java/lang/System", "err", "Ljava/io/PrintStream;")), method("readsAField").references)
    assertEquals(Vector("a string constant"), method("readsAString").strings)
  }

  @Test def aUseKnowsItsSourceLine(): Unit = {
    def lineOf(name: String): Int = {
      val m = method(name)
      m.uses.headOption.flatMap(u => m.line(u.offset)).getOrElse(throw new AssertionError(s"$name has no line"))
    }
    val lines = Seq("callsCompare", "comparesTwoValues", "comparesWithZero", "comparesWithZeroOnTheLeft").map(lineOf)
    assertEquals("one line apart, two by two, as the fixture is written", Seq(2, 2, 2), lines.sliding(2).map(p => p(1) - p(0)).toSeq)
  }

  @Test def everyReferenceInThePoolIsFoundInAMethod(): Unit =
    assertEquals(Vector.empty, fixture.unattributed)

  /** Hand-assembled: the switches at each of the four alignments their
    * padding depends on, and `wide` before a load and an `iinc`. */
  @Test def decodesSwitchPaddingAndWide(): Unit = {
    def bytes(xs: Int*): Array[Byte] = xs.map(_.toByte).toArray
    def int(v: Int): Seq[Int] = Seq(v >>> 24, v >>> 16, v >>> 8, v).map(_ & 0xff)
    (0 to 3).foreach { lead =>
      val nops = Seq.fill(lead)(0x00)
      val pad = Seq.fill((4 - ((lead + 1) & 3)) & 3)(0x00)
      // tableswitch 0..1, then return
      val table = bytes(nops ++ Seq(0xaa) ++ pad ++ int(20) ++ int(0) ++ int(1) ++ int(20) ++ int(20) ++ Seq(0xb1): _*)
      assertEquals(s"tableswitch after $lead nops", Seq.fill(lead)(0x00) ++ Seq(0xaa, 0xb1), ClassFile.decode(table).map(_.opcode))
      // lookupswitch with two pairs, then return
      val lookup = bytes(nops ++ Seq(0xab) ++ pad ++ int(28) ++ int(2) ++ int(5) ++ int(28) ++ int(9) ++ int(28) ++ Seq(0xb1): _*)
      val decoded = ClassFile.decode(lookup)
      assertEquals(s"lookupswitch after $lead nops", Seq.fill(lead)(0x00) ++ Seq(0xab, 0xb1), decoded.map(_.opcode))
      assertEquals(lookup.length - 1, decoded.last.offset)
    }
    // wide dload 300; wide iinc 2 by 1000; dreturn
    val wide = ClassFile.decode(bytes(0xc4, 0x18, 0x01, 0x2c, 0xc4, 0x84, 0x00, 0x02, 0x03, 0xe8, 0xaf))
    assertEquals(Seq(Instruction(0, 0x18, 300), Instruction(4, 0x84, 2), Instruction(10, 0xaf, -1)), wide)
  }

  /** Every method of every class the Scala library and `java.base` hold,
    * decoded to its last byte, and each class's references all found in its
    * methods: thousands of methods javac and scalac wrote. */
  @Test def decodesTheScalaLibraryAndJavaBase(): Unit = {
    val scalaLibrary = ClassFiles.read(ClassFiles.locationOf(classOf[scala.Option[_]]))
    assertTrue(s"read ${scalaLibrary.size} classes of the Scala library", scalaLibrary.size > 1000)
    val jrt = FileSystems.getFileSystem(URI.create("jrt:/"))
    val javaBase = Files.walk(jrt.getPath("/modules/java.base")).iterator.asScala
      .filter(p => p.toString.endsWith(".class") && !p.toString.endsWith("module-info.class"))
      .map(p => ClassFile.read(Files.readAllBytes(p))).toVector
    assertTrue(s"read ${javaBase.size} classes of java.base", javaBase.size > 3000)
    val unattributed = (scalaLibrary ++ javaBase).filter(_.unattributed.nonEmpty)
      .map(c => s"${c.name}: ${c.unattributed.mkString(", ")}")
    assertTrue(unattributed.take(20).mkString("\n"), unattributed.isEmpty)
  }

  private def fixtureCode(name: String): Vector[Instruction] = method(name).code
}
