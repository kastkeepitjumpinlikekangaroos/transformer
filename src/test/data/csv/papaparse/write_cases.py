"""Writes PapaParse's cases (the JSON read_test_cases.py writes) as files of
src/test/data/csv/papaparse/: for each parse case whose options the reader
has, NNN.csv (its text) and NNN.json (the options and PapaParse's rows and
error codes); for each unparse case whose options the writer has, NNN.json
(the rows, the options and PapaParse's text). Prints what it left out.
Usage: write_cases.py <cases.json> <out dir>"""
import json
import os
import sys

src = json.load(open(sys.argv[1]))
out = sys.argv[2]

READER = {"delimiter", "quoteChar", "escapeChar", "header", "newline"}
WRITER = {"delimiter", "quoteChar", "escapeChar", "header", "newline"}
left = []


def single(c):
    return isinstance(c, str) and len(c) == 1


def write_parse(kind, cases):
    d = os.path.join(out, kind)
    os.makedirs(d, exist_ok=True)
    n = 0
    for i, c in enumerate(cases):
        name = "%s %03d %s" % (kind, i + 1, c.get("description"))
        if "unsupported" in c:
            left.append((name, "its case holds " + c["unsupported"]))
            continue
        cfg = c.get("config") or {}
        extra = sorted(set(cfg) - READER)
        if extra:
            left.append((name, "options the reader has not: " + ", ".join(extra)))
            continue
        if any(k in cfg and not single(cfg[k]) for k in ("delimiter", "quoteChar", "escapeChar")):
            left.append((name, "a separator of more than one character"))
            continue
        if cfg.get("delimiter") in ("\n", "\r"):
            left.append((name, "a delimiter the reader refuses, a line break, which PapaParse replaces by its guess"))
            continue
        text = c["input"]
        if not isinstance(text, str):
            left.append((name, "its input is no text"))
            continue
        nl = cfg.get("newline")
        if nl is not None:
            others = [e for e in ("\r\n", "\n", "\r") if e != nl and e in text.replace(nl, "")]
            if others:
                left.append((name, "a newline option the reader has not: it ends lines at every break"))
                continue
        exp = c["expected"]
        meta = exp.get("meta") or {}
        if "delimiter" in meta and meta["delimiter"] != cfg.get("delimiter", ","):
            left.append((name, "PapaParse guesses its delimiter, which the reader takes as given"))
            continue
        options = {}
        if cfg.get("header"): options["header"] = "true"
        else: options["header"] = "false"
        if "delimiter" in cfg: options["delimiter"] = cfg["delimiter"]
        if "quoteChar" in cfg: options["quote"] = cfg["quoteChar"]
        # PapaParse's escape is its quote unless given; the reader's is '"'.
        if "escapeChar" in cfg or "quoteChar" in cfg: options["escape"] = cfg.get("escapeChar", cfg.get("quoteChar"))
        n += 1
        base = os.path.join(d, "%03d" % (i + 1))
        open(base + ".csv", "w", encoding="utf-8", newline="").write(text)
        json.dump({"description": c.get("description"), "options": options, "rows": exp.get("data"),
                   "fields": meta.get("fields"),
                   "errors": sorted({e.get("code") for e in exp.get("errors") or []})},
                  open(base + ".json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    return n


def write_unparse(cases):
    d = os.path.join(out, "unparse")
    os.makedirs(d, exist_ok=True)
    n = 0
    for i, c in enumerate(cases):
        name = "unparse %03d %s" % (i + 1, c.get("description"))
        if "unsupported" in c:
            left.append((name, "its case holds " + c["unsupported"]))
            continue
        cfg = c.get("config") or {}
        extra = sorted(set(cfg) - WRITER)
        if extra:
            left.append((name, "options the writer has not: " + ", ".join(extra)))
            continue
        inp = c["input"]
        expected = c["expected"]
        if not isinstance(expected, str):
            left.append((name, "PapaParse refuses it"))
            continue
        if isinstance(inp, dict) and "data" in inp:
            fields, rows = inp.get("fields"), inp.get("data")
        elif isinstance(inp, dict) and "fields" in inp:
            fields, rows = inp.get("fields"), []
        elif isinstance(inp, list) and inp and isinstance(inp[0], dict):
            fields = list(inp[0].keys())
            rows = [[r.get(f) for f in fields] for r in inp]
        elif isinstance(inp, list) and all(isinstance(r, list) for r in inp):
            fields, rows = None, inp
        else:
            left.append((name, "its input is no table"))
            continue
        if rows and not all(isinstance(r, list) for r in rows):
            left.append((name, "a row that is no list"))
            continue
        if any(not (v is None or isinstance(v, str)) for r in rows for v in r):
            left.append((name, "a value that is no text: the writer writes a column's type"))
            continue
        widths = {len(r) for r in rows} | ({len(fields)} if fields else set())
        if len(widths) > 1:
            left.append((name, "rows of different widths, which a table has not"))
            continue
        if cfg.get("header") is False and fields:
            fields_written = None
        else:
            fields_written = fields
        options = {"header": "true" if fields_written else "false"}
        options["lineEnding"] = cfg.get("newline", "\r\n")
        if options["lineEnding"] not in ("\n", "\r\n", "\r"):
            left.append((name, "a newline the writer has not: it ends a line with a line break"))
            continue
        if "delimiter" in cfg:
            if not single(cfg["delimiter"]):
                left.append((name, "a separator of more than one character"))
                continue
            if cfg["delimiter"] in ("\n", "\r"):
                left.append((name, "a delimiter the writer refuses, a line break"))
                continue
            options["delimiter"] = cfg["delimiter"]
        if "quoteChar" in cfg: options["quote"] = cfg["quoteChar"]
        if "escapeChar" in cfg or "quoteChar" in cfg: options["escape"] = cfg.get("escapeChar", cfg.get("quoteChar"))
        n += 1
        json.dump({"description": c.get("description"), "options": options, "fields": fields_written,
                   "rows": rows, "text": expected},
                  open(os.path.join(d, "%03d.json" % (i + 1)), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    return n


print("core", write_parse("core", src["CORE_PARSER_TESTS"]), "of", len(src["CORE_PARSER_TESTS"]))
print("parse", write_parse("parse", src["PARSE_TESTS"]), "of", len(src["PARSE_TESTS"]))
print("unparse", write_unparse(src["UNPARSE_TESTS"]), "of", len(src["UNPARSE_TESTS"]))
json.dump(left, open(os.path.join(out, "left-out.json"), "w"), indent=1, ensure_ascii=False)
from collections import Counter
print(Counter(w.split(":")[0] for _, w in left))
