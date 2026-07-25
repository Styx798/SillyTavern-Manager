package io.github.styx798.sillytavernmanager.stmcore.installer

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import java.security.Principal
import java.security.cert.Certificate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StmGitHubPrebuiltSlotPreparerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `catalog selects only the exact ST 1 18 0 source and runtime`() {
        val entry = StmPrebuiltRuntimeCatalog.ST_1_18_0
        val request = request(
            repository = entry.repository,
            commit = entry.commitSha,
            version = entry.stVersion,
            lock = entry.packageLockSha256,
        )

        assertEquals(entry, StmPrebuiltRuntimeCatalog.find(request))
        assertNull(StmPrebuiltRuntimeCatalog.find(request.copy(commitSha = "0".repeat(40))))
        assertNull(StmPrebuiltRuntimeCatalog.find(request.copy(stVersion = "1.18.1")))
        assertNull(
            StmPrebuiltRuntimeCatalog.find(
                request.copy(packageLockSha256 = "0".repeat(64)),
            ),
        )
        assertNull(
            StmPrebuiltRuntimeCatalog.find(
                request.copy(repository = "${entry.repository}.git"),
            ),
        )
    }

    @Test
    fun `transport unavailability invokes the device local fallback`() {
        val entry = testEntry(bytes = 1, sha256 = "0".repeat(64))
        val fallbackCalls = AtomicInteger()
        val fallbackEvidence = evidence()
        val preparer = StmGitHubPrebuiltSlotPreparer(
            localFallback = StmRuntimeSlotPreparer { _, _, _ ->
                fallbackCalls.incrementAndGet()
                fallbackEvidence
            },
            runnableAcceptor = StmRuntimeSlotRunnableAcceptor { _, _, _ ->
                error("Unavailable downloads must not run prebuilt acceptance")
            },
            catalogLookup = { entry },
            downloader = StmRuntimeLayerDownloader { _, _, _ ->
                StmRuntimeLayerDownloadResult.Unavailable("offline")
            },
            archiveInstaller = StmRuntimeLayerArchiveInstaller { _, _, _, _ ->
                error("Unavailable downloads must not enter integration")
            },
        )
        val phases = mutableListOf<StmRuntimeSlotPreparationPhase>()

        assertEquals(
            fallbackEvidence,
            preparer.prepare(request(), StmExtractionCancellation.NONE, phases::add),
        )
        assertEquals(1, fallbackCalls.get())
        assertEquals(
            listOf(StmRuntimeSlotPreparationPhase.DOWNLOADING_RUNTIME_LAYER),
            phases,
        )
    }

    @Test
    fun `rejected download fails closed without local fallback`() {
        val entry = testEntry(bytes = 1, sha256 = "0".repeat(64))
        val fallbackCalled = AtomicBoolean(false)
        val preparer = StmGitHubPrebuiltSlotPreparer(
            localFallback = StmRuntimeSlotPreparer { _, _, _ ->
                fallbackCalled.set(true)
                evidence()
            },
            runnableAcceptor = StmRuntimeSlotRunnableAcceptor { _, _, _ -> },
            catalogLookup = { entry },
            downloader = StmRuntimeLayerDownloader { _, _, _ ->
                StmRuntimeLayerDownloadResult.Rejected("hash mismatch")
            },
            archiveInstaller = StmRuntimeLayerArchiveInstaller { _, _, _, _ -> evidence() },
        )

        val error = runCatching {
            preparer.prepare(request(), StmExtractionCancellation.NONE) {}
        }.exceptionOrNull() as? StmRuntimeSlotPreparationException

        assertNotNull(error)
        assertEquals(
            StmRuntimeSlotPreparationErrorCode.PREBUILT_RUNTIME_REJECTED,
            error?.code,
        )
        assertFalse(fallbackCalled.get())
    }

    @Test
    fun `downloaded verified layer integrates and runs acceptance`() {
        val bytes = "runtime-layer".toByteArray()
        val entry = testEntry(bytes.size.toLong(), bytes.sha256())
        val fallbackCalled = AtomicBoolean(false)
        val installerCalls = AtomicInteger()
        val acceptanceCalls = AtomicInteger()
        val preparedEvidence = evidence()
        val preparer = StmGitHubPrebuiltSlotPreparer(
            localFallback = StmRuntimeSlotPreparer { _, _, _ ->
                fallbackCalled.set(true)
                evidence()
            },
            runnableAcceptor = StmRuntimeSlotRunnableAcceptor { _, bundle, _ ->
                acceptanceCalls.incrementAndGet()
                assertEquals(
                    preparedEvidence.runtimeFiles.getValue(
                        StmRuntimeSlotAdmissionEvidence.BUNDLE_FILE,
                    ),
                    bundle,
                )
            },
            catalogLookup = { entry },
            downloader = StmRuntimeLayerDownloader { _, destination, _ ->
                destination.writeBytes(bytes)
                StmRuntimeLayerDownloadResult.Downloaded(
                    destination,
                    bytes.size.toLong(),
                    bytes.sha256(),
                )
            },
            archiveInstaller = StmRuntimeLayerArchiveInstaller { _, _, archive, _ ->
                installerCalls.incrementAndGet()
                assertTrue(archive.isFile)
                preparedEvidence
            },
        )
        val phases = mutableListOf<StmRuntimeSlotPreparationPhase>()
        val preparationRequest = request()

        assertEquals(
            preparedEvidence,
            preparer.prepare(
                preparationRequest,
                StmExtractionCancellation.NONE,
                phases::add,
            ),
        )
        assertFalse(fallbackCalled.get())
        assertEquals(1, installerCalls.get())
        assertEquals(1, acceptanceCalls.get())
        assertEquals(
            listOf(
                StmRuntimeSlotPreparationPhase.DOWNLOADING_RUNTIME_LAYER,
                StmRuntimeSlotPreparationPhase.VERIFYING_RUNTIME_LAYER,
                StmRuntimeSlotPreparationPhase.RUNNABLE_ACCEPTANCE,
            ),
            phases,
        )
        assertFalse(
            preparationRequest.operationRoot.resolve("prebuilt-runtime-layer.part").exists(),
        )
    }

    @Test
    fun `outer archive is identity checked extracted and cleaned before returning`() {
        val operation = temporaryFolder.newFolder("operation")
        val payload = File(operation, "payload").apply { mkdirs() }
        val archive = File(operation, "runtime.zip")
        writeRuntimeLayerZip(archive, "test-runtime-root")
        val entry = testEntry(archive.length(), archive.readBytes().sha256()).copy(
            archiveRoot = "test-runtime-root",
        )
        val expectedEvidence = evidence()
        val supplyCalls = AtomicInteger()
        val installer = StmDefaultRuntimeLayerArchiveInstaller(
            supplyInstaller = StmSignedPrebuiltSupplyInstaller {
                    suppliedEntry,
                    suppliedRequest,
                    supplyRoot,
                    dependencyExtractionRoot,
                    _,
                ->
                supplyCalls.incrementAndGet()
                assertEquals(entry, suppliedEntry)
                assertEquals(operation, suppliedRequest.operationRoot)
                assertEquals("test-runtime-root", supplyRoot.name)
                assertTrue(supplyRoot.resolve("marker.txt").isFile)
                assertEquals(
                    File(operation, "prebuilt-dependency-extraction"),
                    dependencyExtractionRoot,
                )
                expectedEvidence
            },
        )

        assertEquals(
            expectedEvidence,
            installer.install(
                entry = entry,
                request = request(
                    operation = operation,
                    payload = payload,
                ),
                archive = archive,
                cancellation = StmExtractionCancellation.NONE,
            ),
        )
        assertEquals(1, supplyCalls.get())
        assertFalse(File(operation, "prebuilt-runtime-extraction").exists())
    }

    @Test
    fun `outer archive identity mismatch never reaches signed supply integration`() {
        val operation = temporaryFolder.newFolder("identity-mismatch")
        val payload = File(operation, "payload").apply { mkdirs() }
        val archive = File(operation, "runtime.zip").apply { writeText("wrong") }
        val supplyCalled = AtomicBoolean(false)
        val installer = StmDefaultRuntimeLayerArchiveInstaller(
            supplyInstaller = StmSignedPrebuiltSupplyInstaller { _, _, _, _, _ ->
                supplyCalled.set(true)
                evidence()
            },
        )

        val error = runCatching {
            installer.install(
                entry = testEntry(archive.length(), "0".repeat(64)),
                request = request(operation = operation, payload = payload),
                archive = archive,
                cancellation = StmExtractionCancellation.NONE,
            )
        }.exceptionOrNull()

        assertNotNull(error)
        assertFalse(supplyCalled.get())
    }

    @Test
    fun `HTTPS downloader stores only the exact catalog identity`() {
        val body = "exact GitHub release body".toByteArray()
        val entry = testEntry(body.size.toLong(), body.sha256())
        val destination = File(temporaryFolder.newFolder("download-success"), "runtime.part")
        val downloader = StmGitHubRuntimeLayerDownloader(
            connectionFactory = { url ->
                FakeHttpsURLConnection(url, 200, body)
            },
        )

        val result = downloader.download(
            entry,
            destination,
            StmExtractionCancellation.NONE,
        )

        assertTrue(result is StmRuntimeLayerDownloadResult.Downloaded)
        result as StmRuntimeLayerDownloadResult.Downloaded
        assertEquals(body.size.toLong(), result.bytes)
        assertEquals(body.sha256(), result.sha256)
        assertTrue(destination.readBytes().contentEquals(body))
    }

    @Test
    fun `HTTPS downloader treats missing private release as unavailable`() {
        val entry = testEntry(1, "0".repeat(64))
        val destination = File(temporaryFolder.newFolder("download-missing"), "runtime.part")
        val downloader = StmGitHubRuntimeLayerDownloader(
            connectionFactory = { url ->
                FakeHttpsURLConnection(url, 404, ByteArray(0))
            },
        )

        val result = downloader.download(
            entry,
            destination,
            StmExtractionCancellation.NONE,
        )

        assertTrue(result is StmRuntimeLayerDownloadResult.Unavailable)
        assertFalse(destination.exists())
    }

    @Test
    fun `HTTPS downloader rejects redirects outside GitHub asset hosts`() {
        val entry = testEntry(1, "0".repeat(64))
        val destination = File(temporaryFolder.newFolder("download-redirect"), "runtime.part")
        val downloader = StmGitHubRuntimeLayerDownloader(
            connectionFactory = { url ->
                FakeHttpsURLConnection(
                    url = url,
                    observedResponseCode = 302,
                    body = ByteArray(0),
                    headers = mapOf("Location" to "https://example.com/runtime.zip"),
                )
            },
        )

        val result = downloader.download(
            entry,
            destination,
            StmExtractionCancellation.NONE,
        )

        assertTrue(result is StmRuntimeLayerDownloadResult.Rejected)
        assertFalse(destination.exists())
    }

    private fun request(
        repository: String = "https://github.com/SillyTavern/SillyTavern",
        commit: String = "1".repeat(40),
        version: String = "1.18.0",
        lock: String = "2".repeat(64),
        operation: File = temporaryFolder.newFolder("operation-${UUID.randomUUID()}"),
        payload: File = File(operation, "payload").apply { mkdirs() },
    ) = StmRuntimeSlotPreparationRequest(
        operationId = UUID.randomUUID().toString(),
        operationRoot = operation,
        payloadDirectory = payload,
        archiveRoot = "SillyTavern-$commit",
        repository = repository,
        commitSha = commit,
        stVersion = version,
        packageLockSha256 = lock,
    )

    private fun testEntry(bytes: Long, sha256: String) =
        StmPrebuiltRuntimeCatalog.ST_1_18_0.copy(
            repository = "https://github.com/SillyTavern/SillyTavern",
            commitSha = "1".repeat(40),
            stVersion = "1.18.0",
            packageLockSha256 = "2".repeat(64),
            archiveBytes = bytes,
            archiveSha256 = sha256,
        )

    private fun evidence(): StmRuntimeSlotAdmissionEvidence {
        val bundle = StmRuntimeFileBinding(10, "a".repeat(64))
        return StmRuntimeSlotAdmissionEvidence(
            repository = "https://github.com/SillyTavern/SillyTavern",
            commitSha = "1".repeat(40),
            packageLockSha256 = "2".repeat(64),
            dependencyTreeSha256 = "3".repeat(64),
            postAdapterProgramTreeSha256 = "4".repeat(64),
            runtimeFiles = mapOf(StmRuntimeSlotAdmissionEvidence.BUNDLE_FILE to bundle),
        )
    }

    private fun writeRuntimeLayerZip(file: File, root: String) {
        FileOutputStream(file).use { output ->
            ZipOutputStream(output).use { zip ->
                val directory = ZipEntry("$root/").apply {
                    method = ZipEntry.STORED
                    size = 0
                    compressedSize = 0
                    crc = 0
                    time = 0
                }
                zip.putNextEntry(directory)
                zip.closeEntry()
                val marker = "verified".toByteArray()
                zip.putNextEntry(ZipEntry("$root/marker.txt").apply { time = 0 })
                zip.write(marker)
                zip.closeEntry()
            }
        }
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private class FakeHttpsURLConnection(
        url: URL,
        private val observedResponseCode: Int,
        private val body: ByteArray,
        private val headers: Map<String, String> = emptyMap(),
    ) : HttpsURLConnection(url) {
        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = observedResponseCode

        override fun getInputStream() = ByteArrayInputStream(body)

        override fun getContentLengthLong(): Long =
            if (observedResponseCode == 200) body.size.toLong() else -1

        override fun getHeaderField(name: String?): String? = headers[name]

        override fun getCipherSuite(): String = "TLS_FAKE"

        override fun getLocalCertificates(): Array<Certificate>? = null

        override fun getServerCertificates(): Array<Certificate> = emptyArray()

        override fun getPeerPrincipal(): Principal? = null

        override fun getLocalPrincipal(): Principal? = null
    }
}
