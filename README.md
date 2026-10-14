# <APP_NAME>

<!--
  ============================================================================
  BEFORE THE FIRST PUSH -- replace these three placeholders, then delete this
  comment block. A README that still says "<APP_NAME>" is the fastest way to
  make a project look abandoned.
  ============================================================================
-->

> **<one-line description of what this app does and who it is for>**

<!--
  Add 2-4 real screenshots here once the UI exists. They are the single
  strongest signal a visitor uses to decide whether to read further.
  <p align="center">
    <img src="docs/screenshots/home.png" width="30%" />
  </p>
-->

## Features

- <feature one>
- <feature two>
- <feature three>

## Screenshots

_Coming once the first build is usable._

## Requirements

| Tool | Version used by this project |
| --- | --- |
| JDK | 17 (Gradle toolchain) |
| Android SDK Platform | 36 (`compileSdk` / `targetSdk`) |
| Minimum Android | 24 (`minSdk`) |
| Gradle | 9.1 |
| Android Gradle Plugin | 9.0.1 |
| Kotlin | 2.3.20 |

## Building

```bash
git clone https://github.com/KAYOYAK/<REPO_NAME>.git
cd <REPO_NAME>

# Point Gradle at your SDK. This file is machine-specific and gitignored.
echo "sdk.dir=$ANDROID_HOME" > local.properties

# Debug build
./gradlew assembleDebug

# Unit tests
./gradlew testDebugUnitTest

# Lint
./gradlew lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

On Windows use `gradlew.bat` instead of `./gradlew`.

### Instrumented tests

They need a device or emulator, and they need a model on it. The script runs them without
losing the model:

```powershell
./scripts/run-instrumented-test.ps1
./scripts/run-instrumented-test.ps1 -Class com.example.demoapp.inference.LiteRtLlmEngineTest
./scripts/run-instrumented-test.ps1 -ModelPath D:\path\to\another.litertlm
```

`gradlew connectedDebugAndroidTest` is the plain Gradle route, and it uninstalls the
application when it finishes — which takes any model in app-private storage with it, so
that route cannot be run twice without pushing a model again. The script drives the same
instrumentation directly: it builds, installs both APKs without uninstalling, launches the
app once because its `filesDir` is created lazily, copies a model in if one is not already
there, and runs `am instrument` on the class you asked for.

The engine tests look in the app's own `files/models` directory first and fall back to any
`*.litertlm` file in `/data/local/tmp/models`, so a hand-dropped model is enough:

```bash
adb shell mkdir -p /data/local/tmp/models
adb push Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm /data/local/tmp/models/
```

A test that finds no model says so and returns rather than failing, and the note it prints
names the file it used.

No pipeline runs this set. An emulator job whose tests skip reports the same green as one
whose tests pass, and the smallest model in the catalogue is 344 MB — so the choice was
between a job that cannot fail honestly and no job.

### Release builds

Release signing is intentionally **not** configured in this repository. Supply
your own keystore locally rather than committing it:

```bash
./gradlew assembleRelease \
  -Pandroid.injected.signing.store.file=/path/to/release.jks \
  -Pandroid.injected.signing.store.password="$STORE_PASSWORD" \
  -Pandroid.injected.signing.key.alias="$KEY_ALIAS" \
  -Pandroid.injected.signing.key.password="$KEY_PASSWORD"
