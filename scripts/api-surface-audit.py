#!/usr/bin/env python3
"""api-surface-audit.py — dump-based public-surface audit that guards the 1.0 curated API.

It reads the committed BCV ABI dumps (`sdk/api/sdk.api` JVM/android-off, `sdk/api/jvm/sdk.api`
JVM/android-on, `sdk/api/sdk.klib.api` klib/native/JS) and the curated Kotlin sources under
`sdk/src/**`, partitions every dumped declaration into *curated* (its class/top-level facade is
declared in a file under `sdk/src`) vs *generated* (everything else), and FAILS on any curated
declaration that breaks the 1.0 curation contract:

  (a) data class      — no curated `data class`. Detected from source (`data class` in sdk/src,
                        cross-platform) and, belt-and-suspenders, from a curated dump class that
                        exhibits both a synthetic `component1` and `copy` member.
  (b) @PublishedApi   — every curated `@PublishedApi` symbol must be in PUBLISHED_API_ALLOWLIST
                        (currently empty; ADR 0008 is the source of truth).

  Explicit return types are NOT audited here: the strict `explicitApi()` compiler gate already
  requires them on the source, and a compiled ABI dump always carries a resolved return descriptor,
  so a dump-based "missing return type" check could never fire on real output.

It also PRINTS, informationally (never a violation), every `@OpenRouterExperimentalApi`-annotated
curated declaration (file + symbol).

Only the main source sets (commonMain, jvmMain) form the *public* curated surface — test source sets
are neither public nor present in the ABI dumps, and may legitimately contain their own data classes.

Usage:
  api-surface-audit.py [--check] [--root .]     # --check exits non-zero on any violation

Python 3 stdlib only; mirrors kdoc-audit.py / coverage-dashboard.py house style.
"""
from __future__ import annotations

import argparse
import os
import pathlib
import re
import sys
from collections import namedtuple

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The public curated surface is the *main* source sets only. Test sources are not public API, are
# absent from the ABI dumps, and may hold their own `data class` fixtures — scanning them would
# manufacture false violations.
SOURCE_ROOTS = ("sdk/src/commonMain", "sdk/src/jvmMain")

# BCV dumps to audit: (relative path, format). "jvm" dumps use JVM descriptors; "klib" the klib grammar.
DUMP_FILES = (
    ("sdk/api/sdk.api", "jvm"),          # JVM, android-off
    ("sdk/api/jvm/sdk.api", "jvm"),      # JVM, android-on
    ("sdk/api/sdk.klib.api", "klib"),    # klib / native / JS
)

# @PublishedApi symbols intentionally exposed in the ABI. ADR 0008 is the source of truth; the 1.0
# surface has none, so the allowlist is empty and any curated @PublishedApi symbol is a violation.
PUBLISHED_API_ALLOWLIST: set[str] = set()

EXPERIMENTAL_ANNOTATION = "@OpenRouterExperimentalApi"
PUBLISHED_API_ANNOTATION = "@PublishedApi"

Report = namedtuple("Report", ["violations", "experimental"])
CuratedSource = namedtuple("CuratedSource", ["fqns", "data_classes", "published_api", "experimental"])

_PACKAGE = re.compile(r"^package\s+([\w.]+)")
_TYPE_DECL = re.compile(
    r"^(?:public\s+|internal\s+|private\s+|protected\s+|@[\w.]+(?:\([^)]*\))?\s+)*"
    r"(?:expect\s+|actual\s+|sealed\s+|abstract\s+|open\s+|final\s+|data\s+|value\s+|inline\s+|"
    r"enum\s+|annotation\s+|fun\s+)*"
    r"(class|interface|object|annotation class|enum class)\s+([A-Za-z_][A-Za-z0-9_]*)"
)
# The declaration name following an annotation (for @PublishedApi / @OpenRouterExperimentalApi).
_NAME_TYPE = re.compile(r"\b(?:class|interface|object)\s+([A-Za-z_]\w*)")
_NAME_FUN = re.compile(r"\bfun\s+(?:<[^>]*>\s+)?(?:[\w.<>?]+\.)?([A-Za-z_]\w*)\s*[(<]")
_NAME_PROP = re.compile(r"\b(?:val|var)\s+(?:[\w.<>?]+\.)?([A-Za-z_]\w*)\b")


