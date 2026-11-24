package app.morphe.extension.reddit.reports;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public final class UploadJob extends JobService {
    private Thread worker;

    @Override public boolean onStartJob(JobParameters params) {
        worker = new Thread(() -> {
            Thread current = Thread.currentThread();
            boolean retry = false;
            try {
                if (CrashReports.credential(this).getBaseFile().isFile()) {
                    String token = new String(CrashReports.credential(this).readFully(), StandardCharsets.UTF_8);
                    File[] files = CrashReports.pending(this);
                    Arrays.sort(files);
                    for (File f : files) {
                        if (current.isInterrupted() || !CrashReports.credential(this).getBaseFile().isFile()) break;
                        byte[] bytes = Files.readAllBytes(f.toPath());
                        int code = upload(f.getName(), bytes, token);
                        if (code != 201 && code != 200) {
                            retry = code == 408 || code == 409 || code == 429 || code >= 500;
                            status("GitHub returned HTTP " + code + ". " + (retry ? "Retry scheduled." : "Check the token and tap Save and sync."));
                            break;
                        }
                        if (!f.delete() && f.exists()) throw new IOException("Could not clear the uploaded report");
                        status("Uploaded " + f.getName().substring(0, f.getName().lastIndexOf('_')) + ".txt");
                    }
                }
            } catch (Exception e) {
                retry = true;
                status("Upload interrupted. Retry scheduled.");
            } finally {
                boolean needsRetry = retry;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (worker == current) { worker = null; jobFinished(params, needsRetry); }
                });
            }
        }, "Gigi crash uploads");
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (worker != null) worker.interrupt();
        worker = null;
        return true;
    }

    private void status(String message) {
        getSharedPreferences("gigi_report_status", MODE_PRIVATE).edit().putString("status", message).apply();
    }

    private HttpURLConnection connect(URL url, String method, String token) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(20_000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("User-Agent", "GigiMorphs-Reddit");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        return conn;
    }

    private int upload(String name, byte[] data, String token) throws Exception {
        URL url = new URL("https://api.github.com/repos/" + CrashReports.REPO + "/contents/logs/reddit/" + name);
        HttpURLConnection put = connect(url, "PUT", token);
        int code;
        try {
            byte[] body = new JSONObject().put("message", "Upload Reddit crash report")
                    .put("content", Base64.encodeToString(data, Base64.NO_WRAP)).toString().getBytes(StandardCharsets.UTF_8);
            put.setDoOutput(true);
            put.setFixedLengthStreamingMode(body.length);
            put.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = put.getOutputStream()) { out.write(body); }
            code = put.getResponseCode();
            if (code == 403 && ("0".equals(put.getHeaderField("X-RateLimit-Remaining"))
                    || put.getHeaderField("Retry-After") != null)) return 429;
        } finally { put.disconnect(); }
        if (code != 422) return code;
        // The PUT may have committed before the phone lost its connection. Match the blob
        // before acknowledging the retry, and never overwrite a different report.
        HttpURLConnection get = connect(url, "GET", token);
        try {
            get.setRequestProperty("Accept", "application/vnd.github.object+json");
            if (get.getResponseCode() == 200) {
                try (java.util.Scanner response = new java.util.Scanner(get.getInputStream(), "UTF-8").useDelimiter("\\A")) {
                    String sha = new JSONObject(response.next()).optString("sha");
                    if (sha.equals(CrashReports.digest("SHA-1", data))) return 200;
                }
            }
        } finally { get.disconnect(); }
        return code;
    }
}
