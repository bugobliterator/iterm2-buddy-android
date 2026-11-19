package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.core.CompanionRequestException
import dev.bugobliterator.itermbuddy.core.sessions.SessionsRig
import dev.bugobliterator.itermbuddy.core.stream.ScriptedStreamLink
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.decodeEnvelope
import dev.bugobliterator.itermbuddy.core.wire.encodeEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WorkgroupWatchTest {
    private val workgroupID = "wg-5C4D3E2F-1A0B-4C9D-8E7F-6A5B4C3D2E1F"

    /** A poll interval and a request bound unlike the defaults, so a test proves they are injected. */
    private val poll = 7.seconds

    private class Rig(scope: TestScope, workgroupID: String, poll: kotlin.time.Duration) {
        val link = ScriptedStreamLink()
        val log = ArrayList<String>()
        val watch = WorkgroupWatch(link, scope.backgroundScope, workgroupID, pollInterval = poll, timeout = 11.seconds, log = { log += it })

        fun nextRequest(): ScriptedStreamLink.Pending {
            val pending = link.requests.tryReceive().getOrNull() ?: throw AssertionError("no request was sent")
            assertEquals(11.seconds, pending.timeout)
            return pending
        }

        fun assertNoRequest() = assertTrue("an unexpected request was sent", link.requests.tryReceive().isFailure)
    }

    private fun TestScope.rig() = Rig(this, workgroupID, poll)

    private fun sampleJson(name: String) =
        Json.parseToJsonElement(Fixtures.read("messages/agent-chat/$name.json")).jsonObject.getValue("json").jsonObject

    private fun sampleReply(name: String): MacMessage = decodeEnvelope(sampleJson(name).toString().toByteArray()).second

    private fun info() = sampleReply("workgroup-info")

    private fun otherError() = MacMessage.Error(
        buildJsonObject {
            put("code", JsonPrimitive("internalError"))
            put("message", JsonPrimitive("Something went wrong"))
        },
    )

    private fun shown(watch: WorkgroupWatch) = watch.state.value as WorkgroupState.Shown

    @Test
    fun requestMatchesSample() = runTest {
        val rig = rig()
        rig.watch.start()
        runCurrent()
        val pending = rig.nextRequest()
        assertEquals(PhoneMessage.FetchWorkgroupInfo(workgroupID), pending.message)
        val sample = sampleJson("fetch-workgroup-info")
        val requestID = (sample.getValue("requestID") as JsonPrimitive).content.toLong()
        assertEquals(sample, Json.parseToJsonElement(encodeEnvelope(pending.message, requestID).decodeToString()))
        assertEquals(WorkgroupState.Loading, rig.watch.state.value)
        pending.reply.complete(info())
        runCurrent()
        val state = shown(rig.watch)
        assertEquals((info() as MacMessage.WorkgroupInfoMessage).info, state.info)
        assertFalse(state.stale)
        assertFalse(state.refreshing)
    }

    @Test
    fun pollsOnlyWhileStarted() = runTest {
        val rig = rig()
        // Nothing is asked before start.
        advanceTimeBy(poll * 3)
        runCurrent()
        rig.assertNoRequest()

        rig.watch.start()
        runCurrent()
        rig.nextRequest().reply.complete(info())
        runCurrent()
        // The next poll comes one interval after the answer, not before.
        advanceTimeBy(poll - 1.seconds)
        runCurrent()
        rig.assertNoRequest()
        advanceTimeBy(1.seconds)
        runCurrent()
        rig.nextRequest().reply.complete(info())
        runCurrent()
        advanceTimeBy(poll)
        runCurrent()
        rig.nextRequest().reply.complete(info())
        runCurrent()

        // Stopped: no more polls, and an answer still out is dropped.
        advanceTimeBy(poll)
        runCurrent()
        val late = rig.nextRequest()
        rig.watch.stop()
        late.reply.complete(otherError())
        runCurrent()
        assertFalse("a stopped watch drops a late answer", shown(rig.watch).stale)
        advanceTimeBy(poll * 5)
        runCurrent()
        rig.assertNoRequest()

        // Started again (the screen came back): one request at once, showing the last info meanwhile.
        rig.watch.start()
        runCurrent()
        rig.nextRequest()
        assertTrue(rig.watch.state.value is WorkgroupState.Shown)
    }

    @Test
    fun pollingPausesInBackgroundAndWhileDisconnected() = runTest {
        val rig = rig()
        rig.watch.start()
        runCurrent()
        rig.nextRequest().reply.complete(info())
        runCurrent()

        // Backgrounded: no polls, however long.
        rig.watch.setForeground(false)
        advanceTimeBy(poll * 5)
        runCurrent()
        rig.assertNoRequest()
        // Back in front: one request at once, then the interval again.
        rig.watch.setForeground(true)
        runCurrent()
        rig.nextRequest().reply.complete(info())
        runCurrent()
        rig.assertNoRequest()
        advanceTimeBy(poll)
        runCurrent()
        rig.nextRequest().reply.complete(info())
        runCurrent()

        // Disconnected: no polls either.
        rig.watch.setConnected(false)
        advanceTimeBy(poll * 5)
        runCurrent()
        rig.assertNoRequest()
        // Foreground again while still disconnected asks nothing.
        rig.watch.setForeground(false)
        rig.watch.setForeground(true)
        runCurrent()
        rig.assertNoRequest()
        // The new link, announced while the gate is still shut, asks once the client is connected.
        rig.watch.linkUp()
        runCurrent()
        rig.assertNoRequest()
        rig.watch.setConnected(true)
        runCurrent()
        rig.nextRequest().reply.complete(info())
        runCurrent()
        rig.assertNoRequest()

        // The gate alone reopening (no new link) restarts the poll timer without asking at once.
        rig.watch.setConnected(false)
        rig.watch.setConnected(true)
        advanceTimeBy(poll - 1.seconds)
        runCurrent()
        rig.assertNoRequest()
        advanceTimeBy(1.seconds)
        runCurrent()
        rig.nextRequest().reply.complete(info())
        runCurrent()

        // A link up in the background waits for the foreground.
        rig.watch.setConnected(false)
        rig.watch.setForeground(false)
        rig.watch.setConnected(true)
        rig.watch.linkUp()
        advanceTimeBy(poll * 3)
        runCurrent()
        rig.assertNoRequest()
        rig.watch.setForeground(true)
        runCurrent()
        rig.nextRequest()
        rig.assertNoRequest()
    }

    @Test
    fun oneRequestInFlight() = runTest {
        val rig = rig()
        rig.watch.start()
        rig.watch.start()
        runCurrent()
        val first = rig.nextRequest()
        rig.assertNoRequest()
        // A refresh, a foreground flip and polling time all wait for the answer still out.
        rig.watch.refresh()
        rig.watch.setForeground(false)
        rig.watch.setForeground(true)
        advanceTimeBy(poll * 4)
        runCurrent()
        rig.assertNoRequest()
        first.reply.complete(info())
        runCurrent()
        rig.assertNoRequest()

        // A refresh while a poll is out sends nothing more, but shows the list refreshing until it lands.
        advanceTimeBy(poll)
        runCurrent()
        val polled = rig.nextRequest()
        assertFalse(shown(rig.watch).refreshing)
        rig.watch.refresh()
        assertTrue(shown(rig.watch).refreshing)
        runCurrent()
        rig.assertNoRequest()
        polled.reply.complete(info())
        runCurrent()
        assertFalse(shown(rig.watch).refreshing)
        rig.assertNoRequest()

        // A refresh while shown marks the list refreshing until its answer lands.
        rig.watch.refresh()
        runCurrent()
        assertTrue(shown(rig.watch).refreshing)
        val refresh = rig.nextRequest()
        rig.watch.refresh()
        runCurrent()
        rig.assertNoRequest()
        refresh.reply.complete(info())
        runCurrent()
        assertFalse(shown(rig.watch).refreshing)
    }

    @Test
    fun failureKeepsLastInfoStale() = runTest {
        val rig = rig()
        rig.watch.start()
        runCurrent()
        // With nothing shown yet, a failure is retryable.
        rig.nextRequest().reply.completeExceptionally(CompanionRequestException("timed out"))
        runCurrent()
        assertEquals(WorkgroupState.Failed(WorkgroupFailure.UNREACHABLE, retryable = true), rig.watch.state.value)
        // Try again shows Loading, then the info.
        rig.watch.refresh()
        runCurrent()
        assertEquals(WorkgroupState.Loading, rig.watch.state.value)
        rig.nextRequest().reply.complete(info())
        runCurrent()
        val expected = (info() as MacMessage.WorkgroupInfoMessage).info

        // A timeout, another error and an unexpected reply each keep the info, marked stale.
        for (failure in listOf<(ScriptedStreamLink.Pending) -> Unit>(
            { it.reply.completeExceptionally(CompanionRequestException("connection lost")) },
            { it.reply.complete(otherError()) },
            { it.reply.complete(sampleReply("session-screen-info")) },
        )) {
            advanceTimeBy(poll)
            runCurrent()
            failure(rig.nextRequest())
            runCurrent()
            assertEquals(WorkgroupState.Shown(expected, refreshing = false, stale = true), rig.watch.state.value)
        }
        // Polling goes on after a failure, and an answer clears the stale mark.
        advanceTimeBy(poll)
        runCurrent()
        rig.nextRequest().reply.complete(info())
        runCurrent()
        assertEquals(WorkgroupState.Shown(expected, refreshing = false, stale = false), rig.watch.state.value)
        assertTrue("the log names no workgroup content", rig.log.none { "Widget" in it || "Something" in it })
    }

    @Test
    fun unknownWorkgroupStopsPolling() = runTest {
        val rig = rig()
        rig.watch.start()
        runCurrent()
        rig.nextRequest().reply.complete(info())
        runCurrent()
        advanceTimeBy(poll)
        runCurrent()
        rig.nextRequest().reply.complete(sampleReply("error-unknown-workgroup"))
        runCurrent()
        assertEquals(WorkgroupState.Failed(WorkgroupFailure.GONE, retryable = false), rig.watch.state.value)
        // No polls, no link-up or foreground refetch, and no retry.
        advanceTimeBy(poll * 5)
        rig.watch.setConnected(false)
        rig.watch.setConnected(true)
        rig.watch.linkUp()
        rig.watch.setForeground(false)
        rig.watch.setForeground(true)
        rig.watch.refresh()
        runCurrent()
        rig.assertNoRequest()
        assertEquals(WorkgroupState.Failed(WorkgroupFailure.GONE, retryable = false), rig.watch.state.value)
        assertTrue("the Mac's text is never logged", rig.log.none { "no longer exists" in it })
    }

    @Test
    fun refetchesOnLinkUp() = runTest {
        val rig = rig()
        rig.watch.start()
        runCurrent()
        rig.nextRequest().reply.complete(info())
        runCurrent()
        // The link drops and comes back mid-interval: one request at once, not at the next poll.
        advanceTimeBy(poll / 2)
        rig.watch.setConnected(false)
        rig.watch.setConnected(true)
        rig.watch.linkUp()
        runCurrent()
        rig.nextRequest().reply.complete(info())
        runCurrent()
        rig.assertNoRequest()

        // A link that comes back while the old link's request is still out asks again once that
        // request ends, so the answer is from the new link.
        advanceTimeBy(poll)
        runCurrent()
        val oldLink = rig.nextRequest()
        rig.watch.setConnected(false)
        rig.watch.setConnected(true)
        rig.watch.linkUp()
        runCurrent()
        rig.assertNoRequest()
        oldLink.reply.completeExceptionally(CompanionRequestException("connection lost"))
        runCurrent()
        rig.nextRequest().reply.complete(info())
        runCurrent()
        assertFalse(shown(rig.watch).stale)
        rig.assertNoRequest()
    }

    @Test
    fun futureMemberStateReadsUnknown() = runTest {
        val rig = rig()
        rig.watch.start()
        runCurrent()
        val future = sampleJson("workgroup-info").toString().replace("\"state\":\"working\"", "\"state\":\"sleeping\"")
        assertTrue(future.contains("sleeping"))
        rig.nextRequest().reply.complete(decodeEnvelope(future.toByteArray()).second)
        runCurrent()
        val members = shown(rig.watch).info.members
        assertEquals(5, members.size)
        assertEquals(
            listOf(SessionState.IDLE, SessionState.UNKNOWN, SessionState.WAITING, SessionState.UNKNOWN, SessionState.UNKNOWN),
            members.map { it.state },
        )
    }

    @Test
    fun stateShowsNoContent() = runTest {
        val rig = rig()
        rig.watch.start()
        runCurrent()
        rig.nextRequest().reply.complete(info())
        runCurrent()
        for (text in listOf(rig.watch.state.value.toString(), rig.watch.toString())) {
            for (secret in listOf("Widget Review", "Implementer", "Reviewing", "widget.c", "Needs input", "zsh", "docs-writer")) {
                assertFalse("$secret leaked", secret in text)
            }
        }
        assertEquals(
            "Failed(reason=GONE, retryable=false)",
            WorkgroupState.Failed(WorkgroupFailure.GONE, retryable = false).toString(),
        )
    }

    @Test
    fun homeGraphWatchFollowsForegroundAndConnection() = runTest {
        val rig = SessionsRig(this)
        rig.answerFetches()
        rig.mac.onRequest("fetchWorkgroupInfo") { sampleJson("workgroup-info").getValue("payload").jsonObject }
        rig.connect()
        fun asked() = rig.mac.receivedCases().count { it == "fetchWorkgroupInfo" }
        val watch = rig.graph.watchWorkgroup(backgroundScope, workgroupID, pollInterval = poll)
        watch.start()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(1, asked())
        assertEquals("Widget Review", shown(watch).info.name)
        advanceTimeBy(poll)
        runCurrent()
        assertEquals(2, asked())

        // The process lifecycle's ON_STOP pauses it; ON_START resumes with one request.
        rig.graph.setForeground(false)
        advanceTimeBy(poll * 4)
        runCurrent()
        assertEquals(2, asked())
        rig.graph.setForeground(true)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(3, asked())

        // A reconnect asks again on the new link, without waiting for the next poll.
        advanceTimeBy(1.seconds)
        rig.mac.dropSocket()
        rig.awaitConnected(2)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals("asked once on the new link", 4, asked())
        assertFalse(shown(watch).stale)
        watch.stop()
        val stopped = asked()
        advanceTimeBy(poll * 4)
        runCurrent()
        assertEquals(stopped, asked())
    }

    @Test
    fun cancelledTimerNeitherAsksNorForksALoop() = runTest {
        val rig = rig()
        rig.watch.start()
        runCurrent()
        rig.nextRequest().reply.complete(info())
        runCurrent()
        // The timer has waited its interval; before it takes the lock another thread backgrounds the
        // app (cancelling it), so it must not ask.
        rig.watch.beforeTimerLock = {
            rig.watch.beforeTimerLock = {}
            rig.watch.setForeground(false)
        }
        advanceTimeBy(poll)
        runCurrent()
        rig.assertNoRequest()
        advanceTimeBy(poll * 3)
        runCurrent()
        rig.assertNoRequest()
        rig.watch.setForeground(true)
        runCurrent()
        rig.nextRequest().reply.complete(info())
        runCurrent()

        // Here the gate shuts and reopens meanwhile, which starts a new timer: the old one must not
        // ask too, or two poll loops would run.
        rig.watch.beforeTimerLock = {
            rig.watch.beforeTimerLock = {}
            rig.watch.setConnected(false)
            rig.watch.setConnected(true)
        }
        advanceTimeBy(poll)
        runCurrent()
        rig.assertNoRequest()
        // One loop: exactly one request per interval from here on.
        repeat(4) {
            advanceTimeBy(poll)
            runCurrent()
            rig.nextRequest().reply.complete(info())
            runCurrent()
            rig.assertNoRequest()
        }
    }

    @Test
    fun inFlightIsReleasedWhenTheScopeIsCancelled() = runTest {
        val link = ScriptedStreamLink()
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val watch = WorkgroupWatch(link, scope, workgroupID, pollInterval = poll)
        // A scope already cancelled: the request never runs, and is not left "in flight".
        scope.cancel()
        watch.start()
        runCurrent()
        assertTrue(link.requests.tryReceive().isFailure)
        assertTrue(watch.toString(), "asking=false" in watch.toString())

        // A request cancelled with its scope while out is released as well.
        val live = CoroutineScope(backgroundScope.coroutineContext + Job())
        val other = WorkgroupWatch(link, live, workgroupID, pollInterval = poll)
        other.start()
        runCurrent()
        link.requests.tryReceive().getOrThrow()
        assertTrue("asking=true" in other.toString())
        live.cancel()
        runCurrent()
        assertTrue(other.toString(), "asking=false" in other.toString())
    }

    @Test
    fun startAfterGoneAsksAgain() = runTest {
        val rig = rig()
        rig.watch.start()
        runCurrent()
        rig.nextRequest().reply.complete(sampleReply("error-unknown-workgroup"))
        runCurrent()
        assertEquals(WorkgroupState.Failed(WorkgroupFailure.GONE, retryable = false), rig.watch.state.value)
        // The view appears again: a fresh start asks once more, showing Loading meanwhile.
        rig.watch.stop()
        rig.watch.start()
        runCurrent()
        assertEquals(WorkgroupState.Loading, rig.watch.state.value)
        rig.nextRequest().reply.complete(info())
        runCurrent()
        assertFalse(shown(rig.watch).stale)
    }

    @Test
    fun reAskAfterFailureShowsLoading() = runTest {
        val rig = rig()
        rig.watch.start()
        runCurrent()
        rig.nextRequest().reply.completeExceptionally(CompanionRequestException("timed out"))
        runCurrent()
        assertEquals(WorkgroupState.Failed(WorkgroupFailure.UNREACHABLE, retryable = true), rig.watch.state.value)
        // The next poll asks again: the failure gives way to Loading while it is out.
        advanceTimeBy(poll)
        runCurrent()
        assertEquals(WorkgroupState.Loading, rig.watch.state.value)
        rig.nextRequest().reply.complete(info())
        runCurrent()
        assertTrue(rig.watch.state.value is WorkgroupState.Shown)
    }

    @Test
    fun stopWhileRefreshingClearsRefreshing() = runTest {
        val rig = rig()
        rig.watch.start()
        runCurrent()
        rig.nextRequest().reply.complete(info())
        runCurrent()
        rig.watch.refresh()
        runCurrent()
        val refresh = rig.nextRequest()
        assertTrue(shown(rig.watch).refreshing)
        rig.watch.stop()
        assertFalse(shown(rig.watch).refreshing)
        refresh.reply.complete(otherError())
        runCurrent()
        assertEquals(false, shown(rig.watch).stale)
    }
}
