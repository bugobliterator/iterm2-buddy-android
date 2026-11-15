# Handover: Pairing Milestone build

Date: 2026-11-15. Branch: `pairing-milestone`, made from the approved `design-android-companion`.

## What this is

This is a Kotlin Android port of the iOS iTerm2 Buddy companion app, for the owner's Pixel (Android 15+). It pairs with iTerm2 3.7.7 on the owner's Mac through a self-hosted relay reached over Tailscale.

- **Main feature:** talking to the AI agents running in iTerm2.
- **This milestone:** pairing only, which covers the QR scan, relay admission, Noise XK, the 6-digit code, registration and reconnect.

## Read first

- Spec (approved): `docs/superpowers/specs/2026-11-15-android-companion-design.md`
- Protocol notes: `docs/superpowers/specs/2026-11-15-protocol-notes.md`
- Plan (approved after four adversarial rounds by Codex Astra and Mythos): `docs/superpowers/plans/2026-11-15-pairing-milestone.md`

## Owner's working rules for this build

- **Execution:** subagent-driven. Opus subagents (Opus 5.5 or better, with the model passed explicitly) write code, and only they do. One task at a time; never two implementers on the same branch at once.
- **Per-task review:** Codex Sol (`gpt-6.1-sol`) through the Codex plugin's companion script (`codex-companion.mjs task --model gpt-6.1-sol`, read-only), not Claude reviewer subagents. The reviewer gets the task section of the plan, the implementer's report, and the diff of the task's commits.
- **Large or critical reviews** (the whole milestone branch, security-sensitive pieces): adversarial review by both Mythos (`claude -p --model claude-mythos-5-1`, prompt on stdin, edit tools disallowed) and Codex Astra (`gpt-6-astra`, via the Codex plugin's `adversarial-review`).
- **Fixing findings:** triage every finding, fix the valid ones, and re-review until clean.
- **Push and merge:** pushing to `pairing-milestone` is allowed; no force-pushes. The lead may fast-forward `main` (`--ff-only`, linear history) once reviews are clean. History rewrites, deletions and releases still need the owner's OK.
- **Commits:** author `bugobliterator <siddharthbharatpurohit@gmail.com>`; check `git config user.email`. Keep messages concise. Never put a Claude session link in commits, PRs or published text.
- **Names:** descriptive, never numbered (phases, tasks, tests, fixtures, branches).
- **After each phase,** ask the owner before starting the next. The phases are Relay Setup, Wire Fixtures, Android Skeleton and Pairing.
- **Tests:** sandboxed (temp dirs, ephemeral ports), run in parallel, and fast. Report what was run and what was skipped.
- **Tooling:** use pnpm, never npm.
- **Waits:** report every wait on the owner to the `master-session` Claude session and to the `tracker` CLI, which runs on the owner's Mac. A cloud session without those should list its waits in its report so the Mac session can relay them.

## Task status

| Phase | Task | State |
|---|---|---|
| Relay Setup | Relay Service on the Mac | **Running on the Mac** (local by design). The implementer stops before the iTerm2 settings steps, which need the owner to quit iTerm2. It pushes `ops/mac/*` when done. Not a cloud task. |
| Wire Fixtures | Upstream Harness | **Done** (`939cd02`). Linux build verified in the cloud (see below). |
| Wire Fixtures | Crypto Vectors | **Done** (`9225e0f`, `1604a6f`). Opus review clean. |
| Wire Fixtures | Session Message Samples | **Done** (`7b1891d`, `7385893`). Opus review clean after fixes. |
| Android Skeleton | Gradle Project | **Done** (`22887e8`, `4831a68`, `449e740`, `58cb693`). Opus review clean after fixes. AGP 9.5.1, Kotlin 2.4.21, Gradle 9.9.1, SDK 37, min 34. |
| Pairing | Pairing Code and Room Name; SAS and Join Signature; Noise Channel; Wire Envelope and Session Messages; Relay Connection; Pairing Store; Companion Client | Not started, in this order |
| Pairing | Pairing Screens | Not started; it ends with an on-device check by the owner on the Mac and Pixel |

## Closed: Linux build of the harness

Codex Sol's review of Upstream Harness has one Important finding: the Linux build ran in local Docker (`swift:6.2-noble` on the Mac), not in a cloud Linux session. In the cloud session:

```
cd fixtures/harness && ./sync-upstream.sh && swift build && swift run wire-fixtures /tmp/out
```

Expected: "Synced 39 files from v3.7.7 (e0025340…)", "Build complete!" with zero `warning:` lines, and the stub message. Record the toolchain version and output. That closes the task.

Result (cloud session, 2026-11-15): Swift 6.2.4 (`swift:6.2-noble`) on Ubuntu 24.04, kernel 6.18. `sync-upstream.sh` synced 39 files from v3.7.7 (e0025340…), a clean `swift build` completed with 0 warnings, and `wire-fixtures` printed its stub message. The cloud container has no Swift toolchain (download.swift.org is blocked), so it ran in Docker after starting `dockerd`.

Notes from that task:
- The plan's CompanionProtocol file list was missing three upstream files, now included and commented in `upstream-files.txt`: `DataHex`, `CompanionSyncItem` and `Wire/CompanionError`.
- Linux needs two small shims, under `Sources/WireFixtures/Shims/` and only on non-Darwin: `String(localized:defaultValue:comment:)` and `NSMutableString()`.
- `Sources/*/Upstream/` is generated and not committed; run `sync-upstream.sh` first.

## Review setup from 2026-11-15 (owner's instruction)

- Work lands on `main` (fast-forward) once reviewed.
- Per-task review is by an Opus subagent (read-only), in place of Codex Sol. Mythos is reserved for critical or large reviews.
- Codex is not reachable from the cloud session (api.openai.com is blocked).

## Cloud environment notes

- The network policy must allow `dl.google.com` (Google Maven and SDK). The full domain list used so far is in the session report; `scripts/cloud-setup.sh` installs the SDK and JDK 17 into `$HOME`.
- Swift runs in Docker (`swift:6.2-noble`); start `dockerd` first if it is not running.
- Maven Central (`repo.maven.apache.org`) intermittently answers 429 or a spurious 404 through the proxy. Gradle caches those misses, so a failed resolve may need `--refresh-dependencies` and a retry.

## Follow-ups found in review

- Enable R8 for release builds (`isMinifyEnabled = true` with `proguard-android-optimize.txt`). BouncyCastle (`bcprov-jdk18on`, 7 MB) ships whole without it; a trial R8 build kept about 30 BouncyCastle classes, gave a 1.8 MB release APK and no warnings.
- Check the relay challenge nonce is 32 bytes where it is base64-decoded (Relay Connection).

## Plan deviations already ruled on

- Plan tasks are named, not numbered. Hand each implementer only "Global Constraints", "Review Focus" and its own `### Task:` section, never the whole plan.
- The app advertises protocol revision 11 for this milestone; `fixtures/messages/revision.json` is reference only.
- Spec harness item (c), where Swift decodes Kotlin output, is met by Kotlin encodings matching Swift's as JSON trees.

## Needs the owner (on the Mac, not the cloud)

- After Relay Service reports: quit iTerm2, then apply `defaults write com.googlecode.iterm2 CompanionRelayOrigin -string https://relay.example.ts.net` and `defaults write com.googlecode.iterm2 CompanionResolverURL -string ""`. Then check that iTerm2 parks on the relay.
- The Pairing Screens on-device check with the Pixel over adb.
