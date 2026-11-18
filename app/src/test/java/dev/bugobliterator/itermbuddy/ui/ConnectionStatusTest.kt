package dev.bugobliterator.itermbuddy.ui

import com.google.mlkit.common.MlKitException
import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.WaitReason
import dev.bugobliterator.itermbuddy.core.wire.MacStatus
import dev.bugobliterator.itermbuddy.ui.home.macAlertBanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

class ConnectionStatusTest {
    @Test
    fun macAlertBannerUsesIosWording() {
        assertNull(macAlertBanner(null))
        assertNull(macAlertBanner(MacStatus(mainBlocked = false, alertCount = 0)))
        // iOS's pill (iTerm2CompanionApp.swift:286) whenever an alert shows, blocked or not.
        assertEquals("Your Mac needs a response", macAlertBanner(MacStatus(mainBlocked = false, alertCount = 1)))
        assertEquals("Your Mac needs a response", macAlertBanner(MacStatus(mainBlocked = true, alertCount = 2)))
        // iOS's blocked banner (iTerm2CompanionApp.swift:296): busy with nothing to answer.
        assertEquals(
            "Your Mac is busy or showing a dialog that can\u2019t be answered from here",
            macAlertBanner(MacStatus(mainBlocked = true, alertCount = 0)),
        )
    }

    @Test
    fun codeIsShownAsTwoGroupsOfThree() {
        assertEquals("482 913", formatPairingCode("482913"))
        assertEquals("000 001", formatPairingCode("000001"))
    }

    @Test
    fun codeIsSpokenDigitByDigit() {
        assertEquals("Code 4 8 2 9 1 3", spokenPairingCode("482913"))
    }

    @Test
    fun showCodeScreenCarriesTheFormattedCode() {
        val content = screenContent(ClientState.ShowCode("482913"))
        assertEquals("Type this code on your Mac", content.title)
        assertEquals(CodeDisplay("482 913", "Code 4 8 2 9 1 3"), content.code)
        assertEquals(listOf(ButtonAction.CANCEL), content.buttons.map { it.action })
    }

    /** Every state, each variant of the ones with data, and the screen each one must get. */
    @Test
    fun everyStateHasItsScreen() {
        for (state in allStates()) {
            val content = screenContent(state)
            val name = state.toString()
            assertTrue("$name has a title", content.title.isNotBlank())
            assertTrue("$name has a button", content.buttons.isNotEmpty())
            // Exhaustive: a new ClientState fails to compile here until it has an expected screen.
            val expected: Expected = when (state) {
                ClientState.Unpaired -> Expected(
                    "Not paired",
                    "Open pairing in iTerm2 on your Mac, then scan the QR code it shows.",
                    listOf("Scan to pair" to ButtonAction.SCAN),
                )
                // Neutral: Idle also follows a cancel while the store was unreadable.
                ClientState.Idle -> Expected(
                    "Not connected",
                    null,
                    listOf("Connect" to ButtonAction.START, "Unpair" to ButtonAction.UNPAIR),
                )
                ClientState.Connecting -> Expected("Connecting…", null, CANCEL_ONLY, busy = true)
                is ClientState.WaitingForMac -> when (state.reason) {
                    WaitReason.MAC_OFFLINE ->
                        Expected("Waiting for your Mac", "Is iTerm2 running?", CANCEL_ONLY, busy = true)
                    WaitReason.RELAY_UNREACHABLE -> Expected(
                        "Can't reach the relay",
                        "Is Tailscale connected on this phone, and is the Mac awake?",
                        CANCEL_ONLY,
                        busy = true,
                    )
                    WaitReason.STORE_UNAVAILABLE -> Expected(
                        "Can't open this phone's secure storage right now",
                        "Retrying…",
                        CANCEL_ONLY,
                        busy = true,
                    )
                }
                is ClientState.ShowCode -> Expected("Type this code on your Mac", null, CANCEL_ONLY)
                is ClientState.Connected ->
                    Expected("Connected to iTerm2", null, listOf("Unpair" to ButtonAction.UNPAIR))
                is ClientState.Failed -> Expected(
                    "Not connected",
                    state.message,
                    if (state.rescanNeeded) {
                        listOf("Scan again" to ButtonAction.SCAN)
                    } else {
                        listOf("Try again" to ButtonAction.START, "Scan again" to ButtonAction.SCAN)
                    },
                )
            }
            assertEquals("$name title", expected.title, content.title)
            assertEquals("$name body", expected.body, content.body)
            assertEquals("$name buttons", expected.buttons, content.buttons.map { it.label to it.action })
            assertEquals("$name busy", expected.busy, content.busy)
            assertEquals("$name code", state is ClientState.ShowCode, content.code != null)
        }
    }

