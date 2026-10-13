#!/usr/bin/env python3
"""Import-contract guards for the Undal Kotlin sources (LAW-11 helpers, no compiler needed).

`check_kotlin_shape.py` asks "does this file look like Kotlin?". This one asks the
next question: **is every cross-package name actually imported at the point of
use?** — a mistake that costs a whole CI round trip, because Gradle prints a dozen
"unresolved reference" lines and every one after the first is noise.

Five shapes, each of which has already failed a real build:

1. `task.await()` without `kotlinx.coroutines.tasks.await`. A Firebase `Task` is
   not a Kotlin suspend function; the import is what makes it one, so the missing
   import shows up as `Unresolved reference 'await'`.
2. `Modifier.background(...)` without `androidx.compose.foundation.background`.
   The extension lives in `foundation`, not in `material3`, and nothing else in
   the imports pulls it in.
3. `semantics { stateDescription = ... }` without
   `androidx.compose.ui.semantics.stateDescription`. The `semantics { }` receiver
   resolves either way, which is what makes this one hard to spot by eye.
4. `MaterialTheme.undalSemantics` without
   `com.undal.syl.app.core.theme.undalSemantics`. The theme extension property is
   ours, so the compiler has no idea where to look for it.
5. Any top-level type, function or property this repository declares in **another**
   package, used unqualified and unimported — `UndalBuildInfo.workerBaseUrl` in
   `di/AppModule.kt` being the original example.

The check is deliberately conservative: it never guesses about third-party code
(the JDK, AndroidX, Firebase, osmdroid), it ignores comments and string literals,
and it treats a wildcard import as covering its package. A clean run means the
five bug shapes are gone, not that the project compiles — the compiler still is
the compiler. It is written to say *one sentence per real problem*, with the file,
the line and the exact import to add, so the fix is obvious from the log alone.

Run it from the repository root:

    python3 tools/check_import_contracts.py
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE_ROOT = ROOT / "app" / "src"

APP_PACKAGE = "com.undal.syl.app"

# ---------------------------------------------------------------------------
# The four extension symbols that have to be imported to be visible, with the
# sentence each missing import already produced in CI.
# ---------------------------------------------------------------------------

EXTENSION_CONTRACTS = (
    {
        "symbol": "await",
        "import": "kotlinx.coroutines.tasks.await",
        "package": "kotlinx.coroutines.tasks",
        # `\.await\(\)` — never `awaitClose(`, never our own `awaitSomething(`.
        "use": re.compile(r"\.await\s*\(\s*\)"),
        # A coroutine `await()` only exists on Firebase/GMS Tasks in this app, so
        # the rule is scoped to files that already talk to one of them.
        "scope": re.compile(r"^import\s+(?:com\.google\.firebase|com\.google\.android\.gms)\b", re.MULTILINE),
        "why": "Firebase/GMS Task.await() is an extension from kotlinx-coroutines-play-services",
    },
    {
        "symbol": "background",
        "import": "androidx.compose.foundation.background",
        "package": "androidx.compose.foundation",
        # Both spellings count: `Modifier.background(` and `.background(` on a
        # Modifier receiver, which is how the shared surfaces call it.
        "use": re.compile(r"(?<![\w])background\s*\("),
        "scope": None,
        "why": "Modifier.background() is a foundation extension, not a material3 one",
    },
    {
        "symbol": "stateDescription",
        "import": "androidx.compose.ui.semantics.stateDescription",
        "package": "androidx.compose.ui.semantics",
        # `Modifier.undalStateDescription(...)` is a different name: the word
        # boundary before `stateDescription` is broken by the `l`, so it can
        # never match here. The dot allowance is for the receiver spelling.
        "use": re.compile(r"(?<![\w])stateDescription\b"),
        "scope": None,
        "why": "the semantics{} scope resolves without it, so the compiler blames the property",
    },
    {
        "symbol": "undalSemantics",
        "import": "com.undal.syl.app.core.theme.undalSemantics",
        "package": "com.undal.syl.app.core.theme",
        # Always written `MaterialTheme.undalSemantics`, so the preceding dot is
        # expected and the name itself is what has to be imported.
        "use": re.compile(r"(?<![\w])undalSemantics\b"),
        "scope": None,
        "why": "the theme extension property is ours, so it needs our import",
    },
)

IMPORT_LINE = re.compile(r"^\s*import\s+([A-Za-z_][\w.]*)(?:\.\*)?\s*(?:as\s+[A-Za-z_]\w*)?\s*$", re.MULTILINE)
PACKAGE_LINE = re.compile(r"^\s*package\s+([\w.]+)\s*$", re.MULTILINE)
WILDCARD_IMPORT = re.compile(r"^\s*import\s+([A-Za-z_][\w.]*)\.\*\s*(?:as\s+[A-Za-z_]\w*)?$", re.MULTILINE)
IMPORT_STATEMENT = re.compile(r"^\s*import\s+.*$", re.MULTILINE)

# Top-level declarations, at column 0 — the style every file in this repository
# already follows. `enum class X`, `data object X`, `sealed interface X` are all
# covered: the leading words are modifiers, the keyword is class/interface/object.
TOP_LEVEL_DECLARATION = re.compile(
    r"^(?:(?:public|internal|private|open|abstract|sealed|data|value|enum|annotation|inline|"
    r"const|external|expect|actual|lateinit|override|operator|infix|tailrec|suspend|companion)\s+)*"
    r"(?P<kind>class|interface|object|typealias|fun|val|var)\s+"
    r"(?P<qualified>[A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*)",
    re.MULTILINE,
)

# Any declaration indented inside a file (nested classes, member functions, local
# vals): a name declared *here* is not an unimported cross-package reference.
LOCAL_DECLARATION = re.compile(
    r"^[ \t]+(?:(?:public|internal|private|protected|open|abstract|sealed|data|value|enum|"
    r"annotation|inline|const|external|lateinit|override|operator|infix|tailrec|suspend|"
    r"companion)\s+)*(?:class|interface|object|typealias)\s+([A-Za-z_]\w*)",
    re.MULTILINE,
)

# A reference is: an identifier that does not continue a dotted chain... unless it
# starts one. `UndalBuildInfo.workerBaseUrl` starts a chain; the `UndalBuildInfo`
# in `com.undal.syl.app.core.config.UndalBuildInfo` does not.
REFERENCE = re.compile(r"(?<![\w.])([A-Z][A-Za-z0-9_]*)\s*(?=\.)")

# Type positions: `val x: UndalLocation?`, `-> StoreConfig`, `is VendorOrder`,
# `as MenuSnapshot`, `List<StoreSlot>`. Only types (never functions or
# properties) are checked here, which keeps `map { … }` and `fun` out of it.
TYPE_REFERENCE = re.compile(r"(?::|->|\bis\b|\bas\b|<|,)\s*([A-Z][A-Za-z0-9_]*)\s*(?=[?>,)\s{])")

# A constructor or top-level-function call: `UndalStatusPill(`, `VendorOrder(`.
CALL_REFERENCE = re.compile(r"(?<![\w.])([A-Z][A-Za-z0-9_]*)\s*\(")

GENERATED_TYPES = frozenset({"R", "BuildConfig"})


def templated_expression(text: str, i: int, n: int) -> int:
    """Index just past the `${ … }` that starts at [i] (which is the `$`).

    A Kotlin string template holds real code: `"${UndalBuildInfo.workerHost}"`
    is a reference to a top-level type in another package, and a gate that blanks
    whole string literals would never see it. The expression is copied through
    verbatim — nested braces, nested string literals and all — while the literal
    text around it stays blanked.
    """
    depth = 0
    j = i + 1  # sitting on '{'
    while j < n:
        char = text[j]
        if char == "{":
            depth += 1
            j += 1
            continue
        if char == "}":
            depth -= 1
            j += 1
            if depth == 0:
                return j
            continue
        if text[j : j + 3] == '"""':
            end = text.find('"""', j + 3)
            j = n if end == -1 else end + 3
            continue
        if char == '"':
            j += 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"' or text[j] == "\n":
                    j += 1
                    break
                j += 1
            continue
        if char == "'":
            j += 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'" or text[j] == "\n":
                    j += 1
                    break
                j += 1
            continue
        j += 1
    return n


