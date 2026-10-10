// What the island tells the phone link (src/island/phone-link.ts): the picture it
// publishes, that nothing runs while the link is off, and that a phone's answer
// closes the card for that request and no other.

import { afterEach, beforeEach, mock, test } from "node:test";
import assert from "node:assert/strict";
import { calls, emit, internals, sent } from "./tauri.mjs";
import { bareCommand, folderName, linkSnapshot, registerPhoneLink } from "../src/island/phone-link.ts";
import { makeDiffStep } from "../src/core/diff.ts";
import { DEFAULT_SETTINGS, State } from "../src/core/state.ts";

const CLAUDE = "integration_claude";

let status = { enabled: true, running: true, port: 47821, host: "192.168.1.2", name: "PC", clients: 0, error: null };
const original = internals.invoke;
internals.invoke = async (cmd, args) => (cmd === "phone_link_status" ? status : original(cmd, args));

let asked;
let dispose = () => {};
const island = {
  setView: (view) => asked.push(`setView:${view}`),
  dropPin: () => asked.push("dropPin"),
};

/** Lets the async status call settle (setImmediate is not mocked). */
const settle = () => new Promise((resolve) => setImmediate(resolve));
const tick = (ms) => mock.timers.tick(ms);

beforeEach(() => {
  mock.timers.enable({ apis: ["setTimeout"] });
  asked = [];
  calls.length = 0;
  status = { ...status, running: true };
  State.tasks = [];
  State.focusId = null;
  State.pendingApproval = null;
  State.settings = { ...DEFAULT_SETTINGS, activeIntegrations: ["integration_github"] };
  State.loadIntegrationTasks();
});

afterEach(() => {
  dispose();
  mock.timers.runAll();
  mock.timers.reset();
});

test("the phone sees the agents, not the services", () => {
  const { sessions } = linkSnapshot();
  const ids = sessions.map((s) => s.pillId);
  assert.ok(ids.includes(CLAUDE));
  assert.ok(!ids.includes("integration_github"), "a service is not an agent session");
  const claude = sessions.find((s) => s.pillId === CLAUDE);
  assert.equal(claude.state, "idle");
  assert.equal(claude.statusText, "");
});

test("a session carries its state, its last step and its progress", () => {
  State.updateTask(CLAUDE, "working");
  State.appendStep(CLAUDE, "Reading the project");
  State.appendStep(CLAUDE, "Editing files");
  const claude = linkSnapshot().sessions.find((s) => s.pillId === CLAUDE);
  assert.equal(claude.state, "working");
  assert.equal(claude.statusText, "Editing files");
  assert.equal(claude.stepCount, 2);
  assert.equal(claude.stepIndex, 1);
});

test("a step that stands for a file diff is shown by the file's name", () => {
  State.appendStep(CLAUDE, makeDiffStep("src/app.ts", 3, 1, 7));
  const claude = linkSnapshot().sessions.find((s) => s.pillId === CLAUDE);
  assert.equal(claude.statusText, "src/app.ts");
});

test("only a permission request goes to the phone, not a question", () => {
  assert.equal(linkSnapshot().approval, null);

  State.beginApproval({ requestId: "r1", sessionId: "s1", pillId: CLAUDE, tool: "Bash", command: "npm test" });
  assert.deepEqual(linkSnapshot().approval, {
    requestId: "r1", sessionId: "s1", pillId: CLAUDE, tool: "Bash", command: "npm test",
  });

  State.endApproval();
  State.beginApproval({
    requestId: "r2", sessionId: "s1", pillId: CLAUDE, tool: "AskUserQuestion", command: "Which branch?",
    questions: [{ question: "Which branch?", options: [], multiSelect: false }],
  });
  assert.equal(linkSnapshot().approval, null, "a question needs its options picked on the island");

  State.endApproval();
  State.beginApproval({ requestId: "", sessionId: "s1", pillId: CLAUDE, tool: "Bash", command: "ls" });
  assert.equal(linkSnapshot().approval, null, "no request id, nothing the phone could answer");
});

