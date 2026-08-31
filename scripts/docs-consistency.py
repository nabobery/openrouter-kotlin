#!/usr/bin/env python3
"""docs-consistency.py — the docs-vs-code consistency gate.

Four invariants, each independently testable, so a document can never silently disagree with the code:

  1. targets   the KMP targets declared in `sdk/build.gradle.kts` are exactly the target rows of
               `docs/target-support.md`, `docs/adr/0007-final-target-tiers-for-1-0.md`, and `README.md` (as a set).
  2. defaults  documented default values match the source constants (base URL, RetryPolicy defaults, download
               bound, User-Agent product token) in every doc that quotes them — a small manifest lists which doc
               quotes which value, and the expected value is read from the source constant, not hard-coded.
  3. pins      `spec/pin.json` sha256 == `spec/sdkgen.yaml` source sha256 == the current snapshot manifest source
               sha256 (when generated).
  4. coverage  the coverage totals line in `README.md` ("N of M operations") matches
               `docs/coverage/operation-coverage.md`.

`check` prints `file: expected X, found Y` lines and exits 1 on any violation. Python 3 stdlib only.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

# The canonical target vocabulary (code and every doc must agree on this set).
TARGET_VOCABULARY = {
    "jvm", "android", "js",
    "iosArm64", "iosSimulatorArm64", "iosX64",
    "macosArm64", "macosX64", "linuxX64", "linuxArm64", "mingwX64",
}
# How each target is detected in the KMP build script (a factory call, `js { }`, or the imperative android target).
_CODE_TARGET_PATTERNS = {
    "jvm": r"\bjvm\s*[({]",
    "js": r"\bjs\s*[({]",
    "iosArm64": r"\biosArm64\s*\(",
    "iosSimulatorArm64": r"\biosSimulatorArm64\s*\(",
    "iosX64": r"\biosX64\s*\(",
    "macosArm64": r"\bmacosArm64\s*\(",
    "macosX64": r"\bmacosX64\s*\(",
    "linuxX64": r"\blinuxX64\s*\(",
    "linuxArm64": r"\blinuxArm64\s*\(",
    "mingwX64": r"\bmingwX64\s*\(",
    "android": r"androidLibrary|android\.kotlin\.multiplatform|multiplatform\.library|androidTarget",
}
_ADR_TARGETS = "docs/adr/0007-final-target-tiers-for-1-0.md"


def _read(root: pathlib.Path, rel: str) -> str:
    return (root / rel).read_text()


def _targets_in_code(build_script: str) -> set[str]:
    return {name for name, pat in _CODE_TARGET_PATTERNS.items() if re.search(pat, build_script)}


def _targets_in_doc(doc_text: str) -> set[str]:
    """Every vocabulary token that appears as an exact backtick token (`jvm`, `iosArm64`, …) in the doc."""
    found = set(re.findall(r"`([A-Za-z0-9]+)`", doc_text))
    return {t for t in found if t in TARGET_VOCABULARY}


def _tier_table_rows(doc_text: str) -> list[str]:
    """The body rows of the doc's target/tier table — the one table whose header names both a tier and a target
    column. Restricting to this table means a target mentioned only in prose (or in some other table) never counts
    as "documented", which is what lets the tier check below be meaningful."""
    rows: list[str] = []
    in_table = False
    for line in doc_text.splitlines():
        s = line.strip()
        if not in_table:
            low = s.lower()
            if s.startswith("|") and "tier" in low and "target" in low:
                in_table = True
            continue
        if not s.startswith("|"):
            break  # table ended
        if set(s) <= set("-:| "):
            continue  # separator row
        rows.append(s)
    return rows


def _doc_target_tiers(doc_text: str) -> dict[str, int]:
    """Map each vocabulary target named in the tier table to its tier number. Handles both per-target-row tables
    (`| `jvm` | 1 | … |`) and tier-grouped tables (`| 1 | `jvm`, `android`, … | … |`): backtick spans are removed
    before the tier integer is read, so a target name that contains digits (`iosX64`, `mingwX64`) is never
    mistaken for the tier."""
    tiers: dict[str, int] = {}
    for row in _tier_table_rows(doc_text):
        targets = {t for t in re.findall(r"`([A-Za-z0-9]+)`", row) if t in TARGET_VOCABULARY}
        if not targets:
            continue
        without_ticks = re.sub(r"`[^`]*`", "", row)
        m = re.search(r"\b(\d+)\b", without_ticks)
        if m is None:
            continue
        tier = int(m.group(1))
        for t in targets:
            tiers[t] = tier
    return tiers


def target_rules(root: pathlib.Path) -> list[str]:
    code = _targets_in_code(_read(root, "sdk/build.gradle.kts"))
    failures: list[str] = []
    if code != TARGET_VOCABULARY:
        failures.append(
            f"sdk/build.gradle.kts: declared targets {sorted(code)} != expected {sorted(TARGET_VOCABULARY)} "
            "(update TARGET_VOCABULARY and the docs together if the target set really changed)"
        )
    docs = ("docs/target-support.md", _ADR_TARGETS, "README.md")
    doc_tiers: dict[str, dict[str, int]] = {}
    for doc in docs:
        tiers = _doc_target_tiers(_read(root, doc))
        doc_tiers[doc] = tiers
        missing = code - set(tiers)
        extra = set(tiers) - code
        if missing or extra:
            failures.append(
                f"{doc}: tier-table targets disagree with code — missing {sorted(missing)}, extra {sorted(extra)}"
            )
    # Every target's tier must agree across the docs that list it (the ADR is the decision; the others mirror it).
    for tgt in sorted(code):
        assigned = {doc: tiers[tgt] for doc, tiers in doc_tiers.items() if tgt in tiers}
        if len(set(assigned.values())) > 1:
            detail = ", ".join(f"{doc.split('/')[-1]}={tier}" for doc, tier in sorted(assigned.items()))
            failures.append(f"tier mismatch for `{tgt}`: {detail}")
    return failures


def default_rules(root: pathlib.Path) -> list[str]:
    retry = _read(root, "sdk/src/commonMain/kotlin/com/nabobery/openrouter/RetryPolicy.kt")
    bytestreams = _read(root, "sdk/src/commonMain/kotlin/com/nabobery/openrouter/io/ByteStreams.kt")
    root_kt = _read(root, "sdk/src/commonMain/kotlin/com/nabobery/openrouter/OpenRouterRoot.kt")

    # (rendered value, docs that quote it, the source-constant guard). If the guard is False the SOURCE changed, so
    # the checker AND the docs must be updated together — the gate then fails loudly rather than passing on a stale
    # expectation. If the guard is True, every listed doc must contain the rendered value.
    manifest: list[tuple[str, list[str], bool]] = [
        ("500 ms", ["docs/guides/how-to/configure-retries-and-deadlines.md"], "500.milliseconds" in retry),
        ("60 s", ["docs/guides/how-to/configure-retries-and-deadlines.md"], "60.seconds" in retry),
        (
            "429",
            [
                "docs/public-api-design.md",
                "docs/guides/how-to/configure-retries-and-deadlines.md",
                "README.md",
                "docs/parity/behaviors.json",
            ],
            "setOf(429)" in retry,
        ),
        ("64 MiB", ["docs/security-and-privacy.md"], "64L * 1024 * 1024" in bytestreams),
        (
            "https://openrouter.ai/api/v1",
            ["docs/parity/behaviors.json"],
            'DEFAULT_BASE_URL: String = "https://openrouter.ai/api/v1"' in root_kt,
        ),
        (
            "openrouter-kotlin/",
            ["docs/public-api-design.md", "docs/parity/behaviors.json"],
            'productToken = "openrouter-kotlin/$SDK_VERSION"' in root_kt,
        ),
    ]
    failures: list[str] = []
    for value, docs, source_ok in manifest:
        if not source_ok:
            failures.append(f"source constant for documented value '{value}' changed — update docs-consistency.py and the docs")
            continue
        for doc in docs:
            if value not in _read(root, doc):
                failures.append(f"{doc}: expected to quote the source default '{value}', but it is absent")
    return failures


def _pin_sha(root: pathlib.Path) -> str:
    return json.loads(_read(root, "spec/pin.json"))["sha256"]


def _sdkgen_source_sha(root: pathlib.Path) -> str | None:
    # The first top-level `sha256:` in sdkgen.yaml is the source digest (overlay digests are indented under `overlays:`).
    for line in _read(root, "spec/sdkgen.yaml").splitlines():
        m = re.match(r"^  sha256:\s*([0-9a-f]{64})\s*$", line)
        if m:
            return m.group(1)
    return None


def _snapshot_manifest_sha(root: pathlib.Path) -> str | None:
    # Resolve the CURRENT snapshot via the `sources` symlink the generator maintains, not an arbitrary
    # `.snapshots/*/manifest.json` glob — stale snapshots from earlier builds live alongside it and picking the
    # first would make the result depend on leftover build contents.
    sources = root / "sdk/build/generated/sdkgen/openrouter/sources"
    try:
        snapshot_dir = sources.resolve(strict=True)  # …/.snapshots/<address>/sources
    except (OSError, RuntimeError):
        return None
    manifest = snapshot_dir.parent / "manifest.json"
    if not manifest.is_file():
        return None
    # The source digest is the first sha256 the manifest records.
    shas = re.findall(r'"sha256"\s*:\s*"([0-9a-f]{64})"', manifest.read_text())
    return shas[0] if shas else None


def pin_rules(root: pathlib.Path) -> list[str]:
    failures: list[str] = []
    pin = _pin_sha(root)
    sdkgen = _sdkgen_source_sha(root)
    if sdkgen != pin:
        failures.append(f"spec/sdkgen.yaml: source sha256 {sdkgen} != spec/pin.json sha256 {pin}")
    manifest = _snapshot_manifest_sha(root)
    if manifest is not None and manifest != pin:
        failures.append(f"snapshot manifest source sha256 {manifest} != spec/pin.json sha256 {pin}")
    return failures


def coverage_rules(root: pathlib.Path) -> list[str]:
    cov = _read(root, "docs/coverage/operation-coverage.md")
    spec_m = re.search(r"Spec operations.*?\*\*(\d+)\*\*", cov)
    gen_m = re.search(r"Generated operations:\s*\*\*(\d+)\*\*", cov)
    if not spec_m or not gen_m:
        return ["docs/coverage/operation-coverage.md: could not parse spec/generated totals"]
    spec, gen = spec_m.group(1), gen_m.group(1)
    readme = _read(root, "README.md")
    expected = f"{gen} of {spec} operations"
    if expected not in readme:
        return [f"README.md: expected coverage totals '{expected}' (from the coverage dashboard), but it is absent"]
    return []


def run(root: pathlib.Path) -> list[str]:
    failures: list[str] = []
    for rule in (target_rules, default_rules, pin_rules, coverage_rules):
        failures.extend(rule(root))
    return failures


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["check"])
    parser.add_argument("--root", default=".")
    args = parser.parse_args(argv)
    failures = run(pathlib.Path(args.root).resolve())
    if failures:
        print("docs-consistency: FAIL", file=sys.stderr)
        for f in failures:
            print(f"  {f}", file=sys.stderr)
        return 1
    print("docs-consistency: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
