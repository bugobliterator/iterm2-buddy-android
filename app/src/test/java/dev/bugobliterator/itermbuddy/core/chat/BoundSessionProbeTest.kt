package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.Fixtures
import dev.bugobliterator.itermbuddy.core.CompanionRequestException
import dev.bugobliterator.itermbuddy.core.stream.ScriptedStreamLink
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import dev.bugobliterator.itermbuddy.core.wire.decodeEnvelope
import dev.bugobliterator.itermbuddy.core.wire.encodeEnvelope
import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BoundSessionProbeTest {
    private val chat = "6A4F2C1E-9B3D-4E8A-B7C5-2D1F0E9A8B7C"
    private val legacySession = "C3D4E5F6-0718-4293-A4B5-C6D7E8F90A1B"

    private class Rig(scope: TestScope) {
        val link = ScriptedStreamLink()
        val probe = BoundSessionProbe(link, scope.backgroundScope, timeout = 9.seconds)

        fun nextRequest(): ScriptedStreamLink.Pending {
            val pending = link.requests.tryReceive().getOrNull() ?: throw AssertionError("no request was sent")
            assertEquals(9.seconds, pending.timeout)
            return pending
        }

        fun assertNoRequest() = assertTrue("an unexpected request was sent", link.requests.tryReceive().isFailure)
    }

    private fun sampleJson(name: String) =
        Json.parseToJsonElement(Fixtures.read("messages/agent-chat/$name.json")).jsonObject.getValue("json").jsonObject

    private fun sampleReply(name: String): MacMessage =
        decodeEnvelope(sampleJson(name).toString().toByteArray()).second

    private fun unknownSession() = sampleReply("error-unknown-session")

    private fun otherError() = MacMessage.Error(
        buildJsonObject {
            put("code", JsonPrimitive("internalError"))
            put("message", JsonPrimitive("Something went wrong"))
        },
    )

    @Test
    fun probeMatchesSamples() = runTest {
        val rig = Rig(this)
        rig.probe.probe(chat, legacySession)
        runCurrent()
        val pending = rig.nextRequest()
        assertEquals(PhoneMessage.FetchSessionScreenInfo(legacySession), pending.message)
        // The envelope the client writes for it is the sample's, request ID included.
        val sample = sampleJson("fetch-session-screen-info")
        val requestID = (sample.getValue("requestID") as JsonPrimitive).content.toLong()
        assertEquals(sample, Json.parseToJsonElement(encodeEnvelope(pending.message, requestID).decodeToString()))
        pending.reply.complete(sampleReply("session-screen-info"))
        runCurrent()
        assertEquals(mapOf(chat to true), rig.probe.live.value)
    }

    @Test
    fun infoReplyConfirmsLive() = runTest {
        val rig = Rig(this)
        assertNull(rig.probe.live.value[chat])
        rig.probe.watch(chat, legacySession)
        runCurrent()
        assertNull("not confirmed before the reply", rig.probe.live.value[chat])
        rig.nextRequest().reply.complete(sampleReply("session-screen-info"))
        runCurrent()
        assertEquals(true, rig.probe.live.value[chat])
    }

    @Test
    fun unknownSessionMarksGone() = runTest {
        val rig = Rig(this)
        rig.probe.watch(chat, legacySession)
        runCurrent()
        rig.nextRequest().reply.complete(unknownSession())
        runCurrent()
        assertEquals(false, rig.probe.live.value[chat])
    }

    @Test
    fun transientFailureKeepsPriorVerdict() = runTest {
        val rig = Rig(this)
        rig.probe.watch(chat, legacySession)
        runCurrent()
        rig.nextRequest().reply.complete(sampleReply("session-screen-info"))
        runCurrent()
        assertEquals(true, rig.probe.live.value[chat])

        // Another error, a timeout and a lost link each leave the verdict as it was.
        rig.probe.linkUp()
        runCurrent()
        rig.nextRequest().reply.complete(otherError())
        runCurrent()
        assertEquals(true, rig.probe.live.value[chat])
        rig.probe.linkUp()
        runCurrent()
        rig.nextRequest().reply.completeExceptionally(CompanionRequestException("timed out"))
        runCurrent()
        assertEquals(true, rig.probe.live.value[chat])
        rig.probe.linkUp()
        runCurrent()
        rig.nextRequest().reply.completeExceptionally(CompanionRequestException("connection lost"))
        runCurrent()
        assertEquals(true, rig.probe.live.value[chat])

        // A gone verdict is kept the same way.
        rig.probe.linkUp()
        runCurrent()
        rig.nextRequest().reply.complete(unknownSession())
        runCurrent()
        rig.probe.linkUp()
        runCurrent()
        rig.nextRequest().reply.completeExceptionally(CompanionRequestException("timed out"))
        runCurrent()
        assertEquals(false, rig.probe.live.value[chat])
    }

    @Test
    fun reprobesOnLinkUp() = runTest {
        val rig = Rig(this)
        rig.probe.watch(chat, legacySession)
        runCurrent()
        // The first probe dies with the link; the next link probes again, and its answer lands.
        val first = rig.nextRequest()
        rig.probe.linkUp()
        runCurrent()
        val second = rig.nextRequest()
        assertEquals(PhoneMessage.FetchSessionScreenInfo(legacySession), second.message)
        first.reply.complete(unknownSession())
        runCurrent()
        assertNull("a superseded probe's answer is dropped", rig.probe.live.value[chat])
        second.reply.complete(sampleReply("session-screen-info"))
        runCurrent()
        assertEquals(true, rig.probe.live.value[chat])

        // Once the conversation closes, link ups probe nothing.
        rig.probe.unwatch(chat)
        rig.probe.linkUp()
        runCurrent()
        rig.assertNoRequest()
        // Watching the same binding again probes once; watching it twice does not.
        rig.probe.watch(chat, legacySession)
        rig.probe.watch(chat, legacySession)
        runCurrent()
        rig.nextRequest()
        rig.assertNoRequest()
    }

    /** A link whose requests ignore cancellation: a superseded probe runs on to its verdict. */
    private class UncancellableLink : StreamLink {
        val waiting = ArrayList<Continuation<MacMessage>>()

        override suspend fun send(message: PhoneMessage, onLink: Long?) = Unit

        override suspend fun request(message: PhoneMessage, onLink: Long?, timeout: Duration?, onSent: (requestID: Long) -> Unit): MacMessage =
            suspendCoroutine { waiting += it }
    }

    @Test
    fun supersededProbeStillRunningIsDropped() = runTest {
        val link = UncancellableLink()
        val probe = BoundSessionProbe(link, backgroundScope)
        probe.watch(chat, legacySession)
        runCurrent()
        probe.linkUp()
        runCurrent()
        assertEquals(2, link.waiting.size)
        // The superseded probe's coroutine is cancelled but still running: its answer reaches the
        // identity guard and is dropped.
        link.waiting[0].resume(unknownSession())
        runCurrent()
        assertNull("the superseded answer is dropped", probe.live.value[chat])
        link.waiting[1].resume(sampleReply("session-screen-info"))
        runCurrent()
        assertEquals(true, probe.live.value[chat])

        // A probe still running when the probe is cleared (unpair) is dropped by the generation guard.
        probe.linkUp()
        runCurrent()
        probe.clear()
        link.waiting[2].resume(unknownSession())
        runCurrent()
        assertTrue(probe.live.value.isEmpty())
    }

    @Test
    fun unboundChatIsNotProbed() = runTest {
        val rig = Rig(this)
        rig.probe.watch(chat, null)
        rig.probe.linkUp()
        runCurrent()
        rig.assertNoRequest()
        assertFalse(chat in rig.probe.live.value)
    }

    @Test
    fun bindingLearnedFromTheListIsProbedOnce() = runTest {
        val rig = Rig(this)
        // Opened before the list named its session: nothing to probe yet.
        rig.probe.watch(chat, null)
        runCurrent()
        rig.assertNoRequest()
        rig.probe.refreshBindings { legacySession }
        runCurrent()
        assertEquals(PhoneMessage.FetchSessionScreenInfo(legacySession), rig.nextRequest().message)
        // The same binding in the next list probes nothing; chats not watched are never asked about.
        rig.probe.refreshBindings { legacySession }
        runCurrent()
        rig.assertNoRequest()
        rig.probe.unwatch(chat)
        rig.probe.refreshBindings { legacySession }
        runCurrent()
        rig.assertNoRequest()
    }

    @Test
    fun clearForgetsVerdictsAndDropsAnswersInFlight() = runTest {
        val rig = Rig(this)
        rig.probe.watch(chat, legacySession)
        runCurrent()
        val pending = rig.nextRequest()
        rig.probe.clear()
        pending.reply.complete(sampleReply("session-screen-info"))
        runCurrent()
        assertTrue(rig.probe.live.value.isEmpty())
        rig.probe.linkUp()
        runCurrent()
        rig.assertNoRequest()
    }
}
