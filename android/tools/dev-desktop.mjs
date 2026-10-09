#!/usr/bin/env node
// A pretend Coucou desktop for developing and testing the Android app without the real one.
// Speaks docs/ANDROID_LINK.md (v1): TLS with a self-signed certificate, newline-delimited JSON.
//
//   node android/tools/dev-desktop.mjs [--host IP] [--port N] [--name "My PC"] [--step MS] [--once] [--fake-chat] [--details]
//
// --details offers the "details" capability: the sessions then carry steps, finalLine, project (a folder
// name) and color, like the real desktop with "Show session details on the phone" turned on. A phone that
// did not ask for it gets the plain v1 sessions.
//
// --fake-chat offers the "chat" capability with a FAKE provider, so the phone's Chat screen can be tried
// without a key and without spending anything. Models: fake/echo, fake/other. Special messages:
//   /error  a provider error      /auth  a refused key      /slow  a never-ending answer (Cancel it)
//   /long   a 20 KB answer        /rewrite  a streamed text that the final answer replaces
// anything else is answered word by word. Same limits as the real desktop (4000 characters,
// one answer at a time, 12 sends per 10 minutes).
//
// It prints a pairing link (paste it in the app, or open it on the phone). Needs `openssl` on PATH.
// Not for production: the real desktop link lives in the Tauri app.
import tls from "node:tls";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";

const arg = (name, def) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? process.argv[i + 1] : def;
};
const flag = (name) => process.argv.includes(`--${name}`);

const V = 1;
const MAX_LINE = 64 * 1024;
const NAME = arg("name", os.hostname());
const STEP_MS = Number(arg("step", "2500"));
const TOKEN = arg("token", crypto.randomBytes(18).toString("base64url"));
const lanIp = () => {
  for (const list of Object.values(os.networkInterfaces()))
    for (const a of list ?? []) if (a.family === "IPv4" && !a.internal) return a.address;
  return "127.0.0.1";
};
const HOST = arg("host", lanIp());

// The Mac's ApprovalRelay.fingerprint: SHA-256 of the fields joined by U+001F.
export const fingerprint = (pill, session, tool, command, inputKey) =>
  crypto.createHash("sha256").update([pill, session, tool, command, inputKey].join("\u001f"), "utf8").digest("hex");

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "coucou-dev-"));
execFileSync("openssl", [
  "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
  "-keyout", path.join(dir, "key.pem"), "-out", path.join(dir, "cert.pem"),
  "-days", "30", "-subj", "/CN=coucou-dev-desktop",
], { stdio: "ignore" });
const key = fs.readFileSync(path.join(dir, "key.pem"));
const cert = fs.readFileSync(path.join(dir, "cert.pem"));
fs.rmSync(dir, { recursive: true, force: true });
const certSha256 = new crypto.X509Certificate(cert).fingerprint256.replaceAll(":", "").toLowerCase();

const log = (...a) => console.log(...a);

// Details the way the real desktop adds them (hub.rs): only for a phone that asked and was offered them.
const DETAILS = {
  integration_claude: { steps: ["Read · README.md", "Grep · TODO", "Edit · src/app.ts", "Bash · npm test", "Edit · src/app.ts"], project: "coucou", color: "#2DD4BF" },
  agent_codex: { steps: ["Search · the code", "Read · lib.rs"], project: "api-server", color: "#E879F9" },
  agent_gemini: { steps: [], project: "notes", color: "#8AB4F8" },
};
const withDetails = (list, state) =>
  list.map((s) => {
    const d = DETAILS[s.pillId] ?? {};
    const out = { ...s, ...(d.steps?.length ? { steps: d.steps } : {}), ...(d.project ? { project: d.project } : {}), ...(d.color ? { color: d.color } : {}) };
    if (s.state === "finished") out.finalLine = "I updated the tests and everything passes.";
    return out;
  });

