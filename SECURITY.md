# Security Policy

## Reporting a vulnerability

**Please do not report security issues through public issues, discussions or
pull requests.**

Use GitHub's private reporting instead:

**[Open a private security advisory →](https://github.com/KAYOYAK/<REPO_NAME>/security/advisories/new)**

If you cannot use that form, open a minimal public issue asking for a private
channel and disclose nothing further until one is established.

Please include, as far as you can:

- what the issue allows an attacker to do, and what they need in order to do it
- the app version, Android version and device where you observed it
- reproduction steps or a proof of concept
- any suggested remediation

## What to expect

| Stage | Target |
| --- | --- |
| Acknowledgement | within 7 days |
| Initial assessment | within 14 days |
| Fix or documented mitigation | depends on severity and complexity |

You will be credited in the advisory and release notes unless you ask not to be.

## Scope

In scope:

- the application source in this repository
- the build and release workflow definitions under `.github/`
- any APK published through this repository's releases

Out of scope:

- vulnerabilities in Android itself, in the Android SDK, or in a third-party
  library — report those upstream (though a note here is welcome if this app is
  affected)
- issues that require a rooted device, a physical attacker, or a modified APK
- anything in a build you did not obtain from this repository's releases

## Handling of secrets

This repository contains no credentials, and its ignore rules are configured to
reject signing keystores and credential files. Release signing is performed
either locally or in CI from repository secrets that are never written to the
working tree.

If you believe a credential has been committed, treat that as a vulnerability
report and use the private channel above — a committed secret remains in the
history even after the file is deleted.
