package app.morphe.extension.youtube.patches;

import static java.lang.Boolean.TRUE;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.StringSetting;

/**
 * GigiMorphs: upload a log snapshot to a private GitHub repo.
 * <p>
 * Manual from settings, and automatically after a crash (on the next start).
 * Nothing piles up on the phone: Morphe's log buffer is in memory and capped, logcat is
 * read on demand, and at most one pending crash snapshot is kept on disk.
 */
@SuppressWarnings("unused")
public final class GigiLogUploadPatch {

    public static final StringSetting TOKEN = new StringSetting("gigi_logs_token", "", false, false);
    public static final StringSetting REPO = new StringSetting("gigi_logs_repo", "Gigimooshi2/GigiMorphs-logs");
    public static final BooleanSetting AUTO_CRASH = new BooleanSetting("gigi_logs_auto_crash", TRUE);

    private static final int LOGCAT_LINES = 3000;
    private static final int MAX_TRACE_BYTES = 512 * 1024;
    private static final int MAX_AUTO_UPLOADS_PER_DAY = 6;
    private static final String PENDING = "pending-crash.log.gz";

    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final AtomicBoolean uploading = new AtomicBoolean();

    /**
     * Injection point: main activity onCreate.
     */
    public static void install(Activity activity) {
        if (!installed.compareAndSet(false, true)) return;
        try {
            final Context app = activity.getApplicationContext();
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
                try {
                    StringWriter sw = new StringWriter();
                    ex.printStackTrace(new PrintWriter(sw));
                    byte[] snapshot = snapshot(app, "crash",
                            "Uncaught exception on thread " + thread.getName() + "\n" + sw);
                    File f = pendingFile(app);
                    try (OutputStream out = new FileOutputStream(f, false)) {
                        out.write(snapshot);
                    }
                } catch (Throwable ignored) {
                    // Never get in the way of the real crash.
                }
                if (previous != null) previous.uncaughtException(thread, ex);
            });

