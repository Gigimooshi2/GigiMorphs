package app.morphe.patches.youtube.interaction.gigifactcheck

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.interaction.downloads.YouTubeOfflineVideoEndpointFingerprint
import app.morphe.patches.youtube.interaction.downloads.downloadsPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.misc.spoof.spoofVideoStreamsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/GigiFactCheckPatch;"

private val gigiFactCheckResourcePatch = patch {
    dependsOn(settingsPatch)

    execute {
        copyResources(
            "gigifactcheck",
            ResourceGroup("drawable", "gigi_yt_fact_check.xml")
        )
    }
}

@Suppress("unused")
val gigiFactCheckPatch = patch(
    name = "Fact check button",
    description = "Adds Fact check to the Download button below the video, a Fact check button to the player " +
        "and to the menu of Shorts. " +
        "It downloads the video on the phone (at most 20 MB, or else only its audio), " +
        "then opens the share sheet with the file, its captions and a caption text. (GigiMorphs)",
) {
    dependsOn(
        gigiFactCheckResourcePatch,
        sharedExtensionPatch,
        settingsPatch,
        authHookPatch,
        flyoutPatch,
        spoofVideoStreamsPatch,
        playerOverlayButtonsHookPatch,
        videoInformationPatch,
        // Its Download button hook is added first, so the hook added here runs before it.
        downloadsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.MISC.addPreferences(
            PreferenceScreenPreference(
                key = "gigi_factcheck_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    NonInteractivePreference(
                        "gigi_factcheck_info",
                        tag = "app.morphe.extension.youtube.patches.GigiFactCheckInfoPreference",
                    ),
                    SwitchPreference("gigi_factcheck_download_menu"),
                    SwitchPreference("gigi_factcheck_send_captions"),
                    TextPreference("gigi_factcheck_chat_id", inputType = InputType.NUMBER),
                ),
            ),
        )

        // The Download button below the video, next to Share.
        YouTubeOfflineVideoEndpointFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p3 .. p3 }, $EXTENSION_CLASS->onDownloadButtonClick(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :gigi_not_fact_check
                return-void
                :gigi_not_fact_check
                nop
            """
        )

        addPlayerBottomButton(EXTENSION_CLASS)

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
