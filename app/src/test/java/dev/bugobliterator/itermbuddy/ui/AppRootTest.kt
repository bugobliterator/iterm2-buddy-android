package dev.bugobliterator.itermbuddy.ui

import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.WaitReason
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
        assertEquals(AppScreen.LOADING, appScreenFor(ClientState.Unpaired, paired = null))
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
    fun oneHomeTabShowsNoBottomBar() {
        assertEquals(listOf(HomeTab.SESSIONS), HomeTab.entries.toList())
        assertFalse(showsBottomBar())
    }
}
