package dev.bugobliterator.itermbuddy.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bugobliterator.itermbuddy.core.input.ImeTranslator
import dev.bugobliterator.itermbuddy.core.input.InputTarget
import dev.bugobliterator.itermbuddy.core.input.Key
import dev.bugobliterator.itermbuddy.core.input.KeyEvent
import dev.bugobliterator.itermbuddy.core.input.ModifierKey
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.stream.ActiveStream
import dev.bugobliterator.itermbuddy.core.stream.BrowserDriver
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.ui.LiveSessionTheme
import dev.bugobliterator.itermbuddy.ui.live.KeyBarActions
import dev.bugobliterator.itermbuddy.ui.live.KeyBarSlot
import dev.bugobliterator.itermbuddy.ui.live.LiveSessionActions
import dev.bugobliterator.itermbuddy.ui.live.LiveVideoSource
import dev.bugobliterator.itermbuddy.ui.live.OverlayAction
import dev.bugobliterator.itermbuddy.ui.live.OverlayCard
import dev.bugobliterator.itermbuddy.ui.live.TerminalInput
import dev.bugobliterator.itermbuddy.ui.live.TerminalKeyboard
import dev.bugobliterator.itermbuddy.ui.live.VideoCanvas
import dev.bugobliterator.itermbuddy.ui.live.VideoStatsOverlay
import dev.bugobliterator.itermbuddy.ui.live.insetBy
import dev.bugobliterator.itermbuddy.ui.live.liveOverlay
import dev.bugobliterator.itermbuddy.ui.live.rememberBottomCover
import dev.bugobliterator.itermbuddy.ui.live.shownConfig
import dev.bugobliterator.itermbuddy.ui.live.tapNow
import kotlin.math.roundToInt

/** A move of the page's keyboard. */
internal enum class KeyboardMove { SHOW, HIDE }

/**
 * When the keyboard follows the page, as iOS's `applyBrowserEditing` and `handleBrowserTap`
 * (`IOS/Screens/SessionView.swift:2188-2222`): a change of the Mac's `browserEditing` raises or
 * lowers it, but only while browser input is active, so an editing state that arrives before the
 * first config says browser is applied once it does; a tap on the page while editing raises it
 * again (tapping the focused field makes no editing change). Not thread-safe; the main thread.
 */
internal class BrowserKeyboardRule {
    /** The editing state last acted on (iOS `appliedBrowserEditing`). */
    private var applied = false

    /** The page's editing state is [editing]; [active] whether browser input is. */
    fun onEditing(editing: Boolean, active: Boolean): KeyboardMove? {
        if (!active || editing == applied) return null
        applied = editing
        return if (editing) KeyboardMove.SHOW else KeyboardMove.HIDE
    }

    /** A tap on the page: whether it raises the keyboard. */
    fun onTap(editing: Boolean, active: Boolean): Boolean = active && editing
}

/**
 * Where the browser view's input goes: the active stream's link and watch for [session], only
 * while [live] is `Live` for it with a browser config, the active stream is this session's, and
 * browser input is active ([inputActive]: the driver's, a revision 15 Mac). Null: no keyboard, no
 * taps or scrolls, and the controls are disabled.
 */
internal fun browserInputTarget(session: SessionSummary, live: LiveViewState, activeStream: ActiveStream?, inputActive: Boolean): InputTarget? {
    if (!inputActive || live !is LiveViewState.Live || live.session.guid != session.guid || !live.config.isBrowser) return null
    val stream = activeStream?.takeIf { it.sessionGuid == session.guid } ?: return null
    return InputTarget(stream.linkId, stream.watchEpoch, session.guid)
}

