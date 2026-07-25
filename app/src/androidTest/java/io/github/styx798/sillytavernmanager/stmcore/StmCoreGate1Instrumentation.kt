package io.github.styx798.sillytavernmanager.stmcore

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import io.github.styx798.sillytavernmanager.app.StmApplication
import io.github.styx798.sillytavernmanager.core.stmcore.StmCoreCommandResult
import io.github.styx798.sillytavernmanager.data.stmcore.AndroidStmCoreController
import io.github.styx798.sillytavernmanager.stmcore.testing.StmCoreExperiment
import io.github.styx798.sillytavernmanager.stmcore.testing.StmCoreExperimentClient
import io.github.styx798.sillytavernmanager.stmcore.testing.StmCoreExperimentListener
import io.github.styx798.sillytavernmanager.stmcore.testing.StmCoreExperimentResult
import io.github.styx798.sillytavernmanager.stmcore.testing.saturatingTimeoutBudgetMillis
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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

                                "3b-runtime-image-obb" ->
                                    runGate3bRuntimeImageObbExperiment(result)

                                "3b-fault-matrix" ->
                                    runGate3bFaultMatrix(result)

                                "3b-protocol" ->
                                    runGate3bProtocolProbe(result)

                                "4-st-lifecycle" ->
                                    runGate4SillyTavernLifecycle(result)

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

    private suspend fun runGate3bRuntimeImageObbExperiment(result: Bundle) {
        requireGate3bRunnableCoreStopped()
        val outcome = runExperiment(
            StmCoreExperiment.GATE3B_RUNTIME_IMAGE_OBB,
            GATE3B_RUNTIME_IMAGE_TIMEOUT_MILLIS,
        )
        check(outcome is ExperimentOutcome.Completed) {
            "Runtime Image OBB experiment terminated the Core process"
        }
        outcome.result.values.forEach { (key, value) ->
            result.putString("gate3b_runtime_image_$key", value)
        }
        check(
            outcome.result.values["result"] == "passed" &&
                outcome.result.values["obb_read_only"] == "true" &&
                outcome.result.values["obb_tree_matches_extracted"] == "true" &&
                outcome.result.values["port_released"] == "true"
        ) {
            "Runtime Image OBB experiment failed: ${outcome.result.values}"
        }
    }

    private suspend fun runGate4SillyTavernLifecycle(result: Bundle) {
        val application = targetContext.applicationContext as StmApplication
        val controller = onMain {
            application.container.stmCoreController as AndroidStmCoreController
        }
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
        val target = settled.slots.singleOrNull { slot ->
            slot.id == GATE4_SLOT_ID &&
                slot.state == StmCoreSlotState.READY &&
                slot.artifact?.kind == StmCoreArtifactKind.SILLY_TAVERN_SOURCE
        } ?: error("Gate 4 READY SillyTavern slot is unavailable: ${settled.slots}")
        check(target.commitSha == GATE4_ST_COMMIT && target.artifact?.stVersion == GATE4_ST_VERSION) {
            "Gate 4 slot has unexpected upstream identity: $target"
        }
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
        result.putString("gate4_active_slot", active.slotId)
        result.putLong("gate4_active_revision", active.activeRevision)

        val first = startAndVerifySillyTavern(controller, target, "first", result)
        stopAndVerifySillyTavern(controller, first, target, result)
        val second = startAndVerifySillyTavern(controller, target, "second", result)
        stopAndVerifySillyTavern(controller, second, target, result)
        check(second.sessionId != first.sessionId) {
            "Gate 4 reused a closed Feather Engine session"
        }
        check(second.processId == first.processId) {
            "Gate 4 clean restart unexpectedly replaced the Core process"
        }
        check(second.port == first.port) {
            "Gate 4 did not preserve its preferred loopback origin: ${first.port} -> ${second.port}"
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

        val version = httpGet("$baseUrl/version", "application/json")
        check(version.code == 200 && version.body.toString(Charsets.UTF_8).contains(
            "\"pkgVersion\":\"$GATE4_ST_VERSION\"",
        )) {
            "Gate 4 $label /version failed: HTTP ${version.code}"
        }
        val home = httpGet("$baseUrl/", "text/html")
        check(home.code == 200 && home.body.isNotEmpty()) {
            "Gate 4 $label homepage failed: HTTP ${home.code}, bytes=${home.body.size}"
        }
        val bundle = httpGet("$baseUrl/lib.js", "application/javascript")
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
        return RunningSession(
            processId = requireNotNull(running.processId),
            sessionId = requireNotNull(running.sessionId),
            port = endpoint.port,
        )
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

    private fun httpGet(url: String, accept: String): HttpEvidence {
        val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        return try {
            connection.connectTimeout = GATE4_HTTP_TIMEOUT_MILLIS
            connection.readTimeout = GATE4_HTTP_TIMEOUT_MILLIS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", accept)
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

    private fun rawHttp(endpoint: URI): RawEvidence = Socket().use { socket ->
        socket.connect(InetSocketAddress(endpoint.host, endpoint.port), HTTP_TIMEOUT_MILLIS)
        socket.soTimeout = HTTP_TIMEOUT_MILLIS
        val request =
            "GET /health HTTP/1.1\r\n" +
                "Host: 127.0.0.1:${endpoint.port}\r\n" +
                "Connection: close\r\n" +
                "Accept: application/json\r\n\r\n"
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

    private sealed interface ExperimentOutcome {
        data class Completed(val result: StmCoreExperimentResult) : ExperimentOutcome

        data class Disconnected(val processId: Int?) : ExperimentOutcome
    }

    private companion object {
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
        const val GATE3B_RUNTIME_IMAGE_TIMEOUT_MILLIS = 900_000L
        const val GATE4_STATE_TIMEOUT_MILLIS = 90_000L
        const val GATE4_STOP_TIMEOUT_MILLIS = 180_000L
        const val GATE4_HTTP_TIMEOUT_MILLIS = 10_000
        const val HTTP_TIMEOUT_MILLIS = 2_000
        const val POLL_MILLIS = 50L
        const val GATE4_SLOT_ID = "st-1-18-0-stage3b-debug"
        const val GATE4_ST_VERSION = "1.18.0"
        const val GATE4_ST_COMMIT = "8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8"
        const val GATE3B_SOURCE_ARCHIVE_SHA256 =
            "92ce95bd95f277e73c8aa6efb57f34821136262076a756efd19ffbaa58773b03"
        const val GATE4_BUNDLE_SHA256 =
            "2d5fb1eedcbefe7062421e8ca54b90a23312f64df8d480c16538714c5157e0bf"
        const val GATE4_BUNDLE_BYTES = 1_947_206L
        const val GATE4_BUNDLE_LICENSE_SHA256 =
            "7d9c6fd5c043071752d853a02c63fbb9a7828157265ff1a90b75edaf6f5a9fc0"
        const val GATE4_BUNDLE_LICENSE_BYTES = 1_283L
        val ACTIVE_JOB_STATES = setOf(
            StmCoreJobState.QUEUED,
            StmCoreJobState.RUNNING,
            StmCoreJobState.CANCELLING,
        )
        const val EXPECTED_HEALTH_BODY =
            "{\"status\":\"ok\",\"component\":\"stm-core\",\"engine\":\"feather\",\"version\":\"0.1.0\"}"
    }
}