def blank(text: str) -> str:
    """Spaces, keeping newlines, so line numbers do not move."""
    return "".join(c if c == "\n" else " " for c in text)


def strip_comments_and_literals(text: str) -> str:
    """Blank out comments and string/char literals, keeping every newline.

    Line numbers have to survive, and the `//` inside `https://…` must not start
    a comment, so this walks the source the way the Kotlin lexer would.
    """
    out: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        char = text[i]
        if char == "\n":
            out.append(char)
            i += 1
            continue
        pair = text[i : i + 2]
        if pair == "//":
            end = text.find("\n", i)
            if end == -1:
                end = n
            out.append(" " * (end - i))
            i = end
            continue
        if pair == "/*":
            end = text.find("*/", i + 2)
            end = n if end == -1 else end + 2
            out.append("".join(c if c == "\n" else " " for c in text[i:end]))
            i = end
            continue
        if text[i : i + 3] == '"""':
            j = i + 3
            pieces: list[str] = []
            while j < n:
                if text[j : j + 3] == '"""':
                    pieces.append(blank(text[j : j + 3]))
                    j += 3
                    break
                if text[j : j + 2] == "${":
                    end = templated_expression(text, j, n)
                    pieces.append(text[j:end])
                    j = end
                    continue
                pieces.append(text[j])
                j += 1
            for piece in pieces:
                out.append(piece if piece.startswith("${") else blank(piece))
            i = j
            continue
        if char == "'":
            # A character literal — `','`, `'"'`, `'\n'` — may hold a quote.
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'" or text[j] == "\n":
                    j += 1
                    break
                j += 1
            out.append("".join(c if c == "\n" else " " for c in text[i:j]))
            i = j
            continue
        if char == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                if text[j] == "\n":
                    break
                if text[j : j + 2] == "${":
                    # Real code inside a string literal: keep it visible, then
                    # carry on blanking the rest of the literal.
                    out.append(blank(text[i:j]))
                    end = templated_expression(text, j, n)
                    out.append(text[j:end])
                    i = end
                    j = end
                    continue
                j += 1
            if i < j:
                out.append(blank(text[i:j]))
            i = j
            continue
        out.append(char)
        i += 1
    return "".join(out)


