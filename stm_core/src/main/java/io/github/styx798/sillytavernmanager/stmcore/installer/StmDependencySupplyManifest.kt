package io.github.styx798.sillytavernmanager.stmcore.installer

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Locale

internal enum class StmDependencySupplyCandidate {
    NPM_CLI,
    ARBORIST,
    SIGNED_PREBUILT,
}

internal enum class StmDependencyPrunePolicy {
    /** Preserve every production dependency selected by the fixed upstream lockfile. */
    LOCKFILE_COMPLETE,

    /** Apply a separately hashed, version-bound policy that removes runtime-dead build tooling. */
    VERSION_BOUND_PRUNED_WEBPACK,
}

/**
 * The complete identity of one signed, prebuilt Stage 3B dependency supply.
 *
 * Timestamps and host paths are deliberately absent: they would make equal content differ across
 * otherwise reproducible builds. Every externally stored payload is instead bound by byte length
 * and SHA-256, while the final dependency tree and post-adapter program tree have independent
 * content identities.
 */
internal data class StmDependencySupplyManifest(
    val schemaVersion: Int,
    val supplyId: String,
    val repository: String,
    val stCommitSha: String,
    val packageLockSha256: String,
    val buildNodeVersion: String,
    val buildNpmVersion: String,
    val buildImageDigest: String,
    val webpackConfigSha256: String,
    val buildLibSha256: String,
    val adapterSha256: String,
    val prunePolicy: StmDependencyPrunePolicy,
    val prunePolicySha256: String,
    val dependenciesArchiveSha256: String,
    val dependenciesArchiveBytes: Long,
    val dependencyTreeSha256: String,
    val dependencyTreeFileCount: Int,
    val dependencyTreeDirectoryCount: Int,
    val dependencyTreeSymlinkCount: Int,
    val dependencyTreeBytes: Long,
    val treeManifestSha256: String,
    val treeManifestBytes: Long,
    val sbomSha256: String,
    val sbomBytes: Long,
    val licenseManifestSha256: String,
    val licenseManifestBytes: Long,
    val bundleSha256: String,
    val bundleBytes: Long,
    val bundleLicenseSha256: String,
    val bundleLicenseBytes: Long,
    val postAdapterProgramTreeSha256: String,
    val signingKeyId: String,
    val deviceNodeVersion: String,
    val deviceJavetCoordinate: String,
    val deviceAbi: String,
)

internal data class StmDependencySourceBinding(
    val repository: String,
    val commitSha: String,
    val packageLockSha256: String,
)

internal data class StmDependencyRuntimeBinding(
    val nodeVersion: String,
    val javetCoordinate: String,
    val abi: String,
)

internal enum class StmDependencyManifestErrorCode {
    MANIFEST_FORMAT_INVALID,
    MANIFEST_FIELD_INVALID,
    SOURCE_BINDING_MISMATCH,
    RUNTIME_BINDING_MISMATCH,
    TRUSTED_KEY_NOT_FOUND,
    SIGNATURE_FORMAT_INVALID,
    SIGNATURE_MISMATCH,
    CRYPTOGRAPHIC_FAILURE,
    PAYLOAD_MISSING,
    PAYLOAD_UNSAFE,
    PAYLOAD_LENGTH_MISMATCH,
    PAYLOAD_SHA256_MISMATCH,
    PAYLOAD_IO_FAILURE,
}

internal sealed interface StmDependencyManifestVerification {
    data class Verified(
        val manifest: StmDependencySupplyManifest,
        val canonicalSha256: String,
    ) : StmDependencyManifestVerification

    data class Rejected(
        val code: StmDependencyManifestErrorCode,
        val detail: String,
    ) : StmDependencyManifestVerification
}

internal sealed interface StmDependencyPayloadVerification {
    data class Verified(
        val bytes: Long,
        val sha256: String,
    ) : StmDependencyPayloadVerification

    data class Rejected(
        val code: StmDependencyManifestErrorCode,
        val detail: String,
        val observedBytes: Long? = null,
        val observedSha256: String? = null,
    ) : StmDependencyPayloadVerification
}

internal fun interface StmDependencyTrustedKeyResolver {
    fun resolve(signingKeyId: String): PublicKey?
}

