package io.github.styx798.sillytavernmanager.stmcore.testing

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import io.github.styx798.sillytavernmanager.stmcore.BuildConfig
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactIntegrity
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactTrust
import io.github.styx798.sillytavernmanager.stmcore.StmCorePaths
import io.github.styx798.sillytavernmanager.stmcore.installer.ArtifactIdentity
import io.github.styx798.sillytavernmanager.stmcore.installer.ArtifactIntegrityResult
import io.github.styx798.sillytavernmanager.stmcore.installer.ArtifactKind
import io.github.styx798.sillytavernmanager.stmcore.installer.StmArtifactVerifier
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencyRuntimeBinding
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencySourceBinding
import io.github.styx798.sillytavernmanager.stmcore.installer.StmExtractionCancellation
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSafeZipExtractor
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSignedPrebuiltSlotIntegrator
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSillyTavernSourceInspectionResult
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSillyTavernSourceInspector
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSlotCommitOutcome
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSlotCommitRequest
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSlotDeleteResult
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSlotPayloadKind
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSlotStore
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSlotVerificationResult
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only end-to-end Stage 3B gate.
 *
 * It imports the already downloaded exact-commit SillyTavern source ZIP, assembles the signed
 * prebuilt dependency supply inside Core-owned staging, and commits one immutable READY slot.
 * The active-slot pointer is intentionally never changed.
 */
