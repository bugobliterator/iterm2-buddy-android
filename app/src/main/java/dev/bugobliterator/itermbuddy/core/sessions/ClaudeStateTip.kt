package dev.bugobliterator.itermbuddy.core.sessions

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** The text of the Sessions list's tip about Claude Code's state. */
const val CLAUDE_STATE_TIP =
    "To see Claude Code's state here, add \\(status) to your iTerm2 profile's Subtitle (Settings › Profiles › General)"

/** Where [ClaudeStateTip] keeps its dismissal. Holds one flag, no content. May do disk I/O: the tip calls it off the main thread. */
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
    private val appContext = context.applicationContext

    /** Opened on first use, on the tip's I/O coroutine, never while the app starts. */
    private val prefs by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

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
 * [shown] once [offer]ed (the first tree of this pairing that shows no status; not persisted, so a
 * cold start offers it again on its first such tree) and until it is dismissed: by the owner
 * ([dismiss]) or because a tree shows a Claude Code state ([statusSeen]); either is persisted in
 * [store]. [clear] (unpair) forgets both.
 *
 * **Store I/O.** Nothing touches [store] on the caller's thread: one coroutine in [scope] on
 * [ioContext] (the app passes `Dispatchers.IO`) reads the dismissal once and then writes every
 * change, in order. [shown] stays false until that read is done. A change made before it (a
 * dismissal, a clear) wins over the value read. Thread-safe.
 */
class ClaudeStateTip(private val store: ClaudeTipStore, scope: CoroutineScope, ioContext: CoroutineContext) {
    private val lock = Any()

    // Guarded by lock.
    private var loaded = false
    private var offered = false
    private var dismissed = false

    /** Whether [dismissed] was set here before the load, which then keeps it over the stored value. */
    private var changedBeforeLoad = false

    /** The dismissals to write, in order, after the load. */
    private val writes = Channel<Boolean>(Channel.UNLIMITED)

    private val shownFlow = MutableStateFlow(false)

    val shown: StateFlow<Boolean> = shownFlow.asStateFlow()

    init {
        scope.launch(ioContext) {
            val stored = store.dismissed()
            update {
                if (!changedBeforeLoad) dismissed = stored
                loaded = true
            }
            for (dismissed in writes) store.setDismissed(dismissed)
        }
    }

    /** A tree with no Claude Code state is up: offer the tip. */
    fun offer() = update { offered = true }

    /** A tree shows a Claude Code state: the owner has set it up; the tip goes for good. */
    fun statusSeen() = markDismissed()

    /** The owner dismissed the tip. */
    fun dismiss() = markDismissed()

    /** Unpair: the dismissal and the offer are forgotten. */
    fun clear() = update {
        offered = false
        setDismissed(false)
    }

    private fun markDismissed() = update { if (!dismissed) setDismissed(true) }

    /** Under the lock: records [value] and queues its write. */
    private fun setDismissed(value: Boolean) {
        dismissed = value
        if (!loaded) changedBeforeLoad = true
        writes.trySend(value)
    }

    private fun update(change: () -> Unit) {
        synchronized(lock) {
            change()
            shownFlow.value = loaded && offered && !dismissed
        }
    }

    override fun toString(): String = synchronized(lock) { "ClaudeStateTip(loaded=$loaded, offered=$offered, dismissed=$dismissed)" }
}
