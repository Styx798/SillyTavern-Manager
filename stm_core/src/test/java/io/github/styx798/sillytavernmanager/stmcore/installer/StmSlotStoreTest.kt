package io.github.styx798.sillytavernmanager.stmcore.installer

import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifact
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactIntegrity
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactKind
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactTrust
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

class StmSlotStoreTest {
    @Test
    fun `synthetic A and B commit atomically with stable sorted manifests`() {
        val fixture = newFixture()
        writeSyntheticPayload(
            fixture.store,
            "operation-a",
            linkedMapOf("z-last.txt" to "z", "nested/a-first.txt" to "a"),
        )
        writeSyntheticPayload(
            fixture.store,
            "operation-b",
            linkedMapOf("nested/a-first.txt" to "a", "z-last.txt" to "z"),
        )

        val first = fixture.store.prepareAndCommit(
            syntheticRequest("operation-a", "slot-a", 11),
        ) as StmSlotCommitOutcome.Ready
        val second = fixture.store.prepareAndCommit(
            syntheticRequest("operation-b", "slot-b", 22),
        ) as StmSlotCommitOutcome.Ready

        assertEquals(first.slot.manifest, second.slot.manifest)
        assertEquals(
            first.slot.manifest.entries.sortedBy(StmSlotManifestEntry::relativePath),
            first.slot.manifest.entries,
        )
        assertTrue(first.slot.manifest.entries.all { entry ->
            entry.relativePath != ".stm-slot" && !entry.relativePath.startsWith(".stm-slot/")
        })
        assertTrue(first.slot.directory.resolve(".stm-slot/content-manifest.bin").isFile)
        val metadataFile = first.slot.directory.resolve(".stm-slot/slot-metadata.bin")
        assertTrue(metadataFile.isFile)
        assertEquals(3, ByteBuffer.wrap(metadataFile.readBytes()).getInt(Int.SIZE_BYTES))
        assertEquals(SYNTHETIC_REPOSITORY, first.slot.metadata.repository)
        assertEquals(SYNTHETIC_COMMIT_SHA, first.slot.metadata.commitSha)
        assertEquals(SYNTHETIC_ARCHIVE_SHA256, first.slot.metadata.archiveSha256)
        assertEquals(StmCoreArtifactIntegrity.VERIFIED, first.slot.metadata.integrity)
        assertEquals(StmCoreArtifactTrust.TRUSTED_CATALOG, first.slot.metadata.trust)
        assertEquals(syntheticArtifact(), first.slot.metadata.toCoreArtifact())
        assertTrue(fixture.store.verifyCommitted("slot-a") is StmSlotVerificationResult.Valid)
        assertTrue(fixture.store.verifyCommitted("slot-b") is StmSlotVerificationResult.Valid)
        val scan = fixture.store.scanCommitted()
        assertEquals(listOf("slot-a", "slot-b"), scan.map(StmSlotScanEntry::entryName))
        assertTrue(scan.all { it.verification is StmSlotVerificationResult.Valid })
    }

    @Test
    fun `startup scan reconstructs persisted artifact evidence without a checkpoint`() {
        val fixture = newFixture()
        writeSyntheticPayload(fixture.store, "operation-a", mapOf("content.txt" to "content"))
        fixture.store.prepareAndCommit(syntheticRequest("operation-a", "slot-a", 9))

        val reopenedStore = StmSlotStore(fixture.slots, fixture.staging)
        val entry = reopenedStore.scanCommitted().single()
        val valid = entry.verification as StmSlotVerificationResult.Valid

        assertEquals("slot-a", entry.entryName)
        assertEquals(9, valid.slot.metadata.slotRevision)
        assertEquals(StmSlotPayloadKind.GATE2_SYNTHETIC, valid.slot.metadata.payloadKind)
        assertEquals(SYNTHETIC_REPOSITORY, valid.slot.metadata.repository)
        assertEquals(SYNTHETIC_COMMIT_SHA, valid.slot.metadata.commitSha)
        assertEquals(SYNTHETIC_ARCHIVE_SHA256, valid.slot.metadata.archiveSha256)
        assertEquals(StmCoreArtifactIntegrity.VERIFIED, valid.slot.metadata.integrity)
        assertEquals(StmCoreArtifactTrust.TRUSTED_CATALOG, valid.slot.metadata.trust)
        assertEquals(syntheticArtifact(), valid.slot.metadata.toCoreArtifact())
    }

    @Test
    fun `nullable public artifact fields roundtrip without recovery placeholders`() {
        val fixture = newFixture()
        val expected = syntheticArtifact().copy(
            trust = StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG,
            catalogVersion = null,
            archiveRoot = null,
            stVersion = null,
            nodeRequirement = null,
            packageLockSha256 = null,
            licenseStatus = null,
        )
        writeSyntheticPayload(fixture.store, "operation-nullable", mapOf("content.txt" to "safe"))
        val request = syntheticRequest("operation-nullable", "slot-nullable", 10).copy(
            trust = expected.trust,
            catalogVersion = expected.catalogVersion,
            archiveRoot = expected.archiveRoot,
            stVersion = expected.stVersion,
            nodeRequirement = expected.nodeRequirement,
            packageLockSha256 = expected.packageLockSha256,
            licenseStatus = expected.licenseStatus,
        )
        fixture.store.prepareAndCommit(request)

        val valid = StmSlotStore(fixture.slots, fixture.staging)
            .scanCommitted()
            .single()
            .verification as StmSlotVerificationResult.Valid

        assertEquals(expected, valid.slot.metadata.toCoreArtifact())
    }

