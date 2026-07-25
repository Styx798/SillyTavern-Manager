package io.github.styx798.sillytavernmanager.stmcore.testing

import io.github.styx798.sillytavernmanager.stmcore.BuildConfig
import io.github.styx798.sillytavernmanager.stmcore.StmCorePaths
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencyManifestVerification
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencyPayloadVerification
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencyPrunePolicy
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencyRuntimeBinding
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencySourceBinding
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencySupplyCandidate
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencySupplyManifest
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencySupplyManifestVerifier
import io.github.styx798.sillytavernmanager.stmcore.installer.StmExtractionCancellation
import io.github.styx798.sillytavernmanager.stmcore.installer.StmSafeZipExtractor
import io.github.styx798.sillytavernmanager.stmcore.installer.StmZipExtractionResult
import io.github.styx798.sillytavernmanager.stmcore.installer.StmZipManifestEntryType
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal data class Gate3bVerifiedPrebuiltSupply(
    val root: File,
    val manifest: StmDependencySupplyManifest,
    val manifestSha256: String,
    val archiveSha256: String,
)

/**
 * Debug-only signed-prebuilt dependency experiment.
 *
 * The public key below is an ephemeral test trust root. The corresponding private key is not in the
 * repository or APK. Production trust-root provisioning remains a separate Stage 3B decision.
 */
