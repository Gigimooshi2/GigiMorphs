package app.morphe.extension.reddit.patches;

import static java.lang.Boolean.TRUE;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Xml;
import android.widget.Toast;

import com.reddit.domain.model.Link;
import com.reddit.domain.model.LinkMedia;
import com.reddit.domain.model.Preview;
import com.reddit.domain.model.RedditVideo;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.StringSetting;

/**
 * Fact check button of the Gigi video controls: sends the post's video and a #factcheck caption to Telegram.
 * <p>
 * Reddit blocks the fact checker's server, so the phone downloads the video (H.264, 720p or lower,
 * at most 19.5 MB, merged with its audio) and shares it, like the YouTube fact check patch.
 * Nothing here runs before the button is tapped, and every step catches everything it throws.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class GigiRedditFactCheck {
    private static final String LOG_PREFIX = "GigiMorphs Reddit fact check: ";

    /** Telegram bots cannot download files over 20 MB. Kept a little under it. */
    private static final long MAX_FILE_BYTES = 19_500_000L;
    private static final int MAX_SHORT_SIDE = 720;
    private static final int HTTP_TIMEOUT_MS = 20_000;
    private static final int RANGE_BYTES = 4 * 1024 * 1024;
    private static final int CAPTION_MAX_CHARS = 1024;
    /** How far the manifest duration may be from the player's before the video counts as another one. */
    private static final long DURATION_TOLERANCE_MS = 2_500;
    private static final String DOWNLOADS_FOLDER = "FactCheck";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";
    private static final Pattern POST_ID = Pattern.compile("(?:^|[^a-z0-9])t3_([a-z0-9]{4,12})(?![a-z0-9])");

    private static final String[] TELEGRAM_PACKAGES = {
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
    };

    /** Same key as the YouTube patch: the bot's numeric chat id, so Telegram can open that chat directly. */
    public static final StringSetting TELEGRAM_CHAT_ID = new StringSetting("gigi_factcheck_chat_id", "");
    public static final BooleanSetting BUTTON = new BooleanSetting("gigi_reddit_factcheck_button", TRUE);

    private static final AtomicBoolean running = new AtomicBoolean();
    private static volatile Handler mainHandler;

    private static Handler main() {
        Handler handler = mainHandler;
        if (handler == null) mainHandler = handler = new Handler(Looper.getMainLooper());
        return handler;
    }

    public static boolean isPatchIncluded() { return false; }

    // region Filled in by the patch.

    // Same helpers as GigiNavigation: the top screen (including overlays) and its arguments.
    private static Object topScreen(Activity activity) { return null; }
    private static Bundle screenArgs(Object screen) { return null; }
    // The video url of a video surface model (com.reddit.exokit.internal.ui.b, VideoProps.url).
    private static String surfaceUrl(Object surfaceModel) { return null; }
    // Media3 in the targeted build, one step each so no stub needs scratch registers:
    // player timeline, current item index, timeline window count, a new Window, and the
    // uri of a window's MediaItem.localConfiguration.
    private static Object timeline(Object player) { return null; }
    private static int itemIndex(Object player) { return -1; }
    private static int windowCount(Object timeline) { return 0; }
    private static Object newWindow(Object unused) { return null; }
    private static Object windowUri(Object timeline, int index, Object window) { return null; }

    // endregion

    /** Uri of the player's current media item, or null. Main thread only. */
    private static String playerMediaUri(Object player) {
        if (player == null) return null;
        Object timeline = timeline(player);
        if (timeline == null) return null;
        int index = itemIndex(player);
        if (index < 0 || index >= windowCount(timeline)) return null;
        Object window = newWindow(null);
        if (window == null) return null;
        Object uri = windowUri(timeline, index, window);
        return uri == null ? null : uri.toString();
    }


    public static boolean buttonEnabled() {
        try {
            return isPatchIncluded() && BUTTON.get();
        } catch (Throwable ex) {
            return false;
        }
    }

    private static final class Post {
        String id;          // Without the t3_ prefix.
        String permalink;   // Absolute.
        String title;
        String subreddit;
        String author;
        String dashUrl;
        String hlsUrl;
        String fallbackUrl;
        String externalUrl; // Link of a post whose media is not a Reddit video.
    }

    private static final class Rendition {
        String url;
        boolean audio;
        int width;
        int height;
        long bandwidth;
        String codecs = "";
        long size;
        int shortSide() { return Math.min(width, height) > 0 ? Math.min(width, height) : Math.max(width, height); }
    }

    private static final class Shared {
        final Uri uri;
        final String mimeType;
        Shared(Uri uri, String mimeType) { this.uri = uri; this.mimeType = mimeType; }
    }


    /**
     * Called on the main thread when 🔎 is tapped.
     *
     * @param playerDurationMs Duration of the playing video, or a negative value if unknown.
     */
    public static void start(Context context, Object surfaceModel, Object player, long playerDurationMs) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                toast("needs Android 10 or newer");
                return;
            }
            if (!running.compareAndSet(false, true)) {
                toast("still working on the last one");
                return;
            }
        } catch (Throwable ex) {
            log(LOG_PREFIX + "start failed", ex);
            return;
        }

        // Screens and the player belong to the main thread: read them here, work in the background.
        Link link = null;
        String postId = null;
        boolean verified = false;
        String mediaUri = null;
        String videoUrl = null;
        try {
            mediaUri = playerMediaUri(player);
        } catch (Throwable ex) {
            log(LOG_PREFIX + "no media uri from the player", ex);
        }
        try {
            videoUrl = surfaceUrl(surfaceModel);
        } catch (Throwable ex) {
            log(LOG_PREFIX + "no url on the video surface", ex);
        }
        String key = mediaKey(mediaUri);
        if (key == null) key = mediaKey(videoUrl);
        try {
            Object screen = topScreen(activity(context));
            if (screen != null) {
                Finder args = new Finder(null);
                Bundle bundle = null;
                try {
                    bundle = screenArgs(screen);
                } catch (Throwable ex) {
                    log(LOG_PREFIX + "no screen args", ex);
                }
                args.walk(bundle, true);
                if (key != null && args.link != null && matches(args.link, key)) {
                    link = args.link;
                    verified = true;
                } else if (key != null) {
                    // The feed or a video pager: the post whose video is the one playing.
                    Finder search = new Finder(key);
                    search.walk(bundle, false);
                    if (search.link == null) search.walk(screen, false);
                    link = search.link;
                    verified = link != null;
                }
                if (link == null) {
                    // Not matched (or nothing to match with): the screen's own post, checked by duration later.
                    if (args.link == null) {
                        Finder fields = new Finder(null);
                        fields.walk(screen, false);
                        args.link = fields.link;
                    }
                    link = args.link;
                    postId = args.postId;
                }
            }
        } catch (Throwable ex) {
            log(LOG_PREFIX + "no post on the top screen", ex);
        }

        final Link foundLink = link;
        final String foundId = postId;
        final boolean foundVerified = verified;
        final String foundUri = mediaUri;
        final String foundVideoUrl = videoUrl;
        final String foundKey = key;
        final Context app = context.getApplicationContext();
        Logger.printInfo(() -> LOG_PREFIX + "tap: media " + foundUri + " surface " + foundVideoUrl + " key " + foundKey
                + " post " + (foundLink != null) + " verified " + foundVerified);
        toast("getting the video…");
        try {
            new Thread(() -> {
                try {
                    run(app, foundLink, foundId, foundVerified, foundUri, foundVideoUrl, foundKey, playerDurationMs);
                } catch (Throwable ex) {
                    log(LOG_PREFIX + "failed", ex);
                    toast("failed. " + ex.getMessage());
                } finally {
                    running.set(false);
                }
            }, "GigiRedditFactCheck").start();
        } catch (Throwable ex) {
            running.set(false);
            log(LOG_PREFIX + "could not start", ex);
        }
    }

    // region Finding the post.

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /**
     * Id of a Reddit-hosted video ("abc123xyz" of https://v.redd.it/abc123xyz/DASHPlaylist.mpd),
     * or null for other hosts.
     */
    static String mediaKey(String url) {
        try {
            if (url == null || !url.startsWith("https://")) return null;
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null || !host.endsWith("redd.it") || host.equals("i.redd.it")
                    || host.endsWith("preview.redd.it")) return null;
            for (String segment : uri.getPathSegments()) {
                if (segment.length() >= 8 && segment.matches("[A-Za-z0-9]+")) return segment;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** If the post's video urls contain the media id. */
    private static boolean matches(Link link, String key) {
        try {
            Post post = fromLink(link);
            for (String url : new String[]{post.dashUrl, post.hlsUrl, post.fallbackUrl, post.externalUrl}) {
                if (url != null && url.contains(key)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Walks screen objects for a post: the first Link (without a key), or the Link whose video has the key.
     * Bounded by object count, depth and time, since it runs on the main thread.
     */
    private static final class Finder {
        private static final int MAX_OBJECTS = 30_000;
        private static final int MAX_DEPTH = 8;
        private static final int MAX_ITEMS = 300;
        private static final long MAX_MILLIS = 300;

        final String key;
        Link link;
        String postId;
        private final java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        private long deadline;

        Finder(String key) { this.key = key; }

        /** @param ids If strings may give a post id (screen arguments only: fields hold other posts' ids). */
        void walk(Object root, boolean ids) {
            if (root == null || link != null) return;
            deadline = android.os.SystemClock.uptimeMillis() + MAX_MILLIS;
            java.util.ArrayDeque<Object[]> queue = new java.util.ArrayDeque<>();
            queue.add(new Object[]{root, 0});
            while (!queue.isEmpty() && link == null) {
                if (seen.size() > MAX_OBJECTS || android.os.SystemClock.uptimeMillis() > deadline) return;
                Object[] entry = queue.poll();
                Object value = entry[0];
                int depth = (Integer) entry[1];
                if (value == null || seen.put(value, Boolean.TRUE) != null) continue;
                try {
                    visit(value, depth, ids, queue);
                } catch (Throwable ignored) {
                    // A collection changed while iterating, or a field cannot be read: skip it.
                }
            }
        }

        private void add(java.util.ArrayDeque<Object[]> queue, Object value, int depth) {
            if (value != null && depth <= MAX_DEPTH && !(value instanceof Number) && !(value instanceof Boolean)
                    && !(value instanceof Enum)) queue.add(new Object[]{value, depth});
        }

        private void visit(Object value, int depth, boolean ids, java.util.ArrayDeque<Object[]> queue)
                throws IllegalAccessException {
            if (value instanceof Link) {
                Link candidate = (Link) value;
                if (key == null || matches(candidate, key)) link = candidate;
                return; // Not into posts: their crossposts are other posts.
            }
            if (value instanceof CharSequence) {
                if (ids && postId == null) {
                    Matcher m = POST_ID.matcher(value.toString());
                    if (m.find()) postId = m.group(1);
                }
                return;
            }
            if (value instanceof Bundle) {
                Bundle bundle = (Bundle) value;
                for (String k : bundle.keySet()) add(queue, bundle.get(k), depth + 1);
                return;
            }
            if (value instanceof java.util.Map) {
                int count = 0;
                for (Object item : ((java.util.Map<?, ?>) value).values()) {
                    if (++count > MAX_ITEMS) break;
                    add(queue, item, depth + 1);
                }
                return;
            }
            if (value instanceof Iterable) {
                int count = 0;
                for (Object item : (Iterable<?>) value) {
                    if (++count > MAX_ITEMS) break;
                    add(queue, item, depth + 1);
                }
                return;
            }
            Class<?> type = value.getClass();
            if (type.isArray()) {
                if (type.getComponentType().isPrimitive()) return;
                Object[] array = (Object[]) value;
                for (int i = 0; i < array.length && i < MAX_ITEMS; i++) add(queue, array[i], depth + 1);
                return;
            }
            if (!appClass(type)) return;
            for (; type != null && appClass(type); type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    field.setAccessible(true);
                    add(queue, field.get(value), depth + 1);
                }
            }
        }

        /** Reddit's own classes (obfuscated ones have no package) and Compose/flow state holders. */
        private static boolean appClass(Class<?> type) {
            String name = type.getName();
            return name.indexOf('.') < 0 || name.startsWith("com.reddit.")
                    || name.startsWith("kotlinx.coroutines.flow.") || name.startsWith("androidx.compose.runtime.");
        }
    }


    private static String call(java.util.concurrent.Callable<String> getter) {
        try {
            String value = getter.call();
            return value == null || value.trim().isEmpty() ? null : value.trim();
        } catch (Throwable ex) {
            // A getter missing in this Reddit build throws NoSuchMethodError.
            return null;
        }
    }

    private static Post fromLink(Link link) {
        Post post = new Post();
        post.id = stripKind(call(link::getId));
        if (post.id == null) post.id = stripKind(call(link::getKindWithId));
        post.title = call(link::getTitle);
        post.subreddit = call(link::getSubreddit);
        post.author = call(link::getAuthor);
        String permalink = call(() -> (String) link.getClass().getMethod("getPermalink").invoke(link));
        if (permalink != null && permalink.startsWith("/")) permalink = "https://www.reddit.com" + permalink;
        post.permalink = permalink;

        Link content = link;
        try {
            List<Link> parents = link.getCrossPostParentList();
            if (parents != null && !parents.isEmpty() && parents.get(0) != null) content = parents.get(0);
        } catch (Throwable ignored) {
        }
        RedditVideo video = null;
        try {
            LinkMedia media = content.getMedia();
            if (media != null) video = media.getRedditVideo();
        } catch (Throwable ignored) {
        }
        if (video == null) {
            try {
                Preview preview = content.getPreview();
                if (preview != null) video = preview.getRedditVideoPreview();
            } catch (Throwable ignored) {
            }
        }
        if (video != null) {
            final RedditVideo v = video;
            post.dashUrl = call(v::getDashUrl);
            post.hlsUrl = call(v::getHlsUrl);
            post.fallbackUrl = call(v::getFallBackUrl);
        } else {
            final Link c = content;
            post.externalUrl = call(c::getUrl);
        }
        return post;
    }

    private static String stripKind(String id) {
        if (id == null) return null;
        return id.startsWith("t3_") ? id.substring(3) : id;
    }

    /** Public post data, used only when the screen has the post id but no post. */
    private static Post fetchPost(String id) throws IOException {
        byte[] body = httpGet("https://www.reddit.com/by_id/t3_" + id + ".json?raw_json=1", 2_000_000);
        try {
            JSONObject data = new JSONObject(new String(body, StandardCharsets.UTF_8))
                    .getJSONObject("data").getJSONArray("children").getJSONObject(0).getJSONObject("data");
            Post post = new Post();
            post.id = id;
            post.title = data.optString("title", null);
            post.subreddit = data.optString("subreddit", null);
            post.author = data.optString("author", null);
            String permalink = data.optString("permalink", null);
            post.permalink = permalink == null ? null : "https://www.reddit.com" + permalink;
            JSONObject content = data;
            JSONArray parents = data.optJSONArray("crosspost_parent_list");
            if (parents != null && parents.length() > 0) content = parents.getJSONObject(0);
            JSONObject video = null;
            JSONObject media = content.optJSONObject("secure_media");
            if (media != null) video = media.optJSONObject("reddit_video");
            if (video == null) {
                JSONObject preview = content.optJSONObject("preview");
                if (preview != null) video = preview.optJSONObject("reddit_video_preview");
            }
            if (video != null) {
                post.dashUrl = video.optString("dash_url", null);
                post.hlsUrl = video.optString("hls_url", null);
                post.fallbackUrl = video.optString("fallback_url", null);
            } else {
                post.externalUrl = content.optString("url", null);
            }
            return post;
        } catch (Exception ex) {
            throw new IOException("Unexpected post data", ex);
        }
    }

    // endregion

    private static void run(Context context, Link link, String postId, boolean verified, String mediaUri,
                            String videoUrl, String key, long playerDurationMs) throws Exception {
        Post post = null;
        if (link != null) post = fromLink(link);
        if (post == null && postId != null) {
            try {
                post = fetchPost(postId);
            } catch (IOException ex) {
                log(LOG_PREFIX + "could not load post " + postId, ex);
            }
        }
        if (post == null && key != null) {
            // The post was not found, but the video is known: send it with its own link,
            // which opens the post.
            post = new Post();
            post.permalink = "https://v.redd.it/" + key;
            verified = true;
            Logger.printInfo(() -> LOG_PREFIX + "no post for " + key + ", sending the video with its link");
        }
        if (post == null) {
            toast("open the post (its comments), then tap 🔎 again");
            return;
        }
        // The player's own manifest or file when the post has none.
        for (String url : new String[]{mediaUri, videoUrl}) {
            if (post.dashUrl == null && isManifest(url) && (verified || key == null)) post.dashUrl = url;
        }
        if (post.dashUrl == null && key != null && verified && "v.redd.it".equals(Uri.parse(post.permalink).getHost())) {
            post.dashUrl = "https://v.redd.it/" + key + "/DASHPlaylist.mpd";
        }
        if (post.permalink == null && post.id != null) {
            post.permalink = post.subreddit != null
                    ? "https://www.reddit.com/r/" + post.subreddit + "/comments/" + post.id + "/"
                    : "https://www.reddit.com/comments/" + post.id + "/";
        }
        final String caption = caption(post);
        final Post p = post;
        final String playing = mediaUri;
        Logger.printInfo(() -> LOG_PREFIX + "post " + p.id + " dash: " + p.dashUrl + " fallback: " + p.fallbackUrl
                + " player: " + playing);

        File directory = new File(context.getCacheDir(), "gigi_factcheck");
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
        deleteFiles(directory);
        File output = new File(directory, "video.mp4");
        String mimeType = "video/mp4";
        String extension = "mp4";
        boolean found = false;

        // 1. The player's own file, when it is a direct MP4 small enough.
        if (!isDirectMp4(mediaUri) && verified && isDirectMp4(videoUrl)) mediaUri = videoUrl;
        if (isDirectMp4(mediaUri)) {
            try {
                long size = probeLength(mediaUri);
                if (size > 0 && size <= MAX_FILE_BYTES) {
                    download(mediaUri, output, size, MAX_FILE_BYTES);
                    found = true;
                }
            } catch (IOException ex) {
                log(LOG_PREFIX + "player MP4 failed", ex);
            }
        }

        // 2. The post's DASH manifest: H.264 video at 720p or lower, merged with its audio.
        if (!found && post.dashUrl != null) {
            String result = null;
            try {
                result = fromDash(post.dashUrl, directory, output, playerDurationMs, mediaUri, post.id, verified);
            } catch (Exception ex) {
                log(LOG_PREFIX + "DASH failed, trying the fallback MP4", ex);
            }
            if ("mismatch".equals(result)) {
                toast("this video is not the open post. Open its comments, then tap 🔎 again");
                return;
            }
            if ("audio".equals(result)) {
                mimeType = "audio/mp4";
                extension = "m4a";
            }
            found = result != null;
        }

        // 3. The post's fallback MP4 (video only).
        if (!found && isHttps(post.fallbackUrl)) {
            long size = probeLength(post.fallbackUrl);
            if (size > 0 && size <= MAX_FILE_BYTES) {
                download(post.fallbackUrl, output, size, MAX_FILE_BYTES);
                found = true;
            }
        }

        ArrayList<Shared> files = new ArrayList<>();
        if (found) {
            deleteOldSharedFiles(context);
            String name = "factcheck_reddit_" + (post.id == null ? "video" : post.id) + "." + extension;
            File published = new File(directory, "factcheck." + extension);
            if (!output.renameTo(published)) published = output;
            files.add(new Shared(publish(context, published, name, mimeType), mimeType));
            deleteFiles(directory);
        } else if (post.externalUrl == null) {
            toast("no file small enough. Sending the link only");
        }
        final ArrayList<Shared> shared = files;
        main().post(() -> share(context, shared, caption));
    }

    private static boolean isHttps(String url) {
        return url != null && url.startsWith("https://");
    }

    private static boolean isManifest(String url) {
        if (!isHttps(url)) return false;
        String path = Uri.parse(url).getPath();
        return path != null && path.toLowerCase(Locale.US).endsWith(".mpd");
    }

    private static boolean isDirectMp4(String url) {
        if (!isHttps(url)) return false;
        String path = Uri.parse(url).getPath();
        return path != null && path.toLowerCase(Locale.US).endsWith(".mp4");
    }

    // region DASH.

    /**
     * @return "video" or "audio" for the file written to output, "mismatch" if the manifest is of another
     *         video than the one playing, or null if nothing fits.
     */
    private static String fromDash(String dashUrl, File directory, File output, long playerDurationMs,
                                   String mediaUri, String postId, boolean verified) throws Exception {
        List<Rendition> renditions = new ArrayList<>();
        long durationMs = parseManifest(dashUrl, httpGet(dashUrl, 1_000_000), renditions);

        // The post found on the screen may be another one than the playing video (a video pager).
        boolean sameMedia = verified || (mediaUri != null && postId != null && mediaUri.contains(postId));
        if (!sameMedia && mediaUri != null) {
            String folder = mediaFolder(dashUrl);
            sameMedia = folder != null && mediaUri.contains(folder);
        }
        if (!sameMedia && durationMs > 0 && playerDurationMs > 0
                && Math.abs(durationMs - playerDurationMs) > DURATION_TOLERANCE_MS) {
            Logger.printInfo(() -> LOG_PREFIX + "manifest is " + durationMs + " ms, player " + playerDurationMs + " ms");
            return "mismatch";
        }

        List<Rendition> videos = new ArrayList<>();
        List<Rendition> audios = new ArrayList<>();
        for (Rendition r : renditions) {
            if (r.audio) audios.add(r);
            else if (r.shortSide() <= MAX_SHORT_SIDE && !r.codecs.startsWith("hev") && !r.codecs.startsWith("hvc")
                    && !r.codecs.startsWith("av01") && !r.codecs.startsWith("vp")) videos.add(r);
        }
        videos.sort((a, b) -> a.shortSide() != b.shortSide()
                ? Integer.compare(b.shortSide(), a.shortSide()) : Long.compare(b.bandwidth, a.bandwidth));
        audios.sort((a, b) -> Long.compare(b.bandwidth, a.bandwidth));
        for (Rendition r : videos) r.size = probeLength(r.url);
        for (Rendition r : audios) r.size = probeLength(r.url);

        Rendition audio = null;
        for (Rendition a : audios) {
            if (a.size > 0) {
                audio = a;
                break;
            }
        }
        for (Rendition v : videos) {
            if (v.size <= 0) continue;
            if (audio == null) {
                if (v.size > MAX_FILE_BYTES) continue;
                download(v.url, output, v.size, MAX_FILE_BYTES);
                return "video";
            }
            for (Rendition a : audios) {
                if (a.size <= 0 || v.size + a.size > MAX_FILE_BYTES) continue;
                File videoFile = new File(directory, "v.mp4");
                File audioFile = new File(directory, "a.mp4");
                download(v.url, videoFile, v.size, Long.MAX_VALUE);
                download(a.url, audioFile, a.size, Long.MAX_VALUE);
                mux(videoFile, audioFile, output);
                //noinspection ResultOfMethodCallIgnored
                videoFile.delete();
                //noinspection ResultOfMethodCallIgnored
                audioFile.delete();
                if (output.length() > 0 && output.length() <= MAX_FILE_BYTES) return "video";
                Logger.printInfo(() -> LOG_PREFIX + "merged file too big: " + output.length());
                //noinspection ResultOfMethodCallIgnored
                output.delete();
                break;
            }
        }
        // Too big as video: the sound is what gets fact checked.
        for (Rendition a : audios) {
            if (a.size <= 0 || a.size > MAX_FILE_BYTES) continue;
            download(a.url, output, a.size, MAX_FILE_BYTES);
            return "audio";
        }
        return null;
    }

    /** "abc123" of https://v.redd.it/abc123/DASHPlaylist.mpd */
    private static String mediaFolder(String url) {
        try {
            String path = Uri.parse(url).getPath();
            if (path == null) return null;
            String[] parts = path.split("/");
            for (String part : parts) if (part.length() >= 6) return part;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Fills the renditions and returns the duration in milliseconds, or -1. */
    private static long parseManifest(String manifestUrl, byte[] body, List<Rendition> out) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(new ByteArrayInputStream(body), null);
        long durationMs = -1;
        String setMime = "";
        String setType = "";
        String setCodecs = "";
        Rendition current = null;
        boolean inBaseUrl = false;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG) {
                String tag = localName(parser.getName());
                switch (tag) {
                    case "MPD":
                        durationMs = isoDurationMs(parser.getAttributeValue(null, "mediaPresentationDuration"));
                        break;
                    case "AdaptationSet":
                        setMime = attr(parser, "mimeType");
                        setType = attr(parser, "contentType");
                        setCodecs = attr(parser, "codecs");
                        break;
                    case "Representation":
                        current = new Rendition();
                        String mime = attr(parser, "mimeType");
                        if (mime.isEmpty()) mime = setMime;
                        String codecs = attr(parser, "codecs");
                        current.codecs = (codecs.isEmpty() ? setCodecs : codecs).toLowerCase(Locale.US);
                        current.audio = mime.startsWith("audio") || setType.equals("audio")
                                || current.codecs.startsWith("mp4a");
                        current.width = toInt(attr(parser, "width"));
                        current.height = toInt(attr(parser, "height"));
                        current.bandwidth = toInt(attr(parser, "bandwidth"));
                        break;
                    case "BaseURL":
                        inBaseUrl = current != null;
                        break;
                    default:
                        break;
                }
            } else if (event == XmlPullParser.TEXT && inBaseUrl && current != null) {
                String text = parser.getText().trim();
                if (!text.isEmpty()) current.url = URI.create(manifestUrl).resolve(text).toString();
            } else if (event == XmlPullParser.END_TAG) {
                String tag = localName(parser.getName());
                if (tag.equals("BaseURL")) {
                    inBaseUrl = false;
                } else if (tag.equals("Representation")) {
                    if (current != null && isHttps(current.url)) out.add(current);
                    current = null;
                } else if (tag.equals("AdaptationSet")) {
                    setMime = setType = setCodecs = "";
                }
            }
        }
        return durationMs;
    }

    private static String localName(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static String attr(XmlPullParser parser, String name) {
        String value = parser.getAttributeValue(null, name);
        return value == null ? "" : value.trim();
    }

    private static int toInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** PT1M2.5S → 62500 */
    static long isoDurationMs(String value) {
        if (value == null) return -1;
        Matcher m = Pattern.compile("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?").matcher(value.trim());
        if (!m.matches()) return -1;
        double seconds = 0;
        if (m.group(1) != null) seconds += Long.parseLong(m.group(1)) * 86400;
        if (m.group(2) != null) seconds += Long.parseLong(m.group(2)) * 3600;
        if (m.group(3) != null) seconds += Long.parseLong(m.group(3)) * 60;
        if (m.group(4) != null) seconds += Double.parseDouble(m.group(4));
        return Math.round(seconds * 1000);
    }

    // endregion

    // region Network.

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(HTTP_TIMEOUT_MS);
        connection.setReadTimeout(HTTP_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        return connection;
    }

    private static byte[] httpGet(String url, int maxBytes) throws IOException {
        HttpURLConnection connection = open(url);
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for " + Uri.parse(url).getHost());
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > maxBytes) throw new IOException("Response too large");
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    /** @return Length of the file, or zero if unknown. */
    private static long probeLength(String url) {
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Range", "bytes=0-1");
            int code = connection.getResponseCode();
            if (code == 206) {
                String range = connection.getHeaderField("Content-Range");
                if (range != null && range.contains("/")) {
                    String total = range.substring(range.lastIndexOf('/') + 1).trim();
                    if (!total.equals("*")) return Long.parseLong(total);
                }
            } else if (code == 200) {
                return Math.max(0, connection.getContentLengthLong());
            }
        } catch (Exception ex) {
            log(LOG_PREFIX + "no length for " + url, ex);
        } finally {
            if (connection != null) connection.disconnect();
        }
        return 0;
    }

    /** Downloads in ranges. Stops after maxBytes. */
    private static void download(String url, File file, long totalLength, long maxBytes) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long position = 0;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            while (position < maxBytes && (totalLength <= 0 || position < totalLength)) {
                long end = position + RANGE_BYTES - 1;
                if (maxBytes != Long.MAX_VALUE) end = Math.min(end, maxBytes - 1);
                if (totalLength > 0) end = Math.min(end, totalLength - 1);
                final long expected = end - position + 1;
                HttpURLConnection connection = open(url);
                long received = 0;
                final int code;
                try {
                    connection.setRequestProperty("Accept-Encoding", "identity");
                    connection.setRequestProperty("Range", "bytes=" + position + "-" + end);
                    code = connection.getResponseCode();
                    if (code == 416) break;
                    if (code != 200 && code != 206) throw new IOException("Download got HTTP " + code);
                    try (InputStream in = connection.getInputStream()) {
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            long allowed = maxBytes - position;
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
                if (code == 200 || received < expected) break;
            }
        }
        final long downloaded = position;
        Logger.printInfo(() -> LOG_PREFIX + "downloaded " + downloaded + " of " + totalLength + " bytes");
        if (downloaded == 0) throw new IOException("Download was empty");
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
        return format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
    }

    /** Writes the video and audio tracks into one MP4 without encoding them again. */
    private static void mux(File videoFile, File audioFile, File output) throws IOException {
        MediaExtractor video = new MediaExtractor();
        MediaExtractor audio = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            video.setDataSource(videoFile.getAbsolutePath());
            audio.setDataSource(audioFile.getAbsolutePath());
            int videoTrack = findTrack(video, "video/");
            int audioTrack = findTrack(audio, "audio/");
            if (videoTrack < 0 || audioTrack < 0) throw new IOException("Stream has no track");
            video.selectTrack(videoTrack);
            audio.selectTrack(audioTrack);
            MediaFormat videoFormat = video.getTrackFormat(videoTrack);
            MediaFormat audioFormat = audio.getTrackFormat(audioTrack);
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outVideo = muxer.addTrack(videoFormat);
            int outAudio = muxer.addTrack(audioFormat);
            muxer.start();
            started = true;
            ByteBuffer buffer = ByteBuffer.allocate(Math.max(4 * 1024 * 1024,
                    Math.max(maxInputSize(videoFormat), maxInputSize(audioFormat))));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean videoDone = false;
            boolean audioDone = false;
            while (!videoDone || !audioDone) {
                long videoTime = videoDone ? -1 : video.getSampleTime();
                long audioTime = audioDone ? -1 : audio.getSampleTime();
                if (!videoDone && videoTime < 0) {
                    videoDone = true;
                    continue;
                }
                if (!audioDone && audioTime < 0) {
                    audioDone = true;
                    continue;
                }
                boolean writeVideo = !videoDone && (audioDone || videoTime <= audioTime);
                MediaExtractor extractor = writeVideo ? video : audio;
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    if (writeVideo) videoDone = true;
                    else audioDone = true;
                    continue;
                }
                info.set(0, size, extractor.getSampleTime(),
                        (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                muxer.writeSampleData(writeVideo ? outVideo : outAudio, buffer, info);
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

    private static String caption(Post post) {
        StringBuilder caption = new StringBuilder("#factcheck");
        if (post.permalink != null) caption.append('\n').append(post.permalink);
        if (post.title != null) caption.append('\n').append(post.title);
        String sub = post.subreddit == null ? null
                : (post.subreddit.startsWith("r/") ? post.subreddit : "r/" + post.subreddit);
        String author = post.author == null ? null : "u/" + post.author;
        if (sub != null || author != null) {
            caption.append('\n').append(sub == null ? "" : sub)
                    .append(sub != null && author != null ? " · " : "")
                    .append(author == null ? "" : author);
        }
        if (post.externalUrl != null && !post.externalUrl.equals(post.permalink)) {
            caption.append('\n').append(post.externalUrl);
        }
        String text = caption.toString();
        return text.length() > CAPTION_MAX_CHARS ? text.substring(0, CAPTION_MAX_CHARS - 1) + "…" : text;
    }

    /** Files shared before are deleted first, so Download/FactCheck does not grow. */
    private static void deleteOldSharedFiles(Context context) {
        try {
            context.getContentResolver().delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND "
                            + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                    new String[]{"%" + DOWNLOADS_FOLDER + "%", "factcheck_reddit_%"});
        } catch (Throwable ex) {
            log(LOG_PREFIX + "could not delete the old shared files", ex);
        }
    }

    /** Copies the file to Download/FactCheck, where Telegram can read it by its content uri. */
    private static Uri publish(Context context, File file, String name, String mimeType) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOADS_FOLDER);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Cannot create the shared file");
        try (InputStream in = new FileInputStream(file); OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IOException("Cannot write the shared file");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
        return uri;
    }

    private static void share(Context context, ArrayList<Shared> files, String caption) {
        try {
            try {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Fact check", caption));
            } catch (Throwable ignored) {
            }
            String chatId = TELEGRAM_CHAT_ID.get().trim();
            if (!chatId.isEmpty() && files.size() == 1) {
                for (String telegram : TELEGRAM_PACKAGES) {
                    Intent direct = sendIntent(files, caption);
                    direct.setPackage(telegram);
                    direct.putExtra(Intent.EXTRA_SHORTCUT_ID, "did3_" + chatId);
                    if (startActivity(context, direct)) return;
                }
            }
            for (String telegram : TELEGRAM_PACKAGES) {
                Intent send = sendIntent(files, caption);
                send.setPackage(telegram);
                if (startActivity(context, send)) return;
            }
            if (!startActivity(context, Intent.createChooser(sendIntent(files, caption), "Fact check"))) {
                toast("could not open the share sheet");
            }
        } catch (Throwable ex) {
            log(LOG_PREFIX + "share failed", ex);
            toast("could not open the share sheet");
        }
    }

    private static Intent sendIntent(ArrayList<Shared> files, String caption) {
        Intent send = new Intent(Intent.ACTION_SEND);
        if (files.isEmpty()) {
            send.setType("text/plain");
        } else {
            send.setType(files.get(0).mimeType);
            send.putExtra(Intent.EXTRA_STREAM, files.get(0).uri);
            send.setClipData(ClipData.newRawUri("Fact check", files.get(0).uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        send.putExtra(Intent.EXTRA_TEXT, caption);
        return send;
    }

    private static boolean startActivity(Context context, Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException ex) {
            return false;
        }
    }

    // endregion

    private static void toast(String message) {
        try {
            main().post(() -> {
                try {
                    Context context = app.morphe.extension.shared.Utils.getContext();
                    if (context != null) Toast.makeText(context, "Fact check: " + message, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void log(String message, Throwable ex) {
        try {
            Logger.printInfo(() -> message, ex instanceof Exception ? (Exception) ex : new Exception(ex));
        } catch (Throwable ignored) {
        }
    }

    private static void deleteFiles(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
