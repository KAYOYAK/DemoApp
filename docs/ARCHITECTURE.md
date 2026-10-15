# Architecture

## The inference seam

`inference/LlmEngine.kt` is an interface. `inference/LiteRtLlmEngine.kt` is its
only implementation today, and it is the only file that constructs a LiteRT-LM
`Engine` or `Conversation`.

That restriction is deliberate. Nothing upstream of the seam — UI, data,
persistence — knows which runtime is in use.

One file is outside the seam and has to be counted with it: `inference/LocalTools.kt`
imports `com.google.ai.edge.litertlm.Tool` and `ToolSet`, the annotations that make a
method callable by the model. "How does the runtime discover a tool" has no
runtime-neutral answer, so that cannot be routed through the interface, and replacing
the runtime means re-annotating or replacing this file too. Replacing the runtime is
therefore **two** files plus a factory, not one — written down because this paragraph
said "one file" for as long as the tool-calling feature has existed, and a reader
planning the NPU work by that sentence would have under-counted the job.

The reason is the NPU goal. Running inference on vendor silicon is materially
different from running it on the CPU, and it is likely to be a different runtime
rather than a flag on this one. If `Backend.NPU` had leaked into the UI layer,
that replacement would ripple through the whole application.

### What the seam commits to

- `InferenceBackend` is the application's enum, not the library's.
- `EngineState` and `GenerationEvent` describe the lifecycle and the token
  stream in terms the UI can render without translation.
- `generate()` returns a cold `Flow`. Collection starts the work, cancellation
  stops it, and failures arrive as events rather than exceptions so a caller
  cannot leave a progress indicator spinning forever.
- `load()` replaces any loaded model. Holding two models at once is the reliable
  way to be killed for memory on a phone.

### Known constraints handed up from the library

- **Sampling parameters are fixed per conversation**, not per request. Changing
  temperature means recreating the conversation, which discards its history.
  `ChatViewModel.reseedRuntimeWith` does that (in `ui/chat/ChatGeneration.kt`, one of
  the files the class is split across): it rebuilds the conversation with
  the current settings and replays the messages as history, so a temperature
  change applies without losing the conversation. Every path that changes what
  the runtime holds — loading a model, opening a conversation, asking again,
  editing a question, deleting a message, stopping a reply — goes through it,
  because a runtime holding a different conversation from the one on screen is
  the failure that produces confident answers to questions nobody asked.
- **Generation streams genuinely.** Measured at ~119 ms between tokens on an
  x86_64 emulator with the CPU backend, with roughly 2.5 s spent on prefill
  before the first token. Incremental rendering in the UI is therefore honest.
  These figures are from an emulator and say nothing about a real device.

## The NPU path

Not implemented, recorded here so the decision is not re-derived later.

LiteRT-LM already exposes `Backend.NPU(nativeLibraryDir)` and knows the vendors
(`NpuBrand`: Qualcomm, Google Tensor, MediaTek, Intel, Samsung). So a vendor NPU
is reachable without replacing the runtime.

Two things stand in the way, and both are real:

1. **The model must be compiled for the specific NPU.** A `.litertlm` file
   converted for CPU or GPU will not execute on an NPU just because the backend
   was requested. This is a different build of the model, not a different flag.

2. **The device must have that NPU.** There is no fallback that preserves the
   property being tested, which is why `candidateOrder` in
   `LiteRtLlmEngine.kt` deliberately does *not* fall back when NPU is requested:
   silently returning CPU results would make an NPU failure look like a
   performance problem.

The likely shape of the work, when it happens: extend `InferenceBackend` if a
second runtime is needed, add its implementation, select between them in a
factory. The abstraction was built to make that possible; it has not been proven
against a second implementation yet, and that proof is the point at which the
design gets validated.

## Development environment caveats

### The network on the development machine is proxied

`huggingface.co` and roughly thirty `github.com` / `githubusercontent.com`
domains are mapped to `127.0.0.1` in `C:\Windows\System32\drivers\etc\hosts`,
where a local proxy (`WattToolkit`, part of the Steam++ accelerator) answers on
port 443.

Two consequences worth knowing before drawing conclusions from a measurement:

- **Any observation about how Hugging Face behaves is an observation about the
  proxy.** Redirects, headers and status codes seen from this machine are
  whatever the proxy returns, not what the origin server returns.
- **DNS is affected too.** A public resolver returned an address in an unrelated
  network's range for `huggingface.co`, so resolving the real host to connect
  directly does not work either.

The downloader was written to be correct without knowing the answer: see
`ModelDownloader.openStream`, which handles a server that ignores `Range` as
well as one that honours it, and `ModelDownloaderTest`, which covers both.

This affects development only. A phone resolves and routes on its own, so the
application's behaviour in a user's hands is unaffected.

## Model storage

Models live in the app's private `filesDir`. They are hundreds of megabytes to
several gigabytes, they are the largest thing the app owns, and they are user
data in the sense that the user chose to download them.

Consequences that shape the code:

- Storage pressure must be handled explicitly, not discovered through a crash.
- Deletion must be a first-class operation, not a side effect.
- Downloads must be resumable. A user on a slow connection who loses 900 MB of a
  1 GB download does not try again.

## Conversation backup

Conversations are one JSON file each in the app's private storage, and the app
declares no cloud backup. That is a deliberate promise, and it has a consequence
that has to be answered or the promise becomes a cage: a new phone would start
empty, and the only way to keep a year of conversations would be to break the
promise.

So the user carries them. `ConversationBackup` defines the interchange format,
and `ChatStore.restore` writes it back.

Decisions worth knowing:

- **Two formats, two jobs.** `ConversationExport` writes plain text for a person
  to read; `ConversationBackup` writes JSON for the app to read back. One format
  cannot do both: text loses which model said what, when, and whether a reply was
  interrupted, and those are what make a restored conversation the same one.
- **A version is read before anything else.** A backup from a newer build is
  refused by name rather than half-parsed. Guessing produces a conversation that
  is subtly missing messages, and the user cannot tell that from having lost them.
  Adding an optional field does not bump it — an old file still parses. A bump
  means the old file would be read *wrongly*.
- **Restore skips ids it already has.** Ids are generated per conversation, so a
  collision means the same conversation. Overwriting would trade the conversation
  on the device — which may have messages the backup predates — for the older one,
  with no prompt.
- **Nothing writes to shared storage.** The export goes into a share the user
  started, which is the only way an app that keeps everything private can hand
  anything over. See `PRIVACY.md`.
- **Both directions are bounded, and the bound is the platform's.** A Binder
  transaction carries about a megabyte and a `String` costs two bytes per character
  inside one, so `BackupTransfer.MAX_SHARE_CHARS = 300_000` is what keeps a library
  export from raising `TransactionTooLargeException` on the main thread. The other
  direction is bounded because the file is not this app's: `MAX_IMPORT_BYTES`
  refuses a file past 32 MB *while reading it*, so a mis-picked video is not pulled
  into memory to find out what it was. Each bound produces a verdict the screen can
  say, because "too large" is not "unreadable" and neither is "not a backup".
- **Reading a backup happens off the main thread.** The picker's callback is a
  main-thread callback, and reading a stream there is an ANR waiting for a large
  file; the read and the decode both run in `Dispatchers.IO`.
- **The result is reported, not inferred.** "12 restored" and "12 were already
  here" are both successes and mean opposite things to someone who just picked a
  file, so `RestoreReport` carries added, skipped, and failed separately.
