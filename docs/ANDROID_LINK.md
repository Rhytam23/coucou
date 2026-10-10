# Android link (protocol v1)

How *Coucou for Android* talks to a desktop Coucou. The iPhone uses iCloud (CloudKit) and Apple
push, neither of which exists on Android, so the Android app connects to the desktop directly over
the local network.

Status: the Android client, `android/tools/dev-desktop.mjs` (a stand-in desktop for testing) and the
real desktop server of the Windows/Linux app (`windows/src-tauri/src/phone_link/`) implement this.
The Mac app is left to its author.

## Transport

- TCP, then TLS 1.2 or 1.3. The desktop uses a self-signed certificate it generates once.
- Newline-delimited JSON (one object per line, UTF-8). A line over 64 KiB is a protocol error.
- No hostname check and no certificate authority: the phone accepts exactly one certificate, the
  one whose SHA-256 it got when pairing (certificate pinning).
- Default port 47821 (any port works; it is in the pairing link).

## Pairing

The desktop shows a QR code (and a copyable link) containing:

```
coucou://pair?v=1&host=192.168.1.20&port=47821&fp=<sha256 hex of the TLS certificate>&token=<secret>&name=<desktop name>
```

- `token` is 16 to 128 characters of `[A-Za-z0-9_-]`, random, created per pairing. It is the shared
  secret; it travels only inside the pinned TLS connection and is stored on the phone encrypted
  with a key from the Android Keystore.
- The app can read the QR code itself ("Scan QR code" on the pairing card; CameraX for the preview, zxing-core to
  decode, no Play services). Scanning the QR with the phone's own camera app also opens the link in the app (the
  app registers the `coucou://pair` scheme). Either way the app names the computer and asks the user to confirm
  before it pairs; the link can also be pasted. The camera is used only on the scan screen and nothing is stored or sent.
- Unpairing on either side deletes the token. The desktop should accept one token per phone and let
  the user revoke it.

## Messages

Phone to desktop:

| `type` | fields | meaning |
|---|---|---|
| `hello` | `v`, `token`, `device` | first message; must match the pairing |
| `decision` | `fingerprint`, `decision` (`allow` or `deny`) | answer to an approval. No "always" from the phone |
| `ping` | | keep-alive, every 20 s |
| `bye` | | courtesy before closing; may be lost |

Desktop to phone:

| `type` | fields | meaning |
|---|---|---|
| `welcome` | `v`, `desktop`, `os` | reply to a valid `hello`. The phone refuses a different `v` |
| `error` | `code`, `message` | `auth` closes the link and the phone stops retrying quickly |
| `sessions` | `sessions[]` | the full list, replacing the previous one |
| `approval` | `pillId`, `fingerprint`, `tool`, `command`, `createdAt` (ms) | an agent waits for permission |
| `approvalResolved` | `fingerprint` | answered elsewhere or timed out; remove it |
| `pong` | | reply to `ping` |

A session is `{pillId, agent, state, statusText, stepIndex, stepCount, updatedAt}`. `state` is one of
`idle working thinking searching approval question error finished ratelimit sleeping dizzy`; an
unknown value is shown as `idle`. `pillId` values come from the pill catalog
(`windows/src/core/pills.ts`) and are never renamed. Unknown message types are ignored.

## Approvals

- `fingerprint` is the Mac's derivation (`ApprovalRelay.fingerprint`): lowercase hex SHA-256 of
  `pillId`, `sessionId`, `tool`, `command`, `inputKey` joined by U+001F.
- On the Windows/Linux desktop `inputKey` is the island's request id, so asking for the same command
  twice gives two different fingerprints.
- The desktop applies a `decision` only if its fingerprint matches the approval **still pending**
  (same session, tool, command and input). A late decision, or one for another command, is ignored.
- The phone offers an approval for at most 120 s (the desktop dismisses it at 115 s), and sends one
  decision per request.
- **Allow requires the user's biometric or screen lock on the phone.** Deny does not. With no
  screen lock set up, Allow is refused.
- The command text is hidden on the lock screen.

## Capabilities and chat (optional, still protocol v1)

`hello` may carry `caps`, a list of optional features the phone understands; `welcome` carries `caps` with the
ones this computer offers **to this phone right now**. A side that sends no `caps` gets none of them, so older
phones and older computers keep working unchanged (an unknown field is ignored, an unknown message type is ignored).
The only capability defined so far is `chat`: the phone talks to the AI providers the computer is set up for.
**The computer's API key never leaves the computer**; the phone sends text and receives text.

