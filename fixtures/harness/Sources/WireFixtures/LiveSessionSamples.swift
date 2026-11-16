import CompanionProtocol
import Foundation

// Live session message samples (messages/live-session/*.json), in the same
// `{"direction", "swiftValue", "json"}` format as messages/session.
//
// Phone-to-Mac envelopes are built the way the iOS app builds them
// (CompanionClient for the payload, CompanionSession for the requestID: a
// `request` carries one, a `send` has none, CompanionSession.swift:83-86).
// Mac-to-phone envelopes are built the way CompanionHostBridge and
// CompanionLink build them. Paths below are upstream v3.7.7 paths; the Mac
// and iOS sources are not compiled here, so the call sites are cited instead.
// Session names, titles and subtitles are synthetic.

/// The revision the Android app advertises from the Stream Messages task on
/// (plan, Global Constraints). Enough for unsolicited `sessionTree` pushes.
let liveSessionAppRevision = 13

/// Request IDs continue the Android client's order on one connection after
/// messages/session (hello 1, relayRoomSecret 2, ping 3).
let fetchSessionTreeRequestID: UInt64 = 4
let startSessionStreamRequestID: UInt64 = 5
/// The start of the browser stream, the link's second stream.
let startBrowserStreamRequestID: UInt64 = 6

/// A canonical `ptys_` stable session ID (what the Mac sends as `guid`,
/// CompanionSessionLister.swift:23-31) with the given 12-character body: the
/// check character is the one upstream `StableSessionID.canonical` accepts.
func stableSessionID(_ body: String) -> String {
    for check in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" {
        let candidate = StableSessionID.prefix + body + String(check)
        if StableSessionID.canonical(candidate) == candidate {
            return candidate
        }
    }
    require(false, "no check character makes \(body) a stable session ID")
    return ""
}

/// The synthetic Mac the samples describe.
///
/// Window 1 "demo@buildhost: ~/src/widget":
///   Tab "widget": a split pane whose peer group has two members (shell and a
///     Code Review agent) beside a plain pane, so iOS nests the peers under a
///     "Split Pane" row.
///   Tab "agents": one pane whose peer group has two members, so iOS hoists the
///     peers to the tab.
///   Tab 3, title empty, so the Mac names it "Tab 3": one plain pane.
/// Window 2, title empty, so the Mac names it "Window 2":
///   Tab "Widget Docs": a browser session.
enum SyntheticMac {
    static let shell = CompanionSessionSummary(guid: stableSessionID("7K2M9Q4R8T1V"),
                                               name: "zsh", subtitle: "~/src/widget")
    static let codeReview = CompanionSessionSummary(guid: stableSessionID("3HX6W0ZD5N2B"),
                                                    name: "Code Review", subtitle: "reviewing widget.c")
    static let buildLog = CompanionSessionSummary(guid: stableSessionID("9PJ4C8EGA6S0"),
                                                  name: "tail", subtitle: "tail -f build.log")
    static let planner = CompanionSessionSummary(guid: stableSessionID("1F5Y7R3MQ9KD"),
                                                 name: "claude", subtitle: "planning")
    static let tester = CompanionSessionSummary(guid: stableSessionID("6V0T2B8NW4HE"),
                                                name: "claude", subtitle: "")
    static let notes = CompanionSessionSummary(guid: stableSessionID("8M3B5X1QJ7TW"),
                                               name: "vim", subtitle: "notes.md")
    static let docs = CompanionSessionSummary(guid: stableSessionID("4Q8S1D6KZ3MA"),
                                              name: "Widget Docs", subtitle: "", isBrowser: true)

    /// Peers, sorted by workgroup position; roleName is the workgroup label,
    /// else the session's name (CompanionSessionLister.swift:64-83). Every
    /// member is listed, the pane's own session included.
    static let reviewPeers = [
        CompanionSessionTree.Peer(roleName: "Implementer", session: shell),
        CompanionSessionTree.Peer(roleName: "Code Review", session: codeReview)
    ]
    static let agentPeers = [
        CompanionSessionTree.Peer(roleName: "Planner", session: planner),
        CompanionSessionTree.Peer(roleName: "Tester", session: tester)
    ]

