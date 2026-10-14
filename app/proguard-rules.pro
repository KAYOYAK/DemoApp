# Rules for the release build.
#
# This file did not exist until now, and `build.gradle.kts` named it anyway. That was
# invisible while `isMinifyEnabled = false` and fatal the moment it was set to true:
#
#     Supplied proguard configuration does not exist: app/proguard-rules.pro
#
# So the file is what makes R8 possible at all. **Minification is still off**, and the
# last section says why -- read it before turning it on.
#
# Every rule below names what it protects. The rules that were tried and did NOT work
# are recorded too, because a rule that looks right and silently does nothing is worse
# than no rule.

# ---------------------------------------------------------------------------
# Failure classification -- the rule that is not optional
# ---------------------------------------------------------------------------
#
# `LoadFailure.of` builds a searchable string from `Throwable::class.java.simpleName`
# and matches words in it to choose what to tell the user:
#
#     "outofmemory" in lower || "out of memory" in lower || "no space" in lower -> NOT_ENOUGH_MEMORY
#     ...                                                                        -> BROKEN_FILE
#     ...                                                                        -> UNKNOWN
#
# It reads like formatting. It is a decision: the runtime reports through a handful of
# exception types and the difference between "no room" and "broken file" is not in the
# type, so the class name is one of the inputs. Renamed, `OutOfMemoryError` arrives as
# something like `a.b`, no pattern matches, and every failure falls through to
# `Kind.UNKNOWN` -- whose copy tells the user to retry and send the details, instead of
# telling them their device is out of memory. Nothing crashes, nothing is logged where
# anyone would look, and the specific advice silently stops appearing.
#
# `CrashLog` writes `error::class.java.name` into the crash file the user is asked to
# send, so an obfuscated name there turns a diagnosis into a puzzle.
-keepnames class * extends java.lang.Throwable

# ---------------------------------------------------------------------------
# Tool calling -- unresolved, see the last section
# ---------------------------------------------------------------------------
#
# `LocalTools` is handed to the runtime via `tool(activeTools)`, and the runtime finds
# what it can call by reflecting over `@Tool` annotations on its methods. R8 cannot see
# that: the reader lives in the library's `ReflectionTool`, and the annotation is read
# by type.
#
# `litertlm-android-0.18.0.aar` ships no consumer rules -- its contents are the
# manifest, classes.jar, res, R.txt, the two .so files, LICENSE and
# THIRD_PARTY_NOTICE.txt, and no `proguard.txt`.
#
# This rule is kept because it demonstrably works: with it the class and its method
# names survive, without it they are renamed. Verified by comparing the dex.
-keep class com.example.demoapp.inference.LocalTools { *; }

# ---------------------------------------------------------------------------
# Serialization
# ---------------------------------------------------------------------------
#
# `kotlinx-serialization-json` ships its own consumer rules, so the generated
# `$$serializer` companions survive without help. Repeated rather than relied on,
# because the serialized types are the app's persistent state -- `Conversation`,
# `Settings`, the model registry, the backup format -- and the failure mode is reading
# a user's own file back as empty.
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# Platform callbacks
# ---------------------------------------------------------------------------
#
# `AppContainer` passes `StorageManager::class.java` to `getSystemService` and to the
# allocatable-bytes API, which are looked up by that class.
-keep class android.os.storage.StorageManager { *; }

# ---------------------------------------------------------------------------
# What was tried, and what did not work
# ---------------------------------------------------------------------------
#
# The open question was whether `@Tool` annotations survive R8. Each method was tried on
# its own and measured against the minified dex by counting references to the annotation
# type:
#
#   | rules                                              | `litertlm/Tool` in dex |
#   | -------------------------------------------------- | ---------------------- |
#   | none (fully obfuscated)                            | 0                      |
#   | `-keepattributes RuntimeVisibleAnnotations` only   | 0                      |
#   | `-keep class com.google.ai.edge.litertlm.Tool`     | 2                      |
#   | both of the above                                  | 2                      |
#
# Two, either way, is the annotation *class definition*. The unminified build returns
# **102** hits for the same search, and in the minified one every hit that is not the
# class definition sits inside the library's own `ReflectionTool` -- the code that looks
# for the annotation, not a method carrying it:
#
#     1603c4: instance-of v6, v6, Lcom/google/ai/edge/litertlm/Tool;
#
# So keeping the annotation class is not enough to keep the annotation on a method, and
# none of the combinations tried was shown to do it. `-keepattributes` alone does
# nothing here, which is worth knowing on its own: it only applies to classes that some
# rule also keeps.
#
# **Not resolved.** What remains to try is a rule that keeps the annotated members:
#
#     -keepclassmembers class * {
#         @com.google.ai.edge.litertlm.Tool <methods>;
#     }
#
# It is deliberately not written above, because nothing here can confirm it works, and a
# keep rule that silently does nothing is the failure this file exists to avoid.
# Confirming it needs a device: the annotation is only read when the model calls a tool,
# so the test is to load a model with `LocalTools` registered, ask it something needing
# the clock or a calculation, and see whether the tool runs. Byte inspection can show
# whether the annotation is in the dex; it cannot show whether the runtime finds it.
#
# Which is the whole reason `isMinifyEnabled` is still false. R8 would save about 7.6 MB
# of a 57 MB APK whose other 48 MB is two `.so` files, in exchange for a failure mode
# that is silent, reachable only on a device, and breaks a feature the unit tests cannot
# cover. That is not a trade worth taking blind.
