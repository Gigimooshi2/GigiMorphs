package app.morphe.extension.reddit.reports;

import android.app.Activity;
import android.app.job.JobScheduler;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ReportActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int PICK = 1;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView status;
    private EditText input;
    private SharedPreferences prefs;
    private Button save;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        prefs = getSharedPreferences("gigi_report_status", MODE_PRIVATE);
        setTitle("Gigi crash reports");
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        column.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);

        TextView info = new TextView(this);
        info.setText("Save a GitHub fine-grained token with Contents read and write on Gigimooshi2/GigiMorphs-logs. This sends the most recent accessible Reddit crash report and future reports to logs/reddit in that repository. Reports can include device details, stack traces and Morphe debug logs.");
        column.addView(info);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("GitHub token");
        column.addView(input);
        save = button(column, "Save and sync", v -> sync());
        button(column, "Choose a saved crash report", v -> {
            if (!enabled()) { message("Save a token first."); return; }
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*");
            startActivityForResult(pick, PICK);
        });
        button(column, "Turn off uploads and forget token", v -> {
            CrashReports.credential(this).delete();
            getSystemService(JobScheduler.class).cancel(CrashReports.JOB);
            input.setText("");
            message("Uploads are off.");
        });
        status = new TextView(this);
        column.addView(status);
        refresh();
    }

    private boolean enabled() { return CrashReports.credential(this).getBaseFile().isFile(); }

    private Button button(LinearLayout column, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        column.addView(button);
        return button;
    }

    private void sync() {
        String supplied = input.getText().toString().trim();
        try {
            if (!supplied.isEmpty()) {
                if (supplied.matches(".*\\s.*")) { message("Check the token for spaces."); return; }
                CrashReports.save(CrashReports.credential(this), supplied.getBytes(StandardCharsets.UTF_8));
                input.setText("");
            }
            if (!enabled()) { message("Enter a token first."); return; }
        } catch (Exception e) { message("Could not save the token."); return; }
        save.setEnabled(false);
        io.execute(() -> {
            try {
                boolean existing = CrashReports.importLatest(this);
                if (enabled()) {
                    if (existing || CrashReports.pending(this).length > 0) {
                        message("Report queued. Waiting for upload.");
                        CrashReports.schedule(this);
                    } else message("Ready for future crashes. You can also choose a saved report.");
                }
            } catch (Exception e) { message("Could not import automatically. Choose the saved crash report."); }
            finally { runOnUiThread(() -> save.setEnabled(true)); }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null || data.getData() == null || !enabled()) return;
        Uri uri = data.getData();
        io.execute(() -> {
            try (Cursor row = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (row == null || !row.moveToFirst()) { message("Could not open the file."); return; }
                CrashReports.queue(this, row.getString(0), CrashReports.read(getContentResolver().openInputStream(uri)));
                message("Report queued. Waiting for upload.");
                CrashReports.schedule(this);
            } catch (Exception e) { message("Could not queue the file. Choose crash_com.reddit.frontpage_…txt."); }
        });
    }

    private void message(String text) { prefs.edit().putString("status", text).apply(); }
    private void refresh() {
        if (enabled()) input.setHint("Token saved • enter a new token to replace it");
        else input.setHint("GitHub token");
        status.setText(prefs.getString("status", "Uploads are off."));
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) { refresh(); }
    @Override protected void onStart() { super.onStart(); prefs.registerOnSharedPreferenceChangeListener(this); refresh(); }
    @Override protected void onStop() { prefs.unregisterOnSharedPreferenceChangeListener(this); super.onStop(); }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
}