test("the phone gets the bare command, not the island's \"Bash · \" label", () => {
  State.beginApproval({ requestId: "r1", sessionId: "s1", pillId: CLAUDE, tool: "Bash", command: "Bash · npm run build" });
  assert.equal(linkSnapshot().approval.command, "npm run build");
  assert.equal(bareCommand("Edit", "Edit · src/a.ts"), "src/a.ts");
  // A tool with no command to show, or a command that merely mentions the tool, is left alone.
  assert.equal(bareCommand("Task", "Task"), "Task");
  assert.equal(bareCommand("Bash", "echo Bash · hi"), "echo Bash · hi");
});

test("a burst of changes is one picture, and an unchanged picture is not sent again", async () => {
  dispose = registerPhoneLink(island);
  await settle();
  tick(250);
  assert.equal(sent("phone_link_publish").length, 1, "the whole picture goes out when the link is up");

  State.updateTask(CLAUDE, "working");
  State.appendStep(CLAUDE, "one");
  State.appendStep(CLAUDE, "two");
  assert.equal(sent("phone_link_publish").length, 1, "waits for the burst to end");
  tick(250);
  assert.equal(sent("phone_link_publish").length, 2);
  const last = sent("phone_link_publish")[1];
  assert.equal(last.sessions.find((s) => s.pillId === CLAUDE).statusText, "two");
  assert.equal(last.approval, null);

  State.notify(); // something redrew, nothing changed for the phone
  tick(250);
  assert.equal(sent("phone_link_publish").length, 2);
});

test("while the link is off nothing is published", async () => {
  status = { ...status, running: false };
  dispose = registerPhoneLink(island);
  await settle();
  State.updateTask(CLAUDE, "working");
  State.appendStep(CLAUDE, "busy");
  tick(1000);
  assert.equal(sent("phone_link_publish").length, 0);
});

test("turning the link on in Settings sends the whole picture at once", async () => {
  status = { ...status, running: false };
  dispose = registerPhoneLink(island);
  await settle();
  State.updateTask(CLAUDE, "thinking");
  tick(1000);
  assert.equal(sent("phone_link_publish").length, 0);

  status = { ...status, running: true };
  emit("settings-changed", {});
  await settle();
  tick(250);
  assert.equal(sent("phone_link_publish").length, 1);
  assert.equal(sent("phone_link_publish")[0].sessions.find((s) => s.pillId === CLAUDE).state, "thinking");

  // And off again: the next change is not sent.
  status = { ...status, running: false };
  emit("settings-changed", {});
  await settle();
  State.updateTask(CLAUDE, "working");
  tick(1000);
  assert.equal(sent("phone_link_publish").length, 1);
});

test("a request waiting for an answer is published, and withdrawn once it is answered", async () => {
  dispose = registerPhoneLink(island);
  await settle();
  tick(250);
  State.beginApproval({ requestId: "r1", sessionId: "s1", pillId: CLAUDE, tool: "Bash", command: "rm -rf build" });
  tick(250);
  assert.equal(sent("phone_link_publish").at(-1).approval.requestId, "r1");
  State.endApproval(); // answered at the desk
  tick(250);
  assert.equal(sent("phone_link_publish").at(-1).approval, null);
});

test("an answer from the phone closes the card for that request only", async () => {
  dispose = registerPhoneLink(island);
  await settle();
  State.beginApproval({ requestId: "r1", sessionId: "s1", pillId: CLAUDE, tool: "Bash", command: "ls" });

  emit("phone-link-decided", "some-other-request");
  assert.ok(State.pendingApproval, "another request's answer must not close this card");

  emit("phone-link-decided", "r1");
  assert.equal(State.pendingApproval, null);
  assert.ok(asked.includes("dropPin"));
  // The island itself sends nothing: Rust already answered the agent.
  assert.equal(sent("approval_decision").length, 0);
});

// ── session details (cap `details`) ──────────────────────────────────────────────────────────

test("a session carries its last steps, its final line, its folder name and its colour", () => {
  State.appendStep(CLAUDE, "Reading the project");
  State.appendStep(CLAUDE, makeDiffStep("src/app.ts", 3, 1, 7));
  const task = State.tasks.find((t) => t.id === CLAUDE);
  task.finalLine = "All done";
  task.sessionCwd = "/home/me/private/coucou";
  const claude = linkSnapshot().sessions.find((s) => s.pillId === CLAUDE);
  assert.deepEqual(claude.steps.slice(-2), ["Reading the project", "src/app.ts"], "a diff step is its file name");
  assert.equal(claude.finalLine, "All done");
  assert.equal(claude.project, "coucou", "the folder's name, not the path");
  assert.equal(claude.color, task.color);
  assert.ok(!JSON.stringify(claude).includes("private"));
});

