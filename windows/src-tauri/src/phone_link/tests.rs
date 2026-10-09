// The phone link end to end: a real TLS server on loopback and a client that pins
// the certificate the way the Android app does. The Kotlin client is tried
// against this same server in android/.../RustDesktopInteropTest.kt.

use std::future::Future;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use ring::digest;
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader, ReadHalf, WriteHalf};
use tokio::net::TcpStream;
use tokio_rustls::client::TlsStream;
use tokio_rustls::rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use tokio_rustls::rustls::crypto::ring as provider;
use tokio_rustls::rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use tokio_rustls::rustls::{ClientConfig, DigitallySignedStruct, Error, SignatureScheme};
use tokio_rustls::TlsConnector;

use super::hub::{hex, ApprovalIn, Host, Hub, SessionIn};
use super::pairing::{self, tests::MemStore};
use super::server::{self, MAX_LINE};

const TOKEN: &str = "test-token-0123456789abcdef";

fn block_on<F: Future>(f: F) -> F::Output {
    tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(f)
}

#[derive(Default)]
struct Recorder(Mutex<Vec<(String, bool)>>);
impl Host for Recorder {
    fn decide(&self, request_id: &str, allow: bool) {
        self.0.lock().unwrap().push((request_id.to_string(), allow));
    }
}

struct Rig {
    port: u16,
    fingerprint: String,
    hub: Arc<Hub>,
    host: Arc<Recorder>,
    shared: Arc<server::Shared>,
    handle: Option<server::Handle>,
}

impl Rig {
    async fn start() -> Rig {
        Rig::start_with(None).await
    }

    async fn start_with(chat: Option<Arc<super::chat::ChatLink>>) -> Rig {
        let identity = pairing::load_or_create_identity(&MemStore::default()).unwrap();
        let host = Arc::new(Recorder::default());
        let hub = Hub::new(host.clone());
        let shared = server::Shared::with_chat(hub.clone(), TOKEN.to_string(), "Test PC".to_string(), chat);
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = server::serve(listener, server::tls_acceptor(&identity).unwrap(), shared.clone());
        Rig { port, fingerprint: identity.fingerprint, hub, host, shared, handle: Some(handle) }
    }

    fn approval(&self, request: &str, command: &str, age_ms: u64) {
        let now = server::now_ms() - age_ms;
        self.hub.publish(
            vec![],
            Some(ApprovalIn {
                request_id: request.into(),
                session_id: "s1".into(),
                pill_id: "integration_claude".into(),
                tool: "Bash".into(),
                command: command.into(),
            }),
            now,
        );
    }

    fn decisions(&self) -> Vec<(String, bool)> {
        self.host.0.lock().unwrap().clone()
    }
}

impl Drop for Rig {
    fn drop(&mut self) {
        if let Some(h) = self.handle.take() {
            h.stop();
        }
    }
}

/// Accepts exactly one certificate, by its SHA-256 — the Android client's PinnedTrustManager.
#[derive(Debug)]
struct Pin(String);

impl ServerCertVerifier for Pin {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, Error> {
        if hex(digest::digest(&digest::SHA256, end_entity).as_ref()) == self.0 {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(Error::General("certificate does not match the pairing".into()))
        }
    }
    fn verify_tls12_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, Error> {
        tokio_rustls::rustls::crypto::verify_tls12_signature(m, c, d, &provider::default_provider().signature_verification_algorithms)
    }
    fn verify_tls13_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, Error> {
        tokio_rustls::rustls::crypto::verify_tls13_signature(m, c, d, &provider::default_provider().signature_verification_algorithms)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        provider::default_provider().signature_verification_algorithms.supported_schemes()
    }
}

struct Client {
    r: BufReader<ReadHalf<TlsStream<TcpStream>>>,
    w: WriteHalf<TlsStream<TcpStream>>,
}

