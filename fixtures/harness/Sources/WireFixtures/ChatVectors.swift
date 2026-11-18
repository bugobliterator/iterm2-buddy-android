import CompanionProtocol
import Foundation

// Chat vectors (chat/*.json): upstream behaviour the Android chat code must
// reproduce, computed here by upstream v3.7.7's own compiled code (Message,
// LLM, RemoteCommand, MentionParser, StableSessionID) or, for the iOS merge
// rule that is not compiled, by a line-for-line port over upstream's
// compiled `Message.append`. Every text is synthetic.

/// The encoder for chat vector files: the fixture format (pretty, sorted
/// keys, '/' unescaped) with WireCoding's dates (milliseconds since 1970), so
/// an embedded Message reads exactly as the wire carries it.
func chatVectorEncoder() -> JSONEncoder {
    let encoder = fixtureEncoder()
    encoder.dateEncodingStrategy = .millisecondsSince1970
    return encoder
}

func writeChatVector<T: Encodable>(_ value: T, named name: String, to directory: URL) throws {
    var data = try chatVectorEncoder().encode(value)
    data.append(0x0a)
    try data.write(to: directory.appendingPathComponent(name), options: .atomic)
}

/// A JSON value embedded in a vector as a tree, through upstream's own
/// JSONValue (Companion/iTerm2Companion/Satellites/JSONValue.swift).
func embeddedJSON(_ data: Data) throws -> JSONValue {
    try WireCoding.decode(JSONValue.self, from: data)
}

/// A DecodingError as its kind and coding path, which do not change from run
/// to run (the debug description can quote the input).
func decodingErrorSummary(_ error: Error) -> String {
    func path(_ keys: [CodingKey]) -> String {
        keys.reduce(into: "") { text, key in
            if let index = key.intValue {
                text += "[\(index)]"
            } else {
                text += (text.isEmpty ? "" : ".") + key.stringValue
            }
        }
    }
    guard let decodingError = error as? DecodingError else {
        return String(describing: type(of: error))
    }
    switch decodingError {
    case .typeMismatch(_, let context): return "typeMismatch at \(path(context.codingPath))"
    case .valueNotFound(_, let context): return "valueNotFound at \(path(context.codingPath))"
    case .keyNotFound(let key, let context): return "keyNotFound \(key.stringValue) at \(path(context.codingPath))"
    case .dataCorrupted(let context): return "dataCorrupted at \(path(context.codingPath))"
    @unknown default: return "unknown DecodingError"
    }
}

/// `text` with `old` replaced; `old` must occur exactly `count` times.
func replacing(_ old: String, with new: String, in text: String, count: Int = 1, _ name: String) -> String {
    let occurrences = text.components(separatedBy: old).count - 1
    require(occurrences == count, "\(name): \(old) occurs \(occurrences) times, expected \(count)")
    return text.replacingOccurrences(of: old, with: new)
}

// MARK: - Decode tolerance

struct DecodeToleranceVector: Encodable {
    let name: String
    /// The exact bytes to decode, as text.
    let json: String
    /// "decodes" or "rejects", as upstream WireCoding decodes a HostEnvelope.
    let upstream: String
    /// What upstream decoded, re-encoded with sorted keys (absent keys stay absent).
    let upstreamValue: JSONValue?
    /// Why upstream rejected it: the DecodingError's kind and coding path.
    let upstreamError: String?
    /// What the Android app does with it, when that differs from upstream.
    let android: String?
}

