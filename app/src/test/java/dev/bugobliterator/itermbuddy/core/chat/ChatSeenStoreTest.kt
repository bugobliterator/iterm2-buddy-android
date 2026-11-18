package dev.bugobliterator.itermbuddy.core.chat

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's seen marks: chat IDs and the Mac's lastModified, kept across restarts, wiped on unpair. */
class ChatSeenStoreTest {
    private val dir: File = Files.createTempDirectory("chat-seen").toFile()
    private val file = File(dir, "chat-seen.json")
    private val logs = mutableListOf<String>()

    private fun store() = FileChatSeenStore(file, log = { synchronized(logs) { logs += it } })

    @After
    fun removeTempDir() {
        dir.deleteRecursively()
    }

    @Test
    fun seenMarksSurviveRestartAndClearOnUnpair() {
        val first = store()
        assertNull(first.seen(CHAT_A))
        first.markSeen(CHAT_A, WHOLE_MILLIS)
        first.markSeen(CHAT_B, FRACTIONAL_MILLIS)
        first.markSeen(CHAT_A, LATER_MILLIS)

        val restarted = store()
        assertEquals(LATER_MILLIS, restarted.seen(CHAT_A)!!, 0.0)
        assertEquals("fractional Mac milliseconds round-trip exactly", FRACTIONAL_MILLIS, restarted.seen(CHAT_B)!!, 0.0)

        restarted.clear()
        assertNull(restarted.seen(CHAT_A))
        assertFalse("nothing left on disk", file.exists())
        assertNull(store().seen(CHAT_A))
        assertNull(store().seen(CHAT_B))
    }

    @Test
    fun markIsTheMacValueAsGiven() {
        // The mark is the Mac's value at the owner's last look; a Mac clock that went back is
        // recorded as is, so later changes on that clock still count.
        val store = store()
        store.markSeen(CHAT_A, LATER_MILLIS)
        store.markSeen(CHAT_A, WHOLE_MILLIS)
        assertEquals(WHOLE_MILLIS, store().seen(CHAT_A)!!, 0.0)
    }

    @Test
    fun forgetRemovesOneChat() {
        val store = store()
        store.markSeen(CHAT_A, WHOLE_MILLIS)
        store.markSeen(CHAT_B, WHOLE_MILLIS)
        store.forget(CHAT_A)
        val restarted = store()
        assertNull(restarted.seen(CHAT_A))
        assertEquals(WHOLE_MILLIS, restarted.seen(CHAT_B)!!, 0.0)
    }

    @Test
    fun nonFiniteMarksAreIgnored() {
        val store = store()
        store.markSeen(CHAT_A, Double.NaN)
        store.markSeen(CHAT_B, Double.POSITIVE_INFINITY)
        assertNull(store.seen(CHAT_A))
        assertNull(store.seen(CHAT_B))
        assertFalse(file.exists())
    }

    @Test
    fun storeHoldsNoContent() {
        val store = store()
        store.markSeen(CHAT_A, WHOLE_MILLIS)
        store.markSeen(CHAT_B, FRACTIONAL_MILLIS)
        val text = file.readText(Charsets.UTF_8)
        val withoutIDs = text.replace(CHAT_A, "").replace(CHAT_B, "")
        assertTrue("only IDs, digits and JSON punctuation: $withoutIDs", withoutIDs.all { it in "0123456789.{}[]\":," })
        assertEquals("no leftover temp files", listOf(file.name), dir.list()!!.toList())
    }

    @Test
    fun tempFileLeftByAKillIsRemovedOnLoadAndClear() {
        val temp = File(dir, "chat-seen.json.tmp")
        store().markSeen(CHAT_A, WHOLE_MILLIS)
        temp.writeText("{\"$CHAT_B\":1}")

        val restarted = store()
        assertEquals(WHOLE_MILLIS, restarted.seen(CHAT_A)!!, 0.0)
        assertNull("the half-written file is not read", restarted.seen(CHAT_B))
        assertFalse(temp.exists())

        temp.writeText("{\"$CHAT_B\":1}")
        restarted.clear()
        assertEquals("unpair leaves no file at all", emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun unreadableFileStartsEmptyAndIsLoggedWithoutItsBytes() {
        file.writeText("{\"$CHAT_A\":\"secret title\"")
        val store = store()
        assertNull(store.seen(CHAT_A))
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { "secret" in it || CHAT_A in it })

        store.markSeen(CHAT_B, WHOLE_MILLIS)
        assertEquals(WHOLE_MILLIS, store().seen(CHAT_B)!!, 0.0)
    }

    @Test
    fun wrongTypedValuesAreSkipped() {
        file.writeText("{\"$CHAT_A\":\"text\",\"$CHAT_B\":$WHOLE_MILLIS_TEXT,\"x\":{}}")
        val store = store()
        assertNull(store.seen(CHAT_A))
        assertNull(store.seen("x"))
        assertEquals(WHOLE_MILLIS, store.seen(CHAT_B)!!, 0.0)
    }

    @Test
    fun inMemoryStoreFollowsTheSameContract() {
        val store = InMemoryChatSeenStore()
        store.markSeen(CHAT_A, WHOLE_MILLIS)
        store.markSeen(CHAT_B, Double.NaN)
        assertEquals(WHOLE_MILLIS, store.seen(CHAT_A)!!, 0.0)
        assertNull(store.seen(CHAT_B))
        store.forget(CHAT_A)
        assertNull(store.seen(CHAT_A))
        store.markSeen(CHAT_A, WHOLE_MILLIS)
        store.clear()
        assertNull(store.seen(CHAT_A))
    }

    @Test
    fun markSeenAllWritesOnce() {
        val store = store()
        store.markSeenAll((1..10).associate { "chat-$it" to it * 1000.0 })
        assertEquals(1, store.saves)
        store.markSeenAll((1..10).associate { "chat-$it" to it * 1000.0 })
        assertEquals("nothing changed, nothing written", 1, store.saves)
        assertEquals(7000.0, store().seen("chat-7")!!, 0.0)
        val memory = InMemoryChatSeenStore()
        memory.markSeenAll(mapOf("a" to 1.0, "b" to Double.NaN))
        assertEquals(1.0, memory.seen("a")!!, 0.0)
        assertNull(memory.seen("b"))
    }

    private companion object {
        const val CHAT_A = "6A1D3F5B-2C4E-4A8B-9D0F-1E2A3B4C5D6E"
        const val CHAT_B = "9B8C7D6E-5F4A-4B3C-8D2E-1F0A9B8C7D6E"
        const val WHOLE_MILLIS = 1794960060000.0
        const val WHOLE_MILLIS_TEXT = "1794960060000"
        const val FRACTIONAL_MILLIS = 1794960060123.456
        const val LATER_MILLIS = 1795000300000.0
    }
}
