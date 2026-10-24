What it is: `windows/src-tauri/src/phone_link/` plus the settings page ("Android phone"). Off by default; while it is off nothing listens, nothing is announced and no timer runs. No telemetry.

- TLS with a self-signed certificate pinned by SHA-256 from the pairing code; a pairing token checked in constant time; private addresses only; connection, handshake, idle and per-address limits.
- Approvals carry a SHA-256 fingerprint of the exact command and expire; decide once. A command the phone cannot show in full is flagged `truncated`, and the PC refuses Allow from the phone for it.
- mDNS announcement (`_coucou._tcp`, version and short fingerprint only) so the phone finds the computer again after a network change.
- Optional, each with its own switch (all off by default): session details, answers to questions, file changes, chat through the computer's own keys, plan usage, services.
- Review items from Louis already applied: relay keys in `INTERNAL_KEYS` and `kick_all` first in Pair again; the truncated flag; the window check on `phone_link_set_enabled`.
- The relay settings block ("Away from home Wi-Fi") is hidden for now (`SHOW_RELAY_SETTINGS = false` in `settings/phone.ts`); the code and its tests stay.
- Protocol: `docs/ANDROID_LINK.md`; relay design: `docs/RELAY_LINK.md`, `docs/RELAY_DEPLOY.md`.

Tests: Rust phone_link tests (174 at last count), 397 Windows (node) tests pass, `tsc --noEmit` is clean. The Kotlin client is tested against the real Rust server in the Android PR's "Phone link" workflow.

Tried on a real phone (Galaxy A12s, Android 13, home Wi-Fi and a hotspot): real Allow with the fingerprint, Deny, expiry, the link surviving a screen-off lock, a request reaching the locked phone.

Known limits: the Claude Desktop app's requests are declined by design (same rule as the Mac); plan usage comes only from Claude Code's status line.

Copyright line "(c) 2026 Rhytam Biswas, MIT" at the top of `phone_link/mod.rs`, as agreed.

Maintained by me (CODEOWNERS for `windows/src-tauri/src/phone_link/`).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