impl Client {
    async fn connect(port: u16, pin: &str) -> Result<Client, String> {
        let config = ClientConfig::builder_with_provider(Arc::new(provider::default_provider()))
            .with_safe_default_protocol_versions()
            .unwrap()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(Pin(pin.to_string())))
            .with_no_client_auth();
        let tcp = TcpStream::connect(("127.0.0.1", port)).await.map_err(|e| e.to_string())?;
        let tls = TlsConnector::from(Arc::new(config))
            .connect(ServerName::try_from("coucou").unwrap(), tcp)
            .await
            .map_err(|e| e.to_string())?;
        let (r, w) = tokio::io::split(tls);
        Ok(Client { r: BufReader::new(r), w })
    }

    async fn paired(rig: &Rig) -> Client {
        let mut c = Client::connect(rig.port, &rig.fingerprint).await.unwrap();
        c.send(json!({ "type": "hello", "v": 1, "token": TOKEN, "device": "Test phone" })).await;
        assert_eq!(c.recv().await.unwrap()["type"], "welcome");
        c
    }

    async fn send(&mut self, v: Value) {
        self.send_raw(v.to_string().as_bytes()).await;
    }

    async fn send_raw(&mut self, bytes: &[u8]) {
        self.send_unterminated(bytes).await;
        let _ = self.w.write_all(b"\n").await;
        let _ = self.w.flush().await;
    }

    /// Bytes with no end of line, as a misbehaving peer would send them.
    async fn send_unterminated(&mut self, bytes: &[u8]) {
        let _ = self.w.write_all(bytes).await;
        let _ = self.w.flush().await;
    }

    /// The next message, or None if the server closed (or said nothing for 3 s).
    async fn recv(&mut self) -> Option<Value> {
        let mut line = String::new();
        match tokio::time::timeout(Duration::from_secs(3), self.r.read_line(&mut line)).await {
            Ok(Ok(n)) if n > 0 => Some(serde_json::from_str(&line).unwrap()),
            _ => None,
        }
    }

    /// The next message of this type, skipping others.
    async fn expect(&mut self, kind: &str) -> Value {
        for _ in 0..10 {
            let m = self.recv().await.unwrap_or_else(|| panic!("closed while waiting for {kind}"));
            if m["type"] == kind {
                return m;
            }
        }
        panic!("no {kind} message");
    }

    /// True once the server has really closed this connection (an end of stream,
    /// not just a quiet one).
    async fn closed(&mut self) -> bool {
        loop {
            let mut line = String::new();
            match tokio::time::timeout(Duration::from_secs(3), self.r.read_line(&mut line)).await {
                Ok(Ok(0)) | Ok(Err(_)) => return true,
                Ok(Ok(_)) => continue, // a last word before closing
                Err(_) => return false,
            }
        }
    }
}

#[test]
fn a_paired_phone_gets_sessions_and_answers_the_request() {
    block_on(async {
        let rig = Rig::start().await;
        rig.hub.publish(
            vec![SessionIn { pill_id: "integration_claude".into(), agent: "Claude Code".into(), state: "working".into(), status_text: "Editing".into(), step_index: 2, step_count: 6 }],
            None,
            server::now_ms(),
        );
        let mut c = Client::paired(&rig).await;
        let sessions = c.expect("sessions").await;
        assert_eq!(sessions["sessions"][0]["agent"], "Claude Code");
        assert_eq!(sessions["sessions"][0]["state"], "working");

        rig.approval("req-1", "npm run build", 0);
        let approval = c.expect("approval").await;
        assert_eq!(approval["pillId"], "integration_claude");
        assert_eq!(approval["tool"], "Bash");
        assert_eq!(approval["command"], "npm run build");
        let fp = approval["fingerprint"].as_str().unwrap().to_string();
        assert_eq!(fp, super::hub::fingerprint("integration_claude", "s1", "Bash", "npm run build", "req-1"));

        c.send(json!({ "type": "decision", "fingerprint": fp, "decision": "allow" })).await;
        let resolved = c.expect("approvalResolved").await;
        assert_eq!(resolved["fingerprint"], fp);
        assert_eq!(rig.decisions(), vec![("req-1".to_string(), true)]);

        c.send(json!({ "type": "ping" })).await;
        c.expect("pong").await;
        c.send(json!({ "type": "bye" })).await;
        assert!(c.closed().await);
    });
}

#[test]
fn a_wrong_token_is_refused_and_gets_nothing() {
    block_on(async {
        let rig = Rig::start().await;
        rig.approval("req-1", "rm -rf /", 0);
        let mut c = Client::connect(rig.port, &rig.fingerprint).await.unwrap();
        c.send(json!({ "type": "hello", "v": 1, "token": "x".repeat(TOKEN.len()), "device": "Intruder" })).await;
        let first = c.recv().await.unwrap();
        assert_eq!(first["type"], "error");
        assert_eq!(first["code"], "auth");
        assert!(c.closed().await);
        assert_eq!(rig.hub.clients(), 0);
        assert!(rig.decisions().is_empty());
    });
}

