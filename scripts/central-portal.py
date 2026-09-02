#!/usr/bin/env python3
"""Maven Central Portal Publisher API v1 client (stdlib, tested, secret-safe).

Subcommands:
  upload <bundle> --name N [--publishing-type USER_MANAGED|AUTOMATIC] [--wait STATE] [--timeout S]
  status <id>
  publish <id> [--wait PUBLISHED] [--timeout S]
  drop <id>

Auth is `Authorization: Bearer <base64(username:password)>` from env MAVEN_CENTRAL_USERNAME / MAVEN_CENTRAL_PASSWORD.
The bearer token is NEVER printed — not in a log line, not in an exception. On `upload`, the deployment id is echoed
as `deployment-id=<id>` on stdout and appended to $GITHUB_OUTPUT as `deployment_id=<id>` when that file is set.
`--wait` polls `POST /status?id=…` every 10s until the deployment reaches (or passes) the requested state; a FAILED
deployment prints its `errors` and exits 2. Python 3 stdlib only.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://central.sonatype.com/api/v1/publisher"
POLL_INTERVAL_SECONDS = 10
_STATE_ORDER = ["PENDING", "VALIDATING", "VALIDATED", "PUBLISHING", "PUBLISHED"]
_MULTIPART_BOUNDARY = "----openrouterCentralBundleBoundary"

# Indirection points so tests can inject fakes without a network or real clock.
opener = urllib.request.urlopen
sleep = time.sleep
now = time.monotonic


class PortalError(RuntimeError):
    """A Portal API failure. Its message never contains the bearer token."""


def _auth_header() -> str:
    user = os.environ.get("MAVEN_CENTRAL_USERNAME")
    password = os.environ.get("MAVEN_CENTRAL_PASSWORD")
    if not user or not password:
        raise PortalError("MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD must be set")
    token = base64.b64encode(f"{user}:{password}".encode()).decode()
    return f"Bearer {token}"


def _request(method: str, url: str, *, data: bytes | None = None, content_type: str | None = None,
             timeout: float = 60.0) -> tuple[int, bytes]:
    headers = {"Authorization": _auth_header()}
    if content_type:
        headers["Content-Type"] = content_type
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with opener(request, timeout=timeout) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as exc:
        # Echo the status and body (never the request headers, which carry the token).
        body = exc.read().decode(errors="replace") if hasattr(exc, "read") else ""
        raise PortalError(f"{method} {_redact(url)} -> HTTP {exc.code}: {body[:500]}") from None


def _redact(url: str) -> str:
    # URLs never carry the token, but keep this seam so a future signed URL stays out of logs.
    return url


def _multipart(bundle: bytes, filename: str) -> tuple[bytes, str]:
    b = _MULTIPART_BOUNDARY
    body = (
        f"--{b}\r\n".encode()
        + f'Content-Disposition: form-data; name="bundle"; filename="{filename}"\r\n'.encode()
        + b"Content-Type: application/octet-stream\r\n\r\n"
        + bundle
        + f"\r\n--{b}--\r\n".encode()
    )
    return body, f"multipart/form-data; boundary={b}"


def upload(bundle_path: str, name: str, publishing_type: str = "USER_MANAGED") -> str:
    with open(bundle_path, "rb") as handle:
        data = handle.read()
    body, content_type = _multipart(data, os.path.basename(bundle_path))
    url = f"{BASE}/upload?name={urllib.parse.quote(name)}&publishingType={publishing_type}"
    status, response = _request("POST", url, data=body, content_type=content_type)
    deployment_id = response.decode().strip()
    if not deployment_id:
        raise PortalError("upload succeeded but returned no deployment id")
    return deployment_id


def deployment_state(deployment_id: str) -> tuple[str, list]:
    url = f"{BASE}/status?id={urllib.parse.quote(deployment_id)}"
    _, response = _request("POST", url)
    document = json.loads(response.decode() or "{}")
    return document.get("deploymentState", "UNKNOWN"), document.get("errors", [])


def wait_for(deployment_id: str, target: str, timeout: float) -> None:
    if target not in _STATE_ORDER:
        raise PortalError(f"unknown target state '{target}'")
    deadline = now() + timeout
    while True:
        state, errors = deployment_state(deployment_id)
        if state == "FAILED":
            raise PortalError(f"deployment {deployment_id} FAILED: {json.dumps(errors)}")
        if state in _STATE_ORDER and _STATE_ORDER.index(state) >= _STATE_ORDER.index(target):
            return
        if now() >= deadline:
            raise PortalError(f"timed out waiting for {deployment_id} to reach {target} (last state {state})")
        sleep(POLL_INTERVAL_SECONDS)


def publish(deployment_id: str) -> None:
    _request("POST", f"{BASE}/deployment/{urllib.parse.quote(deployment_id)}")


def drop(deployment_id: str) -> None:
    _request("DELETE", f"{BASE}/deployment/{urllib.parse.quote(deployment_id)}")


def _emit_deployment_id(deployment_id: str) -> None:
    print(f"deployment-id={deployment_id}")
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a") as handle:
            handle.write(f"deployment_id={deployment_id}\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Maven Central Portal Publisher API v1 client.")
    sub = parser.add_subparsers(dest="command", required=True)

    up = sub.add_parser("upload")
    up.add_argument("bundle")
    up.add_argument("--name", required=True)
    up.add_argument("--publishing-type", default="USER_MANAGED", choices=["USER_MANAGED", "AUTOMATIC"])
    up.add_argument("--wait")
    up.add_argument("--timeout", type=float, default=1800.0)

    st = sub.add_parser("status")
    st.add_argument("id")

    pub = sub.add_parser("publish")
    pub.add_argument("id")
    pub.add_argument("--wait")
    pub.add_argument("--timeout", type=float, default=3600.0)

    dr = sub.add_parser("drop")
    dr.add_argument("id")

    args = parser.parse_args(argv)

    try:
        if args.command == "upload":
            deployment_id = upload(args.bundle, args.name, args.publishing_type)
            _emit_deployment_id(deployment_id)
            if args.wait:
                wait_for(deployment_id, args.wait, args.timeout)
            return 0
        if args.command == "status":
            state, errors = deployment_state(args.id)
            print(json.dumps({"deploymentState": state, "errors": errors}))
            return 0
        if args.command == "publish":
            publish(args.id)
            if args.wait:
                wait_for(args.id, args.wait, args.timeout)
            return 0
        if args.command == "drop":
            drop(args.id)
            return 0
    except PortalError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    return 2


if __name__ == "__main__":
    sys.exit(main())
