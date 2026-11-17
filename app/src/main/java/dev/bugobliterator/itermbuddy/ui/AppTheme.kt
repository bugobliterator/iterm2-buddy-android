package dev.bugobliterator.itermbuddy.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The app's theme: Material 3 with the wallpaper's dynamic colours (Android 12+, always present at
 * minSdk 34), light or dark as the system setting says. The session list, Settings and the
 * pairing screens use it; the live session view is always dark ([LiveSessionTheme]).
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = remember(context, dark) { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * The live session view's theme: always the dark dynamic scheme, whatever the system setting, so
 * the bar over the picture, the overlays, the ⋮ menu, the key bar and the composer sit dark around
 * the black video. While it is shown the status and navigation bar icons are light; on leaving,
 * they go back to what they were.
 */
@Composable
fun LiveSessionTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = controller?.isAppearanceLightStatusBars
        val lightNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (lightStatus != null) controller.isAppearanceLightStatusBars = lightStatus
            if (lightNavigation != null) controller.isAppearanceLightNavigationBars = lightNavigation
        }
    }
    val context = LocalContext.current
    val scheme = remember(context) { dynamicDarkColorScheme(context) }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
