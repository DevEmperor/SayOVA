package net.devemperor.talova.core.provider

import net.devemperor.talova.core.session.ErrorKind
import net.devemperor.talova.core.session.SessionErrors
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Tries a key with the cheapest request each provider has (listing models), before it is saved. */
class KeyCheck(private val client: OkHttpClient) {

    sealed interface Result {
        data object Valid : Result
        data class Invalid(val reason: String) : Result
        /** No answer (offline, timeout); the key may well be fine. */
        data class Unreachable(val reason: String) : Result
    }

    fun check(provider: ProviderId, apiKey: String): Result {
        val request = when (provider) {
            ProviderId.GEMINI -> Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1")
                .header("x-goog-api-key", apiKey)
            ProviderId.OPENAI -> Request.Builder()
                .url("https://api.openai.com/v1/models")
                .header("Authorization", "Bearer $apiKey")
        }.get().build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Result.Valid
                    response.code >= 500 -> Result.Unreachable("The provider answered ${response.code}.")
                    else -> {
                        val error = SessionErrors.fromHttp(response.code, response.body.string())
                        if (error.kind == ErrorKind.RATE_LIMIT) Result.Valid else Result.Invalid(error.message)
                    }
                }
            }
        } catch (e: IOException) {
            Result.Unreachable(e.message ?: "No connection")
        }
    }
}