/**
 * The browser session view (iOS's `LiveSessionView` with `BrowserControlsBar`,
 * `IOS/Screens/SessionView.swift:789-1214`), always dark ([LiveSessionTheme]): the page's video
 * fit below the [BrowserBar] and zoomable to four view pixels per page pixel, the state's
 * overlay, and the keyboard. Opened and closed only through the navigator, as the live view; the
 * screen is kept on by the home while it shows. No Fit, selection or history tiles.
 *
 * - **Controls** ([browserControls]): ← leaves, ‹ › go back and forward, ⟳ reloads or ✕ stops
 *   while loading, and Go in the [AddressField] loads its trimmed text (the Mac opens a URL or
 *   searches) and leaves the field, which then shows the page's URL again. Disabled unless
 *   [browserInputTarget] gives a target.
 * - **Page** ([VideoCanvas] with the [BrowserDriver]): a tap clicks the page (no double-tap zoom);
 *   a drag scrolls it and flings at fit; a pinch zooms locally; zoomed in, a drag pans and only
 *   vertical travel past the pan edge scrolls the page. Leaving cancels a scroll under way.
 * - **Keyboard**: typed keys go as `sendKey` to the page through the app's one ordered input
 *   sender, after any tap before them. It rises when the page reports editing and falls when that
 *   ends ([BrowserKeyboardRule]), a tap re-raises it while editing, and ⌨ shows or hides it
 *   regardless. The [KeyBar][dev.bugobliterator.itermbuddy.ui.live.KeyBar] is its browser
 *   variant (no ^C, no compose); dictation or a multi-line commit, which the live view sends to
 *   its composer, is typed into the page. The key bar hides while the address field has focus.
 *
 * @param browser the app's driver; its flows give input availability, editing and navigation.
 */
@Composable
fun BrowserSessionScreen(
    title: String,
    session: SessionSummary,
    live: LiveViewState,
    activeStream: ActiveStream?,
    source: LiveVideoSource,
    browser: BrowserDriver,
    debugBuild: Boolean,
    videoStatsShown: Boolean,
    actions: LiveSessionActions,
    modifier: Modifier = Modifier,
    macAlert: String? = null,
) {
    LiveSessionTheme {
        BrowserSessionContent(title, session, live, activeStream, source, browser, debugBuild, videoStatsShown, actions, modifier, macAlert)
    }
}