#[test]
fn a_token_of_another_length_or_a_missing_one_is_refused_too() {
    block_on(async {
        let rig = Rig::start().await;
        for hello in [
            json!({ "type": "hello", "v": 1, "token": "short", "device": "x" }),
            json!({ "type": "hello", "v": 1, "device": "x" }),
            json!({ "type": "hello", "v": 1, "token": 12345, "device": "x" }),
            json!({ "type": "hello", "v": 1, "token": format!("{TOKEN}extra"), "device": "x" }),
            // Right token, other protocol version: the phone would refuse the desktop anyway.
            json!({ "type": "hello", "v": 2, "token": TOKEN, "device": "x" }),
            json!({ "type": "hello", "token": TOKEN, "device": "x" }),
        ] {
            let mut c = Client::connect(rig.port, &rig.fingerprint).await.unwrap();
            c.send(hello.clone()).await;
            assert_eq!(c.recv().await.unwrap()["code"], "auth", "{hello}");
        }
        assert_eq!(rig.hub.clients(), 0);
    });
}

#[test]
fn a_phone_that_does_not_say_hello_first_gets_nowhere() {
    block_on(async {
        let rig = Rig::start().await;
        rig.approval("req-1", "ls", 0);
        let fp = super::hub::fingerprint("integration_claude", "s1", "Bash", "ls", "req-1");
        let mut c = Client::connect(rig.port, &rig.fingerprint).await.unwrap();
        c.send(json!({ "type": "decision", "fingerprint": fp, "decision": "allow" })).await;
        assert!(c.closed().await);
        assert!(rig.decisions().is_empty(), "a decision before hello must never be applied");

        let mut c = Client::connect(rig.port, &rig.fingerprint).await.unwrap();
        c.send_raw(b"this is not json").await;
        assert!(c.closed().await);
    });
}

#[test]
fn a_decision_for_another_request_changes_nothing() {
    block_on(async {
        let rig = Rig::start().await;
        rig.approval("req-2", "npm test", 0);
        let mut c = Client::paired(&rig).await;
        let real = c.expect("approval").await["fingerprint"].as_str().unwrap().to_string();

        // The same command, but the fingerprint of an earlier request.
        let stale = super::hub::fingerprint("integration_claude", "s1", "Bash", "npm test", "req-1");
        for odd in [stale.as_str(), "", "not-a-fingerprint", &real.to_uppercase()] {
            c.send(json!({ "type": "decision", "fingerprint": odd, "decision": "allow" })).await;
        }
        c.send(json!({ "type": "decision", "fingerprint": real, "decision": "always" })).await;
        c.send(json!({ "type": "decision", "fingerprint": real })).await;
        c.send(json!({ "type": "decision", "decision": "allow" })).await;
        // A round trip proves the server has read all of the above.
        c.send(json!({ "type": "ping" })).await;
        c.expect("pong").await;
        assert!(rig.decisions().is_empty());

        // And the real request is still waiting for the real answer.
        c.send(json!({ "type": "decision", "fingerprint": real, "decision": "deny" })).await;
        c.expect("approvalResolved").await;
        assert_eq!(rig.decisions(), vec![("req-2".to_string(), false)]);
    });
}

#[test]
fn a_decision_that_comes_too_late_is_ignored() {
    block_on(async {
        let rig = Rig::start().await;
        // The request has been waiting for two minutes: the desktop dismissed it long ago.
        rig.approval("req-1", "npm test", 120_000);
        let mut c = Client::paired(&rig).await;
        // It is not even offered to a phone that connects now.
        c.send(json!({ "type": "ping" })).await;
        let mut offered = false;
        loop {
            let m = c.recv().await.unwrap();
            offered |= m["type"] == "approval";
            if m["type"] == "pong" {
                break;
            }
        }
        assert!(!offered, "an expired request must not be offered");

        // A phone that still holds it from before answers anyway.
        let fp = super::hub::fingerprint("integration_claude", "s1", "Bash", "npm test", "req-1");
        c.send(json!({ "type": "decision", "fingerprint": fp, "decision": "allow" })).await;
        c.send(json!({ "type": "ping" })).await;
        c.expect("pong").await;
        assert!(rig.decisions().is_empty());
    });
}

#[test]
fn a_decision_after_the_desktop_answered_is_ignored() {
    block_on(async {
        let rig = Rig::start().await;
        rig.approval("req-1", "npm test", 0);
        let mut c = Client::paired(&rig).await;
        let fp = c.expect("approval").await["fingerprint"].as_str().unwrap().to_string();

        // Allowed at the desk: the island publishes no pending request any more.
        rig.hub.publish(vec![], None, server::now_ms());
        assert_eq!(c.expect("approvalResolved").await["fingerprint"], fp);

        c.send(json!({ "type": "decision", "fingerprint": fp, "decision": "allow" })).await;
        c.send(json!({ "type": "ping" })).await;
        c.expect("pong").await;
        assert!(rig.decisions().is_empty());
    });
}

