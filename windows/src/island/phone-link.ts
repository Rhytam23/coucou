// What the island tells the phone link (src-tauri/src/phone_link).
//
// While the link is on, the island publishes a picture of its agent sessions and
// of the permission request waiting for an answer; Rust works out what changed
// and sends it to the paired phone. A phone's Allow or Deny comes back through
// Rust (which answers the waiting hook) and only the card on the island is
// closed here. While the link is off nothing is computed and no timer runs.

import { Bridge, onEvent, type PhoneLinkApproval, type PhoneLinkQuestion, type PhoneLinkSession } from "../core/bridge";
import { parseDiffStep } from "../core/diff";
import { State } from "../core/state";
import { dropPendingCard } from "./hooks";
import type { Island } from "./island";

/** Changes arrive in bursts (a tool call is several events); one picture per burst. */
const DEBOUNCE_MS = 250;

export interface LinkSnapshot {
  sessions: PhoneLinkSession[];
  approval: PhoneLinkApproval | null;
  /** The question Claude Code is asking, offered only to phones that may answer (Rust decides who). */
  question: PhoneLinkQuestion | null;
}

/**
 * The island labels what it shows as "Bash · npm test". The phone shows the tool on its own
 * line, so it gets the bare command.
 */
export function bareCommand(tool: string, command: string): string {
  const prefix = `${tool} · `;
  return command.startsWith(prefix) ? command.slice(prefix.length) : command;
}

/** Only the last segment of a folder path, whichever way it is written; never the path itself. */
export function folderName(path: string | null | undefined): string | undefined {
  const last = (path ?? "").split(/[\\/]+/).filter(Boolean).pop();
  return last && last !== "." && last !== ".." ? last : undefined;
}

/** A step as the phone sees it: a file diff by its file name, anything else as it is. */
function stepText(step: string): string {
  const diff = parseDiffStep(step);
  return diff ? diff.filename : step;
}

/** The agents' sessions — not the services — and the request the phone may answer. */
export function linkSnapshot(): LinkSnapshot {
  const sessions: PhoneLinkSession[] = State.tasks
    .filter((t) => t.source !== "n8n")
    .map((t) => {
      const last = t.steps[t.steps.length - 1] ?? "";
      // A step that stands for a file diff is shown by the file's name.
      const diff = parseDiffStep(last);
      return {
        pillId: t.id,
        agent: t.name,
        state: t.state,
        statusText: diff ? diff.filename : last,
        stepIndex: t.stepIndex,
        stepCount: t.steps.length,
        // Session details. Rust sends them only to a phone that asked and was offered them (the user's
        // switch), and cuts them to size; the folder's name is all that leaves this file, not the path.
        steps: t.steps.slice(-20).map(stepText),
        finalLine: t.finalLine || undefined,
        project: folderName(t.sessionCwd),
        color: t.color,
      };
    });

  // Permission requests (Allow / Deny) go to every phone. A question goes only to a phone that has the `answers`
  // capability (Rust decides), as its own message: the options with their labels, nothing else of the request.
  const a = State.pendingApproval;
  const approval =
    a && !a.questions && a.requestId
      ? { requestId: a.requestId, sessionId: a.sessionId, pillId: a.pillId, tool: a.tool, command: bareCommand(a.tool, a.command) }
      : null;
  const question =
    a && a.questions && a.questions.length > 0 && a.requestId
      ? {
          requestId: a.requestId,
          sessionId: a.sessionId,
          pillId: a.pillId,
          questions: a.questions.map((q) => ({
            question: q.question,
            options: q.options.map((o) => ({ label: o.label, description: o.description ?? "" })),
            multiSelect: q.multiSelect,
          })),
        }
      : null;
  return { sessions, approval, question };
}

/** Returns what undoes it (the tests use it; the app registers once and keeps it). */
export function registerPhoneLink(island: Island): () => void {
  let running = false;
  let last = "";
  /** What was last sent as the question; "" before anything was, so a link that comes up syncs the hub. */
  let lastQuestion = "";
  let timer: number | null = null;

  const publish = () => {
    timer = null;
    publishQuestion();
    const snapshot = linkSnapshot();
    const key = JSON.stringify(snapshot);
    if (key === last) return;
    last = key;
    void Bridge.phoneLinkPublish(snapshot.sessions, snapshot.approval);
  };

  const publishQuestion = () => {
    const key = JSON.stringify(linkSnapshot().question);
    if (key === lastQuestion) return;
    lastQuestion = key;
    void Bridge.phoneLinkPublishQuestion(JSON.parse(key));
  };

  const schedule = () => {
    if (!running || timer != null) return;
    timer = window.setTimeout(publish, DEBOUNCE_MS);
  };

  const refresh = async () => {
    const status = await Bridge.phoneLinkStatus();
    const now = status?.running ?? false;
    if (now === running) return;
    running = now;
    last = ""; // a link that has just come up needs the whole picture
    lastQuestion = "";
    if (running) schedule();
    else if (timer != null) {
      window.clearTimeout(timer);
      timer = null;
    }
  };

  const unsubscribe = State.subscribe(schedule);
  // Turning the link on or off in Settings reaches the island as settings-changed.
  void onEvent("settings-changed", () => void refresh());
  // The phone answered: Rust has already told the agent; the card goes.
  void onEvent<string>("phone-link-decided", (requestId) => {
    if (State.pendingApproval?.requestId === requestId) dropPendingCard(island);
  });
  void refresh();
  return () => {
    unsubscribe();
    if (timer != null) window.clearTimeout(timer);
    timer = null;
    running = false;
  };
}
