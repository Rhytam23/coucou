# Stage C-chat: chat on the phone through the PC

Status: plan approved with the defaults below. **C1 done** (PC protocol core, fake provider tests). **C2 done** (real providers behind it, Settings switch and model checklist on the PC). **C3 done** (Android client: caps, chat messages, `ChatSession`, private history file, `dev-desktop.mjs --fake-chat`, Kotlin interop against the Node fake and the real Rust server). **C4 done** (Chat screen, Markdown-light, Clear, debug sample screen). The Chat tab is always in the bottom bar (no Home entry any more); when chat cannot be used the screen says why (not paired / not connected / chat off on the computer / no model allowed).

Decision by the user: the earlier "no chat on the phone" is reversed. The phone chats with the LLM
providers the PC is set up for. **The API key never leaves the PC**: the phone sends text, the PC calls
the provider, the PC streams the answer back. Protocol stays v1; old phones and old PCs are unaffected.

## 1. How the PC chat works today (audited)

- **Where it runs: Rust.** The chat view (`windows/src/views/chat.ts`) only draws. It calls the Tauri
  command `chat_send(query, context)` (`lib.rs:453`), which calls `chat::send` (`src-tauri/src/chat.rs`).
  Keys are read inside Rust from the OS credential store (`secrets::get`), never sent to the webview.
- **Providers** (`core/providers.ts` lists them for the UI, `chat.rs` routes):
  - Anthropic: `claude.rs` (web search tool is switched on, up to 5 uses per question).
  - OpenAI, Google AI, OpenRouter: `openai_compat.rs`.
  - Ollama, LM Studio, any OpenAI-compatible server: `local_chat.rs` (addresses and an optional key in settings).
  - Models list: `chat::models(settings, provider)` (only when the user opens the picker, only with a key).
  - Chosen model per provider: `settings.model` (Anthropic) and `settings.chat_models[provider]`.
- **Streaming: only local models.** Anthropic, OpenAI, Google and OpenRouter return the whole answer in one
  HTTP reply (90 s timeout, `max_tokens` 4096, body capped by `net::MAX_BODY`). `local_chat.rs` streams server-sent
  events and emits `chat-delta` to the island window. So a phone chat is streamed for local models and "all at
  once after a few seconds" for cloud providers, exactly as on the PC. Real cloud streaming would be a later, optional step.
- **History:** one conversation, in memory only (`Chat` in Rust, `State.chatHistory` in the webview), never
  written to disk; "New chat" resets it. Turns are kept in plain text so switching provider is safe.
- **Context** (a dropped file, the active window) rides along with the first question on the PC. The phone never sends it.
- **Errors** carry the provider's own message text (`net::error_detail`). Some providers echo part of a key
  in an auth error, so the phone must **never** receive that text (see Safety).

**What the phone link needs to call the same code.** `chat::send` needs a Tauri `AppHandle` only so
`local_chat` can emit `chat-delta`. Small refactors, behaviour unchanged for the island:
1. `local_chat::send` gets a sibling `send_with(..., on_delta)` taking a closure; `send` becomes a thin wrapper.
2. New `chat::send_for_phone(settings, chat, provider, model, text, on_delta)`: same routing as `chat::send`, no context, own `Chat` instance (the
   phone's conversation is separate from the island's; the PC chat is never touched).
3. The phone link server gets a `ChatBackend` trait object (like the existing `Host`), so tests use a fake and the real one is wired in `mod.rs`.

## 2. Protocol (additive, v1)

| Direction | Message | Fields |
|---|---|---|
| phone to PC | `hello` | new optional `caps: ["chat"]` |
| PC to phone | `welcome` | new optional `caps: ["chat"]`, present **only** when the PC toggle is on |
| phone to PC | `chatModels` | (none) ask for the allowed list |
| PC to phone | `chatModels` | `models: [{id: "provider/model", provider, label}]`, only what the PC allowed, never a key |
| phone to PC | `chatSend` | `id` (phone-made, <= 64 chars), `model` (an id from the list), `text` (<= 4000 chars) |
| PC to phone | `chatDelta` | `id`, `text` (appended; each line well under 64 KiB, pieces of at most 8 KiB) |
| PC to phone | `chatDone` | `id`, optional `text` (the full final answer, so the phone can replace what it assembled) |
| PC to phone | `chatError` | `id`, `reason` (fixed code), `message` (fixed English text written by us) |
| phone to PC | `chatCancel` | `id` |
| phone to PC | `chatReset` | (none) forget the PC side of this conversation ("New chat" / Clear) |

- `reason` codes: `off`, `not_allowed`, `busy`, `rate`, `too_long`, `no_key`, `unreachable`, `auth`, `provider`, `canceled`, `internal`.
  The provider's message is logged on the PC only (without any key) and is **never** forwarded.
- Compatibility: an old PC sends no `caps`, so the phone hides Chat. An old phone sends no `caps`, so the PC never sends a chat
  message to it and answers any `chat*` it still sends with nothing at all (unknown types are already ignored). Tested both ways.
