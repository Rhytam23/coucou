// The phone link: lets the Android app ("Coucou for Android") see the agents'
// sessions and answer permission requests from the phone.
// Protocol: docs/ANDROID_LINK.md.
//
// Off by default and opt-in (Settings → Phone). While it is on, a TLS server
// listens on the local network; while it is off nothing listens, nothing is
// published and no timer runs. No telemetry: the only traffic is between this
// computer and the phone paired with it.
//
// The island keeps owning the sessions and the pending request. It publishes a
// picture of them (`phone_link_publish`), the hub works out what changed, and a
// phone's decision comes back through `TauriHost`, which answers the waiting
// `coucou-hook` exactly as a click on the island would — but only for the request
// that is still pending (hub.rs). The desktop's own card keeps working all along.

mod chat;
mod chat_backend;
mod discovery;
mod hub;
#[cfg(test)]
mod interop;
mod pairing;
mod server;
#[cfg(test)]
mod tests;

use std::net::{Ipv4Addr, SocketAddr, TcpListener as StdListener};
use std::sync::{Arc, Mutex};

use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager, State, WebviewWindow};

use crate::{island, log, pipe, recap, secrets, settings, Shared};
use hub::{ApprovalIn, Host, Hub, SessionIn};
use pairing::SecretStore;

/// The port the pairing link offers; another is taken if it is busy.
pub const DEFAULT_PORT: u16 = 47821;

/// The OS keystore (secrets.rs).
struct Keystore;

impl SecretStore for Keystore {
    fn get(&self, key: &str) -> Option<String> {
        secrets::get_internal(key)
    }
    fn set(&self, key: &str, value: &str) -> Result<(), String> {
        secrets::set_internal(key, value)
    }
}

/// A phone's decision, applied as a click on the island would be.
struct TauriHost {
    app: AppHandle,
}

impl Host for TauriHost {
    fn get_diff(&self, sub_id: u64, pill_id: &str, file_id: u64) {
        // The island has the diffs; it answers with phone_link_send_diff. Nothing about the file is logged.
        let _ = self.app.emit_to(island::WINDOW_LABEL, "phone-link-getdiff", serde_json::json!({ "conn": sub_id, "pillId": pill_id, "fileId": file_id }));
    }

    fn answer(&self, request_id: &str, answers: &serde_json::Map<String, serde_json::Value>) {
        log::line(format!("phone link: answered a question id={request_id}")); // never what was picked
        recap::forget_request(&self.app, request_id);
        let map: std::collections::HashMap<String, serde_json::Value> = answers.clone().into_iter().collect();
        pipe::answer_question(&self.app, request_id, &map);
        let _ = self.app.emit_to(island::WINDOW_LABEL, "phone-link-decided", request_id.to_string());
    }

    fn decide(&self, request_id: &str, allow: bool) {
        let word = if allow { "allow" } else { "deny" };
        log::line(format!("phone link: decision id={request_id} {word}"));
        recap::record_decision(&self.app, request_id, word);
        pipe::answer(&self.app, request_id, word);
        // The card on the island closes; it was answered elsewhere.
        let _ = self.app.emit_to(island::WINDOW_LABEL, "phone-link-decided", request_id.to_string());
    }
}

struct Running {
    handle: server::Handle,
    shared: Arc<server::Shared>,
    port: u16,
    identity: pairing::Identity,
}

/// The switches in Settings → Android phone, read when a phone connects.
struct TauriFeatures {
    app: AppHandle,
}

impl server::Features for TauriFeatures {
    fn details(&self) -> bool {
        self.app.state::<Shared>().settings.lock().unwrap().phone_details
    }
    fn answers(&self) -> bool {
        self.app.state::<Shared>().settings.lock().unwrap().phone_answers
    }
    fn diffs(&self) -> bool {
        self.app.state::<Shared>().settings.lock().unwrap().phone_diffs
    }
}

pub struct PhoneLink {
    app: AppHandle,
    hub: Arc<Hub>,
    /// Chat from the phone; offered to phones only while the user's switch is on (chat.rs).
    chat: Arc<chat::ChatLink>,
    store: Arc<dyn SecretStore>,
    /// The announcement on the local network (discovery.rs): exists only while the link runs.
    advertiser: discovery::Advertiser,
    running: Mutex<Option<Running>>,
    error: Mutex<Option<String>>,
}

impl PhoneLink {
    pub fn new(app: AppHandle) -> Self {
        Self {
            chat: chat::ChatLink::new(Arc::new(chat_backend::AppChat::new(app.clone()))),
            hub: Hub::new(Arc::new(TauriHost { app: app.clone() })),
            app,
            store: Arc::new(Keystore),
            advertiser: discovery::Advertiser::new(Arc::new(discovery::MdnsPublisher::new())),
            running: Mutex::new(None),
            error: Mutex::new(None),
        }
    }

