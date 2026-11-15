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

License: GPL-2.0, as iTerm2.
