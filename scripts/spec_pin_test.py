#!/usr/bin/env python3
"""Tests for scripts/spec-pin.py — the pin/lock rewriters used by drift-refresh.sh.

The rewriters use targeted `re` substitutions (never a JSON/YAML writer) so that comments, key order,
and the compact overlay formatting survive; only the intended fields move. Python 3 stdlib only.
"""
from __future__ import annotations

import importlib.util
import pathlib
import shutil
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("spec-pin.py")
ROOT = SCRIPT.parent.parent
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location("spec_pin", SCRIPT)
assert _spec is not None and _spec.loader is not None
sp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sp)

# A sha/size deliberately distinct from any committed pin value so update-source moves all four fields.
HEX64 = "abcdef0123456789" * 4
ADDR = "f" * 64
DISTINCT_SIZE = 1234567


class SpecPinTest(unittest.TestCase):
    def setUp(self):
        self.dir = pathlib.Path(tempfile.mkdtemp())
        for name in ("pin.json", "sdkgen.yaml", "generated.lock.json"):
            shutil.copy(ROOT / "spec" / name, self.dir / name)
        self.addCleanup(shutil.rmtree, self.dir)

    def _lines(self, name):
        return (self.dir / name).read_text().splitlines()

    def test_read_source_matches_pin(self):
        self.assertEqual(64, len(sp.read_source(str(self.dir))))

    def test_update_source_moves_only_intended_lines(self):
        before_pin = self._lines("pin.json")
        before_yaml = self._lines("sdkgen.yaml")
        sp.update_source(str(self.dir), HEX64, DISTINCT_SIZE, "2026-08-30T00:00:00Z", "New provenance … line.")

        after_pin = self._lines("pin.json")
        after_yaml = self._lines("sdkgen.yaml")

        changed_pin = [i for i, (a, b) in enumerate(zip(before_pin, after_pin)) if a != b]
        changed_yaml = [i for i, (a, b) in enumerate(zip(before_yaml, after_yaml)) if a != b]

        # pin.json: exactly the four consecutive fields (a single contiguous hunk).
        self.assertEqual([2, 3, 4, 5], changed_pin)  # 0-indexed lines 3-6
        # sdkgen.yaml: exactly the source sha256 (line 4, 0-indexed 3); overlay digests untouched.
        self.assertEqual([3], changed_yaml)

        self.assertIn(HEX64, after_pin[2])
        self.assertIn(str(DISTINCT_SIZE), after_pin[3])
        self.assertIn("2026-08-30T00:00:00Z", after_pin[4])
        self.assertIn("New provenance", after_pin[5])
        self.assertIn(HEX64, after_yaml[3])
        # The overlay sha256 lines (4-space indent) are preserved verbatim — only the source digest moved.
        overlay_before = [ln for ln in before_yaml if ln.startswith("    sha256:")]
        overlay_after = [ln for ln in after_yaml if ln.startswith("    sha256:")]
        self.assertEqual(overlay_before, overlay_after)
        self.assertTrue(overlay_after, "expected overlay sha256 lines to exist")
        self.assertEqual(HEX64, sp.read_source(str(self.dir)))

    def test_update_source_roundtrip_is_a_noop(self):
        # Re-writing with the current values must not change any byte (preserves the literal `…`).
        before = (self.dir / "pin.json").read_text()
        import json
        cur = json.loads(before)
        sp.update_source(str(self.dir), cur["sha256"], cur["sizeBytes"], cur["retrievedAt"], cur["provenance"])
        self.assertEqual(before, (self.dir / "pin.json").read_text())

    def test_update_lock_preserves_comment(self):
        before = self._lines("generated.lock.json")
        sp.update_lock(str(self.dir), ADDR, 1851, 1850)
        after = self._lines("generated.lock.json")
        self.assertTrue(any("_comment" in ln for ln in after))
        self.assertEqual([ln for ln in before if "_comment" in ln], [ln for ln in after if "_comment" in ln])
        self.assertTrue(any(ADDR in ln for ln in after))
        self.assertTrue(any('"fileCount": 1851' in ln for ln in after))
        self.assertTrue(any('"kotlinFileCount": 1850' in ln for ln in after))

    def test_invalid_sha_exits_2(self):
        with self.assertRaises(SystemExit) as ctx:
            sp.update_source(str(self.dir), "tooshort", 1, "x", "y")
        self.assertEqual(2, ctx.exception.code)


if __name__ == "__main__":
    unittest.main()
