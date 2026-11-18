import CompanionProtocol
import Foundation

// Agent chat message samples (messages/agent-chat/*.json), in the same
// `{"direction", "swiftValue", "json"}` format as messages/session.
//
// Phone-to-Mac envelopes are built the way the iOS app builds them
// (CompanionClient for the payload and request/send split, AppModel for the
// arguments). Mac-to-phone envelopes are built the way CompanionHostBridge
// builds them. Paths are upstream v3.7.7 paths; the Mac and iOS sources are not
// compiled here, so the call sites are cited instead (iOS: Companion/
// iTerm2Companion/App/AppModel.swift and Connection/CompanionClient.swift; Mac:
// sources/Companion/CompanionHostBridge.swift). Chat titles, message texts,
// session names, workgroup names and model names are synthetic.

/// The revision this app advertises (app/.../core/wire/ProtocolRevision.kt):
/// v3.7.7's own `current`. Every Mac gate a chat sample depends on reads
/// `min(current, peer)` or `peer` against 8 (turnLifecycle, CompanionHostBridge.swift:1077-1080),
/// 12 (messagesRemoved, 1087-1089) or 13 (the typed aiUnavailable error,
/// 2335-2338); the other peer gates (2 contentless wakeup, 14 macStatus in the
/// hello, 15 browser input) carry no chat content. So every chat sample is the
/// same at 13 and at 15, which `agentChatSamples` checks.
let agentChatAppRevision = 15

/// Request IDs continue the Android client's order on one connection after
/// messages/history-and-selection (fetchHistoryTile 7, copySelection 8).
let listChatsRequestID: UInt64 = 9
let createOrchestratorChatRequestID: UInt64 = 10
let createSessionChatRequestID: UInt64 = 11
let subscribeRequestID: UInt64 = 12
let resolveMentionsRequestID: UInt64 = 13
let fetchWorkgroupInfoRequestID: UInt64 = 14
let fetchSessionScreenInfoRequestID: UInt64 = 15

func fixedUUID(_ text: String) -> UUID {
    guard let uuid = UUID(uuidString: text) else {
        require(false, "\(text) is not a UUID")
        return UUID()
    }
    require(uuid.uuidString == text, "\(text) is not an uppercase canonical UUID")
    return uuid
}