    static func tree(includingBuildLog: Bool, includingBrowser: Bool = true) -> CompanionSessionTree {
        var widgetPanes = [CompanionSessionTree.Pane(session: shell, peers: reviewPeers)]
        if includingBuildLog {
            widgetPanes.append(CompanionSessionTree.Pane(session: buildLog, peers: []))
        }
        var windows = [
            CompanionSessionTree.Window(title: "demo@buildhost: ~/src/widget", tabs: [
                CompanionSessionTree.Tab(title: "widget", panes: widgetPanes),
                CompanionSessionTree.Tab(title: "agents", panes: [
                    CompanionSessionTree.Pane(session: planner, peers: agentPeers)
                ]),
                // CompanionSessionLister.swift:46-51: a tab with no title yet
                // becomes "Tab \(tabIndex + 1)".
                CompanionSessionTree.Tab(title: "Tab 3", panes: [
                    CompanionSessionTree.Pane(session: notes, peers: [])
                ])
            ])
        ]
        if includingBrowser {
            // CompanionSessionLister.swift:53-56: an empty window title becomes
            // "Window \(index + 1)".
            windows.append(CompanionSessionTree.Window(title: "Window 2", tabs: [
                CompanionSessionTree.Tab(title: "Widget Docs", panes: [
                    CompanionSessionTree.Pane(session: docs, peers: [])
                ])
            ]))
        }
        return CompanionSessionTree(windows: windows)
    }
}

/// Every `CompanionSpecialKey` raw value, in declaration order
/// (CompanionMessages.swift:298-306). The enum is not CaseIterable, so each is
/// read back through upstream's `init(rawValue:)`.
let specialKeyRawValues = [
    "escape", "tab", "backspace", "forwardDelete", "up", "down", "left", "right",
    "home", "end", "pageUp", "pageDown", "insert",
    "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10", "f11", "f12"
]

/// Every `CompanionStreamEndReason` raw value (CompanionMessages.swift:620-627).
let streamEndReasonRawValues = ["stoppedByClient", "sessionClosed", "superseded", "error", "dataLimitReached"]

