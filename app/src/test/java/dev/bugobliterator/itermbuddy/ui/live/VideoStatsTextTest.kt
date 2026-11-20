package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.video.LowLatencyState
import dev.bugobliterator.itermbuddy.video.VideoStats
import org.junit.Assert.assertEquals
import org.junit.Test

/** The debug overlay's decoder lines ([videoStatsText]). */
class VideoStatsTextTest {
    private val name = "c2.exynos.hevc.decoder"

    @Test
    fun showsTheLowLatencyStateAndTheLastAndMedianLatency() {
        val stats = VideoStats(name, 1496, 1496, 112, medianLatencyMillis = 108, lowLatency = LowLatencyState.ON)
        assertEquals("$name · LL on\nin 1496 · out 1496 · latency 112 ms · median 108 ms", videoStatsText(stats))
        assertEquals(
            "$name · LL fallback\nin 1 · out 1 · latency 5 ms · median 5 ms",
            videoStatsText(VideoStats(name, 1, 1, 5, medianLatencyMillis = 5, lowLatency = LowLatencyState.FALLBACK)),
        )
        assertEquals(
            "$name · LL off\nin 1 · out 0 · latency — · median —",
            videoStatsText(VideoStats(name, 1, 0, null, lowLatency = LowLatencyState.OFF)),
        )
    }

    @Test
    fun noDecoderShowsNoLowLatencyState() {
        assertEquals("no decoder\nin 0 · out 0 · latency — · median —", videoStatsText(VideoStats(null, 0, 0, null)))
        assertEquals(
            "no decoder\nin 9 · out 9 · latency 5 ms · median 6 ms",
            videoStatsText(VideoStats(null, 9, 9, 5, medianLatencyMillis = 6, lowLatency = LowLatencyState.ON)),
        )
    }
}
