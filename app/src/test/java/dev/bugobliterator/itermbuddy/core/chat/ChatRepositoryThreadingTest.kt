package dev.bugobliterator.itermbuddy.core.chat

import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.MAC_TIME
import dev.bugobliterator.itermbuddy.core.chat.ChatRig.Companion.chatId
import dev.bugobliterator.itermbuddy.core.stream.StreamLink
import dev.bugobliterator.itermbuddy.core.wire.MacMessage
import dev.bugobliterator.itermbuddy.core.wire.PhoneMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository on real threads, as the app runs it: the consumer and the request coroutines on
 * [Dispatchers.Default], replies accepted from another thread (the receive loop's), and calls
 * posted from a third (the UI's). Every other repository test runs on one test thread.
 */
class ChatRepositoryThreadingTest {
    /**
     * Each request's `onSent` is posted before its reply is accepted, whether the reply comes from
     * the receive loop's thread after the request suspends, or at once on the consumer's thread
     * before the launch returns (the hand-off's early path): every chat is subscribed exactly once
     * and every history lands, open conversations included.
     */
    @Test
    fun repliesFromAnotherThreadLandInOrder() = runBlocking {
        repeat(ROUNDS) { round -> oneRound(round) }
    }

    private suspend fun oneRound(round: Int) {
        val receiveLoop = Executors.newSingleThreadExecutor { Thread(it, "receive-loop-$round") }.asCoroutineDispatcher()
        val ui = Executors.newSingleThreadExecutor { Thread(it, "ui-$round") }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ids = (1..CHATS).map(::chatId)
        val entries = ids.map { ChatListEntry(ChatSummary(it, "t", 1.0, MAC_TIME, false, null, null, emptyList(), null), null, false) }
        val requestIDs = AtomicLong(1)
        val subscribes = ConcurrentHashMap<String, AtomicInteger>()
        lateinit var repository: ChatRepository
        val link = object : StreamLink {
            override suspend fun send(message: PhoneMessage, onLink: Long?) = Unit

            override suspend fun request(
                message: PhoneMessage,
                onLink: Long?,
                timeout: Duration?,
                onSent: (requestID: Long) -> Unit,
            ): MacMessage {
                val id = requestIDs.getAndIncrement()
                onSent(id)
                val reply = when (message) {
                    is PhoneMessage.ListChatsAndSessions -> MacMessage.ChatsAndSessions(entries, emptyList())
                    is PhoneMessage.Subscribe -> {
                        subscribes.computeIfAbsent(message.chatID) { AtomicInteger() }.incrementAndGet()
                        MacMessage.History(message.chatID, emptyList(), 0)
                    }
                    else -> error("unexpected request ${message.javaClass.simpleName}")
                }
                val input = ChatInput.Message(LINK, id, reply, FRAME_BYTES)
                if (id % 3 == 0L) {
                    // Answered before the request suspends: the launch has not returned yet.
                    repository.sink.accept(input)
                } else {
                    withContext(receiveLoop) { repository.sink.accept(input) }
                }
                return MacMessage.ChatPayloadTaken("reply", FRAME_BYTES)
            }
        }
        repository = ChatRepository(
            link,
            scope,
            InMemoryChatSeenStore(),
            nowMillis = { System.nanoTime() / 1_000_000 },
            snapshotUncollected = true,
        )
        try {
            val opened = ids.take(OPEN)
            withContext(ui) { opened.forEach(repository::open) }
            withContext(receiveLoop) { repository.sink.accept(ChatInput.LinkUp(LINK, 15, aiAvailable = true)) }
            withContext(ui) { repository.setVisible(opened.first()) }
            withTimeout(WAIT_MILLIS) {
                while (subscribes.values.sumOf { it.get() } < CHATS || opened.any { repository.conversation(it).value !is ConversationState.Shown }) {
                    delay(5)
                }
            }
            // Anything planned twice would go out now.
            delay(SETTLE_MILLIS)
            assertEquals("round $round: every chat once", ids.toSet(), subscribes.keys)
            assertTrue("round $round: none twice: $subscribes", subscribes.values.all { it.get() == 1 })
            for (chatID in opened) {
                val shown = repository.conversation(chatID).value as ConversationState.Shown
                assertTrue("round $round: $chatID sendable", shown.sendable)
            }
            assertEquals(ids, (repository.list.value as ChatListState.Loaded).entries.map { it.chat.id })
        } finally {
            scope.cancel()
            receiveLoop.close()
            ui.close()
        }
    }

    private companion object {
        const val ROUNDS = 20
        const val CHATS = 24
        const val OPEN = 3
        const val LINK = 1L
        const val FRAME_BYTES = 128
        const val WAIT_MILLIS = 20_000L
        const val SETTLE_MILLIS = 50L
    }
}
