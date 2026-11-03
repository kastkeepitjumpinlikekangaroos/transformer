package com.transformer.fuzz

/** The text past ASCII the SQL corpora draw: one pool, shared by [[DataGen]],
  * [[MetaQueryGen]], [[ExprGen]] and [[SqlTextGen]]'s catalog, of the
  * characters the engine's string rules are about. `LENGTH` and `SUBSTRING`
  * count code points, strings order by UTF-16 code unit, `UPPER` and `LOWER`
  * map one character to one and `ILIKE` folds a character at a time, and
  * none of that shows over ASCII, which was all the SQL corpora drew.
  *
  * Each [[TextClass]] sits in [[Values]] beside the ASCII neighbours it is
  * easy to confuse with (`e`, and `e` with a combining acute accent, beside
  * the precomposed e acute; `SS` beside a sharp s; `I` and `i` beside a
  * dotless i), so a contiguous
  * slice, the way every generator draws a column's values, makes them meet
  * as keys, in comparisons and in a LIKE.
  *
  * Every value is built from code points, never written as a backslash-u
  * escape: an editing tool decodes such an escape into the raw character,
  * and the repository's source is ASCII. Half a surrogate pair is not drawn:
  * the parquet writer, and so spill, writes one as `?` where `CsvWriter`
  * refuses it, so one would fail the spill mode on that alone.
  */
object TextPool {

  /** The string of `codePoints`. */
  def text(codePoints: Int*): String = new String(codePoints.toArray, 0, codePoints.length)

  /** A kind of character a string rule is about, and the code points that
    * make a string one of it. */
  sealed abstract class TextClass(val description: String, val codePoints: Set[Int]) {
    def in(s: String): Boolean = s.codePoints().anyMatch(c => codePoints(c))
    override def toString: String = description
  }

  object TextClass {
    /** Two bytes in UTF-8 and one UTF-16 unit: what anything that counts
      * bytes gets wrong. */
    case object TwoBytes extends TextClass("two UTF-8 bytes", Set(0xe9, 0xfc))
    /** A sharp s, a dotted capital I and the ligature fi, whose full case
      * mapping is two characters: the ILIKE split plans/testing/15 found, and
      * `LENGTH(UPPER(s))`. */
    case object CaseChangesLength extends TextClass("a full case map of two characters", Set(0xdf, 0x130, 0xfb01))
    /** A dotless i, which a Turkish locale pairs with `I`: no locale may move
      * it, `--config=zone` among them. */
    case object DotlessI extends TextClass("a dotless i", Set(0x131))
    /** `e` and a combining acute accent: two code points, one character on
      * screen, so `LENGTH` is 2 and `SUBSTRING` may part them. */
    case object CombiningMark extends TextClass("a combining mark", Set(0x301))
    /** A no-break space, a tab and an em space: spaces `TRIM` keeps, since it
      * strips U+0020 alone. */
    case object SpacesTrimKeeps extends TextClass("a space TRIM keeps", Set(0xa0, 0x09, 0x2003))
    /** U+E000 and U+FFFD, which UTF-16 orders above a character past U+FFFF
      * and code points below it. */
    case object HighBmp extends TextClass("a character from U+E000 to U+FFFF", Set(0xe000, 0xfffd))
    /** One code point, two UTF-16 units: `LENGTH` 1, LIKE `_` matches one, and
      * `SUBSTRING` never splits it. */
    case object PastBmp extends TextClass("a character past U+FFFF", Set(0x1f600, 0x1d11e))
    /** Fullwidth Latin letters, whose case maps outside Latin-1. */
    case object Fullwidth extends TextClass("a fullwidth letter", Set(0xff21, 0xff41))

    val All: Seq[TextClass] =
      Seq(TwoBytes, CaseChangesLength, DotlessI, CombiningMark, SpacesTrimKeeps, HighBmp, PastBmp, Fullwidth)
  }

  private val Smile = 0x1f600
  private val Clef = 0x1d11e

  /** The pool, each class beside its ASCII neighbours, in the order a slice
    * keeps them together. */
  val Values: IndexedSeq[String] = IndexedSeq(
    "e", text(0xe9), text(0x65, 0x301), text(0xfc), "u",
    "ss", text(0xdf), "SS", text(0xfb01), "fi",
    "i", text(0x130), "I", text(0x131),
    " a", text(0x61, 0xa0), text(0x09, 0x61), text(0x61, 0x2003), "a ",
    text(0xe000), text(0xfffd), text(Smile), text(0x61, Smile), text(Clef),
    "A", text(0xff21), text(0xff41), "a")

