package app.morphe.extension.reddit.reports;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.AtomicFile;
import android.util.Log;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

public final class CrashReports {
    static final String REPO = "Gigimooshi2/GigiMorphs-logs";
    static final int JOB = 0x47494749;
    static final int MAX_BYTES = 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("crash_com\\.reddit\\.frontpage_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}(?: \\(\\d+\\))?\\.txt");
    private static final byte[] TRUNCATED = "\n[Report truncated at 1 MiB]\n".getBytes(StandardCharsets.UTF_8);

    public static boolean isPatchIncluded() { return false; }

    static File directory(Context c) { return new File(c.getNoBackupFilesDir(), "gigi-reddit-reports"); }

    static AtomicFile credential(Context c) {
        return new AtomicFile(new File(directory(c), "credential"));
    }

    static File[] pending(Context c) {
        File[] files = directory(c).listFiles((dir, name) -> name.startsWith("crash_") && name.endsWith(".txt"));
        return files == null ? new File[0] : files;
    }

    static void save(AtomicFile file, byte[] data) throws IOException {
        FileOutputStream out = file.startWrite();
        try {
            out.write(data);
            file.finishWrite(out);
        } catch (IOException e) {
            file.failWrite(out);
            throw e;
        }
    }

    static String digest(String algorithm, byte[] data) throws Exception {
        MessageDigest hash = MessageDigest.getInstance(algorithm);
        if ("SHA-1".equals(algorithm)) {
            hash.update(("blob " + data.length + "\0").getBytes(StandardCharsets.US_ASCII));
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : hash.digest(data)) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    static byte[] read(InputStream source) throws IOException {
        if (source == null) throw new IOException("Could not open the report");
        try (InputStream in = source; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int count;
            while ((count = in.read(buf)) != -1) {
                int remaining = MAX_BYTES - out.size();
                out.write(buf, 0, Math.min(remaining, count));
                if (count > remaining) {
                    out.write(TRUNCATED);
                    break;
                }
            }
            return out.toByteArray();
        }
    }

    static void queue(Context c, String name, byte[] data) throws Exception {
        String head = new String(data, 0, Math.min(data.length, 2048), StandardCharsets.UTF_8);
        if (!NAME.matcher(name).matches() || !head.startsWith("Morphe crash log\n")
                || !head.contains("\nApp: com.reddit.frontpage ")) {
            throw new IOException("Choose a Reddit Morphe crash report");
        }
        String stem = name.substring(0, name.length() - 4).replaceAll(" \\(\\d+\\)$", "");
        File target = new File(directory(c), stem + "_" + digest("SHA-256", data) + ".txt");
        if (target.exists()) return;
        if (pending(c).length >= 24) throw new IOException("Upload pending reports first");
        // A job in another process must never see a partially written report.
        File temp = File.createTempFile("writing-", ".tmp", directory(c));
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(data);
                out.getFD().sync();
            }
            if (!temp.renameTo(target)) throw new IOException("Could not queue report");
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    // Called inside Morphe's crash writer. Local work only. Its normal report is always written.
    public static void capture(Context c, String name, byte[] data) {
        try {
            if (!credential(c).getBaseFile().isFile()) return;
            if (data.length > MAX_BYTES) data = read(new ByteArrayInputStream(data));
            queue(c, name, data);
            schedule(c);
        } catch (Exception e) {
            Log.w("GigiReports", "Could not queue crash report", e);
        }
    }

    public static void resume(Context c) {
        try {
            if (credential(c).getBaseFile().isFile() && pending(c).length > 0) schedule(c);
        } catch (RuntimeException e) {
            Log.w("GigiReports", "Could not schedule uploads", e);
        }
    }

    static void schedule(Context c) {
        JobScheduler jobs = c.getSystemService(JobScheduler.class);
        JobInfo job = new JobInfo.Builder(JOB, new ComponentName(c, UploadJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
        if (jobs.schedule(job) != JobScheduler.RESULT_SUCCESS) throw new IllegalStateException("Could not schedule upload");
    }

    // Import only the newest pre-update report visible to this installation.
    static boolean importLatest(Context c) throws Exception {
        Uri files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String[] fields = { MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME };
        try (Cursor rows = c.getContentResolver().query(files, fields,
                "relative_path = ? AND _display_name LIKE ?", new String[]{"Documents/Morphe/", "crash_com.reddit.frontpage_%.txt"},
                "_display_name DESC")) {
            if (rows != null) while (rows.moveToNext()) {
                String name = rows.getString(1);
                if (!NAME.matcher(name).matches()) continue;
                Uri uri = ContentUris.withAppendedId(files, rows.getLong(0));
                queue(c, name, read(c.getContentResolver().openInputStream(uri)));
                return true;
            }
        }
        File folder = c.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        File[] logs = folder == null ? null : folder.listFiles((dir, name) -> NAME.matcher(name).matches());
        if (logs == null || logs.length == 0) return false;
        Arrays.sort(logs);
        File last = logs[logs.length - 1];
        queue(c, last.getName(), read(new FileInputStream(last)));
        return true;
    }
}
