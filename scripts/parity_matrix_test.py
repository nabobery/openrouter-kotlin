#!/usr/bin/env python3
"""Tests for scripts/parity-matrix.py — the official-SDK parity matrix generator.

`fetch` (network) pins the official Speakeasy inventories; `render` (offline) builds the matrix from the pinned
inventories + our coverage + curated behaviour rows; `--check` gates freshness. Python 3 stdlib only.
"""
from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest

SCRIPT = pathlib.Path(__file__).with_name("parity-matrix.py")
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location("parity_matrix", SCRIPT)
assert _spec is not None and _spec.loader is not None
pm = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pm)

GEN_LOCK = """\
id: abc
management:
  docChecksum: deadbeef
  speakeasyVersion: 1.787.0
examples:
  getModels:
    speakeasy-default-get-models:
      responses: {}
  sendChatCompletionRequest:
    speakeasy-default-send:
      requestBody: {}
  listFiles:
    x: {}
features:
  typescript: {}
"""

COVERAGE_MD = """\
# Operation coverage dashboard
- Omitted (accepted waivers): **1** — deleteScimGroupMapping

| resource | operationId | method | path | body | pagination | stream | evidence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| chat | sendChatCompletionRequest | POST | `/chat/completions` | json | no | yes | X.kt |
| models | getModels | GET | `/models` | none | no | no | Y.kt |
"""


class ParseTest(unittest.TestCase):
    def test_operation_ids_from_gen_lock(self):
        ops = pm.operation_ids(GEN_LOCK)
        self.assertEqual({"getModels", "sendChatCompletionRequest", "listFiles"}, ops)

    def test_config_facts_from_gen_yaml(self):
        facts = pm.config_facts("version: 1.2.84\nenvVarPrefix: OPENROUTER\nforwardCompatibleEnumsByDefault: false\n")
        self.assertEqual("1.2.84", facts["version"])
        self.assertEqual("OPENROUTER", facts["envVarPrefix"])
        self.assertEqual("false", facts["forwardCompatibleEnumsByDefault"])

    def test_coverage_generated_and_waived(self):
        generated, waived = pm.coverage_sets(COVERAGE_MD)
        self.assertEqual({"sendChatCompletionRequest", "getModels"}, generated)
        self.assertEqual({"deleteScimGroupMapping"}, waived)


class RenderTest(unittest.TestCase):
    def _inv(self, ops, version):
        return {"operationIds": sorted(ops), "config": {"version": version}, "commit": "c", "sdkVersion": version}

    def test_render_is_deterministic_and_has_totals(self):
        inv = {
            "typescript": self._inv(["getModels", "sendChatCompletionRequest", "listFiles"], "1.2.84"),
            "python": self._inv(["getModels", "sendChatCompletionRequest"], "1.1.104"),
            "go": self._inv(["getModels", "sendChatCompletionRequest"], "0.7.97"),
        }
        generated = {"sendChatCompletionRequest", "getModels"}
        waived = {"listFiles"}
        behaviors = [{"aspect": "retry strategy", "ts": "5XX", "py": "5XX", "go": "5XX",
                      "kotlin": "429-only", "status": "deliberate-deviation", "evidence": "RetryPolicy.kt"}]
        a = pm.render(inv, generated, waived, behaviors, {"initialInterval": "500"})
        b = pm.render(inv, generated, waived, behaviors, {"initialInterval": "500"})
        self.assertEqual(a, b)
        self.assertIn("| getModels |", a)
        self.assertIn("retry strategy", a)
        self.assertIn("deliberate-deviation", a)

    def test_check_detects_stale(self):
        self.assertFalse(pm.is_fresh("old", "new"))
        self.assertTrue(pm.is_fresh("same", "same"))

    def test_render_ends_with_exactly_one_newline(self):
        rendered = pm.render({}, set(), set(), [], {})
        self.assertTrue(rendered.endswith("\n"))
        self.assertFalse(rendered.endswith("\n\n"))


if __name__ == "__main__":
    unittest.main()
