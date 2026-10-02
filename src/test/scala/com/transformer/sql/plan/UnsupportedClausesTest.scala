package com.transformer.sql.plan

import com.transformer.core.Catalog
import com.transformer.sql.parse.SqlParser
import org.junit.Assert._
import org.junit.Test

import java.io.{ByteArrayInputStream, DataInputStream, File}
import java.lang.reflect.{Modifier, ParameterizedType, Type, WildcardType}
import java.nio.file.Files
import java.util.jar.JarFile
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Holds [[UnsupportedClauses]] to account against the two things it can
  * drift from: the JSqlParser jar (an upgrade adds a getter, a field or a
  * subclass) and [[LogicalBuilder]] (the binder starts or stops reading a
  * getter).
  *
  * "What the binder reads" is not a list anyone maintains: it is read out of
  * LogicalBuilder's compiled class files, whose constant pool names every
  * class the binder matches on and every getter it calls. */
class UnsupportedClausesTest {
  import UnsupportedClausesTest._

  /** The audit's core invariant. Every public getter and field of every
    * audited class is exactly one of: read by the binder, rejected, or inert.
    * A partial rejection is the one overlap allowed — the binder reads the
    * getter and the entry rejects the uses it does not honour. */
  @Test def everyGetterIsReadRejectedOrInertExactlyOnce(): Unit = {
    val problems = mutable.ArrayBuffer.empty[String]
    UnsupportedClauses.audits.toSeq.sortBy(_._1.getName).foreach { case (cls, audit) =>
      val name = cls.getSimpleName
      val members = (UnsupportedClauses.getters(cls).map(_.getName) ++
        UnsupportedClauses.fields(cls).map(_.getName)).distinct
      val rejections: Map[String, Seq[UnsupportedClauses.Rejection]] =
        audit.rejections.flatMap(r => r.getters.map(_ -> r)).groupBy(_._1).map { case (g, rs) => g -> rs.map(_._2) }

      (audit.inert.keySet ++ rejections.keySet).diff(members.toSet).toSeq.sorted.foreach { g =>
        problems += s"$name.$g is listed in UnsupportedClauses, but $name has no public getter or field of " +
          "that name. JSqlParser renamed or removed it: update or delete the entry."
      }
      members.foreach { g =>
        val read = binderReads(cls, g)
        val inert = audit.inert.contains(g)
        val full = rejections.getOrElse(g, Nil).filterNot(_.partial)
        val partial = rejections.getOrElse(g, Nil).filter(_.partial)
        if (!read && !inert && full.isEmpty && partial.isEmpty)
          problems += s"$name.$g is neither read by LogicalBuilder nor listed in UnsupportedClauses, so a " +
            "clause it carries is silently dropped. If it can change what a query means, add a rejection " +
            "for it (with an example) to UnsupportedClauses; if it cannot (a spelling, a structural " +
            "accessor), list it as inert with the reason; if the engine should honour it, read it in " +
            "LogicalBuilder."
        if (inert && read)
          problems += s"$name.$g is listed as inert, but LogicalBuilder reads it. Remove it from the inert list."
        if (inert && (full.nonEmpty || partial.nonEmpty))
          problems += s"$name.$g is listed both as inert and as rejected. Keep one."
        if (read && full.nonEmpty)
          problems += s"$name.$g is read by LogicalBuilder, but UnsupportedClauses still rejects every use of " +
            s"it (${full.mkString(", ")}). If the binder now honours it, delete the rejection; if only some " +
            "uses, mark the entry partial and narrow its test."
        if (!read && partial.nonEmpty)
          problems += s"$name.$g is rejected as partial (${partial.mkString(", ")}), but LogicalBuilder never " +
            "reads it, so the uses the entry lets through are dropped. Make the rejection total."
      }
    }
    assertTrue(report("Every audited getter must be read, rejected or inert", problems), problems.isEmpty)
  }

