package org.stypox.dicio.skills.telephone

import android.content.ContentResolver
import android.provider.ContactsContract
import android.util.Log

/**
 * Finds the contact the user meant, even when speech recognition spells the name differently
 * from the address book ("Sreya" / "Shreya", "Gautham" / "Gowtham", "Amma ❤️" / "amma").
 *
 * Every contact name (and nickname) is compared word by word using a "sounds like" key built for
 * Indian and English names, plus an edit-distance fallback for small spelling slips.
 */
object ContactMatcher {
    private val TAG = ContactMatcher::class.simpleName

    data class Candidate(val name: String, val numbers: List<String>, val score: Double)

    private val FILLER = Regex("""\b(please|now|for me|right now|on (my )?phone|my|the|to)\b""")

    fun findCandidates(contentResolver: ContentResolver, spokenName: String): List<Candidate> {
        val query = normalize(spokenName.replace(FILLER, " "))
        if (query.isBlank()) return emptyList()

        // contact id -> (display name, other names like nicknames, numbers)
        val names = HashMap<String, String>()
        val altNames = HashMap<String, MutableSet<String>>()
        val numbers = HashMap<String, LinkedHashMap<String, String>>()

        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null, null, null
            )?.use { c ->
                val idCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val id = c.getString(idCol) ?: continue
                    val number = c.getString(numCol) ?: continue
                    c.getString(nameCol)?.let { names[id] = it }
                    // numbers that differ only by country code or spacing are the same number
                    val key = number.filter { it.isDigit() }.takeLast(10)
                    numbers.getOrPut(id) { LinkedHashMap() }.putIfAbsent(key, number)
                }
            }

            // nicknames, so "call amma" finds "Lakshmi Nair" saved with nickname "Amma"
            contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Nickname.NAME,
                ),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val nick = c.getString(1) ?: continue
                    altNames.getOrPut(id) { HashSet() }.add(nick)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read contacts", e)
        }

        return names.mapNotNull { (id, name) ->
            val nums = numbers[id]?.values?.toList().orEmpty()
            if (nums.isEmpty()) return@mapNotNull null
            val score = (listOf(name) + altNames[id].orEmpty()).maxOf { score(query, it) }
            if (score < MIN_SCORE) null else Candidate(name, nums, score)
        }.sortedByDescending { it.score }
    }

    /** How well [contactName] matches what was said ([query], already normalized), 0..1 */
    internal fun score(query: String, contactName: String): Double {
        val name = normalize(contactName)
        if (name.isEmpty()) return 0.0
        if (name == query) return 1.0
        if (soundKey(name) == soundKey(query)) return 0.97

        val nameWords = name.split(' ').filter { it.isNotEmpty() }
        val queryWords = query.split(' ').filter { it.isNotEmpty() }

        // every spoken word must match one word of the name; the name may have extra words
        // ("call priya" -> "Priya College"), which costs a little
        var total = 0.0
        for (qw in queryWords) {
            total += nameWords.maxOf { wordScore(qw, it) }
        }
        val avg = total / queryWords.size
        val extraWords = (nameWords.size - queryWords.size).coerceAtLeast(0)
        return avg - 0.04 * extraWords
    }

    private fun wordScore(spoken: String, word: String): Double {
        if (spoken == word) return 1.0
        val a = soundKey(spoken)
        val b = soundKey(word)
        if (a == b) return 0.95
        if (a.length >= 3 && b.startsWith(a)) return 0.8 // "sree" -> "sreelakshmi"
        val maxLen = maxOf(a.length, b.length)
        val similarity = 1.0 - levenshtein(a, b).toDouble() / maxLen
        return similarity * 0.85
    }

    internal fun normalize(s: String): String = s.lowercase()
        .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    // vowel spellings first, so that "ow" becomes "au" before "w" turns into "v"
    private val SOUND_RULES = listOf(
        "ow" to "au", "ou" to "u", "ee" to "i", "oo" to "u", "aa" to "a", "ae" to "e",
        "sh" to "s", "ch" to "c", "th" to "t", "dh" to "d", "bh" to "b", "ph" to "f",
        "kh" to "k", "gh" to "g", "jh" to "j", "w" to "v", "z" to "j", "q" to "k",
        "x" to "ks", "ck" to "k", "y" to "i",
    )

    /** A rough "sounds like" key: spellings that are pronounced alike get the same key */
    internal fun soundKey(word: String): String {
        var w = word.replace(" ", "")
        for ((from, to) in SOUND_RULES) w = w.replace(from, to)
        // collapse doubled letters ("amma" ~ "ama", "anna" ~ "ana")
        val sb = StringBuilder()
        for (ch in w) if (sb.isEmpty() || sb.last() != ch) sb.append(ch)
        // a final "h" or "a" is often dropped or added ("Radha" / "Radh", "Sneh" / "Sneha")
        return sb.toString().trimEnd('h').let { if (it.length > 3) it.trimEnd('a') else it }
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            cur.copyInto(prev)
        }
        return prev[b.length]
    }

    private const val MIN_SCORE = 0.6
}
