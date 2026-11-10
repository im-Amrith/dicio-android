package org.stypox.dicio.skills.extra

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.stypox.dicio.skills.music.SpotifyPlayPresser
import org.stypox.dicio.util.extractZip
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Reels mode": while Instagram is on screen, reel commands work without saying "Hey Jarvis".
 *
 * The wake word service already reads the microphone all the time; it hands every audio frame to
 * [feed] too. When Instagram is in front, the frames go to a small Vosk recognizer that only knows
 * the reel commands (anything else is "[unk]"), so it is fast and rarely confused:
 * - "scroll down/up", "next/previous reel" act as soon as they are heard (partial results);
 * - one-word commands like "pause" act only if they were the whole utterance, so that speech in
 *   the reel itself doesn't trigger them.
 */
object ReelsVoiceControl {
    private const val TAG = "ReelsVoiceControl"
    private const val INSTAGRAM = "com.instagram.android"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"
    private const val FOREGROUND_CHECK_MILLIS = 1000L
    private const val COOLDOWN_MILLIS = 700L
    private const val UNLOAD_AFTER_MILLIS = 2 * 60 * 1000L

    private enum class Action { NEXT, PREVIOUS, PAUSE, PLAY, FORWARD, REWIND }

    // phrases that act immediately, while still being spoken
    private val INSTANT = mapOf(
        "scroll down" to Action.NEXT, "next reel" to Action.NEXT, "swipe up" to Action.NEXT,
        "scroll up" to Action.PREVIOUS, "previous reel" to Action.PREVIOUS, "swipe down" to Action.PREVIOUS,
        "stop reel" to Action.PAUSE, "pause reel" to Action.PAUSE, "play reel" to Action.PLAY,
        "skip forward" to Action.FORWARD, "skip back" to Action.REWIND,
    )

    // one-word commands: only when said on their own
    private val ALONE = mapOf(
        "next" to Action.NEXT, "previous" to Action.PREVIOUS, "back" to Action.PREVIOUS,
        "pause" to Action.PAUSE, "stop" to Action.PAUSE, "play" to Action.PLAY,
        "resume" to Action.PLAY, "forward" to Action.FORWARD, "rewind" to Action.REWIND,
    )

    private val GRAMMAR: String = JSONArray(INSTANT.keys + ALONE.keys + "[unk]").toString()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var preparing = false
    @Volatile private var model: Model? = null
    @Volatile private var recognizer: Recognizer? = null
    private var instagramInFront = false
    private var lastForegroundCheck = 0L
    private var lastInstagramSeen = 0L
    private var lastActionAt = 0L

    /** Called by the wake word service with every audio frame (16 kHz mono PCM). */
    fun feed(context: Context, audio: ShortArray) {
        val now = System.currentTimeMillis()
        if (now - lastForegroundCheck >= FOREGROUND_CHECK_MILLIS) {
            lastForegroundCheck = now
            instagramInFront = SpotifyPlayPresser.instance?.foregroundPackage() == INSTAGRAM
            if (instagramInFront) lastInstagramSeen = now
            else if (recognizer != null && now - lastInstagramSeen > UNLOAD_AFTER_MILLIS) unload()
        }
        if (!instagramInFront) return

        val rec = recognizer ?: run {
            prepare(context)
            return
        }

        try {
            val isFinal = rec.acceptWaveForm(audio, audio.size)
            if (isFinal) {
                val text = JSONObject(rec.result).optString("text").trim()
                ALONE[text]?.let { act(it, rec) } ?: INSTANT.entries
                    .firstOrNull { text.contains(it.key) }?.let { act(it.value, rec) }
            } else {
                val partial = JSONObject(rec.partialResult).optString("partial")
                INSTANT.entries.firstOrNull { partial.contains(it.key) }?.let { act(it.value, rec) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reels recognizer failed", e)
        }
    }

    private fun act(action: Action, rec: Recognizer) {
        rec.reset() // forget what was heard, so the same words don't trigger twice
        val now = System.currentTimeMillis()
        if (now - lastActionAt < COOLDOWN_MILLIS) return
        lastActionAt = now
        val helper = SpotifyPlayPresser.instance ?: return
        scope.launch {
            when (action) {
                Action.NEXT -> helper.swipeVertical(0.75f, 0.25f)
                Action.PREVIOUS -> helper.swipeVertical(0.25f, 0.75f)
                Action.PAUSE, Action.PLAY -> helper.tapCenter()
                Action.FORWARD -> helper.seekBy(5)
                Action.REWIND -> helper.seekBy(-5)
            }
        }
    }

    /** Downloads (once, ~36 MB) and loads the small Indian-English Vosk model in the background. */
    private fun prepare(context: Context) {
        if (preparing) return
        preparing = true
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "reels-vosk-model")
                if (!File(dir, "ivector").exists() && !File(dir, "am").exists()) {
                    val zip = File(context.cacheDir, "reels-vosk-model.zip")
                    download(MODEL_URL, zip)
                    dir.deleteRecursively()
                    extractZip(zip, dir) {}
                    zip.delete()
                }
                val m = Model(dir.absolutePath)
                model = m
                recognizer = Recognizer(m, 16000f, GRAMMAR)
                Log.i(TAG, "Reels voice control ready")
            } catch (e: Throwable) {
                Log.e(TAG, "Could not prepare reels voice control", e)
            } finally {
                preparing = false
            }
        }
    }

    private fun download(url: String, destination: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.inputStream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun unload() {
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
    }
}
