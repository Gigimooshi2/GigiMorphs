# GigiMorphs

Personal fork of the Morphe patches, rebuilt from upstream `main` daily with the fixes in `gigimorphs.patch` applied on top.

Add to Morphe Manager: https://morphe.software/add-source?github=Gigimooshi2/GigiMorphs
(remove/disable the official Morphe source, the two bundles contain the same patches).

## Fixes
- **Background play / PiP for a video opened from a Short's full-video link.** Upstream blocks background play whenever the Shorts player is attached, even when the regular player is open on top of it. Now allowed when the regular player is maximized, fullscreen, minimized or in PiP.

## How it works
- `build.yml`: on push to main, builds the `.mpp`, creates a release, updates `patches-bundle.json`.
- `sync-upstream.yml`: daily, rebuilds the tree from upstream `main`, re-applies `gigimorphs.patch`, then builds. Fails loudly if the patch no longer applies.
