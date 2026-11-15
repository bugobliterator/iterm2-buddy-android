package dev.bugobliterator.itermbuddy.ui

import com.google.mlkit.common.MlKitException
import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.WaitReason
import dev.bugobliterator.itermbuddy.core.pairing.PairingCode
import dev.bugobliterator.itermbuddy.core.pairing.PairingCodeResult
import dev.bugobliterator.itermbuddy.core.pairing.parsePairingCode

/** What a button does; [PairingScreen] maps each one to a [PairingActions] call. */
enum class ButtonAction {
    /** Open the code scanner. */
    SCAN,

    /** [dev.bugobliterator.itermbuddy.core.CompanionClient.start]: resume the stored pairing. */
    START,

    /** [dev.bugobliterator.itermbuddy.core.CompanionClient.cancel]. */
    CANCEL,

    /** [dev.bugobliterator.itermbuddy.core.CompanionClient.unpair]. */
    UNPAIR,
}

data class ScreenButton(val label: String, val action: ButtonAction)

/** The pairing code as shown: [text] is `482 913`, [spoken] is what TalkBack reads, digit by digit. */
data class CodeDisplay(val text: String, val spoken: String)

/**
 * Everything one pairing screen shows, as plain data: [PairingScreen] only lays it out, so the
 * wording and the buttons for every state are tested on the JVM.
 *
 * @property busy shows a progress indicator: the client is working or retrying.
 */
data class ScreenContent(
    val title: String,
    val body: String? = null,
    val code: CodeDisplay? = null,
    val buttons: List<ScreenButton>,
    val busy: Boolean = false,
)

/** Something the code scanner reported, shown on the screens that offer a scan. */
sealed interface ScanNotice {
    /** The scanner module is still downloading (first use). */
    data object Installing : ScanNotice

    /** A scanned code was refused or the scanner failed; [message] is user-facing. */
    data class Problem(val message: String) : ScanNotice
}

/** What became of one scan. */
sealed interface ScanOutcome {
    data class Scanned(val code: PairingCode) : ScanOutcome

    data class Notice(val notice: ScanNotice) : ScanOutcome

    /** The user backed out, or a scan was already open: return to the button quietly. */
    data object Quiet : ScanOutcome
}

/**
 * A scanned QR's text, through [parsePairingCode]. A rejection shows its user-facing message,
 * never its reason code. A null [text] (a barcode with no text) is parsed as empty.
 */
fun scanOutcome(text: String?): ScanOutcome = when (val result = parsePairingCode(text.orEmpty())) {
    is PairingCodeResult.Ok -> ScanOutcome.Scanned(result.code)
    is PairingCodeResult.Rejected -> ScanOutcome.Notice(ScanNotice.Problem(result.message))
}

/** A failed scan task, by its [MlKitException.getErrorCode]; anything else is a generic problem. */
fun scanFailureOutcome(error: Exception): ScanOutcome = scanErrorOutcome((error as? MlKitException)?.errorCode)

/** [scanFailureOutcome] by error code (null when the error was not an [MlKitException]). */
internal fun scanErrorOutcome(code: Int?): ScanOutcome =
    when (code) {
        MlKitException.CODE_SCANNER_CANCELLED, MlKitException.CODE_SCANNER_TASK_IN_PROGRESS -> ScanOutcome.Quiet
        MlKitException.CODE_SCANNER_UNAVAILABLE -> ScanOutcome.Notice(ScanNotice.Installing)
        MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED -> ScanOutcome.Notice(ScanNotice.Problem(CAMERA_DENIED))
        MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD ->
            ScanOutcome.Notice(ScanNotice.Problem(PLAY_SERVICES_TOO_OLD))
        else -> ScanOutcome.Notice(ScanNotice.Problem(SCANNER_FAILED))
    }

/** `482913` as `482 913`: groups of three from the left. */
fun formatPairingCode(code: String): String = code.chunked(CODE_GROUP).joinToString(" ")

/** The code for TalkBack, one digit at a time: `Code 4 8 2 9 1 3`. */
fun spokenPairingCode(code: String): String = "Code " + code.filter { !it.isWhitespace() }.toList().joinToString(" ")

/** The screen for [state]; [notice] is the latest scanner report, shown where a scan is offered. */
fun screenContent(state: ClientState, notice: ScanNotice? = null): ScreenContent = when (state) {
    ClientState.Unpaired -> scanScreen(
        title = "Not paired",
        body = "Open pairing in iTerm2 on your Mac, then scan the QR code it shows.",
        scanLabel = "Scan to pair",
        notice = notice,
    )
    ClientState.Idle -> ScreenContent(
        title = "Paired with iTerm2, not connected",
        buttons = listOf(ScreenButton("Connect", ButtonAction.START), UNPAIR),
    )
    ClientState.Connecting -> ScreenContent(title = "Connecting…", buttons = listOf(CANCEL), busy = true)
    is ClientState.WaitingForMac -> when (state.reason) {
        WaitReason.MAC_OFFLINE -> ScreenContent(
            title = "Waiting for your Mac",
            body = "Is iTerm2 running?",
            buttons = listOf(CANCEL),
            busy = true,
        )
        WaitReason.RELAY_UNREACHABLE -> ScreenContent(
            title = "Can't reach the relay",
            body = "Is Tailscale connected on this phone, and is the Mac awake?",
            buttons = listOf(CANCEL),
            busy = true,
        )
        WaitReason.STORE_UNAVAILABLE -> ScreenContent(
            title = "Can't open this phone's secure storage right now",
            body = "Retrying…",
            buttons = listOf(CANCEL),
            busy = true,
        )
    }
    is ClientState.ShowCode -> ScreenContent(
        title = "Type this code on your Mac",
        code = CodeDisplay(formatPairingCode(state.code), spokenPairingCode(state.code)),
        buttons = listOf(CANCEL),
    )
    is ClientState.Connected -> ScreenContent(title = "Connected to iTerm2", buttons = listOf(UNPAIR))
    is ClientState.Failed ->
        if (state.rescanNeeded) {
            scanScreen(title = "Not connected", body = state.message, scanLabel = "Scan again", notice = notice)
        } else {
            // The pairing is kept; start() reconnects it, or goes Unpaired if it has since gone.
            ScreenContent(
                title = "Not connected",
                body = state.message,
                buttons = listOf(ScreenButton("Try again", ButtonAction.START)),
            )
        }
}

/** A screen whose button opens the scanner; a scanner notice replaces [body]. */
private fun scanScreen(title: String, body: String, scanLabel: String, notice: ScanNotice?): ScreenContent =
    when (notice) {
        null -> ScreenContent(title, body, buttons = listOf(ScreenButton(scanLabel, ButtonAction.SCAN)))
        ScanNotice.Installing ->
            ScreenContent(title, SCANNER_INSTALLING, buttons = listOf(ScreenButton("Try again", ButtonAction.SCAN)))
        is ScanNotice.Problem ->
            ScreenContent(title, notice.message, buttons = listOf(ScreenButton(scanLabel, ButtonAction.SCAN)))
    }

private val CANCEL = ScreenButton("Cancel", ButtonAction.CANCEL)
private val UNPAIR = ScreenButton("Unpair", ButtonAction.UNPAIR)
private const val CODE_GROUP = 3

internal const val SCANNER_INSTALLING = "The scanner is still installing; try again in a moment."
internal const val CAMERA_DENIED =
    "The scanner needs the camera. Allow camera access for Google Play services, then try again."
internal const val PLAY_SERVICES_TOO_OLD = "The scanner needs a newer Google Play services. Update it, then try again."
internal const val SCANNER_FAILED = "The scanner couldn't start. Try again."
