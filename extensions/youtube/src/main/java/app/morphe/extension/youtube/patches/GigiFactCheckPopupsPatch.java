package app.morphe.extension.youtube.patches;

import static java.lang.Boolean.TRUE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.StringSetting;

/**
 * Fact check pop-ups (GigiMorphs).
 * <p>
 * When a video opens, asks the fact checker's relay for the fact check of that video
 * ({@code GET <server>/fc/<videoId>} with header {@code X-Read-Key}). If there is one, each checked
 * claim pops up over the player at the moment it is said, for {@link #CARD_SHOW_MS}.
 * Tap a pop-up for the details and sources, or for the list of every claim (tap one to jump to it).
 * <p>
 * Nothing is requested until both the server address and the key are set in the settings.
 */
@SuppressWarnings("unused")
public final class GigiFactCheckPopupsPatch {

    private static final String LOG_PREFIX = "GigiMorphs fact check pop-ups: ";

    public static final BooleanSetting POPUPS = new BooleanSetting("gigi_factcheck_popups", TRUE);
    /** Address of the fact checker's relay, for example https://example.duckdns.org */
    public static final StringSetting POPUPS_SERVER = new StringSetting("gigi_factcheck_popups_server", "");
    /** The relay's read-only key (FC_READ_KEY): it can only read stored fact checks. */
    public static final StringSetting POPUPS_KEY = new StringSetting("gigi_factcheck_popups_key", "", false, false);

    /** How long, in video time, a claim stays on screen after it is said. */
    private static final long CARD_SHOW_MS = 12_000;
    /** How long the "this video has a fact check" banner stays, in real time. */
    private static final long BANNER_SHOW_MS = 6_000;
    /** Ask the server again after this long, so a fact check made later shows up. */
    private static final long CACHE_MS = 10 * 60_000;
    private static final int HTTP_TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private static final class Card {
        final long timeMs;
        final String emoji;
        final String claim;
        final String detail;
        final List<String[]> links; // {title, url}

        Card(long timeMs, String emoji, String claim, String detail, List<String[]> links) {
            this.timeMs = timeMs;
            this.emoji = emoji;
            this.claim = claim;
            this.detail = detail;
            this.links = links;
        }
    }

    private static final class FactCheck {
        final String videoId;
        final String title;
        final String verdict;
        final List<Card> cards;
        final List<String[]> sources;
        final long fetchedAt;

        FactCheck(String videoId, String title, String verdict, List<Card> cards, List<String[]> sources) {
            this.videoId = videoId;
            this.title = title;
            this.verdict = verdict;
            this.cards = cards;
            this.sources = sources;
            this.fetchedAt = System.currentTimeMillis();
        }
    }

    /** Fact checks by video id; a stored {@code null} means the server has none (yet). */
    private static final Map<String, FactCheck> cache = Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, Long> missingSince = Collections.synchronizedMap(new HashMap<>());

    private static volatile String currentVideoId = "";
    @Nullable
    private static volatile FactCheck current;
    /** Card on screen, or -1. */
    private static int shownIndex = -1;
    /** Card the user closed, so it does not come back while it would still be showing. */
    private static int dismissedIndex = -1;
    private static boolean bannerShowing;

    private static WeakReference<ViewGroup> rootRef = new WeakReference<>(null);
    private static WeakReference<LinearLayout> popupRef = new WeakReference<>(null);
    private static WeakReference<TextView> titleRef = new WeakReference<>(null);
    private static WeakReference<TextView> detailRef = new WeakReference<>(null);

    private GigiFactCheckPopupsPatch() {
    }

    private static boolean enabled() {
        return POPUPS.get() && !POPUPS_SERVER.get().trim().isEmpty() && !POPUPS_KEY.get().trim().isEmpty();
    }

