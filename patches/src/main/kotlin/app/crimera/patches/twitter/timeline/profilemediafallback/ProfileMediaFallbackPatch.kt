/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.timeline.profilemediafallback

import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.util.matchSingle
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction

private object LegacyMediaRequestFingerprint : Fingerprint(
    parameters = emptyList(),
    filters = listOf(string("media_timeline_v2"), string("rest_id"), string("includeTweetVisibilityNudge")),
)

private object LegacyPostsRequestFingerprint : Fingerprint(
    parameters = emptyList(),
    filters = listOf(string("user_with_profile_tweets_query_v2"), string("rest_id")),
)

@Suppress("unused")
val profileMediaFallbackPatch = bytecodePatch(
    name = "Profile media fallback",
    description = "Shows media from the Posts feed in the Original X profile Media tab, " +
        "including profiles whose dedicated media timeline is empty. " +
        "Pages without matching media may stop pagination.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_X)
    execute {
        val media = LegacyMediaRequestFingerprint.matchSingle()
        val posts = LegacyPostsRequestFingerprint.matchSingle()
        if (media.method.returnType != posts.method.returnType) {
            throw PatchException("Legacy Media and Posts requests no longer share their configuration type")
        }
        val query = media.instructionMatches.first()
        media.method.replaceInstruction(
            query.index,
            "const-string v${query.instruction.registersUsed.single()}, \"user_with_profile_tweets_query_v2\"",
        )

        val mediaTimeline = object : Fingerprint(
            parameters = emptyList(),
            returnType = "Ljava/lang/String;",
            filters = listOf(string("photo_grid")),
            custom = { method, _ -> method.definingClass.startsWith("Lcom/twitter/") },
        ) {}.matchSingle().classDef
        val timelineType = mediaTimeline.methods.single {
            it.parameterTypes.isEmpty() && it.returnType == "I"
        }.implementation!!.instructions.filterIsInstance<NarrowLiteralInstruction>().single().narrowLiteral
        val selection = object : Fingerprint(
            parameters = emptyList(),
            returnType = "Ljava/lang/String;",
            filters = listOf(string("timeline_type"), string("timeline_owner_id"), string("timeline_timeline_tag")),
        ) {}.matchSingle().method
        val returns = selection.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
        if (selection.implementation!!.registerCount < 3 || returns.size != 1) {
            throw PatchException("Unexpected legacy timeline selection implementation")
        }
        val result = returns.single()
        val resultRegister = result.value.registersUsed.single()
        if (resultRegister > 15) throw PatchException("Legacy timeline selection result register is too high")
        val scratchRegister = if (resultRegister == 0) 1 else 0
        // Keep cursor and module rows; the status flags identify images, GIFs and videos.
        val mediaFlags = 1 or 512 or 1024
        val filter = " AND (timeline_type != $timelineType OR timeline_data_type != 1 OR " +
            "((statuses_flags & $mediaFlags) != 0 AND statuses_author_id = timeline_owner_id))"
        selection.addInstructions(result.index, """
            const-string v$scratchRegister, "$filter"
            invoke-virtual {v$resultRegister, v$scratchRegister}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
            move-result-object v$resultRegister
        """)
    }
}
