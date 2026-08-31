# Security Policy

## Supported versions

`openrouter-kotlin` is pre-1.0. Only the **latest published minor** of the `0.x`
line receives security fixes. Older `0.x` minors are not patched — upgrade to the
latest minor to receive fixes.

| Version | Supported |
| ------- | --------- |
| latest `0.x` minor | ✅ |
| any older `0.x` | ❌ |

## Reporting a vulnerability

**Report privately. Do not open a public issue for a suspected vulnerability.**

Use GitHub's private vulnerability reporting:
[**Report a vulnerability**](https://github.com/nabobery/openrouter-kotlin/security/advisories/new)
(repository → **Security** → **Advisories** → **Report a vulnerability**). This opens a
private GitHub Security Advisory visible only to you and the maintainers.

Please include:

- affected version(s) and target(s) (JVM, Android, JS, native, …);
- a minimal reproduction or proof of concept;
- the impact you observed (credential exposure, request tampering, denial of service, …);
- any suggested remediation.

You will receive an acknowledgement of the report. We aim to triage within a few business
days and to keep you informed through the advisory thread until a fix ships.

## Incident response procedure

When a valid report arrives we follow these steps (mirrored in `docs/security-and-privacy.md`):

1. Privately report and track the vulnerability through this security policy / advisory.
2. Assess credential, artifact, and behavioral impact.
3. Revoke affected release or automation credentials.
4. Patch supported release lines.
5. Publish an advisory and migration instructions.
6. Rotate signing/release material if compromise is possible.
7. Record the corrective control and regression test.

## Scope

In scope: the SDK's handling of credentials, request construction, TLS/host trust,
response and stream parsing, pagination, and the repository's CI/automation supply chain.

Out of scope: vulnerabilities in the OpenRouter service itself (report those to OpenRouter),
and content that an untrusted server chooses to echo back inside a bounded response-body
preview (the SDK bounds the preview but does not redact server-supplied bytes — see the
threat model's accepted residuals).
