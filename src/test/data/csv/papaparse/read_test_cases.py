"""Reads PapaParse's tests/test-cases.js as data (nothing in it is run) and
writes its CORE_PARSER_TESTS, PARSE_TESTS and UNPARSE_TESTS as JSON: each case
a {description, input, config, expected} object, or {description,
unsupported: why} where a value is code (a function, a regex, a constructor).
Usage: papa_convert.py <test-cases.js> <out.json>"""
import json
import re
import sys


class Unsupported(Exception):
    pass


class Code:
    def __init__(self, why):
        self.why = why


CONSTANTS = {"RECORD_SEP": "\x1e", "UNIT_SEP": "\x1f", "true": True, "false": False, "null": None,
             "undefined": None}


class Reader:
    def __init__(self, text, pos):
        self.t = text
        self.i = pos

    def ws(self):
        while self.i < len(self.t):
            c = self.t[self.i]
            if c in " \t\r\n":
                self.i += 1
            elif self.t.startswith("//", self.i):
                self.i = self.t.index("\n", self.i)
            elif self.t.startswith("/*", self.i):
                self.i = self.t.index("*/", self.i) + 2
            else:
                break

    def peek(self):
        self.ws()
        return self.t[self.i] if self.i < len(self.t) else ""

    def expect(self, c):
        if self.peek() != c:
            raise ValueError("expected %r at %d: %r" % (c, self.i, self.t[self.i:self.i + 40]))
        self.i += 1

    def string(self):
        q = self.t[self.i]
        self.i += 1
        out = []
        while True:
            c = self.t[self.i]
            if c == q:
                self.i += 1
                return "".join(out)
            if c == "\\":
                n = self.t[self.i + 1]
                self.i += 2
                if n == "n": out.append("\n")
                elif n == "r": out.append("\r")
                elif n == "t": out.append("\t")
                elif n == "b": out.append("\b")
                elif n == "f": out.append("\f")
                elif n == "v": out.append("\v")
                elif n == "0": out.append("\0")
                elif n == "u":
                    out.append(chr(int(self.t[self.i:self.i + 4], 16)))
                    self.i += 4
                elif n == "x":
                    out.append(chr(int(self.t[self.i:self.i + 2], 16)))
                    self.i += 2
                elif n == "\n":
                    pass
                else:
                    out.append(n)
            else:
                out.append(c)
                self.i += 1

    def skip_balanced(self, open_c, close_c):
        depth = 0
        while True:
            c = self.t[self.i]
            if c in "'\"":
                self.string()
                continue
            if c == open_c:
                depth += 1
            elif c == close_c:
                depth -= 1
                if depth == 0:
                    self.i += 1
                    return
            self.i += 1

    def primary(self):
        c = self.peek()
        if c in "'\"":
            return self.string()
        if c == "[":
            self.i += 1
            out = []
            while self.peek() != "]":
                out.append(self.value())
                if self.peek() == ",":
                    self.i += 1
            self.i += 1
            return out
        if c == "{":
            self.i += 1
            out = {}
            while self.peek() != "}":
                k = self.peek()
                if k in "'\"":
                    key = self.string()
                else:
                    m = re.compile(r"[A-Za-z_$0-9][\w$]*").match(self.t, self.i)
                    if not m:
                        raise Unsupported("a computed key at %r" % self.t[self.i:self.i + 30])
                    key = m.group(0)
                    self.i = m.end()
                self.expect(":")
                out[key] = self.value()
                if self.peek() == ",":
                    self.i += 1
            self.i += 1
            return out
        if c == "/":
            raise Unsupported("a regular expression")
        m = re.compile(r"-?\d+(\.\d+)?([eE][-+]?\d+)?").match(self.t, self.i)
        if m and m.group(0) != "-":
            self.i = m.end()
            v = m.group(0)
            return float(v) if ("." in v or "e" in v.lower()) else int(v)
        m = re.compile(r"[A-Za-z_$][\w$.]*").match(self.t, self.i)
        if not m:
            raise ValueError("no value at %d: %r" % (self.i, self.t[self.i:self.i + 40]))
        word = m.group(0)
        self.i = m.end()
        if word == "function":
            self.ws()
            self.skip_balanced("(", ")")
            self.ws()
            self.skip_balanced("{", "}")
            return Code("a function")
        if word == "new":
            self.ws()
            m2 = re.compile(r"[\w$.]+").match(self.t, self.i)
            self.i = m2.end()
            self.ws()
            self.skip_balanced("(", ")")
            return Code("a constructor")
        if word in CONSTANTS:
            return CONSTANTS[word]
        if self.peek() == "(":
            self.skip_balanced("(", ")")
            return Code("a call of " + word)
        return Code("the identifier " + word)

    def value(self):
        v = self.primary()
        while self.peek() == "+":
            self.i += 1
            w = self.primary()
            if isinstance(v, Code) or isinstance(w, Code):
                v = Code("an expression")
            else:
                v = (v if isinstance(v, str) else json.dumps(v)) + (w if isinstance(w, str) else json.dumps(w))
        if self.peek() == "?":
            raise Unsupported("a conditional expression")
        return v


def codes(v):
    if isinstance(v, Code):
        return [v.why]
    if isinstance(v, list):
        return [w for x in v for w in codes(x)]
    if isinstance(v, dict):
        return [w for x in v.values() for w in codes(x)]
    return []


def cases(text, name):
    start = text.index("var %s = [" % name) + len("var %s = " % name)
    r = Reader(text, start)
    r.expect("[")
    out = []
    while r.peek() != "]":
        at = r.i
        try:
            case = r.value()
            why = codes(case)
            if why:
                case = {"description": case.get("description"), "unsupported": why[0]}
        except Unsupported as e:
            r.i = at
            r.ws()
            r.skip_balanced("{", "}")
            m = re.search(r"description:\s*(['\"])(.*?)\1", text[at:r.i])
            case = {"description": m.group(2) if m else None, "unsupported": str(e)}
        out.append(case)
        if r.peek() == ",":
            r.i += 1
    return out


def main():
    text = open(sys.argv[1], encoding="utf-8").read()
    result = {name: cases(text, name) for name in ["CORE_PARSER_TESTS", "PARSE_TESTS", "UNPARSE_TESTS"]}
    json.dump(result, open(sys.argv[2], "w"), indent=1, ensure_ascii=False)
    for k, v in result.items():
        print(k, len(v), "cases,", sum(1 for c in v if "unsupported" in c), "with code")


main()
