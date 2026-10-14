#!/usr/bin/env python3
"""Read Gradle's JUnit XML and print a markdown list of the failing unit tests.

`:app:testReleaseUnitTest` writes one `TEST-<class>.xml` per test class and, on
its own, prints only "There were failing tests. See the report at: …". The report
is an HTML file on the runner, which is no use to somebody reading a workflow log
on a phone, so this turns the same facts into four lines per failure:

    **MoneyTest › groupsThousandTakaInTheLakhPattern**
    org.junit.ComparisonError: expected:<৳ 1,00,000> but was:<৳ 100,000>
    at …MoneyTest.groupsThousandTakaInTheLakhPattern(MoneyTest.kt:21)

Usage:  python3 tools/read-test-results.py app/build/test-results/testReleaseUnitTest
It never fails the step: it either prints the failures or says it found none.
"""

from __future__ import annotations

import pathlib
import sys
import xml.etree.ElementTree as ElementTree

ASSERT_LINE = ("expected", "but was", "AssertionError", "ComparisonFailure", "at ")


def failure_text(case: ElementTree.Element) -> tuple[str, str]:
    """(type: message, stack) for a failed or errored test case."""
    for tag in ("failure", "error"):
        node = case.find(tag)
        if node is None:
            continue
        kind = (node.get("type") or "").split(".")[-1]
        message = (node.get("message") or "").strip()
        body = (node.text or "").strip()
        headline = f"{kind}: {message}".strip(": ").strip() or "the test failed without a message"
        if not message and body:
            headline = f"{kind}: {body.splitlines()[0]}" if kind else body.splitlines()[0]
        return headline, body
    return "", ""


def interesting_stack(body: str) -> str:
    """The first frame that is our own code, or a comparison line, whichever comes first."""
    for line in body.splitlines():
        stripped = line.strip()
        if stripped.startswith("at ") and ("com.undal" in stripped or "Test.kt" in stripped):
            return stripped
    for line in body.splitlines():
        if "expected" in line and "but was" in line:
            return line.strip()
    return ""


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: read-test-results.py <test-results directory>")
        return 0

    results = pathlib.Path(sys.argv[1])
    if not results.is_dir():
        print("\n_No unit-test results were written — the failure happened before the tests ran._")
        return 0

    total = 0
    failures: list[str] = []
    for xml_path in sorted(results.glob("TEST-*.xml")):
        try:
            root = ElementTree.parse(xml_path).getroot()
        except ElementTree.ParseError:
            print(f"\n_Unreadable test report: {xml_path.name}_")
            continue
        for case in root.iter("testcase"):
            total += 1
            headline, body = failure_text(case)
            if not headline:
                continue
            name = case.get("name") or "?"
            classname = (case.get("classname") or xml_path.stem.removeprefix("TEST-")).split(".")[-1]
            block = [f"**{classname} › {name}**", headline]
            stack = interesting_stack(body)
            if stack:
                block.append(stack)
            failures.append("\n".join(block))

    print()
    if not failures:
        print(f"_{total} unit test(s) ran and none failed._")
        return 0
    print(f"### {len(failures)} failing unit test(s) of {total}")
    print()
    for block in failures:
        print(block)
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