  /** Every AST class the binder can receive is audited: each concrete
    * JSqlParser class its bytecode names (the classes it matches on), and each
    * concrete class returned by a getter it calls (Distinct reaches the binder
    * that way: it is tested for null and never matched on). */
  @Test def everyClassTheBinderReachesIsAudited(): Unit = {
    val named = binderClassNames.flatMap(loadJSqlParserClass)
    val returned = binderMemberRefs.toSeq.flatMap { case (owner, member) =>
      loadJSqlParserClass(owner).toSeq.flatMap { cls =>
        cls.getMethods.find(m => m.getName == member && m.getParameterCount == 0)
          .toSeq.flatMap(m => classesIn(m.getGenericReturnType))
      }
    }
    val missing = (named ++ returned).filter(isConcreteNode).filterNot(UnsupportedClauses.audits.contains)
      .map(_.getName).distinct.sorted
    assertTrue(report("LogicalBuilder reaches AST classes UnsupportedClauses does not audit. Audit each " +
      "one: classify every public getter and field as rejected or inert, unless the binder reads it", missing),
      missing.isEmpty)
  }

  /** A `case f: Function` arm binds every subclass of Function too, so a
    * subclass's extra getters would be dropped unless it is audited itself or
    * provably never reaches that arm. */
  @Test def everySubclassOfAnAuditedClassIsAuditedOrUnreachable(): Unit = {
    val audited = UnsupportedClauses.audits.keySet
    val subclasses = jsqlParserClasses.filter(isConcreteNode).filter { c =>
      !audited.contains(c) && audited.exists(a => a != c && a.isAssignableFrom(c))
    }.map(_.getName).toSet
    val uncovered = subclasses.diff(UnsupportedClauses.unreachable.keySet).toSeq.sorted
    val stale = UnsupportedClauses.unreachable.keySet.diff(subclasses).toSeq.sorted
    assertTrue(report("These JSqlParser classes extend an audited class, so the binder's case for the " +
      "parent would bind them too. Audit each one, or list it in UnsupportedClauses.unreachable with " +
      "the reason it never reaches that case", uncovered), uncovered.isEmpty)
    assertTrue(report("UnsupportedClauses.unreachable lists classes that are no longer unaudited " +
      "subclasses of an audited class; delete them", stale), stale.isEmpty)
  }

  /** Each example is real SQL that reaches its own entry through the whole
    * front end — so the parser can produce the clause, the walk visits the
    * node that carries it, and the entry's presence test fires. */
  @Test def everyExampleReachesItsOwnRejection(): Unit = {
    val problems = mutable.ArrayBuffer.empty[String]
    allRejections.foreach { r =>
      r.example.foreach { sql =>
        Try(LogicalBuilder.build(sql, new Catalog)) match {
          case scala.util.Failure(e: UnsupportedClauseException) if e.rejection eq r =>
            assertTrue(s"$sql -> ${e.getMessage}", e.getMessage.contains("is not supported"))
          case scala.util.Failure(e: UnsupportedClauseException) =>
            problems += s"$r: its example `$sql` reaches ${e.rejection} first"
          case scala.util.Failure(e) =>
            problems += s"$r: its example `$sql` fails with ${e.getClass.getSimpleName}: ${e.getMessage}"
          case scala.util.Success(_) =>
            problems += s"$r: its example `$sql` is not rejected"
        }
      }
    }
    assertTrue(report("Examples must reach their own rejection", problems), problems.isEmpty)
  }

  /** The negative control: SQL the engine supports passes the walk untouched,
    * including the spellings the audit calls inert. A presence test that is
    * wrong for the absent case would reject all of these. */
  @Test def supportedSqlPassesThePass(): Unit = {
    val problems = Supported.flatMap { sql =>
      Try(UnsupportedClauses.reject(SqlParser.parseSelect(sql))).failed.toOption
        .map(e => s"`$sql` -> ${e.getClass.getSimpleName}: ${e.getMessage}")
    }
    assertTrue(report("Supported SQL must pass the rejection pass", problems), problems.isEmpty)
  }

  /** The rejection names the clause the user wrote, never an internal slot
    * name — GROUPING SETS used to fail binding with "Unknown column 'g'.
    * Available: [_agg0]". */
  @Test def rejectionsAreUserFacing(): Unit = {
    allRejections.foreach { r =>
      r.example.foreach { sql =>
        val e = Try(LogicalBuilder.build(sql, new Catalog)).failed.get
        assertTrue(s"$sql -> ${e.getMessage}", e.isInstanceOf[IllegalArgumentException])
        assertFalse(s"$sql -> ${e.getMessage}", e.getMessage.contains("_agg"))
      }
    }
  }
}

