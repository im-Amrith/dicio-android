package org.stypox.dicio.io.input.whisper

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.stypox.dicio.io.input.InputEvent
import java.io.File

/**
 * Holds the loaded Whisper recognizer and Silero VAD in RAM, and runs one listening session at a
 * time: it records from the microphone, uses the VAD to detect when the user starts and stops
 * talking, emits [InputEvent.Partial] transcriptions while the user is talking, and finally
 * transcribes the whole utterance with Whisper and emits [InputEvent.Final].
 */
class WhisperEngine(
    model: WhisperModel,
    modelDirectory: File,
    private val language: String,
) {
    private val recognizer: OfflineRecognizer
    private val vad: Vad
    private val decodeMutex = Mutex()

    init {
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(modelDirectory, model.encoderFile).absolutePath,
                    decoder = File(modelDirectory, model.decoderFile).absolutePath,
                    language = if (model.multilingual) language else "",
                    task = "transcribe",
                ),
                tokens = File(modelDirectory, model.tokensFile).absolutePath,
                numThreads = NUM_THREADS,
                modelType = "whisper",
                provider = "cpu",
            ),
        )
        recognizer = OfflineRecognizer(config = config)

        vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = File(modelDirectory, VAD_FILE).absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.1f,
                    windowSize = VAD_WINDOW,
                    maxSpeechDuration = MAX_UTTERANCE_SECONDS.toFloat(),
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
            ),
        )
    }

    /**
     * Starts recording in [scope] and returns the job, which completes after exactly one of
     * [InputEvent.Final], [InputEvent.None] or [InputEvent.Error] was sent to [eventListener]
     * (or when it is cancelled, in which case nothing is sent).
     *
     * @param silenceMillis how long the user has to be silent after talking for listening to stop
     * @param onFinished called right before the final event is emitted, so that the caller can
     * update its state before the event listener reacts
     */
    fun listen(
        scope: CoroutineScope,
        silenceMillis: Long,
        eventListener: (InputEvent) -> Unit,
        onFinished: () -> Unit,
    ): Job = scope.launch(Dispatchers.IO) {
        val result = try {
            record(silenceMillis, eventListener)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Whisper listening failed", e)
            InputEvent.Error(e)
        }
        if (isActive) { // if stopped by the user, the caller already sent InputEvent.None
            onFinished()
            eventListener(result)
        }
    }

    @SuppressLint("MissingPermission") // the UI layer only lets the user listen with permission
    private suspend fun record(
        silenceMillis: Long,
        eventListener: (InputEvent) -> Unit,
    ): InputEvent {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, SAMPLE_RATE * 2 * 4), // room for ~4s, so partial decodes never drop audio
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IllegalStateException("Could not open the microphone")
        }

        vad.reset()
        val audio = FloatArrayBuilder(SAMPLE_RATE * MAX_UTTERANCE_SECONDS)
        val chunk = ShortArray(VAD_WINDOW)
        val floatChunk = FloatArray(VAD_WINDOW)

        var speechStartSample = -1
        var lastSpeechSample = -1
        var lastPartialSample = 0
        var partialJob: Job? = null

        try {
            audioRecord.startRecording()
            while (currentCoroutineContext().isActive) {
                val read = audioRecord.read(chunk, 0, chunk.size)
                if (read <= 0) continue
                for (i in 0 until read) {
                    floatChunk[i] = chunk[i] / 32768.0f
                }
                val samples = if (read == VAD_WINDOW) floatChunk else floatChunk.copyOf(read)
                audio.append(samples)

                vad.acceptWaveform(samples)
                while (!vad.empty()) vad.pop() // we keep our own audio, segments aren't needed
                if (vad.isSpeechDetected()) {
                    if (speechStartSample < 0) {
                        speechStartSample = maxOf(0, audio.size - PRE_ROLL_SAMPLES)
                    }
                    lastSpeechSample = audio.size
                }

                val now = audio.size
                if (speechStartSample < 0) {
                    if (now > SAMPLE_RATE * NO_SPEECH_TIMEOUT_SECONDS) {
                        return InputEvent.None
                    }
                    continue
                }

                val silenceSamples = now - lastSpeechSample
                val tooLong = now - speechStartSample >= SAMPLE_RATE * MAX_UTTERANCE_SECONDS
                if (silenceSamples >= silenceMillis * SAMPLE_RATE / 1000 || tooLong) {
                    break
                }

                // show what the user is saying while they talk, without blocking recording
                if (now - lastPartialSample >= PARTIAL_EVERY_SAMPLES && partialJob?.isActive != true) {
                    lastPartialSample = now
                    val snapshot = audio.slice(speechStartSample, now)
                    partialJob = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Default) {
                        val text = transcribe(snapshot)
                        if (text.isNotEmpty() && isActive) {
                            eventListener(InputEvent.Partial(text))
                        }
                    }
                }
            }
        } finally {
            try { audioRecord.stop() } catch (_: IllegalStateException) {}
            audioRecord.release()
        }

        partialJob?.cancel()
        partialJob?.join()
        if (speechStartSample < 0) return InputEvent.None

        val text = transcribe(audio.slice(speechStartSample, audio.size))
        return if (text.isEmpty()) {
            InputEvent.None
        } else {
            InputEvent.Final(listOf(Pair(text, 1.0f)))
        }
    }

    private suspend fun transcribe(samples: FloatArray): String = decodeMutex.withLock {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            cleanTranscription(recognizer.getResult(stream).text)
        } finally {
            stream.release()
        }
    }

    fun release() {
        recognizer.release()
        vad.release()
    }

    /**
     * Growable float buffer, to avoid boxing millions of samples in a List<Float>.
     */
    private class FloatArrayBuilder(initialCapacity: Int) {
        private var data = FloatArray(initialCapacity)
        var size = 0
            private set

        fun append(values: FloatArray) {
            if (size + values.size > data.size) {
                data = data.copyOf(maxOf(data.size * 2, size + values.size))
            }
            values.copyInto(data, size)
            size += values.size
        }

        fun slice(from: Int, to: Int): FloatArray = data.copyOfRange(from, to)
    }

    companion object {
        private val TAG = WhisperEngine::class.simpleName
        const val SAMPLE_RATE = 16000
        const val VAD_FILE = "silero_vad.onnx"
        private const val VAD_WINDOW = 512
        private const val NUM_THREADS = 4
        private const val PRE_ROLL_SAMPLES = SAMPLE_RATE * 3 / 10 // keep 300ms before speech
        private const val PARTIAL_EVERY_SAMPLES = SAMPLE_RATE * 3 / 2 // partial every 1.5s
        private const val NO_SPEECH_TIMEOUT_SECONDS = 6
        private const val MAX_UTTERANCE_SECONDS = 20

        // Whisper sometimes "hears" these in silence or noise
        private val NON_SPEECH = Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*""")

        /**
         * Whisper outputs "Play Believer on Spotify." with casing and punctuation, while Dicio's
         * sentence matchers were written for Vosk's lowercase, unpunctuated output, so normalize.
         */
        internal fun cleanTranscription(raw: String): String = raw
            .replace(NON_SPEECH, " ")
            .lowercase()
            .replace(Regex("""[^\p{L}\p{N}'\s-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
