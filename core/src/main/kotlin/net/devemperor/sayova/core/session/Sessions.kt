package net.devemperor.sayova.core.session

import net.devemperor.sayova.core.provider.ProviderId
import net.devemperor.sayova.core.provider.gemini.GeminiLiveSession
import net.devemperor.sayova.core.provider.openai.OpenAiRealtimeSession
import okhttp3.OkHttpClient

object Sessions {
    fun create(provider: ProviderId, config: SessionConfig, client: OkHttpClient): VoiceSession = when (provider) {
        ProviderId.GEMINI -> GeminiLiveSession(config, client)
        ProviderId.OPENAI -> OpenAiRealtimeSession(config, client)
    }
}