// ── Fake chat (the messages are those of phone_link/chat.rs, with a fake provider) ─────────────
const CHAT_MODELS = [
  { id: "fake/echo", provider: "fake", label: "Fake · echo" },
  { id: "fake/other", provider: "fake", label: "Fake · other" },
];
const CHAT_REASONS = {
  off: "Chat from the phone is turned off on the computer.",
  not_allowed: "That model is not allowed for the phone.",
  busy: "Another answer is still being written.",
  rate: "Too many messages. Try again in a few minutes.",
  too_long: "The message is too long.",
  empty: "Nothing to send.",
  auth: "The provider refused the computer's key.",
  provider: "The provider returned an error.",
  canceled: "Canceled.",
};
const CHAT_MAX_TEXT = 4000;
const CHAT_MAX_SENDS = 12;
const CHAT_WINDOW_MS = 10 * 60 * 1000;
const CHAT_PIECE = 8 * 1024;
const chatSends = [];

function script(now) {
  const s = (pillId, agent, state, statusText, stepIndex, stepCount) => ({ pillId, agent, state, statusText, stepIndex, stepCount, updatedAt: now });
  return [
    [s("integration_claude", "Claude Code", "thinking", "Reading the project", 1, 6)],
    [s("integration_claude", "Claude Code", "working", "Editing files", 2, 6), s("agent_codex", "Codex", "searching", "Searching the code", 1, 4)],
    "approval",
    [s("integration_claude", "Claude Code", "working", "Building", 4, 6), s("agent_codex", "Codex", "question", "Which branch?", 3, 4)],
    [s("integration_claude", "Claude Code", "finished", "Done", 6, 6), s("agent_codex", "Codex", "error", "Tests failed", 3, 4)],
    [s("agent_gemini", "Gemini CLI", "sleeping", "Idle", 0, 0)],
  ];
}

