package com.transformer.bench.internal

import org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator

import java.io.{BufferedOutputStream, FileInputStream, FileOutputStream}
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.jar.{JarEntry, JarFile, JarOutputStream}
import javax.tools.{StandardLocation, ToolProvider}

/** Build-time runner that bridges JMH's "Pattern B" (programmatic Runner)
  * into Bazel.
  *
  * The benchmark code's `@Benchmark` methods live in a Scala library jar;
  * this tool:
  *
  *   1. Extracts the Scala-compiled classes from `--bench-jar`.
  *   2. Invokes [[JmhBytecodeGenerator]] on those classes, producing Java
  *      sources and the two META-INF resources (`BenchmarkList`,
  *      `CompilerHints`) JMH's runtime needs.
  *   3. Compiles the generated Java sources via the system `JavaCompiler`.
  *   4. Packs the original bench classes + the generated classes +
  *      META-INF resources into a single output jar at `--output`.
  *
  * The output jar is consumed by a downstream `scala_binary` via
  * `runtime_deps`. The resulting deploy_jar contains a fully
  * self-discoverable JMH benchmark set: `Runner.run()` reads
  * `META-INF/BenchmarkList` on the classpath and dispatches accordingly.
  *
  * # Why this is a scala_binary, not a java_binary
  *
  * JMH's bytecode generator opens each scanned `.class` file with ASM and,
  * whenever it encounters a referenced nested or signature type, calls
  * `Class.forName(name)` to resolve it. The benchmark classes are Scala-
  * compiled, so their fields and methods contain references to
  * `scala.runtime.*` (e.g. `AbstractFunction10`) and `scala.collection.*`.
  * Those classes must be resolvable on the generator's runtime classpath
  * — `scala_binary` arranges this automatically because `scala-library` is
  * a transitive runtime dep of every Scala target. A plain `java_binary`
  * cannot resolve them and crashes with `NoClassDefFoundError:
  * scala/runtime/AbstractFunction10` before the generator produces any
  * output.
  *
  * Build-time only; NOT bundled into the runtime benchmark deploy jar.
  */
object JmhGenerateMain {

  def main(args: Array[String]): Unit = {
    var benchJar: String = null
    var output: String   = null
    val classpathJars = new java.util.ArrayList[String]()
    var i = 0
    while (i < args.length) {
      args(i) match {
        case "--bench-jar"     => i += 1; benchJar = args(i)
        case "--classpath-jar" => i += 1; classpathJars.add(args(i))
        case "--output"        => i += 1; output = args(i)
        case other             => throw new IllegalArgumentException(s"Unknown flag: $other")
      }
      i += 1
    }
    if (benchJar == null || output == null) {
      System.err.println(
        "Usage: JmhGenerateMain --bench-jar <input.jar> [--classpath-jar <jar>]... --output <output.jar>")
      System.exit(1)
    }

    val work = Files.createTempDirectory("jmh-gen-")
    val classesDir          = work.resolve("classes")
    val sourcesDir          = work.resolve("sources")
    val resourcesDir        = work.resolve("resources")
    val generatedClassesDir = work.resolve("generated-classes")
    Files.createDirectories(classesDir)
    Files.createDirectories(sourcesDir)
    Files.createDirectories(resourcesDir)
    Files.createDirectories(generatedClassesDir)

    // Step 1: extract the input bench jar so JMH can read class files
    // from disk. JmhBytecodeGenerator takes a directory of .class files,
    // not a jar, as its input. We intentionally extract ONLY the thin
    // bench jar here — the deploy jar (passed via --classpath-jar) is
    // attached to a child class loader below for type resolution, not
    // expanded onto disk for scanning.
    unzipJar(new java.io.File(benchJar), classesDir)

    // Step 2: install a child class loader whose URL classpath includes
    // every `--classpath-jar` arg, so JMH's ASMClassInfo.visitField can
    // resolve transitive types (e.g. `com.transformer.core.ColumnarBatch`)
    // via `Class.forName` against the current thread's context class
    // loader. The deploy jar contains those classes plus their
    // transitive closure; the bench-only jar referenced from the bench
    // classes alone is not enough for resolution.
    val urls = new java.util.ArrayList[java.net.URL]()
    val it = classpathJars.iterator()
    while (it.hasNext) urls.add(new java.io.File(it.next()).toURI.toURL)
    val previousLoader = Thread.currentThread().getContextClassLoader
    val resolverLoader = new java.net.URLClassLoader(
      urls.toArray(new Array[java.net.URL](urls.size)),
      previousLoader)
    Thread.currentThread().setContextClassLoader(resolverLoader)

    // Step 3: run the JMH bytecode generator. We choose "reflection" so
    // the generator reads class files via the JVM's reflective API
    // rather than the ASM walker. The ASM path is faster, but has a
    // known issue with Scala-compiled multi-dimensional arrays: JMH's
    // `ClassInfoRepo.get` rejects the array-of-array descriptor that
    // `Array[Array[Any]]` (used in SortComparatorBench's row buffer)
    // compiles to. The reflection generator goes through the JVM's
    // class loader, which handles those descriptors natively. The trade-
    // off is a heavier scan — the JVM must `Class.forName` each bench
    // class — but the bench surface here is small enough that the
    // generator finishes in well under a second.
    //
    // Argument order matches the upstream usage string:
    //   <compiled-bytecode-dir> <output-source-dir> <output-resource-dir> [type]
    try {
      JmhBytecodeGenerator.main(Array(
        classesDir.toString,
        sourcesDir.toString,
        resourcesDir.toString,
        "reflection"))
    } finally {
      Thread.currentThread().setContextClassLoader(previousLoader)
    }

    // Step 3: compile the generated Java sources. JMH's generator emits
    // *_jmhTest.java alongside its META-INF resources. If no
    // @Benchmark methods were discovered, sourcesDir will be empty —
    // the downstream Runner.run() will surface that as "no matching
    // benchmarks", which the verification gate notices.
    val javaSources = collectJavaSources(sourcesDir)
    if (javaSources.nonEmpty) {
      val compiler = ToolProvider.getSystemJavaCompiler
      if (compiler == null)
        throw new IllegalStateException(
          "No JavaCompiler available — must run on a JDK, not a JRE")
      val fileManager = compiler.getStandardFileManager(null, null, null)
      try {
        fileManager.setLocation(
          StandardLocation.CLASS_OUTPUT,
          java.util.Collections.singletonList(generatedClassesDir.toFile))
        fileManager.setLocation(
          StandardLocation.CLASS_PATH,
          classpathEntries(classesDir, classpathJars))
        val srcFiles = new java.util.ArrayList[java.io.File](javaSources.length)
        javaSources.foreach(p => srcFiles.add(p.toFile))
        val units = fileManager.getJavaFileObjectsFromFiles(srcFiles)
        // `--release 11` targets a class file version (55) that any JVM
        // running this project can load. The scala compiler emits class
        // version 52 (Java 8); the host `javac` running under Bazel's
        // JDK 21 sandbox would otherwise default to version 65, breaking
        // any developer who runs the deploy jar against an older system
        // JVM. Java 11 is below Bazel's pinned 21 toolchain but above
        // the project's documented JDK 21 floor — fine for tooling
        // bytecode that needs to load on any JVM the bench is run on.
        val opts = new java.util.ArrayList[String]()
        opts.add("--release")
        opts.add("11")
        val task = compiler.getTask(null, fileManager, null, opts, null, units)
        val ok = task.call().booleanValue()
        if (!ok)
          throw new IllegalStateException(
            "Failed to compile JMH-generated Java sources")
      } finally fileManager.close()
    }

    // Step 4: pack everything into one jar. Order: original classes
    // first so any name collision keeps the original's class file; then
    // generated classes; then META-INF resources from the JMH generator
    // (BenchmarkList, CompilerHints, ...).
    val out = new JarOutputStream(
      new BufferedOutputStream(new FileOutputStream(output)))
    try {
      addDirectoryToJar(classesDir.toFile, classesDir.toFile, out)
      addDirectoryToJar(generatedClassesDir.toFile, generatedClassesDir.toFile, out)
      addDirectoryToJar(resourcesDir.toFile, resourcesDir.toFile, out)
    } finally out.close()
  }

