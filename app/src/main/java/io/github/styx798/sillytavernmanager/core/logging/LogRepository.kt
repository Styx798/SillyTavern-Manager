package io.github.styx798.sillytavernmanager.core.logging

import kotlinx.coroutines.flow.StateFlow

interface LogRepository {
    val entries: StateFlow<List<LogEntry>>

    fun append(source: LogSource, level: LogLevel, message: String)
}