/**
 * Strict codec and verifier for the signed Stage 3B prebuilt supply boundary.
 *
 * The manifest is a fixed-order UTF-8 record rather than free-form JSON. Decoding accepts only the
 * exact canonical representation that [encode] produces, so field ordering, duplicate keys,
 * numeric spelling, line endings and trailing bytes cannot create alternate signed meanings.
 */
internal class StmDependencySupplyManifestVerifier(
    private val trustedKeyResolver: StmDependencyTrustedKeyResolver,
) {
    fun encode(manifest: StmDependencySupplyManifest): ByteArray {
        validateFields(manifest)
        return buildString {
            appendLine(MAGIC)
            appendLine("schema_version=${manifest.schemaVersion}")
            appendLine("supply_id=${manifest.supplyId}")
            appendLine("repository=${manifest.repository}")
            appendLine("st_commit_sha=${manifest.stCommitSha.lowercase(Locale.ROOT)}")
            appendLine("package_lock_sha256=${manifest.packageLockSha256.lowercase(Locale.ROOT)}")
            appendLine("build_node_version=${manifest.buildNodeVersion}")
            appendLine("build_npm_version=${manifest.buildNpmVersion}")
            appendLine("build_image_digest=${manifest.buildImageDigest.lowercase(Locale.ROOT)}")
            appendLine(
                "webpack_config_sha256=${manifest.webpackConfigSha256.lowercase(Locale.ROOT)}",
            )
            appendLine("build_lib_sha256=${manifest.buildLibSha256.lowercase(Locale.ROOT)}")
            appendLine("adapter_sha256=${manifest.adapterSha256.lowercase(Locale.ROOT)}")
            appendLine("prune_policy=${manifest.prunePolicy.name}")
            appendLine("prune_policy_sha256=${manifest.prunePolicySha256.lowercase(Locale.ROOT)}")
            appendLine(
                "dependencies_archive_sha256=" +
                    manifest.dependenciesArchiveSha256.lowercase(Locale.ROOT),
            )
            appendLine("dependencies_archive_bytes=${manifest.dependenciesArchiveBytes}")
            appendLine(
                "dependency_tree_sha256=${manifest.dependencyTreeSha256.lowercase(Locale.ROOT)}",
            )
            appendLine("dependency_tree_file_count=${manifest.dependencyTreeFileCount}")
            appendLine(
                "dependency_tree_directory_count=${manifest.dependencyTreeDirectoryCount}",
            )
            appendLine("dependency_tree_symlink_count=${manifest.dependencyTreeSymlinkCount}")
            appendLine("dependency_tree_bytes=${manifest.dependencyTreeBytes}")
            appendLine(
                "tree_manifest_sha256=${manifest.treeManifestSha256.lowercase(Locale.ROOT)}",
            )
            appendLine("tree_manifest_bytes=${manifest.treeManifestBytes}")
            appendLine("sbom_sha256=${manifest.sbomSha256.lowercase(Locale.ROOT)}")
            appendLine("sbom_bytes=${manifest.sbomBytes}")
            appendLine(
                "license_manifest_sha256=" +
                    manifest.licenseManifestSha256.lowercase(Locale.ROOT),
            )
            appendLine("license_manifest_bytes=${manifest.licenseManifestBytes}")
            appendLine("bundle_sha256=${manifest.bundleSha256.lowercase(Locale.ROOT)}")
            appendLine("bundle_bytes=${manifest.bundleBytes}")
            appendLine(
                "bundle_license_sha256=${manifest.bundleLicenseSha256.lowercase(Locale.ROOT)}",
            )
            appendLine("bundle_license_bytes=${manifest.bundleLicenseBytes}")
            appendLine(
                "post_adapter_program_tree_sha256=" +
                    manifest.postAdapterProgramTreeSha256.lowercase(Locale.ROOT),
            )
            appendLine("signing_key_id=${manifest.signingKeyId}")
            appendLine("device_node_version=${manifest.deviceNodeVersion}")
            appendLine("device_javet_coordinate=${manifest.deviceJavetCoordinate}")
            appendLine("device_abi=${manifest.deviceAbi}")
        }.toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): StmDependencySupplyManifest {
        require(bytes.size in 1..MAX_MANIFEST_BYTES) {
            "Dependency manifest length is outside bounds"
        }
        require(bytes.last() == '\n'.code.toByte()) {
            "Dependency manifest must end with one LF"
        }
        require(bytes.none { it == '\r'.code.toByte() || it == 0.toByte() }) {
            "Dependency manifest contains a forbidden line ending or NUL"
        }
        val text = try {
            decodeUtf8Strict(bytes)
        } catch (error: Exception) {
            throw IllegalArgumentException("Dependency manifest is not valid UTF-8", error)
        }
        val lines = text.removeSuffix("\n").split('\n')
        require(lines.size == FIELD_KEYS.size + 1 && lines.first() == MAGIC) {
            "Dependency manifest header or field count is invalid"
        }
        val values = linkedMapOf<String, String>()
        FIELD_KEYS.forEachIndexed { index, expectedKey ->
            val line = lines[index + 1]
            val separator = line.indexOf('=')
            require(separator > 0 && line.indexOf('=', separator + 1) == -1) {
                "Dependency manifest field encoding is invalid"
            }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            require(key == expectedKey && value.isNotEmpty()) {
                "Dependency manifest field order or value is invalid at $expectedKey"
            }
            values[key] = value
        }
        val manifest = StmDependencySupplyManifest(
            schemaVersion = values.requiredInt("schema_version"),
            supplyId = values.required("supply_id"),
            repository = values.required("repository"),
            stCommitSha = values.required("st_commit_sha"),
            packageLockSha256 = values.required("package_lock_sha256"),
            buildNodeVersion = values.required("build_node_version"),
            buildNpmVersion = values.required("build_npm_version"),
            buildImageDigest = values.required("build_image_digest"),
            webpackConfigSha256 = values.required("webpack_config_sha256"),
            buildLibSha256 = values.required("build_lib_sha256"),
            adapterSha256 = values.required("adapter_sha256"),
            prunePolicy = values.requiredEnum("prune_policy"),
            prunePolicySha256 = values.required("prune_policy_sha256"),
            dependenciesArchiveSha256 = values.required("dependencies_archive_sha256"),
            dependenciesArchiveBytes = values.requiredLong("dependencies_archive_bytes"),
            dependencyTreeSha256 = values.required("dependency_tree_sha256"),
            dependencyTreeFileCount = values.requiredInt("dependency_tree_file_count"),
            dependencyTreeDirectoryCount = values.requiredInt(
                "dependency_tree_directory_count",
            ),
            dependencyTreeSymlinkCount = values.requiredInt("dependency_tree_symlink_count"),
            dependencyTreeBytes = values.requiredLong("dependency_tree_bytes"),
            treeManifestSha256 = values.required("tree_manifest_sha256"),
            treeManifestBytes = values.requiredLong("tree_manifest_bytes"),
            sbomSha256 = values.required("sbom_sha256"),
            sbomBytes = values.requiredLong("sbom_bytes"),
            licenseManifestSha256 = values.required("license_manifest_sha256"),
            licenseManifestBytes = values.requiredLong("license_manifest_bytes"),
            bundleSha256 = values.required("bundle_sha256"),
            bundleBytes = values.requiredLong("bundle_bytes"),
            bundleLicenseSha256 = values.required("bundle_license_sha256"),
            bundleLicenseBytes = values.requiredLong("bundle_license_bytes"),
            postAdapterProgramTreeSha256 = values.required(
                "post_adapter_program_tree_sha256",
            ),
            signingKeyId = values.required("signing_key_id"),
            deviceNodeVersion = values.required("device_node_version"),
            deviceJavetCoordinate = values.required("device_javet_coordinate"),
            deviceAbi = values.required("device_abi"),
        )
        validateFields(manifest)
        require(MessageDigest.isEqual(bytes, encode(manifest))) {
            "Dependency manifest is not in its canonical representation"
        }
        return manifest
    }

    fun verify(
        manifestBytes: ByteArray,
        detachedSignature: ByteArray,
        expectedSource: StmDependencySourceBinding,
        expectedRuntime: StmDependencyRuntimeBinding,
    ): StmDependencyManifestVerification {
        val manifest = try {
            decode(manifestBytes)
        } catch (error: IllegalArgumentException) {
            return StmDependencyManifestVerification.Rejected(
                StmDependencyManifestErrorCode.MANIFEST_FORMAT_INVALID,
                error.safeDependencyDetail(),
            )
        }
        if (
            manifest.repository != expectedSource.repository ||
            !manifest.stCommitSha.equals(expectedSource.commitSha, ignoreCase = true) ||
            !manifest.packageLockSha256.equals(
                expectedSource.packageLockSha256,
                ignoreCase = true,
            )
        ) {
            return StmDependencyManifestVerification.Rejected(
                StmDependencyManifestErrorCode.SOURCE_BINDING_MISMATCH,
                "Dependency supply does not bind the requested repository, commit, and lockfile",
            )
        }
        if (
            manifest.deviceNodeVersion != expectedRuntime.nodeVersion ||
            manifest.deviceJavetCoordinate != expectedRuntime.javetCoordinate ||
            manifest.deviceAbi != expectedRuntime.abi
        ) {
            return StmDependencyManifestVerification.Rejected(
                StmDependencyManifestErrorCode.RUNTIME_BINDING_MISMATCH,
                "Dependency supply is not compatible with the current Node, Javet, and ABI",
            )
        }
        if (detachedSignature.size != ED25519_SIGNATURE_BYTES) {
            return StmDependencyManifestVerification.Rejected(
                StmDependencyManifestErrorCode.SIGNATURE_FORMAT_INVALID,
                "The Ed25519 detached signature must be exactly 64 bytes",
            )
        }
        val publicKey = trustedKeyResolver.resolve(manifest.signingKeyId)
            ?: return StmDependencyManifestVerification.Rejected(
                StmDependencyManifestErrorCode.TRUSTED_KEY_NOT_FOUND,
                "No trusted dependency-supply key matches ${manifest.signingKeyId}",
            )
        return try {
            if (!StmEd25519Verifier.verify(publicKey, manifestBytes, detachedSignature)) {
                StmDependencyManifestVerification.Rejected(
                    StmDependencyManifestErrorCode.SIGNATURE_MISMATCH,
                    "The detached dependency manifest signature did not match",
                )
            } else {
                StmDependencyManifestVerification.Verified(
                    manifest = manifest,
                    canonicalSha256 = sha256(manifestBytes),
                )
            }
        } catch (error: Exception) {
            StmDependencyManifestVerification.Rejected(
                StmDependencyManifestErrorCode.CRYPTOGRAPHIC_FAILURE,
                error.safeDependencyDetail(),
            )
        }
    }

    fun verifyPayload(
        file: File,
        expectedBytes: Long,
        expectedSha256: String,
        maximumBytes: Long,
    ): StmDependencyPayloadVerification {
        require(expectedBytes >= 0) { "Expected payload length must not be negative" }
        require(maximumBytes > 0) { "Maximum payload length must be positive" }
        require(SHA256_PATTERN.matches(expectedSha256)) { "Expected payload SHA-256 is invalid" }
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return StmDependencyPayloadVerification.Rejected(
                StmDependencyManifestErrorCode.PAYLOAD_MISSING,
                "The dependency-supply payload is missing",
            )
        }
        if (
            Files.isSymbolicLink(file.toPath()) ||
            !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            return StmDependencyPayloadVerification.Rejected(
                StmDependencyManifestErrorCode.PAYLOAD_UNSAFE,
                "The dependency-supply payload must be a real regular file",
            )
        }
        val initialSize = runCatching { Files.size(file.toPath()) }.getOrElse { error ->
            return StmDependencyPayloadVerification.Rejected(
                StmDependencyManifestErrorCode.PAYLOAD_IO_FAILURE,
                error.safeDependencyDetail(),
            )
        }
        if (initialSize != expectedBytes || initialSize > maximumBytes) {
            return StmDependencyPayloadVerification.Rejected(
                StmDependencyManifestErrorCode.PAYLOAD_LENGTH_MISMATCH,
                "The dependency-supply payload length did not match its manifest",
                observedBytes = initialSize,
            )
        }
        val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
        val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        var observed = 0L
        return try {
            Files.newByteChannel(file.toPath(), options).use { channel ->
                val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
                while (true) {
                    val count = channel.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    observed = Math.addExact(observed, count.toLong())
                    if (observed > expectedBytes || observed > maximumBytes) {
                        return StmDependencyPayloadVerification.Rejected(
                            StmDependencyManifestErrorCode.PAYLOAD_LENGTH_MISMATCH,
                            "The dependency-supply payload grew while hashing",
                            observedBytes = observed,
                        )
                    }
                    buffer.flip()
                    digest.update(buffer)
                    buffer.clear()
                }
            }
            val observedHash = digest.digest().toHex()
            val finalAttributes = Files.readAttributes(
                file.toPath(),
                java.nio.file.attribute.BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (
                !finalAttributes.isRegularFile ||
                finalAttributes.isSymbolicLink ||
                observed != expectedBytes ||
                finalAttributes.size() != expectedBytes
            ) {
                StmDependencyPayloadVerification.Rejected(
                    StmDependencyManifestErrorCode.PAYLOAD_LENGTH_MISMATCH,
                    "The dependency-supply payload changed while hashing",
                    observedBytes = observed,
                    observedSha256 = observedHash,
                )
            } else if (!observedHash.equals(expectedSha256, ignoreCase = true)) {
                StmDependencyPayloadVerification.Rejected(
                    StmDependencyManifestErrorCode.PAYLOAD_SHA256_MISMATCH,
                    "The dependency-supply payload SHA-256 did not match its manifest",
                    observedBytes = observed,
                    observedSha256 = observedHash,
                )
            } else {
                StmDependencyPayloadVerification.Verified(observed, observedHash)
            }
        } catch (error: Exception) {
            StmDependencyPayloadVerification.Rejected(
                StmDependencyManifestErrorCode.PAYLOAD_IO_FAILURE,
                error.safeDependencyDetail(),
                observedBytes = observed,
            )
        }
    }

    private fun validateFields(manifest: StmDependencySupplyManifest) {
        require(manifest.schemaVersion == SCHEMA_VERSION) {
            "Unsupported dependency manifest schema ${manifest.schemaVersion}"
        }
        require(SAFE_ID_PATTERN.matches(manifest.supplyId)) { "Supply ID is invalid" }
        require(manifest.repository == OFFICIAL_ST_REPOSITORY) {
            "Dependency supply repository must be the official SillyTavern repository"
        }
        require(GIT_COMMIT_PATTERN.matches(manifest.stCommitSha)) { "ST commit SHA is invalid" }
        listOf(
            manifest.packageLockSha256,
            manifest.webpackConfigSha256,
            manifest.buildLibSha256,
            manifest.adapterSha256,
            manifest.prunePolicySha256,
            manifest.dependenciesArchiveSha256,
            manifest.dependencyTreeSha256,
            manifest.treeManifestSha256,
            manifest.sbomSha256,
            manifest.licenseManifestSha256,
            manifest.bundleSha256,
            manifest.bundleLicenseSha256,
            manifest.postAdapterProgramTreeSha256,
        ).forEach { value ->
            require(SHA256_PATTERN.matches(value)) { "Dependency manifest SHA-256 is invalid" }
        }
        require(NODE_VERSION_PATTERN.matches(manifest.buildNodeVersion)) {
            "Build Node version is invalid"
        }
        require(NPM_VERSION_PATTERN.matches(manifest.buildNpmVersion)) {
            "Build npm version is invalid"
        }
        require(IMAGE_DIGEST_PATTERN.matches(manifest.buildImageDigest)) {
            "Build image digest is invalid"
        }
        require(manifest.dependenciesArchiveBytes in 1..MAX_DEPENDENCY_ARCHIVE_BYTES) {
            "Dependency archive length is outside bounds"
        }
        require(manifest.dependencyTreeFileCount in 1..MAX_TREE_ENTRIES) {
            "Dependency tree file count is outside bounds"
        }
        require(manifest.dependencyTreeDirectoryCount in 1..MAX_TREE_ENTRIES) {
            "Dependency tree directory count is outside bounds"
        }
        require(manifest.dependencyTreeSymlinkCount in 0..MAX_TREE_ENTRIES) {
            "Dependency tree symlink count is outside bounds"
        }
        require(
            manifest.dependencyTreeFileCount.toLong() +
                manifest.dependencyTreeDirectoryCount +
                manifest.dependencyTreeSymlinkCount <= MAX_TREE_ENTRIES,
        ) {
            "Dependency tree total entry count is outside bounds"
        }
        require(manifest.dependencyTreeBytes in 1..MAX_DEPENDENCY_TREE_BYTES) {
            "Dependency tree byte total is outside bounds"
        }
        listOf(
            manifest.treeManifestBytes,
            manifest.sbomBytes,
            manifest.licenseManifestBytes,
            manifest.bundleBytes,
            manifest.bundleLicenseBytes,
        ).forEach { value ->
            require(value in 1..MAX_SIDECAR_BYTES) { "Dependency sidecar length is outside bounds" }
        }
        require(SAFE_ID_PATTERN.matches(manifest.signingKeyId)) { "Signing key ID is invalid" }
        require(NODE_VERSION_PATTERN.matches(manifest.deviceNodeVersion)) {
            "Device Node version is invalid"
        }
        require(COORDINATE_PATTERN.matches(manifest.deviceJavetCoordinate)) {
            "Device Javet coordinate is invalid"
        }
        require(manifest.deviceAbi == REQUIRED_ABI) { "Dependency supply ABI is unsupported" }
    }

    private companion object {
        const val MAGIC = "STM_DEPENDENCY_SUPPLY_MANIFEST_V1"
        const val SCHEMA_VERSION = 1
        const val OFFICIAL_ST_REPOSITORY = "https://github.com/SillyTavern/SillyTavern"
        const val REQUIRED_ABI = "arm64-v8a"
        const val MAX_MANIFEST_BYTES = 32 * 1024
        const val MAX_TREE_ENTRIES = 100_000
        const val MAX_DEPENDENCY_ARCHIVE_BYTES = 1024L * 1024L * 1024L
        const val MAX_DEPENDENCY_TREE_BYTES = 4L * 1024L * 1024L * 1024L
        const val MAX_SIDECAR_BYTES = 512L * 1024L * 1024L
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val ED25519_SIGNATURE_BYTES = 64
        const val SHA256_ALGORITHM = "SHA-256"
        val SAFE_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        val GIT_COMMIT_PATTERN = Regex("^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$")
        val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
        val NODE_VERSION_PATTERN = Regex("^v[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$")
        val NPM_VERSION_PATTERN = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$")
        val IMAGE_DIGEST_PATTERN = Regex("^sha256:[0-9a-fA-F]{64}$")
        val COORDINATE_PATTERN = Regex(
            "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}:" +
                "[A-Za-z0-9][A-Za-z0-9._-]{0,127}:" +
                "[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$",
        )
        val FIELD_KEYS = listOf(
            "schema_version",
            "supply_id",
            "repository",
            "st_commit_sha",
            "package_lock_sha256",
            "build_node_version",
            "build_npm_version",
            "build_image_digest",
            "webpack_config_sha256",
            "build_lib_sha256",
            "adapter_sha256",
            "prune_policy",
            "prune_policy_sha256",
            "dependencies_archive_sha256",
            "dependencies_archive_bytes",
            "dependency_tree_sha256",
            "dependency_tree_file_count",
            "dependency_tree_directory_count",
            "dependency_tree_symlink_count",
            "dependency_tree_bytes",
            "tree_manifest_sha256",
            "tree_manifest_bytes",
            "sbom_sha256",
            "sbom_bytes",
            "license_manifest_sha256",
            "license_manifest_bytes",
            "bundle_sha256",
            "bundle_bytes",
            "bundle_license_sha256",
            "bundle_license_bytes",
            "post_adapter_program_tree_sha256",
            "signing_key_id",
            "device_node_version",
            "device_javet_coordinate",
            "device_abi",
        )
    }
}

