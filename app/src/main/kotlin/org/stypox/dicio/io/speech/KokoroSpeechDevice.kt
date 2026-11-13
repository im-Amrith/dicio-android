package org.stypox.dicio.io.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.dicio.skill.context.SpeechOutputDevice
import org.stypox.dicio.util.FileToDownload
import org.stypox.dicio.util.downloadBinaryFilesWithPartial
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Jarvis's voice: Kokoro-82M (int8), the best-sounding small open-source TTS, run offline through
 * sherpa-onnx with the British male voice "bm_george".
 *
 * The model (~148 MB) downloads in the background the first time; until it's ready, the phone's
 * regular Android voice is used. Each sentence is played as soon as it is generated, so long
 * answers start speaking quickly.
 */
class KokoroSpeechDevice(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    locale: Locale,
) : SpeechOutputDevice {

    private val fallback = AndroidTtsSpeechDevice(context, locale)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val modelDir = File(context.filesDir, "kokoro")
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null

    private val pending = AtomicInteger(0)
    private val stopRequested = AtomicBoolean(false)
    private val runnablesWhenFinished = mutableListOf<Runnable>()

    init {
        scope.launch {
            try {
                val files = FILES.map { FileToDownload("$BASE_URL/$it", File(modelDir, it)) }
                if (files.any { it.needsToBeDownloaded() || !it.file.exists() }) {
                    modelDir.mkdirs()
                    downloadBinaryFilesWithPartial(files, okHttpClient, context.cacheDir) {}
                }
                tts = OfflineTts(
                    config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            kokoro = OfflineTtsKokoroModelConfig(
                                model = File(modelDir, "model.int8.onnx").absolutePath,
                                voices = File(modelDir, "voices.bin").absolutePath,
                                tokens = File(modelDir, "tokens.txt").absolutePath,
                                lexicon = File(modelDir, "lexicon-gb-en.txt").absolutePath,
                            ),
                            numThreads = 4,
                        ),
                        maxNumSentences = 1, // generate (and play) one sentence at a time
                    )
                )
                Log.i(TAG, "Kokoro voice ready")
            } catch (e: Throwable) {
                Log.e(TAG, "Kokoro voice unavailable, using the Android voice", e)
            }
        }
    }

    override fun speak(speechOutput: String) {
        val engine = tts
        if (engine == null) {
            fallback.speak(speechOutput)
            return
        }
        val text = SpeechText.normalize(speechOutput)
        if (text.isBlank()) return

        stopRequested.set(false)
        pending.incrementAndGet()
        worker.execute {
            try {
                if (!stopRequested.get()) play(engine, text)
            } catch (e: Throwable) {
                Log.e(TAG, "Kokoro failed to speak", e)
            } finally {
                if (pending.decrementAndGet() == 0) onAllFinished()
            }
        }
    }

    private fun play(engine: OfflineTts, text: String) {
        val sampleRate = engine.sampleRate()
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
                ) * 4
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = audioTrack
        var written = 0
        try {
            audioTrack.play()
            engine.generateWithCallback(text = text, sid = VOICE_ID, speed = SPEED) { samples ->
                if (stopRequested.get()) return@generateWithCallback 0 // stop generating
                audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                written += samples.size
                1
            }
            // wait until everything written has actually been played
            while (!stopRequested.get() && audioTrack.playbackHeadPosition < written) {
                Thread.sleep(20)
            }
        } finally {
            track = null
            runCatching { audioTrack.stop() }
            audioTrack.release()
        }
    }

    private fun onAllFinished() {
        mainHandler.post {
            val toRun = runnablesWhenFinished.toList()
            runnablesWhenFinished.clear()
            toRun.forEach { it.run() }
        }
    }

    override fun stopSpeaking() {
        fallback.stopSpeaking()
        stopRequested.set(true)
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    override val isSpeaking: Boolean
        get() = pending.get() > 0 || fallback.isSpeaking

    override fun runWhenFinishedSpeaking(runnable: Runnable) {
        if (pending.get() > 0) {
            mainHandler.post {
                if (pending.get() > 0) runnablesWhenFinished.add(runnable) else runnable.run()
            }
        } else {
            fallback.runWhenFinishedSpeaking(runnable)
        }
    }

    override fun cleanup() {
        stopSpeaking()
        fallback.cleanup()
        scope.cancel()
        worker.execute {
            tts?.release()
            tts = null
        }
        worker.shutdown()
    }

    companion object {
        private val TAG = KokoroSpeechDevice::class.simpleName
        private const val BASE_URL =
            "https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_0/resolve/main"
        private val FILES = listOf("model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-gb-en.txt")

        // speaker 26 of Kokoro v1.0 is "bm_george", a calm British male voice
        private const val VOICE_ID = 26
        private const val SPEED = 1.05f
    }
}