#[test]
fn a_line_over_64_kib_closes_the_connection() {
    block_on(async {
        let rig = Rig::start().await;

        // Before hello.
        let mut c = Client::connect(rig.port, &rig.fingerprint).await.unwrap();
        c.send_raw(&vec![b'a'; MAX_LINE + 10]).await;
        assert!(c.closed().await);

        // After hello.
        let mut c = Client::paired(&rig).await;
        c.send_raw(&vec![b'a'; MAX_LINE + 10]).await;
        assert!(c.closed().await);

        // A flood with no newline at all is cut at the limit, not buffered for ever.
        let mut c = Client::paired(&rig).await;
        for _ in 0..8 {
            c.send_unterminated(&vec![b'b'; 32 * 1024]).await;
        }
        assert!(c.closed().await);
        assert_eq!(rig.hub.clients(), 0);

        // The server is fine afterwards.
        let mut c = Client::paired(&rig).await;
        c.send(json!({ "type": "ping" })).await;
        c.expect("pong").await;
    });
}

#[test]
fn a_line_of_exactly_64_kib_is_accepted() {
    block_on(async {
        let rig = Rig::start().await;
        let mut c = Client::paired(&rig).await;
        let head = r#"{"type":"ping","pad":""#;
        let tail = r#""}"#;
        let pad = "p".repeat(MAX_LINE - head.len() - tail.len());
        let line = format!("{head}{pad}{tail}");
        assert_eq!(line.len(), MAX_LINE);
        c.send_raw(line.as_bytes()).await;
        c.expect("pong").await;
    });
}

#[test]
fn a_phone_with_another_certificate_in_mind_does_not_connect() {
    block_on(async {
        let rig = Rig::start().await;
        let err = Client::connect(rig.port, &"0".repeat(64)).await.err().expect("the pin must fail");
        assert!(err.contains("does not match"), "{err}");
        assert_eq!(rig.hub.clients(), 0);
    });
}

#[test]
fn plain_text_is_not_spoken_here() {
    block_on(async {
        let rig = Rig::start().await;
        let mut tcp = TcpStream::connect(("127.0.0.1", rig.port)).await.unwrap();
        tcp.write_all(b"{\"type\":\"hello\",\"v\":1,\"token\":\"test-token-0123456789abcdef\"}\n").await.unwrap();
        let mut buf = Vec::new();
        let mut r = BufReader::new(tcp);
        let _ = tokio::time::timeout(Duration::from_secs(3), r.read_until(b'\n', &mut buf)).await;
        assert!(!String::from_utf8_lossy(&buf).contains("welcome"));
        assert_eq!(rig.hub.clients(), 0);
    });
}

#[test]
fn pairing_again_locks_the_old_phone_out() {
    block_on(async {
        let rig = Rig::start().await;
        let mut old = Client::paired(&rig).await;

        // What phone_link_new_pairing does.
        *rig.shared.token.lock().unwrap() = "a-brand-new-token-0123456789".to_string();
        rig.hub.kick_all("auth", "unpaired");
        let word = old.expect("error").await;
        assert_eq!(word["code"], "auth");
        assert!(old.closed().await);

        let mut again = Client::connect(rig.port, &rig.fingerprint).await.unwrap();
        again.send(json!({ "type": "hello", "v": 1, "token": TOKEN, "device": "old" })).await;
        assert_eq!(again.recv().await.unwrap()["code"], "auth");
        let mut fresh = Client::connect(rig.port, &rig.fingerprint).await.unwrap();
        fresh.send(json!({ "type": "hello", "v": 1, "token": "a-brand-new-token-0123456789", "device": "new" })).await;
        assert_eq!(fresh.recv().await.unwrap()["type"], "welcome");
    });
}

#[test]
fn turning_it_off_closes_the_phones_and_stops_listening() {
    block_on(async {
        let mut rig = Rig::start().await;
        let mut c = Client::paired(&rig).await;
        let port = rig.port;
        rig.handle.take().unwrap().stop();
        assert!(c.closed().await);
        // Nobody answers any more (the listener was dropped with the task).
        tokio::time::sleep(Duration::from_millis(100)).await;
        assert!(Client::connect(port, &rig.fingerprint).await.is_err());
    });
}

#[test]
fn many_connections_cannot_exhaust_the_desktop() {
    block_on(async {
        let rig = Rig::start().await;
        // Idle, un-authenticated connections up to the limit; the next is dropped.
        let mut held = Vec::new();
        for _ in 0..8 {
            held.push(Client::connect(rig.port, &rig.fingerprint).await.unwrap());
        }
        let extra = Client::connect(rig.port, &rig.fingerprint).await;
        if let Ok(mut extra) = extra {
            // The TCP connection may be accepted by the OS but is closed unserved.
            extra.send(json!({ "type": "hello", "v": 1, "token": TOKEN, "device": "late" })).await;
            assert!(extra.recv().await.is_none());
        }
        drop(held);
    });
}


