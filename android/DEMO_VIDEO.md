# Demo video for Louis (about 90 seconds)

Louis asked for a short screen recording of the main flow. Nothing is published: the file goes to Louis only.

## Before recording

- Phone: Coucou for Android from the latest `android` branch, Wi-Fi on, same network as the PC, notification volume up.
- PC: the portable `coucou.exe` (no other Coucou running), Settings > Android phone > Phone link on, phone paired.
- Claude Code (the command-line tool, not the Desktop app) in a folder you trust, started with `claude.cmd --permission-mode default`.
- Phone: Do Not Disturb off, "Show Mochi over other apps" on if you want the island in the video.
- Use a test folder and a harmless command (`echo hello > demo.txt`). No real secrets on screen.

## Shot list

1. Home, connected, Mochi and the agent card (5 s).
2. Lock the phone. In the terminal, ask Claude Code to write `demo.txt` (10 s).
3. The screen lights up with the notification: "Approval needed / Unlock to see the request" (8 s).
4. Unlock, open the request, show what it will do, tap Allow, the fingerprint prompt, the terminal continues (20 s).
5. A second request: tap Deny, the terminal says it was denied (15 s).
6. Plan usage and the Chat tab, if switched on (15 s).
7. Settings > About and Licences: the attribution to Louis (10 s).

## The one catch: secure screens record black

The pairing, chat and approval screens are marked secure on purpose (so nothing sensitive can be captured). A screen
recording of them is black. Do not remove that protection for the video. Options:

- Film those few seconds with another phone pointed at the screen; or
- Record only the parts that are not secure, and show the approval through the PC side and the notification.

## How to record

Wireless or USB, from the PC: `adb shell screenrecord --time-limit 180 /sdcard/coucou-demo.mp4`, then
`adb pull /sdcard/coucou-demo.mp4`. Or use Android's own screen recorder (swipe down twice, Screen recorder).
Trim to about 90 seconds. Check the file for notifications from other apps before sending it.
