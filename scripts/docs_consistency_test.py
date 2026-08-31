#!/usr/bin/env python3
"""Tests for scripts/docs-consistency.py — the docs-vs-code consistency gate. Stdlib only."""
from __future__ import annotations

import importlib.util
import pathlib
import shutil
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("docs-consistency.py")
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location("docs_consistency", SCRIPT)
assert _spec is not None and _spec.loader is not None
dc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(dc)

BUILD_ALL_TARGETS = """
kotlin {
    jvm { }
    js { nodejs() }
    jvmToolchain(25)
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()
    jvmTest.get().dependsOn(engineTest)
}
if (androidTargetEnabled) { apply(plugin = libs.plugins.android.kotlin.multiplatform.library.get().pluginId) }
"""

DOC_ALL_TARGETS = " ".join(f"`{t}`" for t in dc.TARGET_VOCABULARY)


def _tier_table(tiers: dict[str, int], prose: str = "") -> str:
    """A per-target tier table (header names a target + tier column) plus optional trailing prose."""
    lines = ["## Targets", "", "| Target | Tier | Evidence |", "| --- | --- | --- |"]
    lines += [f"| `{t}` | {tier} | evidence |" for t, tier in tiers.items()]
    if prose:
        lines += ["", prose]
    return "\n".join(lines) + "\n"


ALL_TIER1 = {t: 1 for t in dc.TARGET_VOCABULARY}
ALL_TABLE = _tier_table(ALL_TIER1)


class DocsConsistencyTest(unittest.TestCase):
    def setUp(self):
        self.root = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)

    def _write(self, rel: str, text: str):
        p = self.root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)

    # --- rule 1: targets ---
    def test_targets_in_code_detects_block_and_call_forms(self):
        found = dc._targets_in_code(BUILD_ALL_TARGETS)
        self.assertEqual(dc.TARGET_VOCABULARY, found, f"missing {dc.TARGET_VOCABULARY - found}")

    def test_targets_in_doc_only_matches_exact_backtick_tokens(self):
        text = "`jvm` `iosArm64` and prose about `macosArm64Test` (a lane, not a target)"
        self.assertEqual({"jvm", "iosArm64", "macosArm64"}, dc._targets_in_doc(text) | {"macosArm64"})
        # `macosArm64Test` is NOT a vocabulary token, so it is not picked up.
        self.assertNotIn("macosArm64Test", dc._targets_in_doc(text))
        self.assertEqual({"jvm", "iosArm64"}, dc._targets_in_doc(text))

    def test_target_rules_pass_when_docs_match_code(self):
        self._write("sdk/build.gradle.kts", BUILD_ALL_TARGETS)
        for doc in ("docs/target-support.md", dc._ADR_TARGETS, "README.md"):
            self._write(doc, ALL_TABLE)
        self.assertEqual([], dc.target_rules(self.root))

    def test_target_rules_fail_when_a_doc_drops_a_target(self):
        self._write("sdk/build.gradle.kts", BUILD_ALL_TARGETS)
        self._write("docs/target-support.md", ALL_TABLE)
        self._write(dc._ADR_TARGETS, ALL_TABLE)
        # mingwX64 removed from README's table row.
        self._write("README.md", _tier_table({t: 1 for t in dc.TARGET_VOCABULARY if t != "mingwX64"}))
        failures = dc.target_rules(self.root)
        self.assertTrue(any("README.md" in f and "mingwX64" in f for f in failures), failures)

    def test_target_rules_fail_on_tier_mismatch(self):
        # Every doc lists every target, but README puts jvm in the wrong tier.
        self._write("sdk/build.gradle.kts", BUILD_ALL_TARGETS)
        self._write("docs/target-support.md", ALL_TABLE)
        self._write(dc._ADR_TARGETS, ALL_TABLE)
        self._write("README.md", _tier_table({**ALL_TIER1, "jvm": 2}))
        failures = dc.target_rules(self.root)
        self.assertTrue(any("tier mismatch" in f and "jvm" in f for f in failures), failures)

    def test_target_rules_ignore_prose_only_mentions(self):
        # A target named only in prose (not in the tier table) must count as MISSING, not documented.
        self._write("sdk/build.gradle.kts", BUILD_ALL_TARGETS)
        self._write("docs/target-support.md", ALL_TABLE)
        self._write(dc._ADR_TARGETS, ALL_TABLE)
        table_without = _tier_table(
            {t: 1 for t in dc.TARGET_VOCABULARY if t != "mingwX64"},
            prose="Windows is served by the `mingwX64` target (mentioned in prose only).",
        )
        self._write("README.md", table_without)
        failures = dc.target_rules(self.root)
        self.assertTrue(any("README.md" in f and "mingwX64" in f for f in failures), failures)

    def test_grouped_tier_table_is_parsed(self):
        # ADR/README style: tier in the first cell, several targets grouped in one row.
        grouped = (
            "## Targets\n\n| Tier | Targets | Notes |\n| --- | --- | --- |\n"
            "| 1 | " + ", ".join(f"`{t}`" for t in dc.TARGET_VOCABULARY) + " | evidence |\n"
        )
        self.assertEqual({t: 1 for t in dc.TARGET_VOCABULARY}, dc._doc_target_tiers(grouped))

    # --- rule 3: pins ---
    def test_pin_rules_pass_and_fail(self):
        sha = "a" * 64
        self._write("spec/pin.json", '{\n  "sha256": "%s"\n}\n' % sha)
        self._write("spec/sdkgen.yaml", "source:\n  sha256: %s\noverlays:\n    sha256: %s\n" % (sha, "b" * 64))
        self.assertEqual([], dc.pin_rules(self.root))
        # Now break the sdkgen source digest.
        self._write("spec/sdkgen.yaml", "source:\n  sha256: %s\n" % ("c" * 64))
        self.assertTrue(dc.pin_rules(self.root))

    # --- rule 4: coverage ---
    def test_coverage_rules_pass_and_fail(self):
        cov = "## Totals\n- Spec operations (`operationId:` in `spec/openapi.yaml`): **101**\n- Generated operations: **100**\n"
        self._write("docs/coverage/operation-coverage.md", cov)
        self._write("README.md", "The client surface (100 of 101 operations) is generated.")
        self.assertEqual([], dc.coverage_rules(self.root))
        self._write("README.md", "The client surface (99 of 101 operations) is generated.")
        self.assertTrue(dc.coverage_rules(self.root))


if __name__ == "__main__":
    unittest.main()
