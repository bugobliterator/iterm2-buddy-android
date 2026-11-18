package dev.bugobliterator.itermbuddy.ui.chats

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
/** Chat icons: decoded once per content off the caller's thread, under a byte budget, a corrupt one shown as the default glyph. */
class ChatIconCacheTest {
    /** Decodes "png:<label>:<bytes>" into a label of that decoded size; anything else is corrupt. */
    private class FakeDecoder : ImageBytesDecoder<String> {
        val decoded = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<Thread>()

        override fun decode(bytes: ByteBuffer): DecodedImage<String>? {
            threads += Thread.currentThread()
            val array = ByteArray(bytes.remaining()).also { bytes.duplicate().get(it) }
            val text = String(array)
            decoded += text
            val parts = text.split(":")
            if (parts.size != 3 || parts[0] != "png") return null
            if (parts[1] == "throws") throw IllegalArgumentException("bad image $text")
            return DecodedImage(parts[1], parts[2].toInt())
        }
    }

    private fun png(label: String, size: Int): ChatIconRef = ref("png:$label:$size")

    private fun ref(text: String): ChatIconRef = checkNotNull(chatIconRef(ByteBuffer.wrap(text.toByteArray()).asReadOnlyBuffer()))

    /** Decodes on the caller, so the budget tests stay in one thread; [decodingNeverRunsOnTheCallingThread] pins the default. */
    private fun cache(decoder: FakeDecoder, maxBytes: Int) = ChatIconCache(decoder, maxBytes, EmptyCoroutineContext)

    @Test
    fun decodesOncePerContent() = runTest {
        val decoder = FakeDecoder()
        val cache = cache(decoder, maxBytes = 1_000)
        val first = cache.load(png("robot", 100))
        // A different buffer with the same bytes (the next list value) is the same icon, by key.
        assertEquals(png("robot", 100), png("robot", 100))
        assertSame(first, cache.load(png("robot", 100)))
        assertSame(first, cache.cached(png("robot", 100).key))
        assertEquals("robot", first)
        assertEquals(1, decoder.decoded.size)
        assertEquals("cat", cache.load(png("cat", 100)))
        assertEquals(2, decoder.decoded.size)
        // No icon: no ref, the default glyph.
        assertNull(chatIconRef(null))
        assertNull(chatIconRef(ByteBuffer.allocate(0)))
        // Hashing and decoding do not consume the caller's buffer.
        val buffer = ByteBuffer.wrap("png:dog:10".toByteArray())
        cache.load(checkNotNull(chatIconRef(buffer)))
        assertEquals(0, buffer.position())
        // The key is the lowercase hex SHA-256.
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", ref("hello").key)
    }

