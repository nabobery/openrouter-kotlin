#!/usr/bin/env python3
"""Tests for the budget checker (scripts/budgets.py)."""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest


SCRIPT = pathlib.Path(__file__).with_name("budgets.py")
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("budgets", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
budgets = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(budgets)


class BudgetCheckTest(unittest.TestCase):
    def test_within_tolerance_passes(self) -> None:
        self.assertEqual([], budgets.check({"a": 1000}, {"a": 1050}, tolerance=0.10))

    def test_exactly_at_tolerance_passes(self) -> None:
        self.assertEqual([], budgets.check({"a": 1000}, {"a": 1100}, tolerance=0.10))

    def test_over_tolerance_fails(self) -> None:
        failures = budgets.check({"a": 1000}, {"a": 1101}, tolerance=0.10)
        self.assertEqual(1, len(failures))
        self.assertIn("a", failures[0])

    def test_new_artifact_not_in_baseline_fails(self) -> None:
        failures = budgets.check({"a": 1000}, {"a": 1000, "b": 5}, tolerance=0.10)
        self.assertTrue(any("b" in f and "new" in f.lower() for f in failures))

    def test_shrinking_artifact_passes(self) -> None:
        self.assertEqual([], budgets.check({"a": 1000}, {"a": 10}, tolerance=0.10))

    def test_missing_baseline_artifact_fails(self) -> None:
        failures = budgets.check({"a": 1000, "b": 20}, {"a": 1000}, tolerance=0.10)
        self.assertTrue(any("b" in f and "missing" in f.lower() for f in failures))

    def test_missing_artifact_allowed_by_allowlist_passes(self) -> None:
        self.assertEqual(
            [], budgets.check({"a": 1000, "b": 20}, {"a": 1000}, tolerance=0.10, allow_removed={"b"})
        )

    def test_allowlist_does_not_mask_other_failures(self) -> None:
        # 'b' is allowed to vanish, but 'c' vanishing is still a failure.
        failures = budgets.check({"a": 1000, "b": 20, "c": 5}, {"a": 1000}, tolerance=0.10, allow_removed={"b"})
        self.assertTrue(any("c" in f and "missing" in f.lower() for f in failures))
        self.assertFalse(any("'b'" in f for f in failures))


if __name__ == "__main__":
    unittest.main()
