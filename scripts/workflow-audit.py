#!/usr/bin/env python3
"""Machine-checked secret-isolation / least-privilege gate for .github/workflows/*.yml.

This is a SECURITY gate and it FAILS CLOSED: any construct the line-oriented YAML-subset parser
cannot interpret (anchors/aliases, tags, `%` directives, tab indentation, unbalanced quotes) is a
violation, never a silent pass. It deliberately does not use PyYAML — a full YAML engine would accept
constructs this policy has never reasoned about. Python 3 stdlib only, mirroring coverage-dashboard.py.

Rules enforced (each keyed by a short rule name in the `file:line: rule: message` output):
  pin              every `uses:` pins a 40-hex commit SHA (never a mutable tag).
  trigger          `pull_request_target` is banned anywhere.
  permissions      the workflow declares top-level `permissions: { contents: read }` and nothing wider.
  secret           a job may reference only the secrets allowlisted for `<workflow>/<job>` in the policy.
  exec             every non-comment `run:` line in a repository-write job must be listed verbatim in
                   `writeJobAllowedCommands`; arbitrary shell is denied rather than partially parsed.
  persist          every `actions/checkout` step sets `persist-credentials: false`.
  scheduled-secret a `schedule`-triggered workflow using any secret other than GITHUB_TOKEN must be listed
                   in the policy's `scheduledSecretUsers`.
  interpolation    a `${{ … }}` inside a `run:` block is banned unless it is `${{ secrets.* }}` or
                   `${{ steps.*.outputs.* }}` (other context values must reach the shell through `env:`).
  parse            fail-closed: the file used YAML the subset parser does not support.

Usage:
  workflow-audit.py check [--dir .github/workflows] [--policy docs/security/workflow-policy.json]
  workflow-audit.py report [--out docs/security/secret-isolation-report.md] [--check]
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import sys
from collections import namedtuple

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORKFLOW_DIR = os.path.join(ROOT, ".github", "workflows")
POLICY_PATH = os.path.join(ROOT, "docs", "security", "workflow-policy.json")
REPORT_PATH = os.path.join(ROOT, "docs", "security", "secret-isolation-report.md")
REPORT_START = "<!-- workflow-audit:start -->"
REPORT_END = "<!-- workflow-audit:end -->"

Node = namedtuple("Node", ["value", "line"])  # value: dict[str, Node] | list[Node] | str ; line is 0-indexed
Violation = namedtuple("Violation", ["line", "rule", "message"])

SECRET_RE = re.compile(r"secrets\.([A-Za-z_][A-Za-z0-9_-]*)")
INTERP_RE = re.compile(r"\$\{\{(.*?)\}\}", re.S)
SHA_RE = re.compile(r"@[0-9a-fA-F]{40}(?:$|\s|#)")
BLOCK_SCALAR_RE = re.compile(r"^[|>][+-]?\d*$")


class ParseError(Exception):
    """Raised for any YAML construct outside the supported subset — reported as a `parse` violation."""


# --------------------------------------------------------------------------------------------------
# Line-oriented YAML-subset parser (indentation + `key: value` + block scalars + simple flow).
# --------------------------------------------------------------------------------------------------
class Parser:
    def __init__(self, text: str):
        self.lines = text.split("\n")
        self.n = len(self.lines)
        self.i = 0

    def parse(self) -> Node:
        # `%` directives are unsupported (they change YAML semantics we do not model).
        for idx, raw in enumerate(self.lines):
            if raw.startswith("%"):
                raise ParseError(f"line {idx + 1}: YAML directive not supported")
        node = self.parse_node(0)
        # Fail closed: the top-level node must consume all significant input. If anything but blank/comment/doc
        # markers remains, we parsed only a prefix (e.g. an unsupported bare scalar caused the top mapping to stop
        # early), which would let later triggers/permissions/jobs be silently ignored.
        while self.i < self.n and self._is_skip(self.lines[self.i]):
            self.i += 1
        if self.i < self.n:
            raise ParseError(f"line {self.i + 1}: unexpected content after the top-level node (parsed only a prefix)")
        return node if node is not None else Node({}, 0)

    def _indent(self, raw: str, idx: int) -> tuple[int, str]:
        i = 0
        while i < len(raw) and raw[i] in " \t":
            if raw[i] == "\t":
                raise ParseError(f"line {idx + 1}: tab in indentation")
            i += 1
        return i, raw[i:]

    @staticmethod
    def _is_skip(raw: str) -> bool:
        s = raw.strip()
        return s == "" or s.startswith("#") or s == "---" or s == "..."

    def _peek(self):
        j = self.i
        while j < self.n and self._is_skip(self.lines[j]):
            j += 1
        if j >= self.n:
            return None
        col, rest = self._indent(self.lines[j], j)
        return col, self._strip_comment(rest), j

    def _advance_to(self, j: int):
        self.i = j + 1

    @staticmethod
    def _strip_comment(s: str) -> str:
        out, in_s, in_d = [], False, False
        for i, c in enumerate(s):
            if c == "'" and not in_d:
                in_s = not in_s
            elif c == '"' and not in_s:
                in_d = not in_d
            elif c == "#" and not in_s and not in_d and (i == 0 or s[i - 1] == " "):
                break
            out.append(c)
        return "".join(out).rstrip()

    def parse_node(self, indent: int) -> Node | None:
        peek = self._peek()
        if peek is None:
            return None
        col, content, _ = peek
        if col != indent:
            return None
        if content.startswith("- ") or content == "-":
            return self.parse_seq(indent)
        if self._colon_pos(content) >= 0:
            return self.parse_map(indent)
        # bare scalar line
        _, _, j = peek
        self._advance_to(j)
        return Node(self._scalar(content, j), j)

    def parse_seq(self, indent: int) -> Node:
        items: list[Node] = []
        first_line = None
        while True:
            peek = self._peek()
            if peek is None:
                break
            col, content, j = peek
            if col != indent or not (content.startswith("- ") or content == "-"):
                break
            if first_line is None:
                first_line = j
            # Rewrite the "- " into two spaces so the item body becomes a normal node at indent+2.
            after = content[1:]  # drop the leading '-'
            self.lines[j] = " " * (indent + 1) + after
            child = self.parse_node(indent + 2)
            items.append(child if child is not None else Node("", j))
        return Node(items, first_line if first_line is not None else self.i)

    def parse_map(self, indent: int) -> Node:
        mapping: dict[str, Node] = {}
        first_line = None
        while True:
            peek = self._peek()
            if peek is None:
                break
            col, content, j = peek
            if col != indent:
                break
            if content.startswith("- ") or content == "-":
                break
            cpos = self._colon_pos(content)
            if cpos < 0:
                break
            key = self._unquote(content[:cpos].strip(), j)
            if key == "<<":
                raise ParseError(f"line {j + 1}: merge key '<<' not supported")
            if key in mapping:
                # A duplicate mapping key (e.g. a second `jobs:` or `permissions:`) is ambiguous; GitHub rejects it.
                # Fail closed rather than let one definition silently shadow the other.
                raise ParseError(f"line {j + 1}: duplicate mapping key {key!r}")
            valstr = content[cpos + 1:].strip()
            if first_line is None:
                first_line = j
            self._advance_to(j)
            if valstr == "":
                nxt = self._peek()
                if nxt is not None and nxt[0] > indent:
                    child = self.parse_node(nxt[0])
                    mapping[key] = child if child is not None else Node("", j)
                else:
                    mapping[key] = Node("", j)
            elif BLOCK_SCALAR_RE.match(valstr):
                mapping[key] = self._block_scalar(indent)
            elif valstr[0] in "[{":
                mapping[key] = Node(self._flow(valstr, j), j)
            else:
                mapping[key] = Node(self._scalar(valstr, j), j)
        return Node(mapping, first_line if first_line is not None else self.i)

    def _block_scalar(self, indent: int) -> Node:
        start = self.i
        body: list[str] = []
        while self.i < self.n:
            raw = self.lines[self.i]
            if raw.strip() == "":
                body.append("")
                self.i += 1
                continue
            col, _ = self._indent(raw, self.i)
            if col <= indent:
                break
            body.append(raw)
            self.i += 1
        # Trim trailing blank lines that belong to the outer document, not the block.
        while body and body[-1] == "":
            body.pop()
        return Node("\n".join(body), start)

    def _flow(self, s: str, idx: int):
        s = s.strip()
        if s[0] == "[":
            if not s.endswith("]"):
                raise ParseError(f"line {idx + 1}: unbalanced flow sequence")
            inner = s[1:-1].strip()
            if inner == "":
                return []
            return [Node(self._scalar(part.strip(), idx), idx) for part in self._split_flow(inner)]
        if s[0] == "{":
            if not s.endswith("}"):
                raise ParseError(f"line {idx + 1}: unbalanced flow mapping")
            inner = s[1:-1].strip()
            out: dict[str, Node] = {}
            if inner == "":
                return out
            for part in self._split_flow(inner):
                cpos = self._colon_pos(part)
                if cpos < 0:
                    raise ParseError(f"line {idx + 1}: flow mapping entry without ':' -> {part!r}")
                k = self._unquote(part[:cpos].strip(), idx)
                out[k] = Node(self._scalar(part[cpos + 1:].strip(), idx), idx)
            return out
        raise ParseError(f"line {idx + 1}: unrecognised flow scalar")

    @staticmethod
    def _split_flow(inner: str) -> list[str]:
        parts, depth, buf, in_s, in_d = [], 0, [], False, False
        for c in inner:
            if c == "'" and not in_d:
                in_s = not in_s
            elif c == '"' and not in_s:
                in_d = not in_d
            if not in_s and not in_d:
                if c in "[{":
                    depth += 1
                elif c in "]}":
                    depth -= 1
                elif c == "," and depth == 0:
                    parts.append("".join(buf))
                    buf = []
                    continue
            buf.append(c)
        if "".join(buf).strip():
            parts.append("".join(buf))
        return parts

    @staticmethod
    def _colon_pos(content: str) -> int:
        """Index of the mapping colon (`: ` or trailing `:`) outside quotes/flow, else -1."""
        in_s = in_d = False
        depth = 0
        for i, c in enumerate(content):
            if c == "'" and not in_d:
                in_s = not in_s
            elif c == '"' and not in_s:
                in_d = not in_d
            elif not in_s and not in_d:
                if c in "[{":
                    depth += 1
                elif c in "]}":
                    depth -= 1
                elif c == ":" and depth == 0 and (i + 1 == len(content) or content[i + 1] == " "):
                    return i
        return -1

    def _scalar(self, s: str, idx: int) -> str:
        return self._unquote(s, idx)

    @staticmethod
    def _unquote(s: str, idx: int) -> str:
        s = s.strip()
        if s == "":
            return s
        if s[0] in "&*!":
            raise ParseError(f"line {idx + 1}: anchor/alias/tag not supported ({s[:12]!r})")
        if s[0] == '"':
            if len(s) < 2 or not s.endswith('"'):
                raise ParseError(f"line {idx + 1}: unbalanced double quote")
            return s[1:-1]
        if s[0] == "'":
            if len(s) < 2 or not s.endswith("'"):
                raise ParseError(f"line {idx + 1}: unbalanced single quote")
            return s[1:-1].replace("''", "'")
        return s


def parse_workflow(text: str) -> Node:
    return Parser(text).parse()


# --------------------------------------------------------------------------------------------------
# Structural helpers over the parsed tree.
# --------------------------------------------------------------------------------------------------
def _walk_strings(node: Node):
    v = node.value
    if isinstance(v, str):
        yield v
    elif isinstance(v, dict):
        for child in v.values():
            yield from _walk_strings(child)
    elif isinstance(v, list):
        for child in v:
            yield from _walk_strings(child)


def _trigger_names(tree: Node) -> list[str]:
    on = tree.value.get("on") if isinstance(tree.value, dict) else None
    if on is None:
        return []
    v = on.value
    if isinstance(v, dict):
        return list(v.keys())
    if isinstance(v, list):
        return [c.value for c in v if isinstance(c.value, str)]
    if isinstance(v, str) and v:
        return [v]
    return []


def _grants_write(perms: Node | None) -> bool:
    """Any write scope — used for the report's fork-safety column."""
    if perms is None:
        return False
    v = perms.value
    if isinstance(v, str):
        return v.strip() == "write-all"
    if isinstance(v, dict):
        return any(isinstance(c.value, str) and c.value.strip() == "write" for c in v.values())
    return False


