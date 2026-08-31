#!/usr/bin/env python3
"""Layered compatibility report over a before/after snapshot pair.

Seven layers — OpenAPI source, semantic, Kotlin source, JVM ABI, klib ABI, wire+behaviour, targets — each
classified EXPLICITLY. The overall release classification is the worst layer over

    patch < minor < breaking < unclassified

and anything the rules cannot classify (`unclassified`) FAILS the gate. Missing inputs make a layer
`unavailable` (reported, not silently "no change"); a change that cannot be evaluated becomes `unclassified`.

Exit code: 0 for patch|minor, 3 for breaking, 1 for unclassified. Python 3 stdlib only.

Each `--before DIR --after DIR` may hold any subset of:
  openapi.yaml, manifest.json, sources.txt (`<sha256> <path>` per generated file), sdk.api, sdk.klib.api,
  operation-coverage.md, oasdiff-breaking.json, oasdiff-changelog.json, sdkgen-diff.json, tests.json.
"""
from __future__ import annotations

import argparse
import difflib
import json
import os
import re
from collections import namedtuple

Layer = namedtuple("Layer", ["name", "status", "detail"])
Result = namedtuple("Result", ["layers", "overall", "exit_code"])

# Severity ordering. `unavailable` is reported but does not gate (it ranks below patch).
ORDER = {"unavailable": -1, "patch": 0, "minor": 1, "breaking": 2, "unclassified": 3}
EXIT = {"patch": 0, "minor": 0, "breaking": 3, "unclassified": 1, "unavailable": 0}
MANIFEST_DIGESTS = ("declarationModelSha256", "effectiveContractSha256", "semanticModelSha256", "kotlinApiSha256")


def _read(directory: str, name: str) -> str | None:
    path = os.path.join(directory, name)
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        return f.read()


def _json(directory: str, name: str):
    text = _read(directory, name)
    if text is None:
        return None
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return None


def _added_removed(before: str, after: str) -> tuple[list[str], list[str]]:
    """Content lines added / removed (ignoring diff headers)."""
    diff = difflib.unified_diff(before.splitlines(), after.splitlines(), lineterm="")
    added, removed = [], []
    for line in diff:
        if line.startswith("+++") or line.startswith("---") or line.startswith("@@"):
            continue
        if line.startswith("+"):
            added.append(line[1:])
        elif line.startswith("-"):
            removed.append(line[1:])
    return added, removed


# -------------------------------------------------------------------------------------------------
# Layers
# -------------------------------------------------------------------------------------------------
def layer_openapi(before: str, after: str) -> Layer:
    breaking = _json(after, "oasdiff-breaking.json")
    changelog = _json(after, "oasdiff-changelog.json")
    b_spec, a_spec = _read(before, "openapi.yaml"), _read(after, "openapi.yaml")

    if breaking is None:
        # oasdiff unavailable: only classifiable if the spec bytes are known-equal.
        if b_spec is not None and a_spec is not None:
            return Layer("OpenAPI source", "patch" if b_spec == a_spec else "unclassified",
                         "oasdiff unavailable; " + ("spec bytes equal" if b_spec == a_spec else "spec bytes differ"))
        return Layer("OpenAPI source", "unavailable", "oasdiff report absent")
    entries = breaking if isinstance(breaking, list) else breaking.get("breaking", []) or []
    if entries:
        levels = sorted({str(e.get("level", "")) for e in entries if isinstance(e, dict)})
        return Layer("OpenAPI source", "breaking", f"{len(entries)} breaking entr(ies) [{','.join(levels) or '?'}]")
    cl = changelog if isinstance(changelog, list) else (changelog or {}).get("changes", []) or []
    non_doc = [e for e in cl if not (isinstance(e, dict) and str(e.get("level", "")).upper() == "DEBUG")]
    if non_doc:
        return Layer("OpenAPI source", "minor", f"{len(non_doc)} changelog entr(ies), none breaking")
    return Layer("OpenAPI source", "patch", "no breaking or changelog entries")


def layer_semantic(before: str, after: str) -> Layer:
    b, a = _json(before, "manifest.json"), _json(after, "manifest.json")
    if b is None or a is None:
        return Layer("Semantic", "unavailable", "manifest.json missing on a side")
    if all(b.get(k) == a.get(k) for k in MANIFEST_DIGESTS):
        return Layer("Semantic", "patch", "all four generation digests equal")
    diff = _json(after, "sdkgen-diff.json")
    if diff is None:
        return Layer("Semantic", "unclassified", "digests changed but no sdkgen-diff to classify")
    impact = str(diff.get("apiImpact", "")).lower()
    if impact in ("none", "non-breaking"):
        return Layer("Semantic", "minor", f"digests changed; apiImpact={impact}")
    if impact == "breaking":
        return Layer("Semantic", "breaking", "apiImpact=breaking")
    return Layer("Semantic", "unclassified", f"apiImpact={impact or 'unknown'}")


