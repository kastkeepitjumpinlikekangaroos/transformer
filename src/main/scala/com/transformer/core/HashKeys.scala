package com.transformer.core

/** Allocation-light key encoding for pipeline-breaking operators that build
  * `HashMap[KeyValues, ...]` over GROUP BY / DISTINCT / JOIN / PARTITION BY keys.
  *
  * Two concrete strategies, picked automatically by [[KeyCodec.forColumns]]:
  *
  *   - All key columns are fixed-width primitive types (Int/Long/Float/Double/
  *     Boolean/Date/Timestamp) → [[PackedBytesCodec]] packs them into a small
  *     `byte[]` wrapped in a [[BytesKey]] (cached hashCode, `Arrays.equals` for
  *     equality — JIT-intrinsified to SIMD on hot loops). The fast-path
  *     [[KeyCodec.encodeFromBatch]] reads primitive arrays directly out of the
  *     [[ColumnVector]]s, avoiding the per-row boxing the old `Expr.eval`-and-
  *     `Seq.apply` path paid on every value.
  *   - At least one key column is variable-width (String / Binary / Decimal /
  *     NullType) → [[ObjectArrayCodec]] copies the boxed values into a fresh
  *     `Array[AnyRef]` wrapped in an [[ObjectArrayKey]] (cached hashCode,
  *     per-element `equals`/`hashCode` with `Array[Byte]` special-cased for
  *     structural equality).
  *
  * Two operator-facing entry points:
  *
  *   - [[KeyCodec.encodeFromBatch]] reads typed primitives directly from
  *     `batch.column(idx)` at the indices bound at construction time. Use this
  *     when every key expression is a pure column reference (the common case
  *     for GROUP BY / JOIN ON / PARTITION BY) — saves per-row boxing on the
  *     fixed-width path.
  *   - [[KeyCodec.encodeBoxed]] takes a pre-evaluated `Array[Any]` of boxed
  *     values. Use when key expressions are computed (e.g. `GROUP BY a + b`).
  *
  * NULL semantics: GROUP BY / DISTINCT / PARTITION BY group NULLs together —
  * the codec assigns a deterministic, single bucket to NULL values per column.
  * [[encodeFromBatchSkipIfAnyNull]] returns the Java `null` sentinel instead
  * of a key when any key column is NULL — for hash-join probes where SQL
  * three-valued logic says a NULL key can never match.
  *
  * Decoding [[decode]] writes a previously-encoded key back into the requested
  * output columns. The aggregate emit path uses this to materialize group keys
  * into output [[ColumnarBatch]]es alongside their aggregate results.
  *
  * Thread safety: a [[KeyCodec]] holds only immutable configuration. It is
  * safe to share across threads. The per-row buffers operators may keep
  * around [[KeyCodec.encodeBoxed]] (an `Array[Any]` of length `numKeys`) are
  * NOT thread-safe — each worker holds its own.
  */
sealed trait KeyCodec {
  def numKeys: Int

  /** Encode a key from a row of pre-evaluated boxed values. `values.length`
    * must equal [[numKeys]]. The codec does NOT retain the array — callers
    * may reuse it for the next row. */
  def encodeBoxed(values: Array[Any]): AnyRef

  /** Encode a key by reading primitives directly out of `batch`'s columns at
    * the indices bound at construction. */
  def encodeFromBatch(batch: ColumnarBatch, row: Int): AnyRef

  /** Variant of [[encodeFromBatch]] that returns `null` (the Java null) when
    * any key column is NULL at `row`. Used by hash-join probe — a NULL key
    * can never match under SQL three-valued equi-join semantics. */
  def encodeFromBatchSkipIfAnyNull(batch: ColumnarBatch, row: Int): AnyRef

  /** Write the key back into `out`'s columns `[baseCol, baseCol + numKeys)`
    * at row `outRow`. The target columns must have the same [[DataType]]s as
    * the key columns this codec was built for. */
  def decode(key: AnyRef, out: ColumnarBatch, baseCol: Int, outRow: Int): Unit
}

object KeyCodec {

