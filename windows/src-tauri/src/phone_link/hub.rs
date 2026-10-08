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

/// The states the phone draws; anything else is sent as `idle`.
const STATES: &[&str] = &[
    "idle", "working", "thinking", "searching", "approval", "question", "error", "finished",
    "ratelimit", "sleeping", "dizzy",
];

/// How a decision gets back to the agent. The app's implementation answers the
/// waiting `coucou-hook` and closes the card on the island.
pub trait Host: Send + Sync {
    fn decide(&self, request_id: &str, allow: bool);
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
}

#[derive(Default)]
struct Inner {
    sessions: Vec<Session>,
    approval: Option<Approval>,
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
            let line = sessions_line(&inner.sessions);
            broadcast(&mut inner, &line);
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
    pub fn subscribe(&self, tx: mpsc::Sender<Out>, now: u64) -> u64 {
        let mut inner = self.inner.lock().unwrap();
        expire(&mut inner, now);
        let id = inner.next_sub;
        inner.next_sub += 1;
        let _ = tx.try_send(Out::Line(sessions_line(&inner.sessions).into()));
        if let Some(a) = &inner.approval {
            let _ = tx.try_send(Out::Line(approval_line(a).into()));
        }
        inner.subs.push(Sub { id, tx });
        id
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

fn expire(inner: &mut Inner, now: u64) {
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
    inner.subs.retain(|s| s.tx.try_send(Out::Line(shared.clone())).is_ok());
}

fn sessions_line(sessions: &[Session]) -> String {
    json!({ "type": "sessions", "sessions": sessions }).to_string()
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
        hub.subscribe(tx, 1_001);
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
        hub.subscribe(tx, 2_000);
        let lines = drain(&mut rx);
        assert_eq!(lines.iter().map(|l| l["type"].as_str().unwrap()).collect::<Vec<_>>(), ["sessions", "approval"]);
        // A request already past its time is not offered.
        let (tx, mut rx) = mpsc::channel(16);
        hub.subscribe(tx, 1_000 + APPROVAL_TTL_MS + 1);
        assert_eq!(drain(&mut rx).iter().filter(|l| l["type"] == "approval").count(), 0);
    }

    #[test]
    fn what_the_island_sends_is_bounded_and_cleaned() {
        let (hub, _) = hub();
        let (tx, mut rx) = mpsc::channel(16);
        hub.subscribe(tx, 0);
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
        hub.subscribe(tx, 0);
        assert_eq!(hub.clients(), 1);
        for i in 0..5 {
            hub.publish(vec![session("working")], None, 1_000);
            hub.publish(vec![SessionIn { status_text: format!("{i}"), ..session("working") }], None, 1_000);
        }
        assert_eq!(hub.clients(), 0);
    }

    #[test]
    fn kicking_tells_every_phone_why() {
        let (hub, _) = hub();
        let (tx, mut rx) = mpsc::channel(4);
        hub.subscribe(tx, 0);
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
}