    /// Starts listening. Fails without side effects if the keystore is missing:
    /// nothing is ever kept on disk instead.
    pub fn start(&self) -> Result<(), String> {
        let mut running = self.running.lock().unwrap();
        if running.is_some() {
            return Ok(());
        }
        let result = self.start_inner();
        match result {
            Ok(r) => {
                log::line(format!("phone link: listening on port {}", r.port));
                // So the phone can find this computer again after a Wi-Fi change. The link works without it.
                match self.advertiser.start(&pairing::computer_name(), r.port, &r.identity.fingerprint) {
                    Ok(()) => log::line("phone link: announced on the local network"),
                    Err(e) => log::line(format!("phone link: not announced on the local network: {e}")),
                }
                *running = Some(r);
                *self.error.lock().unwrap() = None;
                Ok(())
            }
            Err(e) => {
                log::line(format!("phone link: could not start: {e}"));
                *self.error.lock().unwrap() = Some(e.clone());
                Err(e)
            }
        }
    }

    fn start_inner(&self) -> Result<Running, String> {
        let identity = pairing::load_or_create_identity(&*self.store)
            .map_err(|e| format!("no secure storage for the certificate: {e}"))?;
        let token = pairing::token(&*self.store).map_err(|e| format!("no secure storage for the pairing code: {e}"))?;
        let std_listener = StdListener::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, DEFAULT_PORT)))
            .or_else(|_| StdListener::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))))
            .map_err(|e| format!("cannot listen: {e}"))?;
        std_listener.set_nonblocking(true).map_err(|e| e.to_string())?;
        let port = std_listener.local_addr().map_err(|e| e.to_string())?.port();
        let acceptor = server::tls_acceptor(&identity)?;
        let shared = server::Shared::with_features(
            self.hub.clone(), token, pairing::computer_name(), Some(self.chat.clone()), Arc::new(TauriFeatures { app: self.app.clone() }),
        );
        let handle = {
            let shared = shared.clone();
            tauri::async_runtime::block_on(async move {
                let listener = tokio::net::TcpListener::from_std(std_listener).map_err(|e| e.to_string())?;
                Ok::<_, String>(server::serve(listener, acceptor, shared))
            })?
        };
        Ok(Running { handle, shared, port, identity })
    }

    pub fn stop(&self) {
        self.advertiser.stop();
        if let Some(r) = self.running.lock().unwrap().take() {
            r.handle.stop();
            log::line("phone link: stopped");
        }
    }
}

// ── Commands ──────────────────────────────────────────────────────────────────

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub enabled: bool,
    pub running: bool,
    pub port: u16,
    /// This computer's address on the local network, as the phone will be told.
    pub host: String,
    pub name: String,
    pub clients: usize,
    pub error: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Pairing {
    pub link: String,
    pub qr_svg: String,
    pub host: String,
    pub port: u16,
    pub name: String,
}

fn status_of(link: &PhoneLink, enabled: bool) -> Status {
    let running = link.running.lock().unwrap();
    Status {
        enabled,
        running: running.is_some(),
        port: running.as_ref().map_or(0, |r| r.port),
        host: pairing::lan_address().map(|ip| ip.to_string()).unwrap_or_default(),
        name: pairing::computer_name(),
        clients: link.hub.clients(),
        error: link.error.lock().unwrap().clone(),
    }
}

#[tauri::command]
pub fn phone_link_status(link: State<PhoneLink>, shared: State<Shared>) -> Status {
    let enabled = shared.settings.lock().unwrap().phone_link;
    status_of(&link, enabled)
}

/// Turns the link on or off. Only ever from the switch in Settings → Phone.
#[tauri::command]
pub fn phone_link_set_enabled(
    app: AppHandle,
    link: State<PhoneLink>,
    shared: State<Shared>,
    enabled: bool,
) -> Result<Status, String> {
    if enabled {
        link.start()?;
    } else {
        link.stop();
    }
    let updated = {
        let mut current = shared.settings.lock().unwrap();
        current.phone_link = enabled;
        if let Err(err) = settings::save(&current) {
            log::line(format!("could not save settings: {err}"));
        }
        current.clone()
    };
    let _ = app.emit("settings-changed", updated);
    Ok(status_of(&link, enabled))
}

/// The pairing link and its QR code. Only the settings window may ask: the token
/// is the secret that lets a phone in.
#[tauri::command]
pub fn phone_link_pairing(window: WebviewWindow, link: State<PhoneLink>) -> Result<Pairing, String> {
    if window.label() != "settings" {
        return Err("only the settings window may show the pairing code".into());
    }
    let running = link.running.lock().unwrap();
    let r = running.as_ref().ok_or("the phone link is off")?;
    pairing_of(r)
}

