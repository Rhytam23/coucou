// What the phone is told, and what it may decide.
//
// The hub holds the last list of sessions and the one permission request that is
// still waiting, as the island published them (phone_link_publish). It knows
// nothing about sockets or Tauri: connections subscribe to it, and a decision
// from a phone goes back to the app through the `Host` trait. That keeps every
// rule of docs/ANDROID_LINK.md testable without a window.
//
// The rules that matter:
//   * a phone decision is applied only if its fingerprint is the one of the
//     request that is *still* pending (same session, tool, command and request);
//   * only one decision is ever applied per request;
//   * a request older than APPROVAL_TTL is dismissed, whatever the phone says;
//   * nothing here can block the agent: the desktop's own card keeps working,
//     and a phone that never answers changes nothing.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use ring::digest;
use serde::{Deserialize, Serialize};
use serde_json::json;
use tokio::sync::mpsc;

/// The desktop dismisses a request after this long (the phone offers it for 120 s).
pub const APPROVAL_TTL_MS: u64 = 115_000;

const MAX_SESSIONS: usize = 16;
const MAX_COMMAND_CHARS: usize = 500;
const MAX_TEXT_CHARS: usize = 200;
/// Details (cap `details`): at most this many steps per session, each cut at MAX_TEXT_CHARS.
pub const MAX_STEPS: usize = 20;
const MAX_PROJECT_CHARS: usize = 64;
/// A `sessions` line with details stays under this, so it never reaches the 64 KiB limit of a line
/// (16 sessions of 20 steps of 200 characters would be 64 KB of text alone).
pub const DETAILS_LINE_BUDGET: usize = 56 * 1024;

/// Questions (cap `answers`): a question that does not fit these limits is left to the island, never cut,
/// because the answer must carry the exact text and labels back to the agent.
pub const MAX_QUESTIONS: usize = 4;
pub const MAX_OPTIONS: usize = 8;
const MAX_QUESTION_CHARS: usize = 500;
const MAX_LABEL_CHARS: usize = 120;
const MAX_DESCRIPTION_CHARS: usize = 300;

/// The states the phone draws; anything else is sent as `idle`.
const STATES: &[&str] = &[
    "idle", "working", "thinking", "searching", "approval", "question", "error", "finished",
    "ratelimit", "sleeping", "dizzy",
];

/// How a decision gets back to the agent. The app's implementation answers the
/// waiting `coucou-hook` and closes the card on the island.
pub trait Host: Send + Sync {
    fn decide(&self, request_id: &str, allow: bool);
    /// A phone answered a question Claude Code asked: each question's text mapped to the label picked (a list of
    /// labels for a multi-select), the shape AskUserQuestion takes. Only called for the question still pending.
    fn answer(&self, _request_id: &str, _answers: &serde_json::Map<String, serde_json::Value>) {}
}

/// One agent session, as the island reports it.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SessionIn {
    pub pill_id: String,
    pub agent: String,
    pub state: String,
    pub status_text: String,
    pub step_index: u32,
    pub step_count: u32,
    /// Details, sent only to phones that asked for and were offered `details`.
    pub steps: Vec<String>,
    pub final_line: Option<String>,
    /// The folder the session runs in; only its last segment is ever kept.
    pub project: Option<String>,
    pub color: Option<String>,
}

impl Default for SessionIn {
    fn default() -> Self {
        Self {
            pill_id: String::new(),
            agent: String::new(),
            state: "idle".into(),
            status_text: String::new(),
            step_index: 0,
            step_count: 0,
            steps: Vec::new(),
            final_line: None,
            project: None,
            color: None,
        }
    }
}

/// The permission request waiting on the island.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ApprovalIn {
    pub request_id: String,
    pub session_id: String,
    pub pill_id: String,
    pub tool: String,
    pub command: String,
}

/// The values the island stores for Mochi's wardrobe ("auto" follows the seasons on the phone's own calendar).
/// Same raw values as the Mac and the PC; anything else is not sent.
pub const OUTFIT_CHOICES: [&str; 13] = [
    "auto", "none", "partyHat", "beanie", "crown", "sunglasses", "roundGlasses",
    "bow", "scarf", "witchHat", "pumpkin", "santaHat", "bunnyEars",
];

fn prefs_line(outfit: &str) -> String {
    json!({ "type": "prefs", "outfit": outfit }).to_string()
}

/// One option of a question, as the island has it.
#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct OptionIn {
    pub label: String,
    #[serde(default)]
    pub description: String,
}

/// One question of an AskUserQuestion call.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QuestionItemIn {
    pub question: String,
    pub options: Vec<OptionIn>,
    #[serde(default)]
    pub multi_select: bool,
}

/// The question Claude Code is waiting on, as the island has it (cap `answers`).
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QuestionIn {
    pub request_id: String,
    pub session_id: String,
    pub pill_id: String,
    pub questions: Vec<QuestionItemIn>,
}

struct Question {
    request_id: String,
    fingerprint: String,
    pill_id: String,
    items: Vec<QuestionItemIn>,
    created_at: u64,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
struct Session {
    pill_id: String,
    agent: String,
    state: String,
    status_text: String,
    step_index: u32,
    step_count: u32,
    updated_at: u64,
    // Not part of the v1 session: added by `detailed_line` for phones that have `details`.
    #[serde(skip)]
    steps: Vec<String>,
    #[serde(skip)]
    final_line: Option<String>,
    #[serde(skip)]
    project: Option<String>,
    #[serde(skip)]
    color: Option<String>,
}

impl Session {
    fn same_content(&self, other: &Session) -> bool {
        Session { updated_at: 0, ..self.clone() } == Session { updated_at: 0, ..other.clone() }
    }
}

struct Approval {
    request_id: String,
    fingerprint: String,
    pill_id: String,
    tool: String,
    command: String,
    created_at: u64,
}

/// What reaches a connection's writer.
pub enum Out {
    /// One JSON line, without its newline.
    Line(Arc<str>),
    /// Tell the phone why, then close.
    Close { code: &'static str, message: &'static str },
}

struct Sub {
    id: u64,
    tx: mpsc::Sender<Out>,
    /// Set when the hub gives up on a phone that cannot keep up, so its
    /// connection can be closed (and the phone reconnect to a fresh picture)
    /// instead of staying open and silently going stale.
    evicted: Arc<AtomicBool>,
    /// The phone asked for `details` and the user's switch was on when it connected.
    details: bool,
    /// The phone asked for `answers` and the user's switch was on when it connected.
    answers: bool,
    /// The phone asked for `prefs` (the outfit Mochi wears on the computer).
    prefs: bool,
}

#[derive(Default)]
struct Inner {
    sessions: Vec<Session>,
    approval: Option<Approval>,
    question: Option<Question>,
    /// What Mochi wears on the computer: one of [`OUTFIT_CHOICES`]; None until the island has said.
    outfit: Option<&'static str>,
    subs: Vec<Sub>,
    next_sub: u64,
}

/// What became of a decision from a phone.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    Applied,
    /// Not the pending request (another command, or already answered).
    NoMatch,
    /// The request was pending but is older than APPROVAL_TTL.
    Late,
    /// Neither `allow` nor `deny`.
    Invalid,
}

