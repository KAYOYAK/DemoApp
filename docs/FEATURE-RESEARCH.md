# Feature research

What other on-device LLM apps do, so a new feature starts from knowledge instead
of a blank page.

The rule is in [AGENTS.md](../AGENTS.md): look first, then filter. This file is
the "look" half, kept current so nobody repeats it.

## Check the runtime before the market

**The dependency already solves more of this than the market does.** LiteRT-LM
0.18.0 ships APIs for four features that looked like work; read the AAR's own
classes before designing anything:

```bash
# The API surface, straight out of the resolved artifact.
javap -classpath <expanded classes.jar> com.google.ai.edge.litertlm.<Class>
```

| Wanted | Already in the runtime |
| --- | --- |
| Reasoning separated from the answer | `ThinkingConfig(enableThinking, thinkingTokenBudget)`; `Channel(name, start, end)`; `ConversationConfig.channels`; **`Message.channels`** — the runtime can hand reasoning back in a named channel rather than as text |
| Context window | `ModelInfo.maxContextTokens()`, `isDynamicContext()`; **`Conversation.getTokenCount()`** |
| Prefill vs decode timing | `BenchmarkInfo` — `timeToFirstTokenInSecond`, `lastPrefillTokensPerSecond`, `lastDecodeTokensPerSecond`, `initTimeInSecond`; via `Conversation.getBenchmarkInfo()` |
| Sampler controls | `SamplerParameters(type, temperature, topK, topP)`, `RepetitionPenaltyConfig`, `NoRepeatNgramConfig`, `SuppressTokensConfig` |
| Tool calling | `ToolManager`, `ToolProvider`, `Tool`, `ToolCall`, `OpenApiTool`, `ReflectionTool`, and an `automaticToolCalling` flag on the conversation |
| Model capability reporting | `ModelInfo.inputModalities()`, `supportedBackends(Modality)`, `socName(Modality)`, `minRuntimeVersion()` |

**Consequence for how features get built here**: check `ModelInfo` and
`Conversation` first. Three quarters of "add a feature" turned out to be "call an
API that was already there and stop throwing the result away".

## Who to look at

