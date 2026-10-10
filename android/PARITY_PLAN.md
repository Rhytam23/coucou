# Parity plan: Coucou for Android vs the PC island and the iPhone app

Status: approved by the user on 2026-10-09. Work stage by stage; the user tests each stage on the phone before the next starts.

## Context
The Android basics work on the user's Galaxy A12s: pairing over Wi-Fi, live sessions, the pill, Allow/Deny with biometrics. The user wants what the PC island shows and does to be on the phone too, adapted to a phone. This is step 1 only: an audit and an ordered plan, no feature code. 

Sources audited: `windows/src` and `src-tauri` (desktop), `docs/IPHONE.md` + `NotchBuddy/Sources/{Phone,Widgets,NotificationContent,CoucouKit}` (iPhone), `android/` + `docs/ANDROID_LINK.md` + `phone_link/*` (what exists).

## What the link carries today (protocol v1)
Phone to PC: `hello`, `decision` (allow|deny), `ping`, `bye`. PC to phone: `welcome`, `error`, `sessions[]` (`pillId, agent, state, statusText` = latest step only, `stepIndex, stepCount, updatedAt`; max 16), `approval` (`pillId, fingerprint, tool, command` max 500, `createdAt`), `approvalResolved`, `pong`. LAN only, pinned TLS, 64 KiB per line, one pending request at a time.

Compatibility facts (checked in `Protocol.kt` and `server.rs`): the phone ignores unknown message types and unknown keys on known messages, but drops a line if a required key is missing. The desktop ignores unknown client types and extra `hello` fields. So new optional fields and new message types are safe both ways. There is no capability negotiation, so each new feature is gated by one optional `caps` list in `hello` (what the phone understands) and one in `welcome` (what the PC has switched on). Version stays `v=1`. An old phone talking to a new PC, and a new phone talking to an old PC, both keep working because absent caps mean "nothing extra".

## Gap list (PC / iPhone has it, Android does not)

| # | Feature | PC | iPhone | Carried by link today? | What is needed | Phone? |
|---|---|---|---|---|---|---|
| 1 | Full step list of a session | yes (20 steps) | yes | no, last step only | optional `steps[]` (<=20 x 200 chars) in `sessions`, cap `details` | yes |
| 2 | Final answer line, project name | `finalLine`, `sessionCwd` | yes (full answer, cwd) | no | `finalLine` (<=200), `project` = folder name only, never the path | yes |
| 3 | Session detail screen | ticker, finished card | last turn, actions, files, answer | no | UI + #1/#2; the last-turn prompt/output is NOT sent (see "Not on the phone") | yes |
| 4 | Files changed + diff | diff card, +N -M, open in editor | per-file diffs, 600 lines max | only a file name inside `statusText` | file list with +/- counts under `details`; hunks only on request (`getDiff` then `diff`), separate toggle, caps 200 lines/file, 600 total, 400 chars/line, chunked under 64 KiB | yes, opt-in, off by default |
| 5 | Answer `AskUserQuestion` | question card, options, multi-select | notification buttons + app card | no, PC drops questions | `question{fingerprint,pillId,questions[]}` and `answer{fingerprint,picks}`, PC validates labels exactly like the iPhone; cap `answers` | yes, with screen-lock confirm |
| 6 | Mochi outfits (13) + seasonal Auto | wardrobe | none | no | port `outfits.ts` (1158 lines) and `wardrobe.ts` to Kotlin Canvas with numeric parity tests; PC sends `prefs{outfit}` (id only) | yes |
| 7 | Mochi touch (slap, dizzy, love, hover) and their sounds | yes | no | n/a | wire engine methods that exist but have no callers | yes |
| 8 | Sounds beyond 4 state sounds, sound on/off, volume | 29 sounds, mute, 0..0.2 | 4 notification sounds | n/a | play approve/blip/send/pop on phone actions; add switch + volume | yes |
| 9 | Settings screen | full | About + notification prefs | n/a | sound, volume, language, notify on finish/fail, quiet hours, pill switch, outfit | yes |
| 10 | Language choice | 10 languages | English only | n/a | **decided: the Android app is English only for now** (no picker); translations kept in the repo | no |
| 11 | Notifications for finished / failed / question | sounds + cards | local notifications, quiet hours, reply | no data gap | local notifications from state changes (not first picture), quiet hours | yes |
| 12 | Decision history, search | recap (counts) | decisions + turn archive + Spotlight | no data gap | decision history kept on the phone only; search later | yes, local |
| 13 | Per-pill colours | colour pickers | `color` per session | no | optional `color` hex in `sessions` | yes |
| 14 | Plan usage (Claude 5 h / weekly %, Codex weekly) | header pills + cards | no | no | tiny `usage` message (percent, reset time), cap `usage`, toggle | yes, opt-in |
| 15 | Services cards: Stripe, GitHub, Vercel, n8n, Resend, Notion, Cal.com | pill cards, polled with the PC's keys | Services tab | no | `services` snapshot (headline, reason, up to 3 items) built from data the PC already polls; per-service opt-in; read-only | yes, read-only, each off by default |
| 16 | Away from home Wi-Fi | n/a | works anywhere (iCloud) | LAN only | short term: let the user run a VPN such as Tailscale (desktop opt-in to accept 100.64.0.0/10 as "local") and document it. Long term: a relay like `relay/` is a separate decision for Louis | VPN + docs only (decided) |
| 17 | Widgets, Control Center-like entry | no | widgets, Live Activity, Control | no data gap | home-screen widget (RemoteViews, no new dependency), quick-settings tile, richer ongoing notification | yes, late |
| 18 | Send the next instruction | no | yes (GitHub build, own toggle) | no | would make the PC run `claude -p --resume` itself | **not planned** (decided) |

