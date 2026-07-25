package io.github.styx798.sillytavernmanager.data.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

class AndroidAppFilesDeletionTest {
    @Test
    fun `nested symlinks are unlinked without traversing Core slots or user data`() {
        val root = Files.createTempDirectory("stm-app-files-delete").toFile()
        val safe = root.resolve("safe-parent").apply { mkdirs() }
        val slots = root.resolve("no_backup/stm_core/slots").apply { mkdirs() }
        val data = root.resolve("files/stm_data").apply { mkdirs() }
        val slotSentinel = slots.resolve("slot.txt").apply { writeText("slot") }
        val dataSentinel = data.resolve("user.txt").apply { writeText("user") }
        safe.resolve("nested").mkdirs()
        createSymlinkOrSkip(safe.resolve("slot-link"), slots)
        createSymlinkOrSkip(safe.resolve("nested/data-link"), data)

        val deleted = deleteTreeNoFollow(safe) { candidate ->
            candidate.canonicalFile.isInside(slots.canonicalFile) ||
                candidate.canonicalFile.isInside(data.canonicalFile)
        }

        assertTrue(deleted)
        assertFalse(safe.exists())
        assertTrue(slotSentinel.isFile)
        assertTrue(dataSentinel.isFile)
        assertTrue(slotSentinel.readText() == "slot")
        assertTrue(dataSentinel.readText() == "user")
    }

    @Test
    fun `a nested symlink cycle is treated as a leaf`() {
        val root = Files.createTempDirectory("stm-app-files-cycle").toFile()
        val safe = root.resolve("safe-parent").apply { mkdirs() }
        val nested = safe.resolve("nested").apply { mkdirs() }
        nested.resolve("file.txt").writeText("safe")
        createSymlinkOrSkip(nested.resolve("cycle"), safe)

        assertTrue(deleteTreeNoFollow(safe) { false })
        assertFalse(safe.exists())
    }

    private fun createSymlinkOrSkip(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (error: Exception) {
            assumeNoException("The test filesystem does not support symbolic links", error)
        }
    }

    private fun File.isInside(root: File): Boolean =
        this == root || path.startsWith(root.path + File.separator)
}