// ── Chat from the phone (CHAT_PLAN.md, C1): the rules, with a fake provider ──────────────────

mod chat_tests {
    use super::*;
    use crate::phone_link::chat::{
        reason, BoxFuture, ChatBackend, ChatConfig, ChatFail, ChatLink, ModelOption, DONE_TEXT_MAX, MAX_SENDS, MAX_TEXT_CHARS,
    };
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
    use tokio::sync::mpsc::UnboundedSender;

    #[derive(Clone)]
    enum Script {
        /// Streams these parts one after the other (the answer so far each time).
        Words(Vec<String>),
        /// Streams `streamed`, then returns `full` (which is not an extension of it).
        Rewrite { streamed: String, full: String },
        Fail(ChatFail),
        /// Never answers (a slow provider).
        Hang,
    }

    struct Fake {
        config: Mutex<ChatConfig>,
        script: Mutex<Script>,
        calls: Mutex<Vec<(String, String)>>,
        resets: AtomicUsize,
        dropped: Arc<AtomicBool>,
    }

    impl Fake {
        fn new(enabled: bool) -> Arc<Fake> {
            let models = vec![
                ModelOption { id: "anthropic/claude-x".into(), provider: "anthropic".into(), label: "Claude X".into() },
                ModelOption { id: "openai/gpt-y".into(), provider: "openai".into(), label: "GPT Y".into() },
            ];
            Arc::new(Fake {
                config: Mutex::new(ChatConfig { enabled, allowed: models }),
                script: Mutex::new(Script::Words(vec!["Hel".into(), "lo".into(), " world".into()])),
                calls: Mutex::new(Vec::new()),
                resets: AtomicUsize::new(0),
                dropped: Arc::new(AtomicBool::new(false)),
            })
        }
        fn script(&self, s: Script) {
            *self.script.lock().unwrap() = s;
        }
        fn enable(&self, on: bool) {
            self.config.lock().unwrap().enabled = on;
        }
    }

    impl ChatBackend for Fake {
        fn config(&self) -> ChatConfig {
            self.config.lock().unwrap().clone()
        }
        fn send(&self, model_id: &str, text: String, deltas: UnboundedSender<String>) -> BoxFuture<Result<String, ChatFail>> {
            self.calls.lock().unwrap().push((model_id.to_string(), text));
            let script = self.script.lock().unwrap().clone();
            let dropped = self.dropped.clone();
            self.dropped.store(false, Ordering::SeqCst);
            Box::pin(async move {
                struct Mark(Arc<AtomicBool>);
                impl Drop for Mark {
                    fn drop(&mut self) {
                        self.0.store(true, Ordering::SeqCst);
                    }
                }
                let _mark = Mark(dropped);
                match script {
                    Script::Words(parts) => {
                        let mut so_far = String::new();
                        for p in parts {
                            so_far.push_str(&p);
                            let _ = deltas.send(so_far.clone());
                            tokio::task::yield_now().await;
                        }
                        Ok(so_far)
                    }
                    Script::Rewrite { streamed, full } => {
                        let _ = deltas.send(streamed);
                        tokio::task::yield_now().await;
                        Ok(full)
                    }
                    Script::Fail(f) => Err(f),
                    Script::Hang => std::future::pending().await,
                }
            })
        }
        fn reset(&self) {
            self.resets.fetch_add(1, Ordering::SeqCst);
        }
    }

    async fn rig(fake: &Arc<Fake>) -> Rig {
        Rig::start_with(Some(ChatLink::new(fake.clone()))).await
    }

    async fn phone(rig: &Rig, caps: Option<Value>) -> (Client, Value) {
        let mut c = Client::connect(rig.port, &rig.fingerprint).await.unwrap();
        let mut hello = json!({ "type": "hello", "v": 1, "token": TOKEN, "device": "Test phone" });
        if let Some(caps) = caps {
            hello["caps"] = caps;
        }
        c.send(hello).await;
        let welcome = c.expect("welcome").await;
        (c, welcome)
    }

    fn send(id: &str, model: &str, text: &str) -> Value {
        json!({ "type": "chatSend", "id": id, "model": model, "text": text })
    }

    /// Everything of one answer until chatDone or chatError.
    async fn answer(c: &mut Client, id: &str) -> (String, Value) {
        let mut text = String::new();
        for _ in 0..200 {
            let m = c.recv().await.expect("the connection closed or went quiet");
            match m["type"].as_str() {
                Some("chatDelta") if m["id"] == id => text.push_str(m["text"].as_str().unwrap()),
                Some("chatDone") | Some("chatError") if m["id"] == id => return (text, m),
                _ => {}
            }
        }
        panic!("no end of answer");
    }

