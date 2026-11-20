package dev.bugobliterator.itermbuddy.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.bugobliterator.itermbuddy.core.input.ImeTranslator
import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.input.KeyEvent
import dev.bugobliterator.itermbuddy.core.input.ModifierKey
import dev.bugobliterator.itermbuddy.core.input.StickyModifiers
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.stream.ActiveStream
import dev.bugobliterator.itermbuddy.core.stream.FitViewport
import dev.bugobliterator.itermbuddy.core.stream.LatencyMeter
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.fitGrid
import dev.bugobliterator.itermbuddy.core.stream.latencyStatsText
import dev.bugobliterator.itermbuddy.core.wire.SelectionPhase
import dev.bugobliterator.itermbuddy.core.wire.SelectionPoint
import dev.bugobliterator.itermbuddy.ui.LiveSessionTheme
import dev.bugobliterator.itermbuddy.video.VideoStats
import dev.bugobliterator.itermbuddy.video.lowLatencyLabel
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the live view can ask for; the navigator, the controller and the input sender do the work. */
interface LiveSessionActions {
    /** The navigator's Back: closes the stream. */
    fun back()

    /** The controller's `retry`: restarts the stream (or watches an ended one again). */
    fun retry()

    /** The ⤢ Fit to phone toggle: on fits the session shown now and keeps fitting; off undoes nothing. */
    fun setFitToPhone(enabled: Boolean) {}

    /** The view of a session measured its canvas for Fit to phone (keyboard hidden); on every change. */
    fun fitViewport(viewport: FitViewport) {}

    /** The view of [sessionGuid] left: Fit to phone stops fitting to its canvas. */
    fun clearFitViewport(sessionGuid: String) {}

    /** The too-large overlay's Fit: the toggle on, and [sessionGuid] fitted now even if it was fitted before. */
    fun fitNow(sessionGuid: String) = setFitToPhone(true)

    /** The debug video stats overlay on or off (debug builds only). */
    fun setVideoStatsShown(shown: Boolean)

    /** A key for [target], through the app's one ordered input sender. */
    fun key(target: InputTarget, event: KeyEvent)

    /**
     * A key for a browser session's page [target], through the same sender: always a key, never
     * a paste, whatever Settings' "Send / * + = as paste" (iTerm2 types a browser session's keys
     * into the page itself, so they are not garbled, and its paste path is a terminal's).
     */
    fun browserKey(target: InputTarget, event: KeyEvent) = key(target, event)

    /** The composer's Paste for [target]. */
    fun paste(target: InputTarget, text: String)

    /**
     * The composer's Paste ⏎ for [target]. Owner decision 2026-11-18: single-line drafts are typed
     * then Returned; multi-line drafts are pasted with no Return. The Return follows only typing
     * that was all sent (see `InputSender`).
     */
    fun typeThenReturn(target: InputTarget, text: String)

    /**
     * Mouse-wheel notches for [target]'s stream [streamID] (the alternate screen with wheel
     * reporting), through the same ordered input sender as keys.
     */
    fun scrollWheel(target: InputTarget, streamID: Long, up: Boolean, lines: Int)

    /** The composer's draft for [sessionGuid], kept in memory per session (not in saved state). */
    fun composerDraft(sessionGuid: String): String

    fun setComposerDraft(sessionGuid: String, draft: String)

    /** [sessionGuid]'s sticky modifiers, tray and keyboard-restore flag, app-scoped so rotation keeps them. */
    fun inputMemory(sessionGuid: String): LiveInputMemory

    /** The link bubble's Open for a phone link: [url] in the phone's browser. */
    fun openLink(url: String) {}

    /** The browser session a Mac-local link opens in now (the most recently viewed), or null when there is none. */
    fun linkBrowserSession(): SessionSummary? = null

    /** The link bubble's Open for a Mac-local link: [session]'s browser view, loading [url] once its stream takes input. */
    fun openLinkInBrowserSession(session: SessionSummary, url: String) {}
}