func decodeToleranceVectors(samples: [SessionSample]) throws -> [DecodeToleranceVector] {
    func sampleText(_ name: String) -> String {
        guard let sample = samples.first(where: { $0.name == name }) else {
            require(false, "decode tolerance: \(name) is missing")
            return ""
        }
        return String(decoding: sample.sortedWire, as: UTF8.self)
    }
    func envelopeText(_ envelope: HostEnvelope) throws -> String {
        String(decoding: try sortedWireEncoder().encode(envelope), as: UTF8.self)
    }

    var vectors: [DecodeToleranceVector] = []
    /// Decodes `json` with upstream, requires the recorded verdict, and runs
    /// `check` on a decoded value.
    func add(_ name: String, _ json: String, expect: String, android: String? = nil,
             check: (HostEnvelope) -> Bool = { _ in true }) throws {
        do {
            let decoded = try WireCoding.decode(HostEnvelope.self, from: Data(json.utf8))
            require(expect == "decodes", "\(name): upstream decodes it, expected \(expect)")
            require(check(decoded), "\(name): upstream decoded an unexpected value: \(String(reflecting: decoded))")
            vectors.append(DecodeToleranceVector(name: name, json: json, upstream: "decodes",
                                                 upstreamValue: try embeddedJSON(try sortedWireEncoder().encode(decoded)),
                                                 upstreamError: nil, android: android))
        } catch let error as DecodingError {
            require(expect == "rejects", "\(name): upstream rejects it (\(decodingErrorSummary(error))), expected \(expect)")
            vectors.append(DecodeToleranceVector(name: name, json: json, upstream: "rejects", upstreamValue: nil,
                                                 upstreamError: decodingErrorSummary(error), android: android))
        }
    }
    func deliveredContent(_ envelope: HostEnvelope) -> Message.Content? {
        if case .delivery(let message, _, _) = envelope.payload { return message.content }
        return nil
    }
    func isUnsupported(_ content: Message.Content?) -> Bool {
        if case .unsupported? = content { return true }
        return false
    }

    // A newer Mac's content case: Message.init degrades the content to
    // .unsupported (Message.swift:746-757).
    try add("delivery-future-content-case",
            replacing("{\"markdown\":{\"_0\":\"Running the tests\"}}",
                      with: "{\"voiceNote\":{\"_0\":\"Running the tests\"}}",
                      in: sampleText("delivery-agent-opening"), "delivery-future-content-case"),
            expect: "decodes", check: { isUnsupported(deliveredContent($0)) })
    // A newer ClientLocal.Action inside a known clientLocal content: the
    // whole content degrades to .unsupported (Message.swift:204-214, 750-756).
    try add("delivery-future-client-local-action",
            replacing("\"action\":{\"notice\":", with: "\"action\":{\"shareScreen\":",
                      in: sampleText("delivery-notice"), "delivery-future-client-local-action"),
            expect: "decodes", check: { isUnsupported(deliveredContent($0)) })
    // TurnEvent's own decoder maps an unknown raw value to .unknownFuture
    // (Message.swift:68-81).
    try add("turn-lifecycle-future-event",
            replacing("\"event\":\"started\"", with: "\"event\":\"paused\"",
                      in: sampleText("turn-lifecycle-started"), "turn-lifecycle-future-event"),
            expect: "decodes", check: { envelope in
                if case .turnLifecycle(.unknownFuture, _) = envelope.payload { return true }
                return false
            })
    // A Mac older than chatMuteRevision (5) omits `muted`; it decodes as nil
    // (CompanionMessages.swift:67-70). No pairable Mac (11 or later) omits it.
    var withoutMuted = sampleText("chats-and-sessions")
    withoutMuted = replacing(",\"muted\":false", with: "", in: withoutMuted, count: 2, "chats-and-sessions-without-muted")
    withoutMuted = replacing(",\"muted\":true", with: "", in: withoutMuted, count: 1, "chats-and-sessions-without-muted")
    try add("chats-and-sessions-without-muted", withoutMuted, expect: "decodes", check: { envelope in
        guard case .chatsAndSessions(let chats, _) = envelope.payload else { return false }
        return chats.count == 3 && chats.allSatisfy { $0.muted == nil }
    })
    // A Mac older than aiDecouplingRevision (13) has no aiAvailable in its
    // hello; upstream decodes nil and the phone reads it as true
    // (CompanionMessages.swift:986-991). Built through upstream's types: a
    // revision 12 Mac's hello (CompanionLink.handleHello always sets
    // wantsNotificationPermission; no macStatus below 14).
    let olderHello = try envelopeText(HostEnvelope(requestID: helloRequestID,
                                                   payload: .hello(revision: CompanionProtocolVersion.messageDeletionRevision,
                                                                   minimumPeer: CompanionProtocolVersion.minimumPeer,
                                                                   wantsNotificationPermission: false,
                                                                   aiAvailable: nil, macStatus: nil)))
    require(!olderHello.contains("aiAvailable"), "hello-without-ai-available still has aiAvailable")
    try add("hello-without-ai-available", olderHello, expect: "decodes", android: "aiAvailable reads as true",
            check: { envelope in
                if case .hello(_, _, _, nil, nil) = envelope.payload { return true }
                return false
            })
    // SessionState is a plain String enum (WorkgroupWatcher.swift:170-175): a
    // newer state fails the whole workgroupInfo.
    try add("workgroup-info-future-state",
            replacing("\"state\":\"idle\"", with: "\"state\":\"sleeping\"",
                      in: sampleText("workgroup-info"), "workgroup-info-future-state"),
            expect: "rejects", android: "the member's state reads as UNKNOWN")
    // A corrupt body of a known content case is a DecodingError that fails
    // the whole frame (Message.swift:717-728, 746-757).
    let twoReplies = try envelopeText(HostEnvelope(
        requestID: subscribeRequestID,
        payload: .history(chatID: SyntheticChats.sessionChatID, messages: [
            SyntheticChats.message(SyntheticChats.sessionChatID, .agent, .markdown("First reply"),
                                   sentDate: SyntheticChats.at(700), "11111111-2222-4333-8444-000000000700"),
            SyntheticChats.message(SyntheticChats.sessionChatID, .agent, .markdown("Second reply"),
                                   sentDate: SyntheticChats.at(701), "11111111-2222-4333-8444-000000000701")
        ], maxSeq: 2)))
    try add("history-with-corrupt-markdown-body",
            replacing("{\"markdown\":{\"_0\":\"Second reply\"}}", with: "{\"markdown\":{\"_0\":42}}",
                      in: twoReplies, "history-with-corrupt-markdown-body"),
            expect: "rejects", android: "the frame is kept; the second message's content is Unreadable")
    // The same history with a newer content case: only that message degrades.
    try add("history-with-future-content-case",
            replacing("{\"markdown\":{\"_0\":\"Second reply\"}}", with: "{\"voiceNote\":{\"_0\":\"Second reply\"}}",
                      in: twoReplies, "history-with-future-content-case"),
            expect: "decodes", check: { envelope in
                guard case .history(_, let messages, _) = envelope.payload, messages.count == 2 else { return false }
                return !isUnsupported(messages[0].content) && isUnsupported(messages[1].content)
            })
    // A classic remote command whose RemoteCommand.Content case is unknown:
    // RemoteCommand's synthesized decode throws, so the frame fails
    // (RemoteCommand.swift:82-92, 140-197).
    try add("delivery-remote-command-future-tool",
            replacing("\"executeCommand\":{\"_0\":", with: "\"openPullRequest\":{\"_0\":",
                      in: sampleText("delivery-remote-command-classic"), "delivery-remote-command-future-tool"),
            expect: "rejects", android: "the message is kept; its content is Unreadable")
    return vectors
}

