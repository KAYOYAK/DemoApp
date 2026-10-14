# Disclaimer

**Read this before relying on anything this app produces.**

This document is part of the project's legal terms. It applies to the app, to
its source code, and to anything produced with them.

---

## The app is provided as-is

There is no warranty of any kind, express or implied, including any warranty
that it is merchantable, fit for a particular purpose, accurate, or free of
defects. You use it at your own risk.

The full licence terms are in [LICENSE](LICENSE). Where this document and the
licence differ, the licence governs the code and this document describes
additional expectations about the app's behaviour.

---

## What the app is not

**It is not a source of professional advice, and it must not be used as one.**

The models this app runs are small enough to fit on a phone. They are
demonstrably capable of stating something false in a confident, fluent,
well-formatted sentence. That is not a defect that will be fixed by an update;
it is what a model of this size is. Specifically, do not use its output as:

- medical advice, diagnosis, or a decision about treatment
- legal advice, or the basis of a contract or a filing
- financial advice, an investment decision, or a tax position
- anything a qualified professional is licensed to tell you
- input to a decision that affects someone's safety, liberty, employment,
  credit, housing, immigration status, or access to a service

**It is not a filtering service.** See the next section for why this matters
more here than in a chat app backed by a server.

---

## The output is the model's, not this project's

Every word this app produces is generated on your own device by a model file
that you downloaded or added. It is not written, reviewed, edited, or approved
by anyone who works on this project.

No one maintaining this project can see your conversations or the output, and
no one can filter it. There is no server in the middle to run a filter on,
because there is no server at all. That is a deliberate property of the app --
see [PRIVACY.md](PRIVACY.md) -- and it has a consequence that must be stated
plainly:

> **This project cannot moderate what this app says to you. No one can.**

If a model produces something offensive, false, dangerous, or unlawful, that is
the model's output and your device's rendering of it. The responsibility for
deciding whether to act on it is yours, and there is no intermediary to appeal
to.

---

## Who the models belong to

The model files are not part of this project and are not distributed with it.
They are developed and licensed by other people, under their own terms, which
are separate from this app's licence and may be stricter.

- Which models the app offers, and why each one was accepted, is recorded in
  [docs/legal/MODEL_LICENSES.md](docs/legal/MODEL_LICENSES.md).
- Your use of a model is governed by **that model's** licence, not by this one.
  Some model licences carry their own acceptable-use restrictions. Reading them
  is your responsibility before you use one.
- A model you added yourself from a file is under terms this project has never
  seen and cannot evaluate. You asserted its licence when you added it, and that
  assertion is yours.

Names such as Hugging Face, Qwen, SmolLM, Gemma, and Phi belong to their owners.
See [docs/legal/TRADEMARKS-AND-CLAIMS.md](docs/legal/TRADEMARKS-AND-CLAIMS.md).

---

## You are responsible for what you do with it

You are responsible for:

- **How you use the output**, and for checking anything important against a
  source that is accountable for being right.
- **What you share.** The app can export a conversation to another app when you
  ask it to. That is the only way anything leaves your device, and once it has,
  it is out of both the app's hands and this project's.
- **How you obtained the app.** A build from anywhere other than this
  repository or a store listing it links to may have been modified, and this
  project has no knowledge of what was changed.
- **Complying with the law where you are**, including any restriction on the use
  of a model you chose to run.

---

## The app may have defects

It is a small project written by a small number of people. It has been tested,
but it has not been tested everywhere, on every device, or by anyone with a
formal assurance obligation. Known limitations, including an unresolved
attribution question about one component, are recorded in the repository's issue
tracker rather than left to be discovered.

If a defect causes you to lose something, that is a real cost and this document
does not pretend otherwise. Keeping your own copies of anything you care about
is the only safeguard this project can offer, and it is why the app writes plain
files rather than a proprietary database you would need it to read back.

---

## Limits on liability

To the extent the law where you are permits it, no one who contributes to this
project is liable for any loss arising from the app, its output, or this
document -- including lost data, lost profit, or any damage caused by acting on
something the app said.

Where the law does not permit that exclusion, liability is limited to the amount
you paid for the app. If you obtained it at no charge, that amount is zero.

Nothing here attempts to exclude liability that cannot lawfully be excluded.

---

## If you do not accept this

Do not use the app and do not redistribute it. Continuing to use it means you
accept these terms.

---

*This document describes this project's intent. It is not legal advice to
anyone, and it has not been drafted or reviewed by a lawyer. Anyone distributing
this app commercially, or publishing it to an app store, should have it reviewed
in the jurisdictions they are publishing into — store terms, consumer law, and
what a disclaimer may exclude all vary, and this text has not been checked
against any particular one.*
