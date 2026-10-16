// `java.util.Properties` has to be imported: inside a build script `java` names the
// Java plugin's extension, so the fully qualified form does not resolve.
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// Release signing, when a key is supplied.
//
// The release build type has no signing config of its own, and for most of this
// project's life that was the whole story: `assembleRelease` produced an unsigned APK
// and the release workflow published a debug build instead. The release workflow now
// passes a key with `-Pandroid.injected.signing.*`, which is the form AGP documents
// for an injected signing config -- and which, on its own, does not sign a release
// build here. Nothing read those properties, so a run with every secret configured
// would have attached an APK labelled "signed release" that `apksigner` reports as
// unsigned: the release notes making a claim about provenance that the artefact does
// not support, which is the one thing this project's tamper check exists to prevent.
//
// Three callers, so three spellings of the same four values: the injected Gradle
// properties (CI, and Android Studio when it builds a signed APK), the
// `RELEASE_KEYSTORE_*` environment variables (a shell that would rather not put a
// path in the process list), and `keystore.properties` beside this module (a
// developer's own key, which is in .gitignore and never in the repository).
//
// Absent every one of them the release build stays unsigned rather than failing.
// That is deliberate: the workflow's fallback branch decides what to do about it, and
// a build that cannot be signed is still a build worth being able to compile.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(injected: String, environment: String, local: String): String? =
    (project.findProperty(injected) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environment)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(local)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue(
    injected = "android.injected.signing.store.file",
    environment = "RELEASE_KEYSTORE_FILE",
    local = "storeFile",
)
val releaseStorePassword = signingValue(
    injected = "android.injected.signing.store.password",
    environment = "RELEASE_KEYSTORE_PASSWORD",
    local = "storePassword",
)
val releaseKeyAlias = signingValue(
    injected = "android.injected.signing.key.alias",
    environment = "RELEASE_KEY_ALIAS",
    local = "keyAlias",
)
val releaseKeyPassword = signingValue(
    injected = "android.injected.signing.key.password",
    environment = "RELEASE_KEY_PASSWORD",
    local = "keyPassword",
)

