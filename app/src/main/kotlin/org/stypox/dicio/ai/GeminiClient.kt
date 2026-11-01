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

    suspend fun generate(apiKey: String, systemPrompt: String, turns: List<Turn>): String =
        withContext(Dispatchers.IO) {
            try {
                request(apiKey, systemPrompt, turns, lowThinking = true)
            } catch (e: ApiException) {
                // older models don't know "thinkingLevel": retry without it
                if (e.code == 400 && e.message?.contains("thinking", ignoreCase = true) == true) {
                    request(apiKey, systemPrompt, turns, lowThinking = false)
                } else {
                    throw e
                }
            }
        }

    private fun request(
        apiKey: String,
        systemPrompt: String,
        turns: List<Turn>,
        lowThinking: Boolean,
    ): String {
        val contents = JSONArray()
        for (turn in turns) {
            contents.put(
                JSONObject()
                    .put("role", if (turn.fromUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
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
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
            .put("contents", contents)
            .put("generationConfig", generationConfig)

        val request = Request.Builder()
            .url("$BASE_URL/models/$MODEL:generateContent")
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
        // always points at Google's newest Flash model
        private const val MODEL = "gemini-flash-latest"
    }
}
