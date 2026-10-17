package org.thisisthepy.python.multiplatform.toolchain

import org.gradle.api.Action
import org.gradle.api.Describable
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.tasks.JavaExec
import org.gradle.testfixtures.ProjectBuilder
import org.thisisthepy.python.multiplatform.toolchain.bundle.StagePythonBundleTask
import org.thisisthepy.python.multiplatform.toolchain.devagent.DEV_AGENT_ENVIRONMENT_VARIABLE
import org.thisisthepy.python.multiplatform.toolchain.devagent.DEV_AGENT_LAUNCH_ACTION
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonExtension
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * toolchain#105: the active build type's `enableHotReload` decides both halves. Its staging writes the
 * dev-agent marker into every destination, and the desktop run task (Compose's `run`) gets a port, a token
 * and a connection file. A build type without it gets neither.
 */
class PythonPluginDevAgentTest {
    private fun evaluated(buildType: String? = null): ProjectInternal {
        val project = ProjectBuilder.builder().build() as ProjectInternal
        buildType?.let { project.extensions.extraProperties["python.buildType"] = it }
        project.pluginManager.apply("org.thisisthepy.python.multiplatform")
        // What Compose Multiplatform's `compose.desktop.application` registers.
        project.tasks.register("run", JavaExec::class.java)
        project.extensions.getByType(PythonExtension::class.java).apply {
            packaging { hotReload { serverHost = "https://localhost:8080" } }
            buildTypes {
                getByName("debug") { enableHotReload = true }
                getByName("release") { }
            }
        }
        project.evaluate()
        return project
    }

    /** Gradle's wrapper around a named `doFirst` action describes itself as "Execute <name>". */
    private val Action<*>.name: String get() = (this as? Describable)?.displayName.orEmpty()

    private fun ProjectInternal.stageTasks() =
        listOf("Android", "Ios", "Desktop").map { tasks.getByName("stagePythonBundle$it") as StagePythonBundleTask }

    @Test
    fun `a hot-reload build stages the marker everywhere and its desktop run starts an agent`() {
        val project = evaluated()

        project.stageTasks().forEach { assertTrue(it.devAgentMarker, "${it.name} stages no marker") }
        val run = project.tasks.getByName("run") as JavaExec
        val action = run.actions.firstOrNull { DEV_AGENT_LAUNCH_ACTION in it.name }
        assertNotNull(action, "run has no dev-agent launch action: ${run.actions.map { it.name }}")

        action.execute(run)
        val value = run.environment[DEV_AGENT_ENVIRONMENT_VARIABLE] as String
        assertTrue(Regex("port=\\d+;token=[A-Za-z0-9_-]{43,}").matches(value), value)
        val connection = File(project.layout.buildDirectory.get().asFile, "pythonDevAgent/run.json")
        assertTrue(connection.isFile && value.substringAfter("token=") in connection.readText(), "$connection")
    }

    @Test
    fun `a release build stages no marker and its desktop run starts nothing`() {
        val project = evaluated(buildType = "release")

        project.stageTasks().forEach { assertFalse(it.devAgentMarker, "${it.name} stages the marker") }
        val run = project.tasks.getByName("run") as JavaExec
        assertTrue(run.actions.none { DEV_AGENT_LAUNCH_ACTION in it.name }, "${run.actions.map { it.name }}")
        assertEquals(null, run.environment[DEV_AGENT_ENVIRONMENT_VARIABLE])
    }
}