// MARK: - Streaming merge (AppModel.swift:5215-5275)

/// iOS AppModel `apply(_:into:chatID:)` and `applyStreamDelta`, ported line for
/// line (AppModel.swift:5215-5275; mention bookkeeping left out). The text
/// and attachment merges are upstream's compiled `Message.append`
/// (Message.swift:627-691) and `Attachment.appending` (LLM.swift:158-191).
func iosApply(_ message: Message, into transcript: inout [Message], chatID: String) {
    func applyStreamDelta(to messageID: UUID, fallbackDate: Date,
                          mutate: (inout Message) -> Void, orStartWith makeContent: () -> Message.Content) {
        if let index = transcript.firstIndex(where: { $0.uniqueID == messageID }) {
            switch transcript[index].content {
            case .plainText, .markdown, .multipart:
                mutate(&transcript[index])
            default:
                break
            }
        } else {
            transcript.append(Message(chatID: chatID, author: .agent, content: makeContent(),
                                      sentDate: fallbackDate, uniqueID: messageID))
        }
    }
    switch message.content {
    case .append(let string, let uuid):
        applyStreamDelta(to: uuid, fallbackDate: message.sentDate,
                         mutate: { $0.append(string, useMarkdownIfAmbiguous: true) },
                         orStartWith: { .markdown(string) })
    case .appendAttachment(let attachment, let uuid):
        applyStreamDelta(to: uuid, fallbackDate: message.sentDate,
                         mutate: { $0.append(attachment, vectorStoreID: nil) },
                         orStartWith: { .multipart([.attachment(attachment)], vectorStoreID: nil) })
    case .commit:
        break
    default:
        if let index = transcript.firstIndex(where: { $0.uniqueID == message.uniqueID }) {
            transcript[index] = message
        } else {
            transcript.append(message)
        }
    }
}

struct StreamingMergeVector: Encodable {
    let name: String
    let chatID: String
    let transcript: [Message]
    let deltas: [Message]
    let expected: [Message]
}

