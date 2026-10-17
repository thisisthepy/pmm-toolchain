package org.thisisthepy.python.multiplatform.toolchain.devagent

import org.thisisthepy.python.multiplatform.toolchain.dsl.BuildTypesContainer
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * toolchain#105, the launcher's half of python-multiplatform SPEC L-12: which builds get the marker, and what a
 * desktop launch hands the app (the variable) and the laptop (the connection file).
 */
class DevAgentLaunchTest {

    @Test
    fun `the agent is enabled for the active build type that enables hot reload, and for no other`() {
        val buildTypes = BuildTypesContainer().apply {
            getByName("debug") { enableHotReload = true }
            getByName("release") { }
        }
        assertTrue(devAgentEnabled(buildTypes, "debug"))
        assertFalse(devAgentEnabled(buildTypes, "release"))
        // No buildTypes { } at all: nothing asked for hot reload, so no build carries the marker.
        assertFalse(devAgentEnabled(BuildTypesContainer(), "debug"))
    }

    @Test
    fun `a launch has a 32-byte url-safe token and a free loopback port`() {
        val launch = newDevAgentLaunch()
        // python-multiplatform's MIN_TOKEN_LENGTH: len(secrets.token_urlsafe(32)).
        assertTrue(launch.token.length >= 43, launch.token)
        assertTrue(launch.token.all { it.isLetterOrDigit() || it == '-' || it == '_' }, launch.token)
        assertNotEquals(launch.token, newDevAgentLaunch().token)
        ServerSocket(launch.port, 0, InetAddress.getLoopbackAddress()).close()
    }

    @Test
    fun `the variable has the form the runtime parses`() {
        val launch = DevAgentLaunch(port = 4719, token = "t".repeat(43))
        assertEquals("port=4719;token=${"t".repeat(43)}", launch.environmentValue)
        assertEquals("PYTHON_MULTIPLATFORM_DEV_AGENT", DEV_AGENT_ENVIRONMENT_VARIABLE)
        assertEquals("python_multiplatform_devagent.enable", DEV_AGENT_ENABLE_MARKER)
    }

    @Test
    fun `the connection file names host, port and token and only its owner can read it`() {
        val file = File(createTempDirectory("dev-agent-connection").toFile(), "nested/desktopRun.json")
        file.parentFile.mkdirs()
        file.writeText("stale")
        val launch = DevAgentLaunch(port = 4719, token = "t".repeat(43))

        launch.writeConnectionFile(file)

        val text = file.readText()
        assertTrue("\"host\": \"127.0.0.1\"" in text && "\"port\": 4719" in text && "\"token\": \"${"t".repeat(43)}\"" in text, text)
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file.toPath()),
            )
        }
    }
}