    @Test
    fun `startup scan reports corrupt foreign and symlink entries without skipping evidence`() {
        val fixture = newFixture()
        writeSyntheticPayload(fixture.store, "operation-a", mapOf("content.txt" to "original"))
        writeSyntheticPayload(fixture.store, "operation-b", mapOf("content.txt" to "stable"))
        val first = fixture.store.prepareAndCommit(
            syntheticRequest("operation-a", "slot-a", 1),
        ) as StmSlotCommitOutcome.Ready
        fixture.store.prepareAndCommit(syntheticRequest("operation-b", "slot-b", 2))
        first.slot.directory.resolve("content.txt").writeText("tampered")
        fixture.slots.resolve(".DS_Store").writeText("foreign")
        val outside = fixture.root.resolve("outside").apply { mkdirs() }
        val sentinel = outside.resolve("sentinel.txt").apply { writeText("preserve") }
        try {
            Files.createSymbolicLink(fixture.slots.resolve("slot-link").toPath(), outside.toPath())
        } catch (error: Exception) {
            assumeNoException("The test filesystem does not support symbolic links", error)
        }

        val scan = StmSlotStore(fixture.slots, fixture.staging).scanCommitted()
        val byName = scan.associateBy(StmSlotScanEntry::entryName)

        assertEquals(
            listOf(".DS_Store", "slot-a", "slot-b", "slot-link"),
            scan.map(StmSlotScanEntry::entryName),
        )
        assertEquals(4, byName.size)
        assertInvalidWithEvidence(byName.getValue(".DS_Store").verification)
        assertTrue(byName.getValue("slot-a").verification is StmSlotVerificationResult.Valid)
        assertInvalidWithEvidence(fixture.store.verifyCommitted("slot-a"))
        assertTrue(byName.getValue("slot-b").verification is StmSlotVerificationResult.Valid)
        assertInvalidWithEvidence(byName.getValue("slot-link").verification)
        assertEquals("preserve", sentinel.readText())
    }

    @Test
    fun `committed content tampering fails immutable manifest verification`() {
        val fixture = newFixture()
        writeSyntheticPayload(fixture.store, "operation-a", mapOf("content.txt" to "original"))
        val ready = fixture.store.prepareAndCommit(
            syntheticRequest("operation-a", "slot-a", 1),
        ) as StmSlotCommitOutcome.Ready
        assertTrue(fixture.store.verifyCommitted("slot-a") is StmSlotVerificationResult.Valid)

        ready.slot.directory.resolve("content.txt").writeText("tampered")

        val verification = fixture.store.verifyCommitted("slot-a")
        assertTrue(verification is StmSlotVerificationResult.Invalid)
        assertTrue((verification as StmSlotVerificationResult.Invalid).detail.contains("manifest"))
    }

    @Test
    fun `full verification detects missing extra and special entries without following links`() {
        val fixture = newFixture()
        listOf("missing", "extra", "special").forEachIndexed { index, name ->
            writeSyntheticPayload(
                fixture.store,
                "operation-$name",
                mapOf("content.txt" to "original"),
            )
            fixture.store.prepareAndCommit(
                syntheticRequest("operation-$name", "slot-$name", index.toLong() + 1L),
            )
        }
        fixture.slots.resolve("slot-missing/content.txt").delete()
        fixture.slots.resolve("slot-extra/unexpected.txt").writeText("extra")
        val outside = fixture.root.resolve("outside-sentinel.txt").apply {
            writeText("preserve")
        }
        try {
            Files.createSymbolicLink(
                fixture.slots.resolve("slot-special/link").toPath(),
                outside.toPath(),
            )
        } catch (error: Exception) {
            assumeNoException("The test filesystem does not support symbolic links", error)
        }

        assertInvalidWithEvidence(fixture.store.verifyCommitted("slot-missing"))
        assertInvalidWithEvidence(fixture.store.verifyCommitted("slot-extra"))
        assertInvalidWithEvidence(fixture.store.verifyCommitted("slot-special"))
        assertEquals("preserve", outside.readText())
    }

    @Test
    fun `stored manifest tampering is rejected before content can be trusted`() {
        val fixture = newFixture()
        writeSyntheticPayload(fixture.store, "operation-a", mapOf("content.txt" to "original"))
        val ready = fixture.store.prepareAndCommit(
            syntheticRequest("operation-a", "slot-a", 1),
        ) as StmSlotCommitOutcome.Ready
        val manifestFile = ready.slot.directory.resolve(".stm-slot/content-manifest.bin")
        val manifestBytes = manifestFile.readBytes()
        manifestBytes[manifestBytes.lastIndex] = (manifestBytes.last().toInt() xor 0x01).toByte()
        manifestFile.writeBytes(manifestBytes)

        val verification = fixture.store.verifyCommitted("slot-a")

        assertTrue(verification is StmSlotVerificationResult.Invalid)
    }

