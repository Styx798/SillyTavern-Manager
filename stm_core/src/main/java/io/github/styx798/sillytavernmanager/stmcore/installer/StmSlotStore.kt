package io.github.styx798.sillytavernmanager.stmcore.installer

import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifact
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactIntegrity
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactKind
import io.github.styx798.sillytavernmanager.stmcore.StmCoreArtifactTrust
import io.github.styx798.sillytavernmanager.stmcore.requireValidArtifact
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

internal enum class StmSlotPayloadKind {
    GATE2_SYNTHETIC,
    SILLY_TAVERN_SOURCE,
}

internal enum class StmSlotAdmission {
    READY,
    VERIFIED_NOT_READY,
    BLOCKED,
}

internal enum class StmSlotBlockCode {
    PAYLOAD_MISSING,
    UNSAFE_CONTENT,
    RESERVED_METADATA,
    ACCEPTANCE_FAILED,
    TARGET_EXISTS,
    ATOMIC_MOVE_UNSUPPORTED,
    COMMITTED_SLOT_INVALID,
}

internal data class StmSlotCommitRequest(
    val operationId: String,
    val slotId: String,
    val slotRevision: Long,
    val payloadKind: StmSlotPayloadKind,
    val repository: String,
    val channel: String,
    val commitSha: String,
    val downloadUrl: String,
    val downloadedAtEpochMs: Long,
    val archiveLength: Long,
    val archiveSha256: String,
    val integrity: StmCoreArtifactIntegrity,
    val trust: StmCoreArtifactTrust,
    val catalogVersion: String?,
    val archiveRoot: String?,
    val stVersion: String?,
    val nodeRequirement: String?,
    val packageLockSha256: String?,
    val licenseStatus: String?,
    val runtimeEvidence: StmRuntimeSlotAdmissionEvidence? = null,
)

internal data class StmRuntimeFileBinding(
    val bytes: Long,
    val sha256: String,
)

internal enum class StmRuntimeSupplyKind {
    SIGNED_PREBUILT,
    DEVICE_LOCAL_BUILD,
}

/**
 * Capability produced only after Core assembles and accepts the exact post-adapter program tree.
 * Signed prebuilt supplies and device-local builds have distinct durable evidence sets; neither
 * path is allowed to impersonate the other.
 */
internal data class StmRuntimeSlotAdmissionEvidence(
    val supplyKind: StmRuntimeSupplyKind = StmRuntimeSupplyKind.SIGNED_PREBUILT,
    val repository: String,
    val commitSha: String,
    val packageLockSha256: String,
    val dependencyTreeSha256: String,
    val postAdapterProgramTreeSha256: String,
    val runtimeFiles: Map<String, StmRuntimeFileBinding>,
) {
    companion object {
        const val RUNTIME_DIRECTORY = ".stm-runtime"
        const val MANIFEST_FILE = "manifest.stm"
        const val SIGNATURE_FILE = "manifest.sig"
        const val TREE_MANIFEST_FILE = "tree-manifest.tsv"
        const val SBOM_FILE = "sbom.cdx.json"
        const val LICENSE_MANIFEST_FILE = "licenses.json"
        const val THIRD_PARTY_LICENSE_ARCHIVE_FILE = "third-party-licenses.zip"
        const val STM_LICENSE_FILE = "stm-license.txt"
        const val BUNDLE_FILE = "lib.js"
        const val BUNDLE_LICENSE_FILE = "lib.js.LICENSE.txt"
        const val ADAPTER_FILE = "webpack-serve.adapter.js"
        const val PRUNE_POLICY_FILE = "prune-policy.txt"
        const val RUNNABLE_ACCEPTANCE_FILE = "runnable-acceptance.stm"

        val SIGNED_PREBUILT_RUNTIME_FILES = setOf(
            ADAPTER_FILE,
            BUNDLE_FILE,
            BUNDLE_LICENSE_FILE,
            LICENSE_MANIFEST_FILE,
            MANIFEST_FILE,
            PRUNE_POLICY_FILE,
            SBOM_FILE,
            SIGNATURE_FILE,
            STM_LICENSE_FILE,
            THIRD_PARTY_LICENSE_ARCHIVE_FILE,
            TREE_MANIFEST_FILE,
        )

        val DEVICE_LOCAL_BUILD_RUNTIME_FILES = setOf(
            ADAPTER_FILE,
            BUNDLE_FILE,
            BUNDLE_LICENSE_FILE,
            LICENSE_MANIFEST_FILE,
            MANIFEST_FILE,
            PRUNE_POLICY_FILE,
            RUNNABLE_ACCEPTANCE_FILE,
            SBOM_FILE,
            TREE_MANIFEST_FILE,
        )

        /** Kept as the signed-supply compatibility set for the existing integrator. */
        val REQUIRED_RUNTIME_FILES = SIGNED_PREBUILT_RUNTIME_FILES
    }
}

internal enum class StmSlotManifestEntryType(val wireValue: Int) {
    DIRECTORY(1),
    FILE(2),
    ;

    companion object {
        fun fromWireValue(value: Int): StmSlotManifestEntryType =
            entries.singleOrNull { it.wireValue == value }
                ?: throw SlotValidationException(
                    StmSlotBlockCode.UNSAFE_CONTENT,
                    "Unknown manifest entry type $value",
                )
    }
}

internal data class StmSlotManifestEntry(
    val relativePath: String,
    val type: StmSlotManifestEntryType,
    val bytes: Long,
    val fileSha256: String?,
)

internal data class StmSlotContentManifest(
    val entries: List<StmSlotManifestEntry>,
    val totalFileBytes: Long,
    val manifestSha256: String,
) {
    val fileCount: Int
        get() = entries.count { it.type == StmSlotManifestEntryType.FILE }
}

internal data class StmSlotMetadata(
    val operationId: String,
    val slotId: String,
    val slotRevision: Long,
    val payloadKind: StmSlotPayloadKind,
    val repository: String,
    val channel: String,
    val commitSha: String,
    val downloadUrl: String,
    val downloadedAtEpochMs: Long,
    val archiveLength: Long,
    val archiveSha256: String,
    val integrity: StmCoreArtifactIntegrity,
    val trust: StmCoreArtifactTrust,
    val catalogVersion: String?,
    val archiveRoot: String?,
    val stVersion: String?,
    val nodeRequirement: String?,
    val packageLockSha256: String?,
    val licenseStatus: String?,
    val admission: StmSlotAdmission,
    val manifestSha256: String,
    val manifestEntryCount: Int,
    val totalFileBytes: Long,
)

internal data class StmCommittedSlot(
    val metadata: StmSlotMetadata,
    val manifest: StmSlotContentManifest,
    val directory: File,
)

internal sealed interface StmSlotCommitOutcome {
    data class Ready(val slot: StmCommittedSlot) : StmSlotCommitOutcome

    data class VerifiedNotReady(
        val metadata: StmSlotMetadata,
        val manifest: StmSlotContentManifest,
        val reason: String,
    ) : StmSlotCommitOutcome

    data class Blocked(
        val code: StmSlotBlockCode,
        val detail: String,
        val manifest: StmSlotContentManifest? = null,
    ) : StmSlotCommitOutcome
}

internal sealed interface StmSlotVerificationResult {
    data class Valid(val slot: StmCommittedSlot) : StmSlotVerificationResult

    data object Missing : StmSlotVerificationResult

    data class Invalid(val detail: String) : StmSlotVerificationResult
}

