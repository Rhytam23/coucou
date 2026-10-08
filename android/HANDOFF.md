# Handoff: Coucou for Android (read this first)

Written for a new coding session (cloud or local) that has no memory of the earlier work.

## What this project is

**Coucou** (https://github.com/Louis-CFM/coucou, by Louis Raillé) is a macOS app, with a Windows/Linux
Tauri port in `windows/`, where **Mochi**, a small animated character, shows the state of AI coding
agents (Claude Code, Gemini CLI, Codex...) and lets the user approve or deny their permission requests.
An iPhone app exists (CloudKit + APNs), which does not work on Android.

**Our project:** an **unofficial Android port, "Coucou for Android"**, in `android/` (Kotlin + Jetpack
Compose). It is built by Rhytam Biswas (GitHub `Rhytam23`) with Claude. The work lives on branch
`android` of the fork `Rhytam23/coucou` (`upstream` = Louis-CFM/coucou, read-only for us).

**Final goal:** a free Android app that shows the user's real agent sessions as Mochi on their phone, with
notifications and Allow/Deny from the phone (Allow behind biometrics), connected to the desktop Coucou
over the local network. Then publish it (only after Louis approves, see Permission).

## Permission and rules that must never be broken

Louis agreed by email (terms in `android/PERMISSION.md`). He allows the Mochi character, sounds, visuals,
icon and the name "Coucou for Android" for this Android app only, under these conditions:
- The app is **free: no ads, no paid features, no analytics** (CI greps for ad/billing/analytics libs).
- A visible notice: unofficial port based on Coucou by Louis Raillé, with a link to his repo; MIT notice
  kept; name, Mochi, icon and sounds are © Louis Raillé, used with permission.
- Assets are not used in any other project.
- **Louis must approve the build and the store listing (name, description, screenshots) BEFORE anything is
  published.** Pushing source to the fork is fine. Never create a release, upload an APK, or submit to a
  store without the user confirming Louis approved. Never open a PR to `upstream` without asking.

Repo rules (`CLAUDE.md`, `CONTRIBUTING.md`): no third-party dependencies unless unavoidable (justify any);
secrets in the OS keystore, never on disk or git; no telemetry; never block the agent; never approve a
permission without an explicit click; 0 % CPU when idle; pill IDs are contract values and never renamed;
do not restyle existing shipped views; one topic per PR/commit.

## What exists and works (all tested)

- `app/.../mochi`: Mochi engine (Kotlin port of `windows/src/mochi/engine.ts`) and Compose painter: 11
  states, 7 emotes, hands, particles, badges, mailbox morph. **Outfits are not ported yet.**
- `app/.../core/Pills.kt`: pill catalog mirrored from `windows/src/core/pills.ts` (tests parse the TS file).
- `app/.../link`: the desktop link client: TLS + certificate pinning, newline-delimited JSON, pairing link,
  approvals with the Mac's SHA-256 fingerprint and 120 s expiry, reconnect with backoff, `DemoLink`.
  Protocol spec: `docs/ANDROID_LINK.md`.
- `app/.../app`: `AppModel`, notifications (Deny from the notification, Allow opens the app for the
  biometric prompt), foreground `LinkService`, `SecureStore` (Android Keystore), `BiometricGate`.
- Sounds (res/raw), launcher icon, 10 languages (generated from the desktop catalog by
  `scripts/gen-strings.mjs`, Android-only strings in `i18n/`), dark/light theme.
- `tools/dev-desktop.mjs`: a pretend desktop (Node + openssl) that sends **fake scripted sessions and a
  fake approval**. It is a test tool only; the fake "Claude Code / Codex" data it shows is not real.
- 69 JVM unit tests (`./gradlew test`), lint clean, CI in `.github/workflows/android.yml`.
- Verified on a real phone (Samsung Galaxy A12s, Android 13): Mochi renders, demo mode, pairing over
  Wi-Fi, Allow reaches the desktop. Bugs found that way are fixed (e.g. network write on the main thread).

## What is NOT done (the work ahead, in priority order)

1. **The real desktop side.** Nothing in the Windows/Linux Tauri app sends real sessions to the phone yet,
   so the Android app can only show demo data or the fake dev-desktop. Implement the server described in
   `docs/ANDROID_LINK.md` in `windows/src-tauri/src` (read `pipe.rs`, `hooks.rs`, `lib.rs`,
   `settings.rs`, `secrets.rs`, `windows/src/core/state.ts`: where sessions and the pending approval live,
   and how a decision is sent back, see `pipe.rs` `answer`/`decline`). Requirements: opt-in and **off by
   default** behind a setting; never block the agent (the desktop's own approval keeps working); apply a
   phone decision only if its fingerprint matches the approval still pending; store the pairing token in the
   keystore (`secrets.rs`/keyring); generate the TLS certificate once; show the pairing link/QR in settings;
   no telemetry. New crates (rustls/tokio-rustls, certificate generation, sha2) are the unavoidable
   exception to the no-dependencies rule: keep them minimal and justify them. Tests: cargo tests (wrong
   token, fingerprint mismatch, oversize line, late decision) and an interop test against the Kotlin client
   (see `DevDesktopInteropTest.kt`, which does this against the Node stand-in).
2. A short **proposal text for Louis** explaining the desktop change, because changing his app is his call.
3. Port Mochi's **outfits** (`windows/src/mochi/outfits.ts`, `wardrobe.ts`).
4. Later: widgets, voice instructions, services tab, FCM relay (`relay/`), Play Store listing (needs Louis).

## How we work

- Small steps, **tested before pushed**; every fix gets a regression test where possible. Be honest about
  what was and was not verified.
- Commit messages end with the line `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Android build/test: `cd android && ./gradlew test assembleDebug lint` (JDK 17, Android SDK platform 35).
  Translations must stay in sync: `node scripts/gen-strings.mjs --check`.
- minSdk 30 (the combined biometric / screen-lock prompt needs it).
- Do not edit Louis's existing views; add new code. Keep the Windows/Linux app building.
- The user's own PC has 7 GB RAM, no MSVC, and C: must stay free, so heavy builds belong in the cloud or in
  GitHub Actions. The user tests on a real phone over USB debugging and Wi-Fi; a cloud session cannot reach
  the phone, so the final real-device test is done by the user with a build from GitHub Actions (the fork has
  `windows-ci.yml` / `windows.yml`).
- Environment quirks seen on the user's PC (local sessions only): shell heredocs and `node -e` strings lose
  backslashes, so write files with editor tools; the Android emulator does not run there.

## Useful commands

```
cd android && ./gradlew test assembleDebug lint          # build + 69 tests + lint
node android/scripts/gen-strings.mjs --check              # translations in sync
node android/tools/dev-desktop.mjs --host <LAN IP>        # fake desktop; prints a pairing link
adb install -r android/app/build/outputs/apk/debug/app-debug.apk   # local only
```
