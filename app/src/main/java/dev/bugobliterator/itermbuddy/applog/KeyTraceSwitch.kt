package dev.bugobliterator.itermbuddy.applog

import dev.bugobliterator.itermbuddy.core.input.InputTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Settings switch's title for [KeyTraceSwitch]. */
const val KEY_TRACE_TITLE = "Include typed-key trace"

/** The Settings switch's note for [KeyTraceSwitch]. */
const val KEY_TRACE_SUMMARY =
    "Records each character you type (as code points) in the log, to debug keys that go wrong. " +
        "Turns itself off when the app restarts. Traced keys stay in the log until you clear it."

/** Asked when the owner turns the key trace off: [KEY_TRACE_CLEAR_NOW] or [KEY_TRACE_KEEP]. */
const val KEY_TRACE_OFF_PROMPT = "The log still holds the keys typed while the trace was on. Clear the log now?"

const val KEY_TRACE_CLEAR_NOW = "Clear now"

const val KEY_TRACE_KEEP = "Keep"

/**
 * The Settings switch "Include typed-key trace" ([KEY_TRACE_TITLE]): while on, the input trace
 * (`IB.Input`, [InputTrace]) writes to the log file (not logcat, [keyTraceSink]). Owner
 * decision: off by default, and in memory only, so it is off again whenever the process starts.
 * [onChange] runs after each change (the app re-reads the trace's gate at once).
 */
class KeyTraceSwitch(private val onChange: (Boolean) -> Unit = {}) {
    private val enabledFlow = MutableStateFlow(false)

    val enabled: StateFlow<Boolean> = enabledFlow.asStateFlow()

    fun set(enabled: Boolean) {
        if (enabledFlow.value == enabled) return
        enabledFlow.value = enabled
        onChange(enabled)
    }
}

/**
 * The input trace's gate: on while the in-app switch is ([switch]) or the adb property is set
 * ([adbVerbose]: `Log.isLoggable("IB.Input", VERBOSE)`, `adb shell setprop log.tag.IB.Input
 * VERBOSE`). Either turns it on; a throwing property read counts as off.
 */
fun keyTraceGate(switch: () -> Boolean, adbVerbose: () -> Boolean): () -> Boolean = {
    switch() || try {
        adbVerbose()
    } catch (_: RuntimeException) {
        false
    }
}

/**
 * Where the input trace's lines go: always the log file; logcat too only while the adb property
 * is set ([adbVerbose]), so the in-app switch alone keeps typed keys out of logcat. A throwing
 * property read counts as not set.
 */
fun keyTraceSink(adbVerbose: () -> Boolean, logger: () -> AppLogger): (String) -> Unit = { line ->
    val toLogcat = try {
        adbVerbose()
    } catch (_: RuntimeException) {
        false
    }
    logger().log(LogLevel.VERBOSE, InputTrace.TAG, line, toLogcat = toLogcat)
}
