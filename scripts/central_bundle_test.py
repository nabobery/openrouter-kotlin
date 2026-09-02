#!/usr/bin/env python3
"""Tests for scripts/central-bundle.py. Python 3 stdlib only."""
from __future__ import annotations

import hashlib
import importlib.util
import pathlib
import shutil
import sys
import tempfile
import unittest
import zipfile

SCRIPT = pathlib.Path(__file__).with_name("central-bundle.py")
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location("central_bundle", SCRIPT)
assert _spec is not None and _spec.loader is not None
cb = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cb)


def _write(path: pathlib.Path, content: bytes = b"x") -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)


def build_repo(root: pathlib.Path, version: str) -> None:
    vdir = root / "io" / "github" / "nabobery" / "openrouter-kotlin-jvm" / version
    stem = f"openrouter-kotlin-jvm-{version}"
    for name in (f"{stem}.jar", f"{stem}.pom", f"{stem}.module", f"{stem}-sources.jar", f"{stem}-javadoc.jar"):
        _write(vdir / name, name.encode())
        _write(vdir / f"{name}.md5", b"m")
        _write(vdir / f"{name}.sha1", b"s")
        _write(vdir / f"{name}.sha256", b"256")
        _write(vdir / f"{name}.sha512", b"512")
        _write(vdir / f"{name}.asc", b"sig")
        # Checksums OF the .asc must be dropped.
        _write(vdir / f"{name}.asc.md5", b"m")
    _write(vdir / "maven-metadata.xml", b"<metadata/>")
    _write(vdir / "maven-metadata.xml.sha1", b"s")


class BundleTest(unittest.TestCase):
    def setUp(self):
        self.dir = pathlib.Path(tempfile.mkdtemp())
        self.repo = self.dir / "repo"
        self.out = self.dir / "bundle.zip"
        self.addCleanup(lambda: shutil.rmtree(self.dir))

    def _names(self, zip_path: pathlib.Path) -> list[str]:
        with zipfile.ZipFile(zip_path) as zf:
            return sorted(zf.namelist())

    def test_bundle_excludes_sha256_sha512_metadata_and_asc_checksums(self):
        build_repo(self.repo, "0.1.0-rc.1")
        cb.build(self.repo, self.out, "0.1.0-rc.1")
        names = self._names(self.out)
        for n in names:
            self.assertFalse(n.endswith(".sha256"), n)
            self.assertFalse(n.endswith(".sha512"), n)
            self.assertFalse(pathlib.Path(n).name.startswith("maven-metadata.xml"), n)
            self.assertFalse(n.endswith(".asc.md5"), n)
        # md5 + sha1 of real artifacts, and the .asc itself, are kept.
        self.assertTrue(any(n.endswith(".jar.md5") for n in names))
        self.assertTrue(any(n.endswith(".jar.sha1") for n in names))
        self.assertTrue(any(n.endswith(".jar.asc") for n in names))

    def test_bundle_is_byte_deterministic(self):
        build_repo(self.repo, "0.1.0-rc.1")
        out1 = self.dir / "a.zip"
        out2 = self.dir / "b.zip"
        cb.build(self.repo, out1, "0.1.0-rc.1")
        cb.build(self.repo, out2, "0.1.0-rc.1")
        self.assertEqual(
            hashlib.sha256(out1.read_bytes()).hexdigest(),
            hashlib.sha256(out2.read_bytes()).hexdigest(),
        )

    def test_refuses_snapshot_without_flag(self):
        build_repo(self.repo, "0.1.0-SNAPSHOT")
        with self.assertRaises(ValueError):
            cb.build(self.repo, self.out, "0.1.0-SNAPSHOT")

    def test_allows_snapshot_with_flag(self):
        build_repo(self.repo, "0.1.0-SNAPSHOT")
        summary = cb.build(self.repo, self.out, "0.1.0-SNAPSHOT", allow_snapshot=True)
        self.assertGreater(summary["files"], 0)

    def test_only_requested_version_included(self):
        build_repo(self.repo, "0.1.0-rc.1")
        build_repo(self.repo, "0.0.9")
        cb.build(self.repo, self.out, "0.1.0-rc.1")
        names = self._names(self.out)
        self.assertTrue(all("0.1.0-rc.1" in n for n in names), names)
        self.assertFalse(any("0.0.9" in n for n in names), names)

    def test_paths_use_repository_layout(self):
        build_repo(self.repo, "0.1.0-rc.1")
        cb.build(self.repo, self.out, "0.1.0-rc.1")
        names = self._names(self.out)
        self.assertTrue(all(n.startswith("io/github/nabobery/") for n in names), names)


if __name__ == "__main__":
    unittest.main()
