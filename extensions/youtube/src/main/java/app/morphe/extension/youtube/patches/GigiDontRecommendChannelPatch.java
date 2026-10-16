package app.morphe.extension.youtube.patches;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.patches.utils.requests.PlaylistRoutes;

/**
 * GigiMorphs.
 * <p>
 * Adds "Don't recommend channel" to the menu of a Short in the feed (Shorts shelf).
 * YouTube only puts that option in the Shorts player menu, so this fetches that menu for the Short
 * (reel/reel_item_watch), takes the "Don't recommend channel" feedback token from it and
 * sends it to the feedback endpoint, same as tapping the option inside the Short.
 */
@SuppressWarnings("unused")
public final class GigiDontRecommendChannelPatch {

    private static final String BUTTON_TEXT = "Don't recommend channel";

    private static final Route.CompiledRoute REEL_ITEM_WATCH = new Route(
            Route.Method.POST, "reel/reel_item_watch?prettyPrint=false"
    ).compile();

    private static final Route.CompiledRoute FEEDBACK = new Route(
            Route.Method.POST, "feedback?prettyPrint=false"
    ).compile();

    private static final String[] ICON_CANDIDATES = {
            "yt_outline_x_circle_black_24",
            "yt_outline_x_circle_white_24",
            "yt_outline_remove_circle_black_24",
            "yt_outline_circle_slash_black_24",
    };

    private static volatile boolean requestRunning;

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Called from FlyoutUtils when a flyout menu opens.
     */
    public static int addFlyoutButton(Object flyoutPanel, int nextButtonIndex,
                                      String flyoutVideoId, boolean isShortFlyout) {
        if (!isShortFlyout || TextUtils.isEmpty(flyoutVideoId)) {
            return nextButtonIndex;
        }
        final String videoId = flyoutVideoId;
        Logger.printDebug(() -> "GigiMorphs: adding Don't recommend channel button for Short: " + videoId);
        return FlyoutUtils.addFlyoutButton(
                flyoutPanel,
                getIcon(),
                BUTTON_TEXT,
                v -> {
                    FlyoutUtils.dismissFlyout();
                    dontRecommendChannel(videoId);
                },
                nextButtonIndex
        );
    }

