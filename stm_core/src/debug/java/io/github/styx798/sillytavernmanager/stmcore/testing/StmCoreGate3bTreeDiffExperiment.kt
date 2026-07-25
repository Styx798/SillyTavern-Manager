package io.github.styx798.sillytavernmanager.stmcore.testing

import android.content.Context
import io.github.styx798.sillytavernmanager.stmcore.StmCorePaths
import io.github.styx798.sillytavernmanager.stmcore.installer.StmDependencySupplyCandidate
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

internal class StmCoreGate3bTreeDiffExperiment(
    context: Context,
) : StmCoreGate3bExperimentRunner {
    private val appContext = context.applicationContext
    private val cancelled = AtomicBoolean(false)

    override fun cancel() {
        cancelled.set(true)
    }

    override fun run(): Map<String, String> {
        val npm = loadDeviceEvidence(StmDependencySupplyCandidate.NPM_CLI)
        check(!cancelled.get()) { "Stage 3B tree comparison was cancelled" }
        val arborist = loadDeviceEvidence(StmDependencySupplyCandidate.ARBORIST)
        check(!cancelled.get()) { "Stage 3B tree comparison was cancelled" }
        val verifiedPrebuilt = StmCoreGate3bPrebuiltExperiment(appContext).verifySupply()
        val prebuilt = loadSignedPrebuiltEvidence(verifiedPrebuilt)

        val npmVsArborist = Gate3bTreeManifestCodec.compare(npm.manifest, arborist.manifest)
        val npmVsPrebuilt = Gate3bTreeManifestCodec.compare(npm.manifest, prebuilt.manifest)
        val arboristVsPrebuilt = Gate3bTreeManifestCodec.compare(
            arborist.manifest,
            prebuilt.manifest,
        )
        val arboristHiddenLock = Gate3bTreeEvidenceStore.loadHiddenLock(
            Gate3bTreeEvidenceStore.hiddenLockEvidenceFile(
                appContext,
                StmDependencySupplyCandidate.ARBORIST,
            ),
            "ARBORIST hidden lock evidence",
        )
        requireGate3bFileEvidence(
            arborist.manifest,
            HIDDEN_LOCK_PATH,
            arboristHiddenLock,
            "ARBORIST hidden lock evidence",
        )
        val prebuiltHiddenLock = loadSignedPrebuiltHiddenLock(
            verifiedPrebuilt,
            prebuilt.manifest,
        )
        val hiddenLockJsonDiff = Gate3bJsonDiff.compare(
            Gate3bJsonDiff.toPlainValue(
                JSONObject(prebuiltHiddenLock.bytes.toString(StandardCharsets.UTF_8)),
            ),
            Gate3bJsonDiff.toPlainValue(
                JSONObject(arboristHiddenLock.bytes.toString(StandardCharsets.UTF_8)),
            ),
        )

        return linkedMapOf(
            "result" to "passed",
            "meaning" to "comparison_completed_not_gate_passed",
            "st_commit" to StmCoreGate3bPrebuiltExperiment.ST_COMMIT,
            "package_lock_sha256" to StmCoreGate3bPrebuiltExperiment.PACKAGE_LOCK_SHA256,
        ).apply {
            putManifest("npm", npm)
            putManifest("arborist", arborist)
            putManifest("prebuilt", prebuilt)
            putDiff("npm_vs_arborist", npmVsArborist)
            putDiff("npm_vs_prebuilt", npmVsPrebuilt)
            putDiff("arborist_vs_prebuilt", arboristVsPrebuilt)
            put("prebuilt_hidden_lock_sha256", prebuiltHiddenLock.sha256)
            put("prebuilt_hidden_lock_bytes", prebuiltHiddenLock.bytes.size.toString())
            put("arborist_hidden_lock_sha256", arboristHiddenLock.sha256)
            put("arborist_hidden_lock_bytes", arboristHiddenLock.bytes.size.toString())
            put("prebuilt_vs_arborist_hidden_lock_json_differences", hiddenLockJsonDiff.count.toString())
            put(
                "prebuilt_vs_arborist_hidden_lock_json_details",
                hiddenLockJsonDiff.details.take(MAX_RESULT_CHARS),
            )
        }
    }

    private fun loadDeviceEvidence(
        candidate: StmDependencySupplyCandidate,
    ): Gate3bLoadedTreeManifest {
        val file = Gate3bTreeEvidenceStore.evidenceFile(appContext, candidate)
        return Gate3bTreeEvidenceStore.load(file, "${candidate.name} tree evidence")
    }

    private fun loadSignedPrebuiltEvidence(
        supply: Gate3bVerifiedPrebuiltSupply,
    ): Gate3bLoadedTreeManifest {
        val loaded = Gate3bTreeEvidenceStore.load(
            File(supply.root, StmCoreGate3bPrebuiltExperiment.TREE_MANIFEST_FILE),
            "signed prebuilt tree manifest",
        )
        check(
            loaded.sha256 == supply.manifest.treeManifestSha256 &&
                loaded.bytes == supply.manifest.treeManifestBytes &&
                loaded.manifest.fileCount == supply.manifest.dependencyTreeFileCount &&
                loaded.manifest.directoryCount == supply.manifest.dependencyTreeDirectoryCount &&
                loaded.manifest.fileBytes == supply.manifest.dependencyTreeBytes,
        ) {
            "Signed prebuilt tree evidence did not match its verified supply manifest"
        }
        return loaded
    }

    private fun loadSignedPrebuiltHiddenLock(
        supply: Gate3bVerifiedPrebuiltSupply,
        manifest: Gate3bTreeManifest,
    ): Gate3bLoadedBytes {
        val archive = File(
            supply.root,
            StmCoreGate3bPrebuiltExperiment.DEPENDENCIES_ARCHIVE_FILE,
        )
        val bytes = ZipFile(archive).use { zip ->
            val entry = requireNotNull(zip.getEntry(HIDDEN_LOCK_PATH)) {
                "Signed dependency archive omitted the hidden lockfile"
            }
            check(!entry.isDirectory) { "Signed dependency archive hidden lockfile is not a file" }
            zip.getInputStream(entry).use { input ->
                input.readBounded(Gate3bTreeEvidenceStore.MAX_HIDDEN_LOCK_BYTES)
            }
        }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        return Gate3bLoadedBytes(bytes, sha256).also { loaded ->
            requireGate3bFileEvidence(
                manifest,
                HIDDEN_LOCK_PATH,
                loaded,
                "Signed dependency archive hidden lockfile",
            )
        }
    }

    private fun MutableMap<String, String>.putManifest(
        prefix: String,
        loaded: Gate3bLoadedTreeManifest,
    ) {
        put("${prefix}_manifest_sha256", loaded.sha256)
        put("${prefix}_manifest_bytes", loaded.bytes.toString())
        put("${prefix}_files", loaded.manifest.fileCount.toString())
        put("${prefix}_directories", loaded.manifest.directoryCount.toString())
        put("${prefix}_tree_bytes", loaded.manifest.fileBytes.toString())
    }

    private fun MutableMap<String, String>.putDiff(
        prefix: String,
        diff: Gate3bTreeDiff,
    ) {
        put("${prefix}_different_paths", diff.differentPaths.toString())
        put("${prefix}_only_left", diff.onlyLeft.toString())
        put("${prefix}_only_right", diff.onlyRight.toString())
        put("${prefix}_type_mismatches", diff.typeMismatches.toString())
        put("${prefix}_size_mismatches", diff.sizeMismatches.toString())
        put("${prefix}_content_mismatches", diff.contentMismatches.toString())
        put("${prefix}_byte_delta_right_minus_left", diff.byteDeltaRightMinusLeft.toString())
        put("${prefix}_details", diff.details.take(MAX_RESULT_CHARS))
    }

    private companion object {
        const val MAX_RESULT_CHARS = 4_000
        const val HIDDEN_LOCK_PATH = "node_modules/.package-lock.json"
    }
}

