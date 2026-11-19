package app.morphe.extension.youtube.patches;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

/** GigiMorphs: explanation at the top of the Fact check settings. */
@SuppressWarnings({"unused", "deprecation"})
public class GigiFactCheckInfoPreference extends Preference {
    static {
        // Make sure the fact check settings are registered before the settings screen binds them.
        Object unused = GigiFactCheckPatch.TELEGRAM_CHAT_ID;
    }

    public GigiFactCheckInfoPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public GigiFactCheckInfoPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public GigiFactCheckInfoPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public GigiFactCheckInfoPreference(Context context) {
        super(context);
    }
}
