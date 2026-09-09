#!/usr/bin/env python3
"""Extract SDKGen generator diagnostics from a Gradle problems-report.html and
print them as a Markdown `## Generator diagnostics` section.

The kotlin-sdkgen plugin reports blocking generation diagnostics
(SDKGEN-INVALID-CANONICAL-EXTENSION, SDKGEN-PROJECTION-UNREPRESENTABLE-*, …)
through Gradle's Problems API. Only a summary count ("Generation blocked by N
blocking diagnostic(s)") reaches the console log, so a blocked drift PR is
undiagnosable from refresh.log alone. This turns the report's structured
`problemDetails` into a human-readable excerpt that drift-refresh.sh echoes into
the PR body. Advisory only: always exits 0.

Usage: extract-generator-diagnostics.py [problems-report.html] [--limit N]
"""
from __future__ import annotations

import json
import re
import sys

LIMIT = 40


def parse(html: str) -> list[tuple[str, str]]:
    """Return (diagnostic-type, detail) pairs for SDKGen problems, de-duplicated,
    in first-seen order."""
    out: list[tuple[str, str]] = []
    seen: set[tuple[str, str]] = set()
    for m in re.finditer(r'\{"problemId"', html):
        start, depth, i, in_str, esc = m.start(), 0, m.start(), False, False
        while i < len(html):
            c = html[i]
            if in_str:
                if esc:
                    esc = False
                elif c == "\\":
                    esc = True
                elif c == '"':
                    in_str = False
            elif c == '"':
                in_str = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    i += 1
                    break
            i += 1
        try:
            obj = json.loads(html[start:i])
        except ValueError:
            continue
        pid = obj.get("problemId")
        if isinstance(pid, list):
            names = [p.get("name", "") for p in pid if isinstance(p, dict)]
            display = pid[-1].get("displayName", "") if pid else ""
        else:
            names, display = [], ""
        detail = obj.get("problemDetails")
        if not detail or not any("sdkgen" in n for n in names):
            continue
        key = (display, detail)
        if key in seen:
            continue
        seen.add(key)
        out.append(key)
    return out


def main() -> int:
    args = [a for a in sys.argv[1:]]
    limit = LIMIT
    if "--limit" in args:
        k = args.index("--limit")
        limit = int(args[k + 1])
        del args[k : k + 2]
    path = args[0] if args else "build/reports/problems/problems-report.html"
    print("## Generator diagnostics")
    print()
    try:
        html = open(path, encoding="utf-8").read()
    except OSError as exc:
        print(f"_(problems report unavailable: {exc})_")
        return 0
    diags = parse(html)
    if not diags:
        print("_(no SDKGen diagnostics found in the problems report)_")
        return 0
    shown = diags[:limit]
    for display, detail in shown:
        print(f"- **{display}**: {detail}")
    if len(diags) > limit:
        print()
        print(f"_… and {len(diags) - limit} more (see the attached problems-report.html)._")
    return 0


if __name__ == "__main__":
    sys.exit(main())
