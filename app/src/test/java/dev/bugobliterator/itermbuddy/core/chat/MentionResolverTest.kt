package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.CompanionRequestException
import dev.bugobliterator.itermbuddy.core.stream.ScriptedStreamLink
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MentionResolverTest {
    private val stableID = "ptys_7K2M9Q4R8T1VH"
    private val otherStableID = "ptys_3HX6W0ZD5N2BT"
    private val workgroup = "wg-5C4D3E2F-1A0B-4C9D-8E7F-6A5B4C3D2E1F"
    private val secretName = "Sentinel deploy shell"

    private class Rig(scope: TestScope) {
        val link = ScriptedStreamLink()
        val logs = CopyOnWriteArrayList<String>()
        val resolver = MentionResolver(link, scope.backgroundScope, { logs += it }, requestTimeout = 7.seconds)

        fun nextRequest(): ScriptedStreamLink.Pending {
            val pending = link.requests.tryReceive().getOrNull() ?: throw AssertionError("no request was sent")
            assertEquals(7.seconds, pending.timeout)
            return pending
        }

        fun assertNoRequest() = assertTrue("an unexpected request was sent", link.requests.tryReceive().isFailure)
    }

    private fun hit(identifier: String, name: String = secretName) = MentionResolution(identifier, name, stableID, null)

    private fun miss(identifier: String) = MentionResolution(identifier, null, null, null)

    @Test
    fun hitIsCachedMissIsGated() = runTest {
        val rig = Rig(this)
        rig.resolver.note(listOf("Ask @$stableID and @$otherStableID.", "Then @$stableID again"))
        runCurrent()
        val pending = rig.nextRequest()
        assertEquals(PhoneMessage.ResolveMentions(listOf(stableID, otherStableID)), pending.message)
        pending.reply.complete(MacMessage.MentionsResolved(listOf(hit(stableID), miss(otherStableID))))
        runCurrent()

        assertEquals(mapOf(stableID to hit(stableID), otherStableID to miss(otherStableID)), rig.resolver.resolutions.value)
        assertEquals(MentionDisplay.Named(hit(stableID)), mentionDisplay(rig.resolver.resolutions.value, stableID))
        assertEquals(MentionDisplay.Defunct, mentionDisplay(rig.resolver.resolutions.value, otherStableID))
        assertEquals(MentionDisplay.Raw, mentionDisplay(rig.resolver.resolutions.value, workgroup))

        // The hit is cached and the miss is gated: noting both again asks for nothing.
        rig.resolver.note(listOf("@$stableID @$otherStableID"))
        runCurrent()
        rig.assertNoRequest()
        // Reopening the gate retries the miss but never the hit.
        rig.resolver.reopenRetryGate()
        rig.resolver.note(listOf("@$stableID @$otherStableID"))
        runCurrent()
        assertEquals(PhoneMessage.ResolveMentions(listOf(otherStableID)), rig.nextRequest().message)
    }

    @Test
    fun entryMissingFromTheReplyIsAnAnsweredMiss() = runTest {
        val rig = Rig(this)
        rig.resolver.note(listOf("@$stableID @$workgroup"))
        runCurrent()
        rig.nextRequest().reply.complete(MacMessage.MentionsResolved(listOf(hit(stableID))))
        runCurrent()
        assertEquals(MentionDisplay.Defunct, mentionDisplay(rig.resolver.resolutions.value, workgroup))
        rig.resolver.note(listOf("@$workgroup"))
        runCurrent()
        rig.assertNoRequest()
    }

    @Test
    fun missIsRetriedAfterReconnect() = runTest {
        val rig = Rig(this)
        rig.resolver.note(listOf("@$stableID"))
        runCurrent()
        rig.nextRequest().reply.complete(MacMessage.MentionsResolved(listOf(miss(stableID))))
        runCurrent()
        rig.resolver.note(listOf("@$stableID"))
        runCurrent()
        rig.assertNoRequest()

        // The link comes back: the caller reopens the gate and scans again.
        rig.resolver.reopenRetryGate()
        rig.resolver.note(listOf("@$stableID"))
        runCurrent()
        val retry = rig.nextRequest()
        assertEquals(PhoneMessage.ResolveMentions(listOf(stableID)), retry.message)
        assertEquals(MentionDisplay.Defunct, mentionDisplay(rig.resolver.resolutions.value, stableID))
        retry.reply.complete(MacMessage.MentionsResolved(listOf(hit(stableID))))
        runCurrent()
        assertEquals(MentionDisplay.Named(hit(stableID)), mentionDisplay(rig.resolver.resolutions.value, stableID))
    }

    @Test
    fun inFlightIdentifiersAreNotRequestedTwice() = runTest {
        val rig = Rig(this)
        rig.resolver.note(listOf("@$stableID @$otherStableID"))
        runCurrent()
        val first = rig.nextRequest()
        rig.resolver.note(listOf("@$otherStableID @$workgroup"))
        runCurrent()
        val second = rig.nextRequest()
        assertEquals(PhoneMessage.ResolveMentions(listOf(workgroup)), second.message)
        rig.resolver.note(listOf("@$stableID @$otherStableID @$workgroup"))
        runCurrent()
        rig.assertNoRequest()

        // Once the first answers, its identifiers are no longer in flight.
        first.reply.complete(MacMessage.MentionsResolved(listOf(hit(stableID), hit(otherStableID))))
        second.reply.completeExceptionally(CompanionRequestException("link ended"))
        runCurrent()
        rig.resolver.reopenRetryGate()
        rig.resolver.note(listOf("@$stableID @$otherStableID @$workgroup"))
        runCurrent()
        assertEquals(PhoneMessage.ResolveMentions(listOf(workgroup)), rig.nextRequest().message)
    }

    @Test
    fun failedRequestGatesTheBatch() = runTest {
        val rig = Rig(this)
        rig.resolver.note(listOf("@$stableID @$workgroup"))
        runCurrent()
        rig.nextRequest().reply.completeExceptionally(CompanionRequestException("no reply within 15s"))
        runCurrent()
        // Not answered by the Mac: the mention stays raw, and is not asked for again yet.
        assertEquals(emptyMap<String, MentionResolution>(), rig.resolver.resolutions.value)
        assertEquals(MentionDisplay.Raw, mentionDisplay(rig.resolver.resolutions.value, stableID))
        rig.resolver.note(listOf("@$stableID @$workgroup"))
        runCurrent()
        rig.assertNoRequest()

        // An error reply gates the batch the same way.
        rig.resolver.reopenRetryGate()
        rig.resolver.note(listOf("@$stableID @$workgroup"))
        runCurrent()
        val error = buildJsonObject {
            put("code", JsonPrimitive("internalError"))
            put("message", JsonPrimitive(secretName))
        }
        rig.nextRequest().reply.complete(MacMessage.Error(error))
        runCurrent()
        assertEquals(emptyMap<String, MentionResolution>(), rig.resolver.resolutions.value)
        rig.resolver.note(listOf("@$stableID @$workgroup"))
        runCurrent()
        rig.assertNoRequest()
        assertFalse(rig.logs.joinToString().contains(secretName))
    }

    @Test
    fun agentTextAdmitsBareStableIdsUserTextDoesNot() = runTest {
        val rig = Rig(this)
        fun message(author: Participant, body: MessageBody, id: Int) =
            ChatMessage("9B8C7D6E-5F4A-4B3C-8D2E-1F0A9B8C7D6E", author, body, 1.0, "11111111-2222-4333-8444-00000000000$id")
        rig.resolver.noteMessages(
            listOf(
                message(Participant.USER, MessageBody.PlainText("ask $otherStableID"), 1),
                message(Participant.AGENT, MessageBody.Markdown("asked $stableID"), 2),
                message(Participant.AGENT, MessageBody.Multipart(listOf(Subpart.Context("@$workgroup"))), 3),
                message(Participant.AGENT, MessageBody.Hidden("renameChat"), 4),
            ),
        )
        runCurrent()
        assertEquals(PhoneMessage.ResolveMentions(listOf(stableID)), rig.nextRequest().message)
    }

    @Test
    fun mentionableTextsFollowIos() {
        val command = CommandContent.ExecuteCommand("ls @$stableID")
        val texts = listOf(
            MessageBody.PlainText("a") to listOf("a"),
            MessageBody.Markdown("b") to listOf("b"),
            MessageBody.Multipart(listOf(Subpart.PlainText("c"), Subpart.Context("x"), Subpart.Markdown("d"))) to listOf("c", "d"),
            MessageBody.ExplanationResponse("e") to listOf("e"),
            MessageBody.ClassicCommand(command, null) to listOf(command.markdownDescription()),
            MessageBody.ExternalCommand("tool", "f", null) to listOf("f"),
            MessageBody.WatcherEvent("g") to emptyList(),
            MessageBody.TerminalCommand("h", "i") to emptyList(),
            MessageBody.Unsupported to emptyList(),
            MessageBody.ClientNotice(ClientAction.ExecutingCommand(command)) to listOf(command.markdownDescription()),
            MessageBody.ClientNotice(ClientAction.Notice("@$stableID")) to emptyList(),
        )
        for ((body, expected) in texts) assertEquals(body.toString(), expected, mentionableTexts(body))
    }

    @Test
    fun nameWithNothingToOpenIsDefunct() {
        val named = mapOf(
            stableID to MentionResolution(stableID, secretName, stableID, null),
            workgroup to MentionResolution(workgroup, secretName, null, workgroup),
            otherStableID to MentionResolution(otherStableID, secretName, null, null),
        )
        assertEquals(MentionDisplay.Named(named.getValue(stableID)), mentionDisplay(named, stableID))
        assertEquals(MentionDisplay.Named(named.getValue(workgroup)), mentionDisplay(named, workgroup))
        assertEquals(MentionDisplay.Defunct, mentionDisplay(named, otherStableID))
    }

    @Test
    fun inFlightIsReleasedWhenTheScopeIsCancelledBeforeTheRequestStarts() = runTest {
        val link = ScriptedStreamLink()
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val resolver = MentionResolver(link, scope, {})
        resolver.note(listOf("@$stableID"))
        scope.cancel()
        runCurrent()
        assertTrue(resolver.toString(), resolver.toString().contains("inFlight=0"))
    }

    @Test
    fun clearForgetsEverythingAndDropsLateAnswers() = runTest {
        val rig = Rig(this)
        rig.resolver.note(listOf("@$stableID @$otherStableID"))
        runCurrent()
        rig.nextRequest().reply.complete(MacMessage.MentionsResolved(listOf(hit(stableID), miss(otherStableID))))
        runCurrent()
        rig.resolver.note(listOf("@$workgroup"))
        runCurrent()
        val late = rig.nextRequest()

        rig.resolver.clear()
        assertEquals(emptyMap<String, MentionResolution>(), rig.resolver.resolutions.value)
        late.reply.complete(MacMessage.MentionsResolved(listOf(hit(workgroup))))
        runCurrent()
        assertEquals(emptyMap<String, MentionResolution>(), rig.resolver.resolutions.value)

        // Nothing is cached, gated or in flight: all three are asked for again.
        rig.resolver.note(listOf("@$stableID @$otherStableID @$workgroup"))
        runCurrent()
        assertEquals(PhoneMessage.ResolveMentions(listOf(stableID, otherStableID, workgroup)), rig.nextRequest().message)
    }

    @Test
    fun resolutionsHoldNoContentInToString() = runTest {
        val rig = Rig(this)
        rig.resolver.note(listOf("Ask @$stableID about $secretName"))
        runCurrent()
        rig.nextRequest().reply.complete(MacMessage.MentionsResolved(listOf(hit(stableID))))
        runCurrent()
        for (text in listOf(
            rig.resolver.toString(),
            rig.resolver.resolutions.value.toString(),
            mentionDisplay(rig.resolver.resolutions.value, stableID).toString(),
            rig.logs.joinToString(),
        )) {
            assertFalse(text, text.contains(secretName))
            assertFalse(text, text.contains("Sentinel"))
        }
        assertTrue(rig.logs.isNotEmpty())
    }
}
