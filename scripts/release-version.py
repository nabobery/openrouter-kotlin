#!/usr/bin/env python3
"""Single source of truth for the published version.

The version lives in `gradle.properties` (`openrouter.version=`) and is mirrored into the
`SDK_VERSION` constant in `OpenRouterVersion.kt`; `checkSdkVersionConstant` fails the build if
the two ever drift apart. This tool is the ONLY supported way to move the version: `set`
rewrites both files with a targeted `re` substitution so comments and every other byte survive.

Grammar (ADR 0006 RC grammar): MAJOR.MINOR.PATCH[-rc.N|-SNAPSHOT]
  0.1.0            release
  0.1.0-rc.1       release candidate (rc.N, N >= 1)
  0.1.0-SNAPSHOT   development snapshot

Subcommands:
  get                       print openrouter.version
  set <version>             rewrite gradle.properties + SDK_VERSION (validates the grammar first)
  check [--tag vX]          exit 0 iff property, SDK_VERSION, and (if given) the tag agree
  next-snapshot             rewrite both files to the next development -SNAPSHOT, then print it

Python 3 stdlib only.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
GRADLE_PROPERTIES = ROOT / "gradle.properties"
VERSION_FILE = ROOT / "sdk" / "src" / "commonMain" / "kotlin" / "com" / "nabobery" / "openrouter" / "OpenRouterVersion.kt"

GRAMMAR = "MAJOR.MINOR.PATCH[-rc.N|-SNAPSHOT]"
_VERSION_RE = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-rc\.[1-9]\d*|-SNAPSHOT)?$")

_PROPERTY_RE = re.compile(r"^(openrouter\.version=)(.*)$", re.MULTILINE)
_CONSTANT_RE = re.compile(r'(SDK_VERSION:\s*String\s*=\s*")([^"]+)(")')


def parse(version: str) -> re.Match:
    """Validate a version string against the RC grammar; raise ValueError naming the grammar."""
    match = _VERSION_RE.match(version)
    if not match:
        raise ValueError(f"invalid version {version!r}; expected grammar {GRAMMAR}")
    return match


def get_version(props_path: pathlib.Path = GRADLE_PROPERTIES) -> str:
    match = _PROPERTY_RE.search(props_path.read_text())
    if not match:
        raise ValueError(f"no `openrouter.version=` line in {props_path}")
    return match.group(2).strip()


def get_constant(version_file: pathlib.Path = VERSION_FILE) -> str:
    match = _CONSTANT_RE.search(version_file.read_text())
    if not match:
        raise ValueError(f"no SDK_VERSION constant in {version_file}")
    return match.group(2)


def set_version(
    version: str,
    props_path: pathlib.Path = GRADLE_PROPERTIES,
    version_file: pathlib.Path = VERSION_FILE,
) -> None:
    parse(version)  # validate before touching any file

    props_text = props_path.read_text()
    new_props, props_count = _PROPERTY_RE.subn(rf"\g<1>{version}", props_text)
    if props_count != 1:
        raise ValueError(f"expected exactly one `openrouter.version=` line in {props_path}, found {props_count}")

    kt_text = version_file.read_text()
    new_kt, kt_count = _CONSTANT_RE.subn(rf"\g<1>{version}\g<3>", kt_text)
    if kt_count != 1:
        raise ValueError(f"expected exactly one SDK_VERSION constant in {version_file}, found {kt_count}")

    props_path.write_text(new_props)
    version_file.write_text(new_kt)


def check_consistency(
    tag: str | None,
    props_path: pathlib.Path = GRADLE_PROPERTIES,
    version_file: pathlib.Path = VERSION_FILE,
) -> list[str]:
    """Return a list of human-readable mismatches; empty means property/constant/tag all agree."""
    problems: list[str] = []
    version = get_version(props_path)
    constant = get_constant(version_file)
    if constant != version:
        problems.append(
            f"SDK_VERSION constant ('{constant}') != gradle.properties openrouter.version ('{version}')"
        )
    if tag is not None:
        if not tag.startswith("v"):
            problems.append(f"tag '{tag}' must start with 'v' (expected 'v{version}')")
        elif tag[1:] != version:
            problems.append(f"tag '{tag}' != 'v{version}' (from gradle.properties openrouter.version)")
    return problems


def next_snapshot(version: str) -> str:
    match = parse(version)
    major, minor, patch = int(match.group(1)), int(match.group(2)), int(match.group(3))
    qualifier = match.group(4)
    if qualifier == "-SNAPSHOT":
        return version
    if qualifier and qualifier.startswith("-rc."):
        # A release candidate develops toward the same base release.
        return f"{major}.{minor}.{patch}-SNAPSHOT"
    # A finished release: the next development line is the next patch.
    return f"{major}.{minor}.{patch + 1}-SNAPSHOT"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Single-source-of-truth version tool.")
    # Shared file-location options; accepted after the subcommand so callers (and tests) can point
    # the tool at a temp copy of the two files.
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--properties", type=pathlib.Path, default=GRADLE_PROPERTIES)
    common.add_argument("--version-file", type=pathlib.Path, default=VERSION_FILE)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("get", parents=[common])
    set_parser = sub.add_parser("set", parents=[common])
    set_parser.add_argument("version")
    check_parser = sub.add_parser("check", parents=[common])
    check_parser.add_argument("--tag", default=None)
    sub.add_parser("next-snapshot", parents=[common])

    args = parser.parse_args(argv)

    if args.command == "get":
        print(get_version(args.properties))
        return 0
    if args.command == "set":
        try:
            set_version(args.version, args.properties, args.version_file)
        except ValueError as exc:
            print(str(exc), file=sys.stderr)
            return 2
        print(f"set version to {args.version}")
        return 0
    if args.command == "check":
        problems = check_consistency(args.tag, args.properties, args.version_file)
        if problems:
            for problem in problems:
                print(problem, file=sys.stderr)
            return 1
        return 0
    if args.command == "next-snapshot":
        current = get_version(args.properties)
        new_version = next_snapshot(current)
        # Rewrite both files in lockstep (the runbook's post-publish step relies on this side effect); a no-op when
        # the version is already a -SNAPSHOT.
        if new_version != current:
            set_version(new_version, args.properties, args.version_file)
        print(new_version)
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
