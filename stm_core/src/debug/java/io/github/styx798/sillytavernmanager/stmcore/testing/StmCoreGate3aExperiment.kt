package io.github.styx798.sillytavernmanager.stmcore.testing

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import io.github.styx798.sillytavernmanager.stmcore.BuildConfig
import io.github.styx798.sillytavernmanager.stmcore.FeatherEngine
import io.github.styx798.sillytavernmanager.stmcore.FeatherEngineLaunchSpec
import io.github.styx798.sillytavernmanager.stmcore.LoopbackHealthProbe
import io.github.styx798.sillytavernmanager.stmcore.LoopbackProbeResult
import io.github.styx798.sillytavernmanager.stmcore.StmCorePaths
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Debug-only Gate 3A spike. It never promotes real SillyTavern source to a READY slot. */
internal enum class Gate3aRunProfile(
    val measuresPerformance: Boolean,
    val disablesWebpackCacheCompression: Boolean,
    val usesPrebuiltBundle: Boolean,
) {
    ACCEPTANCE(
        measuresPerformance = false,
        disablesWebpackCacheCompression = false,
        usesPrebuiltBundle = false,
    ),
    PERFORMANCE(
        measuresPerformance = true,
        disablesWebpackCacheCompression = false,
        usesPrebuiltBundle = false,
    ),
    PERFORMANCE_NO_COMPRESSION(
        measuresPerformance = true,
        disablesWebpackCacheCompression = true,
        usesPrebuiltBundle = false,
    ),
    PERFORMANCE_PREBUILT_BUNDLE(
        measuresPerformance = true,
        disablesWebpackCacheCompression = false,
        usesPrebuiltBundle = true,
    ),
}

