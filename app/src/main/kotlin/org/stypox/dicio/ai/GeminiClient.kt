package org.stypox.dicio.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal client for Google's Gemini API (free tier with an API key from aistudio.google.com).
 */
class GeminiClient(baseClient: OkHttpClient) {
    private val httpClient = baseClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    /** One message in the conversation: [fromUser] is false for the model's own replies. */
    data class Turn(val fromUser: Boolean, val text: String)

    /** The API answered with an error, e.g. a wrong key (400/403) or quota exceeded (429). */
    class ApiException(val code: Int, message: String) : IOException(message)

    // model -> time until which it is skipped because its free quota ran out
    private val exhaustedUntil = mutableMapOf<String, Long>()

    /**
     * Tries the free models in [MODELS] order; when one is out of free quota (HTTP 429) or not
     * available (404), moves on to the next one, and skips it for a while.
     */
    suspend fun generate(apiKey: String, systemPrompt: String, turns: List<Turn>): String =
        withContext(Dispatchers.IO) {
            var lastError: ApiException? = null
            for (model in MODELS) {
                if ((exhaustedUntil[model] ?: 0L) > System.currentTimeMillis()) continue
                try {
                    return@withContext requestWithRetry(model, apiKey, systemPrompt, turns)
                } catch (e: ApiException) {
                    if (e.code != 429 && e.code != 404) throw e
                    exhaustedUntil[model] = System.currentTimeMillis() +
                        if (e.code == 404) 24 * 60 * 60 * 1000L else EXHAUSTED_SKIP_MILLIS
                    lastError = e
                }
            }
            throw lastError ?: ApiException(429, "All free models are out of quota")
        }

    private fun requestWithRetry(
        model: String,
        apiKey: String,
        systemPrompt: String,
        turns: List<Turn>,
    ): String {
        val isGemma = model.startsWith("gemma")
        return try {
            request(model, apiKey, systemPrompt, turns, lowThinking = !isGemma, inlineSystem = isGemma)
        } catch (e: ApiException) {
            // some models don't know "thinkingLevel" or system instructions: retry without them
            if (e.code == 400) {
                request(model, apiKey, systemPrompt, turns, lowThinking = false, inlineSystem = true)
            } else {
                throw e
            }
        }
    }

    private fun request(
        model: String,
        apiKey: String,
        systemPrompt: String,
        turns: List<Turn>,
        lowThinking: Boolean,
        inlineSystem: Boolean,
    ): String {
        val contents = JSONArray()
        for ((i, turn) in turns.withIndex()) {
            // models without system instructions (Gemma) get them in the first message instead
            val text = if (inlineSystem && i == 0) "$systemPrompt\n\n${turn.text}" else turn.text
            contents.put(
                JSONObject()
                    .put("role", if (turn.fromUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
            )
        }
        val generationConfig = JSONObject()
            .put("temperature", 0.4)
            .put("maxOutputTokens", 600)
        if (lowThinking) {
            // answers should come fast, this is a voice assistant
            generationConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        }
        val body = JSONObject()
            .put("contents", contents)
            .put("generationConfig", generationConfig)
        if (!inlineSystem) {
            body.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
        }

        val request = Request.Builder()
            .url("$BASE_URL/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(text).getJSONObject("error").getString("message")
                }.getOrDefault(text.take(200))
                throw ApiException(response.code, message)
            }
            val parts = JSONObject(text)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val out = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (!part.optBoolean("thought", false)) out.append(part.optString("text"))
            }
            return out.toString()
        }
    }

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val EXHAUSTED_SKIP_MILLIS = 10 * 60 * 1000L

        /**
         * Free-tier models, best first. Flash Lite models are fast (good for voice) with 500 free
         * requests a day each; Gemma 4 is slower but allows 14,400 a day, so it catches the rest.
         */
        private val MODELS = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemma-4-31b-it",
        )
    }
}