```

Never commit a `.jks` file or its password. Losing the keystore means you can
never publish an update to an app that is already on a store — the signing
identity cannot be replaced.

## Project layout

```
app/src/main/java/...   Application source (Jetpack Compose)
app/src/main/res/       Resources: strings, themes, drawables, icons
app/src/test/           JVM unit tests, run by `testDebugUnitTest`
app/src/androidTest/    Instrumented tests, need a device or emulator
gradle/libs.versions.toml   Single source of truth for dependency versions
```

## Privacy

<!--
  If the app collects or transmits anything at all, say so here and add a
  privacy policy before publishing to a store. If it truly collects nothing,
  say that explicitly -- it is a selling point.
-->

This app collects nothing. There is no analytics, no crash reporting, no
telemetry and no account. The network is used to download models and for nothing
else; after a model is on the device the app works with it switched off.

Full detail, and the checks that keep these statements true:
[PRIVACY.md](PRIVACY.md).

## Project principles

Three constraints are non-negotiable and bind every change — legal, privacy, and
open-source verifiability. Each one is enforced by a script that fails the build,
not by a promise in a document.

**[docs/PRINCIPLES.md](docs/PRINCIPLES.md)**

| Constraint | Enforced by |
| --- | --- |
| Licences | `scripts/check-licenses.ps1` |
| Privacy contract | `scripts/check-privacy.ps1` |
| Notices packed inside the shipped AARs | `scripts/check-embedded-notices.ps1` |
| Legal notice and attribution | `scripts/check-legal-docs.ps1` |
| Copy in one place, in the app's language | `scripts/check-ui-copy.ps1` |
| Every screen callback the graph opens is wired | `scripts/check-ui-wiring.ps1` |
| No copy left over from a reworded feature | `scripts/check-unused-strings.ps1` |
| Attribution accuracy | `.github/workflows/compliance.yml` |

## Language

**The app is in Chinese, and it is for Chinese speakers.** There is no
translation layer and no plan for one: the copy lives in
`app/src/main/res/values/strings.xml`, one file, and every model the catalogue
offers is one that can hold a Chinese conversation.

That last part is a correctness requirement, not a preference. The first build
of this app put an English-only 360M model at the top of the list, and a Chinese
question came back as confident English about something else — which reads as a
broken app rather than as the wrong model for the job. Every entry now records
what it can be talked to in, the list shows it, and
`ModelCatalogue.validate()` refuses a catalogue whose first (default) model
cannot do Chinese.

**The catalogue holds Chinese models only.** A model published elsewhere may be
excellent and still be the wrong choice here: it may not hold a Chinese
conversation, its host may be unreachable from mainland China, and its publisher
has no stake in whether it works there. `validate()` also refuses a catalogue
whose first download source is `huggingface.co`, and the app tries the mirror
first for the same reason. The list starts at 329 MB so that a device with
little room to spare can still use the app.

The source comments and this README stay in English, which is the convention the
codebase has and what an outside contributor reads. That is a decision about
contributors, not about users.

## What it does

Text chat with a model that runs entirely on the phone.

- **Models.** Download, import from a file, delete. Downloads are resumable and
  verified against a recorded digest; the mirror is tried first, because the
  publisher's host is blocked in the country this app is for.
- **Chat.** Streaming replies, stop mid-reply, regenerate an answer, delete a
  message or a whole exchange, and edit the last question and ask it again.
  Code blocks, headings, lists, quotes and inline emphasis are rendered; a reply
  can be copied whole, or one code block at a time. Every message carries the time
  it was sent, and replies carry how long they took and how many tokens per second
  they produced, which is what makes two models comparable.
- **Search inside a conversation.** A long reply is one where finding a word again
  matters. The match count is shown, so a shortened transcript never reads as lost
  messages.
- **History.** Search across titles and message text, rename, delete, pin to the
  top, and share a conversation as text. Each row shows how much room it takes.
- **Backup.** Export every conversation as one file to keep, and import it back.
  This app keeps everything private and declares no cloud backup, so this is the
  only way a conversation survives a new phone: "not synced" must not mean "not
  movable", or privacy becomes a cage.
- **Text shared in from another app.** Sharing a paragraph here puts it in the
  composer to be edited and sent. It is not sent automatically — asking is still
  the user's act.
- **Settings.** Which processor to use, the system prompt, reply length,
  temperature, light/dark/system appearance, and text size. Five instructions are
  built in as a starting point, and your own wording can be saved under a name and
  reused — reproducing a good instruction from memory is the thing nobody does.
- **About.** What runs, what does not leave the device, and how to erase
  everything.

## Disclaimer and terms

This app runs a language model on your device and shows you what it says. Nobody
reviewed that output, and there is no server in the middle that could have — the
same property that keeps your conversations private is the one that means nothing
here can be moderated.

**It is not professional advice, and it must not be used for medical, legal,
financial, or safety decisions.** The models it runs are small enough to fit on a
phone and will state false things confidently.

- **[DISCLAIMER.md](DISCLAIMER.md)** — warranty, liability, what the output is,
  and what you are responsible for.
- **[docs/legal/TRADEMARKS-AND-CLAIMS.md](docs/legal/TRADEMARKS-AND-CLAIMS.md)** —
  no affiliation with any model publisher, the trademarks this project refers to
  by name, what it does not distribute, and how to file an infringement claim.

Both are enforced by `scripts/check-legal-docs.ps1`, which fails the build if
either is missing, unlinked, or no longer credits a publisher named in the model
catalogue — and if the app itself has stopped showing a disclaimer. The app shows
one offline, in **Models → About**, because a document on a website is not
reachable from the device it is about.

## Contributing
Bug reports and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).
Please report security issues privately as described in [SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE) © 2026 KAYOYAK

<!--
  Swapping the licence later is easy while the repository is young and hard once
  outside contributions exist. If you would rather have:
    Apache-2.0  -- adds an explicit patent grant; common across the Android
                   ecosystem (AOSP itself). Recommended if you may ever want
                   corporate contributors.
    GPL-3.0     -- copyleft: derivatives must stay open. Use if you want that.
  Replace LICENSE, then update this section and the badge below.
-->

[![CI](https://github.com/KAYOYAK/<REPO_NAME>/actions/workflows/ci.yml/badge.svg)](https://github.com/KAYOYAK/<REPO_NAME>/actions/workflows/ci.yml)

## What is built, and what is next

[docs/ROADMAP.md](docs/ROADMAP.md) lists every feature this project has decided to
build, decided against, or has not yet decided about -- one row each, with what it
is grounded in.