/** One direct entry beneath the Core-owned slots root, including invalid foreign entries. */
internal data class StmSlotScanEntry(
    val entryName: String,
    val verification: StmSlotVerificationResult,
)

internal data class StmSlotReference(
    val slotId: String,
    val slotRevision: Long,
)

internal sealed interface StmSlotDeleteResult {
    data object Deleted : StmSlotDeleteResult

    data object Missing : StmSlotDeleteResult

    data class RejectedReferenced(val reference: String) : StmSlotDeleteResult
}

internal enum class StmSlotStoreFailpoint {
    BEFORE_SLOT_MOVE,
    AFTER_SLOT_MOVE,
}

internal fun interface StmSlotStoreFaultInjector {
    fun hit(failpoint: StmSlotStoreFailpoint)
}

/** Owns immutable slot commits beneath one slots root and operation payloads beneath staging. */
internal class StmSlotStore(
    slotsRoot: File,
    stagingRoot: File,
    private val faultInjector: StmSlotStoreFaultInjector = StmSlotStoreFaultInjector { },
    private val directoryRenamer: (Path, Path) -> Boolean = { source, target ->
        source.toFile().renameTo(target.toFile())
    },
) {
    private val slotsRoot: Path = initializeOwnedRoot(slotsRoot.toPath())
    private val stagingRoot: Path = initializeOwnedRoot(stagingRoot.toPath())

    init {
        require(this.slotsRoot != this.stagingRoot) { "Slots and staging roots must be different" }
        require(!this.slotsRoot.startsWith(this.stagingRoot)) {
            "The slots root cannot be nested inside staging"
        }
        require(!this.stagingRoot.startsWith(this.slotsRoot)) {
            "The staging root cannot be nested inside slots"
        }
    }

    fun operationPayloadDirectory(operationId: String): File {
        requireSafeIdentifier(operationId, "operation ID")
        return resolveChild(stagingRoot, operationId)
            .resolve(PAYLOAD_DIRECTORY)
            .toFile()
    }

    @Synchronized
    fun prepareAndCommit(request: StmSlotCommitRequest): StmSlotCommitOutcome {
        request.requireValid()
        val payload = operationPayloadDirectory(request.operationId).toPath()
        if (!Files.exists(payload, LinkOption.NOFOLLOW_LINKS)) {
            return StmSlotCommitOutcome.Blocked(
                StmSlotBlockCode.PAYLOAD_MISSING,
                "The operation payload does not exist",
            )
        }

        val manifest = try {
            resolveOwnedPayload(payload, stagingRoot)
            buildManifest(payload, metadataAllowed = false, syncFiles = true)
        } catch (error: SlotValidationException) {
            return StmSlotCommitOutcome.Blocked(error.code, error.safeDetail())
        } catch (error: IllegalArgumentException) {
            return StmSlotCommitOutcome.Blocked(
                StmSlotBlockCode.UNSAFE_CONTENT,
                error.safeDetail(),
            )
        }

        val acceptance = try {
            evaluateAcceptance(payload, request, manifest)
        } catch (error: IllegalArgumentException) {
            return StmSlotCommitOutcome.Blocked(
                StmSlotBlockCode.UNSAFE_CONTENT,
                error.safeDetail(),
                manifest,
            )
        }
        val metadata = StmSlotMetadata(
            operationId = request.operationId,
            slotId = request.slotId,
            slotRevision = request.slotRevision,
            payloadKind = request.payloadKind,
            repository = request.repository,
            channel = request.channel,
            commitSha = request.commitSha,
            downloadUrl = request.downloadUrl,
            downloadedAtEpochMs = request.downloadedAtEpochMs,
            archiveLength = request.archiveLength,
            archiveSha256 = request.archiveSha256,
            integrity = request.integrity,
            trust = request.trust,
            catalogVersion = request.catalogVersion,
            archiveRoot = request.archiveRoot,
            stVersion = request.stVersion,
            nodeRequirement = request.nodeRequirement,
            packageLockSha256 = request.packageLockSha256,
            licenseStatus = request.licenseStatus,
            admission = acceptance.admission,
            manifestSha256 = manifest.manifestSha256,
            manifestEntryCount = manifest.entries.size,
            totalFileBytes = manifest.totalFileBytes,
        )
        writeControlFiles(payload, manifest, metadata)

        when (acceptance.admission) {
            StmSlotAdmission.BLOCKED -> return StmSlotCommitOutcome.Blocked(
                code = StmSlotBlockCode.ACCEPTANCE_FAILED,
                detail = acceptance.reason,
                manifest = manifest,
            )

            StmSlotAdmission.VERIFIED_NOT_READY -> return StmSlotCommitOutcome.VerifiedNotReady(
                metadata = metadata,
                manifest = manifest,
                reason = acceptance.reason,
            )

            StmSlotAdmission.READY -> Unit
        }

        val target = resolveChild(slotsRoot, request.slotId)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return StmSlotCommitOutcome.Blocked(
                StmSlotBlockCode.TARGET_EXISTS,
                "Slot ${request.slotId} already exists and will not be replaced",
                manifest,
            )
        }
        faultInjector.hit(StmSlotStoreFailpoint.BEFORE_SLOT_MOVE)
        // Android API 31's NIO directory move calls its unimplemented getFileStore().
        // File.renameTo() delegates to the platform rename syscall; never fall back to copying.
        if (!directoryRenamer(payload, target)) {
            val targetAppeared = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            return StmSlotCommitOutcome.Blocked(
                if (targetAppeared) {
                    StmSlotBlockCode.TARGET_EXISTS
                } else {
                    StmSlotBlockCode.ATOMIC_MOVE_UNSUPPORTED
                },
                if (targetAppeared) {
                    "Slot ${request.slotId} appeared before commit and was not replaced"
                } else {
                    "The slot filesystem did not complete the atomic directory rename"
                },
                manifest,
            )
        }
        faultInjector.hit(StmSlotStoreFailpoint.AFTER_SLOT_MOVE)
        bestEffortSyncDirectory(slotsRoot)

        return when (val verification = verifyCommitted(request.slotId)) {
            is StmSlotVerificationResult.Valid -> StmSlotCommitOutcome.Ready(verification.slot)
            StmSlotVerificationResult.Missing -> StmSlotCommitOutcome.Blocked(
                StmSlotBlockCode.COMMITTED_SLOT_INVALID,
                "The atomically moved slot is unexpectedly missing",
                manifest,
            )

            is StmSlotVerificationResult.Invalid -> StmSlotCommitOutcome.Blocked(
                StmSlotBlockCode.COMMITTED_SLOT_INVALID,
                verification.detail,
                manifest,
            )
        }
    }

    fun verifyCommitted(slotId: String): StmSlotVerificationResult {
        return try {
            requireSafeIdentifier(slotId, "slot ID")
            val slot = resolveChild(slotsRoot, slotId)
            if (!Files.exists(slot, LinkOption.NOFOLLOW_LINKS)) {
                return StmSlotVerificationResult.Missing
            }
            resolveOwnedPayload(slot, slotsRoot)
            val controlDirectory = slot.resolve(CONTROL_DIRECTORY)
            validateControlDirectory(controlDirectory)
            val storedManifest = readManifest(controlDirectory.resolve(MANIFEST_FILE))
            val metadata = readMetadata(controlDirectory.resolve(METADATA_FILE))
            require(metadata.slotId == slotId) { "Slot metadata ID does not match its directory" }
            require(metadata.admission == StmSlotAdmission.READY) {
                "Only READY metadata may exist in committed slots"
            }
            require(metadata.manifestSha256 == storedManifest.manifestSha256) {
                "Slot metadata does not match the stored manifest"
            }
            require(metadata.manifestEntryCount == storedManifest.entries.size) {
                "Slot metadata entry count does not match the stored manifest"
            }
            require(metadata.totalFileBytes == storedManifest.totalFileBytes) {
                "Slot metadata byte count does not match the stored manifest"
            }

            val actualManifest = buildManifest(slot, metadataAllowed = true, syncFiles = false)
            require(actualManifest == storedManifest) {
                "Committed slot content differs from its immutable manifest"
            }
            StmSlotVerificationResult.Valid(
                StmCommittedSlot(metadata, storedManifest, slot.toFile()),
            )
        } catch (error: Exception) {
            StmSlotVerificationResult.Invalid(error.safeDetail())
        }
    }

    /**
     * Enumerates every direct slots-root entry without following links. Invalid or foreign entries
     * are retained as evidence so callers can rebuild a checkpoint without hiding disk state.
     */
    fun scanCommitted(): List<StmSlotScanEntry> =
        Files.newDirectoryStream(slotsRoot).use { children ->
            children.map { child ->
                val entryName = child.fileName.toString()
                val verification = if (!isSafeIdentifier(entryName)) {
                    StmSlotVerificationResult.Invalid(
                        "Direct slot entry has an unsafe identifier",
                    )
                } else {
                    when (val result = verifyCommitted(entryName)) {
                        StmSlotVerificationResult.Missing ->
                            StmSlotVerificationResult.Invalid(
                                "Direct slot entry disappeared during committed-slot scan",
                            )

                        else -> result
                    }
                }
                StmSlotScanEntry(entryName, verification)
            }.sortedBy(StmSlotScanEntry::entryName)
        }

    @Synchronized
    fun deleteSlot(
        slotId: String,
        active: StmSlotReference?,
        running: StmSlotReference?,
    ): StmSlotDeleteResult {
        requireSafeIdentifier(slotId, "slot ID")
        if (active?.slotId == slotId) {
            return StmSlotDeleteResult.RejectedReferenced("active")
        }
        if (running?.slotId == slotId) {
            return StmSlotDeleteResult.RejectedReferenced("running")
        }

        val target = resolveChild(slotsRoot, slotId)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return StmSlotDeleteResult.Missing
        deleteTreeNoFollow(target)
        bestEffortSyncDirectory(slotsRoot)
        return StmSlotDeleteResult.Deleted
    }

    private fun buildManifest(
        payload: Path,
        metadataAllowed: Boolean,
        syncFiles: Boolean,
    ): StmSlotContentManifest {
        val root = payload.toRealPath()
        val entries = mutableListOf<StmSlotManifestEntry>()
        val collisionKeys = mutableSetOf<String>()
        var totalFileBytes = 0L

        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                if (directory == root) return FileVisitResult.CONTINUE
                ensureInside(directory, root)
                require(!attributes.isSymbolicLink && attributes.isDirectory) {
                    "Manifest directories must be real directories"
                }
                val relative = manifestRelativePath(root, directory)
                if (relative == CONTROL_DIRECTORY) {
                    if (!metadataAllowed) {
                        throw SlotValidationException(
                            StmSlotBlockCode.RESERVED_METADATA,
                            "The payload already contains reserved STM slot metadata",
                        )
                    }
                    return FileVisitResult.SKIP_SUBTREE
                }
                require(!relative.startsWith("$CONTROL_DIRECTORY/")) {
                    "Reserved STM slot metadata cannot be nested in content"
                }
                addManifestEntry(
                    entries,
                    collisionKeys,
                    StmSlotManifestEntry(relative, StmSlotManifestEntryType.DIRECTORY, 0, null),
                )
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                ensureInside(file, root)
                val relative = manifestRelativePath(root, file)
                if (relative == CONTROL_DIRECTORY || relative.startsWith("$CONTROL_DIRECTORY/")) {
                    throw SlotValidationException(
                        StmSlotBlockCode.RESERVED_METADATA,
                        "Reserved STM slot metadata must be a controlled directory",
                    )
                }
                if (attributes.isSymbolicLink || !attributes.isRegularFile) {
                    throw SlotValidationException(
                        StmSlotBlockCode.UNSAFE_CONTENT,
                        "Slot content contains a symbolic link or non-regular file: $relative",
                    )
                }
                require(attributes.size() in 0..MAX_SINGLE_FILE_BYTES) {
                    "Slot file exceeds the allowed size: $relative"
                }
                if (syncFiles) syncRegularFile(file)
                val digest = hashRegularFile(file, attributes.size())
                totalFileBytes = Math.addExact(totalFileBytes, attributes.size())
                require(totalFileBytes <= MAX_TOTAL_FILE_BYTES) {
                    "Slot content exceeds the allowed total size"
                }
                addManifestEntry(
                    entries,
                    collisionKeys,
                    StmSlotManifestEntry(
                        relativePath = relative,
                        type = StmSlotManifestEntryType.FILE,
                        bytes = attributes.size(),
                        fileSha256 = digest,
                    ),
                )
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                throw SlotValidationException(
                    StmSlotBlockCode.UNSAFE_CONTENT,
                    "Slot content could not be inspected: ${error.safeDetail()}",
                )
            }

            override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                error?.let { throw it }
                if (syncFiles && directory != root.resolve(CONTROL_DIRECTORY)) {
                    bestEffortSyncDirectory(directory)
                }
                return FileVisitResult.CONTINUE
            }
        })

        val sorted = entries.sortedBy(StmSlotManifestEntry::relativePath)
        val encoded = encodeManifest(sorted, totalFileBytes)
        require(encoded.size <= MAX_MANIFEST_BYTES) { "Slot manifest exceeds the allowed size" }
        return StmSlotContentManifest(
            entries = sorted,
            totalFileBytes = totalFileBytes,
            manifestSha256 = sha256(encoded).toHex(),
        )
    }

    private fun addManifestEntry(
        entries: MutableList<StmSlotManifestEntry>,
        collisionKeys: MutableSet<String>,
        entry: StmSlotManifestEntry,
    ) {
        require(entries.size < MAX_MANIFEST_ENTRIES) { "Slot contains too many manifest entries" }
        val collisionKey = entry.relativePath.lowercase(Locale.ROOT)
        if (!collisionKeys.add(collisionKey)) {
            throw SlotValidationException(
                StmSlotBlockCode.UNSAFE_CONTENT,
                "Slot contains a case or Unicode-normalization path collision",
            )
        }
        entries += entry
    }

    private fun evaluateAcceptance(
        payload: Path,
        request: StmSlotCommitRequest,
        manifest: StmSlotContentManifest,
    ): AcceptanceResult {
        if (request.integrity != StmCoreArtifactIntegrity.VERIFIED) {
            return AcceptanceResult(
                StmSlotAdmission.BLOCKED,
                "Slot admission requires verified artifact integrity",
            )
        }
        if (request.trust == StmCoreArtifactTrust.REJECTED) {
            return AcceptanceResult(
                StmSlotAdmission.BLOCKED,
                "Slot admission rejects artifact trust evidence marked REJECTED",
            )
        }
        val files = manifest.entries
            .filter { it.type == StmSlotManifestEntryType.FILE }
            .associateBy(StmSlotManifestEntry::relativePath)
        return when (request.payloadKind) {
            StmSlotPayloadKind.GATE2_SYNTHETIC -> {
                val marker = files[GATE2_MARKER_FILE]
                if (marker == null || marker.bytes != GATE2_MARKER_BYTES.size.toLong()) {
                    AcceptanceResult(
                        StmSlotAdmission.BLOCKED,
                        "The Gate 2 synthetic marker is missing or has the wrong length",
                    )
                } else {
                    val markerBytes = readSmallRegularFile(
                        payload.resolve(GATE2_MARKER_FILE),
                        GATE2_MARKER_BYTES.size,
                    )
                    if (!markerBytes.contentEquals(GATE2_MARKER_BYTES)) {
                        AcceptanceResult(
                            StmSlotAdmission.BLOCKED,
                            "The Gate 2 synthetic marker content did not match",
                        )
                    } else {
                        AcceptanceResult(StmSlotAdmission.READY, "Gate 2 synthetic fixture accepted")
                    }
                }
            }

            StmSlotPayloadKind.SILLY_TAVERN_SOURCE -> {
                val archiveRoot = request.archiveRoot
                val evidenceComplete = archiveRoot != null &&
                    request.stVersion != null &&
                    request.nodeRequirement != null &&
                    request.packageLockSha256 != null &&
                    request.licenseStatus != null
                if (!evidenceComplete) {
                    AcceptanceResult(
                        StmSlotAdmission.BLOCKED,
                        "SillyTavern source is missing Core-derived source evidence",
                    )
                } else {
                    val safeRoot = requireNotNull(archiveRoot).takeIf { root ->
                        root.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")) &&
                            root != "." && root != ".."
                    }
                    if (safeRoot == null) {
                        AcceptanceResult(
                            StmSlotAdmission.BLOCKED,
                            "SillyTavern source archive root is unsafe",
                        )
                    } else {
                        val missing = REQUIRED_ST_SOURCE_FILES.filter { path ->
                            files["$safeRoot/$path"]?.bytes?.let { it > 0 } != true
                        }
                        if (missing.isNotEmpty()) {
                            AcceptanceResult(
                                StmSlotAdmission.BLOCKED,
                                "SillyTavern source is missing required files: ${missing.joinToString()}",
                            )
                        } else if (request.runtimeEvidence != null) {
                            evaluateRuntimeAcceptance(
                                archiveRoot = safeRoot,
                                request = request,
                                manifest = manifest,
                                evidence = request.runtimeEvidence,
                            )
                        } else {
                            AcceptanceResult(
                                StmSlotAdmission.VERIFIED_NOT_READY,
                                "SillyTavern source is verified but dependencies and runnable acceptance are not available in Gate 2",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun evaluateRuntimeAcceptance(
        archiveRoot: String,
        request: StmSlotCommitRequest,
        manifest: StmSlotContentManifest,
        evidence: StmRuntimeSlotAdmissionEvidence,
    ): AcceptanceResult {
        if (
            evidence.repository != request.repository ||
            evidence.commitSha != request.commitSha ||
            evidence.packageLockSha256 != request.packageLockSha256
        ) {
            return AcceptanceResult(
                StmSlotAdmission.BLOCKED,
                "Runtime evidence does not bind the source artifact and package lock",
            )
        }

        val programPrefix = "$archiveRoot/"
        val programEntries = manifest.entries
            .asSequence()
            .filter { entry -> entry.relativePath.startsWith(programPrefix) }
            .map { entry ->
                entry.toZipManifestEntry(entry.relativePath.removePrefix(programPrefix))
            }
            .toList()
        if (
            programEntries.isEmpty() ||
            stmTreeIdentitySha256(programEntries) != evidence.postAdapterProgramTreeSha256
        ) {
            return AcceptanceResult(
                StmSlotAdmission.BLOCKED,
                "Assembled SillyTavern program tree does not match signed runtime evidence",
            )
        }

        val dependencyRoot = "$archiveRoot/node_modules"
        val dependencyEntries = manifest.entries
            .asSequence()
            .filter { entry ->
                entry.relativePath == dependencyRoot ||
                    entry.relativePath.startsWith("$dependencyRoot/")
            }
            .map { entry ->
                entry.toZipManifestEntry(entry.relativePath.removePrefix("$archiveRoot/"))
            }
            .toList()
        if (
            dependencyEntries.isEmpty() ||
            stmTreeIdentitySha256(dependencyEntries) != evidence.dependencyTreeSha256
        ) {
            return AcceptanceResult(
                StmSlotAdmission.BLOCKED,
                "Installed node_modules does not match signed dependency-tree evidence",
            )
        }

        val runtimeDirectory = StmRuntimeSlotAdmissionEvidence.RUNTIME_DIRECTORY
        val runtimePrefix = "$runtimeDirectory/"
        val runtimeDirectories = manifest.entries.filter { entry ->
            entry.type == StmSlotManifestEntryType.DIRECTORY &&
                entry.relativePath.startsWith(runtimePrefix)
        }
        if (runtimeDirectories.isNotEmpty()) {
            return AcceptanceResult(
                StmSlotAdmission.BLOCKED,
                "Runtime evidence directory must contain only direct regular files",
            )
        }
        val runtimeFiles = manifest.entries
            .filter { entry ->
                entry.type == StmSlotManifestEntryType.FILE &&
                    entry.relativePath.startsWith(runtimePrefix)
            }
            .associateBy { entry -> entry.relativePath.removePrefix(runtimePrefix) }
        val requiredRuntimeFiles = when (evidence.supplyKind) {
            StmRuntimeSupplyKind.SIGNED_PREBUILT ->
                StmRuntimeSlotAdmissionEvidence.SIGNED_PREBUILT_RUNTIME_FILES

            StmRuntimeSupplyKind.DEVICE_LOCAL_BUILD ->
                StmRuntimeSlotAdmissionEvidence.DEVICE_LOCAL_BUILD_RUNTIME_FILES
        }
        if (runtimeFiles.keys != requiredRuntimeFiles ||
            evidence.runtimeFiles.keys != requiredRuntimeFiles
        ) {
            return AcceptanceResult(
                StmSlotAdmission.BLOCKED,
                "Runtime evidence directory has missing or unexpected files",
            )
        }
        val sidecarsMatch = evidence.runtimeFiles.all { (name, binding) ->
            val actual = runtimeFiles[name]
            actual?.bytes == binding.bytes && actual.fileSha256 == binding.sha256
        }
        if (!sidecarsMatch) {
            return AcceptanceResult(
                StmSlotAdmission.BLOCKED,
                "Runtime evidence sidecar content does not match its verified identity",
            )
        }

        val adapter = manifest.entries.singleOrNull { entry ->
            entry.relativePath == "$archiveRoot/src/middleware/webpack-serve.js" &&
                entry.type == StmSlotManifestEntryType.FILE
        }
        val adapterBinding = evidence.runtimeFiles.getValue(
            StmRuntimeSlotAdmissionEvidence.ADAPTER_FILE,
        )
        if (
            adapter?.bytes != adapterBinding.bytes ||
            adapter.fileSha256 != adapterBinding.sha256
        ) {
            return AcceptanceResult(
                StmSlotAdmission.BLOCKED,
                "Installed Webpack adapter does not match the signed adapter",
            )
        }

        return AcceptanceResult(
            StmSlotAdmission.READY,
            when (evidence.supplyKind) {
                StmRuntimeSupplyKind.SIGNED_PREBUILT ->
                    "Signed dependency supply and post-adapter SillyTavern program accepted"

                StmRuntimeSupplyKind.DEVICE_LOCAL_BUILD ->
                    "Device-local npm build and runnable SillyTavern program accepted"
            },
        )
    }

    private fun writeControlFiles(
        payload: Path,
        manifest: StmSlotContentManifest,
        metadata: StmSlotMetadata,
    ) {
        val controlDirectory = payload.resolve(CONTROL_DIRECTORY)
        try {
            Files.createDirectory(controlDirectory)
        } catch (_: FileAlreadyExistsException) {
            throw SlotValidationException(
                StmSlotBlockCode.RESERVED_METADATA,
                "The payload already contains reserved STM slot metadata",
            )
        }
        val manifestBytes = encodeManifest(manifest.entries, manifest.totalFileBytes)
        require(sha256(manifestBytes).toHex() == manifest.manifestSha256) {
            "Manifest changed before it was persisted"
        }
        writeAtomicNew(controlDirectory.resolve(MANIFEST_FILE), manifestBytes)
        writeAtomicNew(controlDirectory.resolve(METADATA_FILE), encodeMetadata(metadata))
        bestEffortSyncDirectory(controlDirectory)
        bestEffortSyncDirectory(payload)
    }

    private fun readManifest(path: Path): StmSlotContentManifest {
        val bytes = readBoundedRegularFile(path, MAX_MANIFEST_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MANIFEST_MAGIC) { "Slot manifest magic did not match" }
        require(input.readInt() == MANIFEST_VERSION) { "Slot manifest version is unsupported" }
        val entryCount = input.readInt()
        require(entryCount in 0..MAX_MANIFEST_ENTRIES) { "Slot manifest entry count is invalid" }
        val recordedTotal = input.readLong()
        require(recordedTotal in 0..MAX_TOTAL_FILE_BYTES) { "Slot manifest total is invalid" }
        val entries = ArrayList<StmSlotManifestEntry>(entryCount)
        var computedTotal = 0L
        var previousPath: String? = null
        repeat(entryCount) {
            val type = StmSlotManifestEntryType.fromWireValue(input.readUnsignedByte())
            val relativePath = input.readBoundedUtf8(MAX_RELATIVE_PATH_BYTES)
            validateManifestRelativePath(relativePath)
            require(previousPath == null || requireNotNull(previousPath) < relativePath) {
                "Slot manifest paths are not strictly sorted"
            }
            previousPath = relativePath
            val fileBytes = input.readLong()
            val fileSha = when (type) {
                StmSlotManifestEntryType.DIRECTORY -> {
                    require(fileBytes == 0L) { "Manifest directories must have zero bytes" }
                    null
                }

                StmSlotManifestEntryType.FILE -> {
                    require(fileBytes in 0..MAX_SINGLE_FILE_BYTES) { "Manifest file size is invalid" }
                    computedTotal = Math.addExact(computedTotal, fileBytes)
                    ByteArray(SHA256_BYTES).also(input::readFully).toHex()
                }
            }
            entries += StmSlotManifestEntry(relativePath, type, fileBytes, fileSha)
        }
        require(input.read() == -1) { "Slot manifest contains trailing bytes" }
        require(computedTotal == recordedTotal) { "Slot manifest total does not match its entries" }
        return StmSlotContentManifest(entries, recordedTotal, sha256(bytes).toHex())
    }

    private fun readMetadata(path: Path): StmSlotMetadata {
        val bytes = readBoundedRegularFile(path, MAX_METADATA_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == METADATA_MAGIC) { "Slot metadata magic did not match" }
        require(input.readInt() == METADATA_VERSION) { "Slot metadata version is unsupported" }
        val payloadLength = input.readInt()
        require(payloadLength in 1..MAX_METADATA_PAYLOAD_BYTES) {
            "Slot metadata payload length is invalid"
        }
        require(bytes.size == METADATA_HEADER_BYTES + payloadLength + SHA256_BYTES) {
            "Slot metadata is truncated or has trailing bytes"
        }
        val payload = ByteArray(payloadLength).also(input::readFully)
        val expectedChecksum = ByteArray(SHA256_BYTES).also(input::readFully)
        require(input.read() == -1) { "Slot metadata contains trailing bytes" }
        require(MessageDigest.isEqual(expectedChecksum, sha256(payload))) {
            "Slot metadata checksum did not match"
        }

        val payloadInput = DataInputStream(ByteArrayInputStream(payload))
        val metadata = StmSlotMetadata(
            operationId = payloadInput.readBoundedUtf8(MAX_IDENTIFIER_BYTES),
            slotId = payloadInput.readBoundedUtf8(MAX_IDENTIFIER_BYTES),
            slotRevision = payloadInput.readLong(),
            payloadKind = StmSlotPayloadKind.valueOf(
                payloadInput.readBoundedUtf8(MAX_ENUM_NAME_BYTES),
            ),
            repository = payloadInput.readBoundedUtf8(MAX_REPOSITORY_BYTES),
            channel = payloadInput.readBoundedUtf8(MAX_CHANNEL_BYTES),
            commitSha = payloadInput.readBoundedUtf8(MAX_COMMIT_SHA_BYTES),
            downloadUrl = payloadInput.readBoundedUtf8(MAX_DOWNLOAD_URL_BYTES),
            downloadedAtEpochMs = payloadInput.readLong(),
            archiveLength = payloadInput.readLong(),
            archiveSha256 = payloadInput.readBoundedUtf8(MAX_SHA256_TEXT_BYTES),
            integrity = StmCoreArtifactIntegrity.valueOf(
                payloadInput.readBoundedUtf8(MAX_ENUM_NAME_BYTES),
            ),
            trust = StmCoreArtifactTrust.valueOf(
                payloadInput.readBoundedUtf8(MAX_ENUM_NAME_BYTES),
            ),
            catalogVersion = payloadInput.readNullableBoundedUtf8(MAX_CATALOG_VERSION_BYTES),
            archiveRoot = payloadInput.readNullableBoundedUtf8(MAX_ARCHIVE_ROOT_BYTES),
            stVersion = payloadInput.readNullableBoundedUtf8(MAX_ST_VERSION_BYTES),
            nodeRequirement = payloadInput.readNullableBoundedUtf8(MAX_NODE_REQUIREMENT_BYTES),
            packageLockSha256 = payloadInput.readNullableBoundedUtf8(MAX_SHA256_TEXT_BYTES),
            licenseStatus = payloadInput.readNullableBoundedUtf8(MAX_LICENSE_STATUS_BYTES),
            admission = StmSlotAdmission.valueOf(
                payloadInput.readBoundedUtf8(MAX_ENUM_NAME_BYTES),
            ),
            manifestSha256 = ByteArray(SHA256_BYTES).also(payloadInput::readFully).toHex(),
            manifestEntryCount = payloadInput.readInt(),
            totalFileBytes = payloadInput.readLong(),
        )
        require(payloadInput.read() == -1) { "Slot metadata payload contains trailing bytes" }
        metadata.requireValid()
        return metadata
    }

    private fun validateControlDirectory(controlDirectory: Path) {
        require(Files.isDirectory(controlDirectory, LinkOption.NOFOLLOW_LINKS)) {
            "Committed slot metadata directory is missing"
        }
        require(!Files.isSymbolicLink(controlDirectory)) {
            "Committed slot metadata cannot be a symbolic link"
        }
        val names = Files.newDirectoryStream(controlDirectory).use { entries ->
            entries.map { it.fileName.toString() }.toSet()
        }
        require(names == setOf(MANIFEST_FILE, METADATA_FILE)) {
            "Committed slot metadata contains missing or unexpected files"
        }
    }

    private fun deleteTreeNoFollow(target: Path) {
        ensureInside(target, slotsRoot)
        require(target.parent == slotsRoot) { "Only direct slot children may be deleted" }
        Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                ensureInside(directory, slotsRoot)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                ensureInside(file, slotsRoot)
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                error?.let { throw it }
                ensureInside(directory, slotsRoot)
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private data class AcceptanceResult(
        val admission: StmSlotAdmission,
        val reason: String,
    )

    private companion object {
        const val PAYLOAD_DIRECTORY = "payload"
        const val CONTROL_DIRECTORY = ".stm-slot"
        const val MANIFEST_FILE = "content-manifest.bin"
        const val METADATA_FILE = "slot-metadata.bin"
        const val GATE2_MARKER_FILE = "gate2-fixture.txt"
        val GATE2_MARKER_BYTES = "STM_GATE2_SYNTHETIC_V1\n".toByteArray(StandardCharsets.UTF_8)
        val REQUIRED_ST_SOURCE_FILES = listOf("LICENSE", "package-lock.json", "package.json", "server.js")

        const val MANIFEST_MAGIC = 0x53544D46 // STMF
        const val MANIFEST_VERSION = 1
        const val METADATA_MAGIC = 0x53544D53 // STMS
        const val METADATA_VERSION = 3
        const val METADATA_HEADER_BYTES = Int.SIZE_BYTES * 3
        const val SHA256_BYTES = 32
        const val MAX_IDENTIFIER_BYTES = 80
        const val MAX_ENUM_NAME_BYTES = 64
        const val MAX_REPOSITORY_BYTES = 200
        const val MAX_CHANNEL_BYTES = 80
        const val MAX_COMMIT_SHA_BYTES = 64
        const val MAX_DOWNLOAD_URL_BYTES = 2 * 1024
        const val MAX_SHA256_TEXT_BYTES = 64
        const val MAX_CATALOG_VERSION_BYTES = 128
        const val MAX_ARCHIVE_ROOT_BYTES = 1024
        const val MAX_ST_VERSION_BYTES = 128
        const val MAX_NODE_REQUIREMENT_BYTES = 256
        const val MAX_LICENSE_STATUS_BYTES = 256
        const val MAX_RELATIVE_PATH_BYTES = 4 * 1024
        const val MAX_MANIFEST_ENTRIES = 100_000
        const val MAX_SINGLE_FILE_BYTES = 512L * 1024 * 1024
        const val MAX_TOTAL_FILE_BYTES = 4L * 1024 * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 64 * 1024 * 1024
        const val MAX_METADATA_PAYLOAD_BYTES = 8 * 1024
        const val MAX_METADATA_BYTES = METADATA_HEADER_BYTES + MAX_METADATA_PAYLOAD_BYTES + SHA256_BYTES
    }
}

private class SlotValidationException(
    val code: StmSlotBlockCode,
    message: String,
) : IllegalArgumentException(message)

private fun StmSlotManifestEntry.toZipManifestEntry(
    relativePath: String,
): StmZipManifestEntry = StmZipManifestEntry(
    relativePath = relativePath,
    type = when (type) {
        StmSlotManifestEntryType.DIRECTORY -> StmZipManifestEntryType.DIRECTORY
        StmSlotManifestEntryType.FILE -> StmZipManifestEntryType.FILE
    },
    sizeBytes = bytes,
    sha256 = fileSha256,
)

private fun StmSlotCommitRequest.requireValid() {
    requireSafeIdentifier(operationId, "operation ID")
    requireSafeIdentifier(slotId, "slot ID")
    require(slotRevision > 0) { "Slot revision must be positive" }
    toCoreArtifact().requireValidPersistedArtifact()
    runtimeEvidence?.requireValid()
}

private fun StmRuntimeSlotAdmissionEvidence.requireValid() {
    requireBoundedArtifactText(repository, 200, "runtime repository")
    require(commitSha.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}"))) {
        "Runtime evidence commit SHA is invalid"
    }
    require(packageLockSha256.matches(Regex("[0-9a-f]{64}"))) {
        "Runtime evidence package-lock SHA-256 is invalid"
    }
    require(dependencyTreeSha256.matches(Regex("[0-9a-f]{64}"))) {
        "Runtime dependency tree SHA-256 is invalid"
    }
    require(postAdapterProgramTreeSha256.matches(Regex("[0-9a-f]{64}"))) {
        "Runtime program tree SHA-256 is invalid"
    }
    val requiredRuntimeFiles = when (supplyKind) {
        StmRuntimeSupplyKind.SIGNED_PREBUILT ->
            StmRuntimeSlotAdmissionEvidence.SIGNED_PREBUILT_RUNTIME_FILES

        StmRuntimeSupplyKind.DEVICE_LOCAL_BUILD ->
            StmRuntimeSlotAdmissionEvidence.DEVICE_LOCAL_BUILD_RUNTIME_FILES
    }
    require(runtimeFiles.keys == requiredRuntimeFiles) {
        "Runtime evidence has missing or unexpected files"
    }
    runtimeFiles.forEach { (name, binding) ->
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) {
            "Runtime evidence file name is invalid"
        }
        require(binding.bytes in 1..(512L * 1024 * 1024)) {
            "Runtime evidence file length is invalid"
        }
        require(binding.sha256.matches(Regex("[0-9a-f]{64}"))) {
            "Runtime evidence file SHA-256 is invalid"
        }
    }
    if (supplyKind == StmRuntimeSupplyKind.SIGNED_PREBUILT) {
        require(
            runtimeFiles.getValue(StmRuntimeSlotAdmissionEvidence.SIGNATURE_FILE).bytes == 64L,
        ) {
            "Runtime Ed25519 signature length is invalid"
        }
    }
}

private fun StmSlotMetadata.requireValid() {
    requireSafeIdentifier(operationId, "operation ID")
    requireSafeIdentifier(slotId, "slot ID")
    require(slotRevision > 0) { "Slot revision must be positive" }
    toCoreArtifact().requireValidPersistedArtifact()
    if (admission == StmSlotAdmission.READY) {
        require(integrity == StmCoreArtifactIntegrity.VERIFIED) {
            "READY slot metadata requires verified artifact integrity"
        }
        require(trust != StmCoreArtifactTrust.REJECTED) {
            "READY slot metadata cannot contain rejected artifact trust"
        }
    }
    require(manifestSha256.matches(Regex("[0-9a-f]{64}"))) { "Manifest SHA-256 is invalid" }
    require(manifestEntryCount in 0..100_000) { "Manifest entry count is invalid" }
    require(totalFileBytes in 0..(4L * 1024 * 1024 * 1024)) { "Manifest byte total is invalid" }
}

private fun StmSlotCommitRequest.toCoreArtifact() = StmCoreArtifact(
    kind = payloadKind.toCoreArtifactKind(),
    repository = repository,
    channel = channel,
    commitSha = commitSha,
    downloadUrl = downloadUrl,
    downloadedAtEpochMs = downloadedAtEpochMs,
    archiveLength = archiveLength,
    archiveSha256 = archiveSha256,
    integrity = integrity,
    trust = trust,
    catalogVersion = catalogVersion,
    archiveRoot = archiveRoot,
    stVersion = stVersion,
    nodeRequirement = nodeRequirement,
    packageLockSha256 = packageLockSha256,
    licenseStatus = licenseStatus,
)

internal fun StmSlotMetadata.toCoreArtifact(): StmCoreArtifact = StmCoreArtifact(
    kind = payloadKind.toCoreArtifactKind(),
    repository = repository,
    channel = channel,
    commitSha = commitSha,
    downloadUrl = downloadUrl,
    downloadedAtEpochMs = downloadedAtEpochMs,
    archiveLength = archiveLength,
    archiveSha256 = archiveSha256,
    integrity = integrity,
    trust = trust,
    catalogVersion = catalogVersion,
    archiveRoot = archiveRoot,
    stVersion = stVersion,
    nodeRequirement = nodeRequirement,
    packageLockSha256 = packageLockSha256,
    licenseStatus = licenseStatus,
).requireValidPersistedArtifact()

private fun StmSlotPayloadKind.toCoreArtifactKind(): StmCoreArtifactKind =
    StmCoreArtifactKind.valueOf(name)

private fun StmCoreArtifact.requireValidPersistedArtifact(): StmCoreArtifact = apply {
    requireValidArtifact()
    val identity = ArtifactIdentity(
        repository = repository,
        commitSha = commitSha,
        archiveSha256 = archiveSha256,
        archiveLength = archiveLength,
        downloadUrl = downloadUrl,
        catalogVersion = catalogVersion,
        kind = when (kind) {
            StmCoreArtifactKind.GATE2_SYNTHETIC -> ArtifactKind.SYNTHETIC_TEST_ARCHIVE
            StmCoreArtifactKind.SILLY_TAVERN_SOURCE -> ArtifactKind.UPSTREAM_SOURCE_ARCHIVE
        },
    )
    when (val validation = StmArtifactVerifier().validateIdentity(identity)) {
        ArtifactIdentityValidation.Valid -> Unit
        is ArtifactIdentityValidation.Invalid -> throw IllegalArgumentException(validation.detail)
    }
    requireBoundedArtifactText(repository, 200, "repository")
    requireBoundedArtifactText(channel, 80, "channel")
    requireBoundedArtifactText(downloadUrl, 2 * 1024, "download URL")
    catalogVersion?.let { value ->
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(value)) {
            "Artifact catalog version is invalid"
        }
    }
    requireOptionalArtifactText(archiveRoot, 1024, "archive root")
    requireOptionalArtifactText(stVersion, 128, "SillyTavern version")
    requireOptionalArtifactText(nodeRequirement, 256, "Node requirement")
    requireOptionalArtifactText(licenseStatus, 256, "license status")
}

private fun requireBoundedArtifactText(value: String, maximumBytes: Int, label: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(value.isNotBlank() && bytes.size in 1..maximumBytes) {
        "Artifact $label is outside its encoded bound"
    }
    require(value.none { it.isISOControl() }) { "Artifact $label contains control characters" }
}

private fun requireOptionalArtifactText(value: String?, maximumBytes: Int, label: String) {
    value?.let { requireBoundedArtifactText(it, maximumBytes, label) }
}

private fun initializeOwnedRoot(input: Path): Path {
    val absolute = input.toAbsolutePath().normalize()
    if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(absolute)) {
        throw IllegalArgumentException("STM slot roots cannot be symbolic links")
    }
    Files.createDirectories(absolute)
    require(Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
        "STM slot root is not a directory"
    }
    return absolute.toRealPath()
}

private fun resolveChild(root: Path, identifier: String): Path {
    requireSafeIdentifier(identifier, "path identifier")
    val child = root.resolve(identifier).normalize()
    ensureInside(child, root)
    require(child.parent == root) { "Path identifier did not resolve to a direct child" }
    return child
}

private fun resolveOwnedPayload(path: Path, ownedRoot: Path): Path {
    ensureInside(path.toAbsolutePath().normalize(), ownedRoot)
    if (Files.isSymbolicLink(path)) {
        throw SlotValidationException(
            StmSlotBlockCode.UNSAFE_CONTENT,
            "Operation payload cannot be a symbolic link",
        )
    }
    val real = path.toRealPath()
    if (!real.startsWith(ownedRoot)) {
        throw SlotValidationException(
            StmSlotBlockCode.UNSAFE_CONTENT,
            "Operation payload escapes its Core-owned root",
        )
    }
    if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
        throw SlotValidationException(
            StmSlotBlockCode.UNSAFE_CONTENT,
            "Operation payload is not a directory",
        )
    }
    return real
}

private fun ensureInside(candidate: Path, root: Path) {
    val normalized = candidate.toAbsolutePath().normalize()
    require(normalized != root && normalized.startsWith(root)) {
        "Path escapes the Core-owned root"
    }
}

private fun requireSafeIdentifier(value: String, label: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.isNotEmpty() && bytes.size <= 80) { "$label length is outside bounds" }
    require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}").matches(value)) {
        "$label contains forbidden characters"
    }
    require(value != "." && value != ".." && !value.contains("..")) {
        "$label contains a reserved path segment"
    }
}

