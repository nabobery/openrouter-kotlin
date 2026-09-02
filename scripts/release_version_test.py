#!/usr/bin/env python3
"""Tests for scripts/release-version.py — the single-source-of-truth version tool.

The version lives in gradle.properties (`openrouter.version=`) and is mirrored into the
SDK_VERSION constant of OpenRouterVersion.kt. Every rewrite is a targeted `re` substitution
(never a properties/Kotlin serializer) so comments and surrounding bytes survive untouched.
Python 3 stdlib only.
"""
from __future__ import annotations

import importlib.util
import io
import pathlib
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

SCRIPT = pathlib.Path(__file__).with_name("release-version.py")
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location("release_version", SCRIPT)
assert _spec is not None and _spec.loader is not None
rv = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(rv)

PROPS_TEMPLATE = (
    "# leading comment\n"
    "org.gradle.caching=true\n"
    "# Single source of truth for the published version.\n"
    "openrouter.version={version}\n"
    "kotlin.code.style=official\n"
)
KT_TEMPLATE = (
    "package com.nabobery.openrouter\n"
    "\n"
    "/** doc comment stays. */\n"
    'internal const val SDK_VERSION: String = "{version}"\n'
)


class ParseTest(unittest.TestCase):
    def test_accepts_release_rc_and_snapshot(self):
        for good in ("0.1.0", "0.1.0-rc.1", "0.1.0-SNAPSHOT", "1.2.3", "10.20.30-rc.42"):
            with self.subTest(good=good):
                rv.parse(good)  # must not raise

    def test_rejects_bad_grammar_naming_the_grammar(self):
        for bad in ("0.1.0-RC1", "v0.1.0", "0.1", "0.1.0-rc.0", "0.1.0-rc", "01.0.0", ""):
            with self.subTest(bad=bad):
                with self.assertRaises(ValueError) as ctx:
                    rv.parse(bad)
                self.assertIn("MAJOR.MINOR.PATCH", str(ctx.exception))


class FilesTest(unittest.TestCase):
    def setUp(self):
        self.dir = pathlib.Path(tempfile.mkdtemp())
        self.props = self.dir / "gradle.properties"
        self.kt = self.dir / "OpenRouterVersion.kt"
        self.props.write_text(PROPS_TEMPLATE.format(version="0.1.0-SNAPSHOT"))
        self.kt.write_text(KT_TEMPLATE.format(version="0.1.0-SNAPSHOT"))
        self.addCleanup(self._cleanup)

    def _cleanup(self):
        import shutil

        shutil.rmtree(self.dir)

    def test_get_reads_the_property(self):
        self.assertEqual("0.1.0-SNAPSHOT", rv.get_version(self.props))

    def test_get_constant_reads_the_kt_file(self):
        self.assertEqual("0.1.0-SNAPSHOT", rv.get_constant(self.kt))

    def test_set_rewrites_both_and_touches_nothing_else(self):
        before_props = self.props.read_text()
        before_kt = self.kt.read_text()
        rv.set_version("0.1.0-rc.1", self.props, self.kt)
        after_props = self.props.read_text()
        after_kt = self.kt.read_text()
        self.assertEqual("0.1.0-rc.1", rv.get_version(self.props))
        self.assertEqual("0.1.0-rc.1", rv.get_constant(self.kt))
        # Only the single version line changed in each file; every other byte is identical.
        self.assertEqual(
            before_props.replace("0.1.0-SNAPSHOT", "0.1.0-rc.1"), after_props
        )
        self.assertEqual(before_kt.replace("0.1.0-SNAPSHOT", "0.1.0-rc.1"), after_kt)

    def test_set_rejects_bad_version_and_leaves_files_untouched(self):
        before_props = self.props.read_text()
        with self.assertRaises(ValueError):
            rv.set_version("0.1.0-RC1", self.props, self.kt)
        self.assertEqual(before_props, self.props.read_text())

    def test_check_passes_when_all_three_agree(self):
        self.assertEqual([], rv.check_consistency("v0.1.0-SNAPSHOT", self.props, self.kt))

    def test_check_passes_without_tag(self):
        self.assertEqual([], rv.check_consistency(None, self.props, self.kt))

    def test_check_names_the_mismatching_tag(self):
        problems = rv.check_consistency("v0.2.0", self.props, self.kt)
        self.assertTrue(any("tag" in p for p in problems), problems)

    def test_check_names_the_mismatching_constant(self):
        self.kt.write_text(KT_TEMPLATE.format(version="9.9.9"))
        problems = rv.check_consistency("v0.1.0-SNAPSHOT", self.props, self.kt)
        self.assertTrue(any("SDK_VERSION" in p or "constant" in p for p in problems), problems)


class NextSnapshotTest(unittest.TestCase):
    def test_rc_maps_to_same_base_snapshot(self):
        self.assertEqual("0.1.0-SNAPSHOT", rv.next_snapshot("0.1.0-rc.1"))

    def test_release_bumps_patch(self):
        self.assertEqual("0.1.1-SNAPSHOT", rv.next_snapshot("0.1.0"))
        self.assertEqual("1.4.6-SNAPSHOT", rv.next_snapshot("1.4.5"))


class CliTest(unittest.TestCase):
    def setUp(self):
        self.dir = pathlib.Path(tempfile.mkdtemp())
        self.props = self.dir / "gradle.properties"
        self.kt = self.dir / "OpenRouterVersion.kt"
        self.props.write_text(PROPS_TEMPLATE.format(version="0.1.0-SNAPSHOT"))
        self.kt.write_text(KT_TEMPLATE.format(version="0.1.0-SNAPSHOT"))
        self.addCleanup(self._cleanup)

    def _cleanup(self):
        import shutil

        shutil.rmtree(self.dir)

    def _run(self, *args):
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = rv.main([*args, "--properties", str(self.props), "--version-file", str(self.kt)])
        return code, buf.getvalue()

    def test_cli_get_prints_property(self):
        code, out = self._run("get")
        self.assertEqual(0, code)
        self.assertEqual("0.1.0-SNAPSHOT", out.strip())

    def test_cli_check_ok(self):
        code, _ = self._run("check", "--tag", "v0.1.0-SNAPSHOT")
        self.assertEqual(0, code)

    def test_cli_check_mismatch_exits_1(self):
        code, _ = self._run("check", "--tag", "v9.9.9")
        self.assertEqual(1, code)

    def test_cli_next_snapshot_writes_both_files(self):
        self.props.write_text(PROPS_TEMPLATE.format(version="0.1.0"))
        self.kt.write_text(KT_TEMPLATE.format(version="0.1.0"))
        code, out = self._run("next-snapshot")
        self.assertEqual(0, code)
        self.assertEqual("0.1.1-SNAPSHOT", out.strip())
        # The command performs the bump, not merely prints it (the runbook documents this side effect).
        self.assertEqual("0.1.1-SNAPSHOT", rv.get_version(self.props))
        self.assertEqual("0.1.1-SNAPSHOT", rv.get_constant(self.kt))

    def test_cli_next_snapshot_on_snapshot_is_a_noop(self):
        code, out = self._run("next-snapshot")  # setUp seeds 0.1.0-SNAPSHOT
        self.assertEqual(0, code)
        self.assertEqual("0.1.0-SNAPSHOT", out.strip())
        self.assertEqual("0.1.0-SNAPSHOT", rv.get_version(self.props))


if __name__ == "__main__":
    unittest.main()
