package net.devemperor.talova.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.devemperor.talova.core.conversation.Conversation
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Conversation history as one JSON file per conversation in the app's private storage. A watch keeps
 * a few hundred at most, so a database would only add weight.
 */
class HistoryStore(context: Context) {
    private val dir = File(context.filesDir, "conversations").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())

    /** Newest first. */
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _conversations.value = dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { file -> runCatching { json.decodeFromString(Conversation.serializer(), file.readText()) }.getOrNull() }
            .sortedByDescending { it.startedAt }
    }

    suspend fun save(conversation: Conversation) = withContext(Dispatchers.IO) {
        File(dir, "${conversation.id}.json").writeText(json.encodeToString(Conversation.serializer(), conversation))
        _conversations.value = (listOf(conversation) + _conversations.value.filter { it.id != conversation.id })
            .sortedByDescending { it.startedAt }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        _conversations.value = _conversations.value.filter { it.id != id }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dir.listFiles().orEmpty().forEach(File::delete)
        _conversations.value = emptyList()
    }

    /** Applies the retention setting: drops conversations older than [days] (0 = all, -1 = none). */
    suspend fun prune(days: Int) {
        when {
            days < 0 -> return
            days == 0 -> deleteAll()
            else -> {
                val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
                _conversations.value.filter { it.startedAt < cutoff }.forEach { delete(it.id) }
            }
        }
    }

    fun find(id: String): Conversation? = _conversations.value.firstOrNull { it.id == id }
}
