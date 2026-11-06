package com.transformer.core

import org.junit.Assert._
import org.junit.Test

import scala.util.Random

/** [[IndexSort]] orders as `Arrays.sort` of boxed indices does under the same
  * comparator: a stable sort's order under one comparator is the only one,
  * so the permutations are equal, ties included. Over 0 to 10^5 elements,
  * heavy ties, input already in order and reversed, and the lengths around
  * the insertion sort's cutoff. */
class IndexSortTest {

  /** Rows `0 until n` ordered by `keys(row)`, ties left to the sort. */
  private def byKeys(keys: Array[Int]): IntComparator = new IntComparator {
    def compare(a: Int, b: Int): Int = Integer.compare(keys(a), keys(b))
  }

  private def check(label: String, keys: Array[Int], start: Array[Int]): Unit = {
    val cmp = byKeys(keys)
    val boxed = start.map(Integer.valueOf)
    java.util.Arrays.sort(boxed, (a: Integer, b: Integer) => cmp.compare(a.intValue, b.intValue))
    val perm = start.clone()
    IndexSort.sort(perm, perm.length, cmp)
    assertArrayEquals(label, boxed.map(_.intValue), perm)
  }

  private def identity(n: Int): Array[Int] = Array.tabulate(n)(i => i)

  @Test def ordersAsAStableSortOfBoxedIndices(): Unit = {
    val rng = new Random(15)
    val sizes = Seq(0, 1, 2, 3, 7, 31, 32, 33, 63, 64, 65, 100, 1000, 4095, 4096, 4097, 65537, 100000)
    for (n <- sizes; domain <- Seq(1, 2, 3, 10, n max 1, Int.MaxValue)) {
      val keys = Array.fill(n)(if (domain == Int.MaxValue) rng.nextInt() else rng.nextInt(domain))
      check(s"n $n, $domain keys, in order", keys, identity(n))
      check(s"n $n, $domain keys, reversed", keys, identity(n).reverse)
      check(s"n $n, $domain keys, shuffled", keys, rng.shuffle(identity(n).toSeq).toArray)
    }
  }

  @Test def inputAlreadySortedOrReversedByKey(): Unit = {
    for (n <- Seq(5, 32, 33, 1000, 100000)) {
      val ascending = Array.tabulate(n)(i => i / 3)
      check(s"n $n ascending keys", ascending, identity(n))
      val descending = Array.tabulate(n)(i => (n - i) / 3)
      check(s"n $n descending keys", descending, identity(n))
      val sawtooth = Array.tabulate(n)(i => i % 17)
      check(s"n $n sawtooth keys", sawtooth, identity(n))
    }
  }

  /** Only the first `n` elements are sorted; the rest are left alone. */
  @Test def sortsAPrefixOfTheArray(): Unit = {
    val keys = Array(5, 4, 3, 2, 1, 0)
    val perm = identity(6)
    IndexSort.sort(perm, 4, byKeys(keys))
    assertArrayEquals(Array(3, 2, 1, 0, 4, 5), perm)
    assertThrows(classOf[IllegalArgumentException], () => IndexSort.sort(perm, 7, byKeys(keys)))
  }

  /** Every element compares by the comparator alone: two rows the comparator
    * ties keep their order whatever their numbers. */
  @Test def tiesKeepTheOrderTheyAreGivenIn(): Unit = {
    val n = 5000
    val start = new Random(7).shuffle(identity(n).toSeq).toArray
    val perm = start.clone()
    IndexSort.sort(perm, n, new IntComparator { def compare(a: Int, b: Int): Int = 0 })
    assertArrayEquals(start, perm)
  }
}
