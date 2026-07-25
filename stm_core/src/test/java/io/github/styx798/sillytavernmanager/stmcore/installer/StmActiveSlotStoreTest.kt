package io.github.styx798.sillytavernmanager.stmcore.installer

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StmActiveSlotStoreTest {
    @Test
    fun `round trip returns verified checksum and current source`() {
        val activeFile = newActiveFile()
        val store = StmActiveSlotStore(activeFile)
        val pointer = firstPointer()

        val written = store.write(pointer)
        val loaded = store.read() as StmActiveSlotReadResult.Loaded

        assertEquals(pointer, written.pointer)
        assertTrue(written.checksumSha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals(StmActiveSlotRecordSource.CURRENT, loaded.source)
        assertEquals(written, loaded.stored)
    }

    @Test
    fun `a new activation durably preserves the old complete record`() {
        val activeFile = newActiveFile()
        val store = StmActiveSlotStore(activeFile)
        val first = firstPointer()
        val second = secondPointer()
        store.write(first)

        store.write(second)
        Files.write(
            activeFile.toPath(),
            byteArrayOf(0x01, 0x02, 0x03),
            StandardOpenOption.TRUNCATE_EXISTING,
        )

        val recovered = store.read() as StmActiveSlotReadResult.Loaded
        assertEquals(StmActiveSlotRecordSource.PREVIOUS, recovered.source)
        assertEquals(first, recovered.stored.pointer)
    }

    @Test
    fun `a missing current record can recover from the previous complete record`() {
        val activeFile = newActiveFile()
        val store = StmActiveSlotStore(activeFile)
        val first = firstPointer()
        store.write(first)
        store.write(secondPointer())

        Files.delete(activeFile.toPath())

        val recovered = store.read() as StmActiveSlotReadResult.Loaded
        assertEquals(StmActiveSlotRecordSource.PREVIOUS, recovered.source)
        assertEquals(first, recovered.stored.pointer)
    }

    @Test
    fun `rollback is another monotonic activation and preserves its former current`() {
        val activeFile = newActiveFile()
        val store = StmActiveSlotStore(activeFile)
        store.write(firstPointer())
        store.write(secondPointer())
        val rollback = StmActiveSlotPointer(
            current = SLOT_A,
            previous = SLOT_B,
            activeRevision = 3,
            operationId = "rollback-3",
        )

        store.write(rollback)

        val loaded = store.read() as StmActiveSlotReadResult.Loaded
        assertEquals(rollback, loaded.stored.pointer)
        assertEquals(StmActiveSlotRecordSource.CURRENT, loaded.source)
    }

    @Test
    fun `transition validation rejects gaps and loss of the old current slot`() {
        val activeFile = newActiveFile()
        val store = StmActiveSlotStore(activeFile)

        assertThrows(IllegalArgumentException::class.java) {
            store.write(firstPointer().copy(activeRevision = 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write(firstPointer().copy(previous = SLOT_B))
        }

        store.write(firstPointer())
        assertThrows(IllegalArgumentException::class.java) {
            store.write(secondPointer().copy(activeRevision = 3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write(secondPointer().copy(previous = StmActiveSlotRef("slot-c", 4)))
        }
    }

    @Test
    fun `an idempotent retry leaves the current record unchanged`() {
        val activeFile = newActiveFile()
        val store = StmActiveSlotStore(activeFile)
        val pointer = firstPointer()
        val firstWrite = store.write(pointer)

        val retry = store.write(pointer)

        assertEquals(firstWrite, retry)
        assertEquals(pointer, (store.read() as StmActiveSlotReadResult.Loaded).stored.pointer)
    }

    @Test
    fun `interruption before write leaves no active record`() {
        val activeFile = newActiveFile()
        val store = faultingStore(activeFile, StmActiveSlotFailpoint.BEFORE_WRITE)

        assertInterrupted { store.write(firstPointer()) }

        assertEquals(StmActiveSlotReadResult.Missing, store.read())
    }

    @Test
    fun `first activation killed after temp fsync and before atomic replace is still missing`() {
        val activeFile = newActiveFile()
        val store = faultingStore(
            activeFile,
            StmActiveSlotFailpoint.ACTIVE_AFTER_TEMP_SYNC_BEFORE_ATOMIC_REPLACE,
        )

        assertInterrupted { store.write(firstPointer()) }

        assertEquals(StmActiveSlotReadResult.Missing, store.read())
        assertTrue(
            requireNotNull(activeFile.parentFile).listFiles().orEmpty().any { file ->
                file.name.startsWith(".${activeFile.name}.tmp-")
            },
        )
    }

    @Test
    fun `restart after temp fsync and before atomic replace recovers the old pointer`() {
        val activeFile = newActiveFile()
        StmActiveSlotStore(activeFile).write(firstPointer())
        val store = faultingStore(
            activeFile,
            StmActiveSlotFailpoint.ACTIVE_AFTER_TEMP_SYNC_BEFORE_ATOMIC_REPLACE,
        )

        assertInterrupted { store.write(secondPointer()) }

        val loaded = StmActiveSlotStore(activeFile).read() as StmActiveSlotReadResult.Loaded
        assertEquals(StmActiveSlotRecordSource.CURRENT, loaded.source)
        assertEquals(firstPointer(), loaded.stored.pointer)
        assertTrue(
            requireNotNull(activeFile.parentFile).listFiles().orEmpty().any { file ->
                file.name.startsWith(".${activeFile.name}.tmp-")
            },
        )
    }

    @Test
    fun `recovery cleanup removes interrupted active temp and preserves unrelated files`() {
        val activeFile = newActiveFile()
        StmActiveSlotStore(activeFile).write(firstPointer())
        val store = faultingStore(
            activeFile,
            StmActiveSlotFailpoint.ACTIVE_AFTER_TEMP_SYNC_BEFORE_ATOMIC_REPLACE,
        )
        assertInterrupted { store.write(secondPointer()) }
        val stateDirectory = requireNotNull(activeFile.parentFile)
        val unrelated = stateDirectory.resolve("recovery-note")
        val invalidLookalike = stateDirectory.resolve(".${activeFile.name}.tmp-not-a-uuid")
        unrelated.writeText("keep")
        invalidLookalike.writeText("keep")

        val removed = StmActiveSlotStore(activeFile).cleanupTemporaryFilesForRecovery()

        assertEquals(1, removed)
        assertTrue(
            stateDirectory.listFiles().orEmpty().none { file ->
                file.name.startsWith(".${activeFile.name}.tmp-") && file != invalidLookalike
            },
        )
        assertEquals("keep", unrelated.readText())
        assertEquals("keep", invalidLookalike.readText())
        val loaded = StmActiveSlotStore(activeFile).read() as StmActiveSlotReadResult.Loaded
        assertEquals(firstPointer(), loaded.stored.pointer)
    }

    @Test
    fun `restart after atomic replace and before directory sync recovers the new pointer`() {
        val activeFile = newActiveFile()
        StmActiveSlotStore(activeFile).write(firstPointer())
        val store = faultingStore(
            activeFile,
            StmActiveSlotFailpoint.ACTIVE_AFTER_ATOMIC_REPLACE_BEFORE_DIRECTORY_SYNC,
        )

        assertInterrupted { store.write(secondPointer()) }

        val loaded = StmActiveSlotStore(activeFile).read() as StmActiveSlotReadResult.Loaded
        assertEquals(StmActiveSlotRecordSource.CURRENT, loaded.source)
        assertEquals(secondPointer(), loaded.stored.pointer)
        Files.write(
            activeFile.toPath(),
            byteArrayOf(0x01, 0x02, 0x03),
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        val fallback = StmActiveSlotStore(activeFile).read() as StmActiveSlotReadResult.Loaded
        assertEquals(StmActiveSlotRecordSource.PREVIOUS, fallback.source)
        assertEquals(firstPointer(), fallback.stored.pointer)
    }

    @Test
    fun `checksum corruption and oversized records are rejected with bounded errors`() {
        val corruptFile = newActiveFile()
        StmActiveSlotStore(corruptFile).write(firstPointer())
        val bytes = Files.readAllBytes(corruptFile.toPath())
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        Files.write(corruptFile.toPath(), bytes, StandardOpenOption.TRUNCATE_EXISTING)

        val corrupt = StmActiveSlotStore(corruptFile).read() as StmActiveSlotReadResult.Corrupt
        assertTrue(corrupt.detail.length <= 500)
        assertTrue(corrupt.detail.contains("checksum"))

        val oversizedFile = newActiveFile()
        Files.createDirectories(requireNotNull(oversizedFile.parentFile).toPath())
        Files.write(oversizedFile.toPath(), ByteArray(64 * 1024))
        val oversized = StmActiveSlotStore(oversizedFile).read()
        assertTrue(oversized is StmActiveSlotReadResult.Corrupt)

        val unboundedLengthFile = newActiveFile()
        StmActiveSlotStore(unboundedLengthFile).write(firstPointer())
        val unboundedLengthBytes = Files.readAllBytes(unboundedLengthFile.toPath())
        ByteBuffer.wrap(unboundedLengthBytes).putInt(8, Int.MAX_VALUE)
        Files.write(
            unboundedLengthFile.toPath(),
            unboundedLengthBytes,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        val unboundedLength = StmActiveSlotStore(unboundedLengthFile).read()
        assertTrue(unboundedLength is StmActiveSlotReadResult.Corrupt)
    }

    @Test
    fun `invalid slot and operation identifiers never reach disk`() {
        val activeFile = newActiveFile()
        val store = StmActiveSlotStore(activeFile)

        assertThrows(IllegalArgumentException::class.java) {
            store.write(firstPointer().copy(current = StmActiveSlotRef("../slot-a", 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.write(firstPointer().copy(operationId = "operation/one"))
        }
        assertEquals(StmActiveSlotReadResult.Missing, store.read())
    }

    @Test
    fun `active pointer writes never alter slot or data siblings`() {
        val root = Files.createTempDirectory("stm-active-isolation").toFile()
        val activeFile = root.resolve("core/state/active-slot")
        val slotSentinel = root.resolve("core/slots/slot-a/content.txt")
        val dataSentinel = root.resolve("data/user-data.txt")
        requireNotNull(slotSentinel.parentFile).mkdirs()
        requireNotNull(dataSentinel.parentFile).mkdirs()
        slotSentinel.writeText("immutable-slot")
        dataSentinel.writeText("user-data")

        val store = StmActiveSlotStore(activeFile)
        store.write(firstPointer())
        store.write(secondPointer())

        assertEquals("immutable-slot", slotSentinel.readText())
        assertEquals("user-data", dataSentinel.readText())
    }

    private fun newActiveFile(): File =
        Files.createTempDirectory("stm-active-store").resolve("state/active-slot").toFile()

    private fun faultingStore(
        activeFile: File,
        target: StmActiveSlotFailpoint,
    ): StmActiveSlotStore = StmActiveSlotStore(
        activeFile = activeFile,
        faultInjector = StmActiveSlotFaultInjector { actual ->
            if (actual == target) throw SimulatedInterruption(target)
        },
    )

    private fun assertInterrupted(block: () -> Unit) {
        assertThrows(SimulatedInterruption::class.java) { block() }
    }

    private class SimulatedInterruption(failpoint: StmActiveSlotFailpoint) :
        RuntimeException("Interrupted at $failpoint")

    private companion object {
        val SLOT_A = StmActiveSlotRef("slot-a", 11)
        val SLOT_B = StmActiveSlotRef("slot-b", 22)

        fun firstPointer() = StmActiveSlotPointer(
            current = SLOT_A,
            previous = null,
            activeRevision = 1,
            operationId = "activate-1",
        )

        fun secondPointer() = StmActiveSlotPointer(
            current = SLOT_B,
            previous = SLOT_A,
            activeRevision = 2,
            operationId = "activate-2",
        )
    }
}
