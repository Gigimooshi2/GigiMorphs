package app.morphe.extension.youtube.patches;

import static java.lang.Boolean.TRUE;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Nullable;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;
import app.morphe.extension.youtube.innertube.GigiFactCheckOuterClass.FactCheckCaptionTrack;
import app.morphe.extension.youtube.innertube.GigiFactCheckOuterClass.FactCheckFormat;
import app.morphe.extension.youtube.innertube.GigiFactCheckOuterClass.FactCheckPlayerResponse;
import app.morphe.extension.youtube.innertube.GigiFactCheckOuterClass.FactCheckVideoDetails;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.videoplayer.PlayerOverlayButton;

/**
 * GigiMorphs.
 * <p>
 * "Fact check" button. On tap it downloads the video on the phone, at most 20 MB (the largest file
 * a Telegram bot can receive), and opens the share sheet with the file, the captions if there are any,
 * and a caption text, so it can be sent to a fact checking Telegram bot from the user's own account.
 * <p>
 * Nothing runs until the button is tapped. One player request is made (the same one saving a video makes),
 * then the streams and the caption track are downloaded.
 */
@SuppressWarnings("unused")
public final class GigiFactCheckPatch {

    private static final String LOG_PREFIX = "GigiMorphs fact check: ";

    /**
     * Telegram bots cannot download files over 20 MB. Kept a little under it.
     */
    private static final long MAX_FILE_BYTES = 19_500_000L;

    /**
     * The server sends larger ranges at about playback speed.
     */
    private static final int RANGE_BYTES = 4 * 1024 * 1024;
    private static final int HTTP_TIMEOUT_MS = 20_000;

    private static final int CAPTION_MAX_CHARS = 1024;
    private static final int DESCRIPTION_MAX_CHARS = 500;

    private static final String DOWNLOADS_FOLDER = "FactCheck";
    private static final String ICON_NAME = "gigi_yt_fact_check";
    private static final String BUTTON_TEXT = "Fact check";

    private static final int ITAG_VIDEO_480P = 135;
    private static final int ITAG_VIDEO_360P = 134;

    /**
     * Clients that answer with stream urls. Same order the in-app download uses.
     */
    private static final List<ClientType> CLIENTS = List.of(
            ClientType.VISIONOS_1_03,
            ClientType.VISIONOS_1_02,
            ClientType.ANDROID_VR_DASH,
            ClientType.TV_DASH,
            ClientType.ANDROID_CREATOR,
            ClientType.TV_SIMPLY
    );

    private static final AtomicBoolean running = new AtomicBoolean();

    /**
     * Numeric id of the fact check bot (the part of its token before ':').
     * With it the file goes straight into the bot chat, if Telegram offers that chat as a share target.
     */
    public static final StringSetting TELEGRAM_CHAT_ID = new StringSetting("gigi_factcheck_chat_id", "");
    /**
     * If the Download button below the video asks between Fact check and Download.
     */
    public static final BooleanSetting DOWNLOAD_BUTTON_MENU = new BooleanSetting("gigi_factcheck_download_menu", TRUE);
    /**
     * If the captions are sent as a second file. Telegram can only open a chat directly for one file.
     */
    public static final BooleanSetting SEND_CAPTIONS = new BooleanSetting("gigi_factcheck_send_captions", TRUE);

    private static final String[] TELEGRAM_PACKAGES = {
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
    };

    private record SharedFile(Uri uri, String mimeType) {
    }

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     * Adds the button to the player overlay.
     */
    public static void initializeButton(View controlsView) {
        try {
            if (LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS) {
                return;
            }
            PlayerOverlayButton.addButton(controlsView,
                    ICON_NAME,
                    view -> start(VideoInformation.getVideoId()),
                    view -> {
                        start(VideoInformation.getVideoId());
                        return true;
                    }
            );
        } catch (Exception ex) {
            Logger.printException(() -> LOG_PREFIX + "initializeButton failure", ex);
        }
    }

