package io.github.styx798.sillytavernmanager.core.instances

import io.github.styx798.sillytavernmanager.core.downloads.*
import io.github.styx798.sillytavernmanager.core.logging.LogRepository
import io.github.styx798.sillytavernmanager.core.stmcore.*
import io.github.styx798.sillytavernmanager.stmcore.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StInstanceInstallCoordinatorTest {
    @Test
    fun `local fallback is offered only for transport or missing runtime`() {
        val failed = StInstanceInstallState(
            phase = StInstanceInstallPhase.FAILED,
            failure = StInstanceInstallFailure.INSTALL_FAILED,
            installMode = StmCoreInstallMode.FAST_SIGNED_RUNTIME,
        )
        listOf("PREBUILT_RUNTIME_TRANSPORT_UNAVAILABLE", "PREBUILT_RUNTIME_NOT_AVAILABLE").forEach {
            assertTrue(failed.copy(failureCode = it).canChooseLocalBuild)
        }
        listOf("SIGNATURE_INVALID", "HASH_MISMATCH", "BINDING_MISMATCH", "CANCELLED", null).forEach {
            assertFalse(failed.copy(failureCode = it).canChooseLocalBuild)
            assertFalse(failed.copy(failureCode = it).canRetry)
        }
    }

    @Test
    fun `archive installation persists the exact requested identity in an instance`() = runTest {
        val core = MutableStateFlow(StmCoreState())
        val registry = MutableStateFlow(StInstanceState())
        val downloads = MutableStateFlow(StDownloadState())
        var requestedCommit: String? = null
        val repository = proxy<StInstanceRepository> { name, args ->
            when (name) {
                "getState" -> registry
                "beginInstall" -> {
                    registry.value = registry.value.copy(pendingInstall = args[0] as StPendingInstanceInstall)
                    Unit
                }
                else -> Unit
            }
        }
        val controller = proxy<StmCoreController> { name, _ ->
            when (name) {
                "getState" -> core
                else -> StmCoreCommandResult.Accepted
            }
        }
        val downloadRepository = proxy<StDownloadRepository> { name, args ->
            when (name) {
                "getState" -> downloads
                "start" -> { requestedCommit = args[1] as String?; Unit }
                else -> Unit
            }
        }
        val coordinator = StInstanceInstallCoordinator(
            backgroundScope, downloadRepository, repository, controller,
            proxy<LogRepository> { _, _ -> Unit },
        )
        coordinator.install("My instance", StDownloadChannel.PREVIEW,
            StmCoreInstallMode.LOCAL_NPM_BUILD, "a".repeat(40))
        runCurrent()
        val pending = requireNotNull(registry.value.pendingInstall)
        assertEquals("My instance", pending.displayName)
        assertEquals("st-${pending.instanceId}", pending.slotId)
        assertEquals("a".repeat(40), requestedCommit)
        assertEquals(StmCoreInstallMode.LOCAL_NPM_BUILD, pending.installMode)
        coordinator.retry(true)
        assertEquals(pending, registry.value.pendingInstall)
    }

    @Test
    fun `confirmed fallback starts a new instance operation and does not replay the failed job`() = runTest {
        val commit = "b".repeat(40)
        val core = MutableStateFlow(StmCoreState())
        val registry = MutableStateFlow(StInstanceState())
        val archive = DownloadedStArchive(
            StDownloadChannel.STABLE, "source.zip", 1,
            identity = StArchiveIdentity(StArchiveIdentityClassification.EXACT_COMMIT,
                channelRef = "release", exactCommit = commit),
        )
        val downloads = MutableStateFlow(StDownloadState(archives = listOf(archive)))
        val submissions = mutableListOf<Pair<String, StmCoreInstallMode>>()
        val repository = proxy<StInstanceRepository> { name, args ->
            when (name) {
                "getState" -> registry
                "beginInstall" -> {
                    check(registry.value.pendingInstall == null)
                    registry.value = registry.value.copy(pendingInstall = args[0] as StPendingInstanceInstall)
                    Unit
                }
                "clearPendingInstall" -> { registry.value = registry.value.copy(pendingInstall = null); Unit }
                else -> Unit
            }
        }
        val controller = proxy<StmCoreController> { name, args ->
            when (name) {
                "getState" -> core
                "installDownloadedArchive" -> {
                    submissions += (args[0] as String) to (args[2] as StmCoreInstallMode)
                    StmCoreCommandResult.Accepted
                }
                else -> StmCoreCommandResult.Accepted
            }
        }
        val coordinator = StInstanceInstallCoordinator(backgroundScope,
            proxy<StDownloadRepository> { name, _ -> if (name == "getState") downloads else Unit },
            repository, controller, proxy<LogRepository> { _, _ -> Unit })
        coordinator.install("Home", exactCommit = commit)
        runCurrent()
        val first = submissions.single().first
        core.value = StmCoreState(jobs = listOf(StmCoreJob(
            operationId = "failed-operation", type = StmCoreJobType.INSTALL, targetId = first,
            phase = StmCoreJobPhase.PREFLIGHT, state = StmCoreJobState.FAILED,
            startedAtEpochMs = 1, updatedAtEpochMs = 2,
            error = StmCoreError(domain = "installer", code = "PREBUILT_RUNTIME_TRANSPORT_UNAVAILABLE",
                summary = "Offline"),
        )))
        runCurrent()
        assertTrue(coordinator.state.value.canChooseLocalBuild)
        assertEquals(1, submissions.size)
        assertEquals(first, registry.value.pendingInstall?.slotId)
        coordinator.retry(localBuildConfirmed = true)
        runCurrent()
        assertEquals(2, submissions.size)
        assertNotEquals(first, submissions.last().first)
        assertEquals(StmCoreInstallMode.LOCAL_NPM_BUILD, submissions.last().second)
        assertEquals(StInstanceInstallPhase.INSTALLING, coordinator.state.value.phase)
        assertEquals(commit, registry.value.pendingInstall?.expectedCommitSha)
    }

    private inline fun <reified T> proxy(crossinline handle: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            handle(method.name.substringBefore('-'), args ?: emptyArray())
        } as T
}