private fun isSafeIdentifier(value: String): Boolean =
    runCatching {
        requireSafeIdentifier(value, "slot ID")
    }.isSuccess

private fun manifestRelativePath(root: Path, path: Path): String {
    val relative = root.relativize(path.toAbsolutePath().normalize())
    val normalized = relative.joinToString(separator = "/") { segment ->
        val value = Normalizer.normalize(segment.toString(), Normalizer.Form.NFC)
        require(value.isNotBlank() && value != "." && value != ".." && '\\' !in value) {
            "Manifest path contains a forbidden segment"
        }
        value
    }
    validateManifestRelativePath(normalized)
    return normalized
}

private fun validateManifestRelativePath(relativePath: String) {
    require(relativePath.isNotBlank()) { "Manifest path cannot be empty" }
    require(relativePath.toByteArray(StandardCharsets.UTF_8).size <= 4 * 1024) {
        "Manifest path exceeds the allowed length"
    }
    require(!relativePath.startsWith('/') && '\\' !in relativePath) {
        "Manifest path must be relative and use forward slashes"
    }
    require(relativePath.split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "Manifest path contains a forbidden segment"
    }
    require(Normalizer.normalize(relativePath, Normalizer.Form.NFC) == relativePath) {
        "Manifest path is not Unicode-normalized"
    }
}

