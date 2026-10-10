# Coucou for Android (unofficial)

An unofficial Android port based on [Coucou](https://github.com/Louis-CFM/coucou) by Louis Raillé. It shows your coding
agents as Mochi on your phone and lets you approve or deny their permission requests, answer their questions and chat
through your computer's AI providers.

The name, the Mochi character, the icon and the sounds are © Louis Raillé and used with permission (see
[PERMISSION.md](PERMISSION.md)). The code is under the MIT License. The app is free: no ads, no billing, no analytics.
It is English only and dark only. Not published anywhere: Louis must approve the build and the store listing first.

## Build and test

Needs JDK 17 and the Android SDK (platform 35). From this folder:

```bash
./gradlew test assembleDebug lint
node scripts/gen-strings.mjs --check      # res/values/strings.xml is generated from i18n/app-strings.json
```

Without a computer: open the app and tap *Try the demo*. Or run the pretend computer (fake sessions, a fake approval; a test tool):

```bash
node tools/dev-desktop.mjs --host <LAN IP>     # prints a pairing link; flags: --details --answers --diffs --usage --services --fake-chat
```

The desktop side is in `windows/src-tauri/src/phone_link`; the protocol is `docs/ANDROID_LINK.md`, the internet relay
`docs/RELAY_LINK.md` (deploy and test it with `docs/RELAY_DEPLOY.md`).

## What it does

- **Home**: the focused agent as a black hero panel (Mochi in the agent's colour, a plain sentence), the other agents below, plan
  usage and service cards when the computer offers them, and *Recent* (your own Allow/Deny decisions, kept only on the phone).
  Raw tool names become plain words (`core/ToolLabels.kt`); a command or path is never shown without a deliberate tap (`core/SafeText.kt`).
- **Requests**: a sheet rises over any screen. Allow needs the fingerprint or screen lock, Deny does not, taps in the first 600 ms
  are ignored, and each request expires after 120 s. Questions from Claude Code can be answered the same way (the computer must allow it).
- **Island over other apps** (optional, off by default): a black island from the camera cut-out drops while an agent works, shows
  the result for 10 s and goes back up with the computer's motion. Needs Android's "Display over other apps". Rules are pure and
  tested (`core/Island*.kt`); `ui/IslandOverlay.kt` draws.
- **Chat** (a tab, when the computer offers it), **session details**, **file changes** and **plan usage** are opt-in switches on the
  computer (Settings > Android phone), all off by default; the phone is told only what it asks for and the computer allows.
- **Pairing**: scan the QR code with the in-app camera (CameraX + zxing, offline) or paste the link; a scan or a `coucou://pair`
  link always asks to confirm. The certificate is pinned. If the computer's address changes the phone finds it again by itself (mDNS,
  checked against the pinned certificate before the token is sent), or you can type the address.
- **Away from home Wi-Fi** (optional): through your own Cloudflare relay, end-to-end encrypted; the direct link is always tried first.
- **Widget, quick-settings tile, ongoing notification**: agent names and state words only. Updated when something changes, never on a timer.
- Mochi and his 11 outfits, sounds, wardrobe (Settings), a tap on Mochi slaps him, a long press pets him.

## Security rules the tests guard

Certificate pinning and the pairing token; approval fingerprints, 120 s expiry and decide-once; the biometric gate for Allow;
`MainActivity` ignores intent extras (only the non-exported `LaunchActivity` can ask for the Allow prompt); `FLAG_SECURE` on pairing,
chat, approval and question screens; secrets only in the Android Keystore blob; no logging of tokens, answers or relay keys; no ad,
billing or analytics library.

## Layout

- `app/src/main/kotlin/.../mochi` the engine (pure Kotlin) and the Compose painter; `mochi/outfit` the outfits (ported line by line)
- `.../link` protocol, pairing, TLS client, WebSocket client, relay, transport choice, demo link
- `.../core` pure rules (island, navigation, safe text, home text, glance); `.../ui` Compose screens; `.../app` model, notifications, service, storage
- `relay/` the relay (Cloudflare Worker + Node twin); `tools/` the pretend computer and the outfit trace; `i18n/` the English strings
- Tests compare pill IDs, colours, outfits and sounds with the desktop sources, so a drift fails the build
