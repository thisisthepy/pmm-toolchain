package org.thisisthepy.python.multiplatform.toolchain

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.thisisthepy.python.multiplatform.toolchain.bundle.AssemblePythonPackageTask
import org.thisisthepy.python.multiplatform.toolchain.bundle.BuildPythonArtifactTask
import org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python.InstallTargetDependenciesTask
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonExtension
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stands in for python-multiplatform's `EmbeddedPythonVersion` extension, read by name, reflectively. */
open class FakeEmbeddedPythonVersion(var pythonVersion: String)

/**
 * docs/SPEC.md §1.2, §1.12, issue #42, on an applied plugin: toolchain ships only `python/`. At
 * embedLevel 2 there is no interpreter acquisition task and no `runtime/`; the record states the
 * version python-multiplatform is expected to embed; compileSdk still selects the wheels' Python
 * version (#16); and python-multiplatform's `pythonVersion` reaches the bundling task as a rejection
 * when it disagrees with compileSdk.
 */
class PythonPluginPythonOnlyTest {
    private fun configured(
        project: Project,
        sdk: String? = "3.15.0",
        pythonMultiplatformVersion: String? = null,
        configure: PythonExtension.() -> Unit,
    ): Project {
        project.pluginManager.apply("org.thisisthepy.python.multiplatform")
        pythonMultiplatformVersion?.let {
            project.extensions.extraProperties.set(PYTHON_MULTIPLATFORM_VERSION_PROPERTY, it)
        }
        project.extensions.getByType(PythonExtension::class.java).apply {
            sdk?.let { compileSdk.assign(it) }
            localLibraryPath = "python"
            configure()
        }
        (project as ProjectInternal).evaluate()
        return project
    }

    private fun evaluated(
        sdk: String? = "3.15.0",
        pythonMultiplatformVersion: String? = null,
        configure: PythonExtension.() -> Unit,
    ): Project =
        configured(
            ProjectBuilder.builder().withProjectDir(createTempDirectory("python-only").toFile()).build(),
            sdk,
            pythonMultiplatformVersion,
            configure,
        )

    private fun Project.build(name: String) = assertIs<BuildPythonArtifactTask>(tasks.getByName(name))

    private fun Project.pack(name: String) = assertIs<AssemblePythonPackageTask>(tasks.getByName(name))

    private fun Task.dependencyNames(): Set<String> = taskDependencies.getDependencies(this).map { it.name }.toSet()

    @Test
    fun `level 2 registers no interpreter acquisition and the bundling task carries no interpreter`() {
        val project = evaluated {
            androidArm64()
            iosArm64()
            packaging { embedLevel = 2 }
        }

        val acquisitions = project.tasks.names.filter { it.startsWith("acquirePythonInterpreter") }
        assertTrue(acquisitions.isEmpty(), "$acquisitions")
        listOf("buildPythonAndroidArm64Debug", "buildPythonIosArm64Debug", PythonPlugin.BUILD_TASK).forEach { name ->
            val deps = project.build(name).dependencyNames()
            assertFalse(deps.any { it.startsWith("acquirePythonInterpreter") }, "$name: $deps")
        }
        // No property of the bundling task names an interpreter or a runtime directory any more.
        val getters = BuildPythonArtifactTask::class.java.declaredMethods.map { it.name }
            .filter { it.startsWith("get") && ("Interpreter" in it || "Runtime" in it) }
        assertTrue(getters.isEmpty(), "$getters")
        assertFalse(File(project.layout.buildDirectory.get().asFile, "pythonRuntime").exists())
    }

    @Test
    fun `the level 2 zip carries python only and its record names the expected version`() {
        val project = evaluated { androidArm64() }
        val buildDir = project.layout.buildDirectory.get().asFile
        File(buildDir, "pythonBundle/androidArm64-debug/python/app/main.py")
            .apply { parentFile.mkdirs() }.writeText("")

        val pack = project.pack("packagePythonAndroidArm64Debug")
        assertEquals(2, pack.embedLevel)
        assertEquals("3.15.0", pack.interpreterVersion)
        pack.destinationDirectory.get().asFile.mkdirs()
        pack.actions.forEach { it.execute(pack) }

        val entries = ZipFile(File(buildDir, "distributions/app-androidArm64-debug.zip"))
            .use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        assertTrue("python/app/main.py" in entries, "$entries")
        assertFalse(entries.any { it.startsWith("runtime/") || it == "runtime-manifest.json" }, "$entries")
        val record = File(buildDir, "distributions/app-androidArm64-debug.embed.json").readText()
        assertTrue("\"interpreterVersion\": \"3.15.0\"" in record, record)
        assertFalse("interpreterBundled" in record, record)
    }

