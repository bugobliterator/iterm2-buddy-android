package dev.bugobliterator.itermbuddy.core.sessions

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The text of the Sessions list's tip about Claude Code's state. */
const val CLAUDE_STATE_TIP =
    "To see Claude Code's state here, add \\(status) to your iTerm2 profile's Subtitle (Settings › Profiles › General)"

/** Where [ClaudeStateTip] keeps its dismissal. Holds one flag, no content. */
interface ClaudeTipStore {
    fun dismissed(): Boolean

    fun setDismissed(dismissed: Boolean)
}

/** A [ClaudeTipStore] in memory only, for tests. Thread-safe. */
class InMemoryClaudeTipStore : ClaudeTipStore {
    @Volatile private var dismissed = false

    override fun dismissed(): Boolean = dismissed

    override fun setDismissed(dismissed: Boolean) {
        this.dismissed = dismissed
    }
}

/**
 * The app's [ClaudeTipStore]: one boolean in the private SharedPreferences file [PREFS_NAME],
 * written with `apply()` (off the caller's thread). Not backed up apart from the app's own rules.
 */
class SharedPrefsClaudeTipStore(context: Context) : ClaudeTipStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun dismissed(): Boolean = prefs.getBoolean(KEY_DISMISSED, false)

    override fun setDismissed(dismissed: Boolean) {
        prefs.edit { putBoolean(KEY_DISMISSED, dismissed) }
    }

    private companion object {
        const val PREFS_NAME = "sessions-tips"
        const val KEY_DISMISSED = "claude-state-tip-dismissed"
    }
}

/**
 * Whether the Sessions list shows its one-line tip [CLAUDE_STATE_TIP]: a row shows Claude Code's
 * state only once the owner adds `\(status)` to the profile's Subtitle, and until then there is
 * nothing on the rows to say so.
 *
 * [shown] once the app has connected in this pairing ([connected]; not persisted, so a cold start
 * shows it after its first connect) and until it is dismissed: by the owner ([dismiss]) or because
 * a tree already shows a Claude Code state ([statusSeen]); either is persisted in [store]. [clear]
 * (unpair) forgets both. Thread-safe.
 */
class ClaudeStateTip(private val store: ClaudeTipStore) {
    private val lock = Any()

    // Guarded by lock.
    private var connectedOnce = false
    private var dismissed = store.dismissed()

    private val shownFlow = MutableStateFlow(false)

    val shown: StateFlow<Boolean> = shownFlow.asStateFlow()

    /** The client connected. */
    fun connected() = update { connectedOnce = true }

    /** A tree shows a Claude Code state: the owner has set it up; the tip goes for good. */
    fun statusSeen() = markDismissed()

    /** The owner dismissed the tip. */
    fun dismiss() = markDismissed()

    /** Unpair: the dismissal and the connect are forgotten. */
    fun clear() = update {
        connectedOnce = false
        dismissed = false
        store.setDismissed(false)
    }

    private fun markDismissed() = update {
        if (!dismissed) {
            dismissed = true
            store.setDismissed(true)
        }
    }

    private fun update(change: () -> Unit) {
        synchronized(lock) {
            change()
            shownFlow.value = connectedOnce && !dismissed
        }
    }

    override fun toString(): String = synchronized(lock) { "ClaudeStateTip(connectedOnce=$connectedOnce, dismissed=$dismissed)" }
}
