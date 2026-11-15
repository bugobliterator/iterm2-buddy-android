// Admission probe for the self-hosted companion relay.
//
//   node check-relay.mjs <origin>                    expects {"ok":false,"error":"mac offline"}
//   node check-relay.mjs <origin> --qr '<iterm2://pair?…>'   expects {"ok":true}
//
// Joins as a phone in open mode: Hello {"v":1,"role":"phone"}, receives
// {"nonce":…}, sends an empty proof {}. Without --qr the room is 64 random hex
// characters, so no Mac is parked there. With --qr the room is the one iTerm2
// parks in, SHA256(canonical("iterm2-room", [rs, utf8(pid)])), and the socket
// is closed as soon as it is admitted. Exits 0 on the expected reply.
//
// Uses the relay checkout's `ws` (Node's global WebSocket cannot set headers).
// Set RELAY_CHECKOUT to load it from somewhere other than the installed path.

import { createHash, randomBytes } from "node:crypto";
import { createRequire } from "node:module";
import { homedir } from "node:os";
import { join } from "node:path";

const checkout = process.env.RELAY_CHECKOUT
  ?? join(homedir(), "Library/Application Support/iterm2-companion-relay");
const WebSocket = createRequire(join(checkout, "package.json"))("ws");

const TIMEOUT_MS = 10_000;

function usage(message) {
  console.error(`check-relay: ${message}\nusage: node check-relay.mjs <https origin> [--qr '<iterm2://pair?...>']`);
  process.exit(2);
}

// len32be(domain) || domain || for each field len32be(field) || field
function canonical(domain, fields) {
  return Buffer.concat([Buffer.from(domain, "utf8"), ...fields].flatMap((bytes) => {
    const length = Buffer.alloc(4);
    length.writeUInt32BE(bytes.length);
    return [length, bytes];
  }));
}

// Query values are percent-decoded only ('+' stays '+'), as in Swift's URLComponents.
function roomFromQr(qr) {
  const query = qr.slice(qr.indexOf("?") + 1);
  const values = new Map(query.split("&").map((item) => {
    const at = item.indexOf("=");
    return at < 0 ? [decodeURIComponent(item), ""] : [decodeURIComponent(item.slice(0, at)), decodeURIComponent(item.slice(at + 1))];
  }));
  if (!qr.startsWith("iterm2://pair?")) usage("--qr is not an iterm2://pair code");
  if (values.get("v") !== "1") usage(`--qr has v=${values.get("v")}, expected v=1`);
  const responderKey = Buffer.from(values.get("rs") ?? "", "base64url");
  if (responderKey.length !== 32) usage("--qr rs is not a 32-byte base64url key");
  const pairingId = values.get("pid");
  if (!pairingId) usage("--qr has no pid");
  return createHash("sha256").update(canonical("iterm2-room", [responderKey, Buffer.from(pairingId, "utf8")])).digest("hex");
}

const [origin, flag, qr] = process.argv.slice(2);
if (!origin?.startsWith("https://")) usage("origin must start with https://");
if (flag !== undefined && (flag !== "--qr" || !qr)) usage("unexpected arguments");

const room = qr ? roomFromQr(qr) : randomBytes(32).toString("hex");
const expected = qr ? { ok: true } : { ok: false, error: "mac offline" };
const url = origin.replace(/^https:/, "wss:").replace(/\/?$/, "/");

console.log(`check-relay: ${url} room=${qr ? "from QR" : "random"}, expecting ${JSON.stringify(expected)}`);
const socket = new WebSocket(url, { headers: { "x-relay-room": room } });
const timer = setTimeout(() => finish(1, `no reply within ${TIMEOUT_MS} ms`), TIMEOUT_MS);
let sentProof = false;

function finish(code, message) {
  clearTimeout(timer);
  console[code === 0 ? "log" : "error"](`check-relay: ${message}`);
  socket.terminate();
  process.exit(code);
}

socket.on("open", () => socket.send(JSON.stringify({ v: 1, role: "phone" })));
socket.on("message", (data) => {
  const text = data.toString();
  let reply;
  try {
    reply = JSON.parse(text);
  } catch {
    return finish(1, `non-JSON reply: ${text}`);
  }
  if (!sentProof) {
    if (typeof reply.nonce !== "string") return finish(1, `expected {"nonce":…}, got ${text}`);
    console.log("check-relay: got nonce, sending {}");
    sentProof = true;
    return socket.send("{}");
  }
  const matches = reply.ok === expected.ok && reply.error === expected.error;
  finish(matches ? 0 : 1, `${matches ? "OK" : "FAIL"}: ${text}`);
});
socket.on("unexpected-response", (_request, response) => finish(1, `upgrade refused: HTTP ${response.statusCode}`));
socket.on("error", (error) => finish(1, `socket error: ${error.message}`));
socket.on("close", (code, reason) => finish(1, `closed ${code} ${reason} before the expected reply`));
