package dev.bugobliterator.itermbuddy.core.wire

/**
 * The companion protocol revision this app advertises in its `hello`.
 *
 * Global Constraint (Sessions plan): revision 13 from the Stream Messages task, 15 from
 * the Browser Messages task; minimum peer stays 11, the Mac's floor
 * (`CompanionProtocolVersion.minimumPeer` at iTerm2 v3.7.7). 13 gets the Mac's
 * unsolicited `sessionTree` push.
 *
 * Agent Chat needs nothing beyond 13, and this app decodes every chat message 13 claims:
 * `turnLifecycle` events (8, `turnLifecycleRevision`), `messagesRemoved` for a subscribed chat
 * and the `deleteMessages` it echoes (12, `messageDeletionRevision`), and `aiAvailable` in the
 * hello reply, `aiAvailabilityChanged` and the typed `aiUnavailable` error (13,
 * `aiDecouplingRevision`). Revisions 14 and 15 change no chat message. Chat features gate on the
 * Mac's hello revision, not this one (mute at 5, Delete From Here at 12).
 *
 * 15 is v3.7.7's own revision. At 14 a v3.7.7 Mac adds `macStatus` to its hello reply and
 * sends `macStatusChanged` on each change (`MC/CompanionLink.swift:518-536, 559-572,
 * 621-625`), and `modalAlertAnswerRejected` only after an `answerModalAlert`, which this app
 * never sends (`MC/CompanionLink.swift:627-640`). At 15 it pushes `browserEditingChanged` and
 * `browserNavigationChanged` for a browser stream (`MC/CompanionHostBridge.swift:745-788`).
 * Nothing else it sends depends on the phone's revision beyond 13 (its other gates are at 2, 8,
 * 12 and 13), and its refusal of terminal input while its main thread is blocked does not
 * depend on it either (`MC/CompanionLink.swift:476-492`). Its timing does: at 14 and above the
 * Mac expedites its stall detection for a phone it sends status to, so input is refused about
 * 0.3 s after an app-modal alert appears instead of 2 s (`MC/CompanionLink.swift:525-529,
 * 566-571`; `MC/CompanionMainThreadMonitor.swift:50-55`).
 */
object ProtocolRevision {
    const val REVISION = 15
    const val MINIMUM_PEER = 11
}
