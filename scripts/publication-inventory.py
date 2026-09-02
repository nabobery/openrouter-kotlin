#!/usr/bin/env python3
"""Staged-artifact inventory gate for the Maven Central release bundle.

`check` proves a staged repository (from publication/isolated-repository.init.gradle.kts) contains
exactly the coordinates in publication/expected-artifacts.json — every target's main artifact, its
`.pom`, `.module`, `-sources.jar`, `-javadoc.jar`, any Apple `-metadata.jar`, and (with
--require-signatures) a `.asc` for each — with valid POM metadata and no HTTP-engine leakage, and NO
unexpected coordinate (a leftover `sdk-*` directory is a defect).

`write` records `{stripped-name: {sha256, bytes}}` for every artifact and a `{files, bytes}` summary
of what a Central bundle would upload (md5 + sha1 kept; sha256/sha512 and maven-metadata dropped).

Presence is matched by classifier/extension WITHIN each `<coordinate>/<version>/` directory, so the
same gate works for release versions (`0.1.0-rc.1`) and for SNAPSHOTs (whose files carry a resolved
timestamp instead of the literal version). Python 3 stdlib only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

CHECKSUM_SUFFIXES = (".md5", ".sha1", ".sha256", ".sha512")
BUNDLE_DROP_SUFFIXES = (".sha256", ".sha512")
POM_REQUIRED_FIELDS = ("name", "description", "url", "licenses", "developers", "scm")

# Strips the embedded version from an artifact file name for a version-stable inventory key. Handles
# release (`-0.1.0`, `-0.1.0-rc.1`), SNAPSHOT (`-0.1.0-SNAPSHOT`), and the resolved SNAPSHOT timestamp
# form (`-0.1.0-20260902.141215-1`).
_VERSION_IN_NAME = re.compile(r"-\d+\.\d+\.\d+(?:-SNAPSHOT|-rc\.\d+|-\d{8}\.\d{6}-\d+)?")


class InventoryError(RuntimeError):
    """A defect that makes the staged repository unfit to bundle (raised by `write`)."""


def _group_base(repo: pathlib.Path, group: str) -> pathlib.Path:
    return repo.joinpath(*group.split("."))


def _is_checksum(name: str) -> bool:
    return name.endswith(CHECKSUM_SUFFIXES)


def _content_files(vdir: pathlib.Path) -> list[str]:
    """Non-checksum, non-signature, non-maven-metadata file names in a version directory."""
    return [
        f.name
        for f in vdir.iterdir()
        if f.is_file()
        and not _is_checksum(f.name)
        and not f.name.endswith(".asc")
        and not f.name.startswith("maven-metadata.xml")
    ]


def _expected_coordinates(expected: dict) -> dict[str, dict]:
    """Map every expected coordinate directory name to its {main_ext, extra:[...]} shape."""
    coords: dict[str, dict] = {}
    for artifact_name, spec in expected["artifacts"].items():
        coords[artifact_name] = {"main_ext": spec["root"]["extension"], "extra": []}
        for target, ext in spec["targets"].items():
            extra = spec.get("extraFiles", {}).get(target, [])
            coords[f"{artifact_name}-{target}"] = {"main_ext": ext, "extra": list(extra)}
    return coords


def _pom_problems(pom_path: pathlib.Path, coord: str) -> list[str]:
    problems: list[str] = []
    try:
        root = ET.fromstring(pom_path.read_text())
    except ET.ParseError as exc:
        return [f"{coord}: POM {pom_path.name} is not valid XML ({exc})"]

    def local(tag: str) -> str:
        return tag.rsplit("}", 1)[-1]

    top_level = {local(child.tag) for child in root}
    for field in POM_REQUIRED_FIELDS:
        if field not in top_level:
            problems.append(f"{coord}: POM {pom_path.name} is missing required <{field}>")

    for dep in root.iter():
        if local(dep.tag) != "dependency":
            continue
        artifact_id = next((local(c.tag) == "artifactId" and c.text for c in dep if local(c.tag) == "artifactId"), None)
        if artifact_id and artifact_id.startswith("ktor-client-") and not artifact_id.startswith("ktor-client-core"):
            problems.append(f"{coord}: POM {pom_path.name} leaks an HTTP engine dependency '{artifact_id}'")
    return problems


def _check_publication(
    coord_dir: pathlib.Path,
    coord: str,
    shape: dict,
    version: str,
    required_sidecars: list[str],
    require_signatures: bool,
) -> list[str]:
    problems: list[str] = []
    vdir = coord_dir / version
    if not vdir.is_dir():
        return [f"{coord}: missing version directory '{version}'"]

    files = _content_files(vdir)
    main_ext = shape["main_ext"]

    def has(predicate) -> bool:
        return any(predicate(name) for name in files)

    def is_main(name: str) -> bool:
        return (
            name.endswith(f".{main_ext}")
            and "-sources" not in name
            and "-javadoc" not in name
            and not name.endswith("-metadata.jar")
        )

    required_present: list[str] = []

    main_matches = [name for name in files if is_main(name)]
    if not main_matches:
        problems.append(f"{coord}: missing main artifact *.{main_ext}")
    else:
        required_present.extend(main_matches)

    for sidecar in required_sidecars:
        matches = [name for name in files if name.endswith(sidecar)]
        if not matches:
            problems.append(f"{coord}: missing {sidecar}")
        else:
            required_present.extend(matches)

    for extra in shape["extra"]:
        matches = [name for name in files if name.endswith(extra)]
        if not matches:
            problems.append(f"{coord}: missing {extra}")
        else:
            required_present.extend(matches)

    if require_signatures:
        for name in required_present:
            if not (vdir / f"{name}.asc").is_file():
                problems.append(f"{coord}: missing signature {name}.asc")

    for name in files:
        if name.endswith(".pom"):
            problems.extend(_pom_problems(vdir / name, coord))

    return problems


def check(
    expected_path: pathlib.Path,
    repo: pathlib.Path,
    version: str,
    require_signatures: bool = False,
) -> list[str]:
    expected = json.loads(expected_path.read_text())
    base = _group_base(repo, expected["group"])
    if not base.is_dir():
        return [f"no staged group directory at {base}"]

    coords = _expected_coordinates(expected)
    required_sidecars = expected["requiredPerPublication"]
    problems: list[str] = []

    actual_dirs = {d.name for d in base.iterdir() if d.is_dir()}
    for stray in sorted(actual_dirs - set(coords)):
        problems.append(f"unexpected coordinate directory '{stray}' (not in expected-artifacts.json)")

    for coord, shape in coords.items():
        coord_dir = base / coord
        if not coord_dir.is_dir():
            problems.append(f"{coord}: missing coordinate directory")
            continue
        problems.extend(_check_publication(coord_dir, coord, shape, version, required_sidecars, require_signatures))

    return problems


def _sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write(repo: pathlib.Path, out_path: pathlib.Path, version: str) -> dict:
    """Record a version-stripped artifact inventory + a Central-bundle summary; raise on defects."""
    inventory: dict[str, dict] = {}
    bundle_names: list[str] = []
    bundle_bytes = 0

    version_dirs: dict[pathlib.Path, list[pathlib.Path]] = {}
    for path in sorted(repo.rglob("*")):
        if not path.is_file():
            continue
        version_dirs.setdefault(path.parent, []).append(path)

    for vdir, files in version_dirs.items():
        # A coordinate-level directory legitimately holds only the artifact-level maven-metadata.xml (Maven's
        # version listing) alongside the real version subdirectories; the metadata-only defect applies only to a
        # leaf (version) directory that should contain artifacts but does not.
        is_leaf = not any(child.is_dir() for child in vdir.iterdir())
        non_checksum = [f for f in files if not _is_checksum(f.name)]
        if is_leaf and non_checksum and all(f.name.startswith("maven-metadata.xml") for f in non_checksum):
            raise InventoryError(f"{vdir} contains only maven-metadata (no published artifact)")

        for path in files:
            name = path.name
            size = path.stat().st_size
            if not _is_checksum(name) and not name.startswith("maven-metadata.xml") and size == 0:
                raise InventoryError(f"zero-byte artifact: {path}")

            # Inventory: content files only, keyed by a version-stripped name.
            if not _is_checksum(name) and not name.startswith("maven-metadata.xml"):
                key = _VERSION_IN_NAME.sub("", name)
                inventory[key] = {"sha256": _sha256(path), "bytes": size}

            # Bundle: everything except sha256/sha512 and maven-metadata* (md5 + sha1 are kept).
            if name.endswith(BUNDLE_DROP_SUFFIXES) or name.startswith("maven-metadata.xml"):
                continue
            bundle_names.append(name)
            bundle_bytes += size

    summary = {"files": len(bundle_names), "bytes": bundle_bytes, "file_names": sorted(bundle_names)}
    document = {"version": version, "artifacts": inventory, "bundle": {"files": summary["files"], "bytes": summary["bytes"]}}
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n")
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Staged-artifact inventory gate.")
    sub = parser.add_subparsers(dest="command", required=True)

    check_parser = sub.add_parser("check")
    check_parser.add_argument("expected", type=pathlib.Path)
    check_parser.add_argument("repo", type=pathlib.Path)
    check_parser.add_argument("--version", required=True)
    check_parser.add_argument("--require-signatures", action="store_true")

    write_parser = sub.add_parser("write")
    write_parser.add_argument("repo", type=pathlib.Path)
    write_parser.add_argument("out", type=pathlib.Path)
    write_parser.add_argument("--version", required=True)

    args = parser.parse_args(argv)

    if args.command == "check":
        problems = check(args.expected, args.repo, args.version, args.require_signatures)
        if problems:
            for problem in problems:
                print(problem, file=sys.stderr)
            return 1
        print(f"publication-inventory: OK ({args.version})")
        return 0
    if args.command == "write":
        try:
            summary = write(args.repo, args.out, args.version)
        except InventoryError as exc:
            print(str(exc), file=sys.stderr)
            return 2
        print(f"publication-inventory: {summary['files']} bundle files, {summary['bytes']} bytes -> {args.out}")
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
