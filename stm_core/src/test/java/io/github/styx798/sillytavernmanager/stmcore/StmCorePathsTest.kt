package io.github.styx798.sillytavernmanager.stmcore

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

class StmCorePathsTest {
    @Test
    fun `instance data roots are isolated while null retains legacy data`() {
        val files = Files.createTempDirectory("stm-instance-data").toFile()
        val first = "08f9bb2b-60f5-4d45-916c-ece2dc1acd40"
        val second = "77eae5e7-ab20-4d76-8e7e-dfc99adc9086"

        assertEquals(
            files.resolve("stm_data").canonicalFile,
            StmCorePaths.prepareInstanceDataRootAt(files, null).canonicalFile,
        )
        assertEquals(
            files.resolve("stm_instances/$first/data").canonicalFile,
            StmCorePaths.prepareInstanceDataRootAt(files, first).canonicalFile,
        )
        assertEquals(
            files.resolve("stm_instances/$second/data").canonicalFile,
            StmCorePaths.prepareInstanceDataRootAt(files, second).canonicalFile,
        )
        assertFalse(
            StmCorePaths.prepareInstanceDataRootAt(files, first).canonicalFile ==
                StmCorePaths.prepareInstanceDataRootAt(files, second).canonicalFile,
        )
    }

    @Test
    fun `instance data root rejects traversal and symlinked instance directories`() {
        val root = Files.createTempDirectory("stm-instance-data-safety").toFile()
        assertThrows(IllegalArgumentException::class.java) {
            StmCorePaths.prepareInstanceDataRootAt(root, "../outside")
        }

        val instances = root.resolve("stm_instances").apply { mkdirs() }
        val outside = root.resolve("outside").apply { mkdirs() }
        val id = "08f9bb2b-60f5-4d45-916c-ece2dc1acd40"
        createSymlinkOrSkip(instances.resolve(id), outside)
        assertThrows(IllegalArgumentException::class.java) {
            StmCorePaths.prepareInstanceDataRootAt(root, id)
        }
    }

    @Test
    fun `reserved path matching does not capture sibling app data`() {
        val root = Files.createTempDirectory("stm-core-paths").toFile().canonicalFile
        val reserved = listOf(root.resolve("no_backup/stm_core").canonicalFile)

        assertTrue(
            StmCorePaths.isInsideAny(
                root.resolve("no_backup/stm_core/state/checkpoint").canonicalFile,
                reserved,
            ),
        )
        assertFalse(
            StmCorePaths.isInsideAny(
                root.resolve("no_backup/other-component").canonicalFile,
                reserved,
            ),
        )
    }

    @Test
    fun `all installer paths stay below the private Core root`() {
        val root = Files.createTempDirectory("stm-installer-paths").toFile().canonicalFile
        val core = root.resolve("no_backup/stm_core").canonicalFile
        val children = listOf(
            core.resolve("state/active-slot"),
            core.resolve("slots/slot-a"),
            core.resolve("staging/operation-a"),
            core.resolve("catalog/catalog-v1"),
            core.resolve("installer-cache/artifact.zip"),
            core.resolve("toolchains/npm-11.6.2-tree/npm/bin/npm-cli.js"),
            core.resolve("logs/installer.log"),
        )

        children.forEach { child ->
            assertTrue(StmCorePaths.isInsideAny(child.canonicalFile, listOf(core)))
        }
        assertFalse(StmCorePaths.isInsideAny(root.resolve("files/stm_data"), listOf(core)))
    }

    @Test
    fun `layout rejects a preexisting symlink core root`() {
        val root = Files.createTempDirectory("stm-core-root-link").toFile()
        val noBackup = root.resolve("no_backup").apply { mkdirs() }
        val outside = root.resolve("outside").apply { mkdirs() }
        createSymlinkOrSkip(noBackup.resolve("stm_core"), outside)

        assertThrows(IllegalArgumentException::class.java) {
            StmCorePaths.initializeCoreLayoutAt(noBackup)
        }
    }

    @Test
    fun `layout rejects a preexisting symlink state directory`() {
        val root = Files.createTempDirectory("stm-core-state-link").toFile()
        val noBackup = root.resolve("no_backup").apply { mkdirs() }
        val core = noBackup.resolve("stm_core").apply { mkdirs() }
        val outside = root.resolve("outside").apply { mkdirs() }
        createSymlinkOrSkip(core.resolve("state"), outside)

        assertThrows(IllegalArgumentException::class.java) {
            StmCorePaths.initializeCoreLayoutAt(noBackup)
        }
    }

    @Test
    fun `layout creates a dedicated Core private toolchain root`() {
        val root = Files.createTempDirectory("stm-core-toolchains").toFile()
        val noBackup = root.resolve("no_backup").apply { mkdirs() }

        StmCorePaths.initializeCoreLayoutAt(noBackup)

        val toolchains = noBackup.resolve("stm_core/toolchains")
        assertTrue(toolchains.isDirectory)
        assertFalse(Files.isSymbolicLink(toolchains.toPath()))
    }

    @Test
    fun `layout rejects a preexisting symlink toolchain root`() {
        val root = Files.createTempDirectory("stm-core-toolchains-link").toFile()
        val noBackup = root.resolve("no_backup").apply { mkdirs() }
        val core = noBackup.resolve("stm_core").apply { mkdirs() }
        val outside = root.resolve("outside").apply { mkdirs() }
        createSymlinkOrSkip(core.resolve("toolchains"), outside)

        assertThrows(IllegalArgumentException::class.java) {
            StmCorePaths.initializeCoreLayoutAt(noBackup)
        }
    }

    @Test
    fun `cache layout creates a dedicated no-follow session root`() {
        val root = Files.createTempDirectory("stm-core-cache-layout").toFile()
        val cache = root.resolve("cache").apply { mkdirs() }

        StmCorePaths.initializeCacheLayoutAt(cache)

        val sessions = cache.resolve("stm_core/sessions")
        assertTrue(sessions.isDirectory)
        assertFalse(Files.isSymbolicLink(sessions.toPath()))
    }

    @Test
    fun `cache layout rejects a preexisting symlink session root`() {
        val root = Files.createTempDirectory("stm-core-session-link").toFile()
        val cache = root.resolve("cache").apply { mkdirs() }
        val core = cache.resolve("stm_core").apply { mkdirs() }
        val outside = root.resolve("outside").apply { mkdirs() }
        createSymlinkOrSkip(core.resolve("sessions"), outside)

        assertThrows(IllegalArgumentException::class.java) {
            StmCorePaths.initializeCacheLayoutAt(cache)
        }
    }

    @Test
    fun `layout rejects a symlink checkpoint control file`() {
        val root = Files.createTempDirectory("stm-core-checkpoint-link").toFile()
        val noBackup = root.resolve("no_backup").apply { mkdirs() }
        StmCorePaths.initializeCoreLayoutAt(noBackup)
        val outside = root.resolve("outside-checkpoint").apply { writeText("outside") }
        createSymlinkOrSkip(
            noBackup.resolve("stm_core/state/core-snapshot"),
            outside,
        )

        assertThrows(IllegalArgumentException::class.java) {
            StmCorePaths.initializeCoreLayoutAt(noBackup)
        }
    }

    private fun createSymlinkOrSkip(link: java.io.File, target: java.io.File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (error: Exception) {
            assumeNoException("The test filesystem does not support symbolic links", error)
        }
    }
}