/**
 * The live session view, always dark ([LiveSessionTheme], with light system bar icons while it
 * shows): the session's video ([VideoCanvas]) with zoom and pan, the state's [liveOverlay] over
 * it, ⌨ (show or hide the keyboard) while input is offered, ⤢ **Fit to phone** and, in debug
 * builds, a ⋮ menu with Video stats. The screen never opens or closes a stream itself.
 *
 * **Fit to phone** is an on/off toggle ([fitToPhone], one global setting): drawn filled while on,
 * outlined while off, and greyed when it cannot fit this session ([fitButton]: shown for every
 * terminal session, as iOS's "Resize for This Screen"). A tap flips it, and turning it on where it
 * cannot fit shows why ([fitToggleMessage]); a long press explains it. The too-large overlay's Fit
 * turns it on. The screen reports its fit viewport ([LiveSessionActions.fitViewport]) while shown;
 * the app's [AutoFitter][dev.bugobliterator.itermbuddy.core.stream.AutoFitter] does the resizing and
 * its [LiveVideoSource.fitNotices] for this session show in the snackbar.
 *
 * **Typing.** Input is offered only while [inputTarget] gives a target: the state is `Live` for
 * this session, the session is a terminal, and the active stream is this session's (a browser
 * here is video only). Then a tap on the picture or ⌨ raises the keyboard into a
 * [TerminalInputView], with the [KeyBar] above it; the key bar's compose key opens the
 * [Composer]. Leaving that state lowers the keyboard. Every key and paste carries the target it
 * was made for, and the input sender discards it if the stream has moved on by the time it is
 * sent; held composing text is dropped when the target changes (a browser session at revision 15
 * has its own [BrowserSessionScreen][dev.bugobliterator.itermbuddy.ui.browser.BrowserSessionScreen]
 * with a keyboard; here, below 15, it is video only). A keyboard that was up when input
 * went away (a reconnect) comes back with it. Multi-line or long commits (dictation) open the
 * composer seeded with them. Sticky modifiers, the tray and the draft are per session; the draft
 * is cleared only once its paste was handed to the sender.
 *
 * **Scrollback.** A terminal session's picture is a [ScrollbackCanvas]: its history tiles above
 * the live band, opening at the bottom, with **↓ Live** over the bottom of the visible area while
 * scrolled up. On the alternate screen with mouse-wheel reporting a one-finger drag sends wheel
 * notches to the active stream through the ordered input sender, only while input is offered.
 * Browser sessions keep the plain [VideoCanvas].
 *
 * **Selection** (iOS `SessionView.swift:2806-2860, 2935-2948`). While the active stream is this
 * session's, a 0.3 s hold on the picture or the history begins a selection on the Mac through the
 * app's [LiveVideoSource.selection] driver, the same press drags it, and lifting ends it (at the
 * last mapped point if the lift cannot be mapped) and, only if a begin was sent ([SelectionPress]),
 * shows the [SelectionMenu] there: **Copy**
 * (the Mac's text to the clipboard, marked sensitive, done in the app scope so a rotation does
 * not cut it short; "Nothing selected" when it has none),
 * **Select All** (the menu stays, for Copy), and **Paste** (the clipboard's first text item through
 * the ordered input sender, only while input is offered). The Mac draws the selection into the
 * video and the tiles. A tap while the menu or a selection is up sends Clear Selection and hides
 * the menu instead of raising the keyboard.
 *
 * **Links** ([LiveVideoSource.links]). Any other single tap on a terminal does what it always did
 * (raises the keyboard while input is offered) and also asks the Mac what its smart selection is at
 * the tapped cell ([LinkTaps], [LinkLookup][dev.bugobliterator.itermbuddy.core.stream.LinkLookup]:
 * select, copy, clear, within 1 s); when that is a link, a [LinkBubble] shows by the tap with
 * **Open** and **Copy**. A phone link opens in the phone's browser ([LiveSessionActions.openLink]);
 * a Mac-local one (localhost and loopback) in the most recently viewed Browser session
 * ([LiveSessionActions.openLinkInBrowserSession]), or the bubble says there is none. A tap
 * elsewhere, a drag, a long press or a stream change hides it (that tap does nothing else); a tap
 * that stopped a coast, a double tap and a tap while a selection shows never look one up.
 *
 * **Edge to edge** (iOS `SessionView.swift:870-889, 1985-2005`). The canvas is the whole window
 * on black; the compact [LiveTopBar] (status bar scrim and a 48 dp row), the navigation bar, and
 * in landscape the side system bars and display cutout sit over it, and are the canvas's insets
 * (the sides from `systemBars ∪ displayCutout`, never the IME). At fit the picture sits just below
 * the bar, centred across and top-aligned in the area inside the insets ([visibleFitRect]), with
 * any height left over at the bottom; zoomed in, it pans until its edges meet the insets.
 * Overlays are centred in that area, whose bottom follows the keyboard (read in layout).
 *
 * **Fit viewport.** Fit to phone computes its grid for the area inside the insets with the
 * keyboard hidden ([fitViewport]), under the bar row but not the Mac alert banner (a banner that
 * comes and goes must not resize the Mac), so a fitted grid fills it; the canvas never shrinks for the
 * keyboard, so it follows rotation, split screen and window resizes at once, and the keyboard never
 * changes it (so never resizes the Mac).
 *
 * **Keyboard lift.** The key bar sits over the picture, padded by `ime ∪ navigationBars`. While
 * the keyboard is up, the taller of the keyboard and the navigation bar plus the key bar (its
 * height coming in with the IME's progress) is the canvas's bottom inset, read only in the
 * canvas's layer and gestures and the overlays' layout, never in composition; the picture's
 * bottom (the prompt) stays just above the key bar while the keyboard animates, as iOS
 * (`SessionView.swift:1551-1559, 1995-2005`), but never rises past a quarter of the height below
 * the bar ([liftForKeyboard]).
 *
 * @param config the image geometry: the shown state's config, else the active stream's when it
 *   is this session's (a too-large window keeps its config for Fit).
 * @param macRevision the Mac's hello revision, for the browser note ([browserNote]); null when not connected.
 * @param macAlert the Mac alert banner's text (`macAlertBanner`), shown as a row under the top
 *   bar, inside its measured height, so the picture and Fit sit below it; null for none.
 * @param fitToPhone whether the Fit to phone toggle is on.
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
    macAlert: String? = null,
    fitToPhone: Boolean = false,
) {
    LiveSessionTheme {
        LiveSessionContent(title, session, live, activeStream, macRevision, source, debugBuild, videoStatsShown, actions, modifier, macAlert, fitToPhone)
    }
}

/** [LiveSessionScreen] inside its dark theme. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveSessionContent(
    title: String,
    session: SessionSummary,
    live: LiveViewState,
    activeStream: ActiveStream?,
    macRevision: Int?,
    source: LiveVideoSource,
    debugBuild: Boolean,
    videoStatsShown: Boolean,
    actions: LiveSessionActions,
    modifier: Modifier,
    macAlert: String?,
    fitToPhone: Boolean,
) {
    val config = shownConfig(session, live, activeStream)
    val target = inputTarget(session, live, activeStream)
    // The canvas is the whole window; the top bar and the navigation bar sit over it (see the KDoc).
    val density = LocalDensity.current
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    var topChrome by remember { mutableIntStateOf(0) }
    // The bar row alone (status bar and the 48 dp row), without the Mac alert banner: Fit's top inset,
    // so the banner coming and going never resizes the Mac.
    var barRow by remember { mutableIntStateOf(0) }
    val navigationBottom = WindowInsets.navigationBars.getBottom(density)
    // Side insets in landscape: system bars and display cutouts (never the IME), as iOS's safe area.
    val sides = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val layoutDirection = LocalLayoutDirection.current
    val leftInset = sides.getLeft(density, layoutDirection)
    val rightInset = sides.getRight(density, layoutDirection)
    val (viewportWidth, viewportHeight) = fitViewport(canvas.width, canvas.height, barRow, navigationBottom, leftInset, rightInset)
    val canFit = !session.isBrowser && config != null &&
        fitGrid(viewportWidth, viewportHeight, config, source.output.maxSize(), source.output::sizeSupported) != null
    val fitState = fitButton(session, live, config, viewportWidth, viewportHeight, source.output.maxSize(), source.output::sizeSupported)
    // The fit viewport while this view shows, for the app's auto fit (it waits for it to settle).
    val fitActions by rememberUpdatedState(actions)
    LaunchedEffect(session.guid, viewportWidth, viewportHeight) {
        if (viewportWidth > 0 && viewportHeight > 0) fitActions.fitViewport(FitViewport(session.guid, viewportWidth, viewportHeight))
    }
    DisposableEffect(session.guid) { onDispose { fitActions.clearFitViewport(session.guid) } }
    // The too-large overlay's Fit: the toggle on, and this session fitted now even if it already was.
    val fit = { actions.fitNow(session.guid) }
    var menuOpen by remember { mutableStateOf(false) }
    val scrollback = remember(session.guid) { ScrollbackControl() }
    val snackbar = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    val keyboard = remember { TerminalKeyboard() }
    val currentTarget by rememberUpdatedState(target)
    val currentActions by rememberUpdatedState(actions)
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val selection = source.selection
    var menuAnchor by remember(session.guid) { mutableStateOf<Offset?>(null) }
    var menuClipboardHasText by remember(session.guid) { mutableStateOf(false) }
    val press = remember(selection, session.guid) { selection?.let(::SelectionPress) }
    // The menu belongs to the stream it was shown on: gone with it (an end, a reconnect, another session).
    val selectionStream = activeStream?.takeIf { it.sessionGuid == session.guid }?.let { it.linkId to it.streamID }
    val linkLookup = source.links
    val linkScope = rememberCoroutineScope()
    val links = remember(session.guid, linkLookup) { linkLookup?.let { lookup -> LinkTaps(linkScope) { point -> lookup.lookup(session.guid, point) } } }
    val linkBubble by (links?.bubble ?: NO_LINK_BUBBLE).collectAsStateWithLifecycle()
    LaunchedEffect(selectionStream) {
        menuAnchor = null
        links?.dismiss()
    }
    val canvasTap = { point: SelectionPoint?, x: Float, y: Float ->
        val range = selection?.range?.value
        if (links?.dismiss() == true) {
            // A tap while the link bubble shows only hides it.
        } else if (menuAnchor != null || range != null) {
            selection?.clear()
            menuAnchor = null
        } else {
            if (currentTarget != null) keyboard.show()
            if (point != null && selectionStream != null) links?.tap(point, x, y)
        }
    }
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
            emit = { event ->
                // Typing stops a coasting flick (vim, less: the next key acts where the view is).
                scrollback.stopCoast()
                currentTarget?.let { currentActions.key(it, event) }
            },
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

    var keyBarHeight by remember { mutableIntStateOf(0) }
    val bottomCover = rememberBottomCover(navigationBottom) { keyBarHeight }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { canvas = it },
    ) {
        TerminalInput(keyboard, translator.takeIf { offersInput }, Modifier.align(Alignment.TopStart))
        val historyTiles = source.historyTiles
        if (historyTiles != null && !session.isBrowser) {
            val streamID = activeStream?.takeIf { it.sessionGuid == session.guid }?.streamID
            val currentStreamID by rememberUpdatedState(streamID)
            ScrollbackCanvas(
                source = source,
                store = historyTiles,
                config = config,
                sessionGuid = session.guid,
                control = scrollback,
                onCanvasSize = {},
                onWheel = { up, lines ->
                    val wheelTarget = currentTarget
                    val wheelStream = currentStreamID
                    if (wheelTarget != null && wheelStream != null) currentActions.scrollWheel(wheelTarget, wheelStream, up, lines)
                },
                onTapAt = canvasTap,
                onPanStart = { links?.dismiss() },
                topInset = topChrome.toFloat(),
                restBottomInset = navigationBottom.toFloat(),
                bottomInset = bottomCover,
                leftInset = leftInset.toFloat(),
                rightInset = rightInset.toFloat(),
                onSelection = if (selection == null || streamID == null) {
                    null
                } else {
                    { phase, point, x, y ->
                        when (phase) {
                            SelectionPhase.BEGIN -> {
                                menuAnchor = null
                                links?.dismiss()
                                if (press?.begin(point) == true) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            SelectionPhase.MOVE -> press?.move(point)
                            SelectionPhase.END -> if (press?.end(point) == true) {
                                menuClipboardHasText = clipboardHasText(context)
                                menuAnchor = Offset(x, y)
                            }
                        }
                    }
                },
            )
        } else {
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
                leftInset = leftInset.toFloat(),
                rightInset = rightInset.toFloat(),
            )
        }
        // The visible area inside the insets, for what is drawn over the picture: its bottom follows
        // the keyboard (read in layout), so an overlay's button is never behind the key bar.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .insetBy(left = leftInset, top = { topChrome }, right = rightInset, bottom = { bottomCover().roundToInt() }),
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
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
            if (debugBuild && videoStatsShown) {
                VideoStatsOverlay(source, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
            if (scrollback.offerLive) {
                LiveChip(onClick = scrollback::jumpToLive, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
        // The auto fit's brief messages for this session (cannot resize, the Mac did not resize).
        LaunchedEffect(source, session.guid) {
            source.fitNotices.collect { notice ->
                if (notice.sessionGuid == session.guid) {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(notice.text)
                }
            }
        }
        if (selection != null) {
            val notice by selection.copyNotices.collectAsStateWithLifecycle()
            LaunchedEffect(notice, session.guid) {
                // Another session's copy waits for that session's view.
                val shown = notice?.takeIf { it.sessionGuid == session.guid } ?: return@LaunchedEffect
                // Marked shown once the snackbar is done (timed out or dismissed); left mid-snackbar
                // (a rotation), the next view shows it again.
                copyMessage(shown.outcome)?.let { message ->
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(message)
                }
                selection.noticeShown(shown)
            }
        }
        val anchor = menuAnchor
        if (selection != null && anchor != null) {
            SelectionMenu(
                anchor = anchor,
                bounds = { Rect4(leftInset, topChrome, canvas.width - rightInset, canvas.height - bottomCover().roundToInt()) },
                canPaste = offersInput && menuClipboardHasText,
                onCopy = {
                    menuAnchor = null
                    // In the driver's app scope, so a rotation does not cut it short.
                    val appContext = context.applicationContext
                    selection.copyToClipboard(session.guid) { text -> copyToClipboard(appContext, text) }
                },
                onSelectAll = selection::selectAll,
                onPaste = {
                    menuAnchor = null
                    val text = clipboardText(context)
                    val pasteTarget = currentTarget
                    if (text != null && pasteTarget != null) {
                        scrollback.stopCoast()
                        currentActions.paste(pasteTarget, text)
                    }
                },
            )
        }
        val bubble = linkBubble
        if (links != null && bubble != null) {
            // Chosen when the bubble shows: the session it names is the one Open goes to.
            val browserTarget = remember(bubble.id) { if (bubble.link.macLocal) actions.linkBrowserSession() else null }
            LinkBubble(
                anchor = Offset(bubble.x, bubble.y),
                bounds = { Rect4(leftInset, topChrome, canvas.width - rightInset, canvas.height - bottomCover().roundToInt()) },
                link = bubble.link,
                browserSession = browserTarget?.let { it.name.ifBlank { "Browser session" } },
                onOpen = {
                    links.dismiss()
                    if (!bubble.link.macLocal) {
                        actions.openLink(bubble.link.url)
                    } else if (browserTarget != null) {
                        actions.openLinkInBrowserSession(browserTarget, bubble.link.url)
                    }
                },
                onCopy = {
                    links.dismiss()
                    copyToClipboard(context, bubble.link.url, LINK_CLIP_LABEL)
                },
            )
        }
        LiveTopBar(
            title = title,
            onBack = actions::back,
            banner = macAlert,
            modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { topChrome = it.height },
            onRowHeight = { barRow = it },
        ) {
            if (offersInput) {
                IconButton(
                    onClick = keyboard::toggle,
                    modifier = Modifier.semantics { contentDescription = "Show or hide keyboard" },
                ) { Text("⌨", style = MaterialTheme.typography.titleMedium) }
            }
            if (fitState != FitButton.Hidden) {
                val show = { message: String? ->
                    if (message != null) {
                        snackbarScope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            snackbar.showSnackbar(message)
                        }
                    }
                }
                FitToolbarButton(
                    on = fitToPhone,
                    available = fitState == FitButton.Enabled,
                    onToggle = {
                        val turnOn = !fitToPhone
                        actions.setFitToPhone(turnOn)
                        show(fitToggleMessage(fitState, turnOn, turnedOn = turnOn))
                    },
                    onExplain = { show(fitToggleMessage(fitState, fitToPhone, turnedOn = null)) },
                )
            }
            if (debugBuild) {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.semantics { contentDescription = "More options" },
                    ) { Text("⋮", style = MaterialTheme.typography.titleMedium) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
        // Over the picture, just above the keyboard: the canvas keeps its size and lifts instead.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
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
                    scrollback.stopCoast()
                    actions.paste(it, draft)
                    updateDraft("")
                    composerOpen = false
                }
            },
            onPasteThenReturn = {
                currentTarget?.let {
                    scrollback.stopCoast()
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
 * What covers the canvas's bottom now, in px: the navigation bar ([navigationBottom]) or the
 * keyboard (whose inset includes it), whichever is taller, plus the key bar ([keyBarHeight]). The
 * key bar shows as soon as the IME starts to open, so its height comes in with the IME's progress
 * rather than in one jump. Read it only in the canvas's layer, its gestures and the overlays'
 * layout, so the IME animation never recomposes the screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberBottomCover(navigationBottom: Int, keyBarHeight: () -> Int): () -> Float {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeSource = WindowInsets.imeAnimationSource
    val imeTarget = WindowInsets.imeAnimationTarget
    val currentNavigationBottom by rememberUpdatedState(navigationBottom)
    val currentKeyBarHeight by rememberUpdatedState(keyBarHeight)
    return remember(ime, imeSource, imeTarget, density) {
        {
            val now = ime.getBottom(density)
            val full = maxOf(imeSource.getBottom(density), imeTarget.getBottom(density), now)
            val progress = if (full > 0) now.toFloat() / full else 0f
            maxOf(currentNavigationBottom, now) + currentKeyBarHeight() * progress
        }
    }
}

/**
 * The compact bar drawn over the top of the picture (iOS keeps its bars over a full-screen canvas):
 * a translucent scrim behind the status bar and a 48 dp row with ←, the title on one line
 * (ellipsized) and [actions] (⌨, ⋮), then, when [banner] is set, the Mac alert row across the
 * same width. Its measured height, banner included, is the canvas's top inset, so the banner
 * never covers the picture or takes its touches.
 */