    /**
     * Injection point.
     * Called when the Download button below the video (next to Share) is tapped,
     * and for the Download option of the menu of feed videos.
     *
     * @return If the tap was handled, otherwise the app or the Downloads patch handles it.
     */
    public static boolean onDownloadButtonClick(@Nullable String videoId) {
        try {
            if (!DOWNLOAD_BUTTON_MENU.get() || TextUtils.isEmpty(videoId)) {
                return false;
            }
            Activity activity = Utils.getActivity();
            if (activity == null) {
                return false;
            }
            new AlertDialog.Builder(activity)
                    .setItems(new CharSequence[]{"Fact check → Telegram", "Download"}, (dialog, which) -> {
                        if (which == 0) {
                            start(videoId);
                        } else {
                            download(videoId);
                        }
                    })
                    .show();
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> LOG_PREFIX + "onDownloadButtonClick failure", ex);
            return false;
        }
    }

    /**
     * Downloads the video the way the Downloads patch is set to, or else saves it in-app.
     */
    private static void download(String videoId) {
        try {
            if (DownloadsPatch.canDownloadWithoutAppButton()) {
                DownloadsPatch.downloadVideoWithoutAppButton(videoId);
            } else {
                // The tap was taken from the app, so its own downloader cannot be opened anymore.
                InAppDownloadsPatch.saveVideo(videoId);
            }
        } catch (Exception ex) {
            Logger.printException(() -> LOG_PREFIX + "download failure", ex);
            toast("download failed");
        }
    }

    /**
     * Called from FlyoutUtils when a flyout menu opens.
     * The Shorts player has no overlay buttons, so Shorts get a menu item.
     */
    public static int addFlyoutButton(Object flyoutPanel, int nextButtonIndex,
                                      String flyoutVideoId, boolean isShortFlyout) {
        if (!isShortFlyout || TextUtils.isEmpty(flyoutVideoId)) {
            return nextButtonIndex;
        }
        final String videoId = flyoutVideoId;
        return FlyoutUtils.addFlyoutButton(
                flyoutPanel,
                getIcon(),
                BUTTON_TEXT,
                v -> {
                    FlyoutUtils.dismissFlyout();
                    start(videoId);
                },
                nextButtonIndex
        );
    }

    @Nullable
    private static Drawable getIcon() {
        try {
            int id = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, ICON_NAME);
            return id == 0 ? null : Utils.getContext().getDrawable(id);
        } catch (Exception ex) {
            return null;
        }
    }

    private static void toast(String message) {
        Utils.showToastShort("Fact check: " + message);
    }

    private static void start(@Nullable String videoId) {
        if (TextUtils.isEmpty(videoId)) {
            toast("no video is open");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            toast("needs Android 10 or newer");
            return;
        }
        if (!Utils.isNetworkConnected()) {
            toast("no network");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            toast("still working on the last one");
            return;
        }

        toast("getting the video…");
        Utils.runOnBackgroundThread(() -> {
            try {
                run(videoId);
            } catch (Exception ex) {
                Logger.printInfo(() -> LOG_PREFIX + "failed for: " + videoId, ex);
                toast("failed. " + ex.getMessage());
            } finally {
                running.set(false);
            }
        });
    }

    private static void run(String videoId) throws Exception {
        Context context = Utils.getContext();

        StreamingDataRequest.StreamData streams = StreamingDataRequest
                .fetchRequestForDownload(videoId, CLIENTS, CLIENTS.get(0)).getStream();
        if (streams == null) {
            throw new IOException("YouTube gave no downloadable streams");
        }
        FactCheckPlayerResponse response = FactCheckPlayerResponse.parseFrom(streams.streamingData());
        FactCheckVideoDetails details = response.getVideoDetails();
        final String userAgent = streams.client().userAgent;
        final long durationMs = durationMs(response);
        Logger.printInfo(() -> LOG_PREFIX + "video: " + videoId + " client: " + streams.client()
                + " durationMs: " + durationMs
                + " formats: " + describe(response.getStreamingData().getFormatsList())
                + " adaptive: " + describe(response.getStreamingData().getAdaptiveFormatsList()));

        File workDir = new File(context.getCacheDir(), "gigi_factcheck");
        deleteFiles(workDir);
        if (!workDir.mkdirs() && !workDir.isDirectory()) {
            throw new IOException("Cannot create the work folder");
        }

        String baseName = "factcheck_" + videoId;
        File media = null;
        String mediaName = null;
        String mediaMimeType = null;

        List<FactCheckFormat> adaptive = response.getStreamingData().getAdaptiveFormatsList();
        FactCheckFormat m4a = pickAudio(adaptive, durationMs, true, Long.MAX_VALUE);

        // 1. Video of 480p, or else 360p, merged with the audio.
        if (m4a != null) {
            for (int itag : new int[]{ITAG_VIDEO_480P, ITAG_VIDEO_360P}) {
                FactCheckFormat video = findVideo(adaptive, itag);
                if (video == null) continue;
                final long videoSize = size(video, durationMs, userAgent);
                final long audioSize = size(m4a, durationMs, userAgent);
                if (videoSize <= 0 || audioSize <= 0 || videoSize + audioSize > MAX_FILE_BYTES) continue;

                try {
                    toast("downloading " + (itag == ITAG_VIDEO_480P ? "480p" : "360p")
                            + " (" + megabytes(videoSize + audioSize) + ")…");
                    File videoFile = new File(workDir, "video.mp4");
                    File audioFile = new File(workDir, "audio.m4a");
                    download(video.getUrl(), userAgent, videoFile, videoSize, Long.MAX_VALUE);
                    download(m4a.getUrl(), userAgent, audioFile, audioSize, Long.MAX_VALUE);
                    File merged = new File(workDir, baseName + ".mp4");
                    mux(videoFile, audioFile, merged);
                    if (merged.length() > 0 && merged.length() <= MAX_FILE_BYTES + 500_000) {
                        media = merged;
                        mediaName = baseName + ".mp4";
                        mediaMimeType = "video/mp4";
                    }
                } catch (Exception ex) {
                    Logger.printInfo(() -> LOG_PREFIX + "merging video and audio failed, itag: " + itag, ex);
                }
                // If merging failed once it would fail again with the smaller video.
                break;
            }
        }

        // 2. A stream that already has video and audio (360p).
        if (media == null) {
            for (FactCheckFormat format : response.getStreamingData().getFormatsList()) {
                if (format.getUrl().isEmpty() || !format.getMimeType().startsWith("video/mp4")) continue;
                final long size = size(format, durationMs, userAgent);
                if (size <= 0 || size > MAX_FILE_BYTES) continue;
                try {
                    toast("downloading 360p (" + megabytes(size) + ")…");
                    File file = new File(workDir, baseName + ".mp4");
                    download(format.getUrl(), userAgent, file, size, MAX_FILE_BYTES);
                    media = file;
                    mediaName = baseName + ".mp4";
                    mediaMimeType = "video/mp4";
                } catch (Exception ex) {
                    Logger.printInfo(() -> LOG_PREFIX + "downloading the combined stream failed", ex);
                }
                break;
            }
        }

        // 3. Audio only. The best audio that fits, or else the start of the smallest one.
        if (media == null) {
            FactCheckFormat audio = pickAudio(adaptive, durationMs, false, MAX_FILE_BYTES);
            boolean partial = false;
            if (audio == null) {
                audio = pickSmallestAudio(adaptive, durationMs);
                partial = true;
            }
            if (audio == null) {
                throw new IOException("No stream of this video can be downloaded");
            }
            final boolean isM4a = audio.getMimeType().startsWith("audio/mp4");
            final long size = size(audio, durationMs, userAgent);
            toast("video is over 20 MB, downloading audio only"
                    + (partial ? " (first part)" : " (" + megabytes(size) + ")") + "…");
            String name = baseName + (partial ? "_partial" : "") + (isM4a ? ".m4a" : ".webm");
            File file = new File(workDir, name);
            download(audio.getUrl(), userAgent, file, size, MAX_FILE_BYTES);
            media = file;
            mediaName = name;
            mediaMimeType = isM4a ? "audio/mp4" : "audio/webm";
        }

        if (media.length() == 0) {
            throw new IOException("Downloaded file is empty");
        }

        // Captions: English, or else auto-generated.
        File captionsFile = null;
        String captionsName = null;
        try {
            FactCheckCaptionTrack track = pickCaptions(response);
            if (track != null) {
                String captionsUrl = track.getBaseUrl();
                String language = language(track);
                boolean auto = isAutoGenerated(track);
                byte[] vtt = httpGet(captionsUrl.replaceAll("&fmt=[^&]*", "") + "&fmt=vtt", userAgent, 5_000_000);
                if (vtt.length > 10 && new String(vtt, 0, 6, StandardCharsets.UTF_8).equals("WEBVTT")) {
                    captionsName = baseName + "." + (TextUtils.isEmpty(language) ? "und" : language) + (auto ? ".auto" : "") + ".vtt";
                    captionsFile = new File(workDir, captionsName);
                    try (OutputStream out = new FileOutputStream(captionsFile)) {
                        out.write(vtt);
                    }
                } else {
                    Logger.printInfo(() -> LOG_PREFIX + "captions response is not WebVTT, bytes: " + vtt.length);
                }
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> LOG_PREFIX + "captions failed", ex);
        }

        String caption = buildCaption(videoId, details);

        deleteOldSharedFiles(context);
        ArrayList<SharedFile> shared = new ArrayList<>();
        shared.add(new SharedFile(publish(context, media, mediaName, mediaMimeType), mediaMimeType));
        if (captionsFile != null && SEND_CAPTIONS.get()) {
            try {
                shared.add(new SharedFile(publish(context, captionsFile, captionsName, "text/vtt"), "text/vtt"));
            } catch (Exception ex) {
                Logger.printInfo(() -> LOG_PREFIX + "sharing the captions failed", ex);
            }
        }
        deleteFiles(workDir);

        final File finalMedia = media;
        final String finalMediaName = mediaName;
        final boolean hasCaptions = shared.size() > 1;
        Logger.printInfo(() -> LOG_PREFIX + "sharing " + finalMediaName
                + " captions: " + hasCaptions + " caption chars: " + caption.length());

        Utils.runOnMainThread(() -> share(shared, caption));
    }

    // region Choosing streams.

    private static long durationMs(FactCheckPlayerResponse response) {
        long durationMs = response.getVideoDetails().getLengthSeconds() * 1000L;
        if (durationMs > 0) return durationMs;
        for (FactCheckFormat format : response.getStreamingData().getAdaptiveFormatsList()) {
            durationMs = Math.max(durationMs, format.getApproxDurationMs());
        }
        return durationMs;
    }

    /**
     * @return Size of the stream in bytes, or zero if it's unknown.
     */
    private static long size(FactCheckFormat format, long durationMs, String userAgent) {
        if (format.getContentLength() > 0) {
            return format.getContentLength();
        }
        try {
            final long probed = probeLength(format.getUrl(), userAgent);
            if (probed > 0) return probed;
        } catch (Exception ex) {
            Logger.printInfo(() -> LOG_PREFIX + "could not read the stream length", ex);
        }
        return estimatedSize(format, durationMs);
    }

    private static long estimatedSize(FactCheckFormat format, long durationMs) {
        if (format.getContentLength() > 0) {
            return format.getContentLength();
        }
        final int bitrate = format.getAverageBitrate() > 0 ? format.getAverageBitrate() : format.getBitrate();
        final long duration = format.getApproxDurationMs() > 0 ? format.getApproxDurationMs() : durationMs;
        return bitrate > 0 && duration > 0 ? (long) bitrate * duration / 8000 : 0;
    }

    @Nullable
    private static FactCheckFormat findVideo(List<FactCheckFormat> adaptive, int itag) {
        for (FactCheckFormat format : adaptive) {
            if (format.getItag() == itag && !format.getUrl().isEmpty()
                    && format.getMimeType().startsWith("video/mp4")) {
                return format;
            }
        }
        return null;
    }

    private static boolean isAudio(FactCheckFormat format) {
        return !format.getUrl().isEmpty() && format.getMimeType().startsWith("audio/");
    }

    /**
     * A video with dubbed audio tracks marks the original one in the url.
     */
    private static boolean isDubbed(FactCheckFormat format) {
        String url = format.getUrl();
        return url.contains("acont%3Ddubbed") || url.contains("acont=dubbed");
    }

    /**
     * @return The audio stream with the highest bitrate that is at most {@code maxBytes},
     *         in its original language if the video has dubbed tracks, or null if there is none.
     */
    @Nullable
    private static FactCheckFormat pickAudio(List<FactCheckFormat> adaptive, long durationMs,
                                             boolean onlyM4a, long maxBytes) {
        FactCheckFormat best = null;
        for (int pass = 0; pass < 2 && best == null; pass++) {
            // The first pass skips dubbed tracks.
            for (FactCheckFormat format : adaptive) {
                if (!isAudio(format)) continue;
                if (pass == 0 && isDubbed(format)) continue;
                final boolean isM4a = format.getMimeType().startsWith("audio/mp4");
                if (onlyM4a && !isM4a) continue;
                final long size = estimatedSize(format, durationMs);
                if (maxBytes != Long.MAX_VALUE && (size <= 0 || size > maxBytes)) continue;
                if (best == null || format.getBitrate() > best.getBitrate()) {
                    best = format;
                }
            }
        }
        return best;
    }

    @Nullable
    private static FactCheckFormat pickSmallestAudio(List<FactCheckFormat> adaptive, long durationMs) {
        FactCheckFormat smallest = null;
        for (int pass = 0; pass < 2 && smallest == null; pass++) {
            for (FactCheckFormat format : adaptive) {
                if (!isAudio(format)) continue;
                if (pass == 0 && isDubbed(format)) continue;
                if (smallest == null || format.getBitrate() < smallest.getBitrate()) {
                    smallest = format;
                }
            }
        }
        return smallest;
    }

    @Nullable
    private static String language(FactCheckCaptionTrack track) {
        String language = track.getLanguageCode();
        return language.isEmpty() ? Uri.parse(track.getBaseUrl()).getQueryParameter("lang") : language;
    }

    private static boolean isAutoGenerated(FactCheckCaptionTrack track) {
        return "asr".equals(track.getKind())
                || "asr".equals(Uri.parse(track.getBaseUrl()).getQueryParameter("kind"));
    }

    /**
     * @return The English captions, or else the auto-generated captions, or null if there are none.
     */
    @Nullable
    private static FactCheckCaptionTrack pickCaptions(FactCheckPlayerResponse response) {
        FactCheckCaptionTrack englishAuto = null;
        FactCheckCaptionTrack anyAuto = null;
        for (FactCheckCaptionTrack track : response.getCaptions()
                .getPlayerCaptionsTracklistRenderer().getCaptionTracksList()) {
            if (track.getBaseUrl().isEmpty()) continue;
            String language = language(track);
            final boolean english = language != null && language.toLowerCase(Locale.ROOT).startsWith("en");
            final boolean auto = isAutoGenerated(track);
            if (english && !auto) return track;
            if (english && englishAuto == null) englishAuto = track;
            if (auto && anyAuto == null) anyAuto = track;
        }
        return englishAuto != null ? englishAuto : anyAuto;
    }

    private static String describe(List<FactCheckFormat> formats) {
        StringBuilder builder = new StringBuilder("[");
        for (FactCheckFormat format : formats) {
            if (builder.length() > 1) builder.append(' ');
            builder.append(format.getItag()).append(format.getUrl().isEmpty() ? "(no url)" : "")
                    .append(':').append(format.getContentLength() / 1000).append("kB");
        }
        return builder.append(']').toString();
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000d);
    }

    // endregion

    // region Network.

    private static HttpURLConnection open(String url, String userAgent) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(HTTP_TIMEOUT_MS);
        connection.setReadTimeout(HTTP_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", userAgent);
        return connection;
    }

    /**
     * @return Length of the stream, read from the response of its first bytes, or zero if unknown.
     */
    private static long probeLength(String url, String userAgent) throws IOException {
        HttpURLConnection connection = open(url, userAgent);
        try {
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Range", "bytes=0-1");
            final int code = connection.getResponseCode();
            if (code == 206) {
                // "bytes 0-1/12345"
                String range = connection.getHeaderField("Content-Range");
                if (range != null && range.contains("/")) {
                    String total = range.substring(range.lastIndexOf('/') + 1).trim();
                    if (!total.equals("*")) return Long.parseLong(total);
                }
            } else if (code == 200) {
                return connection.getContentLengthLong();
            }
            return 0;
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Downloads a stream in short ranges.
     *
     * @param totalLength Length of the stream, or zero if unknown.
     * @param maxBytes    The download stops after this many bytes.
     */
    private static void download(String url, String userAgent, File file, long totalLength, long maxBytes)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long position = 0;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            while (position < maxBytes && (totalLength <= 0 || position < totalLength)) {
                long end = position + RANGE_BYTES - 1;
                if (maxBytes != Long.MAX_VALUE) end = Math.min(end, maxBytes - 1);
                if (totalLength > 0) end = Math.min(end, totalLength - 1);
                final long expected = end - position + 1;

                HttpURLConnection connection = open(url, userAgent);
                long received = 0;
                final int code;
                try {
                    connection.setRequestProperty("Accept-Encoding", "identity");
                    connection.setRequestProperty("Range", "bytes=" + position + "-" + end);
                    code = connection.getResponseCode();
                    if (code == 416) break; // Past the end of the stream.
                    if (code != 200 && code != 206) {
                        throw new IOException("Stream download got HTTP " + code);
                    }
                    try (InputStream in = connection.getInputStream()) {
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            final long allowed = maxBytes - position;
                            if (allowed <= 0) break;
                            if (read > allowed) read = (int) allowed;
                            out.write(buffer, 0, read);
                            position += read;
                            received += read;
                        }
                    }
                } finally {
                    connection.disconnect();
                }
                // The whole stream was sent, or the stream ended.
                if (code == 200 || received < expected) break;
            }
        }
        final long downloaded = position;
        Logger.printInfo(() -> LOG_PREFIX + "downloaded " + downloaded + " bytes of " + totalLength
                + " to " + file.getName());
        if (downloaded == 0) {
            throw new IOException("Stream download was empty");
        }
    }

    private static byte[] httpGet(String url, String userAgent, int maxBytes) throws IOException {
        HttpURLConnection connection = open(url, userAgent);
        try {
            final int code = connection.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            try (InputStream in = connection.getInputStream()) {
                int read;
                while ((read = in.read(buffer)) != -1 && out.size() < maxBytes) {
                    out.write(buffer, 0, read);
                }
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    // endregion

    // region Merging video and audio.

    private static int findTrack(MediaExtractor extractor, String mimePrefix) {
        for (int i = 0, count = extractor.getTrackCount(); i < count; i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return i;
        }
        return -1;
    }

    private static int maxInputSize(MediaFormat format) {
        return format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                : 0;
    }

    /**
     * Writes the video and audio streams into one mp4, without encoding them again.
     */
    private static void mux(File videoFile, File audioFile, File output) throws IOException {
        MediaExtractor video = new MediaExtractor();
        MediaExtractor audio = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            video.setDataSource(videoFile.getAbsolutePath());
            audio.setDataSource(audioFile.getAbsolutePath());
            final int videoTrack = findTrack(video, "video/");
            final int audioTrack = findTrack(audio, "audio/");
            if (videoTrack < 0 || audioTrack < 0) {
                throw new IOException("Stream has no track");
            }
            video.selectTrack(videoTrack);
            audio.selectTrack(audioTrack);
            MediaFormat videoFormat = video.getTrackFormat(videoTrack);
            MediaFormat audioFormat = audio.getTrackFormat(audioTrack);

            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            final int muxerVideoTrack = muxer.addTrack(videoFormat);
            final int muxerAudioTrack = muxer.addTrack(audioFormat);
            muxer.start();
            started = true;

            ByteBuffer buffer = ByteBuffer.allocate(Math.max(4 * 1024 * 1024,
                    Math.max(maxInputSize(videoFormat), maxInputSize(audioFormat))));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean videoDone = false;
            boolean audioDone = false;
            while (!videoDone || !audioDone) {
                final long videoTime = videoDone ? -1 : video.getSampleTime();
                final long audioTime = audioDone ? -1 : audio.getSampleTime();
                if (!videoDone && videoTime < 0) {
                    videoDone = true;
                    continue;
                }
                if (!audioDone && audioTime < 0) {
                    audioDone = true;
                    continue;
                }

                // Write the stream that is behind, so the two are interleaved.
                final boolean writeVideo = !videoDone && (audioDone || videoTime <= audioTime);
                MediaExtractor extractor = writeVideo ? video : audio;
                buffer.clear();
                final int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    if (writeVideo) videoDone = true;
                    else audioDone = true;
                    continue;
                }
                info.set(0, size, extractor.getSampleTime(),
                        (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                ? MediaCodec.BUFFER_FLAG_KEY_FRAME
                                : 0);
                muxer.writeSampleData(writeVideo ? muxerVideoTrack : muxerAudioTrack, buffer, info);
                if (!extractor.advance()) {
                    if (writeVideo) videoDone = true;
                    else audioDone = true;
                }
            }
            muxer.stop();
            started = false;
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw new IOException("Cannot merge the streams: " + ex.getMessage(), ex);
        } finally {
            video.release();
            audio.release();
            if (muxer != null) {
                try {
                    if (started) muxer.stop();
                } catch (Exception ignored) {
                }
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // endregion

    // region Sharing.

    static String buildCaption(String videoId, FactCheckVideoDetails details) {
        String title = details.getTitle().trim();
        String channel = details.getAuthor().trim();
        String description = details.getShortDescription().trim();
        if (description.length() > DESCRIPTION_MAX_CHARS) {
            description = description.substring(0, DESCRIPTION_MAX_CHARS).trim() + "…";
        }

        String caption = "#factcheck\n"
                + "https://www.youtube.com/watch?v=" + videoId + "\n"
                + title + "\n"
                + channel + "\n"
                + description;
        if (caption.length() > CAPTION_MAX_CHARS) {
            caption = caption.substring(0, CAPTION_MAX_CHARS - 1) + "…";
        }
        return caption.trim();
    }

    /**
     * Files shared before are deleted when the next ones are shared, so the folder does not grow.
     */
    private static void deleteOldSharedFiles(Context context) {
        try {
            context.getContentResolver().delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND "
                            + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                    new String[]{"%" + DOWNLOADS_FOLDER + "%", "factcheck_%"}
            );
        } catch (Exception ex) {
            Logger.printInfo(() -> LOG_PREFIX + "could not delete the old shared files", ex);
        }
    }

    /**
     * Copies the file to Download/FactCheck, where other apps can read it by its content uri.
     */
    private static Uri publish(Context context, File file, String name, String mimeType) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOADS_FOLDER);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Cannot create the shared file");
        }
        try (InputStream in = new FileInputStream(file);
             OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) {
                throw new IOException("Cannot write the shared file");
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return uri;
    }

    private static void share(ArrayList<SharedFile> files, String caption) {
        try {
            // Some apps drop the text of a share that has files, so it can also be pasted.
            Utils.setClipboard(caption);

            Activity activity = Utils.getActivity();
            Context context = activity != null ? activity : Utils.getContext();
            String chatId = TELEGRAM_CHAT_ID.get().trim();

            // Telegram opens a chat directly only for a single file, and only a chat
            // it offers as a share target (a recent or frequent chat).
            if (!chatId.isEmpty() && files.size() == 1) {
                for (String telegram : TELEGRAM_PACKAGES) {
                    Intent direct = buildSendIntent(files, caption);
                    direct.setPackage(telegram);
                    direct.putExtra(Intent.EXTRA_SHORTCUT_ID, "did3_" + chatId);
                    if (startActivity(context, direct)) {
                        Logger.printInfo(() -> LOG_PREFIX + "shared straight to the chat: " + chatId + " in: " + telegram);
                        return;
                    }
                }
            }

            // Telegram with its own chat picker.
            for (String telegram : TELEGRAM_PACKAGES) {
                Intent send = buildSendIntent(files, caption);
                send.setPackage(telegram);
                if (startActivity(context, send)) {
                    Logger.printInfo(() -> LOG_PREFIX + "shared to: " + telegram);
                    return;
                }
            }

            // No Telegram: the system share sheet.
            Intent chooser = Intent.createChooser(buildSendIntent(files, caption), BUTTON_TEXT);
            if (!startActivity(context, chooser)) {
                toast("could not open the share sheet");
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> LOG_PREFIX + "share failed", ex);
            toast("could not open the share sheet");
        }
    }

    private static Intent buildSendIntent(ArrayList<SharedFile> files, String caption) {
        Intent send;
        if (files.size() == 1) {
            send = new Intent(Intent.ACTION_SEND);
            send.setType(files.get(0).mimeType());
            send.putExtra(Intent.EXTRA_STREAM, files.get(0).uri());
        } else {
            ArrayList<Uri> uris = new ArrayList<>();
            for (SharedFile file : files) uris.add(file.uri());
            send = new Intent(Intent.ACTION_SEND_MULTIPLE);
            send.setType("*/*");
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        send.putExtra(Intent.EXTRA_TEXT, caption);

        ClipData clip = ClipData.newRawUri(BUTTON_TEXT, files.get(0).uri());
        for (int i = 1; i < files.size(); i++) {
            clip.addItem(new ClipData.Item(files.get(i).uri()));
        }
        send.setClipData(clip);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return send;
    }

    /**
     * @return If an app took the intent.
     */
    private static boolean startActivity(Context context, Intent intent) {
        try {
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException ex) {
            return false;
        }
    }

    // endregion

    private static void deleteFiles(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
