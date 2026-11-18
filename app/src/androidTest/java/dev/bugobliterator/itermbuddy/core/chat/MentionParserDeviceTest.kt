package dev.bugobliterator.itermbuddy.core.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The parser vectors (`fixtures/chat/mention-parser.json`, `cases` and `protect`) on the device,
 * where `java.util.regex` is ICU, as upstream's `NSRegularExpression` is: the JVM tests run the
 * same code on the JDK's engine, which differs in places (case folding, `^`, `\s`).
 */
@RunWith(AndroidJUnit4::class)
class MentionParserDeviceTest {
    private val vectors: JSONObject by lazy {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        JSONObject(assets.open("mention-parser.json").bufferedReader().use { it.readText() })
    }

    private fun section(name: String): List<JSONObject> {
        val array = vectors.getJSONArray(name)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Test
    fun parserMatchesUpstreamVectorsOnIcu() {
        for (case in section("cases")) {
            val name = case.getString("name")
            val expected = case.getJSONArray("mentions").let { array ->
                (0 until array.length()).map { array.getJSONObject(it) }.map {
                    val location = it.getInt("location")
                    Mention(location, location + it.getInt("length"), if (it.isNull("prefix")) null else it.getString("prefix"), it.getString("token"))
                }
            }
            assertEquals(name, expected, MentionParser.mentions(case.getString("text"), case.getBoolean("atSignOptional")))
        }
    }

    @Test
    fun protectMatchesUpstreamVectorsOnIcu() {
        for (case in section("protect")) {
            val name = case.getString("name")
            val mentions = case.getJSONArray("mentions").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
            val expected = mentions.filter { it.getBoolean("protected") }.map { it.getInt("location") }
            val found = MentionParser.protectedMentions(case.getString("text"), case.getBoolean("indentedCodeBlocks"))
            assertEquals(name, expected, found.map { it.start })
        }
    }
}
