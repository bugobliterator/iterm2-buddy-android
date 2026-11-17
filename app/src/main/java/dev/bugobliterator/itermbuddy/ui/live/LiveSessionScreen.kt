package dev.bugobliterator.itermbuddy.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import dev.bugobliterator.itermbuddy.core.stream.ActiveStream
import dev.bugobliterator.itermbuddy.core.stream.LiveViewState
import dev.bugobliterator.itermbuddy.core.stream.StreamConfig
import dev.bugobliterator.itermbuddy.core.stream.fitGrid
import dev.bugobliterator.itermbuddy.video.VideoStats

/** What the live view can ask for; the navigator and the controller do the work. */
interface LiveSessionActions {
    /** The navigator's Back: closes the stream. */
    fun back()

    /** The controller's `retry`: restarts the stream (or watches an ended one again). */
    fun retry()

    /** Fit to phone for the session shown, with the canvas measured with the keyboard hidden. */
    fun fitToPhone(sessionGuid: String, viewportWidthPx: Int, viewportHeightPx: Int)

    /** The debug video stats overlay on or off (debug builds only). */
    fun setVideoStatsShown(shown: Boolean)
}

/**
 * The live session view: the session's video ([VideoCanvas]) with zoom and pan, the state's
 * [liveOverlay] over it, and a ⋮ menu with **Fit to phone** (only when [fitGrid] gives a grid:
 * `canResize == true`, never for a browser) and, in debug builds, Video stats. The screen never
 * opens or closes a stream itself, and Fit is sent only from a tap and never undone.
 *
 * @param config the image geometry: the shown state's config, else the active stream's when it
 *   is this session's (a too-large window keeps its config for Fit).
 * @param macRevision the Mac's hello revision, for the browser note; null when not connected.
 *
 * TODO(Typing task): two known gaps in the keyboard-hidden measurement, left until the keyboard
 * exists here. (1) The Fit viewport is the canvas size last seen with the IME inset at 0; a canvas
 * change while the keyboard is up (rotation inside one activity, split screen, a resized window)
 * leaves it stale until the keyboard hides again. Derive it instead from the current canvas plus
 * the IME inset the layout consumed. (2) `WindowInsets.ime` is read in this composable, so every
 * frame of the IME animation recomposes the whole screen; read it in a narrower scope (a
 * `derivedStateOf` of "hidden" or a layout-phase read) once the key bar and composer arrive.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    // The canvas measured with the keyboard hidden: the last size seen while it was down.
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    var fitViewport by remember { mutableStateOf(IntSize.Zero) }
    val keyboardHidden = imeBottom == 0
    LaunchedEffect(keyboardHidden, canvas) {
        if (keyboardHidden && canvas != IntSize.Zero) fitViewport = canvas
    }
    val viewport = if (fitViewport != IntSize.Zero) fitViewport else canvas
    val canFit = !session.isBrowser && config != null &&
        fitGrid(viewport.width, viewport.height, config, source.output.maxSize(), source.output::sizeSupported) != null
    val fit = { actions.fitToPhone(session.guid, viewport.width, viewport.height) }
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = actions::back, modifier = Modifier.semantics { contentDescription = "Back" }) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    if (canFit || debugBuild) {
                        Box {
                            IconButton(
                                onClick = { menuOpen = true },
                                modifier = Modifier.semantics { contentDescription = "More options" },
                            ) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
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
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            VideoCanvas(
                source = source,
                imageWidth = config?.pixelWidth ?: 0,
                imageHeight = config?.pixelHeight ?: 0,
                sessionGuid = session.guid,
                onCanvasSize = { canvas = it },
            )
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
    }
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
