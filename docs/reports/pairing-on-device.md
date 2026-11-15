# Pairing on device

The owner fills this in on the Mac with the Pixel, after the Pairing Screens task. Write what
happened under each **Result** line: pass or fail, plus anything unexpected (screen text, timing,
relay log lines).

- Date:
- Phone and Android version:
- iTerm2 version: 3.7.7
- Relay commit: `b8aa2f9`

## Prerequisites

- [ ] Tailscale is connected on the Pixel, and the phone can reach the Mac on the tailnet.
- [ ] The relay is running on the Mac under launchd behind `tailscale serve` (not `funnel`),
      listening on `127.0.0.1`.
- [ ] iTerm2's `companionRelayOrigin` is exactly `https://relay.example.ts.net`,
      and `companionResolverURL` is empty.
- [ ] The APK is the one the build reported:

      shasum -a 256 app-debug.apk

  Expected: `6b1d0b69b261df6915031d862613c6feb8ffc28e6fcf16f47386953c59b9bc36`

  **Result:**

## Steps

1. `adb install -r app-debug.apk`, start pairing in iTerm2, tap **Scan to pair**, scan the QR,
   and type the code shown on the phone (`482 913` style) on the Mac.
   Expected: **Connected to iTerm2**.

   **Result:**

2. Force-stop the app (`adb shell am force-stop dev.bugobliterator.itermbuddy`) and reopen it.
   Expected: Connected without scanning.

   **Result:**

3. Quit iTerm2. Expected: **Waiting for your Mac** ("Is iTerm2 running?"). Relaunch iTerm2.
   Expected: Connected.

   **Result:**

4. Tap **Unpair** on the phone. Expected: the phone shows **Scan to pair**, and iTerm2 shows the
   phone as unpaired.

   **Result:**

5. Pair again, then type a wrong code three times on the Mac, or cancel the Mac's code dialog.
   One wrong code only shows "That code doesn't match" on the Mac. Expected: a failure with
   **Scan again** on the phone.

   **Result:**

6. Scan a QR, then wait more than 2 minutes with iTerm2's pairing window closed.
   Expected: **Scan again**, not an endless wait.

   **Result:**

7. Grep the relay log for `bad signature`. Expected: none.

   **Result:**

## Extra checks

- **Instrumented `KeystorePairingStoreTest`** (AndroidKeyStore on the real device). Run it
  after the steps above: the test clears the app's pairing store, so the phone is unpaired
  afterwards. Use `adb` rather than `./gradlew connectedDebugAndroidTest`, which uninstalls the
  app when it finishes. Both APKs must come from the same cloud build, so they share a debug
  signing key: `app/build/outputs/apk/debug/app-debug.apk` and
  `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

      adb install -r app-debug.apk
      adb install -r app-debug-androidTest.apk
      adb shell am instrument -w \
        -e class dev.bugobliterator.itermbuddy.store.KeystorePairingStoreTest \
        dev.bugobliterator.itermbuddy.test/androidx.test.runner.AndroidJUnitRunner

  With a checkout and SDK on the machine that holds the phone, this does the same, then uninstalls
  both APKs:

      ./gradlew connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.class=dev.bugobliterator.itermbuddy.store.KeystorePairingStoreTest

  Expected: `OK (n tests)`.

  **Result:**

- **Camera permission.** Open Settings → Apps → iTerm2 Buddy → Permissions. Expected: no
  camera permission listed; the scanner's camera access belongs to Google Play services.

  **Result:**

## Notes