internal fun requireGate3bFileEvidence(
    manifest: Gate3bTreeManifest,
    path: String,
    loaded: Gate3bLoadedBytes,
    label: String,
) {
    val expected = requireNotNull(manifest.entries[path]) {
        "$label is missing from its tree manifest"
    }
    check(
        expected.type == Gate3bTreeEntryType.FILE &&
            loaded.bytes.size.toLong() == expected.size &&
            loaded.sha256 == expected.sha256,
    ) {
        "$label did not match its tree manifest"
    }
}

internal data class Gate3bTreeFingerprint(
    val sha256: String,
    val files: Long,
    val directories: Long,
    val symlinks: Long,
    val special: Long,
    val bytes: Long,
) {
    companion object {
        val EMPTY = Gate3bTreeFingerprint("", 0, 0, 0, 0, 0)
    }
}

internal data class Gate3bTreeScan(
    val fingerprint: Gate3bTreeFingerprint,
    val manifestBytes: ByteArray?,
)

internal object Gate3bTreeScanner {
    fun scan(root: Path, includeManifest: Boolean): Gate3bTreeScan {
        check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Missing tree: $root" }
        check(!includeManifest || root.fileName.toString() == ROOT_PATH) {
            "Comparable Stage 3B tree evidence must be rooted at $ROOT_PATH"
        }
        val normalizedRoot = root.toAbsolutePath().normalize()
        val entries = Files.walk(normalizedRoot).use { stream ->
            stream.iterator().asSequence()
                .filter { it != normalizedRoot }
                .sortedBy { normalizedRoot.relativize(it).joinToString("/") }
                .toList()
        }
        check(entries.size + 1 <= MAX_ENTRIES) { "Stage 3B tree evidence has too many entries" }

        val treeDigest = MessageDigest.getInstance("SHA-256")
        val manifest = if (includeManifest) {
            StringBuilder().apply {
                append(Gate3bTreeManifestCodec.MAGIC).append('\n')
                append("D\t").append(ROOT_PATH).append('\n')
            }
        } else {
            null
        }
        var files = 0L
        var directories = 0L
        var symlinks = 0L
        var special = 0L
        var bytes = 0L
        val buffer = ByteArray(64 * 1024)

        entries.forEach { entry ->
            val relative = normalizedRoot.relativize(entry).joinToString("/")
            validateRelativePath(relative)
            digestField(treeDigest, relative)
            val manifestPath = "$ROOT_PATH/$relative"
            when {
                Files.isSymbolicLink(entry) -> {
                    symlinks += 1
                    val target = Files.readSymbolicLink(entry)
                    check(!target.isAbsolute) { "Absolute dependency symlink: $relative" }
                    val resolved = requireNotNull(entry.parent).resolve(target).normalize()
                    check(resolved.startsWith(normalizedRoot)) {
                        "Escaping dependency symlink: $relative"
                    }
                    digestField(treeDigest, "L")
                    digestField(treeDigest, target.toString())
                    check(manifest == null) {
                        "Tree manifest cannot represent dependency symlinks: $relative"
                    }
                }

                Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) -> {
                    directories += 1
                    digestField(treeDigest, "D")
                    manifest?.append("D\t")?.append(manifestPath)?.append('\n')
                }

                Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) -> {
                    files += 1
                    val size = Files.size(entry)
                    bytes = Math.addExact(bytes, size)
                    digestField(treeDigest, "F")
                    digestField(treeDigest, size.toString())
                    val fileDigest = manifest?.let { MessageDigest.getInstance("SHA-256") }
                    Files.newInputStream(entry, LinkOption.NOFOLLOW_LINKS).use { input ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) {
                                treeDigest.update(buffer, 0, count)
                                fileDigest?.update(buffer, 0, count)
                            }
                        }
                    }
                    if (manifest != null) {
                        manifest.append("F\t")
                            .append(manifestPath)
                            .append('\t')
                            .append(size)
                            .append('\t')
                            .append(requireNotNull(fileDigest).digest().toHex())
                            .append('\n')
                    }
                }

                else -> {
                    special += 1
                    digestField(treeDigest, "S")
                    check(manifest == null) {
                        "Tree manifest cannot represent special dependency entry: $relative"
                    }
                }
            }
        }

        val manifestBytes = manifest?.toString()?.toByteArray(StandardCharsets.UTF_8)
        check(manifestBytes == null || manifestBytes.size <= Gate3bTreeEvidenceStore.MAX_BYTES) {
            "Stage 3B tree evidence exceeded its byte limit"
        }
        return Gate3bTreeScan(
            fingerprint = Gate3bTreeFingerprint(
                sha256 = treeDigest.digest().toHex(),
                files = files,
                directories = directories,
                symlinks = symlinks,
                special = special,
                bytes = bytes,
            ),
            manifestBytes = manifestBytes,
        )
    }

    private fun validateRelativePath(relative: String) {
        check(
            relative.isNotBlank() &&
                '\\' !in relative &&
                '\t' !in relative &&
                '\n' !in relative &&
                '\r' !in relative &&
                '\u0000' !in relative &&
                relative.split('/').none { it.isBlank() || it == "." || it == ".." },
        ) {
            "Unsafe dependency path in Stage 3B evidence"
        }
    }

    private fun digestField(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }

    private const val ROOT_PATH = "node_modules"
    private const val MAX_ENTRIES = 50_000
}