    @Test
    fun `corrupt persisted artifact fields are rejected by bounds and public validation semantics`() {
        val fixture = newFixture()
        writeSyntheticPayload(fixture.store, "operation-a", mapOf("content.txt" to "a"))
        writeSyntheticPayload(fixture.store, "operation-b", mapOf("content.txt" to "b"))
        val first = fixture.store.prepareAndCommit(
            syntheticRequest("operation-a", "slot-a", 1),
        ) as StmSlotCommitOutcome.Ready
        val second = fixture.store.prepareAndCommit(
            syntheticRequest("operation-b", "slot-b", 2),
        ) as StmSlotCommitOutcome.Ready

        rewriteMetadataPayload(first.slot.directory) { payload ->
            positionAtDownloadedTime(payload)
            payload.putLong(payload.position(), 0L)
        }
        rewriteMetadataPayload(second.slot.directory) { payload ->
            positionAtDownloadedTime(payload)
            payload.position(payload.position() + Long.SIZE_BYTES * 2)
            payload.skipEncodedString() // archive SHA-256
            payload.skipEncodedString() // integrity
            payload.skipEncodedString() // trust
            assertEquals(1, payload.get().toInt()) // catalogVersion is present
            payload.putInt(payload.position(), Int.MAX_VALUE)
        }

        val invalidTime = fixture.store.verifyCommitted("slot-a")
            as StmSlotVerificationResult.Invalid
        val invalidBound = fixture.store.verifyCommitted("slot-b")
            as StmSlotVerificationResult.Invalid
        assertTrue(invalidTime.detail.contains("download time"))
        assertTrue(invalidBound.detail.contains("String length"))
    }

    @Test
    fun `an existing slot target is never replaced`() {
        val fixture = newFixture()
        writeSyntheticPayload(fixture.store, "operation-a", mapOf("content.txt" to "first"))
        val first = fixture.store.prepareAndCommit(
            syntheticRequest("operation-a", "slot-a", 1),
        ) as StmSlotCommitOutcome.Ready
        writeSyntheticPayload(fixture.store, "operation-duplicate", mapOf("content.txt" to "second"))

        val duplicate = fixture.store.prepareAndCommit(
            syntheticRequest("operation-duplicate", "slot-a", 2),
        ) as StmSlotCommitOutcome.Blocked

        assertEquals(StmSlotBlockCode.TARGET_EXISTS, duplicate.code)
        assertEquals("first", first.slot.directory.resolve("content.txt").readText())
        assertTrue(fixture.store.verifyCommitted("slot-a") is StmSlotVerificationResult.Valid)
    }

    @Test
    fun `real SillyTavern source is verified but never promoted READY in Gate 2`() {
        val fixture = newFixture()
        writeSillyTavernSource(fixture.store, "operation-st")

        val outcome = fixture.store.prepareAndCommit(
            StmSlotCommitRequest(
                operationId = "operation-st",
                slotId = "slot-st",
                slotRevision = 7,
                payloadKind = StmSlotPayloadKind.SILLY_TAVERN_SOURCE,
                repository = "https://github.com/SillyTavern/SillyTavern.git",
                channel = "release",
                commitSha = "2".repeat(40),
                downloadUrl = "https://github.com/SillyTavern/SillyTavern/archive/${"2".repeat(40)}.zip",
                downloadedAtEpochMs = 1_753_200_000_000,
                archiveLength = 8_192,
                archiveSha256 = "b".repeat(64),
                integrity = StmCoreArtifactIntegrity.VERIFIED,
                trust = StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG,
                catalogVersion = null,
                archiveRoot = "SillyTavern-${"2".repeat(40)}",
                stVersion = "1.13.4",
                nodeRequirement = ">=18.0.0",
                packageLockSha256 = "d".repeat(64),
                licenseStatus = "AGPL-3.0-only",
            ),
        )

        assertTrue(outcome is StmSlotCommitOutcome.VerifiedNotReady)
        outcome as StmSlotCommitOutcome.VerifiedNotReady
        assertEquals(StmSlotAdmission.VERIFIED_NOT_READY, outcome.metadata.admission)
        assertTrue(outcome.reason.contains("dependencies"))
        assertFalse(fixture.slots.resolve("slot-st").exists())
        val stagingPayload = fixture.store.operationPayloadDirectory("operation-st")
        assertTrue(stagingPayload.resolve(".stm-slot/content-manifest.bin").isFile)
        assertTrue(stagingPayload.resolve(".stm-slot/slot-metadata.bin").isFile)
    }