    @Test
    fun everyWaitingScreenAndConnectingOffersCancel() {
        val waiting = WaitReason.entries.map { ClientState.WaitingForMac(it) } + ClientState.Connecting
        for (state in waiting) {
            assertTrue("$state", screenContent(state).buttons.any { it.action == ButtonAction.CANCEL })
        }
    }

    @Test
    fun everyFailureHasAWayOutThroughScanning() {
        for (state in allStates().filterIsInstance<ClientState.Failed>()) {
            assertTrue("$state", screenContent(state).buttons.any { it.action == ButtonAction.SCAN })
        }
    }

    @Test
    fun scannerInstallingOffersTryAgainThatScans() {
        val states = listOf(
            ClientState.Unpaired,
            ClientState.Failed("Unpaired on the Mac", rescanNeeded = true),
            ClientState.Failed("The relay refused the connection (HTTP 403): forbidden", rescanNeeded = false),
        )
        for (state in states) {
            val content = screenContent(state, ScanNotice.Installing)
            assertEquals("$state", "The scanner is still installing; try again in a moment.", content.body)
            assertEquals("$state", listOf("Try again" to ButtonAction.SCAN), content.buttons.map { it.label to it.action })
        }
    }

    @Test
    fun scanProblemReplacesTheBodyAndKeepsTheScanButton() {
        val content = screenContent(ClientState.Unpaired, ScanNotice.Problem("This QR code is invalid."))
        assertEquals("This QR code is invalid.", content.body)
        assertEquals(listOf("Scan to pair" to ButtonAction.SCAN), content.buttons.map { it.label to it.action })
    }

    @Test
    fun scanNoticeIsIgnoredWhereNoScanIsOffered() {
        val notice = ScanNotice.Problem("This QR code is invalid.")
        for (state in allStates().filterNot { it == ClientState.Unpaired || it is ClientState.Failed }) {
            assertEquals("$state", screenContent(state), screenContent(state, notice))
        }
    }

    @Test
    fun unpairingIsBusyWithNoButtons() {
        for (state in allStates()) {
            assertEquals("$state", ScreenContent("Unpairing…", buttons = emptyList(), busy = true), screenContent(state, unpairing = true))
        }
    }

    @Test
    fun failedUnpairSaysSoAndKeepsTheButtons() {
        for (state in listOf(ClientState.Idle, ClientState.Connected(11))) {
            val content = screenContent(state, unpairFailed = true)
            assertEquals("$state", "Couldn't unpair; try again.", content.body)
            assertEquals("$state", screenContent(state).buttons, content.buttons)
        }
    }

    @Test
    fun eachButtonActionCallsItsAction() {
        val calls = mutableListOf<String>()
        val actions = object : PairingActions {
            override fun scan() {
                calls += "scan"
            }

            override fun start() {
                calls += "start"
            }

            override fun cancel() {
                calls += "cancel"
            }

            override fun unpair() {
                calls += "unpair"
            }
        }
        for (action in ButtonAction.entries) perform(action, actions)
        assertEquals(listOf("scan", "start", "cancel", "unpair"), calls)
        assertEquals(listOf(ButtonAction.SCAN, ButtonAction.START, ButtonAction.CANCEL, ButtonAction.UNPAIR), ButtonAction.entries)
    }

    @Test
    fun rejectedScanShowsItsMessageNotItsReason() {
        val outcome = scanOutcome("https://example.com/not-a-pairing-code")
        assertEquals(ScanOutcome.Notice(ScanNotice.Problem("This QR code is not an iTerm2 pairing code.")), outcome)
    }

    @Test
    fun scanWithoutRelayShowsTheSettingsHint() {
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
        val outcome = scanOutcome("iterm2://pair?v=1&proto=Noise_XK_25519_ChaChaPoly_BLAKE2s&rs=$key&pid=abc")
        val message = ((outcome as ScanOutcome.Notice).notice as ScanNotice.Problem).message
        assertTrue(message, message.startsWith("This QR has no relay origin."))
        assertFalse(message, message.contains("missingRelay"))
    }

    @Test
    fun nullScanTextIsParsedAsEmpty() {
        assertEquals(ScanOutcome.Notice(ScanNotice.Problem("This QR code is not an iTerm2 pairing code.")), scanOutcome(null))
    }