The computer offers `chat` only if the user turned "Let the phone chat with my AI providers" on (off by default),
and re-reads that switch for every message. The user also chooses which provider/model pairs the phone may use.

| Direction | `type` | fields |
|---|---|---|
| phone to computer | `chatModels` | none: ask for the allowed list |
| computer to phone | `chatModels` | `models[]`: `{id: "provider/model", provider, label}`, only what the user allowed |
| phone to computer | `chatSend` | `id` (1 to 64 chars of `[A-Za-z0-9_-]`, made by the phone), `model` (an `id` from the list), `text` (at most 4000 characters) |
| computer to phone | `chatDelta` | `id`, `text` appended to the answer (pieces of at most 8 KiB) |
| computer to phone | `chatDone` | `id`; `text` only if the streamed text must be replaced by this full answer |
| computer to phone | `chatError` | `id`, `reason`, `message` |
| phone to computer | `chatCancel` | `id`: stop waiting for this answer |
| phone to computer | `chatReset` | none: new chat; the computer forgets the conversation and stops a running answer |

- `reason` is one of `off not_allowed busy rate too_long empty no_key unreachable auth provider canceled internal`.
  `message` is a fixed English sentence written by the computer; a provider's own error text is never sent
  (some providers echo part of a key in an error).
- One answer runs at a time (`busy`), at most 12 sends per 10 minutes (`rate`). An answer stops when its phone leaves,
  when the user turns the switch or the link off, or on `chatCancel`. Stopping cannot undo what a provider already started.
- A `chatSend` whose id is not valid is ignored (there is nothing to answer to). A phone that did not negotiate `chat` is
  never sent, and never answered about, any `chat*` message.
- The computer keeps the conversation in memory only while the switch is on; nothing new is written to disk. The phone
  sends no files, window context, keys or addresses, and the computer would not accept them.
- Cloud providers answer all at once (as on the computer's own chat), local models stream.

## Session details (optional capability `details`)

A phone that sends `caps: ["details"]` in its `hello`, while the user has turned on "Show session details on the phone" on the
computer (off by default; re-read for every connection), is offered `details` in `welcome.caps`. Its `sessions` lines then
carry these optional fields on each session; any other phone gets the exact v1 fields and nothing else:

| field | meaning |
|---|---|
| `steps` | the last steps of the session, oldest first: at most 20, each at most 200 characters, as the island shows them (a file change is its file name). Can include commands. |
| `finalLine` | the agent's final line after it stopped, at most 200 characters |
| `project` | the **folder name** the session runs in, at most 64 characters. Never a path (only the last segment survives, on both the island and the link) |
| `color` | the pill's colour as `#RRGGBB`; anything else is dropped |

The whole `sessions` line stays under 56 KiB: when many sessions carry many steps, the oldest steps are dropped until it fits.
A field with nothing to say is left out. Turning the switch on or off disconnects the phones once so they reconnect and are told.
Not sent, ever: the prompt, command output, Claude's full answer, file contents, full paths.

## Finding the computer again (optional, still protocol v1)

The pairing link holds the computer's address, and an address changes with every network (a home Wi-Fi, a hotspot, a
new router). So the computer can also announce itself on the local network, and the phone can find it there. This is
an addition: **v=1 is unchanged, the saved address stays the first thing tried, and an old phone or an old computer
simply keeps using the saved address.**

- **Announcement** (computer): while the phone link is on, DNS-SD over multicast DNS, service type `_coucou._tcp`,
  instance name = the computer's name, the real port, IPv4 address. Off means silent: when the switch is turned off, the
  link stops or the app quits, the announcement is withdrawn and nothing runs.
- **TXT record**: `v=1` and `fp=<first 16 lowercase hex characters of the certificate's SHA-256>`. Nothing else. Never the
  token, never the pairing link, never a user name or a path. The certificate is public by nature (every peer that connects
  receives it), so its short id is not a secret, and it is **a hint, not a credential**.