  /** Collect every `.java` file under `root` (recursive). */
  private def collectJavaSources(root: Path): Seq[Path] = {
    if (!Files.isDirectory(root)) return Seq.empty
    val buf = scala.collection.mutable.ArrayBuffer.empty[Path]
    val stream = Files.walk(root)
    try {
      val it = stream.iterator()
      while (it.hasNext) {
        val p = it.next()
        if (Files.isRegularFile(p) && p.toString.endsWith(".java")) buf += p
      }
    } finally stream.close()
    buf.toSeq
  }

  /** Build the javac classpath. Includes the extracted bench classes
    * directory (so the JMH wrappers can resolve `@Benchmark` target
    * types), every `--classpath-jar` (so the wrappers can resolve
    * transitive types like `com.transformer.core.ColumnarBatch`), plus
    * everything on this JVM's classpath (so JMH infra resolves). */
  private def classpathEntries(
      benchClassesDir: Path,
      classpathJars: java.util.List[String]): java.util.List[java.io.File] = {
    val list = new java.util.ArrayList[java.io.File]()
    list.add(benchClassesDir.toFile)
    val it = classpathJars.iterator()
    while (it.hasNext) list.add(new java.io.File(it.next()))
    val cp = System.getProperty("java.class.path")
    if (cp != null) {
      cp.split(java.io.File.pathSeparator).foreach { e =>
        if (e.nonEmpty) list.add(new java.io.File(e))
      }
    }
    list
  }

  /** Extract every entry of `jarFile` into `outDir`. */
  private def unzipJar(jarFile: java.io.File, outDir: Path): Unit = {
    val jar = new JarFile(jarFile)
    try {
      val entries = jar.entries()
      while (entries.hasMoreElements) {
        val e = entries.nextElement()
        val target = outDir.resolve(e.getName)
        if (e.isDirectory) {
          Files.createDirectories(target)
        } else {
          if (target.getParent != null) Files.createDirectories(target.getParent)
          val in = jar.getInputStream(e)
          try Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING)
          finally in.close()
        }
      }
    } finally jar.close()
  }

  /** Walk a directory and write every file as a jar entry rooted at
    * `root` (so the entry name is the path relative to `root`). Skips the
    * original library's `MANIFEST.MF` so the downstream `scala_binary`
    * stamps its own. */
  private def addDirectoryToJar(
      root: java.io.File,
      current: java.io.File,
      out: JarOutputStream): Unit = {
    if (!current.exists()) return
    val children = current.listFiles()
    if (children == null) return
    children.foreach { c =>
      if (c.isDirectory) addDirectoryToJar(root, c, out)
      else {
        val name = root.toPath.relativize(c.toPath).toString
          .replace(java.io.File.separatorChar, '/')
        if (name != "META-INF/MANIFEST.MF") {
          val entry = new JarEntry(name)
          entry.setTime(0L) // reproducible
          out.putNextEntry(entry)
          val in = new FileInputStream(c)
          try in.transferTo(out)
          finally in.close()
          out.closeEntry()
        }
      }
    }
  }
}
