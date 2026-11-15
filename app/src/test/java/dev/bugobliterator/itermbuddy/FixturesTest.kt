package dev.bugobliterator.itermbuddy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

class FixturesTest {
    @Test
    fun cryptoVectorsAreReadable() {
        val vectors = Json.parseToJsonElement(Fixtures.read("crypto-vectors.json")).jsonObject
        val roomNames = vectors.getValue("roomNames").jsonArray
        assertTrue("roomNames is empty", roomNames.isNotEmpty())
    }
}
