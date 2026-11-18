package dev.bugobliterator.itermbuddy.ui.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer

/** Chat icons: decoded once per content, under a byte budget, a corrupt one shown as the default glyph. */
class ChatIconCacheTest {
    /** Decodes "png:<label>:<bytes>" into a label of that decoded size; anything else is corrupt. */
    private class FakeDecoder : ImageBytesDecoder<String> {
        val decoded = mutableListOf<String>()

        override fun decode(bytes: ByteBuffer): DecodedImage<String>? {
            val array = ByteArray(bytes.remaining()).also { bytes.duplicate().get(it) }
            val text = String(array)
            decoded += text
            val parts = text.split(":")
            if (parts.size != 3 || parts[0] != "png") return null
            if (parts[1] == "throws") throw IllegalArgumentException("bad image $text")
            return DecodedImage(parts[1], parts[2].toInt())
        }
    }

    private fun png(label: String, size: Int): ByteBuffer = ByteBuffer.wrap("png:$label:$size".toByteArray()).asReadOnlyBuffer()

    @Test
    fun decodesOncePerContent() {
        val decoder = FakeDecoder()
        val cache = ChatIconCache(decoder, maxBytes = 1_000)
        val first = cache.icon(png("robot", 100))
        // A different buffer with the same bytes (the next list value) is the same icon.
        assertSame(first, cache.icon(png("robot", 100)))
        assertEquals("robot", first)
        assertEquals(1, decoder.decoded.size)
        // Other content decodes on its own.
        assertEquals("cat", cache.icon(png("cat", 100)))
        assertEquals(2, decoder.decoded.size)
        // No icon: the default glyph, nothing decoded.
        assertNull(cache.icon(null))
        assertEquals(2, decoder.decoded.size)
        // Reading did not consume the caller's buffer.
        val buffer = png("robot", 100)
        cache.icon(buffer)
        assertEquals(0, buffer.position())
    }

    @Test
    fun evictsOverBudget() {
        val decoder = FakeDecoder()
        val cache = ChatIconCache(decoder, maxBytes = 100)
        cache.icon(png("a", 40))
        cache.icon(png("b", 40))
        cache.icon(png("a", 40)) // a is now the most recently used
        cache.icon(png("c", 40)) // over 100: b, the least recently used, goes
        assertEquals(3, decoder.decoded.size)
        assertEquals(80, cache.heldBytes)
        cache.icon(png("a", 40))
        cache.icon(png("c", 40))
        assertEquals(3, decoder.decoded.size)
        cache.icon(png("b", 40))
        assertEquals(4, decoder.decoded.size)
        assertEquals(80, cache.heldBytes)

        // An icon bigger than the whole budget is not kept, shows the default glyph, and is not decoded again.
        assertNull(cache.icon(png("huge", 101)))
        assertNull(cache.icon(png("huge", 101)))
        assertEquals(5, decoder.decoded.size)
        assertEquals(80, cache.heldBytes)

        cache.clear()
        assertEquals(0, cache.heldBytes)
        cache.icon(png("a", 40))
        assertEquals(6, decoder.decoded.size)
    }

    @Test
    fun corruptPngShowsDefaultIcon() {
        val decoder = FakeDecoder()
        val cache = ChatIconCache(decoder, maxBytes = 1_000)
        assertNull(cache.icon(ByteBuffer.wrap("not a png".toByteArray())))
        assertNull(cache.icon(ByteBuffer.wrap("not a png".toByteArray())))
        // A decoder that throws is a corrupt icon too, never an exception.
        assertNull(cache.icon(png("throws", 10)))
        assertNull(cache.icon(png("throws", 10)))
        // Each corrupt content was tried once.
        assertEquals(2, decoder.decoded.size)
        assertEquals(0, cache.heldBytes)
        assertFalse(cache.toString(), "png" in cache.toString())
    }
}
