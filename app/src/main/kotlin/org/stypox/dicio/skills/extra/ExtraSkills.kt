package org.stypox.dicio.skills.extra

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.jsoup.Jsoup
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Alarm
import org.stypox.dicio.sentences.Sentences.Battery
import org.stypox.dicio.sentences.Sentences.Volume
import org.stypox.dicio.sentences.Sentences.Whatsapp
import org.stypox.dicio.sentences.Sentences.Youtube
import org.stypox.dicio.skills.telephone.ContactMatcher
import org.stypox.dicio.util.ConnectionUtils
import org.stypox.dicio.util.PERMISSION_READ_CONTACTS

/** A spoken line that is also shown as the answer. */
class SpokenOutput(private val text: String) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = text
}

private const val TAG = "ExtraSkills"

private fun Context.startOrFalse(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    Log.w(TAG, "No app for $intent", e)
    false
}

/* -------------------------------------- YOUTUBE --------------------------------------- */

object YoutubeInfo : SkillInfo("youtube") {
    override fun name(context: Context) = "YouTube"
    override fun sentenceExample(context: Context) = "Play lofi beats on YouTube"
    @Composable override fun icon() = rememberVectorPainter(Icons.Default.OndemandVideo)
    override fun build(ctx: SkillContext): Skill<*>? =
        Sentences.Youtube[ctx.sentencesLanguage]?.let { YoutubeSkill(this, it) }
}

class YoutubeSkill(info: SkillInfo, data: StandardRecognizerData<Youtube>) :
    StandardRecognizerSkill<Youtube>(info, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Youtube): SkillOutput {
        val query = when (inputData) {
            is Youtube.Query -> inputData.query?.trim()
        }
        if (query.isNullOrBlank()) return SpokenOutput("What should I play on YouTube?")

        // find the actual video and open it: YouTube starts playing a video it is opened on
        val videoId = withContext(Dispatchers.IO) { findVideoId(query) }
        val app = ctx.android
        if (videoId != null) {
            val opened = app.startOrFalse(
                Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).setPackage(YOUTUBE)
            ) || app.startOrFalse(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
            )
            if (opened) return SpokenOutput("Playing $query on YouTube")
        }

        // no video found: show YouTube's own search results
        val searched = app.startOrFalse(
            Intent(Intent.ACTION_SEARCH).setPackage(YOUTUBE).putExtra("query", query)
        ) || app.startOrFalse(
            Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
        )
        return SpokenOutput(if (searched) "Here's YouTube's search for $query" else "YouTube isn't installed")
    }

    companion object {
        private const val YOUTUBE = "com.google.android.youtube"
        private val VIDEO_ID = Regex("""(?:youtube\.com/watch\?v=|youtu\.be/|youtube\.com/shorts/)([A-Za-z0-9_-]{11})""")

        fun findVideoId(query: String): String? = try {
            val html = ConnectionUtils.getPage(
                "https://html.duckduckgo.com/html/?q=" +
                    ConnectionUtils.urlEncode("$query site:youtube.com"),
                mapOf(
                    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:143.0) Gecko/20100101 Firefox/143.0",
                    "Accept" to "text/html,application/xhtml+xml",
                )
            )
            Jsoup.parse(html).select("a.result__a").asSequence()
                .map { a ->
                    val href = a.attr("href")
                    runCatching { Uri.parse(href).getQueryParameter("uddg") }.getOrNull() ?: href
                }
                .mapNotNull { VIDEO_ID.find(Uri.decode(it))?.groupValues?.get(1) }
                .firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Could not search for a YouTube video", e)
            null
        }
    }
}

/* --------------------------------------- ALARM ---------------------------------------- */

object AlarmInfo : SkillInfo("alarm") {
    override fun name(context: Context) = "Alarm"
    override fun sentenceExample(context: Context) = "Wake me up at 6 30 am"
    @Composable override fun icon() = rememberVectorPainter(Icons.Default.Alarm)
    override fun build(ctx: SkillContext): Skill<*>? =
        Sentences.Alarm[ctx.sentencesLanguage]?.let { AlarmSkill(this, it) }
}

class AlarmSkill(info: SkillInfo, data: StandardRecognizerData<Alarm>) :
    StandardRecognizerSkill<Alarm>(info, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Alarm): SkillOutput {
        val spoken = when (inputData) {
            is Alarm.Set -> inputData.time.orEmpty()
        }
        val (hour, minute) = parseTime(spoken)
            ?: return SpokenOutput("What time should I set the alarm for?")
        val ok = ctx.android.startOrFalse(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        return SpokenOutput(
            if (ok) "Alarm set for ${formatTime(hour, minute)}" else "I couldn't find a clock app"
        )
    }

    companion object {
        private val WORD_NUMBERS = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
            "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
            "fifteen" to 15, "thirty" to 30, "forty five" to 45, "forty" to 40, "twenty" to 20,
        )
        private val TIME = Regex("""(\d{1,2})(?:\s*[: ]\s*(\d{2}))?\s*(a\s*m|p\s*m|in the morning|in the evening|at night|tonight)?""")

        /** "6", "6 30", "6:30 pm", "seven thirty am" -> (hour, minute) in 24h */
        fun parseTime(raw: String): Pair<Int, Int>? {
            var s = " " + raw.lowercase().replace(".", " ") + " "
            for ((word, n) in WORD_NUMBERS) s = s.replace(" $word ", " $n ")
            s = s.replace(" o clock", "").replace("half past (\\d{1,2})".toRegex()) { "${it.groupValues[1]} 30" }
            val m = TIME.find(s.trim()) ?: return null
            var hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toIntOrNull() ?: 0
            val suffix = m.groupValues[3].replace(" ", "")
            if (hour > 23 || minute > 59) return null
            if ((suffix == "pm" || suffix.contains("evening") || suffix.contains("night")) && hour < 12) hour += 12
            if (suffix == "am" && hour == 12) hour = 0
            return Pair(hour, minute)
        }

        fun formatTime(hour: Int, minute: Int): String {
            val h12 = if (hour % 12 == 0) 12 else hour % 12
            return "$h12:${minute.toString().padStart(2, '0')} ${if (hour < 12) "am" else "pm"}"
        }
    }
}