| Project | Licence | Why it is worth reading |
| --- | --- | --- |
| [PocketPal AI](https://github.com/a-ghorbani/pocketpal-ai) | MIT | The most complete free app and the usual baseline. Feature-complete README; useful for what users expect by default. |
| [OfflineLLM](https://github.com/jegly/OfflineLLM) | Apache-2.0 | **Closest to this project.** Kotlin, Compose, Room, zero network permission, no telemetry. Its feature list is the best available answer to "what would this app do next". |
| [MNN Chat](https://github.com/alibaba/MNN/tree/master/apps/Android/MnnLlmChat) | Apache-2.0 | Alibaba. Read the **changelog**, not the README -- it is a record of what Chinese users actually reported. |
| [RikkaHub](https://github.com/rikkahub/rikkahub) | AGPL-3.0 | Same stack (Kotlin, Compose, Navigation 3). **AGPL: read it, never copy code from it.** Strongest Markdown rendering of the group. |
| [ChatterUI](https://github.com/Vali-98/ChatterUI) | — | Power-user controls: sampler parameters per model, character cards, archive. |
| [Maid](https://github.com/Mobile-Artificial-Intelligence/maid) | MIT | Flutter. Exposes llama.cpp options directly; has a LAN server mode this project must not have. |
| [SmolChat](https://github.com/shubham0204/SmolChat-Android) | Apache-2.0 | Targets 4–6 GB phones. Its roadmap is a useful cross-check on what is still missing elsewhere. |

Survey method that worked: read the **README for the feature list**, then the
**changelog or releases page for what actually broke**. The second is the useful
one. Two comparison articles, both of which list the criteria the market judges
by, are linked from the Unstore "best apps" series.

## What the market treats as standard

Confirmed by three or more projects. Missing one of these is a real gap, not a
preference.

| Feature | Who has it |
| --- | --- |
| Message branching | PocketPal, ChatterUI, RikkaHub |
| Screen stays awake during inference | PocketPal |
| Model recommendation by device RAM | OfflineLLM, and PocketPal is criticised for lacking it |
| Advanced sampling (top-p, top-k, min-p, repeat penalty) | OfflineLLM, PocketPal, MNN Chat |
| Per-model sampler settings | PocketPal |
| Markdown tables and code highlighting | RikkaHub, OfflineLLM (Prism4j), SmolChat (Markwon) |
| Crash or diagnostic information the user can hand over | OfflineLLM (selectable backend diagnostics), MNN Chat (reports crashes) |
| Context window handling | Listed as a top criterion by the market comparisons |

## Found by reading changelogs

Problems real users hit, which no feature list mentions.

| Finding | Source | State here |
| --- | --- | --- |
| Reasoning models leak their ` thinking` block into the reply | OfflineLLM ("thinking tag stripping"), MNN Chat 0.5.0 ("hide the thinking process") | **Not handled.** DeepSeek-R1-Distill-Qwen-1.5B is in the catalogue and does this. |
| Chinese users need a domestic download source | MNN Chat ("if the system language is Chinese, use ModelScope") | Handled, and further: the mirror is tried first regardless of language. |
| Prefill time and decode time are different complaints | MNN Chat ("show total decode time and prefill time") | Only a combined tokens-per-second. |
| Users report crashes with nothing to send | MNN Chat 0.2.1, 0.4.3 | No local diagnostics export. |
| Large models crash mid-range phones | Market comparisons ("on 8 GB the phone swaps and the app crashes within minutes") | No memory check before loading. |
| Repackaged forks leak privacy | RikkaHub's own README warning about its forks | No tamper detection. |

## Decided against, and why

Looked at, judged against [PRINCIPLES.md](PRINCIPLES.md), refused. Recorded so
the decision is not silently relitigated.

| Feature | Constraint |
| --- | --- |
| Browsing and downloading any GGUF model | P1 — every model here carries a licence record and a reachable source. Arbitrary downloads cannot. |
| Multiple providers, custom API endpoints | P2 — network is for downloading models only. |
| Web search grounding (Exa, Tavily, Perplexity) | P2 |
| MCP | P2 |
| Account sync, cloud backup | P2 |
| Leaderboards, opt-in telemetry, crash reporting | P2 |
| LAN server mode | P2 |
| Model marketplace, in-app purchases | P3, P2 |
| Multi-language UI and tablet layouts | Product scope: Chinese users only. |
| SillyTavern character-card import | Small audience here, and it brings a format to maintain. |

## Context windows, and why a meter comes first

Sources: a Chinese engineering write-up on the four strategies
([腾讯云](https://cloud.tencent.com/developer/article/2742720)), and a bug report
against a real tool whose gauge read past its own maximum
([hermes-agent#109760](https://github.com/NousResearch/hermes-agent/issues/109760)).

The four strategies, and what the article says each costs:

| Strategy | Cost | Suits |
| --- | --- | --- |
| Sliding window | Simplest, cheapest | Short chats with little history dependence |
| Priority truncation | Needs an importance mark per message | Clear business rules |
| Summary compression | Compression cost, information loss | Long conversations |
| Vector memory | Highest complexity, recall error | Facts that must survive |

**Its recommended order is measure first, then window** — record tokens per turn
and window occupancy before changing what is kept. That is why the meter here was
built before any truncation, and why there is still no truncation.

Traps it names, all of which apply here:

- **Trimming by turn rather than by token budget.** One pasted log line fills the
  window whatever the turn count says.
- **Reserving nothing for the system prompt and the current question.** Both have
  to fit whatever else is kept, and a system prompt pushed out of the window is a
  model that forgets what it is.
- **No measurement, so nobody notices the window filled.** The failure looks like
  the model getting worse.
- **The gauge must never read past its maximum.** Reported against a real tool
  after the runtime discarded the oldest messages: the count was read from before
  it did, and the panel showed 355.8k of 262.1k. `ChatUiState.contextUsage` clamps,
  and `ContextUsageTest` holds it there.

## Diagnostics without telemetry

Sources: a full design spec for exactly this feature
([SendspinDroid](https://chrisuthe.github.io/SendspinDroid/superpowers/specs/2026-06-16-diagnostics-feedback-system.html))
and a local-first bug-reporting SDK ([cluvo](https://github.com/Maintainer-Ally/cluvo)).

Their hardest-won lesson is about order: **build the redactor first.**
SendspinDroid's spec says it plainly — exports were not sanitized, and their log
had been recording the server address and, through a proxy config, a token. A
one-tap "share this with a public issue tracker" on top of that is one-tap
leaking infrastructure.

What they agree on:

- **Filter on the way out, not at each log site.** Filtering at the source is a
  rule every future log line has to remember, and the failure is silent.
- **No third-party crash SDK.** No Firebase, no Crashlytics. Local file only,
  never auto-uploaded.
- **The user reads it before it goes anywhere.**
- `Thread.setDefaultUncaughtExceptionHandler` installed early, **delegating to the
  previous handler afterwards** — not delegating swallows the crash, and the app
  looks like it vanished.
- An uncaught-exception handler that only writes to logcat captures nothing,
  because the system log is not something a user can hand over.

What this app adds that they do not need: **the conversation must never be in the
report.** Their logs carry addresses; these carry what someone typed. There is no
code path from `DiagnosticsReport` to the transcript, and a test asserts the
redaction keeps the version, the device, the model name and the stack frames while
removing paths, addresses and credentials.

## Branching, and the smaller version of it

Source: a complete architecture write-up for a conversation tree
([aio-hub](https://github.com/miaotouy/aio-hub/blob/dev/src/tools/llm-chat/docs/architecture/conversation-tree-and-branching.md)),
plus LangChain's branching-chat pattern.

Their principle, in their words: **default not to overwrite, but keep the freedom
to.** The full model is a tree — a flat `nodes` map plus `parentId`/`childrenIds`,
with an `activeLeafId` cursor, so switching branches is a pointer move and nothing
is copied. Every operation has a non-destructive form:

| Operation | Their model |
| --- | --- |
| Regenerate | New **sibling** node; the old answer is untouched |
| Continue | New sibling whose content starts with the previous text |
| Edit → save as branch | New sibling; the original subtree survives |
| Edit → save in place | Overwrite, but recorded on an undo stack |
| Switch branch | Move `activeLeafId` only |
| Disable a message | `isEnabled = false`; excluded from the request, kept on disk |

**What this project took, and what it left.** The tree is the right general model
and is a schema rewrite. What was implemented instead is the case this app can
actually produce: **a reply carries its previous versions**, and regenerating no
longer destroys the answer it replaced. Regenerating is the only operation here
that could make an alternative, and it only ever applies to the last reply — so
version history on a message covers it without a tree, and without the branch
navigation UI that a tree needs to be usable at all.

The full model becomes worth it when **editing an earlier question** stops being
refused. `editAndResend` refuses that today, deliberately, because answers after it
are invalidated and discarding them silently is a bigger thing than a menu item
can honestly offer — which is the same problem the tree solves, and the reason
that refusal is written down rather than left as a gap.

Two things taken directly from their write-up:

- **A version switch must rebuild the runtime.** The model must be told which
  answer is on screen, or the next reply contradicts the one above it.
- **An edited question is not a version.** When the question changes, the old
  answer answers a different question. Offering it as an alternative would put two
  unrelated answers side by side and call them the same thing. `editAndResend` does
  not carry it; `regenerate` does.

## Secure deletion, which is mostly not a thing

Looked at because the closest comparable app lists it — "secure deletion, files
overwritten before removal" — and because it sounds like the obvious companion to
deleting a private conversation.

The mobile-security literature says not to promise it:

> On Android, calling `file.delete()` will not securely erase the target file, and
> as long as it is not overwritten it can be carved from a physical image of the
> device. Traditional approaches to wipe a file generally do not work on mobile
> devices due to the aggressive management of the NAND Flash memory.

([secure-mobile-development](https://github.com/mvandermeulen/secure-mobile-development/blob/master/en/coding-practices/understand-secure-deletion-of-data.md),
same conclusion as ETH Zürich's work on
[log-structured filesystems](https://syssec.ethz.ch/content/dam/ethz/special-interest/infk/inst-infsec/system-security-group-dam/research/publications/pub2011/1106.0917v1.pdf).)

The reason is the flash controller. It remaps writes and keeps spare blocks, so
bytes written over a file are not necessarily the bytes that file occupied. An
overwrite is a write to somewhere, and somewhere is not necessarily where the old
contents are.

**What this project does, in the light of that:**

| Action | Real? |
| --- | --- |
| Overwrite a file's own contents before unlinking it | **Partly.** The blocks are in use while the write happens, so it is not a write elsewhere. Free, immediate, and does nothing at all on some devices. |
| Write over the free space | **The only method with a chance.** Also wears the flash, slows the whole device while it runs, and uses battery. |
| Say "securely deleted" | **No.** That claim is not available on this platform. |

The middle one is offered in About, as a button with its cost written above it,
bounded at two gigabytes. That trade belongs to the user: whether it is worth
wearing the flash depends on who they are defending against, and the app cannot
know that.

**What is actually protecting a lost phone** is that Android encrypts app storage
at rest, keyed to the device lock. That is worth saying on the same screen, because
it is the honest answer to the question the feature pretends to answer — and
because it does nothing at all for a rooted device that is already unlocked.

## Where this project is already ahead
Worth knowing before copying anything, because these are the reasons the app
exists rather than a checklist item.

- **A licence gate on every model**, including user-imported ones, which require
  a declared licence and a source URL. No other project in the table does this.
- **Mirror-first downloads**, so the app is usable where the publisher's host is
  not reachable.
- **No telemetry, no account, no network beyond the model downloader** — matched
  only by OfflineLLM and Private AI Chat.
- **Machine-checked legal documents**: five scripts in `scripts/` and a CI
  workflow, so a compliance claim cannot rot.

## Already done, that others list as missing

Cross-checked; these are on someone else's roadmap or cited as a weakness.

| Feature | Still wanted by |
| --- | --- |
| Automatic conversation titles | SmolChat roadmap |
| Search messages within a conversation | SmolChat roadmap |
| Export and import conversations as JSON | OfflineLLM, Maid |
| System prompt presets | OfflineLLM |
| Multi-line input | MNN Chat 0.4.0 |
| Switching models from the chat screen | MNN Chat 0.6.8 |
| Storage used, shown on device | MNN Chat 0.5.1 |
