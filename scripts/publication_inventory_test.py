#!/usr/bin/env python3
"""Tests for scripts/publication-inventory.py — the staged-artifact inventory gate.

Builds synthetic Maven repository trees in tempdirs and asserts the gate catches every defect a
release must not ship: a missing coordinate, an unexpected coordinate (an `sdk-*` leftover), a
missing sidecar (pom/module/sources/javadoc), a missing `.asc` under --require-signatures, a POM
missing a required field, engine leakage in the dependency list, a metadata-only directory, and a
zero-byte artifact. Python 3 stdlib only.
"""
from __future__ import annotations

import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("publication-inventory.py")
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location("publication_inventory", SCRIPT)
assert _spec is not None and _spec.loader is not None
pi = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pi)

GROUP = "io.github.nabobery"
VERSION = "9.9.9"

# A deliberately small expected spec: the root, a jvm jar, an android aar, and one apple klib whose
# publication also carries a -metadata.jar. Enough to exercise every code path without a 12-target tree.
EXPECTED = {
    "group": GROUP,
    "artifacts": {
        "openrouter-kotlin": {
            "root": {"extension": "jar"},
            "targets": {"jvm": "jar", "android": "aar", "iosarm64": "klib"},
            "extraFiles": {"iosarm64": ["-metadata.jar"]},
        }
    },
    "requiredPerPublication": [".pom", ".module", "-sources.jar", "-javadoc.jar"],
}

POM_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.github.nabobery</groupId>
  <artifactId>{artifact}</artifactId>
  <version>{version}</version>
  <name>openrouter-kotlin</name>
  <description>test</description>
  <url>https://github.com/nabobery/openrouter-kotlin</url>
  <licenses><license><name>Apache-2.0</name></license></licenses>
  <developers><developer><id>nabobery</id></developer></developers>
  <scm><url>https://github.com/nabobery/openrouter-kotlin</url></scm>
  <dependencies>
{deps}
  </dependencies>
