package org.stypox.dicio.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.stypox.dicio.ui.util.Progress
import org.stypox.dicio.util.FileToDownload
import org.stypox.dicio.util.downloadBinaryFilesWithPartial
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mello's "AI brain": a small LLM (Qwen3 1.7B, int4) that runs fully on the phone through
 * LiteRT-LM. It is only asked when none of the built-in skills understood the user. It either
 * rewrites the request into a command that a skill understands ("play believer on spotify"),
 * or answers directly.
 */
@Singleton
class AiBrain @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val okHttpClient: OkHttpClient,
) {
    sealed interface Status {
        data object NotDownloaded : Status
        data class Downloading(val progress: Progress) : Status
        data class ErrorDownloading(val throwable: Throwable) : Status
        data object Ready : Status
    }

    sealed interface Result {
        /** A command for the regular skills, e.g. "set a timer for 5 minutes" */
        data class Command(val text: String) : Result
        /** Something to say to the user */
        data class Answer(val text: String) : Result
        /** The model can't be used right now (e.g. still downloading), [message] says why */
        data class Unavailable(val message: String) : Result
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val modelDir = File(appContext.filesDir, "ai")
    private val modelFile = File(modelDir, MODEL_FILE_NAME)

    private val _status = MutableStateFlow(
        if (modelFile.exists() && !FileToDownload(MODEL_URL, modelFile).needsToBeDownloaded()) {
            Status.Ready
        } else {
            Status.NotDownloaded
        }
    )
    val status: StateFlow<Status> = _status

    private val engineMutex = Mutex()
    private var engine: Engine? = null
    private var releaseJob: Job? = null

    /** Starts downloading the model in the background, if it isn't there yet. */
    fun startDownload() {
        val s = _status.value
        if (s is Status.Downloading || s is Status.Ready) return
        _status.value = Status.Downloading(Progress.UNKNOWN)
        scope.launch {
            try {
                modelDir.mkdirs()
                downloadBinaryFilesWithPartial(
                    urlsFiles = listOf(FileToDownload(MODEL_URL, modelFile)),
                    httpClient = okHttpClient,
                    cacheDir = appContext.cacheDir,
                ) { progress -> _status.value = Status.Downloading(progress) }
                _status.value = Status.Ready
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Could not download the AI model", e)
                _status.value = Status.ErrorDownloading(e)
            }
        }
    }

    suspend fun interpret(userInput: String): Result {
        when (val s = _status.value) {
            Status.NotDownloaded, is Status.ErrorDownloading -> {
                startDownload()
                return Result.Unavailable(
                    "I didn't get that. I'm downloading my AI brain (about 1 GB) so I can " +
                        "understand more; it works offline once it's done."
                )
            }
            is Status.Downloading -> {
                val percent = if (s.progress.totalBytes > 0) {
                    " (${s.progress.currentBytes * 100 / s.progress.totalBytes}%)"
                } else ""
                return Result.Unavailable("I didn't get that. My AI brain is still downloading$percent.")
            }
            Status.Ready -> {}
        }

        return withContext(Dispatchers.Default) {
            engineMutex.withLock {
                releaseJob?.cancel()
                try {
                    val eng = engine ?: loadEngine().also { engine = it }
                    val reply = eng.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(SYSTEM_PROMPT),
                            samplerConfig = SamplerConfig(topK = 1, topP = 0.95, temperature = 0.2),
                        )
                    ).use { conversation ->
                        // "/no_think" turns off Qwen3's slow step-by-step reasoning
                        conversation.sendMessage("$userInput /no_think").toString()
                    }
                    Log.d(TAG, "AI reply for \"$userInput\": $reply")
                    parseReply(reply)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "AI brain failed", e)
                    Result.Unavailable("Sorry, my AI brain hit an error: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    scheduleRelease()
                }
            }
        }
    }

    /** Tries the GPU first (much faster on recent phones), then falls back to the CPU. */
    private fun loadEngine(): Engine {
        var lastError: Throwable? = null
        for (backend in listOf<() -> Backend>({ Backend.GPU() }, { Backend.CPU() })) {
            try {
                val newEngine = Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = backend(),
                        maxNumTokens = MAX_TOKENS,
                        cacheDir = appContext.cacheDir.absolutePath,
                    )
                )
                newEngine.initialize()
                return newEngine
            } catch (e: Throwable) {
                Log.w(TAG, "Could not load the AI model with this backend", e)
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Could not load the AI model")
    }

    /** Frees the ~1.5 GB of RAM the model uses after a few idle minutes. */
    private fun scheduleRelease() {
        releaseJob = scope.launch {
            delay(IDLE_RELEASE_MILLIS)
            engineMutex.withLock {
                engine?.close()
                engine = null
            }
        }
    }

    companion object {
        private val TAG = AiBrain::class.simpleName
        private const val MODEL_FILE_NAME = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/$MODEL_FILE_NAME"
        private const val MAX_TOKENS = 1536
        private const val IDLE_RELEASE_MILLIS = 5 * 60 * 1000L

        private val THINK_BLOCK = Regex("""<think>[\s\S]*?</think>""")
        private val PREFIX = Regex("""^\s*(CMD|SAY)\s*:\s*""", RegexOption.IGNORE_CASE)

        internal fun parseReply(raw: String): Result {
            val text = raw.replace(THINK_BLOCK, "").replace("<think>", "").replace("</think>", "").trim()
            val line = text.lines().map { it.trim() }.firstOrNull { PREFIX.containsMatchIn(it) }
            if (line != null) {
                val kind = PREFIX.find(line)!!.groupValues[1].uppercase()
                val body = line.replace(PREFIX, "").trim().trim('"')
                if (kind == "CMD" && body.isNotEmpty()) {
                    // same normalization as speech input, so the skills match it
                    return Result.Command(
                        body.lowercase()
                            .replace(Regex("""[^\p{L}\p{N}'\s-]"""), " ")
                            .replace(Regex("""\s+"""), " ")
                            .trim()
                    )
                }
                if (body.isNotEmpty()) return Result.Answer(body)
            }
            return if (text.isNotEmpty()) Result.Answer(text) else Result.Unavailable("Sorry, I didn't get that.")
        }

        private val SYSTEM_PROMPT = """
            You are Mello, a friendly voice assistant on the user's Android phone. The user's words
            were transcribed from speech and may contain small mistakes. Reply with exactly ONE line,
            in one of these two forms:

            CMD: <command>
              Use this when the user wants the phone to do something. Rewrite the request as one of
              these commands, filling in the <...> parts:
              play <song, artist, album or playlist> on spotify
              pause the music | resume the music | next song | previous song
              open <app name>
              set a timer for <duration>
              call <contact name>
              navigate to <place>
              what is the weather in <city>
              what time is it
              search <query>
              turn on the flashlight | turn off the flashlight
              calculate <math expression>
              translate <text> to <language>
              lyrics of <song>
              tell me a joke

            SAY: <answer>
              Use this for questions, chatting, or anything no command fits. Answer in at most two
              short sentences, written to be spoken aloud: no lists, no markdown, no emojis.

            Examples:
            User: can you put on some arijit singh
            CMD: play arijit singh on spotify
            User: i need to leave in 10 minutes remind me
            CMD: set a timer for 10 minutes
            User: whatsapp kholo
            CMD: open whatsapp
            User: how far is the moon
            SAY: The moon is about 384,000 kilometres away from Earth.
            User: thanks mello
            SAY: Anytime!
        """.trimIndent()
    }
}
