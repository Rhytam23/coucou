Subject: Coucou for Android 0.3.0: everything in one PR (#682), CI green, short video

Hi Louis,

I put everything I had planned as 0.3.1 to 0.3.7 into one release, so Android 0.3.0 is now a single PR: https://github.com/Louis-CFM/coucou/pull/682 (versionName 0.3.0, versionCode 3; the tag is yours as before). It is merged with your current main (1e51809), it merges cleanly, and all the checks are green.

What is in it
- Home names the likely cause when the link is lost (computer closed, other network, phone link off); predictive back; fewer notification updates.
- Permission requests: a choice of vibration and whether the screen wakes (off by default). Allow still needs the fingerprint or screen lock; approvals are never muted or hidden.
- Light theme (System, Light, Dark) and the phone's own colours on Android 12+; Reduce motion, TalkBack, large text, 48 dp touch targets.
- Nine more languages: French, Spanish, Brazilian Portuguese, Simplified Chinese, Hindi, Bengali, Russian, Indonesian, Arabic (right to left). Native speakers have not reviewed them yet; the strings to check are listed in android/i18n/REVIEW.md.
- Quick actions (launcher shortcuts), a Team widget, and Share to Coucou (the text goes to the chat box and is never sent).
- Weekly recap on Home, search and export of recent decisions, and a per-agent mute for finished/failed/limit notices (requests and questions are never muted).
- GitLab and Gitea pills added to the Android catalog, same IDs and colours as the desktop.

Two things I want you to decide
1. This PR is bigger than before (about 160 files) and it is not only android/ any more: the weekly recap needs a small change on the Windows/Linux side (phone_link, src/recap, one Settings switch, strings and tests; German and Turkish included). If you prefer, I split that into its own PR first, like phone_link last time. Tell me which you want.
2. The recap switch is off by default and sends counts only, so nothing changes for anyone unless they turn it on. I would like you to confirm you are fine with that capability in the protocol.

The videos
Two short clips are attached (Galaxy A12s, Android 13). Please read this before you share them anywhere:
- They were recorded against the test desktop in the repo (android/tools/dev-desktop.mjs, I added a --recap option for the recap card), not the real Windows app, so the sessions, usage, recap numbers and services are fake.
- Video 1 (about 2 minutes) shows only what is new in 0.3.0: the weekly recap card on Home, the light and dark theme, the language picker (French, then Arabic right to left), the Settings sections (reduce motion, vibration and wake choices for requests, mute an agent's notices, quiet hours), and the message on Home when the link is lost.
- Video 2 (under a minute) shows Allow with a real fingerprint on a phone that now has a screen lock, then the Activity screen with a search of the decisions. The request, pairing and chat screens are protected on purpose, so Android records them black; you will see the black frames while the request and the fingerprint prompt are up.
- Not in the videos: the launcher shortcuts, the Team widget and Share to Coucou.

Thank you for the reviews so far,
Rhytam