internal class StmCoreGate3bPrebuiltExperiment(
    context: android.content.Context,
) : StmCoreGate3bExperimentRunner {
    private val appContext = context.applicationContext
    private val cancelled = AtomicBoolean(false)

    override fun cancel() {
        cancelled.set(true)
    }

    internal fun verifySupply(): Gate3bVerifiedPrebuiltSupply {
        val supplyRoot = requireSupplyRoot()
        val verifier = createVerifier()
        val manifestBytes = readBoundedRegularFile(
            File(supplyRoot, MANIFEST_FILE),
            MAX_MANIFEST_BYTES,
        )
        val signatureBytes = readBoundedRegularFile(
            File(supplyRoot, SIGNATURE_FILE),
            ED25519_SIGNATURE_BYTES,
        )
        val verification = verifier.verify(
            manifestBytes = manifestBytes,
            detachedSignature = signatureBytes,
            expectedSource = StmDependencySourceBinding(
                repository = ST_REPOSITORY,
                commitSha = ST_COMMIT,
                packageLockSha256 = PACKAGE_LOCK_SHA256,
            ),
            expectedRuntime = StmDependencyRuntimeBinding(
                nodeVersion = DEVICE_NODE_VERSION,
                javetCoordinate = javetCoordinate(),
                abi = DEVICE_ABI,
            ),
        )
        check(verification is StmDependencyManifestVerification.Verified) {
            val rejected = verification as StmDependencyManifestVerification.Rejected
            "${rejected.code}:${rejected.detail}"
        }
        requireManifestPolicy(verification.manifest)
        verifyFixedSourceBindings(verifier, verification.manifest)
        verifySupplySidecars(verifier, verification.manifest, supplyRoot)
        val archiveVerification = requirePayload(
            verifier = verifier,
            file = File(supplyRoot, DEPENDENCIES_ARCHIVE_FILE),
            expectedBytes = verification.manifest.dependenciesArchiveBytes,
            expectedSha256 = verification.manifest.dependenciesArchiveSha256,
            maximumBytes = MAX_ARCHIVE_BYTES,
        )
        return Gate3bVerifiedPrebuiltSupply(
            root = supplyRoot,
            manifest = verification.manifest,
            manifestSha256 = verification.canonicalSha256,
            archiveSha256 = archiveVerification.sha256,
        )
    }

    override fun run(): Map<String, String> {
        val started = android.os.SystemClock.elapsedRealtime()
        val slotsBefore = captureGate3bCommittedSlotIdentity(appContext)
        val activePointerBefore = captureGate3bFileIdentity(
            StmCorePaths.activeSlotFile(appContext),
            "Stage 3B prebuilt active-slot pointer",
        )
        val sampler = Gate3bMemorySampler().also(Gate3bMemorySampler::start)
        val operationParent = File(
            StmCorePaths.cacheRoot(appContext),
            "experiments/gate3b/work",
        ).canonicalFile
        Files.createDirectories(operationParent.toPath())
        val operationRoot = File(
            operationParent,
            "signed-prebuilt-${UUID.randomUUID()}",
        ).absoluteFile
        var cleanup = "not_attempted"
        var manifestSha256 = ""
        var archiveSha256 = ""
        var treeSha256 = ""
        var files = 0
        var directories = 0
        var bytes = 0L
        var failure = ""
        var manifest: StmDependencySupplyManifest? = null

        try {
            val verified = verifySupply()
            manifest = verified.manifest
            manifestSha256 = verified.manifestSha256
            archiveSha256 = verified.archiveSha256
            val archive = File(verified.root, DEPENDENCIES_ARCHIVE_FILE)
            check(!cancelled.get()) { "Signed-prebuilt experiment was cancelled" }
            val extraction = StmSafeZipExtractor().extract(
                artifact = archive,
                operationStagingRoot = operationRoot,
                cancellation = StmExtractionCancellation(cancelled::get),
            )
            requireExtractedTree(extraction, verified.manifest)
            val expectedTreeManifest = readBoundedRegularFile(
                File(verified.root, TREE_MANIFEST_FILE),
                MAX_TREE_MANIFEST_BYTES,
            )
            val actualTreeManifest = encodeTreeManifest(extraction)
            check(MessageDigest.isEqual(expectedTreeManifest, actualTreeManifest)) {
                "Extracted dependency tree did not match the signed tree manifest"
            }
            treeSha256 = extraction.manifestSha256
            files = extraction.fileCount
            directories = extraction.directoryCount
            bytes = extraction.totalFileBytes
        } catch (error: Exception) {
            failure = error.safePrebuiltDetail()
        } finally {
            sampler.close()
            cleanup = runCatching {
                deleteExactTree(operationRoot.toPath(), operationParent.toPath())
                "removed"
            }.getOrElse { error ->
                "retained:${error.safePrebuiltDetail()}"
            }
        }

        val slotsAfter = captureGate3bCommittedSlotIdentity(appContext)
        val activePointerAfter = captureGate3bFileIdentity(
            StmCorePaths.activeSlotFile(appContext),
            "Stage 3B prebuilt active-slot pointer",
        )

        return linkedMapOf(
            "result" to if (failure.isBlank()) "passed" else "failed",
            "candidate" to StmDependencySupplyCandidate.SIGNED_PREBUILT.name,
            "st_commit" to ST_COMMIT,
            "package_lock_sha256" to PACKAGE_LOCK_SHA256,
            "supply_id" to manifest?.supplyId.orEmpty(),
            "signing_key_id" to manifest?.signingKeyId.orEmpty(),
            "manifest_sha256" to manifestSha256,
            "archive_sha256" to archiveSha256,
            "dependency_tree_sha256" to treeSha256,
            "dependency_tree_files" to files.toString(),
            "dependency_tree_directories" to directories.toString(),
            "dependency_tree_symlinks" to "0",
            "dependency_tree_bytes" to bytes.toString(),
            "post_adapter_tree_validation" to "pending_real_staging",
            "elapsed_ms" to (android.os.SystemClock.elapsedRealtime() - started).toString(),
            "peak_rss_kb" to sampler.peakRssKilobytes.get().toString(),
            "vm_hwm_kb" to sampler.maximumVmHwmKilobytes.get().toString(),
            "cleanup" to cleanup,
            "committed_slots_unchanged" to (slotsBefore == slotsAfter).toString(),
            "active_slot_pointer_unchanged" to
                (activePointerBefore == activePointerAfter).toString(),
            "failure" to failure.take(MAX_RESULT_CHARS),
        )
    }

    private fun requireSupplyRoot(): File {
        val expectedParent = File(
            StmCorePaths.cacheRoot(appContext),
            "experiments/gate3b/prebuilt-supplies",
        ).canonicalFile
        val root = File(expectedParent, DEBUG_SUPPLY_ID).absoluteFile
        check(!Files.isSymbolicLink(root.toPath())) { "Prebuilt supply root is a symbolic link" }
        val canonical = root.canonicalFile
        check(canonical.parentFile == expectedParent && canonical.name == DEBUG_SUPPLY_ID) {
            "Prebuilt supply root escaped its fixed cache parent"
        }
        check(Files.isDirectory(canonical.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Prebuilt supply was not externally staged"
        }
        val names = Files.list(canonical.toPath()).use { stream ->
            stream.iterator().asSequence().map { it.fileName.toString() }.sorted().toList()
        }
        check(names == EXPECTED_SUPPLY_FILES) {
            "Prebuilt supply contains unexpected files: $names"
        }
        return canonical
    }

    private fun createVerifier(): StmDependencySupplyManifestVerifier {
        val encoded = Base64.getDecoder().decode(DEBUG_PUBLIC_KEY_DER_BASE64)
        val publicKey = EncodedEd25519PublicKey(encoded)
        return StmDependencySupplyManifestVerifier { keyId ->
            publicKey.takeIf { keyId == DEBUG_SIGNING_KEY_ID }
        }
    }

    private fun requireManifestPolicy(manifest: StmDependencySupplyManifest) {
        check(manifest.supplyId == DEBUG_SUPPLY_ID) { "Unexpected prebuilt supply ID" }
        check(manifest.signingKeyId == DEBUG_SIGNING_KEY_ID) {
            "Unexpected prebuilt signing key ID"
        }
        check(manifest.prunePolicy == StmDependencyPrunePolicy.LOCKFILE_COMPLETE) {
            "First Stage 3B prebuilt experiment must preserve the lockfile-complete tree"
        }
        check(manifest.dependencyTreeSymlinkCount == 0) {
            "Signed prebuilt dependency tree must not contain symbolic links"
        }
    }

    private fun verifyFixedSourceBindings(
        verifier: StmDependencySupplyManifestVerifier,
        manifest: StmDependencySupplyManifest,
    ) {
        val program = File(
            StmCorePaths.cacheRoot(appContext),
            "experiments/gate3a/$ST_COMMIT/program",
        ).canonicalFile
        check(program.isDirectory && !File(program, ".git").exists()) {
            "Fixed Gate 3A source program is unavailable"
        }
        requirePayload(
            verifier,
            File(program, "webpack.config.js"),
            File(program, "webpack.config.js").length(),
            manifest.webpackConfigSha256,
            MAX_SOURCE_FILE_BYTES,
        )
        requirePayload(
            verifier,
            File(program, "docker/build-lib.js"),
            File(program, "docker/build-lib.js").length(),
            manifest.buildLibSha256,
            MAX_SOURCE_FILE_BYTES,
        )
    }

    private fun verifySupplySidecars(
        verifier: StmDependencySupplyManifestVerifier,
        manifest: StmDependencySupplyManifest,
        root: File,
    ) {
        requirePayload(
            verifier,
            File(root, TREE_MANIFEST_FILE),
            manifest.treeManifestBytes,
            manifest.treeManifestSha256,
            MAX_TREE_MANIFEST_BYTES,
        )
        requirePayload(
            verifier,
            File(root, SBOM_FILE),
            manifest.sbomBytes,
            manifest.sbomSha256,
            MAX_SIDECAR_BYTES,
        )
        requirePayload(
            verifier,
            File(root, LICENSE_MANIFEST_FILE),
            manifest.licenseManifestBytes,
            manifest.licenseManifestSha256,
            MAX_SIDECAR_BYTES,
        )
        requirePayload(
            verifier,
            File(root, BUNDLE_FILE),
            manifest.bundleBytes,
            manifest.bundleSha256,
            MAX_BUNDLE_BYTES,
        )
        requirePayload(
            verifier,
            File(root, BUNDLE_LICENSE_FILE),
            manifest.bundleLicenseBytes,
            manifest.bundleLicenseSha256,
            MAX_SIDECAR_BYTES,
        )
        val adapter = File(root, ADAPTER_FILE)
        requirePayload(
            verifier,
            adapter,
            adapter.length(),
            manifest.adapterSha256,
            MAX_SOURCE_FILE_BYTES,
        )
        val prunePolicy = File(root, PRUNE_POLICY_FILE)
        requirePayload(
            verifier,
            prunePolicy,
            prunePolicy.length(),
            manifest.prunePolicySha256,
            MAX_SOURCE_FILE_BYTES,
        )
        validateAuditJson(
            sbom = readBoundedRegularFile(File(root, SBOM_FILE), MAX_SIDECAR_BYTES),
            licenses = readBoundedRegularFile(
                File(root, LICENSE_MANIFEST_FILE),
                MAX_SIDECAR_BYTES,
            ),
        )
    }

    private fun validateAuditJson(sbom: ByteArray, licenses: ByteArray) {
        val sbomJson = JSONObject(sbom.toString(StandardCharsets.UTF_8))
        check(
            sbomJson.getString("bomFormat") == "CycloneDX" &&
                sbomJson.getJSONObject("metadata").getJSONObject("component")
                    .getString("version") == ST_VERSION,
        ) {
            "Signed SBOM did not identify the fixed SillyTavern version"
        }
        val licenseJson = JSONObject(licenses.toString(StandardCharsets.UTF_8))
        check(
            licenseJson.getInt("schemaVersion") == 1 &&
                licenseJson.getString("stCommit") == ST_COMMIT &&
                licenseJson.getString("packageLockSha256") == PACKAGE_LOCK_SHA256 &&
                licenseJson.getJSONArray("packages").length() > 0,
        ) {
            "Signed license manifest did not bind the fixed SillyTavern supply"
        }
    }

    private fun requireExtractedTree(
        extraction: StmZipExtractionResult,
        manifest: StmDependencySupplyManifest,
    ) {
        check(
            extraction.fileCount == manifest.dependencyTreeFileCount &&
                extraction.directoryCount == manifest.dependencyTreeDirectoryCount &&
                extraction.totalFileBytes == manifest.dependencyTreeBytes &&
                extraction.manifestSha256 == manifest.dependencyTreeSha256,
        ) {
            "Extracted dependency tree did not match the signed manifest"
        }
        check(
            extraction.entries.all { entry ->
                entry.relativePath == "node_modules" ||
                    entry.relativePath.startsWith("node_modules/")
            },
        ) {
            "Dependency archive contains data outside node_modules"
        }
    }

    private fun encodeTreeManifest(extraction: StmZipExtractionResult): ByteArray =
        buildString {
            append(TREE_MANIFEST_MAGIC)
            append('\n')
            extraction.entries.forEach { entry ->
                when (entry.type) {
                    StmZipManifestEntryType.DIRECTORY -> {
                        append("D\t")
                        append(entry.relativePath)
                        append('\n')
                    }

                    StmZipManifestEntryType.FILE -> {
                        append("F\t")
                        append(entry.relativePath)
                        append('\t')
                        append(entry.sizeBytes)
                        append('\t')
                        append(entry.sha256)
                        append('\n')
                    }
                }
            }
        }.toByteArray(StandardCharsets.UTF_8)

    private fun requirePayload(
        verifier: StmDependencySupplyManifestVerifier,
        file: File,
        expectedBytes: Long,
        expectedSha256: String,
        maximumBytes: Long,
    ): StmDependencyPayloadVerification.Verified {
        val result = verifier.verifyPayload(
            file = file,
            expectedBytes = expectedBytes,
            expectedSha256 = expectedSha256,
            maximumBytes = maximumBytes,
        )
        check(result is StmDependencyPayloadVerification.Verified) {
            val rejected = result as StmDependencyPayloadVerification.Rejected
            "${rejected.code}:${rejected.detail}"
        }
        return result
    }

    private fun readBoundedRegularFile(file: File, maximumBytes: Long): ByteArray {
        val path = file.toPath()
        check(
            !Files.isSymbolicLink(path) &&
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                file.length() in 1..maximumBytes,
        ) {
            "Unsafe or oversized prebuilt supply file: ${file.name}"
        }
        return Files.readAllBytes(path)
    }

    private fun javetCoordinate(): String =
        "com.caoccao.javet:${BuildConfig.JAVET_ARTIFACT}:5.0.9"

    private fun deleteExactTree(root: Path, parent: Path) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedParent = parent.toAbsolutePath().normalize()
        check(normalizedRoot.parent == normalizedParent && normalizedRoot != normalizedParent) {
            "Prebuilt cleanup target escaped its exact owned parent"
        }
        if (!Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) return
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

    companion object {
        const val ST_REPOSITORY = "https://github.com/SillyTavern/SillyTavern"
        const val ST_COMMIT = "8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8"
        const val ST_VERSION = "1.18.0"
        const val PACKAGE_LOCK_SHA256 =
            "7484f87e7dc6e99044ad532b80111c3e93463aaf1d5dbe377b3a4486bfe65f6f"
        const val DEVICE_NODE_VERSION = "v24.17.0"
        const val DEVICE_ABI = "arm64-v8a"
        const val DEBUG_SUPPLY_ID = "st-1.18.0-arm64-debug-20260723-v2"
        const val DEBUG_SIGNING_KEY_ID = "stage3b-debug-20260723-v2"
        const val DEBUG_PUBLIC_KEY_DER_BASE64 =
            "MCowBQYDK2VwAyEAyJHK40Pq+e5OizFf4xGwgmoU24sc4yD4/2FCUIxRlh8="
        const val MANIFEST_FILE = "manifest.stm"
        const val SIGNATURE_FILE = "manifest.sig"
        const val DEPENDENCIES_ARCHIVE_FILE = "dependencies.zip"
        const val TREE_MANIFEST_FILE = "tree-manifest.tsv"
        const val SBOM_FILE = "sbom.cdx.json"
        const val LICENSE_MANIFEST_FILE = "licenses.json"
        const val BUNDLE_FILE = "lib.js"
        const val BUNDLE_LICENSE_FILE = "lib.js.LICENSE.txt"
        const val ADAPTER_FILE = "webpack-serve.adapter.js"
        const val PRUNE_POLICY_FILE = "prune-policy.txt"
        const val TREE_MANIFEST_MAGIC = "STM_DEPENDENCY_TREE_MANIFEST_V1"
        const val MAX_MANIFEST_BYTES = 32L * 1024L
        const val ED25519_SIGNATURE_BYTES = 64L
        const val MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L
        const val MAX_TREE_MANIFEST_BYTES = 16L * 1024L * 1024L
        const val MAX_SIDECAR_BYTES = 64L * 1024L * 1024L
        const val MAX_BUNDLE_BYTES = 16L * 1024L * 1024L
        const val MAX_SOURCE_FILE_BYTES = 4L * 1024L * 1024L
        const val MAX_RESULT_CHARS = 2_000
        val EXPECTED_SUPPLY_FILES = listOf(
            ADAPTER_FILE,
            DEPENDENCIES_ARCHIVE_FILE,
            BUNDLE_FILE,
            BUNDLE_LICENSE_FILE,
            LICENSE_MANIFEST_FILE,
            MANIFEST_FILE,
            PRUNE_POLICY_FILE,
            SBOM_FILE,
            SIGNATURE_FILE,
            TREE_MANIFEST_FILE,
        ).sorted()
    }
}

internal class EncodedEd25519PublicKey(
    encoded: ByteArray,
) : PublicKey {
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
        .take(500)