</project>
"""

GOOD_DEP = """    <dependency><groupId>io.ktor</groupId><artifactId>ktor-client-core-jvm</artifactId><scope>compile</scope></dependency>"""
ENGINE_DEP = """    <dependency><groupId>io.ktor</groupId><artifactId>ktor-client-cio-jvm</artifactId><scope>runtime</scope></dependency>"""


def _write(path: pathlib.Path, content: bytes = b"x") -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)


def build_tree(root: pathlib.Path, *, version: str = VERSION, deps: str = GOOD_DEP, signatures: bool = False) -> None:
    """Materialize a complete, valid staged repository for the EXPECTED spec."""
    base = root / "io" / "github" / "nabobery"
    coords = {
        "openrouter-kotlin": ("jar", []),
        "openrouter-kotlin-jvm": ("jar", []),
        "openrouter-kotlin-android": ("aar", []),
        "openrouter-kotlin-iosarm64": ("klib", ["-metadata.jar"]),
    }
    for coord, (ext, extra) in coords.items():
        vdir = base / coord / version
        stem = f"{coord}-{version}"
        required = [f"{stem}.{ext}", f"{stem}.pom", f"{stem}.module", f"{stem}-sources.jar", f"{stem}-javadoc.jar"]
        required += [f"{stem}{suffix}" for suffix in extra]
        for name in required:
            if name.endswith(".pom"):
                _write(vdir / name, POM_TEMPLATE.format(artifact=coord, version=version, deps=deps).encode())
            else:
                _write(vdir / name, b"content-bytes")
            # Central needs md5 + sha1 (bundle keeps them); sha256/sha512 exist locally but are dropped from the bundle.
            for algo in ("md5", "sha1", "sha256", "sha512"):
                _write(vdir / f"{name}.{algo}", b"deadbeef")
            if signatures:
                _write(vdir / f"{name}.asc", b"-----BEGIN PGP SIGNATURE-----")
        # maven-metadata is always present and always excluded from the bundle.
        _write(vdir / "maven-metadata.xml", b"<metadata/>")
        for algo in ("md5", "sha1", "sha256", "sha512"):
            _write(vdir / f"maven-metadata.xml.{algo}", b"deadbeef")


class InventoryCheckTest(unittest.TestCase):
    def setUp(self):
        self.dir = pathlib.Path(tempfile.mkdtemp())
        self.expected = self.dir / "expected.json"
        self.expected.write_text(json.dumps(EXPECTED))
        self.repo = self.dir / "repo"
        self.addCleanup(self._cleanup)

    def _cleanup(self):
        import shutil

        shutil.rmtree(self.dir)

    def _check(self, **kw):
        return pi.check(self.expected, self.repo, VERSION, **kw)

    def test_complete_tree_passes(self):
        build_tree(self.repo)
        self.assertEqual([], self._check())

    def test_missing_sidecar_is_named(self):
        build_tree(self.repo)
        # Remove the jvm .module and its checksums.
        vdir = self.repo / "io/github/nabobery/openrouter-kotlin-jvm" / VERSION
        for f in list(vdir.glob(f"openrouter-kotlin-jvm-{VERSION}.module*")):
            f.unlink()
        problems = self._check()
        self.assertTrue(any(".module" in p and "openrouter-kotlin-jvm" in p for p in problems), problems)

    def test_missing_main_artifact_is_named(self):
        build_tree(self.repo)
        vdir = self.repo / "io/github/nabobery/openrouter-kotlin-android" / VERSION
        for f in list(vdir.glob(f"openrouter-kotlin-android-{VERSION}.aar*")):
            f.unlink()
        problems = self._check()
        self.assertTrue(any("aar" in p and "openrouter-kotlin-android" in p for p in problems), problems)

    def test_missing_apple_metadata_jar_is_named(self):
        build_tree(self.repo)
        vdir = self.repo / "io/github/nabobery/openrouter-kotlin-iosarm64" / VERSION
        for f in list(vdir.glob(f"openrouter-kotlin-iosarm64-{VERSION}-metadata.jar*")):
            f.unlink()
        problems = self._check()
        self.assertTrue(any("-metadata.jar" in p for p in problems), problems)

    def test_unexpected_coordinate_is_a_defect(self):
        build_tree(self.repo)
        # A leftover pre-rename coordinate.
        stray = self.repo / "io/github/nabobery/sdk-jvm" / VERSION
        _write(stray / f"sdk-jvm-{VERSION}.jar", b"leftover")
        problems = self._check()
        self.assertTrue(any("sdk-jvm" in p and "unexpected" in p for p in problems), problems)

    def test_require_signatures_fails_without_asc(self):
        build_tree(self.repo, signatures=False)
        problems = self._check(require_signatures=True)
        self.assertTrue(any(".asc" in p for p in problems), problems)

    def test_require_signatures_passes_with_asc(self):
        build_tree(self.repo, signatures=True)
        self.assertEqual([], self._check(require_signatures=True))

    def test_pom_missing_field_is_named(self):
        build_tree(self.repo)
        pom = self.repo / "io/github/nabobery/openrouter-kotlin-jvm" / VERSION / f"openrouter-kotlin-jvm-{VERSION}.pom"
        pom.write_text(pom.read_text().replace("<description>test</description>", ""))
        problems = self._check()
        self.assertTrue(any("description" in p for p in problems), problems)

    def test_engine_leakage_is_named(self):
        build_tree(self.repo, deps=GOOD_DEP + "\n" + ENGINE_DEP)
        problems = self._check()
        self.assertTrue(any("ktor-client-cio" in p for p in problems), problems)


class InventoryWriteTest(unittest.TestCase):
    def setUp(self):
        self.dir = pathlib.Path(tempfile.mkdtemp())
        self.repo = self.dir / "repo"
        self.out = self.dir / "inv.json"
        self.addCleanup(self._cleanup)

    def _cleanup(self):
        import shutil

        shutil.rmtree(self.dir)

    def test_write_emits_stripped_inventory_and_bundle_summary(self):
        build_tree(self.repo)
        summary = pi.write(self.repo, self.out, VERSION)
        data = json.loads(self.out.read_text())
        # Version-stripped keys (e.g. the jvm jar) are present.
        self.assertIn("openrouter-kotlin-jvm.jar", data["artifacts"])
        entry = data["artifacts"]["openrouter-kotlin-jvm.jar"]
        self.assertIn("sha256", entry)
        self.assertIn("bytes", entry)
        # Bundle summary excludes sha256/sha512 and maven-metadata but keeps md5/sha1.
        self.assertEqual(summary["files"], data["bundle"]["files"])
        self.assertGreater(summary["files"], 0)
        self.assertGreater(summary["bytes"], 0)

    def test_write_bundle_excludes_checksums_and_metadata(self):
        build_tree(self.repo)
        summary = pi.write(self.repo, self.out, VERSION)
        # No bundle file may be a dropped checksum or maven-metadata.
        for name in summary["file_names"]:
            self.assertFalse(name.endswith(".sha256"), name)
            self.assertFalse(name.endswith(".sha512"), name)
            self.assertFalse(name.startswith("maven-metadata.xml"), name)

    def test_write_fails_on_zero_byte_file(self):
        build_tree(self.repo)
        target = self.repo / "io/github/nabobery/openrouter-kotlin-jvm" / VERSION / f"openrouter-kotlin-jvm-{VERSION}.jar"
        target.write_bytes(b"")
        with self.assertRaises(pi.InventoryError):
            pi.write(self.repo, self.out, VERSION)

    def test_write_fails_on_metadata_only_directory(self):
        base = self.repo / "io/github/nabobery/openrouter-kotlin-ghost" / VERSION
        _write(base / "maven-metadata.xml", b"<metadata/>")
        with self.assertRaises(pi.InventoryError):
            pi.write(self.repo, self.out, VERSION)


if __name__ == "__main__":
    unittest.main()
