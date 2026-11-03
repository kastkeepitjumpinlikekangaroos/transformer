package com.transformer.bytecode

import java.io.{ByteArrayInputStream, DataInputStream, File}
import java.net.JarURLConnection
import java.nio.file.Files
import java.util.jar.JarFile
import scala.jdk.CollectionConverters._

/** A field or method a class refers to: its owner's internal name
  * (`java/lang/Double`), its name (`compare`) and its descriptor (`(DD)I`). */
final case class MemberRef(owner: String, name: String, descriptor: String) {
  override def toString: String = s"$owner.$name$descriptor"
}

/** Something a method's code does that a rule can ban, at the byte offset of
  * the instruction that does it. */
sealed trait Use {
  def offset: Int
}

object Use {

  /** A call or a field read or write; or a method handle, which a lambda, a
    * bootstrap method and its arguments are: `Double.compare _` passed as a
    * function is a handle on `Double.compare` in the method that passes it. */
  final case class Reference(ref: MemberRef, offset: Int) extends Use

  /** An `ldc` of a string, or a string argument of a bootstrap method. */
  final case class StringConstant(value: String, offset: Int) extends Use

  /** `fcmpl`, `fcmpg`, `dcmpl` or `dcmpg`: two floats or doubles compared
    * raw, which a Scala `<`, `<=`, `>`, `>=`, `==` or `!=` between them
    * compiles to. `againstConstant` when one side is a constant
    * ([[ClassFile.againstConstant]] says which shapes it sees). */
  final case class FloatComparison(opcode: Int, againstConstant: Boolean, offset: Int) extends Use
}

/** One instruction of a method's code: the offset it starts at, its opcode
  * (the widened one after a `wide` prefix), and its operand: a constant-pool
  * index, a local variable's index or a `bipush` / `sipush` value, or -1 for
  * an instruction with none of these. */
final case class Instruction(offset: Int, opcode: Int, operand: Int)

/** A method: its code decoded, what the code does that a rule can ban, in
  * order, and its LineNumberTable, (start offset, source line) sorted by
  * offset. */
final case class Method(
    name: String,
    descriptor: String,
    code: Vector[Instruction],
    uses: Vector[Use],
    lines: Vector[(Int, Int)]) {

  def references: Vector[MemberRef] = uses.collect { case Use.Reference(r, _) => r }

  def strings: Vector[String] = uses.collect { case Use.StringConstant(s, _) => s }

  def floatComparisons: Vector[Use.FloatComparison] = uses.collect { case c: Use.FloatComparison => c }

  /** The source line of the instruction at `offset`. */
  def line(offset: Int): Option[Int] = lines.takeWhile(_._1 <= offset).lastOption.map(_._2)
}

/** A class file, read without a bytecode library (JVMS chapter 4): its
  * constant pool, and each method's code decoded an instruction at a time, so
  * that every member a class refers to, every string constant and every float
  * comparison is found in the method that holds it. A constant pool alone
  * says what a class calls, not where, and holds no opcodes: a raw `<`
  * between two doubles is a `dcmpg` in a method's code, no reference at all.
  *
  * `classes`, `references` and `strings` are the pool's: every Class
  * constant's name, every Fieldref, Methodref and InterfaceMethodref, every
  * String constant. A lambda's body is a method of its own, named
  * `$anonfun$<enclosing method>$<n>`. */
final case class ClassFile(
    name: String,
    sourceFile: Option[String],
    classes: Vector[String],
    references: Vector[MemberRef],
    strings: Vector[String],
    methods: Vector[Method]) {

  /** The pool's member references no method's code reaches. Empty for every
    * class scalac or javac writes: a reference is in the pool because an
    * instruction or a method handle uses it. A rule that reads methods sees
    * what the pool holds only while this stays empty. */
  def unattributed: Vector[MemberRef] = {
    val reached = methods.iterator.flatMap(_.references).toSet
    references.filterNot(reached)
  }
}

object ClassFile {