private fun encodeManifest(entries: List<StmSlotManifestEntry>, totalFileBytes: Long): ByteArray {
    require(entries.size <= 100_000) { "Slot manifest contains too many entries" }
    require(totalFileBytes in 0..(4L * 1024 * 1024 * 1024)) {
        "Slot manifest byte total is outside bounds"
    }
    var encodedSize = Int.SIZE_BYTES * 3 + Long.SIZE_BYTES
    var computedFileBytes = 0L
    var previousPath: String? = null
    entries.forEach { entry ->
        validateManifestRelativePath(entry.relativePath)
        require(previousPath == null || requireNotNull(previousPath) < entry.relativePath) {
            "Slot manifest entries must be strictly sorted"
        }
        previousPath = entry.relativePath
        val pathBytes = entry.relativePath.toByteArray(StandardCharsets.UTF_8).size
        encodedSize = Math.addExact(
            encodedSize,
            1 + Int.SIZE_BYTES + pathBytes + Long.SIZE_BYTES +
                if (entry.type == StmSlotManifestEntryType.FILE) 32 else 0,
        )
        require(encodedSize <= 64 * 1024 * 1024) { "Slot manifest exceeds the allowed size" }
        when (entry.type) {
            StmSlotManifestEntryType.DIRECTORY -> {
                require(entry.bytes == 0L && entry.fileSha256 == null) {
                    "Manifest directories cannot contain file metadata"
                }
            }

            StmSlotManifestEntryType.FILE -> {
                require(entry.bytes in 0..(512L * 1024 * 1024)) {
                    "Manifest file size is outside bounds"
                }
                require(entry.fileSha256?.matches(Regex("[0-9a-f]{64}")) == true) {
                    "Manifest file SHA-256 is invalid"
                }
                computedFileBytes = Math.addExact(computedFileBytes, entry.bytes)
            }
        }
    }
    require(computedFileBytes == totalFileBytes) {
        "Slot manifest byte total does not match its entries"
    }

    return ByteArrayOutputStream(encodedSize).use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(0x53544D46)
            output.writeInt(1)
            output.writeInt(entries.size)
            output.writeLong(totalFileBytes)
            entries.forEach { entry ->
                output.writeByte(entry.type.wireValue)
                output.writeBoundedUtf8(entry.relativePath, 4 * 1024)
                output.writeLong(entry.bytes)
                entry.fileSha256?.let { output.write(it.hexToBytes()) }
            }
            output.flush()
        }
        buffer.toByteArray()
    }
}

