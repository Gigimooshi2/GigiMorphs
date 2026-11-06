package app.morphe.extension.youtube.patches;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

/** GigiMorphs: "Upload logs now" button. */
@SuppressWarnings({"unused", "deprecation"})
public class GigiUploadLogsPreference extends Preference {
    static {
        // Make sure the log settings are registered before the settings screen binds them.
        // Otherwise opening the screen shows "Preference key has no setting" and they don't save.
        Object unused = GigiLogUploadPatch.AUTO_CRASH;
    }

    {
        setOnPreferenceClickListener(pref -> {
            GigiLogUploadPatch.uploadNow();
            return true;
        });
    }

    public GigiUploadLogsPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public GigiUploadLogsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public GigiUploadLogsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public GigiUploadLogsPreference(Context context) {
        super(context);
    }
}
