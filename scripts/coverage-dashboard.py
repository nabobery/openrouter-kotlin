#!/usr/bin/env python3
"""Generate docs/coverage/operation-coverage.md from the generated clients, and enforce coverage invariants.

Parses every generated *Client.kt for its operations (operationId, method, path, pagination, streaming,
request-body kind), scans the *comment-stripped* curated sources / tests / samples for references to each
operation's Kotlin method name (recording which file is the evidence), reads the accepted waivers, and emits a
deterministic Markdown dashboard. Python 3 stdlib only.

This is a GATE, not only a freshness report. It exits non-zero when a hard invariant is violated:

  * generated and waived operation IDs exactly partition the spec operation IDs

CI runs the script (so a violated invariant fails the job) and then diffs the emitted Markdown
(`git diff --exit-code -- docs/coverage/operation-coverage.md`) so a stale dashboard also fails.

The "evidence" column is an honest heuristic: it names the first non-generated file whose (comment-stripped) text
references the operation's Kotlin method. A reference is not proof of execution or assertion — it is a pointer to
where coverage, if any, lives. Operations with no evidence file are surfaced as `—` so gaps are visible.
"""
from __future__ import annotations
import os
import re
import sys
import glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GEN = os.path.join(ROOT, "sdk", "build", "generated", "sdkgen", "openrouter", "sources", "com", "nabobery", "openrouter")
OUT = os.path.join(ROOT, "docs", "coverage", "operation-coverage.md")
SPEC = os.path.join(ROOT, "spec", "openapi.yaml")
SDKGEN_YAML = os.path.join(ROOT, "spec", "sdkgen.yaml")

META_RE = re.compile(
    r'OperationMetadata\(\s*operationId\s*=\s*"(?P<op>[^"]+)"\s*,\s*'
    r'method\s*=\s*"(?P<method>[^"]+)"\s*,\s*path\s*=\s*"(?P<path>[^"]+)"',
    re.S,
)

OPERATION_ID_RE = re.compile(r"^\s*operationId:\s*['\"]?([^'\"\s#]+)['\"]?\s*(?:#.*)?$")

_LINE_COMMENT = re.compile(r'//[^\n]*')
_BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.S)


def strip_comments(src: str) -> str:
    """Remove // line comments and /* */ block comments so a name mentioned only in prose does not count."""
    return _LINE_COMMENT.sub("", _BLOCK_COMMENT.sub("", src))


def spec_operations() -> list[str]:
    with open(SPEC, encoding="utf-8") as f:
        return [match.group(1) for line in f if (match := OPERATION_ID_RE.match(line))]


def accepted_waivers() -> list[str]:
    ops: list[str] = []
    with open(SDKGEN_YAML, encoding="utf-8") as f:
        for line in f:
            m = re.search(r'symbolId:\s*"operation:([^"]+)"', line)
            if m:
                ops.append(m.group(1))
    return ops


def inventory_errors(spec: list[str], generated: list[str], waived: list[str]) -> list[str]:
    """Return every violation of the generated/waived exact-partition invariant."""
    errors: list[str] = []
    for label, operations in (("spec", spec), ("generated", generated), ("waived", waived)):
        duplicates = sorted({operation for operation in operations if operations.count(operation) > 1})
        if duplicates:
            errors.append(f"duplicate {label} operation IDs: {', '.join(duplicates)}")

    spec_set = set(spec)
    generated_set = set(generated)
    waived_set = set(waived)
    overlap = sorted(generated_set & waived_set)
    missing = sorted(spec_set - generated_set - waived_set)
    extra_generated = sorted(generated_set - spec_set)
    extra_waived = sorted(waived_set - spec_set)
    if overlap:
        errors.append(f"operation IDs are both generated and waived: {', '.join(overlap)}")
    if missing:
        errors.append(f"spec operation IDs are missing: {', '.join(missing)}")
    if extra_generated:
        errors.append(f"generated operation IDs are not present in the spec: {', '.join(extra_generated)}")
    if extra_waived:
        errors.append(f"waived operation IDs are not present in the spec: {', '.join(extra_waived)}")
    return errors


def evidence_files() -> list[tuple[str, str]]:
    """(basename, comment-stripped text) for every non-generated curated/test/sample Kotlin source."""
    files: list[tuple[str, str]] = []
    for base in (
        os.path.join(ROOT, "sdk", "src", "commonMain"),
        os.path.join(ROOT, "sdk", "src", "commonTest"),
        os.path.join(ROOT, "sdk", "src", "engineTest"),
        os.path.join(ROOT, "sdk", "src", "jvmTest"),
        os.path.join(ROOT, "samples"),
    ):
        for path in glob.glob(os.path.join(base, "**", "*.kt"), recursive=True):
            with open(path, encoding="utf-8") as f:
                files.append((os.path.relpath(path, ROOT), strip_comments(f.read())))
    return files


