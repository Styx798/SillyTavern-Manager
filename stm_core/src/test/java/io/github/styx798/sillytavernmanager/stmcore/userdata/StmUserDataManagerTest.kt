package io.github.styx798.sillytavernmanager.stmcore.userdata

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class StmUserDataManagerTest {
    private lateinit var root: File
    private lateinit var legacy: File
    private lateinit var instances: File
    private lateinit var backups: File
    private lateinit var cache: File
    private lateinit var manager: StmUserDataManager
    private lateinit var instanceId: String
    private lateinit var dataRoot: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("stm-user-data-test").toFile()
        legacy = root.resolve("stm_data").apply { mkdir() }
        instances = root.resolve("stm_instances").apply { mkdir() }
        backups = root.resolve("stm_backups")
        cache = root.resolve("cache").apply { mkdir() }
        instanceId = UUID.randomUUID().toString()
        dataRoot = instances.resolve(instanceId).resolve("data").apply { mkdirs() }
        manager = StmUserDataManager(legacy, instances, backups, cache)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun backupMatchesOfficialUserRootAndExcludesSecrets() {
        val user = dataRoot.resolve("default-user").apply { mkdir() }
        user.resolve("characters/hero.png").writeText("card")
        user.resolve("chats/hero/chat.jsonl").writeText("chat")
        user.resolve("extensions/local-addon/index.js").writeText("extension")
        user.resolve("secrets.json").writeText("secret")
        user.resolve("backups/secrets_migration_20260727.json").writeText("old secret")
        dataRoot.resolve("config.yaml").writeText("port: 8080")

        val result = manager.createBackup(instanceId, "My Tavern", UUID.randomUUID().toString())

        val archive = backups.resolve(instanceId).resolve(result.fileName)
        assertTrue(archive.isFile)
        ZipFile(archive).use { zip ->
            val names = zip.entries().asSequence().map(ZipEntry::getName).toSet()
            assertTrue("characters/hero.png" in names)
            assertTrue("chats/hero/chat.jsonl" in names)
            assertTrue("extensions/local-addon/index.js" in names)
            assertFalse("secrets.json" in names)
            assertFalse("backups/secrets_migration_20260727.json" in names)
            assertFalse("config.yaml" in names)
            assertFalse(names.any { it.startsWith("default-user/") })
        }
    }

    @Test
    fun backupCanBeRestoredByTheStrictUserDataExtractor() {
        dataRoot.resolve("default-user/characters/hero.png").writeText("original")
        dataRoot.resolve("default-user/empty-directory").mkdirs()
        val result = manager.createBackup(
            instanceId,
            "My Tavern",
            UUID.randomUUID().toString(),
        )

        dataRoot.resolve("default-user/characters/hero.png").writeText("modified")
        dataRoot.resolve("default-user/unbacked.txt").writeText("remove me")

        manager.restoreBackup(instanceId, UUID.randomUUID().toString(), result.fileName)

        assertEquals(
            "original",
            dataRoot.resolve("default-user/characters/hero.png").readText(),
        )
        assertFalse(dataRoot.resolve("default-user/unbacked.txt").exists())
        assertTrue(dataRoot.resolve("default-user/empty-directory").isDirectory)
    }

    @Test
    fun importCompletelyReplacesDefaultUserAndLeavesDataRootFiles() {
        dataRoot.resolve("default-user/characters/old.png").writeText("old")
        dataRoot.resolve("config.yaml").writeText("unchanged")
        val archive = zipOf(
            "characters/new.png" to "new",
            "extensions/new-addon/index.js" to "addon",
        )

        manager.replaceFromArchive(
            instanceId,
            "My Tavern",
            UUID.randomUUID().toString(),
            ByteArrayInputStream(archive),
            backupFirst = false,
        )

        assertFalse(dataRoot.resolve("default-user/characters/old.png").exists())
        assertEquals("new", dataRoot.resolve("default-user/characters/new.png").readText())
        assertEquals(
            "addon",
            dataRoot.resolve("default-user/extensions/new-addon/index.js").readText(),
        )
        assertEquals("unchanged", dataRoot.resolve("config.yaml").readText())
    }

    @Test
    fun backupFailurePreventsReplacement() {
        dataRoot.resolve("default-user/characters/old.png").writeText("old")
        val backupRoot = backups.resolve(instanceId).apply { mkdirs() }
        Files.createSymbolicLink(
            backupRoot.resolve("blocked").toPath(),
            dataRoot.resolve("default-user").toPath(),
        )
        // A symbolic link in the source is rejected before import replacement starts.
        Files.createSymbolicLink(
            dataRoot.resolve("default-user/blocked").toPath(),
            dataRoot.resolve("default-user/characters").toPath(),
        )

        try {
            manager.replaceFromArchive(
                instanceId,
                "My Tavern",
                UUID.randomUUID().toString(),
                ByteArrayInputStream(zipOf("characters/new.png" to "new")),
                backupFirst = true,
            )
            fail("Expected backup failure")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        assertEquals("old", dataRoot.resolve("default-user/characters/old.png").readText())
        assertFalse(dataRoot.resolve("default-user/characters/new.png").exists())
    }

    @Test
    fun interruptedSwapRestoresPreviousUserDirectory() {
        dataRoot.resolve("default-user/characters/old.png").writeText("old")
        val operationId = UUID.randomUUID().toString()
        val previous = dataRoot.resolve(".stm-user-data-previous-$operationId")
        Files.move(
            dataRoot.resolve("default-user").toPath(),
            previous.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )
        dataRoot.resolve(".stm-user-data-import-$operationId/payload/characters")
            .mkdirs()
        dataRoot.resolve(".stm-user-data-import-$operationId/payload/characters/new.png")
            .writeText("new")

        manager.recoverInterruptedOperations()

        assertEquals("old", dataRoot.resolve("default-user/characters/old.png").readText())
        assertFalse(previous.exists())
        assertFalse(dataRoot.resolve(".stm-user-data-import-$operationId").exists())
    }

    @Test
    fun committedReplacementWinsWhenCrashHappensBeforePreviousCleanup() {
        dataRoot.resolve("default-user/characters/new.png").writeText("new")
        val operationId = UUID.randomUUID().toString()
        dataRoot.resolve(".stm-user-data-previous-$operationId/characters/old.png")
            .writeText("old")
        dataRoot.resolve(".stm-user-data-import-$operationId").mkdirs()

        manager.recoverInterruptedOperations()

        assertEquals("new", dataRoot.resolve("default-user/characters/new.png").readText())
        assertFalse(dataRoot.resolve(".stm-user-data-previous-$operationId").exists())
        assertFalse(dataRoot.resolve(".stm-user-data-import-$operationId").exists())
    }

    @Test
    fun recoveryRemovesInterruptedPartialBackup() {
        val partial = backups.resolve(instanceId).resolve(".tavern.zip.partial")
        requireNotNull(partial.parentFile).mkdirs()
        partial.writeText("partial")

        manager.recoverInterruptedOperations()

        assertFalse(partial.exists())
    }

    @Test
    fun legacyMigrationCopiesThenFinalizationRemovesLegacyRoot() {
        legacy.resolve("default-user/chats/chat.jsonl").writeText("legacy")
        dataRoot.deleteRecursively()

        manager.migrateLegacyData(instanceId, UUID.randomUUID().toString())

        assertEquals(
            "legacy",
            dataRoot.resolve("default-user/chats/chat.jsonl").readText(),
        )
        assertTrue(legacy.exists())

        manager.migrateLegacyData(instanceId, UUID.randomUUID().toString())

        manager.finalizeLegacyMigration(instanceId)

        assertFalse(legacy.exists())
        assertEquals(
            "legacy",
            dataRoot.resolve("default-user/chats/chat.jsonl").readText(),
        )
        assertFalse(dataRoot.resolve(".stm-legacy-migration-complete").exists())
    }

    @Test
    fun unsafeArchiveCannotEscapeReplacementStaging() {
        dataRoot.resolve("default-user/settings.json").writeText("old")
        val archive = zipOf("../escaped.txt" to "bad")

        try {
            manager.replaceFromArchive(
                instanceId,
                "My Tavern",
                UUID.randomUUID().toString(),
                ByteArrayInputStream(archive),
                backupFirst = false,
            )
            fail("Expected unsafe ZIP rejection")
        } catch (_: Exception) {
            // Expected.
        }

        assertEquals("old", dataRoot.resolve("default-user/settings.json").readText())
        assertFalse(root.resolve("escaped.txt").exists())
    }

    private fun zipOf(vararg files: Pair<String, String>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun File.writeText(content: String) {
        parentFile?.mkdirs()
        Files.write(toPath(), content.toByteArray())
    }
}
