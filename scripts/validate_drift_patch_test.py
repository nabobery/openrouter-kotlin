#!/usr/bin/env python3
"""Tests for scripts/validate-drift-patch.py — the structural gate the write-capable drift `open-pr` job
runs on the untrusted patch before applying it. A path allowlist alone is not a control: `git apply` can
create symlinks, set the executable bit, or apply binary hunks inside allowed directories. So the patch is
validated structurally (allowlisted regular 100644 paths only; no binary hunks; no mode/type/rename changes;
non-empty) and only then applied to a temporary index. Python 3 stdlib only.
"""
from __future__ import annotations

import importlib.util
import pathlib
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("validate-drift-patch.py")
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location("validate_drift_patch", SCRIPT)
assert _spec is not None and _spec.loader is not None
vdp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(vdp)


def violations(patch_text: str):
    with tempfile.NamedTemporaryFile("w", suffix=".patch", delete=False) as f:
        f.write(patch_text)
        path = f.name
    try:
        return vdp.validate(path)
    finally:
        pathlib.Path(path).unlink()


VALID = """\
diff --git a/spec/pin.json b/spec/pin.json
index 1111111..2222222 100644
--- a/spec/pin.json
+++ b/spec/pin.json
@@ -1,1 +1,1 @@
-  "sha256": "aaaa"
+  "sha256": "bbbb"
diff --git a/sdk/api/sdk.api b/sdk/api/sdk.api
index 3333333..4444444 100644
--- a/sdk/api/sdk.api
+++ b/sdk/api/sdk.api
@@ -1,0 +1,1 @@
+public final class Foo
diff --git a/docs/compat/2026-08-30-b2a4948a-to-e88b0cec.md b/docs/compat/2026-08-30-b2a4948a-to-e88b0cec.md
new file mode 100644
index 0000000..5555555
--- /dev/null
+++ b/docs/compat/2026-08-30-b2a4948a-to-e88b0cec.md
@@ -0,0 +1,1 @@
+minor
"""


class ValidateDriftPatchTest(unittest.TestCase):
    def test_valid_patch_has_no_violations(self):
        self.assertEqual([], violations(VALID))

    def test_empty_patch_rejected(self):
        self.assertTrue(violations("\n"))

    def test_path_outside_allowlist_rejected(self):
        bad = VALID.replace("spec/pin.json", "spec/secret.txt")
        v = violations(bad)
        self.assertTrue(any("secret.txt" in x for x in v))

    def test_symlink_mode_rejected(self):
        bad = """\
diff --git a/docs/compat/2026-08-30-b2a4948a-to-e88b0cec.md b/docs/compat/2026-08-30-b2a4948a-to-e88b0cec.md
new file mode 120000
index 0000000..5555555
--- /dev/null
+++ b/docs/compat/2026-08-30-b2a4948a-to-e88b0cec.md
@@ -0,0 +1 @@
+../../../etc/passwd
"""
        self.assertTrue(any("120000" in x for x in violations(bad)))

    def test_executable_mode_rejected(self):
        bad = """\
diff --git a/spec/pin.json b/spec/pin.json
old mode 100644
new mode 100755
index 1111111..2222222
"""
        self.assertTrue(any("100755" in x for x in violations(bad)))

    def test_binary_hunk_rejected(self):
        bad = """\
diff --git a/spec/pin.json b/spec/pin.json
index 1111111..2222222 100644
GIT binary patch
literal 4
Tcmp= 1
"""
        self.assertTrue(any("binary" in x.lower() for x in violations(bad)))

    def test_rename_rejected(self):
        bad = """\
diff --git a/spec/pin.json b/spec/sdkgen.yaml
similarity index 100%
rename from spec/pin.json
rename to spec/sdkgen.yaml
"""
        self.assertTrue(any("rename" in x.lower() for x in violations(bad)))

    def test_dotdot_path_rejected(self):
        bad = VALID.replace("spec/pin.json", "spec/../pin.json")
        self.assertTrue(violations(bad))

    def test_main_returns_nonzero_on_violation(self):
        with tempfile.NamedTemporaryFile("w", suffix=".patch", delete=False) as f:
            f.write("\n")
            path = f.name
        try:
            self.assertEqual(1, vdp.main([path]))
        finally:
            pathlib.Path(path).unlink()

    def test_main_returns_zero_on_valid(self):
        with tempfile.NamedTemporaryFile("w", suffix=".patch", delete=False) as f:
            f.write(VALID)
            path = f.name
        try:
            self.assertEqual(0, vdp.main([path]))
        finally:
            pathlib.Path(path).unlink()


if __name__ == "__main__":
    unittest.main()