object UnsupportedClausesTest {

  /** Class-file name prefixes of the code that binds the AST. If binding moves
    * into another object, add its prefix here, or every getter it reads will
    * be reported as dropped. */
  private val BinderClassPrefixes = Seq("com/transformer/sql/plan/LogicalBuilder")

  private val Supported: Seq[String] = Seq(
    "SELECT a, b FROM t",
    "SELECT t.a, x.b, t.* FROM t JOIN u x ON t.a = x.a",
    "SELECT * FROM t AS x",
    "SELECT DISTINCT a FROM t",
    "SELECT UNIQUE a FROM t",
    "SELECT a, COUNT(*), COUNT(DISTINCT b), SUM(a), SUM(ALL a), AVG(a), MIN(a), MAX(a) FROM t " +
      "GROUP BY a HAVING COUNT(*) > 1 ORDER BY a DESC, 2 ASC LIMIT 5",
    "SELECT a, COUNT(*) FROM t GROUP BY (a)",
    "SELECT a, b, COUNT(*) FROM t GROUP BY (a, b)",
    "SELECT STDDEV(a), VARIANCE(a), COVAR_SAMP(a, b), CORR(a, b), COUNT_IF(a > 1) FROM t",
    "SELECT t.a FROM t LEFT OUTER JOIN u ON t.a = u.a RIGHT JOIN u u2 ON u2.a = t.a " +
      "FULL OUTER JOIN u u3 ON u3.a = t.a CROSS JOIN u u4 INNER JOIN u u5 ON (u5.a = t.a), u u6",
    "SELECT a FROM t WHERE a BETWEEN 1 AND 2 AND a NOT BETWEEN 5 AND 6 AND a IN (1, 2) AND a NOT IN (3) " +
      "AND s IS NOT NULL AND s IS NULL AND s LIKE 'x%' AND s NOT LIKE 'y' AND s LIKE BINARY 'z'",
    "SELECT a FROM t WHERE s ISNULL",
    "SELECT a FROM t WHERE a <> 1 AND a != 2 AND NOT a = 3 AND !(a = 4) AND (a = 5 OR a > 6) && a < 7",
    "SELECT a + b, a - b, a * b, a / b, a % b, s || s, -a, +a, (a + b) * 2 FROM t",
    "SELECT CASE WHEN a > 1 THEN 'x' ELSE 'y' END, CASE a WHEN 1 THEN 2 END FROM t",
    "SELECT CAST(a AS BIGINT), a::DOUBLE, CAST(s AS DATE), INT '1' FROM t",
    "SELECT TRIM(s), UPPER(s), {fn LOWER(s)}, COALESCE(s, 'x'), ROUND(a, 2) FROM t",
    "SELECT 1, 2.5, 'x', N'y', NULL, TRUE, DATE '2020-01-01', TIMESTAMP '2020-01-01 00:00:00' FROM t",
    "SELECT ROW_NUMBER() OVER (ORDER BY a), RANK() OVER (PARTITION BY b ORDER BY a DESC), " +
      "DENSE_RANK() OVER (PARTITION BY (b) ORDER BY a) FROM t",
    "SELECT LAG(a) OVER (ORDER BY b), LEAD(a, 2, 0) OVER (ORDER BY b), " +
      "LAG(a) RESPECT NULLS OVER (ORDER BY b) FROM t",
    "SELECT COUNT(*) OVER (), COUNT(DISTINCT a) OVER (), COUNT(a) OVER (PARTITION BY b), " +
      "SUM(a) OVER (ORDER BY b ROWS BETWEEN 1 PRECEDING AND CURRENT ROW), " +
      "AVG(a) OVER (ORDER BY b ROWS UNBOUNDED PRECEDING), " +
      "MAX(a) OVER (ORDER BY b RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) FROM t",
    "WITH c(x) AS (SELECT a FROM t), d AS (SELECT x FROM c) SELECT x FROM d",
    "SELECT a FROM t UNION SELECT a FROM u UNION ALL SELECT a FROM u UNION DISTINCT SELECT a FROM t",
    "SELECT a FROM t UNION ALL SELECT a FROM u ORDER BY 1 LIMIT 3",
    "(SELECT a FROM t) UNION ALL (SELECT a FROM u ORDER BY a LIMIT 1)",
    "(SELECT a FROM t ORDER BY a LIMIT 1)",
    "SELECT a FROM t ORDER BY (a), b ASC",
    // What plan 01's second half honours, so a narrowed partial rejection
    // cannot quietly widen back over it.
    "SELECT a FROM t ORDER BY a LIMIT 2 OFFSET 1",
    "SELECT a FROM t OFFSET 1 ROWS",
    "SELECT a FROM t LIMIT 1, 2",
    "SELECT a FROM t UNION ALL SELECT a FROM u LIMIT 2 OFFSET 1",
    "SELECT a FROM t ORDER BY a NULLS LAST, b DESC NULLS FIRST",
    "SELECT ROW_NUMBER() OVER (ORDER BY a DESC NULLS LAST) FROM t",
    "SELECT SUM(DISTINCT a), AVG(DISTINCT a), MIN(DISTINCT a), MAX(DISTINCT b), COUNT(ALL a) FROM t",
    "SELECT COUNT(ALL a) OVER (), COUNT(DISTINCT a) OVER () FROM t",
    "SELECT a FROM t WHERE s ILIKE 'x%' AND s NOT ILIKE 'y' AND s LIKE 'a!_%' ESCAPE '!' " +
      "AND s ILIKE 'b' ESCAPE ''",
    "SELECT 1 AS x",
    "SELECT COUNT(*) WHERE 1 = 1",
    "SELECT 1 FROM t HAVING COUNT(*) > 1",
    "SELECT TOP 2 a FROM t ORDER BY a",
    "SELECT DISTINCT TOP (2) a FROM t",
    "SELECT a FROM t ORDER BY a OFFSET 1 ROWS FETCH NEXT 2 ROWS ONLY",
    "SELECT a FROM t FETCH FIRST ROW ONLY",
    "SELECT a FROM t UNION ALL SELECT a FROM u FETCH FIRST 2 ROWS ONLY",
    "WITH c AS (SELECT a FROM t FETCH FIRST 1 ROWS ONLY) SELECT a FROM c"
  )

