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

When two keys with the same modifiers produce the same string, the lower key code now wins instead of the higher one. On US layouts that matters for:

- DEL (0x7F): it now maps to `kVK_Delete` (Backspace) instead of `kVK_ForwardDelete`. Under CSI u this reports Backspace, not Forward Delete, which seems more correct for a raw DEL.
- U+0010: shared by all the F-keys.

Printable ASCII is not otherwise affected.

## Notes

- The companion used for these measurements is the third-party Android client "iTerm2 Buddy for Android", which uses the same protocol. The iOS Companion (iTerm2 Buddy) has the same problem, because both go through `CompanionKeyEvent.makeKeyDownEvents()` → `NSEvent.keyDown(forCharacter:)` on the Mac. Vim-style key strings parsed by `VimKeyParser` use the same map, so they had the same bug.
- Not built and tests not run (no Xcode).