pub struct Hub {
    host: Arc<dyn Host>,
    inner: Mutex<Inner>,
}

impl Hub {
    pub fn new(host: Arc<dyn Host>) -> Arc<Hub> {
        Arc::new(Hub { host, inner: Mutex::new(Inner::default()) })
    }

    /// The island's current picture. Sends the phones only what changed.
    pub fn publish(&self, sessions: Vec<SessionIn>, approval: Option<ApprovalIn>, now: u64) {
        let mut inner = self.inner.lock().unwrap();

        // Sessions: stamp `updatedAt` where something changed.
        let next: Vec<Session> = sessions
            .into_iter()
            .filter(|s| !s.pill_id.is_empty())
            .take(MAX_SESSIONS)
            .map(|s| {
                let mut fresh = Session {
                    state: if STATES.contains(&s.state.as_str()) { s.state } else { "idle".into() },
                    pill_id: s.pill_id,
                    agent: clip(&s.agent, 64),
                    status_text: clip(&s.status_text, MAX_TEXT_CHARS),
                    step_index: s.step_index,
                    step_count: s.step_count,
                    updated_at: now,
                    steps: clean_steps(&s.steps),
                    final_line: s.final_line.as_deref().map(|l| clip(l, MAX_TEXT_CHARS)).filter(|l| !l.trim().is_empty()),
                    project: s.project.as_deref().and_then(project_name),
                    color: s.color.filter(|c| is_color(c)),
                };
                if let Some(old) = inner.sessions.iter().find(|o| o.pill_id == fresh.pill_id) {
                    if old.same_content(&fresh) {
                        fresh.updated_at = old.updated_at;
                    }
                }
                fresh
            })
            .collect();
        let sessions_changed = next != inner.sessions;
        inner.sessions = next;
        if sessions_changed {
            broadcast_sessions(&mut inner);
        }

        // The pending request: a new one replaces the old, none retracts it.
        let same = match (&inner.approval, &approval) {
            (Some(a), Some(b)) => a.request_id == b.request_id,
            (None, None) => true,
            _ => false,
        };
        if same {
            return;
        }
        if let Some(old) = inner.approval.take() {
            let line = resolved_line(&old.fingerprint);
            broadcast(&mut inner, &line);
        }
        if let Some(a) = approval.filter(|a| !a.request_id.is_empty()) {
            let tool = clip(&a.tool, 64);
            let command = clip(&a.command, MAX_COMMAND_CHARS);
            let approval = Approval {
                fingerprint: fingerprint(&a.pill_id, &a.session_id, &tool, &command, &a.request_id),
                request_id: a.request_id,
                pill_id: a.pill_id,
                tool,
                command,
                created_at: now,
            };
            let line = approval_line(&approval);
            inner.approval = Some(approval);
            broadcast(&mut inner, &line);
        }
    }

    /// Registers a connection that has authenticated. The returned lines bring it
    /// up to date: the sessions, then the request still waiting, if any.
    #[cfg(test)]
    pub fn subscribe(&self, tx: mpsc::Sender<Out>, now: u64, details: bool) -> (u64, Arc<AtomicBool>) {
        self.subscribe_with(tx, now, details, false, false)
    }

    /// Like [`subscribe`], for a phone that may also have been offered `answers` and `prefs`.
    pub fn subscribe_with(&self, tx: mpsc::Sender<Out>, now: u64, details: bool, answers: bool, prefs: bool) -> (u64, Arc<AtomicBool>) {
        let mut inner = self.inner.lock().unwrap();
        expire(&mut inner, now);
        let id = inner.next_sub;
        inner.next_sub += 1;
        let first = if details { detailed_line(&inner.sessions) } else { sessions_line(&inner.sessions) };
        let _ = tx.try_send(Out::Line(first.into()));
        if let Some(a) = &inner.approval {
            let _ = tx.try_send(Out::Line(approval_line(a).into()));
        }
        if answers {
            if let Some(q) = &inner.question {
                let _ = tx.try_send(Out::Line(question_line(q).into()));
            }
        }
        if prefs {
            if let Some(o) = inner.outfit {
                let _ = tx.try_send(Out::Line(prefs_line(o).into()));
            }
        }
        let evicted = Arc::new(AtomicBool::new(false));
        inner.subs.push(Sub { id, tx, evicted: evicted.clone(), details, answers, prefs });
        (id, evicted)
    }

    pub fn unsubscribe(&self, id: u64) {
        self.inner.lock().unwrap().subs.retain(|s| s.id != id);
    }

    /// Unpaired or switched off: every connection is told and closed.
    pub fn kick_all(&self, code: &'static str, message: &'static str) {
        let subs = std::mem::take(&mut self.inner.lock().unwrap().subs);
        for s in subs {
            let _ = s.tx.try_send(Out::Close { code, message });
        }
    }

    pub fn clients(&self) -> usize {
        self.inner.lock().unwrap().subs.len()
    }

