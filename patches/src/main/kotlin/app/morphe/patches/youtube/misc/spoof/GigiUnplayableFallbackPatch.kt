package app.morphe.patches.youtube.misc.spoof

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayWithRegistryMethodRef
import app.morphe.patches.shared.misc.fix.proto.protoDefaultInstanceFingerprint
import app.morphe.patches.shared.misc.spoof.CreateStreamingDataFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/GigiUnplayableFallbackPatch;"

private const val GET_GENERATED_REGISTRY =
    "Lcom/google/protobuf/ExtensionRegistryLite;->getGeneratedRegistry()Lcom/google/protobuf/ExtensionRegistryLite;"

/**
 * Creates a player response model from the bytes of a player response.
 * Found in the player response model class, which is parcelable.
 */
private object GigiPlayerResponseModelFromBytesFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf("[B", "J"),
    filters = listOf(
        methodCall(
            parameters = listOf("[B", "Lcom/google/protobuf/MessageLite;"),
            returnType = "Lcom/google/protobuf/MessageLite;"
        ),
        opcode(Opcode.CHECK_CAST) // Player response.
    ),
    custom = { _, classDef ->
        classDef.fields.any { it.name == "CREATOR" }
    }
)

@Suppress("unused")
val gigiUnplayableFallbackPatch = bytecodePatch(
    name = "Play videos YouTube refuses",
    description = "When YouTube answers the app's own video request with \"unplayable\" " +
        "but the spoofed client can play the video, the video is played with the response " +
        "of the spoofed client. Needs Spoof video streams to be on. (GigiMorphs)",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        fixProtoLibraryPatch,
        spoofVideoStreamsPatch,
    )

    execute {
        // The player response proto, which the streaming data is created from.
        val playerProtoType = CreateStreamingDataFingerprint.method.parameterTypes.first().toString()
        val modelClass = GigiPlayerResponseModelFromBytesFingerprint.classDef
        val modelType = modelClass.type

        // Static factory of the model class that creates the streaming data of a player response:
        // (config, player response, response time) -> streaming data, or null if the response has no streams.
        val streamingDataFactory = modelClass.methods.single { method ->
            AccessFlags.STATIC.isSet(method.accessFlags)
                    && method.parameterTypes.size == 3
                    && method.parameterTypes[1].toString() == playerProtoType
                    && method.parameterTypes[2].toString() == "J"
                    && method.returnType.startsWith("L")
        }
        val streamingDataType = streamingDataFactory.returnType
        val configType = streamingDataFactory.parameterTypes[0].toString()
        val configDefaultField = classDefBy(configType).fields.first { field ->
            field.type == configType && AccessFlags.STATIC.isSet(field.accessFlags)
        }
        val playerProtoDefaultInstance = protoDefaultInstanceFingerprint(playerProtoType)
            .instructionMatches.first().fieldReference

        // Returns the player response to use instead of the given one, or null to keep it.
        val replaceHelper = ImmutableMethod(
            modelType,
            "patch_gigiReplaceUnplayable",
            listOf(ImmutableMethodParameter(playerProtoType, null, null)),
            playerProtoType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(4),
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p0 }, $EXTENSION_CLASS->replaceUnplayablePlayerResponse(Ljava/lang/Object;)[B
                    move-result-object v0
                    if-nez v0, :replace
                    const/4 v0, 0x0
                    return-object v0
                    :replace
                    # The player config holds extensions, which are lost if parsed without the registry.
                    sget-object v1, $playerProtoDefaultInstance
                    invoke-static { }, $GET_GENERATED_REGISTRY
                    move-result-object v2
                    invoke-static { v1, v0, v2 }, ${parseByteArrayWithRegistryMethodRef.get()!!}
                    move-result-object v0
                    check-cast v0, $playerProtoType
                    return-object v0
                """
            )
        }
        modelClass.methods.add(replaceHelper)

        // Creates the streaming data of the replaced player response.
        val streamingDataHelper = ImmutableMethod(
            modelType,
            "patch_gigiCreateStreamingData",
            listOf(
                ImmutableMethodParameter(playerProtoType, null, null),
                ImmutableMethodParameter("J", null, null),
            ),
            streamingDataType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(4),
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    sget-object v0, $configType->${configDefaultField.name}:$configType
                    invoke-static { v0, p0, p1, p2 }, $streamingDataFactory
                    move-result-object v0
                    return-object v0
                """
            )
        }
        modelClass.methods.add(streamingDataHelper)

        // Every model is created with a constructor that is given the player response and its
        // streaming data. The other constructors create the streaming data and call these.
        val constructors = modelClass.methods.filter { method ->
            method.name == "<init>"
                    && method.parameterTypes.firstOrNull()?.toString() == playerProtoType
                    && method.parameterTypes.any { it.toString() == streamingDataType }
        }
        check(constructors.isNotEmpty()) { "Player response model constructors not found" }

        constructors.forEach { constructor ->
            // Parameter registers, p0 is the model itself.
            var register = 1
            var timeRegister = -1
            var streamingDataRegister = -1
            constructor.parameterTypes.forEach { parameter ->
                val type = parameter.toString()
                if (type == "J" && timeRegister < 0) timeRegister = register
                if (type == streamingDataType) streamingDataRegister = register
                register += if (type == "J" || type == "D") 2 else 1
            }
            val localRegisters = constructor.implementation!!.registerCount - register
            check(timeRegister > 0 && streamingDataRegister > 0 && localRegisters >= 1) {
                "Unexpected player response model constructor: $constructor"
            }

            constructor.addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p1 .. p1 }, $replaceHelper
                    move-result-object v0
                    if-eqz v0, :gigi_keep_player_response
                    move-object/from16 p1, v0
                    invoke-static { p1, p$timeRegister, p${timeRegister + 1} }, $streamingDataHelper
                    move-result-object v0
                    move-object/from16 p$streamingDataRegister, v0
                    :gigi_keep_player_response
                    nop
                """
            )
        }
    }
}
