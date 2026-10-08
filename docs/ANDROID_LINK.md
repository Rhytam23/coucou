# Android link (protocol v1)

How *Coucou for Android* talks to a desktop Coucou. The iPhone uses iCloud (CloudKit) and Apple
push, neither of which exists on Android, so the Android app connects to the desktop directly over
the local network.

Status: the Android client and `android/tools/dev-desktop.mjs` (a stand-in desktop for testing)
implement this. The real desktop server in the Windows/Linux app is **not written yet**; the Mac
app is left to its author.

## Transport

- TCP, then TLS 1.2 or 1.3. The desktop uses a self-signed certificate it generates once.
- Newline-delimited JSON (one object per line, UTF-8). A line over 64 KiB is a protocol error.
- No hostname check and no certificate authority: the phone accepts exactly one certificate, the
  one whose SHA-256 it got when pairing (certificate pinning).
- Default port 47821 (any port works; it is in the pairing link).

## Pairing

The desktop shows a QR code (and a copyable link) containing:

```
coucou://pair?v=1&host=192.168.1.20&port=47821&fp=<sha256 hex of the TLS certificate>&token=<secret>&name=<desktop name>
```

- `token` is 16 to 128 characters of `[A-Za-z0-9_-]`, random, created per pairing. It is the shared
  secret; it travels only inside the pinned TLS connection and is stored on the phone encrypted
  with a key from the Android Keystore.
- Scanning the QR with the phone camera opens the link in the app (the app registers the
  `coucou://pair` scheme). The link can also be pasted.
- Unpairing on either side deletes the token. The desktop should accept one token per phone and let
  the user revoke it.

## Messages

Phone to desktop:

| `type` | fields | meaning |
|---|---|---|
| `hello` | `v`, `token`, `device` | first message; must match the pairing |
| `decision` | `fingerprint`, `decision` (`allow` or `deny`) | answer to an approval. No "always" from the phone |
| `ping` | | keep-alive, every 20 s |
| `bye` | | courtesy before closing; may be lost |

Desktop to phone:

| `type` | fields | meaning |
|---|---|---|
| `welcome` | `v`, `desktop`, `os` | reply to a valid `hello`. The phone refuses a different `v` |
| `error` | `code`, `message` | `auth` closes the link and the phone stops retrying quickly |
| `sessions` | `sessions[]` | the full list, replacing the previous one |
| `approval` | `pillId`, `fingerprint`, `tool`, `command`, `createdAt` (ms) | an agent waits for permission |
| `approvalResolved` | `fingerprint` | answered elsewhere or timed out; remove it |
| `pong` | | reply to `ping` |

A session is `{pillId, agent, state, statusText, stepIndex, stepCount, updatedAt}`. `state` is one of
`idle working thinking searching approval question error finished ratelimit sleeping dizzy`; an
unknown value is shown as `idle`. `pillId` values come from the pill catalog
(`windows/src/core/pills.ts`) and are never renamed. Unknown message types are ignored.

## Approvals

- `fingerprint` is the Mac's derivation (`ApprovalRelay.fingerprint`): lowercase hex SHA-256 of
  `pillId`, `sessionId`, `tool`, `command`, `inputKey` joined by U+001F.
- The desktop applies a `decision` only if its fingerprint matches the approval **still pending**
  (same session, tool, command and input). A late decision, or one for another command, is ignored.
- The phone offers an approval for at most 120 s (the desktop dismisses it at 115 s), and sends one
  decision per request.
- **Allow requires the user's biometric or screen lock on the phone.** Deny does not. With no
  screen lock set up, Allow is refused.
- The command text is hidden on the lock screen.

## Behaviour rules (same as the other ports)

- Never block the agent: if the phone does not answer, the desktop's own approval stays usable.
- No telemetry. The only network traffic is between the phone and its paired desktop.
- The desktop link is **off by default** and opt-in in settings. It should listen only while it is
  on, and only to the local network.

## Android notes

- Android 16 asks for local network access the first time the app connects.
- A foreground service keeps the link alive in the background so approvals arrive as notifications.
- The app needs Android 11 or later (API 30) for the combined biometric / screen-lock prompt.

## Testing

- `android/tools/dev-desktop.mjs` pretends to be a desktop (needs `node` and `openssl`).
- `DevDesktopInteropTest` runs the Android client against it.
