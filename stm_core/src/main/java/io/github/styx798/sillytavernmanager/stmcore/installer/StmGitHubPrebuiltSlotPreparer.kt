package io.github.styx798.sillytavernmanager.stmcore.installer

import io.github.styx798.sillytavernmanager.stmcore.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

internal data class StmPrebuiltRuntimeCatalogEntry(
    val repository: String,
    val commitSha: String,
    val stVersion: String,
    val packageLockSha256: String,
    val downloadUrl: String,
    val archiveBytes: Long,
    val archiveSha256: String,
    val archiveRoot: String,
    val signingKeyId: String,
    val publicKeyDerBase64: String,
    val deviceNodeVersion: String,
    val deviceJavetCoordinate: String,
    val deviceAbi: String,
)

internal object StmPrebuiltRuntimeCatalog {
    fun find(request: StmRuntimeSlotPreparationRequest): StmPrebuiltRuntimeCatalogEntry? =
        ST_1_18_0.takeIf { entry ->
            request.repository == entry.repository &&
                request.commitSha.equals(entry.commitSha, ignoreCase = true) &&
                request.stVersion == entry.stVersion &&
                request.packageLockSha256.equals(entry.packageLockSha256, ignoreCase = true) &&
                entry.deviceJavetCoordinate ==
                "com.caoccao.javet:${BuildConfig.JAVET_ARTIFACT}:$JAVET_VERSION"
        }

    val ST_1_18_0 = StmPrebuiltRuntimeCatalogEntry(
        repository = "https://github.com/SillyTavern/SillyTavern",
        commitSha = "8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8",
        stVersion = "1.18.0",
        packageLockSha256 =
            "7484f87e7dc6e99044ad532b80111c3e93463aaf1d5dbe377b3a4486bfe65f6f",
        downloadUrl =
            "https://github.com/Styx798/SillyTavern-Manager/releases/download/" +
                "st-runtime-v1.18.0-rc.1/" +
                "stm-st-1.18.0-arm64-v8a-20260725.2.stmsupply.zip",
        archiveBytes = 102_958_786L,
        archiveSha256 =
            "439772a250a21094dcb46029b4edf528cb58b72b3b685f63e27019c03220eb73",
        archiveRoot = "stm-st-1.18.0-arm64-v8a",
        signingKeyId = "stm-dependency-release-2026-01",
        publicKeyDerBase64 = "MCowBQYDK2VwAyEA826aGzjapllXUewDaCa2YoaUiYTzdCdOGKHjC8Z5gXA=",
        deviceNodeVersion = "v24.17.0",
        deviceJavetCoordinate =
            "com.caoccao.javet:javet-node-android-i18n:$JAVET_VERSION",
        deviceAbi = "arm64-v8a",
    )

    private const val JAVET_VERSION = "5.0.9"
}

internal sealed interface StmRuntimeLayerDownloadResult {
    data class Downloaded(
        val file: File,
        val bytes: Long,
        val sha256: String,
    ) : StmRuntimeLayerDownloadResult

    data class Unavailable(val detail: String) : StmRuntimeLayerDownloadResult

    data class Rejected(val detail: String) : StmRuntimeLayerDownloadResult
}

internal fun interface StmRuntimeLayerDownloader {
    fun download(
        entry: StmPrebuiltRuntimeCatalogEntry,
        destination: File,
        cancellation: StmExtractionCancellation,
    ): StmRuntimeLayerDownloadResult
}

internal fun interface StmRuntimeLayerArchiveInstaller {
    fun install(
        entry: StmPrebuiltRuntimeCatalogEntry,
        request: StmRuntimeSlotPreparationRequest,
        archive: File,
        cancellation: StmExtractionCancellation,
    ): StmRuntimeSlotAdmissionEvidence
}

internal fun interface StmSignedPrebuiltSupplyInstaller {
    fun integrate(
        entry: StmPrebuiltRuntimeCatalogEntry,
        request: StmRuntimeSlotPreparationRequest,
        supplyRoot: File,
        dependencyExtractionRoot: File,
        cancellation: StmExtractionCancellation,
    ): StmRuntimeSlotAdmissionEvidence
}

/**
 * Production ST runtime preparation policy.
 *
 * An exact catalog hit first tries the immutable signed runtime layer. Only transport-level
 * unavailability falls back to the fixed device-local npm path. A downloaded asset that violates
 * its content identity, archive boundary, signature, source binding, runtime binding, inventory or
 * assembled-tree identity fails closed and never triggers a local build.
 */