    @Test
    fun `signed prebuilt runtime evidence promotes exact SillyTavern program to READY`() {
        val fixture = newFixture()
        val payload = fixture.store.operationPayloadDirectory("operation-st-ready")
        val sourceRoot = writeSillyTavernSource(fixture.store, "operation-st-ready")
        val adapterBytes = "export const stmPrebuilt = true;\n".toByteArray()
        val adapter = sourceRoot.resolve("src/middleware/webpack-serve.js")
        requireNotNull(adapter.parentFile).mkdirs()
        adapter.writeBytes(adapterBytes)
        val dependencyFile = sourceRoot.resolve("node_modules/example/index.js")
        requireNotNull(dependencyFile.parentFile).mkdirs()
        dependencyFile.writeText("export default 'fixture';\n")
        val runtimeBindings = writeRuntimeSidecars(payload, adapterBytes)
        val programEntries = scanTreeEntries(sourceRoot.toPath())
        val packageLockSha256 = sha256(sourceRoot.resolve("package-lock.json").toPath())
        val runtimeEvidence = StmRuntimeSlotAdmissionEvidence(
            repository = SILLY_TAVERN_REPOSITORY,
            commitSha = SILLY_TAVERN_COMMIT_SHA,
            packageLockSha256 = packageLockSha256,
            dependencyTreeSha256 = stmTreeIdentitySha256(
                programEntries.filter { entry ->
                    entry.relativePath == "node_modules" ||
                        entry.relativePath.startsWith("node_modules/")
                },
            ),
            postAdapterProgramTreeSha256 = stmTreeIdentitySha256(programEntries),
            runtimeFiles = runtimeBindings,
        )

        val outcome = fixture.store.prepareAndCommit(
            sillyTavernRequest(
                operationId = "operation-st-ready",
                slotId = "slot-st-ready",
                packageLockSha256 = packageLockSha256,
                runtimeEvidence = runtimeEvidence,
            ),
        ) as StmSlotCommitOutcome.Ready

        assertEquals(StmSlotAdmission.READY, outcome.slot.metadata.admission)
        assertTrue(fixture.store.verifyCommitted("slot-st-ready") is StmSlotVerificationResult.Valid)
        assertTrue(
            outcome.slot.directory.resolve(
                "${StmRuntimeSlotAdmissionEvidence.RUNTIME_DIRECTORY}/" +
                    StmRuntimeSlotAdmissionEvidence.MANIFEST_FILE,
            ).isFile,
        )
        assertFalse(outcome.slot.directory.resolve("dependencies.zip").exists())

        outcome.slot.directory.resolve(
            "${StmRuntimeSlotAdmissionEvidence.RUNTIME_DIRECTORY}/" +
                StmRuntimeSlotAdmissionEvidence.BUNDLE_FILE,
        ).appendText("tampered")
        assertTrue(
            fixture.store.verifyCommitted("slot-st-ready") is StmSlotVerificationResult.Invalid,
        )
    }

    @Test
    fun `device local runtime evidence promotes without a forged signature sidecar`() {
        val fixture = newFixture()
        val payload = fixture.store.operationPayloadDirectory("operation-local-ready")
        val sourceRoot = writeSillyTavernSource(fixture.store, "operation-local-ready")
        val adapterBytes = "export const stmLocalBuild = true;\n".toByteArray()
        val adapter = sourceRoot.resolve("src/middleware/webpack-serve.js")
        requireNotNull(adapter.parentFile).mkdirs()
        adapter.writeBytes(adapterBytes)
        val dependencyFile = sourceRoot.resolve("node_modules/example/index.js")
        requireNotNull(dependencyFile.parentFile).mkdirs()
        dependencyFile.writeText("export default 'local';\n")
        val runtimeBindings = writeRuntimeSidecars(
            payload,
            adapterBytes,
            StmRuntimeSupplyKind.DEVICE_LOCAL_BUILD,
        )
        val programEntries = scanTreeEntries(sourceRoot.toPath())
        val packageLockSha256 = sha256(sourceRoot.resolve("package-lock.json").toPath())
        val runtimeEvidence = StmRuntimeSlotAdmissionEvidence(
            supplyKind = StmRuntimeSupplyKind.DEVICE_LOCAL_BUILD,
            repository = SILLY_TAVERN_REPOSITORY,
            commitSha = SILLY_TAVERN_COMMIT_SHA,
            packageLockSha256 = packageLockSha256,
            dependencyTreeSha256 = stmTreeIdentitySha256(
                programEntries.filter { entry ->
                    entry.relativePath == "node_modules" ||
                        entry.relativePath.startsWith("node_modules/")
                },
            ),
            postAdapterProgramTreeSha256 = stmTreeIdentitySha256(programEntries),
            runtimeFiles = runtimeBindings,
        )

        val outcome = fixture.store.prepareAndCommit(
            sillyTavernRequest(
                operationId = "operation-local-ready",
                slotId = "slot-local-ready",
                packageLockSha256 = packageLockSha256,
                runtimeEvidence = runtimeEvidence,
            ),
        ) as StmSlotCommitOutcome.Ready

        val runtime = outcome.slot.directory.resolve(
            StmRuntimeSlotAdmissionEvidence.RUNTIME_DIRECTORY,
        )
        assertFalse(runtime.resolve(StmRuntimeSlotAdmissionEvidence.SIGNATURE_FILE).exists())
        assertTrue(
            runtime.resolve(StmRuntimeSlotAdmissionEvidence.RUNNABLE_ACCEPTANCE_FILE).isFile,
        )
        assertTrue(fixture.store.verifyCommitted("slot-local-ready") is StmSlotVerificationResult.Valid)
    }

    @Test
    fun `signed runtime evidence cannot promote a different program tree`() {
        val fixture = newFixture()
        val payload = fixture.store.operationPayloadDirectory("operation-st-mismatch")
        val sourceRoot = writeSillyTavernSource(fixture.store, "operation-st-mismatch")
        val adapterBytes = "export const stmPrebuilt = true;\n".toByteArray()
        val adapter = sourceRoot.resolve("src/middleware/webpack-serve.js")
        requireNotNull(adapter.parentFile).mkdirs()
        adapter.writeBytes(adapterBytes)
        val dependencyFile = sourceRoot.resolve("node_modules/example/index.js")
        requireNotNull(dependencyFile.parentFile).mkdirs()
        dependencyFile.writeText("export default 'fixture';\n")
        val runtimeBindings = writeRuntimeSidecars(payload, adapterBytes)
        val programEntries = scanTreeEntries(sourceRoot.toPath())
        val packageLockSha256 = sha256(sourceRoot.resolve("package-lock.json").toPath())
        val runtimeEvidence = StmRuntimeSlotAdmissionEvidence(
            repository = SILLY_TAVERN_REPOSITORY,
            commitSha = SILLY_TAVERN_COMMIT_SHA,
            packageLockSha256 = packageLockSha256,
            dependencyTreeSha256 = stmTreeIdentitySha256(
                programEntries.filter { entry ->
                    entry.relativePath == "node_modules" ||
                        entry.relativePath.startsWith("node_modules/")
                },
            ),
            postAdapterProgramTreeSha256 = "f".repeat(64),
            runtimeFiles = runtimeBindings,
        )

        val outcome = fixture.store.prepareAndCommit(
            sillyTavernRequest(
                operationId = "operation-st-mismatch",
                slotId = "slot-st-mismatch",
                packageLockSha256 = packageLockSha256,
                runtimeEvidence = runtimeEvidence,
            ),
        ) as StmSlotCommitOutcome.Blocked

        assertEquals(StmSlotBlockCode.ACCEPTANCE_FAILED, outcome.code)
        assertTrue(outcome.detail.contains("program tree"))
        assertFalse(fixture.slots.resolve("slot-st-mismatch").exists())
    }

