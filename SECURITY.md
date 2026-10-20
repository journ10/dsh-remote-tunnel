# Security policy

## Supported versions

The project is pre-1.0. Security fixes target the latest `main` and the latest
tagged release.

## Reporting a vulnerability

Please do **not** open a public issue for an unfixed vulnerability.

Report privately to the maintainers through the repository's private security
advisory channel (GitHub **Security → Advisories → Report a vulnerability**).
Include:

- affected component (`plugin`, `server`, or `mobile`) and version/commit;
- a minimal reproduction or proof of concept;
- impact and any known mitigations;
- whether you want credit in the advisory.

We aim to acknowledge within 72 hours and to publish a coordinated fix and
advisory. Do not test against infrastructure you do not own.

## Scope

In scope:

- relay authentication, pairing, device-cookie, and revocation bypasses;
- tunnel framing, desynchronization, or request smuggling;
- cross-instance routing or SSRF through the relay;
- DSH cookie/token exposure through the relay, app, logs, or error messages;
- KMP pairing, credential isolation, file/download, or platform intent issues;
- TLS/Caddy configuration mistakes in the shipped deployment.

Out of scope:

- compromise of a DSH host or a relay host after an attacker has root;
- a malicious relay operator reading plaintext in the v1 TLS-terminating
  design (documented boundary; use a relay you control);
- denial of service from unlimited resources on a single-process relay;
- social engineering of a user who scans an attacker-supplied QR code;
  pairing is possession-based.

## Security model

Read [`docs/security.md`](docs/security.md) for the threat model, secrets,
pairing limits, and deployment hardening checklist.