func streamingMergeVectors() throws -> [StreamingMergeVector] {
    typealias C = SyntheticChats
    let chat = C.sessionChatID
    var serial = 0
    /// Message IDs in this file count up from ...0800.
    func nextID() -> String {
        serial += 1
        return String(format: "22222222-3333-4444-8555-%012d", 800 + serial)
    }
    func agent(_ content: Message.Content, _ id: String, at seconds: Int64 = 800) -> Message {
        C.message(chat, .agent, content, sentDate: C.at(seconds), id)
    }
    func append(_ text: String, to target: String, at seconds: Int64 = 801) -> Message {
        C.message(chat, .agent, .append(string: text, uuid: fixedUUID(target)), sentDate: C.at(seconds), nextID())
    }
    func appendAttachment(_ attachment: LLM.Message.Attachment, to target: String, at seconds: Int64 = 801) -> Message {
        C.message(chat, .agent, .appendAttachment(attachment: attachment, uuid: fixedUUID(target)),
                  sentDate: C.at(seconds), nextID())
    }
    func code(_ id: String, _ text: String) -> LLM.Message.Attachment {
        LLM.Message.Attachment(inline: true, id: id, type: .code(text))
    }
    func status(_ id: String, _ update: LLM.Message.StatusUpdate) -> LLM.Message.Attachment {
        LLM.Message.Attachment(inline: true, id: id, type: .statusUpdate(update))
    }
    func file(_ id: String, _ name: String, _ bytes: String, _ mime: String) -> LLM.Message.Attachment {
        LLM.Message.Attachment(inline: false, id: id,
                               type: .file(.init(name: name, content: Data(bytes.utf8), mimeType: mime, localPath: nil)))
    }

    var vectors: [StreamingMergeVector] = []
    func add(_ name: String, transcript: [Message], deltas: [Message]) {
        var merged = transcript
        for delta in deltas {
            iosApply(delta, into: &merged, chatID: chat)
        }
        vectors.append(StreamingMergeVector(name: name, chatID: chat, transcript: transcript, deltas: deltas,
                                            expected: merged))
    }

    let user = C.message(chat, .user, .plainText("Why is the widget test flaky?", context: nil),
                         sentDate: C.at(799), nextID())
    let target = nextID()
    add("append-to-markdown",
        transcript: [user, agent(.markdown("The test reads"), target)],
        deltas: [append(" the size", to: target), append(" too early.", to: target, at: 802)])
    let plainTarget = nextID()
    add("append-to-plain-text-drops-context",
        transcript: [agent(.plainText("Checking", context: "terminal state"), plainTarget)],
        deltas: [append(" the build log.", to: plainTarget)])
    let multipartMarkdown = nextID()
    add("append-to-multipart-ending-in-markdown",
        transcript: [agent(.multipart([.attachment(code("ci_a", "print(1)")), .markdown("It printed")],
                                      vectorStoreID: "vs_widget_notes"), multipartMarkdown)],
        deltas: [append(" 1.", to: multipartMarkdown)])
    let multipartPlain = nextID()
    add("append-to-multipart-ending-in-plain-text",
        transcript: [agent(.multipart([.markdown("Steps:"), .plainText("one")], vectorStoreID: nil), multipartPlain)],
        deltas: [append(", two", to: multipartPlain)])
    let multipartAttachment = nextID()
    add("append-to-multipart-ending-in-attachment",
        transcript: [agent(.multipart([.markdown("Output:"), .attachment(code("ci_b", "print(2)"))],
                                      vectorStoreID: nil), multipartAttachment)],
        deltas: [append("That is the output.", to: multipartAttachment)])
    let missing = nextID()
    add("append-to-missing-target-starts-agent-markdown",
        transcript: [user],
        deltas: [append("Starting fresh", to: missing, at: 803), append(" here.", to: missing, at: 804)])
    let missingAttachment = nextID()
    add("append-attachment-to-missing-target-starts-multipart",
        transcript: [user],
        deltas: [appendAttachment(code("ci_c", "print(3)"), to: missingAttachment, at: 805)])
    let command = nextID()
    add("append-to-non-text-target-is-ignored",
        transcript: [agent(.remoteCommandRequest(.classic(C.executeMakeTest), safe: false), command)],
        deltas: [append("ignored", to: command),
                 appendAttachment(code("ci_d", "ignored"), to: command)])
    let sameCode = nextID()
    add("code-attachments-with-the-same-id-coalesce",
        transcript: [agent(.multipart([.markdown("Running:"), .attachment(code("ci_e", "for i in range(3):\n"))],
                                      vectorStoreID: nil), sameCode)],
        deltas: [appendAttachment(code("ci_e", "    print(i)\n"), to: sameCode)])
    let otherCode = nextID()
    add("code-attachments-with-different-ids-do-not-coalesce",
        transcript: [agent(.multipart([.attachment(code("ci_f", "print(4)"))], vectorStoreID: nil), otherCode)],
        deltas: [appendAttachment(code("ci_g", "print(5)"), to: otherCode)])
    let statuses = nextID()
    add("status-updates-always-coalesce",
        transcript: [agent(.multipart([.attachment(status("status-a", .webSearchStarted))], vectorStoreID: nil),
                           statuses)],
        deltas: [appendAttachment(status("status-b", .webSearchFinished("widget flake")), to: statuses),
                 appendAttachment(status("status-c", .reasoningSummaryUpdate("Comparing runs.")), to: statuses,
                                  at: 802)])
    let files = nextID()
    add("file-attachments-with-the-same-id-concatenate",
        transcript: [agent(.multipart([.attachment(file("file-a", "log", "line 1\n", "text/"))], vectorStoreID: nil),
                           files)],
        deltas: [appendAttachment(file("file-a", ".txt", "line 2\n", "plain"), to: files)])
    let attachToMarkdown = nextID()
    add("append-attachment-to-markdown-makes-multipart",
        transcript: [agent(.markdown("Here is the fix:"), attachToMarkdown)],
        deltas: [appendAttachment(code("ci_h", "x = 1"), to: attachToMarkdown)])
    let attachToPlain = nextID()
    add("append-attachment-to-plain-text-makes-multipart",
        transcript: [agent(.plainText("Plain answer", context: "dropped"), attachToPlain)],
        deltas: [appendAttachment(code("ci_i", "y = 2"), to: attachToPlain)])
    let upserted = nextID()
    let after = agent(.markdown("A later message."), nextID(), at: 810)
    add("upsert-replaces-in-place",
        transcript: [user, agent(.markdown("Draft answer"), upserted), after],
        deltas: [agent(.markdown("Final answer"), upserted, at: 811)])
    let echo = C.message(chat, .user, .plainText("Ship it", context: nil), sentDate: C.at(820), nextID())
    add("user-echo-replaces-optimistic-copy",
        transcript: [user, echo],
        deltas: [echo])
    let appended = agent(.markdown("A new message."), nextID(), at: 821)
    add("upsert-appends-an-unknown-message",
        transcript: [user],
        deltas: [appended])
    let committed = nextID()
    add("commit-is-ignored",
        transcript: [agent(.markdown("Done."), committed)],
        deltas: [C.message(chat, .agent, .commit(fixedUUID(committed)), sentDate: C.at(830), nextID())])

    // A 1 000-delta reply: the opening delivery, then 1 000 appends.
    let longReply = nextID()
    var longDeltas = [agent(.markdown(""), longReply, at: 900)]
    var expectedText = ""
    for index in 0..<1000 {
        let chunk = "chunk \(index) "
        expectedText += chunk
        longDeltas.append(C.message(chat, .agent, .append(string: chunk, uuid: fixedUUID(longReply)),
                                    sentDate: C.at(901), String(format: "33333333-4444-4555-8666-%012d", index)))
    }
    add("one-thousand-delta-reply", transcript: [user], deltas: longDeltas)

    // Self-checks on what upstream produced.
    func vector(_ name: String) -> StreamingMergeVector {
        guard let found = vectors.first(where: { $0.name == name }) else {
            require(false, "streaming merge: \(name) is missing")
            return vectors[0]
        }
        return found
    }
    func contentText(_ message: Message?) -> String {
        guard let message, let data = try? sortedWireEncoder().encode(message.content) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }
    let long = vector("one-thousand-delta-reply")
    require(long.deltas.count == 1001 && long.expected.count == 2, "the 1000-delta reply did not merge into one bubble")
    if case .markdown(let text)? = long.expected.last?.content {
        require(text == expectedText, "the 1000-delta reply lost or reordered a chunk")
    } else {
        require(false, "the 1000-delta reply is not markdown")
    }
    let ignored = vector("append-to-non-text-target-is-ignored")
    require(contentText(ignored.expected.first) == contentText(ignored.transcript.first),
            "a delta changed a non-text target")
    require(vector("commit-is-ignored").expected.count == 1, "a commit changed the transcript")
    require(vector("user-echo-replaces-optimistic-copy").expected.count == 2, "the echo was appended twice")
    require(vector("upsert-replaces-in-place").expected.map(\.uniqueID)
                == vector("upsert-replaces-in-place").transcript.map(\.uniqueID),
            "the upsert moved the message")
    require(vector("code-attachments-with-the-same-id-coalesce").expected.count == 1, "same-id code split")
    if case .multipart(let parts, _)? = vector("code-attachments-with-the-same-id-coalesce").expected.first?.content {
        require(parts.count == 2, "same-id code attachments did not coalesce")
    }
    if case .multipart(let parts, _)? = vector("code-attachments-with-different-ids-do-not-coalesce").expected.first?.content {
        require(parts.count == 2, "different-id code attachments coalesced")
    }
    if case .multipart(let parts, _)? = vector("status-updates-always-coalesce").expected.first?.content {
        require(parts.count == 1, "status updates did not coalesce")
    }
    let fresh = vector("append-to-missing-target-starts-agent-markdown").expected.last
    require(fresh?.author == .agent && fresh?.sentDate == C.at(803), "a fresh bubble is not dated by its first delta")
    return vectors
}