    @Test
    fun `synthetic payload without the exact fixture marker is BLOCKED`() {
        val fixture = newFixture()
        val payload = fixture.store.operationPayloadDirectory("operation-bad")
        payload.mkdirs()
        payload.resolve("gate2-fixture.txt").writeText("not-the-marker")

        val outcome = fixture.store.prepareAndCommit(
            syntheticRequest("operation-bad", "slot-bad", 1),
        ) as StmSlotCommitOutcome.Blocked

        assertEquals(StmSlotBlockCode.ACCEPTANCE_FAILED, outcome.code)
        assertFalse(fixture.slots.resolve("slot-bad").exists())
    }

    @Test
    fun `integrity and trust evidence independently prevent READY admission`() {
        val fixture = newFixture()
        writeSyntheticPayload(fixture.store, "operation-pending", mapOf("content.txt" to "safe"))
        writeSyntheticPayload(fixture.store, "operation-rejected", mapOf("content.txt" to "safe"))

        val pending = fixture.store.prepareAndCommit(
            syntheticRequest("operation-pending", "slot-pending", 1).copy(
                integrity = StmCoreArtifactIntegrity.PENDING,
                trust = StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG,
            ),
        ) as StmSlotCommitOutcome.Blocked
        val rejected = fixture.store.prepareAndCommit(
            syntheticRequest("operation-rejected", "slot-rejected", 2).copy(
                trust = StmCoreArtifactTrust.REJECTED,
            ),
        ) as StmSlotCommitOutcome.Blocked

        assertEquals(StmSlotBlockCode.ACCEPTANCE_FAILED, pending.code)
        assertEquals(StmSlotBlockCode.ACCEPTANCE_FAILED, rejected.code)
        assertFalse(fixture.slots.resolve("slot-pending").exists())
        assertFalse(fixture.slots.resolve("slot-rejected").exists())
    }

    @Test
    fun `active and running references independently block deletion`() {
        val fixture = newFixture()
        writeSyntheticPayload(fixture.store, "operation-a", mapOf("a.txt" to "a"))
        writeSyntheticPayload(fixture.store, "operation-b", mapOf("b.txt" to "b"))
        fixture.store.prepareAndCommit(syntheticRequest("operation-a", "slot-a", 1))
        fixture.store.prepareAndCommit(syntheticRequest("operation-b", "slot-b", 2))

        val activeRejected = fixture.store.deleteSlot(
            slotId = "slot-a",
            active = StmSlotReference("slot-a", 1),
            running = null,
        )
        val runningRejected = fixture.store.deleteSlot(
            slotId = "slot-b",
            active = null,
            running = StmSlotReference("slot-b", 2),
        )

        assertEquals(StmSlotDeleteResult.RejectedReferenced("active"), activeRejected)
        assertEquals(StmSlotDeleteResult.RejectedReferenced("running"), runningRejected)
        assertTrue(fixture.slots.resolve("slot-a").isDirectory)
        assertTrue(fixture.slots.resolve("slot-b").isDirectory)
    }

    @Test
    fun `deleting an unreferenced slot never touches a data sentinel`() {
        val fixture = newFixture()
        writeSyntheticPayload(fixture.store, "operation-a", mapOf("content.txt" to "content"))
        fixture.store.prepareAndCommit(syntheticRequest("operation-a", "slot-a", 1))
        val dataSentinel = fixture.root.resolve("stm_data/user-data.txt")
        requireNotNull(dataSentinel.parentFile).mkdirs()
        dataSentinel.writeText("preserve-me")

        val deleted = fixture.store.deleteSlot("slot-a", active = null, running = null)

        assertEquals(StmSlotDeleteResult.Deleted, deleted)
        assertFalse(fixture.slots.resolve("slot-a").exists())
        assertEquals("preserve-me", dataSentinel.readText())
    }

    @Test
    fun `recursive deletion unlinks a tampered symlink without following it into data`() {
        val fixture = newFixture()
        val dataDirectory = fixture.root.resolve("stm_data").apply { mkdirs() }
        val dataSentinel = dataDirectory.resolve("user-data.txt").apply { writeText("preserve-me") }
        val corruptSlot = fixture.slots.resolve("slot-corrupt").apply { mkdirs() }
        try {
            Files.createSymbolicLink(
                corruptSlot.resolve("data-link").toPath(),
                dataDirectory.toPath(),
            )
        } catch (error: Exception) {
            assumeNoException("The test filesystem does not support symbolic links", error)
        }

        val deleted = fixture.store.deleteSlot("slot-corrupt", active = null, running = null)

        assertEquals(StmSlotDeleteResult.Deleted, deleted)
        assertFalse(corruptSlot.exists())
        assertEquals("preserve-me", dataSentinel.readText())
    }

