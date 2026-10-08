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
        let identity = pairing::load_or_create_identity(&MemStore::default()).unwrap();
        let host = Arc::new(Recorder::default());
        let hub = Hub::new(host.clone());
        let shared = server::Shared::new(hub.clone(), TOKEN.to_string(), "Test PC".to_string());
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
