# Agent Chat on device

The owner fills this in on the Mac with the Pixel, after the Chats Tab and Navigation task. Write what
happened under each **Result** line: pass or fail, plus anything unexpected (screen text, timing, relay
log lines).

- Date:
- Phone and Android version: Pixel 9,
- iTerm2 version: 3.7.7
- Relay commit: `b8aa2f9`
- App build: (the newest 0.1.0-<sha> in App Tester at or after the commit that adds this report)

## Prerequisites

- [ ] Tailscale is connected on the Pixel, and the relay is running on the Mac behind
      `tailscale serve`.
- [ ] AI is on in iTerm2's settings, and Claude Code is installed on the Mac.
- [ ] The phone is paired and shows the home screen.
- [ ] Start a log capture before the first check and keep it running through the last:

      adb logcat -c
      adb logcat > agent-chat-logcat.txt

- Relay log: `~/Library/Logs/iterm2-companion-relay.log`.

## Checks

### Chat list.

After launch the Chats tab shows the Mac's chats in the Mac's order with icons, snippets (mentions as names) and times. Rename a chat on the Mac: the row updates within about a second without pulling. Search filters by title and snippet.

**Result:**

### Live states.

Start a long Claude Code task in an orchestrator chat on the Mac and watch the list on the phone: the row shows Working; when the turn ends it shows Done and an unread dot; opening it clears both. Record the delay from the Mac's last word to Done.

**Result:**

### Waiting.

Ask a session-bound chat to run a command that needs permission: the row shows "Waiting for you" within about 2 s; the conversation shows the request card with "Answer this on your Mac". Answer on the Mac: the state returns to Working. The card shows the command's description, the permission category and the whole command. Then have an orchestrator chat ask to claim a workgroup (a workgroup-permission request, typing stays on): the row shows "Waiting for you" too.

**Result:**

### Turn ends while locked.

Start a long task, lock the phone, let it finish on the Mac, then unlock: the row shows Done with the unread dot.

**Result:**

### Every chat is watched.

Start a task in the chat at the bottom of the list: its row shows Working, then Done.

**Result:**

### Two chats streaming at once.

Start replies in two chats together, one open on the phone: both rows show Working, the open one streams smoothly, and neither reply leaks into the other.

**Result:**

### Streaming.

Open a chat and send "count slowly to 50, one number per line": the reply streams into one bubble in order, with no doubled or missing numbers; the typing indicator shows while it streams.

**Result:**

### Reconnect mid-stream.

While a reply streams, lock the phone for 60 s (the link drops) and unlock: the conversation shows the reply complete (no duplicates, no gap) after "Reconnecting…".

**Result:**

### Reconnect cost.

After a 60 s lock, unlock with every chat watched: record the number of chats, the time from `LinkUp` to the last chat's history and the bytes received (the repository's per-link cost log), and whether a live session opened at the same time stays smooth.

**Result:**

### Memory after reconnects.

With a conversation open, lock and unlock three times, recording `dumpsys meminfo` after each: no growth after the first.

**Result:**

### Send and echo.

Send a message with a session @-mention picked from the picker: one bubble, not two; on the Mac the message shows the session mention; the agent replies.

**Result:**

### Pending approval warning.

With a request waiting, type a message: the composer warns; confirm: the Mac shows the request cancelled. With a select-session request waiting there is no warning.

**Result:**

### Mention tokens and the keyboard.

In the composer, pick a session mention, then backspace into the token with Gboard: the whole token goes, leaving no fragment of its name. Type a letter straight after the token: the token stays and the letter follows it. Select the token and type over it: what was typed stays. Type a word right before and right after the token and let autocorrect change the word beside it: the token stays whole and the Mac receives `@<guid>`.

**Result:**

### Send not delivered.

Turn AI off on the Mac and send at once from an open conversation (before the phone hears of it), or send while the link is dropping: the bubble shows "Not delivered" with Retry within about 10 s; Retry after AI is back delivers one copy.

**Result:**

### Mentions.

An agent reply naming a session shows its name as a link; tapping opens the live view; Back returns to the conversation. A workgroup mention opens the workgroup view.

**Result:**

### Workgroup.

The member list shows each member's state and status line; start and stop work in one member on the Mac: its state changes on the phone within about 5 s.

**Result:**

### Create, mute, delete, truncate.

Create an orchestrator chat and a session-bound chat from the phone; mute one (the bell-slash shows, and the Mac stops pushing for it); Delete From Here on your own message truncates on both sides; delete a chat on the phone and confirm it is gone on the Mac; delete one on the Mac while it is open on the phone: the deleted notice shows and sending is disabled. Delete From Here on your own message while a reply is still streaming: both sides end truncated and no fragment of the reply reappears. "Go to session" on an old session-bound chat appears after a moment and opens its session; close that session on the Mac and reopen the chat: the button does not appear.

**Result:**

### AI off.

Turn AI off in iTerm2 settings while on the Chats tab: the phone moves to Sessions and Chats shows "AI Chat Is Off"; turn it back on: chats return and an open conversation streams again without reconnecting. Then turn AI off just as the phone reconnects after a lock (subscribes in flight): no stall or reconnect loop, Chats shows "AI Chat Is Off", and turning AI on restores every chat.

**Result:**

### Code attachment streaming.

Ask an agent for a reply that streams a code attachment: it grows in one monospace block, in order, with no duplicated lines.

**Result:**

### Big chat.

Open the longest chat on the Mac under the 8 MiB cap: record its size, its time to show and whether the phone stays smooth while scrolling; record the app's **peak** memory while it loads (`adb shell dumpsys meminfo dev.bugobliterator.itermbuddy` sampled every second, the largest Java heap and TOTAL PSS) and after. A chat over the cap shows "This chat is too large to open on this phone." and the app does not restart.

**Result:**

### Tabs.

Switch between Chats and Sessions; open a live session from Sessions, switch to Chats: the stream stops (the Mac's log shows the stop); rotation keeps the conversation and its scroll position.

**Result:**

### No content in logs.

`grep -iE 'count slowly|<a word from a reply>|<a chat title>|kittens' agent-chat-logcat.txt` finds nothing.

**Result:**

### Default tab

On a fresh launch with AI on, the app opens on Chats. Pick Sessions, lock for 60 s and unlock: it stays on Sessions. With a conversation open, turn AI off on the Mac: it stays in the conversation and the composer says AI is off.

**Result:**

### Bottom bar and dot

The bar shows only on the Chats and Sessions lists, not in a conversation, New Chat, a workgroup, Settings or a live view, and hides while the keyboard is up. The Chats item shows a dot when a chat is unread or waiting.

**Result:**

### Go to session and Back

From a session-bound chat, Go to session (>_) opens the live view over the conversation; Back stops the stream and returns to the conversation at the same scroll position, with no loading flash after more than 5 s away.

**Result:**

### New Chat

✎ New Chat: "A session" and "Orchestration" each open the new chat, and Back goes to the list, not to New Chat. With AI off, New Chat shows the AI-unavailable panel.

**Result:**

### Delete From Here while sending

Send a message and, while it shows Sending…, open ⋯ on an earlier message of yours: Delete From Here is not offered until the echo arrives.

**Result:**

### Workgroup polling

In a workgroup view, tap a member, then Back: the members are still shown with no Loading flash. Exactly one fetchWorkgroupInfo per reconnect; none while the app is backgrounded. After approving a workgroup command or watch approval on the Mac, does Waiting clear promptly?

**Result:**

### Unpair

Unpair (on the phone and from the Mac): it returns to pairing; after pairing again no old chats, titles or icons show, and the new pairing picks its default tab again.

**Result:**
