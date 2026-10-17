package org.thisisthepy.python.multiplatform.toolchain

import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.thisisthepy.python.multiplatform.toolchain.bundle.AssemblePythonPackageTask
import org.thisisthepy.python.multiplatform.toolchain.bundle.BuildPythonArtifactTask
import org.thisisthepy.python.multiplatform.toolchain.bundle.StagePythonBundleTask
import org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python.InstallDependenciesTask
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonExtension
import org.thisisthepy.python.multiplatform.toolchain.hotreload.CodePushPendingTask
import org.thisisthepy.python.multiplatform.toolchain.hotreload.HotReloadPushTask
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * docs/SPEC.md §1.1: applying the plugin id to a real Gradle `Project` creates the `python`
 * extension and registers the tasks of §1.10–§1.15, all in group `python`.
 *
 * Before this file the only thing that applied the plugin was a `usage-example` build, which needs
 * `publishToMavenLocal` first and an Android SDK; a ProjectBuilder project needs neither.
 */
class PythonPluginApplyTest {
    private fun newProject(): Project =
        ProjectBuilder.builder()
            .withProjectDir(createTempDirectory("python-plugin-apply").toFile())
            .build()
            .also { it.pluginManager.apply("org.thisisthepy.python.multiplatform") }

    @Test
    fun `applying the plugin id creates the python extension`() {
        val project = newProject()

        assertTrue(project.plugins.hasPlugin(PythonPlugin::class.java))
        assertIs<PythonExtension>(project.extensions.getByName("python"))
    }

    @Test
    fun `the tasks registered at apply time exist with their types and the python group`() {
        val project = newProject()

        val expected = mapOf(
            PythonPlugin.INSTALL_TASK to InstallDependenciesTask::class.java,
            PythonPlugin.BUILD_TASK to BuildPythonArtifactTask::class.java,
            PythonPlugin.PACKAGE_TASK to AssemblePythonPackageTask::class.java,
            "stagePythonBundleAndroid" to StagePythonBundleTask::class.java,
            "stagePythonBundleIos" to StagePythonBundleTask::class.java,
            "stagePythonBundleDesktop" to StagePythonBundleTask::class.java,
        )
        expected.forEach { (name, type) ->
            val task = assertNotNull(project.tasks.findByName(name), "task '$name' is not registered")
            assertTrue(type.isInstance(task), "'$name' is ${task.javaClass}, expected $type")
            assertEquals(PythonPlugin.TASK_GROUP, task.group, "group of '$name'")
        }

        val aggregate = assertNotNull(project.tasks.findByName(PythonPlugin.STAGE_TASK))
        assertEquals(PythonPlugin.TASK_GROUP, aggregate.group)
        val stageDependencies = aggregate.taskDependencies.getDependencies(aggregate).map { it.name }.toSet()
        assertEquals(
            setOf("stagePythonBundleAndroid", "stagePythonBundleIos", "stagePythonBundleDesktop"),
            stageDependencies,
        )
    }

    @Test
    fun `hot reload and code push tasks appear once the project is evaluated`() {
        val project = newProject()
        (project as ProjectInternal).evaluate()

        val hotReload = assertNotNull(project.tasks.findByName(PythonPlugin.HOT_RELOAD_TASK))
        assertIs<HotReloadPushTask>(hotReload)
        assertEquals(PythonPlugin.TASK_GROUP, hotReload.group)

        val codePush = assertNotNull(project.tasks.findByName(PythonPlugin.CODE_PUSH_TASK))
        assertIs<CodePushPendingTask>(codePush)
        assertEquals(PythonPlugin.TASK_GROUP, codePush.group)
    }

    @Test
    fun `packagePython depends on buildPython, which depends on installPythonDependencies`() {
        val project = newProject()

        val packageTask = project.tasks.getByName(PythonPlugin.PACKAGE_TASK)
        val buildTask = project.tasks.getByName(PythonPlugin.BUILD_TASK)
        assertTrue(buildTask in packageTask.taskDependencies.getDependencies(packageTask))
        assertTrue(
            project.tasks.getByName(PythonPlugin.INSTALL_TASK) in buildTask.taskDependencies.getDependencies(buildTask),
        )
    }
}