private fun encodeMetadata(metadata: StmSlotMetadata): ByteArray {
    metadata.requireValid()
    val payload = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeBoundedUtf8(metadata.operationId, 80)
            output.writeBoundedUtf8(metadata.slotId, 80)
            output.writeLong(metadata.slotRevision)
            output.writeBoundedUtf8(metadata.payloadKind.name, 64)
            output.writeBoundedUtf8(metadata.repository, 200)
            output.writeBoundedUtf8(metadata.channel, 80)
            output.writeBoundedUtf8(metadata.commitSha, 64)
            output.writeBoundedUtf8(metadata.downloadUrl, 2 * 1024)
            output.writeLong(metadata.downloadedAtEpochMs)
            output.writeLong(metadata.archiveLength)
            output.writeBoundedUtf8(metadata.archiveSha256, 64)
            output.writeBoundedUtf8(metadata.integrity.name, 64)
            output.writeBoundedUtf8(metadata.trust.name, 64)
            output.writeNullableBoundedUtf8(metadata.catalogVersion, 128)
            output.writeNullableBoundedUtf8(metadata.archiveRoot, 1024)
            output.writeNullableBoundedUtf8(metadata.stVersion, 128)
            output.writeNullableBoundedUtf8(metadata.nodeRequirement, 256)
            output.writeNullableBoundedUtf8(metadata.packageLockSha256, 64)
            output.writeNullableBoundedUtf8(metadata.licenseStatus, 256)
            output.writeBoundedUtf8(metadata.admission.name, 64)
            output.write(metadata.manifestSha256.hexToBytes())
            output.writeInt(metadata.manifestEntryCount)
            output.writeLong(metadata.totalFileBytes)
            output.flush()
        }
        buffer.toByteArray()
    }
    require(payload.size <= 8 * 1024) { "Slot metadata payload exceeds bounds" }
    return ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(0x53544D53)
            output.writeInt(3)
            output.writeInt(payload.size)
            output.write(payload)
            output.write(sha256(payload))
            output.flush()
        }
        buffer.toByteArray()
    }
}

