package dev.bugobliterator.itermbuddy.core.wire

import java.lang.management.ManagementFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The peak cost of decoding a cap-sized `history` frame through [decodeEnvelope]. Run only by the
 * `chatMemoryTest` task, whose JVM has a 128 MiB heap, so a decode whose peak does not fit fails
 * with an OutOfMemoryError. Asserts peak heap and allocated bytes, never retained heap or time.
 */
class HistoryMemoryTest {
    @Test
    fun capSizedHistoryDecodesInABoundedHeap() {
        val maxHeap = Runtime.getRuntime().maxMemory()
        assertTrue("run by chatMemoryTest with a bounded heap, not $maxHeap bytes", maxHeap <= BOUNDED_HEAP_BYTES)
        val frame = historyFrame(MAX_HISTORY_BYTES, MESSAGES)
        assertEquals(MAX_HISTORY_BYTES, frame.size)
        val (requestID, message) = decodeEnvelope(frame)
        assertEquals(12L, requestID)
        val history = message as MacMessage.History
        assertEquals(MESSAGES, history.messages.size)
        assertEquals(MESSAGES - 1L, history.maxSeq)
    }

    @Test
    fun decodeAllocationIsLinearInSize() {
        val small = historyFrame(MAX_HISTORY_BYTES / 8, MESSAGES / 8)
        repeat(3) { decodeEnvelope(small) } // warm up, so class loading and JIT do not count
        val smallPerByte = allocatedBy { decodeEnvelope(small) }.toDouble() / small.size
        val large = historyFrame(MAX_HISTORY_BYTES, MESSAGES)
        val largePerByte = allocatedBy { decodeEnvelope(large) }.toDouble() / large.size
        assertTrue(
            "allocated bytes per input byte: $smallPerByte at 1 MiB, $largePerByte at 8 MiB",
            largePerByte < smallPerByte * 1.25 && smallPerByte < largePerByte * 1.25,
        )
    }

    private fun allocatedBy(block: () -> Unit): Long {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = threads.currentThreadAllocatedBytes
        block()
        return threads.currentThreadAllocatedBytes - before
    }

    companion object {
        /** The plan's chat history cap (`maxHistoryBytes`): a larger `history` is never parsed. */
        const val MAX_HISTORY_BYTES = 8 shl 20

        const val MESSAGES = 20_000

        /** `chatMemoryTest`'s `maxHeapSize`, with room for the JVM's rounding. */
        const val BOUNDED_HEAP_BYTES = 136L shl 20

        /**
         * A `history` envelope of exactly [sizeBytes] holding [messages] messages in the wire's shape:
         * markdown replies, multipart code and user plain text, each with one non-Latin-1 character so
         * the decoded text is UTF-16, padded with ASCII to the exact size.
         */
        fun historyFrame(sizeBytes: Int, messages: Int): ByteArray {
            val chatID = "6A1D3F5B-2C4E-4A8B-9D0F-1E2A3B4C5D6E"
            val head = """{"payload":{"history":{"chatID":"$chatID","maxSeq":${messages - 1},"messages":["""
            val tail = "]}},\"requestID\":12}"
            fun message(index: Int, padding: Int): String {
                val text = "✓ " + "x".repeat(padding)
                val content = when (index % 3) {
                    0 -> """{"markdown":{"_0":"$text"}}"""
                    1 -> """{"multipart":{"_0":[{"markdown":{"_0":"Output"}},{"attachment":{"_0":{"id":"ci_$index",""" +
                        """"inline":true,"type":{"code":{"_0":"$text"}}}}}]}}"""
                    else -> """{"plainText":{"_0":"$text"}}"""
                }
                val author = if (index % 3 == 2) "user" else "agent"
                return """{"author":"$author","chatID":"$chatID","content":$content,""" +
                    """"sentDate":${1795000000000L + index},"uniqueID":"11111111-2222-4333-8444-%012d"}""".format(index)
            }
            val unpadded = head.length + tail.length + (messages - 1) +
                (0 until messages).sumOf { message(it, 0).toByteArray(Charsets.UTF_8).size }
            val spare = sizeBytes - unpadded
            require(spare >= 0) { "$messages messages do not fit in $sizeBytes bytes" }
            val frame = StringBuilder(sizeBytes)
            frame.append(head)
            for (index in 0 until messages) {
                if (index > 0) frame.append(',')
                frame.append(message(index, spare / messages + if (index < spare % messages) 1 else 0))
            }
            frame.append(tail)
            return frame.toString().toByteArray(Charsets.UTF_8)
        }
    }
}