def _sources_map(text: str) -> dict[str, str]:
    out = {}
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split(None, 1)
        if len(parts) == 2:
            out[parts[1].strip()] = parts[0].strip()
    return out


def layer_kotlin_source(before: str, after: str) -> Layer:
    b, a = _read(before, "sources.txt"), _read(after, "sources.txt")
    if b is None or a is None:
        return Layer("Kotlin source", "unavailable", "sources.txt missing on a side")
    bm, am = _sources_map(b), _sources_map(a)
    removed = sorted(set(bm) - set(am))
    added = sorted(set(am) - set(bm))
    changed = [p for p in set(bm) & set(am) if bm[p] != am[p]]
    if removed:
        return Layer("Kotlin source", "breaking", f"{len(removed)} generated file(s) removed")
    if added or changed:
        return Layer("Kotlin source", "minor", f"{len(added)} added, {len(changed)} changed, 0 removed")
    return Layer("Kotlin source", "patch", "generated file set and contents unchanged")


def _abi_layer(name: str, before: str, after: str, filename: str) -> Layer:
    b, a = _read(before, filename), _read(after, filename)
    if b is None and a is None:
        return Layer(name, "unavailable", f"{filename} absent on both sides")
    if b is None or a is None:
        return Layer(name, "unclassified", f"{filename} present on only one side")
    if b == a:
        return Layer(name, "patch", "ABI unchanged")
    added, removed = _added_removed(b, a)
    if removed:
        return Layer(name, "breaking", f"{len(removed)} ABI line(s) removed")
    return Layer(name, "minor", f"{len(added)} ABI line(s) added, 0 removed")


def layer_jvm_abi(before: str, after: str) -> Layer:
    return _abi_layer("JVM ABI", before, after, "sdk.api")


def layer_klib_abi(before: str, after: str) -> Layer:
    return _abi_layer("klib ABI", before, after, "sdk.klib.api")


def _targets(text: str) -> set[str] | None:
    m = re.search(r"Targets:\s*\[([^\]]*)\]", text)
    if not m:
        return None
    return {t.strip() for t in m.group(1).split(",") if t.strip()}


def layer_targets(before: str, after: str) -> Layer:
    b, a = _read(before, "sdk.klib.api"), _read(after, "sdk.klib.api")
    if b is None or a is None:
        return Layer("Targets", "unavailable", "klib dump missing on a side")
    bt, at = _targets(b), _targets(a)
    if bt is None or at is None:
        return Layer("Targets", "unclassified", "no `Targets:` header in a klib dump")
    if bt == at:
        return Layer("Targets", "patch", f"{len(at)} target(s), unchanged")
    if bt - at:
        return Layer("Targets", "breaking", f"target(s) removed: {sorted(bt - at)}")
    return Layer("Targets", "minor", f"target(s) added: {sorted(at - bt)}")


def layer_wire(before: str, after: str) -> Layer:
    t = _json(after, "tests.json")
    if t is None:
        return Layer("Wire + behaviour", "unavailable", "tests.json absent")
    jvm = str(t.get("jvmTest", "unavailable")).lower()
    goldens = str(t.get("goldens", "unavailable")).lower()
    if "failed" in (jvm, goldens):
        return Layer("Wire + behaviour", "breaking", f"jvmTest={jvm}, goldens={goldens}")
    if jvm == "passed" and goldens == "passed":
        return Layer("Wire + behaviour", "patch", "jvmTest and goldens passed")
    return Layer("Wire + behaviour", "unclassified", f"jvmTest={jvm}, goldens={goldens}")


LAYERS = (layer_openapi, layer_semantic, layer_kotlin_source, layer_jvm_abi, layer_klib_abi, layer_targets, layer_wire)


def classify(before: str, after: str) -> Result:
    layers = [fn(before, after) for fn in LAYERS]
    gating = [l.status for l in layers if l.status != "unavailable"]
    overall = max(gating, key=lambda s: ORDER[s]) if gating else "unavailable"
    return Result(layers, overall, EXIT[overall])


def render(result: Result) -> str:
    lines = ["# Compatibility report", "", "| Layer | Classification | Detail |", "| --- | --- | --- |"]
    for l in result.layers:
        lines.append(f"| {l.name} | {l.status} | {l.detail} |")
    lines += ["", f"**Release classification: {result.overall}** (exit {result.exit_code})", ""]
    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description="Layered compatibility report (fail on unclassified).")
    ap.add_argument("--before", required=True)
    ap.add_argument("--after", required=True)
    ap.add_argument("--out")
    ap.add_argument("--json", dest="as_json", action="store_true")
    args = ap.parse_args()
    result = classify(args.before, args.after)
    if args.as_json:
        print(json.dumps({"overall": result.overall, "exit": result.exit_code,
                          "layers": [l._asdict() for l in result.layers]}, indent=2))
    md = render(result)
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(md)
        print(f"Wrote {args.out}: {result.overall} (exit {result.exit_code})")
    elif not args.as_json:
        print(md)
    return result.exit_code


if __name__ == "__main__":
    raise SystemExit(main())
