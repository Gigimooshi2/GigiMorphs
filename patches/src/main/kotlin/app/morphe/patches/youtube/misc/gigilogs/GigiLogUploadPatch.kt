package app.morphe.patches.youtube.misc.gigilogs

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/GigiLogUploadPatch;"

@Suppress("unused")
val gigiLogUploadPatch = bytecodePatch(
    name = "Upload logs",
    description = "Uploads a log snapshot to a private GitHub repo from settings, " +
        "and automatically after a crash. (GigiMorphs)",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    execute {
        PreferenceScreen.MISC.addPreferences(
            PreferenceScreenPreference(
                key = "gigi_logs_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    NonInteractivePreference(
                        "gigi_logs_upload",
                        tag = "app.morphe.extension.youtube.patches.GigiUploadLogsPreference",
                        selectable = true,
                    ),
                    SwitchPreference("gigi_logs_auto_crash", summary = true),
                    TextPreference("gigi_logs_token", inputType = InputType.TEXT_PASSWORD),
                    TextPreference("gigi_logs_repo"),
                ),
            ),
        )

        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->install(Landroid/app/Activity;)V",
        )
    }
}