    /**
     * Injection point: the player's overlay layout was created.
     */
    public static void initialize(ViewGroup overlayRoot) {
        try {
            Context context = overlayRoot.getContext();
            LinearLayout popup = new LinearLayout(context);
            popup.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(context, 10);
            popup.setPadding(pad, dp(context, 8), pad, dp(context, 8));
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.argb(230, 24, 24, 27));
            background.setCornerRadius(dp(context, 12));
            popup.setBackground(background);
            popup.setElevation(dp(context, 4));

            TextView title = new TextView(context);
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            popup.addView(title);

            TextView detail = new TextView(context);
            detail.setTextColor(Color.argb(220, 230, 230, 235));
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            detail.setMaxLines(2);
            detail.setEllipsize(TextUtils.TruncateAt.END);
            detail.setPadding(0, dp(context, 2), 0, 0);
            popup.addView(detail);

            TextView hint = new TextView(context);
            hint.setTextColor(Color.argb(160, 200, 200, 210));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            hint.setText("Tap for details · hold to close");
            hint.setPadding(0, dp(context, 3), 0, 0);
            popup.addView(hint);

            popup.setOnClickListener(v -> openDetails());
            popup.setOnLongClickListener(v -> {
                if (bannerShowing) {
                    bannerShowing = false;
                } else {
                    dismissedIndex = shownIndex;
                }
                hide();
                return true;
            });
            popup.setVisibility(View.GONE);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START);
            params.setMargins(dp(context, 12), dp(context, 44), dp(context, 12), 0);
            overlayRoot.addView(popup, params);
            // Keep it narrow enough to leave the video visible.
            overlayRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                int max = Math.min(dp(context, 360), (int) ((r - l) * 0.62f));
                if (max > 0 && title.getMaxWidth() != max) {
                    title.setMaxWidth(max);
                    detail.setMaxWidth(max);
                }
            });

            rootRef = new WeakReference<>(overlayRoot);
            popupRef = new WeakReference<>(popup);
            titleRef = new WeakReference<>(title);
            detailRef = new WeakReference<>(detail);
        } catch (Exception ex) {
            Logger.printException(() -> LOG_PREFIX + "initialize failure", ex);
        }
    }

    /**
     * Injection point: a new video was loaded.
     */
    public static void newVideoLoaded(String videoId) {
        try {
            if (videoId == null || videoId.equals(currentVideoId)) {
                return;
            }
            currentVideoId = videoId;
            current = null;
            shownIndex = -1;
            dismissedIndex = -1;
            Utils.runOnMainThread(GigiFactCheckPopupsPatch::hide);
            if (!enabled()) {
                return;
            }

            FactCheck cached = cache.get(videoId);
            if (cached != null && System.currentTimeMillis() - cached.fetchedAt < CACHE_MS) {
                setCurrent(cached);
                return;
            }
            Long missing = missingSince.get(videoId);
            if (missing != null && System.currentTimeMillis() - missing < CACHE_MS) {
                return;
            }
            Utils.runOnBackgroundThread(() -> {
                try {
                    FactCheck factCheck = fetch(videoId);
                    if (factCheck == null) {
                        missingSince.put(videoId, System.currentTimeMillis());
                        return;
                    }
                    cache.put(videoId, factCheck);
                    if (videoId.equals(currentVideoId)) {
                        setCurrent(factCheck);
                    }
                } catch (Exception ex) {
                    Logger.printInfo(() -> LOG_PREFIX + "could not get the fact check for " + videoId, ex);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> LOG_PREFIX + "newVideoLoaded failure", ex);
        }
    }

    /**
     * Injection point: the video time, about once a second.
     */
    public static void setVideoTime(long videoTimeMs) {
        FactCheck factCheck = current;
        if (factCheck == null || factCheck.cards.isEmpty() || !POPUPS.get()) {
            return;
        }
        try {
            int index = -1;
            List<Card> cards = factCheck.cards;
            for (int i = 0; i < cards.size(); i++) {
                if (cards.get(i).timeMs <= videoTimeMs) {
                    index = i;
                } else {
                    break;
                }
            }
            final int show = (index >= 0 && videoTimeMs - cards.get(index).timeMs < CARD_SHOW_MS
                    && index != dismissedIndex) ? index : -1;
            if (show == shownIndex && !bannerShowing) {
                return;
            }
            Utils.runOnMainThread(() -> {
                if (current != factCheck) {
                    return;
                }
                if (show < 0) {
                    if (!bannerShowing) {
                        hide();
                    }
                } else if (show != shownIndex || bannerShowing) {
                    Card card = cards.get(show);
                    bannerShowing = false;
                    showPopup(card.emoji + " " + formatTime(card.timeMs) + "  " + card.claim, card.detail);
                }
                shownIndex = show;
            });
        } catch (Exception ex) {
            Logger.printException(() -> LOG_PREFIX + "setVideoTime failure", ex);
        }
    }

    private static void setCurrent(FactCheck factCheck) {
        current = factCheck;
        Utils.runOnMainThread(() -> {
            if (current != factCheck || shownIndex >= 0) {
                return;
            }
            bannerShowing = true;
            showPopup("🔎 Fact checked: " + factCheck.cards.size() + " claims",
                    factCheck.verdict.isEmpty() ? factCheck.title : factCheck.verdict);
            Utils.runOnMainThreadDelayed(() -> {
                if (bannerShowing && current == factCheck) {
                    bannerShowing = false;
                    hide();
                }
            }, BANNER_SHOW_MS);
        });
    }

    private static void showPopup(String title, String detail) {
        LinearLayout popup = popupRef.get();
        TextView titleView = titleRef.get();
        TextView detailView = detailRef.get();
        if (popup == null || titleView == null || detailView == null) {
            return;
        }
        titleView.setText(title);
        detailView.setText(detail);
        detailView.setVisibility(TextUtils.isEmpty(detail) ? View.GONE : View.VISIBLE);
        popup.animate().cancel();
        if (popup.getVisibility() != View.VISIBLE) {
            popup.setAlpha(0f);
            popup.setVisibility(View.VISIBLE);
        }
        popup.bringToFront();
        popup.animate().alpha(1f).setDuration(180).setListener(null).start();
    }

    private static void hide() {
        LinearLayout popup = popupRef.get();
        if (popup == null || popup.getVisibility() != View.VISIBLE) {
            return;
        }
        popup.animate().cancel();
        popup.animate().alpha(0f).setDuration(180).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                popup.setVisibility(View.GONE);
            }
        }).start();
    }

    private static void openDetails() {
        try {
            FactCheck factCheck = current;
            Activity activity = Utils.getActivity();
            if (factCheck == null || activity == null) {
                return;
            }
            int index = shownIndex;
            if (bannerShowing || index < 0 || index >= factCheck.cards.size()) {
                showAllClaims(activity, factCheck);
                return;
            }
            Card card = factCheck.cards.get(index);
            List<String[]> links = new ArrayList<>(card.links);
            if (links.isEmpty()) {
                links.addAll(factCheck.sources);
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle(card.emoji + " " + formatTime(card.timeMs) + "  " + card.claim)
                    .setMessage(card.detail)
                    .setNeutralButton("All claims", (d, w) -> showAllClaims(activity, factCheck))
                    .setNegativeButton("Close", null);
            if (!links.isEmpty()) {
                builder.setPositiveButton("Sources", (d, w) -> showLinks(activity, links));
            }
            builder.show();
        } catch (Exception ex) {
            Logger.printException(() -> LOG_PREFIX + "openDetails failure", ex);
        }
    }

    private static void showAllClaims(Activity activity, FactCheck factCheck) {
        CharSequence[] items = new CharSequence[factCheck.cards.size()];
        for (int i = 0; i < items.length; i++) {
            Card card = factCheck.cards.get(i);
            items[i] = card.emoji + " " + formatTime(card.timeMs) + "  " + card.claim;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(factCheck.verdict.isEmpty() ? factCheck.title : factCheck.verdict)
                .setItems(items, (d, which) -> {
                    dismissedIndex = -1;
                    VideoInformation.seekTo(factCheck.cards.get(which).timeMs);
                })
                .setNegativeButton("Close", null);
        if (!factCheck.sources.isEmpty()) {
            builder.setPositiveButton("Sources", (d, w) -> showLinks(activity, factCheck.sources));
        }
        builder.show();
    }

    private static void showLinks(Activity activity, List<String[]> links) {
        CharSequence[] items = new CharSequence[links.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = links.get(i)[0];
        }
        new AlertDialog.Builder(activity)
                .setTitle("Sources")
                .setItems(items, (d, which) -> Utils.openLink(links.get(which)[1]))
                .setNegativeButton("Close", null)
                .show();
    }

    @Nullable
    private static FactCheck fetch(String videoId) throws Exception {
        String server = POPUPS_SERVER.get().trim();
        while (server.endsWith("/")) {
            server = server.substring(0, server.length() - 1);
        }
        if (!server.startsWith("https://")) {
            Logger.printInfo(() -> LOG_PREFIX + "the server address must start with https://");
            return null;
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(server + "/fc/" + videoId).openConnection();
        try {
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            connection.setRequestProperty("X-Read-Key", POPUPS_KEY.get().trim());
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code != 200) {
                Logger.printInfo(() -> LOG_PREFIX + "server answered " + code + " for " + videoId);
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > MAX_RESPONSE_BYTES) {
                        throw new IllegalStateException("fact check too large");
                    }
                }
                return parse(videoId, new JSONObject(out.toString(StandardCharsets.UTF_8.name())));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static FactCheck parse(String videoId, JSONObject json) {
        List<Card> cards = new ArrayList<>();
        JSONArray array = json.optJSONArray("cards");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject c = array.optJSONObject(i);
                if (c == null) {
                    continue;
                }
                String claim = c.optString("claim", "");
                if (claim.isEmpty()) {
                    continue;
                }
                cards.add(new Card(c.optLong("t", 0) * 1000, c.optString("emoji", "🔎"), claim,
                        c.optString("detail", ""), links(c.optJSONArray("links"))));
            }
        }
        Collections.sort(cards, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        return new FactCheck(videoId, json.optString("title", ""), json.optString("verdict", ""),
                cards, links(json.optJSONArray("sources")));
    }

    private static List<String[]> links(@Nullable JSONArray array) {
        List<String[]> links = new ArrayList<>();
        if (array == null) {
            return links;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject link = array.optJSONObject(i);
            String url = link == null ? "" : link.optString("url", "");
            if (url.startsWith("https://") || url.startsWith("http://")) {
                String title = link.optString("title", "");
                links.add(new String[]{title.isEmpty() ? url : title, url});
            }
        }
        return links;
    }

    private static String formatTime(long ms) {
        long s = ms / 1000;
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private static int dp(Context context, float dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