const server = tls.createServer({ key, cert, minVersion: "TLSv1.2" }, (sock) => {
  sock.setNoDelay(true);
  sock.setTimeout(90_000, () => sock.destroy());
  const send = (o) => sock.writable && sock.write(JSON.stringify(o) + "\n");
  let buf = "";
  let authed = false;
  let timer = null;
  let pending = null; // { fingerprint }
  let step = 0;
  let chat = false; // negotiated in the hello
  let details = false;
  const sessionsMsg = (list) => ({ type: "sessions", sessions: details ? withDetails(list) : list });
  let run = null; // { id, timer }
  const chatError = (id, reason) => send({ type: "chatError", id, reason, message: CHAT_REASONS[reason] ?? "Something went wrong on the computer." });
  const stopRun = () => { if (run) { clearTimeout(run.timer); clearInterval(run.timer); run = null; } };

  const streamWords = (id, words, everyMs, done) => {
    let i = 0;
    const timer = setInterval(() => {
      if (!run || run.id !== id) return clearInterval(timer);
      if (i >= words.length) { clearInterval(timer); return done(); }
      send({ type: "chatDelta", id, text: (i === 0 ? "" : " ") + words[i++] });
    }, everyMs);
    run.timer = timer;
  };

  const chatSend = (m) => {
    const id = m.id;
    if (typeof id !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(id)) return;
    const text = typeof m.text === "string" ? m.text : "";
    if (!CHAT_MODELS.some((x) => x.id === m.model)) return chatError(id, "not_allowed");
    if (!text.trim()) return chatError(id, "empty");
    if ([...text].length > CHAT_MAX_TEXT) return chatError(id, "too_long");
    if (run) return chatError(id, "busy");
    const now = Date.now();
    while (chatSends.length && now - chatSends[0] >= CHAT_WINDOW_MS) chatSends.shift();
    if (chatSends.length >= CHAT_MAX_SENDS) return chatError(id, "rate");
    chatSends.push(now);
    log(`CHAT send ${m.model} ${JSON.stringify(text.slice(0, 60))}`);
    run = { id, timer: null };
    const done = (full) => { if (run?.id === id) { stopRun(); send(full === undefined ? { type: "chatDone", id } : { type: "chatDone", id, text: full }); } };
    if (text.startsWith("/error")) { run.timer = setTimeout(() => { stopRun(); chatError(id, "provider"); }, 200); return; }
    if (text.startsWith("/auth")) { run.timer = setTimeout(() => { stopRun(); chatError(id, "auth"); }, 200); return; }
    if (text.startsWith("/slow")) return streamWords(id, Array.from({ length: 200 }, (_, i) => `slow${i}`), 300, () => done());
    if (text.startsWith("/rewrite")) {
      send({ type: "chatDelta", id, text: "<think>hmm" });
      run.timer = setTimeout(() => done("Answer"), 100);
      return;
    }
    if (text.startsWith("/long")) {
      const big = "Lorem ipsum dolor sit amet. ".repeat(750);
      for (let i = 0; i < big.length; i += CHAT_PIECE) send({ type: "chatDelta", id, text: big.slice(i, i + CHAT_PIECE) });
      run.timer = setTimeout(() => done(), 50);
      return;
    }
    streamWords(id, `Fake answer to: ${text}`.split(" "), 60, () => done());
  };

  const advance = () => {
    const entries = script(Date.now());
    const e = entries[step % entries.length];
    step++;
    if (e === "approval") {
      const command = "npm run build";
      const fp = fingerprint("integration_claude", "dev_session", "Bash", command, `n${step}`);
      pending = { fingerprint: fp };
      send(sessionsMsg([
        { pillId: "integration_claude", agent: "Claude Code", state: "approval", statusText: "Waiting for your approval", stepIndex: 3, stepCount: 6, updatedAt: Date.now() },
      ]));
      send({ type: "approval", pillId: "integration_claude", fingerprint: fp, tool: "Bash", command, createdAt: Date.now() });
      log(`APPROVAL sent ${fp}`);
    } else {
      send(sessionsMsg(e));
    }
  };

  const handle = (line) => {
    let m;
    try { m = JSON.parse(line); } catch { return sock.destroy(); }
    if (!authed) {
      if (m.type !== "hello") return sock.destroy();
      const ok = m.v === V && typeof m.token === "string" &&
        m.token.length === TOKEN.length && crypto.timingSafeEqual(Buffer.from(m.token), Buffer.from(TOKEN));
      if (!ok) { send({ type: "error", code: "auth", message: "bad token or version" }); log("AUTH rejected"); return sock.end(); }
      authed = true;
      log(`HELLO ok device=${JSON.stringify(m.device)}`);
      chat = flag("fake-chat") && Array.isArray(m.caps) && m.caps.includes("chat");
      details = flag("details") && Array.isArray(m.caps) && m.caps.includes("details");
      const offered = [...(chat ? ["chat"] : []), ...(details ? ["details"] : [])];
      send({ type: "welcome", v: V, desktop: NAME, os: process.platform, ...(offered.length ? { caps: offered } : {}) });
      advance();
      timer = setInterval(advance, STEP_MS);
      return;
    }
    switch (m.type) {
      case "ping": return send({ type: "pong" });
      case "bye": log("BYE"); return sock.end();
      case "chatModels": return chat && send({ type: "chatModels", models: CHAT_MODELS });
      case "chatSend": return chat && chatSend(m);
      case "chatCancel": {
        if (chat && run && run.id === m.id) { const id = run.id; stopRun(); chatError(id, "canceled"); log(`CHAT canceled ${id}`); }
        return;
      }
      case "chatReset": {
        if (chat) { if (run) { const id = run.id; stopRun(); chatError(id, "canceled"); } log("CHAT reset"); }
        return;
      }
      case "decision": {
        if (pending && m.fingerprint === pending.fingerprint && (m.decision === "allow" || m.decision === "deny")) {
          log(`DECISION ${m.decision} ${m.fingerprint}`);
          send({ type: "approvalResolved", fingerprint: pending.fingerprint });
          pending = null;
          if (flag("once")) setTimeout(() => process.exit(0), 200);
        } else {
          log(`DECISION ignored ${m.fingerprint}`);
        }
        return;
      }
      default: return;
    }
  };

  sock.on("data", (d) => {
    buf += d.toString("utf8");
    if (buf.length > MAX_LINE * 2) return sock.destroy();
    let i;
    while ((i = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, i);
      buf = buf.slice(i + 1);
      if (line.length > MAX_LINE) return sock.destroy();
      if (line) handle(line);
    }
  });
  sock.on("close", () => { if (timer) clearInterval(timer); stopRun(); });
  sock.on("error", () => {});
});

server.listen(Number(arg("port", "47821")), "0.0.0.0", () => {
  const port = server.address().port;
  const link = `coucou://pair?v=${V}&host=${HOST}&port=${port}&fp=${certSha256}&token=${TOKEN}&name=${encodeURIComponent(NAME)}`;
  log(JSON.stringify({ listening: port, fp: certSha256, link }));
  if (!flag("quiet")) console.error(`\nPairing link (paste it in Coucou for Android):\n${link}\n`);
});
