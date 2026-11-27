package app.morphe.patches.reddit.gigi

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.util.registersUsed
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.util.setExtensionIsPatchIncluded
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

private const val CONTROLS = "Lapp/morphe/extension/reddit/patches/GigiPlayerControls;"
private const val MODEL = "Lcom/reddit/exokit/internal/ui/b;"
private const val RENDERER = "Lcom/reddit/exokit/api/ui/b;"
private const val PLAYER = "Leci;"
private const val FUNCTION = "Lkotlin/jvm/functions/Function1;"
private const val FACTCHECK = "Lapp/morphe/extension/reddit/patches/GigiRedditFactCheck;"

private object VideoBox : Fingerprint(
    definingClass = RENDERER, name = "c", returnType = "V",
    filters = listOf(methodCall(definingClass = RENDERER, name = "a"),
                     methodCall(definingClass = RENDERER, name = "b"))
)

val gigiPlayerControlsPatch = patch(
    name = "Gigi video controls",
    description = "Adds play, pause, a scrubber, ten-second skips and a fact check button (video to Telegram) directly to Reddit video surfaces."
) {
    compatibleWith(target)
    dependsOn(sharedExtensionPatch, settingsPatch)
    execute {
        // Fact check button: finds the post on the top screen (same native helpers as Gigi Back and
        // Forward) and runs only when tapped. surfacePost and playerMediaUri stay unpatched (null)
        // until they are mapped in the targeted APK. The extension catches
        // everything these throw (a screen that is not a navstack screen throws ClassCastException).
        setExtensionIsPatchIncluded(FACTCHECK)
        mutableClassDefBy(FACTCHECK).methods.apply {
            first { it.name == "topScreen" }.addInstructions(0, """
                if-eqz p0, :done
                invoke-static { p0 }, Lpzo0;->I(Landroid/content/Context;)Lcom/reddit/screen/BaseScreen;
                move-result-object p0
                :done
                return-object p0
            """)
            first { it.name == "screenArgs" }.addInstructions(0, """
                check-cast p0, Lcom/reddit/navstack/v;
                iget-object p0, p0, Lcom/reddit/navstack/v;->c:Landroid/os/Bundle;
                return-object p0
            """)
        }

        // Each branch emits one video surface in a Compose Box. Add our controls inside the
        // same Box, bound to that surface's model. Recycled feed cells receive a new binding.
        VideoBox.instructionMatches.asReversed().forEach { call ->
            val regs = call.instruction.registersUsed
            VideoBox.method.addInstructions(call.index + 1,
                "invoke-static { v${regs[0]}, v${regs[3]} }, $CONTROLS->draw(Ljava/lang/Object;Ljava/lang/Object;)V")
        }
        mutableClassDefBy(CONTROLS).methods.apply {
            first { it.name == "compose" }.addInstructions(0, """
                check-cast p1, Ljax;
                check-cast p3, Li7c;
                invoke-static/range { p0 .. p5 }, Landroidx/compose/ui/viewinterop/d;->a(${FUNCTION}Ljax;${FUNCTION}Li7c;II)V
                return-void
            """)
            first { it.name == "player" }.addInstructions(0, """
                check-cast p0, $MODEL
                iget-object p0, p0, ${MODEL}->q:Lcom/reddit/exokit/internal/data/coordinator/a;
                if-eqz p0, :done
                iget-object p0, p0, Lcom/reddit/exokit/internal/data/coordinator/a;->e:Lcom/reddit/exokit/internal/data/a;
                if-eqz p0, :done
                iget-object p0, p0, Lcom/reddit/exokit/internal/data/a;->a:Landroidx/media3/exoplayer/ExoPlayer;
                check-cast p0, $PLAYER
                :done
                return-object p0
            """)
            for ((name, getter) in listOf("position" to "h", "duration" to "l")) {
                first { it.name == name }.addInstructions(0, """
                    check-cast p0, $PLAYER
                    invoke-virtual { p0 }, ${PLAYER}->${getter}()J
                    move-result-wide v0
                    return-wide v0
                """)
            }
            first { it.name == "wantsPlay" }.addInstructions(0, """
                check-cast p0, $PLAYER
                invoke-virtual { p0 }, ${PLAYER}->m()Z
                move-result p0
                return p0
            """)
            first { it.name == "play" }.addInstructions(0, """
                check-cast p0, $PLAYER
                invoke-virtual { p0, p1 }, ${PLAYER}->B(Z)V
                return-void
            """)
            first { it.name == "seek" }.addInstructions(0, """
                check-cast p0, $PLAYER
                invoke-virtual { p0 }, ${PLAYER}->f()I
                move-result p3
                invoke-virtual { p0, p3, p1, p2 }, ${PLAYER}->y(IJ)V
                return-void
            """)
        }
    }
}

private const val REPORT_PACKAGE = "app.morphe.extension.reddit.reports"
private const val REPORT_QUEUE = "Lapp/morphe/extension/reddit/reports/CrashReports;"

val gigiCrashUploadPatch = patch(
    name = "Gigi crash report upload",
    description = "Adds a crash reports launcher and an option to send Reddit crash reports to the private GigiMorphs logs repository."
) {
    compatibleWith(target)
    dependsOn(settingsPatch)
    execute {
        setExtensionIsPatchIncluded(REPORT_QUEUE)
        val logger = mutableClassDefBy("Lapp/morphe/extension/shared/patches/CrashLogPatch;")
        // On every supported Android version Morphe passes the finished report here,
        // including when its public write subsequently falls back to app-specific storage.
        logger.methods.single { it.name == "writeToMediaStoreDocuments" }.addInstructions(
            0, "invoke-static/range { p0 .. p2 }, $REPORT_QUEUE->capture(Landroid/content/Context;Ljava/lang/String;[B)V"
        )
        logger.methods.single { it.name == "setUncaughtExceptionHandler" }.addInstructions(
            0, "invoke-static/range { p0 .. p0 }, $REPORT_QUEUE->resume(Landroid/content/Context;)V"
        )
        resourceXml("AndroidManifest.xml").use { manifest ->
            val app = manifest.elements("application").single()
            check(app.attribute("android:appComponentFactory") == "androidx.core.app.CoreComponentFactory") {
                "Unexpected Reddit component factory"
            }
            app.setAttribute("android:appComponentFactory", "$REPORT_PACKAGE.ReportComponentFactory")
            app.appendChild("activity") {
                setAttribute("android:name", "$REPORT_PACKAGE.ReportActivity")
                setAttribute("android:label", "Gigi crash reports")
                setAttribute("android:exported", "true")
                setAttribute("android:process", ":gigi_reports")
                setAttribute("android:taskAffinity", "com.reddit.frontpage.gigi_reports")
                setAttribute("android:theme", "@android:style/Theme.Material.Light")
                appendChild("intent-filter") {
                    appendChild("action") { setAttribute("android:name", "android.intent.action.MAIN") }
                    appendChild("category") { setAttribute("android:name", "android.intent.category.LAUNCHER") }
                }
            }
            app.appendChild("service") {
                setAttribute("android:name", "$REPORT_PACKAGE.UploadJob")
                setAttribute("android:exported", "false")
                setAttribute("android:permission", "android.permission.BIND_JOB_SERVICE")
                setAttribute("android:process", ":gigi_reports")
            }
        }
    }
}
