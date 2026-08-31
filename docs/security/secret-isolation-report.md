# Secret-isolation report

**Purpose.** This report proves that CI secrets are confined to the jobs that need them, that write-capable
jobs cannot execute untrusted input, and that fork pull requests never receive secrets. The workflow table
below is **generated** by `scripts/workflow-audit.py report` and CI fails if it goes stale
(`report --check`); the surrounding frame is maintained by hand. The auditor that produces it fails **closed**
(see `scripts/workflow-audit.py` and `docs/security/workflow-policy.json`).

## Secret inventory

| Secret | Used by | Readable from | Fork PRs | Rotation owner |
| --- | --- | --- | --- | --- |
| `OPENROUTER_API_KEY` | `live.yml/live-smoke` (nightly) | schedule/dispatch only | never | maintainers (least-privileged key with a spend limit) |
| `DRIFT_APP_PRIVATE_KEY` + var `DRIFT_APP_CLIENT_ID` | `drift.yml/open-pr` (optional) | schedule/dispatch only | never | maintainers (GitHub App; optional — falls back to `GITHUB_TOKEN`) |
| `GITHUB_TOKEN` | per-job, scoped | every run (auto) | read-only on forks | GitHub-managed |
| `GPG_SIGNING_KEY`, `MAVEN_CENTRAL_*` | *planned* | future release workflow only | never | maintainers |

Fork pull requests run with a read-only `GITHUB_TOKEN` and **no** repository secrets (GitHub's default), so none
of the above is exposed to fork PR code. Secrets are referenced only through `secrets.*` in the jobs the policy
allowlists; the auditor rejects any other reference.

## Generated workflow table

Columns: whether the job is write-capable, which secrets it references, its triggers, and whether it is safe on a
fork PR (a job is fork-unsafe if it is write-capable or references a secret).

<!-- workflow-audit:start -->
| workflow | job | write? | secrets | triggers | fork-safe? |
| --- | --- | --- | --- | --- | --- |
| ci.yml | build-linux | read | - | push,pull_request | yes |
| ci.yml | build-apple | read | - | push,pull_request | yes |
| ci.yml | build-linux-arm64 | read | - | push,pull_request | yes |
| ci.yml | build-windows | read | - | push,pull_request | yes |
| codeql.yml | analyze | write | - | schedule,workflow_dispatch | no |
| compat.yml | compat | read | - | pull_request | yes |
| dependency-review.yml | dependency-review | write | - | pull_request | no |
| drift.yml | detect | read | - | schedule,workflow_dispatch | yes |
| drift.yml | open-pr | write | DRIFT_APP_PRIVATE_KEY,GITHUB_TOKEN | schedule,workflow_dispatch | no |
| gitleaks.yml | scan | read | GITHUB_TOKEN | pull_request,push,schedule | no |
| live.yml | live-smoke | read | OPENROUTER_API_KEY | schedule,workflow_dispatch | no |
| nightly-targets.yml | intel-macos | read | - | schedule,workflow_dispatch | yes |
| parity.yml | refresh | read | - | schedule,workflow_dispatch | yes |
| perf.yml | bench-jvm-linux | read | - | schedule,workflow_dispatch | yes |
| perf.yml | bench-macos | read | - | schedule,workflow_dispatch | yes |
| scorecard.yml | analysis | write | - | schedule,push | no |
<!-- workflow-audit:end -->

## Controls (enforced by `scripts/workflow-audit.py check`, CI-gated)

- Every `uses:` pins a 40-hex commit SHA (no mutable tags).
- Top-level `permissions` is `contents: read`; write is granted per-job only where documented.
- Every `actions/checkout` sets `persist-credentials: false`.
- Secrets are allowlisted per `<workflow>/<job>`; a `schedule`-triggered workflow using a non-`GITHUB_TOKEN`
  secret must be declared.
- Every non-comment `run:` line in a repository-write job must match the policy verbatim; arbitrary shell is
  denied instead of being partially parsed through an interpreter or wrapper blocklist.
- `${{ … }}` context values may not be interpolated into `run:` — they pass through `env:`.
- The parser fails **closed**: any unsupported YAML construct is a violation, never a silent pass.

## Operator actions (repository settings; not created by automation)

1. **Enable GitHub secret scanning + push protection** (Settings → Code security).
2. **Actions → Fork pull request workflows from outside collaborators: require approval** (confirm the default).
3. Store the optional drift App credentials as described in `docs/spec-sync-and-release.md` (variable +
   secret), or leave them unset to run drift PRs with the default token (maintainer approves each workflow run).