# Rule (e)'s execution ban targets only the *code-injection* write scopes: a job that can push to a branch or a
# pull request is the vector by which executing untrusted input escalates into merged/published code. Benign
# writes (security-events, id-token, pages, packages) cannot inject code, so a security scanner (CodeQL,
# Scorecard) that needs `security-events: write` and legitimately builds the committed source with Gradle is not
# gated by the exec ban — only its ability to alter the repo would be. `write-all` grants contents write, so it
# counts. (Rule/ test changed together on 2026-08-30; see workflow_audit_test.py.)
_DANGEROUS_WRITE_SCOPES = ("contents", "pull-requests")


def _grants_dangerous_write(perms: Node | None) -> bool:
    if perms is None:
        return False
    v = perms.value
    if isinstance(v, str):
        return v.strip() == "write-all"
    if isinstance(v, dict):
        return any(scope in _DANGEROUS_WRITE_SCOPES and isinstance(node.value, str)
                   and node.value.strip() == "write" for scope, node in v.items())
    return False


def _top_permission_violation(tree: Node) -> str | None:
    perms = tree.value.get("permissions") if isinstance(tree.value, dict) else None
    if perms is None:
        return "top-level `permissions` missing (require `contents: read`)"
    v = perms.value
    if isinstance(v, str):
        return f"top-level `permissions: {v}` is wider than `contents: read`"
    if isinstance(v, dict):
        for scope, node in v.items():
            if scope != "contents" or (isinstance(node.value, str) and node.value.strip() != "read"):
                return "top-level `permissions` grants more than `contents: read`"
        return None
    return "top-level `permissions` is not a recognised mapping"