  /** Constant-pool tags (JVMS 4.4). */
  private object Tag {
    final val Utf8 = 1
    final val Integer = 3
    final val Float = 4
    final val Long = 5
    final val Double = 6
    final val Class = 7
    final val String = 8
    final val Fieldref = 9
    final val Methodref = 10
    final val InterfaceMethodref = 11
    final val NameAndType = 12
    final val MethodHandle = 15
    final val MethodType = 16
    final val Dynamic = 17
    final val InvokeDynamic = 18
    final val Module = 19
    final val Package = 20
  }

  /** The opcodes the reader looks at (JVMS chapter 6). */
  object Op {
    final val Bipush = 0x10
    final val Sipush = 0x11
    final val Ldc = 0x12
    final val LdcW = 0x13
    final val Ldc2W = 0x14
    final val Fload = 0x17
    final val Dload = 0x18
    final val Iinc = 0x84
    final val Fcmpl = 0x95
    final val Fcmpg = 0x96
    final val Dcmpl = 0x97
    final val Dcmpg = 0x98
    final val TableSwitch = 0xaa
    final val LookupSwitch = 0xab
    final val GetStatic = 0xb2
    final val InvokeInterface = 0xb9
    final val InvokeDynamic = 0xba
    final val Wide = 0xc4
  }

  /** Each opcode's length in bytes; 0 for the three whose length the code
    * gives (`tableswitch`, `lookupswitch`, `wide`), -1 for a byte no class
    * file holds as an opcode. */
  private val Lengths: Array[Int] = {
    val a = Array.fill(256)(-1)
    def set(from: Int, to: Int, n: Int): Unit = (from to to).foreach(a(_) = n)
    set(0x00, 0x0f, 1) // nop, aconst_null, iconst_m1 .. dconst_1
    set(0x10, 0x10, 2) // bipush
    set(0x11, 0x11, 3) // sipush
    set(0x12, 0x12, 2) // ldc
    set(0x13, 0x14, 3) // ldc_w, ldc2_w
    set(0x15, 0x19, 2) // iload .. aload
    set(0x1a, 0x35, 1) // iload_0 .. aload_3, iaload .. saload
    set(0x36, 0x3a, 2) // istore .. astore
    set(0x3b, 0x83, 1) // istore_0 .. sastore, the stack, arithmetic
    set(0x84, 0x84, 3) // iinc
    set(0x85, 0x98, 1) // conversions, lcmp, fcmpl .. dcmpg
    set(0x99, 0xa8, 3) // ifeq .. if_acmpne, goto, jsr
    set(0xa9, 0xa9, 2) // ret
    set(0xaa, 0xab, 0) // tableswitch, lookupswitch
    set(0xac, 0xb1, 1) // ireturn .. return
    set(0xb2, 0xb8, 3) // getstatic .. invokestatic
    set(0xb9, 0xba, 5) // invokeinterface, invokedynamic
    set(0xbb, 0xbb, 3) // new
    set(0xbc, 0xbc, 2) // newarray
    set(0xbd, 0xbd, 3) // anewarray
    set(0xbe, 0xbf, 1) // arraylength, athrow
    set(0xc0, 0xc1, 3) // checkcast, instanceof
    set(0xc2, 0xc3, 1) // monitorenter, monitorexit
    set(0xc4, 0xc4, 0) // wide
    set(0xc5, 0xc5, 4) // multianewarray
    set(0xc6, 0xc7, 3) // ifnull, ifnonnull
    set(0xc8, 0xc9, 5) // goto_w, jsr_w
    a
  }

  /** The instructions whose operand is a constant-pool index. */
  private def readsPool(op: Int): Boolean =
    op == Op.Ldc || op == Op.LdcW || op == Op.Ldc2W || (op >= Op.GetStatic && op <= 0xbb) || op == 0xbd ||
      op == 0xc0 || op == 0xc1 || op == 0xc5

