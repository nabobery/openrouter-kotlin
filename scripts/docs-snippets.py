#!/usr/bin/env python3
"""docs-snippets.py — inject compile-checked source snippets into the Markdown guides.

Snippets are ordinary `// region <name>` … `// endregion` blocks in the compiled `:samples:docs` module (and, for
the Android/iOS guides, in the sample modules that `samplesCheck` / `ios-consumer-check.sh` compile). A Markdown
guide references one with a marker pair around a fenced code block:

    <!-- snippet: samples/docs/src/main/kotlin/guides/FirstChatRequest.kt#client -->
    ```kotlin
    ...injected verbatim from the region...
    ```
    <!-- /snippet -->

`update` rewrites every fenced block between the markers with the referenced region's lines (common leading
indentation stripped; the `region`/`endregion` marker lines themselves excluded). `check` renders in memory and
exits 1 on any stale block, or a marker that references a missing file or region. The regions use IntelliJ's own
`// region` / `// endregion` folding syntax so the examples stay readable and refactorable in the IDE, and the same
syntax works as a plain comment in `.swift`. Python 3 stdlib only.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

# A snippet marker: `<!-- snippet: <path>#<region> -->`, the fenced block, then `<!-- /snippet -->`.
_SNIPPET_RE = re.compile(
    r"(?P<open><!--\s*snippet:\s*(?P<path>[^#\s]+)#(?P<region>[^\s]+)\s*-->[ \t]*\n)"
    r"(?P<fence>```[^\n]*\n)(?P<body>.*?)(?P<fenceclose>```[ \t]*\n)"
    r"(?P<close><!--\s*/snippet\s*-->)",
    re.DOTALL,
)
_REGION_START = re.compile(r"^\s*//\s*region\s+(\S+)\s*$")
_REGION_END = re.compile(r"^\s*//\s*endregion\b.*$")


class SnippetError(Exception):
    """A marker references a missing file/region, or a source file has malformed regions."""


def extract_regions(text: str) -> dict[str, list[str]]:
    """Map each `// region NAME` block to its dedented lines. Nested regions are an error."""
    regions: dict[str, list[str]] = {}
    current: str | None = None
    buffer: list[str] = []
    for line in text.splitlines():
        start = _REGION_START.match(line)
        if start:
            if current is not None:
                raise SnippetError(f"nested region '{start.group(1)}' inside '{current}'")
            current = start.group(1)
            buffer = []
            continue
        if _REGION_END.match(line):
            if current is None:
                raise SnippetError("`// endregion` with no open region")
            regions[current] = _dedent(buffer)
            current = None
            continue
        if current is not None:
            buffer.append(line)
    if current is not None:
        raise SnippetError(f"region '{current}' is never closed")
    return regions


def _dedent(lines: list[str]) -> list[str]:
    """Strip the common leading whitespace of the non-blank lines; trim leading/trailing blank lines."""
    trimmed = list(lines)
    while trimmed and not trimmed[0].strip():
        trimmed.pop(0)
    while trimmed and not trimmed[-1].strip():
        trimmed.pop()
    indents = [len(ln) - len(ln.lstrip()) for ln in trimmed if ln.strip()]
    common = min(indents) if indents else 0
    return [ln[common:] if ln.strip() else "" for ln in trimmed]


def render(md_text: str, root: pathlib.Path) -> str:
    """Return `md_text` with every snippet body replaced by its region's current lines."""
    cache: dict[str, dict[str, list[str]]] = {}

    def replace(match: re.Match[str]) -> str:
        rel = match.group("path")
        region = match.group("region")
        if rel not in cache:
            src = root / rel
            if not src.is_file():
                raise SnippetError(f"snippet source not found: {rel}")
            cache[rel] = extract_regions(src.read_text())
        regions = cache[rel]
        if region not in regions:
            raise SnippetError(f"region '{region}' not found in {rel} (have: {', '.join(sorted(regions)) or 'none'})")
        body = "".join(f"{ln}\n" for ln in regions[region])
        return match.group("open") + match.group("fence") + body + match.group("fenceclose") + match.group("close")

    return _SNIPPET_RE.sub(replace, md_text)


def _guide_files(root: pathlib.Path) -> list[pathlib.Path]:
    return sorted(p for p in (root / "docs" / "guides").rglob("*.md"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["update", "check"])
    parser.add_argument("--root", default=".", help="repository root (default: cwd)")
    args = parser.parse_args(argv)
    root = pathlib.Path(args.root).resolve()

    failures: list[str] = []
    changed: list[str] = []
    for md in _guide_files(root):
        original = md.read_text()
        try:
            rendered = render(original, root)
        except SnippetError as exc:
            failures.append(f"{md.relative_to(root)}: {exc}")
            continue
        if rendered != original:
            if args.command == "update":
                md.write_text(rendered)
                changed.append(str(md.relative_to(root)))
            else:
                failures.append(f"{md.relative_to(root)}: snippet blocks are stale; run `docs-snippets.py update`")

    if failures:
        print("docs-snippets: FAIL", file=sys.stderr)
        for f in failures:
            print(f"  {f}", file=sys.stderr)
        return 1
    if args.command == "update" and changed:
        print("docs-snippets: updated " + ", ".join(changed))
    else:
        print("docs-snippets: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
