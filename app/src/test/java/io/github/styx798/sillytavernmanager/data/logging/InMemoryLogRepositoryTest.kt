package io.github.styx798.sillytavernmanager.data.logging

import io.github.styx798.sillytavernmanager.core.logging.LogLevel
import io.github.styx798.sillytavernmanager.core.logging.LogSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InMemoryLogRepositoryTest {
    @Test
    fun `append records timestamp level and message`() {
        val repository = InMemoryLogRepository(clock = { 1234L })

        repository.append(LogSource.APP, LogLevel.INFO, "started")

        val entry = repository.entries.value.single()
        assertEquals(1L, entry.sequence)
        assertEquals(1234L, entry.timestampMillis)
        assertEquals(LogSource.APP, entry.source)
        assertEquals(LogLevel.INFO, entry.level)
        assertEquals("started", entry.message)
    }

    @Test
    fun `repository keeps only newest entries within capacity`() {
        var timestamp = 0L
        val repository = InMemoryLogRepository(
            clock = { ++timestamp },
            capacity = 2,
        )

        repository.append(LogSource.APP, LogLevel.INFO, "one")
        repository.append(LogSource.RUNTIME, LogLevel.WARNING, "two")
        repository.append(LogSource.RUNTIME, LogLevel.ERROR, "three")

        assertEquals(listOf("two", "three"), repository.entries.value.map { it.message })
    }

    @Test
    fun `blank messages are rejected`() {
        val repository = InMemoryLogRepository()

        assertThrows(IllegalArgumentException::class.java) {
            repository.append(LogSource.APP, LogLevel.INFO, "   ")
        }
    }
}
