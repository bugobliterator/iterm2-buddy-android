// A fake OpenAI Chat Completions model for the real iTerm2 CI job's Agent Chat stage. iTerm2's
// manual model (seed-ai.sh) points at it with no credential; nothing here talks to a real model.
//
// Usage: node fake-model.mjs
// Environment:
//   FAKE_MODEL_PORT (8790)               listens on 127.0.0.1 only
//   FAKE_MODEL_DIAGNOSTICS_DIR           where the captured request shape and tool schema go
//   FAKE_MODEL_TOOL                      the tool the approval trigger calls (session_execute_command)
//   FAKE_MODEL_TOOL_SCHEMA_SHA256        that tool's pinned schema hash; empty: not pinned yet
//   FAKE_MODEL_CHUNK_DELAY_MS (250)      the pause between streamed chunks
//
// POST /v1/chat/completions answers as OpenAI does, as server-sent events when the body asks for
// `stream` and as one JSON object otherwise:
// - by default, a short deterministic reply (REPLY_PARTS, one chunk per part);
// - when a user message after the conversation's last assistant or tool message holds TRIGGER and
//   the request offers FAKE_MODEL_TOOL, one call of that tool with `session_guid` and a harmless
//   `command`. The session is the `session_guid=<id>` written after the trigger, else the first
//   `"session_guid": "<id>"` in the request (the orchestrator's <workgroups> snapshot). In an
//   orchestrator chat iTerm2 parks that call on the owner's approval to control the session
//   (OrchestratorDispatcher.ensureSessionClaim), so the command never runs unless someone approves.
//   A request whose last message is a tool result gets the plain reply, so a turn always ends.
//
// GET /fake/stats returns counts and the captured tool facts (no content); GET /health says ok.
//
// It logs one line per request with the body's shape only: roles, counts, lengths, tool count,
// flags and what it answered, never message text. The first chat request body and the first one
// offering tools are written to the diagnostics directory with every message's content replaced
// by its length (the tools stay verbatim), and the trigger tool's schema as canonical JSON.
// Node built-ins only.

import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";

const port = Number(process.env.FAKE_MODEL_PORT || 8790);
const diagnosticsDir = process.env.FAKE_MODEL_DIAGNOSTICS_DIR || "";
const toolName = process.env.FAKE_MODEL_TOOL || "session_execute_command";
const pinnedSchemaSha256 = (process.env.FAKE_MODEL_TOOL_SCHEMA_SHA256 || "").trim();
const chunkDelayMs = Number(process.env.FAKE_MODEL_CHUNK_DELAY_MS || 250);

// Synthetic test data, mirrored in RealIterm2AgentChatTest; never real content.
const TRIGGER = "buddy-ci-approval-trigger";
const REPLY_PARTS = ["Fake model reply: ", "the real iTerm2 ", "agent chat ", "check streamed ", "in six ", "parts."];
const REPLY = REPLY_PARTS.join("");
const PROBE_COMMAND = "echo buddy-ci-approval-probe";
const MAX_BODY_BYTES = 32 * 1024 * 1024;

const stats = {
  requests: 0,
  chatRequests: 0,
  streamedRequests: 0,
  textReplies: 0,
  toolCalls: 0,
  triggersWithoutTool: 0,
  triggersWithoutSessionGuid: 0,
  badRequests: 0,
  replyLength: REPLY.length,
  firstToolsRequest: null,
  targetTool: { name: toolName, offered: null, sha256: null, pinnedSha256: pinnedSchemaSha256 || null, matchesPin: null },
  lastDecision: null,
};
let wroteFirstRequest = false;

function log(...args) {
  console.log(new Date().toISOString(), "fake-model:", ...args);
}

function writeDiagnostic(name, text) {
  if (!diagnosticsDir) return;
  try {
    fs.mkdirSync(diagnosticsDir, { recursive: true });
    fs.writeFileSync(path.join(diagnosticsDir, name), text);
    log(`wrote ${name} (${Buffer.byteLength(text)} bytes)`);
  } catch (error) {
    log(`could not write ${name}: ${error.code || error.name}`);
  }
}

/** JSON with every object's keys sorted, so equal schemas hash equally. */
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  }
  return value;
}

/** The text of a message's content: a string, or the text of its parts. */
function textOf(content) {
  if (typeof content === "string") return content;
  if (Array.isArray(content)) return content.map((part) => (typeof part?.text === "string" ? part.text : "")).join("\n");
  return "";
}

function redacted(text) {
  return `<redacted: ${text.length} chars>`;
}

