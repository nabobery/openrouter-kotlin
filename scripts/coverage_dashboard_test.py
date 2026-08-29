#!/usr/bin/env python3
"""Tests for the operation inventory invariant enforced by coverage-dashboard.py."""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest


SCRIPT = pathlib.Path(__file__).with_name("coverage-dashboard.py")
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("coverage_dashboard", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
coverage_dashboard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(coverage_dashboard)


class OperationInventoryTest(unittest.TestCase):
    def test_exact_generated_and_waived_partition_is_valid(self) -> None:
        self.assertEqual([], coverage_dashboard.inventory_errors(["a", "b"], ["a"], ["b"]))

    def test_overlapping_waiver_cannot_mask_missing_operation(self) -> None:
        errors = coverage_dashboard.inventory_errors(["a", "b"], ["a"], ["a"])
        self.assertTrue(any("missing" in error for error in errors))
        self.assertTrue(any("both generated and waived" in error for error in errors))

    def test_unknown_waiver_is_rejected(self) -> None:
        errors = coverage_dashboard.inventory_errors(["a"], [], ["not-in-spec"])
        self.assertTrue(any("not present in the spec" in error for error in errors))

    def test_duplicate_ids_are_rejected(self) -> None:
        errors = coverage_dashboard.inventory_errors(["a", "a"], ["a", "a"], [])
        self.assertTrue(any("duplicate spec" in error for error in errors))
        self.assertTrue(any("duplicate generated" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