/* -------------------------------------- WHATSAPP -------------------------------------- */

object WhatsappInfo : SkillInfo("whatsapp") {
    override fun name(context: Context) = "WhatsApp message"
    override fun sentenceExample(context: Context) = "Message Amma on WhatsApp saying I'll be late"
    @Composable override fun icon() = rememberVectorPainter(Icons.Default.Chat)
    override val neededPermissions: List<Permission> = listOf(PERMISSION_READ_CONTACTS)
    override fun build(ctx: SkillContext): Skill<*>? =
        Sentences.Whatsapp[ctx.sentencesLanguage]?.let { WhatsappSkill(this, it) }
}

class WhatsappSkill(info: SkillInfo, data: StandardRecognizerData<Whatsapp>) :
    StandardRecognizerSkill<Whatsapp>(info, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Whatsapp): SkillOutput {
        val (who, message) = when (inputData) {
            is Whatsapp.Send -> Pair(inputData.who.orEmpty(), inputData.message.orEmpty())
        }
        val contact = withContext(Dispatchers.IO) {
            ContactMatcher.findCandidates(ctx.android.contentResolver, who).firstOrNull()
        } ?: return SpokenOutput("I couldn't find $who in your contacts")

        var digits = contact.numbers[0].filter { it.isDigit() }
        if (digits.length == 10) digits = "91$digits" // Indian number saved without country code
        // opens the chat with the message typed in; you check it and tap send
        val ok = ctx.android.startOrFalse(
            Intent(Intent.ACTION_VIEW, Uri.parse(
                "https://api.whatsapp.com/send?phone=$digits&text=" + Uri.encode(message)
            )).setPackage("com.whatsapp")
        )
        return SpokenOutput(
            if (ok) "Message to ${contact.name} is ready, tap send" else "WhatsApp isn't installed"
        )
    }
}

/* --------------------------------------- VOLUME --------------------------------------- */

object VolumeInfo : SkillInfo("volume") {
    override fun name(context: Context) = "Volume"
    override fun sentenceExample(context: Context) = "Turn the volume up"
    @Composable override fun icon() = rememberVectorPainter(Icons.Default.VolumeUp)
    override fun build(ctx: SkillContext): Skill<*>? =
        Sentences.Volume[ctx.sentencesLanguage]?.let { VolumeSkill(this, it) }
}

class VolumeSkill(info: SkillInfo, data: StandardRecognizerData<Volume>) :
    StandardRecognizerSkill<Volume>(info, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Volume): SkillOutput {
        val audio = ctx.android.getSystemService(AudioManager::class.java)
            ?: return SpokenOutput("I can't reach the volume controls")
        val stream = AudioManager.STREAM_MUSIC
        val flags = AudioManager.FLAG_SHOW_UI
        when (inputData) {
            is Volume.Up -> repeat(2) { audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags) }
            is Volume.Down -> repeat(2) { audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags) }
            is Volume.Mute -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
            is Volume.Max -> audio.setStreamVolume(stream, audio.getStreamMaxVolume(stream), flags)
        }
        val percent = audio.getStreamVolume(stream) * 100 / audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        // say nothing: speaking would itself be loud, the on-screen slider shows the level
        return object : HeadlineSpeechSkillOutput {
            override fun getSpeechOutput(ctx: SkillContext) = ""
            @Composable override fun GraphicalOutput(ctx: SkillContext) =
                org.stypox.dicio.io.graphical.Headline("Volume $percent%")
        }
    }
}

/* --------------------------------------- BATTERY -------------------------------------- */

object BatteryInfo : SkillInfo("battery") {
    override fun name(context: Context) = "Battery"
    override fun sentenceExample(context: Context) = "How much battery is left?"
    @Composable override fun icon() = rememberVectorPainter(Icons.Default.BatteryStd)
    override fun build(ctx: SkillContext): Skill<*>? =
        Sentences.Battery[ctx.sentencesLanguage]?.let { BatterySkill(this, it) }
}

class BatterySkill(info: SkillInfo, data: StandardRecognizerData<Battery>) :
    StandardRecognizerSkill<Battery>(info, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Battery): SkillOutput {
        val bm = ctx.android.getSystemService(BatteryManager::class.java)
            ?: return SpokenOutput("I can't read the battery")
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return SpokenOutput(
            "Battery is at $level%" + if (charging) " and charging" else ""
        )
    }
}
