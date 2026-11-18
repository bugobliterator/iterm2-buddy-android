package dev.bugobliterator.itermbuddy.ui.chats

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** The chat row's stamp, iOS's rules (`IOS/Screens/HomeView.swift:134-145`). */
class ChatTimestampsTest {
    private val now = Instant.parse("2026-11-18T15:00:00Z").toEpochMilli() // a Wednesday
    private val utc: ZoneId = ZoneOffset.UTC

    private fun stamp(at: String, zone: ZoneId = utc, locale: Locale = Locale.US, use24Hour: Boolean? = null) =
        // JDKs from 20 put a narrow no-break space before "PM"; compare with a plain space.
        chatTimestamp(Instant.parse(at).toEpochMilli().toDouble(), now, zone, locale, use24Hour).replace(' ', ' ')

    @Test
    fun todayWeekAndOlder() {
        // Within 24 hours: the time of day.
        assertEquals("1:05 PM", stamp("2026-11-18T13:05:00Z"))
        assertEquals("3:00 PM", stamp("2026-11-17T15:00:00.001Z"))
        assertEquals("13:05", stamp("2026-11-18T13:05:00Z", use24Hour = true))
        assertEquals("1:05 PM", stamp("2026-11-18T13:05:00Z", use24Hour = false))
        // In the device's zone.
        assertEquals("22:05", stamp("2026-11-18T13:05:00Z", zone = ZoneId.of("Asia/Tokyo"), use24Hour = true))
        // A Mac clock ahead of the phone's still reads as today.
        assertEquals("3:30 PM", stamp("2026-11-18T15:30:00Z"))

        // From 24 hours to a week: the weekday.
        assertEquals("Tuesday", stamp("2026-11-17T15:00:00Z"))
        assertEquals("Wednesday", stamp("2026-11-11T15:00:00.001Z"))
        assertEquals("Dienstag", stamp("2026-11-17T09:00:00Z", locale = Locale.GERMANY))

        // A week or more: a short numeric date, unpadded, two-digit year, in the locale's order.
        assertEquals("11/11/26", stamp("2026-11-11T15:00:00Z"))
        assertEquals("11/4/26", stamp("2026-11-04T12:00:00Z"))
        assertEquals("4/11/26", stamp("2026-11-04T12:00:00Z", locale = Locale.UK))
        assertEquals("4.11.26", stamp("2026-11-04T12:00:00Z", locale = Locale.GERMANY))
        assertEquals("1/2/24", stamp("2024-01-02T12:00:00Z"))
    }

    @Test
    fun refreshAlignsToTheNextMinute() {
        assertEquals(60_000L, nextStampRefreshDelay(now, 60_000))
        assertEquals(59_999L, nextStampRefreshDelay(now + 1, 60_000))
        assertEquals(1L, nextStampRefreshDelay(now + 59_999, 60_000))
        assertEquals(500L, nextStampRefreshDelay(1_500, 1_000))
    }
}
