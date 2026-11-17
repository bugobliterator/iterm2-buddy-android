# Agent Chat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** From the Pixel, the owner talks to the AI agents (Claude Code and iTerm2's own AI chats) running in iTerm2: lists chats and sees at a glance which agents are working, done or waiting; opens a chat and watches the reply stream in; sends messages with @-mentions of sessions; creates, mutes, deletes and truncates chats; and opens a workgroup to see each member agent's state.

**Architecture:** Chat-scoped messages from the Mac (`history`, `delivery`, `typingStatus`, `turnLifecycle`, `messagesRemoved`, `chatListChanged`, the chat-list and create replies, `aiAvailabilityChanged`) never touch the 64-slot, dropping `events` flow. The session's receive loop hands them, in wire order and bracketed per link, to an app-scoped `ChatRepository` through a second ordered, non-dropping sink, exactly as stream-scoped messages reach the `StreamController` today. The repository owns the chat list, the per-link subscription set, each open transcript (streaming deltas merged as iOS merges them), a per-chat activity state machine fed by `turnLifecycle` (typing is only the spinner), unread marks, and AI availability. Compose screens render its flows. A `HomeNavigator` with a Chats tab and a Sessions tab replaces the single-tab `SessionNavigator`; opening and closing a conversation, like a stream, is driven only by navigator calls.

**Tech Stack:** Kotlin 2.4, Jetpack Compose (Material 3, `NavigationBar`, `LazyColumn`, `TextFieldState` with input/output transformations), kotlinx-coroutines, kotlinx-serialization-json (tree API). Tests: JUnit 4, kotlinx-coroutines-test, the in-process fake relay and `FakeMac`. Fixtures: the Swift harness (Swift 6.2, `swift:6.2-noble` in Docker). No new runtime dependency.

**Spec:** `docs/superpowers/specs/2026-11-15-android-companion-design.md` (Goals 2, "What the owner experiences → Chat", Phases → Agent Chat), its protocol notes `docs/superpowers/specs/2026-11-15-protocol-notes.md` (App layer → Message catalogue, History and live events, Sending), and the Sessions notes `docs/superpowers/specs/2026-11-16-sessions-protocol-notes.md` §1 (revisions and gates). The rules and rulings in `docs/superpowers/handoff/2026-11-15-pairing-milestone-handover.md` apply unchanged.

Upstream citations are to tag `v3.7.7` (`e0025340`), with these prefixes:
`MSG` = `sources/Companion/Shared/CompanionMessages.swift`, `HB` = `sources/Companion/CompanionHostBridge.swift`,
`MS` = `sources/AITerm/Message.swift`, `CHAT` = `sources/AITerm/Chat.swift`, `LLM` = `sources/AITerm/LLM.swift`,
`RC` = `sources/AITerm/RemoteCommand.swift`, `MP` = `sources/ClaudeCode/Orchestration/MentionParser.swift`,
`SSID` = `sources/ClaudeCode/Orchestration/StableSessionID.swift`, `WW` = `sources/ClaudeCode/Orchestration/WorkgroupWatcher.swift`,
`CPV` = `Companion/CompanionCore/Sources/CompanionProtocol/CompanionProtocolVersion.swift`,
`IOS` = `Companion/iTerm2Companion`, `AM` = `IOS/App/AppModel.swift`, `CC` = `IOS/Connection/CompanionClient.swift`.
Mac-only files fetched from gitlab at `e0025340` (not in the sparse clone, not compiled by the harness):
`CB` = `sources/AITerm/ChatBroker.swift`, `CLM` = `sources/AITerm/ChatListModel.swift`, `CS` = `sources/AITerm/ChatService.swift`.
`AND` = `app/src/main/java/dev/bugobliterator/itermbuddy` (this repo).

**Scope:** One phase, Agent Chat, ending with the owner's on-device check. In: chat list with live states and search, conversation with streaming, send with session @-mentions, mention rendering and resolution, create (orchestrator or session-bound), delete, mute, delete-from-here (truncate), the AI-unavailable state, the workgroup view with member states, and a Chats tab beside Sessions. Out (later phases): answering approvals (`remoteCommandDecision`, `selectSessionResponse`, `linkSession`, `userCommand` publishes), Mac modal alerts, notifications and the foreground service (`pushStatus`, `syncSince`, `requestNotificationPermission`), the live session view's own chat composer and its auto-provide consent (`fetchAutoProvideConsent`, revision 7, `AM:3205-3440`), and voice dictation. Approval requests do appear in conversations, read-only, so the chat interoperates with them (see *Conversation*).

**Review:** not yet reviewed. Before implementation the lead runs the adversarial plan review the Sessions plan had, and records dispositions at the end.

## Global Constraints