  /** `code` decoded an instruction at a time. A `tableswitch` or
    * `lookupswitch` is padded so that its table starts at a multiple of four
    * bytes from the start of the code; `wide` widens the next instruction's
    * local index to two bytes, and an `iinc`'s increment too. */
  def decode(code: Array[Byte]): Vector[Instruction] = {
    def u1(i: Int): Int = code(i) & 0xff
    def u2(i: Int): Int = (u1(i) << 8) | u1(i + 1)
    def s4(i: Int): Int = (u1(i) << 24) | (u1(i + 1) << 16) | (u1(i + 2) << 8) | u1(i + 3)
    val out = Vector.newBuilder[Instruction]
    var pc = 0
    while (pc < code.length) {
      val op = u1(pc)
      val table = pc + 1 + ((4 - ((pc + 1) & 3)) & 3)
      val (instruction, length) = op match {
        case Op.TableSwitch =>
          val (low, high) = (s4(table + 4), s4(table + 8))
          require(low <= high, s"tableswitch at $pc runs from $low to $high")
          (Instruction(pc, op, -1), table + 12 + 4 * (high - low + 1) - pc)
        case Op.LookupSwitch =>
          (Instruction(pc, op, -1), table + 8 + 8 * s4(table + 4) - pc)
        case Op.Wide =>
          val widened = u1(pc + 1)
          (Instruction(pc, widened, u2(pc + 2)), if (widened == Op.Iinc) 6 else 4)
        case _ =>
          val n = Lengths(op)
          require(n > 0, s"byte $op at $pc is no opcode")
          val operand =
            if (op == Op.Ldc) u1(pc + 1)
            else if (readsPool(op)) u2(pc + 1)
            else if (op == Op.Bipush) code(pc + 1).toInt
            else if (op == Op.Sipush) ((code(pc + 1) << 8) | u1(pc + 2)).toShort.toInt
            else if (n == 2 || op == Op.Iinc) u1(pc + 1) // a local's index (newarray: its element type)
            else -1
          (Instruction(pc, op, operand), n)
      }
      out += instruction
      pc += length
    }
    require(pc == code.length, s"the last instruction runs past the code's ${code.length} bytes")
    out.result()
  }

  /** Read one class file. */
  def read(bytes: Array[Byte]): ClassFile = {
    val in = new DataInputStream(new ByteArrayInputStream(bytes))
    require(in.readInt() == 0xCAFEBABE, "not a class file")
    skip(in, 4) // minor and major version
    val pool = Pool.read(in)
    skip(in, 2) // access flags
    val name = pool.className(in.readUnsignedShort())
    skip(in, 2) // super class
    skip(in, 2 * in.readUnsignedShort()) // interfaces
    repeat(in.readUnsignedShort()) { skip(in, 6); skipAttributes(in) } // fields
    val raw = Vector.fill(in.readUnsignedShort())(readMethod(in, pool))
    var sourceFile = Option.empty[String]
    var bootstraps = Vector.empty[Bootstrap]
    repeat(in.readUnsignedShort()) {
      val attribute = pool.utf8(in.readUnsignedShort())
      val length = in.readInt()
      attribute match {
        case "SourceFile" => sourceFile = Some(pool.utf8(in.readUnsignedShort()))
        case "BootstrapMethods" =>
          bootstraps = Vector.fill(in.readUnsignedShort()) {
            val handle = in.readUnsignedShort()
            Bootstrap(handle, Vector.fill(in.readUnsignedShort())(in.readUnsignedShort()))
          }
        case _ => skip(in, length)
      }
    }
    val methods = raw.map { m =>
      val code = decode(m.code)
      Method(m.name, m.descriptor, code, uses(code, pool, bootstraps), m.lines)
    }
    ClassFile(name, sourceFile, pool.all(Tag.Class).map(pool.className), pool.members, pool.all(Tag.String).map(pool.string),
      methods)
  }

  /** What each instruction of `code` does that a rule can ban. */
  private def uses(code: Vector[Instruction], pool: Pool, bootstraps: Vector[Bootstrap]): Vector[Use] = {
    val out = Vector.newBuilder[Use]
    def bootstrap(constant: Int, offset: Int): Unit = {
      val b = bootstraps(pool.bootstrapIndex(constant))
      out += Use.Reference(pool.handle(b.handle), offset)
      b.arguments.foreach(arg => pooled(arg, offset))
    }
    def pooled(constant: Int, offset: Int): Unit = pool.tag(constant) match {
      case Tag.String => out += Use.StringConstant(pool.string(constant), offset)
      case Tag.MethodHandle => out += Use.Reference(pool.handle(constant), offset)
      case Tag.Dynamic => bootstrap(constant, offset)
      case _ => ()
    }
    code.indices.foreach { i =>
      val ins = code(i)
      ins.opcode match {
        case op if op >= Op.GetStatic && op <= Op.InvokeInterface => out += Use.Reference(pool.member(ins.operand), ins.offset)
        case Op.InvokeDynamic => bootstrap(ins.operand, ins.offset)
        case Op.Ldc | Op.LdcW | Op.Ldc2W => pooled(ins.operand, ins.offset)
        case op if op >= Op.Fcmpl && op <= Op.Dcmpg =>
          out += Use.FloatComparison(op, againstConstant(code, i, pool), ins.offset)
        case _ => ()
      }
    }
    out.result()
  }