func liveSessionSamples(clip: TerminalClip) throws -> [SessionSample] {
    require(liveSessionAppRevision >= CompanionProtocolVersion.aiDecouplingRevision,
            "the Mac pushes sessionTree only to a phone at aiDecouplingRevision or later")
    require(liveSessionAppRevision >= CompanionProtocolVersion.messageDeletionRevision,
            "the Mac forwards messagesRemoved only to a phone at messageDeletionRevision or later")
    require(liveSessionAppRevision < CompanionProtocolVersion.modalAlertRevision,
            "the app's revision now gets macStatus")

    let watched = SyntheticMac.shell.guid
    let streamID = LiveStream.streamID
    var samples: [SessionSample] = []

    // MARK: Phone to Mac

    // CompanionClient.swift:435, `session.request(.fetchSessionTree)`.
    samples.append(try clientSample("fetch-session-tree",
                                    ClientEnvelope(requestID: fetchSessionTreeRequestID, payload: .fetchSessionTree)))
    // AppModel.swift:4585-4589: iOS's params; CompanionClient.swift:275 sends a request.
    let iosParams = CompanionStreamParams(supportedCodecs: [.hevc], maxFrameRate: 30, maxBitrate: nil,
                                          maxMediaFrameVersion: 2)
    samples.append(try clientSample("start-session-stream",
                                    ClientEnvelope(requestID: startSessionStreamRequestID,
                                                   payload: .startSessionStream(sessionGuid: watched, params: iosParams))))
    // CompanionClient.swift:287-299: stop, keyframe and ack are `send`s.
    samples.append(try clientSample("stop-session-stream",
                                    ClientEnvelope(requestID: nil, payload: .stopSessionStream(streamID: streamID))))
    // SessionView.swift:982-983: every frame acked on receipt, its own pts, queueDepth 0.
    samples.append(try clientSample("stream-ack",
                                    ClientEnvelope(requestID: nil,
                                                   payload: .streamAck(streamID: streamID,
                                                                       lastPTSMilliseconds: clip.frames[0].ptsMilliseconds,
                                                                       queueDepth: 0))))
    samples.append(try clientSample("request-keyframe",
                                    ClientEnvelope(requestID: nil, payload: .requestKeyframe(streamID: streamID))))

    // CompanionClient.swift:380 (`send`). The key bar never sets shift
    // (SessionKeyboardAccessory.swift:54-62).
    func sendKey(_ name: String, _ key: CompanionKeyEvent.Key, _ modifiers: CompanionKeyModifiers = .none) throws {
        samples.append(try clientSample(name,
                                        ClientEnvelope(requestID: nil,
                                                       payload: .sendKey(sessionGuid: watched,
                                                                         event: CompanionKeyEvent(key: key, modifiers: modifiers)))))
    }
    try sendKey("send-key-text", .text("ls"))
    // Return is text "\n" (CompanionKeyInjection.swift:149-157).
    try sendKey("send-key-return", .text("\n"))
    // ^C is text "c" with control (SessionKeyboardAccessory.swift:105-108).
    try sendKey("send-key-control-c", .text("c"), CompanionKeyModifiers(control: true))
    // An armed one-shot Left Option on a typed letter (Meta-b, back a word).
    try sendKey("send-key-left-option-text", .text("b"), CompanionKeyModifiers(leftOption: true))
    for raw in specialKeyRawValues {
        guard let key = CompanionSpecialKey(rawValue: raw) else {
            require(false, "\(raw) is not a CompanionSpecialKey")
            continue
        }
        require(key.rawValue == raw, "\(raw): raw value differs")
        try sendKey("send-key-special-\(raw)", .special(key))
    }

    // AppModel.swift:5115, CompanionClient.swift:372-373 (`send`).
    samples.append(try clientSample("paste-text-multiline",
                                    ClientEnvelope(requestID: nil,
                                                   payload: .pasteText(sessionGuid: watched,
                                                                       text: "make test\ngit status --short\n"))))
    // AppModel.swift:5129-5153, portrait: 60 columns over a 412x640 pt canvas
    // with the config's 15.75x32 px cells: cell 6.87 pt wide, 13.95 pt high,
    // floor(640 / 13.95) = 45 rows.
    samples.append(try clientSample("resize-session",
                                    ClientEnvelope(requestID: nil,
                                                   payload: .resizeSession(sessionGuid: watched, columns: 60, rows: 45))))

    // MARK: Mac to phone

    // CompanionHostBridge.swift:525-526: the reply echoes the request's ID.
    samples.append(try hostSample("session-tree-reply",
                                  HostEnvelope(requestID: fetchSessionTreeRequestID,
                                               payload: .sessionTree(SyntheticMac.tree(includingBuildLog: true)))))
    // CompanionHostBridge.swift:262-272: unsolicited push after the build-log
    // pane closed; no requestID.
    samples.append(try hostSample("session-tree-push",
                                  HostEnvelope(requestID: nil,
                                               payload: .sessionTree(SyntheticMac.tree(includingBuildLog: false)))))
    // CompanionHostBridge.swift:1216-1217, 1288-1289: streamID 1 on a new
    // link, always hevc, echoing the start's requestID.
    samples.append(try hostSample("stream-started",
                                  HostEnvelope(requestID: startSessionStreamRequestID,
                                               payload: .streamStarted(CompanionStreamStarted(streamID: streamID,
                                                                                              codec: .hevc)))))
    // A later start for the browser session, the link's second stream.
    samples.append(try hostSample("stream-started-browser",
                                  HostEnvelope(requestID: startBrowserStreamRequestID,
                                               payload: .streamStarted(CompanionStreamStarted(streamID: streamID + 1,
                                                                                              codec: .hevc)))))

    // CompanionSessionStreamer.swift:579-594 builds the config;
    // CompanionHostBridge.swift:1246-1247 sends it with no requestID. Terminal
    // geometry (CompanionTerminalFrameSource.swift:24-63): 80x25 cells of
    // 7.875x16 pt with the 5 pt side margins at scale 2 make a 1280x800 image.
    let cellGeometry = CompanionCellGeometry(cellWidth: 7.875 * LiveStream.scale,
                                             cellHeight: 16 * LiveStream.scale,
                                             leftMargin: 5 * LiveStream.scale,
                                             topMargin: 0)
    let terminalConfig = CompanionStreamConfig(streamID: streamID,
                                               generationId: LiveStream.generationId,
                                               codecExtradata: clip.codecExtradata,
                                               pixelWidth: LiveStream.pixelWidth,
                                               pixelHeight: LiveStream.pixelHeight,
                                               scale: LiveStream.scale,
                                               columns: LiveStream.columns,
                                               rows: LiveStream.rows,
                                               cellGeometry: cellGeometry,
                                               firstAbsLine: LiveStream.scrollbackOverflow,
                                               totalLines: LiveStream.totalLines,
                                               canResize: true,
                                               altScreen: false,
                                               scrollWheelReporting: false,
                                               isBrowser: false)
    samples.append(try hostSample("stream-config-terminal",
                                  HostEnvelope(requestID: nil, payload: .streamConfig(terminalConfig))))
    // Decode tolerance only, not a real host message: a v3.7.7 Mac's terminal
    // source always reports cell geometry, and every Mac the phone can pair
    // with (revision 11 or later) sends it. The optional field absent
    // (CompanionMessages.swift:343-346), for the phone's no-selection path.
    var withoutCellGeometry = terminalConfig
    withoutCellGeometry.cellGeometry = nil
    samples.append(try hostSample("stream-config-terminal-cell-geometry-absent",
                                  HostEnvelope(requestID: nil, payload: .streamConfig(withoutCellGeometry))))
    // CompanionBrowserFrameSource.swift:69-83 and
    // CompanionSessionStreamer.swift:63-71: no grid, no cell geometry, no
    // history, not resizable. A second stream on the link, so streamID 2.
    let browserConfig = CompanionStreamConfig(streamID: streamID + 1,
                                              generationId: LiveStream.generationId,
                                              codecExtradata: clip.codecExtradata,
                                              pixelWidth: LiveStream.pixelWidth,
                                              pixelHeight: LiveStream.pixelHeight,
                                              scale: LiveStream.scale,
                                              columns: 0,
                                              rows: 0,
                                              cellGeometry: nil,
                                              firstAbsLine: 0,
                                              totalLines: 0,
                                              canResize: false,
                                              altScreen: false,
                                              scrollWheelReporting: false,
                                              isBrowser: true)
    samples.append(try hostSample("stream-config-browser",
                                  HostEnvelope(requestID: nil, payload: .streamConfig(browserConfig))))

    // CompanionHostBridge.swift:1341-1350: unsolicited, no requestID.
    for raw in streamEndReasonRawValues {
        guard let reason = CompanionStreamEndReason(rawValue: raw) else {
            require(false, "\(raw) is not a CompanionStreamEndReason")
            continue
        }
        samples.append(try hostSample("stream-ended-\(raw)",
                                      HostEnvelope(requestID: nil,
                                                   payload: .streamEnded(streamID: streamID, reason: reason))))
    }

    // CompanionHostBridge.swift:2006-2013 (contentSession): the reply to a
    // start for a session that is gone.
    samples.append(try hostSample("error-unknown-session",
                                  HostEnvelope(requestID: startSessionStreamRequestID,
                                               payload: .error(CompanionError(
                                                   code: .unknownSession,
                                                   message: "That session no longer exists. It may have been closed.")))))
    // CompanionHostBridge.swift:2014-2018 (contentSession): a session whose
    // text view has no width yet.
    samples.append(try hostSample("error-unknown-session-not-started",
                                  HostEnvelope(requestID: startSessionStreamRequestID,
                                               payload: .error(CompanionError(
                                                   code: .unknownSession,
                                                   message: "That session hasn\u{2019}t started running yet, so there is nothing to show.")))))
    // CompanionHostBridge.swift:1202-1207: a start without hevc.
    samples.append(try hostSample("error-no-supported-codec",
                                  HostEnvelope(requestID: startSessionStreamRequestID,
                                               payload: .error(CompanionError(
                                                   code: .badRequest,
                                                   message: "No supported video codec (HEVC required)")))))

    // Extras. CompanionHostBridge.swift:1850-1858: a chat truncated on the
    // Mac, forwarded only to a phone at messageDeletionRevision (12) or later
    // and only from a chat subscription's broker updates; this app never
    // subscribes to a chat (chat IDs are `UUID().uuidString`, Chat.swift:15).
    samples.append(try hostSample("messages-removed",
                                  HostEnvelope(requestID: nil,
                                               payload: .messagesRemoved(
                                                   chatID: "5E0B7C1A-9D24-4F36-8A51-0C7E2D9B4F18",
                                                   messageIDs: [UUID(uuidString: "A1C3E5F7-0B2D-4F6A-8C1E-3D5F7A9B0C2D")!,
                                                                UUID(uuidString: "B2D4F6A8-1C3E-4A7B-9D2F-4E6A8B0C1D3E")!]))))
    // CompanionLink.swift:549-558: sent when AI is turned off on the Mac, at
    // any phone revision, once the hello reply has gone out.
    samples.append(try hostSample("ai-availability-changed",
                                  HostEnvelope(requestID: nil, payload: .aiAvailabilityChanged(available: false))))

    require(samples.filter { $0.name.hasPrefix("send-key-special-") }.count == specialKeyRawValues.count,
            "a special key sample is missing")
    return samples
}