@Composable
private fun LiveTopBar(
    title: String,
    onBack: () -> Unit,
    banner: String?,
    modifier: Modifier,
    onRowHeight: (Int) -> Unit = {},
    actions: @Composable RowScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LiveTopBarRow(title, onBack, Modifier.onSizeChanged { onRowHeight(it.height) }, actions)
        if (banner != null) LiveAlertBanner(banner)
    }
}

/** The Mac alert row under the bar, in the live theme's colours, its text inside the side insets. */
@Composable
internal fun LiveAlertBanner(text: String) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = text,
        color = colors.onErrorContainer,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.errorContainer)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun LiveTopBarRow(title: String, onBack: () -> Unit, modifier: Modifier, actions: @Composable RowScope.() -> Unit) {
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

/**
 * Pads the content by [left] and [right] (px) and by [top] and [bottom] (px, read at layout, so a
 * change such as the IME animating relayouts without recomposing).
 */
internal fun Modifier.insetBy(left: Int, top: () -> Int, right: Int, bottom: () -> Int): Modifier = layout { measurable, constraints ->
    val horizontal = (maxOf(0, left) + maxOf(0, right)).coerceAtMost(constraints.maxWidth)
    val topPx = maxOf(0, top())
    val vertical = (topPx + maxOf(0, bottom())).coerceAtMost(constraints.maxHeight)
    val placeable = measurable.measure(constraints.offset(-horizontal, -vertical))
    layout(constraints.constrainWidth(placeable.width + horizontal), constraints.constrainHeight(placeable.height + vertical)) {
        placeable.place(maxOf(0, left), minOf(topPx, vertical))
    }
}

/**
 * ⤢ in the live bar, the Fit to phone toggle. [on]: drawn on a filled circle (the theme's primary)
 * in the primary's content colour; off: plain, with a thin outline. Not [available] (it cannot fit
 * this session now) it is drawn greyed. The touch target is the whole 48 dp; the circle is drawn
 * inside it. A tap is [onToggle], whatever its state; a long press is [onExplain]. TalkBack reads it
 * as a switch with its on/off state (the same semantics `Modifier.toggleable` gives, kept on
 * `combinedClickable` so the long press stays), and "can't fit this session" when greyed.
 */
@Composable
private fun FitToolbarButton(on: Boolean, available: Boolean, onToggle: () -> Unit, onExplain: () -> Unit) {
    val alpha = if (available) 1f else DISABLED_ALPHA
    val colors = MaterialTheme.colorScheme
    val content = if (on) colors.onPrimary else LocalContentColor.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(role = Role.Switch, onClick = onToggle, onLongClick = onExplain, onLongClickLabel = "Explain")
            .semantics {
                contentDescription = "Fit to phone"
                toggleableState = ToggleableState(on)
                if (!available) stateDescription = (if (on) "On" else "Off") + ", can't fit this session"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(
                    if (on) {
                        Modifier.background(colors.primary.copy(alpha = alpha), CircleShape)
                    } else {
                        Modifier.border(1.dp, LocalContentColor.current.copy(alpha = OUTLINE_ALPHA * alpha), CircleShape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) { Text("⤢", style = MaterialTheme.typography.titleMedium, color = content.copy(alpha = alpha)) }
    }
}

/** **↓ Live**: back to the live band from the history (Android-only; iOS has no such control). */
@Composable
private fun LiveChip(onClick: () -> Unit, modifier: Modifier) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = "Back to live output" },
    ) { Text("↓ Live") }
}

