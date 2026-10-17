package org.thisisthepy.python.multiplatform.toolchain.devagent

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.tasks.JavaExec
import org.thisisthepy.python.multiplatform.toolchain.dsl.BuildTypesContainer
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

/*
 * The launcher's half of the dev agent (toolchain#105; python-multiplatform SPEC L-12; docs/design/live-connection.md).
 *
 * python-multiplatform's runtime starts its agent only when both hold: the process has
 * [DEV_AGENT_ENVIRONMENT_VARIABLE], and a payload root holds [DEV_AGENT_ENABLE_MARKER]. This plugin provides
 * both, for a build whose active Python build type has `enableHotReload = true` (the example build file's
 * `debug`): the staging tasks write the marker, and the desktop run task gets a fresh port and token.
 * A build without it gets neither, and a process started with the variable by hand still opens no port.
 */

/** The variable python-multiplatform's `PythonDevAgent` reads: `port=<n>;token=<t>`. */
const val DEV_AGENT_ENVIRONMENT_VARIABLE = "PYTHON_MULTIPLATFORM_DEV_AGENT"

/** python-multiplatform's `PythonDevAgent.ENABLE_MARKER`, written at the root of the staged `python/` payload. */
const val DEV_AGENT_ENABLE_MARKER = "python_multiplatform_devagent.enable"

/** The name of the `doFirst` action that starts an agent; Gradle shows it as "Execute startPythonDevAgent". */
const val DEV_AGENT_LAUNCH_ACTION = "startPythonDevAgent"

/** `<buildDir>/pythonDevAgent/<task>.json`: where a launch leaves its port and token for `tcl connect`. */
const val DEV_AGENT_CONNECTION_DIRECTORY = "pythonDevAgent"

/** Random bytes in a token: python-multiplatform refuses fewer than `secrets.token_urlsafe(32)` gives. */
private const val TOKEN_BYTES = 32

/**
 * Whether [activeBuildType] is a build the dev agent belongs in: it declares `enableHotReload = true`.
 *
 * No `buildTypes { }` block, or an active type that does not set it, is false: nothing asked for a live
 * channel, so no payload carries the marker.
 */
fun devAgentEnabled(buildTypes: BuildTypesContainer, activeBuildType: String): Boolean =
    buildTypes.all().firstOrNull { it.name == activeBuildType }?.enableHotReload == true

/** One launch's loopback port and token. */
data class DevAgentLaunch(val port: Int, val token: String) {
    /** The value of [DEV_AGENT_ENVIRONMENT_VARIABLE]. */
    val environmentValue: String get() = "port=$port;token=$token"

    /**
     * Writes `{"host", "port", "token"}` to [file], replacing it. On a POSIX file system the file is created
     * owner-only before the token is written: the token is what keeps any other local user from running code
     * in the app.
     */
    fun writeConnectionFile(file: File) {
        file.parentFile?.mkdirs()
        val path = file.toPath()
        Files.deleteIfExists(path)
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }
        file.writeText("""{"host": "127.0.0.1", "port": $port, "token": "$token"}""" + "\n")
    }
}

/** A launch with a free loopback port and [TOKEN_BYTES] random bytes as an unpadded URL-safe token (43 characters). */
fun newDevAgentLaunch(random: SecureRandom = SecureRandom()): DevAgentLaunch {
    val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
    val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
    return DevAgentLaunch(port, token)
}

/**
 * The `doFirst` of a desktop run task: a fresh launch per run, handed to the app as the variable and to the
 * laptop as [connectionFile]. The port is picked here rather than by the agent (port 0) because the laptop
 * has to know it before the app has printed anything; the window between closing the probe socket and the
 * app binding it is the only race, and losing it fails the agent's bind with a warning, not the app.
 */
class DevAgentLaunchAction(private val connectionFile: File) : Action<Task> {
    override fun execute(task: Task) {
        val launch = newDevAgentLaunch()
        (task as JavaExec).environment(DEV_AGENT_ENVIRONMENT_VARIABLE, launch.environmentValue)
        launch.writeConnectionFile(connectionFile)
        task.logger.lifecycle("Python dev agent: 127.0.0.1:${launch.port}, connection file $connectionFile")
    }
}