  /** Build a codec bound to the given column indices and types. Both arrays
    * must have the same length. When the key spans zero columns (e.g. a
    * `GROUP BY ()` from `SELECT COUNT(*)`), returns [[EmptyKeyCodec]] which
    * collapses every encode to a singleton sentinel. */
  def forColumns(columnIndices: Array[Int], columnTypes: Array[DataType]): KeyCodec = {
    require(columnIndices.length == columnTypes.length,
      s"columnIndices.length ${columnIndices.length} != columnTypes.length ${columnTypes.length}")
    if (columnTypes.length == 0) EmptyKeyCodec
    else {
      var allPackable = true
      var i = 0
      while (i < columnTypes.length) {
        if (!isPackable(columnTypes(i))) allPackable = false
        i += 1
      }
      if (allPackable) new PackedBytesCodec(columnIndices.clone(), columnTypes.clone())
      else new ObjectArrayCodec(columnIndices.clone(), columnTypes.clone())
    }
  }

  /** Convenience overload taking `Seq`s. */
  def forColumns(columnIndices: Seq[Int], columnTypes: Seq[DataType]): KeyCodec =
    forColumns(columnIndices.toArray, columnTypes.toArray)

  /** True iff a value of `t` fits in a small fixed-width slot we can pack
    * into a `byte[]` without an interner. */
  def isPackable(t: DataType): Boolean = t match {
    case DataType.IntType | DataType.LongType | DataType.FloatType | DataType.DoubleType => true
    case DataType.BooleanType | DataType.DateType | DataType.TimestampType => true
    case _ => false
  }

  /** True iff a value of `t` round-trips through a `Long` without loss — the
    * gate for [[LongHashMap]]'s single-column primitive-key fast path. Float
    * / Double are deliberately excluded (NaN canonicalization + `-0.0` vs
    * `+0.0` make the bit-level conversion subtly diverge from `Double.equals`;
    * they use [[PackedBytesCodec]] instead). */
  def isLongFittable(t: DataType): Boolean = t match {
    case DataType.IntType | DataType.LongType | DataType.BooleanType => true
    case DataType.DateType | DataType.TimestampType => true
    case _ => false
  }

  /** Read column `col` at `row` as a primitive long. Callers must check
    * `col.isNull(row)` first — this is a NULL-unsafe primitive read. */
  def readAsLong(col: ColumnVector, row: Int): Long = col match {
    case v: LongVector      => v.values(row)
    case v: IntVector       => v.values(row).toLong
    case v: BooleanVector   => if (v.values(row)) 1L else 0L
    case v: DateVector      => v.values(row).toLong
    case v: TimestampVector => v.values(row)
    case other              => throw new IllegalStateException(
      s"readAsLong: unsupported column type ${other.dataType}")
  }

  /** Write a primitive long into `col` at `row`, narrowing to the column's
    * type. Inverse of [[readAsLong]]. */
  def writeLongTo(col: ColumnVector, row: Int, v: Long): Unit = col match {
    case c: LongVector      => c.set(row, v)
    case c: IntVector       => c.set(row, v.toInt)
    case c: BooleanVector   => c.set(row, v != 0L)
    case c: DateVector      => c.set(row, v.toInt)
    case c: TimestampVector => c.set(row, v)
    case other              => throw new IllegalStateException(
      s"writeLongTo: unsupported column type ${other.dataType}")
  }

  /** Unbox a value that came out of `ColumnVector.getBoxed` for a long-fittable
    * type back into a primitive `Long`. Used by [[HashJoinExec]]'s build-side
    * keymap build path, where rows are materialized as `Array[Any]` (boxed)
    * but the keymap stores primitive long keys. */
  def boxedToLong(v: Any, t: DataType): Long = t match {
    case DataType.LongType      => v.asInstanceOf[java.lang.Long].longValue
    case DataType.IntType       => v.asInstanceOf[java.lang.Integer].intValue.toLong
    case DataType.BooleanType   => if (v.asInstanceOf[java.lang.Boolean].booleanValue) 1L else 0L
    case DataType.DateType      => v.asInstanceOf[java.time.LocalDate].toEpochDay
    case DataType.TimestampType =>
      val i = v.asInstanceOf[java.time.Instant]
      i.getEpochSecond * 1000000L + i.getNano / 1000L
    case other => throw new IllegalStateException(s"boxedToLong: unsupported type $other")
  }
}

/** Hashmap-suitable wrapper around a `byte[]`. Caches `hashCode`; `equals`
  * does `Arrays.equals` after a same-hash check. */
final class BytesKey(val bytes: Array[Byte]) {
  private val hash: Int = java.util.Arrays.hashCode(bytes)
  override def hashCode(): Int = hash
  override def equals(other: Any): Boolean = other match {
    case k: BytesKey => (k eq this) || (k.hash == hash && java.util.Arrays.equals(bytes, k.bytes))
    case _ => false
  }
}

