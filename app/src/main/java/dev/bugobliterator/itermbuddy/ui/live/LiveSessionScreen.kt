package dev.bugobliterator.itermbuddy.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bugobliterator.itermbuddy.core.input.ImeTranslator
import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.input.KeyEvent
import dev.bugobliterator.itermbuddy.core.input.ModifierKey
import dev.bugobliterator.itermbuddy.core.input.StickyModifiers
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.stream.ActiveStream
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.fitGrid
import dev.bugobliterator.itermbuddy.video.VideoStats

/** What the live view can ask for; the navigator, the controller and the input sender do the work. */
interface LiveSessionActions {
    /** The navigator's Back: closes the stream. */
    fun back()

    /** The controller's `retry`: restarts the stream (or watches an ended one again). */
    fun retry()

    /** Fit to phone for the session shown, with the canvas measured with the keyboard hidden. */
    fun fitToPhone(sessionGuid: String, viewportWidthPx: Int, viewportHeightPx: Int)

    /** The debug video stats overlay on or off (debug builds only). */
    fun setVideoStatsShown(shown: Boolean)

    /** A key for [target], through the app's one ordered input sender. */
    fun key(target: InputTarget, event: KeyEvent)

    /** The composer's Paste for [target]. */
    fun paste(target: InputTarget, text: String)

    /**
     * The composer's Paste ⏎ for [target]. Owner decision 2026-11-18: single-line drafts are typed
     * then Returned; multi-line drafts are pasted with no Return. The Return follows only typing
     * that was all sent (see `InputSender`).
     */
    fun typeThenReturn(target: InputTarget, text: String)

    /** The composer's draft for [sessionGuid], kept in memory per session (not in saved state). */
    fun composerDraft(sessionGuid: String): String

    fun setComposerDraft(sessionGuid: String, draft: String)

    /** [sessionGuid]'s sticky modifiers, tray and keyboard-restore flag, app-scoped so rotation keeps them. */
    fun inputMemory(sessionGuid: String): LiveInputMemory
}

/**
 * The live session view: the session's video ([VideoCanvas]) with zoom and pan, the state's
 * [liveOverlay] over it, ⌨ (show or hide the keyboard) while input is offered, and a ⋮ menu
 * with **Fit to phone** (only when [fitGrid] gives a grid: `canResize == true`, never for a
 * browser) and, in debug builds, Video stats. The screen never opens or closes a stream itself,
 * and Fit is sent only from a tap and never undone.
 *
 * **Typing.** Input is offered only while [inputTarget] gives a target: the state is `Live` for
 * this session, the session is a terminal, and the active stream is this session's (browser
 * sessions get no keyboard yet). Then a tap on the picture or ⌨ raises the keyboard into a
 * [TerminalInputView], with the [KeyBar] above it; the key bar's compose key opens the
 * [Composer]. Leaving that state lowers the keyboard. Every key and paste carries the target it
 * was made for, and the input sender discards it if the stream has moved on by the time it is
 * sent; held composing text is dropped when the target changes. A keyboard that was up when input
 * went away (a reconnect) comes back with it. Multi-line or long commits (dictation) open the
 * composer seeded with them. Sticky modifiers, the tray and the draft are per session; the draft
 * is cleared only once its paste was handed to the sender.
 *
 * **Edge to edge** (iOS `SessionView.swift:870-889, 1985-2005`). The canvas is the whole window
 * on black; the compact [LiveTopBar] (status bar scrim and a 48 dp row) and the navigation bar
 * sit over it, and their heights are the canvas's top and bottom insets. At fit the picture sits
 * just below the bar, top-aligned in the area between the insets ([visibleFitRect]), with any
 * height left over at the bottom; zoomed in, it pans until its edges meet the insets. Overlays
 * are centred in that area.
 *
 * **Fit viewport.** Fit to phone computes its grid for the area between the insets with the
 * keyboard hidden ([fitViewport]), so a fitted grid fills it; the canvas never shrinks for the
 * keyboard, so it follows rotation, split screen and window resizes at once.
 *
 * **Keyboard lift.** The key bar sits over the picture just above the keyboard. While it is up,
 * the keyboard (its inset includes the navigation bar) plus the key bar is the canvas's bottom
 * inset instead of the navigation bar, read only in the canvas's layer and gestures, never in
 * composition; the picture's bottom (the prompt) stays just above the key bar while the keyboard
 * animates, as iOS (`SessionView.swift:1551-1559, 1995-2005`).
 *
 * @param config the image geometry: the shown state's config, else the active stream's when it
 *   is this session's (a too-large window keeps its config for Fit).
 * @param macRevision the Mac's hello revision, for the browser note; null when not connected.
 */