    @Nullable
    private static Drawable getIcon() {
        try {
            Context context = Utils.getContext();
            if (context == null) return null;
            for (String name : ICON_CANDIDATES) {
                int id = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, name);
                if (id != 0) return context.getDrawable(id);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "GigiMorphs: icon lookup failed", ex);
        }
        return null;
    }

    private static void dontRecommendChannel(String videoId) {
        if (requestRunning) {
            return;
        }
        if (AuthUtils.isNotLoggedIn()) {
            Utils.showToastShort("Sign in first");
            return;
        }
        requestRunning = true;
        final Map<String, String> authHeaders = AuthUtils.getRequestHeader();
        Utils.runOnBackgroundThread(() -> {
            try {
                String token = fetchDontRecommendToken(videoId, authHeaders);
                if (token == null) {
                    Utils.showToastShort("Couldn't find Don't recommend channel for this Short");
                    return;
                }
                boolean ok = sendFeedback(token, authHeaders);
                Utils.showToastShort(ok
                        ? "Got it, this channel won't be recommended"
                        : "Don't recommend channel failed");
            } catch (Exception ex) {
                Logger.printException(() -> "GigiMorphs: dontRecommendChannel failure", ex);
                Utils.showToastShort("Don't recommend channel failed");
            } finally {
                requestRunning = false;
            }
        });
    }

    private static JSONObject androidContext() throws Exception {
        JSONObject client = new JSONObject();
        client.put("clientName", "ANDROID");
        client.put("clientVersion", "20.26.46");
        client.put("deviceMake", Build.MANUFACTURER);
        client.put("deviceModel", Build.MODEL);
        client.put("osName", "Android");
        client.put("osVersion", Build.VERSION.RELEASE);
        client.put("androidSdkVersion", Build.VERSION.SDK_INT);
        Locale locale = Locale.getDefault();
        client.put("hl", locale.getLanguage());
        client.put("gl", locale.getCountry());
        JSONObject context = new JSONObject();
        context.put("client", client);
        return context;
    }

    @Nullable
    private static JSONObject post(Route.CompiledRoute route, JSONObject body,
                                   Map<String, String> authHeaders) throws Exception {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = PlaylistRoutes.getConnection(route, authHeaders);
        connection.setFixedLengthStreamingMode(bytes.length);
        connection.getOutputStream().write(bytes);
        int code = connection.getResponseCode();
        if (code == Requester.HTTP_STATUS_CODE_SUCCESS) {
            return Requester.parseJSONObject(connection);
        }
        Logger.printDebug(() -> "GigiMorphs: " + route + " failed with code: " + code);
        return null;
    }

    private record MenuItem(String iconType, String text, String token) {}

    @Nullable
    private static String fetchDontRecommendToken(String videoId, Map<String, String> authHeaders) throws Exception {
        JSONObject body = new JSONObject();
        body.put("context", androidContext());
        JSONObject playerRequest = new JSONObject();
        playerRequest.put("videoId", videoId);
        body.put("playerRequest", playerRequest);
        body.put("disablePlayerResponse", true);

        JSONObject json = post(REEL_ITEM_WATCH, body, authHeaders);
        if (json == null) {
            return null;
        }

        List<MenuItem> items = new ArrayList<>();
        collectFeedbackItems(json, items);
        for (MenuItem item : items) {
            Logger.printDebug(() -> "GigiMorphs: Short menu feedback item, icon: "
                    + item.iconType + " text: " + item.text);
        }

        // 1. The Shorts player shows "Don't recommend this channel" with the REMOVE icon.
        for (MenuItem item : items) {
            if ("REMOVE".equals(item.iconType)) return item.token;
        }
        // 2. Text match (English and other languages that mention "channel" in the label).
        for (MenuItem item : items) {
            String t = item.text.toLowerCase(Locale.ROOT);
            if (t.contains("recommend") || t.contains("channel")) return item.token;
        }
        // 3. Anything that is not the "Not interested" item.
        for (MenuItem item : items) {
            if (!"NOT_INTERESTED".equals(item.iconType) && !"HIDE".equals(item.iconType)
                    && !"FLAG".equals(item.iconType)) {
                return item.token;
            }
        }
        return null;
    }

    private static void collectFeedbackItems(Object node, List<MenuItem> out) {
        if (node instanceof JSONObject obj) {
            JSONObject renderer = obj.optJSONObject("menuServiceItemRenderer");
            if (renderer != null) {
                JSONObject endpoint = renderer.optJSONObject("serviceEndpoint");
                JSONObject feedback = endpoint != null ? endpoint.optJSONObject("feedbackEndpoint") : null;
                String token = feedback != null ? feedback.optString("feedbackToken", "") : "";
                if (!token.isEmpty()) {
                    JSONObject icon = renderer.optJSONObject("icon");
                    String iconType = icon != null ? icon.optString("iconType", "") : "";
                    out.add(new MenuItem(iconType, readText(renderer.optJSONObject("text")), token));
                }
            }
            JSONArray names = obj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    collectFeedbackItems(obj.opt(names.optString(i)), out);
                }
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                collectFeedbackItems(array.opt(i), out);
            }
        }
    }

    private static String readText(@Nullable JSONObject text) {
        if (text == null) return "";
        String simple = text.optString("simpleText", "");
        if (!simple.isEmpty()) return simple;
        JSONArray runs = text.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) sb.append(run.optString("text", ""));
        }
        return sb.toString();
    }

    private static boolean sendFeedback(String token, Map<String, String> authHeaders) throws Exception {
        JSONObject body = new JSONObject();
        body.put("context", androidContext());
        JSONArray tokens = new JSONArray();
        tokens.put(token);
        body.put("feedbackTokens", tokens);
        body.put("isFeedbackTokenUnencrypted", false);
        body.put("shouldMerge", false);

        JSONObject json = post(FEEDBACK, body, authHeaders);
        if (json == null) return false;
        Logger.printDebug(() -> "GigiMorphs: feedback response: " + json);
        JSONArray responses = json.optJSONArray("feedbackResponses");
        if (responses == null || responses.length() == 0) {
            // Some responses only carry responseContext; a 200 means it was accepted.
            return true;
        }
        JSONObject first = responses.optJSONObject(0);
        return first == null || first.optBoolean("isProcessed", true);
    }
}
