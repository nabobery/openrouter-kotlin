#!/usr/bin/env python3
"""Tests for scripts/compat-report.py — the layered compatibility classifier.

Seven layers (OpenAPI, semantic, Kotlin source, JVM ABI, klib ABI, wire+behaviour, targets), each classified
explicitly; the overall is the worst layer over patch < minor < breaking < unclassified, and ANYTHING the
rules cannot classify fails the gate. Python 3 stdlib only.
"""
from __future__ import annotations

import importlib.util
import json
import pathlib
import shutil
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("compat-report.py")
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location("compat_report", SCRIPT)
assert _spec is not None and _spec.loader is not None
cr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cr)

MAN = {"declarationModelSha256": "a", "effectiveContractSha256": "b",
       "semanticModelSha256": "c", "kotlinApiSha256": "d"}


class CompatReportTest(unittest.TestCase):
    def setUp(self):
        self.b = pathlib.Path(tempfile.mkdtemp())
        self.a = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.b)
        self.addCleanup(shutil.rmtree, self.a)

    def w(self, d, name, text):
        (d / name).write_text(text)

    def classify(self):
        return cr.classify(str(self.b), str(self.a))

    # ---- individual layers -------------------------------------------------
    def test_semantic_patch_when_manifests_equal(self):
        self.w(self.b, "manifest.json", json.dumps(MAN))
        self.w(self.a, "manifest.json", json.dumps(MAN))
        layer = cr.layer_semantic(str(self.b), str(self.a))
        self.assertEqual("patch", layer.status)

    def test_semantic_unclassified_when_digests_change_without_sdkgen_diff(self):
        self.w(self.b, "manifest.json", json.dumps(MAN))
        self.w(self.a, "manifest.json", json.dumps({**MAN, "kotlinApiSha256": "z"}))
        self.assertEqual("unclassified", cr.layer_semantic(str(self.b), str(self.a)).status)

    def test_semantic_minor_when_sdkgen_diff_non_breaking(self):
        self.w(self.b, "manifest.json", json.dumps(MAN))
        self.w(self.a, "manifest.json", json.dumps({**MAN, "kotlinApiSha256": "z"}))
        self.w(self.a, "sdkgen-diff.json", json.dumps({"apiImpact": "non-breaking"}))
        self.assertEqual("minor", cr.layer_semantic(str(self.b), str(self.a)).status)

    def test_jvm_abi_breaking_on_removal(self):
        self.w(self.b, "sdk.api", "public final class A\npublic final class B\n")
        self.w(self.a, "sdk.api", "public final class A\n")
        self.assertEqual("breaking", cr.layer_jvm_abi(str(self.b), str(self.a)).status)

    def test_jvm_abi_minor_on_addition(self):
        self.w(self.b, "sdk.api", "public final class A\n")
        self.w(self.a, "sdk.api", "public final class A\npublic final class B\n")
        self.assertEqual("minor", cr.layer_jvm_abi(str(self.b), str(self.a)).status)

    def test_klib_missing_one_side_is_unclassified(self):
        self.w(self.b, "sdk.klib.api", "// Targets: [macosArm64]\npublic final class A\n")
        # after has no klib dump
        self.assertEqual("unclassified", cr.layer_klib_abi(str(self.b), str(self.a)).status)

    def test_kotlin_source_breaking_on_file_removal(self):
        self.w(self.b, "sources.txt", "h1 a/X.kt\nh2 a/Y.kt\n")
        self.w(self.a, "sources.txt", "h1 a/X.kt\n")
        self.assertEqual("breaking", cr.layer_kotlin_source(str(self.b), str(self.a)).status)

    def test_targets_removed_is_breaking(self):
        self.w(self.b, "sdk.klib.api", "// Targets: [macosArm64, iosArm64]\npublic class A\n")
        self.w(self.a, "sdk.klib.api", "// Targets: [macosArm64]\npublic class A\n")
        self.assertEqual("breaking", cr.layer_targets(str(self.b), str(self.a)).status)

    def test_openapi_breaking_on_warn_entry(self):
        self.w(self.a, "oasdiff-breaking.json", json.dumps([{"level": "WARN", "id": "x"}]))
        self.assertEqual("breaking", cr.layer_openapi(str(self.b), str(self.a)).status)

    def test_openapi_minor_on_changelog_info_only(self):
        self.w(self.a, "oasdiff-breaking.json", json.dumps([]))
        self.w(self.a, "oasdiff-changelog.json", json.dumps([{"level": "INFO", "id": "y"}]))
        self.assertEqual("minor", cr.layer_openapi(str(self.b), str(self.a)).status)

    def test_wire_behaviour_unavailable_without_tests(self):
        self.assertIn(cr.layer_wire(str(self.b), str(self.a)).status, ("unavailable", "unclassified"))

    # ---- overall + exit codes ----------------------------------------------
    def test_overall_breaking_exits_3(self):
        self.w(self.b, "sdk.api", "public final class A\npublic final class B\n")
        self.w(self.a, "sdk.api", "public final class A\n")
        result = self.classify()
        self.assertEqual("breaking", result.overall)
        self.assertEqual(3, result.exit_code)

    def test_overall_unclassified_exits_1(self):
        self.w(self.b, "sdk.klib.api", "// Targets: [macosArm64]\npublic class A\n")
        result = self.classify()
        self.assertEqual(1, result.exit_code)
        self.assertEqual("unclassified", result.overall)

    def test_all_patch_exits_0(self):
        self.w(self.b, "manifest.json", json.dumps(MAN))
        self.w(self.a, "manifest.json", json.dumps(MAN))
        self.w(self.b, "sdk.api", "public final class A\n")
        self.w(self.a, "sdk.api", "public final class A\n")
        self.w(self.b, "sources.txt", "h1 a/X.kt\n")
        self.w(self.a, "sources.txt", "h1 a/X.kt\n")
        result = self.classify()
        self.assertEqual(0, result.exit_code)
        self.assertIn(result.overall, ("patch", "minor"))

    def test_render_markdown_has_rows_and_classification(self):
        self.w(self.b, "sdk.api", "public final class A\n")
        self.w(self.a, "sdk.api", "public final class A\npublic final class B\n")
        md = cr.render(self.classify())
        self.assertIn("Release classification", md)
        self.assertIn("JVM ABI", md)


if __name__ == "__main__":
    unittest.main()