  /** LIKE patterns over the pool: `_` and `__` against one code point and
    * two, letters whose case the pool varies, and its characters beside a
    * wildcard. */
  val Patterns: IndexedSeq[String] = IndexedSeq(
    "_", "__", "SS", "ss", "S%", "%i%", "I", text(0x130), text(0xdf), text(0xe9, 0x25), "e_", "%" + text(0xfffd),
    "_" + text(Smile), text(0xff21, 0x25), "%" + text(0x301), "a_")

  /** `n` neighbouring values of the pool, from 2 to `widest`. */
  def slice(rng: Rng, widest: Int): IndexedSeq[String] = {
    val w = rng.between(2, widest)
    val start = rng.between(0, Values.length - w)
    Values.slice(start, start + w)
  }

  /** The classes `s` holds. */
  def classesOf(s: String): Set[TextClass] = TextClass.All.filter(_.in(s)).toSet

  /** The classes a column of `cells` holds: each a string of the class, and
    * [[TextClass.HighBmp]] only beside a character past U+FFFF, the pair
    * the two orders disagree on. */
  def columnClasses(cells: Iterable[Any]): Set[TextClass] = {
    val strings = cells.iterator.collect { case s: String => s }.toVector
    val found = strings.iterator.flatMap(classesOf).toSet
    if (found(TextClass.HighBmp) && !found(TextClass.PastBmp)) found - TextClass.HighBmp else found
  }

  /** Whether `c`'s capital and small forms are not one pair: the small form
    * of its capital is not its small form, or the capital of its small form
    * is not its capital. A dotless i (its capital is `I`, whose small form is
    * `i`) and a dotted capital I (its small form is `i`, whose capital is `I`)
    * are, as are the Kelvin sign and the long s. `ILIKE` folds `c` to the
    * small form of its capital, `LOWER` to its small form and `UPPER` to its
    * capital, so over such a character the three spellings of a
    * case-insensitive match part. */
  def unpairedCase(c: Int): Boolean =
    Character.toLowerCase(Character.toUpperCase(c)) != Character.toLowerCase(c) ||
      Character.toUpperCase(Character.toLowerCase(c)) != Character.toUpperCase(c)

  /** Whether `s` holds a character of [[unpairedCase]]. */
  def holdsUnpairedCase(s: String): Boolean = s.codePoints().anyMatch(c => unpairedCase(c))

  // ---- coverage ------------------------------------------------------------

  /** The string sites the classes must reach in each SQL corpus: the
    * functions that count, cut and map case, the two LIKEs, and the
    * operators that order and group strings. */
  val Sites: Seq[String] = Seq("LENGTH", "SUBSTRING", "UPPER", "LOWER", "LIKE", "ILIKE", "ORDER BY", "GROUP BY", "MIN/MAX")

  /** Over a block of seeds, on how many each class reached each site. */
  final case class Tally(seeds: Int, counts: Map[(String, TextClass), Int]) {
    def apply(site: String, c: TextClass): Int = counts.getOrElse((site, c), 0)

    /** One line per site, a count per class. */
    def report: String = Sites.map { site =>
      f"  $site%-9s " + TextClass.All.map(c => f"${apply(site, c)}%5d").mkString
    }.mkString(s"over $seeds seeds, by class (${TextClass.All.mkString(" | ")}):\n", "\n", "")

    /** The cells under `floor`, each as `site / class = count`. */
    def below(floor: Int): Seq[String] = for {
      site <- Sites; c <- TextClass.All if apply(site, c) <= floor
    } yield s"$site / $c = ${apply(site, c)}"
  }

  /** The tally of `reached` over seeds `0 until n`: each seed's set of
    * (site, class) pairs. */
  def tally(n: Int)(reached: Long => Set[(String, TextClass)]): Tally = {
    val counts = scala.collection.mutable.Map.empty[(String, TextClass), Int].withDefaultValue(0)
    (0 until n).foreach(seed => reached(seed.toLong).foreach(k => counts(k) += 1))
    Tally(n, counts.toMap)
  }
}
