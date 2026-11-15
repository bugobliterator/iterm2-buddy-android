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
- [ ] iTerm2's `companionRelayOrigin` is exactly `https://siddharths-macbook-pro.tailnet-99bb.ts.net`,
      and `companionResolverURL` is empty.
- [ ] The APKs are the ones the build reported:

      shasum -a 256 app-debug.apk app-debug-androidTest.apk

  Expected:
  - `app-debug.apk`: `ec9764cfb6c84bc49e804022c38b2bf4877703d14de3eecbeb3b1761cb280914`
  - `app-debug-androidTest.apk`: `611a45b4762b0fd52d1cd1891daeafb6cba4fe46861e1d2255a69d3e31058108`

  **Result:**

- If `adb install -r` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the phone holds a build
  signed with another debug key (another cloud session). Uninstall it first, which also deletes
  the pairing: `adb uninstall dev.bugobliterator.itermbuddy` (and
  `adb uninstall dev.bugobliterator.itermbuddy.test` for the test APK).
- Start a log capture before step 1 and keep it running through step 7:

      adb logcat -c
      adb logcat > pairing-logcat.txt

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

- **Scanning without ACCESS_NETWORK_STATE.** The app declares INTERNET only; the permission ML
  Kit's telemetry asks for is removed from the manifest. Step 1's scan must work, and the log
  must hold no crash from it:

      grep -E 'FATAL EXCEPTION|SecurityException' pairing-logcat.txt

  Expected: no `FATAL EXCEPTION`. A caught `SecurityException` logged by datatransport (tags
  such as `JobInfoScheduler`, `Executor` or `DefaultScheduler`) is expected and harmless. If the
  app crashed, restore the permission (delete the `tools:node="remove"` entry in
  `app/src/main/AndroidManifest.xml`) and note it here.

  **Result:**

- **The QR text is never logged.** It carries the Mac's key and pairing ID.

      grep -i 'iterm2://' pairing-logcat.txt

  Expected: no lines.

  **Result:**

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
