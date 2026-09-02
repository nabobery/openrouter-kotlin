#!/usr/bin/env python3
"""Tests for scripts/changelog-extract.py. Python 3 stdlib only."""
from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest

SCRIPT = pathlib.Path(__file__).with_name("changelog-extract.py")
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location("changelog_extract", SCRIPT)
assert _spec is not None and _spec.loader is not None
ce = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ce)

CHANGELOG = """# Changelog

## [Unreleased]

### Added
- next thing

## [0.1.0-rc.1] - 2026-09-02

### Added
- the first candidate
- streaming

### Breaking (pre-1.0)
- none

## [0.0.9] - 2026-08-01

### Added
- older
"""


class ExtractTest(unittest.TestCase):
    def test_extracts_named_section_without_header(self):
        body = ce.extract(CHANGELOG, "0.1.0-rc.1")
        self.assertIsNotNone(body)
        self.assertIn("the first candidate", body)
        self.assertIn("Breaking (pre-1.0)", body)
        # The next section's content must not bleed in, and the header line is excluded.
        self.assertNotIn("older", body)
        self.assertNotIn("## [0.1.0-rc.1]", body)

    def test_unreleased_is_addressable(self):
        self.assertIn("next thing", ce.extract(CHANGELOG, "Unreleased"))

    def test_absent_version_returns_none(self):
        self.assertIsNone(ce.extract(CHANGELOG, "9.9.9"))

    def test_empty_section_returns_none(self):
        text = "# Changelog\n\n## [0.2.0]\n\n## [0.1.0]\n\n- content\n"
        self.assertIsNone(ce.extract(text, "0.2.0"))

    def test_rc_suffix_is_not_a_regex_wildcard(self):
        # The version is matched literally: `0.1.0` must not match the `0.1.0-rc.1` header.
        self.assertIsNone(ce.extract(CHANGELOG, "0.1.0"))

    def test_main_exit_codes(self):
        import io
        from contextlib import redirect_stdout, redirect_stderr

        tmp = pathlib.Path(ce.__file__).parent / "_changelog_fixture.md"
        tmp.write_text(CHANGELOG)
        try:
            out = io.StringIO()
            with redirect_stdout(out):
                self.assertEqual(0, ce.main(["0.1.0-rc.1", "--changelog", str(tmp)]))
            self.assertIn("the first candidate", out.getvalue())
            with redirect_stderr(io.StringIO()):
                self.assertEqual(1, ce.main(["9.9.9", "--changelog", str(tmp)]))
        finally:
            tmp.unlink()


if __name__ == "__main__":
    unittest.main()