/** Material's alpha for disabled content. */
private const val DISABLED_ALPHA = 0.38f

/** The Fit toggle's outline while off. */
private const val OUTLINE_ALPHA = 0.5f

/** No link taps: no bubble ever. */
private val NO_LINK_BUBBLE = MutableStateFlow<LinkBubbleState?>(null)

/** The top bar's opacity over the picture. */
internal const val TOP_BAR_ALPHA = 0.8f

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
internal fun TerminalInput(keyboard: TerminalKeyboard, translator: ImeTranslator?, modifier: Modifier) {
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
internal fun KeyBarSlot(
    shown: Boolean,
    modifiers: StickyModifiers,
    modifierVersion: Int,
    expanded: Boolean,
    actions: KeyBarActions,
    browser: Boolean = false,
) {
    if (!shown || !WindowInsets.isImeVisible) return
    KeyBar(modifiers, modifierVersion, expanded, actions, browser = browser)
}

/** The config whose image is shown: the Live state's, else the active stream's for this session. */
internal fun shownConfig(session: SessionSummary, live: LiveViewState, activeStream: ActiveStream?): StreamConfig? {
    if (live is LiveViewState.Live && live.session.guid == session.guid) return live.config
    return activeStream?.takeIf { it.sessionGuid == session.guid }?.config
}

@Composable
internal fun OverlayCard(overlay: Overlay, busy: Boolean, onAction: (OverlayAction) -> Unit, modifier: Modifier) {
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

/**
 * Debug builds: the decoder's counters and the latency numbers ([LatencyMeter]), collected only
 * while shown. While it shows and the screen is at least STARTED, the meter records and pings
 * every 2 s, off the main thread; in the background it stops (no pings, no stamps).
 */
@Composable
internal fun VideoStatsOverlay(source: LiveVideoSource, modifier: Modifier) {
    val stats by source.output.stats.collectAsStateWithLifecycle()
    val meter = source.latency
    val latency = meter?.stats?.collectAsStateWithLifecycle()?.value
    if (meter != null) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(meter, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                withContext(Dispatchers.Default) { meter.measure(ping = source.ping) }
            }
        }
    }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f), shape = MaterialTheme.shapes.small) {
        Text(
            text = videoStatsText(stats) + (latency?.let { "\n" + latencyStatsText(it) } ?: ""),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(8.dp),
        )
    }
}

/**
 * The debug stats lines: the decoder's name and whether it runs with the low-latency keys
 * ("LL on", "LL fallback" when it rejected them, "LL off" by the debug switch), then the
 * counters and the accept-to-output latency, last and median of the recent frames. Counters
 * and the decoder's name only, never content.
 */
internal fun videoStatsText(stats: VideoStats): String {
    val name = stats.decoderName ?: "no decoder"
    val lowLatency = stats.lowLatency?.takeIf { stats.decoderName != null }?.let { " · LL ${lowLatencyLabel(it)}" } ?: ""
    fun ms(value: Long?) = value?.let { "$it ms" } ?: "—"
    return "$name$lowLatency\nin ${stats.framesIn} · out ${stats.framesOut} · " +
        "latency ${ms(stats.lastLatencyMillis)} · median ${ms(stats.medianLatencyMillis)}"
}