    @Test
    fun decodingNeverRunsOnTheCallingThread() = runTest {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "icon-decoder-test") }
        try {
            val decoder = FakeDecoder()
            val cache = ChatIconCache(decoder, maxBytes = 1_000, decodeContext = executor.asCoroutineDispatcher())
            val caller = Thread.currentThread()
            val decoderThread = executor.submit<Thread> { Thread.currentThread() }.get()
            assertEquals("robot", cache.load(png("robot", 10)))
            assertNull(cache.load(ref("corrupt")))
            assertEquals(2, decoder.threads.size)
            for (thread in decoder.threads) {
                assertNotEquals(caller, thread)
                assertSame(decoderThread, thread)
            }
            // A cached icon is a lookup: nothing is decoded again.
            assertEquals("robot", cache.load(png("robot", 10)))
            assertEquals(2, decoder.threads.size)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun evictsOverBudget() = runTest {
        val decoder = FakeDecoder()
        val cache = cache(decoder, maxBytes = 100)
        cache.load(png("a", 40))
        cache.load(png("b", 40))
        cache.load(png("a", 40)) // a is now the most recently used
        cache.load(png("c", 40)) // over 100: b, the least recently used, goes
        assertEquals(3, decoder.decoded.size)
        assertEquals(80, cache.heldBytes)
        assertNull(cache.cached(png("b", 40).key))
        cache.load(png("a", 40))
        cache.load(png("c", 40))
        assertEquals(3, decoder.decoded.size)
        cache.load(png("b", 40))
        assertEquals(4, decoder.decoded.size)
        assertEquals(80, cache.heldBytes)

        // An icon bigger than the whole budget is not kept, shows the default glyph, and is not decoded again.
        assertNull(cache.load(png("huge", 101)))
        assertNull(cache.load(png("huge", 101)))
        assertEquals(5, decoder.decoded.size)
        assertEquals(80, cache.heldBytes)

        cache.clear()
        assertEquals(0, cache.heldBytes)
        cache.load(png("a", 40))
        assertEquals(6, decoder.decoded.size)
    }

    @Test
    fun corruptPngShowsDefaultIcon() = runTest {
        val decoder = FakeDecoder()
        val cache = cache(decoder, maxBytes = 1_000)
        assertNull(cache.load(ref("not a png")))
        assertNull(cache.load(ref("not a png")))
        // A decoder that throws is a corrupt icon too, never an exception.
        assertNull(cache.load(png("throws", 10)))
        assertNull(cache.load(png("throws", 10)))
        // Each corrupt content was tried once.
        assertEquals(2, decoder.decoded.size)
        assertEquals(0, cache.heldBytes)
        assertFalse(cache.toString(), "png" in cache.toString())
        assertFalse(png("x", 1).toString(), "png" in png("x", 1).toString())
    }

    @Test
    fun concurrentLoadsShareOneDecode() = runTest {
        val decoder = FakeDecoder()
        val cache = ChatIconCache(decoder, maxBytes = 1_000, decodeContext = StandardTestDispatcher(testScheduler))
        val loads = List(3) { async(start = CoroutineStart.UNDISPATCHED) { cache.load(png("robot", 10)) } }
        advanceUntilIdle()
        assertEquals(listOf("robot", "robot", "robot"), loads.awaitAll())
        assertEquals(1, decoder.decoded.size)
    }

    @Test
    fun decodeFinishingAfterItsCallerLeftIsKept() = runTest {
        val decoder = FakeDecoder()
        val cache = ChatIconCache(decoder, maxBytes = 1_000, decodeContext = StandardTestDispatcher(testScheduler))
        val caller = launch(start = CoroutineStart.UNDISPATCHED) { cache.load(png("robot", 10)) }
        caller.cancel()
        runCurrent()
        advanceUntilIdle()
        assertEquals("robot", cache.cached(png("robot", 10).key))
        assertEquals(1, decoder.decoded.size)
        // A decode still out when the cache is cleared (unpair) is not kept.
        val late = launch(start = CoroutineStart.UNDISPATCHED) { cache.load(png("cat", 10)) }
        late.cancel()
        cache.clear()
        advanceUntilIdle()
        assertNull(cache.cached(png("cat", 10).key))
    }

    @Test
    fun atMostTwoDecodesRunAtOnce() {
        val executor = Executors.newFixedThreadPool(4)
        val running = AtomicInteger()
        val most = AtomicInteger()
        val twoStarted = CountDownLatch(2)
        val release = CountDownLatch(1)
        val decoder = ImageBytesDecoder { bytes ->
            most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            twoStarted.countDown()
            release.await(30, TimeUnit.SECONDS)
            running.decrementAndGet()
            DecodedImage(bytes.remaining().toString(), 1)
        }
        try {
            val cache = ChatIconCache(decoder, maxBytes = 1_000, decodeContext = executor.asCoroutineDispatcher())
            runBlocking(Dispatchers.Default) {
                val loads = List(4) { index -> async { cache.load(png("image$index", 1)) } }
                assertEquals(true, twoStarted.await(30, TimeUnit.SECONDS))
                release.countDown()
                loads.awaitAll()
            }
            assertEquals(2, most.get())
        } finally {
            executor.shutdown()
        }
    }
}
