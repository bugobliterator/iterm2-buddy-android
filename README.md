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

## Installing with Obtainium

Every push to `main` publishes a GitHub pre-release named `build-<short sha>` with a debug APK signed with the project's shared debug key. To install and update it on a phone with [Obtainium](https://github.com/ImranR98/Obtainium):

1. In Obtainium, tap **Add App** and enter `https://github.com/bugobliterator/iterm2-buddy-android`.
2. Turn on **Include prereleases**.
3. Set the APK filter regex to `itermbuddy-debug-.*\.apk`.
4. Tap **Add**, then **Install**.

Example: after a push of commit `5c99c6a`, Obtainium shows an update to version `0.1.0-5c99c6a` and installs `itermbuddy-debug-5c99c6a.apk`.

The first install of a shared-key build fails if the phone holds a build signed with another key (from a cloud session or `adb install`). Uninstall that build first; this deletes the pairing, so pair again afterwards. Later updates keep the pairing.

License: GPL-2.0, as iTerm2.
