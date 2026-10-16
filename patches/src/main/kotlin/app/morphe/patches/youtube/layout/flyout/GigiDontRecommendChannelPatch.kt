package app.morphe.patches.youtube.layout.flyout

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/GigiDontRecommendChannelPatch;"

@Suppress("unused")
val gigiDontRecommendChannelPatch = bytecodePatch(
    name = "Don't recommend channel for feed Shorts",
    description = "Adds \"Don't recommend channel\" to the menu of Shorts in the feed. (GigiMorphs)",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        authHookPatch,
        flyoutPatch,
    )

    execute {
        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