/// A Date that `WireCoding` writes as exactly `milliseconds`, an integer
/// literal. Checked: a Date is stored relative to 2001, so not every
/// millisecond value survives the round trip through Double.
func wholeMillisecondDate(_ milliseconds: Int64) -> Date {
    let date = Date(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
    require(date.timeIntervalSince1970 * 1000 == TimeInterval(milliseconds),
            "\(milliseconds) ms does not survive a Date round trip")
    return date
}

/// Synthetic chats, messages and identifiers the chat samples share.
enum SyntheticChats {
    // MARK: Identifiers

    /// An orchestrator chat that claims a workgroup and one session.
    static let orchestratorChatID = "6A1D3F5B-2C4E-4A8B-9D0F-1E2A3B4C5D6E"
    /// A session-bound chat made on the Mac (its guid is a legacy PTYSession guid).
    static let sessionChatID = "9B8C7D6E-5F4A-4B3C-8D2E-1F0A9B8C7D6E"
    /// A chat with no visible message yet, so no snippet.
    static let emptyChatID = "D4E5F6A7-B8C9-4DAE-8F01-23456789ABCD"
    /// Chats `createChat` makes (Chat.swift:15 mints `UUID().uuidString`).
    static let createdOrchestratorChatID = "2F3E4D5C-6B7A-4980-A1B2-C3D4E5F60718"
    static let createdSessionChatID = "7E6D5C4B-3A29-4817-B6F5-E4D3C2B1A090"
    /// A chat the Mac does not know (deleted, or never existed).
    static let unknownChatID = "0A1B2C3D-4E5F-4061-8293-A4B5C6D7E8F9"

    /// A real workgroup instance ID (`wg-<uuid>`) and its synthetic name.
    static let workgroupID = "wg-5C4D3E2F-1A0B-4C9D-8E7F-6A5B4C3D2E1F"
    static let workgroupName = "Widget Review"
    /// A workgroup that has been torn down.
    static let missingWorkgroupID = "wg-E1F2A3B4-C5D6-4E7F-8091-A2B3C4D5E6F7"
    /// Legacy PTYSession guids (UUID strings): the Mac-made session chat's
    /// binding, and the notes session's guid before its shell reload.
    static let legacySessionGuid = "C3D4E5F6-0718-4293-A4B5-C6D7E8F90A1B"
    static let notesLegacyGuid = "1D2C3B4A-5968-4776-A5B4-C3D2E1F00F1E"
    /// A session guid no session has any more.
    static let closedSessionGuid = "F0E1D2C3-B4A5-4968-8776-A5B4C3D2E1F0"

    static let modelName = "example-model"

    // MARK: Dates (whole milliseconds unless said otherwise)

    static let chatCreated: Int64 = 1_794_960_000_000
    static let conversationStart: Int64 = 1_795_000_000_000

    static func at(_ secondsIntoConversation: Int64) -> Date {
        wholeMillisecondDate(conversationStart + secondsIntoConversation * 1000)
    }

    // MARK: Building blocks

    static func message(_ chatID: String, _ author: Participant, _ content: Message.Content,
                        sentDate: Date, _ uniqueID: String) -> Message {
        Message(chatID: chatID, author: author, content: content, sentDate: sentDate,
                uniqueID: fixedUUID(uniqueID))
    }

    /// `execute_command` as session-bound mode's RemoteCommand carries it: the
    /// LLM's function call (RemoteCommand.swift:378-380).
    static func remoteCommand(_ content: RemoteCommand.Content, callID: String) -> RemoteCommand {
        let call = LLM.FunctionCall(name: content.functionName,
                                    arguments: remoteCommandArguments(content),
                                    id: callID)
        return RemoteCommand(llmMessage: LLM.Message(role: .assistant,
                                                     body: .functionCall(call, id: nil)),
                             content: content)
    }

    static let executeMakeTest = remoteCommand(.executeCommand(RemoteCommand.ExecuteCommand(command: "make test")),
                                               callID: "call_execute_make_test")

    /// An orchestrator tool call (RemoteCommand.swift:67-75): opaque JSON
    /// arguments and the orchestrator's own description.
    static let sendTextToImplementer = ExternalRemoteCommand(
        llmMessage: LLM.Message(role: .assistant,
                                body: .functionCall(LLM.FunctionCall(name: "send_text",
                                                                     arguments: "{\"role\":\"Implementer\",\"text\":\"make test\\n\",\"workgroup_id\":\"\(workgroupID)\"}",
                                                                     id: "call_send_text_implementer"),
                                                    id: nil)),
        name: "send_text",
        argsJSON: "{\"role\":\"Implementer\",\"text\":\"make test\\n\",\"workgroup_id\":\"\(workgroupID)\"}",
        markdownDescription: "Sending `make test` to Implementer in \(workgroupName)")

    static let watcherFired = StatusUpdate(watcherID: "watch-tests-idle",
                                           workgroupID: workgroupID,
                                           workgroupName: workgroupName,
                                           roleID: "implementer",
                                           roleName: "Implementer",
                                           reason: .stateReached,
                                           stateReached: "idle",
                                           timestamp: at(150),
                                           detail: "Implementer is idle after the test run.",
                                           pushed: true,
                                           screenLastChanged: "< 1 min ago")

    // MARK: Chats

    /// A registered watcher (WorkgroupWatcher.swift:14-60) on the orchestrator chat.
    static let testsIdleWatcher = WorkgroupWatcher(watcherID: "watch-tests-idle",
                                                   sessionGUID: SyntheticMac.shell.guid,
                                                   workgroupID: workgroupID,
                                                   workgroupName: workgroupName,
                                                   roleID: "implementer",
                                                   roleName: "Implementer",
                                                   targetState: .idle,
                                                   registeredAt: at(100),
                                                   mode: .tabStatus,
                                                   condition: nil,
                                                   notifyUser: true)

    static let orchestratorChat = Chat(id: orchestratorChatID,
                                       title: "Widget release checklist",
                                       creationDate: wholeMillisecondDate(chatCreated),
                                       lastModifiedDate: at(400),
                                       orchestrationEnabled: true,
                                       terminalSessionGuid: nil,
                                       browserSessionGuid: nil,
                                       permissions: "",
                                       vectorStore: nil,
                                       modelName: modelName,
                                       modelVendor: nil,
                                       blobProtocol: nil,
                                       claimedScopes: [workgroupID, "session:" + SyntheticMac.notes.guid],
                                       watchers: [testsIdleWatcher],
                                       icon: minimalPNG())

    static let sessionChat = Chat(id: sessionChatID,
                                  title: "Fix flaky widget test",
                                  creationDate: wholeMillisecondDate(chatCreated + 60_000),
                                  lastModifiedDate: at(300),
                                  orchestrationEnabled: false,
                                  terminalSessionGuid: legacySessionGuid,
                                  browserSessionGuid: nil,
                                  permissions: "",
                                  vectorStore: nil,
                                  modelName: modelName,
                                  modelVendor: nil,
                                  blobProtocol: nil,
                                  claimedScopes: [],
                                  watchers: [],
                                  icon: nil)

    static let emptyChat = Chat(id: emptyChatID,
                                title: "New Chat",
                                creationDate: wholeMillisecondDate(chatCreated + 120_000),
                                lastModifiedDate: wholeMillisecondDate(chatCreated + 120_000),
                                orchestrationEnabled: false,
                                terminalSessionGuid: SyntheticMac.planner.guid,
                                browserSessionGuid: nil,
                                permissions: "",
                                vectorStore: nil,
                                modelName: modelName,
                                modelVendor: nil,
                                blobProtocol: nil,
                                claimedScopes: [],
                                watchers: [],
                                icon: nil)

    /// The session chat's last visible message: longer than the Mac's 200
    /// character snippet cut (CompanionHostBridge.swift:2201-2203).
    static let longAgentReply = "The flaky test is widget_resize_test: it reads the window size before the resize "
        + "notification lands, so on a slow runner it sees the old 80 columns. I moved the assertion after "
        + "the notification and added a timeout of 2 seconds; it passed 50 runs in a row."

    /// The snippet the Mac computes (ChatListModel.snippet(forChatID:maxLength:),
    /// CompanionHostBridge.swift:2205-2217), through upstream Content.snippetText.
    static func snippet(_ content: Message.Content) -> String? {
        content.snippetText(maxLength: 200)
    }

    static func listEntries(lastOrchestratorContent: Message.Content) -> [CompanionChatListEntry] {
        // ChatListModel keeps the most recently bumped chat first (ChatListModel.swift:122-129).
        [
            CompanionChatListEntry(chat: orchestratorChat, snippet: snippet(lastOrchestratorContent), muted: false),
            CompanionChatListEntry(chat: sessionChat, snippet: snippet(.markdown(longAgentReply)), muted: true),
            CompanionChatListEntry(chat: emptyChat, snippet: nil, muted: false)
        ]
    }
}

/// The arguments JSON of a RemoteCommand's function call: the content's own
/// encoding with the case wrapper removed, keys sorted.
func remoteCommandArguments(_ content: RemoteCommand.Content) -> String {
    do {
        let encoded = try sortedWireEncoder().encode(content)
        guard let outer = try JSONSerialization.jsonObject(with: encoded) as? [String: Any],
              outer.count == 1,
              let wrapper = outer.values.first as? [String: Any],
              let args = wrapper["_0"] else {
            require(false, "\(content.functionName): unexpected RemoteCommand.Content encoding")
            return ""
        }
        let data = try JSONSerialization.data(withJSONObject: args, options: [.sortedKeys, .withoutEscapingSlashes])
        return String(decoding: data, as: UTF8.self)
    } catch {
        require(false, "\(content.functionName): \(error)")
        return ""
    }
}

/// A Message.Content decoded through upstream's own decoder from hand-written
/// JSON, for content whose initializer is impractical here.
func decodedContent(_ json: String) -> Message.Content {
    do {
        return try WireCoding.decode(Message.Content.self, from: Data(json.utf8))
    } catch {
        require(false, "hand-written content does not decode: \(error)")
        return .unsupported
    }
}

/// The shapes of `Message.Content` this generator writes. Message.Content is
/// not CaseIterable; an exhaustive switch makes a new upstream case a compile
/// error here.
func contentCaseName(_ content: Message.Content) -> String {
    switch content {
    case .plainText: return "plainText"
    case .markdown: return "markdown"
    case .explanationRequest: return "explanationRequest"
    case .explanationResponse: return "explanationResponse"
    case .remoteCommandRequest: return "remoteCommandRequest"
    case .watcherEvent: return "watcherEvent"
    case .remoteCommandResponse: return "remoteCommandResponse"
    case .selectSessionRequest: return "selectSessionRequest"
    case .clientLocal: return "clientLocal"
    case .renameChat: return "renameChat"
    case .append: return "append"
    case .appendAttachment: return "appendAttachment"
    case .commit: return "commit"
    case .userCommand: return "userCommand"
    case .setPermissions: return "setPermissions"
    case .vectorStoreCreated: return "vectorStoreCreated"
    case .terminalCommand: return "terminalCommand"
    case .multipart: return "multipart"
    case .unsupported: return "unsupported"
    }
}

func clientLocalActionName(_ action: ClientLocal.Action) -> String {
    switch action {
    case .pickingSession: return "pickingSession"
    case .executingCommand: return "executingCommand"
    case .notice: return "notice"
    case .streamingChanged: return "streamingChanged"
    case .offerLink: return "offerLink"
    case .offerOrchestration: return "offerOrchestration"
    case .permissions: return "permissions"
    case .workgroupPermissionRequest: return "workgroupPermissionRequest"
    case .enableOrchestrationRequest: return "enableOrchestrationRequest"
    case .orchestrationPermissionGranted: return "orchestrationPermissionGranted"
    }
}

/// The ten ClientLocal actions (Message.swift:131-189), one each.
let everyClientLocalAction: [ClientLocal.Action] = [
    .pickingSession,
    .executingCommand(SyntheticChats.executeMakeTest),
    .notice("Linked this chat to zsh."),
    .streamingChanged(.active),
    .offerLink(terminal: true, guid: SyntheticMac.shell.guid, name: "zsh"),
    .offerOrchestration,
    .permissions(terminal: true, guid: SyntheticMac.shell.guid),
    .workgroupPermissionRequest(requestID: "permission-widget-review",
                                workgroupID: SyntheticChats.workgroupID,
                                workgroupName: SyntheticChats.workgroupName,
                                summary: "Send `make test` to Implementer"),
    .enableOrchestrationRequest(requestID: "enable-orchestration-widget"),
    .orchestrationPermissionGranted(scope: "session:" + SyntheticMac.notes.guid, name: "vim")
]

/// The visible history of the orchestrator chat: one message of every content
/// kind the Mac forwards. A history holds only stored messages, so the
/// streaming deltas (`append`, `appendAttachment`) appear only as deliveries,
/// and `unsupported` is never encoded (decode-tolerance cases cover it).
/// Not one plausible conversation: it collects every kind for decoding.
func orchestratorHistory() -> [Message] {
    typealias C = SyntheticChats
    let chat = C.orchestratorChatID
    let png = minimalPNG()
    let notesText = Data("TODO: tag v1.2\n".utf8)
    let userText = "Ask @\(SyntheticMac.shell.guid) to run the tests, then summarise."
    let originalAsk = C.message(chat, .user, .plainText("Run the tests in my build session.", context: nil),
                                sentDate: C.at(40), "11111111-2222-4333-8444-000000000040")

    // AIExplanationRequest carries iTermCodableLocatedString, which only
    // decodes (MacTypeShadows.swift:16-34); its `gridCoords` follow the Mac's
    // GridCoordArray/VT100GridCoord coding ({"coords": [{"x", "y"}]}).
    let explanationRequestJSON = """
        {"explanationRequest":{"request":{"command":"make test","context":{"baseOffset":3000,\
        "sessionID":"\(C.legacySessionGuid)"},"originalString":{"gridCoords":{"coords":[{"x":0,"y":12},\
        {"x":31,"y":12}]},"string":"widget.c:42: error: expected ';'"},"question":"Why did the build fail?",\
        "subjectMatter":"build output","truncated":false}}}
        """
    let explanationResponseJSON = """
        {"explanationResponse":{"_0":{"annotations":[{"annotatedText":"expected ';'","line":0,\
        "note":"The statement before line 42 is missing its semicolon.","utf16Length":12,\
        "utf16OffsetInLine":20}],"mainResponse":"Line 41 of widget.c lacks a semicolon.",\
        "rawResponse":"","request":{"command":"make test","context":{"baseOffset":3000,\
        "sessionID":"\(C.legacySessionGuid)"},"originalString":{"gridCoords":{"coords":[{"x":0,"y":12},\
        {"x":31,"y":12}]},"string":"widget.c:42: error: expected ';'"},"question":"Why did the build fail?",\
        "subjectMatter":"build output","truncated":false},"unparsed":""},\
        "markdown":"Line 41 of `widget.c` lacks a semicolon."}}
        """

    var messages: [Message] = [
        // plainText without context: the phone's own publish (AppModel.swift:3100-3118).
        C.message(chat, .user, .plainText(userText, context: nil), sentDate: C.at(0),
                  "11111111-2222-4333-8444-000000000001"),
        // plainText with context (Message.swift:287).
        C.message(chat, .user, .plainText("What does this error mean?",
                                          context: "Selected text: widget.c:42: error: expected ';'"),
                  sentDate: C.at(10), "11111111-2222-4333-8444-000000000010"),
        C.message(chat, .agent, .markdown("Asking **Implementer** to run `make test`."), sentDate: C.at(20),
                  "11111111-2222-4333-8444-000000000020"),
        // multipart with plainText, context and a non-image file.
        C.message(chat, .user, .multipart([.plainText("Here are my notes."),
                                           .context("Attached from the Mac."),
                                           .attachment(LLM.Message.Attachment(
                                               inline: false, id: "file-notes",
                                               type: .file(.init(name: "notes.txt", content: notesText,
                                                                 mimeType: "text/plain", localPath: nil))))],
                                          vectorStoreID: nil),
                  sentDate: C.at(30), "11111111-2222-4333-8444-000000000030"),
        // multipart with markdown and the other attachment types: code,
        // an image file, a fileID and a status update.
        C.message(chat, .agent, .multipart([.markdown("The run produced this:"),
                                            .attachment(LLM.Message.Attachment(
                                                inline: true, id: "ci_run_tests",
                                                type: .code("print('2 passed, 1 failed')"))),
                                            .attachment(LLM.Message.Attachment(
                                                inline: true, id: "file-plot",
                                                type: .file(.init(name: "plot.png", content: png,
                                                                  mimeType: "image/png",
                                                                  localPath: "/tmp/plot.png")))),
                                            .attachment(LLM.Message.Attachment(
                                                inline: false, id: "file-report",
                                                type: .fileID(id: "file-abc123", name: "report.csv"))),
                                            .attachment(LLM.Message.Attachment(
                                                inline: true, id: "status-search",
                                                type: .statusUpdate(.webSearchFinished("widget resize flake"))))],
                                           vectorStoreID: "vs_widget_notes"),
                  sentDate: C.at(35), "11111111-2222-4333-8444-000000000035"),
        originalAsk,
        C.message(chat, .user, decodedContent(explanationRequestJSON), sentDate: C.at(50),
                  "11111111-2222-4333-8444-000000000050"),
        C.message(chat, .agent, decodedContent(explanationResponseJSON), sentDate: C.at(60),
                  "11111111-2222-4333-8444-000000000060"),
        C.message(chat, .agent, .remoteCommandRequest(.classic(C.executeMakeTest), safe: false), sentDate: C.at(70),
                  "11111111-2222-4333-8444-000000000070"),
        C.message(chat, .agent, .remoteCommandRequest(.external(C.sendTextToImplementer), safe: nil),
                  sentDate: C.at(80), "11111111-2222-4333-8444-000000000080"),
        C.message(chat, .user, .watcherEvent(C.watcherFired), sentDate: C.at(150),
                  "11111111-2222-4333-8444-000000000150"),
        C.message(chat, .agent, .selectSessionRequest(originalAsk, terminal: true), sentDate: C.at(160),
                  "11111111-2222-4333-8444-000000000160")
    ]
    for (index, action) in everyClientLocalAction.enumerated() {
        messages.append(C.message(chat, .agent, .clientLocal(ClientLocal(action: action)),
                                  sentDate: C.at(200 + Int64(index)),
                                  String(format: "11111111-2222-4333-8444-%012d", 200 + index)))
    }
    messages.append(C.message(chat, .user,
                              .terminalCommand(TerminalCommand(username: "demo", hostname: "buildhost",
                                                               directory: "/Users/demo/src/widget",
                                                               command: "make test",
                                                               output: "2 passed, 1 failed\n",
                                                               exitCode: 2,
                                                               url: URL(string: "file://buildhost/Users/demo/src/widget")!)),
                              sentDate: C.at(300), "11111111-2222-4333-8444-000000000300"))
    // The one fractional date in the chat samples: the Mac stamps Date(), so a
    // stored sentDate usually has a sub-millisecond part. Decode only.
    let fractional = Date(timeIntervalSince1970: TimeInterval(C.conversationStart + 400_000) / 1000 + 0.000_25)
    messages.append(Message(chatID: chat, author: .agent,
                            content: .markdown("All three tests pass now."),
                            sentDate: fractional,
                            uniqueID: fixedUUID("11111111-2222-4333-8444-000000000400")))
    return messages
}

/// Every Content discriminator a history must carry: the visible cases
/// (Message.swift:549-562) less the streaming deltas and `unsupported`.
func requiredHistoryContentCases() -> Set<String> {
    let hidden: [Message.Content] = [
        .remoteCommandResponse(.success("2 passed"), fixedUUID("11111111-2222-4333-8444-000000000070"),
                               "execute_command", nil),
        .renameChat("Widget"),
        .commit(fixedUUID("11111111-2222-4333-8444-000000000020")),
        .userCommand(.stop),
        .setPermissions([]),
        .vectorStoreCreated(id: "vs_widget_notes")
    ]
    let notStored: [Message.Content] = [
        .append(string: "x", uuid: fixedUUID("11111111-2222-4333-8444-000000000020")),
        .appendAttachment(attachment: LLM.Message.Attachment(inline: true, id: "x", type: .code("x")),
                          uuid: fixedUUID("11111111-2222-4333-8444-000000000020")),
        .unsupported
    ]
    func probe(_ content: Message.Content) -> Message {
        SyntheticChats.message(SyntheticChats.orchestratorChatID, .agent, content, sentDate: SyntheticChats.at(0),
                               "11111111-2222-4333-8444-999999999999")
    }
    for content in hidden {
        require(probe(content).hiddenFromClient, "\(contentCaseName(content)) is no longer hidden from the phone")
    }
    for content in notStored {
        require(!probe(content).hiddenFromClient, "\(contentCaseName(content)) is now hidden from the phone")
    }
    let excluded = Set((hidden + notStored).map(contentCaseName))
    return Message.Content.knownContentKeys.subtracting(excluded)
}

/// Every phone-to-Mac sample: a publish has exactly the five keys of an iOS
/// user message (AppModel.swift:3100-3118) and every date is written as a
/// whole number of milliseconds.
func checkPhoneSentChatSample(_ sample: SessionSample) throws {
    guard let tree = try jsonObject(sample.sortedWire),
          let payload = tree["payload"] as? [String: Any] else {
        require(false, "\(sample.name): not an envelope")
        return
    }
    if let publish = payload["publish"] as? [String: Any] {
        guard let message = publish["message"] as? [String: Any] else {
            require(false, "\(sample.name): publish without a message")
            return
        }
        require(Set(message.keys) == ["chatID", "author", "content", "sentDate", "uniqueID"],
                "\(sample.name): the published message has keys \(message.keys.sorted())")
        require(publish["partial"] as? Bool == false, "\(sample.name): publish must be partial:false")
        guard let content = message["content"] as? [String: Any],
              let plain = content["plainText"] as? [String: Any] else {
            require(false, "\(sample.name): the published content is not plainText")
            return
        }
        require(Set(plain.keys) == ["_0"], "\(sample.name): the published plainText carries \(plain.keys.sorted())")
    }
    // Every "sentDate" number in the text is an integer literal.
    let text = String(decoding: sample.sortedWire, as: UTF8.self)
    let datePattern = try Regex("\"sentDate\":([-0-9.eE+]+)")
    for match in text.matches(of: datePattern) {
        let literal = String(text[match.range]).split(separator: ":").last.map(String.init) ?? ""
        require(Int64(literal) != nil, "\(sample.name): phone-sent date \(literal) is not whole milliseconds")
    }
}

/// A JSON object as Swift values.
func jsonObject(_ data: Data) throws -> [String: Any]? {
    try JSONSerialization.jsonObject(with: data) as? [String: Any]
}

/// The JSON object's single discriminator key, e.g. "markdown" for content.
func discriminator(_ value: Any?) -> String? {
    guard let object = value as? [String: Any], object.count == 1 else { return nil }
    return object.keys.first
}

func agentChatSamples() throws -> [SessionSample] {
    typealias C = SyntheticChats
    // Revision: the app's, and the gates the chat samples depend on read the
    // same at 13 and at 15 (see agentChatAppRevision).
    require(agentChatAppRevision == CompanionProtocolVersion.current, "the app no longer advertises the Mac's revision")
    for peer in [CompanionProtocolVersion.aiDecouplingRevision, agentChatAppRevision] {
        let local = CompanionProtocolVersion.current
        require(min(local, peer) >= CompanionProtocolVersion.turnLifecycleRevision,
                "revision \(peer) does not get turnLifecycle")
        require(min(local, peer) >= CompanionProtocolVersion.messageDeletionRevision,
                "revision \(peer) does not get messagesRemoved")
        require(peer >= CompanionProtocolVersion.aiDecouplingRevision, "revision \(peer) gets internalError, not aiUnavailable")
        require(peer >= CompanionProtocolVersion.chatMuteRevision, "revision \(peer) gets no muted flag")
    }

    var samples: [SessionSample] = []
    let history = orchestratorHistory()

    // MARK: Phone to Mac

    // AppModel.swift:2224, CompanionClient.swift:95-97: a request.
    samples.append(try clientSample("list-chats-and-sessions",
                                    ClientEnvelope(requestID: listChatsRequestID, payload: .listChatsAndSessions)))
    // AppModel.swift:2586-2587 (title "Orchestrator" for orchestrator mode),
    // CompanionClient.swift:108-109: a request.
    samples.append(try clientSample("create-chat-orchestrator",
                                    ClientEnvelope(requestID: createOrchestratorChatRequestID,
                                                   payload: .createChat(title: "Orchestrator", mode: .orchestrator))))
    // AppModel.swift:3364 and 2586 ("New Chat" for a session), with a
    // session's guid from the list (CompanionMessages.swift:650-655).
    samples.append(try clientSample("create-chat-session",
                                    ClientEnvelope(requestID: createSessionChatRequestID,
                                                   payload: .createChat(title: "New Chat",
                                                                        mode: .session(guid: SyntheticMac.shell.guid)))))
    // AppModel.swift:2939, CompanionClient.swift:142-143: a request.
    samples.append(try clientSample("subscribe",
                                    ClientEnvelope(requestID: subscribeRequestID,
                                                   payload: .subscribe(chatID: C.orchestratorChatID))))
    // AppModel.swift:3055, CompanionClient.swift:172-173: a send.
    samples.append(try clientSample("unsubscribe",
                                    ClientEnvelope(requestID: nil, payload: .unsubscribe(chatID: C.orchestratorChatID))))

    // AppModel.swift:3087-3091 trims and never sends empty text;
    // 3100-3118 builds the message; CompanionClient.swift:176-178 sends it
    // with partial:false. The text pins escaping and UTF-8: a mention, a
    // newline, '/', an emoji and non-ASCII letters. iOS stamps Date(); this
    // app sends whole milliseconds.
    let typed = "  Ask @\(SyntheticMac.codeReview.guid) to review src/widget.c\nthen ship it \u{1F680} — naïve café  \n"
    let trimmed = typed.trimmingCharacters(in: .whitespacesAndNewlines)
    require(!trimmed.isEmpty, "the typed message is empty")
    let published = Message(chatID: C.orchestratorChatID, author: .user,
                            content: .plainText(trimmed, context: nil),
                            sentDate: C.at(500),
                            uniqueID: fixedUUID("11111111-2222-4333-8444-000000000500"))
    samples.append(try clientSample("publish-plain-text",
                                    ClientEnvelope(requestID: nil,
                                                   payload: .publish(message: published,
                                                                     toChatID: C.orchestratorChatID,
                                                                     partial: false))))
    // AppModel.swift:2624, CompanionClient.swift:122-123: a send.
    samples.append(try clientSample("delete-chat",
                                    ClientEnvelope(requestID: nil, payload: .deleteChat(chatID: C.sessionChatID))))
    // AppModel.swift:2640-2650, CompanionClient.swift:130-131: a send of the
    // chosen message and every later one (a suffix): the history's last two.
    let removedIDs = history.suffix(2).map(\.uniqueID)
    samples.append(try clientSample("delete-messages",
                                    ClientEnvelope(requestID: nil,
                                                   payload: .deleteMessages(chatID: C.orchestratorChatID,
                                                                            messageIDs: removedIDs))))
    // AppModel.swift:2756, CompanionClient.swift:137-138: a send.
    samples.append(try clientSample("set-chat-muted-on",
                                    ClientEnvelope(requestID: nil,
                                                   payload: .setChatMuted(chatID: C.sessionChatID, muted: true))))
    samples.append(try clientSample("set-chat-muted-off",
                                    ClientEnvelope(requestID: nil,
                                                   payload: .setChatMuted(chatID: C.sessionChatID, muted: false))))
    // AppModel.swift:4192, CompanionClient.swift:215-216: a request. One of
    // each identifier form (CompanionMessages.swift:77-79).
    let mentionIdentifiers = [SyntheticMac.notes.guid,
                              "session:" + C.notesLegacyGuid,
                              C.workgroupID,
                              C.closedSessionGuid]
    samples.append(try clientSample("resolve-mentions",
                                    ClientEnvelope(requestID: resolveMentionsRequestID,
                                                   payload: .resolveMentions(identifiers: mentionIdentifiers))))
    // AppModel.swift:4288, CompanionClient.swift:422-423: a request.
    samples.append(try clientSample("fetch-workgroup-info",
                                    ClientEnvelope(requestID: fetchWorkgroupInfoRequestID,
                                                   payload: .fetchWorkgroupInfo(workgroupID: C.workgroupID))))
    // AppModel.swift:4262-4269: the bound chat's raw terminalSessionGuid;
    // CompanionClient.swift:227-228: a request.
    samples.append(try clientSample("fetch-session-screen-info",
                                    ClientEnvelope(requestID: fetchSessionScreenInfoRequestID,
                                                   payload: .fetchSessionScreenInfo(
                                                       sessionGuid: C.sessionChat.terminalSessionGuid ?? ""))))
    let phoneSampleCount = samples.count

    // MARK: Mac to phone, replies

    let lastOrchestratorContent = history.last?.content ?? .unsupported
    let entries = C.listEntries(lastOrchestratorContent: lastOrchestratorContent)
    let sessions = [SyntheticMac.shell, SyntheticMac.codeReview, SyntheticMac.buildLog, SyntheticMac.planner,
                    SyntheticMac.tester, SyntheticMac.notes, SyntheticMac.docs]
    require(entries[1].snippet?.count == 200, "the session chat's snippet is not cut at 200 characters")
    // CompanionHostBridge.swift:448-455 with chatEntries (2205-2217).
    samples.append(try hostSample("chats-and-sessions",
                                  HostEnvelope(requestID: listChatsRequestID,
                                               payload: .chatsAndSessions(chats: entries, sessions: sessions))))
    // AI off: no chats, sessions still listed (CompanionHostBridge.swift:448-455).
    samples.append(try hostSample("chats-and-sessions-ai-off",
                                  HostEnvelope(requestID: listChatsRequestID,
                                               payload: .chatsAndSessions(chats: [], sessions: sessions))))

    // CompanionHostBridge.swift:1361-1409 (handleCreate): the chat ChatBroker
    // makes (ChatBroker.swift:83-97, 99-143: permissions "", the provider's
    // model pinned), flipped to orchestration for that mode, then
    // entry(forChatID:) (2220-2227). Its only message so far is the hidden
    // setPermissions, so no snippet.
    let createdAt = C.at(600)
    let createdOrchestrator = Chat(id: C.createdOrchestratorChatID, title: "Orchestrator",
                                   creationDate: createdAt, lastModifiedDate: createdAt,
                                   orchestrationEnabled: true, terminalSessionGuid: nil, browserSessionGuid: nil,
                                   permissions: "", vectorStore: nil, modelName: C.modelName, modelVendor: nil,
                                   blobProtocol: nil, claimedScopes: [], watchers: [], icon: nil)
    samples.append(try hostSample("chat-created",
                                  HostEnvelope(requestID: createOrchestratorChatRequestID,
                                               payload: .chatCreated(entry: CompanionChatListEntry(
                                                   chat: createdOrchestrator, snippet: nil, muted: false)))))
    // A session mode files the guid as the terminal binding (1375-1381).
    let createdSession = Chat(id: C.createdSessionChatID, title: "New Chat",
                              creationDate: C.at(610), lastModifiedDate: C.at(610),
                              orchestrationEnabled: false, terminalSessionGuid: SyntheticMac.shell.guid,
                              browserSessionGuid: nil, permissions: "", vectorStore: nil, modelName: C.modelName,
                              modelVendor: nil, blobProtocol: nil, claimedScopes: [], watchers: [], icon: nil)
    samples.append(try hostSample("chat-created-session",
                                  HostEnvelope(requestID: createSessionChatRequestID,
                                               payload: .chatCreated(entry: CompanionChatListEntry(
                                                   chat: createdSession, snippet: nil, muted: false)))))

    // CompanionHostBridge.swift:1411-1427 (handleSubscribe) with history(chatID:)
    // (2229-2239): the visible messages and the chat's max seq.
    samples.append(try hostSample("history",
                                  HostEnvelope(requestID: subscribeRequestID,
                                               payload: .history(chatID: C.orchestratorChatID, messages: history,
                                                                 maxSeq: 57))))
    // An unknown or deleted chat: no messages, maxSeq 0, not an error
    // (2229-2233, 1424).
    samples.append(try hostSample("history-empty",
                                  HostEnvelope(requestID: subscribeRequestID,
                                               payload: .history(chatID: C.unknownChatID, messages: [], maxSeq: 0))))

    // CompanionHostBridge.swift:507-509 with resolveMention (1989-2002) and
    // OrchestrationMentionRenderer.resolve: a session hit reveals by its
    // stableID, a legacy guid too; a workgroup hit reveals its leader; a miss
    // has only the identifier.
    let resolutions = [
        CompanionMentionResolution(identifier: SyntheticMac.notes.guid, displayName: "vim",
                                   sessionGuid: SyntheticMac.notes.guid, workgroupID: nil),
        CompanionMentionResolution(identifier: "session:" + C.notesLegacyGuid, displayName: "vim",
                                   sessionGuid: SyntheticMac.notes.guid, workgroupID: nil),
        CompanionMentionResolution(identifier: C.workgroupID, displayName: C.workgroupName,
                                   sessionGuid: SyntheticMac.shell.guid, workgroupID: C.workgroupID),
        CompanionMentionResolution(identifier: C.closedSessionGuid, displayName: nil,
                                   sessionGuid: nil, workgroupID: nil)
    ]
    require(resolutions.map(\.identifier) == mentionIdentifiers, "the resolutions do not answer the request in order")
    samples.append(try hostSample("mentions-resolved",
                                  HostEnvelope(requestID: resolveMentionsRequestID,
                                               payload: .mentionsResolved(resolutions))))

    // CompanionHostBridge.swift:2164-2200 (handleFetchWorkgroupInfo): members
    // by role; a member whose session has not launched has no session and
    // state unknown; status texts are nil when empty.
    let members = [
        CompanionWorkgroupMember(roleName: "Implementer", sessionGuid: SyntheticMac.shell.guid, sessionName: "zsh",
                                 statusText: nil, detailText: nil, state: .idle),
        CompanionWorkgroupMember(roleName: "Code Review", sessionGuid: SyntheticMac.codeReview.guid,
                                 sessionName: "Code Review", statusText: "Reviewing",
                                 detailText: "widget.c, 3 of 7 hunks", state: .working),
        CompanionWorkgroupMember(roleName: "Planner", sessionGuid: SyntheticMac.planner.guid, sessionName: "claude",
                                 statusText: "Needs input", detailText: nil, state: .waiting),
        CompanionWorkgroupMember(roleName: "Tester", sessionGuid: SyntheticMac.tester.guid, sessionName: "claude",
                                 statusText: nil, detailText: nil, state: .unknown),
        CompanionWorkgroupMember(roleName: "docs-writer", sessionGuid: nil, sessionName: nil,
                                 statusText: nil, detailText: nil, state: .unknown)
    ]
    samples.append(try hostSample("workgroup-info",
                                  HostEnvelope(requestID: fetchWorkgroupInfoRequestID,
                                               payload: .workgroupInfo(CompanionWorkgroupInfo(
                                                   workgroupID: C.workgroupID, name: C.workgroupName,
                                                   members: members)))))

    // CompanionHostBridge.swift:2023-2040: the guid echoes the request; width,
    // line height and scale are Doubles (fractional here to pin number decoding).
    samples.append(try hostSample("session-screen-info",
                                  HostEnvelope(requestID: fetchSessionScreenInfoRequestID,
                                               payload: .sessionScreenInfo(CompanionSessionScreenInfo(
                                                   guid: C.sessionChat.terminalSessionGuid ?? "",
                                                   name: "zsh", lineCount: 1025, columns: 81,
                                                   width: 647.875, lineHeight: 16.25, scale: 1.5)))))

    // CompanionHostBridge.swift:2276-2298 (requireAI): an AI-only request
    // (subscribe, 2318-2321) while AI is off, to a phone at 13 or later.
    samples.append(try hostSample("error-ai-unavailable",
                                  HostEnvelope(requestID: subscribeRequestID,
                                               payload: .error(CompanionError(
                                                   code: .aiUnavailable,
                                                   message: "AI features are turned off on the paired Mac.")))))
    // CompanionHostBridge.swift:2164-2170.
    samples.append(try hostSample("error-unknown-workgroup",
                                  HostEnvelope(requestID: fetchWorkgroupInfoRequestID,
                                               payload: .error(CompanionError(
                                                   code: .unknownSession,
                                                   message: "That workgroup no longer exists.")))))
    // CompanionHostBridge.swift:458-470: deleteChat is a send, so the error
    // carries no requestID. The text is "\(error)": here
    // CompanionMacError.chatSystemUnavailable (CompanionMacIdentity.swift:351-352).
    // Deleting an unknown chat is not an error (ChatListModel.swift:97-100).
    samples.append(try hostSample("error-delete-refused",
                                  HostEnvelope(requestID: nil,
                                               payload: .error(CompanionError(
                                                   code: .internalError,
                                                   message: "The chat system is not available")))))
    // CompanionHostBridge.swift:2003-2010 (contentSession).
    samples.append(try hostSample("error-unknown-session",
                                  HostEnvelope(requestID: fetchSessionScreenInfoRequestID,
                                               payload: .error(CompanionError(
                                                   code: .unknownSession,
                                                   message: "That session no longer exists. It may have been closed.")))))

    // MARK: Mac to phone, events (no requestID)

    // CompanionHostBridge.swift:1825-1834: every visible broker delivery,
    // partial exactly for append and appendAttachment (1861-1868).
    func delivery(_ name: String, _ message: Message, chatID: String = C.orchestratorChatID) throws {
        let partial: Bool
        switch message.content {
        case .append, .appendAttachment: partial = true
        default: partial = false
        }
        require(!message.hiddenFromClient, "\(name): the Mac does not forward hidden content")
        samples.append(try hostSample(name, HostEnvelope(requestID: nil,
                                                         payload: .delivery(message: message, chatID: chatID,
                                                                            partial: partial))))
    }
    // The phone's publish comes back verbatim (1812-1823).
    try delivery("delivery-user-echo", published)
    let reply = C.message(C.orchestratorChatID, .agent, .markdown("Running the tests"), sentDate: C.at(501),
                          "11111111-2222-4333-8444-000000000501")
    try delivery("delivery-agent-opening", reply)
    try delivery("delivery-append",
                 C.message(C.orchestratorChatID, .agent, .append(string: " in **zsh** now.", uuid: reply.uniqueID),
                           sentDate: C.at(502), "11111111-2222-4333-8444-000000000502"))
    try delivery("delivery-append-attachment-code",
                 C.message(C.orchestratorChatID, .agent,
                           .appendAttachment(attachment: LLM.Message.Attachment(inline: true, id: "ci_run_tests",
                                                                                type: .code("print('ok')")),
                                             uuid: reply.uniqueID),
                           sentDate: C.at(503), "11111111-2222-4333-8444-000000000503"))
    try delivery("delivery-append-attachment-status",
                 C.message(C.orchestratorChatID, .agent,
                           .appendAttachment(attachment: LLM.Message.Attachment(inline: true, id: "status-reasoning",
                                                                                type: .statusUpdate(.reasoningSummaryUpdate(
                                                                                    "Checking which test is flaky."))),
                                             uuid: reply.uniqueID),
                           sentDate: C.at(504), "11111111-2222-4333-8444-000000000504"))
    try delivery("delivery-remote-command-classic",
                 C.message(C.sessionChatID, .agent, .remoteCommandRequest(.classic(C.executeMakeTest), safe: false),
                           sentDate: C.at(505), "11111111-2222-4333-8444-000000000505"),
                 chatID: C.sessionChatID)
    try delivery("delivery-remote-command-external",
                 C.message(C.orchestratorChatID, .agent,
                           .remoteCommandRequest(.external(C.sendTextToImplementer), safe: nil),
                           sentDate: C.at(506), "11111111-2222-4333-8444-000000000506"))
    try delivery("delivery-select-session-request",
                 C.message(C.sessionChatID, .agent,
                           .selectSessionRequest(C.message(C.sessionChatID, .user,
                                                           .plainText("Run the tests in my build session.", context: nil),
                                                           sentDate: C.at(506), "11111111-2222-4333-8444-000000000516"),
                                                 terminal: true),
                           sentDate: C.at(507), "11111111-2222-4333-8444-000000000507"),
                 chatID: C.sessionChatID)
    try delivery("delivery-workgroup-permission-request",
                 C.message(C.orchestratorChatID, .agent, .clientLocal(ClientLocal(action: everyClientLocalAction[7])),
                           sentDate: C.at(508), "11111111-2222-4333-8444-000000000508"))
    try delivery("delivery-enable-orchestration-request",
                 C.message(C.sessionChatID, .agent, .clientLocal(ClientLocal(action: everyClientLocalAction[8])),
                           sentDate: C.at(509), "11111111-2222-4333-8444-000000000509"),
                 chatID: C.sessionChatID)
    try delivery("delivery-notice",
                 C.message(C.sessionChatID, .agent, .clientLocal(ClientLocal(action: .notice("Linked this chat to zsh."))),
                           sentDate: C.at(510), "11111111-2222-4333-8444-000000000510"),
                 chatID: C.sessionChatID)
    try delivery("delivery-watcher-event",
                 C.message(C.orchestratorChatID, .user, .watcherEvent(C.watcherFired),
                           sentDate: C.at(511), "11111111-2222-4333-8444-000000000511"))

    // CompanionHostBridge.swift:1836-1838.
    samples.append(try hostSample("typing-status-on",
                                  HostEnvelope(requestID: nil,
                                               payload: .typingStatus(isTyping: true, participant: .agent,
                                                                      chatID: C.orchestratorChatID))))
    samples.append(try hostSample("typing-status-off",
                                  HostEnvelope(requestID: nil,
                                               payload: .typingStatus(isTyping: false, participant: .agent,
                                                                      chatID: C.orchestratorChatID))))
    // CompanionHostBridge.swift:1839-1849 (live) and 1091-1107 (the subscribe snapshot's started).
    samples.append(try hostSample("turn-lifecycle-started",
                                  HostEnvelope(requestID: nil,
                                               payload: .turnLifecycle(event: .started, chatID: C.orchestratorChatID))))
    samples.append(try hostSample("turn-lifecycle-ended",
                                  HostEnvelope(requestID: nil,
                                               payload: .turnLifecycle(event: .ended, chatID: C.orchestratorChatID))))
    // CompanionHostBridge.swift:1850-1858: the echo of delete-messages.
    samples.append(try hostSample("messages-removed",
                                  HostEnvelope(requestID: nil,
                                               payload: .messagesRemoved(chatID: C.orchestratorChatID,
                                                                         messageIDs: removedIDs))))
    // CompanionHostBridge.swift:277-292: the whole list 0.5 s after a change;
    // here the session chat was deleted (delete-chat).
    samples.append(try hostSample("chat-list-changed",
                                  HostEnvelope(requestID: nil,
                                               payload: .chatListChanged(chats: entries.filter {
                                                   $0.chat.id != C.sessionChatID
                                               }))))
    // CompanionLink.swift:549-558: AI turned back on.
    samples.append(try hostSample("ai-availability-changed-on",
                                  HostEnvelope(requestID: nil, payload: .aiAvailabilityChanged(available: true))))

    try checkSessionSamples(samples)

    // MARK: Self-checks

    for sample in samples.prefix(phoneSampleCount) {
        require(sample.direction == .phoneToMac, "\(sample.name): expected a phone-to-Mac sample")
        try checkPhoneSentChatSample(sample)
    }
    for sample in samples.dropFirst(phoneSampleCount) {
        require(sample.direction == .macToPhone, "\(sample.name): expected a Mac-to-phone sample")
    }
    // Requests carry a requestID and sends do not (CompanionClient.swift:95-237).
    let requests: Set<String> = ["list-chats-and-sessions", "create-chat-orchestrator", "create-chat-session",
                                 "subscribe", "resolve-mentions", "fetch-workgroup-info", "fetch-session-screen-info"]
    for sample in samples.prefix(phoneSampleCount) {
        let tree = try jsonObject(sample.sortedWire)
        require((tree?["requestID"] != nil) == requests.contains(sample.name),
                "\(sample.name): requestID presence does not match request/send")
    }
    // history.json carries every visible stored Content case, every
    // ClientLocal action, every Subpart and every attachment type.
    guard let historySample = samples.first(where: { $0.name == "history" }),
          let historyTree = try jsonObject(historySample.sortedWire),
          let historyPayload = (historyTree["payload"] as? [String: Any])?["history"] as? [String: Any],
          let historyMessages = historyPayload["messages"] as? [[String: Any]] else {
        require(false, "history: unexpected shape")
        return samples
    }
    let contents = historyMessages.map { $0["content"] }
    let contentCases = Set(contents.compactMap(discriminator))
    let missingCases = requiredHistoryContentCases().subtracting(contentCases)
    require(missingCases.isEmpty, "history lacks content cases \(missingCases.sorted())")
    let actions = Set(contents.compactMap { content -> String? in
        guard let local = (content as? [String: Any])?["clientLocal"] as? [String: Any],
              let wrapped = local["_0"] as? [String: Any] else { return nil }
        return discriminator(wrapped["action"])
    })
    let missingActions = ClientLocal.Action.knownActionKeys.subtracting(actions)
    require(missingActions.isEmpty, "history lacks ClientLocal actions \(missingActions.sorted())")
    require(actions.count == everyClientLocalAction.count, "an action is not one of the ten")
    var subparts = Set<String>()
    var attachmentTypes = Set<String>()
    var mimeTypes = Set<String>()
    for message in history {
        guard case .multipart(let parts, _) = message.content else { continue }
        for part in parts {
            switch part {
            case .plainText: subparts.insert("plainText")
            case .markdown: subparts.insert("markdown")
            case .context: subparts.insert("context")
            case .attachment(let attachment):
                subparts.insert("attachment")
                switch attachment.type {
                case .code: attachmentTypes.insert("code")
                case .statusUpdate: attachmentTypes.insert("statusUpdate")
                case .fileID: attachmentTypes.insert("fileID")
                case .file(let file):
                    attachmentTypes.insert("file")
                    mimeTypes.insert(file.mimeType.hasPrefix("image/") ? "image" : "other")
                }
            }
        }
    }
    require(subparts == ["plainText", "markdown", "context", "attachment"], "history lacks a Subpart: \(subparts)")
    require(attachmentTypes == ["code", "statusUpdate", "fileID", "file"], "history lacks an attachment type")
    require(mimeTypes == ["image", "other"], "history lacks an image or a non-image file")
    let remoteKinds = Set(history.compactMap { message -> String? in
        guard case .remoteCommandRequest(let payload, _) = message.content else { return nil }
        return payload.classic == nil ? "external" : "classic"
    })
    require(remoteKinds == ["classic", "external"], "history lacks a classic or an external remote command")
    let plainContexts = Set(history.compactMap { message -> Bool? in
        guard case .plainText(_, let context) = message.content else { return nil }
        return context != nil
    })
    require(plainContexts == [true, false], "history lacks plainText with and without context")
    for message in history {
        require(!message.hiddenFromClient, "history holds hidden content \(contentCaseName(message.content))")
    }
    // Exactly one fractional date, in history.json.
    let historyText = String(decoding: historySample.sortedWire, as: UTF8.self)
    let fractionalDates = try Regex("\"(sentDate|timestamp|registeredAt|creationDate|lastModifiedDate)\":[0-9]+\\.[0-9]+")
    require(historyText.matches(of: fractionalDates).count == 1, "history must hold exactly one fractional date")
    for sample in samples where sample.name != "history" {
        let text = String(decoding: sample.sortedWire, as: UTF8.self)
        require(text.matches(of: fractionalDates).isEmpty, "\(sample.name): a fractional date outside history")
    }
    // Every delivered streaming delta names a message the phone holds.
    require(samples.contains { $0.name == "delivery-append" } && samples.contains { $0.name == "delivery-append-attachment-code" },
            "a streaming delta sample is missing")
    return samples
}

// MARK: - Deterministic descriptions

/// JSONValue (MacTypeShadows' stand-in payload) holds a Dictionary, whose
/// default description follows Swift's per-process hash seed. Sorting the
/// keys keeps `swiftValue` byte-identical from run to run; the wire encoding
/// is unchanged.
extension JSONValue: CustomDebugStringConvertible {
    var debugDescription: String {
        switch self {
        case .null: return "null"
        case .bool(let value): return "bool(\(value))"
        case .number(let value): return "number(\(value))"
        case .string(let value): return "string(\(String(reflecting: value)))"
        case .array(let values): return "array([\(values.map(\.debugDescription).joined(separator: ", "))])"
        case .object(let fields):
            let body = fields.keys.sorted().map { "\(String(reflecting: $0)): \(fields[$0]!.debugDescription)" }
            return "object([\(body.isEmpty ? ":" : body.joined(separator: ", "))])"
        }
    }
}
