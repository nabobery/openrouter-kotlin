# Contributing to openrouter-kotlin

Thanks for your interest. This SDK is **generated from the OpenRouter OpenAPI document** by
[kotlin-sdkgen](https://github.com/nabobery/kotlin-sdkgen) and hand-wraps a small curated
facade on top. A few rules follow from that architecture — please read them before opening a
pull request.

## Prerequisites

- **JDK 25** (Temurin recommended). The build's Kotlin/Gradle toolchains are driven from it.
- Use the **Gradle wrapper** (`./gradlew`) — never a locally installed Gradle. The wrapper
  pins the supported Gradle version.
- Optional: an Android SDK (`ANDROID_HOME`) to build the Android target and the runnable
  Android sample; Xcode on macOS for the iOS/Apple targets and the Swift consumer.

## The one rule that matters most

**Never hand-edit generated sources.** The generated Kotlin is not checked into git; it is
reproduced from the pinned spec on every build into `sdk/build/generated/…`. Editing it (or
anything under `sdk/build/`) is a no-op that the drift gate will reject. To change the
generated surface you change an **input** — the pinned spec, an overlay, or the generator
version — and regenerate.

## Local verification

Run the same gate CI runs before you push:

```bash
./gradlew :sdk:verificationCheck samplesCheck
```

`verificationCheck` is host-aware: it compiles every target this host can build, runs the
runtime lanes it can execute, and checks the public-API baseline (JVM + klib ABI), the SDK
version constant, and ktlint. `samplesCheck` compiles the sample consumers.

If your change touches the **public API**, refresh the ABI baseline and commit it:

```bash
./gradlew :sdk:apiDump
```

If your change moves the **spec pin**, run the drift/regeneration flow rather than editing
sources by hand:

```bash
bash scripts/drift-refresh.sh   # fetch → re-pin → regenerate → re-baseline
bash scripts/check-drift.sh     # asserts the committed baseline reproduces
```

**Budgets:** artifact-size, compile-time, and warning budgets live under `docs/budgets/`.
If a legitimate change moves a budget, **re-record the baseline in the same commit that
explains why** — never widen a tolerance to make a run pass.

## Commit and PR conventions

- **Conventional Commits.** Prefix with `feat:`, `fix:`, `docs:`, `chore:`, `ci:`,
  `build:`, `tooling:`, `security:`, `samples:`, `spec:`, etc.
- **Breaking changes** carry a `!` (e.g. `feat(sdk)!: …`) and a `Breaking:`/`BREAKING CHANGE:`
  note, and must update `CHANGELOG.md` and (for a release) `docs/migration/`.
- No CLA and **no DCO sign-off** is required.

## Releasing

Releases are cut by a maintainer following [`docs/release-runbook.md`](docs/release-runbook.md): rehearse
credential-free with `bash scripts/release-rehearsal.sh`, set the version with
`python3 scripts/release-version.py set <version>`, tag `v<version>` on the merged commit, and dispatch the
protected **Release** workflow. Maven Central is immutable — a bad release is superseded by a patch, never edited.

## Security

Do not file public issues for vulnerabilities — see [SECURITY.md](SECURITY.md) for private
reporting via GitHub Security Advisories.
