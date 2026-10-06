package app.morphe.patches.youtube.layout.shortsplayer

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.hookVideoIntent
import app.morphe.patches.youtube.shared.openVideoIntentPatch

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/GigiShortsFullVideoLinkPatch;"

@Suppress("unused")
val gigiShortsFullVideoLinkPatch = bytecodePatch(
    name = "Fix Shorts full video link",
    description = "Opens a Short's full-video link in the regular player with the Shorts player closed, " +
        "so the video keeps playing on rotation, screen lock and in PiP. (GigiMorphs)",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
        playerTypeHookPatch,
        openVideoIntentPatch,
    )

    execute {
        hookVideoIntent(EXTENSION_CLASS, detectVideo = true, detectShorts = false)
    }
}