    #[test]
    fn a_phone_that_asks_is_offered_chat_and_gets_only_the_allowed_models() {
        block_on(async {
            let fake = Fake::new(true);
            let rig = rig(&fake).await;
            let (mut c, welcome) = phone(&rig, Some(json!(["chat"]))).await;
            assert_eq!(welcome["caps"], json!(["chat"]));
            c.send(json!({ "type": "chatModels" })).await;
            let m = c.expect("chatModels").await;
            assert_eq!(m["models"][0], json!({ "id": "anthropic/claude-x", "provider": "anthropic", "label": "Claude X" }));
            assert_eq!(m["models"].as_array().unwrap().len(), 2);
            // only ids, providers and labels: nothing else is in the message
            assert_eq!(m["models"][0].as_object().unwrap().len(), 3);
        });
    }

    #[test]
    fn an_older_phone_is_never_offered_nor_answered_anything_about_chat() {
        block_on(async {
            let fake = Fake::new(true);
            let rig = rig(&fake).await;
            let (mut c, welcome) = phone(&rig, None).await;
            assert!(welcome.get("caps").is_none(), "{welcome}");
            c.send(send("a1", "anthropic/claude-x", "hi")).await;
            c.send(json!({ "type": "chatModels" })).await;
            c.send(json!({ "type": "ping" })).await;
            // the first thing back is the pong: nothing was said about chat in between
            loop {
                let m = c.recv().await.unwrap();
                assert!(!m["type"].as_str().unwrap().starts_with("chat"), "{m}");
                if m["type"] == "pong" {
                    break;
                }
            }
            assert!(fake.calls.lock().unwrap().is_empty());
        });
    }

    #[test]
    fn an_older_computer_is_the_same_as_chat_being_off_no_caps_in_welcome() {
        block_on(async {
            // No chat wired in at all (as before this feature): the welcome has no caps even if the phone asks.
            let rig = Rig::start().await;
            let (_c, welcome) = phone(&rig, Some(json!(["chat", "details"]))).await;
            assert!(welcome.get("caps").is_none());
            assert_eq!(welcome["v"], 1);
        });
    }

    #[test]
    fn with_the_switch_off_nothing_is_offered_and_a_send_is_ignored() {
        block_on(async {
            let fake = Fake::new(false);
            let rig = rig(&fake).await;
            let (mut c, welcome) = phone(&rig, Some(json!(["chat"]))).await;
            assert!(welcome.get("caps").is_none());
            c.send(send("a1", "anthropic/claude-x", "hi")).await;
            c.send(json!({ "type": "ping" })).await;
            loop {
                let m = c.recv().await.unwrap();
                assert!(!m["type"].as_str().unwrap().starts_with("chat"), "{m}");
                if m["type"] == "pong" {
                    break;
                }
            }
            assert!(fake.calls.lock().unwrap().is_empty());
        });
    }

    #[test]
    fn an_answer_arrives_in_pieces_that_add_up_and_the_model_gets_the_text() {
        block_on(async {
            let fake = Fake::new(true);
            let rig = rig(&fake).await;
            let (mut c, _) = phone(&rig, Some(json!(["chat"]))).await;
            c.send(send("a1", "openai/gpt-y", "Say hello")).await;
            let (text, end) = answer(&mut c, "a1").await;
            assert_eq!(text, "Hello world");
            assert_eq!(end["type"], "chatDone");
            assert!(end.get("text").is_none(), "no correction needed");
            assert_eq!(*fake.calls.lock().unwrap(), vec![("openai/gpt-y".to_string(), "Say hello".to_string())]);
        });
    }

    #[test]
    fn a_rewritten_answer_is_corrected_in_done() {
        block_on(async {
            let fake = Fake::new(true);
            fake.script(Script::Rewrite { streamed: "<think>hmm".into(), full: "Answer".into() });
            let rig = rig(&fake).await;
            let (mut c, _) = phone(&rig, Some(json!(["chat"]))).await;
            c.send(send("a1", "openai/gpt-y", "q")).await;
            let (_, end) = answer(&mut c, "a1").await;
            assert_eq!(end["type"], "chatDone");
            assert_eq!(end["text"], "Answer");
        });
    }