    @Test
    fun validScanPairs() {
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
        val outcome = scanOutcome(
            "iterm2://pair?v=1&proto=Noise_XK_25519_ChaChaPoly_BLAKE2s&rs=$key&pid=abc" +
                "&relay=https://relay.example.ts.net",
        )
        val code = (outcome as ScanOutcome.Scanned).code
        assertEquals("abc", code.pairingID)
        assertEquals("https://relay.example.ts.net", code.relayOrigin)
    }

    @Test
    fun scannerFailuresMapToNotices() {
        // MlKitException's constructor calls android.text.TextUtils, so the mapping is tested by code.
        fun failure(code: Int) = scanErrorOutcome(code)
        assertEquals(ScanOutcome.Quiet, failure(MlKitException.CODE_SCANNER_CANCELLED))
        assertEquals(ScanOutcome.Quiet, failure(MlKitException.CODE_SCANNER_TASK_IN_PROGRESS))
        assertEquals(ScanOutcome.Notice(ScanNotice.Installing), failure(MlKitException.CODE_SCANNER_UNAVAILABLE))
        assertEquals(
            ScanOutcome.Notice(ScanNotice.Problem(CAMERA_DENIED)),
            failure(MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED),
        )
        assertEquals(
            ScanOutcome.Notice(ScanNotice.Problem(PLAY_SERVICES_TOO_OLD)),
            failure(MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD),
        )
        assertEquals(
            ScanOutcome.Notice(ScanNotice.Problem(SCANNER_FAILED)),
            failure(MlKitException.CODE_SCANNER_PIPELINE_INITIALIZATION_ERROR),
        )
        assertEquals(ScanOutcome.Notice(ScanNotice.Problem(SCANNER_FAILED)), scanErrorOutcome(null))
        assertEquals(ScanOutcome.Notice(ScanNotice.Problem(SCANNER_FAILED)), scanFailureOutcome(IOException("x")))
    }

    @Test
    fun scannerConstantsMatchThePlayServicesReference() {
        assertEquals(200, MlKitException.CODE_SCANNER_UNAVAILABLE)
        assertEquals(201, MlKitException.CODE_SCANNER_CANCELLED)
    }

    private data class Expected(
        val title: String,
        val body: String?,
        val buttons: List<Pair<String, ButtonAction>>,
        val busy: Boolean = false,
    )

    private companion object {
        val CANCEL_ONLY = listOf("Cancel" to ButtonAction.CANCEL)

        /** One of each state, with every Failed message CompanionClient produces (copied from it). */
        fun allStates(): List<ClientState> = listOf(
            ClientState.Unpaired,
            ClientState.Idle,
            ClientState.Connecting,
            ClientState.ShowCode("482913"),
            ClientState.Connected(macRevision = 11),
        ) + WaitReason.entries.map { ClientState.WaitingForMac(it) } + listOf(
            // TLS certificate failure.
            ClientState.Failed(
                "The relay's certificate was rejected. Check the relay address and its tailscale serve certificate.",
                rescanNeeded = false,
            ),
            // Fatal HTTP status at admission.
            ClientState.Failed("The relay refused the connection (HTTP 403): forbidden", rescanNeeded = false),
            // Three bad signatures in a row.
            ClientState.Failed(
                "The relay refused this phone's signature. Check the relay address matches iTerm2's " +
                    "companionRelayOrigin, or another device may have taken the relay room. If iTerm2 still " +
                    "lists this phone, unpair it on the Mac, then scan again.",
                rescanNeeded = true,
            ),
            ClientState.Failed("Update the app: this iTerm2 needs a newer iTerm2 Buddy.", rescanNeeded = false),
            ClientState.Failed("Update iTerm2: this app needs iTerm2 3.7.7 or later.", rescanNeeded = false),
            ClientState.Failed("Unpaired on the Mac", rescanNeeded = true),
            ClientState.Failed("Something went wrong: boom", rescanNeeded = false),
            ClientState.Failed("The code was rejected on your Mac. Scan again to pair.", rescanNeeded = true),
            ClientState.Failed(
                "iTerm2 isn't showing that pairing code any more. Open pairing on the Mac and scan again.",
                rescanNeeded = true,
            ),
            ClientState.Failed("The code wasn't entered on your Mac in time. Scan again to pair.", rescanNeeded = true),
            ClientState.Failed("Your Mac couldn't save the pairing. Check iTerm2, then reconnect.", rescanNeeded = false),
            ClientState.Failed("The relay closed this pairing. Open pairing on the Mac and scan again.", rescanNeeded = true),
            ClientState.Failed("The stored relay address can't be used. Scan again.", rescanNeeded = true),
            ClientState.Failed("This phone couldn't save the pairing. Scan again.", rescanNeeded = true),
        )
    }
}
