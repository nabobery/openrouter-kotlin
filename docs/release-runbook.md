# Release runbook

How a release of `io.github.nabobery:openrouter-kotlin` (and its companion `…:openrouter-kotlin-testing`) is cut,
verified, and published to Maven Central.

## The core invariant

**A published Maven Central component is immutable.** It cannot be edited, replaced, or deleted. The only remedy for
a bad release is to publish a new, higher patch version that supersedes it. Everything below exists to make sure
nothing reaches Central that has not already been proven — signed, inventoried, and resolved by real consumers — in a
credential-free rehearsal.

## Who may release

A maintainer with: approval rights on the `maven-central` GitHub environment, the Central Portal user token, and the
release signing key. The workflow is human-dispatched only; nothing publishes on a push or a schedule.

## Rehearsal (mandatory)

Before every release, run the credential-free rehearsal on macOS (the only host that stages every publication — the
Apple klibs and the Android aar):

```bash
bash scripts/release-rehearsal.sh          # stage -> inventory -> consumer matrix -> SBOM -> bundle
```

On a memory-constrained host add `GRADLE_PUBLISH_ARGS="--max-workers=1"` to serialize the native compiles. Then:

- Review `build/publication-inventory.json` — the exact coordinates, per-artifact sizes, and the bundle file/byte
  count that would be uploaded.
- Confirm the consumer matrix resolved the published coordinates on every host lane (`build/consumer-matrix.log`
  ends with `openrouter-kotlin OK: resolved`).
- Sanity-check `build/central-bundle.zip` — it is byte-deterministic (two runs of the same staged repo are
  identical) and excludes `.sha256`/`.sha512` and `maven-metadata.xml`.

## Cutting a release

1. `python3 scripts/release-version.py set <version>` — rewrites `gradle.properties` **and** the `SDK_VERSION`
   constant in lockstep (RC grammar: `MAJOR.MINOR.PATCH[-rc.N]`; the first candidate is `0.1.0-rc.1`).
2. Add a `## [<version>] - <date>` section to `CHANGELOG.md` (move everything from `[Unreleased]`); add a migration
   note under `docs/migration/` if anything is **Breaking**.
3. Open a PR, get it reviewed, merge to `main`.
4. Create a **signed** tag `v<version>` on the merge commit and push it. (`python3 scripts/release-version.py check
   --tag v<version>` must agree.)
5. Dispatch the **Release** workflow with `version=<version>` and `publish=false` (park the deployment).
6. Approve the `maven-central` environment when the `stage-and-publish` job requests it.
7. The job signs, stages, inventories, runs the consumer matrix, builds the SBOM and bundle, attests, uploads
   `USER_MANAGED`, waits for `VALIDATED`, and **re-runs the consumer matrix against the validated deployment**.
8. Inspect the validated deployment in the Portal (Deployments tab) and confirm the consumer-matrix-against-validated
   step passed. Then **click Publish in the Portal** to release the exact deployment you reviewed.

   > Do **not** re-dispatch the workflow with `publish=true` to release a *parked* deployment. A second dispatch
   > stages and uploads a **new** deployment (not the one you reviewed) and its `github-release` job re-runs
   > `gh release create` for the already-created `v<version>` tag, which fails. To publish automatically instead of
   > parking, choose `publish=true` on the **single** initial dispatch — that path publishes the same deployment it
   > just validated.

The RC's recommended disposition is to **publish it for real** so that post-publish resolution is rehearsed before
1.0 — an RC coordinate is harmless, and Central is immutable either way.

## Post-publish verification

- Wait for the deployment to reach `PUBLISHED`, then (after Central sync) resolve the coordinate from a fresh
  directory **outside** the repo:
  ```bash
  bash scripts/consumer-matrix.sh --repo https://repo1.maven.org/maven2/ --public --version <version>
  ```
- Verify provenance and SBOM attestations:
  ```bash
  gh attestation verify central-bundle.zip --repo nabobery/openrouter-kotlin
  gh attestation verify central-bundle.zip --repo nabobery/openrouter-kotlin --predicate-type https://cyclonedx.org/bom
  ```
- Confirm the Maven Central badge in `README.md` shows the new version.
- Bump to the next development version: `python3 scripts/release-version.py next-snapshot` (writes
  `<next>-SNAPSHOT`), commit.

## Rollback / incident

Nothing published can be deleted. If a release is bad:

- **Publish a patch** that supersedes it (bump the patch, fix, release again).
- Add a deprecation note to the GitHub Release and `CHANGELOG.md`.
- If security-relevant, open a GitHub Security Advisory.
- If the signing key or Central token was exposed, **rotate both** (new GPG key published to the keyservers; new
  Portal token) and update the GitHub secrets.
- A **parked** deployment (`publish=false`) that failed verification is **dropped**, never published:
  `python3 scripts/central-portal.py drop <deployment-id>`.

## Publishing limits

Maven Central meters each namespace against ~1,167 files / 78 MB / 7 releases per 3-month average (soft since
2026-06-16, rate-limited since 2026-08-11). This SDK is a generated multi-target SDK — the named high-volume pattern —
so a single release is large: **396 upload files, approximately 234 MiB** across both modules' 24 publications (the SDK's
generated surface makes the per-target artifacts large; the `-javadoc.jars` are deliberately lightweight, with the
full Dokka site on GitHub Pages instead). Consequences:

- **Cap cadence at two releases per month** unless an exemption is granted; batch spec drift into scheduled releases
  rather than releasing per upstream change.
- SBOM and provenance go to the **GitHub Release**, never to Central.
- Watch the Usage Center in the Portal; if the byte budget is tight, request the generated-SDK exemption (the
  documented path for exactly this pattern).

## What this runbook does NOT authorize

- No `AUTOMATIC` uploads from a laptop — always `USER_MANAGED`, always park-then-review for the first releases.
- No `-P` version overrides — the version is read from the tagged commit (`gradle.properties` + `SDK_VERSION`).
- No force-pushed or unsigned `v*` tags.
