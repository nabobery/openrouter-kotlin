#!/usr/bin/env python3
"""Print the body of a single `## [<version>]` section of CHANGELOG.md (release-notes source).

The header line itself is excluded; the body runs until the next `## ` heading or end of file. Exits 1 if the
section is absent or has no non-blank content. Python 3 stdlib only.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys


def extract(text: str, version: str) -> str | None:
    """Return the trimmed body under `## [<version>]`, or None if absent/empty."""
    lines = text.splitlines()
    # Match `## [<version>]` optionally followed by ` - <date>`; the version is matched literally.
    header = re.compile(r"^##\s+\[" + re.escape(version) + r"\](\s|$)")
    start = next((i for i, line in enumerate(lines) if header.match(line)), None)
    if start is None:
        return None
    body: list[str] = []
    for line in lines[start + 1 :]:
        if line.startswith("## "):
            break
        body.append(line)
    trimmed = "\n".join(body).strip()
    return trimmed or None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Extract one CHANGELOG.md release section.")
    parser.add_argument("version")
    parser.add_argument("--changelog", type=pathlib.Path, default=pathlib.Path("CHANGELOG.md"))
    args = parser.parse_args(argv)

    body = extract(args.changelog.read_text(), args.version)
    if body is None:
        print(f"no non-empty '## [{args.version}]' section in {args.changelog}", file=sys.stderr)
        return 1
    print(body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
