package dev.bugobliterator.itermbuddy.applog

import dev.bugobliterator.itermbuddy.core.input.InputTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyTraceTest {
    @Test
    fun theGateIsTheSwitchOrTheAdbProperty() {
        for (switch in listOf(false, true)) {
            for (prop in listOf(false, true)) {
                assertEquals("switch=$switch prop=$prop", switch || prop, keyTraceGate({ switch }, { prop })())
            }
        }
    }

    @Test
    fun aThrowingPropertyReadCountsAsOff() {
        assertFalse(keyTraceGate({ false }, { throw RuntimeException("Method not mocked") })())
        assertTrue(keyTraceGate({ true }, { throw RuntimeException("Method not mocked") })())
    }

    @Test
    fun theSwitchStartsOffAndReportsEachChangeOnce() {
        val changes = mutableListOf<Boolean>()
        val switch = KeyTraceSwitch { changes += it }
        assertFalse(switch.enabled.value)
        switch.set(true)
        switch.set(true)
        switch.set(false)
        assertEquals(listOf(true, false), changes)
        // In memory only: a new switch (a new process) is off again.
        assertFalse(KeyTraceSwitch().enabled.value)
    }

    @Test
    fun turningTheSwitchOnTracesTheNextLineWithoutWaitingASecond() {
        val lines = mutableListOf<String>()
        lateinit var trace: InputTrace
        val switch = KeyTraceSwitch { trace.recheck() }
        trace = InputTrace(gate = keyTraceGate({ switch.enabled.value }, { false }), nowMillis = { 0L }, sink = { lines += it })
        trace.log { "before" }
        switch.set(true)
        trace.log { "on" }
        switch.set(false)
        trace.log { "off" }
        assertEquals(listOf("on"), lines)
    }
}
