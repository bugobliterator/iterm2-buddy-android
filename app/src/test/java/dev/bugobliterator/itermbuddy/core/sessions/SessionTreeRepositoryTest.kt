package dev.bugobliterator.itermbuddy.core.sessions

import dev.bugobliterator.itermbuddy.core.sessions.SessionsRig.Companion.PUSH
import dev.bugobliterator.itermbuddy.core.sessions.SessionsRig.Companion.REPLY
import dev.bugobliterator.itermbuddy.core.sessions.SessionsRig.Companion.payloadOf
import dev.bugobliterator.itermbuddy.core.sessions.SessionsRig.Companion.treeOf
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The session tree: fetched on each connect, replaced by pushes, applied in arrival order. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTreeRepositoryTest {
    private val replyTree = treeOf(REPLY)
    private val pushTree = treeOf(PUSH)

    @Test
    fun fetchesOnConnectAndReconnect() = runTest {
        val rig = SessionsRig(this)
        rig.answerFetches()
        assertEquals(TreeState.Loading, rig.repository.state.value)
        rig.connect()
        runCurrent()
        assertEquals(1, rig.fetches())
        assertEquals(TreeState.Loaded(replyTree, refreshing = false), rig.repository.state.value)

        rig.mac.dropSocket()
        rig.awaitConnected(2)
        runCurrent()
        assertEquals("one more fetch after the reconnect", 2, rig.fetches())
        assertEquals(TreeState.Loaded(replyTree, refreshing = false), rig.repository.state.value)
    }

    @Test
    fun pushReplacesTreeWithoutRefetch() = runTest {
        val rig = SessionsRig(this)
        rig.answerFetches()
        rig.connect()
        runCurrent()
        rig.mac.sendSample(PUSH, null)
        runCurrent()
        assertEquals(TreeState.Loaded(pushTree, refreshing = false), rig.repository.state.value)
        advanceTimeBy(30.seconds)
        assertEquals(1, rig.fetches())
    }

    @Test
    fun timeoutIsRetryableFailure() = runTest {
        val rig = SessionsRig(this)
        rig.mac.onRequest("fetchSessionTree") { null }
        rig.connect()
        advanceTimeBy(15.seconds - 10.milliseconds)
        assertEquals(TreeState.Loading, rig.repository.state.value)
        advanceTimeBy(20.milliseconds)
        assertEquals(TreeState.Failed(retryable = true), rig.repository.state.value)

        // Try again fetches once more and loads.
        rig.answerFetches()
        rig.repository.refresh()
        assertEquals(TreeState.Loading, rig.repository.state.value)
        runCurrent()
        assertEquals(2, rig.fetches())
        assertEquals(TreeState.Loaded(replyTree, refreshing = false), rig.repository.state.value)
    }

    @Test
    fun refreshKeepsOldTreeWhileLoading() = runTest {
        val rig = SessionsRig(this)
        rig.answerFetches(sample = PUSH)
        rig.connect()
        runCurrent()
        assertEquals(TreeState.Loaded(pushTree, refreshing = false), rig.repository.state.value)

        val ids = Channel<Long>(Channel.UNLIMITED)
        rig.mac.onRequest("fetchSessionTree") { ids.trySend(it.getValue("requestID").jsonPrimitive.long); null }
        rig.repository.refresh()
        runCurrent()
        assertEquals(TreeState.Loaded(pushTree, refreshing = true), rig.repository.state.value)
        rig.mac.sendSample(REPLY, ids.receive())
        runCurrent()
        assertEquals(TreeState.Loaded(replyTree, refreshing = false), rig.repository.state.value)
    }

    @Test
    fun refreshThatTimesOutKeepsTheTree() = runTest {
        val rig = SessionsRig(this)
        rig.answerFetches()
        rig.connect()
        runCurrent()
        rig.mac.onRequest("fetchSessionTree") { null }
        rig.repository.refresh()
        advanceTimeBy(16.seconds)
        assertEquals(TreeState.Loaded(replyTree, refreshing = false), rig.repository.state.value)
    }

    @Test
    fun clearDropsTree() = runTest {
        val rig = SessionsRig(this)
        rig.answerFetches()
        rig.connect()
        runCurrent()
        assertTrue(rig.repository.state.value is TreeState.Loaded)
        rig.repository.clear()
        assertEquals(TreeState.Loading, rig.repository.state.value)
        advanceTimeBy(30.seconds)
        assertEquals(TreeState.Loading, rig.repository.state.value)
    }

    @Test
    fun clearDiscardsAFetchInFlight() = runTest {
        val rig = SessionsRig(this)
        rig.mac.onRequest("fetchSessionTree") { null }
        rig.connect()
        assertEquals(1, rig.fetches())
        rig.repository.clear()
        advanceTimeBy(30.seconds)
        assertEquals("the abandoned fetch does not report a failure", TreeState.Loading, rig.repository.state.value)
    }

    @Test
    fun clearIgnoresTreesReceivedBeforeIt() = runTest {
        val rig = SessionsRig(this, events = { client ->
            client.events.onEach { if (it is MacMessage.SessionTreeMessage) delay(100.milliseconds) }
        })
        rig.answerFetches()
        rig.connect()
        runCurrent()
        rig.mac.sendSample(PUSH, null)
        runCurrent()
        // The push has been received but not yet applied when the pairing is cleared.
        rig.repository.clear()
        advanceTimeBy(1.seconds)
        assertEquals(TreeState.Loading, rig.repository.state.value)
    }

    /**
     * The binding ordering rule: a push the Mac sent after the fetch request but before its reply
     * is older than the reply, so the reply's tree must win however late the push is applied.
     * The repository's push path is slowed here so the push is applied after the reply.
     */
    @Test
    fun pushBetweenFetchAndReplyNeverOverridesTheNewerReply() = runTest {
        val rig = SessionsRig(this, events = { client ->
            client.events.onEach { if (it is MacMessage.SessionTreeMessage) delay(100.milliseconds) }
        })
        rig.mac.onRequest("fetchSessionTree") { request ->
            val id = request.getValue("requestID").jsonPrimitive.long
            backgroundScope.launch {
                rig.mac.sendSample(PUSH, null)
                rig.mac.sendMessage(payloadOf(REPLY), id)
            }
            null
        }
        rig.connect()
        runCurrent()
        assertEquals(TreeState.Loaded(replyTree, refreshing = false), rig.repository.state.value)
        advanceTimeBy(1.seconds)
        assertEquals("the older push is ignored", TreeState.Loaded(replyTree, refreshing = false), rig.repository.state.value)

        // A push the Mac sends later is newer and is applied.
        rig.mac.sendSample(PUSH, null)
        advanceTimeBy(1.seconds)
        assertEquals(TreeState.Loaded(pushTree, refreshing = false), rig.repository.state.value)
        assertNotEquals(replyTree, pushTree)
    }

    @Test
    fun treeStateToStringHidesNames() {
        val sentinel = "SENTINEL-${'$'}HOME-secret"
        val summary = SessionSummary("ptys_1", sentinel, sentinel, false)
        val tree = SessionTree(listOf(WindowEntry(sentinel, listOf(TabEntry(sentinel, listOf(PaneEntry(summary, listOf(PeerEntry(sentinel, summary)))))))))
        val text = TreeState.Loaded(tree, refreshing = false).toString()
        assertTrue(text, !text.contains(sentinel))
    }
}
