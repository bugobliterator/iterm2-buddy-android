// The key-bytes probe's capture program (RealIterm2KeyBytesTest): what bytes reach a program in
// an iTerm2 session when the phone types, under the keyboard modes a full-screen app asks for.
//
// Usage: node key-capture.mjs <out-dir> <mode>...
//   mode: none | kitty1 | kitty3 | kitty5 | kitty31 | paste | mok2 (several may be given)
//     kitty<N>  CSI > N u      (pushes kitty keyboard flags N; popped with CSI < u on exit)
//     paste     CSI ? 2004 h   (bracketed paste; CSI ? 2004 l on exit)
//     mok2      CSI > 4 ; 2 m  (xterm modifyOtherKeys 2; CSI > 4 m on exit)
// Environment: KEY_CAPTURE_TIMEOUT_SECONDS (120): exits on its own after this long.
//
// It puts its tty in raw mode, writes the enable sequences, asks the terminal for its kitty flags
// (CSI ? u) and logs the answer, then writes <out-dir>/ready. Every chunk read from the tty is
// appended to <out-dir>/capture.jsonl as {"t": epoch ms, "phase": "query"|"keys", "hex": "..."}.
// It exits, restoring the modes and the tty, when <out-dir>/stop appears or on the timeout, and
// writes <out-dir>/exited last. It never writes what it reads to the terminal.
// Node built-ins only.

import fs from "node:fs";
import path from "node:path";

const ESC = "\u001b";
const MODES = {
    none: { enable: "", disable: "" },
    kitty1: { enable: `${ESC}[>1u`, disable: `${ESC}[<u` },
    kitty3: { enable: `${ESC}[>3u`, disable: `${ESC}[<u` },
    kitty5: { enable: `${ESC}[>5u`, disable: `${ESC}[<u` },
    kitty31: { enable: `${ESC}[>31u`, disable: `${ESC}[<u` },
    paste: { enable: `${ESC}[?2004h`, disable: `${ESC}[?2004l` },
    mok2: { enable: `${ESC}[>4;2m`, disable: `${ESC}[>4m` },
};
const QUERY_WAIT_MS = 500;
const STOP_POLL_MS = 100;

const [outDir, ...modeNames] = process.argv.slice(2);
if (!outDir) {
    process.stderr.write("usage: node key-capture.mjs <out-dir> <mode>...\n");
    process.exit(2);
}
fs.mkdirSync(outDir, { recursive: true });
const capturePath = path.join(outDir, "capture.jsonl");
const statusPath = path.join(outDir, "status.txt");
const status = (line) => fs.appendFileSync(statusPath, `${new Date().toISOString()} ${line}\n`);

const modes = modeNames.length ? modeNames : ["none"];
for (const name of modes) {
    if (!(name in MODES)) {
        status(`unknown mode ${name}`);
        process.exit(2);
    }
}
const timeoutSeconds = Number(process.env.KEY_CAPTURE_TIMEOUT_SECONDS || 120);

if (!process.stdin.isTTY || typeof process.stdin.setRawMode !== "function") {
    status("stdin is not a tty");
    fs.writeFileSync(path.join(outDir, "exited"), "no tty\n");
    process.exit(1);
}

let phase = "query";
process.stdin.setRawMode(true);
process.stdin.on("data", (chunk) => {
    fs.appendFileSync(capturePath, JSON.stringify({ t: Date.now(), phase, hex: chunk.toString("hex") }) + "\n");
});
process.stdin.resume();

let finished = false;
function finish(reason) {
    if (finished) return;
    finished = true;
    const disable = modes.slice().reverse().map((name) => MODES[name].disable).join("");
    process.stdout.write(disable, () => {
        try {
            process.stdin.setRawMode(false);
        } catch {
            // The tty may already be gone.
        }
        status(`exit: ${reason}`);
        fs.writeFileSync(path.join(outDir, "exited"), `${reason}\n`);
        process.exit(0);
    });
}

const stopPath = path.join(outDir, "stop");
setInterval(() => {
    if (fs.existsSync(stopPath)) finish("stop file");
}, STOP_POLL_MS);
setTimeout(() => finish(`timeout after ${timeoutSeconds} s`), timeoutSeconds * 1000);
for (const signal of ["SIGTERM", "SIGHUP", "SIGINT"]) process.on(signal, () => finish(signal));

const enable = modes.map((name) => MODES[name].enable).join("");
status(`modes ${modes.join(" ")}: enable ${JSON.stringify(enable)}`);
process.stdout.write(`${enable}${ESC}[?u`, () => {
    setTimeout(() => {
        phase = "keys";
        fs.writeFileSync(path.join(outDir, "ready"), `${Date.now()}\n`);
        status("ready");
    }, QUERY_WAIT_MS);
});
