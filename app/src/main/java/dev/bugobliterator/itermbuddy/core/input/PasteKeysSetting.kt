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
 * Where [PasteKeysSetting] keeps the switch. Holds one flag, no content. May do disk I/O: the
 * setting calls it off the main thread.
 */
interface PasteKeysStore {
    /** The stored value; null when never set (the setting is then [PasteKeysSetting.DEFAULT]). */
    fun enabled(): Boolean?

    /** Stores [enabled]; null forgets it. */
    fun setEnabled(enabled: Boolean?)
}

/** A [PasteKeysStore] in memory only, for tests. Thread-safe. */
class InMemoryPasteKeysStore(@Volatile private var value: Boolean? = null) : PasteKeysStore {
    override fun enabled(): Boolean? = value

    override fun setEnabled(enabled: Boolean?) {
        value = enabled
    }
}

/**
 * The app's [PasteKeysStore]: one boolean in the private SharedPreferences file [PREFS_NAME],
 * written with `apply()` (off the caller's thread); absent when never set.
 */
class SharedPrefsPasteKeysStore(context: Context) : PasteKeysStore {
    private val appContext = context.applicationContext

    /** Opened on first use, on the setting's I/O coroutine, never while the app starts. */
    private val prefs by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override fun enabled(): Boolean? = if (prefs.contains(KEY_ENABLED)) prefs.getBoolean(KEY_ENABLED, PasteKeysSetting.DEFAULT) else null

    override fun setEnabled(enabled: Boolean?) {
        prefs.edit { if (enabled == null) remove(KEY_ENABLED) else putBoolean(KEY_ENABLED, enabled) }
    }

    private companion object {
        const val PREFS_NAME = "input-settings"
        const val KEY_ENABLED = "paste-keys"
    }
}

/**
 * The Settings switch "Send / * + = as paste" ([PASTE_KEYS_TITLE]): whether [InputSender] sends
 * [PASTED_KEYS] typed with no modifiers as pastes. Owner decision 2026-11-20: on by default
 * ([DEFAULT]).
 *
 * [enabled] is [DEFAULT] until the stored value is read, then the stored value, then each [set].
 * [clear] (unpair, as every other per-owner setting) forgets it: back to [DEFAULT].
 *
 * **Store I/O.** Nothing touches [store] on the caller's thread: one coroutine in [scope] on
 * [ioContext] (the app passes `Dispatchers.IO`) reads the value once and then writes every
 * change, in order. A change made before the read wins over the value read. Thread-safe.
 */
class PasteKeysSetting(private val store: PasteKeysStore, scope: CoroutineScope, ioContext: CoroutineContext) {
    private val lock = Any()

    // Guarded by lock.
    private var loaded = false
    private var changedBeforeLoad = false

    /** The values to write, in order, after the load; null forgets the stored value. */
    private val writes = Channel<Boolean?>(Channel.UNLIMITED)

    private val enabledFlow = MutableStateFlow(DEFAULT)

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

    /** Unpair: the switch goes back to [DEFAULT] and the stored value is forgotten. */
    fun clear() = change(DEFAULT, null)

    private fun change(value: Boolean, stored: Boolean?) {
        synchronized(lock) {
            enabledFlow.value = value
            if (!loaded) changedBeforeLoad = true
            writes.trySend(stored)
        }
    }

    override fun toString(): String = synchronized(lock) { "PasteKeysSetting(enabled=${enabledFlow.value}, loaded=$loaded)" }

    companion object {
        /** On: Claude Code, the app this is for, is where the four keys go wrong. */
        const val DEFAULT = true
    }
}