// MARK: - Mention parser (MentionParser.swift)

struct MentionVector: Encodable {
    let location: Int
    let length: Int
    let prefix: String?
    let token: String
    let identifier: String

    private enum CodingKeys: String, CodingKey { case location, length, prefix, token, identifier }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(location, forKey: .location)
        try container.encode(length, forKey: .length)
        try container.encode(prefix, forKey: .prefix)  // null when absent
        try container.encode(token, forKey: .token)
        try container.encode(identifier, forKey: .identifier)
    }
}

struct MentionCase: Encodable {
    let name: String
    let text: String
    let atSignOptional: Bool
    let mentions: [MentionVector]
}

struct MentionSplit: Encodable {
    let name: String
    let identifier: String
    let prefix: String?
    let token: String?
    let invalid: Bool?

    private enum CodingKeys: String, CodingKey { case name, identifier, prefix, token, invalid }

    /// A valid identifier writes `prefix` (null when absent) and `token`; an
    /// invalid one writes `invalid: true` only.
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(name, forKey: .name)
        try container.encode(identifier, forKey: .identifier)
        if invalid == true {
            try container.encode(true, forKey: .invalid)
        } else {
            try container.encode(prefix, forKey: .prefix)
            try container.encode(token, forKey: .token)
        }
    }
}

