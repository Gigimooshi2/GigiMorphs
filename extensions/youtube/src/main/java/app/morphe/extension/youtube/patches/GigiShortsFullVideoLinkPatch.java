package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.os.SystemClock;

import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

/**
 * GigiMorphs.
 * <p>
 * Tapping a Short's "full video" link opens the regular player on top of a Shorts player
 * that stays alive underneath. Both players then fight over playback, so the full video
 * pauses on rotation, screen lock and when leaving the app (no PiP).
 * <p>
 * Fix: when a regular video is about to open while the Shorts player is open,
 * cancel it, close the Shorts player, then open the video again as a normal watch page.
 */
@SuppressWarnings("unused")
public final class GigiShortsFullVideoLinkPatch {

    private static final long REOPEN_WINDOW_MS = 5000;
    private static final long CLOSE_SHORTS_DELAY_MS = 450;

    private static volatile String reopeningVideoId;
    private static volatile long reopeningTime;

    /**
     * Injection point.
     *
     * @return true to cancel opening the video.
     */
    public static boolean onVideoIntentLoaded(Map<Object, Object> playbackStartDescriptorMap, String videoId) {
        try {
            final boolean shortsOpen = ShortsPlayerState.isOpen();
            Logger.printDebug(() -> "GigiMorphs: video intent, videoId: " + videoId + " shortsOpen: " + shortsOpen);

            if (videoId == null || videoId.isEmpty()) {
                return false;
            }

            final long now = SystemClock.uptimeMillis();
            if (videoId.equals(reopeningVideoId) && now - reopeningTime < REOPEN_WINDOW_MS) {
                // This is our own reopen. Never intercept twice, even if Shorts is somehow still open.
                Logger.printDebug(() -> "GigiMorphs: letting our reopened video through: " + videoId);
                reopeningVideoId = null;
                return false;
            }

            if (!shortsOpen) {
                return false;
            }

            Activity activity = Utils.getActivity();
            if (activity == null) {
                Logger.printDebug(() -> "GigiMorphs: no activity, not intercepting");
                return false;
            }

            reopeningVideoId = videoId;
            reopeningTime = now;
            Logger.printDebug(() -> "GigiMorphs: intercepting full video link, closing Shorts then reopening: " + videoId);

            Utils.runOnMainThreadDelayed(() -> {
                try {
                    // Closes the Shorts player (same as pressing back).
                    activity.onBackPressed();
                } catch (Exception ex) {
                    Logger.printException(() -> "GigiMorphs: back press failure", ex);
                }
            }, 0);

            Utils.runOnMainThreadDelayed(() -> {
                Logger.printDebug(() -> "GigiMorphs: reopening " + videoId + " shortsOpen: " + ShortsPlayerState.isOpen());
                LoadVideoPatch.openVideoIntent("https://www.youtube.com/watch?v=" + videoId, false);
            }, CLOSE_SHORTS_DELAY_MS);

            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "GigiMorphs: onVideoIntentLoaded failure", ex);
            return false;
        }
    }
}
