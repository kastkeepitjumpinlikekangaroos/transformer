package com.transformer.gui

import com.transformer.gui.VimKey._

/** A normal- or visual-mode command as [[VimParser]] reads it from the keys:
  * `["x][count]` and an [[VimCommand.Action]]. */
private[gui] object VimCommand {

  /** How an operator reads the text between the cursor and where a motion
    * lands: up to it, through it, or as whole lines. */
  sealed trait Kind
  object Kind {
    case object Exclusive extends Kind
    case object Inclusive extends Kind
    case object Linewise extends Kind
  }

  sealed trait Motion
  object Motion {
    case object Left extends Motion
    case object Right extends Motion
    /** `<BS>`: left, on to the end of the line above. */
    case object LeftWrap extends Motion
    /** `<Space>`: right, on to the start of the line below. */
    case object RightWrap extends Motion
    case object Up extends Motion
    case object Down extends Motion
    case object LineStart extends Motion
    case object FirstNonBlank extends Motion
    case object LineEnd extends Motion
    case object LastNonBlank extends Motion
    case object NextLine extends Motion
    case object PrevLine extends Motion
    case object CurrentLine extends Motion
    case object FileStart extends Motion
    case object FileEnd extends Motion
    final case class Word(big: Boolean) extends Motion
    final case class WordBack(big: Boolean) extends Motion
    final case class WordEnd(big: Boolean) extends Motion
    final case class WordEndBack(big: Boolean) extends Motion
    /** `f`, `F`, `t` or `T` (the `kind`) and the character. */
    final case class Find(kind: Char, c: Char) extends Motion
    case object RepeatFind extends Motion
    case object RepeatFindBack extends Motion
    case object MatchPair extends Motion
    case object ParagraphForward extends Motion
    case object ParagraphBack extends Motion
    case object ScreenTop extends Motion
    case object ScreenMiddle extends Motion
    case object ScreenBottom extends Motion
    case object SearchNext extends Motion
    case object SearchPrev extends Motion
    final case class Star(forward: Boolean) extends Motion
    final case class Search(pattern: String, forward: Boolean) extends Motion
    /** `/` or `?` typed: the pattern comes from the command line. */
    final case class SearchPrompt(forward: Boolean) extends Motion
    /** `<C-d>`, `<C-u>`, `<C-f>`, `<C-b>`: the cursor moves by part of a
      * screen. Not an operator's motion. */
    final case class Page(down: Boolean, half: Boolean) extends Motion
  }

  sealed trait Op
  object Op {
    case object Delete extends Op
    case object Change extends Op
    case object Yank extends Op
    case object ShiftRight extends Op
    case object ShiftLeft extends Op
    case object ToggleCase extends Op
    case object Lower extends Op
    case object Upper extends Op
  }

  /** A text object: `w`, `W`, `p`, a bracket or a quote, inner or not. */
  final case class Obj(kind: Char, inner: Boolean)

  sealed trait Target
  object Target {
    final case class ByMotion(m: Motion) extends Target
    final case class ByObject(obj: Obj) extends Target
    /** The operator doubled (`dd`, `>>`, `gUU`): `count` lines. */
    case object ByLines extends Target
  }

  sealed trait Action
  object Action {
    final case class Move(m: Motion) extends Action
    final case class Operate(op: Op, target: Target) extends Action
    /** `i`, `a`, `I`, `A`, `o`, `O`; `0` for `gI` and `` ` `` for `gi`. */
    final case class InsertAt(how: Char) extends Action
    final case class Put(before: Boolean, cursorAfter: Boolean) extends Action
    /** `r` and the key to put: a character or `<CR>`. */
    final case class ReplaceChar(with_ : VimKey) extends Action
    final case class Join(spaces: Boolean) extends Action
    case object ToggleCaseChar extends Action
    case object ReplaceMode extends Action
    case object Undo extends Action
    case object Redo extends Action
    case object RepeatChange extends Action
    final case class StartVisual(linewise: Boolean) extends Action
    case object Reselect extends Action
    final case class CmdLine(kind: Char) extends Action
    final case class Scroll(where: VimScroll, toFirstNonBlank: Boolean) extends Action
    final case class Record(register: Char) extends Action
    final case class Play(register: Char) extends Action
    final case class Increment(sign: Int) extends Action
    case object WriteQuit extends Action
    case object ForceQuit extends Action
    case object RepeatSubstitute extends Action
    // Visual mode.
    final case class VisualOperate(op: Op, linewise: Boolean) extends Action
    final case class SelectObject(obj: Obj) extends Action
    final case class VisualPut(keepRegister: Boolean) extends Action
    final case class VisualReplace(with_ : VimKey) extends Action
    final case class VisualJoin(spaces: Boolean) extends Action
    final case class VisualSwitch(linewise: Boolean) extends Action
    case object SwapEnds extends Action
  }

  final case class Cmd(register: Option[Char], count: Option[Int], action: Action) {
    def n: Int = count.getOrElse(1)
  }
}