fn pairing_of(r: &Running) -> Result<Pairing, String> {
    let host = pairing::lan_address().map(|ip| ip.to_string()).ok_or("no local network address found")?;
    let name = r.shared.name.clone();
    let token = r.shared.token.lock().unwrap().clone();
    let link = pairing::pairing_link(&host, r.port, &r.identity.fingerprint, &token, &name);
    let qr_svg = pairing::qr_svg(&link).unwrap_or_default();
    Ok(Pairing { link, qr_svg, host, port: r.port, name })
}

/// A new pairing code: the phone that had the old one is disconnected and must
/// pair again.
#[tauri::command]
pub fn phone_link_new_pairing(window: WebviewWindow, link: State<PhoneLink>) -> Result<Pairing, String> {
    if window.label() != "settings" {
        return Err("only the settings window may change the pairing".into());
    }
    let running = link.running.lock().unwrap();
    let r = running.as_ref().ok_or("the phone link is off")?;
    let token = pairing::new_token(&*link.store)?;
    *r.shared.token.lock().unwrap() = token;
    link.hub.kick_all("auth", "unpaired");
    log::line("phone link: new pairing code, phones disconnected");
    pairing_of(r)
}

// ── Session details for the phone: the switch (Settings → Android phone) ──

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DetailsStatus {
    pub enabled: bool,
}

#[tauri::command]
pub fn phone_details_status(shared: State<Shared>) -> DetailsStatus {
    DetailsStatus { enabled: shared.settings.lock().unwrap().phone_details }
}

/// "Show session details on the phone". Off by default. A change disconnects the phones once so they
/// reconnect and are told (or not) about the capability.
#[tauri::command]
pub fn phone_details_set_enabled(
    app: AppHandle,
    window: WebviewWindow,
    link: State<PhoneLink>,
    shared: State<Shared>,
    enabled: bool,
) -> Result<DetailsStatus, String> {
    only_settings(&window)?;
    let updated = {
        let mut current = shared.settings.lock().unwrap();
        current.phone_details = enabled;
        if let Err(err) = settings::save(&current) {
            log::line(format!("could not save settings: {err}"));
        }
        current.clone()
    };
    link.hub.kick_all("closed", "details setting changed");
    log::line(format!("phone link: details {}", if enabled { "on" } else { "off" }));
    let _ = app.emit("settings-changed", updated);
    Ok(DetailsStatus { enabled })
}

// ── Answering questions from the phone: the switch (Settings → Android phone) ──

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AnswersStatus {
    pub enabled: bool,
}

#[tauri::command]
pub fn phone_answers_status(shared: State<Shared>) -> AnswersStatus {
    AnswersStatus { enabled: shared.settings.lock().unwrap().phone_answers }
}

/// "Let the phone answer Claude Code's questions". Off by default. A change disconnects the phones once so they
/// reconnect and are told (or not) about the capability. The phone confirms every answer with its screen lock.
#[tauri::command]
pub fn phone_answers_set_enabled(
    app: AppHandle,
    window: WebviewWindow,
    link: State<PhoneLink>,
    shared: State<Shared>,
    enabled: bool,
) -> Result<AnswersStatus, String> {
    only_settings(&window)?;
    let updated = {
        let mut current = shared.settings.lock().unwrap();
        current.phone_answers = enabled;
        if let Err(err) = settings::save(&current) {
            log::line(format!("could not save settings: {err}"));
        }
        current.clone()
    };
    link.hub.kick_all("closed", "answers setting changed");
    log::line(format!("phone link: answering questions {}", if enabled { "on" } else { "off" }));
    let _ = app.emit("settings-changed", updated);
    Ok(AnswersStatus { enabled })
}

// ── File changes on the phone: the switch (Settings → Android phone) ──

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DiffsStatus {
    pub enabled: bool,
}

#[tauri::command]
pub fn phone_diffs_status(shared: State<Shared>) -> DiffsStatus {
    DiffsStatus { enabled: shared.settings.lock().unwrap().phone_diffs }
}

/// "Show the files an agent changed on the phone". Off by default. A change disconnects the phones once so they
/// reconnect and are told (or not) about the capability.
#[tauri::command]
pub fn phone_diffs_set_enabled(
    app: AppHandle,
    window: WebviewWindow,
    link: State<PhoneLink>,
    shared: State<Shared>,
    enabled: bool,
) -> Result<DiffsStatus, String> {
    only_settings(&window)?;
    let updated = {
        let mut current = shared.settings.lock().unwrap();
        current.phone_diffs = enabled;
        if let Err(err) = settings::save(&current) {
            log::line(format!("could not save settings: {err}"));
        }
        current.clone()
    };
    link.hub.kick_all("closed", "diffs setting changed");
    log::line(format!("phone link: file changes on the phone {}", if enabled { "on" } else { "off" }));
    let _ = app.emit("settings-changed", updated);
    Ok(DiffsStatus { enabled })
}

