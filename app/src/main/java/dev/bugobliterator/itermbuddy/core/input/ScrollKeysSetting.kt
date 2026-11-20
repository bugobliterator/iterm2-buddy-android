package dev.bugobliterator.itermbuddy.core.input

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext

/** The Settings switch's title for [ScrollKeysSetting]. */
const val SCROLL_KEYS_TITLE = "Scroll web pages with keys"

/** The Settings switch's summary for [ScrollKeysSetting]. */
const val SCROLL_KEYS_SUMMARY =
    "iTerm2 misplaces scroll events when the browser window isn't at the screen's top-left. " +
        "Keys scroll reliably, in coarser steps. Arrow keys go to the focused control, " +
        "so to scroll an inner panel, tap it first."

/** Where [ScrollKeysSetting] keeps the switch. */
interface ScrollKeysStore : SwitchStore

/** A [ScrollKeysStore] in memory only, for tests. Thread-safe. */
class InMemoryScrollKeysStore(value: Boolean? = null) : InMemorySwitchStore(value), ScrollKeysStore

/** The app's [ScrollKeysStore], beside the paste switch. */
class SharedPrefsScrollKeysStore(context: Context) : SharedPrefsSwitchStore(context, "browser-scroll-keys"), ScrollKeysStore

/**
 * The Settings switch "Scroll web pages with keys" ([SCROLL_KEYS_TITLE]): whether a drag or fling
 * on a browser page scrolls it with arrow and Page Up/Down keys through the page's key path
 * instead of `browserScroll` ([dev.bugobliterator.itermbuddy.core.stream.BrowserDriver]).
 * iTerm2 3.7.7 places a `browserScroll`'s wheel event off by the browser window's origin on
 * screen (`docs/reports/iterm2-companion-browser-scroll-offset.md`), so it can miss what is under
 * the finger, or the page. Owner decision 2026-11-20: on by default ([DEFAULT]).
 */
class ScrollKeysSetting(store: ScrollKeysStore, scope: CoroutineScope, ioContext: CoroutineContext) : StoredSwitch(store, DEFAULT, scope, ioContext) {
    companion object {
        const val DEFAULT = true
    }
}
