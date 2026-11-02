package com.transformer.cli

import org.jline.reader.impl.DefaultParser
import org.jline.reader.{Candidate, Completer, EOFError, EndOfFileException, LineReader, LineReaderBuilder, ParsedLine,
  Parser, UserInterruptException}
import org.jline.terminal.{Terminal, TerminalBuilder}

import java.io.{IOException, OutputStream, PrintStream}
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** The `transformer query` shell: statements typed at a terminal, run one
  * input at a time in a [[QuerySession]].
  *
  * An input is whole when every statement in it ends with `;`, or it is a dot
  * command; until then Enter starts another line of it, so a statement over
  * several lines is one input and one entry of the history, which is kept in
  * a file across sessions. Tab completes the names a statement can read, SQL's
  * keywords and the dot commands.
  *
  * An input runs on a thread of its own while the shell waits. Ctrl-C stops
  * it: every scan of the running statement stops at its next batch, the
  * statement prints nothing more and writes no file, and the prompt returns
  * when it has stopped. Work already holding its rows, such as a sort of what
  * it has read, runs to its end first; a second Ctrl-C returns to the prompt
  * without waiting for that. */
object Shell {

  val Prompt = "transformer> "
  val ContinuationPrompt = "        ...> "

  val Banner: String =
    "transformer query: SQL over files. End each statement with ;. " +
      ".tables lists what SQL can read, .help the shell's commands, .quit (or Ctrl-D) leaves."

  /** Read inputs from `terminal` and run each in `session` until `.quit` or
    * the end of input. Errors print on `err`. */
  def run(session: QuerySession, terminal: Terminal, err: PrintStream, history: Option[Path]): Unit = {
    val out = new PrintStream(terminal.output(), true, terminal.encoding())
    val builder = LineReaderBuilder.builder()
      .terminal(terminal)
      .appName("transformer")
      .parser(new StatementParser)
      .completer(new NameCompleter(session))
      .variable(LineReader.SECONDARY_PROMPT_PATTERN, ContinuationPrompt)
      // `!` is SQL's, not a history event: `a != 1`.
      .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
      .option(LineReader.Option.CASE_INSENSITIVE, true)
    history.foreach(h => builder.variable(LineReader.HISTORY_FILE, h))
    val reader = builder.build()
    out.println(Banner)
    try {
      var reading = true
      while (reading && !session.quit) {
        try {
          val input = reader.readLine(Prompt)
          if (input.trim.nonEmpty) runInput(session, terminal, input, out, err)
        } catch {
          case _: UserInterruptException => () // Ctrl-C while typing drops the input
          case _: EndOfFileException => reading = false
        }
      }
    } finally {
      try reader.getHistory.save() catch { case _: IOException => () }
    }
  }

  private def runInput(session: QuerySession, terminal: Terminal, input: String, out: PrintStream,
      err: PrintStream): Unit = {
    val cancel = new Cancel
    val io = new Io(gated(out, cancel), gated(err, cancel), cancel)
    session.width = Some(terminal.getColumns).filter(_ > 0)
    val worker = new Thread(() => { session.runScript(input, io); () }, "transformer-query")
    worker.setDaemon(true)
    val interrupts = new AtomicInteger
    val previous = terminal.handle(Terminal.Signal.INT, _ => { interrupts.incrementAndGet(); cancel.cancel() })
    try {
      worker.start()
      while (worker.isAlive && interrupts.get < 2) worker.join(50)
    } finally terminal.handle(Terminal.Signal.INT, previous)
    out.flush()
    if (cancel.isCancelled)
      err.println(
        if (worker.isAlive) "Cancelled; the statement finishes the work it holds in the background" else "Cancelled")
  }

  /** `to`, until `cancel`: a stopped statement prints nothing more. */
  private def gated(to: PrintStream, cancel: Cancel): PrintStream =
    new PrintStream(new OutputStream {
      def write(b: Int): Unit = if (!cancel.isCancelled) to.write(b)
      override def write(b: Array[Byte], off: Int, len: Int): Unit = if (!cancel.isCancelled) to.write(b, off, len)
      override def flush(): Unit = to.flush()
    }, true, to.charset())

  /** A terminal for the shell on standard input and output. Where one can be
    * had without native code (macOS, Linux) it is, so no JDK warns of native
    * access. */
  def systemTerminal(): Terminal = TerminalBuilder.builder()
    .system(true)
    .providers(if (isWindows) "jni,ffm" else "exec,jni,ffm")
    .dumb(true)
    .build()

  /** The width of the terminal standard output writes to, if it says. */
  def terminalWidth(): Option[Int] =
    try {
      val t = systemTerminal()
      try Some(t.getColumns).filter(_ > 0) finally t.close()
    } catch { case _: IOException => None }

  private def isWindows: Boolean = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")

  /** Lines as the shell reads them: a line that leaves a statement without
    * its `;` asks for another, and a backslash is a character of SQL, not an
    * escape (JLine's default would drop it from the line it returns). */
  private final class StatementParser extends DefaultParser {
    override def isEscapeChar(ch: Char): Boolean = false

    override def parse(line: String, cursor: Int, context: Parser.ParseContext): ParsedLine = {
      if (context == Parser.ParseContext.ACCEPT_LINE && !SqlScript.complete(line))
        throw new EOFError(-1, -1, "a statement ends with ;")
      super.parse(line, cursor, context)
    }
  }

  /** Dot commands at the start of a line; else the names a statement can
    * read, and SQL's keywords. */
  private final class NameCompleter(session: QuerySession) extends Completer {
    def complete(reader: LineReader, line: ParsedLine, candidates: java.util.List[Candidate]): Unit = {
      val words =
        if (line.wordIndex == 0 && line.word.startsWith(".")) DotNames
        else session.names ++ Keywords
      words.foreach(w => candidates.add(new Candidate(w)))
    }
  }

  private val DotNames: Seq[String] =
    QuerySession.DotCommands.map(_._1.takeWhile(_ != ' ')) :+ ".exit"

  private val Keywords: Seq[String] = Seq(
    "SELECT", "FROM", "WHERE", "GROUP", "BY", "HAVING", "ORDER", "LIMIT", "OFFSET", "JOIN", "LEFT", "RIGHT",
    "FULL", "INNER", "OUTER", "CROSS", "ON", "AS", "AND", "OR", "NOT", "NULL", "IS", "IN", "BETWEEN", "LIKE",
    "CASE", "WHEN", "THEN", "ELSE", "END", "DISTINCT", "UNION", "ALL", "WITH", "EXPLAIN", "CAST", "COUNT", "SUM",
    "AVG", "MIN", "MAX", "OVER", "PARTITION", "ASC", "DESC", "NULLS", "FIRST", "LAST", "CREATE", "REPLACE", "VIEW",
    "DROP", "COPY", "TO", "FORMAT")
}