private fun readBoundedRegularFile(path: Path, maximumBytes: Int): ByteArray {
    require(!Files.isSymbolicLink(path)) { "Controlled slot file cannot be a symbolic link" }
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        "Controlled slot file is missing or not regular"
    }
    val size = Files.size(path)
    require(size in 1..maximumBytes.toLong()) { "Controlled slot file length is outside bounds" }
    return Files.readAllBytes(path).also { bytes ->
        require(bytes.size == size.toInt()) { "Controlled slot file changed while reading" }
    }
}

private fun readSmallRegularFile(path: Path, exactBytes: Int): ByteArray {
    val bytes = readBoundedRegularFile(path, exactBytes)
    require(bytes.size == exactBytes) { "Required fixture file length did not match" }
    return bytes
}

private fun hashRegularFile(path: Path, expectedBytes: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    var count = 0L
    Files.newByteChannel(path, options).use { channel ->
        val buffer = ByteBuffer.allocate(64 * 1024)
        while (true) {
            val read = channel.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            count = Math.addExact(count, read.toLong())
            require(count <= expectedBytes) { "Slot file grew while hashing" }
            buffer.flip()
            digest.update(buffer)
            buffer.clear()
        }
    }
    require(count == expectedBytes) { "Slot file length changed while hashing" }
    val attributes = Files.readAttributes(
        path,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )
    require(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.size() == expectedBytes) {
        "Slot file type or length changed while hashing"
    }
    return digest.digest().toHex()
}

