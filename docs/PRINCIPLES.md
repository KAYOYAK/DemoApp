# Project principles

Three constraints that are not negotiable. They bind every change to this
project — human authors, AI agents, and outside contributors alike.

If a change cannot satisfy all three, the change is wrong. Not the principles.

---

## Why this file exists

These constraints were each learned from a concrete problem, and each of them
is the kind of problem that **cannot be repaired after release**:

- A licence obligation attaches to every copy already distributed. Once the APK
  is out, editing a file does not recall it.
- A privacy claim that turns out to be false is not a bug. It is a broken
  promise, and the users who relied on it cannot get their data back.
- An open-source project whose stated guarantees cannot be checked is
  indistinguishable from one that is lying.

Because the damage is irreversible, these are enforced **before** the change
lands, not reviewed after.

---

## P1 · Legal

> **Every distributed artifact must be redistributable under terms this project
> can satisfy.**

### What this means

- Every dependency on the release runtime classpath carries a licence listed in
  `compliance/allowed-licenses.json`.
- Copyleft licences (GPL, LGPL, AGPL, SSPL, BUSL) are not on that list and will
  not be added to it.
- Custom or bespoke licences are treated as denied until read and recorded.
- Attribution obligations are discharged: `NOTICE` and
  `THIRD_PARTY_LICENSES.md` ship with the project and stay accurate.
- The project says plainly what it is not: `DISCLAIMER.md` states the absence of
  warranty, that the output is the model's rather than the author's, and that no
  one can moderate it. `docs/legal/TRADEMARKS-AND-CLAIMS.md` states that no model
  publisher is affiliated with this project, names the marks referred to by
  name, and gives a process for an infringement claim.
- Those documents are reachable from the app itself, offline. A disclaimer that
  lives only on a website is not one the person holding the phone can read.

### It forbids

- Adding a dependency without regenerating the licence lock.
- Adding a model to the catalogue whose licence is not Apache-2.0, MIT or BSD.
- Adding a **gated** model, which would force an authentication flow on users.
- Bundling model weights into the APK. The project is not a redistributor of
  model weights; the user obtains them from their publisher.
- Editing `THIRD_PARTY_LICENSES.md` by hand. It is generated.
- Removing or weakening the disclaimer to make the app more attractive to a
  store or a user. The limits it states are real limits of what a small model on
  a phone can do.
- Reusing a company's logo, wordmark or artwork to describe which model the app
  can run. Names may be used descriptively; brand assets may not be used at all.
  **Until 2026-10-15 the app's launcher icon broke this rule** — it was a drawing
  of a character owned by another company. The rule was not weakened to
  accommodate it: the icon was recorded as a known violation, in
  [legal/TRADEMARKS-AND-CLAIMS.md](legal/TRADEMARKS-AND-CLAIMS.md), with what it
  would take to remove, and then it was replaced with geometry drawn for this
  project. A principle quietly edited to match what was already shipped is not a
  principle.
- Merging a contribution whose author cannot state that the work is theirs.

### Enforcement

`scripts/check-licenses.ps1` — fails on an unlisted licence, and fails when the
resolved graph no longer matches the committed lock.

### Background

`docs/legal/MODEL_LICENSES.md` records why specific models are accepted or
refused, including the Gemma 3 case.

---

## P2 · Privacy

> **The network is used to download models. For nothing else.**

### What this means

The application declares one permission itself, `INTERNET`, and uses it for one
purpose. Two more arrive from the app lock's library and grant neither storage nor
data: see [PRIVACY.md](../PRIVACY.md), which names the three. After a model is on
the device, the application works with the network switched off — that is a
product requirement, not a nice-to-have.

### It forbids

- Any analytics, crash-reporting, attribution or telemetry SDK. Including one
  pulled in transitively.
- Declaring a permission without a written reason in the check script.
- `usesCleartextTraffic`, and network security configs that re-enable it.
- Sending prompts, replies, conversation history, or device identifiers
  anywhere.
