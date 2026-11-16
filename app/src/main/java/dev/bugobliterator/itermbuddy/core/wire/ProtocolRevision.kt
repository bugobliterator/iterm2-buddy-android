package dev.bugobliterator.itermbuddy.core.wire

/**
 * The companion protocol revision this app advertises in its `hello`.
 *
 * Global Constraint (Sessions plan): revision 13 from the Stream Messages task, 15 from
 * the Browser Messages task; minimum peer stays 11, the Mac's floor
 * (`CompanionProtocolVersion.minimumPeer` at iTerm2 v3.7.7). 13 gets the Mac's
 * unsolicited `sessionTree` push. It also claims revision 12 (`messagesRemoved`, sent
 * only for a subscribed chat) and 13's typed `aiUnavailable` error; this app ignores
 * both, as unknown cases decode to `MacMessage.Unsupported`.
 */
object ProtocolRevision {
    const val REVISION = 13
    const val MINIMUM_PEER = 11
}