internal class StmCoreGate3bReadySlotExperiment(
    context: Context,
    private val retainCommittedSlot: Boolean = true,
) : StmCoreGate3bExperimentRunner {
    private val appContext = context.applicationContext
    private val cancelled = AtomicBoolean(false)

    override fun cancel() {
        cancelled.set(true)
    }

    override fun run(): Map<String, String> {
        val started = SystemClock.elapsedRealtime()
        val sampler = Gate3bMemorySampler().also(Gate3bMemorySampler::start)
        val operationId = UUID.randomUUID().toString()
        val slotId = if (retainCommittedSlot) {
            RETAINED_SLOT_ID
        } else {
            "st-1-18-0-cold-${operationId.take(8)}"
        }
        var operationRoot: File? = null
        var verifiedSource: File? = null
        var cleanup = "not_attempted"
        var sourceEntryCount = 0
        var sourceBytes = 0L
        var slotManifestSha256 = ""
        var slotEntryCount = 0
        var slotBytes = 0L
        var dependencyTreeSha256 = ""
        var programTreeSha256 = ""
        var supplyManifestSha256 = ""
        var outcome = ""
        var failure = ""

        try {
            StmCorePaths.initializeCoreLayout(appContext)
            val slotsRoot = StmCorePaths.slotsRoot(appContext)
            val stagingRoot = StmCorePaths.stagingRoot(appContext)
            val installerCache = StmCorePaths.installerCacheRoot(appContext)
            val store = StmSlotStore(slotsRoot, stagingRoot)
            val existing = store.verifyCommitted(slotId)
            check(existing is StmSlotVerificationResult.Missing) {
                "The fixed debug READY slot already exists; this gate never replaces a slot"
            }
            val activeBefore = controlFileIdentity(StmCorePaths.activeSlotFile(appContext))

            val sourceArchive = requireSourceArchive()
            val identity = ArtifactIdentity(
                repository = ST_REPOSITORY,
                commitSha = ST_COMMIT,
                archiveSha256 = SOURCE_ARCHIVE_SHA256,
                archiveLength = SOURCE_ARCHIVE_BYTES,
                downloadUrl = SOURCE_DOWNLOAD_URL,
                kind = ArtifactKind.UPSTREAM_SOURCE_ARCHIVE,
            )
            verifiedSource = File(
                installerCache,
                "stage3b-ready-$operationId.verified.zip",
            )
            val verified = sourceArchive.inputStream().use { input ->
                StmArtifactVerifier().verifyAndCopy(identity, input, verifiedSource)
            }
            check(verified is ArtifactIntegrityResult.Verified) {
                val rejected = verified as ArtifactIntegrityResult.Rejected
                "${rejected.code}:${rejected.detail}"
            }

            operationRoot = File(stagingRoot, operationId)
            val extraction = StmSafeZipExtractor().extract(
                artifact = verified.protectedTemporaryFile,
                operationStagingRoot = operationRoot,
                cancellation = StmExtractionCancellation(cancelled::get),
            )
            sourceEntryCount = extraction.fileCount + extraction.directoryCount
            sourceBytes = extraction.totalFileBytes
            val inspection = StmSillyTavernSourceInspector().inspect(
                payloadDirectory = extraction.payloadDirectory,
                expectedExactCommit = ST_COMMIT,
            )
            check(inspection is StmSillyTavernSourceInspectionResult.Accepted) {
                val rejected = inspection as StmSillyTavernSourceInspectionResult.Rejected
                "${rejected.code}:${rejected.detail}"
            }
            val source = inspection.evidence
            check(source.stVersion == ST_VERSION) {
                "Unexpected SillyTavern version ${source.stVersion}"
            }
            check(source.packageLockSha256 == PACKAGE_LOCK_SHA256) {
                "Downloaded source package-lock does not match the signed supply"
            }

            val publicKey = EncodedEd25519PublicKey(
                Base64.getDecoder().decode(DEBUG_PUBLIC_KEY_DER_BASE64),
            )
            val integration = StmSignedPrebuiltSlotIntegrator(
                trustedKeyResolver = { keyId ->
                    publicKey.takeIf { keyId == DEBUG_SIGNING_KEY_ID }
                },
            ).integrate(
                payloadDirectory = extraction.payloadDirectory,
                archiveRoot = source.archiveRoot,
                supplyRoot = requireSupplyRoot(),
                dependencyExtractionRoot = File(operationRoot, DEPENDENCY_OPERATION_DIRECTORY),
                expectedSource = StmDependencySourceBinding(
                    repository = ST_REPOSITORY,
                    commitSha = ST_COMMIT,
                    packageLockSha256 = source.packageLockSha256,
                ),
                expectedRuntime = StmDependencyRuntimeBinding(
                    nodeVersion = DEVICE_NODE_VERSION,
                    javetCoordinate = javetCoordinate(),
                    abi = DEVICE_ABI,
                ),
                cancellation = StmExtractionCancellation(cancelled::get),
            )
            supplyManifestSha256 = integration.canonicalManifestSha256
            dependencyTreeSha256 = integration.dependencyTreeSha256
            programTreeSha256 = integration.postAdapterProgramTreeSha256

            val commit = store.prepareAndCommit(
                StmSlotCommitRequest(
                    operationId = operationId,
                    slotId = slotId,
                    slotRevision = SLOT_REVISION,
                    payloadKind = StmSlotPayloadKind.SILLY_TAVERN_SOURCE,
                    repository = ST_REPOSITORY,
                    channel = "release",
                    commitSha = ST_COMMIT,
                    downloadUrl = SOURCE_DOWNLOAD_URL,
                    downloadedAtEpochMs = sourceArchive.lastModified().coerceAtLeast(1L),
                    archiveLength = verified.archiveLength,
                    archiveSha256 = verified.archiveSha256,
                    integrity = StmCoreArtifactIntegrity.VERIFIED,
                    trust = StmCoreArtifactTrust.DEGRADED_UNSIGNED_CATALOG,
                    catalogVersion = null,
                    archiveRoot = source.archiveRoot,
                    stVersion = source.stVersion,
                    nodeRequirement = source.nodeRequirement,
                    packageLockSha256 = source.packageLockSha256,
                    licenseStatus = source.licenseStatus,
                    runtimeEvidence = integration.runtimeEvidence,
                ),
            )
            check(commit is StmSlotCommitOutcome.Ready) {
                val blocked = commit as? StmSlotCommitOutcome.Blocked
                blocked?.let { "${it.code}:${it.detail}" }
                    ?: "Unexpected slot outcome ${commit.javaClass.simpleName}"
            }
            val committed = store.verifyCommitted(slotId)
            check(committed is StmSlotVerificationResult.Valid) {
                "Committed READY slot did not pass immutable verification"
            }
            check(committed.slot.metadata.commitSha == ST_COMMIT) {
                "Committed slot metadata lost the exact SillyTavern commit"
            }
            check(committed.slot.metadata.packageLockSha256 == PACKAGE_LOCK_SHA256) {
                "Committed slot metadata lost the package-lock binding"
            }
            check(controlFileIdentity(StmCorePaths.activeSlotFile(appContext)) == activeBefore) {
                "The READY-slot experiment changed the active-slot pointer"
            }
            slotManifestSha256 = committed.slot.manifest.manifestSha256
            slotEntryCount = committed.slot.manifest.entries.size
            slotBytes = committed.slot.manifest.totalFileBytes
            outcome = if (retainCommittedSlot) {
                "ready_committed"
            } else {
                check(store.deleteSlot(slotId, active = null, running = null) is
                    StmSlotDeleteResult.Deleted) {
                    "Fresh READY regression slot could not be removed"
                }
                check(store.verifyCommitted(slotId) is StmSlotVerificationResult.Missing) {
                    "Fresh READY regression slot remained after exact cleanup"
                }
                "ready_verified_removed"
            }
        } catch (error: Exception) {
            failure = error.safeReadySlotDetail()
        } finally {
            sampler.close()
            cleanup = runCatching {
                verifiedSource?.let { source ->
                    deleteExactOwnedPath(
                        source.toPath(),
                        requireNotNull(source.parentFile).toPath(),
                    )
                }
                operationRoot?.let {
                    deleteExactOwnedPath(it.toPath(), StmCorePaths.stagingRoot(appContext).toPath())
                }
                "transient_removed"
            }.getOrElse { error ->
                "retained:${error.safeReadySlotDetail()}"
            }
        }

        return linkedMapOf(
            "result" to if (failure.isBlank()) "passed" else "failed",
            "outcome" to outcome,
            "slot_id" to slotId,
            "slot_revision" to SLOT_REVISION.toString(),
            "st_commit" to ST_COMMIT,
            "st_version" to ST_VERSION,
            "source_archive_sha256" to SOURCE_ARCHIVE_SHA256,
            "source_entries" to sourceEntryCount.toString(),
            "source_bytes" to sourceBytes.toString(),
            "supply_manifest_sha256" to supplyManifestSha256,
            "dependency_tree_sha256" to dependencyTreeSha256,
            "post_adapter_program_tree_sha256" to programTreeSha256,
            "slot_manifest_sha256" to slotManifestSha256,
            "slot_entries" to slotEntryCount.toString(),
            "slot_bytes" to slotBytes.toString(),
            "active_pointer" to "unchanged",
            "elapsed_ms" to (SystemClock.elapsedRealtime() - started).toString(),
            "peak_rss_kb" to sampler.peakRssKilobytes.get().toString(),
            "vm_hwm_kb" to sampler.maximumVmHwmKilobytes.get().toString(),
            "cleanup" to cleanup,
            "failure" to failure.take(MAX_RESULT_CHARS),
        )
    }

    private fun requireSourceArchive(): File {
        val downloads = requireNotNull(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) {
            "App external Download directory is unavailable"
        }.canonicalFile
        val source = File(downloads, SOURCE_ARCHIVE_FILE).absoluteFile
        check(!Files.isSymbolicLink(source.toPath()) && source.canonicalFile.parentFile == downloads) {
            "Downloaded source archive escaped the app Download directory"
        }
        check(
            Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                source.length() == SOURCE_ARCHIVE_BYTES &&
                sha256(source.toPath()) == SOURCE_ARCHIVE_SHA256
        ) {
            "The fixed exact-commit source archive is missing or changed"
        }
        return source
    }

    private fun requireSupplyRoot(): File {
        val parent = File(
            StmCorePaths.cacheRoot(appContext),
            "experiments/gate3b/prebuilt-supplies",
        ).canonicalFile
        val root = File(parent, DEBUG_SUPPLY_ID).absoluteFile
        check(!Files.isSymbolicLink(root.toPath())) { "Signed supply root is a symbolic link" }
        val canonical = root.canonicalFile
        check(canonical.parentFile == parent && canonical.name == DEBUG_SUPPLY_ID) {
            "Signed supply root escaped its fixed cache parent"
        }
        check(Files.isDirectory(canonical.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Signed dependency supply is unavailable"
        }
        return canonical
    }

    private fun controlFileIdentity(file: File): String {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return "missing"
        check(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Active-slot pointer is not a safe regular file"
        }
        return "${Files.size(path)}:${sha256(path)}"
    }

    private fun javetCoordinate(): String =
        "com.caoccao.javet:${BuildConfig.JAVET_ARTIFACT}:5.0.9"

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
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

    private fun deleteExactOwnedPath(root: Path, parent: Path) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedParent = parent.toAbsolutePath().normalize()
        check(normalizedRoot.parent == normalizedParent && normalizedRoot != normalizedParent) {
            "READY-slot cleanup target escaped its exact owned parent"
        }
        if (!Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(normalizedRoot)
        ) {
            Files.delete(normalizedRoot)
            return
        }
        Files.walkFileTree(normalizedRoot, object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                directory: Path,
                error: java.io.IOException?,
            ): FileVisitResult {
                error?.let { throw it }
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private companion object {
        const val ST_REPOSITORY = StmCoreGate3bPrebuiltExperiment.ST_REPOSITORY
        const val ST_COMMIT = StmCoreGate3bPrebuiltExperiment.ST_COMMIT
        const val ST_VERSION = StmCoreGate3bPrebuiltExperiment.ST_VERSION
        const val PACKAGE_LOCK_SHA256 =
            StmCoreGate3bPrebuiltExperiment.PACKAGE_LOCK_SHA256
        const val DEVICE_NODE_VERSION = StmCoreGate3bPrebuiltExperiment.DEVICE_NODE_VERSION
        const val DEVICE_ABI = StmCoreGate3bPrebuiltExperiment.DEVICE_ABI
        const val DEBUG_SUPPLY_ID = StmCoreGate3bPrebuiltExperiment.DEBUG_SUPPLY_ID
        const val DEBUG_SIGNING_KEY_ID =
            StmCoreGate3bPrebuiltExperiment.DEBUG_SIGNING_KEY_ID
        const val DEBUG_PUBLIC_KEY_DER_BASE64 =
            StmCoreGate3bPrebuiltExperiment.DEBUG_PUBLIC_KEY_DER_BASE64
        const val SOURCE_ARCHIVE_FILE =
            "sillytavern-release-$ST_COMMIT.zip"
        const val SOURCE_DOWNLOAD_URL =
            "https://github.com/SillyTavern/SillyTavern/archive/$ST_COMMIT.zip"
        const val SOURCE_ARCHIVE_BYTES = 38_459_064L
        const val SOURCE_ARCHIVE_SHA256 =
            "92ce95bd95f277e73c8aa6efb57f34821136262076a756efd19ffbaa58773b03"
        const val RETAINED_SLOT_ID = "st-1-18-0-stage3b-debug"
        const val SLOT_REVISION = 1L
        const val DEPENDENCY_OPERATION_DIRECTORY = "dependency-extraction"
        const val MAX_RESULT_CHARS = 2_000
    }
}

private fun Throwable.safeReadySlotDetail(): String =
    (message ?: javaClass.simpleName)
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .ifBlank { javaClass.simpleName }
        .take(500)