@Composable
fun LiveSessionScreen(
    title: String,
    session: SessionSummary,
    live: LiveViewState,
    activeStream: ActiveStream?,
    macRevision: Int?,
    source: LiveVideoSource,
    debugBuild: Boolean,
    videoStatsShown: Boolean,
    actions: LiveSessionActions,
    modifier: Modifier = Modifier,
) {
    val config = shownConfig(session, live, activeStream)
    val target = inputTarget(session, live, activeStream)
    // The canvas is the whole window; the top bar and the navigation bar sit over it (see the KDoc).
    val density = LocalDensity.current
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    var topChrome by remember { mutableIntStateOf(0) }
    val navigationBottom = WindowInsets.navigationBars.getBottom(density)
    val (viewportWidth, viewportHeight) = fitViewport(canvas.width, canvas.height, topChrome, navigationBottom)
    val canFit = !session.isBrowser && config != null &&
        fitGrid(viewportWidth, viewportHeight, config, source.output.maxSize(), source.output::sizeSupported) != null
    val fit = { actions.fitToPhone(session.guid, viewportWidth, viewportHeight) }
    var menuOpen by remember { mutableStateOf(false) }

    val keyboard = remember { TerminalKeyboard() }
    val currentTarget by rememberUpdatedState(target)
    val currentActions by rememberUpdatedState(actions)
    var modifierVersion by remember { mutableIntStateOf(0) }
    // App-scoped per session (like the draft), so rotation keeps an armed ctrl, the tray and the restore flag.
    val memory = remember(session.guid) { actions.inputMemory(session.guid) }
    val modifiers = memory.modifiers
    DisposableEffect(modifiers) {
        modifiers.onChange = { modifierVersion++ }
        onDispose { modifiers.onChange = {} }
    }
    var composerOpen by rememberSaveable(session.guid) { mutableStateOf(false) }
    // The draft lives in the app's per-session holder, not in saved state (it can be large).
    var draft by remember(session.guid) { mutableStateOf(actions.composerDraft(session.guid)) }
    val updateDraft = { text: String ->
        draft = text
        currentActions.setComposerDraft(session.guid, text)
    }
    val translator = remember(session.guid) {
        ImeTranslator(
            modifiers,
            emit = { event -> currentTarget?.let { currentActions.key(it, event) } },
            onComposingFlushed = keyboard::restartInput,
            toComposer = { text ->
                // Dictation or a multi-line or long commit: the owner confirms it in the composer.
                updateDraft(if (draft.isEmpty()) text else draft + text)
                keyboard.hide()
                composerOpen = true
            },
        )
    }
    var expanded by remember(session.guid) { mutableStateOf(memory.expanded) }
    val offersInput = target != null
    // Raise the keyboard again when input comes back (a reconnect) if it was up when it went.
    LaunchedEffect(offersInput) {
        if (!offersInput) {
            if (keyboard.visible()) memory.restoreKeyboard = true
            keyboard.hide()
        } else if (memory.restoreKeyboard) {
            memory.restoreKeyboard = false
            keyboard.show()
        }
    }
    // Held composing text and the deletion tail belong to the target they were typed for.
    LaunchedEffect(translator, target) { translator.reset() }
    val keyBarActions = remember(translator) {
        object : KeyBarActions {
            override fun key(event: KeyEvent) = translator.keyBar(event)

            override fun modifier(key: ModifierKey) = modifiers.tapNow(key)

            override fun compose() {
                translator.flushComposing()
                keyboard.hide()
                composerOpen = true
            }

            override fun toggleExpanded() {
                expanded = !expanded
                memory.expanded = expanded
            }

            override fun hideKeyboard() = keyboard.hide()
        }
    }

    val ime = WindowInsets.ime
    var keyBarHeight by remember { mutableIntStateOf(0) }
    val currentNavigationBottom by rememberUpdatedState(navigationBottom)
    // What covers the canvas's bottom now, in px: the navigation bar, or the keyboard (whose inset
    // includes it) plus the key bar. Read only by the canvas's layer and gestures, so the IME
    // animation never recomposes this screen.
    val bottomCover: () -> Float = remember(ime, density) {
        { maxOf(currentNavigationBottom, ime.getBottom(density) + keyBarHeight).toFloat() }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { canvas = it },
    ) {
        TerminalInput(keyboard, translator.takeIf { offersInput }, Modifier.align(Alignment.TopStart))
        VideoCanvas(
            source = source,
            imageWidth = config?.pixelWidth ?: 0,
            imageHeight = config?.pixelHeight ?: 0,
            sessionGuid = session.guid,
            onCanvasSize = {},
            onTap = { if (currentTarget != null) keyboard.show() },
            topInset = topChrome.toFloat(),
            restBottomInset = navigationBottom.toFloat(),
            bottomInset = bottomCover,
        )
        // The visible area between the bars, for what is drawn over the picture.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = with(density) { topChrome.toDp() },
                    bottom = with(density) { navigationBottom.toDp() },
                ),
        ) {
            liveOverlay(live, canFit, session.guid)?.let { overlay ->
                OverlayCard(
                    overlay = overlay,
                    busy = live is LiveViewState.Starting || live is LiveViewState.Reconnecting,
                    onAction = { action ->
                        when (action) {
                            OverlayAction.BACK -> actions.back()
                            OverlayAction.RETRY, OverlayAction.RESTART -> actions.retry()
                            OverlayAction.FIT -> fit()
                        }
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            browserNote(session, macRevision)?.let { note ->
                Text(
                    text = note,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
            if (debugBuild && videoStatsShown) {
                VideoStatsOverlay(source, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
        }
        LiveTopBar(
            title = title,
            onBack = actions::back,
            modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { topChrome = it.height },
        ) {
            if (offersInput) {
                IconButton(
                    onClick = keyboard::toggle,
                    modifier = Modifier.semantics { contentDescription = "Show or hide keyboard" },
                ) { Text("⌨", style = MaterialTheme.typography.titleMedium) }
            }
            if (canFit || debugBuild) {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.semantics { contentDescription = "More options" },
                    ) { Text("⋮", style = MaterialTheme.typography.titleMedium) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (canFit) {
                            DropdownMenuItem(
                                text = { Text("Fit to phone") },
                                onClick = {
                                    menuOpen = false
                                    fit()
                                },
                            )
                        }
                        if (debugBuild) {
                            DropdownMenuItem(
                                text = { Text("Video stats") },
                                trailingIcon = { Checkbox(checked = videoStatsShown, onCheckedChange = null) },
                                onClick = {
                                    menuOpen = false
                                    actions.setVideoStatsShown(!videoStatsShown)
                                },
                            )
                        }
                    }
                }
            }
        }
        // Over the picture, just above the keyboard: the canvas keeps its size and lifts instead.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .onSizeChanged { keyBarHeight = it.height },
        ) {
            KeyBarSlot(
                shown = offersInput && !composerOpen,
                modifiers = modifiers,
                modifierVersion = modifierVersion,
                expanded = expanded,
                actions = keyBarActions,
            )
        }
    }
    if (composerOpen) {
        Composer(
            title = title,
            draft = draft,
            onDraftChange = updateDraft,
            canSend = offersInput,
            onPaste = {
                currentTarget?.let {
                    actions.paste(it, draft)
                    updateDraft("")
                    composerOpen = false
                }
            },
            onPasteThenReturn = {
                currentTarget?.let {
                    actions.typeThenReturn(it, draft)
                    updateDraft("")
                    composerOpen = false
                }
            },
            onDismiss = { composerOpen = false },
        )
    }
}

/**
 * The compact bar drawn over the top of the picture (iOS keeps its bars over a full-screen canvas):
 * a translucent scrim behind the status bar and a 48 dp row with ←, the title on one line
 * (ellipsized) and [actions] (⌨, ⋮). Its measured height is the canvas's top inset.
 */
@Composable
private fun LiveTopBar(title: String, onBack: () -> Unit, modifier: Modifier, actions: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer.copy(alpha = TOP_BAR_ALPHA))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                Text("←", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
    }
}

/** The top bar's opacity over the picture. */
private const val TOP_BAR_ALPHA = 0.8f

/**
 * Where the live view's input goes: the active stream's link and watch for [session], only while
 * [live] is `Live` for it and it is a terminal (neither the session nor its config a browser).
 * Null means no keyboard is offered.
 */
internal fun inputTarget(session: SessionSummary, live: LiveViewState, activeStream: ActiveStream?): InputTarget? {
    if (live !is LiveViewState.Live || live.session.guid != session.guid) return null
    if (session.isBrowser || live.config.isBrowser) return null
    val stream = activeStream?.takeIf { it.sessionGuid == session.guid } ?: return null
    return InputTarget(stream.linkId, stream.watchEpoch, session.guid)
}

/** The [TerminalInputView] the live view types through, while it is composed. */
internal class TerminalKeyboard {
    var view: TerminalInputView? = null

    fun show() {
        view?.showKeyboard()
    }

    fun hide() {
        view?.hideKeyboard()
    }

    fun toggle() {
        if (visible()) hide() else show()
    }

    fun restartInput() {
        view?.restartInput()
    }

    /** Whether the keyboard is up for the terminal view. */
    fun visible(): Boolean = view?.let { it.hasFocus() && it.keyboardVisible() } == true
}

/**
 * The [TerminalInputView], 1 dp square (a view with no size cannot take focus), behind the picture.
 * [translator] null drops IME calls (no input offered).
 */
@Composable
private fun TerminalInput(keyboard: TerminalKeyboard, translator: ImeTranslator?, modifier: Modifier) {
    AndroidView(
        factory = { context -> TerminalInputView(context).also { keyboard.view = it } },
        modifier = modifier.size(1.dp),
        update = { view -> view.translator = translator },
        onRelease = { view ->
            view.translator = null
            if (keyboard.view === view) keyboard.view = null
        },
    )
}

/**
 * The key bar, while input is offered and the keyboard is up. The only reader of the IME's
 * visibility (which changes at the start and end of its animation, not per frame), so nothing
 * else recomposes with the keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyBarSlot(
    shown: Boolean,
    modifiers: StickyModifiers,
    modifierVersion: Int,
    expanded: Boolean,
    actions: KeyBarActions,
) {
    if (!shown || !WindowInsets.isImeVisible) return
    KeyBar(modifiers, modifierVersion, expanded, actions)
}

/** The config whose image is shown: the Live state's, else the active stream's for this session. */
internal fun shownConfig(session: SessionSummary, live: LiveViewState, activeStream: ActiveStream?): StreamConfig? {
    if (live is LiveViewState.Live && live.session.guid == session.guid) return live.config
    return activeStream?.takeIf { it.sessionGuid == session.guid }?.config
}

@Composable
private fun OverlayCard(overlay: Overlay, busy: Boolean, onAction: (OverlayAction) -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier.padding(24.dp).widthIn(max = 480.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (busy) CircularProgressIndicator()
            Text(
                text = overlay.text,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            overlay.action?.let { action ->
                FilledTonalButton(onClick = { onAction(action) }) { Text(actionLabel(action)) }
            }
        }
    }
}

/** The overlay button's label. */
internal fun actionLabel(action: OverlayAction): String = when (action) {
    OverlayAction.BACK -> "Back"
    OverlayAction.RETRY -> "Try again"
    OverlayAction.RESTART -> "Restart"
    OverlayAction.FIT -> "Fit to phone"
}

/** Debug builds: the decoder's counters, collected only while shown. */
@Composable
private fun VideoStatsOverlay(source: LiveVideoSource, modifier: Modifier) {
    val stats by source.output.stats.collectAsStateWithLifecycle()
    Surface(modifier = modifier, color = Color.Black.copy(alpha = 0.6f), shape = MaterialTheme.shapes.small) {
        Text(
            text = videoStatsText(stats),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(8.dp),
        )
    }
}

/** The debug stats line: counters and the decoder's name only, never content. */
internal fun videoStatsText(stats: VideoStats): String =
    "${stats.decoderName ?: "no decoder"}\nin ${stats.framesIn} · out ${stats.framesOut} · " +
        "latency ${stats.lastLatencyMillis?.let { "$it ms" } ?: "—"}"
