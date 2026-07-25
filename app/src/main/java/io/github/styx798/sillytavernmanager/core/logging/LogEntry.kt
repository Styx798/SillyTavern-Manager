package io.github.styx798.sillytavernmanager.core.logging

enum class LogLevel {
    INFO,
    WARNING,
    ERROR,
}

enum class LogSource {
    APP,
    RUNTIME,
}

data class LogEntry(
    val sequence: Long,
    val timestampMillis: Long,
    val source: LogSource,
    val level: LogLevel,
    val message: String,
)
