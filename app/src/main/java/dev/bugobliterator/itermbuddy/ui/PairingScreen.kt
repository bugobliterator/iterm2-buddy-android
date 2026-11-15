package dev.bugobliterator.itermbuddy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bugobliterator.itermbuddy.core.ClientState
import dev.bugobliterator.itermbuddy.core.WaitReason

/** What the pairing screens can ask for. */
interface PairingActions {
    fun scan()

    fun start()

    fun cancel()

    fun unpair()
}

/** The screen for [state]: a thin layout of [screenContent], with no state of its own. */
@Composable
fun PairingScreen(
    state: ClientState,
    actions: PairingActions,
    modifier: Modifier = Modifier,
    scanNotice: ScanNotice? = null,
) {
    ScreenLayout(screenContent(state, scanNotice), actions, modifier)
}

@Composable
private fun ScreenLayout(content: ScreenContent, actions: PairingActions, modifier: Modifier) {
    Scaffold(modifier = modifier.fillMaxSize()) { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = content.title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            content.code?.let { code ->
                Text(
                    text = code.text,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = code.spoken },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 56.sp,
                    style = MaterialTheme.typography.displayLarge,
                )
            }
            content.body?.let { body ->
                Text(text = body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
            if (content.busy) LinearProgressIndicator(modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth())
            content.buttons.forEachIndexed { index, button ->
                val onClick = { perform(button.action, actions) }
                val buttonModifier = Modifier.widthIn(min = 200.dp)
                if (index == 0) {
                    Button(onClick = onClick, modifier = buttonModifier) { Text(button.label) }
                } else {
                    OutlinedButton(onClick = onClick, modifier = buttonModifier) { Text(button.label) }
                }
            }
        }
    }
}

private fun perform(action: ButtonAction, actions: PairingActions) = when (action) {
    ButtonAction.SCAN -> actions.scan()
    ButtonAction.START -> actions.start()
    ButtonAction.CANCEL -> actions.cancel()
    ButtonAction.UNPAIR -> actions.unpair()
}

private object NoActions : PairingActions {
    override fun scan() = Unit

    override fun start() = Unit

    override fun cancel() = Unit

    override fun unpair() = Unit
}

@Composable
private fun PreviewOf(state: ClientState, notice: ScanNotice? = null) {
    MaterialTheme { PairingScreen(state, NoActions, scanNotice = notice) }
}

@Preview(showBackground = true)
@Composable
private fun UnpairedPreview() = PreviewOf(ClientState.Unpaired)

@Preview(showBackground = true)
@Composable
private fun UnpairedScannerInstallingPreview() = PreviewOf(ClientState.Unpaired, ScanNotice.Installing)

@Preview(showBackground = true)
@Composable
private fun UnpairedQrRejectedPreview() =
    PreviewOf(ClientState.Unpaired, ScanNotice.Problem("This QR code is not an iTerm2 pairing code."))

@Preview(showBackground = true)
@Composable
private fun IdlePreview() = PreviewOf(ClientState.Idle)

@Preview(showBackground = true)
@Composable
private fun ConnectingPreview() = PreviewOf(ClientState.Connecting)

@Preview(showBackground = true)
@Composable
private fun WaitingMacOfflinePreview() = PreviewOf(ClientState.WaitingForMac(WaitReason.MAC_OFFLINE))

@Preview(showBackground = true)
@Composable
private fun WaitingRelayUnreachablePreview() = PreviewOf(ClientState.WaitingForMac(WaitReason.RELAY_UNREACHABLE))

@Preview(showBackground = true)
@Composable
private fun WaitingStoreUnavailablePreview() = PreviewOf(ClientState.WaitingForMac(WaitReason.STORE_UNAVAILABLE))

@Preview(showBackground = true)
@Composable
private fun ShowCodePreview() = PreviewOf(ClientState.ShowCode("482913"))

@Preview(showBackground = true)
@Composable
private fun ConnectedPreview() = PreviewOf(ClientState.Connected(macRevision = 15))

@Preview(showBackground = true)
@Composable
private fun FailedRescanPreview() =
    PreviewOf(ClientState.Failed("The code was rejected on your Mac. Scan again to pair.", rescanNeeded = true))

@Preview(showBackground = true)
@Composable
private fun FailedTryAgainPreview() = PreviewOf(
    ClientState.Failed(
        "The relay's certificate was rejected. Check the relay address and its tailscale serve certificate.",
        rescanNeeded = false,
    ),
)