android {
    namespace = "com.kayoyak.demoapp"

    // compileSdk only decides which APIs are visible while compiling; it does
    // not change runtime behaviour. targetSdk below stays at 36 and is what
    // actually opts the app into platform behaviour changes.
    //
    // 37 is required by okhttp-android 5.5.0, which declares a minimum compile
    // SDK of 37 in its AAR metadata. Raising it here rather than pinning an
    // outdated HTTP client is the intended use of the split.
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.kayoyak.demoapp"
        // 26 rather than 24 for java.nio.file, which ChatStore uses to replace a
// conversation file atomically; File.renameTo cannot do that consistently across
// platforms. Android 8.0 is a reasonable floor for an app whose smallest model
// is 356 MB and which needs gigabytes of free RAM to run one.
        minSdk = 26
        // Kept equal to compileSdk. `lint` reports a lower target as "not targeting
        // the latest versions; compatibility modes apply", and there is nothing here
        // that wants a compatibility mode: the app declares one permission, serves no
        // ads, has no background service and no content provider. Raising it is the
        // cheap direction -- a target below the compile level is the one that gets
        // behaviour the code was not written against.
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"

        // The inference runtime is the constraint, not a preference.
        //
        // litertlm-android ships its native library for these two ABIs only. Without
        // this filter the app also packages androidx.graphics.path for armeabi-v7a
        // and x86, and the ABI split then looks like a 32-bit build to a 32-bit
        // device: the package is offered by the store and accepted by the installer,
        // and every launch fails on UnsatisfiedLinkError the moment the engine loads.
        // Refusing the install is the failure a user can act on -- a 32-bit phone
        // cannot run this app, and it can neither fit nor run a model from the
        // catalogue at all.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // Only when a key is present, so an unsigned build stays possible. The
            // store file is checked rather than assumed: a path that does not exist is a
            // configuration mistake, and AGP's own failure names the file, which is a
            // better message than an APK that silently comes out unsigned.
            if (releaseStoreFile != null && releaseStorePassword != null &&
                releaseKeyAlias != null && releaseKeyPassword != null
            ) {
                val store = file(releaseStoreFile)
                require(store.exists()) {
                    "Release signing was configured with '$releaseStoreFile', which does not exist."
                }
                signingConfigs.create("release") {
                    storeFile = store
                    storePassword = releaseStorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                }
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // On-device LLM runtime. Apache-2.0; see docs/PRINCIPLES.md and
  // compliance/allowed-licenses.json.
  //
  // This is deliberately the ONLY inference dependency: adding a second runtime
  // would double the native payload in the APK and multiply the licence surface.
  implementation(libs.litertlm.android)

  // HTTP client, used by the model downloader and nothing else. That class is
  // the only place in the application that touches the network -- see
  // docs/PRINCIPLES.md. Apache-2.0.
  implementation(libs.okhttp)

  // Chat history persistence. See gradle/libs.versions.toml for why this is
  // JSON files rather than Room: the serialization compiler plugin is already
  // applied, so this costs one runtime dependency and no annotation processor.
  implementation(libs.kotlinx.serialization.json)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
    // For the app lock. The platform has BiometricPrompt from API 28; this covers
    // 26 and 27, which the app supports.
    implementation(libs.androidx.biometric)
    // BiometricPrompt is hosted by a FragmentActivity.
    implementation(libs.androidx.fragment.ktx)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Core icon set only -- about thirty icons.
  //
  // material-icons-extended is deliberately not used: it carries thousands of
  // vector assets to supply one or two, and the whole of it lands in every debug
  // build. Where a core icon is not a close enough match the UI uses a close
  // one, which is why stopping a reply uses a close mark rather than a stop
  // square.
  implementation(libs.androidx.compose.material.icons.core)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  // Test-only: lets the downloader's awkward cases (Range ignored, 416,
  // checksum mismatch) be provoked deterministically. Not on the release
  // classpath and therefore not part of what ships.
  testImplementation(libs.mockwebserver)

  // Instrumented tests.
  //
  // Two groups, and the difference between them is the point.
  //
  // **Kept** (above, with the Compose block): `compose-ui-test-junit4` and
  // `ui-test-manifest`. Nothing imports them today. They cost nothing that ships --
  // one is `androidTest` only, the other `debug` only -- and they are the entire
  // facility for a Compose UI test. Removing them would have made the next person
  // re-add a dependency to write one test.
  //
  // **Removed** here: espresso, test-core, and test-runner. Nothing imported those
  // either, and unlike the Compose pair they are not what a UI test needs.
  //
  // What made this worth looking at: "there is no Compose UI test setup" was used
  // once as the reason not to add regression tests for the font-scale sample and the
  // erase progress. That reason was wrong -- the setup was already declared, and what
  // was missing was a test, not a facility. Whether those two get written now depends
  // on having a device to run them, which is a different question from what the build
  // declares.
  //
  // One dependency is genuinely needed by the one instrumented test that exists:
  // `LiteRtLlmEngineTest` runs on a device to prove the native library loads and the
  // runtime reports what this project thinks it does, and it needs a runner
  // annotation.
  androidTestImplementation(libs.androidx.test.ext.junit)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}

// A project property crosses into the test JVM only when it is forwarded. `-D` on a Gradle
// command line sets a property on the daemon, and the test task forks its own process with
// its own system properties -- and the daemon outlives the invocation, so an environment
// variable set by a later caller does not reach it either.
//
// `demoapp.refusalWatch` is the property that needs this. Set, it makes a move refusal that
// the retry window recovered from a failure in `ChatViewModelTest`: that is the only way a
// run reports what the file system objected to, because the save lands and nothing fails on
// its own. Three widenings of `MOVE_RETRY_MILLIS` were made without that reason, so
// `scripts/stress-flake.ps1` passes `-Pdemoapp.refusalWatch=1` and every other build --
// CI included -- passes nothing, which leaves a recovered refusal a recorded fact instead
// of a failure. See `FileChatStore.SaveAudit.absorbedMoveRefusals`.
tasks.withType<Test>().configureEach {
  providers.gradleProperty("demoapp.refusalWatch").orNull?.let {
    systemProperty("demoapp.refusalWatch", it)
  }
}