internal class StmGitHubPrebuiltSlotPreparer(
    private val localFallback: StmRuntimeSlotPreparer,
    private val runnableAcceptor: StmRuntimeSlotRunnableAcceptor,
    private val catalogLookup: (StmRuntimeSlotPreparationRequest) ->
        StmPrebuiltRuntimeCatalogEntry? = StmPrebuiltRuntimeCatalog::find,
    private val downloader: StmRuntimeLayerDownloader = StmGitHubRuntimeLayerDownloader(),
    private val archiveInstaller: StmRuntimeLayerArchiveInstaller =
        StmDefaultRuntimeLayerArchiveInstaller(),
) : StmRuntimeSlotPreparer {
    override fun prepare(
        request: StmRuntimeSlotPreparationRequest,
        cancellation: StmExtractionCancellation,
        onPhase: (StmRuntimeSlotPreparationPhase) -> Unit,
    ): StmRuntimeSlotAdmissionEvidence {
        val entry = catalogLookup(request)
            ?: return localFallback.prepare(request, cancellation, onPhase)
        throwIfCancelled(cancellation)
        val download = File(request.operationRoot, DOWNLOAD_FILE)
        check(download.parentFile == request.operationRoot && !download.exists()) {
            "Runtime-layer download path is not a fresh installer child"
        }

        try {
            onPhase(StmRuntimeSlotPreparationPhase.DOWNLOADING_RUNTIME_LAYER)
            when (val outcome = downloader.download(entry, download, cancellation)) {
                is StmRuntimeLayerDownloadResult.Unavailable -> {
                    deleteRegularNoFollow(download)
                    throwIfCancelled(cancellation)
                    return localFallback.prepare(request, cancellation, onPhase)
                }

                is StmRuntimeLayerDownloadResult.Rejected ->
                    throw StmRuntimeSlotPreparationException(
                        StmRuntimeSlotPreparationErrorCode.PREBUILT_RUNTIME_REJECTED,
                        "Prebuilt runtime download was rejected: ${outcome.detail.safeDetail()}",
                    )

                is StmRuntimeLayerDownloadResult.Downloaded -> {
                    check(outcome.file.toPath().toAbsolutePath().normalize() ==
                        download.toPath().toAbsolutePath().normalize()) {
                        "Runtime-layer downloader returned an unexpected path"
                    }
                    check(
                        outcome.bytes == entry.archiveBytes &&
                            outcome.sha256 == entry.archiveSha256
                    ) {
                        "Runtime-layer downloader returned an unbound identity"
                    }
                }
            }
            throwIfCancelled(cancellation)

            val evidence = try {
                onPhase(StmRuntimeSlotPreparationPhase.VERIFYING_RUNTIME_LAYER)
                archiveInstaller.install(entry, request, download, cancellation)
            } catch (error: StmRuntimeSlotPreparationException) {
                throw error
            } catch (error: Exception) {
                throw StmRuntimeSlotPreparationException(
                    StmRuntimeSlotPreparationErrorCode.PREBUILT_RUNTIME_INTEGRATION_FAILED,
                    "Signed runtime-layer integration failed: ${error.safePrebuiltDetail()}",
                    error,
                )
            }

            val bundle = evidence.runtimeFiles[
                StmRuntimeSlotAdmissionEvidence.BUNDLE_FILE
            ] ?: throw StmRuntimeSlotPreparationException(
                StmRuntimeSlotPreparationErrorCode.PREBUILT_RUNTIME_INTEGRATION_FAILED,
                "Signed runtime-layer evidence did not bind lib.js",
            )
            try {
                onPhase(StmRuntimeSlotPreparationPhase.RUNNABLE_ACCEPTANCE)
                runnableAcceptor.accept(request, bundle, cancellation)
            } catch (error: StmRuntimeSlotPreparationException) {
                throw error
            } catch (error: Exception) {
                throw StmRuntimeSlotPreparationException(
                    StmRuntimeSlotPreparationErrorCode.RUNNABLE_ACCEPTANCE_FAILED,
                    "Signed runtime-layer runnable acceptance failed: " +
                        error.safePrebuiltDetail(),
                    error,
                )
            }
            return evidence
        } finally {
            deleteRegularNoFollow(download)
        }
    }

    private fun throwIfCancelled(cancellation: StmExtractionCancellation) {
        if (cancellation.isCancelled()) {
            throw StmRuntimeSlotPreparationException(
                StmRuntimeSlotPreparationErrorCode.OPERATION_CANCELLED,
                "Runtime-layer preparation was cancelled",
            )
        }
    }

    private fun deleteRegularNoFollow(file: File) {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(path)) {
            "Runtime-layer cleanup target is not a regular file"
        }
        Files.delete(path)
    }

    private companion object {
        const val DOWNLOAD_FILE = "prebuilt-runtime-layer.part"
    }
}