    @Test
    fun `a symlink inside payload is rejected without reading or changing its target`() {
        val fixture = newFixture()
        val outside = fixture.root.resolve("outside-secret.txt").apply { writeText("outside") }
        val payload = writeSyntheticPayload(
            fixture.store,
            "operation-link",
            mapOf("content.txt" to "safe"),
        )
        try {
            Files.createSymbolicLink(payload.resolve("escape-link").toPath(), outside.toPath())
        } catch (error: Exception) {
            assumeNoException("The test filesystem does not support symbolic links", error)
        }

        val outcome = fixture.store.prepareAndCommit(
            syntheticRequest("operation-link", "slot-link", 1),
        ) as StmSlotCommitOutcome.Blocked

        assertEquals(StmSlotBlockCode.UNSAFE_CONTENT, outcome.code)
        assertEquals("outside", outside.readText())
        assertFalse(fixture.slots.resolve("slot-link").exists())
    }

    @Test
    fun `a payload symlink and path escape operation ID are rejected`() {
        val fixture = newFixture()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.store.operationPayloadDirectory("../escape")
        }

        val outsidePayload = fixture.root.resolve("outside-payload").apply { mkdirs() }
        outsidePayload.resolve("gate2-fixture.txt").writeText(GATE2_MARKER)
        val operationDirectory = fixture.staging.resolve("operation-symlink").apply { mkdirs() }
        try {
            Files.createSymbolicLink(
                operationDirectory.resolve("payload").toPath(),
                outsidePayload.toPath(),
            )
        } catch (error: Exception) {
            assumeNoException("The test filesystem does not support symbolic links", error)
        }

