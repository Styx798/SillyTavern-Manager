package io.github.styx798.sillytavernmanager.stmcore.installer

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
    fun `transport unavailability stops fast install without invoking local npm`() {
        val entry = testEntry(bytes = 1, sha256 = "0".repeat(64))
        val fallbackCalls = AtomicInteger()
        val preparer = StmGitHubPrebuiltSlotPreparer(
            localPreparer = StmRuntimeSlotPreparer { _, _, _ ->
                fallbackCalls.incrementAndGet()
                evidence()
            },
            runnableAcceptor = StmRuntimeSlotRunnableAcceptor { _, _, _, _ ->
                error("Unavailable downloads must not run prebuilt acceptance")
            },
            catalogLookup = { entry },
            downloader = StmRuntimeLayerDownloader { _, _, _, _ ->
                StmRuntimeLayerDownloadResult.Unavailable("offline")
            },
            archiveInstaller = StmRuntimeLayerArchiveInstaller { _, _, _, _ ->
                error("Unavailable downloads must not enter integration")
            },
        )
        val phases = mutableListOf<StmRuntimeSlotPreparationPhase>()

        val error = runCatching {
            preparer.prepare(request(), StmExtractionCancellation.NONE, phases::add)
        }.exceptionOrNull() as? StmRuntimeSlotPreparationException

        assertNotNull(error)
        assertEquals(
            StmRuntimeSlotPreparationErrorCode.PREBUILT_RUNTIME_TRANSPORT_UNAVAILABLE,
            error?.code,
        )
        assertEquals(0, fallbackCalls.get())
        assertEquals(
            listOf(StmRuntimeSlotPreparationPhase.DOWNLOADING_RUNTIME_LAYER),
            phases,
        )
    }

    @Test
    fun `missing prebuilt identity stops fast install without invoking local npm`() {
        val localCalls = AtomicInteger()
        val preparer = StmGitHubPrebuiltSlotPreparer(
            localPreparer = StmRuntimeSlotPreparer { _, _, _ ->
                localCalls.incrementAndGet()
                evidence()
            },
            runnableAcceptor = StmRuntimeSlotRunnableAcceptor { _, _, _, _ -> },
            catalogLookup = { null },
            downloader = StmRuntimeLayerDownloader { _, _, _, _ ->
                error("A missing catalog entry must not download")
            },
        )

        val error = runCatching {
            preparer.prepare(request(), StmExtractionCancellation.NONE) {}
        }.exceptionOrNull() as? StmRuntimeSlotPreparationException

        assertNotNull(error)
        assertEquals(
            StmRuntimeSlotPreparationErrorCode.PREBUILT_RUNTIME_NOT_AVAILABLE,
            error?.code,
        )
        assertEquals(0, localCalls.get())
    }

    @Test
    fun `explicit local mode invokes npm without catalog lookup or download`() {
        val localCalls = AtomicInteger()
        val localEvidence = evidence()
        val preparer = StmGitHubPrebuiltSlotPreparer(
            localPreparer = StmRuntimeSlotPreparer { actual, _, _ ->
                assertEquals(
                    io.github.styx798.sillytavernmanager.stmcore
                        .StmCoreInstallMode.LOCAL_NPM_BUILD,
                    actual.installMode,
                )
                localCalls.incrementAndGet()
                localEvidence
            },
            runnableAcceptor = StmRuntimeSlotRunnableAcceptor { _, _, _, _ ->
                error("Local mode owns its own runnable acceptance")
            },
            catalogLookup = { error("Local mode must not inspect the prebuilt catalog") },
            downloader = StmRuntimeLayerDownloader { _, _, _, _ ->
                error("Local mode must not download a prebuilt runtime")
            },
        )

        val actual = preparer.prepare(
            request().copy(
                installMode = io.github.styx798.sillytavernmanager.stmcore
                    .StmCoreInstallMode.LOCAL_NPM_BUILD,
            ),
            StmExtractionCancellation.NONE,
        ) {}

        assertEquals(localEvidence, actual)
        assertEquals(1, localCalls.get())
    }

    @Test
    fun `rejected download fails closed without local fallback`() {
        val entry = testEntry(bytes = 1, sha256 = "0".repeat(64))
        val fallbackCalled = AtomicBoolean(false)
        val preparer = StmGitHubPrebuiltSlotPreparer(
            localPreparer = StmRuntimeSlotPreparer { _, _, _ ->
                fallbackCalled.set(true)
                evidence()
            },
            runnableAcceptor = StmRuntimeSlotRunnableAcceptor { _, _, _, _ -> },
            catalogLookup = { entry },
            downloader = StmRuntimeLayerDownloader { _, _, _, _ ->
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
        var acceptanceProgramPolicy: StmRunnableAcceptanceProgramPolicy? = null
        val preparedEvidence = evidence()
        val preparer = StmGitHubPrebuiltSlotPreparer(
            localPreparer = StmRuntimeSlotPreparer { _, _, _ ->
                fallbackCalled.set(true)
                evidence()
            },
            runnableAcceptor = StmRuntimeSlotRunnableAcceptor { _, bundle, _, programPolicy ->
                acceptanceCalls.incrementAndGet()
                acceptanceProgramPolicy = programPolicy
                assertEquals(
                    preparedEvidence.runtimeFiles.getValue(
                        StmRuntimeSlotAdmissionEvidence.BUNDLE_FILE,
                    ),
                    bundle,
                )
            },
            catalogLookup = { entry },
            downloader = StmRuntimeLayerDownloader { _, destination, _, onProgress ->
                destination.writeBytes(bytes)
                onProgress(bytes.size.toLong(), bytes.size.toLong(), bytes.size.toLong())
                StmRuntimeLayerDownloadResult.Downloaded(
                    destination,
                    bytes.size.toLong(),
                    bytes.sha256(),
                )
            },
            archiveInstaller = StmRuntimeLayerArchiveInstaller { _, _, archive, _ ->
                installerCalls.incrementAndGet()
                assertTrue(archive.file.isFile)
                assertEquals(bytes.size.toLong(), archive.bytes)
                assertEquals(bytes.sha256(), archive.sha256)
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
            StmRunnableAcceptanceProgramPolicy.SIGNED_ARCHIVE_BOUND,
            acceptanceProgramPolicy,
        )
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
                archive = StmRuntimeLayerDownloadResult.Downloaded(
                    archive,
                    archive.length(),
                    archive.readBytes().sha256(),
                ),
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
                archive = StmRuntimeLayerDownloadResult.Downloaded(
                    archive,
                    archive.length(),
                    archive.readBytes().sha256(),
                ),
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
        val progress = mutableListOf<Triple<Long, Long, Long>>()

        val result = downloader.download(
            entry,
            destination,
            StmExtractionCancellation.NONE,
            { transferred, total, speed -> progress += Triple(transferred, total, speed) },
        )

        assertTrue(result is StmRuntimeLayerDownloadResult.Downloaded)
        result as StmRuntimeLayerDownloadResult.Downloaded
        assertEquals(body.size.toLong(), result.bytes)
        assertEquals(body.sha256(), result.sha256)
        assertTrue(destination.readBytes().contentEquals(body))
        assertEquals(0L, progress.first().first)
        assertEquals(body.size.toLong(), progress.last().first)
        assertTrue(progress.all { it.second == body.size.toLong() && it.third >= 0L })
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
            { _, _, _ -> },
        )

        assertTrue(result is StmRuntimeLayerDownloadResult.Unavailable)
        assertFalse(destination.exists())
    }

    @Test
    fun `HTTPS downloader retries transient response failures before receiving bytes`() {
        val body = "eventual release response".toByteArray()
        val entry = testEntry(body.size.toLong(), body.sha256())
        val destination = File(temporaryFolder.newFolder("download-retry"), "runtime.part")
        val attempts = AtomicInteger()
        val waits = mutableListOf<Long>()
        val downloader = StmGitHubRuntimeLayerDownloader(
            maxResponseAttempts = 3,
            retryBaseDelayMillis = 25,
            retryWait = waits::add,
            connectionFactory = { url ->
                val attempt = attempts.incrementAndGet()
                FakeHttpsURLConnection(
                    url = url,
                    observedResponseCode = 200,
                    body = body,
                    responseFailure = SocketTimeoutException("sensitive local detail")
                        .takeIf { attempt < 3 },
                )
            },
        )

        val result = downloader.download(
            entry,
            destination,
            StmExtractionCancellation.NONE,
            { _, _, _ -> },
        )

        assertTrue(result is StmRuntimeLayerDownloadResult.Downloaded)
        assertEquals(3, attempts.get())
        assertEquals(listOf(25L, 50L), waits)
        assertTrue(destination.readBytes().contentEquals(body))
    }

    @Test
    fun `HTTPS downloader reports only failure type host and bounded attempts`() {
        val entry = testEntry(1, "0".repeat(64)).copy(
            downloadUrl = StmPrebuiltRuntimeCatalog.ST_1_18_0.downloadUrl +
                "?temporary-secret=must-not-leak",
        )
        val destination = File(temporaryFolder.newFolder("download-diagnostic"), "runtime.part")
        val attempts = AtomicInteger()
        val downloader = StmGitHubRuntimeLayerDownloader(
            maxResponseAttempts = 3,
            retryBaseDelayMillis = 0,
            retryWait = {},
            connectionFactory = { url ->
                attempts.incrementAndGet()
                FakeHttpsURLConnection(
                    url = url,
                    observedResponseCode = 200,
                    body = ByteArray(0),
                    responseFailure = UnknownHostException("private resolver message"),
                )
            },
        )

        val result = downloader.download(
            entry,
            destination,
            StmExtractionCancellation.NONE,
            { _, _, _ -> },
        )

        assertTrue(result is StmRuntimeLayerDownloadResult.Unavailable)
        result as StmRuntimeLayerDownloadResult.Unavailable
        assertEquals(3, attempts.get())
        assertTrue(result.detail.contains("github.com"))
        assertTrue(result.detail.contains("UnknownHostException"))
        assertTrue(result.detail.contains("3 attempt(s)"))
        assertFalse(result.detail.contains("temporary-secret"))
        assertFalse(result.detail.contains("private resolver message"))
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
            { _, _, _ -> },
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
        private val responseFailure: IOException? = null,
    ) : HttpsURLConnection(url) {
        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int =
            responseFailure?.let { throw it } ?: observedResponseCode

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
