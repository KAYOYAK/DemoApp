# Contributing

Thanks for considering a contribution. This document covers the mechanics;
the [README](README.md) covers what the project is.

## Before anything else: the three constraints

**[docs/PRINCIPLES.md](docs/PRINCIPLES.md)** sets out three non-negotiable
constraints — legal, privacy, and open-source verifiability. They are not
aspirations. A contribution that cannot satisfy all three will not be merged,
regardless of how useful it is.

In practice, for a pull request, this means:

- **No new dependency without a licence check.** Run
  `scripts/check-licenses.ps1 -Update` and commit the regenerated
  `compliance/licenses.lock.json` and `THIRD_PARTY_LICENSES.md` in the same PR.
- **No telemetry, analytics, or crash-reporting SDK.** Not directly, and not
  transitively. The privacy gate resolves the full dependency graph to catch the
  indirect case.
- **No new permission without a written reason** in `scripts/check-privacy.ps1`.
- **No model whose licence is not Apache-2.0, MIT or BSD**, and none that is
  gated behind a login. See [docs/legal/MODEL_LICENSES.md](docs/legal/MODEL_LICENSES.md).
- **No feature that requires connectivity.** The app must work with the network
  off except while downloading a model.

All seven gates run automatically in CI; a `pre-commit` hook runs the two whose
cost is highest when missed:

```bash
scripts/check-licenses.ps1
scripts/check-privacy.ps1
scripts/check-embedded-notices.ps1
scripts/check-legal-docs.ps1
scripts/check-ui-copy.ps1
scripts/check-ui-wiring.ps1
scripts/check-unused-strings.ps1
```

If you believe a constraint is wrong, open an issue explaining why. Rules that
are wrong should be fixed in `docs/PRINCIPLES.md`, with reasoning. They should
not be worked around.

## Reporting a bug

Open an [issue](../../issues/new/choose) and use the bug report form. The form
asks for the app version, your Android version and reproduction steps — those
three things are what make a report actionable.

For a **security** problem, do not open a public issue. Follow
[SECURITY.md](SECURITY.md).

## Suggesting a feature

Open an issue with the feature request form. Describe the problem you are
trying to solve rather than a specific implementation; there is often a simpler
answer than the first one that comes to mind.

## Setting up a development environment

You need:

- **JDK 17** — the build declares `jvmToolchain(17)`. Newer JDKs can *run*
  Gradle, but the toolchain must be resolvable or the build fails with
  `Cannot find a Java installation ... matching: {languageVersion=17}`.
- **Android SDK** with platform 36 and build-tools 36.0.0 or newer.
  Android Studio's SDK Manager installs these.
- An **emulator or device** running API 24+ for instrumented tests.

```bash
git clone https://github.com/KAYOYAK/<REPO_NAME>.git
cd <REPO_NAME>

# Tell Gradle where your SDK is. This file is gitignored on purpose.
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

Open the directory in Android Studio, or build from the command line:

```bash
./gradlew assembleDebug        # build
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew lintDebug            # Android lint
```

On Windows use `gradlew.bat`.

## Pull requests

- **One concern per PR.** An unrelated refactor bundled with a fix makes the
  fix hard to review and impossible to revert independently.
- **Match the existing style.** Run `./gradlew lintDebug` before pushing; CI
  runs it too and will fail the PR otherwise.
- **Put user-facing text in `res/values/strings.xml`**, not inline in Kotlin.
  Hardcoded strings cannot be translated and are easy to miss in review.
- **Describe how you verified it.** "Tested locally" tells a reviewer nothing;
  "ran the unit tests, installed on API 36, confirmed the list scrolls to the
  end" does.
- **Keep commits meaningful.** A commit that says `fix` will be squashed; one
  that says why the fix is needed is worth keeping.

CI must pass before a PR is merged. If CI fails for a reason unrelated to your
change, say so in the PR rather than force-pushing blindly.

## What will not be merged

- Code that collects or transmits user data without this being documented in
  the README.
- A signing keystore, API key, token, or any other credential. These belong in
  local configuration and are rejected by the repository's ignore rules.
- Dependencies under a licence incompatible with [MIT](LICENSE).

## Licence of contributions

By submitting a pull request you agree that your contribution is licensed under
the project's [MIT licence](LICENSE).