- Package `dev.bugobliterator.itermbuddy`; `minSdk 34`; single module `app`; Pixel 9, Android 15+. Paths are under `AND` (main) and `app/src/test/java/dev/bugobliterator/itermbuddy/` (test) unless written in full.
- Target iTerm2 **3.7.7**, tag `v3.7.7` (`e0025340b49a2fc6415f490d77bbead5d3e0583e`).
- **Protocol revision: no bump.** `ProtocolRevision.REVISION` stays **13** (`AND/core/wire/ProtocolRevision.kt`), `MINIMUM_PEER` stays **11**. Agent Chat needs: `turnLifecycle` (8, `CPV` `turnLifecycleRevision`), `messagesRemoved`/`deleteMessages` (12, `messageDeletionRevision`), `aiAvailable` in the hello and `aiAvailabilityChanged` plus the typed `aiUnavailable` error (13, `aiDecouplingRevision`). Revision 13 already claims all three; until this phase the app ignored 12 and 13's chat parts, and after it the app fully supports what 13 claims. Revision 14 (Mac modal alerts) is not needed by chat; if the Browser Messages task has raised the constant to 15 first, nothing here changes. The task *Chat Messages* rewrites the constant's comment and pins `advertisedRevisionCoversAgentChat` (`REVISION >= 13`).
- Gates read the **Mac's** hello revision, as iOS does: mute offered when `macRevision >= 5` (`AM:2670-2672`), Delete From Here when `macRevision >= 12` (`AM:2678-2680`), and `turnLifecycle` is trusted when `min(macRevision, REVISION) >= 8` (`AM:1079-1081`, `HB:1078-1080`), which every pairable Mac (≥ 11) meets, so typing edges never drive turn state. `aiAvailable` absent in the hello means **true** (`MSG:986-991`).
- Request or send, exactly as iOS `CompanionClient` (`CC:95-225, 422-432`): requests (with a requestID) are `listChatsAndSessions`, `createChat`, `subscribe`, `resolveMentions`, `fetchWorkgroupInfo`; fire-and-forget sends are `deleteChat`, `deleteMessages`, `setChatMuted`, `unsubscribe`, `publish` (always `partial:false`, `CC:176-178`). Request bounds, all injectable: list 15 s (`AM:2223`), subscribe 15 s (`AM:2939`), workgroup 15 s (`AM:4287`), create and resolve the client's `rpc` 15 s.
- **Byte-exact wire parity.** Every phone→Mac chat message has an upstream sample, and its parity test passes two ways: the JSON trees are equal, and the Kotlin encoding re-serialised in the samples' canonical form (keys sorted, no whitespace, `/` written `\/`, non-ASCII as raw UTF-8, numbers as Swift writes them via `swiftDouble`) equals the sample's `json` text **byte for byte**. Every new Mac→phone case has a sample and a decode test. A mismatch is a failure.
- `UUID` fields (message `uniqueID`, `messageIDs`, `append.uuid`) are encoded as **uppercase** canonical strings, decoded case-insensitively and held uppercase (Swift `UUID`, `MS:509`). `Date` fields are milliseconds since 1970 as a JSON number (`WireCoding.swift:15-25`); decode accepts any number literal into a `Double`; the phone only sends whole milliseconds, written as an integer literal.
- The published user message is exactly `{"chatID", "author":"user", "content":{"plainText":{"_0":text}}, "sentDate", "uniqueID"}`, nothing else (`AM:3100-3118`, `MS:286-287`); `context`, `inResponseTo`, `responseID`, `agentReasoning`, `configuration`, `firstBlobRef` are omitted (nil). Text is trimmed of leading and trailing whitespace and newlines; empty text is never sent (`AM:3087-3091`).
- Forward compatibility, as upstream: an unknown `Message.Content` discriminator or an unknown `ClientLocal.Action` decodes to `Unsupported` content, not an error (`MS:732-758`, `MS:191-215`); `TurnEvent` unknown raw value decodes to `UNKNOWN_FUTURE` (`MS:68-81`). Android leniencies, each tested and named here: an unknown `SessionState` raw value decodes to `UNKNOWN` (upstream's synthesized enum throws, `WW:170-175`, and would lose the whole `workgroupInfo`); a message whose content body is corrupt is kept as `Unreadable` content instead of failing the whole `history` frame (upstream fails the frame, `MS:744-757`), while a corrupt `chatID`, `author`, `sentDate` or `uniqueID` still fails it.
- **Chat-scoped messages never reach `CompanionClient.events`** (a `MutableSharedFlow` with `extraBufferCapacity = 64` and `tryEmit`, which drops when full, `AND/core/CompanionClient.kt` `emitEvent`). They go to the ordered chat sink (*Chat Channel*): suspending, back-pressured, never dropped, bounded only by `ClientTimeouts.sinkStall`.
- **No content in logs or `toString`.** Chat titles, snippets, message text, attachment names and bytes, mention display names, session names in mention picks, workgroup and role names, `statusText`/`detailText`, drafts, search queries and chat icons are never logged. Chat IDs, message UUIDs, session GUIDs, workgroup IDs, counts, sizes, case names and revisions may be. Every type that carries such content overrides `toString()` to show lengths only, and has a sentinel test: `ChatSummary`, `ChatListEntry`, `ChatMessage`, `MessageBody` (every case), `Subpart`, `Attachment`, `MentionResolution`, `WorkgroupMember`, `WorkgroupInfo`, `UserTextMessage`, `PhoneMessage.Publish`, `PhoneMessage.CreateChat`, `MacMessage.History`, `MacMessage.Delivery`, `MacMessage.ChatsAndSessions`, `MacMessage.ChatListChanged`, `MacMessage.ChatCreated`, `MacMessage.MentionsResolved`, `MacMessage.WorkgroupInfoMessage`, `ChatInput.Message`, `ConversationState`, `ChatListState`, `TranscriptItem`, `MentionDraft`, `ChatSearch`.
- Memory budgets, all injectable: full transcripts are kept only for conversations on the navigation stacks plus the **3** most recently closed (iOS keeps only mounted ones, `AM:2844-2846`); file attachment bytes are kept only for `image/*` up to **4 MiB** each and **32 MiB** per transcript (others keep name, type and size); decoded inline images share one **48 MiB** bitmap budget; chat icons are decoded once per content hash into a **2 MiB** cache. Over a budget, bytes are dropped and the item shows its name, never an exception.
- The Noise reassembly cap stays 32 MiB (`DEFAULT_MAX_MESSAGE_BYTES`, `AND/core/noise/NoiseChannel.kt`). A `history` over it ends the link (`LinkDown(messageTooLarge = true)`); the chat whose subscribe was in flight is marked too large and is never re-subscribed automatically (*Chat Repository*). Raising the cap is an open question.
- Approvals and notifications are out of scope: the phone never sends `remoteCommandDecision`, `selectSessionResponse`, `linkSession`, a `userCommand` publish, `pushStatus`, `syncSince`, `messagesSince` or `notificationPermissionResponse` in this phase. A test pins that no chat path can produce them.
- The conversation and chat list follow the app theme; the live session view stays dark (owner decision).
- Tests: sandboxed (no fixed paths or ports), parallel (`maxParallelForks` = processors), virtual time; every timeout, interval, debounce and budget in production code is injectable.
- Builds, SDK installs, JVM tests and fixture generation run in cloud sessions; on-device checks run on the Mac with the Pixel over adb.
- Code is written by Opus 5.5 or better subagents, model passed explicitly; per-task review by an Opus subagent (read-only); **Mythos** reviews the critical tasks marked below and the whole phase before the on-device check. Hand each implementer only Global Constraints, Review Focus and its own `### Task:` section. Commit author `bugobliterator <siddharthbharatpurohit@gmail.com>`; concise messages; no session links anywhere. pnpm, never npm.
- Names are descriptive: no numbered or lettered tasks, tests, fixtures, files or branches.

## Review Focus

- **Streaming replies stay in order across reconnects.** A reply is a full opening `delivery` followed by `delivery` messages whose content is `append(string, uuid)` or `appendAttachment(attachment, uuid)` naming it (`HB:1825-1869`); `.commit` is hidden and never forwarded (`MS:549-562`), so the end of a turn is `turnLifecycle(ended)` only (`CS:494-505`). The Mac sends `history` and then the subscribe snapshot before any live delivery on that subscription (`HB:1411-1443`), and its control lane is never dropped or reordered (`sources/Companion/CompanionPriorityOutbox.swift:5-21`). The phone keeps that order end to end: the history reply and every delivery pass through one ordered sink, a history **replaces** the chat's transcript at its wire position, and nothing from an older link is applied after a newer link's history. Pinned in *Chat Channel* (`historyReplyPrecedesLiveDeliveriesInSink`, `chatInputsFromAnEndedLinkAreDropped`) and *Chat Repository* (`resubscribeOnReconnectReplacesTranscript`, `deltaBeforeHistoryOnNewLinkIsNotApplied`).
- **At-least-once versus duplicates.** Within a link every chat event arrives exactly once. Duplicates come from three places, each handled: the phone's own published message comes back as a `delivery` with the same `uniqueID` (`HB:1812-1823`) and is upserted, never appended twice; a second `subscribe` re-sends the whole history (`HB:1417`), which replaces rather than merges, so append deltas are never applied twice; and a delivery for a chat the phone has no transcript for is dropped (iOS merges only into an existing snapshot, `AM:4296-4313`). Pinned in *Transcript Merge* and *Chat Repository* (`ownEchoIsUpsertedNotDuplicated`, `secondHistoryReplacesWithoutDoubleAppend`).
- **The subscribe lifecycle per link.** Subscriptions live and die with the Mac's bridge, i.e. with the link (`AM:2427-2438`, `AM:3072-3074`); the Mac's subscription is not reference-counted and a later `unsubscribe` cuts a fresh one (`HB:489-493`, `AM:3040-3062`); turning AI off drops every subscription on the Mac and turning it back on does **not** restore them (`HB:2250-2269`, `AM:4470-4486`). One owner (`ChatSubscriptions`) computes the wanted set and diffs it on each link, list change, navigation change and AI flip; subscribes run one at a time; an unsubscribe is never sent for a chat still wanted. Pinned in *Chat Repository* (`subscriptionsAreReissuedOnEveryLink`, `unsubscribeNeverCutsAWantedChat`, `aiBackOnResubscribesWantedChats`).
- **Turn lifecycle versus typing edges.** "A mid-turn park corrupts typing edges" (`CPV` revision 8 note, `MSG:1031-1037`): typing goes false while a turn is parked on the user and true again on resume, while `TurnStatusModel` stays in progress across the park (`sources/AITerm/ChatListModel.swift:776-802`). Working/done/waiting therefore come from `turnLifecycle`, with typing only splitting *working* from *parked*. The subscribe snapshot re-seeds `typingStatus(true)` and `turnLifecycle(started)` only when they hold (`HB:1091-1107`), so a `history` reply is the reset point and a lost link makes state unknown, never stale. Transient edges (typing false just before `ended`, `started` just before typing true, `CS:688-695`) must not flash "Waiting". Pinned in *Chat Activity* (`parkShowsWaitingAfterDebounce`, `endOfTurnTypingEdgeDoesNotFlashWaiting`, `historyResetsTurnState`, `linkDownMakesStateUnknown`).
- **Mention resolution.** The phone parses mentions with a port of `MP` (including stableID canonical folding, `SSID:61-110`) checked against upstream vectors; resolutions are cached only on a hit, misses go to a retry gate reopened on chat open and reconnect (`AM:4171-4226`); a mention inside a code span is not linkified and markdown cannot eat a stableID's underscores (`MP:108-190`). Composer tokens serialise to `@<guid>` (`IOS/Views/MentionComposerView.swift:105-120`). Pinned in *Mentions* (`parserMatchesUpstreamVectors`, `missIsRetriedAfterReconnect`) and *Composer and Send* (`draftSerialisesTokensAsGuids`).
- **Unread and "done".** Every committed message bumps the chat (`CLM:122-129`) and the Mac pushes the whole list 0.5 s later (`HB:166-179`, `HB:277-292`); the phone's own publish bumps it too. A chat is unread only when it moved after the owner last saw it **and** the change was not the owner's own send; a turn that ends while the conversation is visible is not unread; marks survive process death and are wiped on unpair. Pinned in *Chat Repository* (`ownSendDoesNotMarkUnread`, `turnEndedWhileVisibleIsRead`, `seenMarksSurviveRestartAndClearOnUnpair`).
- **Large histories, paging and memory.** The protocol has no paging: `subscribe` returns the entire visible history in one frame (`HB:2229-2239`), again on every reconnect. Decoding runs on the receive loop under `sinkStall`; a history over the Noise cap ends the link; transcripts, attachments and icons are under byte budgets; the conversation list is lazy and re-renders only the bubble that changed. Pinned in *Chat Repository* (`tooLargeHistoryIsNotResubscribedInALoop`, `closedTranscriptsAreEvictedBeyondThree`), *Chat Messages* (`historyOfTwentyThousandMessagesDecodesWithinBudget`) and *Conversation* (`streamingDeltaRecomposesOnlyItsBubble`).
- **Sending interacts with a pending approval.** A message typed while the agent is parked on Allow/Deny abandons that approval on the Mac (`CS:192-227`, GitLab #12984). The composer says so before sending. Pinned in *Composer and Send* (`pendingRequestWarnsBeforeSend`).
- **A deleted chat is inferred only from the list.** Subscribing to a deleted or unknown chat returns an empty `history`, not an error (`HB:1411-1420`, `HB:2229-2239`), contrary to the iOS comment at `AM:2949-2953`. The phone latches "deleted" only from an authoritative list (`AM:2877-2900`). Pinned in *Chat Repository* (`emptyHistoryIsNotDeletion`, `chatMissingFromListIsDeleted`).

---

## Agent Chat

### Task: Agent Chat Wire Samples

Runs in the cloud with Docker (`swift:6.2-noble`), as the earlier harness tasks did.

**Files:**
- Create: `fixtures/harness/Sources/WireFixtures/AgentChatSamples.swift`, `fixtures/harness/Sources/WireFixtures/ChatVectors.swift`
- Modify: `fixtures/harness/Sources/WireFixtures/main.swift` (call the new generators), `fixtures/README.md`
- Create (generated): `fixtures/messages/agent-chat/*.json`, `fixtures/chat/streaming-merge.json`, `fixtures/chat/mention-parser.json`, `fixtures/chat/status-update-display.json`, `fixtures/chat/decode-tolerance.json`

**Interfaces:**
- Produces message samples in the existing `{"direction", "swiftValue", "json"}` format, built as iOS `CompanionClient` builds phone→Mac envelopes and as `CompanionHostBridge` builds Mac→phone ones, with every call site cited in the generator. `let agentChatAppRevision = 13`. Request IDs continue after live-session's 4–6: list 7, create (orchestrator) 8, create (session) 9, subscribe 10, resolve mentions 11, workgroup info 12. Chat IDs, message UUIDs and workgroup IDs are fixed uppercase UUIDs; session GUIDs reuse `SyntheticMac` (`LiveSessionSamples.swift`); titles and texts are synthetic. All dates are whole milliseconds except one fractional `sentDate` in `history.json` (decode only).
  - phone→Mac: `list-chats-and-sessions.json`, `create-chat-orchestrator.json` (title "Orchestrator", `AM:2587`), `create-chat-session.json` (title "New Chat", mode `{"session":{"guid":…}}`, `MSG:650-655`), `subscribe.json`, `unsubscribe.json`, `publish-plain-text.json` (text with a `@ptys_…` mention, a newline, `/`, an emoji and non-ASCII letters, so escaping and UTF-8 are pinned), `delete-chat.json`, `delete-messages.json` (two IDs), `set-chat-muted-on.json`, `set-chat-muted-off.json`, `resolve-mentions.json` (a stableID, `session:<uuid>`, `wg-<uuid>`, a bare legacy UUID), `fetch-workgroup-info.json`.
  - Mac→phone replies: `chats-and-sessions.json` (three chats: an orchestrator chat with `claimedScopes` holding one `wg-…` workgroup ID and one `session:…` scope and a 1×1 PNG `icon`; a session-bound chat with `muted:true` and a 200-character snippet, `HB:2203`; a chat with no snippet), `chats-and-sessions-ai-off.json` (empty chats, sessions present, `HB:448-455`), `chat-created.json`, `history.json`, `history-empty.json` (an unknown chat, `HB:2229-2239`), `mentions-resolved.json` (a session hit, a workgroup hit whose `sessionGuid` is the leader, a miss with only `identifier`, `HB:1989-2002`), `workgroup-info.json` (members in `idle`, `working`, `waiting`, `unknown` and one unlaunched member with no session and state `unknown`, `HB:2164-2200`), `error-ai-unavailable.json` (`aiUnavailable`, "AI features are turned off on the paired Mac.", `HB:2276-2298`), `error-unknown-workgroup.json` (`unknownSession`, "That workgroup no longer exists.").
  - Mac→phone events (no requestID): `delivery-user-echo.json`, `delivery-agent-opening.json` (markdown, `partial:false`), `delivery-append.json` (`partial:true`), `delivery-append-attachment-code.json`, `delivery-append-attachment-status.json`, `delivery-remote-command-classic.json` (`safe:false`), `delivery-remote-command-external.json`, `delivery-select-session-request.json`, `delivery-workgroup-permission-request.json`, `delivery-enable-orchestration-request.json`, `delivery-notice.json`, `delivery-watcher-event.json`, `typing-status-on.json`, `typing-status-off.json`, `turn-lifecycle-started.json`, `turn-lifecycle-ended.json`, `messages-removed.json`, `chat-list-changed.json`, `ai-availability-changed-on.json`.
  - `history.json` holds one message of every content kind the Mac forwards (every `Content` case for which `hiddenFromClient` is false, `MS:549-562`): `plainText` with and without `context`, `markdown`, `multipart` with each `Subpart` and each attachment type (`code`, `file` image, `file` non-image, `fileID`, `statusUpdate`), `explanationRequest`, `explanationResponse`, `remoteCommandRequest` classic and external, `watcherEvent`, `selectSessionRequest`, `clientLocal` with each of the ten actions (`MS:131-189`), `terminalCommand`. Where an upstream initializer is impractical (`explanationRequest` carries `iTermCodableLocatedString`), the generator decodes hand-written JSON through upstream's own decoder and re-encodes it, and says so.
- Produces `fixtures/chat/decode-tolerance.json`: `[{name, json, upstream: "decodes"|"rejects", upstreamValue?, upstreamError?}]`, each checked by the harness against upstream's decoder: a delivery with a future content case (`unsupported`), a delivery with a future `clientLocal` action (`unsupported`), `turnLifecycle` with event `"paused"` (`unknownFuture`), `chats-and-sessions` with `muted` cut (what a Mac older than revision 5 sends; nil; no pairable Mac omits it, so this is tolerance only), a hello reply without `aiAvailable` (a pre-13 Mac; nil), `workgroupInfo` with member state `"sleeping"` (upstream **rejects**; Android reads `UNKNOWN`), and a message with a corrupt `content` body of a known case (upstream rejects the frame; Android keeps `Unreadable`).
- Produces `fixtures/chat/streaming-merge.json`: `[{name, chatID, transcript: [Message], deltas: [Message], expected: [Message]}]`. The harness applies iOS's merge rule (`AM:5215-5277`: upsert by `uniqueID`; `append` mutates only `plainText`/`markdown`/`multipart` targets with `Message.append(_:useMarkdownIfAmbiguous: true)`, `MS:655-690`; `appendAttachment` with `Message.append(_:vectorStoreID: nil)`, `MS:627-653`, and `Attachment.appending`, `LLM:158-190`; a delta for a missing target starts an agent message with `.markdown(chunk)` or `.multipart([.attachment])` dated by the delta) by reimplementing that 40-line rule over upstream's compiled `Message.append`. AppModel itself is not compiled; the generator cites the lines. Cases: append to markdown, plainText, multipart ending in markdown, multipart ending in attachment; append to a missing target; append to a non-text target (ignored); code attachments with the same `id` coalesce, different `id` do not; status updates always coalesce; upsert replaces in place; the user echo replaces the optimistic copy; a 1 000-delta reply.
- Produces `fixtures/chat/mention-parser.json`: `[{name, text, atSignOptional, mentions: [{location, length, prefix, token, identifier}]}]` from `MentionParser.mentions(in:atSignOptional:)` (UTF-16 ranges), plus `split: [{identifier, prefix, token} | {identifier, invalid: true}]` from `MentionParser.split`. Cases: each identifier form, lowercase and Crockford-confusable stableIDs (folded), a bare stableID with and without `atSignOptional`, a bare UUID (never a mention), glued left boundary, trailing `-x` (no match), mentions next to emoji (UTF-16 offsets), two stableIDs in one paragraph.
- Produces `fixtures/chat/status-update-display.json`: `[{name, statusUpdate, displayString}]` from `LLM.Message.StatusUpdate.displayString` (`LLM:106-130, 534-552`).

- [ ] **Step: Extend the harness and generate** with `./sync-upstream.sh && swift run wire-fixtures ../` in Docker. The harness exits non-zero if any list is empty, if any sample does not decode back to the same value through `WireCoding` (except the decode-tolerance entries, which must match their recorded verdict), if `history.json` lacks any visible `Content` case or any `ClientLocal.Action`, if a phone→Mac publish carries any key beyond the five in Global Constraints, or if a phone-sent date is not whole milliseconds. Two runs write byte-identical files.
- [ ] **Step: Document** every new file in `fixtures/README.md` (format, call sites, synthetic data, the decode-tolerance verdicts).
- [ ] **Step: Commit** ("Add agent chat message samples and chat vectors").

### Task: Chat Messages

**Files:**
- Create: `core/wire/ChatMessages.kt` (new `PhoneMessage`/`MacMessage` cases and codecs), `core/chat/ChatModel.kt`, `core/chat/MessageBody.kt`, `core/chat/MessageDecoding.kt`, `core/wire/WireIdentifiers.kt` (UUID and date helpers)
- Modify: `core/wire/SessionMessages.kt` (the sealed interfaces gain the cases), `core/wire/Envelope.kt` (`decodeMacCase` dispatch; `hello` keeps `raw`), `core/wire/ProtocolRevision.kt` (comment only: 13 now covers Agent Chat; value unchanged)
- Modify (fallout): `core/wire/StreamMessagesTest.kt` (`higherRevisionExtrasAreUnsupported` becomes `revisionThirteenChatEventsDecode`: `messages-removed` and `ai-availability-changed` now decode to `MessagesRemoved` and `AiAvailabilityChanged`; its `toString` sentinel case moves to a real `Unsupported` case such as `browserEditingChanged`)
- Test: `core/wire/ChatMessagesTest.kt`, `core/chat/MessageDecodingTest.kt`

**Interfaces:**
- Produces in `core/chat/ChatModel.kt`:
  - `enum class Participant { USER, AGENT }` (strict: anything else throws, `MS:57-60`); `enum class TurnEvent { STARTED, ENDED, UNKNOWN_FUTURE }`; `enum class SessionState { IDLE, WORKING, WAITING, UNKNOWN }` (unknown raw → `UNKNOWN`, Android leniency).
  - `class ChatSummary(val id: String, val title: String, val createdMillis: Double, val lastModifiedMillis: Double, val orchestrationEnabled: Boolean, val terminalSessionGuid: String?, val browserSessionGuid: String?, val claimedScopes: List<String>, val icon: ByteArray?)` with content `equals` (`CHAT:13-88`; other `Chat` fields are read past, not validated). `val workgroupScopes: List<String>` = `claimedScopes` without the `session:` prefix ones.
  - `data class ChatListEntry(val chat: ChatSummary, val snippet: String?, val muted: Boolean?)` (`MSG:64-71`).
  - `data class ChatMessage(val chatID: String, val author: Participant, val body: MessageBody, val sentMillis: Double, val uniqueID: String)`; `val hidden: Boolean` per `MS:549-562`; `val isInteractiveRequest: Boolean` (classic `remoteCommandRequest`, `selectSessionRequest`, `clientLocal` `workgroupPermissionRequest`, `enableOrchestrationRequest`, `offerLink`; the same set `HB`'s notifier treats as blocking plus iOS's button bubbles, `sources/Companion/CompanionAgentActivityNotifier.swift:48-75`, `IOS/Views/MessageBubbleView.swift:216-375`).
  - `data class MentionResolution(val identifier: String, val displayName: String?, val sessionGuid: String?, val workgroupID: String?)` (`MSG:76-90`); `data class WorkgroupMember(…)`, `data class WorkgroupInfo(val workgroupID: String, val name: String, val members: List<WorkgroupMember>)` (`MSG:118-136`); `sealed interface NewChatMode { data object Orchestrator; data class Session(val guid: String) }` (encoded `{"orchestrator":{}}` / `{"session":{"guid":…}}`).
  - `data class UserTextMessage(val chatID: String, val text: String, val sentMillis: Long, val uniqueID: String)` (uppercase UUID, whole ms).
- Produces in `core/chat/MessageBody.kt`: `sealed interface MessageBody` with `PlainText(text)`, `Markdown(text)`, `Multipart(parts: List<Subpart>)`, `Append(text, target)`, `AppendAttachment(attachment, target)`, `Commit(target)`, `CommandRequest(classic: Boolean, functionName: String, permissionCategory: String?, externalDescription: String?, safe: Boolean?)` (classic `markdownDescription` is computed upstream from `RemoteCommand.Content`, `RC:404`; porting it is the Approvals phase), `SelectSessionRequest(terminal: Boolean)`, `ClientNotice(action: ClientAction)`, `WatcherEvent(detail)`, `TerminalCommand(command, output)`, `ExplanationRequest(snippet)`, `ExplanationResponse(markdown)`, `Hidden(case)` (for the hidden kinds), `Unsupported`, `Unreadable(case)`; `sealed interface Subpart { PlainText; Markdown; AttachmentPart(attachment); Context }`; `data class Attachment(val inline: Boolean, val id: String, val kind: AttachmentKind)` with `AttachmentKind { Code(text); StatusUpdate(display: String); File(name, mimeType, size: Int, bytes: ByteArray?); FileId(id, name) }`; `sealed interface ClientAction` with the ten actions (`MS:131-189`) carrying only what is displayed (request IDs and workgroup IDs kept for the Approvals phase).
- Produces in `core/wire/ChatMessages.kt`:
  - `PhoneMessage.ListChatsAndSessions`, `CreateChat(title, mode)`, `DeleteChat(chatID)`, `DeleteMessages(chatID, messageIDs: List<String>)`, `SetChatMuted(chatID, muted)`, `Subscribe(chatID)`, `Unsubscribe(chatID)`, `Publish(message: UserTextMessage, toChatID: String)` (`partial:false` always), `ResolveMentions(identifiers)`, `FetchWorkgroupInfo(workgroupID)` (`MSG:695-761`).
  - `MacMessage.ChatsAndSessions(chats, sessions: List<SessionSummary>)`, `ChatCreated(entry)`, `History(chatID, messages, maxSeq: Long)`, `Delivery(message, chatID, partial)`, `MessagesRemoved(chatID, messageIDs)`, `TypingStatus(isTyping, participant, chatID)`, `TurnLifecycle(event, chatID)`, `MentionsResolved(resolutions)`, `WorkgroupInfoMessage(info)`, `ChatListChanged(chats)`, `AiAvailabilityChanged(available)` (`MSG:1003-1141`). `HelloReply` gains `val aiAvailable: Boolean` (absent or null → true).
- Produces in `core/wire/WireIdentifiers.kt`: `fun newMessageId(): String` (uppercase), `JsonObject.uuid(case, key)` (case-insensitive, canonical form, returned uppercase), `JsonObject.millis(case, key): Double`.

- [ ] **Step: Failing tests** in `ChatMessagesTest`, each looping over its fixture directory and naming the failing file:
  - `agentChatPhoneSamplesEncodeExactly`: every `phoneToMac` file in `fixtures/messages/agent-chat/` equals Kotlin's encoding as a tree **and** as canonical bytes (Global Constraints); a sample with no table entry fails.
  - `agentChatMacSamplesDecode`: every `macToPhone` file decodes to the expected case; `history.json` yields one message per listed kind with the expected `MessageBody` case, and none `Unsupported` or `Unreadable`.
  - `decodeToleranceMatchesTheRecordedVerdicts`: every `decode-tolerance.json` entry upstream decodes is decoded to the same meaning; the two upstream rejects decode to `SessionState.UNKNOWN` and `MessageBody.Unreadable` with the frame kept.
  - `publishCarriesExactlyFiveMessageKeys`, `publishedUuidIsUppercase`, `publishedSentDateIsAnIntegerLiteral`.
  - `helloWithoutAiAvailableMeansAvailable`.
  - `revisionThirteenChatEventsDecode`, `advertisedRevisionCoversAgentChat`.
  - `participantOutsideTheEnumIsRejected` (`WireDecodeException`), `lowercaseUuidDecodesUppercase`, `fractionalDatesDecode`.
  - `noChatPathEncodesApprovalOrPushMessages`: the `PhoneMessage` cases defined in this file encode none of `remoteCommandDecision`, `selectSessionResponse`, `linkSession`, `pushStatus`, `syncSince`, `messagesSince`, `notificationPermissionResponse`, and `Publish` cannot carry anything but `plainText`.
  - `chatContentIsRedactedInToString`: every type in the Global Constraints list, built with the sentinel `"kittens-secret"` in every text, title, snippet, name and attachment name; no `toString()` contains it.
- [ ] **Step: Failing tests** in `MessageDecodingTest`: `hiddenKindsAreMarkedHidden` (`MS:549-562`), `interactiveRequestsAreFlagged`, `statusUpdateDisplayMatchesUpstream` (every `status-update-display.json` entry), `nonImageFileBytesAreNotKept`, `imageOverFourMebibytesKeepsNameOnly`, `historyOfTwentyThousandMessagesDecodesWithinBudget` (a generated 20 000-message history of 30 MiB decodes in under 3 s on the CI runner and retains under 64 MiB of heap, measured with a heap-retained estimate of the result).
- [ ] **Step: Run** `./gradlew testDebugUnitTest --tests '*ChatMessagesTest*' --tests '*MessageDecodingTest*' --tests '*StreamMessagesTest*'`. Expected: FAIL.
- [ ] **Step: Implement** with the `Envelope.kt` helpers; decoding ignores unknown keys and validates only fields it reads, as before.
- [ ] **Step: Run** the same, then the full `./gradlew testDebugUnitTest`. Expected: PASS.
- [ ] **Step: Commit** ("Add agent chat messages").

### Task: Chat Channel

**Critical: Mythos review.** Changes the receive loop every frame passes through.

**Files:**
- Create: `core/chat/ChatInput.kt`
- Modify: `core/CompanionSession.kt` (route chat-scoped messages to the chat sink under the same lock and link id as stream inputs; complete a waiting request only after the sink took the reply), `core/CompanionClient.kt` (`chatSink` constructor parameter on both constructors; `ChatInput.LinkUp` after `StreamInput.LinkUp` and before `Connected`; `ChatInput.LinkDown` after `StreamInput.LinkDown`, uncancellably; a missed chat `LinkUp` ends the link as a missed stream `LinkUp` does), `fakemac/FakeMac.kt` (test: a scripted chat Mac)
- Test: `core/ChatChannelTest.kt`, `fakemac/FakeChatMac.kt` (test helper)

**Interfaces:**
- Produces in `core/chat/ChatInput.kt`:
  - `sealed interface ChatInput { data class LinkUp(val linkId: Long, val macRevision: Int, val aiAvailable: Boolean); data class LinkDown(val linkId: Long, val messageTooLarge: Boolean = false); data class Message(val linkId: Long, val requestID: Long?, val message: MacMessage) }`
  - `fun interface ChatSink { suspend fun accept(input: ChatInput) }`, with the same contract as `StreamSink` (`AND/core/stream/StreamInput.kt`): cancellable, never waits for a Mac reply, may `send` from inside `accept`.
  - `fun isChatScoped(message: MacMessage): Boolean`: true for `History`, `Delivery`, `TypingStatus`, `TurnLifecycle`, `MessagesRemoved`, `ChatListChanged`, `ChatsAndSessions`, `ChatCreated`, `AiAvailabilityChanged`. Not for `MentionsResolved`, `WorkgroupInfoMessage` or `Error` (plain replies).
- Routing in `CompanionSession.handle`, after decode: stream-scoped as today; else chat-scoped → `chatSink.accept(Message(linkId, requestID, message))` whether or not a request waits, then complete its waiter; else as today. The lock that orders stream inputs orders chat inputs too, so `ChatInput.LinkUp` precedes every `ChatInput.Message` on its link.
- `FakeChatMac` (test): answers `listChatsAndSessions`, `subscribe` (history then the snapshot), `createChat`, `resolveMentions`, `fetchWorkgroupInfo` from the `agent-chat` samples; can stream a scripted reply (opening delivery, N appends, typing edges, `turnLifecycle`), push `chatListChanged`, flip AI, and records what the phone sent.

- [ ] **Step: Failing tests** in `ChatChannelTest` (in-process fake relay, virtual time):
  - `historyReplyPrecedesLiveDeliveriesInSink`: FakeChatMac answers `subscribe` and immediately sends 50 appends; the sink sees `History` then the 50 deliveries in order, and the `request` returns `History` only after the sink took it.
  - `chatEventsNeverReachEvents`: with a collector on `events`, 1 000 chat deliveries go to the sink and none to `events`; a slow sink (5 ms per item) loses none.
  - `chatLinkMarkersBracketEachConnection`: connect, drop, reconnect → `LinkUp(1, 15, true)`, `LinkDown(1)`, `LinkUp(2, 15, true)`; `aiAvailable` false when the hello says false and true when the key is absent.
  - `chatInputsFromAnEndedLinkAreDropped`: nothing from link 1 reaches the sink after `LinkDown(1)`.
  - `streamAndChatInputsKeepWireOrder`: interleaved media, stream control and chat deliveries reach their sinks in wire order, each after its link's `LinkUp`.
  - `missedChatLinkUpEndsTheLink`, `chatLinkDownSurvivesCancellation` (as the stream versions in `MediaChannelTest`).
  - `oversizedHistoryEndsLinkWithFlag`: a 33 MiB history → `ChatInput.LinkDown(linkId, messageTooLarge = true)`, then a reconnect.
  - `sessionTreesStillUseEvents`: an unsolicited `sessionTree` still reaches `events` with its arrival stamp.
- [ ] **Step: Run** `./gradlew testDebugUnitTest --tests '*ChatChannelTest*' --tests '*MediaChannelTest*' --tests '*CompanionClientTest*'`. FAIL.
- [ ] **Step: Implement.** Then run the unit tests and `scripts/relay-it.sh`. PASS.
- [ ] **Step: Commit** ("Route chat messages to an ordered chat sink").

### Task: Transcript Merge

Pure Kotlin. Mirrors iOS `apply` (`AM:5208-5277`) and the Mac's text and attachment append (`MS:627-690`, `LLM:158-190`).

**Files:**
- Create: `core/chat/TranscriptMerge.kt`, `core/chat/Transcript.kt`
- Test: `core/chat/TranscriptMergeTest.kt`

**Interfaces:**
- Produces: `class Transcript` (an ordered list of `ChatMessage` with an index by `uniqueID`, a `version: Long` and the changed message's index from the last apply; never exposes a mutable list), `fun Transcript.applyDelivery(message: ChatMessage, chatID: String): MergeResult` with `MergeResult { Appended(index); Replaced(index); Ignored(reason) }`, `fun Transcript.removeAll(ids: Set<String>): Int`, `fun Transcript.suffixFrom(uniqueID: String): List<String>` (`AM:2660-2669`).
- Rules: hidden messages are ignored (`AM:4296-4297`); `append` mutates a `PlainText`, `Markdown` or `Multipart` target with upstream's `append(_:useMarkdownIfAmbiguous: true)` semantics and ignores any other target; `appendAttachment` likewise with `append(_:vectorStoreID: nil)`; a delta whose target is missing starts an agent message (`Markdown(chunk)` or `Multipart([attachment])`) dated by the delta, as iOS does (the Mac instead drops it, `CLM:325-350`; iOS parity is chosen because the phone may have missed the opening message only by joining mid-stream, which `history` then covers); `commit` is ignored; anything else upserts by `uniqueID` in place.

- [ ] **Step: Failing tests** in `TranscriptMergeTest`: `streamingMergeMatchesUpstreamVectors` (every `streaming-merge.json` case gives the expected transcript, compared as `MessageBody` trees), `ownEchoIsUpsertedNotDuplicated`, `appendToNonTextTargetIsIgnored`, `hiddenDeliveryIsIgnored`, `removeAllIsIdempotent`, `suffixFromUnknownIdIsEmpty`, `applyIsLinearInDeltas` (10 000 appends to one message in a 5 000-message transcript complete in under 1 s and copy no other message).
- [ ] **Step: Run** `./gradlew testDebugUnitTest --tests '*TranscriptMergeTest*'`. FAIL. **Implement.** **Run.** PASS.
- [ ] **Step: Commit** ("Merge streamed replies into transcripts").

### Task: Chat Activity

Pure Kotlin: the per-chat working/done/waiting state machine. Upstream has no chat-level state on the phone; the inputs and their meaning are upstream's.

**Files:**
- Create: `core/chat/ChatActivity.kt`
- Test: `core/chat/ChatActivityTest.kt`

**Interfaces:**
- Produces: `enum class AgentState { UNKNOWN, IDLE, WORKING, WAITING, DONE }` and `class ChatActivityTracker(nowMillis: () -> Long, parkDebounceMillis: Long = 1_500)` with `fun onHistory(chatID: String, lastVisible: ChatMessage?)`, `fun onTyping(chatID: String, participant: Participant, isTyping: Boolean)`, `fun onTurn(chatID: String, event: TurnEvent, visible: Boolean)`, `fun onDelivery(chatID: String, message: ChatMessage)`, `fun onLinkDown()`, `fun onUnsubscribed(chatID: String)`, `fun markRead(chatID: String)`, `fun tick()`, `val states: StateFlow<Map<String, AgentState>>`.
- Rules:
  - `onHistory` resets the chat to `IDLE` (typing false, no turn): the snapshot that follows re-seeds what still holds (`HB:1091-1107`).
  - `turnLifecycle(started)` → turn in progress; `ended` → no turn and, unless the chat is visible, `DONE` until read; `UNKNOWN_FUTURE` ignored (`AM:3664-3671`). User-participant typing is ignored (`AM:4323-4331`).
  - With a turn in progress: typing true → `WORKING`; typing false → `WAITING` once it has stayed false for `parkDebounceMillis`, or at once when the last visible message is an interactive request. The debounce absorbs `started` arriving just before typing true and typing false arriving just before `ended` (`CS:494-505, 688-695`).
  - `onLinkDown` makes every chat `UNKNOWN` (typing is not re-sent after a drop, `AM:2449-2454`); `onUnsubscribed` makes that chat `UNKNOWN`.

- [ ] **Step: Failing tests** in `ChatActivityTest`: `turnStartedThenTypingIsWorking`, `turnEndedWhileHiddenIsDoneUntilRead`, `turnEndedWhileVisibleIsIdle`, `parkShowsWaitingAfterDebounce`, `endOfTurnTypingEdgeDoesNotFlashWaiting`, `startBeforeTypingDoesNotFlashWaiting`, `interactiveRequestShowsWaitingAtOnce`, `historyResetsTurnState`, `snapshotAfterHistoryReseedsWorking` (`history`, `typing(true)`, `started` → `WORKING`), `linkDownMakesStateUnknown`, `userTypingIsIgnored`, `futureTurnEventIsIgnored`.
- [ ] **Step: Run** `./gradlew testDebugUnitTest --tests '*ChatActivityTest*'`. FAIL. **Implement.** **Run.** PASS.
- [ ] **Step: Commit** ("Track agent working, waiting and done states").

### Task: Chat Repository

**Critical: Mythos review.** Owns subscriptions, ordering and memory for the whole feature.

**Files:**
- Create: `core/chat/ChatRepository.kt`, `core/chat/ChatSubscriptions.kt`, `core/chat/ChatSeenStore.kt` (interface plus a file-backed implementation in `noBackupFilesDir`, and an in-memory one for tests)
- Modify: `AppGraph.kt` (`SessionsGraph` becomes `HomeGraph` and `wireSessions` becomes `wireHome`: the client is built with a forwarding chat sink to the repository; unpair cleanups gain `chats::clear` and `seenStore::clear`), `CompanionApp.kt` (build the repository and the seen store), `core/sessions/UnpairCleanup.kt` (doc only)
- Test: `core/chat/ChatRepositoryTest.kt`, `core/chat/ChatSubscriptionsTest.kt`, `core/chat/ChatSeenStoreTest.kt`, `core/sessions/UnpairCleanupTest.kt` (extended)

**Interfaces:**
- Consumes: `ChatInput`/`ChatSink` (*Chat Channel*), `StreamLink` (`send`/`request` with `onLink`), `Transcript` (*Transcript Merge*), `ChatActivityTracker` (*Chat Activity*).
- Produces:
  - `sealed interface ChatListState { data object Loading; data class Loaded(val entries: List<ChatListEntry>, val refreshing: Boolean); data class Failed(val retryable: Boolean); data object AiUnavailable }`
  - `sealed interface ConversationState { data object Loading; data class Shown(val transcript: Transcript, val stale: Boolean, val deleted: Boolean, val sendable: Boolean); data class Failed(val retryable: Boolean); data object TooLarge }` (`stale` = served from cache while a refetch is out or after it failed, iOS's soft banner, `AM:2934-3010`; `deleted` from the list only, `AM:2877-2900`; `sendable` = connected, AI available, not deleted)
  - `data class ChatRow(val entry: ChatListEntry, val state: AgentState, val unread: Boolean)`
  - `class ChatRepository(link: StreamLink, scope: CoroutineScope, seen: ChatSeenStore, timeouts: ChatTimeouts = ChatTimeouts(), watchCount: Int = 6, nowMillis: () -> Long, log: (String) -> Unit) : ChatSink` with `val list: StateFlow<ChatListState>`, `val rows: StateFlow<List<ChatRow>>`, `val aiAvailable: StateFlow<Boolean>`, `val macRevision: StateFlow<Int?>`, `fun conversation(chatID: String): StateFlow<ConversationState>`, `fun open(chatID: String)`, `fun close(chatID: String)`, `fun setVisible(chatID: String?)`, `fun refresh()`, `fun clear()`, and the actions the later tasks add (`send`, `create`, `delete`, `setMuted`, `deleteFrom`).
  - `data class ChatTimeouts(val list: Duration = 15.seconds, val subscribe: Duration = 15.seconds, val deleteListWait: Duration = 3.seconds)`
  - `class ChatSubscriptions` (pure): `fun wanted(open: List<String>, entries: List<ChatListEntry>, watchCount: Int, tooLarge: Set<String>): List<String>` (open conversations first, then the `watchCount` most recently modified other chats, minus too-large ones) and `fun plan(current: Set<String>, wanted: List<String>): SubscriptionPlan(subscribe: List<String>, unsubscribe: List<String>)`.
  - `interface ChatSeenStore { fun seen(chatID: String): Double?; fun markSeen(chatID: String, lastModifiedMillis: Double); fun clear() }`: chat IDs and timestamps only, no content.
- Rules:
  - On `LinkUp`: record revision and AI; if AI is available, request the list (bounded by `timeouts.list`) and then subscribe the wanted set one chat at a time (`AM:2401-2438`); if not, `AiUnavailable` and no chat requests (the Mac refuses them, `HB:2276-2330`). On `LinkDown`: no subscriptions, every activity `UNKNOWN`, open conversations keep their transcript marked `stale`.
  - `ChatsAndSessions`, `ChatCreated` and `ChatListChanged` are applied in sink order; the list is replaced wholesale (`AM:4346-4358`); chats no longer listed lose their cached transcript and activity (`AM:2242-2254`), and an open one is latched `deleted` with iOS's notice text "This chat was deleted on your Mac. You can keep reading it until you leave, but nothing new can be sent." (`AM:441-442`). `ChatCreated` inserts the entry at the top only if missing (`AM:2587-2591`).
  - `History(chatID)` replaces that chat's transcript (hidden messages dropped) if it is open or cached; for a watched-only chat it feeds `ChatActivityTracker.onHistory` with the last visible message and is then dropped, so watched chats cost no transcript memory. `Delivery` merges only into an existing transcript (`AM:4296-4313`) and always feeds the tracker. `MessagesRemoved` removes from transcript and cache (`AM:4336-4345`).
  - `AiAvailabilityChanged(false)` → `AiUnavailable`, transcripts kept read-only; `(true)` after false → refetch the list and resubscribe the wanted set (`AM:4460-4486`).
  - Unread: `unread = lastModifiedMillis > seen(chatID)`, or `DONE`. Opening a conversation and each list update while it is visible mark it seen; a successful `send` marks the chat seen at the next list value that includes the bump, so the owner's own message never marks unread.
  - Too large: subscribes run one at a time, so a `LinkDown(messageTooLarge = true)` while a subscribe is out names that chat; it joins `tooLarge` for the process, its conversation shows `TooLarge`, and it is subscribed again only from an explicit Try again.
  - Memory: transcripts are kept for open conversations and the 3 most recently closed (LRU); `clear()` (unpair) drops everything and ignores every input from links before it.

- [ ] **Step: Failing tests** in `ChatRepositoryTest` (real `CompanionClient`, `FakeChatMac`, virtual time):
  - `listIsFetchedOnEveryLinkAndReplacedByPushes`, `aiOffShowsUnavailableAndSendsNoChatRequest`, `aiBackOnResubscribesWantedChats`.
  - `subscriptionsAreReissuedOnEveryLink`, `subscribesRunOneAtATime`, `unsubscribeNeverCutsAWantedChat` (close a conversation while the same chat is in the watch set: no `unsubscribe` is sent), `watchSetFollowsRecency`.
  - `resubscribeOnReconnectReplacesTranscript`, `deltaBeforeHistoryOnNewLinkIsNotApplied`, `secondHistoryReplacesWithoutDoubleAppend`, `ownEchoIsUpsertedNotDuplicated`, `deliveryForUncachedChatIsDropped`.
  - `emptyHistoryIsNotDeletion`, `chatMissingFromListIsDeleted`, `chatBackInListClearsDeleted`.
  - `ownSendDoesNotMarkUnread`, `turnEndedWhileVisibleIsRead`, `activityFromListMarksUnwatchedChatUnread`.
  - `tooLargeHistoryIsNotResubscribedInALoop`: FakeChatMac answers one chat's subscribe with 33 MiB; after the reconnect that chat is `TooLarge`, no second subscribe for it is sent, the other wanted chats are subscribed.
  - `closedTranscriptsAreEvictedBeyondThree`, `watchedOnlyHistoryKeepsNoTranscript`.
  - `clearDropsEverythingAndIgnoresOldLinks`.
- [ ] **Step: Failing tests** `ChatSubscriptionsTest.openChatsComeFirst`, `tooLargeChatsAreExcluded`, `planIsAMinimalDiff`; `ChatSeenStoreTest.seenMarksSurviveRestartAndClearOnUnpair` (temp dir), `storeHoldsNoContent` (file bytes contain only IDs, digits and JSON punctuation); `UnpairCleanupTest.localAndRemoteUnpairClearChats`.
- [ ] **Step: Run** `./gradlew testDebugUnitTest --tests '*ChatRepositoryTest*' --tests '*ChatSubscriptionsTest*' --tests '*ChatSeenStoreTest*' --tests '*UnpairCleanupTest*'`. FAIL. **Implement.** **Run.** PASS.
- [ ] **Step: Commit** ("Add the chat repository with per-link subscriptions").

### Task: Mentions

**Files:**
- Create: `core/chat/StableSessionId.kt`, `core/chat/MentionParser.kt`, `core/chat/MentionResolver.kt`
- Test: `core/chat/MentionParserTest.kt`, `core/chat/MentionResolverTest.kt`

**Interfaces:**
- Produces: `object StableSessionId { const val PREFIX = "ptys_"; fun canonical(candidate: String): String? }` (port of `SSID:27-110`, Crockford alphabet and check character); `data class Mention(val start: Int, val end: Int, val prefix: String?, val token: String) { val identifier: String }` with UTF-16 offsets; `object MentionParser { fun mentions(text: String, atSignOptional: Boolean = false): List<Mention>; fun split(identifier: String): Pair<String?, String>?; fun protectedSpans(markdown: String): List<IntRange> }` (`MP:16-205`; `protectedSpans` is the `unprotectedSpanRegex` set, `MP:111-145`, used by the renderer instead of upstream's random-nonce placeholders).
- Produces: `class MentionResolver(link: StreamLink, scope: CoroutineScope, log: (String) -> Unit)` with `val resolutions: StateFlow<Map<String, MentionResolution>>`, `fun note(texts: List<String>)`, `fun reopenRetryGate()`. Rules (`AM:4141-4226`): scan with `atSignOptional = false` for user text and `true` for agent text (`MP:62-74`); request only identifiers not resolved, not in flight and not in the retry gate; cache a hit (non-null `displayName`) only; a miss or a failed request goes to the gate; the gate reopens on conversation open, on each `LinkUp`, and on AI back on. A resolved entry renders its display name; an unresolved one renders "[defunct session]" only once the Mac answered a miss (`MSG:80-82`), otherwise the raw `@identifier`.

- [ ] **Step: Failing tests**: `MentionParserTest.parserMatchesUpstreamVectors` (every `mention-parser.json` case, offsets included), `splitMatchesUpstreamVectors`, `bareUuidIsNeverAMention`, `codeSpansAreProtected`; `MentionResolverTest.hitIsCachedMissIsGated`, `missIsRetriedAfterReconnect`, `inFlightIdentifiersAreNotRequestedTwice`, `failedRequestGatesTheBatch`, `resolutionsHoldNoContentInToString`.
- [ ] **Step: Run** `./gradlew testDebugUnitTest --tests '*MentionParserTest*' --tests '*MentionResolverTest*'`. FAIL. **Implement.** **Run.** PASS.
- [ ] **Step: Commit** ("Parse and resolve session and workgroup mentions").

### Task: Chat List

**Files:**
- Create: `ui/chats/ChatListScreen.kt`, `ui/chats/ChatRowView.kt`, `ui/chats/ChatSearch.kt`, `ui/chats/ChatTimestamps.kt`, `ui/chats/ChatIconCache.kt`, `ui/chats/AiUnavailablePanel.kt`
- Test: `ui/chats/ChatListRulesTest.kt`, `ui/chats/ChatTimestampsTest.kt`, `ui/chats/ChatIconCacheTest.kt`

**Interfaces:**
- Consumes: `ChatRepository.rows`, `list`, `aiAvailable`, `macRevision`; `MentionResolver` (snippets show resolved names, `AM:4228-4247`).
- Produces: `class ChatSearch` (app-scoped query, like `SessionSearch`; cleared on unpair; `toString` shows the length only); `fun filterChatRows(rows: List<ChatRow>, query: String): List<ChatRow>` (case-insensitive match on title and snippet with mentions rendered as names); `fun chatTimestamp(lastModifiedMillis: Double, nowMillis: Long, zone: ZoneId, locale: Locale): String` (time of day within 24 h, weekday within 7 days, else short date, `IOS/Screens/HomeView.swift:136-148`); `fun agentStateLabel(state: AgentState): String?` ("Working", "Waiting for you", "Done", none for `IDLE`/`UNKNOWN`); `class ChatIconCache(maxBytes: Int = 2 MiB)` decoding each PNG once per content hash.
- Screen: rows sorted as the Mac sends them (most recent first); each shows the icon (or a bubble glyph), title, a muted bell-slash, the timestamp, two lines of snippet, an unread dot and the state chip (green Working with a small spinner, orange Waiting for you, blue Done). A search field as on the session list. Swipe or long-press: Mute/Unmute (only `macRevision >= 5`), Delete. Pull to refresh. Empty: "No chats yet" / "Tap New Chat to start a conversation with a session or the orchestrator." (`IOS/Screens/HomeView.swift:75-88`). AI off: "AI Chat Is Off" with iOS's two paragraphs (`IOS/Views/AIUnavailablePanel.swift:20-26`). New Chat (✎) in the top bar, disabled when AI is off.

- [ ] **Step: Failing tests**: `ChatListRulesTest.searchMatchesTitleAndRenderedSnippet`, `muteHiddenBelowRevisionFive`, `stateLabels`, `rowsKeepMacOrder`, `snippetMentionsShowNames`; `ChatTimestampsTest.todayWeekAndOlder`; `ChatIconCacheTest.decodesOncePerContent`, `evictsOverBudget`, `corruptPngShowsDefaultIcon`.
- [ ] **Step: Run** `./gradlew testDebugUnitTest --tests '*ChatListRulesTest*' --tests '*ChatTimestampsTest*' --tests '*ChatIconCacheTest*'`. FAIL. **Implement.** **Run.** PASS. **Build** `./gradlew lint assembleDebug`.
- [ ] **Step: Commit** ("Add the chat list with live agent states").

### Task: Conversation

**Files:**
- Create: `ui/conversation/ConversationScreen.kt`, `ui/conversation/MessageBubble.kt`, `ui/conversation/InlineMarkdown.kt`, `ui/conversation/TranscriptItems.kt`, `ui/conversation/TypingIndicator.kt`
- Test: `ui/conversation/InlineMarkdownTest.kt`, `ui/conversation/TranscriptItemsTest.kt`, `ui/conversation/MessageBubbleRulesTest.kt`

**Interfaces:**
- Consumes: `ChatRepository.conversation`, `ChatActivityTracker.states`, `MentionResolver.resolutions`, `MentionParser`.
- Produces: `fun inlineMarkdown(text: String, mentions: List<Mention>, resolve: (String) -> MentionResolution?): AnnotatedString` (inline only, whitespace preserved: `**bold**`, `*italic*`/`_italic_`, `` `code` ``, `~~strike~~`, `[text](url)`, bare URLs; fenced code blocks shown monospace; mentions outside protected spans become links styled as iOS's "terminal glyph + name", `IOS/Views/MessageBubbleView.swift:119-180, 454-472`; a parse failure falls back to plain text); `sealed interface TranscriptItem` (`Bubble(message, fromUser)`, `Notice(message)` for `ClientNotice` and `WatcherEvent`, `RequestCard(message)`, `Typing`) with a stable key per item; `fun transcriptItems(transcript: Transcript, typing: Boolean): List<TranscriptItem>`.
- Screen rules: user bubbles right in the accent colour, agent bubbles left, notices centred (`IOS/Views/MessageBubbleView.swift:19-38`); attachments: code monospace, status updates secondary text, image files inline (within the bitmap budget) else a paperclip with the name, `fileID` a paperclip (`:398-424`); `Unsupported` reads "You need a newer version of iTerm2 Buddy to view this message." (`:284-291`) and `Unreadable` reads "This message couldn't be read."; the typing indicator shows while the activity is `WORKING`; the list stays pinned to the bottom while it was at the bottom and new content arrives, otherwise a "New messages ↓" pill; stable keys are message `uniqueID`s so a delta recomposes only its bubble. Text is selectable.
- Interactive requests render read-only, with the request text (classic: "The agent wants to run a command" + function name and permission category, and the orange "flagged as potentially dangerous" line when `safe == false`, `:235-249`; external: its `markdownDescription`; select-session, workgroup permission, enable orchestration and offer-link with iOS's texts, `:216-375`) and the line "Answer this on your Mac. Answering from the phone comes in a later update." No button sends anything.
- Top bar: title, a mute toggle (`macRevision >= 5`), "Go to session" when the chat is session-bound and that session is in the current session tree (iOS probes liveness, `AM:4263-4276`; the tree is the Android source), and, for an orchestrator chat with workgroup scopes, a "Workgroups" menu listing them (*Workgroup View*). Banners: stale "Couldn't refresh. Showing the last loaded messages." (`IOS/Screens/ConversationView.swift:107-123`), deleted (iOS notice), too large "This chat is too large to open on this phone." with Try again, failed "Couldn't load this chat." with Try again.

- [ ] **Step: Failing tests**: `InlineMarkdownTest.inlineStylesAndWhitespace`, `stableIdUnderscoresSurviveEmphasis` (two `@ptys_…` in one paragraph keep their underscores, `MP:98-118`), `mentionInCodeSpanIsNotLinked`, `unresolvedMentionStaysRaw`, `missRendersDefunct`, `brokenMarkdownFallsBackToText`; `TranscriptItemsTest.noticesAndRequestsAreClassified`, `typingItemOnlyWhileWorking`, `keysAreStableAcrossDeltas`, `streamingDeltaRecomposesOnlyItsBubble` (two consecutive `transcriptItems` results differ in exactly one item, by identity); `MessageBubbleRulesTest.requestCardsHaveNoActions`, `dangerousCommandShowsWarning`, `goToSessionNeedsSessionInTree`.
- [ ] **Step: Run** `./gradlew testDebugUnitTest --tests '*InlineMarkdownTest*' --tests '*TranscriptItemsTest*' --tests '*MessageBubbleRulesTest*'`. FAIL. **Implement.** **Run.** PASS. **Build** `./gradlew lint assembleDebug`.
- [ ] **Step: Commit** ("Add the conversation view with streaming replies").

### Task: Composer and Send

**Files:**
- Create: `core/chat/MentionDraft.kt`, `ui/conversation/ChatComposer.kt`, `ui/conversation/MentionPicker.kt`, `ui/conversation/ChatDrafts.kt`
- Modify: `core/chat/ChatRepository.kt` (`send`)
- Test: `core/chat/MentionDraftTest.kt`, `core/chat/ChatSendTest.kt`

**Interfaces:**
- Produces: `data class MentionDraft(val text: String, val tokens: List<MentionToken>)` where the raw `text` already holds each mention as `@<guid>` and `MentionToken(start, end, guid, displayName)` marks its span; `fun MentionDraft.insertMention(at: Int, guid: String, displayName: String): MentionDraft` (token plus a trailing space, `IOS/Views/MentionComposerView.swift:126-150`); `fun MentionDraft.applyEdit(…)` that deletes a token whole when any of its characters is deleted and keeps tokens' offsets right after edits elsewhere; `fun MentionDraft.serialised(): String` (= `text`, `:105-120`); `fun MentionDraft.displayText(): AnnotatedString` (the output transformation: each token shown as its name in link colour). `class ChatDrafts` (per chat, app-scoped, in memory, cleared on unpair).
- `ChatRepository.send(chatID: String, draft: MentionDraft): SendOutcome` with `SendOutcome { Sent; Empty; NotSendable(reason) }`: trims, refuses empty (`AM:3087-3091`), builds `UserTextMessage` with a new uppercase UUID and whole-ms date, appends the optimistic echo marked pending (`AM:3100-3118`), sends `Publish` with no requestID on the current link, and then: on success the echo stays pending until the Mac's `delivery` with the same `uniqueID` replaces it (`HB:1812-1823`); on a send failure the echo is marked "Not sent" with Retry and Delete (Android addition: iOS leaves a silent phantom, `AM:3120-3135`). The draft clears only on `Sent`.
- Mention picker: a sheet listing the session tree with the session list's rows and search (`sessionRows`, `filterSessionRows`), picking inserts the token named by the row title (`IOS/Views/AgentComposerBar.swift:233-245, 397-470`).
- Pending approval: when the chat's activity is `WAITING` and its last visible message is an interactive request, the composer shows "The agent is waiting for an answer on your Mac. Sending a message now cancels that request." and the first Send asks to confirm (`CS:192-227`).
- The composer is disabled with its reason when `ConversationState.Shown.sendable` is false (not connected, AI off, deleted, `IOS/Screens/ConversationView.swift:47-55`).

- [ ] **Step: Failing tests**: `MentionDraftTest.draftSerialisesTokensAsGuids`, `deletingInsideATokenDeletesItWhole`, `editsBeforeATokenShiftIt`, `pasteOfRawGuidStaysText`, `displayShowsNames`; `ChatSendTest.publishMatchesSampleShape` (against `publish-plain-text.json` with its UUID and date injected), `emptyAfterTrimIsNotSent`, `echoIsReplacedByDelivery`, `failedSendIsMarkedNotSentAndKeepsDraft`, `sendNeedsCurrentLink`, `pendingRequestWarnsBeforeSend`, `noSendWhileAiUnavailable`, `draftsClearOnUnpair`.
- [ ] **Step: Run** `./gradlew testDebugUnitTest --tests '*MentionDraftTest*' --tests '*ChatSendTest*'`. FAIL. **Implement.** **Run.** PASS. **Build** `./gradlew lint assembleDebug`.
- [ ] **Step: Commit** ("Add the chat composer with session mentions").

### Task: Chat Management

**Files:**
- Create: `ui/chats/NewChatScreen.kt`
- Modify: `core/chat/ChatRepository.kt` (`create`, `delete`, `setMuted`, `deleteFrom`), `ui/chats/ChatListScreen.kt`, `ui/conversation/ConversationScreen.kt`
- Test: `core/chat/ChatManagementTest.kt`

**Interfaces:**
- `create(mode: NewChatMode): CreateOutcome` (`Created(chatID)` or `Failed(retryable)`): `createChat` with title "Orchestrator" or "New Chat" (`AM:2582-2602`); on `chatCreated` the entry is inserted if missing and the navigator opens the conversation. New Chat screen: "Orchestration" ("The orchestrator can see and act across all of your sessions.") or "A session" ("The chat is bound to one terminal session.") with the session tree to pick from (`IOS/Screens/CreateView.swift:40-90`); hidden behind the AI-unavailable panel when AI is off.
- `delete(chatID)`: after a confirm dialog, remove the row and its transcript, typing and activity at once (`AM:2605-2632`), send `DeleteChat`; if no `chatListChanged` arrives within `deleteListWait` (3 s), refetch the list (Android addition: the Mac pushes only on change, so a refused delete would otherwise leave the phone diverged, `HB:458-470`).
- `setMuted(chatID, muted)`: flip the entry at once, send `SetChatMuted` (`AM:2748-2765`); the Mac persists it and does not push (`sources/Companion/CompanionChatMuteRegistry.swift:28-38`), so the optimistic flag stands until the next list.
- `deleteFrom(chatID, messageID)`: "Delete From Here" on the owner's own messages only, when `macRevision >= 12` (`IOS/Views/MessageBubbleView.swift:40-74`); after a confirm, truncate the suffix at once (`AM:2637-2669`) and send `DeleteMessages`; the `messagesRemoved` echo is idempotent.

- [ ] **Step: Failing tests** in `ChatManagementTest` (FakeChatMac): `createOrchestratorAndSessionMatchSamples`, `createdChatIsInsertedOnceAndOpened`, `deleteIsOptimisticAndRefetchesWithoutPush`, `deleteThenPushDoesNotRefetch`, `muteIsOptimisticAndSent`, `deleteFromSendsTheSuffix`, `deleteFromHiddenBelowRevisionTwelve`, `deleteFromOnlyOnOwnMessages`, `messagesRemovedEchoIsIdempotent`.
- [ ] **Step: Run** `./gradlew testDebugUnitTest --tests '*ChatManagementTest*'`. FAIL. **Implement.** **Run.** PASS. **Build** `./gradlew lint assembleDebug`.
- [ ] **Step: Commit** ("Create, delete, mute and truncate chats").

### Task: Workgroup View

**Files:**
- Create: `core/chat/WorkgroupWatch.kt`, `ui/workgroup/WorkgroupScreen.kt`
- Test: `core/chat/WorkgroupWatchTest.kt`, `ui/workgroup/WorkgroupRulesTest.kt`

**Interfaces:**
- Produces: `sealed interface WorkgroupState { data object Loading; data class Shown(val info: WorkgroupInfo, val refreshing: Boolean, val stale: Boolean); data class Failed(val message: String, val retryable: Boolean) }` and `class WorkgroupWatch(link: StreamLink, scope: CoroutineScope, workgroupID: String, pollInterval: Duration = 5.seconds, timeout: Duration = 15.seconds)` with `val state: StateFlow<WorkgroupState>`, `fun start()`, `fun stop()`, `fun refresh()`. Requests `fetchWorkgroupInfo` (`HB:2164-2200`) on start, on refresh, on each `LinkUp`, and every `pollInterval` while started (Android addition: upstream has no workgroup push, and iOS loads only on appear and pull, `IOS/Screens/WorkgroupView.swift:42-46, 137-147`); one request in flight; a failure keeps the last info marked `stale`; an `unknownSession` error shows "That workgroup no longer exists." without retrying.
- Produces: `fun memberStatusLine(member: WorkgroupMember): String?` ("Hasn't started yet" without a session; else status and detail joined by ": ", `IOS/Screens/WorkgroupView.swift:96-108`) and `fun memberStateLabel(member: WorkgroupMember): String` (Working / Waiting / Idle / Unknown; not started shows a dashed circle, `:110-133`).
- Entry points: a workgroup mention (`MentionResolution.workgroupID`, `AM:1376-1379`), and the conversation's "Workgroups" menu for an orchestrator chat's `claimedScopes` that are real workgroup IDs (not `session:…`, `CHAT:58-68`). A member with a session opens its live session view (title = role name, `IOS/Screens/WorkgroupView.swift:64-72`), with `isBrowser` from the session tree (false if not found).

- [ ] **Step: Failing tests**: `WorkgroupWatchTest.pollsOnlyWhileStarted`, `oneRequestInFlight`, `failureKeepsLastInfoStale`, `unknownWorkgroupStopsPolling`, `refetchesOnLinkUp`, `futureMemberStateReadsUnknown`; `WorkgroupRulesTest.statusLines`, `stateLabels`, `memberWithoutSessionIsNotTappable`.
- [ ] **Step: Run** `./gradlew testDebugUnitTest --tests '*WorkgroupWatchTest*' --tests '*WorkgroupRulesTest*'`. FAIL. **Implement.** **Run.** PASS. **Build** `./gradlew lint assembleDebug`.
- [ ] **Step: Commit** ("Add the workgroup view with member states").

### Task: Chats Tab and Navigation

**Files:**
- Create: `ui/HomeNavigator.kt` (replaces `ui/SessionNavigator.kt`)
- Modify: `ui/AppRoot.kt` (`HomeTab { CHATS, SESSIONS }`; `showsBottomBar` now true), `ui/home/HomeScaffold.kt` (bottom bar on tab roots only; render the current tab's top destination; `BackHandler` → `navigator.back()`), `MainActivity.kt` (collect the chat flows into the home model), `AppGraph.kt`, `CompanionApp.kt` (navigator wiring: streams through `StreamController.open/close`, conversations through `ChatRepository.open/close/setVisible`; `ProcessLifecycleOwner` stop marks no conversation visible)
- Delete: `ui/SessionNavigator.kt`, `ui/SessionNavigatorTest.kt` (its cases move to `HomeNavigatorTest`)
- Test: `ui/HomeNavigatorTest.kt`, `ui/AppRootTest.kt` (extended)

**Interfaces:**
- Produces: `sealed interface HomeDestination` with `ChatList`, `SessionList`, `Settings`, `NewChat`, `Conversation(chatID)`, `Workgroup(workgroupID, title)`, `LiveSession(session, title)`, `BrowserSession(session, title)` (redacting `toString`); `class HomeNavigator(openStream, closeStream, openChat, closeChat, setVisibleChat)` with `val tab: StateFlow<HomeTab>`, `val destination: StateFlow<HomeDestination>` (top of the current tab's stack), `fun selectTab(tab: HomeTab)`, `fun push(destination: HomeDestination)`, `fun back()`, `fun openSettings()`, `fun reset()`, and `fun keepsScreenOn(destination)` unchanged (live and browser sessions only).
- Rules:
  - Each tab has its own back stack with its list at the root. **Only navigator calls open and close streams and conversations**, never composition, so rotation and recreation change nothing (the Sessions rule, kept).
  - Pushing a `Conversation` calls `openChat`; popping it calls `closeChat` (the repository keeps it subscribed while it is still wanted). The visible conversation is the top of the selected tab's stack while the app is started.
  - "Go to session" and a workgroup member push `LiveSession` onto the **current** tab's stack (iOS pushes above the conversation, `AM:1371-1374`); Back closes the stream and returns to the conversation or workgroup. At most one stream exists: pushing a session while another session destination is on any stack closes the old stream and removes that destination.
  - Selecting another tab while the current top is a live or browser session pops it first (closing the stream): no hidden stream runs behind another tab.
  - Cold start lands on Chats when AI is available and on Sessions when it is not (`AM:2184-2190`); AI turning off while on Chats moves to Sessions (`AM:2214-2219`).
  - The bottom bar (Chats, Sessions; the Chats item shows a dot when any chat is unread or waiting) shows only on tab roots, so the live view keeps the whole screen.
  - `reset()` (unpair) closes any stream, closes every conversation, clears both stacks and selects the default tab.

- [ ] **Step: Failing tests** in `HomeNavigatorTest`: `openAndBackDriveController` and `recreatingTheUiDoesNotRestart` (moved), `conversationOpenAndCloseDriveRepository`, `goToSessionReturnsToConversation`, `onlyOneStreamAcrossTabs`, `tabSwitchAwayFromLiveSessionClosesIt`, `stacksAreKeptPerTab`, `coldStartTabFollowsAi`, `aiOffMovesToSessions`, `resetClosesEverything`, `screenKeptOnOnlyInSessionViews`; `AppRootTest.bottomBarOnlyOnTabRoots`.
- [ ] **Step: Run** `./gradlew testDebugUnitTest --tests '*HomeNavigatorTest*' --tests '*AppRootTest*' --tests '*UnpairCleanupTest*'`. FAIL. **Implement.** **Run.** PASS, then the full `./gradlew testDebugUnitTest`.
- [ ] **Step: Build** `./gradlew lint assembleDebug`; deliver the APK as before (CI artifact with `apk-sha256.txt`).
- [ ] **Step: Commit** ("Add the Chats tab beside Sessions").

### Task: Agent Chat On-Device Check

Before it, Mythos reviews the whole phase branch (critical review, as the handover sets out) and every finding is triaged and fixed. Then local, owner, Pixel 9 with Tailscale and the relay running. The lead writes `docs/reports/agent-chat-on-device.md` from this list first (same layout as `docs/reports/live-session-on-device.md`: prerequisites with APK hashes, a **Result:** line under each check, logcat captured from the start), then the owner fills it in.

- [ ] **Step: Write the report skeleton** with these checks:
  - **Chat list.** After launch the Chats tab shows the Mac's chats in the Mac's order with icons, snippets (mentions as names) and times. Rename a chat on the Mac: the row updates within about a second without pulling. Search filters by title and snippet.
  - **Live states.** Start a long Claude Code task in an orchestrator chat on the Mac and watch the list on the phone: the row shows Working; when the turn ends it shows Done and an unread dot; opening it clears both. Record the delay from the Mac's last word to Done.
  - **Waiting.** Ask a session-bound chat to run a command that needs permission: the row shows "Waiting for you" within about 2 s; the conversation shows the request card with "Answer this on your Mac". Answer on the Mac: the state returns to Working.
  - **Streaming.** Open a chat and send "count slowly to 50, one number per line": the reply streams into one bubble in order, with no doubled or missing numbers; the typing indicator shows while it streams.
  - **Reconnect mid-stream.** While a reply streams, lock the phone for 60 s (the link drops) and unlock: the conversation shows the reply complete (no duplicates, no gap) after "Reconnecting…".
  - **Send and echo.** Send a message with a session @-mention picked from the picker: one bubble, not two; on the Mac the message shows the session mention; the agent replies.
  - **Pending approval warning.** With a request waiting, type a message: the composer warns; confirm: the Mac shows the request cancelled.
  - **Mentions.** An agent reply naming a session shows its name as a link; tapping opens the live view; Back returns to the conversation. A workgroup mention opens the workgroup view.
  - **Workgroup.** The member list shows each member's state and status line; start and stop work in one member on the Mac: its state changes on the phone within about 5 s.
  - **Create, mute, delete, truncate.** Create an orchestrator chat and a session-bound chat from the phone; mute one (the bell-slash shows, and the Mac stops pushing for it); Delete From Here on your own message truncates on both sides; delete a chat on the phone and confirm it is gone on the Mac; delete one on the Mac while it is open on the phone: the deleted notice shows and sending is disabled.
  - **AI off.** Turn AI off in iTerm2 settings while on the Chats tab: the phone moves to Sessions and Chats shows "AI Chat Is Off"; turn it back on: chats return and an open conversation streams again without reconnecting.
  - **Big chat.** Open the longest chat on the Mac: record its time to show and whether the phone stays smooth while scrolling; record the app's memory (`adb shell dumpsys meminfo dev.bugobliterator.itermbuddy`) before and after.
  - **Tabs.** Switch between Chats and Sessions; open a live session from Sessions, switch to Chats: the stream stops (the Mac's log shows the stop); rotation keeps the conversation and its scroll position.
  - **No content in logs.** `grep -iE 'count slowly|<a word from a reply>|<a chat title>|kittens' agent-chat-logcat.txt` finds nothing.
- [ ] **Step: Owner runs the checks**; the lead records results and follow-ups, reports to the owner, `master-session` and the tracker, and asks before starting the next phase.
- [ ] **Step: Commit** ("Record agent chat on-device results").

---

## Open questions for the owner

Upstream does not settle these; each has the plan's default in brackets.

1. **How many chats to watch for live states.** Working and Waiting exist only for subscribed chats, and each subscribe (and each resubscribe after every reconnect, including the screen-lock drop) sends that chat's whole history. [Default: the open conversation plus the 6 most recently modified chats; the rest show only the unread dot from the list.] Larger means more state, more data and more battery.
2. **"Waiting" is inferred, not reported.** No upstream message says "the agent is waiting for you"; the plan infers it from a turn in progress with typing off for 1.5 s, or a pending request as the last message. [Default: as described.] Acceptable, or show only Working and Done?
3. **Default tab.** iOS opens on Chats when AI is available. Sessions has been the home since the last phase. [Default: Chats when AI is available, else Sessions.]
4. **Workgroup polling.** iOS does not poll; the plan refreshes the open workgroup view every 5 s. [Default: 5 s while visible.]
5. **Very large chats.** The Mac sends a whole history in one message; over the phone's 32 MiB cap the link drops and the plan marks that chat "too large". [Default: keep the cap.] Raise it (say to 96 MiB) at the cost of peak memory?
6. **Command request text.** Showing a classic command's full description needs a port of `RemoteCommand.markdownDescription` (`RC:404` and its content cases), planned for Approvals. [Default: show the function name and permission category now.]
7. **Inline images.** iOS shows image attachments inline. [Default: inline within a 4 MiB per image and 32 MiB per chat budget, else the file name.]
8. **Unread marks across restarts.** [Default: persisted per pairing in app-private storage (chat IDs and timestamps only), wiped on unpair.]
9. **Chat from the live session view.** iOS has a chat button in the session view (continue or create the session-bound chat, with the auto-provide consent prompt). [Default: out of this phase.]
10. **Tab switch while watching.** [Default: switching tabs away from a live session closes it, so no stream runs unseen.] Or keep it running in the background tab?

## After this plan

Ask the owner before starting the next phase. Carry forward: answering approvals (the read-only request cards become buttons; `RemoteCommand.markdownDescription` port), Mac modal alerts (revision 14), notifications and the foreground service (subscribe-all, `syncSince`), the session view's chat button, and any budget or watch-set tuning the on-device check suggests.