  /** Whether the comparison at `code(i)` has a constant side, as far as the
    * instructions just before it show: the one before it pushes a constant,
    * or it is a `fload` / `dload` and the one before that pushes a constant.
    * A constant widened by a conversion (`iconst_3; i2d`, which `x > 3`
    * compiles to for a double `x`) is a constant. A constant reached any
    * other way (a local `val`, a field) is not seen. */
  private[bytecode] def againstConstant(code: Vector[Instruction], i: Int, pool: Pool): Boolean = {
    def pushesConstant(j: Int): Boolean = j >= 0 && {
      val op = code(j).opcode
      (op >= 0x02 && op <= Op.Sipush) || // iconst_m1 .. dconst_1, bipush, sipush
        ((op == Op.Ldc || op == Op.LdcW || op == Op.Ldc2W) && pool.isNumber(code(j).operand))
    }
    def endsConstant(j: Int): Boolean =
      pushesConstant(j) || (j >= 1 && code(j).opcode >= 0x85 && code(j).opcode <= 0x90 && pushesConstant(j - 1))
    def loadsFloat(j: Int): Boolean = {
      val op = code(j).opcode
      op == Op.Fload || op == Op.Dload || (op >= 0x22 && op <= 0x29) // fload_0 .. dload_3
    }
    endsConstant(i - 1) || (i >= 2 && loadsFloat(i - 1) && endsConstant(i - 2))
  }

  /** An entry of the BootstrapMethods attribute: a MethodHandle constant and
    * the constants passed to it. */
  private final case class Bootstrap(handle: Int, arguments: Vector[Int])

  private final case class RawMethod(name: String, descriptor: String, code: Array[Byte], lines: Vector[(Int, Int)])

  private def readMethod(in: DataInputStream, pool: Pool): RawMethod = {
    skip(in, 2) // access flags
    val name = pool.utf8(in.readUnsignedShort())
    val descriptor = pool.utf8(in.readUnsignedShort())
    var code = Array.emptyByteArray
    var lines = Vector.empty[(Int, Int)]
    repeat(in.readUnsignedShort()) {
      val attribute = pool.utf8(in.readUnsignedShort())
      val length = in.readInt()
      if (attribute == "Code") {
        skip(in, 4) // max stack, max locals
        code = new Array[Byte](in.readInt())
        in.readFully(code)
        skip(in, 8 * in.readUnsignedShort()) // the exception table
        repeat(in.readUnsignedShort()) {
          val inner = pool.utf8(in.readUnsignedShort())
          val innerLength = in.readInt()
          if (inner == "LineNumberTable")
            lines ++= Vector.fill(in.readUnsignedShort())((in.readUnsignedShort(), in.readUnsignedShort()))
          else skip(in, innerLength)
        }
      } else skip(in, length)
    }
    RawMethod(name, descriptor, code, lines.sortBy(_._1))
  }

  private def repeat(n: Int)(body: => Unit): Unit = (0 until n).foreach(_ => body)

  private def skip(in: DataInputStream, n: Int): Unit =
    require(in.skipBytes(n) == n, "the class file ends early")

  private def skipAttributes(in: DataInputStream): Unit = repeat(in.readUnsignedShort()) {
    skip(in, 2)
    skip(in, in.readInt())
  }

