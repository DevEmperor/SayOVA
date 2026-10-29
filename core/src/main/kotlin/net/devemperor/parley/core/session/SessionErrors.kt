package net.devemperor.parley.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/** Maps what a provider says when it refuses or ends a session onto the few cases the UI explains. */
object SessionErrors {

    fun fromHttp(code: Int, body: String?): SessionError {
        val text = readableMessage(body.orEmpty())
        val kind = when {
            code == 401 || code == 403 -> ErrorKind.AUTH
            code == 402 -> ErrorKind.QUOTA
            code == 429 && looksLikeQuota(text) && !looksLikeRateLimit(text) -> ErrorKind.QUOTA
            code == 429 -> ErrorKind.RATE_LIMIT
            code in 400..499 -> fromText(text)?.kind ?: ErrorKind.REJECTED
            else -> ErrorKind.SERVER
        }
        return SessionError(kind, text.take(300).ifBlank { "HTTP $code" })
    }

    /**
     * The message inside a JSON error body (Google and OpenAI both answer {"error": {"message": …}}),
     * or the body as it came: shown on the watch, raw JSON is unreadable.
     */
    fun readableMessage(body: String): String = runCatching {
        Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: body

    fun fromThrowable(t: Throwable): SessionError =
        SessionError(if (t is IOException) ErrorKind.NETWORK else ErrorKind.SERVER, t.message ?: t.javaClass.simpleName)

    /** Classifies a close reason or error message; null when nothing in it is recognisable. */
    fun fromText(text: String): SessionError? {
        val lower = text.lowercase()
        val kind = when {
            "api key" in lower || "api_key" in lower || "unauthorized" in lower || "permission" in lower ||
                "authentication" in lower -> ErrorKind.AUTH
            // Google also says "quota" for a limit per minute, which passes by itself: not a lack of credit.
            looksLikeRateLimit(lower) -> ErrorKind.RATE_LIMIT
            looksLikeQuota(lower) -> ErrorKind.QUOTA
            "not found" in lower || "not supported" in lower || "invalid" in lower || "unsupported" in lower -> ErrorKind.REJECTED
            else -> return null
        }
        return SessionError(kind, text.take(300))
    }

    private fun looksLikeRateLimit(text: String): Boolean {
        val lower = text.lowercase()
        return "rate limit" in lower || "rate_limit" in lower || "too many requests" in lower || "per minute" in lower ||
            "per_minute" in lower || "perminute" in lower || "try again in" in lower || "retry in" in lower
    }

    private fun looksLikeQuota(text: String): Boolean {
        val lower = text.lowercase()
        return "quota" in lower || "billing" in lower || "resource_exhausted" in lower || "insufficient" in lower ||
            "credit" in lower || "spend" in lower
    }
}
