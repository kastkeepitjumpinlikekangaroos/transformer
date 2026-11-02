"""Writes the parquet files of src/test/data/parquet-testing/pyarrow/: one or a
few for each row of the README's parquet type table, each layout the reader
must meet, and each codec, all written by pyarrow (decision 5 of
plans/testing/13). Deterministic: no clock, no randomness. Run once, outside
the build, with pyarrow 26.0.0:

    python3 make_files.py <output directory>
"""
import datetime
import decimal
import os
import sys

import pyarrow as pa
import pyarrow.parquet as pq

OUT = sys.argv[1]


def write(name, table, **kwargs):
    pq.write_table(table, os.path.join(OUT, name + ".parquet"), **kwargs)


def ints(n):
    return pa.array([None if i % 7 == 3 else i * 37 - 500 for i in range(n)], pa.int64())


def texts(n):
    return pa.array([None if i % 11 == 5 else "v%d-%s" % (i % 13, "x" * (i % 4)) for i in range(n)], pa.string())


def doubles(n):
    return pa.array([None if i % 9 == 2 else i * 0.25 - 3.0 for i in range(n)], pa.float64())


def plain_table(n=200):
    return pa.table({"i": ints(n), "s": texts(n), "d": doubles(n)})


# Every codec pyarrow writes. Parquet's LZ4 is Hadoop-framed, which pyarrow
# does not write; its "lz4" is LZ4_RAW. BROTLI the reader refuses.
for codec in ["none", "snappy", "gzip", "brotli", "zstd", "lz4"]:
    write("codec_" + codec, plain_table(), compression=codec)

# Data pages v1 and v2, many pages a column, with and without a dictionary.
for version in ["1.0", "2.0"]:
    for dictionary in [True, False]:
        write("pages_v%s_%s" % (version[0], "dictionary" if dictionary else "plain"), plain_table(500),
              data_page_version=version, use_dictionary=dictionary, data_page_size=256, write_batch_size=16,
              compression="snappy")

