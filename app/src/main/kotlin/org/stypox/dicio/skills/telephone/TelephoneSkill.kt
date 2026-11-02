package org.stypox.dicio.skills.telephone

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Telephone

class TelephoneSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Telephone>,
    val yesNoData: StandardRecognizerData<Sentences.UtilYesNo>,
) : StandardRecognizerSkill<Telephone>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Telephone): SkillOutput {
        val contentResolver = ctx.android.contentResolver
        val userContactName = when (inputData) {
            is Telephone.Dial -> inputData.who?.trim { it <= ' ' } ?: ""
        }
        val candidates = ContactMatcher.findCandidates(contentResolver, userContactName)
        val best = candidates.firstOrNull()
            ?: return TelephoneOutput(listOf(), yesNoData) // nobody matched

        // a clear winner: call straight away
        val runnerUp = candidates.getOrNull(1)
        val clearWinner = best.score >= CALL_DIRECTLY_SCORE &&
            (runnerUp == null || best.score - runnerUp.score >= CALL_DIRECTLY_MARGIN)
        if (clearWinner || candidates.size == 1) {
            val number = best.numbers[0]
            call(ctx.android, number)
            return ConfirmedCallOutput(number)
        }

        // otherwise let the user pick, best guesses first
        return TelephoneOutput(
            candidates.take(MAX_CHOICES).map { Pair(it.name, it.numbers) },
            yesNoData,
        )
    }

    companion object {
        private const val CALL_DIRECTLY_SCORE = 0.85
        private const val CALL_DIRECTLY_MARGIN = 0.1
        private const val MAX_CHOICES = 5
        private val NOT_LETTER_OR_DIGIT = Regex("""[^\p{L}\p{N}]""")

        fun normalizeName(name: String): String = name.lowercase().replace(NOT_LETTER_OR_DIGIT, "")

        fun call(context: Context, number: String?) {
            val callIntent = Intent(Intent.ACTION_CALL)
            callIntent.data = Uri.parse("tel:$number")
            callIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(callIntent)
        }
    }
}
