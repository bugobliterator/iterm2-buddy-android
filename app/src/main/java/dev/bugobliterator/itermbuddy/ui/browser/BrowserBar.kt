package dev.bugobliterator.itermbuddy.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.bugobliterator.itermbuddy.core.wire.BrowserNavigationState
import dev.bugobliterator.itermbuddy.ui.live.LiveAlertBanner
import dev.bugobliterator.itermbuddy.ui.live.TOP_BAR_ALPHA

/**
 * Which browser controls are enabled: [back] and [forward] (page history), the trailing button
 * showing Stop ([showsStop]) or Reload and whether it is enabled ([reloadOrStop]), and the
 * [address] field.
 */
data class BrowserControls(
    val back: Boolean,
    val forward: Boolean,
    val showsStop: Boolean,
    val reloadOrStop: Boolean,
    val address: Boolean,
)

/**
 * The controls for the page's [navigation] (null until the Mac reports it) while browser input is
 * [active] (a live browser stream on a revision 15 Mac), as iOS's `BrowserControlsBar`
 * (`IOS/Screens/SessionView.swift:1130-1186`): back and forward from `canGoBack`/`canGoForward`;
 * Stop while loading, else Reload, which needs a navigation state; all disabled while inactive.
 */
fun browserControls(navigation: BrowserNavigationState?, active: Boolean): BrowserControls = BrowserControls(
    back = active && navigation?.canGoBack == true,
    forward = active && navigation?.canGoForward == true,
    showsStop = navigation?.isLoading == true,
    reloadOrStop = active && navigation != null,
    address = active,
)

/** What the browser bar's buttons do. */
internal interface BrowserBarActions {
    /** Leaves the browser view (the navigator's Back: closes the stream). */
    fun close()

    fun goBack()

    fun goForward()

    fun reload()

    fun stop()

    /** Go in the address field. */
    fun go()

    /** ⌨: shows or hides the keyboard for the page. */
    fun toggleKeyboard()
}

/**
 * The browser view's bar, drawn over the top of the page like the live view's (a translucent
 * scrim behind the status bar and a 48 dp row), with iOS's browser controls in it
 * (`IOS/Screens/SessionView.swift:1114-1214`): ← (leave), ‹ back, › forward, the [AddressField],
 * ⟳ Reload or ✕ Stop, and ⌨ (iOS's toolbar keyboard button, 910-924, for editors the Mac cannot
 * detect), greyed unless [keyboardEnabled]. Then, when [banner] is set, the Mac alert row. Its measured
 * height is the canvas's top inset, so the page sits below it.
 */
@Composable
internal fun BrowserBar(
    controls: BrowserControls,
    address: AddressFieldState,
    url: String?,
    onAddress: ((AddressFieldState) -> AddressFieldState) -> Unit,
    keyboardEnabled: Boolean,
    actions: BrowserBarActions,
    banner: String?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceContainer.copy(alpha = TOP_BAR_ALPHA))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .height(48.dp)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
                BarButton("←", "Back to sessions", enabled = true, onClick = actions::close, wide = true)
                BarButton("‹", "Back", controls.back, actions::goBack)
                BarButton("›", "Forward", controls.forward, actions::goForward)
                AddressField(
                    state = address,
                    url = url,
                    enabled = controls.address,
                    onUpdate = onAddress,
                    onGo = actions::go,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                if (controls.showsStop) {
                    BarButton("✕", "Stop loading", controls.reloadOrStop, actions::stop)
                } else {
                    BarButton("⟳", "Reload", controls.reloadOrStop, actions::reload)
                }
                BarButton("⌨", "Show or hide keyboard", keyboardEnabled, actions::toggleKeyboard)
            }
        }
        if (banner != null) LiveAlertBanner(banner)
    }
}

/** One of the bar's glyph buttons: 40 dp (48 dp when [wide]) so the address field keeps its room. */
@Composable
private fun BarButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit, wide: Boolean = false) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(if (wide) 48.dp else 40.dp).semantics { contentDescription = description },
    ) { Text(glyph, style = MaterialTheme.typography.titleMedium) }
}
