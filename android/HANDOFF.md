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
- Settings screen (sound on/off + volume, notices when an agent finishes or fails, quiet hours), a History of the phone's own decisions (kept only on the phone, command cut to 120 characters, 50 entries), quiet "Updates" notifications, and tap/long-press on Mochi (slap, dizzy, love). Plan: `android/PARITY_PLAN.md` (stage A done; B..H wait for the user's test of each stage).
- Optional island over other apps (`ui/IslandOverlay.kt` + `core/Island*.kt`): drops while an agent works, holds a result 10 s, goes up with the PC's motion; see `android/README.md`. The wish is saved at the tap; a debug-only broadcast (`src/debug`) drives it without an agent. Session details (opt-in cap `details`: steps, last line, project folder name, colour) open from the agent card (`ui/SessionScreen.kt`); chat through the computer is in `android/CHAT_PLAN.md`. Pairing by QR code from the app (`ui/ScanScreen.kt`, `scan/QrDecoder.kt`, `core/PairingScan.kt`; CameraX + zxing-core, no Play services; a link or scan always asks to confirm: `ui/PairConfirm.kt`). Home = agent card + pill grid (`core/HomePanel.kt`, `ui/HomePanel.kt`); Settings (gear on Home) holds disconnect, island switch, sound, notices, History, gallery and About. Raw tool names are mapped by `core/ToolLabels.kt`; colours live in `core/Palette.kt` (contrast tested). Never seen rendered by the author's tools: check it on the phone.
- **UI/UX redesign: planned, not built.** `android/UX_PLAN.md` (problems, screen map, three Home/navigation options, visual system, stages U0..U8) and a clickable mock `android/design/prototype.html`. Option A decided. **U0 done**: `core/Tokens.kt`, `core/IconSpec.kt`, `ui/DesignSystem.kt`, `ui/DesignControls.kt`, `ui/DesignIcons.kt`, `ui/DesignScreen.kt` (reach it from Settings > Mochi gallery > Design system). Existing screens are not moved yet (U1 next).
- Sounds (res/raw), launcher icon, English only for now (`resourceConfigurations = en`; the other `values-xx` folders and `i18n/extra.json` are kept so the languages can come back: set `_englishOnly` to false in `i18n/app-strings.json` and run `gen-strings.mjs`). Strings come from the desktop catalog by
  `scripts/gen-strings.mjs`, Android-only strings in `i18n/`), dark/light theme.
- `tools/dev-desktop.mjs`: a pretend desktop (Node + openssl) that sends **fake scripted sessions and a
  fake approval**. It is a test tool only; the fake "Claude Code / Codex" data it shows is not real.
- 114 JVM unit tests (`./gradlew test`; some need cargo/node and skip without them), lint clean, CI in `.github/workflows/android.yml`.
- Verified on a real phone (Samsung Galaxy A12s, Android 13): Mochi renders, demo mode, pairing over
  Wi-Fi, Allow reaches the desktop. Bugs found that way are fixed (e.g. network write on the main thread).

## What is NOT done (the work ahead, in priority order)

1. **The real desktop side: written, to be tried on a real phone.** `windows/src-tauri/src/phone_link/`
   (hub = rules, server = TLS, pairing = certificate/token/QR, tests, interop), front end in
   `windows/src/island/phone-link.ts` and `windows/src/settings/phone.ts`. Verified: cargo tests (Linux),
   front-end tests, the real Kotlin client against it (kotlinc + JUnit, 5/5). NOT verified: Windows
   build/tests (CI job `Phone link` / dispatch `Windows`), the Gradle run of `RustDesktopInteropTest`,
   a real phone on real Wi-Fi, real Claude Code sessions end to end.
2. **Proposal text for Louis**: drafted in `android/PROPOSAL_FOR_LOUIS.md`; it is for the user to send. Nothing is opened upstream.
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
cd android && ./gradlew test assembleDebug lint          # build + 114 tests + lint
node android/scripts/gen-strings.mjs --check              # translations in sync
node android/tools/dev-desktop.mjs --host <LAN IP>        # fake desktop; prints a pairing link
adb install -r android/app/build/outputs/apk/debug/app-debug.apk   # local only
```
