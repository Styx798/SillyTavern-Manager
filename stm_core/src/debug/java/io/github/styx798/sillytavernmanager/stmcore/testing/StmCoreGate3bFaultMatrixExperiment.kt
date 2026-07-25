package io.github.styx798.sillytavernmanager.stmcore.testing

import io.github.styx798.sillytavernmanager.stmcore.BuildConfig
import io.github.styx798.sillytavernmanager.stmcore.StmCorePaths
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencyManifestErrorCode
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencyManifestVerification
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencyPayloadVerification
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencyRuntimeBinding
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencySourceBinding
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencySupplyManifestVerifier
import io.github.styx798.sillytavernmanager.stmcore.installer.DefaultStmZipSinkFactory
import io.github.styx798.sillytavernmanager.stmcore.installer.StmExtractionCancellation
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSafeZipExtractor
import io.github.styx798.sillytavernmanager.stmcore.installer.StmZipErrorCode
import io.github.styx798.sillytavernmanager.stmcore.installer.StmZipExtractionException
import io.github.styx798.sillytavernmanager.stmcore.installer.StmZipSink
import io.github.styx798.sillytavernmanager.stmcore.installer.StmZipSinkFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** API 31 rejection matrix for the signed Stage 3B supply boundary. */
internal class StmCoreGate3bFaultMatrixExperiment(
    context: android.content.Context,
) : StmCoreGate3bExperimentRunner {
    private val appContext = context.applicationContext
    private val cancelled = AtomicBoolean(false)

    override fun cancel() {
        cancelled.set(true)
    }

    override fun run(): Map<String, String> {
        val supply = requireSupplyRoot()
        val manifestBytes = readBounded(File(supply, MANIFEST_FILE), MAX_MANIFEST_BYTES)
        val signatureBytes = readBounded(File(supply, SIGNATURE_FILE), SIGNATURE_BYTES)
        val publicKey = EncodedEd25519PublicKey(
            Base64.getDecoder().decode(DEBUG_PUBLIC_KEY_DER_BASE64),
        )
        val verifier = StmDependencySupplyManifestVerifier { keyId ->
            publicKey.takeIf { keyId == DEBUG_SIGNING_KEY_ID }
        }
        val source = StmDependencySourceBinding(
            repository = ST_REPOSITORY,
            commitSha = ST_COMMIT,
            packageLockSha256 = PACKAGE_LOCK_SHA256,
        )
        val runtime = StmDependencyRuntimeBinding(
            nodeVersion = DEVICE_NODE_VERSION,
            javetCoordinate = javetCoordinate(),
            abi = DEVICE_ABI,
        )
        check(!cancelled.get()) { "Stage 3B fault matrix was cancelled" }
        val valid = verifier.verify(manifestBytes, signatureBytes, source, runtime)
        check(valid is StmDependencyManifestVerification.Verified) {
            "Untampered signed supply did not pass before fault injection"
        }

        val signatureTampered = signatureBytes.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }
        expectRejected(
            verifier.verify(manifestBytes, signatureTampered, source, runtime),
            StmDependencyManifestErrorCode.SIGNATURE_MISMATCH,
        )
        expectRejected(
            verifier.verify(
                manifestBytes,
                signatureBytes,
                source.copy(commitSha = "0".repeat(40)),
                runtime,
            ),
            StmDependencyManifestErrorCode.SOURCE_BINDING_MISMATCH,
        )
        expectRejected(
            verifier.verify(
                manifestBytes,
                signatureBytes,
                source,
                runtime.copy(abi = "x86_64"),
            ),
            StmDependencyManifestErrorCode.RUNTIME_BINDING_MISMATCH,
        )
        expectRejected(
            verifier.verify(
                manifestBytes + 0.toByte(),
                signatureBytes,
                source,
                runtime,
            ),
            StmDependencyManifestErrorCode.MANIFEST_FORMAT_INVALID,
        )
        val payload = verifier.verifyPayload(
            file = File(supply, BUNDLE_FILE),
            expectedBytes = valid.manifest.bundleBytes,
            expectedSha256 = "0".repeat(64),
            maximumBytes = MAX_BUNDLE_BYTES,
        )
        check(
            payload is StmDependencyPayloadVerification.Rejected &&
                payload.code == StmDependencyManifestErrorCode.PAYLOAD_SHA256_MISMATCH
        ) {
            "Bundle SHA-256 fault did not fail closed: $payload"
        }
        val dependencyArchive = File(supply, DEPENDENCIES_ARCHIVE_FILE)
        val archiveControl = verifier.verifyPayload(
            file = dependencyArchive,
            expectedBytes = valid.manifest.dependenciesArchiveBytes,
            expectedSha256 = valid.manifest.dependenciesArchiveSha256,
            maximumBytes = MAX_DEPENDENCY_ARCHIVE_BYTES,
        )
        check(archiveControl is StmDependencyPayloadVerification.Verified) {
            "Untampered dependency archive did not pass before extraction fault injection"
        }

        val slotsBefore = captureGate3bCommittedSlotIdentity(appContext)
        val activeBefore = captureGate3bFileIdentity(
            StmCorePaths.activeSlotFile(appContext),
            "Stage 3B fault-matrix active-slot pointer",
        )
        val operationParent = File(
            StmCorePaths.cacheRoot(appContext),
            "experiments/gate3b/fault-work",
        ).canonicalFile
        Files.createDirectories(operationParent.toPath())
        check(
            !Files.isSymbolicLink(operationParent.toPath()) &&
                Files.isDirectory(operationParent.toPath(), LinkOption.NOFOLLOW_LINKS),
        ) {
            "Stage 3B extraction fault root is unsafe"
        }

        val cancelledBytes = AtomicLong(0)
        val cancellationRoot = File(operationParent, "cancel-${UUID.randomUUID()}")
        val countingFactory = StmZipSinkFactory { path ->
            val delegate = DefaultStmZipSinkFactory.open(path)
            object : StmZipSink {
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    delegate.write(buffer, offset, length)
                    cancelledBytes.addAndGet(length.toLong())
                }

                override fun sync() = delegate.sync()

                override fun close() = delegate.close()
            }
        }
        expectExtractionFailure(StmZipErrorCode.OPERATION_CANCELLED, cancellationRoot) {
            StmSafeZipExtractor(countingFactory).extract(
                artifact = dependencyArchive,
                operationStagingRoot = cancellationRoot,
                cancellation = StmExtractionCancellation {
                    cancelled.get() || cancelledBytes.get() >= CANCEL_AFTER_BYTES
                },
            )
        }
        check(cancelledBytes.get() >= CANCEL_AFTER_BYTES) {
            "Cancellation fault did not reach dependency payload extraction"
        }

        val noSpaceBytes = AtomicLong(0)
        val noSpaceRoot = File(operationParent, "enospc-${UUID.randomUUID()}")
        val noSpaceFactory = StmZipSinkFactory { path ->
            val delegate = DefaultStmZipSinkFactory.open(path)
            object : StmZipSink {
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    if (noSpaceBytes.get() >= ENOSPC_AFTER_BYTES) throw IOException("ENOSPC")
                    delegate.write(buffer, offset, length)
                    noSpaceBytes.addAndGet(length.toLong())
                }

                override fun sync() = delegate.sync()

                override fun close() = delegate.close()
            }
        }
        expectExtractionFailure(StmZipErrorCode.STORAGE_NO_SPACE, noSpaceRoot) {
            StmSafeZipExtractor(noSpaceFactory).extract(
                artifact = dependencyArchive,
                operationStagingRoot = noSpaceRoot,
            )
        }
        check(noSpaceBytes.get() >= ENOSPC_AFTER_BYTES) {
            "ENOSPC fault did not reach dependency payload extraction"
        }
        check(Files.list(operationParent.toPath()).use { children -> !children.findAny().isPresent }) {
            "Stage 3B extraction fault matrix left operation residue"
        }
        check(captureGate3bCommittedSlotIdentity(appContext) == slotsBefore) {
            "Stage 3B extraction fault matrix changed committed slot content or metadata"
        }
        check(
            captureGate3bFileIdentity(
                StmCorePaths.activeSlotFile(appContext),
                "Stage 3B fault-matrix active-slot pointer",
            ) == activeBefore,
        ) {
            "Stage 3B extraction fault matrix changed the active-slot pointer"
        }

        return linkedMapOf(
            "result" to "passed",
            "valid_control" to "verified",
            "signature_tamper" to StmDependencyManifestErrorCode.SIGNATURE_MISMATCH.name,
            "source_mismatch" to StmDependencyManifestErrorCode.SOURCE_BINDING_MISMATCH.name,
            "runtime_mismatch" to StmDependencyManifestErrorCode.RUNTIME_BINDING_MISMATCH.name,
            "manifest_format" to StmDependencyManifestErrorCode.MANIFEST_FORMAT_INVALID.name,
            "payload_tamper" to StmDependencyManifestErrorCode.PAYLOAD_SHA256_MISMATCH.name,
            "extraction_cancel" to StmZipErrorCode.OPERATION_CANCELLED.name,
            "extraction_cancel_after_bytes" to cancelledBytes.get().toString(),
            "extraction_enospc" to StmZipErrorCode.STORAGE_NO_SPACE.name,
            "extraction_enospc_after_bytes" to noSpaceBytes.get().toString(),
            "extraction_cleanup" to "removed",
            "committed_slots" to "unchanged",
            "active_slot_pointer" to "unchanged",
            "faults_rejected" to "7",
        )
    }

    private fun expectExtractionFailure(
        expected: StmZipErrorCode,
        operationRoot: File,
        block: () -> Unit,
    ) {
        val error = runCatching(block).exceptionOrNull()
        check(error is StmZipExtractionException && error.code == expected) {
            "Expected extraction fault $expected but received $error"
        }
        check(!Files.exists(operationRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Failed extraction left its owned operation root"
        }
    }

    private fun expectRejected(
        result: StmDependencyManifestVerification,
        expected: StmDependencyManifestErrorCode,
    ) {
        check(
            result is StmDependencyManifestVerification.Rejected &&
                result.code == expected
        ) {
            "Expected $expected but received $result"
        }
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

    private fun readBounded(file: File, maximumBytes: Long): ByteArray {
        val path = file.toPath()
        check(
            !Files.isSymbolicLink(path) &&
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                file.length() in 1..maximumBytes
        ) {
            "Fault-matrix input is missing or unsafe: ${file.name}"
        }
        return Files.readAllBytes(path)
    }

    private fun javetCoordinate(): String =
        "com.caoccao.javet:${BuildConfig.JAVET_ARTIFACT}:5.0.9"

    private companion object {
        const val ST_REPOSITORY = StmCoreGate3bPrebuiltExperiment.ST_REPOSITORY
        const val ST_COMMIT = StmCoreGate3bPrebuiltExperiment.ST_COMMIT
        const val PACKAGE_LOCK_SHA256 =
            StmCoreGate3bPrebuiltExperiment.PACKAGE_LOCK_SHA256
        const val DEVICE_NODE_VERSION = StmCoreGate3bPrebuiltExperiment.DEVICE_NODE_VERSION
        const val DEVICE_ABI = StmCoreGate3bPrebuiltExperiment.DEVICE_ABI
        const val DEBUG_SUPPLY_ID = StmCoreGate3bPrebuiltExperiment.DEBUG_SUPPLY_ID
        const val DEBUG_SIGNING_KEY_ID =
            StmCoreGate3bPrebuiltExperiment.DEBUG_SIGNING_KEY_ID
        const val DEBUG_PUBLIC_KEY_DER_BASE64 =
            StmCoreGate3bPrebuiltExperiment.DEBUG_PUBLIC_KEY_DER_BASE64
        const val MANIFEST_FILE = "manifest.stm"
        const val SIGNATURE_FILE = "manifest.sig"
        const val BUNDLE_FILE = "lib.js"
        const val DEPENDENCIES_ARCHIVE_FILE = "dependencies.zip"
        const val MAX_MANIFEST_BYTES = 32L * 1024L
        const val SIGNATURE_BYTES = 64L
        const val MAX_BUNDLE_BYTES = 16L * 1024L * 1024L
        const val MAX_DEPENDENCY_ARCHIVE_BYTES = 512L * 1024L * 1024L
        const val CANCEL_AFTER_BYTES = 256L * 1024L
        const val ENOSPC_AFTER_BYTES = 256L * 1024L
    }
}
