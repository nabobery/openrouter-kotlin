#!/usr/bin/env python3
"""Tests for scripts/central-portal.py: URLs, headers, multipart, the state machine, and token safety.

A fake `opener` records every request and returns scripted responses; `sleep` is stubbed so polling never waits.
Python 3 stdlib only.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("central-portal.py")
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location("central_portal", SCRIPT)
assert _spec is not None and _spec.loader is not None
cp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cp)

TOKEN_USER = "portal-user"
TOKEN_PASS = "super-secret-password"


class FakeResponse:
    def __init__(self, status: int, body: bytes):
        self.status = status
        self._body = body

    def read(self) -> bytes:
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FakeOpener:
    """Records requests and replays a queue of (status, body) responses."""

    def __init__(self, responses: list[tuple[int, bytes]]):
        self.responses = list(responses)
        self.requests: list = []

    def __call__(self, request, timeout=None):
        self.requests.append(request)
        status, body = self.responses.pop(0)
        return FakeResponse(status, body)


class PortalTest(unittest.TestCase):
    def setUp(self):
        self._env = {"MAVEN_CENTRAL_USERNAME": TOKEN_USER, "MAVEN_CENTRAL_PASSWORD": TOKEN_PASS}
        for k, v in self._env.items():
            self.addCleanup(lambda k=k: __import__("os").environ.pop(k, None))
            __import__("os").environ[k] = v
        self._orig_opener = cp.opener
        self._orig_sleep = cp.sleep
        cp.sleep = lambda _seconds: None
        self.addCleanup(self._restore)

    def _restore(self):
        cp.opener = self._orig_opener
        cp.sleep = self._orig_sleep

    def test_upload_posts_multipart_with_bearer_auth(self):
        cp.opener = FakeOpener([(201, b"deadbeef-deployment-id")])
        with tempfile.NamedTemporaryFile(suffix=".zip", delete=False) as f:
            f.write(b"BUNDLE-BYTES")
            bundle = f.name
        deployment_id = cp.upload(bundle, "openrouter-kotlin:0.1.0-rc.1", "USER_MANAGED")
        self.assertEqual("deadbeef-deployment-id", deployment_id)
        req = cp.opener.requests[0]
        self.assertEqual("POST", req.get_method())
        self.assertIn("/api/v1/publisher/upload?name=openrouter-kotlin%3A0.1.0-rc.1", req.full_url)
        self.assertIn("publishingType=USER_MANAGED", req.full_url)
        self.assertTrue(req.headers["Authorization"].startswith("Bearer "))
        self.assertIn(b'name="bundle"', req.data)
        self.assertIn(b"BUNDLE-BYTES", req.data)

    def test_wait_for_polls_until_target_state(self):
        cp.opener = FakeOpener([
            (200, json.dumps({"deploymentState": "PENDING"}).encode()),
            (200, json.dumps({"deploymentState": "VALIDATING"}).encode()),
            (200, json.dumps({"deploymentState": "VALIDATED"}).encode()),
        ])
        cp.wait_for("id-1", "VALIDATED", timeout=100)
        self.assertEqual(3, len(cp.opener.requests))

    def test_wait_for_succeeds_when_state_is_past_target(self):
        cp.opener = FakeOpener([(200, json.dumps({"deploymentState": "PUBLISHED"}).encode())])
        cp.wait_for("id-1", "VALIDATED", timeout=100)

    def test_failed_state_raises_with_errors(self):
        cp.opener = FakeOpener([(200, json.dumps({"deploymentState": "FAILED", "errors": ["bad pom"]}).encode())])
        with self.assertRaises(cp.PortalError) as ctx:
            cp.wait_for("id-1", "VALIDATED", timeout=100)
        self.assertIn("bad pom", str(ctx.exception))

    def test_wait_for_times_out(self):
        # Always PENDING; a zero timeout makes the deadline pass on the first check.
        cp.opener = FakeOpener([(200, json.dumps({"deploymentState": "PENDING"}).encode())] * 5)
        with self.assertRaises(cp.PortalError) as ctx:
            cp.wait_for("id-1", "VALIDATED", timeout=0)
        self.assertIn("timed out", str(ctx.exception))

    def test_token_never_appears_in_output_or_errors(self):
        # An HTTP error path plus a normal status call: neither may leak the base64 token or the raw password.
        cp.opener = FakeOpener([(200, json.dumps({"deploymentState": "PENDING"}).encode())])
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            cp.main(["status", "id-1"])
        combined = out.getvalue() + err.getvalue()
        self.assertNotIn(TOKEN_PASS, combined)
        import base64
        b64 = base64.b64encode(f"{TOKEN_USER}:{TOKEN_PASS}".encode()).decode()
        self.assertNotIn(b64, combined)

    def test_http_error_message_excludes_token(self):
        import urllib.error

        class ErrorOpener:
            def __call__(self, request, timeout=None):
                raise urllib.error.HTTPError(request.full_url, 400, "Bad Request", {}, io.BytesIO(b"pom invalid"))

        cp.opener = ErrorOpener()
        with self.assertRaises(cp.PortalError) as ctx:
            cp.deployment_state("id-1")
        msg = str(ctx.exception)
        self.assertIn("400", msg)
        self.assertNotIn(TOKEN_PASS, msg)

    def test_missing_credentials_raises(self):
        import os

        os.environ.pop("MAVEN_CENTRAL_PASSWORD", None)
        with self.assertRaises(cp.PortalError):
            cp._auth_header()


if __name__ == "__main__":
    unittest.main()