def _steps(job: Node) -> list[Node]:
    steps = job.value.get("steps") if isinstance(job.value, dict) else None
    if steps is None or not isinstance(steps.value, list):
        return []
    return steps.value


# --------------------------------------------------------------------------------------------------
# The audit itself.
# --------------------------------------------------------------------------------------------------
def audit_workflow(name: str, text: str, policy: dict) -> list[Violation]:
    try:
        tree = parse_workflow(text)
    except ParseError as exc:
        return [Violation(0, "parse", f"cannot parse — failing closed: {exc}")]

    if not isinstance(tree.value, dict):
        return [Violation(0, "parse", "cannot parse — failing closed: top level is not a mapping")]

    out: list[Violation] = []

    # Rule (c): top-level permissions.
    perm_msg = _top_permission_violation(tree)
    if perm_msg:
        pl = tree.value.get("permissions").line if "permissions" in tree.value else 0
        out.append(Violation(pl, "permissions", perm_msg))

    # Rule (b): pull_request_target trigger.
    triggers = _trigger_names(tree)
    if "pull_request_target" in triggers:
        on_line = tree.value["on"].line if "on" in tree.value else 0
        out.append(Violation(on_line, "trigger", "pull_request_target is banned"))

    # Rule (g): scheduled workflows using a non-GITHUB_TOKEN secret must be declared.
    all_secrets = {m for s in _walk_strings(tree) for m in SECRET_RE.findall(s)}
    non_default = all_secrets - {"GITHUB_TOKEN"}
    if "schedule" in triggers and non_default and name not in policy.get("scheduledSecretUsers", []):
        out.append(Violation(0, "scheduled-secret",
                             f"schedule-triggered workflow uses {sorted(non_default)} but is not in scheduledSecretUsers"))

    jobs = tree.value.get("jobs")
    if jobs is None or not isinstance(jobs.value, dict):
        return sorted(out)

    allow = policy.get("secretsAllowlist", {})
    allowed_cmds = policy.get("writeJobAllowedCommands", {})

    for job_id, job in jobs.value.items():
        job_key = f"{name}/{job_id}"
        # Rule (e) uses the code-injection write scopes only (see _grants_dangerous_write).
        write_capable = _grants_dangerous_write(job.value.get("permissions") if isinstance(job.value, dict) else None)

        # Rule (d): secrets referenced by this job must be allowlisted for it.
        job_secrets = {m for s in _walk_strings(job) for m in SECRET_RE.findall(s)}
        permitted = set(allow.get(job_key, []))
        for secret in sorted(job_secrets - permitted):
            out.append(Violation(job.line, "secret",
                                 f"job '{job_id}' references secrets.{secret} which is not allowlisted for {job_key}"))

        for step in _steps(job):
            if not isinstance(step.value, dict):
                continue
            uses = step.value.get("uses")
            run = step.value.get("run")

            # Rule (a): uses must pin a 40-hex SHA.
            if uses is not None and isinstance(uses.value, str):
                ref = uses.value.strip()
                if not ref.startswith("./") and not SHA_RE.search(ref):
                    out.append(Violation(uses.line, "pin", f"`uses: {ref}` is not pinned to a 40-hex commit SHA"))
                # Rule (f): checkout must disable credential persistence.
                if ref.startswith("actions/checkout@"):
                    with_node = step.value.get("with")
                    ok = False
                    if with_node is not None and isinstance(with_node.value, dict):
                        pc = with_node.value.get("persist-credentials")
                        ok = pc is not None and str(pc.value).strip() == "false"
                    if not ok:
                        out.append(Violation(uses.line, "persist",
                                             "actions/checkout must set persist-credentials: false"))

            # Rules (e) + (h): scan run content.
            if run is not None and isinstance(run.value, str) and run.value != "":
                base = run.line
                lines = run.value.split("\n")
                for offset, raw in enumerate(lines):
                    lineno = base + offset
                    stripped = raw.strip()
                    if stripped == "" or stripped.startswith("#"):
                        continue
                    # Rule (h): interpolation.
                    for expr in INTERP_RE.findall(raw):
                        e = expr.strip()
                        if not (e.startswith("secrets.") or re.match(r"^steps\..+\.outputs\..+", e)):
                            out.append(Violation(lineno, "interpolation",
                                                 f"`${{{{ {e} }}}}` in run: pass context values through env, not run"))
                    # Rule (e): write-capable jobs are default-deny. Shell is too expressive for a safe negative
                    # command list (absolute paths, wrappers, variables, substitutions, and functions all bypass
                    # first-token inspection), so every executable/structural line must match the policy verbatim.
                    if write_capable and stripped not in allowed_cmds.get(job_key, []):
                        out.append(Violation(
                            lineno,
                            "exec",
                            f"write-capable job '{job_id}' contains a run line not exactly allowlisted: {stripped!r}",
                        ))

    return sorted(set(out))


