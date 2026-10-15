# Trademarks, attribution, and infringement claims

This document records what this project claims, what it does not claim, and how
to have something corrected or removed.

It exists because an app like this necessarily refers to other people's work by
name, downloads other people's files, and produces text nobody reviewed. Each of
those is a place where a claim can arise, and the honest thing is to say up front
what the position is rather than to wait for a complaint.

---

## 1. No affiliation, no endorsement

**This project is not affiliated with, authorised by, sponsored by, or endorsed
by any of the organisations named below, or by any model publisher.**

Any impression that a named company produced, reviewed, supports, or recommends
this app is mistaken. The names appear because there is no accurate way to say
"this app can run Qwen3" without writing "Qwen3".

This project speaks only for itself. Statements here are not statements by any
company named here, and those companies have no involvement in the app.

---

## 2. Trademarks

The following names are the property of their owners. They are used in this
repository and in the app **descriptively** -- to identify the thing being
referred to -- and no claim is made to any of them.

| Name | Owner (as commonly held) | Where it appears |
| --- | --- | --- |
| Qwen, 通义千问, 阿里通义千问 | Alibaba Group (阿里巴巴集团) | Model catalogue |
| MiniCPM, 面壁智能 | ModelBest Inc. (北京面壁智能科技) | Model catalogue |
| DeepSeek, 深度求索 | Hangzhou DeepSeek Artificial Intelligence (杭州深度求索人工智能) | Model catalogue |
| Hugging Face, Hugging Face Hub | Hugging Face, Inc. | Download hosts, docs |
| Android, Google Play, LiteRT | Google LLC | Platform targets, dependencies |
| Kotlin, JetBrains | JetBrains s.r.o. | Language and libraries used to build the app |
| OkHttp, Okio | Square, Inc. | Dependencies |
| Gradle | Gradle, Inc. | Build system |
| Pokémon, Pikachu | Nintendo, Creatures Inc., GAME FREAK Inc.; administered by The Pokémon Company | The launcher icon until 2026-10-15 — see below |

This table is kept accurate as the catalogue changes. It is not a claim that the
list is exhaustive: a name may appear in a dependency's own metadata, which this
project does not author or control. The generated, complete list of dependencies
is [THIRD_PARTY_LICENSES.md](../THIRD_PARTY_LICENSES.md).

**No logos, wordmarks, or brand fonts belonging to any of these organisations are
included in this repository or in the app.** Nothing here reproduces a company's
visual identity.

### The icon that was not covered by that sentence, and is gone

**Until 2026-10-15 the app's launcher icon was a drawing of Pikachu**, a character
owned by Nintendo, Creatures Inc., and GAME FREAK Inc., and administered by The
Pokémon Company. It was used as the application's own icon, and this document
recorded it as a known violation rather than glossing over it — the paragraph above
would otherwise have been false, and a false claim of that kind is worse than the
risk it hides: anyone reading this document is entitled to know exactly what is in
the build.

**Nothing in this build uses it.** The icon is now geometry drawn for this project —
a processor outline with a speech bubble in `app/src/main/res/drawable-*/ic_launcher_foreground.png`,
masked by the adaptive icon in `app/src/main/res/mipmap-anydpi/`, on the same
`#20201E` background the app already carried — so the paragraph above is true of
this build, and the one item here that a takedown request could have been entirely
right about is closed.

What it meant while it lasted, kept because the reasoning is what a fork inherits:

- Not a claim of any right in the character, and not a claim of affiliation with or
  endorsement by any of those companies.
- Not part of this project's licence. The MIT licence covered the code in this
  repository; **it did not and could not cover that image.**
- A fork that took this code while the image was in it had to supply its own icon
  before publishing, and a request about the icon would have been honoured without
  argument.

The one-file shape of that replacement, for anyone doing it in a fork:
`app/src/main/res/drawable-*/ic_launcher_foreground.png` and the adaptive icon in
`app/src/main/res/mipmap-anydpi/`.

---

## 3. What this project does not distribute

This is the most important section for anyone assessing a claim.