# --------------------------------------------------------------------------------------------------
# Source scanning: what is curated, and which curated declarations break the contract.
# --------------------------------------------------------------------------------------------------
def _package_of(text: str) -> str | None:
    for line in text.splitlines():
        m = _PACKAGE.match(line.strip())
        if m:
            return m.group(1)
    return None


def _decl_name(line: str) -> str | None:
    """The simple name declared on `line` (type, function, or property), else None."""
    for pat in (_NAME_TYPE, _NAME_FUN, _NAME_PROP):
        m = pat.search(line)
        if m:
            return m.group(1)
    return None


def _next_declaration(lines: list[str], start: int) -> tuple[int, str] | None:
    """From the annotation at `start`, the first real declaration line below it (skipping further
    annotations, blank lines, and comments). Returns (index, simple name) or None."""
    j = start + 1
    while j < len(lines):
        s = lines[j].strip()
        if s == "" or s.startswith("@") or s.startswith("//") or s.startswith("*") or s.startswith("/*"):
            j += 1
            continue
        name = _decl_name(s)
        return (j, name) if name else None
    return None


def scan_source(root: pathlib.Path) -> CuratedSource:
    """Walk the curated main source sets, returning the curated FQN set (dump-style `pkg/Name`,
    including each file's `…Kt` top-level facade) plus the source-level contract findings."""
    fqns: set[str] = set()
    data_classes: list[str] = []
    published_api: list[tuple[str, str]] = []  # (display, allowlist-key)
    experimental: list[str] = []               # display strings

    for src_root in SOURCE_ROOTS:
        base = root / src_root
        if not base.is_dir():
            continue
        for kt in sorted(base.rglob("*.kt")):
            text = kt.read_text()
            pkg = _package_of(text)
            if pkg is None:
                continue
            prefix = pkg.replace(".", "/")
            rel = kt.relative_to(root)
            lines = text.splitlines()
            # Every file contributes a `…Kt` facade (harmless if it never emits top-level members).
            fqns.add(f"{prefix}/{kt.stem}Kt")
            for i, raw in enumerate(lines):
                s = raw.strip()
                m = _TYPE_DECL.match(s)
                if m:
                    fqns.add(f"{prefix}/{m.group(2)}")
                    if re.search(r"\bdata\s+class\b", s):
                        data_classes.append(f"{rel}: data class {m.group(2)}")
                if s.startswith(PUBLISHED_API_ANNOTATION):
                    nxt = _next_declaration(lines, i)
                    if nxt is not None:
                        name = nxt[1]
                        published_api.append((f"{rel}: {PUBLISHED_API_ANNOTATION} {name}", f"{pkg}.{name}"))
                if s.startswith(EXPERIMENTAL_ANNOTATION):
                    nxt = _next_declaration(lines, i)
                    if nxt is not None:
                        experimental.append(f"{rel}: {nxt[1]}")
    return CuratedSource(fqns, data_classes, sorted(published_api), sorted(experimental))


# --------------------------------------------------------------------------------------------------
# Dump partitioning: curated data classes, per ABI format.
# --------------------------------------------------------------------------------------------------
_JVM_CLASS = re.compile(r"^public\s+(?:final\s+|abstract\s+|open\s+)*(?:class|interface|annotation class|enum class)\s+([^\s{]+)")
_KLIB_CLASS = re.compile(
    r"^\s*(?:final\s+|open\s+|abstract\s+|sealed\s+)*(?:class|interface|enum class|annotation class|object|fun interface)\s+([\w.]+/[\w.]+)"
)


