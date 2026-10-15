#!/usr/bin/env python3
"""Foreground-service and Task-read guards for the Undal Kotlin sources.

Two mistakes in this app do not look like mistakes. They look like working code,
they pass the compiler, they pass every unit test, and then they kill the process
from a thread nobody is watching — which on a phone is an app that reopens itself
three times and lands the person on their home screen.

**1. A foreground service that stops without taking the foreground.**

`Context.startForegroundService()` creates an obligation: the service must call
`startForeground()` within about five seconds, *including when the intent it was
handed says "stop"*. Break it and Android raises
`ForegroundServiceDidNotStartInTimeException` (Android 12+) or the "isn't
responding" dialog (older), on the main thread, in a callback the caller cannot
wrap in `runCatching` because the caller has already returned.

Undal had this in three services, and the one that mattered was the kitchen
watcher, because a *customer* login is what asked for that stop — so the crash
landed on every phone that opened the app. The fix is structural, not careful:
`UndalForeground` is the only file allowed to touch the foreground API, and every
`onStartCommand` takes the foreground before it decides anything.

**2. `Task.getResult()` read as though it waited.**

`getIdToken(false).result` does not block for the token. It throws
`IllegalStateException` unless the task has *already* finished, and a token being
fetched has not. Wrapped in `runCatching` — as all four sites were — that throw
becomes a `false`, so a kitchen phone answered "I am not the kitchen" to the
watchdog, to the boot receiver, to the push handler and to the router's own first
emission. The correct read is `await()` in a coroutine or the task's own callback.

Run it from the repository root:

    python3 tools/check_foreground_contract.py
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE_ROOT = ROOT / "app" / "src"

# The one file that is allowed to speak to the foreground-service API. Everything
# else goes through it, so there is exactly one place to get the contract right.
FOREGROUND_GATEWAY = "UndalForeground.kt"

FOREGROUND_API = (
    re.compile(r"\bstartForegroundService\s*\("),
    re.compile(r"\bServiceCompat\s*\.\s*startForeground\s*\("),
    re.compile(r"\bServiceCompat\s*\.\s*stopForeground\s*\("),
    re.compile(r"(?<![\w.])startForeground\s*\("),
    re.compile(r"(?<![\w.])stopForeground\s*\("),
)

ON_START_COMMAND = re.compile(r"\boverride\s+fun\s+onStartCommand\s*\(")
ENTER_FOREGROUND = re.compile(r"\bUndalForeground\s*\.\s*enter\s*\(")
STOPS = re.compile(r"\bstopSelf\s*\(|\breturn\b|\bUndalForeground\s*\.\s*stop")

# `getIdToken(...).result` and any `?.result?.claims` chain: the shape that throws
# instead of waiting. `ActivityResult` and friends are excluded by requiring the
# read to sit on a token/task expression.
TASK_RESULT_READS = (
    re.compile(r"getIdToken\s*\([^)]*\)\s*\??\.\s*result\b"),
    re.compile(r"\?\s*\.\s*result\s*\?\s*\.\s*claims\b"),
    re.compile(r"\bTasks\s*\.\s*await\s*\("),
)

# A private function in the same file whose own body takes the foreground counts
# as taking it: `OrderAlertService` has four branches that need the same notice,
# so it builds that notice in one place and calls it.
FUN_CALLING_ENTER = re.compile(
    r"\bfun\s+(?P<name>[A-Za-z_]\w*)\s*\([^)]*\)[^{;]*\{",
)


def blank_out(text: str) -> str:
    """Return `text` with comments and string contents replaced by spaces.

    Lengths and line numbers are preserved, so an index in the result is an index
    in the original and a line number still means what it says.
    """
    out: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        two = text[i : i + 2]
        three = text[i : i + 3]
        if three == '"""':
            end = text.find('"""', i + 3)
            end = n if end == -1 else end + 3
            out.append(_keep_newlines(text[i:end]))
            i = end
            continue
        if two == "//":
            end = text.find("\n", i)
            end = n if end == -1 else end
            out.append(" " * (end - i))
            i = end
            continue
        if two == "/*":
            end = text.find("*/", i + 2)
            end = n if end == -1 else end + 2
            out.append(_keep_newlines(text[i:end]))
            i = end
            continue
        char = text[i]
        if char == "'":
            end = text.find("'", i + 1)
            end = n if end == -1 else end + 1
            out.append(_keep_newlines(text[i:end]))
            i = end
            continue
        if char == '"':
            j = i + 1
            while j < n and text[j] != '"':
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "\n":
                    break
                j += 1
            end = min(j + 1, n)
            out.append(_keep_newlines(text[i:end]))
            i = end
            continue
        out.append(char)
        i += 1
    return "".join(out)


