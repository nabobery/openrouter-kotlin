#!/usr/bin/env python3
"""Structurally validate an untrusted drift patch before the write-capable `open-pr` job applies it.

This is the ONLY command the drift workflow's write-capable job is allowed to execute (see the audit
policy's `writeJobAllowedCommands`), and it reads only the patch text — it never runs Gradle, never
executes anything from the patch, and never applies it (the job applies it to a *temporary* index after
this passes). A path allowlist alone is not a control: `git apply` can create symlinks (mode 120000),
set the executable bit (100755), or apply binary hunks inside allowlisted directories. So the patch must
be, in full:

  * non-empty;
  * free of rename/copy headers;
  * free of any mode other than 100644 (kills symlinks and executables);
  * free of binary hunks;
  * touching only files matching the strict path allowlist, with no `..` component or leading `/`.

Paths are taken from `git apply --numstat -z` (NUL-delimited, so a quoted/odd path cannot smuggle a
second target past the allowlist). Python 3 stdlib only. Exit 1 on any violation, 2 on usage error.
"""
from __future__ import annotations

import re
import subprocess
import sys

ALLOWLIST = re.compile(
    r"^(spec/(openapi\.yaml|pin\.json|sdkgen\.yaml|generated\.lock\.json)"
    r"|sdk/api/[^/]+(/[^/]+)?\.api"
    r"|docs/coverage/operation-coverage\.md"
    r"|docs/compat/[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9a-f]{8}-to-[0-9a-f]{8}\.md)$"
)

_MODE_HEADERS = ("old mode ", "new mode ", "deleted file mode ", "new file mode ")
_RENAME_HEADERS = ("rename from", "rename to", "copy from", "copy to")


def validate(patch_path: str) -> list[str]:
    with open(patch_path, encoding="utf-8", errors="replace") as f:
        text = f.read()

    if text.strip() == "":
        return ["empty patch"]

    violations: list[str] = []

    # Text scan: mode/rename/binary headers are caught here even if the patch is not numstat-parseable.
    for i, line in enumerate(text.split("\n"), 1):
        if line.startswith(_RENAME_HEADERS):
            violations.append(f"line {i}: rename/copy not allowed: {line}")
        if line.startswith(_MODE_HEADERS):
            mode = line.rsplit(" ", 1)[-1].strip()
            if mode != "100644":
                violations.append(f"line {i}: disallowed file mode {mode} (only regular 100644): {line}")
        if line.startswith("GIT binary patch") or line.startswith("Binary files"):
            violations.append(f"line {i}: binary hunk not allowed: {line}")

    # Authoritative path list via git's own parser (NUL-delimited).
    try:
        proc = subprocess.run(
            ["git", "apply", "--numstat", "-z", patch_path],
            capture_output=True, text=True,
        )
    except FileNotFoundError:
        violations.append("git is not available to extract patch paths")
        return violations

    if proc.returncode != 0:
        violations.append(f"git apply --numstat failed to parse the patch: {proc.stderr.strip()[:200]}")
        return violations

    for record in proc.stdout.split("\0"):
        if record.strip() == "":
            continue
        parts = record.split("\t")
        if len(parts) < 3:
            violations.append(f"unparseable numstat record: {record!r}")
            continue
        added, removed, path = parts[0], parts[1], "\t".join(parts[2:])
        if added == "-" and removed == "-":
            violations.append(f"binary change to {path}")
        if path.startswith("/") or ".." in path.split("/"):
            violations.append(f"unsafe path (leading '/' or '..' component): {path}")
        if not ALLOWLIST.match(path):
            violations.append(f"path outside the drift allowlist: {path}")

    return violations


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 1:
        print("usage: validate-drift-patch.py <patch-file>", file=sys.stderr)
        return 2
    violations = validate(argv[0])
    for v in violations:
        print(f"REJECT: {v}", file=sys.stderr)
    if violations:
        print(f"\nvalidate-drift-patch: {len(violations)} violation(s) — patch refused", file=sys.stderr)
        return 1
    print("OK: patch is non-empty, regular-100644 only, no binary/rename, and within the path allowlist")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