def _partition_jvm(text: str, curated: set[str], label: str) -> list[str]:
    data_classes: list[str] = []
    lines = text.splitlines()
    i, n = 0, len(lines)
    while i < n:
        m = _JVM_CLASS.match(lines[i])
        if m:
            name = m.group(1)
            outer = name.split("$")[0]
            is_curated = outer in curated
            has_c1 = has_copy = False
            i += 1
            while i < n and lines[i].strip() != "}":
                body = lines[i]
                if is_curated:
                    if re.search(r"\bfun\s+component1\s*\(", body):
                        has_c1 = True
                    if re.search(r"\bfun\s+copy\s*\(", body):
                        has_copy = True
                i += 1
            if is_curated and has_c1 and has_copy:
                data_classes.append(f"{label}: {name}")
        i += 1
    return data_classes


def _partition_klib(text: str, curated: set[str], label: str) -> list[str]:
    # klib curated base names are `pkg.dotted/Name` (BCV renders types with dotted packages).
    # klib is used only for the data-class guard.
    kbase = set()
    for c in curated:
        if "/" in c:
            pk, nm = c.rsplit("/", 1)
            kbase.add(f"{pk.replace('/', '.')}/{nm}")
    data_classes: list[str] = []
    lines = text.splitlines()
    i, n = 0, len(lines)
    while i < n:
        line = lines[i]
        m = _KLIB_CLASS.match(line)
        if m and (len(line) - len(line.lstrip(" "))) == 0:
            name = m.group(1)
            base = f"{name.split('/')[0]}/{name.split('/')[1].split('.')[0]}"
            is_curated = base in kbase
            has_c1 = has_copy = False
            depth = line.count("{") - line.count("}")
            j = i + 1
            if "{" in line:
                while j < n and depth > 0:
                    body = lines[j]
                    if is_curated:
                        if re.search(r"\bfun\s+component1\b", body):
                            has_c1 = True
                        if re.search(r"\bfun\s+copy\b", body):
                            has_copy = True
                    depth += body.count("{") - body.count("}")
                    j += 1
            if is_curated and has_c1 and has_copy:
                data_classes.append(f"{label}: {name}")
            i = j
            continue
        i += 1
    return data_classes


def partition_dump(path: pathlib.Path, curated: set[str], fmt: str, label: str) -> list[str]:
    if not path.is_file():
        return []
    text = path.read_text()
    if fmt == "klib":
        return _partition_klib(text, curated, label)
    return _partition_jvm(text, curated, label)


# --------------------------------------------------------------------------------------------------
# The audit.
# --------------------------------------------------------------------------------------------------
def audit(root: pathlib.Path | str, allowlist: set[str] | None = None) -> Report:
    root = pathlib.Path(root)
    allow = PUBLISHED_API_ALLOWLIST if allowlist is None else allowlist
    src = scan_source(root)

    violations: list[str] = []

    # (a) curated data classes — from source (all platforms).
    violations.extend(f"data class (source): {d}" for d in src.data_classes)

    # (a) per-dump curated data classes (component1+copy shape) — a cross-platform guard alongside the source scan.
    for rel, fmt in DUMP_FILES:
        dc = partition_dump(root / rel, src.fqns, fmt, rel)
        violations.extend(f"data class (dump component1+copy): {d}" for d in dc)

    # (c) @PublishedApi symbols not in the allowlist.
    for display, key in src.published_api:
        if key not in allow:
            violations.append(f"@PublishedApi not in allowlist: {display} [{key}]")

    return Report(sorted(violations), src.experimental)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit non-zero on any violation")
    parser.add_argument("--root", default=ROOT)
    args = parser.parse_args(argv)

    report = audit(args.root)

    print(f"@OpenRouterExperimentalApi curated declarations ({len(report.experimental)}):")
    for entry in report.experimental:
        print(f"  {entry}")

    if report.violations:
        print(f"\napi-surface-audit: FAIL — {len(report.violations)} violation(s):", file=sys.stderr)
        for v in report.violations:
            print(f"  {v}", file=sys.stderr)
        return 1 if args.check else 0

    print("\napi-surface-audit: OK — curated public surface is clean (0 violations)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
