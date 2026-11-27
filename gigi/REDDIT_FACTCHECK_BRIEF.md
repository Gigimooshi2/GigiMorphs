# Brief: "Fact check" button for the Reddit app (GigiMorphs)

## Why

The owner runs a personal fact-checking bot (Telegram **@GigisFactCheckerBot**, numeric bot id `9159815728`).
Claude does the research in a cloud session behind a relay server. Reddit blocks every hosting-company IP,
even with a logged-in account ("Your IP address is unable to access the Reddit API"), so the server can't fetch
Reddit videos. The phone can: it's on a normal mobile/home connection and Reddit's own app already has the media.

Goal: one tap in the Reddit app → the post's video + its link and title arrive at the bot, exactly like the
YouTube "Fact check button" patch already in this repo (`GigiFactCheckPatch`, Telegram share with a `#factcheck`
caption). Reuse that flow and its settings screen where possible.

## Where to hook

`GigiRedditPatches.kt` already has **Gigi video controls** (`GigiPlayerControls`): a row of buttons drawn inside
each Reddit video surface, bound to the surface's model, with access to the Media3 `ExoPlayer` (`player(model)`).
Add one more button there: **🔎** (content description "Fact check").

## What the button does

1. **Find the media:** read the playing item from the player: `getCurrentMediaItem().localConfiguration.uri`
   (obfuscated names on `Leci;`, find them in the APK). Prefer a direct MP4. `GigiVideo.startupStream()` already
   picks an H.264 MP4 ≤720p from `VideoUrls.getPlaybackMp4s()` for clips ≤90 s, so you could keep the last
   `VideoUrls` seen per model and reuse that choice. For HLS/DASH (longer videos), download the
   audio+video renditions and mux them (the YouTube patch already does MediaExtractor/MediaMuxer merging).
2. **Find the post:** permalink (`https://www.reddit.com/r/<sub>/comments/<id>/`), title and subreddit from
   the post model around the video surface. If the permalink isn't reachable, the title + subreddit is still useful.
3. **Size:** keep the file ≤ 19.5 MB (Telegram bot download limit). Pick a lower rendition, or audio only,
   like the YouTube patch.
4. **Send:** same as the YouTube patch: copy to `Download/FactCheck`, open Telegram with the file and a caption:
   ```
   #factcheck
   <permalink>
   <title>
   r/<subreddit> · u/<author>
   ```
   Use the existing `gigi_factcheck_chat_id` setting to open the bot chat directly when Telegram offers it.
5. Set the right MIME type (`video/mp4`). The YouTube patch has sent audio named `.jpg` before; avoid that.

## Settings

Reuse the existing "Fact check (GigiMorphs)" screen (chat id). Add a switch "Fact check button on Reddit videos"
(default on) if a separate toggle is easy.

## Rules

- Nothing runs before the tap. No background network calls.
- Don't touch login/session data. Only the media URL the player already has, and the post's public metadata.
- Keep it in `gigi/gigimorphs.patch` like the other fixes (the daily upstream sync re-applies that patch).
- Note: GitHub Actions minutes are used up until Dec 1, so the build/release runs then (or after adding a payment method).

## Test

Open a Reddit video post → tap 🔎 → Telegram opens with the MP4 and the `#factcheck` caption → send to the bot.
The bot should reply "🔎 Got it, checking…".

## Status (branch `claude/reddit-factcheck-button`, not built yet)

Done, without the obfuscated names:
- `GigiRedditFactCheck` (extension): finds the post's `com.reddit.domain.model.Link` in the top screen's
  args/fields (same native helpers as Gigi Back and Forward, `Lpzo0;->I`, `navstack/v;->c`), or a `t3_` id
  there (then public `by_id` JSON, fetched on tap), reads the v.redd.it DASH manifest, picks H.264 ≤720p +
  audio that fit 19.5 MB, merges with MediaMuxer, shares to Telegram (`gigi_factcheck_chat_id`, same as YouTube).
  Refuses if the manifest duration is >2.5 s from the player's (a video pager showing another post).
- 🔎 button in `GigiPlayerControls`, setting `gigi_reddit_factcheck_button` (default on) + chat id in Misc.

Still needs the APK (Reddit **2026.40.0, versionCode 2640091, arm64-v8a**, the exact build the Gigi patches
target). Two stubs in `GigiRedditFactCheck` return null until patched:
- `surfacePost(Object surfaceModel)`: the `Link` bound to `Lcom/reddit/exokit/internal/ui/b;` (feed support).
- `playerMediaUri(Object player)`: `Leci;` → current MediaItem → localConfiguration.uri (direct MP4 first, and
  a surer match than the duration check).
APK mirrors were not reachable from the cloud session (APKMirror/APKPure: Cloudflare challenge; Uptodown:
captcha token). Upload that APK (or the .apkm bundle) to the session/repo to finish these.
