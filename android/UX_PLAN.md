# Coucou for Android: full UI/UX redesign, "island first"

Status: **decided: Option A, with the defaults below (section 8).** **U0 (design system) is done**: tokens, icons, components and a Design screen (Settings > Mochi gallery > Design system); no existing screen uses them yet. **U1 (navigation shell + Settings) is done.** **U2 (Home) is done.** **U3 (approval and question sheets) is done.** **U4 (pairing and scan) is done.** **U5 (chat) is done.** **U6 (session detail and activity) is done.** U7 (the island) is next. The prototype was updated after the
user's note about agent colours (section 4.1). Prototype: `android/design/prototype.html` (open it in any browser; it is a phone-sized mock).

Sources read: `android/HANDOFF.md`, `android/PARITY_PLAN.md`, `windows/src/views/*` (chat, ticker, views,
recap...), `design/prototype/notch-buddy.html`, `design/captures/` (compact, overview, approval, question,
finished, search result, empty, no-notch), and the current Android screens (`ui/*.kt`, `MainActivity.kt`,
`core/Palette.kt`, `ui/Theme.kt`).

Fixed by the brief and by `PERMISSION.md` / `CLAUDE.md`: Louis's Mochi, colours and sounds stay as they are; no new
branding; the "unofficial port" notice stays one tap from the main screen; no ads, no analytics; never approve
without an explicit click (and Allow stays behind the lock); English only; pill IDs are never renamed.

---------------------------------------------------------------------------------------------------

## 1. What is wrong today

I checked each point you listed against the code, and added what the code review found.

| # | Problem | Cause in the code | Effect |
|---|---|---|---|
| 1 | The gear looks like a sun | `GearButton.kt` draws a circle with 8 spokes on a canvas | Reads as "brightness", not "settings"; also a lonely icon with no label |
| 2 | Raw command text on the card | `ApprovalCard` prints `r.tool` and `r.command` in monospace (up to 8 lines); the History repeats `tool: command` | Looks like a log, not a calm app; paths and flags leak onto the screen |
| 3 | Half-empty Home | Home is a `LazyColumn` of unrelated cards. With one agent there is one card and 70% black screen | No sense of "what is going on", nothing to do, nothing to look at |
| 4 | "Step X of Y" and a bar | `stepCount` is `steps.length` (how many steps the hook has *seen so far*, `phone-link.ts:58`), not a total | The bar jumps and never means "x% done": it is false information. It goes away |
| 5 | Odd slider thumb | Stock Material 3 `Slider` (big thumb, thick track, blue accent) | Does not match anything else on screen |
| 6 | The island is a bar under the camera | The overlay window is placed below the cut-out and drawn as a rounded rectangle | It does not look like it grows out of the hole. The PC island is attached to the screen edge with concave "ears" |
| 7 | The look is the website, not the island | `Theme.kt`: near-black page, bordered flat cards, Material blue accent, Material buttons and switches | Louis's island is black panels (`#141518`, radius 20) with a soft coloured glow at the bottom (the "wash"), white pill buttons, and no blue accent |
| 8 | Pairing card has 5 controls | `PairCard`: Scan, paste field, Pair, Paste from clipboard, demo | A first run should have one obvious action |
| 9 | Settings is one long list of 7 cards | `SettingsScreen` | Hard to scan; Disconnect (rare, destructive) sits in the first card |
| 10 | Navigation is "back buttons only" | `MainActivity`: an enum, `BackHandler`, text "Back" buttons | Chat and History are buried; no sense of where you are |
| 11 | Text sizes and weights vary by screen | `titleLarge`, `titleMedium`, `titleSmall`, `labelLarge`... picked per screen | No type scale |
| 12 | Approval buttons and links look like different apps | Filled blue `Button`, outlined `OutlinedButton`, `TextButton` | The PC has one rule: one white pill for the main action, grey pills for the rest |

What is good and stays: Mochi and its painter, the states, `ToolLabels` (plain sentences), the pinned link, the
biometric gate, the 600 ms tap guard, the dedupe and rate rules, the contrast tests in `Palette`.

