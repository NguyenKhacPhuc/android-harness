# Security Policy

> **Stub.** Full disclosure process, supported versions, and signing details will be filled out in week 2 alongside `docs/08-security-and-compliance.md`.

## Reporting a vulnerability

If you find a security issue, please **do not open a public GitHub issue**. Instead, email the maintainer privately:

- **Contact:** _TBD — to be added before the repo is made public._

Please include:

- A description of the issue and the impact.
- Steps to reproduce, or a proof-of-concept if you have one.
- The affected version / commit SHA, if known.
- Whether you intend to disclose publicly, and on what timeline.

We will acknowledge receipt within 72 hours and aim to provide a remediation plan within 7 days. Coordinated disclosure timelines are negotiated case-by-case.

## Scope

This repository's security model is described in `docs/08-security-and-compliance.md`. In particular:

- The substrate is on-device. There is no backend run by the maintainers.
- Users supply their own Anthropic API key, stored locally.
- Concerns about the LLM provider's policies belong upstream with Anthropic.

In scope for this repo:

- Vulnerabilities in the substrate libraries, reference app, or build/release tooling.
- Issues that could leak the user's API key, conversation history, or memory store off-device.
- Issues in the harness that could cause incorrect tool invocation or bypass user confirmation gates.

Out of scope (here):

- Anthropic API itself.
- Third-party scripts or templates built on top of the substrate by other authors.

## Supported versions

Pre-v1: only `main` is supported. No security backports.