struct MentionParserVectors: Encodable {
    let cases: [MentionCase]
    let split: [MentionSplit]
}

func mentionParserVectors() -> MentionParserVectors {
    let shell = SyntheticMac.shell.guid          // ptys_7K2M9Q4R8T1V + check
    let review = SyntheticMac.codeReview.guid
    let notes = SyntheticMac.notes.guid
    let uuid = SyntheticChats.notesLegacyGuid
    let workgroupUUID = String(SyntheticChats.workgroupID.dropFirst(3))
    // A stableID with every Crockford confusable folded back: '1' written
    // 'l', '0' written 'O' (shell contains a '1'; docs' check makes no '0',
    // so the body of review is used for 'O').
    let shellConfusable = shell.replacingOccurrences(of: "1", with: "l")
    let reviewConfusable = review.replacingOccurrences(of: "0", with: "O")
    require(shellConfusable != shell && reviewConfusable != review, "no confusable to fold")
    require(StableSessionID.canonical(shellConfusable) == shell, "canonical does not fold the confusable")
    // A stableID-shaped token whose check character is wrong.
    let badCheck = String(shell.dropLast()) + (shell.last == "0" ? "1" : "0")
    require(StableSessionID.canonical(badCheck) == nil, "the bad-check stableID is valid")

    let inputs: [(name: String, text: String, atSignOptional: Bool)] = [
        ("stable-id", "Ask @\(shell) to run the tests.", false),
        ("legacy-uuid", "Ask @\(uuid) to run the tests.", false),
        ("session-prefix-uuid", "Watch @session:\(uuid) for me.", false),
        ("session-prefix-stable-id", "Watch @session:\(shell) for me.", false),
        ("workgroup", "Tell @wg-\(workgroupUUID) to start.", false),
        ("uppercase-prefixes-are-lowercased", "@SESSION:\(shell) and @WG-\(workgroupUUID)", false),
        ("uppercase-stable-id-prefix", "Ask @PTYS_\(shell.dropFirst(5)) now.", false),
        ("lowercase-stable-id-is-folded", "Ask @\(shell.lowercased()) now.", false),
        ("crockford-confusable-l-for-one", "Ask @\(shellConfusable) now.", false),
        ("crockford-confusable-o-for-zero", "Ask @\(reviewConfusable) now.", false),
        ("lowercase-uuid-is-verbatim", "Ask @\(uuid.lowercased()) now.", false),
        ("bad-check-character-with-at-sign", "Ask @\(badCheck) now.", false),
        ("bad-check-character-bare", "Ask \(badCheck) now.", true),
        ("bare-stable-id-without-option", "Ask \(shell) to run the tests.", false),
        ("bare-stable-id-with-option", "Ask \(shell) to run the tests.", true),
        ("bare-uuid-is-never-a-mention", "Session \(uuid) finished.", true),
        ("bare-session-prefix-needs-at-sign", "Watch session:\(shell) now.", true),
        ("bare-glued-left-boundary", "abc\(shell) and x_\(review) and -\(notes)", true),
        ("at-sign-glued-left-is-fine", "x@\(shell)", false),
        ("trailing-hyphen-x-is-no-match", "Ask @\(shell)-x now.", false),
        ("trailing-alphanumeric-is-no-match", "Ask @\(shell)Z and @\(uuid)0 now.", false),
        ("trailing-punctuation", "(@\(shell)), @\(review). @\(notes)!", false),
        ("next-to-emoji", "\u{1F680}@\(shell) \u{1F44B}\u{1F3FD} @wg-\(workgroupUUID)\u{2705}", false),
        ("bare-after-emoji", "\u{1F680}\(shell) done", true),
        ("two-stable-ids-in-one-paragraph", "@\(shell) and @\(review) should pair up.", false),
        ("mention-in-code-span", "Run `@\(shell)` literally, then ask @\(review).", false),
        ("no-mentions", "Nothing to see here: @ alone, @session: and @wg- too.", true)
    ]
    let cases = inputs.map { input -> MentionCase in
        let mentions = MentionParser.mentions(in: input.text, atSignOptional: input.atSignOptional).map {
            MentionVector(location: $0.range.location, length: $0.range.length, prefix: $0.prefix,
                          token: $0.token, identifier: $0.identifier)
        }
        return MentionCase(name: input.name, text: input.text, atSignOptional: input.atSignOptional,
                           mentions: mentions)
    }

    let splitInputs: [(name: String, identifier: String)] = [
        ("stable-id", shell),
        ("lowercase-stable-id", shell.lowercased()),
        ("legacy-uuid", uuid),
        ("session-prefix-uuid", "session:" + uuid),
        ("session-prefix-stable-id", "session:" + shell),
        ("workgroup", "wg-" + workgroupUUID),
        ("uppercase-workgroup-prefix", "WG-" + workgroupUUID),
        ("bad-check-character", badCheck),
        ("crockford-confusable", shellConfusable),
        ("with-at-sign", "@" + shell),
        ("trailing-text", shell + " now"),
        ("leading-space", " " + shell),
        ("unknown-prefix", "tab:" + uuid),
        ("empty", "")
    ]
    let split = splitInputs.map { input -> MentionSplit in
        if let parts = MentionParser.split(identifier: input.identifier) {
            return MentionSplit(name: input.name, identifier: input.identifier, prefix: parts.prefix,
                                token: parts.token, invalid: nil)
        }
        return MentionSplit(name: input.name, identifier: input.identifier, prefix: nil, token: nil, invalid: true)
    }

    func mentionCount(_ name: String) -> Int {
        cases.first { $0.name == name }?.mentions.count ?? -1
    }
    require(mentionCount("bare-stable-id-without-option") == 0, "a bare stableID matched without the option")
    require(mentionCount("bare-stable-id-with-option") == 1, "a bare stableID did not match with the option")
    require(mentionCount("bare-uuid-is-never-a-mention") == 0, "a bare UUID matched")
    require(mentionCount("two-stable-ids-in-one-paragraph") == 2, "two stableIDs did not both match")
    require(mentionCount("trailing-hyphen-x-is-no-match") == 0, "a trailing -x matched")
    require(cases.first { $0.name == "lowercase-stable-id-is-folded" }?.mentions.first?.token == shell,
            "a lowercase stableID was not folded")
    require(cases.first { $0.name == "next-to-emoji" }?.mentions.first?.location == 2,
            "the emoji offset is not UTF-16")
    require(split.contains { $0.invalid == true } && split.contains { $0.invalid == nil }, "split has one verdict only")
    return MentionParserVectors(cases: cases, split: split)
}