private fun decodeUtf8Strict(bytes: ByteArray): String =
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

private fun Map<String, String>.required(key: String): String =
    requireNotNull(this[key]) { "Dependency manifest is missing $key" }

private fun Map<String, String>.requiredInt(key: String): Int {
    val value = required(key)
    require(CANONICAL_INTEGER.matches(value)) { "Dependency manifest integer $key is invalid" }
    return value.toIntOrNull() ?: throw IllegalArgumentException(
        "Dependency manifest integer $key is outside bounds",
    )
}

private fun Map<String, String>.requiredLong(key: String): Long {
    val value = required(key)
    require(CANONICAL_INTEGER.matches(value)) { "Dependency manifest integer $key is invalid" }
    return value.toLongOrNull() ?: throw IllegalArgumentException(
        "Dependency manifest integer $key is outside bounds",
    )
}

private inline fun <reified T : Enum<T>> Map<String, String>.requiredEnum(key: String): T =
    enumValues<T>().singleOrNull { it.name == required(key) }
        ?: throw IllegalArgumentException("Dependency manifest enum $key is invalid")

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(radix = 16).padStart(2, '0')
}

private fun Throwable.safeDependencyDetail(): String =
    (message ?: javaClass.simpleName)
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .ifBlank { javaClass.simpleName }
        .take(500)

private val CANONICAL_INTEGER = Regex("^(?:0|[1-9][0-9]*)$")
