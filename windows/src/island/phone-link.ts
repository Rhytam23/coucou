// What the island tells the phone link (src-tauri/src/phone_link).
//
// While the link is on, the island publishes a picture of its agent sessions and
// of the permission request waiting for an answer; Rust works out what changed
// and sends it to the paired phone. A phone's Allow or Deny comes back through
// Rust (which answers the waiting hook) and only the card on the island is
// closed here. While the link is off nothing is computed and no timer runs.

import { Bridge, onEvent, type PhoneLinkApproval, type PhoneLinkSession } from "../core/bridge";
import { parseDiffStep } from "../core/diff";
import { State } from "../core/state";
import { dropPendingCard } from "./hooks";
import type { Island } from "./island";

/** Changes arrive in bursts (a tool call is several events); one picture per burst. */
const DEBOUNCE_MS = 250;

export interface LinkSnapshot {
  sessions: PhoneLinkSession[];
  approval: PhoneLinkApproval | null;
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
      };
    });

  // A question Claude Code asks needs its options picked on the island: only
  // permission requests (Allow / Deny) go to the phone.
  const a = State.pendingApproval;
  const approval =
    a && !a.questions && a.requestId
      ? { requestId: a.requestId, sessionId: a.sessionId, pillId: a.pillId, tool: a.tool, command: a.command }
      : null;
  return { sessions, approval };
}

/** Returns what undoes it (the tests use it; the app registers once and keeps it). */
export function registerPhoneLink(island: Island): () => void {
  let running = false;
  let last = "";
  let timer: number | null = null;

  const publish = () => {
    timer = null;
    const snapshot = linkSnapshot();
    const key = JSON.stringify(snapshot);
    if (key === last) return;
    last = key;
    void Bridge.phoneLinkPublish(snapshot.sessions, snapshot.approval);
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
