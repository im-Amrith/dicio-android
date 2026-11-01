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
        val contacts = Contact.getFilteredSortedContacts(contentResolver, userContactName)

        // A contact whose name is exactly what the user said (e.g. "Amma", ignoring case, spaces,
        // emojis and punctuation) wins outright, even if other names contain it ("Ammachi"):
        // call it right away instead of asking which one was meant.
        val wanted = normalizeName(userContactName)
        if (wanted.isNotEmpty()) {
            val exact = contacts.filter { normalizeName(it.name) == wanted }
            val exactWithNumbers = exact.map { Pair(it, it.getNumbers(contentResolver)) }
                .filter { it.second.isNotEmpty() }
            if (exactWithNumbers.size == 1) {
                val number = exactWithNumbers[0].second[0]
                call(ctx.android, number)
                return ConfirmedCallOutput(number)
            }
        }

        val validContacts = ArrayList<Pair<String, List<String>>>()

        var i = 0
        while (validContacts.size < 5 && i < contacts.size) {
            val contact = contacts[i]
            val numbers = contact.getNumbers(contentResolver)
            if (numbers.isEmpty()) {
                ++i
                continue
            }
            if (validContacts.isEmpty()
                && contact.distance < 3
                && numbers.size == 1 // it has just one number
                && (contacts.size <= i + 1 // the next contact has a distance higher by 3+
                        || contacts[i + 1].distance - 2 > contact.distance)
            ) {
                // very close match with just one number and without distance ties: call it directly
                return ConfirmCallOutput(contact.name, numbers[0], yesNoData)
            }
            validContacts.add(Pair(contact.name, numbers))
            ++i
        }

        if (validContacts.size == 1 // there is exactly one valid contact and ...
            // ... either it has exactly one number, or we would be forced (because no number parser
            // is available) to use ContactChooserName, which only uses the first phone number
            // anyway
            && (validContacts[0].second.size == 1 || ctx.parserFormatter == null)
        ) {
            // not a good enough match, but since we have only this, call it directly
            val contact = validContacts[0]
            return ConfirmCallOutput(contact.first, contact.second[0], yesNoData)
        }

        // this point will not be reached if a very close match was found
        return TelephoneOutput(validContacts, yesNoData)
    }

    companion object {
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