/** Hashmap-suitable wrapper around an `Array[AnyRef]`. Caches `hashCode`;
  * `equals` is element-wise with `Array[Byte]` special-cased for structural
  * equality (raw arrays would fall back to reference equality otherwise, which
  * is incorrect for any non-singleton binary key). */
final class ObjectArrayKey(val values: Array[AnyRef]) {
  private val hash: Int = {
    var h = 1
    var i = 0
    while (i < values.length) {
      val v = values(i)
      val eh =
        if (v == null) 0
        else v match {
          case b: Array[Byte] => java.util.Arrays.hashCode(b)
          case other          => other.hashCode
        }
      h = 31 * h + eh
      i += 1
    }
    h
  }
  override def hashCode(): Int = hash
  override def equals(other: Any): Boolean = other match {
    case k: ObjectArrayKey =>
      if (k eq this) return true
      if (k.hash != hash || k.values.length != values.length) return false
      var i = 0
      while (i < values.length) {
        val a = values(i); val b = k.values(i)
        val same =
          if (a == null) b == null
          else if (b == null) false
          else (a, b) match {
            case (x: Array[Byte], y: Array[Byte]) => java.util.Arrays.equals(x, y)
            case (x, y)                           => x.equals(y)
          }
        if (!same) return false
        i += 1
      }
      true
    case _ => false
  }
}

/** Codec for zero-column keys (e.g. ungrouped aggregation). Every encode
  * returns the same sentinel object so all rows collapse into one bucket. */
object EmptyKeyCodec extends KeyCodec {
  private val SENTINEL: AnyRef = new Object()
  def numKeys: Int = 0
  def encodeBoxed(values: Array[Any]): AnyRef = SENTINEL
  def encodeFromBatch(batch: ColumnarBatch, row: Int): AnyRef = SENTINEL
  def encodeFromBatchSkipIfAnyNull(batch: ColumnarBatch, row: Int): AnyRef = SENTINEL
  def decode(key: AnyRef, out: ColumnarBatch, baseCol: Int, outRow: Int): Unit = ()
}

/** Fixed-width packed codec. Key layout in the resulting `byte[]`:
  *
  *   [null_bits ...][value_bytes ...]
  *
  * `null_bits` is `ceil(numKeys / 8)` bytes — column `i`'s NULL flag lives in
  * bit `i & 7` of byte `i >> 3`. `value_bytes` are the column values
  * concatenated in column order; a NULL column's value bytes are zeros (the
  * null bit distinguishes "this column is NULL" from "this column equals 0").
  *
  * Per-row allocations on the encode path: one fresh `byte[]` of size
  * `nullBytes + Σ width(type)` and one [[BytesKey]] wrapper. No boxing on
  * [[encodeFromBatch]] — primitives come straight out of the typed
  * [[ColumnVector]]s. */
