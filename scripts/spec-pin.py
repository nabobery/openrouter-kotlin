#!/usr/bin/env python3
"""Rewrite the spec pin / generation lock in place from upstream fetch results.

Used by scripts/drift-refresh.sh (and by a human re-pin). Every rewrite is a *targeted* `re`
substitution on the file text — never a JSON/YAML serializer — so comments, key order, and the
compact single-line overlay objects in spec/pin.json survive untouched, and only the intended
fields move. Python 3 stdlib only.

Subcommands:
  read-source                                   print `sha256=<current source digest>` for shell use
  update-source --sha --size --retrieved-at --provenance
                                                rewrite pin.json (sha256/sizeBytes/retrievedAt/provenance)
                                                and the source.sha256 line of sdkgen.yaml
  update-lock --address --files --kotlin-files  rewrite generated.lock.json (snapshot/file counts), keep _comment

Exit code 2 signals a usage/validation error (e.g. an sha that is not 64 hex).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_SPEC_DIR = os.path.join(ROOT, "spec")

_HEX64 = re.compile(r"^[0-9a-fA-F]{64}$")


def _validate_sha(value: str) -> str:
    if not _HEX64.match(value):
        print(f"error: expected a 64-character hex sha256, got {value!r} ({len(value)} chars)", file=sys.stderr)
        raise SystemExit(2)
    return value.lower()


def _pin_path(spec_dir: str) -> str:
    return os.path.join(spec_dir, "pin.json")


def _sdkgen_path(spec_dir: str) -> str:
    return os.path.join(spec_dir, "sdkgen.yaml")


def _lock_path(spec_dir: str) -> str:
    return os.path.join(spec_dir, "generated.lock.json")


def read_source(spec_dir: str = DEFAULT_SPEC_DIR) -> str:
    with open(_pin_path(spec_dir), encoding="utf-8") as f:
        return json.load(f)["sha256"]


def _sub_once(pattern: str, repl, text: str, field: str) -> str:
    new, count = re.subn(pattern, repl, text, count=1, flags=re.MULTILINE)
    if count != 1:
        print(f"error: could not locate the {field} field to rewrite", file=sys.stderr)
        raise SystemExit(2)
    return new


def update_source(spec_dir: str, sha: str, size: int, retrieved_at: str, provenance: str) -> None:
    sha = _validate_sha(sha)

    with open(_pin_path(spec_dir), encoding="utf-8") as f:
        pin = f.read()
    # Anchor each field to a top-level (2-space) line so the deeper overlay `sha256` entries are never touched.
    pin = _sub_once(r'^(  "sha256": ")[0-9a-fA-F]{64}(",?)$', lambda m: f"{m.group(1)}{sha}{m.group(2)}", pin, "sha256")
    pin = _sub_once(r'^(  "sizeBytes": )\d+(,?)$', lambda m: f"{m.group(1)}{int(size)}{m.group(2)}", pin, "sizeBytes")
    pin = _sub_once(r'^(  "retrievedAt": ")[^"]*(",?)$',
                    lambda m: f"{m.group(1)}{retrieved_at}{m.group(2)}", pin, "retrievedAt")
    # provenance is free text that may contain quotes/unicode — JSON-encode it (ensure_ascii=False keeps `…`).
    encoded = json.dumps(provenance, ensure_ascii=False)
    pin = _sub_once(r'^(  "provenance": ).*$', lambda m: f"{m.group(1)}{encoded},", pin, "provenance")
    with open(_pin_path(spec_dir), "w", encoding="utf-8") as f:
        f.write(pin)

    with open(_sdkgen_path(spec_dir), encoding="utf-8") as f:
        yaml = f.read()
    # Only the 2-space-indented source digest; overlay digests are 4-space-indented and left alone.
    yaml = _sub_once(r"^(  sha256: )[0-9a-fA-F]{64}$", lambda m: f"{m.group(1)}{sha}", yaml, "source.sha256")
    with open(_sdkgen_path(spec_dir), "w", encoding="utf-8") as f:
        f.write(yaml)


def update_lock(spec_dir: str, address: str, files: int, kotlin_files: int) -> None:
    address = _validate_sha(address)
    with open(_lock_path(spec_dir), encoding="utf-8") as f:
        lock = f.read()
    lock = _sub_once(r'^(  "snapshotContentAddress": ")[0-9a-fA-F]{64}(",?)$',
                     lambda m: f"{m.group(1)}{address}{m.group(2)}", lock, "snapshotContentAddress")
    lock = _sub_once(r'^(  "fileCount": )\d+(,?)$', lambda m: f"{m.group(1)}{int(files)}{m.group(2)}", lock, "fileCount")
    lock = _sub_once(r'^(  "kotlinFileCount": )\d+(,?)$',
                     lambda m: f"{m.group(1)}{int(kotlin_files)}{m.group(2)}", lock, "kotlinFileCount")
    with open(_lock_path(spec_dir), "w", encoding="utf-8") as f:
        f.write(lock)


def main() -> int:
    ap = argparse.ArgumentParser(description="Rewrite the spec pin / generation lock in place.")
    ap.add_argument("--spec-dir", default=DEFAULT_SPEC_DIR)
    sub = ap.add_subparsers(dest="command", required=True)

    sub.add_parser("read-source")

    us = sub.add_parser("update-source")
    us.add_argument("--sha", required=True)
    us.add_argument("--size", required=True, type=int)
    us.add_argument("--retrieved-at", required=True)
    us.add_argument("--provenance", required=True)

    ul = sub.add_parser("update-lock")
    ul.add_argument("--address", required=True)
    ul.add_argument("--files", required=True, type=int)
    ul.add_argument("--kotlin-files", required=True, type=int)

    args = ap.parse_args()
    if args.command == "read-source":
        print(f"sha256={read_source(args.spec_dir)}")
    elif args.command == "update-source":
        update_source(args.spec_dir, args.sha, args.size, args.retrieved_at, args.provenance)
        print(f"updated source pin -> {args.sha}")
    elif args.command == "update-lock":
        update_lock(args.spec_dir, args.address, args.files, args.kotlin_files)
        print(f"updated generation lock -> {args.address} ({args.files} files / {args.kotlin_files} kt)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
