package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings({"unused", "deprecation"})
public final class GigiNavigation implements Application.ActivityLifecycleCallbacks {
    private static final String MAIN = "com.reddit.launch.main.MainActivity";
    private final Map<Integer, History> tasks = new HashMap<>();
    private static final int LIMIT = 32;

    public static void install(Application application) {
        application.registerActivityLifecycleCallbacks(new GigiNavigation());
    }

    // Replaced by the patch with calls into the verified Reddit build.
    private static Object topScreen(Activity activity) { return null; }
    private static Bundle screenArgs(Object screen) { return null; }
    private static void openScreen(Activity activity, Object screen, Object transition) { }

    private static class Entry {
        WeakReference<Object> identity;
        final Class<?> screenType;
        final Bundle args;
        final Intent intent;

        Entry(Activity activity, Object screen) {
            identity = new WeakReference<>(screen);
            if (screen != activity) {
                screenType = screen.getClass();
                Bundle source = screenArgs(screen);
                args = source == null ? new Bundle() : source.deepCopy();
                intent = null;
            } else {
                screenType = null;
                args = null;
                Intent source = activity.getIntent();
                ComponentName target = source.getComponent();
                String action = source.getAction();
                // Only replay internal browsing activities. Share, login and result flows are one-shot.
                intent = target != null && target.getPackageName().equals(activity.getPackageName())
                        && (Intent.ACTION_VIEW.equals(action) || action == null)
                        && source.getClipData() == null && activity.getCallingActivity() == null
                        ? new Intent(source).setFlags(0) : null;
            }
        }

        boolean canOpen() { return screenType != null || intent != null; }

        Object reopen(Activity activity) throws ReflectiveOperationException {
            if (screenType == null) {
                activity.startActivity(new Intent(intent));
                return null;
            }
            Object screen;
            try {
                screen = screenType.getConstructor(Bundle.class).newInstance(args.deepCopy());
            } catch (NoSuchMethodException e) {
                screen = screenType.getConstructor().newInstance();
                screenArgs(screen).putAll(args.deepCopy());
            }
            openScreen(activity, screen, null);
            return screen;
        }
    }

    private static class History {
        final ArrayList<Entry> entries = new ArrayList<>();
        int index = -1;
        Object pendingScreen;
        ComponentName pendingActivity;
        long pendingUntil;

        void clearPending() {
            pendingScreen = pendingActivity = null;
            pendingUntil = 0;
        }

        void observe(Activity activity, Object screen) {
            if (screen == null || index >= 0 && entries.get(index).identity.get() == screen) return;
            if (pendingUntil > SystemClock.uptimeMillis() && index + 1 < entries.size()
                    && (screen == pendingScreen || (pendingActivity != null
                    && pendingActivity.equals(activity.getComponentName())))) {
                entries.get(++index).identity = new WeakReference<>(screen);
                clearPending();
                return;
            }
            clearPending();
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (entries.get(i).identity.get() == screen) {
                    index = i;
                    return;
                }
            }
            entries.subList(index + 1, entries.size()).clear();
            entries.add(new Entry(activity, screen));
            if (entries.size() > LIMIT) entries.remove(0);
            index = entries.size() - 1;
        }

        boolean canForward() {
            return index + 1 < entries.size() && entries.get(index + 1).canOpen()
                    && SystemClock.uptimeMillis() >= pendingUntil;
        }

        void forward(Activity activity) {
            if (!canForward()) return;
            Entry next = entries.get(index + 1);
            try {
                pendingUntil = SystemClock.uptimeMillis() + 2000;
                pendingActivity = next.intent == null ? null : next.intent.getComponent();
                pendingScreen = next.reopen(activity);
            } catch (ReflectiveOperationException | RuntimeException e) {
                clearPending();
                android.util.Log.w("GigiNavigation", "Could not reopen " + next.screenType, e);
            }
        }
    }

    private static class Controls extends LinearLayout {
        final Activity activity;
        final History history;
        final Button forward;

        Controls(Activity activity, History history) {
            super(activity);
            this.activity = activity;
            this.history = history;
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xd9363636);
            background.setCornerRadius(dp(24));
            setBackground(background);
            setElevation(dp(6));
            Button back = button("‹", "Back");
            forward = button("›", "Forward");
            back.setOnClickListener(v -> activity.onBackPressed());
            forward.setOnClickListener(v -> {
                refresh();
                history.forward(activity);
                refresh();
                postDelayed(this::refresh, 2100);
            });
            getViewTreeObserver().addOnGlobalLayoutListener(this::refresh);
        }

        int dp(int n) { return Math.round(getResources().getDisplayMetrics().density * n); }

        Button button(String glyph, String label) {
            Button b = new Button(activity, null, android.R.attr.borderlessButtonStyle);
            b.setPadding(0, 0, 0, 0);
            b.setText(glyph);
            b.setTextSize(26);
            b.setTextColor(Color.WHITE);
            b.setContentDescription(label);
            b.setTooltipText(label);
            addView(b, new LinearLayout.LayoutParams(dp(48), dp(48)));
            return b;
        }

        void refresh() {
            if (activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()) return;
            Object screen = activity;
            if (MAIN.equals(activity.getClass().getName())) {
                try {
                    screen = topScreen(activity);
                } catch (RuntimeException ignored) {
                    // The router can be unavailable during startup and teardown.
                    return;
                }
            }
            history.observe(activity, screen);
            boolean enabled = history.canForward();
            if (forward.isEnabled() != enabled) forward.setEnabled(enabled);
            forward.setAlpha(enabled ? 1f : .45f);
        }

        @Override public void onWindowFocusChanged(boolean focus) {
            super.onWindowFocusChanged(focus);
            if (focus) post(this::refresh);
        }
    }

    @Override public void onActivityResumed(Activity activity) {
        FrameLayout decor = (FrameLayout) activity.getWindow().getDecorView();
        for (int i = 0; i < decor.getChildCount(); i++) {
            if (decor.getChildAt(i) instanceof Controls) return;
        }
        History history = tasks.computeIfAbsent(activity.getTaskId(), ignored -> new History());
        Controls controls = new Controls(activity, history);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        params.setMarginEnd(controls.dp(8));
        params.bottomMargin = controls.dp(64);
        controls.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = controls.dp(64) + insets.getSystemWindowInsetBottom();
            if (params.bottomMargin != bottom) {
                params.bottomMargin = bottom;
                controls.setLayoutParams(params);
            }
            return insets;
        });
        decor.addView(controls, params);
        controls.requestApplyInsets();
    }

    @Override public void onActivityDestroyed(Activity a) {
        if (a.isTaskRoot() && a.isFinishing()) tasks.remove(a.getTaskId());
    }
    @Override public void onActivityCreated(Activity a, Bundle s) { }
    @Override public void onActivityStarted(Activity a) { }
    @Override public void onActivityPaused(Activity a) { }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle s) { }
}