final class PackedBytesCodec private[core] (
    private val columnIndices: Array[Int],
    private val columnTypes: Array[DataType]) extends KeyCodec {

  val numKeys: Int = columnTypes.length

  private val nullByteCount: Int = (numKeys + 7) / 8

  private val valueOffsets: Array[Int] = {
    val a = new Array[Int](numKeys)
    var off = nullByteCount
    var i = 0
    while (i < numKeys) {
      a(i) = off
      off += widthOf(columnTypes(i))
      i += 1
    }
    a
  }

  private val totalBytes: Int = {
    var sum = nullByteCount
    var i = 0
    while (i < numKeys) { sum += widthOf(columnTypes(i)); i += 1 }
    sum
  }

  def encodeFromBatch(batch: ColumnarBatch, row: Int): AnyRef = {
    val bytes = new Array[Byte](totalBytes)
    var i = 0
    while (i < numKeys) {
      val col = batch.column(columnIndices(i))
      if (col.isNull(row)) setNullBit(bytes, i)
      else writeFromBatch(bytes, valueOffsets(i), columnTypes(i), col, row)
      i += 1
    }
    new BytesKey(bytes)
  }

  def encodeFromBatchSkipIfAnyNull(batch: ColumnarBatch, row: Int): AnyRef = {
    var i = 0
    while (i < numKeys) {
      if (batch.column(columnIndices(i)).isNull(row)) return null
      i += 1
    }
    encodeFromBatch(batch, row)
  }

  def encodeBoxed(values: Array[Any]): AnyRef = {
    val bytes = new Array[Byte](totalBytes)
    var i = 0
    while (i < numKeys) {
      val v = values(i)
      if (v == null) setNullBit(bytes, i)
      else writeFromBoxed(bytes, valueOffsets(i), columnTypes(i), v)
      i += 1
    }
    new BytesKey(bytes)
  }

  def decode(key: AnyRef, out: ColumnarBatch, baseCol: Int, outRow: Int): Unit = {
    val bytes = key.asInstanceOf[BytesKey].bytes
    var i = 0
    while (i < numKeys) {
      val outCol = out.column(baseCol + i)
      if (isNullBit(bytes, i)) outCol.setNull(outRow)
      else writeToColumn(bytes, valueOffsets(i), columnTypes(i), outCol, outRow)
      i += 1
    }
  }

  // ---- helpers --------------------------------------------------------------

  private def setNullBit(bytes: Array[Byte], i: Int): Unit = {
    bytes(i >> 3) = (bytes(i >> 3) | (1 << (i & 7))).toByte
  }

  private def isNullBit(bytes: Array[Byte], i: Int): Boolean =
    (bytes(i >> 3) & (1 << (i & 7))) != 0

  private def widthOf(t: DataType): Int = t match {
    case DataType.IntType       => 4
    case DataType.LongType      => 8
    case DataType.FloatType     => 4
    case DataType.DoubleType    => 8
    case DataType.BooleanType   => 1
    case DataType.DateType      => 4
    case DataType.TimestampType => 8
    case other =>
      throw new IllegalStateException(s"PackedBytesCodec does not support $other")
  }

  private def writeFromBatch(
      bytes: Array[Byte], off: Int, t: DataType, col: ColumnVector, row: Int): Unit = t match {
    case DataType.IntType       => writeInt(bytes, off, col.asInstanceOf[IntVector].values(row))
    case DataType.LongType      => writeLong(bytes, off, col.asInstanceOf[LongVector].values(row))
    case DataType.FloatType     =>
      writeInt(bytes, off, java.lang.Float.floatToIntBits(col.asInstanceOf[FloatVector].values(row)))
    case DataType.DoubleType    =>
      writeLong(bytes, off, java.lang.Double.doubleToLongBits(col.asInstanceOf[DoubleVector].values(row)))
    case DataType.BooleanType   => bytes(off) = if (col.asInstanceOf[BooleanVector].values(row)) 1 else 0
    case DataType.DateType      => writeInt(bytes, off, col.asInstanceOf[DateVector].values(row))
    case DataType.TimestampType => writeLong(bytes, off, col.asInstanceOf[TimestampVector].values(row))
    case other                  => throw new IllegalStateException(s"PackedBytesCodec does not support $other")
  }

  private def writeFromBoxed(
      bytes: Array[Byte], off: Int, t: DataType, v: Any): Unit = t match {
    case DataType.IntType       => writeInt(bytes, off, v.asInstanceOf[Number].intValue)
    case DataType.LongType      => writeLong(bytes, off, v.asInstanceOf[Number].longValue)
    case DataType.FloatType     =>
      writeInt(bytes, off, java.lang.Float.floatToIntBits(v.asInstanceOf[Number].floatValue))
    case DataType.DoubleType    =>
      writeLong(bytes, off, java.lang.Double.doubleToLongBits(v.asInstanceOf[Number].doubleValue))
    case DataType.BooleanType   => bytes(off) = if (v.asInstanceOf[Boolean]) 1 else 0
    case DataType.DateType      =>
      v match {
        case d: java.time.LocalDate => writeInt(bytes, off, d.toEpochDay.toInt)
        case n: Number              => writeInt(bytes, off, n.intValue)
        case other                  => throw new IllegalStateException(s"Cannot pack date from ${other.getClass.getName}")
      }
    case DataType.TimestampType =>
      v match {
        case i: java.time.Instant => writeLong(bytes, off, i.getEpochSecond * 1000000L + i.getNano / 1000L)
        case n: Number            => writeLong(bytes, off, n.longValue)
        case other                => throw new IllegalStateException(s"Cannot pack timestamp from ${other.getClass.getName}")
      }
    case other => throw new IllegalStateException(s"PackedBytesCodec does not support $other")
  }

  private def writeToColumn(
      bytes: Array[Byte], off: Int, t: DataType, col: ColumnVector, row: Int): Unit = t match {
    case DataType.IntType       => col.asInstanceOf[IntVector].set(row, readInt(bytes, off))
    case DataType.LongType      => col.asInstanceOf[LongVector].set(row, readLong(bytes, off))
    case DataType.FloatType     =>
      col.asInstanceOf[FloatVector].set(row, java.lang.Float.intBitsToFloat(readInt(bytes, off)))
    case DataType.DoubleType    =>
      col.asInstanceOf[DoubleVector].set(row, java.lang.Double.longBitsToDouble(readLong(bytes, off)))
    case DataType.BooleanType   => col.asInstanceOf[BooleanVector].set(row, bytes(off) != 0)
    case DataType.DateType      => col.asInstanceOf[DateVector].set(row, readInt(bytes, off))
    case DataType.TimestampType => col.asInstanceOf[TimestampVector].set(row, readLong(bytes, off))
    case other                  => throw new IllegalStateException(s"PackedBytesCodec does not support $other")
  }

  private def writeInt(bytes: Array[Byte], off: Int, v: Int): Unit = {
    bytes(off)     = (v        & 0xff).toByte
    bytes(off + 1) = ((v >>> 8)  & 0xff).toByte
    bytes(off + 2) = ((v >>> 16) & 0xff).toByte
    bytes(off + 3) = ((v >>> 24) & 0xff).toByte
  }

  private def writeLong(bytes: Array[Byte], off: Int, v: Long): Unit = {
    bytes(off)     = (v        & 0xff).toByte
    bytes(off + 1) = ((v >>> 8)  & 0xff).toByte
    bytes(off + 2) = ((v >>> 16) & 0xff).toByte
    bytes(off + 3) = ((v >>> 24) & 0xff).toByte
    bytes(off + 4) = ((v >>> 32) & 0xff).toByte
    bytes(off + 5) = ((v >>> 40) & 0xff).toByte
    bytes(off + 6) = ((v >>> 48) & 0xff).toByte
    bytes(off + 7) = ((v >>> 56) & 0xff).toByte
  }

  private def readInt(bytes: Array[Byte], off: Int): Int =
    (bytes(off)        & 0xff)        |
    ((bytes(off + 1) & 0xff) << 8)    |
    ((bytes(off + 2) & 0xff) << 16)   |
    ((bytes(off + 3) & 0xff) << 24)

  private def readLong(bytes: Array[Byte], off: Int): Long =
    (bytes(off).toLong         & 0xffL)         |
    ((bytes(off + 1).toLong  & 0xffL) << 8)     |
    ((bytes(off + 2).toLong  & 0xffL) << 16)    |
    ((bytes(off + 3).toLong  & 0xffL) << 24)    |
    ((bytes(off + 4).toLong  & 0xffL) << 32)    |
    ((bytes(off + 5).toLong  & 0xffL) << 40)    |
    ((bytes(off + 6).toLong  & 0xffL) << 48)    |
    ((bytes(off + 7).toLong  & 0xffL) << 56)
}

