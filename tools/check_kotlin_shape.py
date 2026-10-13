#!/usr/bin/env python3
"""Shape guards for the Undal Kotlin sources (LAW-11 helpers, no compiler needed).

Two mistakes are cheap to make and expensive to find on a phone:

1. a normal string literal split across two source lines — Kotlin only allows
   that inside a raw `\"\"\"` string, so the file will not compile;
2. a stray or missing `{` `(` — a whole screen silently stops being a screen.

This script walks `app/src` and fails loudly on either. Run it before every
commit; it is deliberately dependency-free so it also runs inside Actions.
"""

from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE_ROOT = ROOT / "app" / "src"


def scan(text: str) -> tuple[list[int], int, int]:
    """Return (lines opening an unterminated normal string, brace delta, paren delta)."""
    broken_lines: list[int] = []
    shapes: list[str] = []
    i = 0
    n = len(text)
    line = 1
    while i < n:
        char = text[i]
        if char == "\n":
            line += 1
            i += 1
            continue
        if char == "/" and text[i : i + 2] == "//":
            end = text.find("\n", i)
            i = n if end == -1 else end
            continue
        if char == "/" and text[i : i + 2] == "/*":
            end = text.find("*/", i + 2)
            if end == -1:
                i = n
            else:
                line += text.count("\n", i, end + 2)
                i = end + 2
            continue
        if text[i : i + 3] == '"""':
            end = text.find('"""', i + 3)
            if end == -1:
                i = n
            else:
                line += text.count("\n", i, end + 3)
                i = end + 3
            continue
        if char == '"':
            opened_on = line
            i += 1
            closed = False
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == "\n":
                    break
                if text[i] == '"':
                    i += 1
                    closed = True
                    break
                i += 1
            if not closed:
                broken_lines.append(opened_on)
            continue
        if char in "{}()":
            shapes.append(char)
        i += 1

    braces = shapes.count("{") - shapes.count("}")
    parens = shapes.count("(") - shapes.count(")")
    return broken_lines, braces, parens


def main() -> int:
    if not SOURCE_ROOT.is_dir():
        print(f"check_kotlin_shape: {SOURCE_ROOT} not found")
        return 2

    problems = 0
    files = sorted(SOURCE_ROOT.rglob("*.kt"))
    for path in files:
        broken, braces, parens = scan(path.read_text(encoding="utf-8"))
        rel = path.relative_to(ROOT)
        for opened_on in broken:
            print(f"{rel}:{opened_on}: string literal is not closed before the end of the line")
            problems += 1
        if braces:
            print(f"{rel}: unbalanced braces (delta {braces:+d})")
            problems += 1
        if parens:
            print(f"{rel}: unbalanced parentheses (delta {parens:+d})")
            problems += 1

    if problems:
        print(f"check_kotlin_shape: {problems} problem(s) in {len(files)} Kotlin files")
        return 1
    print(f"check_kotlin_shape: {len(files)} Kotlin files balanced, no split string literals")
    return 0


if __name__ == "__main__":
    sys.exit(main())