// MARK: - Older Macs

/// A sample as a Mac older than the field's revision sends it: upstream's
/// sorted encoding with `removedText` cut out of the JSON text (so upstream's
/// escaping is kept), which upstream must decode to the same value as `full`.
/// Re-encoding adds the key back, so these do not round-trip byte for byte.
struct OlderMacSample {
    let file: SessionSample
    let removedKey: String
}

func olderMacSample<Payload: CompanionMessagePayload>(_ name: String, from full: SessionSample, removing removedText: String,
                             key removedKey: String, as _: Payload.Type) throws -> OlderMacSample {
    let text = String(decoding: full.sortedWire, as: UTF8.self)
    require(text.contains(removedText), "\(name): \(removedText) is not in \(full.name)")
    let trimmed = Data(text.replacingOccurrences(of: removedText, with: "").utf8)
    require(!String(decoding: trimmed, as: UTF8.self).contains("\"\(removedKey)\""),
            "\(name): \(removedKey) is still present")
    let decoded = try WireCoding.decode(CompanionEnvelope<Payload>.self, from: trimmed)
    require(String(reflecting: decoded) == full.swiftValue, "\(name): decodes differently from \(full.name)")
    require(try sortedWireEncoder().encode(decoded) == full.sortedWire, "\(name): re-encoding differs from \(full.name)")
    require(try jsonTree(trimmed) != nil, "\(name): not a JSON object")
    let file = SessionSample(name: name, direction: full.direction, swiftValue: full.swiftValue,
                             wire: trimmed, sortedWire: trimmed,
                             roundTrip: { _ in (full.swiftValue, full.sortedWire) })
    return OlderMacSample(file: file, removedKey: removedKey)
}

/// `isBrowser` arrived with browser sessions at revision 15; a revision 11-14
/// Mac omits it from session summaries and stream configs, and upstream
/// decodes it with decodeIfPresent as false (CompanionMessages.swift:52-59,
/// 401-417). The tree has no browser session, as such a Mac would not list
/// one as a browser.
func olderMacSamples(liveSamples: [SessionSample]) throws -> [SessionSample] {
    let absent = "\"isBrowser\":false,"
    let tree = try hostSample("session-tree-reply-full-keys",
                              HostEnvelope(requestID: fetchSessionTreeRequestID,
                                           payload: .sessionTree(SyntheticMac.tree(includingBuildLog: true,
                                                                                   includingBrowser: false))))
    guard let config = liveSamples.first(where: { $0.name == "stream-config-terminal" }) else {
        require(false, "stream-config-terminal is missing")
        return []
    }
    let samples = [
        try olderMacSample("session-tree-reply-without-is-browser", from: tree, removing: absent,
                           key: "isBrowser", as: CompanionHostMessage.self),
        try olderMacSample("stream-config-terminal-without-is-browser", from: config, removing: absent,
                           key: "isBrowser", as: CompanionHostMessage.self)
    ]
    return samples.map(\.file)
}
