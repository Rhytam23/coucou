The Android app, in `android/` (Kotlin, Jetpack Compose; min SDK 30, Android 11; app id `com.coucou.android`, to be confirmed before any store listing). Needs the phone link from the previous PR to talk to a computer.

- Mochi on the phone with his 11 outfits and sounds; Home, agent details, Chat, plan usage, services, "Recent" decisions kept only on the phone.
- Approvals: a sheet over any screen; Allow needs the fingerprint or screen lock, Deny does not; taps in the first 600 ms ignored; 120 s expiry; a truncated command cannot be allowed from the phone. Notifications show a label only, never the command; the lock screen shows "Unlock to see the request"; a request on a sleeping phone lights the screen and vibrates.
- Pairing by QR (in-app camera, offline) or pasted link, always with a confirmation; the certificate is pinned; the phone finds the computer again by itself (mDNS, checked against the pinned certificate before the token is sent); "Can't connect?" checks.
- An alarm keeps the link alive with the screen off (no exact-alarm permission).
- Optional island over other apps, widget, quick-settings tile (off by default).
- Release build type: R8, resources shrunk, `android.util.Log` removed, not debuggable; a CI check that no debug text survives. No signing key in the repo.
- Licences screen with the full MIT text and the third-party notices (zxing, Apache 2.0). Free, no ads, no billing, no analytics (CI greps for them).
- Credit: "Coucou for Android, by Rhytam Biswas", part of Coucou by Louis Raillé. The name, Mochi, the icon and the sounds stay (c) Louis Raillé under `LICENSE-ASSETS.md` (to be extended to Android on his side). The code is MIT, (c) Rhytam Biswas, in `android/LICENSE`.
- Includes `android.yml` (tests, debug and release build, lint, strings check) and `phone-link.yml` (the Kotlin client against the real Rust server), now triggered on pull requests and pushes to main.

Tests: more than 700 unit tests. One of them (RelayNodeTwinTest) starts a real relay and can occasionally time out on a busy CI runner; it passed on re-run, and I am watching it. Two Mochi-outfit parity tests need Node 22 and fail on a local Node 24; they pass in CI.

Real phone: see the phone-link PR. A second phone and more networks are still to be tried (help welcome, issue Rhytam23/coucou#9). The relay (Cloudflare) is written and tested against a local Worker but not deployed, and its settings are hidden on the computer for now (the app only mentions it when a pairing carries relay keys).

Nothing is published. First distribution, when Louis approves: a signed APK from the repository's CI, with the key in the repository's secrets.

Maintained by me (CODEOWNERS for `android/`).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