def _job_rows(name: str, text: str, policy: dict):
    try:
        tree = parse_workflow(text)
    except ParseError:
        return [(name, "(parse error)", "?", "?", "?", "?")]
    triggers = ",".join(_trigger_names(tree)) or "-"
    jobs = tree.value.get("jobs") if isinstance(tree.value, dict) else None
    rows = []
    if jobs is None or not isinstance(jobs.value, dict):
        return [(name, "-", "-", "-", triggers, "yes")]
    for job_id, job in jobs.value.items():
        perms = job.value.get("permissions") if isinstance(job.value, dict) else None
        perm_str = "write" if _grants_write(perms) else "read"
        secrets = sorted({m for s in _walk_strings(job) for m in SECRET_RE.findall(s)}) or ["-"]
        fork_safe = "no" if (_grants_write(perms) or secrets != ["-"]) else "yes"
        rows.append((name, job_id, perm_str, ",".join(secrets), triggers, fork_safe))
    return rows


def render(files: list[tuple[str, str]], policy: dict) -> str:
    lines = [
        "| workflow | job | write? | secrets | triggers | fork-safe? |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for name, text in sorted(files):
        for name_, job, perm, secrets, triggers, fork in _job_rows(name, text, policy):
            lines.append(f"| {name_} | {job} | {perm} | {secrets} | {triggers} | {fork} |")
    return "\n".join(lines) + "\n"


def _load_policy(path: str) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _workflow_files(directory: str) -> list[tuple[str, str]]:
    out = []
    for path in sorted(glob.glob(os.path.join(directory, "*.yml")) + glob.glob(os.path.join(directory, "*.yaml"))):
        with open(path, encoding="utf-8") as f:
            out.append((os.path.basename(path), f.read()))
    return out


def cmd_check(args) -> int:
    policy = _load_policy(args.policy)
    files = _workflow_files(args.dir)
    total = 0
    for name, text in files:
        for v in audit_workflow(name, text, policy):
            total += 1
            print(f"{name}:{v.line + 1}: {v.rule}: {v.message}")
    if total:
        print(f"\nworkflow-audit: {total} violation(s) across {len(files)} workflow(s)", file=sys.stderr)
        return 1
    print(f"OK: {len(files)} workflow(s) pass the secret-isolation / least-privilege audit")
    return 0


def cmd_report(args) -> int:
    policy = _load_policy(args.policy)
    files = _workflow_files(args.dir)
    table = render(files, policy)
    if args.check:
        if not os.path.exists(args.out):
            print(f"report missing: {args.out}", file=sys.stderr)
            return 1
        with open(args.out, encoding="utf-8") as f:
            current = f.read()
        if REPORT_START in current and REPORT_END in current:
            pre = current.split(REPORT_START)[0] + REPORT_START + "\n"
            post = REPORT_END + current.split(REPORT_END)[1]
            expected = pre + table + post
        else:
            expected = current  # nothing to compare; frame owns the file
        if expected != current:
            print(f"stale generated table in {args.out} — run `workflow-audit.py report`", file=sys.stderr)
            return 1
        print(f"OK: {args.out} table is current")
        return 0
    if os.path.exists(args.out):
        with open(args.out, encoding="utf-8") as f:
            current = f.read()
        if REPORT_START in current and REPORT_END in current:
            pre = current.split(REPORT_START)[0] + REPORT_START + "\n"
            post = REPORT_END + current.split(REPORT_END)[1]
            content = pre + table + post
        else:
            content = current
    else:
        content = f"{REPORT_START}\n{table}{REPORT_END}\n"
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"Wrote generated workflow table into {args.out}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Secret-isolation / least-privilege workflow audit (fail-closed).")
    ap.add_argument("--dir", default=WORKFLOW_DIR)
    ap.add_argument("--policy", default=POLICY_PATH)
    sub = ap.add_subparsers(dest="command", required=True)
    sub.add_parser("check")
    rep = sub.add_parser("report")
    rep.add_argument("--out", default=REPORT_PATH)
    rep.add_argument("--check", action="store_true")
    args = ap.parse_args()
    if args.command == "check":
        return cmd_check(args)
    return cmd_report(args)


if __name__ == "__main__":
    raise SystemExit(main())
