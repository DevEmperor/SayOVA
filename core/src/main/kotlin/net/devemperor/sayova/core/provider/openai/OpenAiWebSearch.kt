package net.devemperor.sayova.core.provider.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.devemperor.sayova.core.session.SessionErrors
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The Realtime API has no built-in web search, so the voice model calls `search_web` and this answers
 * it with one Responses API request that may use OpenAI's hosted web_search tool.
 */
class OpenAiWebSearch(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://api.openai.com/v1/",
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** @return a short spoken-style summary; throws [IOException] when the request fails. */
    fun search(apiKey: String, query: String): String {
        val body = buildJsonObject {
            put("model", MODEL)
            put(
                "instructions",
                "You research for a voice assistant on a smartwatch. Answer the query in two to four short " +
                    "sentences with the concrete facts, including dates where they matter. No URLs, no " +
                    "markdown, no lists.",
            )
            put("input", query)
            putJsonArray("tools") { addJsonObject { put("type", "web_search") } }
            putJsonObject("reasoning") { put("effort", "low") }
        }.toString()
        val request = Request.Builder()
            .url(baseUrl + "responses")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IOException(SessionErrors.fromHttp(response.code, text).message)
            return extractText(text) ?: throw IOException("The search returned no answer.")
        }
    }

    internal fun extractText(responseJson: String): String? {
        val root = runCatching { json.parseToJsonElement(responseJson).jsonObject }.getOrNull() ?: return null
        val parts = (root["output"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "message" }
            .flatMap { (it["content"] as? JsonArray).orEmpty() }
            .mapNotNull { it as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "output_text" }
            .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
        return parts.joinToString("\n").trim().ifEmpty { null }
    }

    private companion object {
        // OpenAI's current low-cost text model ($0.10 / $0.50 per 1M tokens), the one Dictate moved to
        // after the gpt-4o-mini deprecation (OpenAI deprecations page, read 2026-10-16).
        const val MODEL = "gpt-6-luna"
    }
}
