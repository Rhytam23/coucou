// The real phone link server, run for the Android app's tests.
//
// Not a test of its own: it is `#[ignore]`d, and android/.../RustDesktopInteropTest.kt
// starts it (`cargo test ... -- --ignored --nocapture interop_server`) to try the
// Kotlin client against this implementation. It prints one JSON line (the port,
// the certificate fingerprint, the pairing link), then obeys one command per line
// on stdin and reports what the desktop would do on stdout:
//
//   sessions                 publish two sample sessions
//   approval <id> <command>  a permission request starts waiting
//   clear                    the request was answered at the desk
//   repair <token>           pair again with a new token (phones are kicked)
//   quit
//
//   DECISION allow|deny <id> printed when a phone's decision is applied

use std::io::BufRead;
use std::sync::Arc;
use std::time::Duration;

use super::hub::{ApprovalIn, Host, Hub, SessionIn};
use super::pairing::{self, tests::MemStore};
use super::server;

struct Printing;
impl Host for Printing {
    fn decide(&self, request_id: &str, allow: bool) {
        println!("DECISION {} {request_id}", if allow { "allow" } else { "deny" });
    }
}

#[test]
#[ignore = "driven by android/app/src/test/.../RustDesktopInteropTest.kt"]
fn interop_server() {
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
    runtime.block_on(async {
        let identity = pairing::load_or_create_identity(&MemStore::default()).unwrap();
        let token = std::env::var("COUCOU_INTEROP_TOKEN").unwrap_or_else(|_| "interop-token-0123456789abcdef".into());
        let hub = Hub::new(Arc::new(Printing));
        let shared = server::Shared::new(hub.clone(), token.clone(), "Rust desktop".into());
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = server::serve(listener, server::tls_acceptor(&identity).unwrap(), shared.clone());
        let link = pairing::pairing_link("127.0.0.1", port, &identity.fingerprint, &token, "Rust desktop");
        println!("{}", serde_json::json!({ "listening": port, "fp": identity.fingerprint, "link": link }));

        let (tx, rx) = std::sync::mpsc::channel::<String>();
        std::thread::spawn(move || {
            for line in std::io::stdin().lock().lines().map_while(Result::ok) {
                if tx.send(line).is_err() {
                    break;
                }
            }
        });

        loop {
            let Ok(line) = rx.try_recv() else {
                tokio::time::sleep(Duration::from_millis(20)).await;
                continue;
            };
            let mut words = line.splitn(3, ' ');
            match (words.next(), words.next(), words.next()) {
                (Some("sessions"), _, _) => hub.publish(
                    vec![
                        SessionIn { pill_id: "integration_claude".into(), agent: "Claude Code".into(), state: "working".into(), status_text: "Editing files".into(), step_index: 2, step_count: 6 },
                        SessionIn { pill_id: "agent_gemini".into(), agent: "Gemini CLI".into(), state: "sleeping".into(), ..Default::default() },
                    ],
                    None,
                    server::now_ms(),
                ),
                (Some("approval"), Some(id), Some(command)) => hub.publish(
                    vec![],
                    Some(ApprovalIn { request_id: id.into(), session_id: "interop".into(), pill_id: "integration_claude".into(), tool: "Bash".into(), command: command.into() }),
                    server::now_ms(),
                ),
                (Some("clear"), _, _) => hub.publish(vec![], None, server::now_ms()),
                (Some("repair"), Some(new_token), _) => {
                    *shared.token.lock().unwrap() = new_token.to_string();
                    hub.kick_all("auth", "unpaired");
                }
                (Some("quit"), _, _) => break,
                _ => println!("? {line}"),
            }
            println!("OK {line}");
        }
        handle.stop();
    });
}
