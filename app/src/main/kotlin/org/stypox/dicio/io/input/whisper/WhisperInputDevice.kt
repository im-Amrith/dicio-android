package org.stypox.dicio.io.input.whisper

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.io.input.whisper.WhisperState.Downloading
import org.stypox.dicio.io.input.whisper.WhisperState.ErrorDownloading
import org.stypox.dicio.io.input.whisper.WhisperState.ErrorLoading
import org.stypox.dicio.io.input.whisper.WhisperState.Listening
import org.stypox.dicio.io.input.whisper.WhisperState.Loaded
import org.stypox.dicio.io.input.whisper.WhisperState.Loading
import org.stypox.dicio.io.input.whisper.WhisperState.NotDownloaded
import org.stypox.dicio.io.input.whisper.WhisperState.NotInitialized
import org.stypox.dicio.io.input.whisper.WhisperState.NotLoaded
import org.stypox.dicio.ui.util.Progress
import org.stypox.dicio.util.FileToDownload
import org.stypox.dicio.util.distinctUntilChangedBlockingFirst
import org.stypox.dicio.util.downloadBinaryFilesWithPartial
import java.io.File
import java.util.Locale

/**
 * Offline speech to text with OpenAI Whisper, run through sherpa-onnx. Much more accurate than the
 * small Vosk models, especially with accents, at the cost of a bigger download and more RAM.
 *
 * The flow is the same as [org.stypox.dicio.io.input.vosk.VoskInputDevice]: the first click
 * downloads the model, then it is loaded in RAM, then each click toggles listening.
 */
class WhisperInputDevice(
    @ApplicationContext appContext: Context,
    private val okHttpClient: OkHttpClient,
    localeManager: LocaleManager,
    private val silencesBeforeStop: StateFlow<Int>,
) : SttInputDevice {

    private val scope = CoroutineScope(Dispatchers.Default)
    private val lock = Any()

    private val _state = MutableStateFlow<WhisperState>(NotInitialized)
    private val _uiState = MutableStateFlow<SttState>(SttState.NotInitialized)
    override val uiState: StateFlow<SttState> = _uiState

    private val cacheDir: File = appContext.cacheDir
    private val rootDir: File = File(appContext.filesDir, "whisper")
    private var language: String = "en"
    private var model: WhisperModel = WhisperModel.ENGLISH_MODEL
    private val modelDirectory get() = File(rootDir, model.name.lowercase())

    private var operationsJob: Job? = null
    private var listeningJob: Job? = null

    init {
        // Blocking, like Vosk, so that the tryLoad() at app start is not lost
        val (firstLocale, nextLocaleFlow) = localeManager.locale
            .distinctUntilChangedBlockingFirst()
        setState(init(firstLocale))

        scope.launch {
            _state.collect { _uiState.value = it.toUiState() }
        }
        scope.launch {
            nextLocaleFlow.collect { locale ->
                deinit()
                setState(init(locale))
            }
        }
    }

    private fun setState(state: WhisperState) {
        _state.value = state
    }

    private fun filesToDownload(): List<FileToDownload> =
        model.filesToDownload().map { (url, name) -> FileToDownload(url, File(modelDirectory, name)) }

    private fun init(locale: Locale): WhisperState {
        language = locale.language.ifEmpty { "en" }
        model = WhisperModel.forLanguage(language)
        return if (filesToDownload().any { it.needsToBeDownloaded() || !it.file.exists() }) {
            NotDownloaded
        } else {
            NotLoaded
        }
    }

    private suspend fun deinit() {
        val prev = synchronized(lock) {
            _state.value.also { setState(NotInitialized) }
        }
        operationsJob?.cancel()
        operationsJob?.join()
        listeningJob?.cancel()
        listeningJob?.join()
        when (prev) {
            is Loaded -> prev.engine.release()
            is Listening -> prev.engine.release()
            else -> {}
        }
    }

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        synchronized(lock) {
            return when (val s = _state.value) {
                NotLoaded, is ErrorLoading -> {
                    load(thenStartListeningEventListener); true
                }
                is Loaded -> {
                    thenStartListeningEventListener?.let { startListening(s.engine, it) }; true
                }
                else -> false
            }
        }
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        synchronized(lock) {
            when (val s = _state.value) {
                NotInitialized, is Downloading -> {} // wait
                NotDownloaded, is ErrorDownloading -> download()
                NotLoaded, is ErrorLoading -> load(eventListener)
                // toggle whether to start listening once loaded
                is Loading -> setState(
                    Loading(if (s.thenStartListening == null) eventListener else null)
                )
                is Loaded -> startListening(s.engine, eventListener)
                is Listening -> stopListeningLocked(s, sendNoneEvent = true)
            }
        }
    }

    override fun stopListening() {
        synchronized(lock) {
            (_state.value as? Listening)?.let { stopListeningLocked(it, sendNoneEvent = true) }
        }
    }

    // must be called while holding [lock]
    private fun download() {
        setState(Downloading(Progress.UNKNOWN))
        operationsJob = scope.launch(Dispatchers.IO) {
            try {
                modelDirectory.mkdirs()
                downloadBinaryFilesWithPartial(
                    urlsFiles = filesToDownload(),
                    httpClient = okHttpClient,
                    cacheDir = cacheDir,
                ) { progress -> setState(Downloading(progress)) }
                // free space taken by models for other languages/sizes
                rootDir.listFiles()?.filter { it != modelDirectory }?.forEach { it.deleteRecursively() }
                setState(NotLoaded)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Can't download Whisper model", e)
                setState(ErrorDownloading(e))
            }
        }
    }

    // must be called while holding [lock]
    private fun load(thenStartListening: ((InputEvent) -> Unit)?) {
        setState(Loading(thenStartListening))
        operationsJob = scope.launch {
            val engine = try {
                WhisperEngine(model, modelDirectory, language)
            } catch (e: Throwable) {
                Log.e(TAG, "Can't load Whisper model", e)
                synchronized(lock) {
                    if (_state.value is Loading) setState(ErrorLoading(e))
                }
                return@launch
            }

            synchronized(lock) {
                val s = _state.value
                if (s !is Loading) {
                    engine.release() // deinit() happened in the meantime
                    return@launch
                }
                val listener = s.thenStartListening
                if (listener == null) setState(Loaded(engine)) else startListening(engine, listener)
            }
        }
    }

    // must be called while holding [lock]
    private fun startListening(engine: WhisperEngine, eventListener: (InputEvent) -> Unit) {
        val listening = Listening(engine, eventListener)
        setState(listening)
        val silenceMillis = (silencesBeforeStop.value * SILENCE_UNIT_MILLIS)
            .coerceIn(MIN_SILENCE_MILLIS, MAX_SILENCE_MILLIS)
        listeningJob = engine.listen(scope, silenceMillis, eventListener) {
            synchronized(lock) {
                if (_state.value === listening) setState(Loaded(engine))
            }
        }
    }

    // must be called while holding [lock]
    private fun stopListeningLocked(s: Listening, sendNoneEvent: Boolean) {
        listeningJob?.cancel()
        listeningJob = null
        setState(Loaded(s.engine))
        if (sendNoneEvent) s.eventListener(InputEvent.None)
    }

    override suspend fun destroy() {
        deinit()
        scope.cancel()
    }

    companion object {
        private val TAG = WhisperInputDevice::class.simpleName

        // the "silences before stop" setting (default 2) is multiplied by this
        private const val SILENCE_UNIT_MILLIS = 500L
        private const val MIN_SILENCE_MILLIS = 500L
        private const val MAX_SILENCE_MILLIS = 4000L
    }
}
