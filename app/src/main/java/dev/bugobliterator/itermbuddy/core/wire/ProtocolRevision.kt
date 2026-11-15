package dev.bugobliterator.itermbuddy.core.wire

/**
 * The companion protocol revision this app advertises in its `hello`.
 *
 * Global Constraint: advertise revision 11 with minimum peer 11 for the pairing
 * milestone. 11 is the Mac's floor (`CompanionProtocolVersion.minimumPeer` at
 * iTerm2 v3.7.7) and claims no feature this app has not built. Later plans raise
 * [REVISION] as features land; revision 15 (browser streaming) is a non-goal.
 */
object ProtocolRevision {
    const val REVISION = 11
    const val MINIMUM_PEER = 11
}
