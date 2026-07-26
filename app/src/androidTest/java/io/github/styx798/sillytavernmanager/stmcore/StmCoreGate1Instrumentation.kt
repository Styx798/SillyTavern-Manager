package io.github.styx798.sillytavernmanager.stmcore

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import io.github.styx798.sillytavernmanager.DEBUG_EXTRA_START_IN_VERSIONS
import io.github.styx798.sillytavernmanager.MainActivity
import io.github.styx798.sillytavernmanager.R
import io.github.styx798.sillytavernmanager.DEBUG_EXTRA_START_IN_TAVERN
import io.github.styx798.sillytavernmanager.app.StmApplication
import io.github.styx798.sillytavernmanager.core.downloads.DownloadedStArchive
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveIdentity
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveIdentityClassification
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveIntegrity
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveIntegrityClassification
import io.github.styx798.sillytavernmanager.core.downloads.StArchiveTrust
import io.github.styx798.sillytavernmanager.core.downloads.StDownloadChannel
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreCommandResult
import io.github.styx798.sillytavernmanager.data.stmcore.AndroidStmCoreController
import io.github.styx798.sillytavernmanager.ui.screens.TavernDestroyedRendererTag
import io.github.styx798.sillytavernmanager.stmcore.testing.StmCoreExperiment
import io.github.styx798.sillytavernmanager.stmcore.testing.StmCoreExperimentClient
import io.github.styx798.sillytavernmanager.stmcore.testing.StmCoreExperimentListener
import io.github.styx798.sillytavernmanager.stmcore.testing.StmCoreExperimentResult
import io.github.styx798.sillytavernmanager.stmcore.testing.saturatingTimeoutBudgetMillis
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class StmCoreGate1Instrumentation : Instrumentation() {
    private var fatalExperimentName: String? = null
    private var gate: String = "1"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        fatalExperimentName = arguments?.getString("fatalExperiment")
        gate = arguments?.getString("gate") ?: "1"
        start()
    }

    override fun onStart() {
        Thread(
            {
                val result = Bundle()
                try {
                    runBlocking {
                        val fatalExperiment = fatalExperimentName?.let { name ->
                            StmCoreExperiment.valueOf(name)
                        }
                        if (fatalExperiment == null) {
                            when (gate) {
                                "1" -> runGate1(result)
                                "2" -> StmCoreGate2Scenario(this@StmCoreGate1Instrumentation)
                                    .run(result)

                                "3a" -> runGate3a(result)
                                "3a-perf" -> runGate3aPerformance(result)
                                "3a-perf-no-compression" ->
                                    runGate3aPerformance(result, disableCompression = true)

                                "3a-perf-prebuilt" ->
                                    runGate3aPerformance(result, usePrebuiltBundle = true)

                                "3b-npm-cli" ->
                                    runGate3bDependencyExperiment(
                                        result,
                                        StmCoreExperiment.GATE3B_NPM_CLI,
                                    )

                                "3b-npm-cli-runnable" ->
                                    runGate3bRunnableExperiment(
                                        result,
                                        StmCoreExperiment.GATE3B_NPM_CLI_RUNNABLE,
                                    )

                                "3b-npm-cli-local-bundle-runnable" ->
                                    runGate3bRunnableExperiment(
                                        result,
                                        StmCoreExperiment.GATE3B_NPM_CLI_LOCAL_BUNDLE_RUNNABLE,
                                    )

                                "3b-npm-cli-bounded-interruption" ->
                                    runGate3bExpectedInterruption(
                                        result,
                                        StmCoreExperiment.GATE3B_NPM_CLI_BOUNDED_INTERRUPTION,
                                    )

                                "3b-npm-cli-cancel" ->
                                    runGate3bExpectedCancellation(
                                        result,
                                        StmCoreExperiment.GATE3B_NPM_CLI_CANCEL,
                                    )

                                "3b-arborist" ->
                                    runGate3bDependencyExperiment(
                                        result,
                                        StmCoreExperiment.GATE3B_ARBORIST,
                                    )

                                "3b-arborist-runnable" ->
                                    runGate3bRunnableExperiment(
                                        result,
                                        StmCoreExperiment.GATE3B_ARBORIST_RUNNABLE,
                                    )

                                "3b-arborist-bounded-interruption" ->
                                    runGate3bExpectedInterruption(
                                        result,
                                        StmCoreExperiment.GATE3B_ARBORIST_BOUNDED_INTERRUPTION,
                                    )

                                "3b-arborist-cancel" ->
                                    runGate3bExpectedCancellation(
                                        result,
                                        StmCoreExperiment.GATE3B_ARBORIST_CANCEL,
                                    )

                                "3b-signed-prebuilt" ->
                                    runGate3bDependencyExperiment(
                                        result,
                                        StmCoreExperiment.GATE3B_SIGNED_PREBUILT,
                                    )

                                "3b-tree-diff" -> runGate3bTreeDiffExperiment(result)

                                "3b-ready-slot" ->
                                    runGate3bReadySlotExperiment(result)

                                "3b-ready-slot-cold" ->
                                    runGate3bColdSlotExperiment(result)

                                "3b-fault-matrix" ->
                                    runGate3bFaultMatrix(result)

                                "3b-protocol" ->
                                    runGate3bProtocolProbe(result)

                                "4-st-lifecycle" ->
                                    runGate4SillyTavernLifecycle(result)

                                "4-renderer-recovery" ->
                                    runGate4WebViewRendererRecovery(result)

                                "4-file-chooser" ->
                                    runGate4CharacterFileChooser(result)

                                "4-blob-saf" ->
                                    runGate4BlobSafExport(result)

                                "4-core-crash-recovery" ->
                                    runGate4CoreCrashRecovery(result)

                                "4-slot-removal-data-isolation" ->
                                    runGate4SlotRemovalDataIsolation(result)

                                "4-first-install" ->
                                    runGate4FirstInstall(result)

                                "all" -> {
                                    runGate1(result)
                                    StmCoreGate2Scenario(this@StmCoreGate1Instrumentation)
                                        .run(result)
                                }

                                else -> error("Unsupported STM Core gate: $gate")
                            }
                        } else {
                            runFatalExperimentOnly(result, fatalExperiment)
                        }
                    }
                    finish(Activity.RESULT_OK, result)
                } catch (error: Throwable) {
                    result.putString(
                        "failure",
                        "${error.javaClass.simpleName}: ${error.message}\n${error.stackTraceToString()}",
                    )
                    finish(Activity.RESULT_FIRST_USER, result)
                }
            },
            "STM-Core-Gate1-Instrumentation",
        ).start()
    }

    private suspend fun runGate1(result: Bundle) {
        val uiProcessId = Process.myPid()
        val application = targetContext.applicationContext as StmApplication
        val controller = onMain {
            application.container.stmCoreController as AndroidStmCoreController
        }
        awaitSettledCore(controller)

        val first = startAndVerify(controller, result, "first")
        stopAndVerify(controller, first.port)
        val stoppedAfterFirst = awaitState(controller) { it.runState == StmCoreRunState.STOPPED }
        check(stoppedAfterFirst.processId == first.processId) {
            "Normal stop unexpectedly replaced the STM Core process"
        }

        val second = startAndVerify(controller, result, "second")
        check(second.processId == first.processId) {
            "A clean stop did not preserve the Core process for session reconstruction"
        }
        check(second.sessionId != first.sessionId) {
            "The second start reused a closed Feather Engine session"
        }
        stopAndVerify(controller, second.port)
        result.putString("session_rebuild", "same_core_process_new_session")

        val semantics = runExperiment(StmCoreExperiment.NODE_SEMANTICS)
        check(semantics is ExperimentOutcome.Completed) {
            "Node semantics experiment killed the Core process"
        }
        semantics.result.values.forEach { (key, value) ->
            result.putString("semantics_$key", value)
        }
        verifyNodeSemantics(semantics.result)

        val termination = runExperiment(StmCoreExperiment.TERMINATE_EXECUTION)
        check(termination is ExperimentOutcome.Completed) {
            "terminateExecution killed the Core process before returning evidence"
        }
        check(termination.result.values["result"] == "execution_interrupted") {
            "terminateExecution did not interrupt the infinite script: ${termination.result.values}"
        }
        check(termination.result.values["different_threads"] == "true") {
            "The terminate watchdog ran on the Engine thread"
        }
        termination.result.values.forEach { (key, value) ->
            result.putString("terminate_$key", value)
        }

        val beforeCrash = startAndVerify(controller, result, "crash_recovery")
        val killedRevision = controller.state.value.revision
        Process.killProcess(beforeCrash.processId)
        val crashed = awaitState(controller) { state ->
            state.runState == StmCoreRunState.CRASHED && state.localBaseUrl == null
        }
        check(Process.myPid() == uiProcessId) {
            "The STM UI process did not survive Core process termination"
        }
        val recovered = awaitCoreProcessReplacement(controller, beforeCrash.processId)
        check(recovered.revision > killedRevision) {
            "Recovered Core revision did not advance beyond the killed process checkpoint"
        }
        check(recovered.runState == StmCoreRunState.CRASHED) {
            "Interrupted RUNNING checkpoint was not recovered as CRASHED: $recovered"
        }
        result.putString("process_recovery_error", recovered.error?.code)
        result.putLong("process_recovery_revision", recovered.revision)
        result.putString("observer_crash_error", crashed.error?.code)

        val recovery = startAndVerify(controller, result, "post_crash")
        stopAndVerify(controller, recovery.port)
        result.putInt("surviving_ui_process", uiProcessId)
    }

    private suspend fun runGate3a(result: Bundle) {
        val application = targetContext.applicationContext as StmApplication
        val controller = onMain {
            application.container.stmCoreController as AndroidStmCoreController
        }
        val settled = awaitSettledCore(controller)
        check(settled.runState != StmCoreRunState.RUNNING) {
            "Gate 3A cannot run while the formal STM Core workload is active"
        }
        val outcome = runExperiment(
            StmCoreExperiment.GATE3A_REAL_ST,
            GATE3A_EXPERIMENT_TIMEOUT_MILLIS,
        )
        check(outcome is ExperimentOutcome.Completed) {
            "The real SillyTavern experiment terminated the Core process"
        }
        outcome.result.values.forEach { (key, value) ->
            result.putString("gate3a_$key", value)
        }
        check(outcome.result.values["result"] == "passed") {
            "Gate 3A failed: ${outcome.result.values}"
        }
    }

    private suspend fun runGate3aPerformance(
        result: Bundle,
        disableCompression: Boolean = false,
        usePrebuiltBundle: Boolean = false,
    ) {
        check(!(disableCompression && usePrebuiltBundle)) {
            "Gate 3A performance profiles are mutually exclusive"
        }
        val application = targetContext.applicationContext as StmApplication
        val controller = onMain {
            application.container.stmCoreController as AndroidStmCoreController
        }
        val settled = awaitSettledCore(controller)
        check(settled.runState != StmCoreRunState.RUNNING) {
            "Gate 3A performance measurement cannot run while STM Core is active"
        }
        val experiment = when {
            usePrebuiltBundle ->
                StmCoreExperiment.GATE3A_REAL_ST_PERFORMANCE_PREBUILT_BUNDLE

            disableCompression ->
                StmCoreExperiment.GATE3A_REAL_ST_PERFORMANCE_NO_COMPRESSION

            else -> StmCoreExperiment.GATE3A_REAL_ST_PERFORMANCE
        }
        val outcome = runExperiment(
            experiment,
            GATE3A_PERFORMANCE_TIMEOUT_MILLIS,
        )
        check(outcome is ExperimentOutcome.Completed) {
            "The real SillyTavern performance experiment terminated the Core process"
        }
        outcome.result.values.forEach { (key, value) ->
            result.putString("gate3a_perf_$key", value)
        }
        check(outcome.result.values["result"] == "passed") {
            "Gate 3A performance measurement failed: ${outcome.result.values}"
        }
    }

    private suspend fun runGate3bDependencyExperiment(
        result: Bundle,
        experiment: StmCoreExperiment,
    ) {
        requireGate3bCoreStopped()
        val outcome = runExperiment(experiment, GATE3B_EXPERIMENT_TIMEOUT_MILLIS)
        check(outcome is ExperimentOutcome.Completed) {
            "Stage 3B dependency experiment terminated the Core process"
        }
        outcome.result.values.forEach { (key, value) ->
            result.putString("gate3b_$key", value)
        }
        check(
            outcome.result.values["result"] == "passed" &&
                outcome.result.values["committed_slots_unchanged"] == "true" &&
                outcome.result.values["active_slot_pointer_unchanged"] == "true"
        ) {
            "Stage 3B dependency experiment failed: ${outcome.result.values}"
        }
        if (
            experiment == StmCoreExperiment.GATE3B_NPM_CLI ||
            experiment == StmCoreExperiment.GATE3B_ARBORIST
        ) {
            check(
                outcome.result.values["process_state_restoration"] == "restored" &&
                    outcome.result.values["process_environment_restored"] == "true" &&
                    outcome.result.values["process_cwd_restored"] == "true" &&
                    outcome.result.values["process_surface_restored"] == "true" &&
                    outcome.result.values["process_exec_path_restored"] == "true"
            ) {
                "Stage 3B dependency experiment leaked Core process state: " +
                    outcome.result.values
            }
            if (experiment == StmCoreExperiment.GATE3B_NPM_CLI) {
                check(
                    outcome.result.values["npm_instance_captured"] == "true" &&
                        outcome.result.values["npm_unload_attempted"] == "true" &&
                        outcome.result.values["npm_unload_succeeded"] == "true" &&
                        outcome.result.values["npm_unload_calls"] == "1" &&
                        outcome.result.values["npm_unref_promises_settled"] == "true"
                ) {
                    "Stage 3B npm dependency lifecycle was incomplete: ${outcome.result.values}"
                }
            }
        }
    }

    private suspend fun runGate3bProtocolProbe(result: Bundle) {
        requireGate3bCoreStopped()
        val first = runExperiment(StmCoreExperiment.TEARDOWN_PROTOCOL_PROBE)
        val second = runExperiment(StmCoreExperiment.TEARDOWN_PROTOCOL_PROBE)
        requireGate3bCoreStopped()

        check(first is ExperimentOutcome.Completed && second is ExperimentOutcome.Completed) {
            "Stage 3B teardown protocol probe disconnected the Core process"
        }
        check(
            !first.result.teardownComplete &&
                !second.result.teardownComplete &&
                first.result.values["result"] == "passed" &&
                second.result.values["result"] == "passed" &&
                first.result.requestId.isNotBlank() &&
                second.result.requestId.isNotBlank() &&
                first.result.requestId != second.result.requestId
        ) {
            "Stage 3B teardown protocol probe did not complete two distinct false-result " +
                "handshakes: first=${first.result}, second=${second.result}"
        }
        result.putString("gate3b_protocol_result", "passed")
        result.putString("gate3b_protocol_first_request_id", first.result.requestId)
        result.putString("gate3b_protocol_second_request_id", second.result.requestId)
        result.putString(
            "gate3b_protocol_result_teardown_complete",
            "${first.result.teardownComplete},${second.result.teardownComplete}",
        )
    }

    private suspend fun runGate3bRunnableExperiment(
        result: Bundle,
        experiment: StmCoreExperiment,
    ) {
        require(
            experiment == StmCoreExperiment.GATE3B_NPM_CLI_RUNNABLE ||
                experiment == StmCoreExperiment.GATE3B_NPM_CLI_LOCAL_BUNDLE_RUNNABLE ||
                experiment == StmCoreExperiment.GATE3B_ARBORIST_RUNNABLE,
        ) {
            "Unsupported Stage 3B runnable experiment: $experiment"
        }
        requireGate3bRunnableCoreStopped()
        val timeoutMillis = when (experiment) {
            StmCoreExperiment.GATE3B_NPM_CLI_LOCAL_BUNDLE_RUNNABLE ->
                GATE3B_LOCAL_BUNDLE_RUNNABLE_EXPERIMENT_TIMEOUT_MILLIS

            else -> GATE3B_RUNNABLE_EXPERIMENT_TIMEOUT_MILLIS
        }
        val outcome = runExperiment(experiment, timeoutMillis)
        check(outcome is ExperimentOutcome.Completed) {
            "Stage 3B runnable experiment terminated the Core process"
        }
        requireGate3bRunnableCoreStopped()
        result.putString("gate3b_runnable_core_boundary", "stopped_idle_before_after")
        check(outcome.result.experiment == experiment && outcome.result.requestId.isNotBlank()) {
            "Stage 3B runnable result was not owned by the requested experiment"
        }
        outcome.result.values.forEach { (key, value) ->
            result.putString("gate3b_runnable_$key", value)
        }
        val values = outcome.result.values
        val expectedCandidate = if (
            experiment == StmCoreExperiment.GATE3B_NPM_CLI_RUNNABLE ||
            experiment == StmCoreExperiment.GATE3B_NPM_CLI_LOCAL_BUNDLE_RUNNABLE
        ) {
            "NPM_CLI"
        } else {
            "ARBORIST"
        }
        check(
            values["result"] == "passed" &&
                values["candidate"] == expectedCandidate &&
                values["runnable_requested"] == "true" &&
                values["runnable_result"] == "passed" &&
                values["runnable_candidate"] == expectedCandidate &&
                values["runnable_server_ready"] == "true" &&
                values["runnable_engine_start_attempted"] == "true" &&
                values["runnable_engine_destroyed"] == "true" &&
                values["runnable_checked_port"] == values["runnable_port"] &&
                values["runnable_checked_ports"].orEmpty().isNotBlank() &&
                values["runnable_port"] in
                values["runnable_checked_ports"].orEmpty().split(',') &&
                values["runnable_port_released"] == "true" &&
                values["runnable_termination_used"] == "false" &&
                values["runnable_dependency_tree_unchanged"] == "true" &&
                values["runnable_program_tree_unchanged"] == "true" &&
                values["runnable_root_tree_unchanged"] == "true" &&
                values["runnable_process_cwd_restored"] == "true" &&
                values["runnable_webpack_cache_absent"] == "true" &&
                values["runnable_source_archive_sha256"] == GATE3B_SOURCE_ARCHIVE_SHA256 &&
                values["runnable_root_kind"] == "writable_temporary_root" &&
                values["runnable_observed_root_identity"] ==
                    "unchanged_before_after_runtime" &&
                values["runnable_slot_admission"] == "not_requested" &&
                values["runnable_candidate_dependency_tree_signed"] == "false" &&
                values["runnable_runtime_kit_dependency_tree_claim_used"] == "false" &&
                values["runnable_workspace_capability"] ==
                    "debug_only_not_a_committed_slot" &&
                values["runnable_meaning"] ==
                    "candidate_runnable_acceptance_only_not_gate_passed_not_ready" &&
                values["process_state_restoration"] == "restored" &&
                values["process_environment_restored"] == "true" &&
                values["process_cwd_restored"] == "true" &&
                values["process_surface_restored"] == "true" &&
                values["process_listener_names_restored"] == "true" &&
                values["process_listeners_restored"] == "true" &&
                values["process_exec_path_restored"] == "true" &&
                values["npm_promise_settled"] == "true" &&
                values["installer_runtime_closure"] == "closed" &&
                values["cleanup"] == "removed" &&
                values["committed_slots_unchanged"] == "true" &&
                values["active_slot_pointer_unchanged"] == "true"
        ) {
            "Stage 3B runnable acceptance failed: $values"
        }
        if (expectedCandidate == "NPM_CLI") {
            check(
                values["npm_instance_captured"] == "true" &&
                    values["npm_unload_attempted"] == "true" &&
                    values["npm_unload_succeeded"] == "true" &&
                    values["npm_unload_calls"] == "1" &&
                    values["npm_unref_promises_settled"] == "true" &&
                    values["installer_runtime_nonce_sha256"]
                        ?.matches(Regex("[0-9a-f]{64}")) == true
            ) {
                "Stage 3B npm lifecycle evidence was incomplete: $values"
            }
        }
        if (experiment == StmCoreExperiment.GATE3B_NPM_CLI_LOCAL_BUNDLE_RUNNABLE) {
            check(
                values["local_bundle_build_requested"] == "true" &&
                    values["runnable_local_bundle_build"] == "true" &&
                    values["runnable_local_build_same_installer_runtime"] == "false" &&
                    values["runnable_local_build_fresh_runtime"] == "true" &&
                    values["runnable_npm_runtime_closed_before_local_build"] == "true" &&
                    values["runnable_build_runtime_closure"] == "closed" &&
                    values["runnable_signed_prebuilt_bundle_used"] == "false" &&
                    values["runnable_bundle_origin"] == "device_local_upstream_build" &&
                    values["runnable_runtime_fixture"] ==
                    "device_local_upstream_bundle_plus_debug_adapter" &&
                    values["runnable_candidate_dependency_attachment"] ==
                    "atomic_move_after_npm_runtime_closed_before_fresh_local_build_runtime" &&
                    values["runnable_local_build_cache_removed"] == "true" &&
                    values["runnable_local_build_fixed_source_oracle_matched"] == "true" &&
                    values["runnable_local_build_process_cwd_restored"] == "true" &&
                    values["runnable_local_build_fresh_runtime_closed"] == "true" &&
                    values["runnable_local_build_installer_state_absent"] == "true" &&
                    values["runnable_local_build_distinct_from_installer_runtime"] == "true" &&
                    values["runnable_runtime_forbidden_module_invariant"] ==
                    "startup_and_shutdown_hard_gate" &&
                    values["runnable_local_build_compiler_run_calls"] == "1" &&
                    values["runnable_local_build_compiler_callback_calls"] == "1" &&
                    values["runnable_local_build_compiler_error_absent"] == "true" &&
                    values["runnable_local_build_compiler_stats_present"] == "true" &&
                    values["runnable_local_build_compiler_stats_has_errors"] == "false" &&
                    values["runnable_local_build_compiler_stats_error_count"] == "0" &&
                    values["runnable_local_build_compiler_lib_asset_present"] == "true" &&
                    values["runnable_local_build_compiler_close_calls"] == "1" &&
                    values["runnable_local_build_compiler_close_callback_calls"] == "1" &&
                    values["runnable_local_build_compiler_close_error_absent"] == "true" &&
                    values["runnable_local_build_compiler_close_same_instance"] == "true" &&
                    values["runnable_local_provenance_sha256"]
                        ?.matches(Regex("[0-9a-f]{64}")) == true &&
                    values["runnable_local_provenance_bytes"]
                        ?.toLongOrNull()
                        ?.let { it > 0 } == true &&
                    values["runnable_bundle_sha256"] == GATE4_BUNDLE_SHA256 &&
                    values["runnable_bundle_bytes"] == GATE4_BUNDLE_BYTES.toString() &&
                    values["runnable_bundle_license_sha256"] == GATE4_BUNDLE_LICENSE_SHA256 &&
                    values["runnable_bundle_license_bytes"] ==
                    GATE4_BUNDLE_LICENSE_BYTES.toString() &&
                    values["runnable_local_build_runtime_nonce_sha256"]
                        ?.matches(Regex("[0-9a-f]{64}")) == true &&
                    values["runnable_local_build_runtime_nonce_sha256"] !=
                    values["installer_runtime_nonce_sha256"]
            ) {
                "Stage 3B local bundle evidence was incomplete: $values"
            }
        } else {
            check(
                values["runnable_fixed_bundle_sha256"] == GATE4_BUNDLE_SHA256 &&
                    values["runnable_runtime_fixture"] ==
                    "common_fixed_signed_fixture_not_candidate_dependency" &&
                    values["runnable_candidate_dependency_attachment"] ==
                    "atomic_move_after_installer_runtime_closed"
            ) {
                "Stage 3B fixed-fixture evidence changed unexpectedly: $values"
            }
        }
    }

    private suspend fun runGate3bReadySlotExperiment(result: Bundle) {
        val outcome = runExperiment(
            StmCoreExperiment.GATE3B_READY_SLOT,
            GATE3B_READY_SLOT_TIMEOUT_MILLIS,
        )
        check(outcome is ExperimentOutcome.Completed) {
            "Stage 3B READY-slot experiment terminated the Core process"
        }
        outcome.result.values.forEach { (key, value) ->
            result.putString("gate3b_ready_$key", value)
        }
        check(outcome.result.values["result"] == "passed") {
            "Stage 3B READY-slot assembly failed: ${outcome.result.values}"
        }

        val application = targetContext.applicationContext as StmApplication
        val controller = onMain {
            application.container.stmCoreController as AndroidStmCoreController
        }
        val settled = awaitSettledCore(controller, GATE3B_READY_RECOVERY_TIMEOUT_MILLIS)
        val slotId = requireNotNull(outcome.result.values["slot_id"])
        val recovered = settled.slots.singleOrNull { slot -> slot.id == slotId }
        check(recovered?.state == StmCoreSlotState.READY) {
            "Core recovery did not publish the committed READY slot: ${settled.slots}"
        }
        check(recovered.commitSha == outcome.result.values["st_commit"]) {
            "Recovered READY slot lost its exact SillyTavern commit"
        }
        check(settled.activeSlot?.slotId != slotId) {
            "The debug READY slot was activated without an explicit activation command"
        }
        result.putString("gate3b_ready_recovery", "ready_visible_not_active")
        result.putLong("gate3b_ready_recovery_revision", settled.revision)
    }

    private suspend fun runGate3bExpectedInterruption(
        result: Bundle,
        experiment: StmCoreExperiment,
    ) {
        requireGate3bCoreStopped()
        val outcome = runExperiment(
            experiment,
            GATE3B_INTERRUPTION_EXPERIMENT_TIMEOUT_MILLIS,
        )
        check(outcome is ExperimentOutcome.Completed) {
            "Stage 3B bounded interruption terminated the Core process"
        }
        outcome.result.values.forEach { (key, value) ->
            result.putString("gate3b_interruption_$key", value)
        }
        check(
                outcome.result.values["result"] == "failed" &&
                outcome.result.values["timed_out"] == "true" &&
                outcome.result.values["process_state_restoration"] == "restored" &&
                outcome.result.values["process_environment_restored"] == "true" &&
                outcome.result.values["process_cwd_restored"] == "true" &&
                outcome.result.values["process_surface_restored"] == "true" &&
                outcome.result.values["process_exec_path_restored"] == "true" &&
                outcome.result.values["cleanup"] == "removed" &&
                outcome.result.values["committed_slots_unchanged"] == "true" &&
                outcome.result.values["active_slot_pointer_unchanged"] == "true"
        ) {
            "Stage 3B bounded interruption did not fail cleanly: ${outcome.result.values}"
        }
        if (experiment == StmCoreExperiment.GATE3B_NPM_CLI_BOUNDED_INTERRUPTION) {
            check(
                outcome.result.values["npm_instance_captured"] == "true" &&
                    outcome.result.values["npm_unload_attempted"] == "true" &&
                    outcome.result.values["npm_unload_succeeded"] == "true" &&
                    outcome.result.values["npm_unload_calls"] == "1"
            ) {
                "Stage 3B interrupted npm lifecycle was incomplete: ${outcome.result.values}"
            }
        }
    }

    private suspend fun runGate3bTreeDiffExperiment(result: Bundle) {
        val outcome = runExperiment(
            StmCoreExperiment.GATE3B_TREE_DIFF,
            EXPERIMENT_TIMEOUT_MILLIS,
        )
        check(outcome is ExperimentOutcome.Completed) {
            "Stage 3B tree comparison terminated the Core process"
        }
        outcome.result.values.forEach { (key, value) ->
            result.putString("gate3b_tree_$key", value)
        }
        check(
            outcome.result.values["result"] == "passed" &&
                outcome.result.values["meaning"] == "comparison_completed_not_gate_passed"
        ) {
            "Stage 3B tree comparison failed: ${outcome.result.values}"
        }
    }

    private suspend fun runGate3bExpectedCancellation(
        result: Bundle,
        experiment: StmCoreExperiment,
    ) {
        requireGate3bCoreStopped()
        val outcome = runExperiment(
            experiment,
            GATE3B_CANCELLATION_EXPERIMENT_TIMEOUT_MILLIS,
        )
        check(outcome is ExperimentOutcome.Completed) {
            "Stage 3B cancellation terminated the Core process"
        }
        outcome.result.values.forEach { (key, value) ->
            result.putString("gate3b_cancel_$key", value)
        }
        check(
            outcome.result.values["result"] == "failed" &&
                outcome.result.values["cancel_requested"] == "true" &&
                outcome.result.values["timed_out"] == "false" &&
                outcome.result.values["process_state_restoration"] == "restored" &&
                outcome.result.values["process_environment_restored"] == "true" &&
                outcome.result.values["process_cwd_restored"] == "true" &&
                outcome.result.values["process_surface_restored"] == "true" &&
                outcome.result.values["process_exec_path_restored"] == "true" &&
                outcome.result.values["cleanup"] == "removed" &&
                outcome.result.values["committed_slots_unchanged"] == "true" &&
                outcome.result.values["active_slot_pointer_unchanged"] == "true"
        ) {
            "Stage 3B cancellation did not fail cleanly: ${outcome.result.values}"
        }
        if (experiment == StmCoreExperiment.GATE3B_NPM_CLI_CANCEL) {
            check(
                outcome.result.values["npm_instance_captured"] == "true" &&
                    outcome.result.values["npm_unload_attempted"] == "true" &&
                    outcome.result.values["npm_unload_succeeded"] == "true" &&
                    outcome.result.values["npm_unload_calls"] == "1"
            ) {
                "Stage 3B cancelled npm lifecycle was incomplete: ${outcome.result.values}"
            }
        }
    }

    private suspend fun requireGate3bCoreStopped() {
        val application = targetContext.applicationContext as StmApplication
        val controller = onMain {
            application.container.stmCoreController as AndroidStmCoreController
        }
        val settled = awaitSettledCore(controller)
        check(settled.runState != StmCoreRunState.RUNNING) {
            "Stage 3B dependency experiment cannot run while STM Core is active"
        }
    }

    private suspend fun requireGate3bRunnableCoreStopped() {
        val application = targetContext.applicationContext as StmApplication
        val controller = onMain {
            application.container.stmCoreController as AndroidStmCoreController
        }
        val settled = awaitSettledCore(controller)
        check(
            settled.runState == StmCoreRunState.STOPPED &&
                settled.jobs.none { job -> job.state in ACTIVE_JOB_STATES },
        ) {
            "Stage 3B runnable acceptance requires a fully stopped idle Core: $settled"
        }
    }

    private suspend fun runGate3bColdSlotExperiment(result: Bundle) {
        val outcome = runExperiment(
            StmCoreExperiment.GATE3B_READY_SLOT_COLD,
            GATE3B_READY_SLOT_TIMEOUT_MILLIS,
        )
        check(outcome is ExperimentOutcome.Completed) {
            "Stage 3B cold READY-slot experiment terminated the Core process"
        }
        outcome.result.values.forEach { (key, value) ->
            result.putString("gate3b_cold_$key", value)
        }
        check(
            outcome.result.values["result"] == "passed" &&
                outcome.result.values["outcome"] == "ready_verified_removed"
        ) {
            "Stage 3B cold READY-slot assembly failed: ${outcome.result.values}"
        }
    }

    private suspend fun runGate3bFaultMatrix(result: Bundle) {
        val outcome = runExperiment(
            StmCoreExperiment.GATE3B_FAULT_MATRIX,
            EXPERIMENT_TIMEOUT_MILLIS,
        )
        check(outcome is ExperimentOutcome.Completed) {
            "Stage 3B fault matrix terminated the Core process"
        }
        outcome.result.values.forEach { (key, value) ->
            result.putString("gate3b_fault_$key", value)
        }
        check(
            outcome.result.values["result"] == "passed" &&
                outcome.result.values["faults_rejected"] == "7" &&
                outcome.result.values["extraction_cleanup"] == "removed" &&
                outcome.result.values["committed_slots"] == "unchanged" &&
                outcome.result.values["active_slot_pointer"] == "unchanged"
        ) {
            "Stage 3B fault matrix failed: ${outcome.result.values}"
        }
    }

    private suspend fun runGate4SillyTavernLifecycle(result: Bundle) {
        val application = targetContext.applicationContext as StmApplication
        val controller = onMain {
            application.container.stmCoreController as AndroidStmCoreController
        }
        val target = prepareGate4Target(controller)
        val active = requireNotNull(controller.state.value.activeSlot)
        result.putString("gate4_active_slot", active.slotId)
        result.putLong("gate4_active_revision", active.activeRevision)

        val first = startAndVerifySillyTavern(controller, target, "first", result)
        stopAndVerifySillyTavern(controller, first, target, result)
        val second = ServerSocket().use { preferredPortBlocker ->
            preferredPortBlocker.bind(
                InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"),
                    GATE4_PREFERRED_PORT,
                ),
            )
            startAndVerifySillyTavern(controller, target, "second", result)
        }
        verifyGate4PortChangeRecovery(first, second, result)
        stopAndVerifySillyTavern(controller, second, target, result)
        verifyFailedUpgradeRollback(controller, target, result)
        check(second.sessionId != first.sessionId) {
            "Gate 4 reused a closed Feather Engine session"
        }
        check(second.processId == first.processId) {
            "Gate 4 clean restart unexpectedly replaced the Core process"
        }
        check(first.port == GATE4_PREFERRED_PORT && second.port != first.port) {
            "Gate 4 did not exercise a controlled loopback origin change: " +
                "${first.port} -> ${second.port}"
        }

        val dataRoot = StmCorePaths.dataRoot(targetContext).absoluteFile
        val config = dataRoot.resolve("config.yaml")
        check(config.isFile && config.readText().contains("backend: builtin")) {
            "Gate 4 did not create the isolated builtin-git config"
        }
        check(!dataRoot.resolve("_webpack").exists()) {
            "Gate 4 prebuilt runtime unexpectedly created dataRoot/_webpack"
        }
        val slotRoot = StmCorePaths.slotsRoot(targetContext).resolve(target.id)
        val programRoot = slotRoot.resolve(requireNotNull(target.artifact?.archiveRoot))
        check(!programRoot.resolve("config.yaml").exists()) {
            "Gate 4 wrote config.yaml into the immutable program slot"
        }
        result.putString("gate4_data_root", dataRoot.absolutePath)
        result.putString("gate4_config_path", config.absolutePath)
        result.putString("gate4_program_root", programRoot.absolutePath)
        result.putString("gate4_result", "passed")
    }

    private suspend fun runGate4CoreCrashRecovery(result: Bundle) {
        val application = targetContext.applicationContext as StmApplication
        val controller = onMain {
            application.container.stmCoreController as AndroidStmCoreController
        }
        val target = prepareGate4Target(controller)
        val running = startAndVerifySillyTavern(controller, target, "core_crash", result)
        val uiProcessId = Process.myPid()
        val killedRevision = controller.state.value.revision
        val activeBefore = requireNotNull(controller.state.value.activeSlot)

        Process.killProcess(running.processId)
        val crashed = awaitState(controller, GATE4_STATE_TIMEOUT_MILLIS) { state ->
            state.runState == StmCoreRunState.CRASHED &&
                state.localBaseUrl == null
        }
        awaitPortReleased(running.port, GATE4_STATE_TIMEOUT_MILLIS)
        check(Process.myPid() == uiProcessId) {
            "The STM UI process did not survive the real ST Core process exit"
        }
        val recovered = awaitCoreProcessReplacement(controller, running.processId)
        check(recovered.revision > killedRevision &&
            recovered.runState == StmCoreRunState.CRASHED &&
            recovered.activeSlot == activeBefore
        ) {
            "The replacement Core did not recover the interrupted ST checkpoint: $recovered"
        }
        val recoveredTarget = recovered.slots.singleOrNull { slot ->
            slot.id == target.id &&
                slot.revision == target.revision &&
                slot.state == StmCoreSlotState.READY &&
                slot.manifestSha256 == target.manifestSha256
        }
        check(recoveredTarget != null) {
            "The replacement Core did not preserve the exact READY slot"
        }

        val restarted = startAndVerifySillyTavern(
            controller,
            recoveredTarget,
            "post_core_crash",
            result,
        )
        stopAndVerifySillyTavern(controller, restarted, recoveredTarget, result)
        result.putString(
            "gate4_core_crash_recovery",
            "ui_pid=$uiProcessId:core_pid=${running.processId}->${recovered.processId}:" +
                "port_${running.port}=released:checkpoint=${crashed.runState.name}:" +
                "restart_session=${restarted.sessionId}:ready_unchanged=true",
        )
    }

    private suspend fun runGate4SlotRemovalDataIsolation(result: Bundle) {
        val application = targetContext.applicationContext as StmApplication
        val controller = onMain {
            application.container.stmCoreController as AndroidStmCoreController
        }
        val target = prepareGate4Target(controller)
        val initialState = controller.state.value
        val activeBefore = requireNotNull(initialState.activeSlot)
        val readyBefore = requireNotNull(
            initialState.slots.singleOrNull { slot ->
                slot.id == target.id &&
                    slot.revision == target.revision &&
                    slot.state == StmCoreSlotState.READY
            },
        ) {
            "Gate 4 slot-removal test did not begin with the exact active READY slot"
        }
        val removableSlotId = GATE4_REMOVABLE_SLOT_ID
        check(initialState.slots.none { it.id == removableSlotId }) {
            "Gate 4 removable READY fixture already exists"
        }
        val dataRoot = StmCorePaths.dataRoot(targetContext).absoluteFile
        val dataBefore = fingerprintTree(dataRoot)
        val activeSlotRoot = StmCorePaths.slotsRoot(targetContext)
            .resolve(target.id)
            .absoluteFile
        val activeTreeBefore = fingerprintTree(activeSlotRoot)
        val archive = exactGate4SourceArchive()
        var activity: MainActivity? = null

        try {
            check(
                controller.installDownloadedArchive(
                    slotId = removableSlotId,
                    archive = archive,
                    installMode = StmCoreInstallMode.FAST_SIGNED_RUNTIME,
                ) == StmCoreCommandResult.Accepted,
            ) {
                "Gate 4 second-slot signed-runtime installation was rejected"
            }
            val installed = awaitState(controller, GATE4_SIGNED_SLOT_TIMEOUT_MILLIS) { state ->
                state.slots.any { slot ->
                    slot.id == removableSlotId &&
                        slot.state == StmCoreSlotState.READY &&
                        slot.commitSha == GATE4_ST_COMMIT &&
                        slot.artifact?.stVersion == GATE4_ST_VERSION
                } &&
                    state.jobs.any { job ->
                        job.type == StmCoreJobType.INSTALL &&
                            job.targetId == removableSlotId &&
                            job.state == StmCoreJobState.SUCCEEDED
                    } &&
                    state.jobs.none { it.state in ACTIVE_JOB_STATES }
            }
            val removable = requireNotNull(
                installed.slots.singleOrNull { it.id == removableSlotId },
            )
            check(installed.activeSlot == activeBefore) {
                "Installing the nonactive READY fixture changed the active pointer"
            }
            assertReadySlotUnchanged(installed, readyBefore)
            check(fingerprintTree(dataRoot) == dataBefore) {
                "Installing a second program slot changed Core user data"
            }
            check(fingerprintTree(activeSlotRoot) == activeTreeBefore) {
                "Installing a second program slot changed the old immutable READY tree"
            }

            activity = startActivitySync(
                Intent(targetContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(DEBUG_EXTRA_START_IN_VERSIONS, true)
                },
            ) as MainActivity
            val removeLabel = activity.getString(R.string.st_slot_remove)
            val removeTitle = activity.getString(R.string.st_slot_remove_confirm_title)
            clickFirstEnabledAccessibilityAction(
                expectedText = removeLabel,
                allowScroll = true,
            )
            awaitAccessibilityText(removeTitle)
            awaitAccessibilityTextContaining(removableSlotId)
            clickFirstEnabledAccessibilityAction(
                expectedText = removeLabel,
                allowScroll = false,
            )

            val removed = awaitState(controller, GATE4_STATE_TIMEOUT_MILLIS) { state ->
                state.slots.none { it.id == removableSlotId } &&
                    state.jobs.any { job ->
                        job.type == StmCoreJobType.REMOVE &&
                            job.targetId == removableSlotId &&
                            job.state == StmCoreJobState.SUCCEEDED
                    } &&
                    state.jobs.none { it.state in ACTIVE_JOB_STATES }
            }
            check(!StmCorePaths.slotsRoot(targetContext).resolve(removableSlotId).exists()) {
                "The product removal path reported success but retained the slot directory"
            }
            check(removed.activeSlot == activeBefore) {
                "Removing the nonactive READY fixture changed the active pointer"
            }
            assertReadySlotUnchanged(removed, readyBefore)
            val dataAfter = fingerprintTree(dataRoot)
            check(dataAfter == dataBefore) {
                "Removing a version slot changed or deleted Core user data"
            }
            check(fingerprintTree(activeSlotRoot) == activeTreeBefore) {
                "Removing a different version changed the old immutable READY tree"
            }

            val restarted = startAndVerifySillyTavern(
                controller,
                readyBefore,
                "post_slot_removal",
                result,
            )
            stopAndVerifySillyTavern(controller, restarted, readyBefore, result)
            result.putString(
                "gate4_slot_removal",
                "installed=${removable.id}:ready=${removable.state.name}:" +
                    "active_pointer=unchanged:product_confirmed_remove=true:" +
                    "slot_directory=absent:old_ready=startable",
            )
            result.putString(
                "gate4_data_isolation",
                "entries=${dataAfter.entries}:bytes=${dataAfter.bytes}:" +
                    "sha256=${dataAfter.sha256}:unchanged=true",
            )
            result.putString(
                "gate4_ready_immutability_after_remove",
                "entries=${activeTreeBefore.entries}:bytes=${activeTreeBefore.bytes}:" +
                    "sha256=${activeTreeBefore.sha256}:unchanged=true",
            )
        } finally {
            val state = controller.state.value
            if (state.runState == StmCoreRunState.RUNNING) {
                controller.stop()
                awaitState(controller, GATE4_STOP_TIMEOUT_MILLIS) {
                    it.runState == StmCoreRunState.STOPPED &&
                        it.runningSlot == null
                }
            }
            if (controller.state.value.slots.any { it.id == removableSlotId }) {
                check(controller.remove(removableSlotId) == StmCoreCommandResult.Accepted) {
                    "Gate 4 could not clean its exact removable-slot fixture"
                }
                awaitState(controller, GATE4_STATE_TIMEOUT_MILLIS) { stateAfterCleanup ->
                    stateAfterCleanup.slots.none { it.id == removableSlotId } &&
                        stateAfterCleanup.jobs.none { it.state in ACTIVE_JOB_STATES }
                }
            }
            activity?.let { launched -> onMain { launched.finish() } }
        }
    }

    private suspend fun runGate4FirstInstall(result: Bundle) {
        val application = targetContext.applicationContext as StmApplication
        val controller = onMain {
            application.container.stmCoreController as AndroidStmCoreController
        }
        val initial = awaitSettledCore(controller, GATE4_STATE_TIMEOUT_MILLIS)
        check(initial.runState == StmCoreRunState.STOPPED &&
            initial.slots.isEmpty() &&
            initial.activeSlot == null &&
            initial.runningSlot == null
        ) {
            "Gate 4 first-install user was not clean: $initial"
        }
        val dataRoot = StmCorePaths.dataRoot(targetContext).absoluteFile
        check(!dataRoot.exists() || dataRoot.listFiles().isNullOrEmpty()) {
            "Gate 4 first-install user already had ST user data"
        }
        val archive = exactGate4SourceArchive()
        val slotId = "st-release-$GATE4_ST_COMMIT"
        check(
            controller.installDownloadedArchive(
                slotId = slotId,
                archive = archive,
                installMode = StmCoreInstallMode.FAST_SIGNED_RUNTIME,
            ) == StmCoreCommandResult.Accepted,
        ) {
            "Gate 4 clean-user signed-runtime installation was rejected"
        }
        val installed = awaitState(controller, GATE4_SIGNED_SLOT_TIMEOUT_MILLIS) { state ->
            state.slots.any { slot ->
                slot.id == slotId &&
                    slot.state == StmCoreSlotState.READY &&
                    slot.commitSha == GATE4_ST_COMMIT &&
                    slot.artifact?.stVersion == GATE4_ST_VERSION
            } &&
                state.jobs.any { job ->
                    job.type == StmCoreJobType.INSTALL &&
                        job.targetId == slotId &&
                        job.state == StmCoreJobState.SUCCEEDED
                } &&
                state.jobs.none { it.state in ACTIVE_JOB_STATES }
        }
        check(installed.activeSlot == null && installed.runningSlot == null) {
            "A successful clean install activated or started ST without user action"
        }
        check(!dataRoot.exists() || dataRoot.listFiles().isNullOrEmpty()) {
            "Installing the program slot created ST user data before first start"
        }
        val ready = requireNotNull(installed.slots.singleOrNull { it.id == slotId })
        check(controller.activate(slotId) == StmCoreCommandResult.Accepted) {
            "Gate 4 clean-user activation was rejected"
        }
        val activated = awaitState(controller, GATE4_STATE_TIMEOUT_MILLIS) { state ->
            state.activeSlot?.slotId == slotId &&
                state.activeSlot?.slotRevision == ready.revision &&
                state.jobs.none { it.state in ACTIVE_JOB_STATES }
        }
        assertReadySlotUnchanged(activated, ready)
        val running = startAndVerifySillyTavern(
            controller,
            ready,
            "first_install",
            result,
        )
        stopAndVerifySillyTavern(controller, running, ready, result)
        check(dataRoot.resolve("config.yaml").isFile &&
            dataRoot.resolve("default-user").isDirectory
        ) {
            "First ST start did not initialize its separate user-data root"
        }
        val slotRoot = StmCorePaths.slotsRoot(targetContext).resolve(slotId)
        val programRoot = slotRoot.resolve(requireNotNull(ready.artifact?.archiveRoot))
        check(!programRoot.resolve("config.yaml").exists() &&
            !dataRoot.resolve("_webpack").exists()
        ) {
            "First ST start crossed the program-slot/user-data boundary"
        }
        result.putString(
            "gate4_first_install",
            "initial_slots=0:catalog_source=exact_commit:" +
                "signed_runtime=accepted:ready=true:auto_start=false:" +
                "activated=true:st=${GATE4_ST_VERSION}:data_initialized_on_first_start=true",
        )
        result.putString(
            "gate4_first_install_ready",
            "revision=${ready.revision}:files=${ready.manifestFileCount}:" +
                "bytes=${ready.manifestTotalBytes}:manifest=${ready.manifestSha256}",
        )
    }

    private fun exactGate4SourceArchive(): DownloadedStArchive {
        val channel = StDownloadChannel.STABLE
        val downloads = requireNotNull(
            targetContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
        ) {
            "Gate 4 has no app-scoped downloads directory"
        }.absoluteFile
        val archiveFile = downloads.resolve(channel.exactArchiveFileName(GATE4_ST_COMMIT))
        check(Files.isRegularFile(archiveFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Gate 4 exact ST source archive is unavailable: $archiveFile"
        }
        check(archiveFile.length() == GATE4_SOURCE_ARCHIVE_BYTES) {
            "Gate 4 exact ST source archive length changed: ${archiveFile.length()}"
        }
        check(sha256(archiveFile) == GATE3B_SOURCE_ARCHIVE_SHA256) {
            "Gate 4 exact ST source archive SHA-256 changed"
        }
        return DownloadedStArchive(
            channel = channel,
            fileName = archiveFile.name,
            sizeBytes = archiveFile.length(),
            downloadedAtEpochMillis = archiveFile.lastModified().coerceAtLeast(1L),
            identity = StArchiveIdentity(
                classification = StArchiveIdentityClassification.EXACT_COMMIT,
                channelRef = channel.branch,
                exactCommit = GATE4_ST_COMMIT,
                archiveUrl = channel.exactArchiveUrl(GATE4_ST_COMMIT),
            ),
            integrity = StArchiveIntegrity(
                classification =
                StArchiveIntegrityClassification.CONTENT_SHA256_RECORDED,
                byteLength = archiveFile.length(),
                sha256 = GATE3B_SOURCE_ARCHIVE_SHA256,
                hasZipFormatHint = true,
            ),
            trust = StArchiveTrust.DEGRADED_UNSIGNED_CATALOG,
        )
    }

    private fun assertReadySlotUnchanged(state: StmCoreState, expected: StmCoreSlot) {
        val actual = state.slots.singleOrNull { it.id == expected.id }
        check(actual?.state == StmCoreSlotState.READY &&
            actual.revision == expected.revision &&
            actual.manifestSha256 == expected.manifestSha256 &&
            actual.artifact == expected.artifact
        ) {
            "The original active READY slot identity changed: $actual"
        }
    }

    private suspend fun prepareGate4Target(
        controller: AndroidStmCoreController,
    ): StmCoreSlot {
        var settled = awaitSettledCore(controller, GATE4_STATE_TIMEOUT_MILLIS)
        if (settled.runState == StmCoreRunState.CRASHED) {
            check(controller.start() == StmCoreCommandResult.Accepted) {
                "Gate 4 could not recover the crashed Core before activation"
            }
            val recoveryRun = awaitState(controller, GATE4_STATE_TIMEOUT_MILLIS) {
                it.runState == StmCoreRunState.RUNNING
            }
            check(controller.stop() == StmCoreCommandResult.Accepted) {
                "Gate 4 could not stop the recovery session"
            }
            awaitState(controller, GATE4_STATE_TIMEOUT_MILLIS) {
                it.runState == StmCoreRunState.STOPPED
            }
            awaitPortReleased(requireNotNull(recoveryRun.port), GATE4_STATE_TIMEOUT_MILLIS)
            settled = controller.state.value
        }
        check(settled.runState == StmCoreRunState.STOPPED) {
            "Gate 4 requires a stopped Core before activation: $settled"
        }
        val matchingSlots = settled.slots.filter { slot ->
            slot.state == StmCoreSlotState.READY &&
                slot.artifact?.kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE &&
                slot.commitSha == GATE4_ST_COMMIT &&
                slot.artifact?.stVersion == GATE4_ST_VERSION
        }
        val target = when (matchingSlots.size) {
            1 -> matchingSlots.single()
            else -> settled.activeSlot?.let { active ->
                matchingSlots.singleOrNull { slot ->
                    slot.id == active.slotId && slot.revision == active.slotRevision
                }
            }
        } ?: error(
            "Gate 4 requires one exact READY SillyTavern $GATE4_ST_VERSION/$GATE4_ST_COMMIT " +
                "slot or an active pointer that disambiguates matching slots: ${settled.slots}",
        )
        if (settled.activeSlot?.slotId != target.id) {
            check(controller.activate(target.id) == StmCoreCommandResult.Accepted) {
                "Gate 4 explicit slot activation was rejected"
            }
            settled = awaitState(controller, GATE4_STATE_TIMEOUT_MILLIS) { state ->
                val pointer = state.activeSlot
                pointer?.slotId == target.id &&
                    pointer.slotRevision == target.revision &&
                    state.jobs.none { it.state in ACTIVE_JOB_STATES }
            }
        }
        val active = requireNotNull(settled.activeSlot)
        check(active.slotId == target.id && active.slotRevision == target.revision) {
            "Gate 4 did not freeze the requested READY slot"
        }
        return target
    }

    private suspend fun runGate4WebViewRendererRecovery(result: Bundle) {
        val application = targetContext.applicationContext as StmApplication
        val controller = onMain {
            application.container.stmCoreController as AndroidStmCoreController
        }
        val target = prepareGate4Target(controller)
        val running = startAndVerifySillyTavern(controller, target, "renderer", result)
        val activity = startActivitySync(
            Intent(targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(DEBUG_EXTRA_START_IN_TAVERN, true)
            },
        ) as MainActivity
        try {
            val initialWebView = awaitLoadedGate4WebView(
                activity = activity,
                baseUrl = requireNotNull(running.baseUrl),
                excluded = null,
            )
            val initialBodyCharacters = awaitWebViewBodyCharacters(initialWebView)
            val uiProcessId = Process.myPid()
            val initialState = controller.state.value
            check(initialState.processId == running.processId &&
                initialState.sessionId == running.sessionId
            ) {
                "Gate 4 renderer test did not begin in the verified Core/ST session"
            }

            val rendererTerminationAccepted = onMain {
                requireNotNull(initialWebView.webViewRenderProcess) {
                    "Gate 4 WebView had no isolated renderer process"
                }.terminate()
            }
            check(rendererTerminationAccepted) {
                "Gate 4 WebView renderer rejected controlled termination"
            }
            awaitRendererGoneCallback(initialWebView)
            check(Process.myPid() == uiProcessId) {
                "Gate 4 renderer termination killed the STM UI process"
            }
            val afterRendererExit = controller.state.value
            check(afterRendererExit.processId == running.processId &&
                afterRendererExit.sessionId == running.sessionId &&
                afterRendererExit.localBaseUrl == running.baseUrl
            ) {
                "Gate 4 renderer termination replaced or stopped the Core/ST session"
            }

            delay(GATE4_RENDERER_SNACKBAR_SETTLE_MILLIS)
            injectRendererReloadTap(activity)
            val recoveredWebView = awaitLoadedGate4WebView(
                activity = activity,
                baseUrl = requireNotNull(running.baseUrl),
                excluded = initialWebView,
            )
            val recoveredBodyCharacters = awaitWebViewBodyCharacters(recoveredWebView)
            val recoveredState = controller.state.value
            check(Process.myPid() == uiProcessId &&
                recoveredState.processId == running.processId &&
                recoveredState.sessionId == running.sessionId &&
                recoveredState.localBaseUrl == running.baseUrl
            ) {
                "Gate 4 WebView reconstruction did not preserve the Core/ST session"
            }
            val version = httpGet(
                "${running.baseUrl}/version",
                "application/json",
                requireNotNull(running.webSessionCredential),
            )
            check(version.code == 200 && version.body.toString(Charsets.UTF_8).contains(
                "\"pkgVersion\":\"$GATE4_ST_VERSION\"",
            )) {
                "Gate 4 ST was not reachable after WebView reconstruction"
            }
            result.putString(
                "gate4_renderer_recovery",
                "terminated=true:new_webview=true:ui_pid=$uiProcessId:" +
                    "core_pid=${running.processId}:session=${running.sessionId}:" +
                    "body_chars=$initialBodyCharacters->$recoveredBodyCharacters:" +
                    "version=200",
            )
        } finally {
            onMain { activity.finish() }
            if (controller.state.value.runState == StmCoreRunState.RUNNING) {
                stopAndVerifySillyTavern(controller, running, target, result)
            }
        }
    }

    private suspend fun runGate4CharacterFileChooser(result: Bundle) {
        val application = targetContext.applicationContext as StmApplication
        val controller = onMain {
            application.container.stmCoreController as AndroidStmCoreController
        }
        val target = prepareGate4Target(controller)
        val running = startAndVerifySillyTavern(controller, target, "chooser", result)
        val syntheticName = "STM-Chooser-${UUID.randomUUID().toString().take(12)}"
        val displayName = "$syntheticName.png"
        val fixtureBytes = createGate4CharacterCardFixture(running, syntheticName)
        val fixtureUri = createGate4PickerFixture(displayName, fixtureBytes)
        val characterFile = StmCorePaths.dataRoot(targetContext)
            .resolve("default-user/characters/$syntheticName.png")
        val activity = startActivitySync(
            Intent(targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(DEBUG_EXTRA_START_IN_TAVERN, true)
            },
        ) as MainActivity
        var imported = false
        try {
            check(!characterFile.exists()) {
                "Gate 4 file chooser fixture unexpectedly existed before import"
            }
            val webView = awaitLoadedGate4WebView(
                activity = activity,
                baseUrl = requireNotNull(running.baseUrl),
                excluded = null,
            )
            exposeGate4CharacterImportButton(webView)
            tapGate4WebViewCenter(webView)
            clickSystemDocument(displayName)
            val deadline = System.currentTimeMillis() + GATE4_FILE_CHOOSER_TIMEOUT_MILLIS
            while (!characterFile.isFile && System.currentTimeMillis() < deadline) {
                delay(POLL_MILLIS)
            }
            check(characterFile.isFile) {
                "Gate 4 system picker selection did not reach ST character import"
            }
            imported = true
            val importedBytes = characterFile.length()
            check(importedBytes > PNG_SIGNATURE.size &&
                characterFile.inputStream().use { input ->
                    val signature = ByteArray(PNG_SIGNATURE.size)
                    input.read(signature) == signature.size &&
                        signature.contentEquals(PNG_SIGNATURE)
                }
            ) {
                "Gate 4 file chooser import did not create a PNG character card"
            }
            result.putString(
                "gate4_file_chooser",
                "input=character_import_file:real_tap=true:picker=system:fixture=$displayName:" +
                    "imported_png_bytes=$importedBytes",
            )
        } finally {
            if (imported) {
                deleteGate4ImportedCharacter(
                    running = running,
                    syntheticName = syntheticName,
                    characterFile = characterFile,
                )
            }
            targetContext.contentResolver.delete(fixtureUri, null, null)
            onMain { activity.finish() }
            if (controller.state.value.runState == StmCoreRunState.RUNNING) {
                stopAndVerifySillyTavern(controller, running, target, result)
            }
        }
    }

    private suspend fun runGate4BlobSafExport(result: Bundle) {
        val application = targetContext.applicationContext as StmApplication
        val controller = onMain {
            application.container.stmCoreController as AndroidStmCoreController
        }
        val target = prepareGate4Target(controller)
        val running = startAndVerifySillyTavern(controller, target, "blob_saf", result)
        val displayName = "STM-Gate4-backup-${UUID.randomUUID().toString().take(12)}.zip"
        var exportedUri: Uri? = null
        val activity = startActivitySync(
            Intent(targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(DEBUG_EXTRA_START_IN_TAVERN, true)
            },
        ) as MainActivity
        try {
            val webView = awaitLoadedGate4WebView(
                activity = activity,
                baseUrl = requireNotNull(running.baseUrl),
                excluded = null,
            )
            awaitWebViewBodyCharacters(webView)
            delay(500)
            prepareGate4BackupDownload(webView, displayName)
            tapGate4WebViewCenter(webView)
            clickSystemSave()
            val exported = awaitGate4ExportedZip(displayName)
            exportedUri = exported.first
            val bytes = exported.second
            val entries = ZipInputStream(bytes.inputStream()).use { zip ->
                buildList {
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory) add(entry.name)
                        zip.closeEntry()
                    }
                }
            }
            check(entries.isNotEmpty() && bytes.size >= 4 &&
                bytes[0] == 'P'.code.toByte() &&
                bytes[1] == 'K'.code.toByte()
            ) {
                "Gate 4 SAF export was not a non-empty ZIP backup"
            }
            result.putString(
                "gate4_blob_saf",
                "endpoint=/api/users/backup:blob=true:system_save=true:" +
                    "name=$displayName:bytes=${bytes.size}:zip_entries=${entries.size}",
            )
        } finally {
            deleteGate4SafExport(displayName, exportedUri)
            dismissSystemPickerIfOpen()
            onMain { activity.finish() }
            if (controller.state.value.runState == StmCoreRunState.RUNNING) {
                stopAndVerifySillyTavern(controller, running, target, result)
            }
        }
    }

    private suspend fun prepareGate4BackupDownload(webView: WebView, displayName: String) {
        val statusKey = "__stmGate4BackupStatus"
        val statusKeyJson = JSONObject.quote(statusKey)
        val script = """
            (() => {
              window[$statusKeyJson] = 'loading';
              (async () => {
                try {
                  const csrfResponse = await fetch('/csrf-token', {
                    headers: { Accept: 'application/json' },
                  });
                  if (!csrfResponse.ok) throw new Error('csrf-' + csrfResponse.status);
                  const csrf = await csrfResponse.json();
                  const userResponse = await fetch('/api/users/me', {
                    headers: {
                      Accept: 'application/json',
                      'X-CSRF-Token': csrf.token,
                    },
                  });
                  if (!userResponse.ok) throw new Error('user-' + userResponse.status);
                  const user = await userResponse.json();
                  const backupResponse = await fetch('/api/users/backup', {
                    method: 'POST',
                    headers: {
                      Accept: 'application/zip',
                      'Content-Type': 'application/json',
                      'X-CSRF-Token': csrf.token,
                    },
                    body: JSON.stringify({ handle: user.handle }),
                  });
                  if (!backupResponse.ok) {
                    throw new Error('backup-' + backupResponse.status);
                  }
                  const blob = await backupResponse.blob();
                  const anchor = document.createElement('a');
                  anchor.id = 'stm-gate4-backup-download';
                  anchor.href = URL.createObjectURL(blob);
                  anchor.download = ${JSONObject.quote(displayName)};
                  anchor.textContent = 'STM Gate 4 backup';
                  const dialogs = Array.from(document.querySelectorAll('dialog[open]'));
                  const host = dialogs.length ? dialogs[dialogs.length - 1] : document.body;
                  host.appendChild(anchor);
                  anchor.style.setProperty('display', 'block', 'important');
                  anchor.style.setProperty('position', 'fixed', 'important');
                  anchor.style.setProperty('left', '40vw', 'important');
                  anchor.style.setProperty('top', '40vh', 'important');
                  anchor.style.setProperty('width', '20vw', 'important');
                  anchor.style.setProperty('height', '20vh', 'important');
                  anchor.style.setProperty('z-index', '2147483647', 'important');
                  anchor.style.setProperty('pointer-events', 'auto', 'important');
                  window[$statusKeyJson] = 'ready:' + blob.size;
                } catch (error) {
                  window[$statusKeyJson] = 'error:' + String(error?.message || error);
                }
              })();
            })()
        """.trimIndent()
        evaluateJavascript(webView, script)
        val deadline = System.currentTimeMillis() + GATE4_BLOB_SAF_TIMEOUT_MILLIS
        var observed = "not-evaluated"
        while (System.currentTimeMillis() < deadline) {
            observed = evaluateJavascript(webView, "window[$statusKeyJson] || 'missing'")
            if (observed.startsWith("\"ready:")) return
            check(!observed.startsWith("\"error:")) {
                "Gate 4 ST backup preparation failed: $observed"
            }
            delay(POLL_MILLIS)
        }
        error("Timed out preparing the Gate 4 ST backup Blob: $observed")
    }

    private suspend fun clickSystemSave() {
        delay(10_000L)
        uiAutomation.executeShellCommand(
            "input tap $GATE4_API31_SAVE_X $GATE4_API31_SAVE_Y",
        ).close()
    }

    private suspend fun awaitGate4ExportedZip(displayName: String): Pair<Uri, ByteArray> {
        val deadline = System.currentTimeMillis() + GATE4_BLOB_SAF_TIMEOUT_MILLIS
        var lastSize = -1
        uiAutomation.adoptShellPermissionIdentity()
        try {
            while (System.currentTimeMillis() < deadline) {
                val resolver = targetContext.contentResolver
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(displayName),
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(0)
                        lastSize = cursor.getLong(1).toInt()
                        if (lastSize > 0) {
                            val uri = Uri.withAppendedPath(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                id.toString(),
                            )
                            val bytes = runCatching {
                                resolver.openInputStream(uri).use { input ->
                                    requireNotNull(input).readBytes()
                                }
                            }.getOrNull()
                            if (bytes != null &&
                                bytes.size == lastSize &&
                                bytes.size >= 4 &&
                                bytes[0] == 'P'.code.toByte() &&
                                bytes[1] == 'K'.code.toByte()
                            ) {
                                return Pair(uri, bytes)
                            }
                        }
                    }
                }
                delay(200)
            }
            error("Timed out waiting for SAF export $displayName; lastSize=$lastSize")
        } finally {
            uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun deleteGate4SafExport(displayName: String, knownUri: Uri?) {
        uiAutomation.adoptShellPermissionIdentity()
        try {
            val resolver = targetContext.contentResolver
            if (knownUri != null) {
                resolver.delete(knownUri, null, null)
                return
            }
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(displayName),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    resolver.delete(
                        Uri.withAppendedPath(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            cursor.getLong(0).toString(),
                        ),
                        null,
                        null,
                    )
                }
            }
        } finally {
            uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun dismissSystemPickerIfOpen() {
        val packageName = uiAutomation.rootInActiveWindow?.packageName?.toString()
        if (packageName == "com.google.android.documentsui" ||
            packageName == "com.android.documentsui"
        ) {
            uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        }
    }

    private suspend fun exposeGate4CharacterImportButton(webView: WebView) {
        val deadline = System.currentTimeMillis() + GATE4_WEBVIEW_TIMEOUT_MILLIS
        var diagnostic = "not-evaluated"
        while (System.currentTimeMillis() < deadline) {
            val result = evaluateJavascript(
                webView,
                """
                    (() => {
                      const button = document.getElementById('character_import_button');
                      const input = document.getElementById('character_import_file');
                      const events = button && window.jQuery && jQuery._data
                        ? jQuery._data(button, 'events')
                        : null;
                      if (button && input && events && events.click) {
                        const dialogs = Array.from(document.querySelectorAll('dialog[open]'));
                        const host = dialogs.length ? dialogs[dialogs.length - 1] : document.body;
                        host.appendChild(button);
                        button.style.setProperty('display', 'block', 'important');
                        button.style.setProperty('position', 'fixed', 'important');
                        button.style.setProperty('left', '40vw', 'important');
                        button.style.setProperty('top', '40vh', 'important');
                        button.style.setProperty('width', '20vw', 'important');
                        button.style.setProperty('height', '20vh', 'important');
                        button.style.setProperty('z-index', '2147483647', 'important');
                        button.style.setProperty('pointer-events', 'auto', 'important');
                        return 'ready';
                      }
                      return JSON.stringify({
                        href: location.href,
                        title: document.title,
                        readyState: document.readyState,
                        hasButton: Boolean(button),
                        hasInput: Boolean(input),
                        hasHandler: Boolean(events && events.click),
                      });
                    })()
                """.trimIndent(),
            )
            if (result == "\"ready\"") return
            diagnostic = result
            delay(POLL_MILLIS)
        }
        error("Gate 4 could not expose the fixed ST import control: $diagnostic")
    }

    private fun tapGate4WebViewCenter(webView: WebView) {
        val center = onMain {
            val location = IntArray(2)
            webView.getLocationOnScreen(location)
            Pair(
                location[0] + webView.width / 2,
                location[1] + webView.height / 2,
            )
        }
        uiAutomation.executeShellCommand(
            "input tap ${center.first} ${center.second}",
        ).close()
    }

    private fun createGate4CharacterCardFixture(
        running: RunningSession,
        syntheticName: String,
    ): ByteArray {
        val baseUrl = requireNotNull(running.baseUrl)
        val session = Gate4HttpSession(
            baseUrl,
            requireNotNull(running.webSessionCredential),
            GATE4_HTTP_TIMEOUT_MILLIS,
        )
        val originHeaders = mapOf("Origin" to baseUrl)
        val csrfResponse = session.request(
            path = "/csrf-token",
            accept = "application/json",
            headers = originHeaders,
        )
        check(csrfResponse.code == 200) {
            "Gate 4 file chooser fixture could not establish CSRF"
        }
        val csrfHeaders = originHeaders + (
            "X-CSRF-Token" to JSONObject(csrfResponse.body.toString(Charsets.UTF_8))
                .getString("token")
            )
        val card = JSONObject()
            .put("name", syntheticName)
            .put("description", "Synthetic Gate 4 system picker fixture")
            .put("first_mes", "Synthetic Gate 4 file chooser greeting")
            .toString()
            .toByteArray()
        val (contentType, multipartBody) = Gate4HttpSession.multipart(
            fields = linkedMapOf(
                "file_type" to "json",
                "preserved_name" to syntheticName,
            ),
            fileField = "avatar",
            fileName = "$syntheticName.json",
            fileContentType = "application/json",
            fileBytes = card,
        )
        val upload = session.request(
            path = "/api/characters/import",
            method = "POST",
            accept = "application/json",
            contentType = contentType,
            body = multipartBody,
            headers = csrfHeaders,
        )
        check(upload.code == 200) {
            "Gate 4 could not create the picker character fixture: HTTP ${upload.code}"
        }
        val export = session.request(
            path = "/api/characters/export",
            method = "POST",
            accept = "image/png",
            contentType = "application/json",
            body = JSONObject()
                .put("format", "png")
                .put("avatar_url", "$syntheticName.png")
                .toString()
                .toByteArray(),
            headers = csrfHeaders,
        )
        val delete = session.request(
            path = "/api/characters/delete",
            method = "POST",
            contentType = "application/json",
            body = JSONObject()
                .put("avatar_url", "$syntheticName.png")
                .put("delete_chats", false)
                .toString()
                .toByteArray(),
            headers = csrfHeaders,
        )
        check(export.code == 200 &&
            export.body.size > PNG_SIGNATURE.size &&
            export.body.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) &&
            delete.code == 200
        ) {
            "Gate 4 could not finalize its picker fixture: " +
                "export=${export.code}, delete=${delete.code}"
        }
        return export.body
    }

    private fun createGate4PickerFixture(displayName: String, fixtureBytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS,
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = targetContext.contentResolver
        val uri = requireNotNull(
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values),
        ) {
            "Gate 4 could not create the system-picker fixture"
        }
        try {
            resolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output) {
                    "Gate 4 could not open the system-picker fixture"
                }.write(fixtureBytes)
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private suspend fun clickSystemDocument(displayName: String) {
        val deadline = System.currentTimeMillis() + GATE4_FILE_CHOOSER_TIMEOUT_MILLIS
        var diagnostic = "no accessibility root"
        while (System.currentTimeMillis() < deadline) {
            val root = uiAutomation.rootInActiveWindow
            diagnostic = root?.let { accessibilitySummary(it) } ?: diagnostic
            val match = root?.let { findAccessibilityNode(it, displayName) }
            if (match != null) {
                val bounds = Rect()
                match.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    uiAutomation.executeShellCommand(
                        "input tap ${bounds.centerX()} ${bounds.centerY()}",
                    ).close()
                    return
                }
            }
            delay(200)
        }
        error(
            "Timed out locating $displayName in the Android system file picker; " +
                "last accessibility tree=$diagnostic",
        )
    }

    private fun accessibilitySummary(root: AccessibilityNodeInfo): String {
        val values = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo) {
            node.text?.toString()?.takeIf(String::isNotBlank)?.let(values::add)
            node.contentDescription?.toString()
                ?.takeIf(String::isNotBlank)
                ?.let(values::add)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::collect)
            }
        }
        collect(root)
        return "package=${root.packageName}; values=${values.distinct().take(40)}"
    }

    private fun findAccessibilityNode(
        node: AccessibilityNodeInfo,
        expectedText: String,
    ): AccessibilityNodeInfo? {
        if (node.text?.toString() == expectedText ||
            node.contentDescription?.toString()?.contains(expectedText) == true
        ) {
            return node
        }
        for (index in 0 until node.childCount) {
            val match = node.getChild(index)?.let { child ->
                findAccessibilityNode(child, expectedText)
            }
            if (match != null) return match
        }
        return null
    }

    private suspend fun clickFirstEnabledAccessibilityAction(
        expectedText: String,
        allowScroll: Boolean,
    ) {
        val deadline = System.currentTimeMillis() + GATE4_UI_ACTION_TIMEOUT_MILLIS
        var diagnostic = "no accessibility root"
        while (System.currentTimeMillis() < deadline) {
            val root = uiAutomation.rootInActiveWindow
            diagnostic = root?.let(::accessibilitySummary) ?: diagnostic
            val matches = mutableListOf<AccessibilityNodeInfo>()
            if (root != null) collectAccessibilityNodes(root, expectedText, matches)
            if (allowScroll && matches.isNotEmpty()) {
                delay(GATE4_UI_SCROLL_SETTLE_MILLIS)
                matches.clear()
                uiAutomation.rootInActiveWindow?.let { settledRoot ->
                    diagnostic = accessibilitySummary(settledRoot)
                    collectAccessibilityNodes(settledRoot, expectedText, matches)
                }
            }
            for (match in matches) {
                val action = match.closestEnabledClickableNode() ?: continue
                val bounds = Rect()
                action.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    injectScreenTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                    delay(GATE4_UI_TAP_SETTLE_MILLIS)
                    return
                }
                if (action.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            }
            if (allowScroll && root != null) {
                val bounds = Rect()
                root.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    uiAutomation.executeShellCommand(
                        "input swipe ${bounds.centerX()} " +
                            "${(bounds.bottom * 0.82f).toInt()} ${bounds.centerX()} " +
                            "${(bounds.bottom * 0.28f).toInt()} 300",
                    ).close()
                }
            }
            delay(250)
        }
        error(
            "Timed out locating enabled accessibility action $expectedText; " +
                "last accessibility tree=$diagnostic",
        )
    }

    private fun injectScreenTap(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            x,
            y,
            0,
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val up = MotionEvent.obtain(
            downTime,
            downTime + 50L,
            MotionEvent.ACTION_UP,
            x,
            y,
            0,
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            check(uiAutomation.injectInputEvent(down, true)) {
                "Android rejected the Gate 4 touch-down event"
            }
            check(uiAutomation.injectInputEvent(up, true)) {
                "Android rejected the Gate 4 touch-up event"
            }
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private suspend fun awaitAccessibilityText(expectedText: String) {
        val deadline = System.currentTimeMillis() + GATE4_UI_ACTION_TIMEOUT_MILLIS
        var diagnostic = "no accessibility root"
        while (System.currentTimeMillis() < deadline) {
            val root = uiAutomation.rootInActiveWindow
            diagnostic = root?.let(::accessibilitySummary) ?: diagnostic
            if (root?.let { findAccessibilityNode(it, expectedText) } != null) return
            delay(200)
        }
        error(
            "Timed out locating accessibility text $expectedText; " +
                "last accessibility tree=$diagnostic",
        )
    }

    private suspend fun awaitAccessibilityTextContaining(expectedText: String) {
        val deadline = System.currentTimeMillis() + GATE4_UI_ACTION_TIMEOUT_MILLIS
        var diagnostic = "no accessibility root"
        while (System.currentTimeMillis() < deadline) {
            val root = uiAutomation.rootInActiveWindow
            diagnostic = root?.let(::accessibilitySummary) ?: diagnostic
            if (root?.containsAccessibilityText(expectedText) == true) return
            delay(200)
        }
        error(
            "Timed out locating accessibility text containing $expectedText; " +
                "last accessibility tree=$diagnostic",
        )
    }

    private fun collectAccessibilityNodes(
        node: AccessibilityNodeInfo,
        expectedText: String,
        matches: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node.text?.toString() == expectedText ||
            node.contentDescription?.toString() == expectedText
        ) {
            matches += node
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                collectAccessibilityNodes(child, expectedText, matches)
            }
        }
    }

    private fun AccessibilityNodeInfo.closestEnabledClickableNode(): AccessibilityNodeInfo? {
        var candidate: AccessibilityNodeInfo? = this
        while (candidate != null) {
            if (candidate.isEnabled && candidate.isClickable) return candidate
            candidate = candidate.parent
        }
        return null
    }

    private fun AccessibilityNodeInfo.containsAccessibilityText(expectedText: String): Boolean {
        if (text?.toString()?.contains(expectedText) == true ||
            contentDescription?.toString()?.contains(expectedText) == true
        ) {
            return true
        }
        for (index in 0 until childCount) {
            if (getChild(index)?.containsAccessibilityText(expectedText) == true) return true
        }
        return false
    }

    private fun deleteGate4ImportedCharacter(
        running: RunningSession,
        syntheticName: String,
        characterFile: File,
    ) {
        val baseUrl = requireNotNull(running.baseUrl)
        val session = Gate4HttpSession(
            baseUrl,
            requireNotNull(running.webSessionCredential),
            GATE4_HTTP_TIMEOUT_MILLIS,
        )
        val originHeaders = mapOf("Origin" to baseUrl)
        val csrfResponse = session.request(
            path = "/csrf-token",
            accept = "application/json",
            headers = originHeaders,
        )
        check(csrfResponse.code == 200) {
            "Gate 4 file chooser cleanup could not establish CSRF"
        }
        val csrfToken = JSONObject(csrfResponse.body.toString(Charsets.UTF_8))
            .getString("token")
        val delete = session.request(
            path = "/api/characters/delete",
            method = "POST",
            contentType = "application/json",
            body = JSONObject()
                .put("avatar_url", "$syntheticName.png")
                .put("delete_chats", false)
                .toString()
                .toByteArray(),
            headers = originHeaders + ("X-CSRF-Token" to csrfToken),
        )
        check(delete.code == 200 && !characterFile.exists()) {
            "Gate 4 file chooser character cleanup failed: HTTP ${delete.code}"
        }
    }

    private suspend fun awaitRendererGoneCallback(webView: WebView) {
        val deadline = System.currentTimeMillis() + GATE4_WEBVIEW_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (onMain { webView.tag === TavernDestroyedRendererTag }) return
            delay(POLL_MILLIS)
        }
        error("Timed out waiting for the WebView renderer exit callback")
    }

    private fun injectRendererReloadTap(activity: MainActivity) {
        val coordinates = onMain {
            val decor = activity.window.decorView
            val systemBars = decor.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())
            val left = systemBars?.left ?: 0
            val top = systemBars?.top ?: 0
            val right = decor.width - (systemBars?.right ?: 0)
            val bottom = decor.height - (systemBars?.bottom ?: 0)
            check(right > left && bottom > top) {
                "Gate 4 renderer recovery had invalid visible window bounds"
            }
            Pair(
                left + ((right - left) * GATE4_RELOAD_ACTION_X_FRACTION),
                top + ((bottom - top) * GATE4_RELOAD_ACTION_Y_FRACTION),
            )
        }
        uiAutomation.executeShellCommand(
            "input tap ${coordinates.first.toInt()} ${coordinates.second.toInt()}",
        ).close()
    }

    private suspend fun awaitLoadedGate4WebView(
        activity: MainActivity,
        baseUrl: String,
        excluded: WebView?,
    ): WebView {
        val deadline = System.currentTimeMillis() + GATE4_WEBVIEW_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val candidate = onMain {
                findWebViews(activity.findViewById(android.R.id.content))
                    .firstOrNull { webView ->
                        webView !== excluded &&
                            webView.isShown &&
                            webView.width > 0 &&
                            webView.height > 0 &&
                            webView.url?.startsWith(baseUrl) == true
                    }
            }
            if (candidate != null) return candidate
            delay(POLL_MILLIS)
        }
        error("Timed out waiting for a loaded Gate 4 WebView at $baseUrl")
    }

    private fun findWebViews(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> buildList {
            repeat(view.childCount) { index ->
                addAll(findWebViews(view.getChildAt(index)))
            }
        }

        else -> emptyList()
    }

    private suspend fun awaitWebViewBodyCharacters(webView: WebView): Int {
        val deadline = System.currentTimeMillis() + GATE4_WEBVIEW_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val characters = evaluateJavascript(
                webView,
                "document.body ? document.body.innerText.length : 0",
            ).toIntOrNull() ?: 0
            if (characters > 0) return characters
            delay(POLL_MILLIS)
        }
        error("Timed out waiting for rendered SillyTavern page content")
    }

    private suspend fun evaluateJavascript(webView: WebView, script: String): String {
        val completed = CompletableDeferred<String>()
        onMain {
            webView.evaluateJavascript(script) { value ->
                completed.complete(value)
            }
        }
        return withTimeout(GATE4_WEBVIEW_JAVASCRIPT_TIMEOUT_MILLIS) {
            completed.await()
        }
    }

    private suspend fun startAndVerifySillyTavern(
        controller: AndroidStmCoreController,
        target: StmCoreSlot,
        label: String,
        result: Bundle,
    ): RunningSession {
        val startedAt = SystemClock.elapsedRealtime()
        check(controller.start() == StmCoreCommandResult.Accepted) {
            "Gate 4 $label start was rejected from ${controller.state.value}"
        }
        val running = awaitState(controller, GATE4_STATE_TIMEOUT_MILLIS) { state ->
            state.runState == StmCoreRunState.RUNNING &&
                state.workload == StmCoreWorkload.SILLY_TAVERN
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val runningPointer = running.runningSlot
        check(runningPointer?.slotId == target.id &&
            runningPointer.slotRevision == target.revision
        ) {
            "Gate 4 $label run did not freeze the active READY slot: $running"
        }
        val baseUrl = requireNotNull(running.localBaseUrl)
        val endpoint = URI(baseUrl)
        check(endpoint.host == "127.0.0.1" && endpoint.port in 1..65_535) {
            "Gate 4 published an invalid endpoint: $baseUrl"
        }
        val credential = requireNotNull(running.webSessionCredential) {
            "Gate 4 did not receive a Binder-bound Web session credential"
        }

        val unauthorized = httpGet("$baseUrl/version", "application/json")
        check(unauthorized.code == 403) {
            "Gate 4 unauthenticated loopback request returned HTTP ${unauthorized.code}"
        }
        val wrongOrigin = httpGet(
            url = "$baseUrl/version",
            accept = "application/json",
            webSessionCredential = credential,
            origin = "http://127.0.0.1:1",
        )
        check(wrongOrigin.code == 403) {
            "Gate 4 accepted the Web session credential from a foreign Origin"
        }
        val wrongHost = rawHttpGet(
            endpoint = endpoint,
            path = "/version",
            host = "localhost:${endpoint.port}",
            cookie = "$STM_CORE_WEB_SESSION_COOKIE_NAME=${credential.value}",
        )
        check(wrongHost.statusLine == "HTTP/1.1 403 Forbidden") {
            "Gate 4 accepted the Web session credential with a foreign Host: " +
                wrongHost.statusLine
        }
        val version = httpGet(
            "$baseUrl/version",
            "application/json",
            credential,
        )
        check(version.code == 200 && version.body.toString(Charsets.UTF_8).contains(
            "\"pkgVersion\":\"$GATE4_ST_VERSION\"",
        )) {
            "Gate 4 $label /version failed: HTTP ${version.code}"
        }
        val home = httpGet("$baseUrl/", "text/html", credential)
        check(home.code == 200 && home.body.isNotEmpty()) {
            "Gate 4 $label homepage failed: HTTP ${home.code}, bytes=${home.body.size}"
        }
        val bundle = httpGet("$baseUrl/lib.js", "application/javascript", credential)
        val bundleSha = sha256(bundle.body)
        check(
            bundle.code == 200 &&
                bundle.body.size.toLong() == GATE4_BUNDLE_BYTES &&
                bundleSha == GATE4_BUNDLE_SHA256
        ) {
            "Gate 4 $label /lib.js mismatch: HTTP ${bundle.code}, " +
                "bytes=${bundle.body.size}, sha256=$bundleSha"
        }
        result.putLong("gate4_${label}_start_ms", elapsed)
        result.putInt("gate4_${label}_port", endpoint.port)
        result.putString("gate4_${label}_session", running.sessionId)
        result.putString("gate4_${label}_version", "$GATE4_ST_VERSION:${version.body.size}")
        result.putString("gate4_${label}_home", "200:${home.body.size}")
        result.putString("gate4_${label}_lib", "200:${bundle.body.size}:$bundleSha")
        result.putString("gate4_${label}_unauthorized", unauthorized.code.toString())
        result.putString("gate4_${label}_foreign_origin", wrongOrigin.code.toString())
        result.putString("gate4_${label}_foreign_host", "403")
        if (label == "first") {
            verifyGate4WebChannels(baseUrl, credential, result)
        }
        return RunningSession(
            processId = requireNotNull(running.processId),
            sessionId = requireNotNull(running.sessionId),
            port = endpoint.port,
            baseUrl = baseUrl,
            webSessionCredential = credential,
        )
    }

    private fun verifyGate4PortChangeRecovery(
        previous: RunningSession,
        current: RunningSession,
        result: Bundle,
    ) {
        val currentBaseUrl = requireNotNull(current.baseUrl)
        val previousCredential = requireNotNull(previous.webSessionCredential)
        val currentCredential = requireNotNull(current.webSessionCredential)
        check(previousCredential != currentCredential) {
            "Gate 4 reused the prior Web credential after a port change"
        }
        val staleCredential = httpGet(
            url = "$currentBaseUrl/version",
            accept = "application/json",
            webSessionCredential = previousCredential,
            origin = currentBaseUrl,
        )
        check(staleCredential.code == 403) {
            "Gate 4 accepted the prior origin's Web credential on the new port"
        }

        val session = Gate4HttpSession(
            currentBaseUrl,
            currentCredential,
            GATE4_HTTP_TIMEOUT_MILLIS,
        )
        val csrfBootstrap = session.request(
            path = "/csrf-token",
            accept = "application/json",
            headers = mapOf("Origin" to currentBaseUrl),
        )
        check(csrfBootstrap.code == 200) {
            "Gate 4 could not bootstrap CSRF after the port change"
        }
        val csrfToken = JSONObject(csrfBootstrap.body.toString(Charsets.UTF_8))
            .getString("token")
        val recovered = session.request(
            path = "/api/ping",
            method = "POST",
            headers = mapOf(
                "Origin" to currentBaseUrl,
                "X-CSRF-Token" to csrfToken,
            ),
        )
        check(recovered.code == 204) {
            "Gate 4 Cookie/CSRF recovery failed after the port change: HTTP ${recovered.code}"
        }
        result.putString(
            "gate4_port_change",
            "${previous.port}->${current.port}:stale_credential=403:new_csrf=204",
        )
    }

    private suspend fun verifyFailedUpgradeRollback(
        controller: AndroidStmCoreController,
        target: StmCoreSlot,
        result: Bundle,
    ) {
        val before = controller.state.value
        val activeBefore = requireNotNull(before.activeSlot)
        val existingOperationIds = before.jobs.mapTo(mutableSetOf(), StmCoreJob::operationId)
        check(activeBefore.slotId == target.id && activeBefore.slotRevision == target.revision) {
            "Gate 4 rollback test did not begin from the expected READY slot"
        }
        val failedCommit = GATE4_FAILED_UPGRADE_COMMIT
        val failedSlotId = "st-gate4-failed-${failedCommit.take(12)}"
        check(before.slots.none { it.id == failedSlotId }) {
            "Gate 4 failed-upgrade fixture slot already exists"
        }
        val downloads = requireNotNull(
            targetContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
        ) {
            "Gate 4 failed-upgrade test has no app-scoped downloads directory"
        }.absoluteFile
        check(downloads.isDirectory || downloads.mkdirs()) {
            "Gate 4 could not create its app-scoped downloads directory"
        }
        val channel = StDownloadChannel.STABLE
        val archiveFile = downloads.resolve(channel.exactArchiveFileName(failedCommit))
        check(!archiveFile.exists()) {
            "Gate 4 failed-upgrade fixture archive already exists"
        }
        try {
            ZipOutputStream(FileOutputStream(archiveFile)).use { zip ->
                zip.putNextEntry(ZipEntry("SillyTavern-$failedCommit/README.md"))
                zip.write("Synthetic incomplete Gate 4 upgrade archive\n".toByteArray())
                zip.closeEntry()
            }
            val archiveBytes = archiveFile.readBytes()
            val archiveSha = sha256(archiveBytes)
            val archive = DownloadedStArchive(
                channel = channel,
                fileName = archiveFile.name,
                sizeBytes = archiveFile.length(),
                downloadedAtEpochMillis = System.currentTimeMillis(),
                identity = StArchiveIdentity(
                    classification = StArchiveIdentityClassification.EXACT_COMMIT,
                    channelRef = channel.branch,
                    exactCommit = failedCommit,
                    archiveUrl = channel.exactArchiveUrl(failedCommit),
                ),
                integrity = StArchiveIntegrity(
                    classification =
                    StArchiveIntegrityClassification.CONTENT_SHA256_RECORDED,
                    byteLength = archiveFile.length(),
                    sha256 = archiveSha,
                    hasZipFormatHint = true,
                ),
                trust = StArchiveTrust.DEGRADED_UNSIGNED_CATALOG,
            )
            check(
                controller.installDownloadedArchive(
                    slotId = failedSlotId,
                    archive = archive,
                    installMode = StmCoreInstallMode.FAST_SIGNED_RUNTIME,
                ) == StmCoreCommandResult.Accepted,
            ) {
                "Gate 4 controlled failed-upgrade request was rejected before Core preflight"
            }
            val failed = awaitState(controller, GATE4_FAILED_UPGRADE_TIMEOUT_MILLIS) { state ->
                state.jobs.any { job ->
                    job.operationId !in existingOperationIds &&
                    job.type == StmCoreJobType.INSTALL &&
                        job.targetId == failedSlotId &&
                        job.state == StmCoreJobState.FAILED
                } && state.jobs.none { it.state in ACTIVE_JOB_STATES }
            }
            val failedJob = requireNotNull(
                failed.jobs.lastOrNull { job ->
                    job.operationId !in existingOperationIds &&
                        job.type == StmCoreJobType.INSTALL &&
                        job.targetId == failedSlotId
                },
            )
            check(failedJob.state == StmCoreJobState.FAILED &&
                failed.slots.none { it.id == failedSlotId && it.state == StmCoreSlotState.READY }
            ) {
                "Gate 4 incomplete new version did not fail closed: $failedJob"
            }
            check(failed.activeSlot == activeBefore) {
                "Gate 4 failed new version changed the active READY pointer"
            }
            val preserved = failed.slots.singleOrNull { it.id == target.id }
            check(preserved?.state == StmCoreSlotState.READY &&
                preserved.revision == target.revision &&
                preserved.manifestSha256 == target.manifestSha256
            ) {
                "Gate 4 failed new version changed the old READY slot: $preserved"
            }

            val rollbackRun = startAndVerifySillyTavern(
                controller,
                target,
                "rollback",
                result,
            )
            stopAndVerifySillyTavern(controller, rollbackRun, target, result)
            result.putString(
                "gate4_failed_upgrade",
                "install=failed:phase=${failedJob.phase}:old_ready=startable",
            )

            val afterRun = controller.state.value
            if (afterRun.slots.any { it.id == failedSlotId }) {
                check(controller.remove(failedSlotId) == StmCoreCommandResult.Accepted) {
                    "Gate 4 could not remove its exact failed-upgrade test slot"
                }
                awaitState(controller, GATE4_FAILED_UPGRADE_TIMEOUT_MILLIS) { state ->
                    state.slots.none { it.id == failedSlotId } &&
                        state.jobs.any { job ->
                            job.type == StmCoreJobType.REMOVE &&
                                job.targetId == failedSlotId &&
                                job.state == StmCoreJobState.SUCCEEDED
                        } &&
                        state.jobs.none { it.state in ACTIVE_JOB_STATES }
                }
            }
            check(controller.state.value.activeSlot == activeBefore) {
                "Gate 4 failed-upgrade cleanup changed the old active READY pointer"
            }
        } finally {
            if (archiveFile.exists()) {
                check(archiveFile.delete()) {
                    "Gate 4 could not remove its exact failed-upgrade archive"
                }
            }
        }
    }

    private fun verifyGate4WebChannels(
        baseUrl: String,
        credential: StmCoreWebSessionCredential,
        result: Bundle,
    ) {
        val session = Gate4HttpSession(baseUrl, credential, GATE4_HTTP_TIMEOUT_MILLIS)
        val originHeaders = mapOf("Origin" to baseUrl)
        val csrfResponse = session.request(
            path = "/csrf-token",
            accept = "application/json",
            headers = originHeaders,
        )
        check(csrfResponse.code == 200) {
            "Gate 4 CSRF bootstrap returned HTTP ${csrfResponse.code}"
        }
        val csrfToken = JSONObject(csrfResponse.body.toString(Charsets.UTF_8))
            .getString("token")
        check(csrfToken.length >= 32) { "Gate 4 received an invalid CSRF token" }
        check(session.cookieNames.any { it != STM_CORE_WEB_SESSION_COOKIE_NAME }) {
            "Gate 4 did not retain SillyTavern's application session cookie"
        }

        val missingCsrf = session.request(
            path = "/api/characters/all",
            method = "POST",
            accept = "application/json",
            contentType = "application/json",
            body = "{}".toByteArray(),
            headers = originHeaders,
        )
        check(missingCsrf.code == 403) {
            "Gate 4 POST without CSRF returned HTTP ${missingCsrf.code}"
        }
        val csrfHeaders = originHeaders + ("X-CSRF-Token" to csrfToken)
        val characterList = session.request(
            path = "/api/characters/all",
            method = "POST",
            accept = "application/json",
            contentType = "application/json",
            body = "{}".toByteArray(),
            headers = csrfHeaders,
        )
        check(characterList.code == 200 &&
            characterList.body.toString(Charsets.UTF_8).trimStart().startsWith("[")
        ) {
            "Gate 4 CSRF-authorized character query failed: HTTP ${characterList.code}"
        }

        val range = session.request(
            path = "/sounds/message.mp3",
            accept = "audio/mpeg",
            headers = originHeaders + ("Range" to "bytes=0-63"),
        )
        check(range.code == 206 && range.body.size == GATE4_RANGE_BYTES) {
            "Gate 4 audio Range failed: HTTP ${range.code}, bytes=${range.body.size}"
        }
        check(range.header("Content-Range")?.startsWith("bytes 0-63/") == true) {
            "Gate 4 audio response lacks the expected Content-Range"
        }
        check(range.header("Content-Type")?.startsWith("audio/mpeg") == true) {
            "Gate 4 audio Range returned MIME ${range.header("Content-Type")}"
        }

        val missingPath = "/stm-gate4-missing-${UUID.randomUUID()}"
        val notFound = session.request(
            path = missingPath,
            accept = "text/html",
            headers = originHeaders,
        )
        check(notFound.code == 404) {
            "Gate 4 missing resource returned HTTP ${notFound.code}"
        }

        verifySyntheticCharacterRoundTrip(session, csrfHeaders, result)
        verifySseForwarding(session, csrfHeaders, result)

        val reload = session.request(
            path = "/",
            accept = "text/html",
            headers = originHeaders,
        )
        check(reload.code == 200 && reload.body.isNotEmpty()) {
            "Gate 4 authenticated page reload failed: HTTP ${reload.code}"
        }
        val postReloadCsrf = session.request(
            path = "/api/ping",
            method = "POST",
            headers = csrfHeaders,
        )
        check(postReloadCsrf.code == 204) {
            "Gate 4 CSRF session did not survive page reload: HTTP ${postReloadCsrf.code}"
        }

        result.putString("gate4_cookie_names", session.cookieNames.sorted().joinToString(","))
        result.putString("gate4_csrf_missing", missingCsrf.code.toString())
        result.putString("gate4_csrf_authorized", characterList.code.toString())
        result.putString(
            "gate4_range",
            "${range.code}:${range.body.size}:${range.header("Content-Range")}",
        )
        result.putString("gate4_404", notFound.code.toString())
        result.putString("gate4_reload", "${reload.code}:${postReloadCsrf.code}")
    }

    private fun verifySyntheticCharacterRoundTrip(
        session: Gate4HttpSession,
        csrfHeaders: Map<String, String>,
        result: Bundle,
    ) {
        val syntheticName = "STM-Gate4-${UUID.randomUUID().toString().take(12)}"
        val dataRoot = StmCorePaths.dataRoot(targetContext).absoluteFile
        val characterFile = dataRoot.resolve("default-user/characters/$syntheticName.png")
        check(!characterFile.exists()) {
            "Gate 4 synthetic character unexpectedly exists before upload"
        }
        val uploadsRoot = dataRoot.resolve("_uploads")
        val uploadsBefore = uploadsRoot.list()?.toSet().orEmpty()
        var imported = false
        try {
            val card = JSONObject()
                .put("name", syntheticName)
                .put("description", "Synthetic Gate 4 upload fixture")
                .put("first_mes", "Synthetic Gate 4 greeting")
                .toString()
                .toByteArray()
            val (contentType, multipartBody) = Gate4HttpSession.multipart(
                fields = linkedMapOf(
                    "file_type" to "json",
                    "preserved_name" to syntheticName,
                ),
                fileField = "avatar",
                fileName = "$syntheticName.json",
                fileContentType = "application/json",
                fileBytes = card,
            )
            val upload = session.request(
                path = "/api/characters/import",
                method = "POST",
                accept = "application/json",
                contentType = contentType,
                body = multipartBody,
                headers = csrfHeaders,
            )
            check(upload.code == 200) {
                "Gate 4 character upload returned HTTP ${upload.code}"
            }
            val importedName = JSONObject(upload.body.toString(Charsets.UTF_8))
                .getString("file_name")
            check(importedName == syntheticName && characterFile.isFile) {
                "Gate 4 character upload did not create the exact synthetic card"
            }
            imported = true

            val exportBody = JSONObject()
                .put("format", "png")
                .put("avatar_url", "$syntheticName.png")
                .toString()
                .toByteArray()
            val download = session.request(
                path = "/api/characters/export",
                method = "POST",
                accept = "image/png",
                contentType = "application/json",
                body = exportBody,
                headers = csrfHeaders,
            )
            check(download.code == 200 &&
                download.body.isNotEmpty() &&
                download.body.size >= PNG_SIGNATURE.size &&
                download.body.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) &&
                download.header("Content-Type")?.startsWith("image/png") == true &&
                download.header("Content-Disposition")?.contains("attachment") == true
            ) {
                "Gate 4 character download failed: HTTP ${download.code}, " +
                    "MIME=${download.header("Content-Type")}, " +
                    "disposition=${download.header("Content-Disposition")}"
            }
            val uploadedSha = sha256(characterFile.readBytes())
            val downloadedSha = sha256(download.body)

            val deleteBody = JSONObject()
                .put("avatar_url", "$syntheticName.png")
                .put("delete_chats", false)
                .toString()
                .toByteArray()
            val delete = session.request(
                path = "/api/characters/delete",
                method = "POST",
                contentType = "application/json",
                body = deleteBody,
                headers = csrfHeaders,
            )
            check(delete.code == 200 && !characterFile.exists()) {
                "Gate 4 synthetic character cleanup failed: HTTP ${delete.code}"
            }
            imported = false
            val uploadsAfter = uploadsRoot.list()?.toSet().orEmpty()
            check(uploadsAfter == uploadsBefore) {
                "Gate 4 upload left a temporary file: ${uploadsAfter - uploadsBefore}"
            }
            result.putString(
                "gate4_character_round_trip",
                "upload=200:stored_sha256=$uploadedSha:download=200:" +
                    "bytes=${download.body.size}:export_sha256=$downloadedSha:" +
                    "delete=200",
            )
        } finally {
            if (imported && characterFile.exists()) {
                check(characterFile.delete()) {
                    "Gate 4 could not remove its exact synthetic character after failure"
                }
            }
            val leftovers = dataRoot.walkTopDown()
                .filter { it.name.contains(syntheticName) }
                .map { it.absolutePath }
                .toList()
            check(leftovers.isEmpty()) {
                "Gate 4 synthetic character left scoped data behind: $leftovers"
            }
        }
    }

    private fun verifySseForwarding(
        session: Gate4HttpSession,
        csrfHeaders: Map<String, String>,
        result: Bundle,
    ) {
        Gate4SseFixture().use { fixture ->
            val requestBody = JSONObject()
                .put("api_type", "generic")
                .put("api_server", "http://127.0.0.1:${fixture.port}")
                .put("stream", true)
                .put("prompt", "STM Gate 4 synthetic SSE request")
                .put("max_tokens", 1)
                .toString()
                .toByteArray()
            val response = session.request(
                path = "/api/backends/text-completions/generate",
                method = "POST",
                accept = "text/event-stream",
                contentType = "application/json",
                body = requestBody,
                headers = csrfHeaders,
            )
            val forwarded = response.body.toString(Charsets.UTF_8)
            check(response.code == 200 &&
                forwarded.contains("STM-Gate4-SSE") &&
                forwarded.contains("data: [DONE]")
            ) {
                "Gate 4 SSE forwarding failed: HTTP ${response.code}, " +
                    "MIME=${response.header("Content-Type")}, body=$forwarded"
            }
            val firstByteAt = requireNotNull(response.firstByteElapsedMillis) {
                "Gate 4 SSE forwarding returned no bytes"
            }
            check(response.completedElapsedMillis - firstByteAt >= GATE4_SSE_MINIMUM_GAP_MILLIS) {
                "Gate 4 SSE response was buffered instead of forwarded incrementally: " +
                    "first=$firstByteAt ms, complete=${response.completedElapsedMillis} ms"
            }
            val upstream = fixture.awaitRequest()
            check(upstream.requestLine.startsWith("POST /v1/completions HTTP/1.1") &&
                upstream.body.contains("\"stream\":true") &&
                upstream.body.contains("STM Gate 4 synthetic SSE request")
            ) {
                "Gate 4 ST SSE handler sent an unexpected upstream request: $upstream"
            }
            result.putString(
                "gate4_sse",
                "200:first_byte_ms=$firstByteAt:" +
                    "complete_ms=${response.completedElapsedMillis}:incremental=true:" +
                    "downstream_content_type=${response.header("Content-Type") ?: "absent"}",
            )
        }
    }

    private suspend fun stopAndVerifySillyTavern(
        controller: AndroidStmCoreController,
        session: RunningSession,
        target: StmCoreSlot,
        result: Bundle,
    ) {
        val stoppedAt = SystemClock.elapsedRealtime()
        check(controller.stop() == StmCoreCommandResult.Accepted) {
            "Gate 4 stop was rejected"
        }
        val stopped = awaitState(controller, GATE4_STOP_TIMEOUT_MILLIS) { state ->
            state.runState == StmCoreRunState.STOPPED &&
                state.runningSlot == null
        }
        awaitPortReleased(session.port, GATE4_STOP_TIMEOUT_MILLIS)
        val recovered = stopped.slots.singleOrNull { it.id == target.id }
        check(recovered?.state == StmCoreSlotState.READY &&
            recovered.revision == target.revision &&
            recovered.manifestSha256 == target.manifestSha256
        ) {
            "Gate 4 post-run slot verification did not preserve READY identity: $recovered"
        }
        result.putLong(
            "gate4_${session.sessionId.take(8)}_stop_verify_ms",
            SystemClock.elapsedRealtime() - stoppedAt,
        )
    }

    private suspend fun awaitPortReleased(port: Int, timeoutMillis: Long) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (isPortOpen(port) && System.currentTimeMillis() < deadline) delay(POLL_MILLIS)
        check(!isPortOpen(port)) { "Loopback port $port remained open after Core stop" }
    }

    private fun httpGet(
        url: String,
        accept: String,
        webSessionCredential: StmCoreWebSessionCredential? = null,
        origin: String? = null,
    ): HttpEvidence {
        val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        return try {
            connection.connectTimeout = GATE4_HTTP_TIMEOUT_MILLIS
            connection.readTimeout = GATE4_HTTP_TIMEOUT_MILLIS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", accept)
            webSessionCredential?.let { credential ->
                connection.setRequestProperty(
                    "Cookie",
                    "$STM_CORE_WEB_SESSION_COOKIE_NAME=${credential.value}",
                )
            }
            origin?.let { connection.setRequestProperty("Origin", it) }
            val code = connection.responseCode
            val source = if (code in 200..399) connection.inputStream else connection.errorStream
            HttpEvidence(code, source?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun fingerprintTree(root: File): TreeFingerprint {
        check(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Fingerprint root is not a no-follow directory: $root"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var entries = 0
        var bytes = 0L
        Files.walk(root.toPath()).use { paths ->
            paths.sorted().forEach { path ->
                val relative = root.toPath().relativize(path)
                    .toString()
                    .replace(File.separatorChar, '/')
                    .ifBlank { "." }
                val kind = when {
                    Files.isSymbolicLink(path) -> "L"
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> "D"
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> "F"
                    else -> "O"
                }
                digest.update("$kind\u0000$relative\u0000".toByteArray())
                if (kind == "F") {
                    val size = Files.size(path)
                    bytes += size
                    digest.update("$size\u0000".toByteArray())
                    digest.update(sha256(path.toFile()).toByteArray())
                } else if (kind == "L") {
                    digest.update(Files.readSymbolicLink(path).toString().toByteArray())
                }
                entries += 1
            }
        }
        return TreeFingerprint(entries = entries, bytes = bytes, sha256 = digest.digest().toHex())
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte) }

    private suspend fun runFatalExperimentOnly(
        result: Bundle,
        experiment: StmCoreExperiment,
    ) {
        check(
            experiment == StmCoreExperiment.PROCESS_EXIT ||
                experiment == StmCoreExperiment.UNCAUGHT_EXCEPTION ||
                experiment == StmCoreExperiment.UNHANDLED_REJECTION,
        ) { "$experiment is not a fatal-process experiment" }
        val uiProcessId = Process.myPid()
        val application = targetContext.applicationContext as StmApplication
        val controller = onMain {
            application.container.stmCoreController as AndroidStmCoreController
        }
        awaitSettledCore(controller)
        val before = controller.state.value
        val beforePid = requireNotNull(before.processId)
        val outcome = runExperiment(experiment)
        when (outcome) {
            is ExperimentOutcome.Completed -> {
                result.putString("fatal_effect", "core_process_survived")
                outcome.result.values.forEach { (key, value) ->
                    result.putString("fatal_$key", value)
                }
            }

            is ExperimentOutcome.Disconnected -> {
                check(outcome.processId == beforePid) {
                    "Experiment service was not hosted by the current STM Core process"
                }
                check(Process.myPid() == uiProcessId) {
                    "The STM UI process died with the Core process"
                }
                val recovered = awaitCoreProcessReplacement(controller, beforePid)
                check(recovered.revision > before.revision) {
                    "The replacement Core did not advance its checkpoint revision"
                }
                result.putString("fatal_effect", "core_process_exited")
                result.putInt("fatal_previous_core_pid", beforePid)
                result.putInt("fatal_replacement_core_pid", requireNotNull(recovered.processId))
                result.putLong("fatal_recovered_revision", recovered.revision)
            }
        }
        result.putString("fatal_experiment", experiment.name)
        result.putInt("fatal_surviving_ui_process", uiProcessId)
    }

    private suspend fun startAndVerify(
        controller: AndroidStmCoreController,
        result: Bundle,
        label: String,
    ): RunningSession {
        check(controller.start() == StmCoreCommandResult.Accepted) {
            "STM Core start command was rejected from ${controller.state.value}"
        }
        val running = awaitState(controller) { it.runState == StmCoreRunState.RUNNING }
        val baseUrl = requireNotNull(running.localBaseUrl)
        val endpoint = URI(baseUrl)
        check(endpoint.host == "127.0.0.1" && endpoint.port in 1..65_535) {
            "STM Core did not publish a valid IPv4 loopback endpoint: $baseUrl"
        }
        val raw = rawHttp(endpoint)
        check(raw.statusLine.startsWith("HTTP/1.1 200")) {
            "Raw health response was not HTTP 200: ${raw.statusLine}"
        }
        check(raw.body == EXPECTED_HEALTH_BODY) {
            "Raw health body did not match: ${raw.body}"
        }
        check(raw.headers["content-length"]?.toIntOrNull() == raw.body.toByteArray().size) {
            "Raw health Content-Length did not match its body"
        }
        check(raw.headers["connection"]?.lowercase() == "close") {
            "Raw health response did not declare Connection: close"
        }

        val httpUrlConnectionEvidence = httpUrlConnectionEvidence(baseUrl)
        result.putString("${label}_http_url_connection", httpUrlConnectionEvidence)
        check(httpUrlConnectionEvidence.split(" | ").all { it.endsWith(":ok") }) {
            "HttpURLConnection did not accept the loopback response: $httpUrlConnectionEvidence"
        }
        result.putString("${label}_raw_status", raw.statusLine)
        result.putString("${label}_raw_headers", raw.headers.toSortedMap().toString())
        result.putInt("${label}_port", endpoint.port)
        result.putLong("${label}_revision", running.revision)
        result.putString("${label}_session", running.sessionId)
        return RunningSession(
            processId = requireNotNull(running.processId),
            sessionId = requireNotNull(running.sessionId),
            port = endpoint.port,
            baseUrl = baseUrl,
        )
    }

    private suspend fun stopAndVerify(controller: AndroidStmCoreController, port: Int) {
        check(controller.stop() == StmCoreCommandResult.Accepted) {
            "STM Core stop command was rejected"
        }
        awaitState(controller) { it.runState == StmCoreRunState.STOPPED }
        val deadline = System.currentTimeMillis() + STATE_TIMEOUT_MILLIS
        while (isPortOpen(port) && System.currentTimeMillis() < deadline) delay(POLL_MILLIS)
        check(!isPortOpen(port)) { "Loopback port $port remained open after Core stop" }
    }

    private fun verifyNodeSemantics(result: StmCoreExperimentResult) {
        val values = result.values
        check(values["process_survived"] == "true") { "Node semantics killed the Core process" }
        check(values["result"] != "java_exception") {
            "Node semantics raised ${values["exception_class"]}: ${values["exception_message"]}"
        }
        check(values["process_argv"]?.startsWith("[") == true) {
            "process.argv was not captured: ${values["process_argv"]}"
        }
        val cwd = values["cwd"]?.let(::File)?.canonicalFile
        val fixtureParent = values["absolute_fixture"]?.let(::File)?.parentFile?.canonicalFile
        check(cwd != null && cwd == fixtureParent) {
            "process.cwd() did not match the absolute experiment directory: $values"
        }
        check(values["absolute_read"] == "absolute-path-ok") {
            "Node did not read the absolute Java-created fixture"
        }
        check(values["code_generation"]?.contains("\"eval\":42") == true) {
            "Javet did not enable main-context eval(): ${values["code_generation"]}"
        }
        check(values["code_generation"]?.contains("\"function\":42") == true) {
            "Javet did not enable the main-context Function constructor: " +
                values["code_generation"]
        }
        check(values["code_generation"]?.contains("\"nativeFunction\":true") == true) {
            "The runtime replaced Node's native Function constructor: ${values["code_generation"]}"
        }
        check(values["module_result"]?.contains("\"result\":42") == true) {
            "Dynamic ESM / top-level await result was not 42: ${values["module_result"]}"
        }
        check(values["module_result"]?.contains("file:") == true) {
            "import.meta.url did not report a file URL: ${values["module_result"]}"
        }
    }

    private suspend fun runExperiment(
        experiment: StmCoreExperiment,
        timeoutMillis: Long = EXPERIMENT_TIMEOUT_MILLIS,
    ): ExperimentOutcome {
        val deferred = CompletableDeferred<ExperimentOutcome>()
        val teardown = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()
        lateinit var client: StmCoreExperimentClient
        val listener = object : StmCoreExperimentListener {
            private var processId: Int? = null

            override fun onExperimentServiceReady(processId: Int) {
                this.processId = processId
            }

            override fun onExperimentResult(result: StmCoreExperimentResult) {
                if (result.experiment == experiment) {
                    deferred.complete(ExperimentOutcome.Completed(result))
                } else {
                    deferred.completeExceptionally(
                        IllegalStateException(
                            "Expected $experiment but received ${result.experiment}",
                        ),
                    )
                }
            }

            override fun onExperimentTeardown(requestId: String) {
                teardown.complete(Unit)
            }

            override fun onExperimentServiceDisconnected() {
                disconnected.complete(Unit)
                deferred.complete(ExperimentOutcome.Disconnected(processId))
            }
        }
        onMain {
            client = StmCoreExperimentClient(targetContext, listener)
            check(client.run(experiment)) { "Could not bind the debug experiment service" }
        }
        var teardownWaitStarted = false

        suspend fun cancelAndAwaitTeardown() {
            teardownWaitStarted = true
            val cancellationAccepted = onMain { client.cancelPending() }
            check(cancellationAccepted || teardown.isCompleted || disconnected.isCompleted) {
                "Could not request $experiment teardown"
            }
            val teardownAcknowledged = withTimeoutOrNull(EXPERIMENT_TEARDOWN_TIMEOUT_MILLIS) {
                if (teardown.isCompleted) {
                    true
                } else if (disconnected.isCompleted) {
                    false
                } else {
                    select {
                        teardown.onAwait { true }
                        disconnected.onAwait { teardown.isCompleted }
                    }
                }
            }
            check(teardownAcknowledged != null) {
                "Timed out waiting for $experiment teardown acknowledgement"
            }
            check(teardownAcknowledged) {
                "Experiment service disconnected before $experiment teardown acknowledgement"
            }
        }

        var terminalOutcome: ExperimentOutcome? = null
        return try {
            withTimeout(timeoutMillis) { deferred.await() }.also { outcome ->
                terminalOutcome = outcome
                if (outcome is ExperimentOutcome.Completed && !outcome.result.teardownComplete) {
                    cancelAndAwaitTeardown()
                }
            }
        } finally {
            try {
                val teardownRequired = when (val outcome = terminalOutcome) {
                    is ExperimentOutcome.Completed -> !outcome.result.teardownComplete
                    is ExperimentOutcome.Disconnected -> false
                    null -> !disconnected.isCompleted
                }
                if (teardownRequired && !teardown.isCompleted && !teardownWaitStarted) {
                    cancelAndAwaitTeardown()
                }
            } finally {
                val canDisconnect =
                    disconnected.isCompleted ||
                        teardown.isCompleted ||
                        (terminalOutcome as? ExperimentOutcome.Completed)
                            ?.result
                            ?.teardownComplete == true
                if (canDisconnect) {
                    onMain { client.disconnect() }
                }
            }
        }
    }

    private suspend fun awaitSettledCore(
        controller: AndroidStmCoreController,
        timeoutMillis: Long = STATE_TIMEOUT_MILLIS,
    ): StmCoreState =
        awaitState(controller, timeoutMillis) { state ->
            state.revision > 0 && state.processId != null && state.installerRecoveryComplete &&
                (state.runState == StmCoreRunState.STOPPED ||
                    state.runState == StmCoreRunState.CRASHED)
        }

    private suspend fun awaitCoreProcessReplacement(
        controller: AndroidStmCoreController,
        previousProcessId: Int,
    ): StmCoreState = awaitState(controller) { state ->
        state.processId != null &&
            state.processId != previousProcessId &&
            state.installerRecoveryComplete
    }

    private suspend fun awaitState(
        controller: AndroidStmCoreController,
        timeoutMillis: Long = STATE_TIMEOUT_MILLIS,
        predicate: (StmCoreState) -> Boolean,
    ): StmCoreState {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!predicate(controller.state.value) && System.currentTimeMillis() < deadline) {
            delay(POLL_MILLIS)
        }
        check(predicate(controller.state.value)) {
            "Timed out waiting for STM Core state; current state is ${controller.state.value}"
        }
        return controller.state.value
    }

    private fun rawHttp(endpoint: URI): RawEvidence = rawHttpGet(
        endpoint = endpoint,
        path = "/health",
        host = "127.0.0.1:${endpoint.port}",
    )

    private fun rawHttpGet(
        endpoint: URI,
        path: String,
        host: String,
        cookie: String? = null,
    ): RawEvidence = Socket().use { socket ->
        socket.connect(InetSocketAddress(endpoint.host, endpoint.port), HTTP_TIMEOUT_MILLIS)
        socket.soTimeout = HTTP_TIMEOUT_MILLIS
        val request =
            "GET $path HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                "Connection: close\r\n" +
                "Accept: application/json\r\n" +
                cookie?.let { "Cookie: $it\r\n" }.orEmpty() +
                "\r\n"
        socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().flush()
        parseRawResponse(socket.getInputStream().readBytes())
    }

    private fun parseRawResponse(raw: ByteArray): RawEvidence {
        val text = raw.toString(Charsets.ISO_8859_1)
        val headerEnd = text.indexOf("\r\n\r\n")
        check(headerEnd >= 0) { "Raw HTTP response had no header terminator" }
        val lines = text.substring(0, headerEnd).split("\r\n")
        val headers = lines.drop(1).associate { line ->
            val separator = line.indexOf(':')
            check(separator > 0) { "Malformed raw HTTP header: $line" }
            line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
        }
        val bodyOffset = text.substring(0, headerEnd + 4).toByteArray(Charsets.ISO_8859_1).size
        return RawEvidence(
            statusLine = lines.first(),
            headers = headers,
            body = raw.copyOfRange(bodyOffset, raw.size).toString(Charsets.UTF_8),
        )
    }

    private fun httpUrlConnectionEvidence(baseUrl: String): String {
        val attempts = mutableListOf<String>()
        repeat(3) { index ->
            val outcome = runCatching {
                val connection = URL("$baseUrl/health")
                    .openConnection(Proxy.NO_PROXY) as HttpURLConnection
                try {
                    connection.connectTimeout = HTTP_TIMEOUT_MILLIS
                    connection.readTimeout = HTTP_TIMEOUT_MILLIS
                    connection.requestMethod = "GET"
                    val code = connection.responseCode
                    val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    check(code == 200 && body == EXPECTED_HEALTH_BODY) {
                        "HTTP $code body=$body"
                    }
                    "${index + 1}:ok"
                } finally {
                    connection.disconnect()
                }
            }.getOrElse { error ->
                "${index + 1}:failure:${error.javaClass.simpleName}:${error.message}"
            }
            attempts += outcome
        }
        return attempts.joinToString(" | ")
    }

    private fun isPortOpen(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 200)
        }
    }.isSuccess

    private fun <T> onMain(block: () -> T): T {
        val value = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        runOnMainSync {
            try {
                value.set(block())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw it }
        return value.get()
    }

    private data class RunningSession(
        val processId: Int,
        val sessionId: String,
        val port: Int,
        val baseUrl: String? = null,
        val webSessionCredential: StmCoreWebSessionCredential? = null,
    )

    private data class RawEvidence(
        val statusLine: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private data class HttpEvidence(
        val code: Int,
        val body: ByteArray,
    )

    private data class TreeFingerprint(
        val entries: Int,
        val bytes: Long,
        val sha256: String,
    )

    private sealed interface ExperimentOutcome {
        data class Completed(val result: StmCoreExperimentResult) : ExperimentOutcome

        data class Disconnected(val processId: Int?) : ExperimentOutcome
    }

    private companion object {
        const val GATE4_API31_SAVE_X = 928
        const val GATE4_API31_SAVE_Y = 2188
        const val STATE_TIMEOUT_MILLIS = 30_000L
        const val EXPERIMENT_TIMEOUT_MILLIS = 20_000L
        const val EXPERIMENT_TEARDOWN_TIMEOUT_MILLIS = 30_000L
        const val GATE3A_EXPERIMENT_TIMEOUT_MILLIS = 600_000L
        const val GATE3A_PERFORMANCE_TIMEOUT_MILLIS = 1_800_000L
        const val GATE3B_EXPERIMENT_TIMEOUT_MILLIS = 1_900_000L
        const val GATE3B_DEPENDENCY_INSTALL_BUDGET_MILLIS = 30L * 60L * 1_000L
        const val GATE3B_LOCAL_BUNDLE_BUILD_BUDGET_MILLIS = 15L * 60L * 1_000L
        const val GATE3B_ST_START_BUDGET_MILLIS = 4L * 60L * 1_000L
        const val GATE3B_POST_START_AND_TEARDOWN_BUDGET_MILLIS = 5L * 60L * 1_000L
        val GATE3B_RUNNABLE_EXPERIMENT_TIMEOUT_MILLIS = saturatingTimeoutBudgetMillis(
            GATE3B_DEPENDENCY_INSTALL_BUDGET_MILLIS,
            GATE3B_ST_START_BUDGET_MILLIS,
            GATE3B_POST_START_AND_TEARDOWN_BUDGET_MILLIS,
        )
        val GATE3B_LOCAL_BUNDLE_RUNNABLE_EXPERIMENT_TIMEOUT_MILLIS =
            saturatingTimeoutBudgetMillis(
                GATE3B_DEPENDENCY_INSTALL_BUDGET_MILLIS,
                GATE3B_LOCAL_BUNDLE_BUILD_BUDGET_MILLIS,
                GATE3B_ST_START_BUDGET_MILLIS,
                GATE3B_POST_START_AND_TEARDOWN_BUDGET_MILLIS,
            )
        const val GATE3B_INTERRUPTION_EXPERIMENT_TIMEOUT_MILLIS = 120_000L
        const val GATE3B_CANCELLATION_EXPERIMENT_TIMEOUT_MILLIS = 60_000L
        const val GATE3B_READY_SLOT_TIMEOUT_MILLIS = 900_000L
        const val GATE3B_READY_RECOVERY_TIMEOUT_MILLIS = 300_000L
        const val GATE4_STATE_TIMEOUT_MILLIS = 90_000L
        const val GATE4_STOP_TIMEOUT_MILLIS = 180_000L
        const val GATE4_HTTP_TIMEOUT_MILLIS = 10_000
        const val GATE4_WEBVIEW_TIMEOUT_MILLIS = 30_000L
        const val GATE4_WEBVIEW_JAVASCRIPT_TIMEOUT_MILLIS = 5_000L
        const val GATE4_FILE_CHOOSER_TIMEOUT_MILLIS = 30_000L
        const val GATE4_BLOB_SAF_TIMEOUT_MILLIS = 120_000L
        const val GATE4_UI_ACTION_TIMEOUT_MILLIS = 30_000L
        const val GATE4_UI_SCROLL_SETTLE_MILLIS = 700L
        const val GATE4_UI_TAP_SETTLE_MILLIS = 500L
        const val GATE4_SIGNED_SLOT_TIMEOUT_MILLIS = 900_000L
        const val GATE4_RENDERER_SNACKBAR_SETTLE_MILLIS = 500L
        const val GATE4_RELOAD_ACTION_X_FRACTION = 0.86f
        const val GATE4_RELOAD_ACTION_Y_FRACTION = 0.945f
        const val GATE4_RANGE_BYTES = 64
        const val GATE4_SSE_MINIMUM_GAP_MILLIS = 100L
        const val GATE4_PREFERRED_PORT = 8000
        const val GATE4_FAILED_UPGRADE_TIMEOUT_MILLIS = 90_000L
        const val GATE4_FAILED_UPGRADE_COMMIT =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HTTP_TIMEOUT_MILLIS = 2_000
        const val POLL_MILLIS = 50L
        const val GATE4_ST_VERSION = "1.18.0"
        const val GATE4_ST_COMMIT = "8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8"
        const val GATE4_REMOVABLE_SLOT_ID = "st-gate4-removable-8172dcd0ee67"
        const val GATE4_SOURCE_ARCHIVE_BYTES = 38_459_064L
        const val GATE3B_SOURCE_ARCHIVE_SHA256 =
            "92ce95bd95f277e73c8aa6efb57f34821136262076a756efd19ffbaa58773b03"
        const val GATE4_BUNDLE_SHA256 =
            "2d5fb1eedcbefe7062421e8ca54b90a23312f64df8d480c16538714c5157e0bf"
        const val GATE4_BUNDLE_BYTES = 1_947_206L
        const val GATE4_BUNDLE_LICENSE_SHA256 =
            "7d9c6fd5c043071752d853a02c63fbb9a7828157265ff1a90b75edaf6f5a9fc0"
        const val GATE4_BUNDLE_LICENSE_BYTES = 1_283L
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
        )
        val ACTIVE_JOB_STATES = setOf(
            StmCoreJobState.QUEUED,
            StmCoreJobState.RUNNING,
            StmCoreJobState.CANCELLING,
        )
        const val EXPECTED_HEALTH_BODY =
            "{\"status\":\"ok\",\"component\":\"stm-core\",\"engine\":\"feather\",\"version\":\"0.1.0\"}"
    }
}