test("at most the last twenty steps go to Rust", () => {
  for (let i = 0; i < 30; i++) State.appendStep(CLAUDE, `step ${i}`);
  const claude = linkSnapshot().sessions.find((s) => s.pillId === CLAUDE);
  assert.equal(claude.steps.length, 20);
  assert.equal(claude.steps.at(-1), "step 29");
});

test("a folder name is the last segment, whichever way the path is written", () => {
  assert.equal(folderName("/home/me/work/app"), "app");
  assert.equal(folderName("/home/me/work/app/"), "app");
  assert.equal(folderName("C:\\Users\\me\\app"), "app");
  assert.equal(folderName("C:\\Users\\me\\app\\"), "app");
  assert.equal(folderName("app"), "app");
  for (const none of [null, undefined, "", "/", "\\", "..", "/a/.."]) assert.equal(folderName(none), undefined, String(none));
});

test("a session with nothing to add has no empty details", () => {
  const task = State.tasks.find((t) => t.id === "agent_gemini") ?? State.tasks[0];
  const s = linkSnapshot().sessions.find((x) => x.pillId === task.id);
  if (s) {
    assert.equal(s.finalLine, undefined);
    assert.equal(s.project, undefined);
  }
});

test("a question goes to the phone as its own message, with the options and nothing else of the request", () => {
  assert.equal(linkSnapshot().question, null);
  State.beginApproval({
    requestId: "q1", sessionId: "s1", pillId: CLAUDE, tool: "AskUserQuestion", command: "Which branch?",
    questions: [
      { question: "Which branch?", options: [{ label: "main", description: "the default" }, { label: "dev", description: "" }], multiSelect: false },
      { question: "Which checks?", options: [{ label: "lint", description: "style" }], multiSelect: true },
    ],
  });
  const snap = linkSnapshot();
  assert.equal(snap.approval, null, "it is not a permission request");
  assert.deepEqual(snap.question, {
    requestId: "q1", sessionId: "s1", pillId: CLAUDE,
    questions: [
      { question: "Which branch?", options: [{ label: "main", description: "the default" }, { label: "dev", description: "" }], multiSelect: false },
      { question: "Which checks?", options: [{ label: "lint", description: "style" }], multiSelect: true },
    ],
  });
  assert.ok(!JSON.stringify(snap.question).includes("command"), "the island's command text is not part of it");
});

test("a request without an id, or a question without options, is not offered", () => {
  State.beginApproval({ requestId: "", sessionId: "s1", pillId: CLAUDE, tool: "AskUserQuestion", command: "x", questions: [{ question: "A?", options: [{ label: "y", description: "" }], multiSelect: false }] });
  assert.equal(linkSnapshot().question, null);
  State.endApproval();
  State.beginApproval({ requestId: "q2", sessionId: "s1", pillId: CLAUDE, tool: "AskUserQuestion", command: "x", questions: [] });
  assert.equal(linkSnapshot().question, null);
});

test("the question is published when it appears and withdrawn when it is answered, and not repeated", async () => {
  dispose = registerPhoneLink(island);
  await settle();
  tick(250);
  const q = () => sent("phone_link_publish_question");
  assert.equal(q().length, 1, "the hub is told there is none when the link comes up");
  assert.equal(q()[0].question, null);

  State.beginApproval({ requestId: "q1", sessionId: "s1", pillId: CLAUDE, tool: "AskUserQuestion", command: "x", questions: [{ question: "A?", options: [{ label: "y", description: "" }], multiSelect: false }] });
  tick(250);
  assert.equal(q().length, 2);
  assert.equal(q()[1].question.requestId, "q1");

  State.notify();
  tick(250);
  assert.equal(q().length, 2, "unchanged: not sent again");

  State.endApproval();
  tick(250);
  assert.equal(q().length, 3);
  assert.equal(q()[2].question, null);
});
