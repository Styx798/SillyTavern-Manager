package io.github.styx798.sillytavernmanager.stmcore.testing

import android.os.Debug
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Low-overhead, debug-only process sampler for the Gate 3A performance experiment. */
internal class Gate3aPerformanceSampler(
    runRoot: File,
    private val observedArtifactRoot: File,
) : AutoCloseable {
    val statusFile = File(runRoot, "android-status.csv")
    val smapsFile = File(runRoot, "android-smaps.csv")
    val stageFile = File(runRoot, "android-stage-memory.csv")
    val topologyFile = File(runRoot, "android-topology.csv")
    val artifactFile = File(runRoot, "artifact-events.csv")

    @Volatile
    var peakRssKb: Long = 0L
        private set

    @Volatile
    var peakRssStage: String = "unobserved"
        private set

    @Volatile
    var peakPssKb: Long = 0L
        private set

    @Volatile
    var peakPssStage: String = "unobserved"
        private set

    @Volatile
    var peakUssKb: Long = 0L
        private set

    @Volatile
    var peakUssStage: String = "unobserved"
        private set

    @Volatile
    var peakThreads: Long = 0L
        private set

    val childProcessesObserved: Long
        get() = observedChildProcesses.size.toLong()

    val artifactEvents: Long
        get() = artifactEventCount.get()

    private val ioLock = Any()
    private val stageLock = Any()
    private val statusCaptureLock = Any()
    private val smapsCaptureLock = Any()
    private val topologyCaptureLock = Any()
    private val artifactCaptureLock = Any()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val failure = AtomicReference<Throwable?>()
    private val observedChildProcesses = linkedSetOf<String>()
    private val artifactSnapshots = mutableMapOf<String, ArtifactSnapshot>()
    private val artifactEventCount = AtomicLong()
    private var stage = "sampler_created"
    private var statusRowsSinceFlush = 0
    private val startedAt = SystemClock.elapsedRealtime()
    private val statusExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "STM-Gate3A-Perf-Status").apply { isDaemon = true }
    }
    private val smapsExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "STM-Gate3A-Perf-Smaps").apply { isDaemon = true }
    }
    private val topologyExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "STM-Gate3A-Perf-Topology").apply { isDaemon = true }
    }
    private val artifactExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "STM-Gate3A-Perf-Artifacts").apply { isDaemon = true }
    }
    private val statusWriter: BufferedWriter = statusFile.bufferedWriter().apply {
        write(
            "wall_time_ms,elapsed_ms,stage,vm_rss_kb,vm_hwm_kb,rss_anon_kb," +
                "rss_file_kb,rss_shmem_kb,vm_swap_kb,threads\n",
        )
        flush()
    }
    private val smapsWriter: BufferedWriter = smapsFile.bufferedWriter().apply {
        write(
            "wall_time_ms,elapsed_ms,stage,rss_kb,pss_kb,pss_anon_kb,pss_file_kb," +
                "pss_shmem_kb,private_clean_kb,private_dirty_kb,uss_kb_approx," +
                "swap_kb,swap_pss_kb\n",
        )
        flush()
    }
    private val stageWriter: BufferedWriter = stageFile.bufferedWriter().apply {
        write(
            "wall_time_ms,elapsed_ms,stage,total_pss_kb,uss_kb_approx,dalvik_pss_kb," +
                "native_pss_kb,other_pss_kb,native_heap_allocated_bytes," +
                "native_heap_size_bytes,native_heap_free_bytes,java_heap_used_bytes," +
                "summary_java_heap_kb,summary_native_heap_kb,summary_code_kb," +
                "summary_stack_kb,summary_graphics_kb,summary_private_other_kb," +
                "summary_system_kb,open_fds\n",
        )
        flush()
    }
    private val topologyWriter: BufferedWriter = topologyFile.bufferedWriter().apply {
        write(
            "wall_time_ms,elapsed_ms,stage,process_pid,thread_count,child_count," +
                "child_pids,thread_name_counts,child_names\n",
        )
        flush()
    }
    private val artifactWriter: BufferedWriter = artifactFile.bufferedWriter().apply {
        write(
            "wall_time_ms,elapsed_ms,stage,event,relative_path,size_bytes,last_modified_ms\n",
        )
        flush()
    }

    fun start() {
        check(started.compareAndSet(false, true)) { "Gate 3A performance sampler already started" }
        statusExecutor.scheduleAtFixedRate(
            { captureSafely(::captureStatus) },
            0L,
            STATUS_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
        smapsExecutor.scheduleAtFixedRate(
            { captureSafely(::captureSmaps) },
            0L,
            SMAPS_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
        topologyExecutor.scheduleAtFixedRate(
            { captureSafely(::captureTopology) },
            0L,
            TOPOLOGY_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
        artifactExecutor.scheduleAtFixedRate(
            { captureSafely(::captureObservedArtifacts) },
            0L,
            ARTIFACT_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun markStage(value: String) {
        check(!closed.get()) { "Gate 3A performance sampler is closed" }
        check(value.matches(STAGE_PATTERN)) { "Unsafe Gate 3A performance stage: $value" }
        synchronized(stageLock) { stage = value }
        captureSafely(::captureStatus)
        captureSafely(::captureSmaps)
        captureSafely(::captureTopology)
        captureSafely(::captureObservedArtifacts)
        captureSafely(::captureDebugStage)
        synchronized(ioLock) {
            statusWriter.flush()
            smapsWriter.flush()
            stageWriter.flush()
            topologyWriter.flush()
            artifactWriter.flush()
        }
    }

    fun requireHealthy() {
        failure.get()?.let { error ->
            throw IllegalStateException(
                "Gate 3A performance sampler failed: ${error.message}",
                error,
            )
        }
        check(statusFile.length() > 0L) { "Gate 3A status timeline is empty" }
        check(smapsFile.length() > 0L) { "Gate 3A smaps timeline is empty" }
        check(stageFile.length() > 0L) { "Gate 3A stage timeline is empty" }
        check(topologyFile.length() > 0L) { "Gate 3A topology timeline is empty" }
        check(artifactFile.length() > 0L) {
            "Gate 3A artifact timeline is empty"
        }
        check(artifactEventCount.get() > 0L) {
            "Gate 3A artifact timeline did not observe any output"
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        statusExecutor.shutdownNow()
        smapsExecutor.shutdownNow()
        topologyExecutor.shutdownNow()
        artifactExecutor.shutdownNow()
        runCatching { statusExecutor.awaitTermination(5L, TimeUnit.SECONDS) }
        runCatching { smapsExecutor.awaitTermination(5L, TimeUnit.SECONDS) }
        runCatching { topologyExecutor.awaitTermination(5L, TimeUnit.SECONDS) }
        runCatching { artifactExecutor.awaitTermination(5L, TimeUnit.SECONDS) }
        runCatching(::captureObservedArtifacts)
            .onFailure { error -> failure.compareAndSet(null, error) }
        synchronized(ioLock) {
            statusWriter.close()
            smapsWriter.close()
            stageWriter.close()
            topologyWriter.close()
            artifactWriter.close()
        }
    }

    private fun captureSafely(block: () -> Unit) {
        if (closed.get() || failure.get() != null) return
        runCatching(block).onFailure { error -> failure.compareAndSet(null, error) }
    }

    private fun captureStatus() = synchronized(statusCaptureLock) {
        val identity = sampleIdentity()
        val values = readProcKilobytes(PROC_STATUS)
        val rssKb = values["VmRSS"] ?: -1L
        val threads = values["Threads"] ?: -1L
        synchronized(ioLock) {
            if (rssKb > peakRssKb) {
                peakRssKb = rssKb
                peakRssStage = identity.stage
            }
            if (threads > peakThreads) peakThreads = threads
            statusWriter.write(
                identity.csvPrefix() + listOf(
                    rssKb,
                    values["VmHWM"] ?: -1L,
                    values["RssAnon"] ?: -1L,
                    values["RssFile"] ?: -1L,
                    values["RssShmem"] ?: -1L,
                    values["VmSwap"] ?: -1L,
                    threads,
                ).joinToString(",") + "\n",
            )
            statusRowsSinceFlush += 1
            if (statusRowsSinceFlush >= STATUS_ROWS_PER_FLUSH) {
                statusWriter.flush()
                statusRowsSinceFlush = 0
            }
        }
    }

    private fun captureSmaps() = synchronized(smapsCaptureLock) {
        val identity = sampleIdentity()
        val values = readProcKilobytes(PROC_SMAPS_ROLLUP)
        val pssKb = values["Pss"] ?: -1L
        val privateCleanKb = values["Private_Clean"] ?: -1L
        val privateDirtyKb = values["Private_Dirty"] ?: -1L
        val ussKb = if (privateCleanKb >= 0L && privateDirtyKb >= 0L) {
            privateCleanKb + privateDirtyKb
        } else {
            -1L
        }
        synchronized(ioLock) {
            if (pssKb > peakPssKb) {
                peakPssKb = pssKb
                peakPssStage = identity.stage
            }
            if (ussKb > peakUssKb) {
                peakUssKb = ussKb
                peakUssStage = identity.stage
            }
            smapsWriter.write(
                identity.csvPrefix() + listOf(
                    values["Rss"] ?: -1L,
                    pssKb,
                    values["Pss_Anon"] ?: -1L,
                    values["Pss_File"] ?: -1L,
                    values["Pss_Shmem"] ?: -1L,
                    privateCleanKb,
                    privateDirtyKb,
                    ussKb,
                    values["Swap"] ?: -1L,
                    values["SwapPss"] ?: -1L,
                ).joinToString(",") + "\n",
            )
            smapsWriter.flush()
        }
    }

    private fun captureDebugStage() {
        val identity = sampleIdentity()
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        val memoryStats = memory.memoryStats
        synchronized(ioLock) {
            stageWriter.write(
                identity.csvPrefix() + listOf(
                    memory.totalPss,
                    memory.totalPrivateClean + memory.totalPrivateDirty,
                    memory.dalvikPss,
                    memory.nativePss,
                    memory.otherPss,
                    Debug.getNativeHeapAllocatedSize(),
                    Debug.getNativeHeapSize(),
                    Debug.getNativeHeapFreeSize(),
                    runtime.totalMemory() - runtime.freeMemory(),
                    memoryStats.kilobytes("summary.java-heap"),
                    memoryStats.kilobytes("summary.native-heap"),
                    memoryStats.kilobytes("summary.code"),
                    memoryStats.kilobytes("summary.stack"),
                    memoryStats.kilobytes("summary.graphics"),
                    memoryStats.kilobytes("summary.private-other"),
                    memoryStats.kilobytes("summary.system"),
                    PROC_FD.list()?.size ?: -1,
                ).joinToString(",") + "\n",
            )
        }
    }

    private fun captureTopology() = synchronized(topologyCaptureLock) {
        val identity = sampleIdentity()
        val taskDirectories = PROC_TASK.listFiles()
            ?.filter { directory ->
                directory.isDirectory && directory.name.all(Char::isDigit)
            }
            ?.sortedBy { it.name.toLongOrNull() ?: Long.MAX_VALUE }
            .orEmpty()
        val threadNameCounts = taskDirectories
            .mapNotNull { task -> File(task, "comm").readTextOrNull()?.trim() }
            .filter(String::isNotEmpty)
            .map { name -> name.sanitizeCell() }
            .groupingBy { name -> name }
            .eachCount()
            .toSortedMap()
            .entries
            .joinToString(";") { (name, count) -> "$name=$count" }
        val childPids = taskDirectories
            .flatMap { task ->
                File(task, "children").readTextOrNull()
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.filter(String::isNotEmpty)
                    .orEmpty()
            }
            .filter { value -> value.all(Char::isDigit) }
            .distinct()
            .sortedBy { value -> value.toLongOrNull() ?: Long.MAX_VALUE }
        val childNames = childPids.joinToString(";") { pid ->
            val name = File("/proc/$pid/comm").readTextOrNull()?.trim().orEmpty().sanitizeCell()
            synchronized(ioLock) {
                observedChildProcesses += "$pid:$name"
            }
            "$pid:$name"
        }
        synchronized(ioLock) {
            topologyWriter.write(
                identity.csvPrefix() + listOf(
                    android.os.Process.myPid(),
                    taskDirectories.size,
                    childPids.size,
                    childPids.joinToString(";"),
                    threadNameCounts,
                    childNames,
                ).joinToString(",") + "\n",
            )
            topologyWriter.flush()
        }
    }

    private fun captureObservedArtifacts() = synchronized(artifactCaptureLock) {
        if (!observedArtifactRoot.isDirectory) return@synchronized
        val identity = sampleIdentity()
        val current = observedArtifactRoot.walkTopDown()
            .maxDepth(ARTIFACT_SCAN_MAX_DEPTH)
            .filter { file ->
                file.isFile && (
                    file.name == "lib.js" ||
                        file.name == "lib.js.LICENSE.txt" ||
                        file.name.contains(".pack")
                    )
            }
            .associate { file ->
                val relative = observedArtifactRoot.toPath()
                    .relativize(file.toPath())
                    .toString()
                    .sanitizeCell()
                relative to ArtifactSnapshot(
                    sizeBytes = file.length(),
                    lastModifiedMillis = file.lastModified(),
                )
            }
            .toSortedMap()
        synchronized(ioLock) {
            current.forEach { (relative, snapshot) ->
                val previous = artifactSnapshots[relative]
                if (snapshot != previous) {
                    artifactWriter.write(
                        identity.csvPrefix() + listOf(
                            if (previous == null) "created" else "changed",
                            relative,
                            snapshot.sizeBytes,
                            snapshot.lastModifiedMillis,
                        ).joinToString(",") + "\n",
                    )
                    artifactEventCount.incrementAndGet()
                }
            }
            artifactSnapshots.keys
                .filterNot(current::containsKey)
                .sorted()
                .forEach { relative ->
                    artifactWriter.write(
                        identity.csvPrefix() + listOf(
                            "deleted",
                            relative,
                            -1L,
                            -1L,
                        ).joinToString(",") + "\n",
                    )
                    artifactEventCount.incrementAndGet()
                }
            artifactSnapshots.clear()
            artifactSnapshots.putAll(current)
            artifactWriter.flush()
        }
    }

    private fun sampleIdentity(): SampleIdentity = synchronized(stageLock) {
        SampleIdentity(
            wallTimeMillis = System.currentTimeMillis(),
            elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
            stage = stage,
        )
    }

    private fun readProcKilobytes(file: File): Map<String, Long> =
        file.useLines { lines ->
            lines.mapNotNull { line ->
                val key = line.substringBefore(':', missingDelimiterValue = "").trim()
                if (key.isEmpty()) return@mapNotNull null
                val value = line.substringAfter(':', missingDelimiterValue = "")
                    .trim()
                    .substringBefore(' ')
                    .toLongOrNull()
                    ?: return@mapNotNull null
                key to value
            }.toMap()
        }

    private fun File.readTextOrNull(): String? = runCatching {
        if (!Files.isRegularFile(toPath(), LinkOption.NOFOLLOW_LINKS)) return@runCatching null
        readText(Charsets.UTF_8)
    }.getOrNull()

    private fun String.sanitizeCell(): String =
        replace(',', '_')
            .replace(';', '_')
            .replace('\n', '_')
            .replace('\r', '_')

    private fun Map<String, String>.kilobytes(key: String): Long =
        get(key)?.toLongOrNull() ?: -1L

    private companion object {
        val PROC_STATUS = File("/proc/self/status")
        val PROC_SMAPS_ROLLUP = File("/proc/self/smaps_rollup")
        val PROC_FD = File("/proc/self/fd")
        val PROC_TASK = File("/proc/self/task")
        val STAGE_PATTERN = Regex("[a-z0-9_]{1,64}")
        const val STATUS_INTERVAL_MILLIS = 250L
        const val SMAPS_INTERVAL_MILLIS = 2_000L
        const val TOPOLOGY_INTERVAL_MILLIS = 2_000L
        const val ARTIFACT_INTERVAL_MILLIS = 500L
        const val ARTIFACT_SCAN_MAX_DEPTH = 8
        const val STATUS_ROWS_PER_FLUSH = 16
    }
}

private data class ArtifactSnapshot(
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

private data class SampleIdentity(
    val wallTimeMillis: Long,
    val elapsedMillis: Long,
    val stage: String,
) {
    fun csvPrefix(): String = "$wallTimeMillis,$elapsedMillis,$stage,"
}
