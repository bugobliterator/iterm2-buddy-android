# Companion relay on the Mac

Runs [gnachman/iterm2-companion-relay](https://github.com/gnachman/iterm2-companion-relay)
at commit `b8aa2f9` as a launchd agent on this Mac and publishes it on the
tailnet only, at `https://<mac>.<tailnet>.ts.net` (the *origin*). iTerm2 and
the phone both connect to `wss://<mac>.<tailnet>.ts.net/`.
Attestation is off (`ATTEST_REQUIRED=false`), so rooms are admitted in open mode.

## Install

Needs git, Node 20+, pnpm and tailscale on PATH, and tailnet HTTPS
certificates enabled.

```sh
ops/mac/install-relay.sh                 # RELAY_PORT=<port> if 8787 is taken; later runs keep it
node ops/mac/check-relay.mjs https://<mac>.<tailnet>.ts.net
# check-relay: OK: {"ok":false,"error":"mac offline"}
```

The origin is one-time config that stays on the Mac and is never committed.
The first run takes `RELAY_ORIGIN` if it is set, otherwise
`https://` plus this Mac's tailnet name from `tailscale status --json`
(`Self.DNSName`). It saves the origin in `relay.env`, and later runs reuse it.
To change it, rerun with `RELAY_ORIGIN=https://<name>.ts.net`. The script ends
by printing the origin and the `defaults write` lines for iTerm2.

The script is safe to re-run. Before it stops the running agent, it checks:
- the origin is `https://<hostname>` with no port or path;
- tailnet certificates exist and Funnel is off, using `tailscale serve status --json`;
- the port is free of anything other than the agent itself;
- the checkout is restored to the pinned commit, with tracked files reset and stray files removed;
- `better-sqlite3` can open a database;
- the rendered plist passes `plutil -lint`.

It then reloads the agent and waits for the
`relay: listening on 127.0.0.1:8787 (…, attest=false)` log line. Next it runs
`tailscale serve` and checks Funnel again. If Funnel is on, it turns Funnel off,
stops the agent and fails.

`install-relay-helpers.mjs` holds the parts the script does in node rather than
bash. It reads the Funnel state from the serve-status JSON, both top-level and
foreground configs. It also renders the plist with XML escaping.

## What goes where

| What | Where |
| --- | --- |
| Relay checkout (pinned, `pnpm install --prod`) | `~/Library/Application Support/iterm2-companion-relay` |
| Relay database (outside the checkout, survives re-clones) | `~/Library/Application Support/iterm2-companion-relay-state/relay.db` |
| Settings including the origin, mode 0600, never committed | `~/.config/iterm2-companion-relay/relay.env` |
| launchd agent `dev.bugobliterator.itermbuddy.relay` | `~/Library/LaunchAgents/dev.bugobliterator.itermbuddy.relay.plist` |
| Log (stdout and stderr) | `~/Library/Logs/iterm2-companion-relay.log` |
| Tailnet mapping | `tailscale serve`: HTTPS 443 to `http://127.0.0.1:8787` |

launchd cannot read `relay.env` and has no nvm `node` on its PATH, so the
script copies the env values into the plist's `EnvironmentVariables` and writes
the absolute `node` path into `ProgramArguments`. After changing `relay.env` by
hand, or after nvm removes the Node version the plist names, re-run the script.

## Point iTerm2 at it

Quit iTerm2 first, then:

```sh
defaults write com.googlecode.iterm2 CompanionRelayOrigin -string https://<mac>.<tailnet>.ts.net
defaults write com.googlecode.iterm2 CompanionResolverURL -string ""
```

To check iTerm2 parks in its room, start companion pairing, copy the QR's
`iterm2://pair?…` string and run
`node ops/mac/check-relay.mjs https://<mac>.<tailnet>.ts.net --qr '<iterm2://pair?…>'`.
It expects `{"ok":true}`, uses one of the room's 8 pairing cycles and makes the
Mac's parked socket close; restart pairing in iTerm2 afterwards.

## Undo

```sh
launchctl bootout gui/$(id -u)/dev.bugobliterator.itermbuddy.relay
rm ~/Library/LaunchAgents/dev.bugobliterator.itermbuddy.relay.plist
tailscale serve --https=443 off
defaults delete com.googlecode.iterm2 CompanionRelayOrigin
defaults delete com.googlecode.iterm2 CompanionResolverURL
rm -rf ~/Library/Application\ Support/iterm2-companion-relay ~/.config/iterm2-companion-relay
rm ~/Library/Logs/iterm2-companion-relay.log
# Only to drop every pairing registration as well:
rm -rf ~/Library/Application\ Support/iterm2-companion-relay-state
```
