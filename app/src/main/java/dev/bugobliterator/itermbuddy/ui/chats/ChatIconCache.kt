package dev.bugobliterator.itermbuddy.ui.chats

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer
import java.security.MessageDigest

/** A decoded image and the bytes it holds in memory (not its encoded size). */
class DecodedImage<out T : Any>(val image: T, val byteCount: Int) {
    override fun toString(): String = "DecodedImage(byteCount=$byteCount)"
}

/**
 * Decodes encoded image bytes (a PNG, a JPEG) into something the UI can draw; null when they are
 * not an image it can read. Injected, so the rules that use it run on the JVM: the app passes
 * [BitmapImageDecoder] (`BitmapFactory` is not on the JVM), tests a fake. It must not keep or
 * change [bytes] and may throw on corrupt input.
 */
fun interface ImageBytesDecoder<out T : Any> {
    fun decode(bytes: ByteBuffer): DecodedImage<T>?
}

/**
 * Chat icons (the small PNG the Mac generates for a chat), each decoded once per content: keyed
 * by the SHA-256 of the bytes, so the same icon in the next list value, or on another chat, is
 * not decoded again. Decoded images are kept least recently used first out under [maxBytes] of
 * decoded size (2 MiB by default, *Global Constraints*). An icon the decoder cannot read, or one
 * larger than the whole budget, is remembered as such (a hash, no image) and shows the default
 * glyph: never an exception, never a second decode. Thread-safe. Nothing is logged; [toString]
 * shows counts only.
 */
class ChatIconCache<T : Any>(private val decoder: ImageBytesDecoder<T>, private val maxBytes: Int = DEFAULT_MAX_BYTES) {
    private val lock = Any()

    /** Access-ordered: the first entry is the least recently used. */
    private val images = LinkedHashMap<String, DecodedImage<T>>(16, 0.75f, true)

    /** Content that decoded to nothing usable (corrupt, or over the budget alone). */
    private val unusable = HashSet<String>()

    private var held = 0L

    /** The decoded bytes held now. */
    val heldBytes: Long get() = synchronized(lock) { held }

    /** The icon for [bytes] (read, never consumed or kept), or null for the default glyph. */
    fun icon(bytes: ByteBuffer?): T? {
        if (bytes == null || !bytes.hasRemaining()) return null
        val key = contentKey(bytes)
        synchronized(lock) {
            images[key]?.let { return it.image }
            if (key in unusable) return null
        }
        val decoded = try {
            decoder.decode(bytes.duplicate())
        } catch (_: Exception) {
            null
        }
        synchronized(lock) {
            if (decoded == null || decoded.byteCount > maxBytes) {
                unusable += key
                return null
            }
            images[key]?.let { return it.image }
            images[key] = decoded
            held += decoded.byteCount
            val eldest = images.entries.iterator()
            while (held > maxBytes && eldest.hasNext()) {
                val entry = eldest.next()
                held -= entry.value.byteCount
                eldest.remove()
            }
            return decoded.image
        }
    }

    /** Drops every icon (unpair). */
    fun clear() = synchronized(lock) {
        images.clear()
        unusable.clear()
        held = 0
    }

    override fun toString(): String = synchronized(lock) {
        "ChatIconCache(images=${images.size}, unusable=${unusable.size}, heldBytes=$held, maxBytes=$maxBytes)"
    }

    private fun contentKey(bytes: ByteBuffer): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes.duplicate())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Int = 2 * 1024 * 1024
    }
}

/**
 * The app's decoder: `BitmapFactory`, sampled down so neither side is over [maxSidePx] (an icon
 * shows at 44 dp; a huge PNG never becomes a huge bitmap). Not unit-tested (Android only).
 */
class BitmapImageDecoder(private val maxSidePx: Int = 256) : ImageBytesDecoder<ImageBitmap> {
    override fun decode(bytes: ByteBuffer): DecodedImage<ImageBitmap>? {
        val array = ByteArray(bytes.remaining()).also { bytes.duplicate().get(it) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(array, 0, array.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxSidePx || bounds.outHeight / sample > maxSidePx) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(array, 0, array.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return DecodedImage(bitmap.asImageBitmap(), bitmap.allocationByteCount)
    }
}
