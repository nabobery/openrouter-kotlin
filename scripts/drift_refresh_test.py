#!/usr/bin/env python3
"""Regression tests for the drift refresh shell orchestrator."""

from __future__ import annotations

import os
import pathlib
import shutil
import subprocess
import tempfile
import textwrap
import unittest


SCRIPT = pathlib.Path(__file__).with_name("drift-refresh.sh")


class DriftRefreshTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name) / "repo"
        self.bin = pathlib.Path(self.tmp.name) / "bin"
        (self.root / "scripts").mkdir(parents=True)
        (self.root / "spec").mkdir()
        (self.root / "sdk" / "api").mkdir(parents=True)
        self.bin.mkdir()
        shutil.copy2(SCRIPT, self.root / "scripts" / SCRIPT.name)

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def _executable(self, path: pathlib.Path, body: str) -> None:
        path.write_text(textwrap.dedent(body))
        path.chmod(0o755)

    def _run(self, **extra_env: str) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env.update(
            PATH=f"{self.bin}:/usr/bin:/bin",
            TMPDIR=str(pathlib.Path(self.tmp.name) / "tmp"),
            **extra_env,
        )
        pathlib.Path(env["TMPDIR"]).mkdir()
        return subprocess.run(
            ["/bin/bash", str(self.root / "scripts" / SCRIPT.name)],
            cwd=self.root,
            env=env,
            capture_output=True,
            text=True,
        )

    def test_rehearsal_keeps_patch_in_callers_output_directory(self) -> None:
        self._executable(
            self.bin / "git",
            """\
            #!/bin/sh
            if [ "$1" = worktree ] && [ "$2" = add ]; then mkdir -p "$4/scripts"; exit 0; fi
            if [ "$1" = worktree ] && [ "$2" = remove ]; then rm -rf "$4"; exit 0; fi
            if [ "$1" = -C ]; then exit 0; fi
            if [ "$1" = add ]; then exit 0; fi
            if [ "$1" = diff ]; then printf '%s\n' 'diff --git a/spec/pin.json b/spec/pin.json'; exit 0; fi
            exit 0
            """,
        )
        self._executable(
            self.bin / "bash",
            """\
            #!/bin/sh
            mkdir -p "$DRIFT_OUT"
            exit 10
            """,
        )

        result = self._run(DRIFT_WORKTREE="HEAD")

        patch = self.root / "build" / "drift" / "drift.patch"
        self.assertEqual(10, result.returncode, result.stderr)
        self.assertTrue(patch.is_file(), f"missing caller-owned patch; stdout={result.stdout!r}")
        self.assertIn("diff --git", patch.read_text())
        self.assertIn(str(patch), result.stdout)

    def test_second_generation_failure_uses_blocked_exit_code(self) -> None:
        old_sha = "a" * 64
        new_sha = "b" * 64
        (self.root / "spec" / "openapi.yaml").write_text("openapi: 3.1.0\n")
        self._executable(
            self.bin / "python3",
            f"""\
            #!/bin/sh
            case "$*" in
              *read-source*) printf '%s\n' 'sha256={old_sha}' ;;
            esac
            exit 0
            """,
        )
        self._executable(
            self.bin / "bash",
            f"""\
            #!/bin/sh
            if [ "$1" = scripts/fetch-upstream-spec.sh ]; then
              printf '%s\n' 'openapi: 3.1.0' > "$2"
              printf '%s\n' 'sha256={new_sha}' 'sizeBytes=16' 'operations=1' 'retrievedAt=2026-08-31T00:00:00Z'
              exit 0
            fi
            exit 0
            """,
        )
        self._executable(
            self.root / "gradlew",
            """\
            #!/bin/sh
            count_file=build/generate-count
            mkdir -p build sdk/build/generated/sdkgen/openrouter/.snapshots/address/sources
            count=0
            [ ! -f "$count_file" ] || count=$(cat "$count_file")
            count=$((count + 1))
            printf '%s\n' "$count" > "$count_file"
            if [ "$count" -eq 1 ]; then
              ln -s .snapshots/address/sources sdk/build/generated/sdkgen/openrouter/sources
              exit 0
            fi
            exit 42
            """,
        )

        result = self._run()

        self.assertEqual(20, result.returncode, result.stdout + result.stderr)
        self.assertIn("generation failed", result.stdout)


if __name__ == "__main__":
    unittest.main()
