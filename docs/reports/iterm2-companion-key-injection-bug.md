# Companion sendKey sends / * + = as the wrong key under the kitty keyboard protocol

A draft for the iTerm2 issue tracker.

## Versions

- iTerm2 3.7.7 (source at commit `e0025340`), macOS runner with the US keyboard layout.
- Companion `sendKey` from a phone (`text` key event, no modifiers).
- Seen in Claude Code 2.1.327, which enables kitty keyboard flags 5 (`CSI > 5 u`),
  modifyOtherKeys 2 (`CSI > 4 ; 2 m`) and bracketed paste.

## Summary

When the program in the session has enabled the kitty keyboard protocol (or modifyOtherKeys),
the companion's injected keys for four printable characters, `/ * + =`, are encoded as the wrong
key: a key number in U+001C..U+001F, with Shift. Programs that decode the key (Claude Code) show
nothing for them. Every other printable ASCII character is encoded correctly, and all four arrive
as themselves when no keyboard mode is on or when they are sent with `pasteText`.

## Steps

1. In an iTerm2 session, run a program that enables kitty flags 5 and prints what it reads, for
   example:

       perl -e 'system("stty raw -echo"); $|=1; print "\e[>5u";
         $SIG{ALRM}=sub{ print "\e[<u"; system("stty sane"); exit }; alarm 20;
         while (sysread(STDIN,$b,64)) { print unpack("H*",$b), "\r\n" }'

2. From the companion app, send each of `/`, `*`, `+`, `=` and `?` as a key (`sendKey` with
   `text`, no modifiers).
3. Compare with the same keys typed on the Mac's keyboard.

Or: run Claude Code and send `/` from the companion. Nothing is drawn and the slash-command menu
does not open; `/` typed on the Mac's keyboard, or sent with the companion's `pasteText`, opens
it.

## Expected and actual bytes (kitty flags 5)

| Key | Expected                                    | Actual (companion sendKey)                          |
|-----|---------------------------------------------|-----------------------------------------------------|
| `/` | `2f` = `/`                                  | `1b5b33303a34373b3275` = `ESC[30:47;2u` (key U+001E, Shift) |
| `*` | `1b5b35363a34323b3275` = `ESC[56:42;2u`     | `1b5b32393a34323b3275` = `ESC[29:42;2u` (key U+001D) |
| `+` | `1b5b36313a34333b3275` = `ESC[61:43;2u`     | `1b5b32383a34333b3275` = `ESC[28:43;2u` (key U+001C) |
| `=` | `3d` = `=`                                  | `1b5b33313a36313b3275` = `ESC[31:61;2u` (key U+001F, Shift) |
| `?` | `1b5b34373a36333b3275` = `ESC[47:63;2u`     | the same (correct)                                  |
| `-` | `2d` = `-`                                  | the same (correct)                                  |

The expected column follows how iTerm2 encodes the companion's other keys, which are correct:
unshifted punctuation as itself (`-`, `.`), shifted keys as CSI u with the base key (`?` above,
`(` as `ESC[57:40;2u`, `A` as `ESC[97:65;2u`). The same four key numbers come out under kitty
flags 1, 3 and 31. Under modifyOtherKeys 2 alone, `/` and `=` carry Shift (`ESC[27;2;47~`,
`ESC[27;2;61~`) and `*` and `+` arrive as `ESC[27;2;42~` and `ESC[27;2;43~`, while `-` and `.`
arrive as themselves. With no keyboard mode on, all four arrive as themselves.

## Cause

`CompanionKeyEvent.makeKeyDownEvents()` (`sources/Companion/CompanionKeyInjection.swift:128`)
builds each character's key-down with `NSEvent.keyDown(forCharacter:)` (`:162`), which looks the
character up in a reverse map from produced string to key code and modifiers,
`productionToCarbonKeystrokeDictionary` (`sources/Keyboard/VimKeyParser.swift:243-275`). The map
iterates the modifier sets `[0, shift, option, shift|option]` (`:251`) over key codes 0...127 and
keeps an existing entry only when the new key is on the keypad and the existing one is not
(`:259-262`) or when it is the **same key code** with no fewer modifiers (`:263-266`). Any other
key code that produces the same string with **more** modifiers replaces it (`:268`): the correct
unmodified entry for `/` (kVK_ANSI_Slash, no modifiers) is overwritten by a later Shift entry
from another key.

Inference (the bytes above are measured; this is not): the key numbers reported, U+001C..U+001F,
are the characters the arrow keys produce (left, right, up, down), and the four affected
characters are the keypad operators other than `-` and `.`. So the overwriting entries appear to
be the arrow keys with Shift, which the layout seems to map to `+ * / =` (up gives `/`, right
`*`, left `+`, down `=`). The arrow keys are not in `keyCodeIsOnKeypad`, so the keypad preference
does not stop them. The CSI-u mapper then derives the key number from the arrow key's unshifted
character and adds Shift.

A modified key (Ctrl+/ from the companion) takes the same base event, so it is presumably wrong
too (not measured). `VimKeyParser.handleRegular` (`:117`) uses the same lookup.

## Suggested fix

Prefer the keystroke with the fewest modifiers for a character across **all** key codes, not
only the same key code; that is, never let a later Shift or Option entry replace an existing
entry with fewer modifiers, while keeping the preference for a non-keypad key:

```swift
if let existing = dict[produced] {
    let newOnKeypad = keyCodeIsOnKeypad(keyCode: keyCode)
    let existingOnKeypad = keyCodeIsOnKeypad(keyCode: existing.keyCode)
    if newOnKeypad != existingOnKeypad {
        if newOnKeypad {
            continue  // Prefer non-keypad to keypad
        }
    } else if existing.modifiers.nonzeroBitCount <= modifier.nonzeroBitCount {
        continue  // Prefer fewer modifiers, whichever key code
    }
}
```

Skipping keys whose unmodified output is a control character (the arrows, function keys) when
building the map would also prevent it.

## Also affected

iTerm2's iOS companion app sends typed text the same way (`sendKey` text, no modifiers), so it
hits this too. The Android companion works around it by sending these four characters as
one-character `pasteText` (a setting, on by default).
