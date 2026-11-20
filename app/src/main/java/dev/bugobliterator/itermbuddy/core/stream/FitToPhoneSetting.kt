package dev.bugobliterator.itermbuddy.core.stream

import android.content.Context
import dev.bugobliterator.itermbuddy.core.input.InMemorySwitchStore
import dev.bugobliterator.itermbuddy.core.input.SharedPrefsSwitchStore
import dev.bugobliterator.itermbuddy.core.input.StoredSwitch
import dev.bugobliterator.itermbuddy.core.input.SwitchStore
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext

/** Where [FitToPhoneSetting] keeps the toggle. */
interface FitToPhoneStore : SwitchStore

/** A [FitToPhoneStore] in memory only, for tests. Thread-safe. */
class InMemoryFitToPhoneStore(value: Boolean? = null) : InMemorySwitchStore(value), FitToPhoneStore

/** The app's [FitToPhoneStore], beside the input switches. */
class SharedPrefsFitToPhoneStore(context: Context) : SharedPrefsSwitchStore(context, "fit-to-phone"), FitToPhoneStore

/**
 * The live bar's ⤢ **Fit to phone** toggle: while on, [AutoFitter] fits every live terminal
 * session's grid to the phone as its view opens, and again after a rotation or a material viewport
 * change. One global value for every session, persisted, a [StoredSwitch] (read and written off the
 * main thread). Owner decision 2026-11-20: off by default ([DEFAULT], the earlier behaviour of
 * never resizing on its own); like every per-owner setting it goes back to off on unpair.
 */
class FitToPhoneSetting(store: FitToPhoneStore, scope: CoroutineScope, ioContext: CoroutineContext) : StoredSwitch(store, DEFAULT, scope, ioContext) {
    companion object {
        const val DEFAULT = false
    }
}
