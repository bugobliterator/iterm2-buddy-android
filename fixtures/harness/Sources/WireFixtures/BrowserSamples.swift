import CompanionProtocol
import Foundation

// Browser input, browser navigation and Mac status samples
// (messages/browser-sessions/*.json), in the same `{"direction", "swiftValue",
// "json"}` format as messages/session.
//
// They continue the live session of LiveSessionSamples.swift on the browser
// session's stream: the link's second stream, streamID 2 (stream-started-browser,
// stream-config-browser), 1280x800 encoded pixels. Phone-to-Mac envelopes are
// built the way iOS CompanionClient builds them (every browser message is a
// `send`, so no requestID: CompanionClient.swift:325-364); Mac-to-phone envelopes
// the way CompanionHostBridge and CompanionLink build them. Paths are upstream
// v3.7.7 paths; the Mac and iOS sources are not compiled here, so the call sites
// are cited instead.

/// The revision this app advertises from the Browser Messages task on (Global
/// Constraints: "15 from the Browser Messages task"): v3.7.7's own
/// `CompanionProtocolVersion.current`, the revision that carries browser input.
let browserAppRevision = 15

func browserSamples() throws -> [SessionSample] {
    require(browserAppRevision == CompanionProtocolVersion.current, "the app no longer advertises the Mac's revision")
    require(browserAppRevision >= CompanionProtocolVersion.browserInputRevision, "15 does not carry browser input")
    require(browserAppRevision >= CompanionProtocolVersion.modalAlertRevision, "15 does not carry the Mac's status")
    require(CompanionProtocolVersion.evaluate(peerRevision: browserAppRevision,
                                              peerMinimumPeer: appAdvertisedMinimumPeer) == .compatible,
            "a v3.7.7 Mac refuses revision 15")

    let streamID = LiveStream.streamID + 1
    var samples: [SessionSample] = []

    // MARK: Phone to Mac

    // CompanionClient.handshakeVersion (iOS), at revision 15. The Mac's reply
    // then carries macStatus (CompanionLink.swift:518-524): hello-mac-with-alerts.
    let hello = ClientEnvelope(requestID: helloRequestID,
                               payload: .hello(revision: browserAppRevision, minimumPeer: appAdvertisedMinimumPeer))
    require(macStatusRule(peerRevision: browserAppRevision,
                          status: CompanionMacStatus(modalAlerts: [], mainBlocked: false)) != nil,
            "a revision 15 phone gets no macStatus")
    samples.append(try clientSample("hello-phone-revision-fifteen", hello))

    // AppModel.swift:4675-4678, CompanionClient.swift:325-329: a tap in
    // encoded-image pixels, origin top left. iOS sends CGPoint components as
    // Double, so a fractional x and an integral y (written as an integer).
    samples.append(try clientSample("browser-tap",
                                    ClientEnvelope(requestID: nil,
                                                   payload: .browserTap(streamID: streamID, x: 412.5, y: 300))))
    // AppModel.swift:4680-4685, CompanionClient.swift:331-340: the finger moved
    // up 48 px, so the content moves up (negative deltaY); integer deltas.
    samples.append(try clientSample("browser-scroll",
                                    ClientEnvelope(requestID: nil,
                                                   payload: .browserScroll(streamID: streamID, x: 640, y: 400,
                                                                           deltaX: 0, deltaY: -48))))
    // AppModel.swift:4687-4715, CompanionClient.swift:342-358.
    samples.append(try clientSample("browser-go-back",
                                    ClientEnvelope(requestID: nil, payload: .browserGoBack(streamID: streamID))))
    samples.append(try clientSample("browser-go-forward",
                                    ClientEnvelope(requestID: nil, payload: .browserGoForward(streamID: streamID))))
    samples.append(try clientSample("browser-reload",
                                    ClientEnvelope(requestID: nil, payload: .browserReload(streamID: streamID))))
    samples.append(try clientSample("browser-stop-loading",
                                    ClientEnvelope(requestID: nil, payload: .browserStopLoading(streamID: streamID))))
    // AppModel.swift:4704-4710: the address field's text, trimmed of
    // whitespace and newlines, and never sent empty; CompanionClient.swift:360-364.
    let typed = "  iterm2.com/documentation-web-browser.html\n"
    let trimmed = typed.trimmingCharacters(in: .whitespacesAndNewlines)
    require(!trimmed.isEmpty, "the typed address is empty")
    samples.append(try clientSample("browser-load",
                                    ClientEnvelope(requestID: nil, payload: .browserLoad(streamID: streamID, text: trimmed))))

    // MARK: Mac to phone

    // CompanionHostBridge.swift:745-758 (pushBrowserEditingIfChanged): on the stream's first tick and on
    // each change, only to a phone at browserInputRevision; no requestID.
    samples.append(try hostSample("browser-editing-changed",
                                  HostEnvelope(requestID: nil,
                                               payload: .browserEditingChanged(streamID: streamID, editing: true))))

    // CompanionHostBridge.swift:773-788 (pushBrowserNavigationIfChanged) with CompanionBrowserFrameSource.swift:129-141:
    // the web view's URL and title ("" when absent) and its history state.
    samples.append(try hostSample("browser-navigation-changed",
                                  HostEnvelope(requestID: nil,
                                               payload: .browserNavigationChanged(
                                                   streamID: streamID,
                                                   state: CompanionBrowserNavigationState(
                                                       url: "https://iterm2.com/documentation-web-browser.html",
                                                       title: "Web Browser - Documentation - iTerm2",
                                                       canGoBack: true, canGoForward: false, isLoading: true)))))

    // Decode tolerance only: a v3.7.7 Mac always writes every field
    // (synthesized encode). Upstream decodes each one leniently
    // (CompanionMessages.swift:608-616): absent strings read as "" and absent
    // booleans as false, so the state with every field removed decodes to
    // the empty state.
    let emptyNavigation = try hostSample("browser-navigation-changed-empty",
                                         HostEnvelope(requestID: nil,
                                                      payload: .browserNavigationChanged(
                                                          streamID: streamID,
                                                          state: CompanionBrowserNavigationState(
                                                              url: "", title: "", canGoBack: false,
                                                              canGoForward: false, isLoading: false))))
    let navigationKeys = ["canGoBack", "canGoForward", "isLoading", "title", "url"]
    let emptyStateFields = "\"canGoBack\":false,\"canGoForward\":false,\"isLoading\":false,\"title\":\"\",\"url\":\"\""
    let minimal = try olderMacSample("browser-navigation-changed-minimal", from: emptyNavigation,
                                     removing: emptyStateFields, key: "url", as: CompanionHostMessage.self).file
    let minimalText = String(decoding: minimal.wire, as: UTF8.self)
    for key in navigationKeys {
        require(!minimalText.contains("\"\(key)\""), "browser-navigation-changed-minimal: \(key) is still present")
    }
    require(minimalText.contains("\"state\":{}"), "browser-navigation-changed-minimal: the state is not empty")

    // CompanionLink.swift:620-625: the complete status, unsolicited, whenever
    // it changes after the hello reply (CompanionLink.swift:559-572), only to a
    // phone at modalAlertRevision. Blocked: an app-modal alert has the main
    // thread frozen.
    samples.append(try hostSample("mac-status-changed-blocked",
                                  HostEnvelope(requestID: nil,
                                               payload: .macStatusChanged(status: CompanionMacStatus(
                                                   modalAlerts: [sampleModalAlert], mainBlocked: true)))))
    // The alert was answered at the Mac and the main thread runs again.
    samples.append(try hostSample("mac-status-changed-clear",
                                  HostEnvelope(requestID: nil,
                                               payload: .macStatusChanged(status: CompanionMacStatus(
                                                   modalAlerts: [], mainBlocked: false)))))
    // CompanionLink.swift:627-640: the answer to an answerModalAlert could not
    // be applied; the current status follows. This app never sends
    // answerModalAlert, so a v3.7.7 Mac never sends it this; decoded as
    // unsupported and dropped.
    samples.append(try hostSample("modal-alert-answer-rejected",
                                  HostEnvelope(requestID: nil,
                                               payload: .modalAlertAnswerRejected(alertID: sampleModalAlert.id))))

    try checkSessionSamples(samples)
    let all = samples + [minimal]
    require(Set(all.map(\.name)).count == all.count, "duplicate browser sample name")
    return all
}
