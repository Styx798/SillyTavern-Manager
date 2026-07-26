package io.github.styx798.sillytavernmanager.stmcore.installer

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

internal data class StmSignedPrebuiltIntegrationResult(
    val manifest: StmDependencySupplyManifest,
    val canonicalManifestSha256: String,
    val dependencyTreeSha256: String,
    val postAdapterProgramTreeSha256: String,
    val runtimeEvidence: StmRuntimeSlotAdmissionEvidence,
)

/**
 * Adds a verified signed dependency supply to an already verified SillyTavern source payload.
 *
 * All writes remain below the caller-owned staging operation. The dependency archive is extracted
 * by [StmSafeZipExtractor], the existing Webpack middleware is replaced as a whole module, and the
 * final program identity must equal the signed post-adapter identity before a READY capability is
 * returned. No slot or active pointer is changed here.
 */
internal class StmSignedPrebuiltSlotIntegrator(
    trustedKeyResolver: StmDependencyTrustedKeyResolver,
    private val zipExtractor: StmSafeZipExtractor = StmSafeZipExtractor(),
) {
    private val verifier = StmDependencySupplyManifestVerifier(trustedKeyResolver)

    fun integrate(
        payloadDirectory: File,
        archiveRoot: String,
        supplyRoot: File,
        dependencyExtractionRoot: File,
        expectedSource: StmDependencySourceBinding,
        expectedRuntime: StmDependencyRuntimeBinding,
        sourceEntries: List<StmZipManifestEntry> = emptyList(),
        cancellation: StmExtractionCancellation = StmExtractionCancellation.NONE,
    ): StmSignedPrebuiltIntegrationResult {
        val payload = requireRealDirectory(payloadDirectory, "Source payload")
        val program = requireContainedProgramRoot(payload, archiveRoot)
        val supply = requireSupplyRoot(supplyRoot)
        val dependencyOperation = requireDependencyOperationRoot(
            payload = payload,
            dependencyExtractionRoot = dependencyExtractionRoot,
        )
        check(!Files.exists(program.resolve(NODE_MODULES), LinkOption.NOFOLLOW_LINKS)) {
            "Verified source payload already contains node_modules"
        }
        check(!Files.exists(payload.resolve(RUNTIME_DIRECTORY), LinkOption.NOFOLLOW_LINKS)) {
            "Verified source payload already contains STM runtime evidence"
        }
        cancellation.throwIfRequested()

        val manifestBytes = readBoundedRegularFile(
            supply.resolve(MANIFEST_FILE),
            MAX_MANIFEST_BYTES,
        )
        val signatureBytes = readBoundedRegularFile(
            supply.resolve(SIGNATURE_FILE),
            ED25519_SIGNATURE_BYTES,
        )
        val verification = verifier.verify(
            manifestBytes = manifestBytes,
            detachedSignature = signatureBytes,
            expectedSource = expectedSource,
            expectedRuntime = expectedRuntime,
        )
        check(verification is StmDependencyManifestVerification.Verified) {
            val rejected = verification as StmDependencyManifestVerification.Rejected
            "${rejected.code}:${rejected.detail}"
        }
        val manifest = verification.manifest
        requireManifestPolicy(manifest)
        verifyProgramSourceBindings(program, manifest)
        verifySupplySidecars(supply, manifest)

        val archive = supply.resolve(DEPENDENCIES_ARCHIVE_FILE)
        requireBoundPayloadSize(archive, manifest.dependenciesArchiveBytes, MAX_ARCHIVE_BYTES)
        cancellation.throwIfRequested()

        val expectedTreeManifest = readBoundedRegularFile(
            supply.resolve(TREE_MANIFEST_FILE),
            MAX_TREE_MANIFEST_BYTES,
        )
        val signedDependencyEntries = parseTreeManifest(expectedTreeManifest)
        val extraction = zipExtractor.extract(
            artifact = archive.toFile(),
            operationStagingRoot = dependencyOperation.toFile(),
            cancellation = cancellation,
            mode = StmZipExtractionMode.SIGNED_ARCHIVE_FAST,
        )
        requireExtractedTree(extraction, signedDependencyEntries, manifest)

        val extractedNodeModules = extraction.payloadDirectory.toPath().resolve(NODE_MODULES)
        val installedNodeModules = program.resolve(NODE_MODULES)
        check(
            Files.isDirectory(extractedNodeModules, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(extractedNodeModules),
        ) {
            "Signed dependency archive did not produce a real node_modules directory"
        }
        check(extractedNodeModules.toFile().renameTo(installedNodeModules.toFile())) {
            "The dependency tree could not be atomically moved into the staged program"
        }
        deleteTreeNoFollow(dependencyOperation)

        replaceWebpackAdapter(
            program = program,
            verifiedAdapter = supply.resolve(ADAPTER_FILE),
        )
        val runtimeDirectory = Files.createDirectory(payload.resolve(RUNTIME_DIRECTORY))
        val runtimeBindings = linkedMapOf<String, StmRuntimeFileBinding>()
        RUNTIME_SUPPLY_FILES.sorted().forEach { name ->
            cancellation.throwIfRequested()
            val source = supply.resolve(name)
            val destination = runtimeDirectory.resolve(name)
            copyRegularFile(source, destination)
            runtimeBindings[name] = StmRuntimeFileBinding(
                bytes = Files.size(destination),
                sha256 = sha256(destination),
            )
        }

        val preparedSlotEntries = buildPreparedSlotEntries(
            archiveRoot = archiveRoot,
            sourceEntries = sourceEntries,
            dependencyEntries = signedDependencyEntries,
            runtimeBindings = runtimeBindings,
        )
        val programIdentity = stmTreeIdentitySha256(
            preparedSlotEntries
                .asSequence()
                .filter { entry ->
                    entry.relativePath.startsWith("$archiveRoot/")
                }
                .map { entry ->
                    entry.toZipManifestEntry(
                        entry.relativePath.removePrefix("$archiveRoot/"),
                    )
                }
                .toList(),
        )
        check(programIdentity == manifest.postAdapterProgramTreeSha256) {
            "Assembled program tree identity $programIdentity did not match signed identity " +
                manifest.postAdapterProgramTreeSha256
        }
        val runtimeEvidence = StmRuntimeSlotAdmissionEvidence(
            repository = manifest.repository,
            commitSha = manifest.stCommitSha,
            packageLockSha256 = manifest.packageLockSha256,
            dependencyTreeSha256 = manifest.dependencyTreeSha256,
            postAdapterProgramTreeSha256 = manifest.postAdapterProgramTreeSha256,
            runtimeFiles = runtimeBindings,
            preparedSlotEntries = preparedSlotEntries,
        )
        return StmSignedPrebuiltIntegrationResult(
            manifest = manifest,
            canonicalManifestSha256 = verification.canonicalSha256,
            dependencyTreeSha256 = manifest.dependencyTreeSha256,
            postAdapterProgramTreeSha256 = programIdentity,
            runtimeEvidence = runtimeEvidence,
        )
    }

    private fun requireSupplyRoot(input: File): Path {
        val path = input.toPath().toAbsolutePath().normalize()
        check(!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "Signed dependency supply root is unavailable or unsafe"
        }
        val names = Files.list(path).use { stream ->
            stream.iterator().asSequence().map { it.fileName.toString() }.sorted().toList()
        }
        check(names == EXPECTED_SUPPLY_FILES) {
            "Signed dependency supply contains unexpected files: $names"
        }
        return path.toRealPath()
    }

    private fun requireManifestPolicy(manifest: StmDependencySupplyManifest) {
        check(manifest.dependencyTreeSymlinkCount == 0) {
            "Signed prebuilt dependency tree must not contain symbolic links"
        }
        check(
            manifest.prunePolicy == StmDependencyPrunePolicy.LOCKFILE_COMPLETE ||
                manifest.prunePolicy == StmDependencyPrunePolicy.VERSION_BOUND_PRUNED_WEBPACK,
        ) {
            "Signed dependency prune policy is unsupported"
        }
    }

    private fun verifyProgramSourceBindings(
        program: Path,
        manifest: StmDependencySupplyManifest,
    ) {
        requirePayload(
            program.resolve("package-lock.json"),
            Files.size(program.resolve("package-lock.json")),
            manifest.packageLockSha256,
            MAX_SOURCE_FILE_BYTES,
        )
        requirePayload(
            program.resolve("webpack.config.js"),
            Files.size(program.resolve("webpack.config.js")),
            manifest.webpackConfigSha256,
            MAX_SOURCE_FILE_BYTES,
        )
        requirePayload(
            program.resolve("docker/build-lib.js"),
            Files.size(program.resolve("docker/build-lib.js")),
            manifest.buildLibSha256,
            MAX_SOURCE_FILE_BYTES,
        )
    }

    private fun verifySupplySidecars(
        supply: Path,
        manifest: StmDependencySupplyManifest,
    ) {
        requirePayload(
            supply.resolve(TREE_MANIFEST_FILE),
            manifest.treeManifestBytes,
            manifest.treeManifestSha256,
            MAX_TREE_MANIFEST_BYTES,
        )
        requirePayload(
            supply.resolve(SBOM_FILE),
            manifest.sbomBytes,
            manifest.sbomSha256,
            MAX_SIDECAR_BYTES,
        )
        val licenseManifest = supply.resolve(LICENSE_MANIFEST_FILE)
        requirePayload(
            licenseManifest,
            manifest.licenseManifestBytes,
            manifest.licenseManifestSha256,
            MAX_SIDECAR_BYTES,
        )
        verifyThirdPartyLicenseArchive(
            supply = supply,
            manifest = manifest,
            licenseManifestBytes = readBoundedRegularFile(
                licenseManifest,
                MAX_SIDECAR_BYTES,
            ),
        )
        requirePayload(
            supply.resolve(BUNDLE_FILE),
            manifest.bundleBytes,
            manifest.bundleSha256,
            MAX_BUNDLE_BYTES,
        )
        requirePayload(
            supply.resolve(BUNDLE_LICENSE_FILE),
            manifest.bundleLicenseBytes,
            manifest.bundleLicenseSha256,
            MAX_SIDECAR_BYTES,
        )
        requirePayload(
            supply.resolve(ADAPTER_FILE),
            Files.size(supply.resolve(ADAPTER_FILE)),
            manifest.adapterSha256,
            MAX_SOURCE_FILE_BYTES,
        )
        requirePayload(
            supply.resolve(PRUNE_POLICY_FILE),
            Files.size(supply.resolve(PRUNE_POLICY_FILE)),
            manifest.prunePolicySha256,
            MAX_SOURCE_FILE_BYTES,
        )
    }

    private fun verifyThirdPartyLicenseArchive(
        supply: Path,
        manifest: StmDependencySupplyManifest,
        licenseManifestBytes: ByteArray,
    ) {
        val inventory = try {
            JSONObject(licenseManifestBytes.toString(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            throw IllegalStateException("Signed third-party license inventory is invalid", error)
        }
        check(inventory.getInt("schemaVersion") == LICENSE_INVENTORY_SCHEMA_VERSION) {
            "Signed third-party license inventory schema is unsupported"
        }
        val artifact = inventory.getJSONObject("artifact")
        check(
            artifact.getString("repository") == manifest.repository &&
                artifact.getString("stCommit") == manifest.stCommitSha &&
                artifact.getString("packageLockSha256") == manifest.packageLockSha256
        ) {
            "Signed third-party license inventory does not bind this dependency supply"
        }
        val counts = inventory.getJSONObject("counts")
        val packageInstances = counts.getInt("packageInstances")
        check(
            packageInstances > 0 &&
                counts.getInt("unresolvedPackageInstances") == 0 &&
                inventory.getJSONArray("packages").length() == packageInstances
        ) {
            "Signed third-party license inventory is incomplete"
        }
        val stmLicense = inventory.getJSONObject("stmProjectLicense")
        val stmLicenseName = stmLicense.getString("file")
        val stmLicenseBytes = stmLicense.getLong("bytes")
        val stmLicenseSha256 = stmLicense.getString("sha256")
        check(
            stmLicenseName == STM_LICENSE_FILE &&
                stmLicense.getString("spdx") == STM_LICENSE_SPDX &&
                stmLicenseBytes in 1..MAX_LICENSE_TEXT_BYTES &&
                stmLicenseSha256.matches(LOWERCASE_SHA256_PATTERN)
        ) {
            "Signed STM project license identity is invalid"
        }
        requirePayload(
            supply.resolve(stmLicenseName),
            stmLicenseBytes,
            stmLicenseSha256,
            MAX_LICENSE_TEXT_BYTES,
        )
        val archive = inventory.getJSONObject("archive")
        val archiveName = archive.getString("file")
        val archiveRoot = archive.getString("root")
        val archiveBytes = archive.getLong("bytes")
        val archiveSha256 = archive.getString("sha256")
        check(
            archiveName == THIRD_PARTY_LICENSE_ARCHIVE_FILE &&
                archiveRoot == THIRD_PARTY_LICENSE_ARCHIVE_ROOT &&
                archiveBytes in 1..MAX_LICENSE_ARCHIVE_BYTES &&
                archiveSha256.matches(LOWERCASE_SHA256_PATTERN) &&
                archive.getInt("fileCount") > 0 &&
                archive.getInt("directoryCount") > 0 &&
                archive.getLong("totalFileBytes") > 0 &&
                archive.getString("treeSha256").matches(LOWERCASE_SHA256_PATTERN)
        ) {
            "Signed third-party license archive identity is invalid"
        }
        requirePayload(
            supply.resolve(archiveName),
            archiveBytes,
            archiveSha256,
            MAX_LICENSE_ARCHIVE_BYTES,
        )
    }

    private fun requireExtractedTree(
        extraction: StmZipExtractionResult,
        signedEntries: List<StmZipManifestEntry>,
        manifest: StmDependencySupplyManifest,
    ) {
        check(
            extraction.fileCount == manifest.dependencyTreeFileCount &&
                extraction.directoryCount == manifest.dependencyTreeDirectoryCount &&
                extraction.totalFileBytes == manifest.dependencyTreeBytes,
        ) {
            "Extracted dependency tree did not match the signed manifest"
        }
        check(
            extraction.entries.all { entry ->
                entry.relativePath == NODE_MODULES ||
                    entry.relativePath.startsWith("$NODE_MODULES/")
            },
        ) {
            "Dependency archive contains data outside node_modules"
        }
        val extractedStructure = extraction.entries.map { entry ->
            Triple(entry.relativePath, entry.type, entry.sizeBytes)
        }
        val signedStructure = signedEntries.map { entry ->
            Triple(entry.relativePath, entry.type, entry.sizeBytes)
        }
        check(extractedStructure == signedStructure) {
            "Extracted dependency structure did not match the signed tree manifest"
        }
        check(stmTreeIdentitySha256(signedEntries) == manifest.dependencyTreeSha256) {
            "Signed dependency tree manifest identity did not match its signed metadata"
        }
    }

    private fun parseTreeManifest(bytes: ByteArray): List<StmZipManifestEntry> {
        val text = bytes.toString(StandardCharsets.UTF_8)
        check(text.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
            "Signed dependency tree manifest is not canonical UTF-8"
        }
        val lines = text.split('\n')
        check(lines.firstOrNull() == TREE_MANIFEST_MAGIC && lines.lastOrNull().isNullOrEmpty()) {
            "Signed dependency tree manifest framing is invalid"
        }
        var previousPath: String? = null
        val entries = lines.drop(1).dropLast(1).map { line ->
            val fields = line.split('\t')
            val entry = when (fields.firstOrNull()) {
                "D" -> {
                    check(fields.size == 2) { "Signed tree directory record is invalid" }
                    StmZipManifestEntry(
                        relativePath = fields[1],
                        type = StmZipManifestEntryType.DIRECTORY,
                        sizeBytes = 0,
                        sha256 = null,
                    )
                }

                "F" -> {
                    check(fields.size == 4) { "Signed tree file record is invalid" }
                    val size = fields[2].toLongOrNull()
                    check(size != null && size >= 0) { "Signed tree file size is invalid" }
                    check(LOWERCASE_SHA256_PATTERN.matches(fields[3])) {
                        "Signed tree file SHA-256 is invalid"
                    }
                    StmZipManifestEntry(
                        relativePath = fields[1],
                        type = StmZipManifestEntryType.FILE,
                        sizeBytes = size,
                        sha256 = fields[3],
                    )
                }

                else -> error("Signed dependency tree record type is invalid")
            }
            check(
                entry.relativePath == NODE_MODULES ||
                    entry.relativePath.startsWith("$NODE_MODULES/")
            ) {
                "Signed dependency tree contains data outside node_modules"
            }
            check(previousPath == null || requireNotNull(previousPath) < entry.relativePath) {
                "Signed dependency tree paths are not strictly sorted"
            }
            previousPath = entry.relativePath
            entry
        }
        check(entries.isNotEmpty()) { "Signed dependency tree manifest is empty" }
        return entries
    }

    private fun buildPreparedSlotEntries(
        archiveRoot: String,
        sourceEntries: List<StmZipManifestEntry>,
        dependencyEntries: List<StmZipManifestEntry>,
        runtimeBindings: Map<String, StmRuntimeFileBinding>,
    ): List<StmSlotManifestEntry> {
        check(sourceEntries.isNotEmpty()) {
            "Signed runtime preparation is missing source extraction evidence"
        }
        val entries = linkedMapOf<String, StmSlotManifestEntry>()
        fun add(entry: StmSlotManifestEntry) {
            check(entries.putIfAbsent(entry.relativePath, entry) == null) {
                "Prepared slot evidence contains a duplicate path"
            }
        }

        sourceEntries.forEach { source ->
            check(
                source.relativePath == archiveRoot ||
                    source.relativePath.startsWith("$archiveRoot/")
            ) {
                "Source extraction evidence escaped the bound archive root"
            }
            val isAdapter = source.relativePath == "$archiveRoot/$ADAPTER_RELATIVE_PATH"
            val adapterBinding = runtimeBindings[ADAPTER_FILE]
            add(
                StmSlotManifestEntry(
                    relativePath = source.relativePath,
                    type = source.type.toSlotManifestType(),
                    bytes = if (isAdapter) requireNotNull(adapterBinding).bytes else source.sizeBytes,
                    fileSha256 = if (isAdapter) {
                        requireNotNull(adapterBinding).sha256
                    } else {
                        source.sha256
                    },
                ),
            )
        }
        dependencyEntries.forEach { dependency ->
            add(
                StmSlotManifestEntry(
                    relativePath = "$archiveRoot/${dependency.relativePath}",
                    type = dependency.type.toSlotManifestType(),
                    bytes = dependency.sizeBytes,
                    fileSha256 = dependency.sha256,
                ),
            )
        }
        add(
            StmSlotManifestEntry(
                relativePath = RUNTIME_DIRECTORY,
                type = StmSlotManifestEntryType.DIRECTORY,
                bytes = 0,
                fileSha256 = null,
            ),
        )
        runtimeBindings.toSortedMap().forEach { (name, binding) ->
            add(
                StmSlotManifestEntry(
                    relativePath = "$RUNTIME_DIRECTORY/$name",
                    type = StmSlotManifestEntryType.FILE,
                    bytes = binding.bytes,
                    fileSha256 = binding.sha256,
                ),
            )
        }
        val sorted = entries.values.sortedBy(StmSlotManifestEntry::relativePath)
        check(sorted.all { entry ->
            entry.type == StmSlotManifestEntryType.DIRECTORY ||
                entry.fileSha256?.matches(LOWERCASE_SHA256_PATTERN) == true
        }) {
            "Prepared slot evidence is missing a file identity"
        }
        return sorted
    }

    private fun replaceWebpackAdapter(program: Path, verifiedAdapter: Path) {
        val target = program.resolve(ADAPTER_RELATIVE_PATH).normalize()
        check(target.startsWith(program) && target.parent != null) {
            "Webpack adapter target escaped the staged program"
        }
        check(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
            "Original Webpack middleware is missing or unsafe"
        }
        val temporary = target.resolveSibling("${target.fileName}.stm-part")
        check(!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            "Webpack adapter temporary target already exists"
        }
        copyRegularFile(verifiedAdapter, temporary)
        Files.delete(target)
        check(temporary.toFile().renameTo(target.toFile())) {
            "Verified Webpack adapter could not replace the staged source module"
        }
    }

    private fun copyRegularFile(source: Path, destination: Path) {
        check(!Files.isSymbolicLink(source) && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            "Signed supply file is missing or unsafe: ${source.fileName}"
        }
        check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            "Staged runtime file already exists: ${destination.fileName}"
        }
        Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS).use { input ->
            FileOutputStream(destination.toFile()).use { output ->
                input.copyTo(output, COPY_BUFFER_SIZE)
                output.fd.sync()
            }
        }
        check(
            Files.size(source) == Files.size(destination) &&
                MessageDigest.isEqual(sha256Bytes(source), sha256Bytes(destination)),
        ) {
            "Staged runtime file changed while being copied: ${destination.fileName}"
        }
    }

    private fun requireBoundPayloadSize(file: Path, expectedBytes: Long, maximumBytes: Long) {
        check(
            !Files.isSymbolicLink(file) &&
                Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
                Files.size(file) == expectedBytes &&
                expectedBytes in 1..maximumBytes
        ) {
            "Signed archive payload length or type is invalid"
        }
    }

    private fun requirePayload(
        file: Path,
        expectedBytes: Long,
        expectedSha256: String,
        maximumBytes: Long,
    ): StmDependencyPayloadVerification.Verified {
        val result = verifier.verifyPayload(
            file = file.toFile(),
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

    private fun readBoundedRegularFile(file: Path, maximumBytes: Long): ByteArray {
        check(
            !Files.isSymbolicLink(file) &&
                Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
                Files.size(file) in 1..maximumBytes,
        ) {
            "Unsafe or oversized signed supply file: ${file.fileName}"
        }
        return Files.readAllBytes(file)
    }

    private fun requireRealDirectory(input: File, label: String): Path {
        val path = input.toPath().toAbsolutePath().normalize()
        check(!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "$label is unavailable or unsafe"
        }
        return path.toRealPath()
    }

    private fun requireContainedProgramRoot(payload: Path, archiveRoot: String): Path {
        check(archiveRoot.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}"))) {
            "SillyTavern archive root is unsafe"
        }
        val program = payload.resolve(archiveRoot).normalize()
        check(program.parent == payload && program.startsWith(payload)) {
            "SillyTavern program root escaped the source payload"
        }
        return requireRealDirectory(program.toFile(), "SillyTavern program")
    }

    private fun requireDependencyOperationRoot(
        payload: Path,
        dependencyExtractionRoot: File,
    ): Path {
        val operationRoot = requireNotNull(payload.parent) { "Source payload has no operation root" }
        val dependencyParent = requireNotNull(dependencyExtractionRoot.parentFile) {
            "Dependency extraction root has no parent"
        }
        val realDependencyParent = requireRealDirectory(
            dependencyParent,
            "Dependency extraction parent",
        )
        val dependencyName = dependencyExtractionRoot.name
        check(dependencyName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) {
            "Dependency extraction root name is unsafe"
        }
        val dependencyRoot = realDependencyParent.resolve(dependencyName).normalize()
        check(
            realDependencyParent == operationRoot &&
                dependencyRoot.parent == operationRoot &&
                dependencyRoot.startsWith(operationRoot) &&
                dependencyRoot != payload &&
                !Files.exists(dependencyRoot, LinkOption.NOFOLLOW_LINKS)
        ) {
            "Dependency extraction root is not a fresh child of the source staging operation"
        }
        return dependencyRoot
    }

    private fun StmExtractionCancellation.throwIfRequested() {
        if (isCancelled()) {
            throw StmZipExtractionException(
                StmZipErrorCode.OPERATION_CANCELLED,
                "Signed prebuilt integration was cancelled",
            )
        }
    }

    private fun sha256(path: Path): String = sha256Bytes(path).toHex()

    private fun sha256Bytes(path: Path): ByteArray {
        val digest = MessageDigest.getInstance(SHA256)
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
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
                error: java.io.IOException?,
            ): FileVisitResult {
                error?.let { throw it }
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private companion object {
        const val NODE_MODULES = "node_modules"
        const val RUNTIME_DIRECTORY = StmRuntimeSlotAdmissionEvidence.RUNTIME_DIRECTORY
        const val MANIFEST_FILE = StmRuntimeSlotAdmissionEvidence.MANIFEST_FILE
        const val SIGNATURE_FILE = StmRuntimeSlotAdmissionEvidence.SIGNATURE_FILE
        const val TREE_MANIFEST_FILE = StmRuntimeSlotAdmissionEvidence.TREE_MANIFEST_FILE
        const val SBOM_FILE = StmRuntimeSlotAdmissionEvidence.SBOM_FILE
        const val LICENSE_MANIFEST_FILE = StmRuntimeSlotAdmissionEvidence.LICENSE_MANIFEST_FILE
        const val THIRD_PARTY_LICENSE_ARCHIVE_FILE =
            StmRuntimeSlotAdmissionEvidence.THIRD_PARTY_LICENSE_ARCHIVE_FILE
        const val STM_LICENSE_FILE = StmRuntimeSlotAdmissionEvidence.STM_LICENSE_FILE
        const val BUNDLE_FILE = StmRuntimeSlotAdmissionEvidence.BUNDLE_FILE
        const val BUNDLE_LICENSE_FILE = StmRuntimeSlotAdmissionEvidence.BUNDLE_LICENSE_FILE
        const val ADAPTER_FILE = StmRuntimeSlotAdmissionEvidence.ADAPTER_FILE
        const val PRUNE_POLICY_FILE = StmRuntimeSlotAdmissionEvidence.PRUNE_POLICY_FILE
        const val DEPENDENCIES_ARCHIVE_FILE = "dependencies.zip"
        const val ADAPTER_RELATIVE_PATH = "src/middleware/webpack-serve.js"
        const val TREE_MANIFEST_MAGIC = "STM_DEPENDENCY_TREE_MANIFEST_V1"
        const val MAX_MANIFEST_BYTES = 32L * 1024L
        const val ED25519_SIGNATURE_BYTES = 64L
        const val MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L
        const val MAX_TREE_MANIFEST_BYTES = 16L * 1024L * 1024L
        const val MAX_SIDECAR_BYTES = 64L * 1024L * 1024L
        const val MAX_LICENSE_ARCHIVE_BYTES = 64L * 1024L * 1024L
        const val MAX_LICENSE_TEXT_BYTES = 128L * 1024L
        const val MAX_BUNDLE_BYTES = 16L * 1024L * 1024L
        const val MAX_SOURCE_FILE_BYTES = 16L * 1024L * 1024L
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val SHA256 = "SHA-256"
        const val LICENSE_INVENTORY_SCHEMA_VERSION = 2
        const val THIRD_PARTY_LICENSE_ARCHIVE_ROOT = "third_party_licenses"
        const val STM_LICENSE_SPDX = "AGPL-3.0-only"

        val RUNTIME_SUPPLY_FILES =
            StmRuntimeSlotAdmissionEvidence.REQUIRED_RUNTIME_FILES
        val EXPECTED_SUPPLY_FILES = (
            RUNTIME_SUPPLY_FILES + DEPENDENCIES_ARCHIVE_FILE
            ).sorted()
        val LOWERCASE_SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

private fun StmZipManifestEntryType.toSlotManifestType(): StmSlotManifestEntryType =
    when (this) {
        StmZipManifestEntryType.DIRECTORY -> StmSlotManifestEntryType.DIRECTORY
        StmZipManifestEntryType.FILE -> StmSlotManifestEntryType.FILE
    }

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