  private lazy val allRejections: Seq[UnsupportedClauses.Rejection] = {
    val seen = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[UnsupportedClauses.Rejection, java.lang.Boolean])
    UnsupportedClauses.audits.toSeq.sortBy(_._1.getName).flatMap(_._2.rejections).filter(seen.add)
  }

  private def report(headline: String, problems: Iterable[String]): String =
    (headline +: problems.toSeq).mkString("\n  - ")

  // ---------------------------------------------------------------------------
  // What LogicalBuilder's bytecode refers to
  // ---------------------------------------------------------------------------

  /** Internal names (`net/sf/...`) of every class the binder's constant pools
    * name, and every (owner, member) it calls or reads. */
  private lazy val (binderClassNames: Seq[String], binderMemberRefs: Set[(String, String)]) = {
    val pools = binderClassFiles.map(ConstantPool.read)
    (pools.flatMap(_.classes).distinct, pools.flatMap(_.members).toSet)
  }

  /** Whether the binder reads `member` on a node of class `cls`: a call or
    * field read whose owner is `cls` or one of its supertypes. A call made
    * through a subclass (`WithItem.getAlias`) does not count for the parent
    * (`ParenthesedSelect.getAlias`). */
  private def binderReads(cls: Class[_], member: String): Boolean =
    binderMemberRefs.exists { case (owner, m) =>
      m == member && loadJSqlParserClass(owner).exists(_.isAssignableFrom(cls))
    }

  private def binderClassFiles: Seq[Array[Byte]] = {
    val location = new File(LogicalBuilder.getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
    def wanted(name: String) = name.endsWith(".class") && BinderClassPrefixes.exists(name.startsWith)
    val files =
      if (location.isDirectory) {
        val root = location.toPath
        Files.walk(root).iterator.asScala.filter(Files.isRegularFile(_))
          .filter(p => wanted(root.relativize(p).toString.replace(File.separatorChar, '/')))
          .map(p => Files.readAllBytes(p)).toVector
      } else {
        val jar = new JarFile(location)
        try jar.entries.asScala.filter(e => wanted(e.getName)).map(e => jar.getInputStream(e).readAllBytes()).toVector
        finally jar.close()
      }
    assertTrue(s"found no class files under $location for $BinderClassPrefixes", files.nonEmpty)
    files
  }

  /** The class and member references in one class file's constant pool (JVMS
    * section 4.4) — enough to see what a class matches on and calls, without a
    * bytecode library. */
  private object ConstantPool {
    final case class Refs(classes: Seq[String], members: Seq[(String, String)])

    def read(bytes: Array[Byte]): Refs = {
      val in = new DataInputStream(new ByteArrayInputStream(bytes))
      require(in.readInt() == 0xCAFEBABE, "not a class file")
      in.readUnsignedShort(); in.readUnsignedShort() // minor, major version
      val n = in.readUnsignedShort()
      val tag = new Array[Int](n)
      val a = new Array[Int](n)
      val b = new Array[Int](n)
      val utf8 = new Array[String](n)
      var i = 1
      while (i < n) {
        tag(i) = in.readUnsignedByte()
        tag(i) match {
          case 1                           => utf8(i) = in.readUTF()
          case 3 | 4                       => in.readInt()
          case 5 | 6                       => in.readLong(); i += 1 // takes two slots
          case 7 | 8 | 16 | 19 | 20        => a(i) = in.readUnsignedShort()
          case 9 | 10 | 11 | 12 | 17 | 18  => a(i) = in.readUnsignedShort(); b(i) = in.readUnsignedShort()
          case 15                          => in.readUnsignedByte(); a(i) = in.readUnsignedShort()
          case other                       => sys.error(s"unknown constant-pool tag $other")
        }
        i += 1
      }
      val classes = (1 until n).filter(tag(_) == 7).map(j => utf8(a(j)))
      // Fieldref, Methodref, InterfaceMethodref: class index, then name-and-type.
      val members = (1 until n).filter(j => tag(j) == 9 || tag(j) == 10 || tag(j) == 11)
        .map(j => (utf8(a(a(j))), utf8(a(b(j)))))
      Refs(classes, members)
    }
  }

  // ---------------------------------------------------------------------------
  // The JSqlParser jar
  // ---------------------------------------------------------------------------

  private def loadJSqlParserClass(internalName: String): Option[Class[_]] = {
    val name = internalName.replace('/', '.')
    if (!name.startsWith("net.sf.jsqlparser.")) None
    else Try(Class.forName(name, false, getClass.getClassLoader)).toOption
  }

  /** A class an AST node can be an instance of. */
  private def isConcreteNode(c: Class[_]): Boolean =
    UnsupportedClauses.isJSqlParserClass(c) && !c.isInterface && !c.isEnum &&
      !Modifier.isAbstract(c.getModifiers) && !classOf[Throwable].isAssignableFrom(c)

  /** The classes a getter's declared return type can hold: the type itself,
    * and the element types of a collection. */
  private def classesIn(t: Type): Seq[Class[_]] = t match {
    case c: Class[_]          => Seq(c)
    case p: ParameterizedType => classesIn(p.getRawType) ++ p.getActualTypeArguments.toSeq.flatMap(classesIn)
    case w: WildcardType      => w.getUpperBounds.toSeq.flatMap(classesIn)
    case _                    => Nil
  }

  private lazy val jsqlParserClasses: Seq[Class[_]] = {
    val jar = new JarFile(new File(classOf[net.sf.jsqlparser.statement.Statement]
      .getProtectionDomain.getCodeSource.getLocation.toURI))
    try jar.entries.asScala.map(_.getName)
      .filter(n => n.startsWith("net/sf/jsqlparser/") && n.endsWith(".class"))
      .flatMap(n => loadJSqlParserClass(n.stripSuffix(".class"))).toVector
    finally jar.close()
  }
}
