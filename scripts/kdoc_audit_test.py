#!/usr/bin/env python3
"""Tests for scripts/kdoc-audit.py — the KDoc completeness gate. Stdlib only."""
from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest

SCRIPT = pathlib.Path(__file__).with_name("kdoc-audit.py")
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location("kdoc_audit", SCRIPT)
assert _spec is not None and _spec.loader is not None
ka = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ka)

DOCUMENTED = '''
package x

/** A documented class. */
public class Foo {
    /** A documented function. */
    public fun bar(): Int = 1

    // A plain comment is NOT KDoc.
    public val undocumented: Int = 2

    @Deprecated("x")
    /** Documented even with an annotation between the doc and the decl. */
    public fun annotated() {}

    override fun toString(): String = "Foo"
}
'''

UNDOCUMENTED = '''
package x
public interface Service {
    public fun run()
}
'''


class KdocAuditTest(unittest.TestCase):
    def test_flags_only_the_undocumented_public_symbol(self):
        found = ka.offenders(DOCUMENTED)
        decls = [d for _, d in found]
        self.assertEqual(1, len(found), decls)
        self.assertIn("public val undocumented", decls[0])

    def test_annotation_between_doc_and_decl_is_documented(self):
        # The `@Deprecated` line sits between the KDoc and the decl; the walk-up skips it.
        found = ka.offenders(DOCUMENTED)
        self.assertFalse(any("annotated" in d for _, d in found))

    def test_override_is_skipped(self):
        self.assertFalse(any("toString" in d for _, d in ka.offenders(DOCUMENTED)))

    def test_undocumented_public_interface_and_member_are_flagged(self):
        found = ka.offenders(UNDOCUMENTED)
        decls = [d for _, d in found]
        self.assertTrue(any("interface Service" in d for d in decls), decls)
        self.assertTrue(any("fun run" in d for d in decls), decls)

    def test_non_public_is_ignored(self):
        self.assertEqual([], ka.offenders("internal fun x() {}\nprivate val y = 1\n"))

    def test_constructor_parameter_properties_are_documented_by_the_class(self):
        # The `val` params sit inside the still-open `(` of the primary constructor, so they are covered by the
        # class KDoc's @property tags and are not separately flagged; a class-body property still is.
        src = (
            "/** Documented. */\n"
            "public class Deadlines(\n"
            "    public val total: Int? = null,\n"
            "    public val attempt: Int? = null,\n"
            ") {\n"
            "    public val derived: Int = 1\n"
            "}\n"
        )
        decls = [d for _, d in ka.offenders(src)]
        self.assertEqual(["public val derived: Int = 1"], decls, decls)


if __name__ == "__main__":
    unittest.main()