internal class StmGitHubRuntimeLayerDownloader(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
    private val overallTimeoutMillis: Long = 10L * 60L * 1000L,
    private val connectionFactory: (URL) -> HttpsURLConnection = { url ->
        url.openConnection() as HttpsURLConnection
    },
) : StmRuntimeLayerDownloader {
    override fun download(
        entry: StmPrebuiltRuntimeCatalogEntry,
        destination: File,
        cancellation: StmExtractionCancellation,
    ): StmRuntimeLayerDownloadResult {
        val destinationPath = destination.toPath().toAbsolutePath().normalize()
        val parent = destinationPath.parent
            ?: return StmRuntimeLayerDownloadResult.Rejected(
                "Download destination has no parent",
            )
        if (!isRealDirectory(parent) ||
            Files.exists(destinationPath, LinkOption.NOFOLLOW_LINKS)
        ) {
            return StmRuntimeLayerDownloadResult.Rejected(
                "Download destination is unsafe or already exists",
            )
        }
        val initial = runCatching { URI(entry.downloadUrl) }.getOrNull()
            ?: return StmRuntimeLayerDownloadResult.Rejected("Release URL is malformed")
        if (!isAllowedUrl(initial, initialRequest = true)) {
            return StmRuntimeLayerDownloadResult.Rejected("Release URL is outside the allowlist")
        }

        val deadline = System.nanoTime() +
            overallTimeoutMillis.coerceAtMost(Long.MAX_VALUE / 1_000_000L) * 1_000_000L
        var current = initial
        var redirects = 0
        while (true) {
            if (cancellation.isCancelled()) {
                return cancelled(destinationPath)
            }
            if (System.nanoTime() >= deadline) {
                return unavailable(destinationPath, "Release download exceeded its time budget")
            }
            val connection = try {
                connectionFactory(current.toURL()).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = connectTimeoutMillis
                    readTimeout = readTimeoutMillis
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("User-Agent", "SillyTavern-Manager/${BuildConfig.STM_CORE_VERSION}")
                    useCaches = false
                }
            } catch (error: Exception) {
                return unavailable(destinationPath, "Release connection failed")
            }
            try {
                val code = try {
                    connection.responseCode
                } catch (_: IOException) {
                    return unavailable(destinationPath, "Release response was unavailable")
                }
                when {
                    code == HttpURLConnection.HTTP_OK ->
                        return receiveBody(
                            connection = connection,
                            entry = entry,
                            destination = destinationPath,
                            cancellation = cancellation,
                            deadlineNanos = deadline,
                        )

                    code in REDIRECT_CODES -> {
                        if (++redirects > MAX_REDIRECTS) {
                            return rejected(destinationPath, "Release redirected too many times")
                        }
                        val location = connection.getHeaderField("Location")
                            ?: return rejected(
                                destinationPath,
                                "Release redirect omitted Location",
                            )
                        val redirected = runCatching { current.resolve(location) }.getOrNull()
                            ?: return rejected(
                                destinationPath,
                                "Release redirect URL was malformed",
                            )
                        if (redirected.host.equals("github.com", ignoreCase = true) &&
                            (
                                redirected.path == "/login" ||
                                    redirected.path.startsWith("/login/")
                                )
                        ) {
                            return unavailable(
                                destinationPath,
                                "Release asset requires authentication",
                            )
                        }
                        if (!isAllowedUrl(redirected, initialRequest = false)) {
                            return rejected(
                                destinationPath,
                                "Release redirect left the HTTPS GitHub asset boundary",
                            )
                        }
                        current = redirected
                    }

                    code in UNAVAILABLE_CODES || code in 500..599 ->
                        return unavailable(
                            destinationPath,
                            "Release asset is unavailable (HTTP $code)",
                        )

                    else ->
                        return rejected(
                            destinationPath,
                            "Release server returned unexpected HTTP $code",
                        )
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun receiveBody(
        connection: HttpsURLConnection,
        entry: StmPrebuiltRuntimeCatalogEntry,
        destination: Path,
        cancellation: StmExtractionCancellation,
        deadlineNanos: Long,
    ): StmRuntimeLayerDownloadResult {
        val declaredLength = connection.contentLengthLong
        if (declaredLength >= 0 && declaredLength != entry.archiveBytes) {
            return rejected(destination, "Release Content-Length did not match the catalog")
        }
        val input = try {
            connection.inputStream
        } catch (_: IOException) {
            return unavailable(destination, "Release body was unavailable")
        }
        val digest = MessageDigest.getInstance(SHA256)
        var total = 0L
        try {
            try {
                Files.createFile(destination)
            } catch (_: IOException) {
                return rejected(destination, "Release download file could not be created")
            }
            FileOutputStream(destination.toFile()).use { output ->
                input.use { stream ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        if (cancellation.isCancelled()) return cancelled(destination)
                        if (System.nanoTime() >= deadlineNanos) {
                            return unavailable(
                                destination,
                                "Release download exceeded its time budget",
                            )
                        }
                        val count = try {
                            stream.read(buffer)
                        } catch (_: IOException) {
                            return unavailable(destination, "Release download was interrupted")
                        }
                        if (count < 0) break
                        if (count == 0) continue
                        total = try {
                            Math.addExact(total, count.toLong())
                        } catch (_: ArithmeticException) {
                            return rejected(destination, "Release download length overflowed")
                        }
                        if (total > entry.archiveBytes) {
                            return rejected(destination, "Release download exceeded catalog length")
                        }
                        try {
                            output.write(buffer, 0, count)
                        } catch (_: IOException) {
                            return rejected(destination, "Release download could not be stored")
                        }
                        digest.update(buffer, 0, count)
                    }
                }
                try {
                    output.fd.sync()
                } catch (_: IOException) {
                    return rejected(destination, "Release download could not be synced")
                }
            }
        } finally {
            runCatching { input.close() }
        }
        val observedSha = digest.digest().toHex()
        if (total != entry.archiveBytes || observedSha != entry.archiveSha256) {
            return rejected(destination, "Release content identity did not match the catalog")
        }
        return StmRuntimeLayerDownloadResult.Downloaded(
            file = destination.toFile(),
            bytes = total,
            sha256 = observedSha,
        )
    }

    private fun isAllowedUrl(uri: URI, initialRequest: Boolean): Boolean {
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.userInfo != null ||
            uri.fragment != null ||
            (uri.port != -1 && uri.port != 443) ||
            uri.host.isNullOrBlank()
        ) {
            return false
        }
        val host = uri.host.lowercase(Locale.ROOT)
        return if (initialRequest) {
            host == "github.com" &&
                uri.path.startsWith(
                    "/Styx798/SillyTavern-Manager/releases/download/",
                )
        } else {
            (
                host == "github.com" &&
                    uri.path.startsWith(
                        "/Styx798/SillyTavern-Manager/releases/download/",
                    )
                ) ||
                host == "objects.githubusercontent.com" ||
                host == "release-assets.githubusercontent.com" ||
                host == "github-releases.githubusercontent.com"
        }
    }

    private fun isRealDirectory(path: Path): Boolean =
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)

    private fun cancelled(path: Path): StmRuntimeLayerDownloadResult {
        deletePartial(path)
        throw StmRuntimeSlotPreparationException(
            StmRuntimeSlotPreparationErrorCode.OPERATION_CANCELLED,
            "Runtime-layer download was cancelled",
        )
    }

    private fun unavailable(path: Path, detail: String): StmRuntimeLayerDownloadResult {
        deletePartial(path)
        return StmRuntimeLayerDownloadResult.Unavailable(detail)
    }

    private fun rejected(path: Path, detail: String): StmRuntimeLayerDownloadResult {
        deletePartial(path)
        return StmRuntimeLayerDownloadResult.Rejected(detail)
    }

    private fun deletePartial(path: Path) {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(path)
        ) {
            runCatching { Files.deleteIfExists(path) }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val SHA256 = "SHA-256"
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val UNAVAILABLE_CODES = setOf(401, 403, 404, 408, 410, 425, 429)
    }
}