    @Test
    fun `level 2 without compileSdk is no longer refused`() {
        val project = evaluated(sdk = null) { androidArm64() }

        val bundle = project.build("buildPythonAndroidArm64Debug")
        assertNull(bundle.pythonVersionRejection)
        assertNull(project.pack("packagePythonAndroidArm64Debug").interpreterVersion)
    }

    @Test
    fun `compileSdk still selects the wheels' python version at level 2`() {
        val project = evaluated(sdk = "3.15") { androidArm64() }

        val install = assertIs<InstallTargetDependenciesTask>(project.tasks.getByName("installPythonDependenciesAndroidArm64"))
        assertEquals("3.15", install.installArguments["python-version"])
    }

    @Test
    fun `a disagreeing python multiplatform version property fails only the level 2 bundling tasks`() {
        val project = evaluated(sdk = "3.15.0", pythonMultiplatformVersion = "3.13.0") {
            packaging { embedLevel = 0 }
            androidArm64()
            macosArm64()
        }

        val android = project.build("buildPythonAndroidArm64Debug")
        val rejection = assertNotNull(android.pythonVersionRejection)
        assertTrue("3.15.0" in rejection && "3.13.0" in rejection, rejection)
        // macOS honours level 0: python-multiplatform embeds nothing there, so nothing to compare.
        assertNull(project.build("buildPythonMacosArm64Debug").pythonVersionRejection)

        // The rejection fails the task action, like pythonSdkRejection (only with a package to bundle).
        android.packageDir = createTempDirectory("python-only-pkg").toFile()
        val error = assertFailsWith<GradleException> { android.buildPython() }
        assertTrue("3.13.0" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `an agreeing version, or none, leaves the bundling task alone`() {
        val agreeing = evaluated(pythonMultiplatformVersion = "3.15.0") { androidArm64() }
        assertNull(agreeing.build("buildPythonAndroidArm64Debug").pythonVersionRejection)

        val unknown = evaluated { androidArm64() }
        assertNull(unknown.build("buildPythonAndroidArm64Debug").pythonVersionRejection)
    }

    private fun appBesidePythonMultiplatform(extensionVersion: String): Project {
        val root = ProjectBuilder.builder().withProjectDir(createTempDirectory("python-only-root").toFile()).build()
        val pythonMultiplatform = ProjectBuilder.builder().withName("python-multiplatform").withParent(root).build()
        pythonMultiplatform.extensions.add(PYTHON_MULTIPLATFORM_EXTENSION, FakeEmbeddedPythonVersion(extensionVersion))
        return ProjectBuilder.builder().withName("app").withParent(root).build()
    }

    @Test
    fun `the python-multiplatform project's extension is read when no property is set`() {
        val app = appBesidePythonMultiplatform("3.13.0")
        assertEquals("3.13.0", readPythonMultiplatformExtensionVersion(app))

        configured(app) { androidArm64() }
        val rejection = assertNotNull(app.build("buildPythonAndroidArm64Debug").pythonVersionRejection)
        assertTrue("3.15.0" in rejection && "3.13.0" in rejection, rejection)
    }

    @Test
    fun `the property overrides the python-multiplatform project's extension`() {
        val app = appBesidePythonMultiplatform("3.13.0")

        configured(app, pythonMultiplatformVersion = "3.15.0") { androidArm64() }
        assertNull(app.build("buildPythonAndroidArm64Debug").pythonVersionRejection)
    }

    @Test
    fun `no python-multiplatform project reads as unknown`() {
        val project = ProjectBuilder.builder().withProjectDir(createTempDirectory("python-only-alone").toFile()).build()
        assertNull(readPythonMultiplatformExtensionVersion(project))
    }
}
