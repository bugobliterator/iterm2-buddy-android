package dev.bugobliterator.itermbuddy.core.chat

import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cost of decoding a whole `history` body, the way the chat consumer decodes one: UTF-8 bytes
 * to a string, the kotlinx tree, then [decodeChatMessages]. Asserted by peak heap (a child JVM with
 * a bounded heap) and by allocated bytes per input byte, never by retained heap or time.
 */
class HistoryDecodeMemoryTest {
    @Test
    fun capSizedHistoryDecodesInABoundedHeap() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java,
            "-Xmx$BOUNDED_HEAP_MEBIBYTES" + "m",
            "-XX:+UseSerialGC",
            "-cp",
            System.getProperty("java.class.path"),
            CapSizedHistoryDecode::class.java.name,
            CHAT_HISTORY_CAP_BYTES.toString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("the child JVM did not finish", process.waitFor(5, TimeUnit.MINUTES))
        assertEquals("child output: ${output.lines().filterNot { it.startsWith("Picked up") }}", 0, process.exitValue())
        assertTrue(output, output.contains(CapSizedHistoryDecode.DONE))
    }

    @Test
    fun decodeAllocationIsLinearInSize() {
        val small = historyBody(targetBytes = 1 shl 20)
        val large = historyBody(targetBytes = 4 shl 20)
        repeat(2) { decodeHistoryBody(small) } // warm up, so class loading and JIT do not count
        val smallBytes = allocatedBy { decodeHistoryBody(small) }
        val largeBytes = allocatedBy { decodeHistoryBody(large) }
        val smallPerByte = smallBytes.toDouble() / small.size
        val largePerByte = largeBytes.toDouble() / large.size
        assertTrue("allocation per input byte grows with size: $smallPerByte, $largePerByte", largePerByte < smallPerByte * 1.25)
        assertTrue("allocation per input byte is $largePerByte", largePerByte < MAX_ALLOCATED_PER_INPUT_BYTE)
    }

    private fun allocatedBy(block: () -> Unit): Long {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = threads.currentThreadAllocatedBytes
        block()
        return threads.currentThreadAllocatedBytes - before
    }

    companion object {
        /** The plan's chat history cap: a larger `history` is never parsed. */
        const val CHAT_HISTORY_CAP_BYTES = 8 shl 20

        /** Peak heap a cap-sized decode must fit in, input bytes included (the Pixel's app limit is 256 MiB). */
        const val BOUNDED_HEAP_MEBIBYTES = 96

        /** Bytes allocated per input byte by the whole decode, garbage included. */
        const val MAX_ALLOCATED_PER_INPUT_BYTE = 20.0

        /** The consumer's decode of a `history` body's `messages`. */
        fun decodeHistoryBody(body: ByteArray): List<ChatMessage> {
            val tree = Json.parseToJsonElement(body.decodeToString()).jsonObject
            return decodeChatMessages(tree.getValue("messages").jsonArray, AttachmentBudgets())
        }

        /**
         * A `history` body of at most, and close to, [targetBytes] in the wire's shape: mostly short replies (the
         * most tree overhead per byte), with long markdown, multipart code and user messages, in
         * non-Latin-1 text so strings are UTF-16.
         */
        fun historyBody(targetBytes: Int): ByteArray {
            val chatID = "6A1D3F5B-2C4E-4A8B-9D0F-1E2A3B4C5D6E"
            // Written straight into one buffer: the body is the only large thing alive when decoding starts.
            val buffer = ByteArray(targetBytes)
            var size = 0
            fun write(bytes: ByteArray) {
                bytes.copyInto(buffer, size)
                size += bytes.size
            }
            write("""{"chatID":"$chatID","maxSeq":1,"messages":[""".toByteArray())
            var index = 0
            while (true) {
                val uuid = "11111111-2222-4333-8444-%012d".format(index)
                val content = when (index % 10) {
                    0 -> """{"markdown":{"_0":"${"Résumé of the widget run → ok. ".repeat(40)}"}}"""
                    1 -> """{"multipart":{"_0":[{"markdown":{"_0":"Output — ✓"}},{"attachment":{"_0":{"id":"ci_$index",""" +
                        """"inline":true,"type":{"code":{"_0":"${"print('ünïcode') ".repeat(8)}"}}}}}]}}"""
                    2 -> """{"plainText":{"_0":"Ask @ptys_7K2M9Q4R8T1VH to run the tests — merci"}}"""
                    else -> """{"markdown":{"_0":"Step $index ✓"}}"""
                }
                val author = if (index % 10 == 2) "user" else "agent"
                val message = """{"author":"$author","chatID":"$chatID","content":$content,""" +
                    """"sentDate":${1795000000000L + index},"uniqueID":"$uuid"}"""
                val encoded = (if (index > 0) ",$message" else message).toByteArray(Charsets.UTF_8)
                if (size + encoded.size + 2 > targetBytes) break
                write(encoded)
                index++
            }
            write("]}".toByteArray())
            return buffer.copyOf(size)
        }
    }
}

/** Run in a child JVM with a bounded heap: builds a cap-sized history body and decodes it. */
object CapSizedHistoryDecode {
    const val DONE = "decoded"

    @JvmStatic
    fun main(args: Array<String>) {
        val body = HistoryDecodeMemoryTest.historyBody(args.single().toInt())
        val messages = HistoryDecodeMemoryTest.decodeHistoryBody(body)
        val cap = args.single().toInt()
        check(messages.isNotEmpty() && body.size <= cap && body.size > cap - 4096)
        println("$DONE ${messages.size} messages from ${body.size} bytes")
    }
}