    #[test]
    fn a_huge_answer_is_cut_into_lines_under_64_kib() {
        block_on(async {
            let fake = Fake::new(true);
            let big = "é".repeat(60_000); // 120 KB in one go
            fake.script(Script::Words(vec![big.clone()]));
            let rig = rig(&fake).await;
            let (mut c, _) = phone(&rig, Some(json!(["chat"]))).await;
            c.send(send("a1", "openai/gpt-y", "q")).await;
            let (text, end) = answer(&mut c, "a1").await;
            assert_eq!(end["type"], "chatDone");
            assert_eq!(text, big);
        });
    }

    #[test]
    fn failures_carry_a_fixed_code_and_message_only() {
        block_on(async {
            for (fail, code) in [
                (ChatFail::NoKey, "no_key"),
                (ChatFail::Unreachable, "unreachable"),
                (ChatFail::Auth, "auth"),
                (ChatFail::Provider, "provider"),
                (ChatFail::Internal, "internal"),
            ] {
                let fake = Fake::new(true);
                fake.script(Script::Fail(fail));
                let rig = rig(&fake).await;
                let (mut c, _) = phone(&rig, Some(json!(["chat"]))).await;
                c.send(send("a1", "openai/gpt-y", "q")).await;
                let (_, end) = answer(&mut c, "a1").await;
                assert_eq!(end["type"], "chatError");
                assert_eq!(end["reason"], code);
                assert_eq!(end["message"], reason(code));
                assert_eq!(end.as_object().unwrap().len(), 4, "type, id, reason, message and nothing else: {end}");
            }
        });
    }

    #[test]
    fn the_text_the_model_and_the_id_are_checked() {
        block_on(async {
            let fake = Fake::new(true);
            let rig = rig(&fake).await;
            let (mut c, _) = phone(&rig, Some(json!(["chat"]))).await;
            c.send(send("e1", "openai/gpt-y", &"x".repeat(MAX_TEXT_CHARS + 1))).await;
            assert_eq!(answer(&mut c, "e1").await.1["reason"], "too_long");
            c.send(send("e2", "openai/gpt-y", "   ")).await;
            assert_eq!(answer(&mut c, "e2").await.1["reason"], "empty");
            c.send(send("e3", "openai/not-allowed", "hi")).await;
            assert_eq!(answer(&mut c, "e3").await.1["reason"], "not_allowed");
            c.send(send("e4", "", "hi")).await;
            assert_eq!(answer(&mut c, "e4").await.1["reason"], "not_allowed");
            // exactly the limit is fine
            c.send(send("ok", "openai/gpt-y", &"é".repeat(MAX_TEXT_CHARS))).await;
            assert_eq!(answer(&mut c, "ok").await.1["type"], "chatDone");
            // an id that is empty, too long or odd is not answered at all
            for bad in ["", &"a".repeat(65), "a b", "a\u{0}b"] {
                c.send(send(bad, "openai/gpt-y", "hi")).await;
            }
            c.send(json!({ "type": "ping" })).await;
            c.expect("pong").await;
            assert_eq!(fake.calls.lock().unwrap().len(), 1, "only the valid send reached the provider");
        });
    }

