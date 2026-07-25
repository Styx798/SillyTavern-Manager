package io.github.styx798.sillytavernmanager.data.logging

import io.github.styx798.sillytavernmanager.core.logging.LogEntry
import io.github.styx798.sillytavernmanager.core.logging.LogLevel
import io.github.styx798.sillytavernmanager.core.logging.LogRepository
import io.github.styx798.sillytavernmanager.core.logging.LogSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class InMemoryLogRepository(
    private val clock: () -> Long = System::currentTimeMillis,
    private val capacity: Int = DEFAULT_CAPACITY,
) : LogRepository {
    init {
        require(capacity > 0) { "Log capacity must be greater than zero" }
    }

    private val mutableEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    private var nextSequence = 0L

    override val entries: StateFlow<List<LogEntry>> = mutableEntries.asStateFlow()

    @Synchronized
    override fun append(source: LogSource, level: LogLevel, message: String) {
        require(message.isNotBlank()) { "Log message must not be blank" }
        val entry = LogEntry(
            sequence = ++nextSequence,
            timestampMillis = clock(),
            source = source,
            level = level,
            message = message,
        )
        mutableEntries.update { current -> (current + entry).takeLast(capacity) }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 500
    }
}
