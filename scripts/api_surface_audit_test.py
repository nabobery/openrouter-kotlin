#!/usr/bin/env python3
"""Tests for scripts/api-surface-audit.py — the dump-based public-surface audit. Stdlib only.

Each test builds a throwaway repo root (a `sdk/src/**` curated tree plus committed `sdk/api/*.api`
BCV dumps) so the audit is exercised end-to-end against fixtures rather than the real baselines.
"""
from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import textwrap
import unittest

SCRIPT = pathlib.Path(__file__).with_name("api-surface-audit.py")
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location("api_surface_audit", SCRIPT)
assert _spec is not None and _spec.loader is not None
asa = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(asa)


def _write(path: pathlib.Path, body: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(body))


# A clean curated declaration: an ordinary class with getters — no data class, no @PublishedApi.
CLEAN_KT = """\
package com.example.demo

/** A curated value type. */
public class RetryPolicy(public val maxAttempts: Int) {
    public fun withAttempts(n: Int): RetryPolicy = RetryPolicy(n)
}
"""

# A clean JVM ABI dump for the curated class above, plus one generated class that must be ignored.
CLEAN_DUMP = """\
public final class com/example/demo/RetryPolicy {
\tpublic fun <init> (I)V
\tpublic final fun getMaxAttempts ()I
\tpublic final fun withAttempts (I)Lcom/example/demo/RetryPolicy;
}

public final class com/example/generated/PageResponse {
\tpublic final fun component1 ()Ljava/lang/String;
\tpublic final fun copy (Ljava/lang/String;)Lcom/example/generated/PageResponse;
}
"""


class ApiSurfaceAuditTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name)
        self.pkg_dir = self.root / "sdk/src/commonMain/kotlin/com/example/demo"
        self.dump = self.root / "sdk/api/sdk.api"

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _clean(self) -> None:
        _write(self.pkg_dir / "RetryPolicy.kt", CLEAN_KT)
        _write(self.dump, CLEAN_DUMP)

    # --- clean surface -------------------------------------------------------------------------
    def test_clean_surface_has_no_violations(self) -> None:
        self._clean()
        report = asa.audit(self.root)
        self.assertEqual([], report.violations, report.violations)

    def test_check_exits_zero_on_clean_surface(self) -> None:
        self._clean()
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--check", "--root", str(self.root)],
            capture_output=True, text=True,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    # --- data class violations -----------------------------------------------------------------
    def test_curated_data_class_in_source_is_a_violation(self) -> None:
        self._clean()
        _write(self.pkg_dir / "Point.kt", "package com.example.demo\n\npublic data class Point(val x: Int)\n")
        report = asa.audit(self.root)
        self.assertTrue(any("Point" in v and "data class" in v for v in report.violations), report.violations)

    def test_curated_component1_copy_in_dump_is_a_data_class_violation(self) -> None:
        # Curated class name present in sdk/src, and its dump block exhibits both component1 + copy.
        _write(self.pkg_dir / "Envelope.kt", "package com.example.demo\n\npublic class Envelope\n")
        _write(self.dump, textwrap.dedent("""\
            public final class com/example/demo/Envelope {
            \tpublic final fun component1 ()Ljava/lang/String;
            \tpublic final fun copy (Ljava/lang/String;)Lcom/example/demo/Envelope;
            }
        """))
        report = asa.audit(self.root)
        self.assertTrue(any("Envelope" in v and "data class" in v for v in report.violations), report.violations)

    def test_generated_data_class_in_dump_is_not_a_violation(self) -> None:
        # PageResponse (component1+copy) lives only in the dump, not in sdk/src → generated → ignored.
        self._clean()
        report = asa.audit(self.root)
        self.assertFalse(any("PageResponse" in v for v in report.violations), report.violations)

    # --- @PublishedApi allowlist ---------------------------------------------------------------
    def test_published_api_not_in_allowlist_is_a_violation(self) -> None:
        self._clean()
        _write(self.pkg_dir / "Internals.kt", textwrap.dedent("""\
            package com.example.demo

            @PublishedApi
            internal fun secretHelper(): Int = 1
        """))
        report = asa.audit(self.root)
        self.assertTrue(any("secretHelper" in v and "PublishedApi" in v for v in report.violations), report.violations)

    def test_published_api_in_allowlist_is_not_a_violation(self) -> None:
        self._clean()
        _write(self.pkg_dir / "Internals.kt", textwrap.dedent("""\
            package com.example.demo

            @PublishedApi
            internal fun secretHelper(): Int = 1
        """))
        report = asa.audit(self.root, allowlist={"com.example.demo.secretHelper"})
        self.assertFalse(any("secretHelper" in v for v in report.violations), report.violations)

    # --- @OpenRouterExperimentalApi listing (informational, not a violation) -------------------
    def test_experimental_declarations_are_listed_and_not_violations(self) -> None:
        self._clean()
        _write(self.pkg_dir / "Preview.kt", textwrap.dedent("""\
            package com.example.demo

            /** Preview. */
            @OpenRouterExperimentalApi
            public fun previewFeature(): Int = 1

            @OpenRouterExperimentalApi
            public class PreviewConfig
        """))
        report = asa.audit(self.root)
        joined = "\n".join(report.experimental)
        self.assertIn("previewFeature", joined)
        self.assertIn("PreviewConfig", joined)
        self.assertIn("Preview.kt", joined)
        # Experimental declarations are informational — never counted as violations.
        self.assertFalse(any("previewFeature" in v or "PreviewConfig" in v for v in report.violations), report.violations)

    # --- --check exit-code discipline ----------------------------------------------------------
    def test_check_exits_nonzero_on_violation(self) -> None:
        self._clean()
        _write(self.pkg_dir / "Point.kt", "package com.example.demo\n\npublic data class Point(val x: Int)\n")
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--check", "--root", str(self.root)],
            capture_output=True, text=True,
        )
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("Point", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