# Encodings beyond PLAIN and the dictionary's.
n = 300
write("encodings", pa.table({
    "delta_int64": ints(n),
    "delta_int32": pa.array([None if i % 5 == 0 else i * 1000003 % 65536 - 32768 for i in range(n)], pa.int32()),
    "delta_length_text": texts(n),
    "delta_text": pa.array(["prefix-%04d" % (i // 3) for i in range(n)], pa.string()),
    "split_double": doubles(n),
    "split_float": pa.array([None if i % 4 == 1 else i / 8.0 for i in range(n)], pa.float32()),
}), use_dictionary=False, column_encoding={
    "delta_int64": "DELTA_BINARY_PACKED", "delta_int32": "DELTA_BINARY_PACKED",
    "delta_length_text": "DELTA_LENGTH_BYTE_ARRAY", "delta_text": "DELTA_BYTE_ARRAY",
    "split_double": "BYTE_STREAM_SPLIT", "split_float": "BYTE_STREAM_SPLIT"})

# Integers: signed of each width, unsigned of each width at its edges.
write("integers", pa.table({
    "int8": pa.array([-128, 0, 127, None, -1], pa.int8()),
    "int16": pa.array([-32768, 0, 32767, None, -1], pa.int16()),
    "int32": pa.array([-2147483648, 0, 2147483647, None, -1], pa.int32()),
    "int64": pa.array([-9223372036854775808, 0, 9223372036854775807, None, -1], pa.int64()),
    "uint8": pa.array([0, 1, 255, None, 128], pa.uint8()),
    "uint16": pa.array([0, 1, 65535, None, 32768], pa.uint16()),
    "uint32": pa.array([0, 1, 4294967295, None, 2147483648], pa.uint32()),
    "uint64": pa.array([0, 1, 18446744073709551615, None, 9223372036854775808], pa.uint64()),
}))

# Floating point: NaN, the infinities, -0.0, the smallest subnormal; FLOAT16.
write("floats", pa.table({
    "float": pa.array([1.5, float("nan"), float("inf"), float("-inf"), -0.0, 1.401298464324817e-45, None],
                      pa.float32()),
    "double": pa.array([1.5, float("nan"), float("inf"), float("-inf"), -0.0, 5e-324, None], pa.float64()),
    "half": pa.array([1.5, float("nan"), float("inf"), float("-inf"), -0.0, 5.960464477539063e-08, None],
                     pa.float16()),
}))

# Booleans, over several pages.
write("booleans", pa.table({"b": pa.array([None if i % 6 == 0 else i % 3 == 1 for i in range(400)], pa.bool_())}),
      data_page_size=32, write_batch_size=8)

# Decimals: as INT32 and INT64 where the precision allows, and as
# FIXED_LEN_BYTE_ARRAY; BINARY is parquet-testing's byte_array_decimal.
D = decimal.Decimal
decimals = pa.table({
    "dec9_2": pa.array([D("1234567.89"), D("-0.01"), D("0.00"), None, D("-9999999.99")], pa.decimal128(9, 2)),
    "dec18_4": pa.array([D("12345678901234.5678"), D("-0.0001"), D("0"), None, D("-99999999999999.9999")],
                        pa.decimal128(18, 4)),
    "dec38_10": pa.array([D("1234567890123456789012345678.0123456789"), D("-1E-10"), D("0"), None,
                          D("-9999999999999999999999999999.9999999999")], pa.decimal128(38, 10)),
    "dec5_0": pa.array([D("12345"), D("-1"), D("0"), None, D("-99999")], pa.decimal128(5, 0)),
})
write("decimals_as_integers", decimals, store_decimal_as_integer=True)
write("decimals_as_fixed", decimals, store_decimal_as_integer=False)

# Dates, from the first day of year 1 to the last of 9999.
write("dates", pa.table({
    "date": pa.array([datetime.date(1, 1, 1), datetime.date(1969, 12, 31), datetime.date(1970, 1, 1),
                      datetime.date(2024, 2, 29), datetime.date(9999, 12, 31), None], pa.date32()),
}))

# Timestamps in each unit, marked UTC and local. Parquet has no seconds, so
# pyarrow writes a seconds column in milliseconds; nanoseconds need format
# version 2.6, pyarrow's default.
epoch = datetime.datetime(1970, 1, 1)
instants = [epoch + datetime.timedelta(microseconds=m) for m in
            [0, -1, 1, 1710505296789123, -2208988800000000, 4102444800000000]]
columns = {}
for unit in ["s", "ms", "us", "ns"]:
    for tz in ["UTC", None]:
        values = [None if i == 3 else t for i, t in enumerate(instants)]
        if unit == "s":
            values = [None if v is None else v.replace(microsecond=0) for v in values]
        if unit == "ms":
            values = [None if v is None else v.replace(microsecond=v.microsecond // 1000 * 1000) for v in values]
        columns["ts_%s_%s" % (unit, "utc" if tz else "local")] = pa.array(values, pa.timestamp(unit, tz=tz))
write("timestamps", pa.table(columns), coerce_timestamps=None, allow_truncated_timestamps=False)
write("timestamps_int96", pa.table({"ts": pa.array(instants[:5] + [None], pa.timestamp("us"))}),
      use_deprecated_int96_timestamps=True)
# Nanoseconds that are no whole microsecond, either side of 1970.
write("timestamps_nanos", pa.table({"ts": pa.array([1500, -1500, 999, -1, None], pa.timestamp("ns", tz="UTC"))}))

# Text: large strings, characters past the BMP, a dictionary-typed column, and
# bytes, fixed-length bytes and large binary.
write("text", pa.table({
    "string": pa.array(["", "plain", "café", "\U0001F600 astral", None, "tab\tand\nnewline"], pa.string()),
    "large_string": pa.array(["a" * 300, "", None, "中文", "x", "y"], pa.large_string()),
    "dictionary": pa.array(["red", "green", "red", None, "blue", "red"]).dictionary_encode(),
    "binary": pa.array([b"", b"\x00\xff", None, b"abc", b"\x80", b"z"], pa.binary()),
    "fixed": pa.array([b"ab", b"\x00\x01", None, b"zz", b"\xff\xfe", b"q!"], pa.binary(2)),
    "large_binary": pa.array([b"q", None, b"", b"\x01\x02\x03", b"r", b"s"], pa.large_binary()),
}))

# NULLs where a page starts and where it ends: pages of a few values, NULL
# runs across their boundaries, and row groups of ten rows.
n = 120
write("nulls_at_page_edges", pa.table({
    "i": pa.array([None if i % 10 in (0, 9) or 40 <= i < 60 else i for i in range(n)], pa.int32()),
    "s": pa.array([None if i % 10 in (0, 1, 8, 9) else "s%d" % i for i in range(n)], pa.string()),
}), row_group_size=10, data_page_size=16, write_batch_size=4)

# A column of only NULLs, of a type and of pyarrow's null type, which
# parquet stores as INT32 annotated UNKNOWN.
write("all_nulls", pa.table({
    "id": pa.array([1, 2, 3], pa.int32()),
    "ints": pa.array([None, None, None], pa.int64()),
    "texts": pa.array([None, None, None], pa.string()),
}))
write("null_type", pa.table({"id": pa.array([1, 2], pa.int32()), "nothing": pa.array([None, None], pa.null())}))

# A file of no rows.
write("no_rows", pa.table({"i": pa.array([], pa.int64()), "s": pa.array([], pa.string()),
                           "ts": pa.array([], pa.timestamp("us", tz="UTC"))}))

# Page CRCs on every page.
write("page_checksums", plain_table(300), write_page_checksum=True, data_page_size=512, compression="snappy")