/** Reads the keys typed so far in normal or visual mode as one command, or
  * says more keys are needed, or that they make no command. The engine keeps
  * the keys of the command being typed and asks again after each one. */
private[gui] object VimParser {
  import VimCommand._
  import VimCommand.Action._

  sealed trait Result
  case object Incomplete extends Result
  case object Invalid extends Result
  final case class Complete(cmd: Cmd) extends Result

  /** The registers a `"x` prefix may name. */
  val RegisterNames: String = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789\"-_+*./:"

  private val ObjectKinds = "wWp()b[]{}B<>\"'`"

  def parse(keys: IndexedSeq[VimKey], visual: Boolean): Result = new Run(keys, visual).command()

  private sealed trait MotionRead
  private final case class Got(m: Motion) extends MotionRead
  private case object NeedMore extends MotionRead
  private case object NotMotion extends MotionRead

  private final class Run(keys: IndexedSeq[VimKey], visual: Boolean) {
    private var i = 0

    private def peek: Option[VimKey] = if (i < keys.length) Some(keys(i)) else None
    private def next(): Option[VimKey] = {
      val k = peek
      if (k.isDefined) i += 1
      k
    }

    private def count(): Option[Int] = {
      var n: Option[Int] = None
      var going = true
      while (going) {
        peek match {
          case Some(Typed(c)) if c.isDigit && (c != '0' || n.isDefined) =>
            i += 1
            n = Some(math.min(n.getOrElse(0) * 10 + (c - '0'), 99999))
          case _ => going = false
        }
      }
      n
    }

    private def times(a: Option[Int], b: Option[Int]): Option[Int] =
      if (a.isEmpty && b.isEmpty) None else Some(math.min(a.getOrElse(1) * b.getOrElse(1), 99999))

    def command(): Result = {
      var register: Option[Char] = None
      if (peek.contains(Typed('"'))) {
        i += 1
        next() match {
          case None => return Incomplete
          case Some(Typed(r)) if RegisterNames.indexOf(r) >= 0 => register = Some(r)
          case _ => return Invalid
        }
      }
      val c1 = count()
      val start = i
      def done(a: Action): Result = Complete(Cmd(register, c1, a))
      if (peek.isEmpty) return Incomplete
      motion(forOperator = false) match {
        case Got(m) => return done(Move(m))
        case NeedMore => return Incomplete
        case NotMotion => i = start
      }
      val k = next().get
      if (visual) visualCommand(k, register, c1) else normalCommand(k, register, c1)
    }

    private def normalCommand(k: VimKey, register: Option[Char], c1: Option[Int]): Result = {
      def done(a: Action): Result = Complete(Cmd(register, c1, a))
      def op(o: Op, doubled: Char): Result = operatorTail(o, doubled, register, c1)
      import Target._
      k match {
        case Typed('d') => op(Op.Delete, 'd')
        case Typed('c') => op(Op.Change, 'c')
        case Typed('y') => op(Op.Yank, 'y')
        case Typed('>') => op(Op.ShiftRight, '>')
        case Typed('<') => op(Op.ShiftLeft, '<')
        case Typed('x') | Delete => done(Operate(Op.Delete, ByMotion(Motion.Right)))
        case Typed('X') => done(Operate(Op.Delete, ByMotion(Motion.Left)))
        case Typed('s') => done(Operate(Op.Change, ByMotion(Motion.Right)))
        case Typed('S') => done(Operate(Op.Change, ByLines))
        case Typed('D') => done(Operate(Op.Delete, ByMotion(Motion.LineEnd)))
        case Typed('C') => done(Operate(Op.Change, ByMotion(Motion.LineEnd)))
        case Typed('Y') => done(Operate(Op.Yank, ByLines))
        case Typed('p') => done(Put(before = false, cursorAfter = false))
        case Typed('P') => done(Put(before = true, cursorAfter = false))
        case Typed('J') => done(Join(spaces = true))
        case Typed('~') => done(ToggleCaseChar)
        case Typed('u') => done(Undo)
        case Ctrl('r') => done(Redo)
        case Typed('.') => done(RepeatChange)
        case Typed(c @ ('i' | 'a' | 'I' | 'A' | 'o' | 'O')) => done(InsertAt(c))
        case Typed('R') => done(ReplaceMode)
        case Typed('v') => done(StartVisual(linewise = false))
        case Typed('V') => done(StartVisual(linewise = true))
        case Typed(':') => done(CmdLine(':'))
        case Typed('&') => done(RepeatSubstitute)
        case Ctrl('a') => done(Increment(1))
        case Ctrl('x') => done(Increment(-1))
        case Typed('r') => withReplacement(r => done(ReplaceChar(r)))
        case Typed('q') =>
          next() match {
            case None => Incomplete
            case Some(Typed(r)) if r.isLetterOrDigit || r == '"' => done(Record(r))
            case _ => Invalid
          }
        case Typed('@') =>
          next() match {
            case None => Incomplete
            case Some(Typed(r)) if r.isLetterOrDigit || r == '"' || r == '@' || r == ':' => done(Play(r))
            case _ => Invalid
          }
        case Typed('Z') =>
          next() match {
            case None => Incomplete
            case Some(Typed('Z')) => done(WriteQuit)
            case Some(Typed('Q')) => done(ForceQuit)
            case _ => Invalid
          }
        case Typed('z') =>
          next() match {
            case None => Incomplete
            case Some(Typed('z')) => done(Scroll(VimScroll.Center, toFirstNonBlank = false))
            case Some(Typed('t')) => done(Scroll(VimScroll.Top, toFirstNonBlank = false))
            case Some(Typed('b')) => done(Scroll(VimScroll.Bottom, toFirstNonBlank = false))
            case Some(Enter) => done(Scroll(VimScroll.Top, toFirstNonBlank = true))
            case Some(Typed('.')) => done(Scroll(VimScroll.Center, toFirstNonBlank = true))
            case Some(Typed('-')) => done(Scroll(VimScroll.Bottom, toFirstNonBlank = true))
            case _ => Invalid
          }
        case Typed('g') =>
          next() match {
            case None => Incomplete
            case Some(Typed('~')) => op(Op.ToggleCase, '~')
            case Some(Typed('u')) => op(Op.Lower, 'u')
            case Some(Typed('U')) => op(Op.Upper, 'U')
            case Some(Typed('J')) => done(Join(spaces = false))
            case Some(Typed('p')) => done(Put(before = false, cursorAfter = true))
            case Some(Typed('P')) => done(Put(before = true, cursorAfter = true))
            case Some(Typed('v')) => done(Reselect)
            case Some(Typed('i')) => done(InsertAt('`'))
            case Some(Typed('I')) => done(InsertAt('0'))
            case _ => Invalid
          }
        case _ => Invalid
      }
    }

    private def visualCommand(k: VimKey, register: Option[Char], c1: Option[Int]): Result = {
      def done(a: Action): Result = Complete(Cmd(register, c1, a))
      k match {
        case Typed('d') | Typed('x') | Delete => done(VisualOperate(Op.Delete, linewise = false))
        case Typed('X') | Typed('D') => done(VisualOperate(Op.Delete, linewise = true))
        case Typed('c') | Typed('s') => done(VisualOperate(Op.Change, linewise = false))
        case Typed('C') | Typed('S') | Typed('R') => done(VisualOperate(Op.Change, linewise = true))
        case Typed('y') => done(VisualOperate(Op.Yank, linewise = false))
        case Typed('Y') => done(VisualOperate(Op.Yank, linewise = true))
        case Typed('>') => done(VisualOperate(Op.ShiftRight, linewise = true))
        case Typed('<') => done(VisualOperate(Op.ShiftLeft, linewise = true))
        case Typed('~') => done(VisualOperate(Op.ToggleCase, linewise = false))
        case Typed('u') => done(VisualOperate(Op.Lower, linewise = false))
        case Typed('U') => done(VisualOperate(Op.Upper, linewise = false))
        case Typed('J') => done(VisualJoin(spaces = true))
        case Typed('p') => done(VisualPut(keepRegister = false))
        case Typed('P') => done(VisualPut(keepRegister = true))
        case Typed('o') | Typed('O') => done(SwapEnds)
        case Typed('v') => done(VisualSwitch(linewise = false))
        case Typed('V') => done(VisualSwitch(linewise = true))
        case Typed(':') => done(CmdLine(':'))
        case Typed('r') => withReplacement(r => done(VisualReplace(r)))
        case Typed(c @ ('i' | 'a')) =>
          next() match {
            case None => Incomplete
            case Some(Typed(o)) if ObjectKinds.indexOf(o) >= 0 => done(SelectObject(Obj(o, inner = c == 'i')))
            case _ => Invalid
          }
        case Typed('g') =>
          next() match {
            case None => Incomplete
            case Some(Typed('~')) => done(VisualOperate(Op.ToggleCase, linewise = false))
            case Some(Typed('u')) => done(VisualOperate(Op.Lower, linewise = false))
            case Some(Typed('U')) => done(VisualOperate(Op.Upper, linewise = false))
            case Some(Typed('J')) => done(VisualJoin(spaces = false))
            case Some(Typed('v')) => done(Reselect)
            case _ => Invalid
          }
        case _ => Invalid
      }
    }

    /** `r`'s argument: a character, or `<CR>` for a line break. */
    private def withReplacement(f: VimKey => Result): Result =
      next() match {
        case None => Incomplete
        case Some(k @ Typed(_)) => f(k)
        case Some(Enter) => f(Enter)
        case _ => Invalid
      }

    /** What follows an operator: a count, then the operator again (whole
      * lines), a text object, or a motion. `doubled` is the key that repeats
      * it: `d` for `dd`, `U` for `gUU`, which also takes `gUgU`. */
    private def operatorTail(op: Op, doubled: Char, register: Option[Char], c1: Option[Int]): Result = {
      import Target._
      val c2 = count()
      val n = times(c1, c2)
      def done(t: Target): Result = Complete(Cmd(register, n, Operate(op, t)))
      val isG = op == Op.ToggleCase || op == Op.Lower || op == Op.Upper
      peek match {
        case None => Incomplete
        case Some(Typed(c)) if c == doubled => i += 1; done(ByLines)
        case Some(Typed('g')) if isG && keys.lift(i + 1).isEmpty => Incomplete
        case Some(Typed('g')) if isG && keys.lift(i + 1).contains(Typed(doubled)) => i += 2; done(ByLines)
        case Some(Typed(c @ ('i' | 'a'))) =>
          i += 1
          next() match {
            case None => Incomplete
            case Some(Typed(o)) if ObjectKinds.indexOf(o) >= 0 => done(ByObject(Obj(o, inner = c == 'i')))
            case _ => Invalid
          }
        case Some(_) =>
          motion(forOperator = true) match {
            case Got(m) => done(ByMotion(m))
            case NeedMore => Incomplete
            case NotMotion => Invalid
          }
      }
    }

    /** A motion at the current key, consuming it (and its argument). Page
      * motions move the cursor but are no operator's motion. */
    private def motion(forOperator: Boolean): MotionRead = {
      import Motion._
      val k = peek.getOrElse(return NeedMore)
      def take(m: Motion): MotionRead = { i += 1; Got(m) }
      def page(down: Boolean, half: Boolean): MotionRead = if (forOperator) NotMotion else take(Page(down, half))
      k match {
        case Typed('h') | VimKey.Left => take(Motion.Left)
        case Typed('l') | VimKey.Right => take(Motion.Right)
        case Backspace | Ctrl('h') => take(LeftWrap)
        case Typed(' ') => take(RightWrap)
        case Typed('j') | VimKey.Down | Ctrl('j') | Ctrl('n') => take(Motion.Down)
        case Typed('k') | VimKey.Up | Ctrl('p') => take(Motion.Up)
        case Typed('0') | Home => take(LineStart)
        case Typed('^') => take(FirstNonBlank)
        case Typed('$') | VimKey.End => take(LineEnd)
        case Typed('+') | Enter | Ctrl('m') => take(NextLine)
        case Typed('-') => take(PrevLine)
        case Typed('_') => take(CurrentLine)
        case Typed('G') => take(FileEnd)
        case Typed('w') => take(Word(big = false))
        case Typed('W') => take(Word(big = true))
        case Typed('b') => take(WordBack(big = false))
        case Typed('B') => take(WordBack(big = true))
        case Typed('e') => take(WordEnd(big = false))
        case Typed('E') => take(WordEnd(big = true))
        case Typed(f @ ('f' | 'F' | 't' | 'T')) =>
          keys.lift(i + 1) match {
            case None => NeedMore
            case Some(Typed(c)) => i += 2; Got(Find(f, c))
            case Some(_) => i += 2; NotMotion
          }
        case Typed(';') => take(RepeatFind)
        case Typed(',') => take(RepeatFindBack)
        case Typed('%') => take(MatchPair)
        case Typed('}') => take(ParagraphForward)
        case Typed('{') => take(ParagraphBack)
        case Typed('H') => take(ScreenTop)
        case Typed('M') => take(ScreenMiddle)
        case Typed('L') => take(ScreenBottom)
        case Typed('n') => take(SearchNext)
        case Typed('N') => take(SearchPrev)
        case Typed('*') => take(Star(forward = true))
        case Typed('#') => take(Star(forward = false))
        case Typed('/') => take(SearchPrompt(forward = true))
        case Typed('?') => take(SearchPrompt(forward = false))
        case Ctrl('d') => page(down = true, half = true)
        case Ctrl('u') => page(down = false, half = true)
        case Ctrl('f') | PageDown => page(down = true, half = false)
        case Ctrl('b') | PageUp => page(down = false, half = false)
        case Typed('g') =>
          keys.lift(i + 1) match {
            case None => NeedMore
            case Some(Typed('g')) => i += 2; Got(FileStart)
            case Some(Typed('e')) => i += 2; Got(WordEndBack(big = false))
            case Some(Typed('E')) => i += 2; Got(WordEndBack(big = true))
            case Some(Typed('_')) => i += 2; Got(LastNonBlank)
            case Some(_) => NotMotion
          }
        case _ => NotMotion
      }
    }
  }
}