// MARK: - Status update display (LLM.swift:106-133, 516-551)

struct StatusUpdateVector: Encodable {
    let name: String
    let statusUpdate: LLM.Message.StatusUpdate
    let displayString: String
}

func statusUpdateVectors() -> [StatusUpdateVector] {
    let inputs: [(String, LLM.Message.StatusUpdate)] = [
        ("web-search-started", .webSearchStarted),
        ("web-search-finished-with-query", .webSearchFinished("widget resize flake")),
        ("web-search-finished-without-query", .webSearchFinished(nil)),
        ("code-interpreter-started", .codeInterpreterStarted),
        ("code-interpreter-finished", .codeInterpreterFinished),
        ("reasoning-summary", .reasoningSummaryUpdate("Comparing the two runs.")),
        ("multipart-of-short-updates-keeps-all", .multipart([.webSearchStarted, .codeInterpreterStarted])),
        ("multipart-keeps-from-last-long-update",
         .multipart([.reasoningSummaryUpdate("First thought."), .webSearchStarted,
                     .webSearchFinished("flake"), .codeInterpreterStarted])),
        ("multipart-last-reasoning-wins",
         .multipart([.reasoningSummaryUpdate("Old."), .codeInterpreterFinished,
                     .reasoningSummaryUpdate("New."), .webSearchStarted])),
        ("nested-multipart-is-flattened",
         .multipart([.multipart([.webSearchStarted, .webSearchFinished(nil)]), .codeInterpreterStarted])),
        ("empty-multipart", .multipart([]))
    ]
    return inputs.map { StatusUpdateVector(name: $0.0, statusUpdate: $0.1, displayString: $0.1.displayString) }
}

// MARK: - Remote command description (RemoteCommand.swift)

struct RemoteCommandVector: Encodable {
    let name: String
    let remoteCommand: RemoteCommand
    let functionName: String
    let permissionCategory: String
    let markdownDescription: String
}

func caseName(_ content: RemoteCommand.Content) -> String {
    guard let data = try? sortedWireEncoder().encode(content),
          let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let name = discriminator(object) else {
        require(false, "\(content.functionName): no case name")
        return ""
    }
    return name
}

