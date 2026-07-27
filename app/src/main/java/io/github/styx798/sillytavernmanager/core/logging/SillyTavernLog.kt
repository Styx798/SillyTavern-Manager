package io.github.styx798.sillytavernmanager.core.logging

data class SillyTavernLogSnapshot(
    val available: Boolean = false,
    val lines: List<String> = emptyList(),
    val truncated: Boolean = false,
    val readAtEpochMs: Long? = null,
)

fun interface SillyTavernLogReader {
    suspend fun readTail(): SillyTavernLogSnapshot
}
