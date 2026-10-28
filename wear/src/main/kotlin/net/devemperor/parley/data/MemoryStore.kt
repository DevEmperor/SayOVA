package net.devemperor.parley.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.devemperor.parley.core.memory.Fact
import net.devemperor.parley.core.memory.Memory
import java.io.File

/**
 * What Parley remembers about the user (see [Memory]), as one small JSON file in the app's private
 * storage. Like the history it never leaves the watch except inside a conversation's instructions.
 */
class MemoryStore(context: Context) {
    private val file = File(context.filesDir, "memory.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Fact.serializer())
    private val lock = Mutex()
    private val _facts = MutableStateFlow<List<Fact>>(emptyList())

    /** Oldest first. */
    val facts: StateFlow<List<Fact>> = _facts.asStateFlow()

    private var loaded = false

    suspend fun load() = lock.withLock { withContext(Dispatchers.IO) { read() } }

    /** The facts, read first if the app was started by a conversation before it loaded them. */
    suspend fun current(): List<Fact> = lock.withLock {
        withContext(Dispatchers.IO) { if (!loaded) read() }
        _facts.value
    }

    suspend fun remember(text: String, replaces: Int?): Memory.Change =
        change { facts -> Memory.remember(facts, text, replaces, System.currentTimeMillis()) }

    suspend fun forget(number: Int): Memory.Change = change { facts -> Memory.forget(facts, number) }

    suspend fun forgetAll() = lock.withLock {
        withContext(Dispatchers.IO) {
            file.delete()
            _facts.value = emptyList()
            loaded = true
        }
    }

    private suspend fun change(rule: (List<Fact>) -> Memory.Change): Memory.Change = lock.withLock {
        withContext(Dispatchers.IO) {
            // Never write before reading: that would replace the saved facts with only the new one.
            if (!loaded) read()
            val change = rule(_facts.value)
            if (change.facts != _facts.value) {
                write(change.facts)
                _facts.value = change.facts
            }
            change
        }
    }

    private fun read() {
        _facts.value = if (file.exists()) {
            runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        loaded = true
    }

    private fun write(facts: List<Fact>) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(serializer, facts))
        tmp.renameTo(file)
    }
}
