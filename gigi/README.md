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

## How it works
- `build.yml`: on push to main, builds the `.mpp`, creates a release, updates `patches-bundle.json`.
- `sync-upstream.yml`: daily, rebuilds the tree from upstream `dev`, re-applies `gigimorphs.patch`, then builds. Fails loudly if the patch no longer applies.