            Utils.runOnBackgroundThread(() -> afterStart(app));
        } catch (Exception ex) {
            Logger.printException(() -> "GigiLogs: install failed", ex);
        }
    }

    /** Upload what the last run left behind: a Java crash snapshot, or a native crash / ANR trace. */
    private static void afterStart(Context app) {
        try {
            if (!AUTO_CRASH.get() || TOKEN.get().trim().isEmpty()) return;

            File pending = pendingFile(app);
            if (pending.exists() && autoAllowed(app)) {
                byte[] data = readAll(new FileInputStream(pending), Integer.MAX_VALUE);
                if (put(name("crash"), data, "Crash snapshot")) {
                    //noinspection ResultOfMethodCallIgnored
                    pending.delete();
                    countAuto(app);
                }
            }

            if (Build.VERSION.SDK_INT >= 30) uploadExitInfo(app);
        } catch (Exception ex) {
            Logger.printException(() -> "GigiLogs: auto upload failed", ex);
        }
    }

    @android.annotation.TargetApi(30)
    private static void uploadExitInfo(Context app) throws Exception {
        SharedPreferences prefs = prefs(app);
        ActivityManager am = app.getSystemService(ActivityManager.class);
        if (am == null) return;
        List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(null, 0, 8);
        long seen = prefs.getLong("exitSeen", -1);
        long newest = seen;
        for (ApplicationExitInfo e : exits) newest = Math.max(newest, e.getTimestamp());
        if (seen < 0) {
            // First run: start from now, don't upload old history.
            prefs.edit().putLong("exitSeen", newest).apply();
            return;
        }
        for (ApplicationExitInfo e : exits) {
            if (e.getTimestamp() <= seen) continue;
            int r = e.getReason();
            if (r != ApplicationExitInfo.REASON_CRASH_NATIVE && r != ApplicationExitInfo.REASON_ANR) continue;
            if (!autoAllowed(app)) break;
            String kind = r == ApplicationExitInfo.REASON_ANR ? "anr" : "native-crash";
            StringBuilder text = new StringBuilder();
            text.append(header(app, kind))
                    .append("Process: ").append(e.getProcessName())
                    .append("\nWhen: ").append(stamp(e.getTimestamp()))
                    .append("\nDescription: ").append(e.getDescription())
                    .append("\n\n--- trace ---\n");
            InputStream trace = e.getTraceInputStream();
            if (trace != null) text.append(new String(readAll(trace, MAX_TRACE_BYTES), StandardCharsets.UTF_8));
            else text.append("(no trace)\n");
            if (put(name(kind), gzip(text.toString()), kind)) countAuto(app);
        }
        prefs.edit().putLong("exitSeen", newest).apply();
    }

    /** Settings button. */
    public static void uploadNow() {
        if (TOKEN.get().trim().isEmpty()) {
            Utils.showToastLong("GigiMorphs: set the log upload token first");
            return;
        }
        if (!uploading.compareAndSet(false, true)) return;
        Utils.showToastShort("GigiMorphs: uploading logs…");
        Utils.runOnBackgroundThread(() -> {
            try {
                Context app = Utils.getContext();
                boolean ok = put(name("manual"), snapshot(app, "manual", null), "Manual upload");
                Utils.showToastLong(ok ? "GigiMorphs: logs uploaded" : "GigiMorphs: upload failed, check token and repo");
            } catch (Exception ex) {
                Logger.printException(() -> "GigiLogs: manual upload failed", ex);
                Utils.showToastLong("GigiMorphs: upload failed: " + ex.getMessage());
            } finally {
                uploading.set(false);
            }
        });
    }

    // ---------------------------------------------------------------------------------------

    private static byte[] snapshot(Context app, String reason, String extra) throws Exception {
        StringBuilder sb = new StringBuilder(256 * 1024);
        sb.append(header(app, reason));
        if (extra != null) sb.append("\n--- crash ---\n").append(extra).append('\n');

        sb.append("\n--- morphe log buffer");
        if (!BaseSettings.DEBUG.get()) sb.append(" (Debug logging is off, so this is mostly empty)");
        sb.append(" ---\n").append(Logger.getFilteredLogs()).append('\n');

        sb.append("\n--- logcat (this process, last ").append(LOGCAT_LINES).append(" lines) ---\n");
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-v", "threadtime", "-t", String.valueOf(LOGCAT_LINES),
                    "--pid=" + Process.myPid()
            });
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.destroy();
        } catch (Exception ex) {
            sb.append("(logcat unavailable: ").append(ex).append(")\n");
        }
        return gzip(sb.toString());
    }

    private static String header(Context app, String reason) {
        return "GigiMorphs log: " + reason
                + "\nTime: " + stamp(System.currentTimeMillis())
                + "\nYouTube: " + Utils.getAppVersionName()
                + "\nPatches: " + Utils.getPatchesReleaseVersion()
                + "\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"
                + "\nDebug logging: " + BaseSettings.DEBUG.get()
                + "\n";
    }

    private static boolean put(String fileName, byte[] gz, String what) throws Exception {
        String repo = REPO.get().trim();
        String token = TOKEN.get().trim();
        if (repo.isEmpty() || token.isEmpty()) return false;
        JSONObject body = new JSONObject();
        body.put("message", what + " " + fileName);
        body.put("content", Base64.encodeToString(gz, Base64.NO_WRAP));
        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://api.github.com/repos/" + repo + "/contents/logs/" + fileName).openConnection();
        c.setRequestMethod("PUT");
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "GigiMorphs");
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        c.disconnect();
        if (code != 201 && code != 200) {
            Logger.printInfo(() -> "GigiLogs: upload HTTP " + code);
            return false;
        }
        return true;
    }

    private static String name(String reason) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
        return f.format(new Date()) + "_" + reason + ".log.gz";
    }

    private static String stamp(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(millis));
    }

    private static byte[] gzip(String text) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static byte[] readAll(InputStream in, int max) throws Exception {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = s.read(buf)) > 0 && out.size() < max) out.write(buf, 0, Math.min(n, max - out.size()));
            return out.toByteArray();
        }
    }

    private static File pendingFile(Context app) {
        File dir = new File(app.getFilesDir(), "gigi-logs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, PENDING);
    }

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences("gigi_logs", Context.MODE_PRIVATE);
    }

    /** Crash loops shouldn't spam the repo. */
    private static boolean autoAllowed(Context app) {
        SharedPreferences p = prefs(app);
        long day = System.currentTimeMillis() / 86_400_000L;
        return p.getLong("autoDay", -1) != day || p.getInt("autoCount", 0) < MAX_AUTO_UPLOADS_PER_DAY;
    }

    private static void countAuto(Context app) {
        SharedPreferences p = prefs(app);
        long day = System.currentTimeMillis() / 86_400_000L;
        int count = p.getLong("autoDay", -1) == day ? p.getInt("autoCount", 0) : 0;
        p.edit().putLong("autoDay", day).putInt("autoCount", count + 1).apply();
    }
}
