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
     * The link's AI flag is published with its generation: a reader woken by the link up reads the
     * hello's `aiAvailable`, not the value left from the link before (the list snapshot follows a
     * consumer step later).
     */
    @Test
    fun linkUpCarriesTheHellosAiAvailability() = runTest {
        val rig = ChatRig(this)
        assertNull(rig.repository.lastLinkUp.value)
        val seen = CopyOnWriteArrayList<ChatLinkUp>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.repository.lastLinkUp.filterNotNull().collect {
                seen += it
                // At the moment it is read, the generation agrees with it.
                assertEquals(it.generation, rig.repository.linkGeneration.value)
            }
        }
        rig.mac.helloAiAvailable = false
        rig.connect()
        settle()
        assertEquals(listOf(ChatLinkUp(1, aiAvailable = false)), seen.toList())
        rig.mac.helloAiAvailable = true
        rig.reconnect()
        settle()
        assertEquals(listOf(ChatLinkUp(1, aiAvailable = false), ChatLinkUp(2, aiAvailable = true)), seen.toList())
        assertTrue(rig.repository.aiAvailable.value)
    }

    private companion object {
        val A = chatId(1)
        val B = chatId(2)
    }
}