/** Open-addressing primitive-long-keyed map, ordered by insertion. Used by
  * the operator fast path when the key reduces to a single fixed-width
  * numeric column (Int / Long / Date / Timestamp / Boolean) — values fit in
  * a `Long`, the table stores them unboxed, the inner loop pays neither a
  * `Seq` allocation nor a `Long` boxing per row.
  *
  * Layout:
  *   - **Slot table** (open-addressing, power-of-two capacity): `slotKeys`
  *     primitive `Long`s + `slotEntryIdx` pointing into the insertion-order
  *     arrays + `slotOccupied` bitset (every `Long` value is a legal key, so
  *     a sentinel doesn't work — the bitset is the source of truth for
  *     "this slot has an entry").
  *   - **Entry arrays** (insertion-order): `entryKeys` + `entryValues` +
  *     `entryIsNull` (the NULL-keyed entry's `entryKeys` slot is ignored).
  *     `entryIsNull` makes the NULL entry an integral part of insertion
  *     order so emit re-materializes it at the right place.
  *
  * Resize doubles `capacity`, rehashes the slot table, and updates pointers.
  * Entry arrays grow independently when full — slot indices stored in
  * `slotEntryIdx` stay valid across grows.
  *
  * Thread safety: same as `mutable.LinkedHashMap` — one writer at a time. */