---------------------------------------------------------------------------------------------------

## 2. Screen map and navigation

```
First run                       Paired (main)                              Over other apps
┌────────────┐   ┌──────────────────────────────────────────┐    ┌───────────────────────────┐
│ Pairing    │──▶│ Home  ·  Chat  ·  Settings   (bottom bar)│    │ Island (overlay window)   │
│ (welcome)  │   │                                          │    │  peek  ▸ working strip    │
│  Scan QR   │   │ Home ─ agent ─▶ Session detail           │    │  ▸ finished card (10 s)   │
│  Paste     │   │ Home ─ "Recent" ─▶ Activity (history)    │    │  ▸ question card          │
│  Demo      │   │ Settings ─▶ Activity, Computer, About    │    │  ▸ approval card (lock)   │
└────────────┘   │                                          │    └───────────────────────────┘
   ▲ Scan screen │ Over any tab: Approval sheet, Question   │
   └─ Confirm    │ sheet (rise from the bottom, modal)      │
                 └──────────────────────────────────────────┘
```

| Screen | Main job (one sentence) | Reached from |
|---|---|---|
| **Pairing** | Connect to a computer with one tap on "Scan QR code" | App start when not paired and not in demo |
| **Scan** | Point at the PC's QR and confirm "Pair with <name>?" | Pairing (already built, `ScanScreen.kt`, restyled only) |
| **Home** | "What are my agents doing right now, and does anything need me?" | Bottom bar; default |
| **Chat** | Ask the PC's AI providers a question; keys stay on the PC | Bottom bar (shown only when the PC offers chat) |
| **Settings** | Change how Coucou behaves; disconnect; About | Bottom bar |
| **Session detail** | See one agent's steps in plain words and its last message | Tap an agent on Home (details cap) |
| **Approval sheet** | Decide Allow or Deny, with the lock for Allow | A request arrives; the island; the notification |
| **Question sheet** | See the question (answering is stage C, not built yet) | A session enters `question` |
| **Activity (History)** | Review what I allowed or denied, kept only on the phone | Home "Recent", Settings |
| **Island over apps** | Glanceable state and the urgent decision without opening the app | System overlay, switch in Settings |
| **Mochi gallery** | Debug/fun: every state and emote | Settings, "More" (kept) |

Navigation rules:
- Three destinations in a bottom bar: Home, Chat, Settings. Chat disappears (two items) when the PC does not offer it, as today.
- The bar is a floating black pill, labels always visible, icons drawn in code (no icon library, as now), 48 dp targets.
- Back always goes up one level; on a tab root it exits. A sheet closes with Back, swipe down or its Close.
- The bar hides while the keyboard is open (Chat) and on Scan and Session detail (full-screen tasks).
- Approvals never wait in a list you have to find: the sheet opens over whatever you are doing (when the app is in front) and a red dot sits on Home.

---------------------------------------------------------------------------------------------------

## 3. Options for Home and navigation

### Option A: "Island panel + bottom bar" (recommended)
Home starts with a black panel hanging from the top edge of the screen, like the PC island grown to full width. The panel holds the hero:
Mochi big, the agent's name, one plain sentence ("Writing code in Korus"), a soft glow in the state's colour at the bottom of the panel.
Under it: other agents as full-width rows (colour dot, name, one sentence), an "Ask" bar when chat is available, and "Recent" (the last three
decisions in sentences). Bottom bar for Home, Chat, Settings.
- Pros: closest to the PC while using the phone's strengths (thumb reach at the bottom); one place for everything; fixes the empty look (the panel, the "Ask" bar and "Recent" fill a normal screen; with no agent the panel shows a sleeping Mochi and a clear next step).
- Cons: more custom drawing than stock Material; the bar costs ~72 dp of height; Chat and Settings are one extra tap from an approval on the phone's Home (the sheet solves approvals).