        val outcome = fixture.store.prepareAndCommit(
            syntheticRequest("operation-symlink", "slot-symlink", 1),
        ) as StmSlotCommitOutcome.Blocked
        assertEquals(StmSlotBlockCode.UNSAFE_CONTENT, outcome.code)
        assertFalse(fixture.slots.resolve("slot-symlink").exists())
    }

    @Test
    fun `reserved metadata supplied by a payload is rejected`() {
        val fixture = newFixture()
        val payload = writeSyntheticPayload(
            fixture.store,
            "operation-reserved",
            mapOf("content.txt" to "safe"),
        )
        payload.resolve(".stm-slot").mkdirs()
        payload.resolve(".stm-slot/foreign.txt").writeText("foreign")

        val outcome = fixture.store.prepareAndCommit(
            syntheticRequest("operation-reserved", "slot-reserved", 1),
        ) as StmSlotCommitOutcome.Blocked

        assertEquals(StmSlotBlockCode.RESERVED_METADATA, outcome.code)
        assertFalse(fixture.slots.resolve("slot-reserved").exists())
    }

    @Test
    fun `failpoints before and after move leave the old slot unchanged`() {
        val fixture = newFixture()
        writeSyntheticPayload(fixture.store, "operation-a", mapOf("stable.txt" to "old"))
        val old = fixture.store.prepareAndCommit(
            syntheticRequest("operation-a", "slot-a", 1),
        ) as StmSlotCommitOutcome.Ready
        val oldManifest = old.slot.manifest

        writeSyntheticPayload(fixture.store, "operation-before", mapOf("new.txt" to "before"))
        val beforeStore = StmSlotStore(
            fixture.slots,
            fixture.staging,
            faultInjector = StmSlotStoreFaultInjector { point ->
                if (point == StmSlotStoreFailpoint.BEFORE_SLOT_MOVE) {
                    throw SimulatedInterruption(point)
                }
            },
        )
        assertThrows(SimulatedInterruption::class.java) {
            beforeStore.prepareAndCommit(syntheticRequest("operation-before", "slot-before", 2))
        }
        assertFalse(fixture.slots.resolve("slot-before").exists())
        assertOldSlotUnchanged(fixture.store, oldManifest)

        writeSyntheticPayload(fixture.store, "operation-after", mapOf("new.txt" to "after"))
        val afterStore = StmSlotStore(
            fixture.slots,
            fixture.staging,
            faultInjector = StmSlotStoreFaultInjector { point ->
                if (point == StmSlotStoreFailpoint.AFTER_SLOT_MOVE) {
                    throw SimulatedInterruption(point)
                }
            },
        )
        assertThrows(SimulatedInterruption::class.java) {
            afterStore.prepareAndCommit(syntheticRequest("operation-after", "slot-after", 3))
        }
        assertTrue(fixture.store.verifyCommitted("slot-after") is StmSlotVerificationResult.Valid)
        assertOldSlotUnchanged(fixture.store, oldManifest)
    }

    @Test
    fun `failed directory rename leaves payload staged and no READY slot`() {
        val fixture = newFixture()
        writeSyntheticPayload(fixture.store, "operation-rename-failed", mapOf("content.txt" to "safe"))
        val failingStore = StmSlotStore(
            fixture.slots,
            fixture.staging,
            directoryRenamer = { _, _ -> false },
        )

        val outcome = failingStore.prepareAndCommit(
            syntheticRequest("operation-rename-failed", "slot-rename-failed", 1),
        ) as StmSlotCommitOutcome.Blocked

        assertEquals(StmSlotBlockCode.ATOMIC_MOVE_UNSUPPORTED, outcome.code)
        assertTrue(failingStore.operationPayloadDirectory("operation-rename-failed").isDirectory)
        assertFalse(fixture.slots.resolve("slot-rename-failed").exists())
    }

    private fun assertInvalidWithEvidence(result: StmSlotVerificationResult) {
        assertTrue(result is StmSlotVerificationResult.Invalid)
        assertTrue((result as StmSlotVerificationResult.Invalid).detail.isNotBlank())
    }

    private fun rewriteMetadataPayload(
        slotDirectory: File,
        mutation: (ByteBuffer) -> Unit,
    ) {
        val metadataFile = slotDirectory.resolve(".stm-slot/slot-metadata.bin")
        val bytes = metadataFile.readBytes()
        val wrapper = ByteBuffer.wrap(bytes)
        assertEquals(0x53544D53, wrapper.int)
        assertEquals(3, wrapper.int)
        val payloadLength = wrapper.int
        assertEquals(Int.SIZE_BYTES * 3 + payloadLength + 32, bytes.size)
        val payload = ByteBuffer.wrap(bytes, Int.SIZE_BYTES * 3, payloadLength).slice()
        mutation(payload)
        val payloadBytes = bytes.copyOfRange(Int.SIZE_BYTES * 3, Int.SIZE_BYTES * 3 + payloadLength)
        val checksum = MessageDigest.getInstance("SHA-256").digest(payloadBytes)
        checksum.copyInto(bytes, Int.SIZE_BYTES * 3 + payloadLength)
        metadataFile.writeBytes(bytes)
    }

    private fun positionAtDownloadedTime(payload: ByteBuffer) {
        payload.skipEncodedString() // operation ID
        payload.skipEncodedString() // slot ID
        payload.position(payload.position() + Long.SIZE_BYTES)
        payload.skipEncodedString() // payload kind
        payload.skipEncodedString() // repository
        payload.skipEncodedString() // channel
        payload.skipEncodedString() // commit SHA
        payload.skipEncodedString() // download URL
    }

    private fun ByteBuffer.skipEncodedString() {
        val length = int
        require(length > 0 && length <= remaining())
        position(position() + length)
    }

    private fun assertOldSlotUnchanged(
        store: StmSlotStore,
        expectedManifest: StmSlotContentManifest,
    ) {
        val verification = store.verifyCommitted("slot-a") as StmSlotVerificationResult.Valid
        assertEquals(expectedManifest, verification.slot.manifest)
        assertEquals("old", verification.slot.directory.resolve("stable.txt").readText())
    }

    private fun newFixture(): Fixture {
        val root = Files.createTempDirectory("stm-slot-store").toFile()
        val slots = root.resolve("core/slots")
        val staging = root.resolve("core/staging")
        return Fixture(root, slots, staging, StmSlotStore(slots, staging))
    }

    private fun writeSyntheticPayload(
        store: StmSlotStore,
        operationId: String,
        files: Map<String, String>,
    ): File {
        val payload = store.operationPayloadDirectory(operationId)
        payload.mkdirs()
        payload.resolve("gate2-fixture.txt").writeText(GATE2_MARKER)
        files.forEach { (relativePath, content) ->
            val file = payload.resolve(relativePath)
            requireNotNull(file.parentFile).mkdirs()
            file.writeText(content)
        }
        payload.resolve("empty-directory").mkdirs()
        return payload
    }

    private fun writeSillyTavernSource(store: StmSlotStore, operationId: String): File {
        val payload = store.operationPayloadDirectory(operationId)
        payload.mkdirs()
        val sourceRoot = payload.resolve("SillyTavern-$SILLY_TAVERN_COMMIT_SHA").apply { mkdirs() }
        listOf("LICENSE", "package-lock.json", "package.json", "server.js").forEach { name ->
            sourceRoot.resolve(name).writeText("fixture-$name")
        }
        return sourceRoot
    }

    private fun writeRuntimeSidecars(
        payload: File,
        adapterBytes: ByteArray,
        supplyKind: StmRuntimeSupplyKind = StmRuntimeSupplyKind.SIGNED_PREBUILT,
    ): Map<String, StmRuntimeFileBinding> {
        val runtimeDirectory = payload.resolve(StmRuntimeSlotAdmissionEvidence.RUNTIME_DIRECTORY)
            .apply { mkdirs() }
        val requiredFiles = when (supplyKind) {
            StmRuntimeSupplyKind.SIGNED_PREBUILT ->
                StmRuntimeSlotAdmissionEvidence.SIGNED_PREBUILT_RUNTIME_FILES

            StmRuntimeSupplyKind.DEVICE_LOCAL_BUILD ->
                StmRuntimeSlotAdmissionEvidence.DEVICE_LOCAL_BUILD_RUNTIME_FILES
        }
        requiredFiles.sorted().forEach { name ->
            val bytes = when (name) {
                StmRuntimeSlotAdmissionEvidence.ADAPTER_FILE -> adapterBytes
                StmRuntimeSlotAdmissionEvidence.SIGNATURE_FILE -> ByteArray(64) { 0x5a }
                else -> "signed-fixture-$name\n".toByteArray()
            }
            runtimeDirectory.resolve(name).writeBytes(bytes)
        }
        return requiredFiles.associateWith { name ->
            val file = runtimeDirectory.resolve(name)
            StmRuntimeFileBinding(
                bytes = file.length(),
                sha256 = sha256(file.toPath()),
            )
        }
    }

    private fun scanTreeEntries(root: Path): List<StmZipManifestEntry> {
        val entries = mutableListOf<StmZipManifestEntry>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                if (directory != root) {
                    entries += StmZipManifestEntry(
                        relativePath = manifestPath(root.relativize(directory)),
                        type = StmZipManifestEntryType.DIRECTORY,
                        sizeBytes = 0,
                        sha256 = null,
                    )
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                entries += StmZipManifestEntry(
                    relativePath = manifestPath(root.relativize(file)),
                    type = StmZipManifestEntryType.FILE,
                    sizeBytes = attributes.size(),
                    sha256 = sha256(file),
                )
                return FileVisitResult.CONTINUE
            }
        })
        return entries.sortedBy(StmZipManifestEntry::relativePath)
    }

    private fun manifestPath(path: Path): String =
        (0 until path.nameCount).joinToString("/") { index -> path.getName(index).toString() }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }
    }

    private fun sillyTavernRequest(
        operationId: String,
        slotId: String,
        packageLockSha256: String,
        runtimeEvidence: StmRuntimeSlotAdmissionEvidence?,
    ) = StmSlotCommitRequest(
        operationId = operationId,
        slotId = slotId,
        slotRevision = 8,
        payloadKind = StmSlotPayloadKind.SILLY_TAVERN_SOURCE,
        repository = SILLY_TAVERN_REPOSITORY,
        channel = "release",
        commitSha = SILLY_TAVERN_COMMIT_SHA,
        downloadUrl = "https://github.com/SillyTavern/SillyTavern/archive/" +
            "$SILLY_TAVERN_COMMIT_SHA.zip",
        downloadedAtEpochMs = 1_753_200_000_000,
        archiveLength = 8_192,
        archiveSha256 = "b".repeat(64),
        integrity = StmCoreArtifactIntegrity.VERIFIED,
        trust = StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG,
        catalogVersion = null,
        archiveRoot = "SillyTavern-$SILLY_TAVERN_COMMIT_SHA",
        stVersion = "1.13.4",
        nodeRequirement = ">=18.0.0",
        packageLockSha256 = packageLockSha256,
        licenseStatus = "AGPL-3.0-only",
        runtimeEvidence = runtimeEvidence,
    )

    private fun syntheticRequest(
        operationId: String,
        slotId: String,
        slotRevision: Long,
    ): StmSlotCommitRequest {
        val artifact = syntheticArtifact()
        return StmSlotCommitRequest(
            operationId = operationId,
            slotId = slotId,
            slotRevision = slotRevision,
            payloadKind = StmSlotPayloadKind.GATE2_SYNTHETIC,
            repository = artifact.repository,
            channel = artifact.channel,
            commitSha = artifact.commitSha,
            downloadUrl = artifact.downloadUrl,
            downloadedAtEpochMs = artifact.downloadedAtEpochMs,
            archiveLength = artifact.archiveLength,
            archiveSha256 = artifact.archiveSha256,
            integrity = artifact.integrity,
            trust = artifact.trust,
            catalogVersion = artifact.catalogVersion,
            archiveRoot = artifact.archiveRoot,
            stVersion = artifact.stVersion,
            nodeRequirement = artifact.nodeRequirement,
            packageLockSha256 = artifact.packageLockSha256,
            licenseStatus = artifact.licenseStatus,
        )
    }

    private fun syntheticArtifact() = StmCoreArtifact(
        kind = StmCoreArtifactKind.GATE2_SYNTHETIC,
        repository = SYNTHETIC_REPOSITORY,
        channel = "gate2",
        commitSha = SYNTHETIC_COMMIT_SHA,
        downloadUrl =
            "https://github.com/example/stm-gate2-fixture/archive/$SYNTHETIC_COMMIT_SHA.zip",
        downloadedAtEpochMs = 1_753_200_000_000,
        archiveLength = 4_096,
        archiveSha256 = SYNTHETIC_ARCHIVE_SHA256,
        integrity = StmCoreArtifactIntegrity.VERIFIED,
        trust = StmCoreArtifactTrust.TRUSTED_CATALOG,
        catalogVersion = "gate2-v1",
        archiveRoot = "stm-gate2-fixture-$SYNTHETIC_COMMIT_SHA",
        stVersion = "fixture-1.0.0",
        nodeRequirement = ">=24.0.0",
        packageLockSha256 = "c".repeat(64),
        licenseStatus = "fixture-approved",
    )

    private data class Fixture(
        val root: File,
        val slots: File,
        val staging: File,
        val store: StmSlotStore,
    )

    private class SimulatedInterruption(point: StmSlotStoreFailpoint) :
        RuntimeException("Interrupted at $point")

    private companion object {
        const val GATE2_MARKER = "STM_GATE2_SYNTHETIC_V1\n"
        const val SYNTHETIC_REPOSITORY = "https://github.com/example/stm-gate2-fixture.git"
        const val SILLY_TAVERN_REPOSITORY = "https://github.com/SillyTavern/SillyTavern.git"
        val SYNTHETIC_COMMIT_SHA = "1".repeat(40)
        val SILLY_TAVERN_COMMIT_SHA = "2".repeat(40)
        val SYNTHETIC_ARCHIVE_SHA256 = "a".repeat(64)
    }
}