final class LongHashMap[V >: Null <: AnyRef](initialCapacity: Int = 16, loadFactor: Float = 0.75f) {
  require(initialCapacity > 0 && (initialCapacity & (initialCapacity - 1)) == 0,
    s"initialCapacity $initialCapacity must be a positive power of 2")
  require(loadFactor > 0f && loadFactor < 1f, s"loadFactor must be in (0,1)")

  private var capacity: Int = initialCapacity
  private var mask: Int = capacity - 1
  private var threshold: Int = math.max(1, (capacity * loadFactor).toInt)
  private var slotKeys: Array[Long] = new Array[Long](capacity)
  private var slotEntryIdx: Array[Int] = new Array[Int](capacity)
  private var slotOccupied: java.util.BitSet = new java.util.BitSet(capacity)

  private var entryKeys: Array[Long] = new Array[Long](16)
  private var entryValues: Array[AnyRef] = new Array[AnyRef](16)
  private var entryIsNull: java.util.BitSet = new java.util.BitSet(16)
  private var _size: Int = 0
  private var nullEntryIdx: Int = -1

  def size: Int = _size
  def isEmpty: Boolean = _size == 0

  /** Look up the value for a primitive-long key. Returns `null` if absent. */
  def get(key: Long): V = {
    var i = hash(key) & mask
    while (slotOccupied.get(i)) {
      if (slotKeys(i) == key) return entryValues(slotEntryIdx(i)).asInstanceOf[V]
      i = (i + 1) & mask
    }
    null
  }

  /** Look up the value for the NULL key. Returns `null` if no NULL entry exists. */
  def getNull: V = {
    if (nullEntryIdx >= 0) entryValues(nullEntryIdx).asInstanceOf[V] else null
  }

  /** If `key` is absent, evaluate `supplier`, store it, and return it; else
    * return the existing value. The supplier is lazy (Scala by-name), so a
    * fresh `Array[AggState]` initializer only runs on insert. */
  def getOrInsert(key: Long, supplier: => V): V = {
    var i = hash(key) & mask
    while (slotOccupied.get(i)) {
      if (slotKeys(i) == key) return entryValues(slotEntryIdx(i)).asInstanceOf[V]
      i = (i + 1) & mask
    }
    val v = supplier
    val entryIdx = appendEntry(key, v, isNull = false)
    slotKeys(i) = key
    slotEntryIdx(i) = entryIdx
    slotOccupied.set(i)
    if (_size > threshold) resize()
    v
  }

  /** [[getOrInsert]] for the NULL key. */
  def getOrInsertNull(supplier: => V): V = {
    if (nullEntryIdx >= 0) return entryValues(nullEntryIdx).asInstanceOf[V]
    val v = supplier
    nullEntryIdx = appendEntry(0L, v, isNull = true)
    v
  }

  /** Unconditionally store `value` at `key`. Overwrites if `key` already
    * exists; preserves the original insertion position. Returns the previous
    * value (or `null`). */
  def put(key: Long, value: V): V = {
    var i = hash(key) & mask
    while (slotOccupied.get(i)) {
      if (slotKeys(i) == key) {
        val prev = entryValues(slotEntryIdx(i))
        entryValues(slotEntryIdx(i)) = value
        return prev.asInstanceOf[V]
      }
      i = (i + 1) & mask
    }
    val entryIdx = appendEntry(key, value, isNull = false)
    slotKeys(i) = key
    slotEntryIdx(i) = entryIdx
    slotOccupied.set(i)
    if (_size > threshold) resize()
    null
  }

  /** Iterate `(boxed key, value)` pairs in insertion order. NULL key yields
    * `(null, value)`. The boxing is per-group (not per-row), so this is the
    * idiomatic merge entry point. */
  def iterator: Iterator[(java.lang.Long, V)] = new Iterator[(java.lang.Long, V)] {
    private var pos = 0
    def hasNext: Boolean = pos < _size
    def next(): (java.lang.Long, V) = {
      val key: java.lang.Long = if (entryIsNull.get(pos)) null else java.lang.Long.valueOf(entryKeys(pos))
      val v = entryValues(pos).asInstanceOf[V]
      pos += 1
      (key, v)
    }
  }

