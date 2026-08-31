#!/usr/bin/env python3
"""Tests for scripts/docs-snippets.py — the Markdown snippet injector used by the compiled guides. Stdlib only."""
from __future__ import annotations

import importlib.util
import pathlib
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("docs-snippets.py")
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location("docs_snippets", SCRIPT)
assert _spec is not None and _spec.loader is not None
ds = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ds)

SOURCE = """package guides
// region imports
import com.nabobery.openrouter.OpenRouter
// endregion
fun demo() {
    // region client
    val client = OpenRouter(credential = cred, httpClient = http)
    println(client)
    // endregion
}
"""

MD_TEMPLATE = """# Guide

<!-- snippet: src/Demo.kt#client -->
```kotlin
{body}```
<!-- /snippet -->
"""


class DocsSnippetsTest(unittest.TestCase):
    def setUp(self):
        self.root = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(__import__("shutil").rmtree, self.root)
        (self.root / "src").mkdir()
        (self.root / "src" / "Demo.kt").write_text(SOURCE)
        self.guides = self.root / "docs" / "guides"
        self.guides.mkdir(parents=True)

    def _write_md(self, body: str) -> pathlib.Path:
        md = self.guides / "g.md"
        md.write_text(MD_TEMPLATE.format(body=body))
        return md

    def test_extract_regions_dedents_and_excludes_markers(self):
        regions = ds.extract_regions(SOURCE)
        self.assertEqual(["import com.nabobery.openrouter.OpenRouter"], regions["imports"])
        # The `client` region is indented 4 spaces in source; dedent removes exactly that common prefix.
        self.assertEqual(
            ["val client = OpenRouter(credential = cred, httpClient = http)", "println(client)"],
            regions["client"],
        )

    def test_nested_region_is_an_error(self):
        with self.assertRaises(ds.SnippetError):
            ds.extract_regions("// region a\n// region b\n// endregion\n// endregion\n")

    def test_unclosed_region_is_an_error(self):
        with self.assertRaises(ds.SnippetError):
            ds.extract_regions("// region a\ncode\n")

    def test_update_fills_the_block_and_check_passes(self):
        md = self._write_md("")  # empty fenced block
        self.assertEqual(0, ds.main(["update", "--root", str(self.root)]))
        text = md.read_text()
        self.assertIn("val client = OpenRouter(credential = cred, httpClient = http)", text)
        self.assertIn("println(client)", text)
        # Freshly updated → check is clean.
        self.assertEqual(0, ds.main(["check", "--root", str(self.root)]))

    def test_update_is_idempotent(self):
        md = self._write_md("")
        ds.main(["update", "--root", str(self.root)])
        once = md.read_text()
        ds.main(["update", "--root", str(self.root)])
        self.assertEqual(once, md.read_text())

    def test_check_detects_a_stale_block(self):
        self._write_md("val client = OLD\n")
        self.assertEqual(1, ds.main(["check", "--root", str(self.root)]))

    def test_missing_region_fails(self):
        (self.guides / "g.md").write_text(
            "<!-- snippet: src/Demo.kt#nope -->\n```kotlin\n```\n<!-- /snippet -->\n"
        )
        self.assertEqual(1, ds.main(["check", "--root", str(self.root)]))

    def test_missing_source_file_fails(self):
        (self.guides / "g.md").write_text(
            "<!-- snippet: src/Nope.kt#client -->\n```kotlin\n```\n<!-- /snippet -->\n"
        )
        self.assertEqual(1, ds.main(["check", "--root", str(self.root)]))


if __name__ == "__main__":
    unittest.main()
