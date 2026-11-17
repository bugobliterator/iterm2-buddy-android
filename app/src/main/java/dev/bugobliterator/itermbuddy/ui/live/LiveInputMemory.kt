package dev.bugobliterator.itermbuddy.ui.live

import dev.bugobliterator.itermbuddy.core.input.StickyModifiers

/**
 * One session's keyboard state that must outlive the live view's composition (rotation recreates
 * the activity): the sticky modifiers (an armed ctrl stays armed), whether the key bar's tray is
 * expanded, and whether the keyboard should come back when input does. Main thread only.
 */
class LiveInputMemory {
    /** The view sets [StickyModifiers.onChange] to redraw its key bar while it shows. */
    val modifiers = StickyModifiers()
    var expanded = false
    var restoreKeyboard = false
}

/**
 * The [LiveInputMemory] of each session GUID, in memory for the process's life, like
 * [ComposerDrafts] and for the same reason: app-scoped rather than saved state. Cleared on unpair.
 */
class LiveInputMemories {
    private val memories = mutableMapOf<String, LiveInputMemory>()

    fun of(sessionGuid: String): LiveInputMemory = memories.getOrPut(sessionGuid) { LiveInputMemory() }

    fun clear() = memories.clear()
}