class Source:
    """One Kotlin file, already reduced to the parts the checks care about."""

    def __init__(self, path: pathlib.Path) -> None:
        self.path = path
        self.rel = path.relative_to(ROOT)
        self.raw = path.read_text(encoding="utf-8")
        self.code = strip_comments_and_literals(self.raw)
        # Usages are searched without the import block, so an `import a.b.c` line
        # can never be mistaken for a use of `c`.
        self.body = IMPORT_STATEMENT.sub("", self.code)

        package = PACKAGE_LINE.search(self.code)
        self.package = package.group(1) if package else ""

        self.imports: set[str] = set()
        for match in IMPORT_LINE.finditer(self.code):
            self.imports.add(match.group(1))
        self.wildcards: set[str] = set(match.group(1) for match in WILDCARD_IMPORT.finditer(self.code))

        self.top_level: dict[str, str] = {}  # name → kind (class/fun/val/…)
        self.local: set[str] = set()
        for match in TOP_LEVEL_DECLARATION.finditer(self.code):
            name = match.group("qualified").split(".")[-1].strip()
            self.top_level[name] = match.group("kind")
        for match in LOCAL_DECLARATION.finditer(self.code):
            self.local.add(match.group(1))

    def declares_itself(self, symbol: str) -> bool:
        """True when this file declares the symbol, so no import is needed.

        The declaration line is `fun Modifier.background(…)`, `object
        UndalBuildInfo`, `val MaterialTheme.undalSemantics` — the nearest name
        before `(`, `:` or `=` is what we recorded as the declared symbol.
        """
        return symbol in self.top_level or symbol in self.local

    def imported(self, package: str, symbol: str) -> bool:
        return f"{package}.{symbol}" in self.imports or package in self.wildcards

    def at(self, offset: int) -> int:
        return self.body.count("\n", 0, offset) + 1