internal class StmDefaultRuntimeLayerArchiveInstaller(
    private val zipExtractor: StmSafeZipExtractor = StmSafeZipExtractor(),
    private val supplyInstaller: StmSignedPrebuiltSupplyInstaller =
        defaultSignedSupplyInstaller(),
) : StmRuntimeLayerArchiveInstaller {
    override fun install(
        entry: StmPrebuiltRuntimeCatalogEntry,
        request: StmRuntimeSlotPreparationRequest,
        archive: File,
        cancellation: StmExtractionCancellation,
    ): StmRuntimeSlotAdmissionEvidence {
        requireArchiveIdentity(archive, entry, cancellation)
        val outerOperation = File(request.operationRoot, OUTER_EXTRACTION_DIRECTORY)
        check(outerOperation.parentFile == request.operationRoot && !outerOperation.exists()) {
            "Outer runtime-layer extraction path is not a fresh installer child"
        }
        try {
            val extraction = zipExtractor.extract(
                artifact = archive,
                operationStagingRoot = outerOperation,
                cancellation = cancellation,
            )
            val payload = extraction.payloadDirectory.toPath().toRealPath()
            val children = Files.list(payload).use { stream ->
                stream.iterator().asSequence().toList()
            }
            check(children.size == 1) {
                "Runtime-layer archive must contain exactly one root"
            }
            val supply = children.single()
            check(
                supply.fileName.toString() == entry.archiveRoot &&
                    Files.isDirectory(supply, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(supply) &&
                    supply.parent == payload
            ) {
                "Runtime-layer archive root did not match the catalog"
            }
            return supplyInstaller.integrate(
                entry = entry,
                request = request,
                supplyRoot = supply.toFile(),
                dependencyExtractionRoot = File(
                    request.operationRoot,
                    DEPENDENCY_EXTRACTION_DIRECTORY,
                ),
                cancellation = cancellation,
            )
        } finally {
            deleteTreeNoFollow(outerOperation.toPath())
        }
    }

    private fun requireArchiveIdentity(
        archive: File,
        entry: StmPrebuiltRuntimeCatalogEntry,
        cancellation: StmExtractionCancellation,
    ) {
        val path = archive.toPath()
        check(
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(path)
        ) {
            "Runtime-layer archive is not a regular file"
        }
        check(Files.size(path) == entry.archiveBytes) {
            "Runtime-layer archive length did not match the catalog"
        }
        val digest = MessageDigest.getInstance(SHA256)
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                if (cancellation.isCancelled()) {
                    throw StmRuntimeSlotPreparationException(
                        StmRuntimeSlotPreparationErrorCode.OPERATION_CANCELLED,
                        "Runtime-layer verification was cancelled",
                    )
                }
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().toHex() == entry.archiveSha256) {
            "Runtime-layer archive SHA-256 did not match the catalog"
        }
    }

    private fun deleteTreeNoFollow(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                directory: Path,
                error: IOException?,
            ): FileVisitResult {
                error?.let { throw it }
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }

    private companion object {
        const val OUTER_EXTRACTION_DIRECTORY = "prebuilt-runtime-extraction"
        const val DEPENDENCY_EXTRACTION_DIRECTORY = "prebuilt-dependency-extraction"
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val SHA256 = "SHA-256"

        fun defaultSignedSupplyInstaller(): StmSignedPrebuiltSupplyInstaller {
            val entry = StmPrebuiltRuntimeCatalog.ST_1_18_0
            val publicKey = StmEncodedEd25519PublicKey(
                Base64.getDecoder().decode(entry.publicKeyDerBase64),
            )
            val integrator = StmSignedPrebuiltSlotIntegrator(
                trustedKeyResolver = { keyId ->
                    publicKey.takeIf { keyId == entry.signingKeyId }
                },
            )
            return StmSignedPrebuiltSupplyInstaller {
                    requestedEntry,
                    request,
                    supplyRoot,
                    dependencyExtractionRoot,
                    cancellation,
                ->
                check(requestedEntry.signingKeyId == entry.signingKeyId) {
                    "Runtime-layer catalog selected an untrusted signing key"
                }
                integrator.integrate(
                    payloadDirectory = request.payloadDirectory,
                    archiveRoot = request.archiveRoot,
                    supplyRoot = supplyRoot,
                    dependencyExtractionRoot = dependencyExtractionRoot,
                    expectedSource = StmDependencySourceBinding(
                        repository = requestedEntry.repository,
                        commitSha = requestedEntry.commitSha,
                        packageLockSha256 = requestedEntry.packageLockSha256,
                    ),
                    expectedRuntime = StmDependencyRuntimeBinding(
                        nodeVersion = requestedEntry.deviceNodeVersion,
                        javetCoordinate = requestedEntry.deviceJavetCoordinate,
                        abi = requestedEntry.deviceAbi,
                    ),
                    cancellation = cancellation,
                ).runtimeEvidence
            }
        }
    }
}

internal class StmEncodedEd25519PublicKey(encoded: ByteArray) : PublicKey {
    private val encodedBytes = encoded.copyOf()

    override fun getAlgorithm(): String = "Ed25519"

    override fun getFormat(): String = "X.509"

    override fun getEncoded(): ByteArray = encodedBytes.copyOf()

    private companion object {
        private const val serialVersionUID = 1L
    }
}

private fun Throwable.safePrebuiltDetail(): String =
    (message ?: javaClass.simpleName)
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .ifBlank { javaClass.simpleName }
        .take(1_000)

private fun String.safeDetail(): String =
    lineSequence().firstOrNull().orEmpty().ifBlank { "unspecified" }.take(1_000)
