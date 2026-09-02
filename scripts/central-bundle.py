#!/usr/bin/env python3
"""Build a deterministic Maven Central upload bundle (a zip in repository layout) from a staged repository.

Central needs md5 + sha1 for every file and an `.asc` signature for every file, but NOT sha256/sha512, NOT
`maven-metadata.xml*`, and NOT checksums OF the `.asc` files. Those are dropped here. Only files under the requested
`<version>` directory are included. Entries are sorted and stamped with a fixed timestamp so two runs of the same
staged repository produce a byte-identical zip (a reproducibility property the release rehearsal asserts). A repo
containing any `-SNAPSHOT` version directory is refused unless `--allow-snapshot`. Python 3 stdlib only.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys
import zipfile

# sha256/sha512 and any maven-metadata are never uploaded; a `.asc` carries no checksum sidecars.
_DROP_EXACT_SUFFIXES = (".sha256", ".sha512")
_DROP_ASC_CHECKSUM = re.compile(r"\.asc\.(md5|sha1|sha256|sha512)$")
_FIXED_DATE_TIME = (1980, 1, 1, 0, 0, 0)


def _is_dropped(name: str) -> bool:
    if name.startswith("maven-metadata.xml"):
        return True
    if name.endswith(_DROP_EXACT_SUFFIXES):
        return True
    return bool(_DROP_ASC_CHECKSUM.search(name))


def collect(repo: pathlib.Path, version: str, allow_snapshot: bool) -> list[pathlib.Path]:
    """Return the sorted list of files to upload for `version`, refusing SNAPSHOTs unless allowed."""
    files: list[pathlib.Path] = []
    for path in repo.rglob("*"):
        if not path.is_file():
            continue
        # Artifact files live at <group>/<artifact>/<version>/<file>; select by the version directory name.
        if path.parent.name != version:
            continue
        if _is_dropped(path.name):
            continue
        files.append(path)
    if not allow_snapshot and version.endswith("-SNAPSHOT"):
        raise ValueError(f"refusing to bundle a SNAPSHOT version '{version}' (pass --allow-snapshot to override)")
    return sorted(files)


def build(repo: pathlib.Path, out: pathlib.Path, version: str, allow_snapshot: bool = False) -> dict:
    files = collect(repo, version, allow_snapshot)
    total = 0
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for path in files:
            arcname = str(path.relative_to(repo))
            data = path.read_bytes()
            info = zipfile.ZipInfo(arcname, date_time=_FIXED_DATE_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 0  # no host-OS leakage
            info.external_attr = 0
            zf.writestr(info, data)
            total += len(data)
    return {"files": len(files), "bytes": total}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build a deterministic Maven Central bundle zip.")
    parser.add_argument("repo", type=pathlib.Path)
    parser.add_argument("out", type=pathlib.Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--allow-snapshot", action="store_true")
    args = parser.parse_args(argv)

    try:
        summary = build(args.repo, args.out, args.version, args.allow_snapshot)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    print(f"central-bundle: {summary['files']} files, {summary['bytes']} bytes -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
