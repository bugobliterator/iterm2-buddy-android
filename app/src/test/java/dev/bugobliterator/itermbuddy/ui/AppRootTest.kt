package dev.bugobliterator.itermbuddy.ui

import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.WaitReason
import dev.bugobliterator.itermbuddy.core.chat.AgentState
import dev.bugobliterator.itermbuddy.core.chat.ChatListEntry
import dev.bugobliterator.itermbuddy.core.chat.ChatRow
import dev.bugobliterator.itermbuddy.core.chat.ChatSummary
import dev.bugobliterator.itermbuddy.core.sessions.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which root screen shows, and the home's connection banner. */
class AppRootTest {
    @Test
    fun homeWhilePairedAndReconnecting() {
        val home = listOf(
            ClientState.Connected(macRevision = 15),
            ClientState.Connecting,
            ClientState.WaitingForMac(WaitReason.MAC_OFFLINE),
            ClientState.WaitingForMac(WaitReason.RELAY_UNREACHABLE),
            ClientState.WaitingForMac(WaitReason.STORE_UNAVAILABLE),
            ClientState.Idle,
        )
        for (state in home) assertEquals("$state", AppScreen.HOME, appScreenFor(state, paired = true))
        // The same states for a pairing not yet confirmed are the pairing flow.
        for (state in home - ClientState.Connected(macRevision = 15)) {
            assertEquals("$state", AppScreen.PAIRING, appScreenFor(state, paired = false))
        }
    }

    @Test
    fun pairingWhileShowingCode() {
        assertEquals(AppScreen.PAIRING, appScreenFor(ClientState.ShowCode("482913"), paired = false))
        assertEquals(AppScreen.PAIRING, appScreenFor(ClientState.Unpaired, paired = false))
    }

    @Test
    fun pairingWhenFailed() {
        val rescan = ClientState.Failed("Unpaired on the Mac", rescanNeeded = true)
        val keep = ClientState.Failed("The relay's certificate was rejected.", rescanNeeded = false)
        assertEquals(AppScreen.PAIRING, appScreenFor(rescan, paired = false))
        assertEquals(AppScreen.PAIRING, appScreenFor(keep, paired = true))
    }

    @Test
    fun coldStartShowsLoadingNotPairing() {
        assertEquals(AppScreen.LOADING, appScreenFor(ClientState.Connecting, paired = null))
    }

    /** Only the first store read is blank: a store that stays unavailable, or a failure, shows the pairing screens. */
    @Test
    fun unknownPairingShowsPairingScreensExceptWhileConnecting() {
        assertEquals(AppScreen.PAIRING, appScreenFor(ClientState.WaitingForMac(WaitReason.STORE_UNAVAILABLE), paired = null))
        assertEquals(AppScreen.PAIRING, appScreenFor(ClientState.Failed("This phone couldn't save the pairing. Scan again.", rescanNeeded = true), paired = null))
        assertEquals(AppScreen.PAIRING, appScreenFor(ClientState.Idle, paired = null))
        assertEquals(AppScreen.PAIRING, appScreenFor(ClientState.Unpaired, paired = null))
    }

    @Test
    fun bannerTexts() {
        assertNull(connectionBanner(ClientState.Connected(macRevision = 15)))
        assertEquals("Reconnecting…", connectionBanner(ClientState.Connecting))
        assertEquals("Waiting for your Mac. Is iTerm2 running?", connectionBanner(ClientState.WaitingForMac(WaitReason.MAC_OFFLINE)))
        assertEquals(
            "Can't reach the relay. Is Tailscale connected on this phone, and is the Mac awake?",
            connectionBanner(ClientState.WaitingForMac(WaitReason.RELAY_UNREACHABLE)),
        )
        assertEquals(
            "Can't open this phone's secure storage right now. Retrying…",
            connectionBanner(ClientState.WaitingForMac(WaitReason.STORE_UNAVAILABLE)),
        )
        assertEquals("Not connected.", connectionBanner(ClientState.Idle))
        assertTrue(bannerOffersReconnect(ClientState.Idle))
        for (state in listOf(ClientState.Connecting, ClientState.Connected(15), ClientState.WaitingForMac(WaitReason.MAC_OFFLINE))) {
            assertFalse("$state", bannerOffersReconnect(state))
        }
    }

    @Test
    fun bottomBarOnlyOnTabRoots() {
        assertEquals(listOf(HomeTab.CHATS, HomeTab.SESSIONS), HomeTab.entries.toList())
        assertTrue(showsBottomBar(HomeDestination.ChatList))
        assertTrue(showsBottomBar(HomeDestination.SessionList))
        val session = SessionSummary("ptys_7K2M9Q4R8T1VH", "zsh", "~", false)
        for (destination in listOf(
            HomeDestination.Settings,
            HomeDestination.NewChat,
            HomeDestination.Conversation("C0DE0000-0000-4000-8000-000000000001"),
            HomeDestination.Workgroup("wg-1", "crew"),
            HomeDestination.LiveSession(session, "zsh"),
            HomeDestination.BrowserSession(session.copy(isBrowser = true), "docs"),
        )) {
            assertFalse("$destination", showsBottomBar(destination))
        }
        assertEquals(HomeDestination.ChatList, HomeTab.CHATS.root)
        assertEquals(HomeDestination.SessionList, HomeTab.SESSIONS.root)
    }

    @Test
    fun chatsTabDotForUnreadOrWaiting() {
        fun row(id: String, state: AgentState, unread: Boolean) =
            ChatRow(ChatListEntry(ChatSummary(id, "t", 1.0, 2.0, false, null, null, emptyList(), null), null, false), state, unread)
        assertFalse(chatsNeedAttention(emptyList()))
        assertFalse(chatsNeedAttention(listOf(row("a", AgentState.WORKING, false), row("b", AgentState.DONE, false), row("c", AgentState.IDLE, false))))
        assertTrue(chatsNeedAttention(listOf(row("a", AgentState.IDLE, false), row("b", AgentState.WAITING, false))))
        assertTrue(chatsNeedAttention(listOf(row("a", AgentState.DONE, true))))
    }
}
