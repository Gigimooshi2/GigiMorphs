# GigiMorphs

Personal fork of the Morphe patches, rebuilt from upstream `dev` daily (latest fixes, occasionally less stable) with the fixes in `gigimorphs.patch` applied on top.

Add to Morphe Manager: https://morphe.software/add-source?github=Gigimooshi2/GigiMorphs
(remove/disable the official Morphe source, the two bundles contain the same patches).

## Fixes
- **Background play / PiP for a video opened from a Short's full-video link.** Upstream blocks background play whenever the Shorts player is attached, even when the regular player is open on top of it. Now allowed when the regular player is maximized, fullscreen, minimized or in PiP.

- **Shorts full-video link opens properly.** New patch "Fix Shorts full video link": when a regular video opens while the Shorts player is open, it closes the Shorts player and reopens the video as a normal watch page, so the two players stop fighting over playback (fixes pause on rotation, screen lock, no PiP).

- **Upload logs.** New patch "Upload logs" (Settings → Misc → Upload logs): uploads a gzipped snapshot (Morphe log buffer + this app's recent logcat) to a private GitHub repo, manually or automatically after a crash/ANR on the next start (max 6 automatic uploads a day). The token is entered on the phone, never built into the bundle.

- **Don't recommend channel for feed Shorts.** New patch: adds "Don't recommend channel" to the 3-dot menu of Shorts in the feed. It fetches the Short's player menu (`reel/reel_item_watch`), takes its "Don't recommend this channel" feedback token and sends it to `feedback`.

- **Play videos YouTube refuses.** New patch: when YouTube answers the app's own video request with "unplayable" (the player shows "content unavailable") but the spoofed client can play the video, the app's player response is replaced with the spoofed client's response when the player response model is created. Needs Spoof video streams on. Videos played this way have no watch-history tracking, captions or seek previews. Every non-playable app response is logged with its status and reason ("GigiMorphs: app player response is not playable").

- **Fact check button.** New patch: tapping the Download button below a video (next to Share) asks "Fact check → Telegram" or "Download"; there is also a button in the player overlay and a "Fact check" item in the menu of Shorts. Fact check makes one player request with the download clients, downloads 480p or 360p video merged with audio if that is at most 20 MB (or the combined 360p stream, or else audio only), fetches English or auto-generated captions as .vtt, copies the files to Download/FactCheck and opens Telegram with them and a `#factcheck` caption (url, title, channel, start of the description). With the bot's numeric chat id set and a single file, Telegram opens the bot chat directly if it offers that chat as a share target; otherwise Telegram's own chat picker. Settings: Misc > Fact check (GigiMorphs). Nothing runs before the tap.

- **Fact check pop-ups.** New patch: when a video you had fact checked plays, each checked claim pops up over the player at the moment it is said (verdict, claim, one line of why), for 12 seconds. Tap it for the details and sources, or "All claims" for the full list (tap a claim to jump to it); hold it to close. At the start a banner shows how many claims were checked. The fact checks come from the fact checker's relay (`GET <server>/fc/<videoId>` with a read-only key); nothing is requested until the server address and key are set in Settings > Misc > Fact check pop-ups (GigiMorphs).

- **Reddit: fact check button.** "Gigi video controls" gets a 🔎 button (Settings > Misc: "Fact check button on Reddit videos", and the bot chat id). On tap it finds the post on the open screen (title, subreddit, author, permalink), reads its v.redd.it DASH manifest, downloads H.264 video at 720p or lower with its audio and merges them (≤ 19.5 MB; otherwise a lower rendition, then audio only, then the fallback MP4), copies the file to Download/FactCheck as `video/mp4` and opens Telegram with it and a `#factcheck` caption. If the manifest duration differs from the playing video, it refuses instead of sending another post's video. Works from a post's own screen; in the feed it asks to open the post until the video surface's post is mapped (see `REDDIT_FACTCHECK_BRIEF.md`). Nothing runs before the tap.

## How it works
- `build.yml`: on push to main, builds the `.mpp`, creates a release, updates `patches-bundle.json`.
- `sync-upstream.yml`: daily, rebuilds the tree from upstream `dev`, re-applies `gigimorphs.patch`, then builds. Fails loudly if the patch no longer applies.
