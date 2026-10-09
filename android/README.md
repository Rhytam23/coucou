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
| Sounds, icon, English only for now (the other languages are kept in the repo, off in the build) | done |
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

## The island over other apps (optional)

Like the notch on the computer: a black island hangs from the top of the screen, centred on the camera
cut-out, over whatever app is open. It drops while an agent works (small strip: Mochi, agent, status), shows
how it ended (finished, error, rate limit) for **10 seconds**, then goes back up into the notch with the PC's
motion (a spring when it grows, a 340 ms curve when it shrinks: `windows/src/core/anim.ts`). A permission
request stays, expanded, until it is answered (Deny works there; Allow opens the app for the fingerprint
check); a question stays until the agent moves on. A new session starting while it goes up opens it again.
Tapping it opens Coucou.

- Off by default. The switch saves the user's choice **at the tap**, then opens Android's "Display over
  other apps" screen. The permission is read live, so granting it later from Android's settings is enough.
- Never while Coucou itself is on screen. "When an agent finishes or fails" and quiet hours govern the
  finished / error / rate-limit part; working, questions and requests need only the switch.
- The window and everything with it (frame loop, timer) exist only while the island is shown: one timer for
  the next deadline at most, none while it is hidden. Rules live in `core/IslandTimeline.kt`,
  `IslandPlan.kt`, `IslandGeometry.kt`, `IslandMotion.kt` (pure Kotlin, unit-tested); `ui/IslandOverlay.kt` draws.
- Taps in the first 600 ms are ignored (they were meant for the app underneath).
- Try it without an agent (**debug builds only**). Switch on, press Home first:

```bash
adb shell am broadcast -n com.coucou.android/.app.DebugPillReceiver --es kind working    # opens and stays
adb shell am broadcast -n com.coucou.android/.app.DebugPillReceiver --es kind finished   # result, then up after 10 s
adb shell am broadcast -n com.coucou.android/.app.DebugPillReceiver --es kind clear      # goes up now
# kind = working | finished | error | question | ratelimit | approval | clear | history
adb logcat -s CoucouIsland CoucouOverlay CoucouLaunch
# add --ez foreground true to an approval to see the heads-up notification instead of the island
```

One announcement at a time: while the island shows a request, its notification is posted quietly (channel
"Approvals (quiet)", shade only); when the island cannot show, the heads-up notification is used.
Deny acts without opening the app; only Allow (biometric) and a tap on the island open it.

## Settings, notices and history

Home > Settings: Mochi's sounds (switch, volume 0..0.2 as on the computer), "when an agent finishes or fails"
(a quiet notice in the shade, plus the pill if it is on), quiet hours (no sound and no pill for finished or
failed agents; requests and questions always come through), and History (your Allow/Deny decisions, only on the phone).
A tap on Mochi slaps him (three quick ones make him dizzy), a long press pets him.

Home looks like the PC's Home panel: a card for the agent that matters most (big Mochi, name and kind, a status
line with a coloured dot, a small link) and the other agents as pills with their own little Mochi; tap a pill to
put it in the card.

```bash
adb shell am broadcast -n com.coucou.android/.app.DebugPillReceiver --es kind history     # sample decisions (debug builds)
adb shell am broadcast -n com.coucou.android/.app.DebugPillReceiver --es kind finished    # with the app in the background: a notice in the shade
```

## Layout

- `app/src/main/kotlin/.../mochi` the engine (pure Kotlin, unit-tested) and the Compose painter
- `.../link` protocol, pairing, TLS client, demo link (see `docs/ANDROID_LINK.md`)
- `.../app` model, notifications, service, biometric gate, secure storage
- `i18n/` Android-only strings; the rest come from the desktop catalog
- Tests compare pill IDs, colours, tints, eyes and sounds with the desktop sources, so a drift fails the build
