# Publication

Everything required to turn `:sdk` and `:openrouter-kotlin-testing` into resolvable Maven Central coordinates
(`io.github.nabobery:openrouter-kotlin` and `…:openrouter-kotlin-testing`) and to prove the result
before anything reaches a remote.

## What lives here

| Path | Purpose |
| --- | --- |
| `isolated-repository.init.gradle.kts` | Init script that rewires every `maven-publish` project to a local `file:` repository, so a release can be staged, inspected, and consumed without touching `~/.m2` or a remote. |
| `expected-artifacts.json` | The authoritative inventory the staged repository must contain — every coordinate, target, POM field, and (in release mode) `.asc` signature. Enforced by `scripts/publication-inventory.py`. |
| `dokka/` | Reference-documentation project (Dokka). A **sibling** project on purpose (see *Classloader isolation*). Its full HTML site is published to GitHub Pages; each `-javadoc.jar` carries only a lightweight overview that links to it (deliberately slim jars — the full site would bloat the bundle to gigabytes). |
| `sbom/` | CycloneDX SBOM project. Also a sibling, for the same reason. |
| `consumers/` | A standalone Gradle build that resolves the **published coordinates** (never `project(":sdk")`) on JVM, web, Native, Apple, and Android. |

## The three commands

```bash
# 1. Rehearsal — stage → inventory → consumer matrix → SBOM → bundle, credential-free.
bash scripts/release-rehearsal.sh

# 2. Inventory check — the staged repository matches expected-artifacts.json exactly.
python3 scripts/publication-inventory.py check publication/expected-artifacts.json \
    build/publication-repository --version "$(python3 scripts/release-version.py get)"

# 3. Consumer matrix — the published coordinates resolve in isolated consumers.
bash scripts/consumer-matrix.sh --repo build/publication-repository
```

Stage into an inspectable local repository directly with:

```bash
./gradlew publishAllPublicationsToIsolatedRepository \
    --init-script publication/isolated-repository.init.gradle.kts \
    -PpublicationRepository=build/publication-repository
```

## Classloader isolation (the load-bearing rule)

Dokka and CycloneDX both bundle Jackson. Applying either to `:sdk` (or the root project) puts that
Jackson on the same classloader as the kotlin-sdkgen YAML parser, and `generateOpenrouterSdk` then
fails with `NoSuchMethodError: YAMLParser._updateToken`. Therefore:

- **Never** apply `org.jetbrains.dokka`, `org.cyclonedx.bom`, or any third-party publishing plugin to
  `:sdk`, `:openrouter-kotlin-testing`, or the root `plugins {}` block.
- Documentation and SBOM generation live in their own sibling projects (`publication/dokka`,
  `publication/sbom`), which never apply the sdkgen plugin.
- The acceptance test for each sibling tool is that `generateOpenrouterSdk --rerun-tasks` still passes
  in the *same* Gradle invocation as the tool.