- Writing user data outside app-private storage.
- Any feature that requires connectivity to function.

### Enforcement

`scripts/check-privacy.ps1` — fails on an unapproved permission or a reachable
telemetry dependency. It reads the **resolved** dependency graph, so a
transitively introduced SDK is caught the same as a direct one.

### The one thing that is not automated

Install, download a model, enable airplane mode, hold a conversation. Do this
before every release. The scripts prove the code contains no networking; only
this proves the feature actually works offline.

---

## P3 · Open source

> **Every claim this project makes about itself must be mechanically checkable.**

### What this means

A public repository is read by people deciding whether to trust it with their
data. Statements in `README.md` and `PRIVACY.md` are commitments. Each one has
something that fails a build if it stops being true.

### It forbids

- A guarantee with no corresponding check. "Pressing share opens a share sheet" is
  a guarantee, and until `check-ui-wiring.ps1` existed nothing failed when it
  stopped being true — the argument was deleted from the call site, the default
  absorbed it, and the build stayed green.
- A check that has never been observed to fail. An untested gate is
  indistinguishable from a disabled one.
- Removing or weakening a gate to make a change pass. Fix the change.
- Shipping a build whose CI is red.

### Enforcement

`.github/workflows/compliance.yml` runs every gate on every push and pull
request, and additionally verifies the attribution file is not stale.

The gates that exist today: `scripts/check-licenses.ps1`,
`scripts/check-privacy.ps1`, `scripts/check-embedded-notices.ps1`,
`scripts/check-legal-docs.ps1`, `scripts/check-ui-copy.ps1`,
`scripts/check-ui-wiring.ps1`, `scripts/check-unused-strings.ps1`. Named rather
than counted — "both gates" was true once and then quietly became false as more
were added, and a document that miscounts its own enforcement is one nobody can
check.

Two of them were in this list before they were in any pipeline: the
embedded-notice walk and the screen-wiring check ran correctly by hand and were
enforced by nothing. That is the same defect as the features `check-ui-wiring`
exists to find, one level up — a check that nothing runs is a comment.

---

## When the principles conflict with what you want to build

They win. That is what "non-negotiable" means.

The productive response is to find a different implementation, not a different
principle:

| You want | The principles say | Instead |
| --- | --- | --- |
| On-device model X, licence is custom | No | Find an Apache-2.0/MIT model with comparable quality |
| Gated model, best in class | No | The 12 accepted models include several small enough that OAuth is not worth it |
| Crash reports to debug field issues | No | Ask users to export logs manually, after they choose to |
| An SDK that bundles analytics | No | Find one that does not, or write the small part you need |
| Ship the model in the APK for convenience | No | Download it. The user gets it from its publisher, under that publisher's terms |
| Every other app has this feature | Depends, and "they have it" is not a reason | Look at how they built it, then read [FEATURE-RESEARCH.md](FEATURE-RESEARCH.md). Several of the market's standard features exist only because those apps are allowed to phone home, take accounts, or sell things — this one is not |

If a genuine incompatibility arises that this table does not cover, the answer is
still no until this file is amended — and amending it requires writing down what
changed and why, in a commit that shows the reasoning.

**Before building anything new, look at how it is done elsewhere.** That is a
rule in [AGENTS.md](../AGENTS.md), and the findings are kept in
[FEATURE-RESEARCH.md](FEATURE-RESEARCH.md). Both exist for the same reason: an
app with unusual constraints cannot tell which parts of a familiar design are
load-bearing and which are habit without going and looking.

---

## Changing these principles

Requires:

1. An explicit decision by the project owner.
2. An update to this file, in the same change that makes the new behaviour true.
3. The corresponding check updated, or added if none exists.
4. The reasoning written down. Not "we relaxed the rule" but why the rule was
   wrong.

Rules that get quietly relaxed are how projects end up with a README that lies.
