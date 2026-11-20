package dev.bugobliterator.itermbuddy.core.input

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** The Settings switch's title for [PasteKeysSetting]. */
const val PASTE_KEYS_TITLE = "Send / * + = as paste"

/** The Settings switch's summary for [PasteKeysSetting]. */
const val PASTE_KEYS_SUMMARY =
    "iTerm2 garbles these keys in apps that use the enhanced keyboard mode, such as Claude Code. " +
        "Turn off if an app needs them as keys (for example / to search in vim)."

/**
 * Where a [StoredSwitch] keeps its flag. Holds one flag, no content. May do disk I/O: the switch
 * calls it off the main thread.
 */
interface SwitchStore {
    /** The stored value; null when never set (the switch is then at its default). */
    fun enabled(): Boolean?

    /** Stores [enabled]; null forgets it. */
    fun setEnabled(enabled: Boolean?)
}

/** Where [PasteKeysSetting] keeps the switch. */
interface PasteKeysStore : SwitchStore

/** A [SwitchStore] in memory only, for tests. Thread-safe. */
open class InMemorySwitchStore(@Volatile private var value: Boolean? = null) : SwitchStore {
    override fun enabled(): Boolean? = value

    override fun setEnabled(enabled: Boolean?) {
        value = enabled
    }
}

/** A [PasteKeysStore] in memory only, for tests. Thread-safe. */
class InMemoryPasteKeysStore(value: Boolean? = null) : InMemorySwitchStore(value), PasteKeysStore

/**
 * A switch's flag in the app's private SharedPreferences file [PREFS_NAME] under [key], written
 * with `apply()` (off the caller's thread); absent when never set.
 */
open class SharedPrefsSwitchStore(context: Context, private val key: String) : SwitchStore {
    private val appContext = context.applicationContext

    /** Opened on first use, on the switch's I/O coroutine, never while the app starts. */
    private val prefs by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override fun enabled(): Boolean? = if (prefs.contains(key)) prefs.getBoolean(key, false) else null

    override fun setEnabled(enabled: Boolean?) {
        prefs.edit { if (enabled == null) remove(key) else putBoolean(key, enabled) }
    }

    private companion object {
        const val PREFS_NAME = "input-settings"
    }
}

/** The app's [PasteKeysStore]. */
class SharedPrefsPasteKeysStore(context: Context) : SharedPrefsSwitchStore(context, "paste-keys"), PasteKeysStore

/**
 * An owner's Settings switch kept in a [SwitchStore]: [enabled] is [default] until the stored
 * value is read, then the stored value, then each [set]. [clear] (unpair, as every other
 * per-owner setting) forgets it: back to [default].
 *
 * **Store I/O.** Nothing touches [store] on the caller's thread: one coroutine in [scope] on
 * [ioContext] (the app passes `Dispatchers.IO`) reads the value once and then writes every
 * change, in order. A change made before the read wins over the value read. Thread-safe.
 */
open class StoredSwitch(private val store: SwitchStore, private val default: Boolean, scope: CoroutineScope, ioContext: CoroutineContext) {
    private val lock = Any()

    // Guarded by lock.
    private var loaded = false
    private var changedBeforeLoad = false

    /** The values to write, in order, after the load; null forgets the stored value. */
    private val writes = Channel<Boolean?>(Channel.UNLIMITED)

    private val enabledFlow = MutableStateFlow(default)

    val enabled: StateFlow<Boolean> = enabledFlow.asStateFlow()

    init {
        scope.launch(ioContext) {
            val stored = store.enabled()
            synchronized(lock) {
                if (!changedBeforeLoad && stored != null) enabledFlow.value = stored
                loaded = true
            }
            for (value in writes) store.setEnabled(value)
        }
    }

    /** The owner flipped the switch. */
    fun set(enabled: Boolean) = change(enabled, enabled)

    /** Unpair: the switch goes back to its default and the stored value is forgotten. */
    fun clear() = change(default, null)

    private fun change(value: Boolean, stored: Boolean?) {
        synchronized(lock) {
            enabledFlow.value = value
            if (!loaded) changedBeforeLoad = true
            writes.trySend(stored)
        }
    }

    override fun toString(): String = synchronized(lock) { "${javaClass.simpleName}(enabled=${enabledFlow.value}, loaded=$loaded)" }
}

/**
 * The Settings switch "Send / * + = as paste" ([PASTE_KEYS_TITLE]): whether [InputSender] sends
 * [PASTED_KEYS] typed with no modifiers as pastes. Owner decision 2026-11-20: on by default
 * ([DEFAULT]). Kept as a [StoredSwitch].
 */
class PasteKeysSetting(store: PasteKeysStore, scope: CoroutineScope, ioContext: CoroutineContext) : StoredSwitch(store, DEFAULT, scope, ioContext) {
    companion object {
        /** On: Claude Code, the app this is for, is where the four keys go wrong. */
        const val DEFAULT = true
    }
}
