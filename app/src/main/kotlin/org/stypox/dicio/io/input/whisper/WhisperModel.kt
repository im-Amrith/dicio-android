package org.stypox.dicio.io.input.whisper

/**
 * Whisper models converted for sherpa-onnx (int8 quantized). English-only (".en") models are more
 * accurate for English than the multilingual ones of the same size, so they are used for English.
 *
 * Rough numbers for a Snapdragon 8 Gen 2 (Galaxy S23) with 4 threads:
 * - TINY:  ~100MB download, very fast, noticeably less accurate
 * - BASE:  ~160MB download, ~0.3-0.6s per command, good accuracy (default)
 * - SMALL: ~360MB download, ~1-2s per command, best accuracy, especially with accents
 */
enum class WhisperModel(
    private val repoName: String,
    private val prefix: String,
    val multilingual: Boolean,
) {
    TINY_EN("sherpa-onnx-whisper-tiny.en", "tiny.en", false),
    BASE_EN("sherpa-onnx-whisper-base.en", "base.en", false),
    SMALL_EN("sherpa-onnx-whisper-small.en", "small.en", false),
    TINY("sherpa-onnx-whisper-tiny", "tiny", true),
    BASE("sherpa-onnx-whisper-base", "base", true),
    SMALL("sherpa-onnx-whisper-small", "small", true);

    val encoderFile get() = "$prefix-encoder.int8.onnx"
    val decoderFile get() = "$prefix-decoder.int8.onnx"
    val tokensFile get() = "$prefix-tokens.txt"

    /** (url, file name) pairs to download into the model directory */
    fun filesToDownload(): List<Pair<String, String>> =
        listOf(encoderFile, decoderFile, tokensFile).map {
            Pair("$HF_BASE/csukuangfj/$repoName/resolve/main/$it", it)
        } + Pair(VAD_URL, WhisperEngine.VAD_FILE)

    companion object {
        private const val HF_BASE = "https://huggingface.co"
        private const val VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

        /**
         * Change these two to switch model size. SMALL_EN is worth trying if BASE_EN still
         * mishears you; it is slower but much better with Indian-English accents.
         */
        val ENGLISH_MODEL = BASE_EN
        val MULTILINGUAL_MODEL = BASE

        fun forLanguage(language: String): WhisperModel =
            if (language == "en") ENGLISH_MODEL else MULTILINGUAL_MODEL
    }
}
