package io.github.styx798.sillytavernmanager.stmcore.testing

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.storage.OnObbStateChangeListener
import android.os.storage.StorageManager
import io.github.styx798.sillytavernmanager.stmcore.FeatherEngine
import io.github.styx798.sillytavernmanager.stmcore.StmCorePaths
import io.github.styx798.sillytavernmanager.stmcore.StmSillyTavernLaunchFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * Debug-only physical-device comparison of the existing extracted READY slot and an AOSP JOBB
 * image made from the exact same ST 1.18.0 runtime. It never changes slot admission or active-slot
 * state and is not a production Runtime Image backend.
 */
internal class StmCoreGate3bRuntimeImageObbExperiment(
    context: Context,
) : StmCoreGate3bExperimentRunner {
    private val appContext = context.applicationContext
    private val cancelled = AtomicBoolean(false)
    private val teardownLock = Any()

    @Volatile
    private var callbackThread: HandlerThread? = null

    @Volatile
    private var activeStorageManager: StorageManager? = null

    @Volatile
    private var activeObbPath: String? = null

    @Volatile
    private var activeEngine: FeatherEngine? = null

    @Volatile
    private var activeCallback: RuntimeImageCallback? = null

    @Volatile
    private var activeSignal: RuntimeImageSignal? = null

    override fun cancel() {
        cancelled.set(true)
        activeCallback?.cancelAll("Runtime Image OBB experiment was cancelled")
        activeEngine?.requestGracefulStop()
    }

    override fun hasLiveResources(): Boolean {
        val storage = activeStorageManager
        val path = activeObbPath
        val mounted = storage != null && path != null &&
            runCatching { storage.isObbMounted(path) }.getOrDefault(false)
        return activeEngine != null || mounted || callbackThread?.isAlive == true
    }

    override fun finishTeardown(): Boolean = synchronized(teardownLock) {
        cancel()
        val signal = activeSignal
        activeEngine?.requestGracefulStop()
        signal?.stopped?.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val engineDestroyed = activeEngine?.destroyAndAwait(ENGINE_DESTROY_TIMEOUT_SECONDS) ?: true
        activeEngine = null
        activeCallback = null
        activeSignal = null

        val storage = activeStorageManager
        val rawPath = activeObbPath
        val unmounted = if (storage != null && rawPath != null) {
            unmountObb(storage, rawPath).success
        } else {
            true
        }
        activeStorageManager = null
        activeObbPath = null
        stopCallbackThread()
        engineDestroyed && unmounted
    }

    override fun run(): Map<String, String> {
        val totalStarted = SystemClock.elapsedRealtime()
        val sampler = Gate3bMemorySampler().also(Gate3bMemorySampler::start)
        val experimentRoot = File(
            StmCorePaths.cacheRoot(appContext),
            "experiments/runtime-image-obb-${UUID.randomUUID()}",
        ).absoluteFile
        var cleanup = "not_attempted"
        var result = "failed"
        var failure = ""
        val evidence = linkedMapOf<String, String>()

        try {
            requireNotCancelled()
            check(experimentRoot.mkdirs()) { "Could not create the Runtime Image experiment root" }

            val extractedSlot = requireExtractedSlot()
            val extractedProgram = requireDirectDirectory(
                extractedSlot,
                File(extractedSlot, ARCHIVE_ROOT),
                "extracted READY program",
            )

            val syncStarted = SystemClock.elapsedRealtime()
            val syncedFiles = syncRegularFiles(extractedProgram.toPath())
            evidence["extracted_fsync_ms"] =
                (SystemClock.elapsedRealtime() - syncStarted).toString()
            evidence["extracted_fsync_files"] = syncedFiles.toString()

            requireNotCancelled()
            val extractedScanStarted = SystemClock.elapsedRealtime()
            val extractedTree = Gate3bTreeScanner.scan(
                extractedProgram.toPath(),
                includeManifest = false,
            ).fingerprint
            evidence["extracted_tree_scan_ms"] =
                (SystemClock.elapsedRealtime() - extractedScanStarted).toString()
            evidence["extracted_tree_sha256"] = extractedTree.sha256
            evidence["extracted_files"] = extractedTree.files.toString()
            evidence["extracted_directories"] = extractedTree.directories.toString()
            evidence["extracted_bytes"] = extractedTree.bytes.toString()
            check(extractedTree.symlinks == 0L && extractedTree.special == 0L) {
                "The fixed extracted runtime contains unsupported entries"
            }

            val obb = requireObb()
            evidence["obb_bytes"] = obb.length().toString()
            val obbHashStarted = SystemClock.elapsedRealtime()
            val observedObbSha256 = sha256(obb.toPath())
            evidence["obb_hash_ms"] = (SystemClock.elapsedRealtime() - obbHashStarted).toString()
            evidence["obb_sha256"] = observedObbSha256
            check(observedObbSha256 == OBB_SHA256) {
                "Runtime Image OBB SHA-256 changed"
            }

            requireNotCancelled()
            val storage = requireNotNull(
                appContext.getSystemService(Context.STORAGE_SERVICE) as? StorageManager,
            ) { "StorageManager is unavailable" }
            activeStorageManager = storage
            activeObbPath = obb.canonicalPath
            check(!storage.isObbMounted(obb.canonicalPath)) {
                "Runtime Image OBB was already mounted before the experiment"
            }

            val mountStarted = SystemClock.elapsedRealtime()
            val mount = mountObb(storage, obb.canonicalPath)
            evidence["obb_mount_ms"] = (SystemClock.elapsedRealtime() - mountStarted).toString()
            evidence["obb_mount_state"] = mount.state.toString()
            evidence["obb_mount_queued"] = mount.queued.toString()
            check(mount.success) { "OBB mount failed with state ${mount.state}" }
            val mountedRoot = requireNotNull(storage.getMountedObbPath(obb.canonicalPath))
                .let(::File)
                .canonicalFile
            evidence["obb_mounted_path"] = mountedRoot.absolutePath
            check(mountedRoot.isDirectory) { "OBB mounted root is unavailable" }

            val writeProbe = File(mountedRoot, WRITE_PROBE)
            check(!writeProbe.exists()) { "Runtime Image write probe already exists" }
            val writeSucceeded = runCatching { writeProbe.createNewFile() }.getOrDefault(false)
            if (writeSucceeded) {
                runCatching { writeProbe.delete() }
                error("Mounted Runtime Image accepted a write")
            }
            evidence["obb_read_only"] = "true"

            val mountedProgram = requireDirectDirectory(
                mountedRoot,
                File(mountedRoot, ARCHIVE_ROOT),
                "mounted Runtime Image program",
            )
            val mountedScanStarted = SystemClock.elapsedRealtime()
            val mountedTree = Gate3bTreeScanner.scan(
                mountedProgram.toPath(),
                includeManifest = false,
            ).fingerprint
            evidence["obb_tree_scan_ms"] =
                (SystemClock.elapsedRealtime() - mountedScanStarted).toString()
            evidence["obb_tree_sha256"] = mountedTree.sha256
            evidence["obb_tree_matches_extracted"] = (mountedTree == extractedTree).toString()
            check(mountedTree == extractedTree) {
                "Mounted Runtime Image tree differs from the extracted READY program"
            }

            requireNotCancelled()
            val prepared = StmSillyTavernLaunchFactory.prepare(
                slotRoot = mountedRoot,
                archiveRoot = ARCHIVE_ROOT,
                dataRoot = File(experimentRoot, "data"),
                sessionDirectory = File(experimentRoot, "session"),
                logsRoot = File(experimentRoot, "logs"),
                expectedVersion = ST_VERSION,
            )
            val callback = RuntimeImageCallback()
            val engine = FeatherEngine(callback)
            val sessionId = "runtime-image-obb-${UUID.randomUUID()}"
            val signal = callback.register(sessionId)
            activeCallback = callback
            activeEngine = engine
            activeSignal = signal

            val startStarted = SystemClock.elapsedRealtime()
            engine.start(sessionId, File(experimentRoot, "session"), prepared.launchSpec)
            check(signal.ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out waiting for ST from the mounted Runtime Image"
            }
            signal.failure?.let(::error)
            requireNotCancelled()
            val port = signal.port
            check(port == prepared.selectedPort) {
                "Mounted ST listened on $port instead of ${prepared.selectedPort}"
            }
            evidence["start_ms"] = (SystemClock.elapsedRealtime() - startStarted).toString()
            evidence["node_version"] = requireNotNull(signal.nodeVersion)
            evidence["port"] = port.toString()

            val baseUrl = "http://127.0.0.1:$port"
            val version = httpGet("$baseUrl/version")
            check(
                version.code == 200 &&
                    JSONObject(version.body.toString(Charsets.UTF_8))
                        .getString("pkgVersion") == ST_VERSION,
            ) {
                "Mounted ST /version acceptance failed"
            }
            evidence["version"] = "200:${version.body.size}:$ST_VERSION"

            val home = httpGet("$baseUrl/")
            check(
                home.code == 200 &&
                    home.body.toString(Charsets.UTF_8).contains("<title>SillyTavern</title>"),
            ) {
                "Mounted ST homepage acceptance failed"
            }
            evidence["home"] = "200:${home.body.size}"

            val bundle = httpGet("$baseUrl/lib.js")
            val bundleSha256 = sha256(bundle.body)
            check(
                bundle.code == 200 &&
                    bundle.body.size.toLong() == BUNDLE_BYTES &&
                    bundleSha256 == BUNDLE_SHA256,
            ) {
                "Mounted ST /lib.js acceptance failed"
            }
            evidence["lib_js"] = "200:${bundle.body.size}:$bundleSha256"

            val stopStarted = SystemClock.elapsedRealtime()
            check(engine.requestGracefulStop()) {
                "Feather Engine rejected the mounted ST stop request"
            }
            check(signal.stopped.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out stopping ST from the mounted Runtime Image"
            }
            signal.failure?.let(::error)
            check(!signal.terminationUsed) {
                "Mounted ST required forced termination"
            }
            evidence["stop_ms"] = (SystemClock.elapsedRealtime() - stopStarted).toString()
            check(engine.destroyAndAwait(ENGINE_DESTROY_TIMEOUT_SECONDS)) {
                "Mounted ST Feather Engine teardown timed out"
            }
            activeEngine = null
            activeCallback = null
            activeSignal = null
            check(awaitPortReleased(port)) {
                "Mounted ST loopback port remained open"
            }
            evidence["port_released"] = "true"

            val unmountStarted = SystemClock.elapsedRealtime()
            val unmount = unmountObb(storage, obb.canonicalPath)
            evidence["obb_unmount_ms"] =
                (SystemClock.elapsedRealtime() - unmountStarted).toString()
            evidence["obb_unmount_state"] = unmount.state.toString()
            check(unmount.success && !storage.isObbMounted(obb.canonicalPath)) {
                "Runtime Image OBB did not unmount cleanly: ${unmount.state}"
            }
            activeStorageManager = null
            activeObbPath = null
            stopCallbackThread()
            result = "passed"
        } catch (error: Throwable) {
            failure = error.safeDetail()
        } finally {
            sampler.close()
            if (hasLiveResources()) {
                runCatching { finishTeardown() }
                    .onFailure { error ->
                        if (failure.isBlank()) failure = error.safeDetail()
                    }
            }
            cleanup = runCatching {
                deleteExactExperimentRoot(experimentRoot)
                "removed"
            }.getOrElse { error -> "retained:${error.safeDetail()}" }
        }

        return linkedMapOf(
            "result" to result,
            "meaning" to "debug_only_physical_device_spike_not_plan_v2_not_slot_admission",
            "device_model" to android.os.Build.MODEL,
            "api_level" to android.os.Build.VERSION.SDK_INT.toString(),
            "abi" to android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            "st_version" to ST_VERSION,
            "st_commit" to ST_COMMIT,
            "obb_format" to "AOSP_JOBB_FAT",
            "total_elapsed_ms" to (SystemClock.elapsedRealtime() - totalStarted).toString(),
            "peak_rss_kb" to sampler.peakRssKilobytes.get().toString(),
            "vm_hwm_kb" to sampler.maximumVmHwmKilobytes.get().toString(),
            "cleanup" to cleanup,
            "failure" to failure,
        ).apply { putAll(evidence) }
    }

    private fun requireExtractedSlot(): File {
        val slots = StmCorePaths.slotsRoot(appContext).canonicalFile
        val slot = File(slots, SLOT_ID).absoluteFile
        check(slot.canonicalFile.parentFile == slots && slot.isDirectory) {
            "The fixed extracted READY slot is unavailable"
        }
        return slot.canonicalFile
    }

    private fun requireObb(): File {
        val obbRoot = requireNotNull(appContext.obbDir).canonicalFile
        val obb = File(obbRoot, OBB_FILE).absoluteFile
        check(
            obb.canonicalFile.parentFile == obbRoot &&
                Files.isRegularFile(obb.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                obb.length() == OBB_BYTES
        ) {
            "The fixed Runtime Image OBB is missing, linked, or changed"
        }
        return obb.canonicalFile
    }

    private fun requireDirectDirectory(parent: File, child: File, label: String): File {
        val realParent = parent.canonicalFile
        val realChild = child.canonicalFile
        check(
            realChild.parentFile == realParent &&
                !Files.isSymbolicLink(child.toPath()) &&
                Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            "$label is unavailable or escaped its direct parent"
        }
        return realChild
    }

    private fun syncRegularFiles(root: Path): Long {
        val count = AtomicInteger(0)
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                requireNotCancelled()
                check(attributes.isRegularFile && !Files.isSymbolicLink(file)) {
                    "Extracted runtime contains a non-regular file"
                }
                FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
                    channel.force(true)
                }
                count.incrementAndGet()
                return FileVisitResult.CONTINUE
            }
        })
        return count.get().toLong()
    }

    private fun mountObb(storage: StorageManager, rawPath: String): ObbOperation {
        val thread = requireCallbackThread()
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        val state = AtomicInteger(Int.MIN_VALUE)
        val queued = AtomicBoolean(false)
        check(handler.post {
            val listener = object : OnObbStateChangeListener() {
                override fun onObbStateChange(path: String?, observedState: Int) {
                    if (path == rawPath) {
                        state.set(observedState)
                        latch.countDown()
                    }
                }
            }
            queued.set(storage.mountObb(rawPath, null, listener))
            if (!queued.get()) latch.countDown()
        }) {
            "Could not schedule the OBB mount"
        }
        check(latch.await(OBB_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "Timed out waiting for the OBB mount callback"
        }
        return ObbOperation(
            queued = queued.get(),
            state = state.get(),
            success = queued.get() && state.get() == OnObbStateChangeListener.MOUNTED,
        )
    }

    private fun unmountObb(storage: StorageManager, rawPath: String): ObbOperation {
        if (!storage.isObbMounted(rawPath)) {
            return ObbOperation(queued = true, state = OnObbStateChangeListener.UNMOUNTED, success = true)
        }
        val thread = requireCallbackThread()
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        val state = AtomicInteger(Int.MIN_VALUE)
        val queued = AtomicBoolean(false)
        check(handler.post {
            val listener = object : OnObbStateChangeListener() {
                override fun onObbStateChange(path: String?, observedState: Int) {
                    if (path == rawPath) {
                        state.set(observedState)
                        latch.countDown()
                    }
                }
            }
            queued.set(storage.unmountObb(rawPath, true, listener))
            if (!queued.get()) latch.countDown()
        }) {
            "Could not schedule the OBB unmount"
        }
        check(latch.await(OBB_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "Timed out waiting for the OBB unmount callback"
        }
        return ObbOperation(
            queued = queued.get(),
            state = state.get(),
            success = queued.get() && state.get() == OnObbStateChangeListener.UNMOUNTED,
        )
    }

    private fun requireCallbackThread(): HandlerThread {
        callbackThread?.takeIf { it.isAlive }?.let { return it }
        return HandlerThread("STM-Runtime-Image-OBB").apply {
            start()
            callbackThread = this
        }
    }

    private fun stopCallbackThread() {
        callbackThread?.let { thread ->
            thread.quitSafely()
            if (Thread.currentThread() !== thread) thread.join(CALLBACK_THREAD_JOIN_MILLIS)
        }
        callbackThread = null
    }

    private fun httpGet(url: String): RuntimeImageHttpEvidence {
        val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        return try {
            connection.connectTimeout = HTTP_TIMEOUT_MILLIS
            connection.readTimeout = HTTP_TIMEOUT_MILLIS
            connection.requestMethod = "GET"
            val code = connection.responseCode
            val source = if (code in 200..399) connection.inputStream else connection.errorStream
            RuntimeImageHttpEvidence(code, source?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    private fun awaitPortReleased(port: Int): Boolean {
        val deadline = SystemClock.elapsedRealtime() + PORT_RELEASE_TIMEOUT_MILLIS
        while (isPortOpen(port) && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(PORT_POLL_MILLIS)
        }
        return !isPortOpen(port)
    }

    private fun isPortOpen(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), PORT_CONNECT_TIMEOUT_MILLIS)
        }
    }.isSuccess

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }

    private fun deleteExactExperimentRoot(root: File) {
        val parent = File(StmCorePaths.cacheRoot(appContext), "experiments").canonicalFile
        if (!root.exists()) return
        val canonical = root.canonicalFile
        check(canonical.parentFile == parent && canonical.name.startsWith("runtime-image-obb-")) {
            "Runtime Image cleanup target escaped its exact experiment parent"
        }
        Files.walkFileTree(canonical.toPath(), object : SimpleFileVisitor<Path>() {
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

    private fun requireNotCancelled() {
        check(!cancelled.get()) { "Runtime Image OBB experiment was cancelled" }
    }

    private fun Throwable.safeDetail(): String =
        "${javaClass.simpleName}: ${message.orEmpty()}"
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .take(MAX_RESULT_CHARS)

    private companion object {
        const val SLOT_ID = "st-release-8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8"
        const val ARCHIVE_ROOT = "SillyTavern-8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8"
        const val ST_COMMIT = "8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8"
        const val ST_VERSION = "1.18.0"
        const val OBB_FILE = "main.1.io.github.styx798.sillytavernmanager.obb"
        const val OBB_BYTES = 515_678_276L
        const val OBB_SHA256 =
            "0993fcc9cf1596d2eb0591a40dc63ab74ac05cf6104906a7d09105f40478a85f"
        const val BUNDLE_SHA256 =
            "2d5fb1eedcbefe7062421e8ca54b90a23312f64df8d480c16538714c5157e0bf"
        const val BUNDLE_BYTES = 1_947_206L
        const val WRITE_PROBE = ".stm-runtime-image-write-probe"
        const val HASH_BUFFER_BYTES = 64 * 1024
        const val START_TIMEOUT_SECONDS = 240L
        const val STOP_TIMEOUT_SECONDS = 20L
        const val ENGINE_DESTROY_TIMEOUT_SECONDS = 12L
        const val OBB_OPERATION_TIMEOUT_SECONDS = 30L
        const val CALLBACK_THREAD_JOIN_MILLIS = 5_000L
        const val HTTP_TIMEOUT_MILLIS = 10_000
        const val PORT_RELEASE_TIMEOUT_MILLIS = 5_000L
        const val PORT_CONNECT_TIMEOUT_MILLIS = 200
        const val PORT_POLL_MILLIS = 50L
        const val MAX_RESULT_CHARS = 4_000
    }
}

private data class ObbOperation(
    val queued: Boolean,
    val state: Int,
    val success: Boolean,
)

private data class RuntimeImageHttpEvidence(
    val code: Int,
    val body: ByteArray,
)

private class RuntimeImageCallback : FeatherEngine.Callback {
    private val sessions = ConcurrentHashMap<String, RuntimeImageSignal>()
    private val cancellation = AtomicReference<String?>(null)

    fun register(sessionId: String): RuntimeImageSignal = RuntimeImageSignal().also { signal ->
        check(sessions.putIfAbsent(sessionId, signal) == null) {
            "Duplicate Runtime Image session"
        }
        cancellation.get()?.let(signal::cancel)
    }

    fun cancelAll(detail: String) {
        cancellation.set(detail)
        sessions.values.forEach { signal -> signal.cancel(detail) }
    }

    override fun onNodeCreated(sessionId: String, nodeVersion: String) {
        sessions[sessionId]?.nodeVersion = nodeVersion
    }

    override fun onReady(sessionId: String, port: Int, nodeVersion: String) {
        sessions[sessionId]?.let { signal ->
            signal.nodeVersion = nodeVersion
            signal.port = port
            signal.ready.countDown()
        }
    }

    override fun onStopped(sessionId: String, terminationUsed: Boolean) {
        sessions[sessionId]?.let { signal ->
            signal.terminationUsed = terminationUsed
            signal.stopped.countDown()
        }
    }

    override fun onFailure(sessionId: String, detail: String) {
        sessions[sessionId]?.let { signal ->
            signal.failure = detail
            signal.ready.countDown()
            signal.stopped.countDown()
        }
    }
}

private class RuntimeImageSignal {
    val ready = CountDownLatch(1)
    val stopped = CountDownLatch(1)

    @Volatile
    var nodeVersion: String? = null

    @Volatile
    var port: Int = 0

    @Volatile
    var failure: String? = null

    @Volatile
    var terminationUsed: Boolean = false

    fun cancel(detail: String) {
        failure = detail
        ready.countDown()
        stopped.countDown()
    }
}
