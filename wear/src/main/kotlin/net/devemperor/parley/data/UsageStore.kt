package net.devemperor.parley.data

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.devemperor.parley.core.usage.Usage
import net.devemperor.parley.core.usage.UsageDay
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Talk time and cost per day (see [UsageDay]), as one small JSON file in the app's private storage:
 * a day is a few dozen bytes, so years fit easily and nothing is ever pruned.
 */
class UsageStore(context: Context) {
    private val file = File(context.filesDir, "usage.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(UsageDay.serializer())
    private val lock = Mutex()
    private val _days = MutableStateFlow<List<UsageDay>>(emptyList())
    private val loaded = CompletableDeferred<Unit>()

    /** Oldest first. */
    val days: StateFlow<List<UsageDay>> = _days.asStateFlow()

    /**
     * Reads the file. On a watch that has none yet, the statistics start from [seed] (the saved
     * conversations and the month's cost Parley counted before it kept statistics).
     */
    suspend fun load(seed: suspend () -> List<UsageDay>) = lock.withLock {
        try {
            withContext(Dispatchers.IO) {
                _days.value = if (file.exists()) {
                    runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())
                } else {
                    seed().also(::write)
                }
            }
        } finally {
            loaded.complete(Unit)
        }
    }

    /** Returns once [load] is done: a conversation started with the app is checked against the real month. */
    suspend fun awaitLoaded() = loaded.await()

    /** One conversation that just ended, counted on today's date; a voice sample costs without being one. */
    suspend fun record(costUsd: Double, talkMs: Long, conversations: Int = 1) = lock.withLock {
        withContext(Dispatchers.IO) {
            val updated = Usage.add(_days.value, LocalDate.now(ZoneId.systemDefault()), costUsd, talkMs, conversations)
            write(updated)
            _days.value = updated
        }
    }

    private fun write(days: List<UsageDay>) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(serializer, days))
        tmp.renameTo(file)
    }
}
