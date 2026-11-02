package com.transformer.bytecode

/** What the reader's test looks for, each in the method named for it. None of
  * it runs: [[ClassFileTest]] reads this object's class file. */
object ClassFileFixture {

  def callsCompare(a: Double, b: Double): Int = java.lang.Double.compare(a, b)

  def comparesTwoValues(a: Double, b: Double): Boolean = a < b

  def comparesWithZero(a: Double): Boolean = a > 0.0

  def comparesWithZeroOnTheLeft(a: Double): Boolean = 0.0 < a

  /** `Double.<(Int)`: the 3 is pushed as an Int and widened by `i2d`. */
  def comparesWithAWidenedInt(a: Double): Boolean = a > 3

  /** A string `match` compiles to a `lookupswitch` on the string's
    * `hashCode`, then `equals` against each case. */
  def matchesAString(s: String): Int = s match {
    case "alpha" => 1
    case "beta"  => 2
    case "gamma" => 3
    case _       => 0
  }

  /** The comparison is the lambda's: `$anonfun$sortsInALambda$1`. */
  def sortsInALambda(xs: Seq[Double]): Seq[Double] = xs.sortWith((a, b) => java.lang.Double.compare(a, b) < 0)

  def readsAString: String = "a string constant"

  def readsAField: java.io.PrintStream = System.err
}