    #[test]
    fn only_one_answer_runs_at_a_time_and_cancel_frees_the_slot() {
        block_on(async {
            let fake = Fake::new(true);
            fake.script(Script::Hang);
            let rig = rig(&fake).await;
            let (mut c, _) = phone(&rig, Some(json!(["chat"]))).await;
            c.send(send("a1", "openai/gpt-y", "slow")).await;
            // wait until the provider call has started
            for _ in 0..100 {
                if !fake.calls.lock().unwrap().is_empty() {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
            c.send(send("a2", "openai/gpt-y", "second")).await;
            assert_eq!(answer(&mut c, "a2").await.1["reason"], "busy");
            c.send(json!({ "type": "chatCancel", "id": "a1" })).await;
            assert_eq!(answer(&mut c, "a1").await.1["reason"], "canceled");
            assert!(wait_for(|| fake.dropped.load(Ordering::SeqCst)).await, "the provider call was dropped");
            fake.script(Script::Words(vec!["ok".into()]));
            c.send(send("a3", "openai/gpt-y", "third")).await;
            assert_eq!(answer(&mut c, "a3").await.0, "ok");
        });
    }

    #[test]
    fn a_cancel_for_another_id_changes_nothing() {
        block_on(async {
            let fake = Fake::new(true);
            fake.script(Script::Hang);
            let rig = rig(&fake).await;
            let (mut c, _) = phone(&rig, Some(json!(["chat"]))).await;
            c.send(send("a1", "openai/gpt-y", "slow")).await;
            c.send(json!({ "type": "chatCancel", "id": "other" })).await;
            c.send(send("a2", "openai/gpt-y", "x")).await;
            assert_eq!(answer(&mut c, "a2").await.1["reason"], "busy", "a1 is still running");
        });
    }

    #[test]
    fn the_rate_limit_stops_the_thirteenth_send() {
        block_on(async {
            let fake = Fake::new(true);
            let rig = rig(&fake).await;
            let (mut c, _) = phone(&rig, Some(json!(["chat"]))).await;
            for i in 0..MAX_SENDS {
                let id = format!("m{i}");
                c.send(send(&id, "openai/gpt-y", "hi")).await;
                assert_eq!(answer(&mut c, &id).await.1["type"], "chatDone", "send {i}");
            }
            c.send(send("over", "openai/gpt-y", "hi")).await;
            assert_eq!(answer(&mut c, "over").await.1["reason"], "rate");
            assert_eq!(fake.calls.lock().unwrap().len(), MAX_SENDS);
        });
    }

    #[test]
    fn leaving_stops_the_answer_and_frees_the_slot_for_the_next_phone() {
        block_on(async {
            let fake = Fake::new(true);
            fake.script(Script::Hang);
            let rig = rig(&fake).await;
            let (mut c, _) = phone(&rig, Some(json!(["chat"]))).await;
            c.send(send("a1", "openai/gpt-y", "slow")).await;
            assert!(wait_for(|| !fake.calls.lock().unwrap().is_empty()).await);
            c.send(json!({ "type": "bye" })).await;
            assert!(c.closed().await);
            assert!(wait_for(|| fake.dropped.load(Ordering::SeqCst)).await, "no answer keeps running for a phone that left");
            fake.script(Script::Words(vec!["fine".into()]));
            let (mut d, _) = phone(&rig, Some(json!(["chat"]))).await;
            d.send(send("b1", "openai/gpt-y", "hi")).await;
            assert_eq!(answer(&mut d, "b1").await.0, "fine");
        });
    }

    #[test]
    fn switching_chat_off_while_connected_refuses_further_sends() {
        block_on(async {
            let fake = Fake::new(true);
            let rig = rig(&fake).await;
            let (mut c, _) = phone(&rig, Some(json!(["chat"]))).await;
            fake.enable(false);
            c.send(send("a1", "openai/gpt-y", "hi")).await;
            let (_, end) = answer(&mut c, "a1").await;
            assert_eq!(end["reason"], "off");
            assert!(fake.calls.lock().unwrap().is_empty());
            c.send(json!({ "type": "chatModels" })).await;
            assert_eq!(c.expect("chatModels").await["models"], json!([]));
        });
    }

    #[test]
    fn turning_the_link_off_cancels_the_answer_and_forgets_the_conversation() {
        block_on(async {
            let fake = Fake::new(true);
            fake.script(Script::Hang);
            let mut rig = rig(&fake).await;
            let (mut c, _) = phone(&rig, Some(json!(["chat"]))).await;
            c.send(send("a1", "openai/gpt-y", "slow")).await;
            assert!(wait_for(|| !fake.calls.lock().unwrap().is_empty()).await);
            rig.handle.take().unwrap().stop();
            assert!(wait_for(|| fake.dropped.load(Ordering::SeqCst)).await);
            assert_eq!(fake.resets.load(Ordering::SeqCst), 1);
        });
    }

    #[test]
    fn new_chat_forgets_the_computers_side_and_stops_a_running_answer() {
        block_on(async {
            let fake = Fake::new(true);
            fake.script(Script::Hang);
            let rig = rig(&fake).await;
            let (mut c, _) = phone(&rig, Some(json!(["chat"]))).await;
            c.send(send("a1", "openai/gpt-y", "slow")).await;
            assert!(wait_for(|| !fake.calls.lock().unwrap().is_empty()).await);
            c.send(json!({ "type": "chatReset" })).await;
            assert_eq!(answer(&mut c, "a1").await.1["reason"], "canceled");
            assert_eq!(fake.resets.load(Ordering::SeqCst), 1);
        });
    }

    #[test]
    fn nothing_that_leaves_the_computer_can_contain_a_key_or_a_provider_message() {
        block_on(async {
            // The backend has no way to hand text to the link besides the answer itself, so
            // a failure cannot carry any; this pins the messages that exist.
            for code in ["off", "not_allowed", "busy", "rate", "too_long", "empty", "no_key", "unreachable", "auth", "provider", "canceled", "internal"] {
                let m = reason(code);
                assert!(!m.contains("sk-") && !m.to_lowercase().contains("bearer") && !m.contains("key:"), "{m}");
            }
            assert!(DONE_TEXT_MAX < 64 * 1024);
        });
    }

    async fn wait_for(cond: impl Fn() -> bool) -> bool {
        for _ in 0..200 {
            if cond() {
                return true;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        false
    }
}