- `caps` is the same handshake stage B will use for details; only the `chat` entry is defined here.

## 3. Safety

- **Own toggle** on the PC: Settings > Android phone > "Let the phone chat with my AI providers". **Off by default**, owned by
  Rust like `phoneLink` (the webview cannot flip it; `save_settings` keeps the Rust value). While it is off nothing is advertised and every `chat*` is refused with `off`.
- **The PC chooses what the phone may use:** a checklist of provider/model pairs (providers that have a key or a connected server; model
  lists come from `chat::models`, shown only when the user opens the list). Empty list = nothing allowed. A `chatSend` for a model not in the list is refused (`not_allowed`).
- **Limits** (constants, tested): text <= 4000 characters; one running request at a time (`busy`); at most 12 sends per 10 minutes for the
  link (`rate`); the providers' existing 90 s timeout, `max_tokens` and body caps stay; `chatCancel` drops the running request.
- **Never sent to the phone:** keys, other sessions' prompts or commands, file contents, the island's own chat, error text from providers.
  Never accepted from the phone: files, window context, a provider address or a key.
- **Storage:** the PC keeps the phone's conversation **in memory only** (separate `Chat`, dropped when the toggle goes off, the link
  stops, or the phone sends `chatReset`). Nothing new is written to disk on the PC. On the phone the history is a private file (no backup;
  `allowBackup` is already false) with a **Clear** button that also sends `chatReset`.
- **Screen lock:** not needed to chat (as asked); Allow/Deny still needs it, unchanged. Consequence to accept: anyone holding the unlocked,
  paired phone can use the allowed models, within the limits above. An optional "ask for the screen lock when the chat opens" setting is a cheap add if you want it.
- **UI note on the phone:** "Uses your computer's API key. Your computer sends your messages to <provider>." shown on the Chat screen
  and before the first message.

## 4. What is not possible (be honest)

- Away from the home Wi-Fi: the link is local-network only. It works over a VPN that puts the phone on the PC's network by itself, not otherwise. No relay.
- The PC must be on, awake and running Coucou with the phone link on; if it sleeps the chat stops (the phone shows "computer not reachable").
- No file or window context, no images, no voice, no PC-side chat history shown on the phone, and no web search choice (Anthropic's web search is part of
  the PC's Claude chat; it would run for the phone too, which costs more per question: the plan below asks you whether to turn it off for the phone).
- Cloud answers arrive whole, not word by word (as on the PC today). Local models stream.
- Money already being spent cannot be undone by Cancel; it only stops waiting.

## 5. Stages (each: Rust and Kotlin tests, CI green, commit id and adb steps)

- **C1. PC protocol core.** `caps` in hello/welcome, `ChatBackend` trait, limits (length, busy, rate), allow-list check, fixed error codes,
  per-link conversation, message handling in `server.rs`. Rust tests with a fake backend: both compatibility directions, every limit, no key or provider text in any outgoing line, cancel, toggle off mid-answer.
- **C2. PC real backend and settings UI.** `local_chat::send_with`, `chat::send_for_phone`, `Settings` fields `phoneChat` (enabled) and `phoneChatModels` (allow-list), Rust-owned; the Android phone section gets the toggle, the checklist and the "uses your key" note. Existing PC chat tests must stay green.
- **C3. Android client.** `Protocol`/`LinkClient` support (`caps`, chat messages), pure `ChatSession` logic (assemble deltas, reconcile with `chatDone.text`, errors to texts, cancel), local history store with Clear, `DemoLink` is untouched. `dev-desktop.mjs --fake-chat` (debug aid: scripted words, errors on keywords like `/error`, `/slow`, `/long`) and interop tests (Kotlin client against it and against the Rust server with a fake backend).
- **C4. Android UI.** A Chat screen like the PC chat panel (bubbles, typing dots, model chip above the box, Markdown-light replies, the key note), reached from the Home card/Settings only when the PC advertises `chat`; Clear; light/dark; font scale. Debug: `adb` receiver kinds to open the screen with sample messages.
- **C5 (optional, later).** Real streaming for cloud providers.
Stage B (details) is not started in this series.

## 6. Decisions (user: "go with your defaults")
Limits as proposed (4000 chars, one at a time, 12 per 10 min); web search stays as on the PC; no screen lock to open Chat; the PC keeps the phone's conversation in memory while the switch is on.

## 7. Questions that were asked

1. **Limits OK?** 4000 characters, one running request, 12 sends per 10 minutes. Say other numbers if you prefer.
2. **Web search for the phone's Claude chat:** allow as on the PC (default), or turn it off for the phone to keep costs predictable?
3. **Screen lock to open the Chat screen:** none (as you said), or an optional switch in Settings, off by default?
4. **Conversation lifetime on the PC:** kept in memory while the app runs and the toggle is on, so a Wi-Fi blip does not lose context (my proposal), or reset at every disconnect.
