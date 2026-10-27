package net.devemperor.parley.core.session

import java.io.IOException

/** Maps what a provider says when it refuses or ends a session onto the few cases the UI explains. */
object SessionErrors {

    fun fromHttp(code: Int, body: String?): SessionError {
        val text = body.orEmpty()
        val kind = when {
            code == 401 || code == 403 -> ErrorKind.AUTH
            code == 402 -> ErrorKind.QUOTA
            code == 429 && looksLikeQuota(text) -> ErrorKind.QUOTA
            code == 429 -> ErrorKind.RATE_LIMIT
            code in 400..499 -> fromText(text)?.kind ?: ErrorKind.REJECTED
            else -> ErrorKind.SERVER
        }
        return SessionError(kind, text.take(300).ifBlank { "HTTP $code" })
    }

    fun fromThrowable(t: Throwable): SessionError =
        SessionError(if (t is IOException) ErrorKind.NETWORK else ErrorKind.SERVER, t.message ?: t.javaClass.simpleName)

    /** Classifies a close reason or error message; null when nothing in it is recognisable. */
    fun fromText(text: String): SessionError? {
        val lower = text.lowercase()
        val kind = when {
            "api key" in lower || "api_key" in lower || "unauthorized" in lower || "permission" in lower ||
                "authentication" in lower -> ErrorKind.AUTH
            looksLikeQuota(lower) -> ErrorKind.QUOTA
            "rate limit" in lower || "rate_limit" in lower || "too many requests" in lower -> ErrorKind.RATE_LIMIT
            "not found" in lower || "not supported" in lower || "invalid" in lower || "unsupported" in lower -> ErrorKind.REJECTED
            else -> return null
        }
        return SessionError(kind, text.take(300))
    }

    private fun looksLikeQuota(text: String): Boolean {
        val lower = text.lowercase()
        return "quota" in lower || "billing" in lower || "resource_exhausted" in lower || "insufficient" in lower ||
            "credit" in lower || "spend" in lower
    }
}