- **No model weights are distributed.** The app downloads model files from the
  publisher's own servers, at the user's request, and verifies the download
  against a recorded digest. No model file is included in this repository, in
  any release, or in the published app. The catalogue records only a URL, a file
  name, a size, and a hash.
- **No third-party text is reproduced.** Descriptions, licence summaries, and
  explanations in this repository are written for this project. Where a licence
  or a publisher's statement is summarised, the summary is linked to its source
  rather than copied.
- **No third-party source code is vendored** into this repository. Dependencies
  are resolved by Gradle from their published coordinates and are governed by
  their own licences, listed in [THIRD_PARTY_LICENSES.md](../THIRD_PARTY_LICENSES.md).
- **No user data leaves the device.** See [PRIVACY.md](../PRIVACY.md). The
  project has no server and receives nothing.

---

## 4. What contributors must warrant

By opening a pull request, a contributor states that:

1. The work is theirs, or is compatible with this project's licence, and they
   have the right to submit it under that licence.
2. They have not copied code from a source whose licence this project does not
   accept; `scripts/check-licenses.ps1` will reject a dependency that is not on
   the allow-list, but it cannot detect copied code.
3. They have not added a model to the catalogue whose licence has not been
   checked against the model's own repository, per
   [MODEL_LICENSES.md](MODEL_LICENSES.md).
4. They have not added a company's logo, wordmark, or artwork.
5. They have not added text, images, or data from a source that does not permit
   it.

A contribution that cannot meet these is not merged.

---

## 5. Filing a claim

If you believe this project infringes a right you hold — copyright, trademark,
patent, publicity, or a licence term — write to the address in
[SECURITY.md](../SECURITY.md) with the subject line **"Infringement claim"**.
Please include:

- **What is claimed.** The specific file, line, screen, string, or asset in this
  repository or in the app.
- **What right you hold**, and how.
- **Why it is claimed to infringe.** Enough that someone can evaluate it without
  needing to ask.
- **What you want done** — removed, corrected, attributed differently, or
  something else.
- **How to reach you**, and whether you are the rights holder or acting for one.

You do not need to be a lawyer, or to use any particular form of words, to raise
one.

### What happens next

- **Acknowledged within 7 days.**
- **Assessed on the material**, not on who is asking.
- **If it is well founded: fixed or removed promptly**, without requiring a
  legal process first. Nothing here is worth defending when there is a
  reasonable claim against it.
- **If it is not well founded**, the reasoning is written back, with the specific
  facts relied on.
- **Where a fix changes something a user relied on**, it is recorded in the
  repository so the change is not silent.

There is no counter-notice procedure, because there is no notice-and-takedown
regime this project operates under. A claim is a claim, and it is answered on
its merits.

---

## 6. A known open question

This section exists because hiding it would make the rest of this document worth
less.

An automated check (`scripts/check-embedded-notices.ps1`) reads the notice files
inside every dependency. It found, inside `com.google.ai.edge.litertlm:litertlm-android:0.18.0`,
a `THIRD_PARTY_NOTICE.txt` containing a section headed **"Google Runtime
Environment"** that refers to **GPL-2.0**, alongside sections referring to
MPL-2.0 (for a bundled certificate bundle) and EPL-1.0.

The GPL-2.0 reference is recorded as **unresolved** in
[compliance/embedded-notices.json](../compliance/embedded-notices.json), and it
blocks a release build. What is not yet established is whether any GPL-2.0 code
is actually present in the artifact this app ships, or whether that section of
the notice describes a component that is not reached by this usage. The
distinction matters: one is a licensing problem, the other is a stale notice
file.

**A release build is not published until this is resolved**, either by
understanding the component or by a statement from its publisher. This is the
project's own constraint, enforced by a script, not a legal conclusion.

---

## 7. Corrections to this document

If something here is wrong — a name attributed to the wrong owner, a claim
overstated, an omission — say so, and it will be corrected. The intent is to be
accurate about what this project is and is not, and an inaccuracy defeats the
purpose whether or not anyone is upset by it.

---

*This document states this project's position. It is not legal advice, and it
has not been drafted or reviewed by a lawyer.*
