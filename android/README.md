# Coucou for Android (unofficial)

An unofficial Android port based on [Coucou](https://github.com/Louis-CFM/coucou) by Louis Raillé.
It shows your coding agents as Mochi on your phone and lets you approve or deny their permission
requests from a notification.

The name, the Mochi character, the icon and the sounds are © Louis Raillé and used with permission
(see [PERMISSION.md](PERMISSION.md)). The code is under the MIT License. The app is free: no ads,
no billing, no analytics.

## Status

| Part | State |
|---|---|
| Mochi (Compose Canvas port of `windows/src/mochi/engine.ts`) | states, emotes, hands, particles, badges, mailbox morph. **Outfits not ported yet** |
| Sounds, icon, 10 languages | done |
| Demo mode (no desktop needed) | done |
| Pairing (QR via the camera app, or paste), TLS pinning, secure storage | done |
| Sessions list, approvals, biometric gate, notifications, background service | done, tested with unit tests; **not yet tried on a real phone** |
| Desktop side in the Windows/Linux app | written (`windows/src-tauri/src/phone_link`), off by default, tested; **not yet tried on a real phone**. Waiting for Louis's decision |
| Widgets, instructions by voice, services tab, FCM relay | not started |

Not published anywhere. Louis must approve the build and the store listing first.

## Build

Needs JDK 17 and the Android SDK (platform 35). From this folder:

```bash
./gradlew test assembleDebug lint
node scripts/gen-strings.mjs --check      # translations are generated, see i18n/
```

Try it without a desktop: open the app and tap *Try demo mode*. Or run the stand-in desktop:

```bash
node tools/dev-desktop.mjs        # prints a pairing link to paste in the app
```

## The pill over other apps (optional)

Like the notch on the computer: a small black pill drops from the top of the screen when an agent
finishes, fails, asks something or is rate limited, then slides away; a permission request stays as a
card with Deny / Allow until answered (Allow opens the app for the fingerprint check).

- Off by default. The switch saves the user's choice **at the tap**, then opens Android's "Display over
  other apps" screen. The permission is read live, so granting it later from Android's settings is enough.
- It shows only on a *change* into those states (not for sessions already finished when the phone
  reconnects) and only while Coucou is not on screen. The window exists only while something is shown.
- Try it without an agent (**debug builds only**, not in release). With the switch on and the app in the
  background (press Home first):

```bash
adb shell am broadcast -n com.coucou.android/.app.DebugPillReceiver --es kind finished
# kind = finished | error | question | ratelimit | approval
adb logcat -s CoucouOverlay CoucouLaunch   # why the pill stayed away / which announcement was chosen / every start of the app and why
# add --ez foreground true to an approval to see the heads-up notification instead of the pill
```

One announcement at a time: while the pill shows a request, its notification is posted quietly (channel
"Approvals (quiet)", shade only); when the pill cannot show, the heads-up notification is used. Taps in the
first 600 ms on the pill are ignored (they were meant for the app underneath). Deny acts without opening the
app; only Allow (biometric) and a tap on the pill's header open it.

## Layout

- `app/src/main/kotlin/.../mochi` the engine (pure Kotlin, unit-tested) and the Compose painter
- `.../link` protocol, pairing, TLS client, demo link (see `docs/ANDROID_LINK.md`)
- `.../app` model, notifications, service, biometric gate, secure storage
- `i18n/` Android-only strings; the rest come from the desktop catalog
- Tests compare pill IDs, colours, tints, eyes and sounds with the desktop sources, so a drift fails the build
