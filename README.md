# iTerm2 Buddy for Android

An Android companion for [iTerm2](https://iterm2.com): chat with the AI agent on your Mac and approve its actions, get notifications, and view and type into terminal sessions.

A Kotlin port of the iOS iTerm2 Buddy app (`Companion/` in the [iTerm2 source](https://gitlab.com/gnachman/iterm2)). It connects to a self-hosted [companion relay](https://github.com/gnachman/iterm2-companion-relay) reached over Tailscale, since the public relays only admit Apple App Attest devices.

Status: design in progress.

## Build

In a fresh cloud session, run `scripts/cloud-setup.sh` first. It installs JDK 17 and the Android SDK under `$HOME` and writes `local.properties`. Then build with JDK 17:

```sh
export JAVA_HOME="$HOME/jdk/java-17-openjdk-amd64"
./gradlew testDebugUnitTest lint assembleDebug
```

## Installing test builds (Firebase App Distribution)

Every push to `main` builds a debug APK signed with the project's shared debug key and sends it to the `owner` tester group in Firebase App Distribution.

1. Accept the tester invite email from Firebase App Distribution on the Pixel.
2. Follow the invite to install the Firebase App Tester flow, then install the build from it.
3. Each later push to `main` sends a new build, for example `0.1.0-5c99c6a`; App Tester notifies you; installing it over the previous build keeps the pairing.

The first install of a shared-key build fails if the phone holds a build signed with another key (from a cloud session or a local build). Uninstall that build first; this deletes the pairing, so pair again afterwards.

One-time owner setup:

- A Firebase project with an Android app for package `dev.bugobliterator.itermbuddy`.
- A tester group `owner` in App Distribution, with the tester added in the Firebase console.
- A Google Cloud service account with the Firebase App Distribution Admin role, and a JSON key for it.
- GitHub repository secret `FIREBASE_SERVICE_ACCOUNT_JSON`: the service account's JSON key.
- GitHub repository variable `FIREBASE_APP_ID`: the Firebase Android app ID (`1:...:android:...`).
- GitHub repository secret `DEBUG_KEYSTORE_BASE64`: the shared debug keystore, base64-encoded (alias `androiddebugkey`, passwords `android`).

Without any of these, the workflow still builds the APKs and skips the upload with a warning.

License: GPL-2.0, as iTerm2.