internal class StmCoreGate3aExperiment(
    private val context: Context,
    private val profile: Gate3aRunProfile = Gate3aRunProfile.ACCEPTANCE,
) {
    @Volatile
    private var cancellationRequested = false

    @Volatile
    private var activeCallback: Gate3aEngineCallback? = null

    fun cancel() {
        cancellationRequested = true
        activeCallback?.cancelAll("Gate 3A was cancelled")
    }

    fun run(): Map<String, String> {
        val programRoot = File(
            StmCorePaths.cacheRoot(context),
            "experiments/gate3a/$ST_COMMIT/program",
        ).absoluteFile
        requireProgramTree(programRoot)

        val programBefore = fingerprintTree(programRoot.toPath())
        requireFixedProgramFingerprint(programBefore)
        val slotsRoot = StmCorePaths.slotsRoot(context).absoluteFile
        val slotsBefore = fingerprintTree(slotsRoot.toPath())
        val activeSlotFile = StmCorePaths.activeSlotFile(context).absoluteFile
        val activeSlotBefore = fingerprintOptionalControlFile(activeSlotFile)
        val prebuiltBundleRoot = if (profile.usesPrebuiltBundle) {
            requirePrebuiltBundleRoot()
        } else {
            null
        }
        val prebuiltBundleBefore = prebuiltBundleRoot?.let { root ->
            fingerprintTree(root.toPath())
        }

        val runId = "run-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val runRoot = File(
            StmCorePaths.dataRoot(context),
            "gate3a/$ST_COMMIT/$runId",
        ).absoluteFile
        val dataRoot = File(runRoot, "data")
        val configFile = File(runRoot, "config.yaml")
        val tempRoot = File(runRoot, "tmp")
        val sessionsRoot = File(runRoot, "sessions")
        val logFile = File(runRoot, "gate3a-node.log")
        listOf(dataRoot, tempRoot, sessionsRoot).forEach { directory ->
            check(directory.mkdirs()) { "Gate 3A directory could not be created: $directory" }
        }
        prepareConfig(programRoot, configFile)
        logFile.createNewFile()
        val gate3aRoot = requireNotNull(runRoot.parentFile?.parentFile)
        check(gate3aRoot.isDirectory || gate3aRoot.mkdirs()) {
            "Gate 3A evidence root could not be created"
        }
        File(gate3aRoot, LATEST_RUN_FILE).writeText(runRoot.absolutePath, Charsets.UTF_8)

        val webpackRoot = File(dataRoot, "_webpack")
        val nodeMemoryFile = if (profile.measuresPerformance) {
            File(runRoot, "node-memory.jsonl").apply { createNewFile() }
        } else {
            null
        }
        val selectedPort = reserveLoopbackPort()
        val processCwdBefore = File(".").canonicalPath
        val vmHwmBefore = readProcStatusKilobytes("VmHWM")
        val acceptanceSampler = if (profile == Gate3aRunProfile.ACCEPTANCE) {
            ProcessMemorySampler().apply { start() }
        } else {
            null
        }
        val performanceSampler = if (profile.measuresPerformance) {
            Gate3aPerformanceSampler(
                runRoot,
                prebuiltBundleRoot ?: webpackRoot,
            ).apply { start() }
        } else {
            null
        }
        val callback = Gate3aEngineCallback()
        val engine = FeatherEngine(callback)
        activeCallback = callback

        val first: SessionEvidence
        val second: SessionEvidence
        try {
            check(!cancellationRequested) { "Gate 3A was cancelled before startup" }
            if (performanceSampler != null) {
                performanceSampler.markStage("core_idle")
                awaitPerformanceWindow(PERFORMANCE_IDLE_BASELINE_SECONDS)
            }
            first = runSession(
                engine = engine,
                callback = callback,
                label = "first",
                sessionDirectory = File(sessionsRoot, "first"),
                launchSpec = createLaunchSpec(
                    programRoot,
                    dataRoot,
                    configFile,
                    tempRoot,
                    logFile,
                    selectedPort,
                    "first",
                    nodeMemoryFile,
                    profile,
                    prebuiltBundleRoot,
                ),
                expectedPort = selectedPort,
                performanceSampler = performanceSampler,
            )
            second = runSession(
                engine = engine,
                callback = callback,
                label = "second",
                sessionDirectory = File(sessionsRoot, "second"),
                launchSpec = createLaunchSpec(
                    programRoot,
                    dataRoot,
                    configFile,
                    tempRoot,
                    logFile,
                    selectedPort,
                    "second",
                    nodeMemoryFile,
                    profile,
                    prebuiltBundleRoot,
                ),
                expectedPort = selectedPort,
                performanceSampler = performanceSampler,
            )
        } finally {
            try {
                check(engine.destroyAndAwait(ENGINE_DESTROY_TIMEOUT_SECONDS)) {
                    "Gate 3A Feather Engine teardown did not complete"
                }
                performanceSampler?.markStage("engine_destroyed")
            } finally {
                try {
                    acceptanceSampler?.close()
                    performanceSampler?.close()
                } finally {
                    activeCallback = null
                }
            }
        }
        performanceSampler?.requireHealthy()
        nodeMemoryFile?.let { file ->
            check(file.length() > 0L) { "Gate 3A Node memory timeline is empty" }
        }

        val processCwdAfter = File(".").canonicalPath
        check(processCwdAfter == processCwdBefore) {
            "Feather Engine did not restore the Core process cwd: $processCwdAfter"
        }
        val programAfter = fingerprintTree(programRoot.toPath())
        check(programAfter == programBefore) {
            "The real SillyTavern program tree changed during Gate 3A"
        }
        val slotsAfter = fingerprintTree(slotsRoot.toPath())
        check(slotsAfter == slotsBefore) {
            "The immutable slot tree changed during Gate 3A"
        }
        val activeSlotAfter = fingerprintOptionalControlFile(activeSlotFile)
        check(activeSlotAfter == activeSlotBefore) {
            "The active-slot pointer changed during Gate 3A"
        }
        val dataSummary = summarizeTree(runRoot.toPath())
        if (profile.usesPrebuiltBundle) {
            check(!webpackRoot.exists()) {
                "Prebuilt-bundle runtime unexpectedly created a Webpack output directory"
            }
        } else {
            check(webpackRoot.isDirectory) {
                "SillyTavern did not place its webpack output under the isolated data root"
            }
        }
        prebuiltBundleRoot?.let { root ->
            check(fingerprintTree(root.toPath()) == prebuiltBundleBefore) {
                "The prebuilt frontend bundle changed during Gate 3A"
            }
        }
        check(configFile.readText(Charsets.UTF_8).contains("backend: builtin")) {
            "Gate 3A config did not retain git.backend=builtin"
        }
        val nodeLog = logFile.readText(Charsets.UTF_8)
        val webpackOverrideCount = nodeLog.lineSequence().count { line ->
            line.contains(WEBPACK_NO_COMPRESSION_MARKER)
        }
        val prebuiltAdapterCount = nodeLog.lineSequence().count { line ->
            line.contains(PREBUILT_WEBPACK_ADAPTER_MARKER)
        }
        val webpackCompilerLogCount = nodeLog.lineSequence().count { line ->
            line.contains("Compiling frontend libraries...")
        }
        if (profile.disablesWebpackCacheCompression) {
            check(webpackOverrideCount == 2) {
                "Webpack no-compression adapter was applied $webpackOverrideCount times instead of 2"
            }
        } else {
            check(webpackOverrideCount == 0) {
                "Webpack no-compression adapter unexpectedly affected the control profile"
            }
        }
        if (profile.usesPrebuiltBundle) {
            check(prebuiltAdapterCount == 2) {
                "Prebuilt Webpack adapter was applied $prebuiltAdapterCount times instead of 2"
            }
            check(webpackCompilerLogCount == 0) {
                "Prebuilt-bundle runtime still emitted $webpackCompilerLogCount compiler logs"
            }
        } else {
            check(prebuiltAdapterCount == 0) {
                "Prebuilt Webpack adapter unexpectedly affected a compile profile"
            }
            check(webpackCompilerLogCount == 2) {
                "Compile profile emitted $webpackCompilerLogCount compiler logs instead of 2"
            }
        }

        return buildMap {
            put("result", "passed")
            put("repository", ST_REPOSITORY)
            put("commit_sha", ST_COMMIT)
            put("st_version", ST_VERSION)
            put("package_lock_sha256", EXPECTED_PACKAGE_LOCK_SHA256)
            put("program_root", programRoot.absolutePath)
            put("program_fingerprint_before", programBefore.sha256)
            put("program_fingerprint_after", programAfter.sha256)
            put("program_files", programBefore.files.toString())
            put("program_directories", programBefore.directories.toString())
            put("program_symlinks", programBefore.symlinks.toString())
            put("program_bytes", programBefore.bytes.toString())
            put("slots_fingerprint_before", slotsBefore.sha256)
            put("slots_fingerprint_after", slotsAfter.sha256)
            put("active_slot_fingerprint_before", activeSlotBefore)
            put("active_slot_fingerprint_after", activeSlotAfter)
            put("data_root", dataRoot.absolutePath)
            put("config_path", configFile.absolutePath)
            put("webpack_root", webpackRoot.absolutePath)
            put("webpack_root_created", webpackRoot.exists().toString())
            put("run_files", dataSummary.files.toString())
            put("run_directories", dataSummary.directories.toString())
            put("run_symlinks", dataSummary.symlinks.toString())
            put("run_bytes", dataSummary.bytes.toString())
            put("node_log", logFile.absolutePath)
            put("node_log_tail", nodeLog.takeLast(LOG_TAIL_CHARS))
            put("node_version_first", first.nodeVersion)
            put("node_version_second", second.nodeVersion)
            put("port", selectedPort.toString())
            put("first_start_ms", first.startMillis.toString())
            put("second_start_ms", second.startMillis.toString())
            put("first_stop_ms", first.stopMillis.toString())
            put("second_stop_ms", second.stopMillis.toString())
            put("first_version_status", first.versionStatus)
            put("second_version_status", second.versionStatus)
            put("first_home_status", first.homeStatus)
            put("second_home_status", second.homeStatus)
            put("http_url_connection", first.urlConnectionEvidence)
            put("lib_js_first", first.libJsEvidence)
            put("lib_js_second", second.libJsEvidence)
            put("port_released_first", first.portReleased.toString())
            put("port_released_second", second.portReleased.toString())
            put("termination_used_first", first.terminationUsed.toString())
            put("termination_used_second", second.terminationUsed.toString())
            put("code_generation_policy", "javet_allow_eval_true")
            put(
                "code_generation_probe",
                "lexical_eval=42,multi_parameter_function=42,native_function_unchanged=true",
            )
            put("code_generation_bridge", "none")
            acceptanceSampler?.let { sampler ->
                put("peak_pss_kb", sampler.peakPssKb.get().toString())
                put("peak_native_heap_bytes", sampler.peakNativeHeapBytes.get().toString())
            }
            performanceSampler?.let { sampler ->
                put("performance_profile", "measurement_only")
                put(
                    "optimization_case",
                    when {
                        profile.usesPrebuiltBundle -> "prebuilt_bundle_runtime_no_webpack"
                        profile.disablesWebpackCacheCompression ->
                            "webpack_cache_compression_false"

                        else -> "upstream_webpack_cache_gzip_control"
                    },
                )
                put(
                    "webpack_cache_compression",
                    when {
                        profile.usesPrebuiltBundle -> "not_applicable"
                        profile.disablesWebpackCacheCompression -> "false"
                        else -> "gzip"
                    },
                )
                put("webpack_config_source_sha256", EXPECTED_WEBPACK_CONFIG_SHA256)
                put("webpack_serve_source_sha256", EXPECTED_WEBPACK_SERVE_SHA256)
                put("webpack_config_override_count", webpackOverrideCount.toString())
                put("prebuilt_adapter_count", prebuiltAdapterCount.toString())
                put("webpack_compiler_log_count", webpackCompilerLogCount.toString())
                put(
                    "runtime_forbidden_webpack_load_count",
                    if (profile.usesPrebuiltBundle) "0" else "not_measured",
                )
                put(
                    "runtime_webpack_load_gate",
                    if (profile.usesPrebuiltBundle) "passed" else "not_applicable",
                )
                prebuiltBundleRoot?.let { root ->
                    put("prebuilt_bundle_root", root.absolutePath)
                    put("prebuilt_bundle_fingerprint", requireNotNull(prebuiltBundleBefore).sha256)
                    put("prebuilt_lib_js_sha256", EXPECTED_LIB_JS_SHA256)
                    put("prebuilt_lib_license_sha256", EXPECTED_LIB_LICENSE_SHA256)
                }
                put(
                    "webpack_config_override",
                    when {
                        profile.usesPrebuiltBundle ->
                            "debug_only_commit_keyed_whole_module_adapter"

                        profile.disablesWebpackCacheCompression ->
                            "debug_only_node_register_hooks_exact_source_transform"

                        else -> "none"
                    },
                )
                put(
                    "first_start_definition",
                    if (profile.usesPrebuiltBundle) {
                        "fresh_data_prebuilt_bundle_fresh_node_runtime"
                    } else {
                        "fresh_data_and_webpack_cache_fresh_node_runtime"
                    },
                )
                put(
                    "second_start_definition",
                    if (profile.usesPrebuiltBundle) {
                        "reused_data_prebuilt_bundle_fresh_node_runtime"
                    } else {
                        "reused_data_and_webpack_cache_fresh_node_runtime"
                    },
                )
                put("program_page_cache_note", "program_tree_was_fingerprinted_before_timing")
                put("android_status_timeline", sampler.statusFile.absolutePath)
                put("android_status_sha256", sha256(sampler.statusFile))
                put("android_smaps_timeline", sampler.smapsFile.absolutePath)
                put("android_smaps_sha256", sha256(sampler.smapsFile))
                put("android_stage_timeline", sampler.stageFile.absolutePath)
                put("android_stage_sha256", sha256(sampler.stageFile))
                put("android_topology_timeline", sampler.topologyFile.absolutePath)
                put("android_topology_sha256", sha256(sampler.topologyFile))
                put(
                    "observed_artifact_root",
                    (prebuiltBundleRoot ?: webpackRoot).absolutePath,
                )
                put("artifact_timeline", sampler.artifactFile.absolutePath)
                put("artifact_sha256", sha256(sampler.artifactFile))
                put("node_memory_timeline", requireNotNull(nodeMemoryFile).absolutePath)
                put("node_memory_sha256", sha256(nodeMemoryFile))
                put("peak_rss_kb", sampler.peakRssKb.toString())
                put("peak_rss_stage", sampler.peakRssStage)
                put("peak_pss_kb", sampler.peakPssKb.toString())
                put("peak_pss_stage", sampler.peakPssStage)
                put("peak_uss_kb_approx", sampler.peakUssKb.toString())
                put("peak_uss_stage", sampler.peakUssStage)
                put("peak_threads", sampler.peakThreads.toString())
                put("child_processes_observed", sampler.childProcessesObserved.toString())
                put("artifact_events", sampler.artifactEvents.toString())
                put(
                    "sampling",
                    "status_250ms_smaps_2s_topology_2s_artifacts_500ms_debug_memory_stage_only",
                )
            }
            put("vm_hwm_kb", readProcStatusKilobytes("VmHWM").toString())
            put("vm_hwm_before_kb", vmHwmBefore.toString())
            put("vm_hwm_scope", "core_process_lifetime_not_experiment_resettable")
            put("process_id", Process.myPid().toString())
            put("process_cwd_restored", (processCwdAfter == processCwdBefore).toString())
            put("javet_artifact", BuildConfig.JAVET_ARTIFACT)
            put("trust_boundary", "debug_only_non_delivery_artifact")
            put(
                "real_ready_slot_created",
                (slotsAfter != slotsBefore || activeSlotAfter != activeSlotBefore).toString(),
            )
        }
    }

    private fun runSession(
        engine: FeatherEngine,
        callback: Gate3aEngineCallback,
        label: String,
        sessionDirectory: File,
        launchSpec: FeatherEngineLaunchSpec,
        expectedPort: Int,
        performanceSampler: Gate3aPerformanceSampler?,
    ): SessionEvidence {
        check(!cancellationRequested) { "Gate 3A was cancelled before $label" }
        val sessionId = "gate3a-$label-${UUID.randomUUID()}"
        val signal = callback.register(sessionId)
        performanceSampler?.markStage("${label}_node_starting")
        val startAt = SystemClock.elapsedRealtime()
        engine.start(sessionId, sessionDirectory.absoluteFile, launchSpec)
        check(signal.ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "Timed out starting real SillyTavern during $label"
        }
        signal.failure?.let { error(it) }
        val actualPort = signal.port
        check(actualPort == expectedPort) {
            "Real SillyTavern listened on $actualPort instead of $expectedPort"
        }
        val startMillis = SystemClock.elapsedRealtime() - startAt
        performanceSampler?.markStage("${label}_health_ready")
        val baseUrl = "http://127.0.0.1:$actualPort"
        val version = requireHealthyVersion(baseUrl)
        val home = requireHealthyHome(baseUrl)
        val urlConnectionEvidence = httpUrlConnectionEvidence(baseUrl)
        val libJsEvidence = requireLibJs(baseUrl)

        if (performanceSampler != null) {
            awaitPerformanceWindow(PERFORMANCE_READY_SHORT_SECONDS)
            performanceSampler.markStage("${label}_ready_30s")
            awaitPerformanceWindow(
                PERFORMANCE_READY_STEADY_SECONDS - PERFORMANCE_READY_SHORT_SECONDS,
            )
            performanceSampler.markStage("${label}_ready_300s")
        }

        performanceSampler?.markStage("${label}_pre_stop")
        val stopAt = SystemClock.elapsedRealtime()
        check(engine.requestGracefulStop()) { "Feather Engine rejected the $label stop request" }
        check(signal.stopped.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "Timed out stopping real SillyTavern during $label"
        }
        signal.failure?.let { error(it) }
        val stopMillis = SystemClock.elapsedRealtime() - stopAt
        check(!signal.terminationUsed) {
            "Real SillyTavern required forced termination during the $label stop"
        }
        val portReleased = awaitPortReleased(actualPort)
        check(portReleased) { "Loopback port $actualPort remained open after the $label stop" }
        performanceSampler?.markStage("${label}_runtime_closed")
        if (performanceSampler != null) {
            awaitPerformanceWindow(PERFORMANCE_POST_STOP_SHORT_SECONDS)
            performanceSampler.markStage("${label}_post_stop_30s")
            awaitPerformanceWindow(
                PERFORMANCE_POST_STOP_STEADY_SECONDS - PERFORMANCE_POST_STOP_SHORT_SECONDS,
            )
            performanceSampler.markStage("${label}_post_stop_120s")
        }
        return SessionEvidence(
            nodeVersion = requireNotNull(signal.nodeVersion),
            startMillis = startMillis,
            stopMillis = stopMillis,
            versionStatus = version.statusLine,
            homeStatus = home.statusLine,
            urlConnectionEvidence = urlConnectionEvidence,
            libJsEvidence = libJsEvidence,
            portReleased = portReleased,
            terminationUsed = signal.terminationUsed,
        )
    }

    private fun requireHealthyVersion(baseUrl: String) =
        when (val result = Gate3aVersionProbe.execute(baseUrl)) {
            is LoopbackProbeResult.Healthy -> result.response
            is LoopbackProbeResult.Failed -> error(result.detail)
        }

    private fun requireHealthyHome(baseUrl: String) =
        when (val result = LoopbackHealthProbe.capture(baseUrl, "/")) {
            is LoopbackProbeResult.Failed -> error(result.detail)
            is LoopbackProbeResult.Healthy -> result.response.also { response ->
                check(response.statusCode == 200) {
                    "SillyTavern home returned HTTP ${response.statusCode}"
                }
                check(response.bodyUtf8().contains("<title>SillyTavern</title>")) {
                    "SillyTavern home did not contain the upstream page title"
                }
            }
        }

    private fun httpUrlConnectionEvidence(baseUrl: String): String {
        val attempts = listOf("/version", "/").map { path ->
            val connection = URL(baseUrl + path)
                .openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                connection.connectTimeout = HTTP_TIMEOUT_MILLIS
                connection.readTimeout = HTTP_TIMEOUT_MILLIS
                connection.requestMethod = "GET"
                val status = connection.responseCode
                val body = connection.inputStream.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                }
                check(status == 200) { "$path returned HTTP $status" }
                if (path == "/version") {
                    check(JSONObject(body).getString("pkgVersion") == ST_VERSION) {
                        "$path returned an unexpected SillyTavern version"
                    }
                } else {
                    check(body.contains("<title>SillyTavern</title>")) {
                        "$path returned an unexpected page"
                    }
                }
                "$path:200"
            } finally {
                connection.disconnect()
            }
        }
        return attempts.joinToString(" | ")
    }

    private fun requireLibJs(baseUrl: String): String {
        val connection = URL("$baseUrl/lib.js")
            .openConnection(Proxy.NO_PROXY) as HttpURLConnection
        return try {
            connection.connectTimeout = HTTP_TIMEOUT_MILLIS
            connection.readTimeout = HTTP_TIMEOUT_MILLIS
            connection.requestMethod = "GET"
            val status = connection.responseCode
            check(status == 200) { "/lib.js returned HTTP $status" }
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            connection.inputStream.buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        digest.update(buffer, 0, count)
                        bytes += count
                    }
                }
            }
            val actualSha = digest.digest().toHex()
            check(bytes == EXPECTED_LIB_JS_BYTES) {
                "/lib.js returned $bytes bytes instead of $EXPECTED_LIB_JS_BYTES"
            }
            check(actualSha == EXPECTED_LIB_JS_SHA256) {
                "/lib.js returned unexpected SHA-256 $actualSha"
            }
            "/lib.js:200:$bytes:$actualSha"
        } finally {
            connection.disconnect()
        }
    }

    private fun createLaunchSpec(
        programRoot: File,
        dataRoot: File,
        configFile: File,
        tempRoot: File,
        logFile: File,
        port: Int,
        label: String,
        nodeMemoryFile: File?,
        runProfile: Gate3aRunProfile,
        prebuiltBundleRoot: File?,
    ): FeatherEngineLaunchSpec {
        val serverFile = File(programRoot, "server.js").absoluteFile
        val loaderFile = File(programRoot, ".stm-gate3a-loader.cjs").absoluteFile
        val nodePerformanceBootstrap = if (nodeMemoryFile == null) {
            """
            state.memoryTimers = [];
            state.captureMemory = () => {};
            state.setMemoryPhase = () => {};
            """.trimIndent()
        } else {
            """
            const v8 = require('node:v8');
            const memoryFile = ${jsString(nodeMemoryFile.absolutePath)};
            state.memoryPhase = 'bootstrap_begin';
            state.memoryTimers = [];
            const safeMetric = value => Number.isSafeInteger(value) && value >= 0 ? value : null;
            state.captureMemory = phase => {
              try {
                const memory = process.memoryUsage();
                const heap = v8.getHeapStatistics();
                const usage = process.resourceUsage();
                const cpu = process.cpuUsage();
                const sample = {
                  schema_version: 1,
                  session: ${jsString(label)},
                  phase: String(phase),
                  wall_time_ms: Date.now(),
                  monotonic_ns: process.hrtime.bigint().toString(),
                  rss_bytes: safeMetric(memory.rss),
                  heap_total_bytes: safeMetric(memory.heapTotal),
                  heap_used_bytes: safeMetric(memory.heapUsed),
                  external_bytes: safeMetric(memory.external),
                  array_buffers_bytes: safeMetric(memory.arrayBuffers),
                  total_heap_size_bytes: safeMetric(heap.total_heap_size),
                  total_physical_size_bytes: safeMetric(heap.total_physical_size),
                  used_heap_size_bytes: safeMetric(heap.used_heap_size),
                  heap_size_limit_bytes: safeMetric(heap.heap_size_limit),
                  total_available_size_bytes: safeMetric(heap.total_available_size),
                  malloced_memory_bytes: safeMetric(heap.malloced_memory),
                  peak_malloced_memory_bytes: safeMetric(heap.peak_malloced_memory),
                  v8_external_memory_bytes: safeMetric(heap.external_memory),
                  native_contexts: safeMetric(heap.number_of_native_contexts),
                  detached_contexts: safeMetric(heap.number_of_detached_contexts),
                  total_global_handles_size_bytes: safeMetric(heap.total_global_handles_size),
                  used_global_handles_size_bytes: safeMetric(heap.used_global_handles_size),
                  resource_max_rss_kb: safeMetric(usage.maxRSS),
                  cpu_user_us: safeMetric(cpu.user),
                  cpu_system_us: safeMetric(cpu.system),
                  resource_user_cpu_us: safeMetric(usage.userCPUTime),
                  resource_system_cpu_us: safeMetric(usage.systemCPUTime),
                };
                fs.appendFileSync(memoryFile, JSON.stringify(sample) + '\n', 'utf8');
              } catch (_) {}
            };
            state.setMemoryPhase = phase => {
              state.memoryPhase = phase;
              state.captureMemory(phase);
            };
            state.captureMemory(state.memoryPhase);
            """.trimIndent()
        }
        val webpackExperimentBootstrap = when {
            runProfile.usesPrebuiltBundle -> {
                val bundleRoot = requireNotNull(prebuiltBundleRoot)
                val adapterSource =
                    """
                    export default function getWebpackServeMiddleware() {
                        const outputRoot = ${jsString(bundleRoot.absolutePath)};
                        function devMiddleware(req, res, next) {
                            if (req.method === 'GET' && req.path === '/lib.js') {
                                return res.sendFile('lib.js', { root: outputRoot });
                            }
                            next();
                        }
                        devMiddleware.runWebpackCompiler = async () => {};
                        return devMiddleware;
                    }
                    """.trimIndent()
                """
                state.webpackConfigAdapterApplied = false;
                state.prebuiltWebpackAdapterApplied = false;
                const crypto = require('node:crypto');
                const { registerHooks } = require('node:module');
                const { pathToFileURL } = require('node:url');
                const webpackServeUrl = pathToFileURL(
                  fs.realpathSync(
                    ${jsString(File(programRoot, "src/middleware/webpack-serve.js").absolutePath)}
                  )
                ).href;
                const webpackConfigUrl = pathToFileURL(
                  fs.realpathSync(${jsString(File(programRoot, "webpack.config.js").absolutePath)})
                ).href;
                const adapterSource = ${jsString(adapterSource)};
                registerHooks({
                  load(url, context, nextLoad) {
                    if (url === webpackConfigUrl ||
                        url.includes('/node_modules/webpack/') ||
                        url.includes('/node_modules/terser-webpack-plugin/') ||
                        url.includes('/node_modules/terser/')) {
                      throw new Error('Forbidden runtime Webpack module load: ' + url);
                    }
                    const result = nextLoad(url, context);
                    if (url !== webpackServeUrl) return result;
                    const source = typeof result.source === 'string'
                      ? result.source
                      : Buffer.from(result.source).toString('utf8');
                    const actualSha = crypto.createHash('sha256').update(source).digest('hex');
                    if (actualSha !== ${jsString(EXPECTED_WEBPACK_SERVE_SHA256)}) {
                      throw new Error('Unexpected webpack-serve.js SHA-256: ' + actualSha);
                    }
                    state.prebuiltWebpackAdapterApplied = true;
                    append('info', [${jsString(PREBUILT_WEBPACK_ADAPTER_MARKER)}, ${jsString(label)}]);
                    return { ...result, source: adapterSource };
                  },
                });
                """.trimIndent()
            }

            runProfile.disablesWebpackCacheCompression ->
                """
                state.webpackConfigAdapterApplied = false;
                state.prebuiltWebpackAdapterApplied = false;
                const crypto = require('node:crypto');
                const { registerHooks } = require('node:module');
                const { pathToFileURL } = require('node:url');
                const webpackConfigUrl = pathToFileURL(
                  fs.realpathSync(${jsString(File(programRoot, "webpack.config.js").absolutePath)})
                ).href;
                registerHooks({
                  load(url, context, nextLoad) {
                    const result = nextLoad(url, context);
                    if (url !== webpackConfigUrl) return result;
                    const source = typeof result.source === 'string'
                      ? result.source
                      : Buffer.from(result.source).toString('utf8');
                    const actualSha = crypto.createHash('sha256').update(source).digest('hex');
                    if (actualSha !== ${jsString(EXPECTED_WEBPACK_CONFIG_SHA256)}) {
                      throw new Error('Unexpected webpack.config.js SHA-256: ' + actualSha);
                    }
                    const needle = "compression: 'gzip',";
                    if (source.split(needle).length !== 2) {
                      throw new Error('Expected exactly one Webpack gzip cache configuration');
                    }
                    state.webpackConfigAdapterApplied = true;
                    append('info', [${jsString(WEBPACK_NO_COMPRESSION_MARKER)}, ${jsString(label)}]);
                    return { ...result, source: source.replace(needle, 'compression: false,') };
                  },
                });
                """.trimIndent()

            else ->
                """
                state.webpackConfigAdapterApplied = false;
                state.prebuiltWebpackAdapterApplied = false;
                """.trimIndent()
        }
        val bootstrap =
            """
            (() => {
              const fs = require('node:fs');
              const http = require('node:http');
              const util = require('node:util');
              const vm = require('node:vm');
              const lexicalValue = 40;
              const evalProbe = eval('lexicalValue + 2');
              const functionProbe = Function('left', 'right', 'return left + right;')(20, 22);
              const nativeFunctionUnchanged = Function.prototype.toString
                .call(Function)
                .includes('[native code]');
              if (evalProbe !== 42 || functionProbe !== 42 || !nativeFunctionUnchanged) {
                throw new Error('Gate 3A Javet code-generation policy failed its preflight');
              }
              const state = globalThis.__stmCore = {
                servers: [],
                server: null,
                port: 0,
                closed: false,
                error: '',
                requestCount: 0,
                lastRequest: '',
                logs: [],
                originalCwd: process.cwd(),
                originalCreateServer: http.createServer,
                originalConsole: {},
                originalEnv: {
                  NODE_ENV: process.env.NODE_ENV,
                  TMPDIR: process.env.TMPDIR,
                  TMP: process.env.TMP,
                  TEMP: process.env.TEMP,
                },
              };
              const logFile = ${jsString(logFile.absolutePath)};
              $nodePerformanceBootstrap
              const format = value => {
                if (value instanceof Error) return String(value.stack || value.message || value);
                if (typeof value === 'string') return value;
                return util.inspect(value, { depth: 3, maxArrayLength: 50, breakLength: 160 });
              };
              const append = (level, values) => {
                const line = '[' + new Date().toISOString() + '] [' + level + '] ' +
                  values.map(format).join(' ');
                state.logs.push(line);
                if (state.logs.length > 200) state.logs.shift();
                try {
                  if (fs.existsSync(logFile) && fs.statSync(logFile).size < $MAX_NODE_LOG_BYTES) {
                    fs.appendFileSync(logFile, line + '\n', 'utf8');
                  }
                } catch (_) {}
                if (level === 'error' && line.includes('A critical error has occurred while starting the server')) {
                  state.error = line;
                }
                if (line.includes('Compiling frontend libraries...')) {
                  state.setMemoryPhase('webpack_begin');
                } else if (line.includes('compiled') && line.includes('successfully')) {
                  state.setMemoryPhase('webpack_success');
                }
              };
              $webpackExperimentBootstrap
              for (const level of ['log', 'info', 'warn', 'error']) {
                const original = console[level].bind(console);
                state.originalConsole[level] = original;
                console[level] = (...values) => {
                  append(level, values);
                  original(...values);
                };
              }
              state.onUncaughtException = error => {
                state.error = 'uncaughtException: ' + format(error);
                append('error', [state.error]);
              };
              state.onUnhandledRejection = error => {
                state.error = 'unhandledRejection: ' + format(error);
                append('error', [state.error]);
              };
              process.prependListener('uncaughtException', state.onUncaughtException);
              process.prependListener('unhandledRejection', state.onUnhandledRejection);
              http.createServer = function(...args) {
                const server = state.originalCreateServer.apply(this, args);
                state.servers.push(server);
                state.server = server;
                server.on('request', request => {
                  state.requestCount += 1;
                  state.lastRequest = String(request.method) + ' ' + String(request.url);
                  if (!state.readyProbeSeen && String(request.url) === '/version') {
                    state.readyProbeSeen = true;
                    state.setMemoryPhase('health_ready');
                    for (const [delay, phase] of [[30000, 'ready_30s'], [300000, 'ready_300s']]) {
                      const timer = setTimeout(() => state.setMemoryPhase(phase), delay);
                      timer.unref?.();
                      state.memoryTimers.push(timer);
                    }
                  }
                });
                server.on('listening', () => {
                  if (${runProfile.disablesWebpackCacheCompression} &&
                      !state.webpackConfigAdapterApplied) {
                    state.error = 'Webpack no-compression adapter did not apply';
                    return;
                  }
                  if (${runProfile.usesPrebuiltBundle} &&
                      !state.prebuiltWebpackAdapterApplied) {
                    state.error = 'Prebuilt Webpack adapter did not apply';
                    return;
                  }
                  const address = server.address();
                  if (address && typeof address === 'object') state.port = Number(address.port || 0);
                  state.setMemoryPhase('server_listening');
                });
                server.on('close', () => {
                  if (state.servers.every(item => !item.listening)) state.closed = true;
                });
                server.on('error', error => {
                  state.error = 'serverError: ' + format(error);
                  append('error', [state.error]);
                });
                return server;
              };
              process.env.NODE_ENV = 'production';
              process.env.TMPDIR = ${jsString(tempRoot.absolutePath)};
              process.env.TMP = ${jsString(tempRoot.absolutePath)};
              process.env.TEMP = ${jsString(tempRoot.absolutePath)};
              process.chdir(${jsString(programRoot.absolutePath)});
              state.setMemoryPhase('st_import_begin');
              append('info', ['STM Gate 3A $label bootstrap', JSON.stringify(process.argv)]);
              const importExpression = ${jsString("import(${jsString(serverFile.toURI().toString())})")};
              const loader = new vm.Script(importExpression, {
                filename: ${jsString(loaderFile.absolutePath)},
                importModuleDynamically: vm.constants.USE_MAIN_CONTEXT_DEFAULT_LOADER,
              });
              Promise.resolve(loader.runInThisContext()).then(
                () => { state.importSettled = true; },
                error => {
                  state.error = 'serverImport: ' + format(error);
                  append('error', [state.error]);
                },
              );
            })();
            """.trimIndent()
        val stopScript =
            """
            (() => {
              const state = globalThis.__stmCore;
              if (!state || !Array.isArray(state.servers) || state.servers.length === 0) {
                if (state) state.closed = true;
                return;
              }
              state.setMemoryPhase?.('pre_stop');
              let pending = 0;
              const completed = () => {
                pending -= 1;
                if (pending <= 0) state.closed = true;
              };
              for (const server of state.servers) {
                if (server && server.listening) {
                  pending += 1;
                  server.close(completed);
                }
              }
              if (pending === 0) state.closed = true;
            })();
            """.trimIndent()
        val cleanupScript =
            """
            (() => {
              const state = globalThis.__stmCore;
              if (!state) return;
              const http = require('node:http');
              state.setMemoryPhase?.('runtime_cleanup');
              for (const timer of state.memoryTimers || []) clearTimeout(timer);
              state.memoryTimers = [];
              if (state.originalCreateServer) http.createServer = state.originalCreateServer;
              if (state.onUncaughtException) {
                process.removeListener('uncaughtException', state.onUncaughtException);
              }
              if (state.onUnhandledRejection) {
                process.removeListener('unhandledRejection', state.onUnhandledRejection);
              }
              for (const level of ['log', 'info', 'warn', 'error']) {
                if (state.originalConsole[level]) console[level] = state.originalConsole[level];
              }
              for (const name of ['NODE_ENV', 'TMPDIR', 'TMP', 'TEMP']) {
                const value = state.originalEnv[name];
                if (value === undefined) delete process.env[name]; else process.env[name] = value;
              }
              if (state.originalCwd) process.chdir(state.originalCwd);
            })();
            """.trimIndent()
        return FeatherEngineLaunchSpec(
            consoleArguments = arrayOf(
                serverFile.absolutePath,
                "--dataRoot",
                dataRoot.absolutePath,
                "--configPath",
                configFile.absolutePath,
                "--port",
                port.toString(),
                "--listen",
                "false",
                "--browserLaunchEnabled",
                "false",
                "--enableIPv4",
                "true",
                "--enableIPv6",
                "false",
            ),
            bootstrapScript = bootstrap,
            startupErrorExpression = "globalThis.__stmCore?.error || ''",
            readinessPortExpression = "globalThis.__stmCore?.port || 0",
            stopScript = stopScript,
            closedExpression = "Boolean(globalThis.__stmCore?.closed)",
            diagnosticsExpression =
                """
                (() => {
                  const state = globalThis.__stmCore;
                  return 'requests=' + String(state?.requestCount || 0) +
                    ', last=' + String(state?.lastRequest || '') +
                    ', importSettled=' + String(state?.importSettled || false) +
                    ', webpackConfigAdapter=' +
                      String(state?.webpackConfigAdapterApplied || false) +
                    ', prebuiltWebpackAdapter=' +
                      String(state?.prebuiltWebpackAdapterApplied || false) +
                    ', error=' + String(state?.error || '') +
                    ', logs=' + String((state?.logs || []).slice(-8).join(' || '));
                })();
                """.trimIndent(),
            cleanupScript = cleanupScript,
            readinessProbe = Gate3aVersionProbe::execute,
        )
    }

    private fun requireProgramTree(programRoot: File) {
        val expectedRoot = File(
            StmCorePaths.cacheRoot(context),
            "experiments/gate3a/$ST_COMMIT",
        ).canonicalFile
        val canonicalProgram = programRoot.canonicalFile
        check(canonicalProgram.parentFile == expectedRoot) {
            "Gate 3A program escaped its fixed cache root"
        }
        check(canonicalProgram.isDirectory) {
            "Gate 3A program tree was not pushed to $canonicalProgram"
        }
        check(!File(canonicalProgram, ".git").exists()) {
            "Gate 3A program tree must not contain Git metadata"
        }
        listOf("server.js", "package.json", "package-lock.json", "LICENSE", "node_modules")
            .forEach { name -> check(File(canonicalProgram, name).exists()) { "Missing $name" } }
        val packageJson = JSONObject(File(canonicalProgram, "package.json").readText(Charsets.UTF_8))
        check(packageJson.getString("version") == ST_VERSION) {
            "Gate 3A program version is not $ST_VERSION"
        }
        check(sha256(File(canonicalProgram, "package-lock.json")) == EXPECTED_PACKAGE_LOCK_SHA256) {
            "Gate 3A package-lock identity did not match the fixed commit"
        }
    }

    private fun requirePrebuiltBundleRoot(): File {
        val expectedParent = File(
            StmCorePaths.cacheRoot(context),
            "experiments/gate3b/prebuilt-bundles/$ST_COMMIT",
        ).canonicalFile
        val bundleRoot = File(expectedParent, EXPECTED_LIB_JS_SHA256).absoluteFile
        check(!Files.isSymbolicLink(bundleRoot.toPath())) {
            "Gate 3A prebuilt bundle root must not be a symbolic link"
        }
        val canonicalRoot = bundleRoot.canonicalFile
        check(canonicalRoot.parentFile == expectedParent && canonicalRoot.name == EXPECTED_LIB_JS_SHA256) {
            "Gate 3A prebuilt bundle escaped its commit-keyed cache root"
        }
        check(Files.isDirectory(canonicalRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Gate 3A prebuilt bundle was not supplied at $canonicalRoot"
        }
        val names = Files.list(canonicalRoot.toPath()).use { stream ->
            stream.iterator().asSequence()
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }
        check(names == listOf("lib.js", "lib.js.LICENSE.txt")) {
            "Gate 3A prebuilt bundle contains unexpected entries: $names"
        }
        val libJs = File(canonicalRoot, "lib.js")
        val license = File(canonicalRoot, "lib.js.LICENSE.txt")
        listOf(libJs, license).forEach { file ->
            check(!Files.isSymbolicLink(file.toPath())) {
                "Gate 3A prebuilt bundle file must not be a symbolic link: ${file.name}"
            }
            check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Gate 3A prebuilt bundle file is not regular: ${file.name}"
            }
        }
        check(libJs.length() == EXPECTED_LIB_JS_BYTES && sha256(libJs) == EXPECTED_LIB_JS_SHA256) {
            "Gate 3A prebuilt lib.js did not match the fixed manifest"
        }
        check(
            license.length() == EXPECTED_LIB_LICENSE_BYTES &&
                sha256(license) == EXPECTED_LIB_LICENSE_SHA256
        ) {
            "Gate 3A prebuilt lib.js.LICENSE.txt did not match the fixed manifest"
        }
        return canonicalRoot
    }

    private fun requireFixedProgramFingerprint(fingerprint: TreeFingerprint) {
        check(
            fingerprint.sha256 == EXPECTED_PROGRAM_SHA256 &&
                fingerprint.files == EXPECTED_PROGRAM_FILES &&
                fingerprint.directories == EXPECTED_PROGRAM_DIRECTORIES &&
                fingerprint.symlinks == EXPECTED_PROGRAM_SYMLINKS &&
                fingerprint.bytes == EXPECTED_PROGRAM_BYTES
        ) {
            "Gate 3A program tree did not match the fixed commit manifest: $fingerprint"
        }
    }

    private fun fingerprintOptionalControlFile(file: File): String {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return "absent"
        check(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Unsafe Gate 3A control file: $file"
        }
        return "${Files.size(path)}:${sha256(file)}"
    }

    private fun prepareConfig(programRoot: File, configFile: File) {
        val defaultConfig = File(programRoot, "default/config.yaml").readText(Charsets.UTF_8)
        val expected = "git:\n  backend: auto"
        check(defaultConfig.contains(expected)) { "Fixed ST config has an unexpected git backend shape" }
        configFile.writeText(defaultConfig.replace(expected, "git:\n  backend: builtin"), Charsets.UTF_8)
    }

    private fun reserveLoopbackPort(): Int = ServerSocket().use { socket ->
        socket.reuseAddress = false
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        socket.localPort
    }

    private fun awaitPortReleased(port: Int): Boolean {
        val deadline = SystemClock.elapsedRealtime() + PORT_RELEASE_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isPortOpen(port)) return true
            Thread.sleep(PORT_POLL_MILLIS)
        }
        return !isPortOpen(port)
    }

    private fun awaitPerformanceWindow(seconds: Long) {
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(seconds)
        while (true) {
            check(!cancellationRequested) { "Gate 3A performance run was cancelled" }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return
            Thread.sleep(minOf(remaining, PERFORMANCE_CANCELLATION_POLL_MILLIS))
        }
    }

    private fun isPortOpen(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), PORT_CONNECT_TIMEOUT_MILLIS)
        }
    }.isSuccess

    private fun fingerprintTree(root: Path): TreeFingerprint {
        check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Missing tree: $root" }
        val normalizedRoot = root.toAbsolutePath().normalize()
        val entries = Files.walk(normalizedRoot).use { stream ->
            stream.iterator().asSequence()
                .filter { it != normalizedRoot }
                .sortedBy { normalizedRoot.relativize(it).toString() }
                .toList()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var files = 0L
        var directories = 0L
        var symlinks = 0L
        var bytes = 0L
        val buffer = ByteArray(64 * 1024)
        entries.forEach { entry ->
            val relative = normalizedRoot.relativize(entry).toString()
            digestField(digest, relative)
            when {
                Files.isSymbolicLink(entry) -> {
                    symlinks += 1
                    val target = Files.readSymbolicLink(entry)
                    check(!target.isAbsolute) { "Absolute symlink in Gate 3A tree: $relative" }
                    val resolved = requireNotNull(entry.parent).resolve(target).normalize()
                    check(resolved.startsWith(normalizedRoot)) {
                        "Escaping symlink in Gate 3A tree: $relative -> $target"
                    }
                    check(Files.exists(resolved)) { "Broken symlink in Gate 3A tree: $relative" }
                    digestField(digest, "L")
                    digestField(digest, target.toString())
                }

                Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) -> {
                    directories += 1
                    digestField(digest, "D")
                }

                Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) -> {
                    files += 1
                    val size = Files.size(entry)
                    bytes += size
                    digestField(digest, "F")
                    digestField(digest, size.toString())
                    Files.newInputStream(entry).use { input ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) digest.update(buffer, 0, count)
                        }
                    }
                }

                else -> error("Unsupported filesystem entry in Gate 3A tree: $relative")
            }
            val permissions = runCatching {
                Files.getPosixFilePermissions(entry, LinkOption.NOFOLLOW_LINKS)
                    .map(Enum<*>::name)
                    .sorted()
                    .joinToString(",")
            }.getOrDefault("")
            digestField(digest, permissions)
        }
        return TreeFingerprint(
            sha256 = digest.digest().toHex(),
            files = files,
            directories = directories,
            symlinks = symlinks,
            bytes = bytes,
        )
    }

    private fun summarizeTree(root: Path): TreeSummary {
        var files = 0L
        var directories = 0L
        var symlinks = 0L
        var bytes = 0L
        Files.walk(root).use { stream ->
            stream.forEach { entry ->
                if (entry == root) return@forEach
                when {
                    Files.isSymbolicLink(entry) -> symlinks += 1
                    Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) -> directories += 1
                    Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) -> {
                        files += 1
                        bytes += Files.size(entry)
                    }
                }
            }
        }
        return TreeSummary(files, directories, symlinks, bytes)
    }

    private fun digestField(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun jsString(value: String): String = buildString(value.length + 2) {
        append('\'')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(character)
            }
        }
        append('\'')
    }

    private fun readProcStatusKilobytes(label: String): Long =
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("$label:") }
                ?.substringAfter(':')
                ?.trim()
                ?.substringBefore(' ')
                ?.toLongOrNull()
        } ?: -1L

    private companion object {
        const val ST_REPOSITORY = "https://github.com/SillyTavern/SillyTavern"
        const val ST_COMMIT = "8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8"
        const val ST_VERSION = "1.18.0"
        const val EXPECTED_PACKAGE_LOCK_SHA256 =
            "7484f87e7dc6e99044ad532b80111c3e93463aaf1d5dbe377b3a4486bfe65f6f"
        const val EXPECTED_WEBPACK_CONFIG_SHA256 =
            "ed61aed24c3779b13d95b46a53a190463fc072bdca746dff7170cb4db02c36fc"
        const val EXPECTED_WEBPACK_SERVE_SHA256 =
            "23ec22ec48760ea1cc87aa288154a9fdafd51f7c8b3b8b8529e7b65b733f63cf"
        const val EXPECTED_LIB_JS_SHA256 =
            "2d5fb1eedcbefe7062421e8ca54b90a23312f64df8d480c16538714c5157e0bf"
        const val EXPECTED_LIB_LICENSE_SHA256 =
            "7d9c6fd5c043071752d853a02c63fbb9a7828157265ff1a90b75edaf6f5a9fc0"
        const val EXPECTED_LIB_JS_BYTES = 1_947_206L
        const val EXPECTED_LIB_LICENSE_BYTES = 1_283L
        const val EXPECTED_PROGRAM_SHA256 =
            "b7e134b2911555173378c00da0196ebfe382f37c67e08a44661057b2c19c40a0"
        const val EXPECTED_PROGRAM_FILES = 20_954L
        const val EXPECTED_PROGRAM_DIRECTORIES = 2_647L
        const val EXPECTED_PROGRAM_SYMLINKS = 34L
        const val EXPECTED_PROGRAM_BYTES = 355_398_217L
        const val LATEST_RUN_FILE = "latest-run.txt"
        const val START_TIMEOUT_SECONDS = 240L
        const val STOP_TIMEOUT_SECONDS = 15L
        const val HTTP_TIMEOUT_MILLIS = 5_000
        const val PORT_RELEASE_TIMEOUT_MILLIS = 5_000L
        const val PORT_CONNECT_TIMEOUT_MILLIS = 200
        const val PORT_POLL_MILLIS = 50L
        const val ENGINE_DESTROY_TIMEOUT_SECONDS = 12L
        const val MAX_NODE_LOG_BYTES = 2_000_000
        const val LOG_TAIL_CHARS = 4_000
        const val WEBPACK_NO_COMPRESSION_MARKER = "STM_GATE3A_WEBPACK_COMPRESSION_FALSE_APPLIED"
        const val PREBUILT_WEBPACK_ADAPTER_MARKER = "STM_GATE3A_PREBUILT_WEBPACK_ADAPTER_APPLIED"
        const val PERFORMANCE_IDLE_BASELINE_SECONDS = 30L
        const val PERFORMANCE_READY_SHORT_SECONDS = 30L
        const val PERFORMANCE_READY_STEADY_SECONDS = 300L
        const val PERFORMANCE_POST_STOP_SHORT_SECONDS = 30L
        const val PERFORMANCE_POST_STOP_STEADY_SECONDS = 120L
        const val PERFORMANCE_CANCELLATION_POLL_MILLIS = 1_000L
    }
}