  /** Iterate entries WITHOUT boxing the key. The callback receives
    * `(isNull, keyIfNotNull, value)`. Used by hot merge loops where the
    * per-group boxing in [[iterator]] is itself the bottleneck. */
  def forEach(action: (Boolean, Long, V) => Unit): Unit = {
    var pos = 0
    while (pos < _size) {
      val isNull = entryIsNull.get(pos)
      val key = if (isNull) 0L else entryKeys(pos)
      action(isNull, key, entryValues(pos).asInstanceOf[V])
      pos += 1
    }
  }

  // ---- internal --------------------------------------------------------------

  private def appendEntry(key: Long, v: V, isNull: Boolean): Int = {
    if (_size >= entryKeys.length) growEntries()
    val idx = _size
    entryKeys(idx) = key
    entryValues(idx) = v
    if (isNull) entryIsNull.set(idx)
    _size += 1
    idx
  }

  private def growEntries(): Unit = {
    val newCap = entryKeys.length * 2
    entryKeys = java.util.Arrays.copyOf(entryKeys, newCap)
    entryValues = java.util.Arrays.copyOf(entryValues, newCap)
    // entryIsNull is a BitSet — grows automatically when high bits are set.
  }

  private def resize(): Unit = {
    val newCap = capacity * 2
    val newMask = newCap - 1
    val newSlotKeys = new Array[Long](newCap)
    val newSlotEntryIdx = new Array[Int](newCap)
    val newOccupied = new java.util.BitSet(newCap)
    var i = 0
    while (i < capacity) {
      if (slotOccupied.get(i)) {
        val k = slotKeys(i)
        var j = hash(k) & newMask
        while (newOccupied.get(j)) j = (j + 1) & newMask
        newSlotKeys(j) = k
        newSlotEntryIdx(j) = slotEntryIdx(i)
        newOccupied.set(j)
      }
      i += 1
    }
    slotKeys = newSlotKeys
    slotEntryIdx = newSlotEntryIdx
    slotOccupied = newOccupied
    capacity = newCap
    mask = newMask
    threshold = math.max(1, (capacity * loadFactor).toInt)
  }

  /** Mix the long to spread the low bits across the slot index. Uses Murmur's
    * 64-bit constant for avalanche; the result is xor-folded to fit in an
    * `Int` slot index. Avoids the pathological clustering plain `Long.hashCode`
    * causes when keys are dense small integers. */
  private def hash(key: Long): Int = {
    val h = key * 0x9E3779B97F4A7C15L
    ((h ^ (h >>> 32)) & 0x7FFFFFFFL).toInt
  }
}

/** Boxed-values codec for keys that include at least one variable-width
  * column (String, Binary, Decimal, NullType). Each encode copies the row's
  * key values into a fresh `Array[AnyRef]` wrapped in an [[ObjectArrayKey]]. */
final class ObjectArrayCodec private[core] (
    private val columnIndices: Array[Int],
    private val columnTypes: Array[DataType]) extends KeyCodec {

  val numKeys: Int = columnTypes.length

  def encodeFromBatch(batch: ColumnarBatch, row: Int): AnyRef = {
    val arr = new Array[AnyRef](numKeys)
    var i = 0
    while (i < numKeys) {
      val col = batch.column(columnIndices(i))
      arr(i) =
        if (col.isNull(row)) null
        else col.getBoxed(row).asInstanceOf[AnyRef]
      i += 1
    }
    new ObjectArrayKey(arr)
  }

  def encodeFromBatchSkipIfAnyNull(batch: ColumnarBatch, row: Int): AnyRef = {
    val arr = new Array[AnyRef](numKeys)
    var i = 0
    while (i < numKeys) {
      val col = batch.column(columnIndices(i))
      if (col.isNull(row)) return null
      arr(i) = col.getBoxed(row).asInstanceOf[AnyRef]
      i += 1
    }
    new ObjectArrayKey(arr)
  }

  def encodeBoxed(values: Array[Any]): AnyRef = {
    val arr = new Array[AnyRef](numKeys)
    var i = 0
    while (i < numKeys) {
      arr(i) = values(i).asInstanceOf[AnyRef]
      i += 1
    }
    new ObjectArrayKey(arr)
  }

  def decode(key: AnyRef, out: ColumnarBatch, baseCol: Int, outRow: Int): Unit = {
    val arr = key.asInstanceOf[ObjectArrayKey].values
    var i = 0
    while (i < numKeys) {
      val v = arr(i)
      val outCol = out.column(baseCol + i)
      if (v == null) outCol.setNull(outRow) else outCol.setBoxed(outRow, v)
      i += 1
    }
  }
}
