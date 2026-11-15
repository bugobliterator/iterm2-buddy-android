// Helpers for install-relay.sh that must not go through shell substitution.
//
//   node install-relay-helpers.mjs funnel-state < serve-status.json
//     Prints "on" or "off". Funnel is on if any AllowFunnel map anywhere in the
//     config has a true value: top level or under Foreground.<session>, as
//     tailscale's IsFunnelOn (ipn/serve.go) checks. `null` (no serve config)
//     is off. Malformed JSON exits non-zero.
//
//   RELAY_NODE=… RELAY_CHECKOUT=… RELAY_LOG=… \
//   node install-relay-helpers.mjs render-plist <template> <relay.env> <out.plist>
//     Fills @NODE@, @CHECKOUT@, @LOG@ and @ENVIRONMENT@ (from the KEY=value
//     lines of relay.env) with XML-escaped values.

import { readFileSync, writeFileSync } from "node:fs";

function funnelIsOn(value) {
  if (value === null || typeof value !== "object") return false;
  for (const [key, child] of Object.entries(value)) {
    if (key === "AllowFunnel" && child && typeof child === "object"
        && Object.values(child).some((enabled) => enabled === true)) return true;
    if (funnelIsOn(child)) return true;
  }
  return false;
}

const xmlEscape = (text) => text
  .replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;");

function requiredEnv(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set`);
  return value;
}

function renderPlist(templatePath, envFilePath, outPath) {
  const environment = readFileSync(envFilePath, "utf8").split("\n").filter(Boolean).map((line) => {
    const at = line.indexOf("=");
    if (at <= 0) throw new Error(`bad env line: ${line}`);
    return `        <key>${xmlEscape(line.slice(0, at))}</key>\n        <string>${xmlEscape(line.slice(at + 1))}</string>`;
  }).join("\n");
  const values = {
    "@NODE@": xmlEscape(requiredEnv("RELAY_NODE")),
    "@CHECKOUT@": xmlEscape(requiredEnv("RELAY_CHECKOUT")),
    "@LOG@": xmlEscape(requiredEnv("RELAY_LOG")),
    "@ENVIRONMENT@": environment,
  };
  // split/join: String.replace* would treat `$&` and similar in values as patterns.
  let plist = readFileSync(templatePath, "utf8");
  for (const [placeholder, value] of Object.entries(values)) plist = plist.split(placeholder).join(value);
  writeFileSync(outPath, plist);
}

const [command, ...args] = process.argv.slice(2);
if (command === "funnel-state") {
  process.stdout.write(funnelIsOn(JSON.parse(readFileSync(0, "utf8"))) ? "on" : "off");
} else if (command === "render-plist" && args.length === 3) {
  renderPlist(...args);
} else {
  console.error("usage: install-relay-helpers.mjs funnel-state | render-plist <template> <relay.env> <out.plist>");
  process.exit(2);
}
