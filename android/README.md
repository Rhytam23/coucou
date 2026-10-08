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
| Desktop side in the Windows/Linux app | **not written** (use `tools/dev-desktop.mjs` meanwhile) |
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

## Layout

- `app/src/main/kotlin/.../mochi` the engine (pure Kotlin, unit-tested) and the Compose painter
- `.../link` protocol, pairing, TLS client, demo link (see `docs/ANDROID_LINK.md`)
- `.../app` model, notifications, service, biometric gate, secure storage
- `i18n/` Android-only strings; the rest come from the desktop catalog
- Tests compare pill IDs, colours, tints, eyes and sounds with the desktop sources, so a drift fails the build
