package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayabilityStatus;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerConfig;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Status;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.VideoDetails;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

/**
 * GigiMorphs.
 * <p>
 * YouTube can answer the app's own video request with "unplayable" (the player then shows
 * an error such as "content unavailable"), while another client is still allowed to play the same video.
 * Spoof video streams only swaps the streams of a response that is already playable, so it cannot help there.
 * <p>
 * Fix: when the app builds its player response model from a response that is not playable,
 * and the spoofed client has a playable response for that video, the spoofed response is used instead.
 * The video then plays with the spoofed streams, without what only the app's own response carries
 * (watch history tracking, captions, seek previews).
 */
@SuppressWarnings("unused")
public final class GigiUnplayableFallbackPatch {

    private static final long REPLACEMENT_LIFETIME_MS = 30 * 60 * 1000;
    private static final long INTENT_VIDEO_ID_LIFETIME_MS = 20 * 1000;
    private static final int MAX_CACHED = 8;

    private record Replacement(byte[] bytes, long time) {
    }

    /**
     * Replacements already built, so a model created again for the same video (also on the main thread,
     * where nothing may be fetched) gets the same response.
     */
    private static final Map<String, Replacement> replacements = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_CACHED, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Replacement> eldest) {
                    return size() > MAX_CACHED;
                }
            });

    /**
     * Player config of the last playable response of the app itself. It fits the device,
     * unlike the config of another client.
     */
    @Nullable
    private static volatile PlayerConfig lastPlayableAppConfig;

    @Nullable
    private static volatile String lastToastVideoId;

    /**
     * Injection point.
     * Called with the player response every player response model is created from.
     *
     * @return Bytes of the player response to use instead, or null to keep the response.
     */
    @Nullable
    public static byte[] replaceUnplayablePlayerResponse(@Nullable Object playerProto) {
        try {
            if (!(playerProto instanceof MessageLite message)) {
                return null;
            }

            PlayerResponse appResponse = PlayerResponse.parseFrom(message.toByteArray());
            if (!appResponse.hasPlayabilityStatus()) {
                return null;
            }

            PlayabilityStatus playability = appResponse.getPlayabilityStatus();
            Status status = playability.getStatus();
            if (status == Status.OK) {
                if (appResponse.hasPlayerConfig() && appResponse.hasStreamingData()) {
                    lastPlayableAppConfig = appResponse.getPlayerConfig();
                }
                return null;
            }

            String responseVideoId = appResponse.getVideoDetails().getVideoId();
            String videoId = responseVideoId.isEmpty() ? lastRequestedVideoId() : responseVideoId;
            final String logVideoId = videoId;
            Logger.printInfo(() -> "GigiMorphs: app player response is not playable. status: " + status
                    + " reason: " + playability.getReason()
                    + " videoId: " + logVideoId + (responseVideoId.isEmpty() ? " (from last request)" : "")
                    + " hasStreams: " + appResponse.hasStreamingData());

            if (status != Status.UNPLAYABLE && status != Status.ERROR && status != Status.LOGIN_REQUIRED) {
                return null;
            }
            if (!app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch.isSpoofingEnabled()) {
                Logger.printInfo(() -> "GigiMorphs: Spoof video streams is off, cannot replace the response");
                return null;
            }
            if (videoId == null || videoId.isEmpty()) {
                Logger.printInfo(() -> "GigiMorphs: no video id for the unplayable response");
                return null;
            }

            Replacement cached = replacements.get(videoId);
            if (cached != null && System.currentTimeMillis() - cached.time < REPLACEMENT_LIFETIME_MS) {
                return cached.bytes;
            }
            if (Utils.isCurrentlyOnMainThread() || !Utils.isNetworkConnected()) {
                return null;
            }

            StreamingDataRequest.StreamData streams = StreamingDataRequest.getOrFetchRequest(videoId).getStream();
            if (streams == null) {
                Logger.printInfo(() -> "GigiMorphs: no other client can play: " + logVideoId);
                return null;
            }

            PlayerResponse.Builder builder = PlayerResponse.parseFrom(streams.streamingData()).toBuilder();
            if (builder.getPlayabilityStatus().getStatus() != Status.OK || !builder.hasStreamingData()) {
                return null;
            }

            // The app reads the video id and length from the video details,
            // which the stream request of the other client does not ask for.
            VideoDetails.Builder details = builder.getVideoDetails().toBuilder();
            if (details.getVideoId().isEmpty()) {
                details.setVideoId(videoId);
            }
            if (details.getLengthSeconds() <= 0) {
                long durationMs = 0;
                for (Format format : builder.getStreamingData().getAdaptiveFormatsList()) {
                    durationMs = Math.max(durationMs, format.getApproxDurationMs());
                }
                if (durationMs > 0) {
                    details.setLengthSeconds((int) (durationMs / 1000));
                }
            }
            builder.setVideoDetails(details);

            PlayerConfig appConfig = lastPlayableAppConfig;
            if (appConfig != null) {
                builder.setPlayerConfig(appConfig);
            }

            byte[] replacement = builder.build().toByteArray();
            replacements.put(videoId, new Replacement(replacement, System.currentTimeMillis()));

            Logger.printInfo(() -> "GigiMorphs: replaced unplayable player response of: " + logVideoId
                    + " with the response of client: " + streams.client()
                    + " lengthSeconds: " + details.getLengthSeconds()
                    + " appConfig: " + (appConfig != null));
            if (!videoId.equals(lastToastVideoId)) {
                lastToastVideoId = videoId;
                Utils.showToastShort("GigiMorphs: YouTube refused this video, playing it as " + streams.client());
            }
            return replacement;
        } catch (Exception ex) {
            Logger.printException(() -> "GigiMorphs: replaceUnplayablePlayerResponse failure", ex);
            return null;
        }
    }

    /**
     * An unplayable response usually has no video details, so its video is taken from the last video
     * the user opened, or else the last video the app requested.
     */
    @Nullable
    private static String lastRequestedVideoId() {
        String intentVideoId = GigiShortsFullVideoLinkPatch.lastIntentVideoId;
        if (intentVideoId != null && !intentVideoId.isEmpty()
                && System.currentTimeMillis() - GigiShortsFullVideoLinkPatch.lastIntentTime < INTENT_VIDEO_ID_LIFETIME_MS) {
            return intentVideoId;
        }
        try {
            Field field = VideoInformation.class.getDeclaredField("playerResponseVideoId");
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof String id ? id : null;
        } catch (Exception ex) {
            Logger.printDebug(() -> "GigiMorphs: could not read the last requested video id", ex);
            return null;
        }
    }
}
