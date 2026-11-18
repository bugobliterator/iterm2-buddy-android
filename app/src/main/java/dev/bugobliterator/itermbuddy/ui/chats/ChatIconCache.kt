package dev.bugobliterator.itermbuddy.ui.chats

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * A chat's icon as a row holds it: [key], the SHA-256 of its bytes (computed once, off the main
 * thread, when the row model is built), and a read-only view of [bytes]. Equality is by [key]
 * only, so a row whose icon did not change is equal across list values. [toString] shows the
 * size only.
 */
class ChatIconRef(val key: String, val bytes: ByteBuffer) {
    override fun equals(other: Any?): Boolean = other is ChatIconRef && other.key == key

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "ChatIconRef(bytes=${bytes.remaining()})"
}

/** The [ChatIconRef] for an icon's [bytes] (read, not consumed), or null when there is none. */
fun chatIconRef(bytes: ByteBuffer?): ChatIconRef? {
    if (bytes == null || !bytes.hasRemaining()) return null
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(bytes.duplicate())
    return ChatIconRef(HexFormat.of().formatHex(digest.digest()), bytes.asReadOnlyBuffer())
}

/**
 * Chat icons (the small PNG the Mac generates for a chat), each decoded once per content: keyed
 * by [ChatIconRef.key], so the same icon in the next list value, or on another chat, is not
 * decoded again. [load] decodes on [decodeContext] (Default; never the caller's thread, so never
 * the main thread). Decoded images are kept least recently used first out under [maxBytes] of
 * decoded size (2 MiB by default, *Global Constraints*: about 32 icons decoded at 128 px). An
 * icon the decoder cannot read, or one larger than the whole budget, is remembered as such and
 * shows the default glyph: never an exception, never a second decode. Thread-safe. Nothing is
 * logged; [toString] shows counts only.
 */
class ChatIconCache<T : Any>(
    private val decoder: ImageBytesDecoder<T>,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val decodeContext: CoroutineContext = Dispatchers.Default,
) {
    private val lock = Any()

    /** Access-ordered: the first entry is the least recently used. */
    private val images = LinkedHashMap<String, DecodedImage<T>>(16, 0.75f, true)

    /** Content that decoded to nothing usable (corrupt, or over the budget alone). */
    private val unusable = HashSet<String>()

    private var held = 0L

    /** The decoded bytes held now. */
    val heldBytes: Long get() = synchronized(lock) { held }

    /** The icon already decoded for [key], or null; a map lookup, cheap enough for composition. */
    fun cached(key: String): T? = synchronized(lock) { images[key]?.image }

    /** The icon for [ref], decoding it on [decodeContext] when needed; null for the default glyph. */
    suspend fun load(ref: ChatIconRef): T? {
        synchronized(lock) {
            images[ref.key]?.let { return it.image }
            if (ref.key in unusable) return null
        }
        val decoded = withContext(decodeContext) {
            try {
                decoder.decode(ref.bytes.duplicate())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        synchronized(lock) {
            if (decoded == null || decoded.byteCount > maxBytes) {
                unusable += ref.key
                return null
            }
            images[ref.key]?.let { return it.image }
            images[ref.key] = decoded
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

    companion object {
        const val DEFAULT_MAX_BYTES: Int = 2 * 1024 * 1024
    }
}

/**
 * The app's decoder: `BitmapFactory`, sampled and then scaled so the longer side is at most
 * [targetSidePx] (the row's 44 dp in pixels; about 128): a huge PNG never becomes a huge bitmap,
 * and a 128 px ARGB icon is 64 KiB. Not unit-tested (Android only).
 */
class BitmapImageDecoder(private val targetSidePx: Int = 128) : ImageBytesDecoder<ImageBitmap> {
    override fun decode(bytes: ByteBuffer): DecodedImage<ImageBitmap>? {
        val array = ByteArray(bytes.remaining()).also { bytes.duplicate().get(it) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(array, 0, array.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= targetSidePx) sample *= 2
        val sampled = BitmapFactory.decodeByteArray(array, 0, array.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val longer = maxOf(sampled.width, sampled.height)
        val bitmap = if (longer <= targetSidePx) {
            sampled
        } else {
            val scale = targetSidePx.toFloat() / longer
            val scaled = sampled.scale(
                (sampled.width * scale).roundToInt().coerceAtLeast(1),
                (sampled.height * scale).roundToInt().coerceAtLeast(1),
            )
            if (scaled !== sampled) sampled.recycle()
            scaled
        }
        return DecodedImage(bitmap.asImageBitmap(), bitmap.allocationByteCount)
    }
}
