# GigiMorphs

Personal fork of the Morphe patches, rebuilt from upstream `main` daily with the fixes in `gigimorphs.patch` applied on top.

Add to Morphe Manager: https://morphe.software/add-source?github=Gigimooshi2/GigiMorphs
(remove/disable the official Morphe source, the two bundles contain the same patches).

## Fixes
- **Background play / PiP for a video opened from a Short's full-video link.** Upstream blocks background play whenever the Shorts player is attached, even when the regular player is open on top of it. Now allowed when the regular player is maximized, fullscreen, minimized or in PiP.

- **Shorts full-video link opens properly.** New patch "Fix Shorts full video link": when a regular video opens while the Shorts player is open, it closes the Shorts player and reopens the video as a normal watch page, so the two players stop fighting over playback (fixes pause on rotation, screen lock, no PiP).

- **Upload logs.** New patch "Upload logs" (Settings → Misc → Upload logs): uploads a gzipped snapshot (Morphe log buffer + this app's recent logcat) to a private GitHub repo, manually or automatically after a crash/ANR on the next start (max 6 automatic uploads a day). The token is entered on the phone, never built into the bundle.

## How it works
- `build.yml`: on push to main, builds the `.mpp`, creates a release, updates `patches-bundle.json`.
- `sync-upstream.yml`: daily, rebuilds the tree from upstream `main`, re-applies `gigimorphs.patch`, then builds. Fails loudly if the patch no longer applies.