def collect_index(files: list[Source]) -> dict[str, str]:
    """name → package, for every top-level declaration in the project.

    A name declared top-level in two different packages is ambiguous for a reader
    and for this gate, so it is dropped from the index rather than guessed at.
    """
    seen: dict[str, str] = {}
    ambiguous: set[str] = set()
    for source in files:
        for name in source.top_level:
            if name in seen and seen[name] != source.package:
                ambiguous.add(name)
            seen[name] = source.package
    return {name: package for name, package in seen.items() if name not in ambiguous}


def check_extension_contracts(files: list[Source]) -> list[str]:
    problems: list[str] = []
    for contract in EXTENSION_CONTRACTS:
        symbol = str(contract["symbol"])
        for source in files:
            if source.declares_itself(symbol) or source.imported(str(contract["package"]), symbol):
                continue
            scope = contract["scope"]
            if scope is not None and not scope.search(source.code):
                continue
            match = contract["use"].search(source.body)
            if match is None:
                continue
            problems.append(
                f"{source.rel}:{source.at(match.start())}: `{symbol}` used without its import — "
                f"{contract['why']}. Add:\n    import {contract['import']}"
            )
    return problems


def check_unqualified_types(sources: list[Source], index: dict[str, str]) -> list[str]:
    """The shape that produced `Unresolved reference 'UndalBuildInfo'`.

    Anything this project declares top-level can be imported anywhere; if it is
    used in another package without its import, the compiler has nowhere to look.
    Three reference shapes are read, each narrowed to the kinds of declaration
    that can legally stand there — so a `map { … }` call or a lambda parameter is
    never mistaken for a type.
    """
    declared_kinds: dict[tuple[str, str], set[str]] = {}
    for source in sources:
        for name, kind in source.top_level.items():
            declared_kinds.setdefault((source.package, name), set()).add(kind)

    problems: list[str] = []
    for source in sources:
        for pattern, kinds in (
            (REFERENCE, None),  # `Name.member` — an object, class or companion member
            (TYPE_REFERENCE, ("class", "interface", "object", "typealias")),
            (CALL_REFERENCE, ("class", "interface", "object", "typealias", "fun")),
        ):
            for match in pattern.finditer(source.body):
                name = match.group(1)
                package = index.get(name)
                if package is None or package == source.package:
                    continue
                if name in GENERATED_TYPES or source.declares_itself(name):
                    continue
                if kinds is not None and not (declared_kinds.get((package, name), set()) & set(kinds)):
                    continue
                if source.imported(package, name):
                    continue
                problems.append(
                    f"{source.rel}:{source.at(match.start())}: `{name}` is declared in "
                    f"{package} and used here without its import. Add:\n    import {package}.{name}"
                )
    return problems


def main() -> int:
    if not SOURCE_ROOT.is_dir():
        print(f"check_import_contracts: {SOURCE_ROOT} not found")
        return 2

    sources = [Source(path) for path in sorted(SOURCE_ROOT.rglob("*.kt"))]
    index = collect_index(sources)

    problems = check_extension_contracts(sources)
    problems += check_unqualified_types(sources, index)

    if problems:
        for problem in problems:
            print(problem)
        print(f"\ncheck_import_contracts: {len(problems)} missing import(s) across {len(sources)} Kotlin files")
        return 1

    print(
        f"check_import_contracts: {len(sources)} Kotlin files — every cross-package symbol "
        f"is imported where it is used ({len(index)} top-level names indexed)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
