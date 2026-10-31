package org.stypox.dicio.io.input.whisper

import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.ui.util.Progress

/**
 * Internal state of [WhisperInputDevice]. Mirrors [org.stypox.dicio.io.input.vosk.VoskState] but
 * without the unzip steps, since sherpa-onnx Whisper models are downloaded as plain files.
 */
sealed interface WhisperState {
    data object NotInitialized : WhisperState
    data object NotDownloaded : WhisperState
    data class Downloading(val progress: Progress) : WhisperState
    data class ErrorDownloading(val throwable: Throwable) : WhisperState
    data object NotLoaded : WhisperState
    data class Loading(val thenStartListening: ((InputEvent) -> Unit)?) : WhisperState
    data class ErrorLoading(val throwable: Throwable) : WhisperState
    data class Loaded(internal val engine: WhisperEngine) : WhisperState
    data class Listening(
        internal val engine: WhisperEngine,
        internal val eventListener: (InputEvent) -> Unit,
    ) : WhisperState

    fun toUiState(): SttState = when (this) {
        NotInitialized -> SttState.NotInitialized
        NotDownloaded -> SttState.NotDownloaded
        is Downloading -> SttState.Downloading(progress)
        is ErrorDownloading -> SttState.ErrorDownloading(throwable)
        NotLoaded -> SttState.NotLoaded
        is Loading -> SttState.Loading(thenStartListening != null)
        is ErrorLoading -> SttState.ErrorLoading(throwable)
        is Loaded -> SttState.Loaded
        is Listening -> SttState.Listening
    }
}
