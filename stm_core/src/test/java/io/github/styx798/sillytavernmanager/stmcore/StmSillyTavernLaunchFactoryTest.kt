package io.github.styx798.sillytavernmanager.stmcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class StmSillyTavernLaunchFactoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `real launch isolates mutable paths and installs runtime webpack hard gate`() {
        val fixture = fixture()
        val originalAdapter = fixture.adapter.readBytes()
        val webSessionCredential = StmCoreWebSessionCredential.generate()

        val prepared = StmSillyTavernLaunchFactory.prepare(
            slotRoot = fixture.slot,
            archiveRoot = ARCHIVE_ROOT,
            dataRoot = fixture.data,
            sessionDirectory = fixture.session,
            logsRoot = fixture.logs,
            expectedVersion = "1.18.0",
            webSessionCredential = webSessionCredential,
        )

        val arguments = prepared.launchSpec.consoleArguments.toList()
        assertEquals(fixture.program.resolve("server.js").absolutePath, arguments.first())
        assertArgument(arguments, "--dataRoot", fixture.data.absolutePath)
        assertArgument(arguments, "--configPath", fixture.data.resolve("config.yaml").absolutePath)
        assertArgument(arguments, "--listen", "false")
        assertArgument(arguments, "--browserLaunchEnabled", "false")
        assertArgument(arguments, "--enableIPv4", "true")
        assertArgument(arguments, "--enableIPv6", "false")
        assertTrue(prepared.selectedPort in 1..65_535)
        assertEquals(
            "git:\n  backend: builtin\n",
            fixture.data.resolve("config.yaml").readText(),
        )
        assertTrue(fixture.session.resolve("tmp").isDirectory)
        assertTrue(fixture.logs.resolve("sillytavern-node.log").isFile)
        assertEquals(originalAdapter.toList(), fixture.adapter.readBytes().toList())
        assertFalse(fixture.program.resolve("config.yaml").exists())

        val bootstrap = prepared.launchSpec.bootstrapScript
        assertTrue(bootstrap.contains("STM_PREBUILT_LIB_JS"))
        assertTrue(bootstrap.contains("Forbidden runtime Webpack module load"))
        assertTrue(bootstrap.contains("timingSafeEqual"))
        assertTrue(bootstrap.contains("/node_modules/webpack/"))
        assertTrue(bootstrap.contains("/node_modules/terser-webpack-plugin/"))
        assertTrue(bootstrap.contains(STM_CORE_WEB_SESSION_COOKIE_NAME))
        assertTrue(bootstrap.contains("requestIsAuthorized"))
        assertTrue(bootstrap.contains("403 Forbidden"))
        assertTrue(bootstrap.contains("event === 'upgrade'"))
        assertEquals("StmCoreWebSessionCredential([redacted])", webSessionCredential.toString())
    }

    @Test
    fun `existing user config is preserved`() {
        val fixture = fixture()
        fixture.data.mkdirs()
        val config = fixture.data.resolve("config.yaml").apply {
            writeText("port: 9123\n")
        }

        StmSillyTavernLaunchFactory.prepare(
            slotRoot = fixture.slot,
            archiveRoot = ARCHIVE_ROOT,
            dataRoot = fixture.data,
            sessionDirectory = fixture.session,
            logsRoot = fixture.logs,
            expectedVersion = "1.18.0",
            webSessionCredential = StmCoreWebSessionCredential.generate(),
        )

        assertEquals("port: 9123\n", config.readText())
    }

    @Test
    fun `symbolic config is rejected without touching its target`() {
        val fixture = fixture()
        fixture.data.mkdirs()
        val outside = temporaryFolder.newFile("outside-config.yaml").apply {
            writeText("sentinel\n")
        }
        Files.createSymbolicLink(
            fixture.data.resolve("config.yaml").toPath(),
            outside.toPath(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            StmSillyTavernLaunchFactory.prepare(
                slotRoot = fixture.slot,
                archiveRoot = ARCHIVE_ROOT,
                dataRoot = fixture.data,
                sessionDirectory = fixture.session,
                logsRoot = fixture.logs,
                expectedVersion = "1.18.0",
                webSessionCredential = StmCoreWebSessionCredential.generate(),
            )
        }
        assertEquals("sentinel\n", outside.readText())
    }

    private fun fixture(): Fixture {
        val root = temporaryFolder.newFolder()
        val slot = root.resolve("slot").apply { mkdir() }
        val program = slot.resolve(ARCHIVE_ROOT).apply { mkdir() }
        program.resolve("server.js").writeText("export {};\n")
        program.resolve("webpack.config.js").writeText("export default {};\n")
        program.resolve("default").mkdir()
        program.resolve("default/config.yaml").writeText("git:\n  backend: auto\n")
        program.resolve("src").mkdir()
        program.resolve("src/middleware").mkdir()
        val adapter = program.resolve("src/middleware/webpack-serve.js").apply {
            writeText("export default function adapter() {};\n")
        }
        slot.resolve(".stm-runtime").mkdir()
        slot.resolve(".stm-runtime/webpack-serve.adapter.js").writeBytes(adapter.readBytes())
        slot.resolve(".stm-runtime/lib.js").writeText("const marker = true;\n")
        return Fixture(
            slot = slot,
            program = program,
            data = root.resolve("data"),
            session = root.resolve("session"),
            logs = root.resolve("logs"),
            adapter = adapter,
        )
    }

    private fun assertArgument(arguments: List<String>, name: String, expected: String) {
        val index = arguments.indexOf(name)
        assertTrue("$name is missing from $arguments", index >= 0)
        assertEquals(expected, arguments[index + 1])
    }

    private data class Fixture(
        val slot: java.io.File,
        val program: java.io.File,
        val data: java.io.File,
        val session: java.io.File,
        val logs: java.io.File,
        val adapter: java.io.File,
    )

    private companion object {
        const val ARCHIVE_ROOT = "SillyTavern-fixed-commit"
    }
}