internal data class Gate3bPersistedTreeEvidence(
    val sha256: String,
    val bytes: Long,
)

internal data class Gate3bLoadedTreeManifest(
    val manifest: Gate3bTreeManifest,
    val sha256: String,
    val bytes: Long,
)

internal data class Gate3bLoadedBytes(
    val bytes: ByteArray,
    val sha256: String,
)

internal object Gate3bTreeEvidenceStore {
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_HIDDEN_LOCK_BYTES = 1024 * 1024

    fun persist(
        context: Context,
        candidate: StmDependencySupplyCandidate,
        bytes: ByteArray,
    ): Gate3bPersistedTreeEvidence {
        check(
            candidate == StmDependencySupplyCandidate.NPM_CLI ||
                candidate == StmDependencySupplyCandidate.ARBORIST,
        ) {
            "Only device-side dependency candidates produce local tree evidence"
        }
        check(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) {
            "Stage 3B tree evidence has an invalid size"
        }
        Gate3bTreeManifestCodec.parse(bytes)

        return persistBytes(evidenceFile(context, candidate), bytes, candidate.name)
    }

    fun persistHiddenLock(
        context: Context,
        candidate: StmDependencySupplyCandidate,
        source: File,
    ): Gate3bPersistedTreeEvidence {
        check(
            !Files.isSymbolicLink(source.toPath()) &&
                Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                source.length() in 1..MAX_HIDDEN_LOCK_BYTES.toLong(),
        ) {
            "Stage 3B hidden lock evidence is missing, unsafe, or oversized"
        }
        val bytes = Files.readAllBytes(source.toPath())
        JSONObject(bytes.toString(StandardCharsets.UTF_8))
        return persistBytes(hiddenLockEvidenceFile(context, candidate), bytes, candidate.name)
    }