def find_evidence(op: str, files: list[tuple[str, str]]) -> str | None:
    """First non-generated file (tests preferred) referencing `op` as a whole word, else None."""
    word = re.compile(r'\b' + re.escape(op) + r'\b')
    # Sort matches explicitly: glob/filesystem traversal order differs across hosts, and several tests may
    # reference the same operation. The dashboard must choose the same evidence pointer everywhere.
    hits = sorted(rel for rel, text in files if word.search(text))
    if not hits:
        return None
    # Prefer a Test file as the evidence pointer; else the first curated/sample reference.
    tests = [h for h in hits if "Test" in os.path.basename(h)]
    return os.path.basename(tests[0] if tests else hits[0])


def client_files() -> list[str]:
    return sorted(glob.glob(os.path.join(GEN, "*", "*Client.kt")))


def main() -> None:
    files = evidence_files()
    waivers = accepted_waivers()
    spec_ops = spec_operations()

    rows = []
    generated_ops: list[str] = []
    with_evidence = 0
    for path in client_files():
        resource = os.path.basename(os.path.dirname(path))
        with open(path, encoding="utf-8") as f:
            src = f.read()
        seen_ops: set[str] = set()
        for m in META_RE.finditer(src):
            op, method, spath = m.group("op"), m.group("method"), m.group("path")
            if op in seen_ops:  # an op's OperationMetadata literal can appear more than once per client
                continue
            seen_ops.add(op)
            generated_ops.append(op)
            paginated = f"fun {op}Pages(" in src or f"fun {op}Items(" in src
            streamed = f"fun {op}Stream(" in src
            body_kind = "multipart" if f"{op}.request" in src and "Multipart" in src else (
                "json" if f"{op}.request" in src else "none")
            evidence = find_evidence(op, files)
            if evidence:
                with_evidence += 1
            rows.append((resource, op, method, spath, body_kind, paginated, streamed, evidence))

    rows.sort(key=lambda r: (r[0], r[1]))

    # ---- Hard invariant: generated and waived IDs exactly partition the spec IDs. ----
    errors = inventory_errors(spec_ops, generated_ops, waivers)
    invariant_ok = not errors
    invariant_line = (
        f"generated ({len(generated_ops)}) and waived ({len(waivers)}) exactly partition spec ({len(spec_ops)})"
    )

    lines = []
    lines.append("# Operation coverage dashboard")
    lines.append("")
    lines.append("Generated by `python3 scripts/coverage-dashboard.py` from the generated clients under")
    lines.append("`sdk/build/generated/sdkgen/openrouter/sources`. Do not edit by hand. The script is a gate: it")
    lines.append("exits non-zero when a hard invariant is violated, and CI additionally diffs this file for freshness.")
    lines.append("")
    lines.append("## Invariants (enforced)")
    lines.append("")
    lines.append(f"- {'PASS' if invariant_ok else 'FAIL'}: {invariant_line} — no operation is silently dropped.")
    lines.append("")
    lines.append("## Totals")
    lines.append("")
    lines.append(f"- Spec operations (`operationId:` in `spec/openapi.yaml`): **{len(spec_ops)}**")
    lines.append(f"- Generated operations: **{len(generated_ops)}**")
    lines.append(f"- Omitted (accepted waivers): **{len(waivers)}** — {', '.join(waivers) or 'none'}")
    lines.append(f"- Generated operations with an evidence file (curated/test/sample reference): **{with_evidence}**")
    lines.append("")
    lines.append("The *evidence* column names the first non-generated file (a test where one exists) whose")
    lines.append("comment-stripped source references the operation. A reference points to where coverage lives; it is")
    lines.append("not by itself proof the operation was executed or asserted. `—` marks an operation with no reference.")
    lines.append("")
    lines.append("See `docs/coverage/exception-register.md` for every omitted or degraded capability.")
    lines.append("")
    lines.append("| resource | operationId | method | path | body | pagination | stream | evidence |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- | --- |")
    for resource, op, method, spath, body_kind, paginated, streamed, evidence in rows:
        lines.append(
            f"| {resource} | {op} | {method} | `{spath}` | {body_kind} | "
            f"{'yes' if paginated else 'no'} | {'yes' if streamed else 'no'} | "
            f"{evidence or '—'} |"
        )
    lines.append("")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print(f"Wrote {OUT}: {len(generated_ops)} generated operations across {len(client_files())} clients; "
          f"{with_evidence} with an evidence file.")

    if not invariant_ok:
        for error in errors:
            print(f"INVARIANT VIOLATED: {error}", file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
