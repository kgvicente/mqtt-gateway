# Security Policy

The security of **MQTT Gateway** is a priority. As a service that sits in the critical path of MQTT broker authentication and authorization, protecting against unauthorized access, credential leakage, and route bypasses is essential.

## Supported Versions

Only the latest release branch receives security updates. We recommend always running the latest minor/patch release.

| Version | Supported          | Notes                                     |
| ------- | ------------------ | ----------------------------------------- |
| 0.1.x   | :white_check_mark: | Current active release line.              |
| main    | :white_check_mark: | Development branch (receives fixes first).|
| < 0.1   | :x:                | Pre-release versions are not supported.   |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues, discussions, or pull requests.**

If you believe you have discovered a security vulnerability in MQTT Gateway, report it privately:

1. **GitHub Private Vulnerability Reporting (Preferred):**  
   Navigate to the repository's **[Security](https://github.com/kgvicente/mqtt-gateway/security)** tab, click on **Advisories**, and select **"Report a vulnerability"**. This creates an encrypted, confidential disclosure channel visible only to the maintainer.
2. **Direct Contact:**  
   If GitHub Private Reporting is unavailable, reach out privately to the maintainer [@kgvicente](https://github.com/kgvicente) via GitHub profile contact methods.

### What to Include in Your Report

To help us assess and resolve the issue quickly, please provide:

- **Component:** Affected broker adapter (`/emqx`, `/mosquitto`, `/vernemq`, `/generic`), admin panel (`/gateway/`), internal auth, or storage/persistence layer.
- **Environment:** Gateway version, Java/Quarkus runtime version, operating system, and container setup (e.g. Docker Compose).
- **Vulnerability Description:** A clear explanation of the potential impact (e.g. authorization bypass, credential disclosure, denial of service).
- **Proof of Concept / Reproduction Steps:** Minimal commands, HTTP payloads, or broker configuration needed to reproduce the issue.
- **Sanitized Data:** Ensure that all actual production passwords, tokens, API keys, and sensitive IP addresses are redacted before submitting.

## Response Process & Timeline

- **Acknowledgement:** We aim to acknowledge receipt of security reports within 48 to 72 hours.
- **Assessment & Triage:** We will investigate and confirm the vulnerability, keeping you informed of progress.
- **Fix & Advisory:** A patch will be developed and verified against the smoke suite (`scripts/smoke.py`) and integration tests before a new release and GitHub Security Advisory are published.
- **Responsible Disclosure:** We request that you give us reasonable time to release a patch before publicly disclosing details. Reporters acting in good faith will be credited in the release notes and advisory (unless anonymity is requested).