private object Gate3aVersionProbe {
    fun execute(baseUrl: String): LoopbackProbeResult {
        val response = when (val result = LoopbackHealthProbe.capture(baseUrl, "/version")) {
            is LoopbackProbeResult.Failed -> return result
            is LoopbackProbeResult.Healthy -> result.response
        }
        if (response.statusCode != 200) {
            return LoopbackProbeResult.Failed(
                "SillyTavern /version returned HTTP ${response.statusCode}",
                response,
            )
        }
        val version = runCatching {
            JSONObject(response.bodyUtf8()).getString("pkgVersion")
        }.getOrElse { error ->
            return LoopbackProbeResult.Failed(
                "SillyTavern /version returned invalid JSON: ${error.message}",
                response,
            )
        }
        return if (version == "1.18.0") {
            LoopbackProbeResult.Healthy(response)
        } else {
            LoopbackProbeResult.Failed(
                "SillyTavern /version returned unexpected pkgVersion=$version",
                response,
            )
        }
    }
}

private class Gate3aEngineCallback : FeatherEngine.Callback {
    private val sessions = ConcurrentHashMap<String, SessionSignal>()
    private val cancellationLock = Any()
    private var cancellationDetail: String? = null

    fun register(sessionId: String): SessionSignal = SessionSignal().also { signal ->
        synchronized(cancellationLock) {
            check(sessions.putIfAbsent(sessionId, signal) == null) {
                "Duplicate Gate 3A session $sessionId"
            }
            cancellationDetail?.let { detail -> signal.cancel(detail) }
        }
    }