### Option B: "The PC clone": tabs inside the island
The whole app is one big black island panel with the PC's header (home, chat, + glyphs at the top, "4 running" and a sound toggle on the right), as in `03_overview.png`.
- Pros: maximum fidelity to Louis's design; very little new design work.
- Cons: the controls sit at the **top**, which is the hardest place to reach on a 6.5" phone; the PC's "+" and drag-and-drop have no meaning here; the header is cramped at 360 dp; Settings has no place in it (the PC's gear opens a window).

### Option C: "One screen and sheets"
There are no tabs. Home is the only screen, with a docked "Ask…" bar at the bottom; Chat, Settings, Activity and Session detail open as bottom sheets or full-screen pages from Home.
- Pros: the calmest and most "island" idea (everything grows out of one place); no bar to learn.
- Cons: Settings and Chat are hidden behind an avatar and a bar, so they are harder to discover; chat needs the full screen and the keyboard, which fights a sheet; more back-stack states, which are harder to test; TalkBack users lose a clear map.

**Recommendation: Option A.** It is the PC's look with the phone's ergonomics, it keeps the three jobs people actually come back for
(see agents, ask, settings) one tap apart, and it is the easiest to build in stages without breaking the others. The prototype has a switch to try all three on Home.

---------------------------------------------------------------------------------------------------

## 4. Visual system

All values are numbers a `Palette`/`Spacing` object can hold, so the existing contrast tests extend to them. Colours of Mochi, its states and the
pills are Louis's and unchanged.

### 4.1 Colours (dark is the main look; the island overlay is always black)
| Token | Dark | Light | Use |
|---|---|---|---|
| `bg` | `#000000` | `#F2F3F5` | Screen. True black on dark: it matches the island and the camera hole, so the island reads as part of the screen |
| `panel` | `#141518` | `#FFFFFF` | The island panels and cards (radius 20), border `rgba(255,255,255,.035)` dark, `#E3E5E9` light |
| `panel2` | `#1D1F23` | `#E9EBEF` | Rows inside a panel, chips, selected tab |
| `text` | `#F5F6F8` | `#16171B` | Primary |
| `textDim` | `#9398A1` | `#5F646D` | Secondary (as the PC `.sub`) |
| `textFaint` | `#6E737C` | `#7A808A` (large text only) | Hints, placeholders |
| `primaryButton` | `#F5F6F8` on `#0B0C0E` | `#16171B` on `#FFFFFF` | The one main action (white pill, as the PC) |
| `secondaryButton` | `rgba(255,255,255,.09)` | `#E3E5E9` | Everything else |
| `danger text` | `#FF8D97` | `#C62828` | Deny/Disconnect labels |
| `online` | `#34D399` | `#0F9F6E` | Connected |