def _keep_newlines(chunk: str) -> str:
    return "".join("\n" if c == "\n" else " " for c in chunk)


def _body_of(code: str, open_at: int) -> tuple[int, int]:
    """Brace-match from the first `{` at or after `open_at`; return (start, end)."""
    start = code.find("{", open_at)
    if start == -1:
        return -1, -1
    depth = 0
    for index in range(start, len(code)):
        if code[index] == "{":
            depth += 1
        elif code[index] == "}":
            depth -= 1
            if depth == 0:
                return start, index
    return start, len(code)


def _line_of(code: str, index: int) -> int:
    return code.count("\n", 0, index) + 1


def _functions_that_enter(code: str) -> list[str]:
    """Names of functions in this file whose own body calls `UndalForeground.enter`."""
    names: list[str] = []
    for match in FUN_CALLING_ENTER.finditer(code):
        body_start, body_end = _body_of(code, match.end() - 1)
        if body_start == -1:
            continue
        if ENTER_FOREGROUND.search(code[body_start:body_end]):
            names.append(match.group("name"))
    return names


def check(path: pathlib.Path, code: str) -> list[str]:
    problems: list[str] = []
    name = path.name
    # `relative_to` would throw on a path outside the checkout, and a gate that
    # crashes reports nothing at all.
    relative = path.relative_to(ROOT) if path.is_relative_to(ROOT) else path

    if name != FOREGROUND_GATEWAY:
        reported: set[int] = set()
        for pattern in FOREGROUND_API:
            for match in pattern.finditer(code):
                if match.start() in reported:
                    continue
                reported.add(match.start())
                line = _line_of(code, match.start())
                problems.append(
                    f"{relative}:{line}: `{match.group(0).strip()}` is the foreground-service API. "
                    f"Go through `UndalForeground` — it is the one file that keeps Android's "
                    f"promise, and the one place a refusal is a logged `false` instead of a dead process."
                )

    helpers = _functions_that_enter(code)
    for match in ON_START_COMMAND.finditer(code):
        body_start, body_end = _body_of(code, match.end())
        if body_start == -1:
            problems.append(f"{relative}: `onStartCommand` has no body this gate can read.")
            continue
        body = code[body_start:body_end]
        entered = ENTER_FOREGROUND.search(body)
        if entered is None:
            for helper in helpers:
                candidate = re.search(r"\b%s\s*\(" % re.escape(helper), body)
                if candidate is not None:
                    entered = candidate
                    break
        if entered is None:
            problems.append(
                f"{relative}:{_line_of(code, body_start)}: `onStartCommand` never calls "
                f"`UndalForeground.enter(...)`. A service started with `startForegroundService()` "
                f"that stops without taking the foreground is killed by the platform from the "
                f"main thread, and no `runCatching` at the call site can catch it."
            )
            continue
        stop = STOPS.search(body)
        if stop is not None and stop.start() < entered.start():
            problems.append(
                f"{relative}:{_line_of(code, body_start + stop.start())}: `onStartCommand` reaches "
                f"`{stop.group(0).strip()}` before it calls `UndalForeground.enter(...)`. Take the "
                f"foreground first, every time: the branches that stop are exactly the ones that "
                f"used to kill the process."
            )

    for pattern in TASK_RESULT_READS:
        for match in pattern.finditer(code):
            line = _line_of(code, match.start())
            problems.append(
                f"{relative}:{line}: `{match.group(0).strip()}` reads a `Task` as though it waited. "
                f"`Task.getResult()` throws unless the task has already finished, so wrapped in "
                f"`runCatching` it silently answers `false` — which is how a kitchen phone came to "
                f"believe it was a customer's. Use `await()` in a coroutine, the task's own callback, "
                f"or the role this phone already established."
            )

    return problems


def main() -> int:
    files = sorted(SOURCE_ROOT.rglob("*.kt"))
    if not files:
        print("check_foreground_contract: no Kotlin sources found under app/src", file=sys.stderr)
        return 1

    problems: list[str] = []
    scanned = 0
    for path in files:
        code = blank_out(path.read_text(encoding="utf-8"))
        scanned += 1
        problems.extend(check(path, code))

    if problems:
        print("\n".join(problems))
        print(
            f"\ncheck_foreground_contract: {len(problems)} problem(s) across {scanned} Kotlin files",
            file=sys.stderr,
        )
        return 1

    print(f"check_foreground_contract: {scanned} Kotlin files — every service keeps Android's "
          f"foreground promise, and no Task is read as though it waited")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