/** The body with every message's content, tool arguments and attachments replaced by their size. */
function redactBody(body) {
  const copy = structuredClone(body);
  if (Array.isArray(copy.messages)) {
    copy.messages = copy.messages.map((message) => {
      const out = { ...message };
      if (typeof out.content === "string") out.content = redacted(out.content);
      else if (Array.isArray(out.content)) {
        out.content = out.content.map((part) => {
          const kept = { type: part?.type };
          if (typeof part?.text === "string") kept.text = redacted(part.text);
          else kept.redacted = true;
          return kept;
        });
      }
      for (const key of ["tool_calls"]) {
        if (Array.isArray(out[key])) {
          out[key] = out[key].map((call) => ({
            ...call,
            function: call?.function
              ? { name: call.function.name, arguments: redacted(String(call.function.arguments ?? "")) }
              : call?.function,
          }));
        }
      }
      if (out.function_call) {
        out.function_call = { name: out.function_call.name, arguments: redacted(String(out.function_call.arguments ?? "")) };
      }
      for (const key of ["images", "reasoning_content"]) if (key in out) out[key] = "<redacted>";
      return out;
    });
  }
  return copy;
}

function toolNamesOf(body) {
  return (Array.isArray(body.tools) ? body.tools : []).map((tool) => tool?.function?.name ?? tool?.name ?? "?");
}

/** Records the tools of the first request that offers any, and the trigger tool's schema hash. */
function captureTools(body) {
  if (stats.firstToolsRequest || !Array.isArray(body.tools) || body.tools.length === 0) return;
  const names = toolNamesOf(body);
  stats.firstToolsRequest = { toolCount: names.length, toolNames: names };
  writeDiagnostic("fake-model-first-tools-request.json", JSON.stringify(redactBody(body), null, 2) + "\n");
  writeDiagnostic("fake-model-tool-names.txt", names.join("\n") + "\n");
  const tool = body.tools.find((entry) => (entry?.function?.name ?? entry?.name) === toolName);
  stats.targetTool.offered = Boolean(tool);
  if (!tool) {
    log(`the first tools request does not offer ${toolName}; it offers ${names.length} tools`);
    return;
  }
  const schema = JSON.stringify(canonical(tool));
  const sha256 = crypto.createHash("sha256").update(schema).digest("hex");
  stats.targetTool.sha256 = sha256;
  stats.targetTool.matchesPin = pinnedSchemaSha256 ? sha256 === pinnedSchemaSha256 : null;
  writeDiagnostic(`fake-model-tool-schema-${toolName}.json`, JSON.stringify(canonical(tool), null, 2) + "\n");
  if (!pinnedSchemaSha256) log(`${toolName} schema sha256 ${sha256} (not pinned yet: pin it as FAKE_MODEL_TOOL_SCHEMA_SHA256 in ci/real-iterm2/common.sh)`);
  else if (stats.targetTool.matchesPin) log(`${toolName} schema sha256 ${sha256} matches the pin`);
  else log(`${toolName} schema sha256 ${sha256} DOES NOT match the pinned ${pinnedSchemaSha256}`);
}

/** What to answer: { kind: "text" } or { kind: "tool", guid, guidSource }. */
function decide(body) {
  const messages = Array.isArray(body.messages) ? body.messages : [];
  const last = messages.at(-1);
  if (last && (last.role === "tool" || last.role === "function")) return { kind: "text", why: "after-tool-result" };
  let start = 0;
  messages.forEach((message, index) => {
    if (message?.role === "assistant" || message?.role === "tool" || message?.role === "function") start = index + 1;
  });
  const triggering = messages.slice(start).filter((message) => message?.role === "user" && textOf(message.content).includes(TRIGGER));
  if (triggering.length === 0) return { kind: "text", why: "no-trigger" };
  if (!toolNamesOf(body).includes(toolName)) {
    stats.triggersWithoutTool++;
    return { kind: "text", why: "trigger-without-tool" };
  }
  const explicit = textOf(triggering.at(-1).content).match(/session_guid=([A-Za-z0-9_.:-]+)/);
  if (explicit) return { kind: "tool", guid: explicit[1], guidSource: "trigger-message" };
  for (const message of messages) {
    const found = textOf(message?.content).match(/"session_guid"\s*:\s*"([^"]+)"/);
    if (found) return { kind: "tool", guid: found[1], guidSource: "workgroups-snapshot" };
  }
  stats.triggersWithoutSessionGuid++;
  return { kind: "text", why: "trigger-without-session-guid" };
}

function shapeOf(body) {
  const messages = Array.isArray(body.messages) ? body.messages : [];
  const roles = messages.map((message) => message?.role ?? "?").join(",");
  const chars = messages.reduce((sum, message) => sum + textOf(message?.content).length, 0);
  return (
    `stream=${Boolean(body.stream)} model(len=${String(body.model ?? "").length}) messages=${messages.length} ` +
    `roles=[${roles}] contentChars=${chars} tools=${toolNamesOf(body).length} tool_choice=${body.tool_choice ?? "-"} ` +
    `max_tokens=${body.max_tokens ?? "-"} temperature=${body.temperature ?? "-"} keys=[${Object.keys(body).sort().join(",")}]`
  );
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function chunk(id, model, delta, finishReason = null) {
  return {
    id,
    object: "chat.completion.chunk",
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta, logprobs: null, finish_reason: finishReason }],
  };
}

