package net.devemperor.parley.core.session

import net.devemperor.parley.core.provider.ProviderId
import net.devemperor.parley.core.provider.gemini.GeminiLiveSession
import net.devemperor.parley.core.provider.openai.OpenAiRealtimeSession
import okhttp3.OkHttpClient

object Sessions {
    fun create(provider: ProviderId, config: SessionConfig, client: OkHttpClient): VoiceSession = when (provider) {
        ProviderId.GEMINI -> GeminiLiveSession(config, client)
        ProviderId.OPENAI -> OpenAiRealtimeSession(config, client)
    }
}