Already on par: states and 7 emotes, sounds file set, approval Allow/Deny with biometrics, quiet/heads-up routing, pill overlay.

## Not on the phone (privacy or safety)
- Chat with LLM providers: was excluded; **reversed by the user** (stage C-chat, see `android/CHAT_PLAN.md`): the phone chats through the PC, API keys stay on the PC.
- Dropped files, uploads, the inbox folder: local file contents.
- Hook/agent installers and any `~/.claude`-style config writes.
- The prompt text, command output and Claude's full answer: the iPhone sends them (encrypted in iCloud); here nothing leaves the PC except what is listed above, and each extra is a separate toggle, off by default, travelling only over the pinned TLS link.
- Full file paths (only the folder name), and any key or token.
- Anything that moves money or sends email: Stripe, Resend, Cal.com, Notion stay read-only; there are no Stripe/Resend actions at all. Services *actions* (re-run CI, merge, redeploy, pause workflow, as on the iPhone) are out of scope: the PC app has none today and adding them would make the PC write to third parties.
- "Always allow" from the phone (stays refused).
- Spotify controls, weekly recap share, shortcuts, tray, window attach: PC concepts.

## Proposed order (small first; one stage = one commit series, each with tests, CI green, a debug trigger and adb steps)
- **A. Phone-only polish, no protocol change. (done, being tested on the phone)**
- **A2. English only + the island at the top of the phone + Home like the PC Home panel. (done, to be tested)** Settings screen, sound switch/volume, language picker, tap-Mochi interactions and their sounds, finished/failed/question notifications with quiet hours, local decision history, update the stale HANDOFF numbers (docs say 69 tests, code has 94).
- **A3. UI/UX polish. (done, to be tested)** Settings gear on Home, disconnect/gallery/overlay/About moved into Settings, human status text instead of raw tool names (Home card and island), "Step n of m", equal pill columns, narrower island that grows out of the camera hole, one gutter/gap rhythm, light theme contrast checked by test.
- **B. Capabilities + session details. (done, to be tested)** `caps` handshake, `steps[]`, `finalLine`, `project`, `color`; session detail screen; PC Settings > Android phone gets one toggle per feature, all off. Rust + Kotlin interop tests, backward-compat tests both directions.
- **C. Answer questions from the phone. (done, to be tested)** Cap `answers`, PC switch `phoneAnswers` (off by default), `question` / `answer` messages, exact-label validation on the PC, screen-lock confirm on the phone, nothing logged or stored. Debug: `--es kind askquestion`; fake desktop: `dev-desktop.mjs --answers`.
- **D. Outfits and wardrobe. (done, to be tested)** The 11 outfits ported line by line (`mochi/outfit/`, compared call by call with the PC's TypeScript by `OutfitParityTest`), `prefs{outfit}` from the PC (cap `prefs`, no switch: it is no secret), Settings > Mochi's wardrobe with "Same as my computer" or a phone-only choice, the hero Mochi wears it. Debug: `--es kind outfit --es value beanie` / `--es local crown`.
- **E. File changes and diffs. (done, to be tested)** Cap `diffs`, PC switch `phoneDiffs` (off by default, its own toggle): the session list carries up to 20 file names with +/- counts; a file's lines (200 max, 400 characters a line, parts of 100) come only when the phone taps the file (`getDiff`/`diff`), through a round trip to the island. Phone: Home > the agent > "Files changed" > a sheet of the lines (colour and +/− marks). Debug: `--es kind diff` (`--es size big`); fake desktop `dev-desktop.mjs --diffs`.
- **F. Plan usage, then services cards** (read-only, per service).
- **G. Away from home:** VPN support + docs; relay only if you and Louis want it.
- **H. Widget, tile, richer ongoing notification.**
- **I. Send next instruction: not planned.**

## Decisions (answered by the user)
1. `project` = folder name only, never the path; sent when "details" is on.
2. Answering a question needs the screen-lock confirm, like the iPhone.
3. Stage G: VPN support plus docs only. No relay unless Louis and the user later decide on one.
4. Stage I (running instructions on the PC): **not planned**. Not to be implemented.
5. Order: A, B, C, D, E, F, G, H (outfits stay after the protocol work).

## Verification (applies to every stage)
`cd android && ./gradlew test assembleDebug lint`, `node android/scripts/gen-strings.mjs --check`, `cargo test -p coucou phone_link` and the interop test for desktop changes, Phone link CI green. Locally, kotlinc + JUnit for pure logic (Gradle is unavailable here). A debug-only receiver case per stage so the user can trigger it with adb without a real agent. New strings are English only for now (decision of stage A2). Honest note per stage of what was not seen on a device. Nothing published; no PR to upstream.
