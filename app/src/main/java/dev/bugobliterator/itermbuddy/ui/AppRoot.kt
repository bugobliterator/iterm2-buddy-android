package dev.bugobliterator.itermbuddy.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.WaitReason
import dev.bugobliterator.itermbuddy.core.chat.AgentState
import dev.bugobliterator.itermbuddy.core.chat.ChatRow
import dev.bugobliterator.itermbuddy.ui.home.ChatsHome
import dev.bugobliterator.itermbuddy.ui.home.HomeActions
import dev.bugobliterator.itermbuddy.ui.home.HomeModel
import dev.bugobliterator.itermbuddy.ui.home.HomeScaffold

/** The root screen. */
enum class AppScreen {
    /** A blank scaffold while the store is first read: never a pairing screen for a paired user. */
    LOADING,

    /** The pairing screens ([PairingScreen]): unpaired, a fresh pairing, the code, a failure. */
    PAIRING,

    /** The home ([dev.bugobliterator.itermbuddy.ui.home.HomeScaffold]). */
    HOME,
}

/**
 * The home's tabs (`IOS/App/iTerm2CompanionApp.swift:157-176`), each with its own back stack
 * ([HomeNavigator]) whose [root] is its list.
 */
enum class HomeTab(val root: HomeDestination) {
    CHATS(HomeDestination.ChatList),
    SESSIONS(HomeDestination.SessionList),
}

/** The bottom bar shows on the tab roots only, so a pushed screen (the live view above all) keeps the whole screen. */
fun showsBottomBar(destination: HomeDestination): Boolean = HomeTab.entries.any { it.root == destination }

/**
 * Whether the Chats item in the bottom bar shows a dot: a chat is unread, or its agent waits on
 * the owner (an Android addition; iOS's tab has no badge).
 */
fun chatsNeedAttention(rows: List<ChatRow>): Boolean = rows.any { it.unread || it.state == AgentState.WAITING }

/**
 * [AppScreen.LOADING] only while [paired] is unknown and the client is [ClientState.Connecting]
 * (the first store read); any other state with [paired] unknown (the store unavailable, a
 * failure, a cancel) is the pairing screen, as before the home existed, so nothing is blank for
 * long. [AppScreen.HOME] for a confirmed pairing that is
 * connected, connecting, waiting for the Mac or idle (with a banner, so the 30 s screen-timeout
 * drop does not bounce to the pairing screens); else [AppScreen.PAIRING].
 */
fun appScreenFor(state: ClientState, paired: Boolean?): AppScreen = when {
    paired == null -> if (state == ClientState.Connecting) AppScreen.LOADING else AppScreen.PAIRING
    paired && (
        state is ClientState.Connected || state == ClientState.Connecting ||
            state is ClientState.WaitingForMac || state == ClientState.Idle
        ) -> AppScreen.HOME
    else -> AppScreen.PAIRING
}

/**
 * The home's banner for [state]: null when connected (and for states the home never shows); for
 * waits the pairing screen's own wording, as one line.
 */
fun connectionBanner(state: ClientState): String? = when (state) {
    ClientState.Connecting -> "Reconnecting…"
    is ClientState.WaitingForMac -> when (state.reason) {
        WaitReason.MAC_OFFLINE -> "Waiting for your Mac. Is iTerm2 running?"
        WaitReason.RELAY_UNREACHABLE -> "Can't reach the relay. Is Tailscale connected on this phone, and is the Mac awake?"
        WaitReason.STORE_UNAVAILABLE -> "Can't open this phone's secure storage right now. Retrying…"
    }
    ClientState.Idle -> "Not connected."
    is ClientState.Connected, ClientState.Unpaired, is ClientState.ShowCode, is ClientState.Failed -> null
}

/** Whether the banner has a Reconnect action ([dev.bugobliterator.itermbuddy.core.CompanionClient.start]): only when idle. */
fun bannerOffersReconnect(state: ClientState): Boolean = state == ClientState.Idle

/** The top bar's short status: "Connected" or the banner's first words. */
fun connectionLabel(state: ClientState): String = when (state) {
    is ClientState.Connected -> "Connected"
    ClientState.Connecting -> "Reconnecting"
    is ClientState.WaitingForMac -> "Waiting"
    else -> "Not connected"
}

/**
 * The app's root: [appScreenFor] picks a blank scaffold, the pairing screens or the home. It holds
 * no state and starts or stops nothing; every input comes from app-scoped flows.
 */
@Composable
fun AppRoot(
    paired: Boolean?,
    home: HomeModel,
    homeActions: HomeActions,
    chats: ChatsHome,
    pairingActions: PairingActions,
    scanNotice: ScanNotice?,
    modifier: Modifier = Modifier,
) {
    when (appScreenFor(home.state, paired)) {
        AppScreen.LOADING -> Surface(modifier = modifier.fillMaxSize()) { }
        AppScreen.PAIRING -> PairingScreen(
            home.state,
            pairingActions,
            modifier,
            scanNotice = scanNotice,
            unpairing = home.unpairing,
            unpairFailed = home.unpairFailed,
        )
        AppScreen.HOME -> HomeScaffold(home, homeActions, chats, modifier)
    }
}