func remoteCommandVectors() -> [RemoteCommandVector] {
    func vector(_ name: String, _ content: RemoteCommand.Content) -> RemoteCommandVector {
        let command = SyntheticChats.remoteCommand(content, callID: "call_" + content.functionName)
        return RemoteCommandVector(name: name, remoteCommand: command, functionName: content.functionName,
                                   permissionCategory: content.permissionCategory.rawValue,
                                   markdownDescription: command.markdownDescription)
    }
    // Every case with its default arguments (RemoteCommand.swift:140-169).
    var vectors = RemoteCommand.Content.allCases.map { vector(caseName($0), $0) }

    // The three descriptions that cut their argument at 32 Characters
    // (truncatedWithTrailingEllipsis, MacShims.swift:22-27) after escaping
    // backticks (TerminalCommand.swift:22-26).
    let exactly32 = "make -j8 test TESTS=widget_resiz"
    let exactly33 = exactly32 + "e"
    let family = "\u{1F468}\u{200D}\u{1F469}\u{200D}\u{1F467}"  // one Character, eight UTF-16 units
    let emojiAt32 = String(exactly32.dropLast()) + family          // 32 Characters, the last an emoji
    let emojiAt32Then = emojiAt32 + "!"                             // 33: the emoji is cut
    let backticks = "echo `date` and `whoami`"
    let backtickAtCut = String(repeating: "a", count: 30) + "`b`"  // escaping pushes it past 32
    require(exactly32.count == 32 && exactly33.count == 33 && emojiAt32.count == 32 && emojiAt32Then.count == 33,
            "the cut arguments have the wrong length")
    let arguments: [(String, String)] = [
        ("exactly-32-characters", exactly32),
        ("exactly-33-characters", exactly33),
        ("backticks", backticks),
        ("backtick-at-the-cut", backtickAtCut),
        ("emoji-at-character-32", emojiAt32),
        ("emoji-cut-at-character-32", emojiAt32Then)
    ]
    for (suffix, text) in arguments {
        vectors.append(vector("executeCommand-\(suffix)", .executeCommand(.init(command: text))))
        vectors.append(vector("insertTextAtCursor-\(suffix)", .insertTextAtCursor(.init(text: text))))
        vectors.append(vector("getManPage-\(suffix)", .getManPage(.init(cmd: text))))
    }
    // The other descriptions that interpolate an argument, uncut.
    vectors.append(vector("createFile-named", .createFile(.init(filename: "notes/widget.md", content: "# Widget\n"))))
    vectors.append(vector("searchBrowser-query", .searchBrowser(.init(query: "resize event"))))
    vectors.append(vector("loadURL-https", .loadURL(.init(url: "https://iterm2.com/documentation.html"))))
    vectors.append(vector("webSearch-query", .webSearch(.init(query: "widget resize flake"))))
    vectors.append(vector("getCommandHistory-limit", .getCommandHistory(.init(limit: 20))))
    vectors.append(vector("readWebPage-lines", .readWebPage(.init(startingLineNumber: 10, numberOfLines: 40))))

    let covered = Set(vectors.map { caseName($0.remoteCommand.content) })
    let allCases = Set(RemoteCommand.Content.allCases.map(caseName))
    require(allCases.count == 26, "RemoteCommand.Content has \(allCases.count) cases, expected 26")
    require(covered == allCases, "remote command vectors lack \(allCases.subtracting(covered).sorted())")
    require(Set(vectors.map(\.name)).count == vectors.count, "duplicate remote command vector name")
    return vectors
}

// MARK: - Writing

func writeChatVectors(samples: [SessionSample], to directory: URL) throws {
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let tolerance = try decodeToleranceVectors(samples: samples)
    let merge = try streamingMergeVectors()
    let mentions = mentionParserVectors()
    let statuses = statusUpdateVectors()
    let commands = remoteCommandVectors()
    require(!tolerance.isEmpty && !merge.isEmpty && !mentions.cases.isEmpty && !mentions.split.isEmpty
            && !statuses.isEmpty && !commands.isEmpty, "a chat vector list is empty")
    for names in [tolerance.map(\.name), merge.map(\.name), mentions.cases.map(\.name), mentions.split.map(\.name),
                  statuses.map(\.name), commands.map(\.name)] {
        require(Set(names).count == names.count, "duplicate chat vector name")
    }
    try writeChatVector(tolerance, named: "decode-tolerance.json", to: directory)
    try writeChatVector(merge, named: "streaming-merge.json", to: directory)
    try writeChatVector(mentions, named: "mention-parser.json", to: directory)
    try writeChatVector(statuses, named: "status-update-display.json", to: directory)
    try writeChatVector(commands, named: "remote-command-description.json", to: directory)
    print("wire-fixtures: wrote \(directory.path): decodeTolerance=\(tolerance.count) streamingMerge=\(merge.count) "
          + "mentionCases=\(mentions.cases.count) mentionSplits=\(mentions.split.count) "
          + "statusUpdates=\(statuses.count) remoteCommands=\(commands.count)")
}
