package io.github.styx798.sillytavernmanager.stmcore.testing

import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StmCoreGate3bRunnableWorkspaceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `assembler installs one fixed adapter and bundle outside candidate dependencies`() {
        val fixture = fixture()

        val evidence = Gate3bRunnableWorkspaceAssembler.assemble(
            payloadRoot = fixture.payload.toPath(),
            programRoot = fixture.program.toPath(),
            adapterSource = fixture.adapter.toPath(),
            bundleSource = fixture.bundle.toPath(),
            bundleLicenseSource = fixture.bundleLicense.toPath(),
            expectedAdapterSha256 = sha256(fixture.adapter.readBytes()),
            expectedBundleSha256 = sha256(fixture.bundle.readBytes()),
            expectedBundleBytes = fixture.bundle.length(),
            expectedBundleLicenseSha256 = sha256(fixture.bundleLicense.readBytes()),
            expectedBundleLicenseBytes = fixture.bundleLicense.length(),
        )

        assertArrayEquals(fixture.adapter.readBytes(), fixture.targetAdapter.readBytes())
        assertArrayEquals(
            fixture.adapter.readBytes(),
            fixture.payload.resolve(".stm-runtime/webpack-serve.adapter.js").readBytes(),
        )
        assertArrayEquals(
            fixture.bundle.readBytes(),
            fixture.payload.resolve(".stm-runtime/lib.js").readBytes(),
        )
        assertArrayEquals(
            fixture.bundleLicense.readBytes(),
            fixture.payload.resolve(".stm-runtime/lib.js.LICENSE.txt").readBytes(),
        )
        assertEquals(sha256(fixture.adapter.readBytes()), evidence.adapterSha256)
        assertEquals(sha256(fixture.bundle.readBytes()), evidence.bundleSha256)
        assertEquals(fixture.bundle.length(), evidence.bundleBytes)
    }

    @Test
    fun `preexisting runtime directory is rejected before source replacement`() {
        val fixture = fixture()
        val original = fixture.targetAdapter.readBytes()
        Files.createDirectory(fixture.payload.resolve(".stm-runtime").toPath())

        assertThrows(IllegalStateException::class.java) {
            Gate3bRunnableWorkspaceAssembler.assemble(
                payloadRoot = fixture.payload.toPath(),
                programRoot = fixture.program.toPath(),
                adapterSource = fixture.adapter.toPath(),
                bundleSource = fixture.bundle.toPath(),
                bundleLicenseSource = fixture.bundleLicense.toPath(),
                expectedAdapterSha256 = sha256(fixture.adapter.readBytes()),
                expectedBundleSha256 = sha256(fixture.bundle.readBytes()),
                expectedBundleBytes = fixture.bundle.length(),
                expectedBundleLicenseSha256 = sha256(fixture.bundleLicense.readBytes()),
                expectedBundleLicenseBytes = fixture.bundleLicense.length(),
            )
        }
        assertArrayEquals(original, fixture.targetAdapter.readBytes())
    }

    @Test
    fun `assembler preserves local build provenance beside generated bundle`() {
        val fixture = fixture()
        val provenance = requireNotNull(fixture.payload.parentFile)
            .resolve("local-build.provenance.stm").apply {
            writeText("format_version=1\nprovenance_kind=device-local-upstream-build\n")
        }

        val evidence = Gate3bRunnableWorkspaceAssembler.assemble(
            payloadRoot = fixture.payload.toPath(),
            programRoot = fixture.program.toPath(),
            adapterSource = fixture.adapter.toPath(),
            bundleSource = fixture.bundle.toPath(),
            bundleLicenseSource = fixture.bundleLicense.toPath(),
            expectedAdapterSha256 = sha256(fixture.adapter.readBytes()),
            expectedBundleSha256 = sha256(fixture.bundle.readBytes()),
            expectedBundleBytes = fixture.bundle.length(),
            expectedBundleLicenseSha256 = sha256(fixture.bundleLicense.readBytes()),
            expectedBundleLicenseBytes = fixture.bundleLicense.length(),
            provenanceSource = provenance.toPath(),
        )

        val installed = fixture.payload.resolve(".stm-runtime/local-build.provenance.stm")
        assertArrayEquals(provenance.readBytes(), installed.readBytes())
        assertEquals(sha256(provenance.readBytes()), evidence.provenanceSha256)
        assertEquals(provenance.length(), evidence.provenanceBytes)
    }

    @Test
    fun `local bundle output requires one exact cache directory and cleans only build tree`() {
        val fixture = localBundleFixture()
        val distExistedBefore = Gate3bLocalBundleOutputInspector.requireFresh(
            fixture.program.toPath(),
        )
        assertFalse(distExistedBefore)
        val outputRoot = fixture.program.resolve("dist/_webpack/0123456789abcdef/output")
        assertTrue(outputRoot.mkdirs())
        outputRoot.resolve("lib.js").writeText("export const local = true;\n")
        outputRoot.resolve("lib.js.LICENSE.txt").writeText("Local license fixture\n")
        requireNotNull(outputRoot.parentFile).resolve("cache").mkdir()

        val output = Gate3bLocalBundleOutputInspector.inspect(
            fixture.program.toPath(),
            distExistedBefore,
        )

        assertEquals("0123456789abcdef", output.cacheVersion)
        assertEquals(sha256(output.bundle.toFile().readBytes()), output.bundleSha256)
        Gate3bLocalBundleOutputInspector.removeBuildTree(output)
        assertFalse(fixture.program.resolve("dist/_webpack").exists())
        assertFalse(fixture.program.resolve("dist").exists())
    }

    @Test
    fun `local bundle output rejects ambiguous cache versions`() {
        val fixture = localBundleFixture()
        val distExistedBefore = Gate3bLocalBundleOutputInspector.requireFresh(
            fixture.program.toPath(),
        )
        listOf("0123456789abcdef", "fedcba9876543210").forEach { version ->
            val output = fixture.program.resolve("dist/_webpack/$version/output")
            assertTrue(output.mkdirs())
            output.resolve("lib.js").writeText("bundle\n")
            output.resolve("lib.js.LICENSE.txt").writeText("license\n")
        }

        assertThrows(IllegalStateException::class.java) {
            Gate3bLocalBundleOutputInspector.inspect(
                fixture.program.toPath(),
                distExistedBefore,
            )
        }
    }

    @Test
    fun `local bundle output rejects missing license`() {
        val fixture = localBundleFixture()
        val distExistedBefore = Gate3bLocalBundleOutputInspector.requireFresh(
            fixture.program.toPath(),
        )
        val output = fixture.program.resolve("dist/_webpack/0123456789abcdef/output")
        assertTrue(output.mkdirs())
        output.resolve("lib.js").writeText("bundle\n")

        assertThrows(IllegalStateException::class.java) {
            Gate3bLocalBundleOutputInspector.inspect(
                fixture.program.toPath(),
                distExistedBefore,
            )
        }
    }

    @Test
    fun `local bundle output rejects a dist symlink before webpack starts`() {
        val fixture = localBundleFixture()
        val externalDist = temporaryFolder.newFolder("external-dist")
        Files.createSymbolicLink(
            fixture.program.resolve("dist").toPath(),
            externalDist.toPath(),
        )

        assertThrows(IllegalStateException::class.java) {
            Gate3bLocalBundleOutputInspector.requireFresh(fixture.program.toPath())
        }
    }

    @Test
    fun `webpack compiler evidence requires one error free lib asset callback`() {
        requireSuccessfulGate3bWebpackCompiler(
            runCalls = 1,
            callbackCalls = 1,
            errorAbsent = true,
            statsPresent = true,
            statsHasErrors = false,
            statsErrorCount = 0,
            libAssetPresent = true,
            closeCalls = 1,
            closeCallbackCalls = 1,
            closeErrorAbsent = true,
            closeSameInstance = true,
        )

        val invalidEvidence = listOf(
            listOf(0, 1, 1, 1, 0, 0, 1, 1, 1, 1, 1),
            listOf(1, 0, 1, 1, 0, 0, 1, 1, 1, 1, 1),
            listOf(1, 1, 0, 1, 0, 0, 1, 1, 1, 1, 1),
            listOf(1, 1, 1, 0, 0, 0, 1, 1, 1, 1, 1),
            listOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
            listOf(1, 1, 1, 1, 0, 0, 0, 1, 1, 1, 1),
            listOf(1, 1, 1, 1, 0, 0, 1, 0, 1, 1, 1),
            listOf(1, 1, 1, 1, 0, 0, 1, 1, 0, 1, 1),
            listOf(1, 1, 1, 1, 0, 0, 1, 1, 1, 0, 1),
            listOf(1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 0),
        )
        invalidEvidence.forEach { evidence ->
            assertThrows(IllegalStateException::class.java) {
                requireSuccessfulGate3bWebpackCompiler(
                    runCalls = evidence[0],
                    callbackCalls = evidence[1],
                    errorAbsent = evidence[2] == 1,
                    statsPresent = evidence[3] == 1,
                    statsHasErrors = evidence[4] == 1,
                    statsErrorCount = evidence[5],
                    libAssetPresent = evidence[6] == 1,
                    closeCalls = evidence[7],
                    closeCallbackCalls = evidence[8],
                    closeErrorAbsent = evidence[9] == 1,
                    closeSameInstance = evidence[10] == 1,
                )
            }
        }
    }

    @Test
    fun `bundle watchdog terminates once from its own thread after timeout`() {
        val testThread = Thread.currentThread()
        val terminationThread = AtomicReference<Thread>()
        val terminationCalls = AtomicInteger(0)
        val terminationObserved = CountDownLatch(1)
        val terminationGate = Gate3bBundleTerminationGate {
            terminationThread.set(Thread.currentThread())
            terminationCalls.incrementAndGet()
            terminationObserved.countDown()
        }
        val watchdog = Gate3bBundleBuildWatchdog(
            timeoutMillis = 100,
            cancelled = { false },
            terminationGate = terminationGate,
        )

        assertTrue(watchdog.start())
        assertFalse(watchdog.start())
        assertTrue(terminationObserved.await(2, TimeUnit.SECONDS))
        watchdog.close()
        watchdog.close()
        terminationGate.close()

        assertTrue(watchdog.hasTimedOut())
        assertTrue(watchdog.isStopped())
        assertTrue(terminationGate.wasRequested())
        assertEquals(1, terminationCalls.get())
        assertFalse(terminationThread.get() === testThread)
    }

    @Test
    fun `closing bundle watchdog twice prevents a later timeout`() {
        val terminationCalls = AtomicInteger(0)
        val terminationGate = Gate3bBundleTerminationGate {
            terminationCalls.incrementAndGet()
        }
        val watchdog = Gate3bBundleBuildWatchdog(
            timeoutMillis = 10_000,
            cancelled = { false },
            terminationGate = terminationGate,
        )

        assertTrue(watchdog.start())
        watchdog.close()
        watchdog.close()

        assertTrue(watchdog.isStopped())
        assertFalse(watchdog.hasTimedOut())
        assertFalse(terminationGate.wasRequested())
        assertEquals(0, terminationCalls.get())
    }

    @Test
    fun `concurrent bundle termination requests execute only once`() {
        val terminationCalls = AtomicInteger(0)
        val successfulRequests = AtomicInteger(0)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(16)
        val terminationGate = Gate3bBundleTerminationGate {
            terminationCalls.incrementAndGet()
        }
        val workers = List(16) { index ->
            Thread(
                {
                    start.await()
                    if (terminationGate.request()) successfulRequests.incrementAndGet()
                    finished.countDown()
                },
                "gate3b-termination-test-$index",
            ).apply {
                isDaemon = true
                start()
            }
        }

        start.countDown()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        workers.forEach { worker -> worker.join(2_000) }
        terminationGate.close()

        assertEquals(1, successfulRequests.get())
        assertEquals(1, terminationCalls.get())
    }

    @Test
    fun `closing bundle termination gate waits for active request and rejects later requests`() {
        val terminationCalls = AtomicInteger(0)
        val terminationStarted = CountDownLatch(1)
        val allowTerminationToFinish = CountDownLatch(1)
        val requestFinished = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val terminationGate = Gate3bBundleTerminationGate {
            terminationCalls.incrementAndGet()
            terminationStarted.countDown()
            allowTerminationToFinish.await()
        }
        val requester = Thread(
            {
                try {
                    terminationGate.request()
                } finally {
                    requestFinished.countDown()
                }
            },
            "gate3b-termination-request-test",
        ).apply {
            isDaemon = true
            start()
        }

        assertTrue(terminationStarted.await(2, TimeUnit.SECONDS))
        val closer = Thread(
            {
                closeStarted.countDown()
                terminationGate.close()
                closeFinished.countDown()
            },
            "gate3b-termination-close-test",
        ).apply {
            isDaemon = true
            start()
        }

        try {
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
            assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS))
        } finally {
            allowTerminationToFinish.countDown()
        }
        assertTrue(requestFinished.await(2, TimeUnit.SECONDS))
        assertTrue(closeFinished.await(2, TimeUnit.SECONDS))
        requester.join(2_000)
        closer.join(2_000)

        terminationGate.close()
        assertFalse(terminationGate.request())
        assertEquals(1, terminationCalls.get())
    }

    @Test
    fun `late cancellation rejects an otherwise successful result`() {
        val cancelled = AtomicBoolean(false)
        requireGate3bNotCancelled(cancelled, "fixture acceptance")

        cancelled.set(true)

        assertThrows(IllegalStateException::class.java) {
            requireGate3bNotCancelled(cancelled, "fixture acceptance")
        }
    }

    @Test
    fun `candidate dependencies move from isolated work root after source identity check`() {
        val fixture = fixture()
        val work = temporaryFolder.newFolder("candidate-work")
        val dependencies = work.resolve("node_modules").apply { mkdir() }
        dependencies.resolve("package").mkdir()
        dependencies.resolve("package/index.js").writeText("module.exports = 42;\n")
        val programBefore = Gate3bTreeScanner.scan(
            fixture.program.toPath(),
            includeManifest = false,
        ).fingerprint
        val dependenciesBefore = Gate3bTreeScanner.scan(
            dependencies.toPath(),
            includeManifest = false,
        ).fingerprint

        Gate3bRunnableWorkspaceAssembler.attachCandidateDependencies(
            workRoot = work.toPath(),
            programRoot = fixture.program.toPath(),
            expectedProgramFingerprint = programBefore,
            expectedDependencyFingerprint = dependenciesBefore,
        )

        assertFalse(dependencies.exists())
        assertTrue(fixture.program.resolve("node_modules/package/index.js").isFile)
        assertEquals(
            dependenciesBefore,
            Gate3bTreeScanner.scan(
                fixture.program.resolve("node_modules").toPath(),
                includeManifest = false,
            ).fingerprint,
        )
    }

    @Test
    fun `source mutation is rejected before candidate dependencies are attached`() {
        val fixture = fixture()
        val work = temporaryFolder.newFolder("mutated-source-work")
        val dependencies = work.resolve("node_modules").apply { mkdir() }
        dependencies.resolve("index.js").writeText("module.exports = true;\n")
        val programBefore = Gate3bTreeScanner.scan(
            fixture.program.toPath(),
            includeManifest = false,
        ).fingerprint
        val dependenciesBefore = Gate3bTreeScanner.scan(
            dependencies.toPath(),
            includeManifest = false,
        ).fingerprint
        fixture.program.resolve("unexpected.txt").writeText("installer must not reach this root\n")

        assertThrows(IllegalStateException::class.java) {
            Gate3bRunnableWorkspaceAssembler.attachCandidateDependencies(
                workRoot = work.toPath(),
                programRoot = fixture.program.toPath(),
                expectedProgramFingerprint = programBefore,
                expectedDependencyFingerprint = dependenciesBefore,
            )
        }
        assertTrue(dependencies.isDirectory)
        assertFalse(fixture.program.resolve("node_modules").exists())
    }

    @Test
    fun `workspace remains retained until engine teardown and all port releases complete`() {
        val lifetime = Gate3bRunnableWorkspaceLifetime()

        assertTrue(lifetime.isDeletionSafe())
        lifetime.markEngineStarted()
        assertFalse(lifetime.isDeletionSafe())
        lifetime.markTeardownCompleted(
            engineDestroyed = true,
            portReleased = false,
            processCwdRestored = true,
        )
        assertFalse(lifetime.isDeletionSafe())
        lifetime.markTeardownCompleted(
            engineDestroyed = false,
            portReleased = true,
            processCwdRestored = true,
        )
        assertFalse(lifetime.isDeletionSafe())
        lifetime.markTeardownCompleted(
            engineDestroyed = true,
            portReleased = true,
            processCwdRestored = false,
        )
        assertFalse(lifetime.isDeletionSafe())
        lifetime.markTeardownCompleted(
            engineDestroyed = true,
            portReleased = true,
            processCwdRestored = true,
        )
        assertTrue(lifetime.isDeletionSafe())
        lifetime.markTeardownCompleted(
            engineDestroyed = true,
            portReleased = true,
            processCwdRestored = true,
        )
        assertTrue(lifetime.isDeletionSafe())
    }

    @Test
    fun `workspace remains retained while the fresh local build runtime is open`() {
        val lifetime = Gate3bRunnableWorkspaceLifetime()

        assertTrue(lifetime.isDeletionSafe())
        lifetime.markBuildRuntimeCreated()
        assertFalse(lifetime.isDeletionSafe())
        lifetime.markBuildRuntimeClosed(false)
        assertFalse(lifetime.isDeletionSafe())
        lifetime.markBuildRuntimeClosed(true)
        assertTrue(lifetime.isDeletionSafe())
    }

    @Test
    fun `teardown ports include every valid observation in priority order`() {
        assertEquals(
            listOf(8001, 8002, 8003),
            selectGate3bTeardownPorts(8001, 8002, 8003),
        )
        assertEquals(
            listOf(8002, 8003),
            selectGate3bTeardownPorts(0, 8002, 8003),
        )
        assertEquals(
            listOf(8003),
            selectGate3bTeardownPorts(0, 0, 8003),
        )
        assertEquals(8001, selectGate3bTeardownPort(8001, 8002, 8003))
        assertEquals(8002, selectGate3bTeardownPort(0, 8002, 8003))
        assertEquals(8003, selectGate3bTeardownPort(0, 0, 8003))
    }

    @Test
    fun `teardown ports discard invalid observations and deduplicate aliases`() {
        assertEquals(
            listOf(8001, 8002),
            selectGate3bTeardownPorts(8001, 8001, 8002),
        )
        assertEquals(
            listOf(8001),
            selectGate3bTeardownPorts(-1, 8001, 8001),
        )
        assertEquals(
            emptyList<Int>(),
            selectGate3bTeardownPorts(0, -1, 65_536),
        )
        assertThrows(IllegalArgumentException::class.java) {
            selectGate3bTeardownPort(0, -1, 65_536)
        }
    }

    @Test
    fun `installer workspace remains retained until runtime and process state are released`() {
        val lifetime = Gate3bInstallerWorkspaceLifetime()

        assertTrue(lifetime.isDeletionSafe())
        lifetime.markRuntimeCreated()
        assertFalse(lifetime.isDeletionSafe())
        lifetime.markRuntimeClosed(true)
        assertFalse(lifetime.isDeletionSafe())
        lifetime.markRuntimeClosed(false)
        lifetime.markProcessStateRestored(true)
        assertFalse(lifetime.isDeletionSafe())
        lifetime.markRuntimeClosed(true)
        assertTrue(lifetime.isDeletionSafe())

        lifetime.markRuntimeCreated()
        assertFalse(lifetime.isDeletionSafe())
        lifetime.markProcessStateRestored(true)
        lifetime.markRuntimeClosed(true)
        assertTrue(lifetime.isDeletionSafe())
    }

    @Test
    fun `cleanup retains a real workspace for every unresolved owner`() {
        listOf(
            Triple(false, true, "installer_runtime_or_process_state_not_released"),
            Triple(true, false, "runnable_runtime_port_or_cwd_not_released"),
        ).forEachIndexed { index, (installerSafe, runnableSafe, reason) ->
            val parent = temporaryFolder.newFolder("retained-parent-$index")
            val root = parent.resolve("work").apply { mkdir() }
            root.resolve("sentinel.txt").writeText("retain\n")

            val result = cleanupGate3bExperimentWorkspace(
                root = root.toPath(),
                ownedParent = parent.toPath(),
                installerDeletionSafe = installerSafe,
                runnableDeletionSafe = runnableSafe,
                currentProcessCwd = parent.toPath(),
            )

            assertTrue(result.contains(reason))
            assertTrue(root.resolve("sentinel.txt").isFile)
        }

        val cwdParent = temporaryFolder.newFolder("cwd-retained-parent")
        val cwdRoot = cwdParent.resolve("work").apply { mkdir() }
        val cwd = cwdRoot.resolve("nested").apply { mkdir() }
        cwdRoot.resolve("sentinel.txt").writeText("retain\n")
        val cwdResult = cleanupGate3bExperimentWorkspace(
            root = cwdRoot.toPath(),
            ownedParent = cwdParent.toPath(),
            installerDeletionSafe = true,
            runnableDeletionSafe = true,
            currentProcessCwd = cwd.toPath(),
        )
        assertTrue(cwdResult.contains("process_cwd_inside_experiment_root"))
        assertTrue(cwdRoot.resolve("sentinel.txt").isFile)
    }

    @Test
    fun `cleanup removes a real workspace only after every owner releases it`() {
        val parent = temporaryFolder.newFolder("removed-parent")
        val root = parent.resolve("work").apply { mkdir() }
        root.resolve("sentinel.txt").writeText("remove\n")

        val result = cleanupGate3bExperimentWorkspace(
            root = root.toPath(),
            ownedParent = parent.toPath(),
            installerDeletionSafe = true,
            runnableDeletionSafe = true,
            currentProcessCwd = parent.toPath(),
        )

        assertEquals("removed", result)
        assertFalse(root.exists())
    }

    private fun fixture(): Fixture {
        val root = temporaryFolder.newFolder()
        val payload = root.resolve("payload").apply { mkdir() }
        val program = payload.resolve("SillyTavern-fixed").apply { mkdir() }
        program.resolve("src").mkdir()
        program.resolve("src/middleware").mkdir()
        val targetAdapter = program.resolve("src/middleware/webpack-serve.js").apply {
            writeText("export default function upstream() {}\n")
        }
        val supply = root.resolve("supply").apply { mkdir() }
        val adapter = supply.resolve("webpack-serve.adapter.js").apply {
            writeText("export default function fixedAdapter() {}\n")
        }
        val bundle = supply.resolve("lib.js").apply {
            writeText("const fixedBundle = true;\n")
        }
        val bundleLicense = supply.resolve("lib.js.LICENSE.txt").apply {
            writeText("Fixed bundle license fixture\n")
        }
        return Fixture(payload, program, targetAdapter, adapter, bundle, bundleLicense)
    }

    private fun localBundleFixture(): LocalBundleFixture {
        val root = temporaryFolder.newFolder()
        val program = root.resolve("program").apply { mkdir() }
        program.resolve("public").mkdir()
        program.resolve("public/lib.js").writeText("export const source = true;\n")
        program.resolve("webpack.config.js").writeText("export default {};\n")
        program.resolve("docker").mkdir()
        program.resolve("docker/build-lib.js").writeText("export default true;\n")
        program.resolve("package-lock.json").writeText("{}\n")
        assertTrue(program.resolve("node_modules/webpack").mkdirs())
        program.resolve("node_modules/webpack/package.json").writeText(
            "{\"name\":\"webpack\",\"version\":\"5.105.4\"}\n",
        )
        return LocalBundleFixture(program)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private data class Fixture(
        val payload: java.io.File,
        val program: java.io.File,
        val targetAdapter: java.io.File,
        val adapter: java.io.File,
        val bundle: java.io.File,
        val bundleLicense: java.io.File,
    )

    private data class LocalBundleFixture(val program: java.io.File)
}