- **Phone**: the framework's `NsdManager` (no library). It searches only when it has a paired computer, a Wi-Fi or cable is
  up and the link is not connected; it holds a multicast lock only while searching.
  1. The saved address is tried first and gets 2 s to accept the TCP connection.
  2. After that failure the network is searched for up to 15 s. Between searches it waits 20 s, 60 s, 3 min, then 5 min;
     a network change (`ConnectivityManager` callback) starts a search at once and resets the waiting. No Wi-Fi: no search.
  3. A service is a candidate only if its `fp` equals the first 16 characters of the **paired** certificate fingerprint and its
     `v` is `1` (or absent), and its address is on the local network. The name and the address never count.
  4. A candidate is then checked with a bare TLS handshake against the **full** pinned fingerprint. The probe takes no
     token: nothing secret can be sent to it. Only a certificate that matches is accepted; one that does not (a lookalike
     that copied the short id) is dropped and never saved. When the paired fingerprint is announced twice, the first that
     passes wins.
  5. The stored host and port are replaced; the token and the pinned fingerprint are untouched. The link reconnects.
- If nothing is found, the phone says so calmly (same Wi-Fi? computer awake and Coucou running? firewall? hotspots and guest
  networks can block devices from finding each other, "client isolation") and offers to pair again or to type the address.
- Limits, honestly: multicast does not cross routers or VPNs; some access points block it between clients; a firewall that
  blocks UDP 5353 on the private profile hides the computer (the Windows prompt "allow Coucou on private networks" covers
  it); two computers with the same certificate do not exist (each has its own).

## Behaviour rules (same as the other ports)

- Never block the agent: if the phone does not answer, the desktop's own approval stays usable.
- No telemetry. The only network traffic is between the phone and its paired desktop.
- The desktop link is **off by default** and opt-in in settings. It should listen only while it is
  on, and only to the local network.

## Windows/Linux implementation notes

- Settings → Android phone: off by default (`phoneLink` in settings.json, owned by Rust: the webview
  cannot switch it on). While off, nothing listens, nothing is published and no timer runs.
- It listens on `0.0.0.0` (port 47821, else any free port) but drops every peer that is not on the
  local network (private, loopback, link-local). At most 8 connections; 10 s to say hello; 90 s idle.
- The certificate (ECDSA P-256, self-signed) is generated once; its key and the pairing token live in
  the OS keystore (Credential Manager / Secret Service) and nowhere else. Without a keystore the link
  does not start.
- One token at a time: "Pair again" replaces it and disconnects the phone that had the old one. The
  pairing code is shown only after a click, and only to the settings window.
- Chat (Settings → Android phone → "Let the phone chat with my AI providers"): off by default, owned by Rust like
  `phoneLink` (`phoneChat`, `phoneChatModels` in settings.json; the webview cannot change them). The user ticks the
  provider/model pairs the phone may use (a provider's model list is asked for only when its "Choose models" button is
  pressed). Turning the switch on or off disconnects the phones once so they reconnect and learn about the capability;
  turning it off also stops a running answer and forgets the phone's conversation. The phone's conversation is a
  separate in-memory `Chat`, not the island's. The provider code is the island's (`chat::send_for_phone`); a provider's
  error text is never forwarded or logged, only a kind (no key, unreachable, auth, provider).
- Only permission requests (Allow/Deny) go to the phone; a question from Claude Code needs its options
  picked on the island.
- New crates: `mdns-sd` (+ `flume`, `if-addrs`, `socket-pktinfo`, `spin`; default features off, so no async runtime or logger) announces the computer on the local network while the link is on (`phone_link/discovery.rs`); `rcgen` (+ `yasna`) makes the certificate once, `qrcode` draws the pairing QR; `rustls`,
  `tokio-rustls` and `ring` were already in the dependency tree (through `reqwest`) and are now named
  directly. Hashing, randomness and the constant-time comparison use `ring`; no other crate.

## Android notes

- Android 16 asks for local network access the first time the app connects.
- A foreground service keeps the link alive in the background so approvals arrive as notifications.
- The app needs Android 11 or later (API 30) for the combined biometric / screen-lock prompt.

## Testing

- `android/tools/dev-desktop.mjs` pretends to be a desktop (needs `node` and `openssl`). With `--fake-chat` it offers
  `chat` with a fake provider (no key, no cost): `/error`, `/auth`, `/slow`, `/long` and `/rewrite` trigger the odd cases.
- `DevDesktopInteropTest` runs the Android client against it.
- `cargo test -p coucou phone_link` also tests the announcement (what is published and when, that no secret can be in it); the real multicast needs a real network and is not in the tests.
- `cargo test -p coucou phone_link` tests the real server (wrong token, fingerprint mismatch, oversize
  line, late decision, answered-at-the-desk, pairing again, local-network filter...).
- `RustDesktopInteropTest` runs the Android client against the real Rust server
  (`COUCOU_RUST_INTEROP=1`, needs cargo; the `Phone link` workflow does it).
