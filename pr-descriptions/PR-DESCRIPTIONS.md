# PR descriptions for Louis-CFM/coucou (drafts, nothing is opened yet)

Order Louis asked for: (a) CI bumps, (b) phone_link, (c) android/. Branches are on Rhytam23/coucou:
`ci/actions-bumps`, `feat/phone-link`, `feat/android`. All three are based on upstream main at 1dc314f (0.3.0).

---

## (a) ci/actions-bumps

**Title:** CI: checkout v7, setup-node v7, cache v6, upload-artifact v7, download-artifact v8

Version bumps only, in `linux.yml`, `windows.yml` and `windows-ci.yml` (15 lines changed, nothing else).
`build.yml`, `release.yml` and `gradle/actions` are not touched (gradle/actions v6 changed its licence, so v5 stays).

Why its own PR: these files are part of the release pipeline, so Louis wants to do a test release with them before the next real one.

How it was checked: the same bumps have been running green on my fork (Windows, Linux, windows-ci).

🤖 Generated with [Claude Code](https://claude.com/claude-code)

---

## (b) feat/phone-link

**Title:** Phone link: an opt-in, local-network server so the Android app can show sessions and answer approvals

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

---

## (c) feat/android

**Title:** Coucou for Android

The Android app, in `android/` (Kotlin, Jetpack Compose; min SDK 26; app id `com.coucou.android`, to be confirmed before any store listing). Needs the phone link from the previous PR to talk to a computer.

- Mochi on the phone with his 11 outfits and sounds; Home, agent details, Chat, plan usage, services, "Recent" decisions kept only on the phone.
- Approvals: a sheet over any screen; Allow needs the fingerprint or screen lock, Deny does not; taps in the first 600 ms ignored; 120 s expiry; a truncated command cannot be allowed from the phone. Notifications show a label only, never the command; the lock screen shows "Unlock to see the request"; a request on a sleeping phone lights the screen and vibrates.
- Pairing by QR (in-app camera, offline) or pasted link, always with a confirmation; the certificate is pinned; the phone finds the computer again by itself (mDNS, checked against the pinned certificate before the token is sent); "Can't connect?" checks.
- An alarm keeps the link alive with the screen off (no exact-alarm permission).
- Optional island over other apps, widget, quick-settings tile (off by default).
- Release build type: R8, resources shrunk, `android.util.Log` removed, not debuggable; a CI check that no debug text survives. No signing key in the repo.
- Licences screen with the full MIT text and the third-party notices (zxing, Apache 2.0). Free, no ads, no billing, no analytics (CI greps for them).
- Credit: "Coucou for Android, by Rhytam Biswas", part of Coucou by Louis Raillé. The name, Mochi, the icon and the sounds stay (c) Louis Raillé under `LICENSE-ASSETS.md` (to be extended to Android on his side). The code is MIT, (c) Rhytam Biswas, in `android/LICENSE`.
- Includes `android.yml` (tests, debug and release build, lint, strings check) and `phone-link.yml` (the Kotlin client against the real Rust server), now triggered on pull requests and pushes to main.

Tests: more than 700 unit tests. Two Mochi-outfit parity tests need Node 22 and fail on a local Node 24; they pass in CI.

Real phone: see the phone-link PR. A second phone and more networks are still to be tried (help welcome, issue Rhytam23/coucou#9). The relay (Cloudflare) is written and tested against a local Worker but not deployed, and its settings are hidden on the computer for now (the app only mentions it when a pairing carries relay keys).

Nothing is published. First distribution, when Louis approves: a signed APK from the repository's CI, with the key in the repository's secrets.

Maintained by me (CODEOWNERS for `android/`).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