    /// A phone's answer. Applied only for the request that is still pending.
    pub fn decide(&self, fingerprint: &str, decision: &str, now: u64) -> Outcome {
        let allow = match decision {
            "allow" => true,
            "deny" => false,
            _ => return Outcome::Invalid,
        };
        let request_id = {
            let mut inner = self.inner.lock().unwrap();
            let late = match &inner.approval {
                Some(a) if constant_eq(a.fingerprint.as_bytes(), fingerprint.as_bytes()) => {
                    now.saturating_sub(a.created_at) > APPROVAL_TTL_MS
                }
                _ => return Outcome::NoMatch,
            };
            // Taken in every case: a request is answered once, or dismissed.
            let a = inner.approval.take().expect("checked above");
            let line = resolved_line(&a.fingerprint);
            broadcast(&mut inner, &line);
            if late {
                return Outcome::Late;
            }
            a.request_id
        };
        // Outside the lock: the host answers the agent and touches the island.
        self.host.decide(&request_id, allow);
        Outcome::Applied
    }
}

impl Hub {
    /// What Mochi wears on the computer ("auto" or an outfit, see [`OUTFIT_CHOICES`]); anything else is ignored.
    /// Only phones that have `prefs` hear of it, and a new one at once.
    pub fn publish_outfit(&self, outfit: &str) {
        let Some(choice) = OUTFIT_CHOICES.iter().find(|c| **c == outfit) else { return };
        let mut inner = self.inner.lock().unwrap();
        if inner.outfit == Some(*choice) {
            return;
        }
        inner.outfit = Some(*choice);
        let shared: Arc<str> = prefs_line(choice).into();
        inner.subs.retain(|s| {
            if !s.prefs {
                return true;
            }
            let kept = s.tx.try_send(Out::Line(shared.clone())).is_ok();
            if !kept {
                s.evicted.store(true, Ordering::SeqCst);
            }
            kept
        });
    }

    /// The question waiting on the island (None: none). Only phones that have `answers` hear of it. One that
    /// does not fit the limits, or whose options cannot be told apart, stays on the island.
    pub fn publish_question(&self, question: Option<QuestionIn>, now: u64) {
        let mut inner = self.inner.lock().unwrap();
        let usable = question.filter(|q| !q.request_id.is_empty() && fits_the_limits(&q.questions));
        let same = match (&inner.question, &usable) {
            (Some(a), Some(b)) => a.request_id == b.request_id,
            (None, None) => true,
            _ => false,
        };
        if same {
            return;
        }
        if let Some(old) = inner.question.take() {
            let line = resolved_line(&old.fingerprint);
            broadcast_to_answers(&mut inner, &line);
        }
        if let Some(q) = usable {
            let texts: Vec<&str> = q.questions.iter().map(|i| i.question.as_str()).collect();
            let question = Question {
                fingerprint: fingerprint(&q.pill_id, &q.session_id, "AskUserQuestion", &texts.join("\n"), &q.request_id),
                request_id: q.request_id,
                pill_id: q.pill_id,
                items: q.questions,
                created_at: now,
            };
            let line = question_line(&question);
            inner.question = Some(question);
            broadcast_to_answers(&mut inner, &line);
        }
    }

    /// A phone's answer to the question that is still pending. `picks` has one list of labels per question, in order.
    /// It is applied only if every label is exactly one of that question's options, one label for a single choice and
    /// at least one (each at most once) for a multiple choice; anything else changes nothing.
    pub fn answer(&self, fingerprint: &str, picks: &[Vec<String>], now: u64) -> Outcome {
        let (request_id, answers) = {
            let mut inner = self.inner.lock().unwrap();
            let (late, answers) = match &inner.question {
                Some(q) if constant_eq(q.fingerprint.as_bytes(), fingerprint.as_bytes()) => {
                    let Some(answers) = build_answers(&q.items, picks) else { return Outcome::Invalid };
                    (now.saturating_sub(q.created_at) > APPROVAL_TTL_MS, answers)
                }
                _ => return Outcome::NoMatch,
            };
            // Taken in every case: a question is answered once, or dismissed.
            let q = inner.question.take().expect("checked above");
            let line = resolved_line(&q.fingerprint);
            broadcast_to_answers(&mut inner, &line);
            if late {
                return Outcome::Late;
            }
            (q.request_id, answers)
        };
        self.host.answer(&request_id, &answers);
        Outcome::Applied
    }
}

fn fits_the_limits(items: &[QuestionItemIn]) -> bool {
    !items.is_empty()
        && items.len() <= MAX_QUESTIONS
        && items.iter().all(|i| {
            let labels: Vec<&str> = i.options.iter().map(|o| o.label.as_str()).collect();
            !i.question.trim().is_empty()
                && i.question.chars().count() <= MAX_QUESTION_CHARS
                && !labels.is_empty()
                && labels.len() <= MAX_OPTIONS
                && labels.iter().all(|l| !l.trim().is_empty() && l.chars().count() <= MAX_LABEL_CHARS)
                && i.options.iter().all(|o| o.description.chars().count() <= MAX_DESCRIPTION_CHARS)
                // two options with the same label could not be told apart in an answer
                && labels.iter().enumerate().all(|(n, l)| !labels[..n].contains(l))
        })
}

fn build_answers(items: &[QuestionItemIn], picks: &[Vec<String>]) -> Option<serde_json::Map<String, serde_json::Value>> {
    if picks.len() != items.len() {
        return None;
    }
    let mut out = serde_json::Map::new();
    for (item, chosen) in items.iter().zip(picks) {
        if chosen.is_empty() || chosen.len() > item.options.len() || (!item.multi_select && chosen.len() != 1) {
            return None;
        }
        for (n, label) in chosen.iter().enumerate() {
            if !item.options.iter().any(|o| &o.label == label) || chosen[..n].contains(label) {
                return None;
            }
        }
        let value = if item.multi_select { json!(chosen) } else { json!(chosen[0]) };
        out.insert(item.question.clone(), value);
    }
    Some(out)
}

fn question_line(q: &Question) -> String {
    let questions: Vec<serde_json::Value> = q
        .items
        .iter()
        .map(|i| {
            json!({
                "question": i.question, "multiSelect": i.multi_select,
                "options": i.options.iter().map(|o| json!({ "label": o.label, "description": o.description })).collect::<Vec<_>>(),
            })
        })
        .collect();
    json!({ "type": "question", "pillId": q.pill_id, "fingerprint": q.fingerprint, "createdAt": q.created_at, "questions": questions }).to_string()
}

/// To the phones that have `answers` only: the others never learn a question existed.
fn broadcast_to_answers(inner: &mut Inner, line: &str) {
    let shared: Arc<str> = line.into();
    inner.subs.retain(|s| {
        if !s.answers {
            return true;
        }
        let kept = s.tx.try_send(Out::Line(shared.clone())).is_ok();
        if !kept {
            s.evicted.store(true, Ordering::SeqCst);
        }
        kept
    });
}

