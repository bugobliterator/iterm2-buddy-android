// A TLS-terminating reverse proxy for the real iTerm2 CI job: https and wss on localhost, plain
// http and ws to the relay on 127.0.0.1. It stands in for the Caddy front of a self-hosted relay
// (relay SELF-HOSTING.md), so iTerm2 reaches the relay at an https origin it trusts.
//
// Usage: node tls-proxy.mjs
// Environment: TLS_PROXY_PORT (8443), TLS_CERT_FILE, TLS_KEY_FILE, RELAY_UPSTREAM_PORT (8787).
//
// It listens on 127.0.0.1 and, when the host has it, ::1, because "localhost" can resolve to
// either. It logs one line per request or upgrade: method, path, status, never a body or header
// value. Node built-ins only.

import fs from "node:fs";
import http from "node:http";
import https from "node:https";
import net from "node:net";

const port = Number(process.env.TLS_PROXY_PORT || 8443);
const upstreamPort = Number(process.env.RELAY_UPSTREAM_PORT || 8787);
const upstreamHost = "127.0.0.1";
const certFile = process.env.TLS_CERT_FILE;
const keyFile = process.env.TLS_KEY_FILE;
if (!certFile || !keyFile) {
  console.error("tls-proxy: TLS_CERT_FILE and TLS_KEY_FILE must be set");
  process.exit(2);
}
const tlsOptions = {
  cert: fs.readFileSync(certFile),
  key: fs.readFileSync(keyFile),
  minVersion: "TLSv1.2",
};

function log(...args) {
  console.log(new Date().toISOString(), "tls-proxy:", ...args);
}

// Headers a proxy must not forward hop by hop; the upgrade path writes its own.
const HOP_BY_HOP = new Set(["connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade", "te", "trailer"]);

function forwardRequest(req, res) {
  const headers = {};
  for (const [name, value] of Object.entries(req.headers)) {
    if (!HOP_BY_HOP.has(name)) headers[name] = value;
  }
  headers["x-forwarded-proto"] = "https";
  const upstream = http.request(
    { host: upstreamHost, port: upstreamPort, method: req.method, path: req.url, headers },
    (upstreamRes) => {
      log(`${req.method} ${req.url} -> ${upstreamRes.statusCode}`);
      const responseHeaders = {};
      for (const [name, value] of Object.entries(upstreamRes.headers)) {
        if (!HOP_BY_HOP.has(name)) responseHeaders[name] = value;
      }
      res.writeHead(upstreamRes.statusCode, responseHeaders);
      upstreamRes.pipe(res);
    },
  );
  upstream.on("error", (error) => {
    log(`${req.method} ${req.url} -> upstream error ${error.code || error.message}`);
    if (!res.headersSent) res.writeHead(502, { "content-type": "text/plain" });
    res.end("relay unreachable\n");
  });
  req.pipe(upstream);
}

function forwardUpgrade(req, socket, head) {
  const upstream = net.connect(upstreamPort, upstreamHost, () => {
    const lines = [`${req.method} ${req.url} HTTP/1.1`];
    for (let i = 0; i < req.rawHeaders.length; i += 2) {
      lines.push(`${req.rawHeaders[i]}: ${req.rawHeaders[i + 1]}`);
    }
    lines.push("X-Forwarded-Proto: https", "", "");
    upstream.write(lines.join("\r\n"));
    if (head && head.length > 0) upstream.write(head);
    upstream.pipe(socket);
    socket.pipe(upstream);
    log(`upgrade ${req.url} -> connected to the relay`);
  });
  const close = (what) => (error) => {
    if (error) log(`upgrade ${req.url}: ${what} ${error.code || error.message}`);
    socket.destroy();
    upstream.destroy();
  };
  upstream.on("error", close("relay side"));
  socket.on("error", close("client side"));
  upstream.on("close", () => socket.destroy());
  socket.on("close", () => upstream.destroy());
}

function listen(host) {
  const server = https.createServer(tlsOptions, forwardRequest);
  server.on("upgrade", forwardUpgrade);
  server.on("tlsClientError", (error) => log(`TLS handshake failed: ${error.code || error.message}`));
  return new Promise((resolve) => {
    server.once("error", (error) => {
      log(`not listening on ${host}:${port}: ${error.code || error.message}`);
      resolve(false);
    });
    server.listen(port, host, () => {
      log(`listening on ${host}:${port}, forwarding to ${upstreamHost}:${upstreamPort}`);
      resolve(true);
    });
  });
}

const ipv4 = await listen("127.0.0.1");
await listen("::1");
if (!ipv4) {
  console.error("tls-proxy: could not listen on 127.0.0.1");
  process.exit(1);
}
for (const signal of ["SIGTERM", "SIGINT"]) process.on(signal, () => process.exit(0));
