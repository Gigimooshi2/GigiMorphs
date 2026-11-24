package app.morphe.patches.reddit.gigi

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patches.reddit.misc.extension.sharedExtensionPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT

// This build was inspected in the stock arm64 APK. Other 2026.40.0 releases have different DEX.
private val target = Compatibility(
    packageName = "com.reddit.frontpage",
    name = "Reddit",
    apkFileType = COMPATIBILITY_REDDIT.apkFileType,
    signatures = COMPATIBILITY_REDDIT.signatures,
    appIconColor = COMPATIBILITY_REDDIT.appIconColor,
    targets = listOf(AppTarget(version = "2026.40.0", versionCode = 2640091, minSdk = 29))
)

private const val VIDEO = "Lapp/morphe/extension/reddit/patches/GigiVideo;"
private const val HISTORY = "Lapp/morphe/extension/reddit/patches/GigiNavigation;"
private const val URLS = "Lcom/reddit/domain/model/VideoUrls;"
private const val TRANSPORT = "Lcom/reddit/domain/media/packagedmp4/PlaybackTransport;"

private object ResolveStream : Fingerprint(
    returnType = "Lgda0;",
    parameters = listOf("Ljava/lang/String;", URLS, "Z"),
    filters = listOf(string("VideoPlaybackResolver"), string("blank cacheKey"))
)

val gigiVideoPatch = patch(
    name = "Gigi fast video startup",
    description = "Uses a supplied H.264 MP4 for short Reddit clips in the feed and fullscreen when available."
) {
    compatibleWith(target)
    dependsOn(sharedExtensionPatch)
    execute {
        // Feed view model, feed preload delegate and full bleed player all call this resolver.
        ResolveStream.method.addInstructions(0, """
            if-nez p3, :stock
            invoke-static { p2 }, $VIDEO->startupStream($URLS)Ljava/lang/String;
            move-result-object v0
            if-eqz v0, :stock
            new-instance v1, Lgda0;
            sget-object v2, ${TRANSPORT}->MP4:$TRANSPORT
            invoke-direct { v1, v0, v2 }, Lgda0;-><init>(Ljava/lang/String;$TRANSPORT)V
            return-object v1
            :stock
            nop
        """)
    }
}

private object PostCommentsRequest : Fingerprint(
    definingClass = "Lcom/reddit/comment/data/datasource/b;",
    returnType = "Ll220;",
    filters = listOf(methodCall(definingClass = "Ll220;", name = "<init>"))
)

val gigiCommentPatch = patch(
    name = "Gigi progressive comments",
    description = "Requests up to 12 comments in the first PostComments page and keeps Reddit's normal continuation pages."
) {
    compatibleWith(target)
    execute {
        // The original constructor maps p2 to count and p4 to after. v0-v2 are scratch at entry.
        PostCommentsRequest.method.addInstructions(0, """
            move-object/from16 v0, p4
            if-nez v0, :stock
            move-object/from16 v0, p2
            const/16 v1, 12
            if-eqz v0, :limit
            invoke-virtual { v0 }, Ljava/lang/Integer;->intValue()I
            move-result v2
            if-le v2, v1, :stock
            :limit
            invoke-static { v1 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
            move-result-object v0
            move-object/16 p2, v0
            :stock
            nop
        """)
    }
}

private object ApplicationCreate : Fingerprint(
    definingClass = "Lcom/reddit/frontpage/FrontpageApplication;",
    name = "onCreate", parameters = listOf(), returnType = "V"
)

val gigiNavigationPatch = patch(
    name = "Gigi Back and Forward",
    description = "Adds Back and Forward buttons to Reddit activities and reopens screens from navigation history."
) {
    compatibleWith(target)
    dependsOn(sharedExtensionPatch)
    execute {
        // Native helpers used by Reddit itself to find the top screen (including overlays)
        // and push a screen through either of its router implementations.
        mutableClassDefBy(HISTORY).methods.apply {
            first { it.name == "topScreen" }.addInstructions(0, """
                invoke-static { p0 }, Lpzo0;->I(Landroid/content/Context;)Lcom/reddit/screen/BaseScreen;
                move-result-object p0
                return-object p0
            """)
            first { it.name == "screenArgs" }.addInstructions(0, """
                check-cast p0, Lcom/reddit/navstack/v;
                iget-object p0, p0, Lcom/reddit/navstack/v;->c:Landroid/os/Bundle;
                return-object p0
            """)
            first { it.name == "openScreen" }.addInstructions(0, """
                check-cast p2, Lhhy;
                check-cast p1, Lcom/reddit/screen/BaseScreen;
                invoke-static { p0, p1, p2 }, Lpzo0;->g0(Landroid/content/Context;Lcom/reddit/screen/BaseScreen;Lhhy;)V
                return-void
            """)
        }
        ApplicationCreate.method.addInstructions(0, """
            invoke-static/range { p0 .. p0 }, $HISTORY->install(Landroid/app/Application;)V
        """)
    }
}