private fun syncRegularFile(path: Path) {
    FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
        channel.force(true)
    }
}

private fun writeAtomicNew(target: Path, bytes: ByteArray) {
    val temporary = requireNotNull(target.parent).resolve(".${target.fileName}.tmp-${UUID.randomUUID()}")
    FileOutputStream(temporary.toFile()).use { output ->
        output.write(bytes)
        output.flush()
        output.fd.sync()
    }
    try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (error: AtomicMoveNotSupportedException) {
        throw IOException("Controlled slot metadata requires atomic replacement", error)
    }
    bestEffortSyncDirectory(requireNotNull(target.parent))
}

private fun bestEffortSyncDirectory(directory: Path) {
    try {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: IOException) {
        // Directory fsync is not exposed by every JVM filesystem provider.
    } catch (_: UnsupportedOperationException) {
        // File sync and atomic moves remain mandatory; only directory sync is best-effort.
    } catch (_: SecurityException) {
        // The store still retains the file-level durability guarantees above.
    }
}

private fun DataOutputStream.writeBoundedUtf8(value: String, maximumBytes: Int) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.isNotEmpty() && bytes.size <= maximumBytes) { "String length is outside bounds" }
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readBoundedUtf8(maximumBytes: Int): String {
    val length = readInt()
    require(length in 1..maximumBytes) { "String length is outside bounds" }
    require(available() >= length) { "String payload is truncated" }
    val bytes = ByteArray(length).also(::readFully)
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return decoder.decode(ByteBuffer.wrap(bytes)).toString()
}

private fun DataOutputStream.writeNullableBoundedUtf8(value: String?, maximumBytes: Int) {
    if (value == null) {
        writeByte(0)
    } else {
        writeByte(1)
        writeBoundedUtf8(value, maximumBytes)
    }
}

private fun DataInputStream.readNullableBoundedUtf8(maximumBytes: Int): String? =
    when (val marker = readUnsignedByte()) {
        0 -> null
        1 -> readBoundedUtf8(maximumBytes)
        else -> throw IllegalArgumentException("Nullable string marker $marker is invalid")
    }

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0 && matches(Regex("[0-9a-f]+"))) { "Hex value is invalid" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(radix = 16).toByte()
    }
}

private fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(radix = 16).padStart(length = 2, padChar = '0')
}

private fun Throwable.safeDetail(): String =
    (message ?: javaClass.simpleName)
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .ifBlank { javaClass.simpleName }
        .take(500)
