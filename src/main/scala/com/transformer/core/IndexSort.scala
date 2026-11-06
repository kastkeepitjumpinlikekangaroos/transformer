package com.transformer.core

/** Orders two rows a sort holds, each named by an `Int`: negative when `a`
  * sorts before `b`, zero when they tie, positive after. A sort's comparison
  * of typed keys, through no box ([[IndexSort]]). */
trait IntComparator {
  def compare(a: Int, b: Int): Int
}

/** A stable sort of `Int`s by an [[IntComparator]]: the rows a sort holds,
  * ordered without moving them. A merge sort, as `java.util.Arrays.sort` of
  * objects once was: insertion sort below [[InsertionBelow]] elements, no
  * merge where two sorted halves are already in order, and an auxiliary array
  * of as many ints as it sorts. Stable, so rows that tie keep the order they
  * are given in; and a stable sort's order under one comparator is the only
  * one, so this orders as TimSort (`Arrays.sort` of boxed indices) does,
  * without the boxes. */
object IndexSort {

  /** Below this many elements a range is sorted by insertion. */
  val InsertionBelow: Int = 32

  /** Sort `perm(0)` to `perm(n - 1)` by `cmp`, stably. */
  def sort(perm: Array[Int], n: Int, cmp: IntComparator): Unit = {
    require(n >= 0 && n <= perm.length, s"cannot sort $n of ${perm.length} elements")
    if (n < 2) return
    val aux = java.util.Arrays.copyOf(perm, n)
    mergeSort(aux, perm, 0, n, cmp)
  }

  /** Sort `src(lo)` to `src(hi - 1)` into `dst`, where both hold the same
    * elements on entry; each half is sorted into `src` first, the roles
    * swapping at each level, so no level copies back. */
  private def mergeSort(src: Array[Int], dst: Array[Int], lo: Int, hi: Int, cmp: IntComparator): Unit = {
    val len = hi - lo
    if (len < InsertionBelow) {
      var i = lo + 1
      while (i < hi) {
        val v = dst(i)
        var j = i
        while (j > lo && cmp.compare(dst(j - 1), v) > 0) {
          dst(j) = dst(j - 1)
          j -= 1
        }
        dst(j) = v
        i += 1
      }
      return
    }
    val mid = (lo + hi) >>> 1
    mergeSort(dst, src, lo, mid, cmp)
    mergeSort(dst, src, mid, hi, cmp)
    // The halves are in order already: the right one starts at or after the
    // left one's end.
    if (cmp.compare(src(mid - 1), src(mid)) <= 0) {
      System.arraycopy(src, lo, dst, lo, len)
      return
    }
    // A tie takes the left half's element first, which keeps the sort stable.
    var i = lo
    var j = mid
    var k = lo
    while (k < hi) {
      if (j >= hi || (i < mid && cmp.compare(src(i), src(j)) <= 0)) {
        dst(k) = src(i)
        i += 1
      } else {
        dst(k) = src(j)
        j += 1
      }
      k += 1
    }
  }
}