async function stream(res, events) {
  res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache", Connection: "keep-alive" });
  for (const event of events) {
    if (res.destroyed) return;
    res.write(`data: ${JSON.stringify(event)}\n\n`);
    await sleep(chunkDelayMs);
  }
  if (!res.destroyed) res.end("data: [DONE]\n\n");
}

function toolCallOf(seq, guid) {
  return {
    index: 0,
    id: `call_buddy_ci_${seq}`,
    type: "function",
    function: { name: toolName, arguments: JSON.stringify({ session_guid: guid, command: PROBE_COMMAND }) },
  };
}

async function answer(res, body, seq, decision) {
  const id = `chatcmpl-buddy-ci-${seq}`;
  const model = String(body.model ?? "buddy-ci-fake-model");
  if (decision.kind === "tool") {
    stats.toolCalls++;
    const call = toolCallOf(seq, decision.guid);
    if (body.stream) {
      // The whole call in one delta, then OpenAI's empty finishing delta.
      await stream(res, [
        chunk(id, model, { role: "assistant", content: null, tool_calls: [call] }),
        chunk(id, model, {}, "tool_calls"),
      ]);
    } else {
      sendJson(res, 200, {
        id,
        object: "chat.completion",
        created: Math.floor(Date.now() / 1000),
        model,
        choices: [{ index: 0, message: { role: "assistant", content: null, tool_calls: [call] }, finish_reason: "tool_calls" }],
      });
    }
    return;
  }
  stats.textReplies++;
  if (body.stream) {
    await stream(res, [
      chunk(id, model, { role: "assistant", content: "" }),
      ...REPLY_PARTS.map((part) => chunk(id, model, { content: part })),
      chunk(id, model, {}, "stop"),
    ]);
  } else {
    sendJson(res, 200, {
      id,
      object: "chat.completion",
      created: Math.floor(Date.now() / 1000),
      model,
      choices: [{ index: 0, message: { role: "assistant", content: REPLY }, finish_reason: "stop" }],
    });
  }
}

function sendJson(res, status, value) {
  const text = JSON.stringify(value);
  res.writeHead(status, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(text) });
  res.end(text);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const parts = [];
    let size = 0;
    req.on("data", (data) => {
      size += data.length;
      if (size > MAX_BODY_BYTES) {
        reject(new Error("body too large"));
        req.destroy();
        return;
      }
      parts.push(data);
    });
    req.on("end", () => resolve(Buffer.concat(parts)));
    req.on("error", reject);
  });
}

async function handle(req, res) {
  const seq = ++stats.requests;
  const url = new URL(req.url, "http://127.0.0.1");
  if (req.method === "GET" && url.pathname === "/health") return sendJson(res, 200, { ok: true });
  if (req.method === "GET" && url.pathname === "/fake/stats") return sendJson(res, 200, stats);
  if (req.method === "GET" && url.pathname.endsWith("/models")) {
    log(`#${seq} GET ${url.pathname}`);
    return sendJson(res, 200, { object: "list", data: [{ id: "buddy-ci-fake-model", object: "model", owned_by: "buddy-ci" }] });
  }
  if (req.method !== "POST" || !url.pathname.endsWith("/chat/completions")) {
    log(`#${seq} ${req.method} ${url.pathname} -> 404`);
    return sendJson(res, 404, { error: { message: "not found", type: "invalid_request_error" } });
  }
  let body;
  try {
    body = JSON.parse((await readBody(req)).toString("utf8"));
    if (!body || typeof body !== "object" || Array.isArray(body)) throw new Error("not an object");
  } catch (error) {
    stats.badRequests++;
    log(`#${seq} POST ${url.pathname} -> 400 (${error.message})`);
    return sendJson(res, 400, { error: { message: "bad request body", type: "invalid_request_error" } });
  }
  stats.chatRequests++;
  if (body.stream) stats.streamedRequests++;
  if (!wroteFirstRequest) {
    wroteFirstRequest = true;
    writeDiagnostic("fake-model-first-request.json", JSON.stringify(redactBody(body), null, 2) + "\n");
  }
  captureTools(body);
  const decision = decide(body);
  stats.lastDecision = decision.kind === "tool" ? `tool:${toolName}:${decision.guidSource}` : `text:${decision.why}`;
  log(`#${seq} POST ${url.pathname} ${shapeOf(body)} -> ${stats.lastDecision}`);
  await answer(res, body, seq, decision);
}

const server = http.createServer((req, res) => {
  handle(req, res).catch((error) => {
    log(`request failed: ${error.name}`);
    if (!res.headersSent) sendJson(res, 500, { error: { message: "fake model error", type: "server_error" } });
    else res.destroy();
  });
});
server.listen(port, "127.0.0.1", () => {
  log(
    `listening on 127.0.0.1:${port}; trigger tool ${toolName}, schema pin ${pinnedSchemaSha256 || "not set"}, ` +
      `reply of ${REPLY.length} chars in ${REPLY_PARTS.length} chunks ${chunkDelayMs} ms apart`,
  );
});
for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, () => server.close(() => process.exit(0)));
