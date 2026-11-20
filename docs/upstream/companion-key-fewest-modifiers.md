# Prefer the fewest modifiers when mapping a character to a key

Branch: `companion-key-fewest-modifiers` (bugobliterator/iTerm2), one commit on master 03ec0f2.

## Problem

Measured on a real Mac (iTerm2 3.7.7, US layout). The app turned on kitty keyboard flags 5 and modifyOtherKeys 2, as Claude Code does. Then the companion's `sendKey` was used to send each printable ASCII character:

| Sent | Bytes the app received | Meaning |
|---|---|---|
| `/` | `ESC[30:47;2u` | Shift + key U+001E (Up Arrow's base character) |
| `*` | `ESC[29:42;2u` | Shift + key U+001D (Right Arrow) |
| `+` | `ESC[28:43;2u` | Shift + key U+001C (Left Arrow) |
| `=` | `ESC[31:61;2u` | Shift + key U+001F (Down Arrow) |

The other 91 printable ASCII characters were encoded correctly. Claude Code draws none of these four, so you can't type a path or an expression from the phone.

## Cause

`NSEvent.keyDown(forCharacter:)` builds a reverse map from each string to the keystroke that produces it on the current layout. It does this by asking `UCKeyTranslate` about key codes 0–127 under no modifiers, Shift, Option, and Shift+Option (`sources/Keyboard/VimKeyParser.swift`, `productionToCarbonKeystrokeDictionary`, ~L243–272). The US layout maps Shift+Arrow to `= / * +`, a holdover from keyboards whose arrows shared the keypad keys. When an entry already exists, the old tie-break only compares modifier counts if the two entries have the same key code (`VimKeyParser.swift:263`). Otherwise the later entry wins:

- `/` and `=`: the unmodified key is replaced by Shift+Up / Shift+Down, which have more modifiers but a different key code.
- `*` and `+`: Shift+8 / Shift+= are replaced by Shift+Right / Shift+Left. These have the same number of modifiers and a higher key code.

The CSI u encoder then works out the key's base character from the arrow's key code (U+001C–U+001F) and reports Shift.

## Fix

- `CarbonKeystroke.isPreferred(over:)` defines a total order. A key off the keypad beats one on it (the existing preference). Then fewer modifiers win, then the lower modifier mask (Shift before Option), then the lower key code. The map keeps the most preferred keystroke for each string, so the result no longer depends on the order keys are visited.
- `productionToCarbonKeystrokeDictionary(forInputSourceID:)` used to ignore its parameter and read `currentInputSourceID()` again (`VimKeyParser.swift:244`). It now uses the parameter. Its only caller passes `currentInputSourceID()`, so behavior doesn't change.
- New `ModernTests/CarbonKeystrokePreferenceTests.swift` tests the selection rule with the US-layout collisions above, checking that the result is the same whichever order the candidates are visited in. ModernTests is a synchronized folder, so the file needs no project change.
- Release note added to `docs/notes-3.7.txt` (Bug Fixes).

## How to test

1. In a session, run `printf '\e[>5u\e[>4;2m'; cat -v` (kitty flags 5 plus modifyOtherKeys 2), or open Claude Code.
2. From the phone, type `/ * + =`.
   - Before: `^[[30:47;2u` and so on.
   - After: these four are encoded the same way as the other printable characters. The reported key is the real `/`, `8`, or `=` key, never U+001C–U+001F.
3. Check a few other characters (letters, `?`, `_`, digits) to confirm nothing else changed.
4. Run `tools/run_tests.expect ModernTests/CarbonKeystrokePreferenceTests`.

## Side effects to review

The new order changes the chosen keystroke wherever two keystrokes produce the same string. Printable ASCII changes only for the four broken characters. The other changes:

- **Non-ASCII characters produced by two keys.** On US, the ISO Section key (key code 10) produces `§` unmodified and `±` with Shift. These now beat Option+6 (`§`) and Shift+Option+= (`±`).
  - Before, the Option keystroke won (the later entry). `CompanionKeyInjection.literalFallback` round-trips on Shift only (`sources/Companion/CompanionKeyInjection.swift:212`), so it sent these characters as literal text.
  - Now they map to key 10 with no modifier or Shift, the round trip succeeds, and they are synthesized as key events on key 10 instead of written literally. Under CSI u they are now reported as the ISO Section key, even on an ANSI keyboard that doesn't physically have it. The legacy mapper still sends the same text.
- **Other layouts.** Ties between two different keys with the same modifiers now go to the lower key code. Before, the later (higher) key code won. Ties between Shift and Option at the same modifier count now go to Shift.
- **U+0010** (produced by every F-key): was F1 (key code 122, the highest), now F17 (key code 64, the lowest). Neither is meaningful. This matters only for a raw Ctrl-P byte sent as text, which wasn't reported correctly before either.
- **DEL (0x7F)**: was Forward Delete (key code 117), now Delete/Backspace (key code 51). Under CSI u a raw DEL is now reported as Backspace instead of Forward Delete. I think that's more correct.
- **Copy mode and other `VimKeyParser` users**: a literal `/` (and `*`, `+`, `=`) in a parsed key string is now the real key rather than Shift+Up Arrow (etc.). For example, copy mode's `/` search key no longer arrives as Shift+Up.

## Notes

- The companion used for these measurements is the third-party Android client "iTerm2 Buddy for Android", which uses the same protocol. The iOS Companion (iTerm2 Buddy) has the same problem, because both go through `CompanionKeyEvent.makeKeyDownEvents()` → `NSEvent.keyDown(forCharacter:)` on the Mac. Vim-style key strings parsed by `VimKeyParser` use the same map, so they had the same bug.
- Not built and tests not run (no Xcode).
