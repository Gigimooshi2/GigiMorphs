package app.morphe.patches.youtube.interaction.gigifactcheck

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.layout.sponsorblock.ControlsOverlayFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoTimeHook
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/GigiFactCheckPopupsPatch;"

@Suppress("unused")
val gigiFactCheckPopupsPatch = patch(
    name = "Fact check pop-ups",
    description = "Shows the fact checks made by your fact checker over the player: each checked claim pops up " +
        "at the moment it is said. Tap one for details and sources, or for the list of claims. " +
        "Needs your fact checker's server address and read-only key in the settings. (GigiMorphs)",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoIdPatch,
        videoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.MISC.addPreferences(
            PreferenceScreenPreference(
                key = "gigi_factcheck_popups_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("gigi_factcheck_popups"),
                    TextPreference("gigi_factcheck_popups_server"),
                    TextPreference("gigi_factcheck_popups_key"),
                ),
            ),
        )

        hookVideoId("$EXTENSION_CLASS->newVideoLoaded(Ljava/lang/String;)V")
        videoTimeHook(EXTENSION_CLASS, "setVideoTime")

        // Add the pop-up to the same player layout as the SponsorBlock and Niconico comments overlays.
        ControlsOverlayFingerprint.let {
            it.clearMatch() // Shared fingerprint, other patches may have changed the method.
            it.method.apply {
                val checkCastIndex = it.instructionMatches.last().index
                val frameLayoutRegister = getInstruction<OneRegisterInstruction>(checkCastIndex).registerA
                addInstruction(
                    checkCastIndex + 1,
                    "invoke-static { v$frameLayoutRegister }, $EXTENSION_CLASS->initialize(Landroid/view/ViewGroup;)V",
                )
            }
        }
    }
}