/** [BrowserSessionScreen] inside its dark theme. [title] (the row's) names the pane for TalkBack; the bar shows the address. */
@Composable
private fun BrowserSessionContent(
    title: String,
    session: SessionSummary,
    live: LiveViewState,
    activeStream: ActiveStream?,
    source: LiveVideoSource,
    browser: BrowserDriver,
    debugBuild: Boolean,
    videoStatsShown: Boolean,
    actions: LiveSessionActions,
    modifier: Modifier,
    macAlert: String?,
) {
    val inputActive by browser.inputActive.collectAsStateWithLifecycle()
    val editing by browser.editing.collectAsStateWithLifecycle()
    val navigation by browser.navigation.collectAsStateWithLifecycle()
    val config = shownConfig(session, live, activeStream)
    val target = browserInputTarget(session, live, activeStream, inputActive)
    val offersInput = target != null
    val currentTarget by rememberUpdatedState(target)
    val currentActions by rememberUpdatedState(actions)
    val currentEditing by rememberUpdatedState(editing)

    val density = LocalDensity.current
    var topChrome by remember { mutableIntStateOf(0) }
    val navigationBottom = WindowInsets.navigationBars.getBottom(density)
    val sides = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val layoutDirection = LocalLayoutDirection.current
    val leftInset = sides.getLeft(density, layoutDirection)
    val rightInset = sides.getRight(density, layoutDirection)
    var keyBarHeight by remember { mutableIntStateOf(0) }
    val bottomCover = rememberBottomCover(navigationBottom) { keyBarHeight }
    val focusManager = LocalFocusManager.current

    val keyboard = remember { TerminalKeyboard() }
    val rule = remember(session.guid) { BrowserKeyboardRule() }
    val memory = remember(session.guid) { actions.inputMemory(session.guid) }
    val modifiers = memory.modifiers
    var modifierVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(modifiers) {
        modifiers.onChange = { modifierVersion++ }
        onDispose { modifiers.onChange = {} }
    }
    var expanded by remember(session.guid) { mutableStateOf(memory.expanded) }
    var address by remember(session.guid) { mutableStateOf(AddressFieldState("", focused = false)) }
    val translator = remember(session.guid) {
        val emit = { event: KeyEvent -> currentTarget?.let { currentActions.key(it, event) } ?: Unit }
        ImeTranslator(
            modifiers,
            emit = emit,
            onComposingFlushed = keyboard::restartInput,
            // No composer here: dictation or a multi-line commit is typed into the page.
            toComposer = { text -> emit(KeyEvent(Key.Text(text.replace("\r\n", "\n").replace('\r', '\n')))) },
        )
    }
    // Held composing text belongs to the target it was typed for.
    LaunchedEffect(translator, target) { translator.reset() }
    LaunchedEffect(offersInput, editing) {
        if (!offersInput) keyboard.hide()
        when (rule.onEditing(editing, offersInput)) {
            KeyboardMove.SHOW -> keyboard.show()
            KeyboardMove.HIDE -> keyboard.hide()
            null -> Unit
        }
    }
    val keyBarActions = remember(translator) {
        object : KeyBarActions {
            override fun key(event: KeyEvent) = translator.keyBar(event)

            override fun modifier(key: ModifierKey) = modifiers.tapNow(key)

            // The browser key bar has no compose key.
            override fun compose() = Unit

            override fun toggleExpanded() {
                expanded = !expanded
                memory.expanded = expanded
            }

            override fun hideKeyboard() = keyboard.hide()
        }
    }
    val barActions = remember(browser) {
        object : BrowserBarActions {
            override fun close() = currentActions.back()

            override fun goBack() = browser.back()

            override fun goForward() = browser.forward()

            override fun reload() = browser.reload()

            override fun stop() = browser.stop()

            override fun go() {
                address.go()?.let(browser::load)
                focusManager.clearFocus()
            }

            override fun toggleKeyboard() {
                focusManager.clearFocus()
                keyboard.toggle()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black).semantics { paneTitle = title }) {
        TerminalInput(keyboard, translator.takeIf { offersInput }, Modifier.align(Alignment.TopStart))
        VideoCanvas(
            source = source,
            imageWidth = config?.pixelWidth ?: 0,
            imageHeight = config?.pixelHeight ?: 0,
            sessionGuid = session.guid,
            onCanvasSize = {},
            onTap = {
                // A tap on the page leaves the address field (restoring the URL).
                focusManager.clearFocus()
                if (rule.onTap(currentEditing, currentTarget != null)) keyboard.show()
            },
            topInset = topChrome.toFloat(),
            restBottomInset = navigationBottom.toFloat(),
            bottomInset = bottomCover,
            leftInset = leftInset.toFloat(),
            rightInset = rightInset.toFloat(),
            doubleTapZoom = false,
            browser = browser.takeIf { offersInput },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .insetBy(left = leftInset, top = { topChrome }, right = rightInset, bottom = { bottomCover().roundToInt() }),
        ) {
            // A browser has no Fit: a too-large page gets the Mac-side advice.
            liveOverlay(live, canFit = false, sessionGuid = session.guid)?.let { overlay ->
                OverlayCard(
                    overlay = overlay,
                    busy = live is LiveViewState.Starting || live is LiveViewState.Reconnecting,
                    onAction = { action ->
                        when (action) {
                            OverlayAction.BACK -> actions.back()
                            OverlayAction.RETRY, OverlayAction.RESTART -> actions.retry()
                            OverlayAction.FIT -> Unit
                        }
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (debugBuild && videoStatsShown) {
                VideoStatsOverlay(source, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
        }
        BrowserBar(
            controls = browserControls(navigation, offersInput),
            address = address,
            url = navigation?.url,
            onAddress = { change -> address = change(address) },
            offersKeyboard = offersInput,
            actions = barActions,
            banner = macAlert,
            modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { topChrome = it.height },
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
                .onSizeChanged { keyBarHeight = it.height },
        ) {
            KeyBarSlot(
                shown = offersInput && !address.focused,
                modifiers = modifiers,
                modifierVersion = modifierVersion,
                expanded = expanded,
                actions = keyBarActions,
                browser = true,
            )
        }
    }
}
