package io.github.rmdodhia.gesturelauncher.data

import io.github.rmdodhia.gesturelauncher.core.AppData
import io.github.rmdodhia.gesturelauncher.core.Gesture
import io.github.rmdodhia.gesturelauncher.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

/** Persists [AppData] as a single JSON file. All writes are atomic (temp file + rename). */
class Store(private val file: File) {
    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data.asStateFlow()
    private val mutex = Mutex()

    /**
     * Loads from disk. If the file is unreadable it is moved aside (never deleted) and an explanatory
     * message is returned so the UI can tell the user.
     */
    fun load(): String? {
        if (!file.exists()) return null
        return try {
            _data.value = decode(file.readText())
            null
        } catch (e: Exception) {
            val backup = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
            file.renameTo(backup)
            "Saved gestures could not be read and were moved to ${backup.name}. (${e.message})"
        }
    }

    suspend fun update(transform: (AppData) -> AppData) {
        mutex.withLock {
            val next = transform(_data.value)
            // Non-cancellable so the file on disk and the in-memory state can never diverge.
            withContext(NonCancellable + Dispatchers.IO) {
                write(next)
                _data.value = next
            }
        }
    }

    suspend fun upsertGesture(g: Gesture) = update { d ->
        val exists = d.gestures.any { it.id == g.id }
        d.copy(gestures = if (exists) d.gestures.map { if (it.id == g.id) g else it } else d.gestures + g)
    }

    suspend fun deleteGesture(id: String) = update { d -> d.copy(gestures = d.gestures.filterNot { it.id == id }) }

    suspend fun updateSettings(s: Settings) = update { it.copy(settings = s) }

    suspend fun replaceAll(d: AppData) = update { d }

    fun export(): String = encode(_data.value)

    private fun write(d: AppData) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(encode(d).toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Could not save gestures to ${file.absolutePath}")
        }
    }

    companion object {
        fun encode(d: AppData): String = AppJson.encodeToString(AppData.serializer(), d)

        fun decode(s: String): AppData {
            val d = AppJson.decodeFromString(AppData.serializer(), s)
            val ids = d.gestures.map { it.id }
            require(ids.size == ids.toSet().size) { "Duplicate gesture ids" }
            return d
        }
    }
}
