package app.morphe.patches.youtube.interaction.gigifactcheck

import app.morphe.patcher.patch.patch
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
    description = "Adds a Fact check button to the video player, and to the menu of Shorts. " +
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
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        addPlayerBottomButton(EXTENSION_CLASS)

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