    fun cancelAll(detail: String) {
        synchronized(cancellationLock) {
            cancellationDetail = detail
            sessions.values.forEach { signal -> signal.cancel(detail) }
        }
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

private class SessionSignal {
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

private data class SessionEvidence(
    val nodeVersion: String,
    val startMillis: Long,
    val stopMillis: Long,
    val versionStatus: String,
    val homeStatus: String,
    val urlConnectionEvidence: String,
    val libJsEvidence: String,
    val portReleased: Boolean,
    val terminationUsed: Boolean,
)

private data class TreeFingerprint(
    val sha256: String,
    val files: Long,
    val directories: Long,
    val symlinks: Long,
    val bytes: Long,
)

private data class TreeSummary(
    val files: Long,
    val directories: Long,
    val symlinks: Long,
    val bytes: Long,
)

private class ProcessMemorySampler : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "STM-Gate3A-Memory").apply { isDaemon = true }
    }
    val peakPssKb = AtomicLong()
    val peakNativeHeapBytes = AtomicLong()

    fun start() {
        executor.scheduleAtFixedRate(
            {
                peakPssKb.accumulateAndGet(Debug.getPss(), ::maxOf)
                peakNativeHeapBytes.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
            },
            0,
            100,
            TimeUnit.MILLISECONDS,
        )
    }

    override fun close() {
        executor.shutdownNow()
    }
}
