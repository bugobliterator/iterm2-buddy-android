package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.MAC_TIME
import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.chatId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/** What the home reads from the repository: the list snapshot, the link generation and each link's AI flag. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatHomeFeedsTest {
    private fun TestScope.settle() {
        advanceTimeBy(5.seconds)
        runCurrent()
    }

    private fun ChatRepository.snapshotMatches() {
        val shot = listSnapshot.value
        assertEquals(list.value, shot.list)
        assertEquals(rows.value, shot.rows)
        assertEquals(aiAvailable.value, shot.aiAvailable)
    }

    @Test
    fun listSnapshotMatchesListRowsAndAiAfterEachPublish() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME, B to MAC_TIME - 1)
        rig.repository.snapshotMatches()
        rig.connect()
        settle()
        rig.repository.snapshotMatches()
        assertEquals(listOf(A, B), rig.repository.listSnapshot.value.rows.map { it.entry.chat.id })

        rig.chats = listOf(B to MAC_TIME + 5, A to MAC_TIME)
        rig.pushList()
        settle()
        rig.repository.snapshotMatches()
        assertEquals(listOf(B, A), rig.repository.listSnapshot.value.rows.map { it.entry.chat.id })

        rig.chatMac.flipAi(false)
        settle()
        rig.repository.snapshotMatches()
        assertFalse(rig.repository.listSnapshot.value.aiAvailable)
        assertEquals(ChatListState.AiUnavailable, rig.repository.listSnapshot.value.list)
    }

    /** The pairing epoch moves the moment the chats are cleared; the snapshot carries it once the consumer ran the clear. */
    @Test
    fun clearBumpsThePairingEpochAtOnceAndTheSnapshotAfter() = runTest {
        val rig = ChatRig(this)
        rig.chats = listOf(A to MAC_TIME)
        rig.connect()
        settle()
        assertEquals(0L, rig.repository.pairingEpoch.value)
        assertEquals(0L, rig.repository.listSnapshot.value.pairingEpoch)
        rig.repository.clear()
        assertEquals("on the caller's thread", 1L, rig.repository.pairingEpoch.value)
        assertEquals("not yet run on the consumer", 0L, rig.repository.listSnapshot.value.pairingEpoch)
        settle()
        assertEquals(1L, rig.repository.listSnapshot.value.pairingEpoch)
        assertEquals(ChatListState.Loading, rig.repository.listSnapshot.value.list)
    }

    @Test
    fun linkGenerationIsBumpedOnEveryChatLinkUp() = runTest {
        val rig = ChatRig(this)
        assertEquals(0L, rig.repository.linkGeneration.value)
        rig.connect()
        settle()
        assertEquals(1L, rig.repository.linkGeneration.value)
        rig.reconnect()
        settle()
        assertEquals(2L, rig.repository.linkGeneration.value)
        // Unpair leaves it growing: a reader never mistakes the next pairing's link for one it saw.
        rig.repository.clear()
        settle()
        assertEquals(2L, rig.repository.linkGeneration.value)
    }

    /**
     * One ordered feed for the navigation: each link up carries its generation and the hello's AI
     * flag (published once [ChatRepository.linkGeneration] holds it), and every AI-off grows a
     * count, so a reader conflating a quick off, on, off still sees that AI turned off. Unpair
     * empties it; the count and the generation never go back.
     */
    @Test
    fun availabilityOrdersLinkUpsAndCountsAiOffs() = runTest {
        val rig = ChatRig(this)
        assertNull(rig.repository.availability.value)
        val seen = CopyOnWriteArrayList<ChatAvailability>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.repository.availability.filterNotNull().collect {
                seen += it
                assertEquals(it.generation, rig.repository.linkGeneration.value)
            }
        }
        rig.mac.helloAiAvailable = false
        rig.connect()
        settle()
        assertEquals(listOf(ChatAvailability(1, aiAvailable = false, aiOffs = 0)), seen.toList())
        rig.mac.helloAiAvailable = true
        rig.reconnect()
        settle()
        assertEquals(ChatAvailability(2, aiAvailable = true, aiOffs = 0), seen.last())

        rig.chatMac.flipAi(false)
        rig.chatMac.flipAi(true)
        rig.chatMac.flipAi(false)
        settle()
        assertEquals(ChatAvailability(2, aiAvailable = false, aiOffs = 2), rig.repository.availability.value)

        rig.repository.clear()
        settle()
        assertNull(rig.repository.availability.value)
        assertEquals(2L, rig.repository.linkGeneration.value)
    }

    private companion object {
        val A = chatId(1)
        val B = chatId(2)
    }
}