fn expire(inner: &mut Inner, now: u64) {
    if inner.question.as_ref().is_some_and(|q| now.saturating_sub(q.created_at) > APPROVAL_TTL_MS) {
        let q = inner.question.take().expect("checked above");
        let line = resolved_line(&q.fingerprint);
        broadcast_to_answers(inner, &line);
    }
    if inner.approval.as_ref().is_some_and(|a| now.saturating_sub(a.created_at) > APPROVAL_TTL_MS) {
        let a = inner.approval.take().expect("checked above");
        let line = resolved_line(&a.fingerprint);
        broadcast(inner, &line);
    }
}

/// Sends one line to every subscriber; one that cannot keep up (a full queue) or
/// has gone is dropped rather than waited for.
fn broadcast(inner: &mut Inner, line: &str) {
    let shared: Arc<str> = line.into();
    inner.subs.retain(|s| {
        let kept = s.tx.try_send(Out::Line(shared.clone())).is_ok();
        if !kept {
            s.evicted.store(true, Ordering::SeqCst);
        }
        kept
    });
}

fn sessions_line(sessions: &[Session]) -> String {
    json!({ "type": "sessions", "sessions": sessions }).to_string()
}

/// The sessions with their details, for a phone that has the capability. Same fields as the v1 line plus
/// `steps`, `finalLine`, `project` and `color` where there is something to say. Steps are dropped from the
/// oldest first (and then from all sessions) until the line fits.
fn detailed_line(sessions: &[Session]) -> String {
    let mut keep = MAX_STEPS;
    loop {
        let list: Vec<serde_json::Value> = sessions
            .iter()
            .map(|s| {
                let mut v = serde_json::to_value(s).unwrap_or_default();
                let steps = &s.steps[s.steps.len().saturating_sub(keep)..];
                if !steps.is_empty() {
                    v["steps"] = json!(steps);
                }
                if let Some(l) = &s.final_line {
                    v["finalLine"] = json!(l);
                }
                if let Some(p) = &s.project {
                    v["project"] = json!(p);
                }
                if let Some(c) = &s.color {
                    v["color"] = json!(c);
                }
                v
            })
            .collect();
        let line = json!({ "type": "sessions", "sessions": list }).to_string();
        if line.len() <= DETAILS_LINE_BUDGET || keep == 0 {
            return line;
        }
        keep /= 2;
    }
}

/// Every phone gets the picture it asked for: the v1 line, or the one with details.
fn broadcast_sessions(inner: &mut Inner) {
    let basic: Arc<str> = sessions_line(&inner.sessions).into();
    let detailed: Option<Arc<str>> = inner.subs.iter().any(|s| s.details).then(|| detailed_line(&inner.sessions).into());
    inner.subs.retain(|s| {
        let line = match (&detailed, s.details) {
            (Some(d), true) => d.clone(),
            _ => basic.clone(),
        };
        let kept = s.tx.try_send(Out::Line(line)).is_ok();
        if !kept {
            s.evicted.store(true, Ordering::SeqCst);
        }
        kept
    });
}

/// The last MAX_STEPS non-empty steps, each cut to MAX_TEXT_CHARS.
fn clean_steps(steps: &[String]) -> Vec<String> {
    let kept: Vec<String> = steps.iter().filter(|s| !s.trim().is_empty()).map(|s| clip(s, MAX_TEXT_CHARS)).collect();
    kept[kept.len().saturating_sub(MAX_STEPS)..].to_vec()
}

/// Only the last segment of a folder path: never a path, never anything that could hold a user name or
/// a drive. None when nothing usable is left.
pub fn project_name(path: &str) -> Option<String> {
    let last = path.trim_end_matches(['/', '\\']).rsplit(['/', '\\']).next().unwrap_or("");
    let clean: String = last.chars().filter(|c| !c.is_control()).collect();
    let clean = clip(clean.trim(), MAX_PROJECT_CHARS);
    (!clean.is_empty() && clean != "." && clean != "..").then_some(clean)
}

/// "#RRGGBB" only.
fn is_color(c: &str) -> bool {
    c.len() == 7 && c.starts_with('#') && c[1..].bytes().all(|b| b.is_ascii_hexdigit())
}

fn approval_line(a: &Approval) -> String {
    json!({
        "type": "approval", "pillId": a.pill_id, "fingerprint": a.fingerprint,
        "tool": a.tool, "command": a.command, "createdAt": a.created_at,
    })
    .to_string()
}

fn resolved_line(fingerprint: &str) -> String {
    json!({ "type": "approvalResolved", "fingerprint": fingerprint }).to_string()
}

/// The first `max` characters, on a character boundary.
fn clip(s: &str, max: usize) -> String {
    match s.char_indices().nth(max) {
        Some((i, _)) => s[..i].to_string(),
        None => s.to_string(),
    }
}

/// The Mac's `ApprovalRelay.fingerprint`: lowercase hex SHA-256 of the fields
/// joined by U+001F. `input_key` is the island's request id, so two requests for
/// the same command still have different fingerprints and a late decision for the
/// first can never answer the second.
pub fn fingerprint(pill_id: &str, session_id: &str, tool: &str, command: &str, input_key: &str) -> String {
    let joined = [pill_id, session_id, tool, command, input_key].join("\u{1f}");
    hex(digest::digest(&digest::SHA256, joined.as_bytes()).as_ref())
}

pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Constant-time equality, so a token or fingerprint cannot be guessed byte by byte.
pub fn constant_eq(a: &[u8], b: &[u8]) -> bool {
    // The length is not secret (tokens and fingerprints have a fixed size).
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex as StdMutex;

    #[derive(Default)]
    struct Recorder(StdMutex<Vec<(String, bool)>>);
    impl Host for Recorder {
        fn decide(&self, request_id: &str, allow: bool) {
            self.0.lock().unwrap().push((request_id.to_string(), allow));
        }
    }

    fn hub() -> (Arc<Hub>, Arc<Recorder>) {
        let rec = Arc::new(Recorder::default());
        (Hub::new(rec.clone()), rec)
    }

    fn approval(request: &str, command: &str) -> ApprovalIn {
        ApprovalIn {
            request_id: request.into(),
            session_id: "s1".into(),
            pill_id: "integration_claude".into(),
            tool: "Bash".into(),
            command: command.into(),
        }
    }

    fn session(state: &str) -> SessionIn {
        SessionIn { pill_id: "integration_claude".into(), agent: "Claude Code".into(), state: state.into(), ..Default::default() }
    }

    fn drain(rx: &mut mpsc::Receiver<Out>) -> Vec<serde_json::Value> {
        let mut v = Vec::new();
        while let Ok(o) = rx.try_recv() {
            if let Out::Line(l) = o {
                v.push(serde_json::from_str(&l).unwrap());
            }
        }
        v
    }

    fn fp_of(hub: &Hub) -> String {
        hub.inner.lock().unwrap().approval.as_ref().unwrap().fingerprint.clone()
    }

    // ── session details (cap `details`) ─────────────────────────────────────────────

    fn rich(pill: &str) -> SessionIn {
        SessionIn {
            pill_id: pill.into(),
            agent: "Claude Code".into(),
            state: "working".into(),
            status_text: "Editing".into(),
            step_index: 1,
            step_count: 3,
            steps: vec!["Read · a.rs".into(), "Edit · a.rs".into()],
            final_line: Some("All done".into()),
            project: Some("/home/me/work/coucou".into()),
            color: Some("#2DD4BF".into()),
        }
    }

    fn first_sessions(rx: &mut mpsc::Receiver<Out>) -> Vec<serde_json::Value> {
        let lines = drain(rx);
        lines[0]["sessions"].as_array().unwrap().clone()
    }

    #[test]
    fn a_phone_without_details_gets_exactly_the_v1_fields() {
        let (hub, _) = hub();
        hub.publish(vec![rich("integration_claude")], None, 1_000);
        let (tx, mut rx) = mpsc::channel(8);
        hub.subscribe(tx, 1_001, false);
        let s = &first_sessions(&mut rx)[0];
        let mut keys: Vec<&str> = s.as_object().unwrap().keys().map(String::as_str).collect();
        keys.sort_unstable();
        assert_eq!(keys, ["agent", "pillId", "state", "statusText", "stepCount", "stepIndex", "updatedAt"]);
    }

    #[test]
    fn a_phone_with_details_gets_them_and_the_other_phone_does_not() {
        let (hub, _) = hub();
        let (tx1, mut plain) = mpsc::channel(8);
        let (tx2, mut rich_rx) = mpsc::channel(8);
        hub.subscribe(tx1, 0, false);
        hub.subscribe(tx2, 0, true);
        drain(&mut plain);
        drain(&mut rich_rx);
        hub.publish(vec![rich("integration_claude")], None, 1_000);
        let p = &first_sessions(&mut plain)[0];
        let r = &first_sessions(&mut rich_rx)[0];
        assert!(p.get("steps").is_none() && p.get("finalLine").is_none() && p.get("project").is_none() && p.get("color").is_none());
        assert_eq!(r["steps"], json!(["Read · a.rs", "Edit · a.rs"]));
        assert_eq!(r["finalLine"], "All done");
        assert_eq!(r["project"], "coucou");
        assert_eq!(r["color"], "#2DD4BF");
        // and the shared fields are the same for both
        assert_eq!(p["statusText"], r["statusText"]);
    }

    #[test]
    fn a_new_phone_with_details_is_brought_up_to_date_with_them() {
        let (hub, _) = hub();
        hub.publish(vec![rich("integration_claude")], None, 1_000);
        let (tx, mut rx) = mpsc::channel(8);
        hub.subscribe(tx, 1_001, true);
        assert_eq!(first_sessions(&mut rx)[0]["project"], "coucou");
    }

    #[test]
    fn a_change_in_details_alone_is_sent() {
        let (hub, _) = hub();
        let (tx, mut rx) = mpsc::channel(8);
        hub.subscribe(tx, 0, true);
        drain(&mut rx);
        hub.publish(vec![rich("a")], None, 1_000);
        assert_eq!(drain(&mut rx).len(), 1);
        hub.publish(vec![rich("a")], None, 2_000);
        assert!(drain(&mut rx).is_empty(), "same picture: nothing sent");
        let mut changed = rich("a");
        changed.final_line = Some("Another".into());
        hub.publish(vec![changed], None, 3_000);
        assert_eq!(drain(&mut rx).len(), 1);
    }

    #[test]
    fn only_the_last_twenty_steps_are_kept_each_cut_short_and_empties_dropped() {
        let mut s = rich("a");
        s.steps = (0..30).map(|i| format!("step {i}")).chain([String::new(), "   ".into(), "x".repeat(500)]).collect();
        let steps = clean_steps(&s.steps);
        assert_eq!(steps.len(), MAX_STEPS);
        assert_eq!(steps.last().unwrap().chars().count(), MAX_TEXT_CHARS);
        assert_eq!(steps[0], "step 11");
        assert!(clean_steps(&[]).is_empty());
    }

    #[test]
    fn the_project_is_a_folder_name_never_a_path() {
        assert_eq!(project_name("/home/me/work/coucou").as_deref(), Some("coucou"));
        assert_eq!(project_name("/home/me/work/coucou/").as_deref(), Some("coucou"));
        assert_eq!(project_name("C:\\Users\\me\\proj").as_deref(), Some("proj"));
        assert_eq!(project_name("C:\\Users\\me\\proj\\").as_deref(), Some("proj"));
        assert_eq!(project_name("proj").as_deref(), Some("proj"));
        assert_eq!(project_name("/a/b\tc\n").as_deref(), Some("bc"));
        for none in ["", "/", "\\", "..", "/a/..", ".", "   "] {
            assert_eq!(project_name(none), None, "{none:?}");
        }
        let long = project_name(&format!("/x/{}", "é".repeat(200))).unwrap();
        assert_eq!(long.chars().count(), MAX_PROJECT_CHARS);
        for p in ["/home/me/secret/app", "C:\\Users\\me\\app", "relative/dir/app"] {
            let name = project_name(p).unwrap();
            assert!(!name.contains('/') && !name.contains('\\') && name == "app");
        }
    }

    #[test]
    fn only_a_six_digit_hex_colour_is_passed_on() {
        for ok in ["#8AB4F8", "#000000", "#abcdef"] {
            assert!(is_color(ok), "{ok}");
        }
        for bad in ["", "red", "8AB4F8", "#12", "#GGGGGG", "#12345678", "#8AB4F8;", "url(x)"] {
            assert!(!is_color(bad), "{bad}");
        }
        let (hub, _) = hub();
        let mut s = rich("a");
        s.color = Some("red".into());
        hub.publish(vec![s], None, 1_000);
        let (tx, mut rx) = mpsc::channel(8);
        hub.subscribe(tx, 1_001, true);
        assert!(first_sessions(&mut rx)[0].get("color").is_none());
    }

    #[test]
    fn sixteen_sessions_of_full_steps_still_fit_in_one_line() {
        let big = |i: usize| SessionIn {
            steps: (0..30).map(|n| format!("{n}: {}", "é".repeat(300))).collect(),
            final_line: Some("f".repeat(400)),
            ..rich(&format!("agent_{i}"))
        };
        let sessions: Vec<SessionIn> = (0..20).map(big).collect();
        let (hub, _) = hub();
        hub.publish(sessions, None, 1_000);
        let (tx, mut rx) = mpsc::channel(8);
        hub.subscribe(tx, 1_001, true);
        let Out::Line(line) = rx.try_recv().unwrap() else { panic!() };
        assert!(line.len() <= DETAILS_LINE_BUDGET, "{} bytes", line.len());
        assert!(line.len() < 64 * 1024);
        let v: serde_json::Value = serde_json::from_str(&line).unwrap();
        let list = v["sessions"].as_array().unwrap();
        assert_eq!(list.len(), MAX_SESSIONS);
        // the newest steps are the ones kept
        let steps = list[0]["steps"].as_array().unwrap();
        assert!(!steps.is_empty() && steps.len() < MAX_STEPS);
        assert!(steps.last().unwrap().as_str().unwrap().starts_with("29: "));
        // and the short fields are all still there
        assert_eq!(list[15]["project"], "coucou");
    }

    #[test]
    fn the_fingerprint_is_the_macs_derivation() {
        // Computed independently of this code:
        //   printf 'integration_claude\x1fs1\x1fBash\x1fnpm test\x1fr1' | sha256sum
        assert_eq!(
            fingerprint("integration_claude", "s1", "Bash", "npm test", "r1"),
            "fa8a3bd8b520efb235b0d275403779ce580d2acbb934f4874f222f434b639c54"
        );
    }

    #[test]
    fn a_matching_decision_is_applied_once() {
        let (hub, rec) = hub();
        hub.publish(vec![], Some(approval("r1", "npm test")), 1_000);
        let fp = fp_of(&hub);
        assert_eq!(hub.decide(&fp, "allow", 2_000), Outcome::Applied);
        assert_eq!(*rec.0.lock().unwrap(), vec![("r1".to_string(), true)]);
        // The second answer, from the same or another phone, finds nothing pending.
        assert_eq!(hub.decide(&fp, "deny", 2_001), Outcome::NoMatch);
        assert_eq!(rec.0.lock().unwrap().len(), 1);
    }

    #[test]
    fn a_fingerprint_that_does_not_match_changes_nothing() {
        let (hub, rec) = hub();
        hub.publish(vec![], Some(approval("r1", "npm test")), 1_000);
        // The same command asked again later is another request: its fingerprint differs.
        let other = fingerprint("integration_claude", "s1", "Bash", "npm test", "r2");
        assert_eq!(hub.decide(&other, "allow", 1_500), Outcome::NoMatch);
        assert_eq!(hub.decide("", "allow", 1_500), Outcome::NoMatch);
        assert_eq!(hub.decide(&"0".repeat(64), "allow", 1_500), Outcome::NoMatch);
        assert!(rec.0.lock().unwrap().is_empty());
        // The real one is still pending afterwards.
        assert_eq!(hub.decide(&fp_of(&hub), "deny", 1_600), Outcome::Applied);
    }

    #[test]
    fn a_decision_for_a_replaced_request_is_ignored() {
        let (hub, rec) = hub();
        hub.publish(vec![], Some(approval("r1", "rm -rf build")), 1_000);
        let first = fp_of(&hub);
        hub.publish(vec![], Some(approval("r2", "ls")), 2_000);
        assert_eq!(hub.decide(&first, "allow", 2_100), Outcome::NoMatch);
        assert!(rec.0.lock().unwrap().is_empty());
    }

    #[test]
    fn a_decision_after_the_island_answered_is_ignored() {
        let (hub, rec) = hub();
        hub.publish(vec![], Some(approval("r1", "npm test")), 1_000);
        let fp = fp_of(&hub);
        // Answered on the desktop: the island publishes no request any more.
        hub.publish(vec![], None, 3_000);
        assert_eq!(hub.decide(&fp, "allow", 3_500), Outcome::NoMatch);
        assert!(rec.0.lock().unwrap().is_empty());
    }

    #[test]
    fn a_late_decision_is_ignored_and_the_request_dismissed() {
        let (hub, rec) = hub();
        hub.publish(vec![], Some(approval("r1", "npm test")), 1_000);
        let fp = fp_of(&hub);
        assert_eq!(hub.decide(&fp, "allow", 1_000 + APPROVAL_TTL_MS + 1), Outcome::Late);
        assert!(rec.0.lock().unwrap().is_empty());
        assert_eq!(hub.decide(&fp, "allow", 1_000 + APPROVAL_TTL_MS + 2), Outcome::NoMatch);
    }

    #[test]
    fn exactly_at_the_limit_is_still_in_time() {
        let (hub, rec) = hub();
        hub.publish(vec![], Some(approval("r1", "npm test")), 1_000);
        assert_eq!(hub.decide(&fp_of(&hub), "allow", 1_000 + APPROVAL_TTL_MS), Outcome::Applied);
        assert_eq!(rec.0.lock().unwrap().len(), 1);
    }

    #[test]
    fn only_allow_and_deny_exist() {
        let (hub, rec) = hub();
        hub.publish(vec![], Some(approval("r1", "npm test")), 1_000);
        let fp = fp_of(&hub);
        for odd in ["always", "ALLOW", "", "allow ", "yes"] {
            assert_eq!(hub.decide(&fp, odd, 1_100), Outcome::Invalid, "{odd:?}");
        }
        assert!(rec.0.lock().unwrap().is_empty());
        // Still pending after the refused attempts.
        assert_eq!(hub.decide(&fp, "deny", 1_200), Outcome::Applied);
        assert_eq!(rec.0.lock().unwrap()[0], ("r1".to_string(), false));
    }

    #[test]
    fn phones_are_told_what_changed_and_only_that() {
        let (hub, _) = hub();
        let (tx, mut rx) = mpsc::channel(16);
        hub.publish(vec![session("working")], None, 1_000);
        hub.subscribe(tx, 1_001, false);
        let first = drain(&mut rx);
        assert_eq!(first.len(), 1);
        assert_eq!(first[0]["type"], "sessions");
        assert_eq!(first[0]["sessions"][0]["updatedAt"], 1_000);

        // Nothing changed: nothing sent, and `updatedAt` stays.
        hub.publish(vec![session("working")], None, 5_000);
        assert!(drain(&mut rx).is_empty());

        hub.publish(vec![session("finished")], None, 6_000);
        let next = drain(&mut rx);
        assert_eq!(next[0]["sessions"][0]["state"], "finished");
        assert_eq!(next[0]["sessions"][0]["updatedAt"], 6_000);

        hub.publish(vec![session("finished")], Some(approval("r1", "ls")), 7_000);
        let a = drain(&mut rx);
        assert_eq!(a.len(), 1);
        assert_eq!(a[0]["type"], "approval");
        assert_eq!(a[0]["command"], "ls");
        assert_eq!(a[0]["createdAt"], 7_000);
        let fp = a[0]["fingerprint"].as_str().unwrap().to_string();

        hub.publish(vec![session("finished")], None, 8_000);
        let gone = drain(&mut rx);
        assert_eq!(gone[0]["type"], "approvalResolved");
        assert_eq!(gone[0]["fingerprint"], fp);
    }

    #[test]
    fn a_phone_that_connects_late_still_sees_the_pending_request() {
        let (hub, _) = hub();
        hub.publish(vec![session("approval")], Some(approval("r1", "ls")), 1_000);
        let (tx, mut rx) = mpsc::channel(16);
        hub.subscribe(tx, 2_000, false);
        let lines = drain(&mut rx);
        assert_eq!(lines.iter().map(|l| l["type"].as_str().unwrap()).collect::<Vec<_>>(), ["sessions", "approval"]);
        // A request already past its time is not offered.
        let (tx, mut rx) = mpsc::channel(16);
        hub.subscribe(tx, 1_000 + APPROVAL_TTL_MS + 1, false);
        assert_eq!(drain(&mut rx).iter().filter(|l| l["type"] == "approval").count(), 0);
    }

    #[test]
    fn what_the_island_sends_is_bounded_and_cleaned() {
        let (hub, _) = hub();
        let (tx, mut rx) = mpsc::channel(16);
        hub.subscribe(tx, 0, false);
        drain(&mut rx);
        let many: Vec<SessionIn> = (0..40)
            .map(|i| SessionIn { pill_id: format!("agent_{i}"), state: "not-a-state".into(), ..Default::default() })
            .chain([SessionIn::default()]) // no pill id: dropped
            .collect();
        hub.publish(many, Some(approval("r1", &"é".repeat(5_000))), 1_000);
        let lines = drain(&mut rx);
        assert_eq!(lines[0]["sessions"].as_array().unwrap().len(), MAX_SESSIONS);
        assert_eq!(lines[0]["sessions"][0]["state"], "idle");
        assert_eq!(lines[1]["command"].as_str().unwrap().chars().count(), MAX_COMMAND_CHARS);
    }

    #[test]
    fn a_phone_that_cannot_keep_up_is_dropped_not_waited_for() {
        let (hub, _) = hub();
        let (tx, _rx) = mpsc::channel(2); // never read
        hub.subscribe(tx, 0, false);
        assert_eq!(hub.clients(), 1);
        for i in 0..5 {
            hub.publish(vec![session("working")], None, 1_000);
            hub.publish(vec![SessionIn { status_text: format!("{i}"), ..session("working") }], None, 1_000);
        }
        assert_eq!(hub.clients(), 0);
    }

    #[test]
    fn a_phone_that_was_dropped_is_marked_so_its_connection_can_close() {
        let (hub, _) = hub();
        let (tx, _rx) = mpsc::channel(1); // never read
        let (_, evicted) = hub.subscribe(tx, 0, false);
        assert!(!evicted.load(Ordering::SeqCst));
        for i in 0..4 {
            hub.publish(vec![SessionIn { status_text: format!("{i}"), ..session("working") }], None, 1_000);
        }
        assert!(evicted.load(Ordering::SeqCst), "a dropped phone must be told apart from a healthy one");
        // A healthy phone is never marked.
        let (tx, mut rx) = mpsc::channel(16);
        let (_, ok) = hub.subscribe(tx, 0, false);
        hub.publish(vec![session("finished")], None, 2_000);
        drain(&mut rx);
        assert!(!ok.load(Ordering::SeqCst));
    }

    #[test]
    fn kicking_tells_every_phone_why() {
        let (hub, _) = hub();
        let (tx, mut rx) = mpsc::channel(4);
        hub.subscribe(tx, 0, false);
        hub.kick_all("auth", "unpaired");
        assert_eq!(hub.clients(), 0);
        let mut saw = false;
        while let Ok(o) = rx.try_recv() {
            if let Out::Close { code, .. } = o {
                saw = code == "auth";
            }
        }
        assert!(saw);
    }

    #[test]
    fn constant_time_comparison_still_compares() {
        assert!(constant_eq(b"abc", b"abc"));
        assert!(!constant_eq(b"abc", b"abd"));
        assert!(!constant_eq(b"abc", b"abcd"));
        assert!(!constant_eq(b"", b"a"));
    }

    // ── questions (cap `answers`) ───────────────────────────────────────────────────

    #[derive(Default)]
    struct AnswerRecorder(StdMutex<Vec<(String, serde_json::Value)>>);
    impl Host for AnswerRecorder {
        fn decide(&self, _: &str, _: bool) {}
        fn answer(&self, request_id: &str, answers: &serde_json::Map<String, serde_json::Value>) {
            self.0.lock().unwrap().push((request_id.to_string(), serde_json::Value::Object(answers.clone())));
        }
    }

    fn ask(request: &str) -> QuestionIn {
        QuestionIn {
            request_id: request.into(),
            session_id: "s1".into(),
            pill_id: "integration_claude".into(),
            questions: vec![QuestionItemIn {
                question: "Which?".into(),
                options: vec![OptionIn { label: "A".into(), description: String::new() }, OptionIn { label: "B".into(), description: "bee".into() }],
                multi_select: false,
            }],
        }
    }

    fn qhub() -> (Arc<Hub>, Arc<AnswerRecorder>, mpsc::Receiver<Out>) {
        let rec = Arc::new(AnswerRecorder::default());
        let hub = Hub::new(rec.clone());
        let (tx, rx) = mpsc::channel(16);
        hub.subscribe_with(tx, 1_000, false, true, false);
        (hub, rec, rx)
    }

    fn qfp(hub: &Hub) -> String {
        hub.inner.lock().unwrap().question.as_ref().unwrap().fingerprint.clone()
    }

    #[test]
    fn an_answer_older_than_the_limit_is_not_applied() {
        let (hub, rec, mut rx) = qhub();
        hub.publish_question(Some(ask("r1")), 1_000);
        let fp = qfp(&hub);
        assert_eq!(hub.answer(&fp, &[vec!["A".into()]], 1_000 + APPROVAL_TTL_MS + 1), Outcome::Late);
        assert!(rec.0.lock().unwrap().is_empty());
        let lines = drain(&mut rx);
        assert_eq!(lines.last().unwrap()["type"], "approvalResolved");
    }

    #[test]
    fn a_new_question_replaces_the_old_and_none_withdraws_it() {
        let (hub, rec, mut rx) = qhub();
        hub.publish_question(Some(ask("r1")), 1_000);
        let first = qfp(&hub);
        hub.publish_question(Some(ask("r1")), 1_001); // the same request again: nothing new
        hub.publish_question(Some(ask("r2")), 1_002);
        let second = qfp(&hub);
        assert_ne!(first, second);
        assert_eq!(hub.answer(&first, &[vec!["A".into()]], 1_003), Outcome::NoMatch, "the old one cannot be answered any more");
        hub.publish_question(None, 1_004);
        assert_eq!(hub.answer(&second, &[vec!["A".into()]], 1_005), Outcome::NoMatch);
        assert!(rec.0.lock().unwrap().is_empty());
        let types: Vec<String> = drain(&mut rx).iter().map(|l| l["type"].as_str().unwrap().to_string()).collect();
        assert_eq!(types.iter().filter(|t| *t == "question").count(), 2);
        assert_eq!(types.iter().filter(|t| *t == "approvalResolved").count(), 2);
    }

    #[test]
    fn the_question_and_a_permission_request_do_not_disturb_each_other() {
        let (hub, rec, _rx) = qhub();
        hub.publish(vec![], Some(approval("p1", "ls")), 1_000);
        hub.publish_question(Some(ask("r1")), 1_000);
        assert!(hub.inner.lock().unwrap().approval.is_some());
        let fp = qfp(&hub);
        assert_eq!(hub.answer(&fp, &[vec!["B".into()]], 1_001), Outcome::Applied);
        assert!(hub.inner.lock().unwrap().approval.is_some(), "the permission request is untouched");
        assert_eq!(rec.0.lock().unwrap()[0], ("r1".to_string(), json!({ "Which?": "B" })));
    }

    #[test]
    fn a_question_that_waited_too_long_is_gone_for_a_phone_that_connects_now() {
        let (hub, _rec, _rx) = qhub();
        hub.publish_question(Some(ask("r1")), 1_000);
        let (tx, mut rx) = mpsc::channel(16);
        hub.subscribe_with(tx, 1_000 + APPROVAL_TTL_MS + 1, false, true, false);
        assert!(drain(&mut rx).iter().all(|l| l["type"] != "question"));
    }
    // ── prefs (the outfit) ──────────────────────────────────────────────────────────

    #[test]
    fn a_phone_with_prefs_hears_the_outfit_when_it_connects_and_whenever_it_changes() {
        let (hub, _) = hub();
        hub.publish_outfit("beanie");
        let (tx, mut rx) = mpsc::channel(16);
        hub.subscribe_with(tx, 1_000, false, false, true);
        let first = drain(&mut rx);
        assert!(first.iter().any(|l| l["type"] == "prefs" && l["outfit"] == "beanie"), "{first:?}");
        hub.publish_outfit("crown");
        let next = drain(&mut rx);
        assert_eq!(next.len(), 1);
        assert_eq!(next[0], serde_json::json!({ "type": "prefs", "outfit": "crown" }));
        hub.publish_outfit("crown"); // the same again: nothing new
        assert!(drain(&mut rx).is_empty());
    }

    #[test]
    fn a_phone_without_prefs_never_hears_of_the_outfit() {
        let (hub, _) = hub();
        hub.publish_outfit("scarf");
        let (tx, mut rx) = mpsc::channel(16);
        hub.subscribe_with(tx, 1_000, true, true, false);
        hub.publish_outfit("bow");
        assert!(drain(&mut rx).iter().all(|l| l["type"] != "prefs"));
    }

    #[test]
    fn only_the_known_wardrobe_values_are_sent() {
        let (hub, _) = hub();
        let (tx, mut rx) = mpsc::channel(16);
        hub.subscribe_with(tx, 1_000, false, false, true);
        drain(&mut rx);
        for bad in ["", "topHat", "Beanie", "beanie ", "../../etc", "auto\nauto"] {
            hub.publish_outfit(bad);
        }
        assert!(drain(&mut rx).is_empty());
        for ok in OUTFIT_CHOICES {
            hub.publish_outfit(ok);
        }
        assert_eq!(drain(&mut rx).len(), OUTFIT_CHOICES.len());
    }

    #[test]
    fn the_outfit_list_is_the_one_of_the_island() {
        let ts = include_str!("../../../src/mochi/wardrobe.ts");
        let body = ts.split("export const OUTFIT_SELECTIONS = [").nth(1).unwrap().split("] as const").next().unwrap();
        let ids: Vec<&str> = body.split('"').skip(1).step_by(2).collect();
        assert_eq!(ids, OUTFIT_CHOICES.to_vec());
    }
}
