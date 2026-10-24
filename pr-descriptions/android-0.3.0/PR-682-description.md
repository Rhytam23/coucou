Coucou for Android 0.3.0 (`versionName` 0.3.0, `versionCode` 3; the tag is yours). It brings together what was planned as 0.3.1 to 0.3.7 into one release. It changes `android/`, **and a small part of the Windows/Linux app** (the weekly recap, opt-in), plus the docs and the CHANGELOG, all listed below.

## Android (`android/`, 145 files)
**Link and requests**
- Home names the likely cause when the link is lost (computer closed, other network, phone link off).
- Settings > Permission requests: a choice of vibration (default, Short, Off; each has its own channel) and "Don't wake the screen for a request" (default off).
- Predictive back, with a guard test for every screen and sheet. The ongoing notification is posted only when its text changes.
- Steps show "A file" for a file name; Mochi Big's state line may take two lines.
- The Away from home Wi-Fi section is shown only when the pairing carries a relay (fixes #629, Fixes Rhytam23/coucou#18).

**Accessibility and look**
- Reduce motion (system default or a Settings choice), TalkBack states in words, large text, 48 dp touch targets.
- Light theme (System, Light, Dark) and the phone's own colours (Android 12+).

**Languages**: French, Spanish, Brazilian Portuguese, Simplified Chinese, Hindi, Bengali, Russian, Indonesian, Arabic (right to left), with the language picker. The strings to check are listed in `android/i18n/REVIEW.md` (not reviewed by native speakers yet).

**Quick actions**: launcher shortcuts (Chat, Recent decisions, Pair again), the Team widget (4x2), Share to Coucou (the text goes to the chat box and is never sent).

**Recap and history**: the weekly recap card on Home (optional, off by default); search and export of recent decisions (labels only, through the share sheet); a per-agent mute for finished, failed and limit notices (requests and questions are never muted).

**Also**: the GitLab and Gitea pills in Android's catalog (same IDs and colours as the desktop); `android/tools/dev-desktop.mjs` has a `--recap` option for trying the recap card with fake numbers; a few unused symbols removed; `android/i18n/REVIEW.md` regenerated.

## Windows and Linux (14 files): the weekly recap, opt-in
The recap capability of the phone link, described in `docs/ANDROID_LINK.md` ("Weekly recap"):
- `windows/src-tauri/src/phone_link/` (`hub.rs`, `mod.rs`, `server.rs`, `interop.rs`, `tests.rs`), `settings.rs`, `lib.rs`: the optional `recap` capability, off by default behind its own switch, sent only to a paired phone over the local network.
- `windows/src/recap/phone.ts`, `windows/src/settings/phone.ts`, `windows/src/island/phone-link.ts`, `windows/src/core/bridge.ts`: the numbers of last week and the Settings switch "Show my weekly recap on the phone".
- `windows/src/i18n/extra.json`: the two new strings in every language (German and Turkish included).
- `windows/tests/phone-link.test.mjs`, `windows/tests/phone-recap.test.mjs`: tests.
- Counts only: no project, file name, command or prompt. The project's folder name is added only for a phone that also has session details, and only if the user did not hide projects.
- No telemetry, no new network path: it uses the existing link to the paired phone.

## Docs and CHANGELOG
- `docs/ANDROID_LINK.md`: the recap capability.
- `docs/android.html`: the Android page.
- `CHANGELOG.md`: an "Unreleased" entry for Android 0.3.0. At release it becomes a dated section and gets its row in the README Versions table.

## Rules kept
Allow still needs the fingerprint or screen lock; approvals are never muted or hidden by any setting; notifications show labels only; no new dependency, no new permission; no telemetry.

## Checked
- Merges cleanly with current `main`. All the checks are green.
- Locally: lint and `assembleDebug` pass; `npm test` in `windows/` passes (438).
- A few unit tests run Node scripts and fail only on my Windows PC (the two outfit parity tests and the strings generator check, sometimes the translations check); they pass in CI.
- `RelayNodeTwinTest` (a test with a real relay process) timed out once on a CI runner and passed on the retrigger.
- Tried on a Galaxy A12s (Android 13) against the test desktop (`dev-desktop.mjs`), including Allow with a fingerprint. Not yet shown with a real Windows or Linux Coucou and real Claude Code sessions.