/// The island's answer to a phone's request for a file's diff (event "phone-link-getdiff").
#[tauri::command]
pub fn phone_link_send_diff(link: State<PhoneLink>, conn: u64, diff: hub::DiffIn) {
    if link.running.lock().unwrap().is_none() {
        return;
    }
    link.hub.send_diff(conn, diff);
}

// ── Chat from the phone: the switch and the list of models (Settings → Android phone) ──

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatStatus {
    pub enabled: bool,
    /// Allowed models as "provider/model".
    pub models: Vec<String>,
}

fn only_settings(window: &WebviewWindow) -> Result<(), String> {
    if window.label() == "settings" {
        Ok(())
    } else {
        Err("only the settings window may change what the phone can use".into())
    }
}

#[tauri::command]
pub fn phone_chat_status(shared: State<Shared>) -> ChatStatus {
    let s = shared.settings.lock().unwrap();
    ChatStatus { enabled: s.phone_chat, models: chat_backend::clean_models(&s.phone_chat_models) }
}

/// The switch "Let the phone chat with my AI providers". Off by default. A change disconnects the
/// phones so they reconnect and are told (or not) about the capability; turning it off also stops a
/// running answer and forgets the phone's conversation.
#[tauri::command]
pub fn phone_chat_set_enabled(
    app: AppHandle,
    window: WebviewWindow,
    link: State<PhoneLink>,
    shared: State<Shared>,
    enabled: bool,
) -> Result<ChatStatus, String> {
    only_settings(&window)?;
    let (updated, status) = {
        let mut current = shared.settings.lock().unwrap();
        current.phone_chat = enabled;
        if let Err(err) = settings::save(&current) {
            log::line(format!("could not save settings: {err}"));
        }
        let status = ChatStatus { enabled, models: chat_backend::clean_models(&current.phone_chat_models) };
        (current.clone(), status)
    };
    if !enabled {
        link.chat.shutdown();
    }
    link.hub.kick_all("closed", "chat setting changed");
    log::line(format!("phone link: chat {}", if enabled { "on" } else { "off" }));
    let _ = app.emit("settings-changed", updated);
    Ok(status)
}

/// Which models the phone may use. Anything that is not a known provider's model is dropped.
#[tauri::command]
pub fn phone_chat_set_models(
    app: AppHandle,
    window: WebviewWindow,
    shared: State<Shared>,
    models: Vec<String>,
) -> Result<ChatStatus, String> {
    only_settings(&window)?;
    let cleaned = chat_backend::clean_models(&models);
    let (updated, status) = {
        let mut current = shared.settings.lock().unwrap();
        current.phone_chat_models = cleaned.clone();
        if let Err(err) = settings::save(&current) {
            log::line(format!("could not save settings: {err}"));
        }
        (current.clone(), ChatStatus { enabled: current.phone_chat, models: cleaned })
    };
    let _ = app.emit("settings-changed", updated);
    Ok(status)
}

/// The question Claude Code is waiting on (None: none), for phones that may answer it.
#[tauri::command]
pub fn phone_link_publish_question(link: State<PhoneLink>, question: Option<hub::QuestionIn>) {
    if link.running.lock().unwrap().is_none() {
        return;
    }
    link.hub.publish_question(question, server::now_ms());
}

/// What Mochi wears on the computer, for phones that asked for `prefs`.
#[tauri::command]
pub fn phone_link_publish_prefs(link: State<PhoneLink>, outfit: String) {
    if link.running.lock().unwrap().is_none() {
        return;
    }
    link.hub.publish_outfit(&outfit);
}

/// The island's picture of its sessions and of the request waiting for an answer.
#[tauri::command]
pub fn phone_link_publish(link: State<PhoneLink>, sessions: Vec<SessionIn>, approval: Option<ApprovalIn>) {
    if link.running.lock().unwrap().is_none() {
        return;
    }
    link.hub.publish(sessions, approval, server::now_ms());
}

/// The app is quitting: withdraw the announcement so phones forget this computer at once.
pub fn stop_on_exit(app: &AppHandle) {
    if let Some(link) = app.try_state::<PhoneLink>() {
        link.stop();
    }
}

/// Called once at launch: the link comes back if the user had turned it on.
pub fn start_if_enabled(app: &AppHandle) {
    let enabled = app.state::<Shared>().settings.lock().unwrap().phone_link;
    if enabled {
        let link = app.state::<PhoneLink>();
        // A failure is kept in the status and the log; the setting stays on so
        // the user sees why in Settings instead of finding it silently off.
        let _ = link.start();
    }
}
