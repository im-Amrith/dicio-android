package org.stypox.dicio.ai

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.ui.util.Progress
import org.stypox.dicio.util.FileToDownload
import org.stypox.dicio.util.downloadBinaryFilesWithPartial
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mello's "AI brain", asked only when none of the built-in skills understood the user. It either
 * rewrites the request into a command a skill understands ("play believer on spotify"), searches
 * the web for current information, or answers directly.
 *
 * - Cloud (preferred): Google Gemini, when an API key is set in settings. Smart, remembers the
 *   last few exchanges so follow-up questions work.
 * - Local (fallback, offline): Qwen3 1.7B running on the phone through LiteRT-LM.
 *
 * Both can use [WebLookup] for news and other real-time information.
 */
@Singleton
class AiBrain @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val okHttpClient: OkHttpClient,
    private val dataStore: DataStore<UserSettings>,
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
        /** The AI can't be used right now (e.g. still downloading), [message] says why */
        data class Unavailable(val message: String) : Result
    }

    /** What the model asked for in one reply */
    internal sealed interface Reply {
        data class Cmd(val text: String) : Reply
        data class Say(val text: String) : Reply
        data class Search(val query: String) : Reply
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val gemini = GeminiClient(okHttpClient)
    private val web = WebLookup(okHttpClient)

    // last few cloud exchanges, so that "and what about tomorrow?" works
    private val history = ArrayDeque<GeminiClient.Turn>()
    private var lastExchangeMillis = 0L

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

    suspend fun interpret(userInput: String): Result {
        val apiKey = dataStore.data.first().geminiApiKey.trim()
        if (apiKey.isNotEmpty()) {
            try {
                return interpretWithCloud(apiKey, userInput)
            } catch (e: GeminiClient.ApiException) {
                Log.e(TAG, "Gemini error ${e.code}", e)
                val msg = e.message.orEmpty()
                if (msg.contains("prepay", true) || msg.contains("billing", true)) {
                    return Result.Unavailable(
                        "This Gemini key belongs to a project with billing on and no credit. Use " +
                            "a key from a free project (AI Studio, create key in new project)."
                    )
                }
                when (e.code) {
                    400, 401, 403 -> return Result.Unavailable(
                        "My cloud brain rejected the API key. Please check it in settings."
                    )
                    // 429 (free quota used up) and server errors: use the phone's model instead
                }
            } catch (e: IOException) {
                Log.w(TAG, "Cloud brain unreachable, using the local one", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Cloud brain failed, using the local one", e)
            }
        }
        return interpretLocally(userInput)
    }

    /* ---------------------------------- CLOUD ---------------------------------- */

    private suspend fun interpretWithCloud(apiKey: String, userInput: String): Result {
        if (System.currentTimeMillis() - lastExchangeMillis > HISTORY_TIMEOUT_MILLIS) {
            history.clear() // a new conversation, old context would only confuse the model
        }
        val turns = history.toMutableList()
        turns.add(GeminiClient.Turn(true, userInput))

        var raw = gemini.generate(apiKey, systemPrompt(), turns)
        var reply = parseReply(raw)
        if (reply is Reply.Search) {
            val results = web.search(reply.query)
            turns.add(GeminiClient.Turn(false, raw.trim()))
            turns.add(GeminiClient.Turn(true, searchResultsMessage(reply.query, results)))
            raw = gemini.generate(apiKey, systemPrompt(), turns)
            reply = parseReply(raw)
        }

        val result = reply.toResult()
        val remembered = when (result) {
            is Result.Answer -> "SAY: ${result.text}"
            is Result.Command -> "CMD: ${result.text}"
            is Result.Unavailable -> null
        }
        if (remembered != null) {
            history.addLast(GeminiClient.Turn(true, userInput))
            history.addLast(GeminiClient.Turn(false, remembered))
            while (history.size > MAX_HISTORY_TURNS) history.removeFirst()
            lastExchangeMillis = System.currentTimeMillis()
        }
        return result
    }

    /* ---------------------------------- LOCAL ---------------------------------- */

    /** Starts downloading the on-phone model in the background, if it isn't there yet. */
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

    private suspend fun interpretLocally(userInput: String): Result {
        when (val s = _status.value) {
            Status.NotDownloaded, is Status.ErrorDownloading -> {
                startDownload()
                return Result.Unavailable(
                    "I didn't get that. For smarter answers, add a free Gemini API key in " +
                        "settings. Meanwhile I'm downloading my offline brain (about 1 GB)."
                )
            }
            is Status.Downloading -> {
                val percent = if (s.progress.totalBytes > 0) {
                    " (${s.progress.currentBytes * 100 / s.progress.totalBytes}%)"
                } else ""
                return Result.Unavailable("I didn't get that. My offline brain is still downloading$percent.")
            }
            Status.Ready -> {}
        }

        return withContext(Dispatchers.Default) {
            engineMutex.withLock {
                releaseJob?.cancel()
                try {
                    val eng = engine ?: loadEngine().also { engine = it }
                    eng.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(systemPrompt()),
                            samplerConfig = SamplerConfig(topK = 1, topP = 0.95, temperature = 0.2),
                        )
                    ).use { conversation ->
                        // "/no_think" turns off Qwen3's slow step-by-step reasoning
                        var reply = parseReply(
                            conversation.sendMessage("$userInput /no_think").toString()
                        )
                        if (reply is Reply.Search) {
                            val results = web.search(reply.query)
                            reply = parseReply(
                                conversation.sendMessage(
                                    searchResultsMessage(reply.query, results) + " /no_think"
                                ).toString()
                            )
                        }
                        reply.toResult()
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Local AI brain failed", e)
                    Result.Unavailable(
                        "Sorry, my offline brain hit an error: ${e.message ?: e.javaClass.simpleName}"
                    )
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

    /** Frees the ~1.5 GB of RAM the local model uses after a few idle minutes. */
    private fun scheduleRelease() {
        releaseJob = scope.launch {
            delay(IDLE_RELEASE_MILLIS)
            engineMutex.withLock {
                engine?.close()
                engine = null
            }
        }
    }

    /* --------------------------------- SHARED ---------------------------------- */

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date())
        return SYSTEM_PROMPT_TEMPLATE
            .replace("{NOW}", now)
            .replace("{TIMEZONE}", TimeZone.getDefault().id)
    }

    private fun searchResultsMessage(query: String, results: String) =
        "Search results for \"$query\":\n$results\n\n" +
            "Using these results, answer my previous request with a single SAY line."

    private fun Reply.toResult(): Result = when (this) {
        is Reply.Cmd -> Result.Command(text)
        is Reply.Say -> Result.Answer(shortenForSpeech(text))
        // a second search is not allowed, so the model gave up
        is Reply.Search -> Result.Answer("Sorry, I couldn't find anything about that.")
    }

    companion object {
        private val TAG = AiBrain::class.simpleName
        private const val MODEL_FILE_NAME = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/$MODEL_FILE_NAME"
        private const val MAX_TOKENS = 2048
        private const val IDLE_RELEASE_MILLIS = 5 * 60 * 1000L
        private const val MAX_HISTORY_TURNS = 12
        private const val HISTORY_TIMEOUT_MILLIS = 10 * 60 * 1000L

        private val THINK_BLOCK = Regex("""<think>[\s\S]*?</think>""")
        private val PREFIX = Regex("""^\W*(CMD|SAY|SEARCH)\s*:\s*""", RegexOption.IGNORE_CASE)

        private const val MAX_SPOKEN_SENTENCES = 3
        private const val MAX_SPOKEN_CHARS = 350
        private val SENTENCE_END = Regex("""(?<=[.!?])\s+""")
        private val LIST_OR_HEADING = Regex("""^\s*(\d+[.)]|[-•#>])\s*""")

        /**
         * Small models sometimes ignore the "short answer" instruction and write essays with
         * numbered lists: keep only the first few sentences of normal prose, since this is spoken.
         */
        internal fun shortenForSpeech(text: String): String {
            val prose = text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !LIST_OR_HEADING.containsMatchIn(it) }
                .joinToString(" ")
                .replace(Regex("""[*_#`]"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .ifEmpty { text.replace(Regex("""\s+"""), " ").trim() }
            // sentences like "Here's an overview of its key milestones:" introduce a list that
            // was cut away, so they are dropped too
            val sentences = SENTENCE_END.split(prose).filter { !it.trimEnd().endsWith(":") }
            var short = (sentences.ifEmpty { listOf(prose) })
                .take(MAX_SPOKEN_SENTENCES).joinToString(" ")
            if (short.length > MAX_SPOKEN_CHARS) {
                val cut = short.take(MAX_SPOKEN_CHARS)
                short = cut.substring(0, cut.lastIndexOf(' ').takeIf { it > 0 } ?: cut.length) + "…"
            }
            return short
        }

        internal fun parseReply(raw: String): Reply {
            val text = raw.replace(THINK_BLOCK, "")
                .replace("<think>", "").replace("</think>", "").trim()
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val index = lines.indexOfFirst { PREFIX.containsMatchIn(it) }
            if (index >= 0) {
                val line = lines[index]
                val kind = PREFIX.find(line)!!.groupValues[1].uppercase()
                // SAY answers may continue on the next lines
                val body = (listOf(line.replace(PREFIX, "")) + lines.drop(index + 1)
                    .takeWhile { !PREFIX.containsMatchIn(it) && kind == "SAY" })
                    .joinToString(" ")
                    .replace("*", "")
                    .trim().trim('"')
                if (body.isNotEmpty()) {
                    return when (kind) {
                        "CMD" -> Reply.Cmd(
                            // same normalization as speech input, so the skills match it
                            body.lowercase()
                                .replace(Regex("""[^\p{L}\p{N}'\s-]"""), " ")
                                .replace(Regex("""\s+"""), " ")
                                .trim()
                        )
                        "SEARCH" -> Reply.Search(body)
                        else -> Reply.Say(body)
                    }
                }
            }
            return Reply.Say(text.replace("*", "").ifEmpty { "Sorry, I didn't get that." })
        }

        private val SYSTEM_PROMPT_TEMPLATE = """
            You are Mello, a friendly, smart voice assistant on the user's Android phone.
            Right now it is {NOW} (time zone {TIMEZONE}). The user's words were transcribed from
            speech and may contain small mistakes; guess what they meant. Reply with exactly ONE of:

            CMD: <command>
              When the user wants the phone to do something. Rewrite the request as one of these
              commands, filling in the <...> parts and keeping names exactly as the user said them:
              play <song, artist, album or playlist> on spotify
              pause the music | resume the music | next song | previous song
              open <app name>
              set a timer for <duration>
              call <contact name>
              navigate to <place>
              what is the weather in <city>
              turn on the flashlight | turn off the flashlight
              calculate <math expression>
              translate <text> to <language>
              tell me a joke

            SEARCH: <search query>
              When the answer needs current or real-time information: news, sports scores, prices,
              stock or crypto values, recent events, new releases, anything that may have changed
              recently. You will get search results back, then answer with SAY.

            SAY: <answer>
              For everything else: questions, chatting, advice. Answer in at most three short
              sentences written to be spoken aloud: no lists, no markdown, no emojis. For news,
              give the two or three most important headlines in one sentence each.

            Examples:
            User: can you put on some arijit singh
            CMD: play arijit singh on spotify
            User: i need to leave in 10 minutes remind me
            CMD: set a timer for 10 minutes
            User: phone amma
            CMD: call amma
            User: whatsapp kholo
            CMD: open whatsapp
            User: what's the latest news
            SEARCH: top news today India
            User: who won the match yesterday
            SEARCH: cricket match result yesterday
            User: how far is the moon
            SAY: The moon is about 384,000 kilometres away from Earth.
        """.trimIndent()
    }
}