  /** The constant pool (JVMS 4.4): each entry's tag, and its one or two
    * indices into the pool (a MethodHandle's kind in `b`), or its UTF-8 text. */
  private[bytecode] final class Pool(tags: Array[Int], a: Array[Int], b: Array[Int], utf8s: Array[String]) {

    def tag(i: Int): Int = tags(i)

    def utf8(i: Int): String = {
      require(tags(i) == Tag.Utf8, s"constant $i is no UTF-8 string")
      utf8s(i)
    }

    def className(i: Int): String = utf8(a(i))

    def string(i: Int): String = utf8(a(i))

    def member(i: Int): MemberRef = MemberRef(className(a(i)), utf8(a(b(i))), utf8(b(b(i))))

    /** The member a MethodHandle constant refers to. */
    def handle(i: Int): MemberRef = member(a(i))

    /** An InvokeDynamic or Dynamic constant's entry in BootstrapMethods. */
    def bootstrapIndex(i: Int): Int = a(i)

    def isNumber(i: Int): Boolean = tags(i) >= Tag.Integer && tags(i) <= Tag.Double

    /** The index of every entry with `tag`. */
    def all(tag: Int): Vector[Int] = tags.indices.filter(tags(_) == tag).toVector

    def members: Vector[MemberRef] =
      tags.indices.filter(i => tags(i) >= Tag.Fieldref && tags(i) <= Tag.InterfaceMethodref).map(member).toVector
  }

  private object Pool {
    def read(in: DataInputStream): Pool = {
      val n = in.readUnsignedShort()
      val (tags, a, b, utf8s) = (new Array[Int](n), new Array[Int](n), new Array[Int](n), new Array[String](n))
      var i = 1
      while (i < n) {
        tags(i) = in.readUnsignedByte()
        tags(i) match {
          case Tag.Utf8 => utf8s(i) = in.readUTF()
          case Tag.Integer | Tag.Float => skip(in, 4)
          case Tag.Long | Tag.Double => skip(in, 8); i += 1 // takes two entries
          case Tag.Class | Tag.String | Tag.MethodType | Tag.Module | Tag.Package => a(i) = in.readUnsignedShort()
          case Tag.Fieldref | Tag.Methodref | Tag.InterfaceMethodref | Tag.NameAndType | Tag.Dynamic | Tag.InvokeDynamic =>
            a(i) = in.readUnsignedShort(); b(i) = in.readUnsignedShort()
          case Tag.MethodHandle => b(i) = in.readUnsignedByte(); a(i) = in.readUnsignedShort()
          case other => throw new IllegalArgumentException(s"unknown constant-pool tag $other at entry $i")
        }
        i += 1
      }
      new Pool(tags, a, b, utf8s)
    }
  }
}

/** The class files a classpath entry holds. */
object ClassFiles {

  /** The jar, or the class directory, `cls` was loaded from. */
  def locationOf(cls: Class[_]): File = new File(cls.getProtectionDomain.getCodeSource.getLocation.toURI)

  /** The jar, or the class directory, that holds `classFile`
    * (`com/transformer/core/Ops.class`) on `loader`'s class path. */
  def locationOf(classFile: String, loader: ClassLoader): File = {
    val url = Option(loader.getResource(classFile))
      .getOrElse(throw new AssertionError(s"$classFile is not on the class path"))
    url.openConnection match {
      case jar: JarURLConnection => new File(jar.getJarFileURL.toURI)
      case _ => classFile.split('/').foldLeft(new File(url.toURI))((f, _) => f.getParentFile)
    }
  }

  /** Every class file in a jar or under a class directory: its path
    * (`com/transformer/core/Ops.class`) and its bytes. */
  def in(location: File): Vector[(String, Array[Byte])] =
    if (location.isDirectory) {
      val root = location.toPath
      Files.walk(root).iterator.asScala.filter(p => Files.isRegularFile(p) && p.toString.endsWith(".class"))
        .map(p => root.relativize(p).toString.replace(File.separatorChar, '/') -> Files.readAllBytes(p)).toVector
    } else {
      val jar = new JarFile(location)
      try jar.entries.asScala.filter(_.getName.endsWith(".class"))
        .map(e => e.getName -> jar.getInputStream(e).readAllBytes()).toVector
      finally jar.close()
    }

  /** Every class in a jar or under a class directory, read. */
  def read(location: File): Vector[ClassFile] = in(location).map { case (_, bytes) => ClassFile.read(bytes) }
}
