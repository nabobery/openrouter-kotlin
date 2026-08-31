#!/usr/bin/env python3
"""kdoc-audit.py — every public stable symbol in the curated sources has KDoc (PRD §7).

Scans the curated Kotlin sources (`sdk/src/commonMain`, `sdk/src/jvmMain`) for `public` declarations
(`class`/`interface`/`object`/`fun`/`val`/`var`/`annotation class`/`typealias`) that are **not** immediately
preceded by a `/** … */` KDoc block. `override` members and non-public declarations are skipped; the generated
surface lives under `build/` and is never scanned. `check` exits 1 listing every offender as `path:line: decl`.
Python 3 stdlib only.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

_DECL = re.compile(
    r"^public\s+"
    r"(?:expect\s+|actual\s+|inline\s+|value\s+|data\s+|sealed\s+|abstract\s+|open\s+|final\s+|enum\s+|"
    r"external\s+|suspend\s+|operator\s+|infix\s+|tailrec\s+|const\s+|lateinit\s+|fun\s+interface\s+)*"
    r"(class|interface|object|fun|val|var|annotation class|typealias)\b"
)
_SOURCE_ROOTS = ("sdk/src/commonMain", "sdk/src/jvmMain")


def offenders(text: str) -> list[tuple[int, str]]:
    """Return (1-based line, declaration text) for each undocumented public declaration in `text`.

    A public `val`/`var` that is a **primary-constructor parameter** (it appears while a `(` is still open) is not
    flagged: the Kotlin/Dokka convention documents those through the enclosing type's KDoc `@property`/`@param`
    tags, not a `/** */` above each parameter. Every other public declaration — types, functions, top-level and
    class-body properties, typealiases, annotation classes — must carry its own KDoc.
    """
    lines = text.splitlines()
    out: list[tuple[int, str]] = []
    depth = 0  # running parenthesis depth (approximate; source here is comment/paren-clean enough for it)
    for i, raw in enumerate(lines):
        stripped = raw.strip()
        depth_at_line_start = depth
        depth += raw.count("(") - raw.count(")")
        if " override " in f" {stripped} ":
            continue  # overrides inherit their supertype's KDoc
        m = _DECL.match(stripped)
        if not m:
            continue
        if m.group(1) in ("val", "var") and depth_at_line_start > 0:
            continue  # a constructor-parameter property — documented via the type's @property tags
        # Walk upward past annotations (including multi-line ones) and blank lines; documented iff the first
        # meaningful line above closes a KDoc block. A running paren balance skips annotation-argument continuation
        # lines (e.g. a multi-line `@RequiresOptIn( … )` between the KDoc and the declaration).
        j = i - 1
        bal = 0
        while j >= 0:
            up = lines[j].strip()
            bal += up.count(")") - up.count("(")
            if up.startswith("@") or up == "" or bal > 0:
                j -= 1
                continue
            break
        if j >= 0 and lines[j].strip().endswith("*/"):
            continue
        out.append((i + 1, stripped))
    return out


def scan(root: pathlib.Path) -> list[str]:
    findings: list[str] = []
    for src_root in _SOURCE_ROOTS:
        base = root / src_root
        if not base.is_dir():
            continue
        for kt in sorted(base.rglob("*.kt")):
            for line, decl in offenders(kt.read_text()):
                findings.append(f"{kt.relative_to(root)}:{line}: {decl}")
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["check"])
    parser.add_argument("--root", default=".")
    args = parser.parse_args(argv)
    findings = scan(pathlib.Path(args.root).resolve())
    if findings:
        print("kdoc-audit: FAIL — undocumented public symbols:", file=sys.stderr)
        for f in findings:
            print(f"  {f}", file=sys.stderr)
        return 1
    print("kdoc-audit: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