    private fun persistBytes(
        target: File,
        bytes: ByteArray,
        label: String,
    ): Gate3bPersistedTreeEvidence {
        val parent = requireNotNull(target.parentFile)
        Files.createDirectories(parent.toPath())
        check(
            !Files.isSymbolicLink(parent.toPath()) &&
                Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS),
        ) {
            "Stage 3B tree evidence parent is unsafe"
        }
        val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            moveReplacing(temporary.toPath(), target.toPath())
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
        val persistedBytes = Files.readAllBytes(target.toPath())
        check(persistedBytes.contentEquals(bytes)) { "Persisted $label evidence changed" }
        return Gate3bPersistedTreeEvidence(
            MessageDigest.getInstance("SHA-256").digest(persistedBytes).toHex(),
            persistedBytes.size.toLong(),
        )
    }

    fun evidenceFile(context: Context, candidate: StmDependencySupplyCandidate): File = File(
        StmCorePaths.cacheRoot(context.applicationContext),
        "experiments/gate3b/tree-evidence/" +
            "${StmCoreGate3bPrebuiltExperiment.ST_COMMIT}/${candidate.name.lowercase()}.tsv",
    ).absoluteFile

    fun hiddenLockEvidenceFile(
        context: Context,
        candidate: StmDependencySupplyCandidate,
    ): File = File(
        StmCorePaths.cacheRoot(context.applicationContext),
        "experiments/gate3b/tree-evidence/" +
            "${StmCoreGate3bPrebuiltExperiment.ST_COMMIT}/" +
            "${candidate.name.lowercase()}-hidden-lock.json",
    ).absoluteFile

    fun loadHiddenLock(file: File, label: String): Gate3bLoadedBytes {
        val path = file.toPath()
        check(
            !Files.isSymbolicLink(path) &&
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                file.length() in 1..MAX_HIDDEN_LOCK_BYTES.toLong(),
        ) {
            "$label is missing, unsafe, or oversized"
        }
        val bytes = Files.readAllBytes(path)
        JSONObject(bytes.toString(StandardCharsets.UTF_8))
        return Gate3bLoadedBytes(
            bytes,
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
        )
    }

    fun load(file: File, label: String): Gate3bLoadedTreeManifest {
        val path = file.toPath()
        check(
            !Files.isSymbolicLink(path) &&
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                file.length() in 1..MAX_BYTES.toLong(),
        ) {
            "$label is missing, unsafe, or oversized"
        }
        val bytes = Files.readAllBytes(path)
        return Gate3bLoadedTreeManifest(
            manifest = Gate3bTreeManifestCodec.parse(bytes),
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
            bytes = bytes.size.toLong(),
        )
    }

    private fun moveReplacing(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal enum class Gate3bTreeEntryType {
    DIRECTORY,
    FILE,
}

internal data class Gate3bTreeManifestEntry(
    val type: Gate3bTreeEntryType,
    val path: String,
    val size: Long = 0,
    val sha256: String = "",
)

internal data class Gate3bTreeManifest(
    val entries: LinkedHashMap<String, Gate3bTreeManifestEntry>,
) {
    val fileCount: Int = entries.values.count { it.type == Gate3bTreeEntryType.FILE }
    val directoryCount: Int = entries.size - fileCount
    val fileBytes: Long = entries.values.fold(0L) { total, entry ->
        if (entry.type == Gate3bTreeEntryType.FILE) Math.addExact(total, entry.size) else total
    }
}

internal data class Gate3bTreeDiff(
    val differentPaths: Int,
    val onlyLeft: Int,
    val onlyRight: Int,
    val typeMismatches: Int,
    val sizeMismatches: Int,
    val contentMismatches: Int,
    val byteDeltaRightMinusLeft: Long,
    val details: String,
)

internal object Gate3bTreeManifestCodec {
    const val MAGIC = "STM_DEPENDENCY_TREE_MANIFEST_V1"

    fun parse(bytes: ByteArray): Gate3bTreeManifest {
        check(bytes.isNotEmpty() && bytes.size <= Gate3bTreeEvidenceStore.MAX_BYTES) {
            "Tree manifest has an invalid size"
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        check(text.endsWith('\n') && '\r' !in text && '\u0000' !in text) {
            "Tree manifest must use canonical UTF-8 lines"
        }
        val lines = text.dropLast(1).split('\n')
        check(lines.firstOrNull() == MAGIC && lines.size in 2..50_001) {
            "Tree manifest magic or entry count is invalid"
        }
        val entries = linkedMapOf<String, Gate3bTreeManifestEntry>()
        var previousPath: String? = null
        lines.drop(1).forEach { line ->
            val fields = line.split('\t')
            val entry = when (fields.firstOrNull()) {
                "D" -> {
                    check(fields.size == 2) { "Directory tree record is malformed" }
                    Gate3bTreeManifestEntry(Gate3bTreeEntryType.DIRECTORY, fields[1])
                }

                "F" -> {
                    check(fields.size == 4) { "File tree record is malformed" }
                    val size = fields[2].toLongOrNull()
                    check(size != null && size >= 0) { "File tree record size is invalid" }
                    check(SHA256_PATTERN.matches(fields[3])) {
                        "File tree record SHA-256 is invalid"
                    }
                    Gate3bTreeManifestEntry(
                        Gate3bTreeEntryType.FILE,
                        fields[1],
                        size,
                        fields[3],
                    )
                }

                else -> error("Tree manifest entry type is invalid")
            }
            validateManifestPath(entry.path)
            check(previousPath == null || requireNotNull(previousPath) < entry.path) {
                "Tree manifest paths are not strictly sorted"
            }
            check(entries.put(entry.path, entry) == null) { "Tree manifest path is duplicated" }
            previousPath = entry.path
        }
        check(entries[ROOT_PATH]?.type == Gate3bTreeEntryType.DIRECTORY) {
            "Tree manifest does not contain the node_modules root"
        }
        return Gate3bTreeManifest(entries)
    }

    fun compare(left: Gate3bTreeManifest, right: Gate3bTreeManifest): Gate3bTreeDiff {
        var onlyLeft = 0
        var onlyRight = 0
        var typeMismatches = 0
        var sizeMismatches = 0
        var contentMismatches = 0
        var differentPaths = 0
        val details = mutableListOf<String>()
        (left.entries.keys + right.entries.keys).toSortedSet().forEach { path ->
            val leftEntry = left.entries[path]
            val rightEntry = right.entries[path]
            when {
                leftEntry == null -> {
                    onlyRight += 1
                    differentPaths += 1
                    addDetail(details, "$path|only_right|${rightEntry?.describe()}")
                }

                rightEntry == null -> {
                    onlyLeft += 1
                    differentPaths += 1
                    addDetail(details, "$path|only_left|${leftEntry.describe()}")
                }

                leftEntry.type != rightEntry.type -> {
                    typeMismatches += 1
                    differentPaths += 1
                    addDetail(
                        details,
                        "$path|type|${leftEntry.describe()}|${rightEntry.describe()}",
                    )
                }

                leftEntry.type == Gate3bTreeEntryType.FILE -> {
                    val sizeMismatch = leftEntry.size != rightEntry.size
                    val contentMismatch = leftEntry.sha256 != rightEntry.sha256
                    if (sizeMismatch) sizeMismatches += 1
                    if (contentMismatch) contentMismatches += 1
                    if (sizeMismatch || contentMismatch) {
                        differentPaths += 1
                        addDetail(
                            details,
                            "$path|file|${leftEntry.size}:${leftEntry.sha256}|" +
                                "${rightEntry.size}:${rightEntry.sha256}",
                        )
                    }
                }
            }
        }
        return Gate3bTreeDiff(
            differentPaths = differentPaths,
            onlyLeft = onlyLeft,
            onlyRight = onlyRight,
            typeMismatches = typeMismatches,
            sizeMismatches = sizeMismatches,
            contentMismatches = contentMismatches,
            byteDeltaRightMinusLeft = Math.subtractExact(right.fileBytes, left.fileBytes),
            details = details.joinToString("\n"),
        )
    }

    private fun validateManifestPath(path: String) {
        check(
            path == ROOT_PATH ||
                (
                    path.startsWith("$ROOT_PATH/") &&
                        '\\' !in path &&
                        path.split('/').none { it.isBlank() || it == "." || it == ".." }
                    ),
        ) {
            "Tree manifest path escaped node_modules"
        }
    }

    private fun Gate3bTreeManifestEntry.describe(): String = when (type) {
        Gate3bTreeEntryType.DIRECTORY -> "directory"
        Gate3bTreeEntryType.FILE -> "file:$size:$sha256"
    }

    private fun addDetail(details: MutableList<String>, detail: String) {
        if (details.size < MAX_DETAIL_PATHS) details += detail
    }

    private const val ROOT_PATH = "node_modules"
    private const val MAX_DETAIL_PATHS = 20
    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
}

internal data class Gate3bJsonDiffResult(
    val count: Int,
    val details: String,
)

internal object Gate3bJsonDiff {
    fun toPlainValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { key ->
            toPlainValue(value.opt(key))
        }
        is JSONArray -> List(value.length()) { index -> toPlainValue(value.opt(index)) }
        else -> value
    }

    fun compare(left: Any?, right: Any?): Gate3bJsonDiffResult {
        val details = mutableListOf<String>()
        var count = 0

        fun visit(leftValue: Any?, rightValue: Any?, path: String) {
            when {
                leftValue is Map<*, *> && rightValue is Map<*, *> -> {
                    val leftMap = leftValue.entries.associate { (key, value) ->
                        key.toString() to value
                    }
                    val rightMap = rightValue.entries.associate { (key, value) ->
                        key.toString() to value
                    }
                    val keys = (leftMap.keys + rightMap.keys).toSortedSet()
                    keys.forEach { key ->
                        val escapedKey = key.replace("~", "~0").replace("/", "~1")
                        val nextPath = "$path/$escapedKey"
                        val hasLeft = leftMap.containsKey(key)
                        val hasRight = rightMap.containsKey(key)
                        when {
                            !hasLeft -> {
                                count += 1
                                addDetail(details, "$nextPath|only_right|${rightMap[key].jsonSummary()}")
                            }

                            !hasRight -> {
                                count += 1
                                addDetail(details, "$nextPath|only_left|${leftMap[key].jsonSummary()}")
                            }

                            else -> visit(leftMap[key], rightMap[key], nextPath)
                        }
                    }
                }

                leftValue is List<*> && rightValue is List<*> -> {
                    val maximum = maxOf(leftValue.size, rightValue.size)
                    repeat(maximum) { index ->
                        val nextPath = "$path/$index"
                        when {
                            index >= leftValue.size -> {
                                count += 1
                                addDetail(
                                    details,
                                    "$nextPath|only_right|${rightValue[index].jsonSummary()}",
                                )
                            }

                            index >= rightValue.size -> {
                                count += 1
                                addDetail(
                                    details,
                                    "$nextPath|only_left|${leftValue[index].jsonSummary()}",
                                )
                            }

                            else -> visit(leftValue[index], rightValue[index], nextPath)
                        }
                    }
                }

                !jsonEquals(leftValue, rightValue) -> {
                    count += 1
                    addDetail(
                        details,
                        "$path|value|${leftValue.jsonSummary()}|${rightValue.jsonSummary()}",
                    )
                }
            }
        }

        visit(left, right, "")
        return Gate3bJsonDiffResult(count, details.joinToString("\n"))
    }

    private fun jsonEquals(left: Any?, right: Any?): Boolean = when {
        left == null || right == null -> left == right
        left is Number && right is Number -> left.toString() == right.toString()
        else -> left == right
    }

    private fun Any?.jsonSummary(): String = when (this) {
        null -> "null"
        is Map<*, *> -> "object"
        is List<*> -> "array:$size"
        else -> toString().replace("\n", "\\n").take(160)
    }

    private fun addDetail(details: MutableList<String>, detail: String) {
        if (details.size < MAX_DETAILS) details += detail
    }

    private const val MAX_DETAILS = 40
}

private fun java.io.InputStream.readBounded(maximumBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(32 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count > 0) {
            check(output.size() + count <= maximumBytes) { "Bounded diagnostic file is oversized" }
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