State colours (Louis's `MochiConst`, used for the dot, the glow ("wash") and the row accent):
idle `#E6E9EE`, working `#3B9EFF`, thinking `#8B5CF6`, searching `#6366F2`, approval `#F5A524`, question `#22D3EE`,
error `#F4505E`, finished `#34D399`, rate limit `#FB923C`, sleeping `#94A2B8`, dizzy `#F472B6`.
- **Wash**: a radial gradient from the bottom edge of a panel, the state colour at 40-55% alpha fading to nothing by 70% of the height (PC `.wash`). Dark only; on light it is 18% and the panel stays white.
- **No blue accent.** The Material blue accent goes: actions are white pills, links are white with a thin underline, focus rings are `#2F6BFF` only for keyboard/TalkBack focus (as the PC).
- **Every agent keeps its own colour everywhere, the main (hero) Mochi included.** The colour is the agent's pill colour: the one the
  user chose on the computer (`color` in the details cap), else the pill catalog's. It tints the Mochi's body (the engine already does this
  for the small ones through `bodyColor`), the row dot and the project chip. The state still shows through the badge, the eyes and the
  wash, which keep the state colours. Tapping another agent makes it the main one and its Mochi keeps its colour, so a coloured Mochi never
  turns white on the way to the main card. Today the hero Mochi has no `bodyColor`, which is the bug this fixes (stage U2). One honest limit:
  the catalog's colour for some agents (for example Claude Code in VS Code, `#F5F6F8`) is near white, so until the user picks a colour on the
  computer that agent looks white; we use the colour as given and do not invent one.
- Every text/background pair is checked by `Palette.contrast` (>= 4.5:1 for text, >= 3:1 for large text and icons), in both themes.

### 4.2 Type scale (system font, as the PC uses the system font)
| Role | Size / line | Weight | Use |
|---|---|---|---|
| Display | 28 / 34 | 700 | Pairing headline |
| Title | 20 / 26 | 700 | Screen titles, hero agent name |
| Headline | 16 / 22 | 600 | Card titles, the hero sentence |
| Body | 15 / 22 | 400 | Normal text, chat |
| Secondary | 13 / 18 | 400 | Dim lines, hints |
| Label | 12 / 16 | 600 | Chips, bar labels, section headings (sentence case, no all caps) |
| Mono | 12 / 18 | 400 | Only in the "exact command" disclosure and code in chat |
Sizes are `sp`, so font scale works; layouts are tested at 1.3x and 2.0x (rows wrap, nothing is cut by a fixed height).

### 4.3 Spacing and shape
- Spacing scale: 4, 8, 12, 16, 24, 32. Screen gutter 16. Gap between cards 12. Inside a card 16.
- Radii: panel 20 (the PC's card), island bottom corners 22 expanded / 14 compact (already in `IslandGeometry`), chips and buttons full pill, inputs 14, bottom sheet top corners 28, bottom bar full pill.
- Minimum touch target 48 x 48 dp everywhere (the bar items, the toggles, the rows).
- Elevation: none. Depth comes from the border and the wash, never from shadows (the PC has none).

### 4.4 Components (all drawn from the tokens above)
Panel, row, section heading, white/grey/danger pill buttons, chip, switch (a 52 x 30 pill; thumb white; on = state-neutral `#34D399`, off = `panel2`),
**slider** (thin 4 dp track, 20 dp white round thumb with a 1 dp ring, value label; replaces the odd stock one), time-of-day chips, segmented control,
bottom bar, bottom sheet, dialog (black panel, same pills), shimmer text for "doing now" (the PC ticker's shimmer), typing dots, list row with a colour dot.

### 4.5 Motion (the PC's, already ported in `core/IslandMotion.kt`)
- Growing: spring, response 0.5 s, damping 0.72. Shrinking and retract: 340 ms, `cubic-bezier(0.45, 0, 0.2, 1)`.
- Panels and sheets enter with the grow spring; content cross-fades with a small blur-to-sharp (16 ms ease then 300 ms), as `.view` on the PC. Compose has no cheap blur on API 30: use alpha + scale .97 to 1 only.
- Mochi keeps its own animation. Nothing else animates continuously (0% CPU rule when hidden; shimmer only while a row is "current" and the screen is on).
- Reduced motion (the system "remove animations" setting): springs become a 120 ms fade, no shimmer.

### 4.6 Light and dark
Follows the system. Light is a calm "paper" version (white panels on `#F2F3F5`, state wash at 18%, the white pill button inverts to dark). The island overlay is black in both (it is a hole extension).
Contrast for every pair is unit tested (as `PaletteTest` does today).

### 4.7 Words (no raw commands or paths)
- Sentences, not tool names: `ToolLabels` stays and grows ("Running a command", "Editing a file", "Reading project files", "Searching the web").
- Never a path on screen: a file shows as its name only, a project as its folder name (already the rule).
- Approval: the sentence says what kind of action it is and who asked ("Claude Code wants to run a command in Korus"). The exact text is behind **"Show exact command"** (see question 3). The lock prompt repeats the exact command, as today, so Allow is never blind.

---------------------------------------------------------------------------------------------------

## 5. Screen designs (what the prototype shows)

1. **Pairing**: sleeping Mochi, "Connect to your computer", one white pill "Scan QR code"; grey pills "Paste the link" (opens a small sheet with the field) and "Try the demo". Two sentences of help, no field on screen.
2. **Home**: the island panel (hero, wash, one plain sentence, a "Details" chip when the PC sent details), agent rows, "Ask" bar (chat), "Recent" (3 lines), link status in the panel's top row (dot + "Connected to Korus-PC"). Empty state: sleeping Mochi, "Nothing is running", "Start an agent on your computer and it shows up here."
3. **Chat**: black bubbles for the user (panel2) and plain text for replies (as the PC), typing dots, model chip above the box, the "uses your computer's API key" note once, Clear in the top row.
4. **Settings**: top card "Your computer" (name, status, **Disconnect** in danger text at the bottom of the card), then "Island" (switch + one line), "Sound" (switch + the new slider), "Notices" (finished/failed switch, quiet hours with from/to chips), "Activity" (opens history), "More" (Mochi gallery), "About" (the required notice and Louis's repo link, always visible at the bottom).
5. **Session detail**: hero panel with the agent colour wash, project folder chip, the last message card (green wash when finished), then the steps as the PC ticker: done steps dim with a check, the current one bright with shimmer. No progress bar, no "x of y".
6. **Approval sheet**: amber wash, Mochi with the "!" badge, "Claude Code wants to run a command", a one-line plain summary, **Deny** (grey pill) and **Allow** (white pill with a small lock), the 120 s countdown as a thin line at the bottom (as the PC `.cd`), "Show exact command" disclosure.
7. **Question sheet**: cyan wash, the question in large type, "Answer it on your computer" (answering from the phone is stage C).
8. **Activity**: day sections, a coloured dot (green allowed, red denied), "You allowed Claude Code to run a command", the time. Clear is one grey pill at the bottom.
9. **Island over apps**: compact "peek" = a black shape attached to the very top of the screen, merged with the camera hole, Mochi on the left of the hole and a dot on the right; "working" = the same shape grown a little wider with a one-line sentence; "finished" and "question" = a card that grows down from the hole, held 10 s; "approval" = the larger card with Deny / Allow.

---------------------------------------------------------------------------------------------------

## 6. Staged build order

One screen per stage; each stage ends with tests, the "Phone link" CI green, a debug path (the existing `DebugPillReceiver`) and a note of what was not seen on a device. No stage changes the link protocol or the PC.

| Stage | Screen / piece | What changes | Debug path |
|---|---|---|---|
| **U0** | Design system | `Palette` new tokens (dark/light), `Type`, `Spacing`, `Shapes`, components: panel, pills, switch, slider, chip, sheet, bottom bar skeleton, drawn icons (home, chat, sliders, check, lock). A hidden "Design" screen from the Mochi gallery lists every component. Contrast tests extended | Gallery screen |
| **U1** | Navigation shell + Settings | Bottom bar, back stack rules, Settings regrouped, new switch and slider, **gear removed** (the bar item replaces it), Disconnect moved down | n/a |
| **U2** | Home | Island panel hero, agent rows, "Ask" bar, "Recent", empty state, step bar removed | `kind working / finished / clear` |
| **U3** | Approval + Question sheets | New sheet, plain sentence, "Show exact command", lock kept, tap guard kept, countdown line | `kind approval / question` |
| **U4** | Pairing + Scan restyle | One-action welcome, "Paste the link" sheet; Scan and Confirm re-skinned only | `kind scan` |
| **U5** | Chat | Bubbles, typing dots, model chip, composer above the bar | `kind chat` |
| **U6** | Session detail + Activity | Ticker-style steps, final-message card, plain history lines | `kind details` |
| **U7** | Island over apps | Edge-attached shape with concave ears, hole-aligned compact/working/card sizes, black always | all island kinds |
| **U8** | Polish | Font scale 1.3x/2.0x, TalkBack labels and order, reduced motion, light theme pass, tablet/landscape sanity | n/a |

I would do U0, then U1 to U3 first (the screens you look at most), then the rest.

### What could break existing behaviour or tests
- **Source-guard tests** read Kotlin files and assert text: `ScanScreenTest` (the scan button is on the pairing card in `MainActivity.kt`, camera asked only in `ScanScreen`, link asks before pairing), `ChatScreenTest`, `SessionScreenTest` (card is clickable, `R.string.session_hint`), `ScreenLayoutTest` (title, equal columns, gutter), `EnglishOnlyTest` (Settings has no language picker). They must be moved and rewritten **in the same commit** as each screen, and I will keep each guard's intent (not delete it).
- **Pure logic tests stay untouched**: `HomePanel`, `Summary`, `ToolLabels`, `IslandPlan/Timeline/Motion/Geometry`, `ChatSession`, `PairingScan`, `QrDecoder`, `Palette`. Removing "Step n of m" removes `Summary.stepNumber/progress` usage from the UI only; I will keep the functions and tests until the detail screen no longer needs them, then delete both together.
- **Strings**: new strings go through `i18n/app-strings.json` and `gen-strings.mjs --check` (English only). Removing `step_of` and unused strings must keep the generated `values/strings.xml` in sync. Lint treats unused resources as warnings: check it stays clean.
- **Safety behaviour to keep**: Allow behind the lock, 600 ms guard against accidental taps, one biometric prompt at a time (`confirming`), approval expiry at 120 s, Deny needs no lock, a link or scan always asks to confirm. Hiding the command (question 3) must never remove it from the lock prompt.
- **Overlay**: `IslandOverlay.kt` is a WindowManager window with `FLAG_LAYOUT_NO_LIMITS`; changing its shape and insets (U7) is the riskiest piece: cut-out geometry differs per phone. Unit-testable (`IslandGeometry`), but the look can only be judged on the A12s.
- **Compose-only code compiles only in CI** (no local Gradle): each stage is a CI round trip; I keep stages small for that reason.
- **Accessibility**: the bar and the custom controls need explicit roles and labels (a custom switch loses Material's semantics unless I add `Role.Switch` and state).
- **Performance**: the wash is a `drawBehind` gradient (cheap); no blur; no continuous animation except Mochi and the "current" shimmer.
- **No new dependencies planned** (no icon library, no navigation library): icons are drawn in code, navigation stays a small state holder with tests.

---------------------------------------------------------------------------------------------------

## 7. What the prototype is, and is not

`android/design/prototype.html` is one self-contained file (no network, no libraries). It mocks a 360 x 800 dp phone (the A12s at 720 x 1600) with its centred waterdrop camera hole, in dark and light, and lets you tap through: Pairing, Home (three options), Chat, Settings, Session detail, Approval, Question, Activity, and the island over another app in each state.
- **Mochi is a simplified stand-in** (an SVG blob in the right state colours), not Louis's painter: the real one is drawn by the engine and is not touched by this plan.
- Text, spacing and colours are the real proposal; real fonts will be the phone's system font (the mock uses the browser's).
- Nothing in it talks to a computer; the data is invented sample data ("Korus", "SBE Hub", "Morning Brief" are the names from Louis's own captures).
- It has not been seen on a real phone, and the real island's cut-out behaviour differs per device.

## 8. Decisions (answered by the user: "Option A, go with your defaults")
1. **Navigation and Home: Option A** (island panel at the top, agent rows, "Ask" bar, "Recent", floating bottom bar Home / Chat / Settings).
2. **Look:** true-black dark with `#141518` panels, and the "paper" light theme, both from the start.
3. **Approval:** the exact command is hidden behind "Show exact command"; a plain sentence says what kind of action it is; the lock prompt
   always shows the exact command, so Allow is never blind.
4. **Order:** U0 (design system), then U1 to U3, then the rest.
5. **Island (U7):** keep the position logic, change only the shape; no always-visible "peek" for now.
6. **Chat tab:** hidden when the computer does not offer chat.
7. **Agent colours** (user's note): each agent keeps its own colour on the main card too (section 4.1); tapping an agent makes it the main one.

No release, no upload, nothing sent to upstream. Each stage ends with tests, CI green and what was not seen on a device.
