package app.morphe.extension.reddit.patches;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import kotlin.jvm.functions.Function1;

@SuppressWarnings("unused")
public final class GigiPlayerControls extends LinearLayout {
    // Keep concrete erased invoke(Object) methods in the extension DEX. The call sites
    // live in Reddit's Compose code, outside the extension shrinker's call graph.
    private static final class Factory implements Function1<Object, Object> {
        @Override public Object invoke(Object context) {
            return new GigiPlayerControls((Context) context);
        }
    }

    private static final Factory FACTORY = new Factory();
    private final Button playButton;
    private final SeekBar scrubber;
    private Object model;
    private Object dragPlayer;
    private final Runnable tick = this::refresh;

    private static final class Update implements Function1<Object, Object> {
        private final Object model;

        Update(Object model) { this.model = model; }

        @Override public Object invoke(Object view) {
            GigiPlayerControls controls = (GigiPlayerControls) view;
            if (controls.model != model) {
                controls.model = model;
                controls.dragPlayer = null;
                controls.refresh();
            }
            return null;
        }
    }

    public static void draw(Object model, Object composer) {
        compose(FACTORY, null, new Update(model), composer, 0, 2);
    }

    // The Kotlin patch supplies the app's Compose AndroidView and Media3 bindings.
    private static void compose(Function1<?, ?> factory, Object modifier, Function1<?, ?> update,
                                Object composer, int flags, int defaults) {}
    private static Object player(Object model) { return null; }
    private static long position(Object p) { return -1L; }
    private static long duration(Object p) { return -1L; }
    private static boolean wantsPlay(Object p) { return false; }
    private static void play(Object p, boolean value) {}
    private static void seek(Object p, long millis, int currentItem) {}

    private GigiPlayerControls(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xbb202020);
        bg.setCornerRadius(dp(16));
        setBackground(bg);
        button("−10", "Back ten seconds").setOnClickListener(v -> skip(-10000));
        playButton = button("▶", "Play");
        playButton.setOnClickListener(v -> {
            Object p = current();
            if (p != null) play(p, !wantsPlay(p));
            refresh();
        });
        button("+10", "Forward ten seconds").setOnClickListener(v -> skip(10000));
        scrubber = new SeekBar(context);
        scrubber.setContentDescription("Video position");
        scrubber.setMax(1000);
        addView(scrubber, new LayoutParams(dp(92), dp(44)));
        scrubber.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { dragPlayer = current(); }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                Object p = current();
                if (p != null && p == dragPlayer && duration(p) > 0) {
                    seek(p, duration(p) * bar.getProgress() / bar.getMax(), 0);
                }
                dragPlayer = null;
                refresh();
            }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {}
        });
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private Object current() { return model == null ? null : player(model); }

    private Button button(String text, String label) {
        Button b = new Button(getContext(), null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setPadding(0, 0, 0, 0);
        b.setContentDescription(label);
        addView(b, new LayoutParams(dp(48), dp(48)));
        return b;
    }

    private void skip(long delta) {
        Object p = current();
        if (p == null || duration(p) <= 0) return;
        seek(p, Math.max(0, Math.min(duration(p), position(p) + delta)), 0);
        refresh();
    }

    private void refresh() {
        removeCallbacks(tick);
        if (!isAttachedToWindow() || getWindowVisibility() != VISIBLE) return;
        Object p = current();
        long length = p == null ? 0 : duration(p);
        boolean playing = p != null && wantsPlay(p);
        String label = playing ? "Pause" : "Play";
        if (!label.contentEquals(playButton.getContentDescription())) {
            playButton.setText(playing ? "Ⅱ" : "▶");
            playButton.setContentDescription(label);
        }
        playButton.setEnabled(p != null);
        scrubber.setEnabled(length > 0);
        if (dragPlayer == null) scrubber.setProgress(length <= 0 ? 0 : (int)(position(p) * 1000 / length));
        postDelayed(tick, 500);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(tick); }
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        dragPlayer = null;
        super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (tick != null && visibility == VISIBLE) post(tick);
    }
}
