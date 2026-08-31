#!/usr/bin/env python3
"""Generate docs/parity/official-sdk-parity.md from pinned official Speakeasy SDK inventories.

`fetch` (network; the scheduled job only) reads each pinned commit's `.speakeasy/gen.lock` + `gen.yaml` and
writes a deterministic inventory under docs/parity/inventory/. `render` (offline; PR CI) builds the matrix from
the committed inventories + our coverage dashboard + the spec's `x-speakeasy-retries` + curated behaviour rows.
`--check` fails when the rendered file is stale. Python 3 stdlib only (regex over YAML, no YAML lib).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PARITY = os.path.join(ROOT, "docs", "parity")
PINS = os.path.join(PARITY, "pins.json")
INVENTORY = os.path.join(PARITY, "inventory")
BEHAVIORS = os.path.join(PARITY, "behaviors.json")
OUT = os.path.join(PARITY, "official-sdk-parity.md")
COVERAGE = os.path.join(ROOT, "docs", "coverage", "operation-coverage.md")
SPEC = os.path.join(ROOT, "spec", "openapi.yaml")

SDKS = ("typescript", "python", "go")
_CONFIG_KEYS = ("version", "envVarPrefix", "enumFormat",
                "forwardCompatibleEnumsByDefault", "forwardCompatibleUnionsByDefault")


def operation_ids(gen_lock: str) -> set[str]:
    """The operationId set is the 2-space-indented keys of the top-level `examples:` block."""
    ops: set[str] = set()
    in_examples = False
    for line in gen_lock.splitlines():
        if re.match(r"^examples:\s*$", line):
            in_examples = True
            continue
        if in_examples:
            if re.match(r"^[A-Za-z0-9_]", line):  # next top-level key ends the block
                break
            m = re.match(r"^  ([A-Za-z][A-Za-z0-9_]*):\s*$", line)
            if m:
                ops.add(m.group(1))
    return ops


def config_facts(gen_yaml: str) -> dict[str, str]:
    facts: dict[str, str] = {}
    for key in _CONFIG_KEYS:
        m = re.search(rf"^\s*{re.escape(key)}:\s*(\S+)\s*$", gen_yaml, re.MULTILINE)
        if m:
            facts[key] = m.group(1).strip().strip("'\"")
    return facts


def coverage_sets(coverage_md: str) -> tuple[set[str], set[str]]:
    generated: set[str] = set()
    for line in coverage_md.splitlines():
        m = re.match(r"^\|\s*[^|]+\|\s*([A-Za-z][A-Za-z0-9_]*)\s*\|\s*(?:GET|POST|PUT|PATCH|DELETE)\b", line)
        if m:
            generated.add(m.group(1))
    waived: set[str] = set()
    m = re.search(r"accepted waivers\).*?—\s*(.+)$", coverage_md, re.MULTILINE)
    if m:
        for tok in re.split(r"[,\s]+", m.group(1).strip()):
            if re.match(r"^[A-Za-z][A-Za-z0-9_]*$", tok) and tok.lower() != "none":
                waived.add(tok)
    return generated, waived


def is_fresh(current: str, rendered: str) -> bool:
    return current == rendered


# -------------------------------------------------------------------------------------------------
def _http_get(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "openrouter-kotlin-parity"})
    with urllib.request.urlopen(req, timeout=60) as resp:  # noqa: S310 (fixed https raw.githubusercontent URLs)
        return resp.read().decode("utf-8")


def cmd_fetch() -> int:
    with open(PINS, encoding="utf-8") as f:
        pins = json.load(f)
    os.makedirs(INVENTORY, exist_ok=True)
    for sdk in SDKS:
        pin = pins[sdk]
        repo, commit = pin["repo"], pin["commit"]
        base = f"https://raw.githubusercontent.com/{repo}/{commit}/.speakeasy"
        gen_lock = _http_get(f"{base}/gen.lock")
        gen_yaml = _http_get(f"{base}/gen.yaml")
        inv = {
            "repo": repo,
            "commit": commit,
            "sdkVersion": pin.get("sdkVersion"),
            "operationIds": sorted(operation_ids(gen_lock)),
            "config": config_facts(gen_yaml),
        }
        path = os.path.join(INVENTORY, f"{sdk}.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(inv, f, indent=2, sort_keys=True)
            f.write("\n")
        print(f"wrote {path}: {len(inv['operationIds'])} operations")
        # Report how far behind the pin is from the repo's current main HEAD.
        try:
            head = json.loads(_http_get(f"https://api.github.com/repos/{repo}/commits/main"))["sha"]
            print(f"  pin {commit[:8]} vs current main {head[:8]}: {'up to date' if head == commit else 'BEHIND'}")
        except Exception as exc:  # network best-effort
            print(f"  (could not resolve current main: {exc})")
    return 0


def _spec_retries() -> dict[str, str]:
    facts: dict[str, str] = {}
    try:
        with open(SPEC, encoding="utf-8") as f:
            text = f.read()
    except FileNotFoundError:
        return facts
    m = re.search(r"x-speakeasy-retries:(.*?)(?:\n[A-Za-z])", text, re.S)
    block = m.group(1) if m else ""
    for key in ("initialInterval", "maxInterval", "exponent", "maxElapsedTime", "retryConnectionErrors"):
        mk = re.search(rf"{key}:\s*([0-9.]+|true|false)", block)
        if mk:
            facts[key] = mk.group(1)
    return facts


def render(inventories: dict, generated: set[str], waived: set[str], behaviors: list[dict],
           retries: dict[str, str]) -> str:
    all_ops = set(generated) | set(waived)
    for inv in inventories.values():
        all_ops |= set(inv.get("operationIds", []))

    def mark(op: str, present: bool, waived_here: bool = False) -> str:
        if waived_here:
            return "waived"
        return "✅" if present else "—"

    lines = ["# Official SDK parity matrix", "",
             "Generated by `python3 scripts/parity-matrix.py render` from the pinned official Speakeasy inventories",
             "(`docs/parity/inventory/`), our coverage dashboard, and the curated behaviour rows in",
             "`docs/parity/behaviors.json`. Do not edit by hand. `--check` gates freshness in CI.", ""]

    # Pins
    lines += ["## Pinned inventories", "", "| SDK | version | commit |", "| --- | --- | --- |"]
    for sdk in SDKS:
        inv = inventories.get(sdk, {})
        lines.append(f"| {sdk} | {inv.get('sdkVersion', '?')} | `{str(inv.get('commit', '?'))[:8]}` |")
    lines.append("")

    # Operation matrix
    kt_total = len(generated)
    lines += ["## Operation matrix", "",
              "| operationId | Kotlin | TypeScript | Python | Go |", "| --- | --- | --- | --- | --- |"]
    ts = set(inventories.get("typescript", {}).get("operationIds", []))
    py = set(inventories.get("python", {}).get("operationIds", []))
    go = set(inventories.get("go", {}).get("operationIds", []))
    for op in sorted(all_ops):
        kt = mark(op, op in generated, op in waived)
        lines.append(f"| {op} | {kt} | {mark(op, op in ts)} | {mark(op, op in py)} | {mark(op, op in go)} |")
    lines.append(f"| **totals** | **{kt_total} generated / {len(waived)} waived** | "
                 f"**{len(ts)}** | **{len(py)}** | **{len(go)}** |")
    lines.append("")

    # Behaviour table
    lines += ["## Defaults and behaviour", "",
              "| aspect | official (TS / Py / Go) | Kotlin | parity | evidence |",
              "| --- | --- | --- | --- | --- |"]
    for b in behaviors:
        official = " / ".join([str(b.get("ts", "?")), str(b.get("py", "?")), str(b.get("go", "?"))])
        lines.append(f"| {b['aspect']} | {official} | {b.get('kotlin', '?')} | {b.get('status', '?')} | "
                     f"{b.get('evidence', '')} |")
    lines.append("")

    # Retry facts from the spec
    if retries:
        lines += ["## Official retry defaults (spec `x-speakeasy-retries`)", "",
                  "```", *[f"{k}: {v}" for k, v in sorted(retries.items())], "```", ""]
    return "\n".join(lines).rstrip() + "\n"


def _load_render_inputs():
    inventories = {}
    for sdk in SDKS:
        path = os.path.join(INVENTORY, f"{sdk}.json")
        with open(path, encoding="utf-8") as f:
            inventories[sdk] = json.load(f)
    with open(COVERAGE, encoding="utf-8") as f:
        generated, waived = coverage_sets(f.read())
    with open(BEHAVIORS, encoding="utf-8") as f:
        behaviors = json.load(f)
    return inventories, generated, waived, behaviors, _spec_retries()


def cmd_render(check: bool) -> int:
    inventories, generated, waived, behaviors, retries = _load_render_inputs()
    rendered = render(inventories, generated, waived, behaviors, retries)
    if check:
        current = ""
        if os.path.exists(OUT):
            with open(OUT, encoding="utf-8") as f:
                current = f.read()
        if not is_fresh(current, rendered):
            print(f"stale {OUT} — run `python3 scripts/parity-matrix.py render`", file=sys.stderr)
            return 1
        print(f"OK: {OUT} is current")
        return 0
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(rendered)
    print(f"wrote {OUT}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Official SDK parity matrix.")
    sub = ap.add_subparsers(dest="command", required=True)
    sub.add_parser("fetch")
    rp = sub.add_parser("render")
    rp.add_argument("--check", action="store_true")
    args = ap.parse_args()
    if args.command == "fetch":
        return cmd_fetch()
    return cmd_render(args.check)


if __name__ == "__main__":
    raise SystemExit(main())
