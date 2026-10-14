# Privacy

This application runs language models on your device. Your conversations never
leave it.

## What is collected

**Nothing is collected. There is no analytics, no crash reporting, no telemetry,
and no account.**

The application declares one permission itself, and two more arrive from a library:

| Permission | Why |
| --- | --- |
| `INTERNET` | To download model files. Nothing else. |

`androidx.biometric`, which backs the app lock, contributes two during the
manifest merge. Neither can read a fingerprint and neither returns data to the
application: they let it ask the system to run an authentication whose only result
is pass or fail, with all matching done in the secure element, outside this
process.

| Permission | Why |
| --- | --- |
| `USE_BIOMETRIC` | Asks the system to authenticate the device's owner. |
| `USE_FINGERPRINT` | The pre-Android-9 name for the same capability, for the releases this app still supports. |

If the app lock is switched off, the order in which the merged manifest is read
does not change and both remain declared — an unused permission is still a
promise about what the app can do. Anyone checking this by hand will see three
permissions in the merged manifest rather than the one above, which is why the
table is split rather than the claim left unqualified.

That is enforced, not merely stated: `scripts/check-privacy.ps1` fails the build
if a permission is added without a documented reason, or if a telemetry library
enters the dependency graph. It reads the *resolved* dependency graph, so a
reporting SDK pulled in transitively is caught the same as a direct one.

## What the app accepts from other applications

An `ACTION_SEND` filter for `text/plain` lets another app hand this one a
paragraph, which lands in the composer to be edited and sent. Three things about
it are deliberate:

- **It carries no permission.** Receiving a share is not a permission: the other
  app chose to send, and this one is only offered the text it put in the intent.
- **Only `EXTRA_TEXT` is read**, and only from an intent that declares itself
  text. This entry point is reached from outside the app, so reading a field the
  sender did not promise would mean acting on whatever happened to be there.
- **Nothing is sent automatically.** A shared paragraph is a request to ask about
  it; the asking is still the user's, because sending spends their battery and
  their model's attention on a question they had not finished writing.

The app also accepts its own `com.example.demoapp.NEW_CHAT` action from its
launcher shortcut. That action is not exported to anything else — a public one
would let any app on the device clear the open conversation.

## What leaves the device, and only when asked

Nothing leaves on its own. Two features put user text into the user's hands,
both started by the user:

- **Sharing a conversation** puts its text in a share the user picked a
  destination for.
- **Exporting everything** does the same for every conversation at once, as one
  file.

Neither writes a copy into shared storage or sends anything anywhere by itself.
`scripts/check-privacy.ps1` fails the build on cleartext or on any network use
outside the model downloader.

## When the network is used

**Only while downloading a model.** After a model is on the device, the
application works with the network off.

Specifically:

- Model downloads are the only outbound requests, and they go to the model's
  publisher (Hugging Face). You can see the exact URL of every model in the
  catalogue before downloading it.
- Inference runs entirely on the device's own CPU, GPU or NPU.
- Prompts, replies, and conversation history are never transmitted anywhere.
- There is no sync, no backup to a server, and no remote configuration.

To verify this yourself: download a model, enable airplane mode, and keep using
the application. It will continue to work.

## What is stored, and where

| Data | Location | Removed when |
| --- | --- | --- |
| Model files | App-private storage (`filesDir`) | You delete the model in the app, or uninstall |
| Conversation history | App-private storage | You delete the conversation, clear app data, or uninstall |
| Settings | App-private storage | You clear app data or uninstall |

App-private storage is not readable by other applications on a non-rooted device.
Nothing is written to shared or external storage.

Uninstalling the app removes all of it.

## What the model publisher can see

When the application downloads a model, the request reaches the publisher's
servers in the normal way. That request necessarily reveals your IP address to
them, and they may log it. This is how any download works and is outside this
application's control.

The application sends no identifying information beyond what an ordinary HTTPS
request carries: no account, no device identifier, no usage data.

You can avoid even that by obtaining model files through some other means and
importing them locally.

## What the model itself does

Model weights are data. They do not execute, and they cannot phone home — the
application provides no mechanism for a model to make a network request.

Any behaviour beyond that depends on what a given model was trained to do, which
is why the catalogue links each model to its publisher's page.

## Children

The application is a general-purpose tool with no accounts, no communication
features and no user-generated content sharing. It collects no data of any kind,
including from children.

## Changes

If a future version changes any of the above — for example by adding an optional
feature that needs the network — this document and the enforcing check will be
updated in the same change, and the release notes will say so.

## Verifying these claims

Everything above is mechanically checkable:

```bash
# Resolve the dependency graph and check for telemetry libraries
scripts/check-privacy.ps1

# Inspect declared permissions
cat app/src/main/AndroidManifest.xml

# Prove the offline path
#   1. install the app
#   2. download a model
#   3. enable airplane mode
#   4. hold a conversation
```

The third check is the only one that cannot be automated, and it is the one that
actually matters.
