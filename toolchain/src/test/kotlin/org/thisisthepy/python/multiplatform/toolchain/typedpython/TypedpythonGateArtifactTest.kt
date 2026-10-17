package org.thisisthepy.python.multiplatform.toolchain.typedpython

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `typedpythonCheck` with the gate from python-multiplatform-ksp's `typedpython-gate` zip and no
 * `-Ptypedpython.wheelDir` (#90, `docs/SPEC.md` §1.18). Needs that zip in `~/.m2` (python-multiplatform's
 * `./gradlew :python-multiplatform-ksp:publishToMavenLocal`), `uv` on `PATH` and network access (pyrefly
 * comes from PyPI). Without the zip these tests are skipped with that reason, as on CI.
 */
class TypedpythonGateArtifactTest {
    private val mavenLocalZip = File(
        System.getProperty("user.home"),
        ".m2/repository/io/github/thisisthepy/python-multiplatform-ksp/3.13.0/" +
            "python-multiplatform-ksp-3.13.0-typedpython-gate.zip",
    )

    @BeforeTest
    fun requireGate() {
        assumeTrue(
            "SKIPPED: needs $DEFAULT_TYPEDPYTHON_GATE:$TYPEDPYTHON_GATE_CLASSIFIER@zip in ~/.m2 " +
                "(python-multiplatform: ./gradlew :python-multiplatform-ksp:publishToMavenLocal)",
            mavenLocalZip.isFile,
        )
    }

    private fun project(name: String, repositories: String): File {
        val dir = File("build/typedpython-testkit/$name").absoluteFile
        dir.deleteRecursively()
        File(dir, "pkg/src/main/demo").mkdirs()
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"$name\"\n")
        File(dir, "build.gradle.kts").writeText(
            "plugins {\n    id(\"org.thisisthepy.python.multiplatform\")\n}\n\n" +
                "repositories {\n$repositories\n}\n\npython {\n    localLibraryPath = \"pkg\"\n}\n",
        )
        File(dir, "pkg/pyproject.toml").writeText("[project]\nname = \"demo\"\nversion = \"0.0.1\"\n")
        File(dir, "pkg/src/main/demo/__init__.py").writeText("")
        File(dir, "pkg/src/main/demo/ok.py").writeText("def g(x: int) -> int:\n    return x\n")
        return dir
    }

    private fun runner(dir: File, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(dir)
            .withTestKitDir(File("build/testkit-home").absoluteFile)
            .withPluginClasspath()
            .withArguments(listOf("typedpythonCheck", "--stacktrace") + arguments)
            .forwardOutput()

    @Test
    fun `with the gate in mavenLocal the check installs and runs without a wheel directory`() {
        val dir = project("gate-mavenlocal", "    mavenLocal()")

        val result = runner(dir).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":typedpythonCheck")?.outcome, result.output)
        assertFalse("SKIPPED" in result.output, result.output)
        assertTrue(File(dir, "build/typedpython/gate/$TYPEDPYTHON_GATE_MANIFEST").isFile)
        val requirements = File(dir, "build/typedpython/gate-requirements.txt").readText()
        assertTrue(requirements.startsWith("typedpython @ file:"), requirements)
        assertTrue("0 errors" in File(dir, "build/typedpython/report.txt").readText())
    }

    @Test
    fun `a gate the repositories do not have is a skip with the warning, not a failure`() {
        val dir = project("gate-missing", "    mavenLocal()")

        val result = runner(dir, "-P$TYPEDPYTHON_GATE_PROPERTY=io.github.thisisthepy:python-multiplatform-ksp:0.0.0-none").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":typedpythonCheck")?.outcome, result.output)
        assertTrue("typedpythonCheck SKIPPED" in result.output, result.output)
        assertFalse(File(dir, "build/typedpython/venv").exists())
    }

    /** A pyrefly whose hash is not in the manifest is refused by `--require-hashes`: the task fails. */
    @Test
    fun `a pyrefly that matches none of the manifest's hashes fails the task`() {
        val repo = File("build/typedpython-testkit/tampered-repo").absoluteFile
        repo.deleteRecursively()
        val target = File(repo, "io/github/thisisthepy/python-multiplatform-ksp/9.9.9/python-multiplatform-ksp-9.9.9-typedpython-gate.zip")
        target.parentFile.mkdirs()
        ZipFile(mavenLocalZip).use { source ->
            ZipOutputStream(target.outputStream()).use { out ->
                source.entries().asSequence().forEach { entry ->
                    var bytes = source.getInputStream(entry).readBytes()
                    if (entry.name == TYPEDPYTHON_GATE_MANIFEST) {
                        val tampered = Regex("(\"pyrefly-[^\"]+\\.whl\"\\s*:\\s*\")[0-9a-f]{64}\"")
                            .replace(bytes.decodeToString()) { it.groupValues[1] + "0".repeat(64) + "\"" }
                        bytes = tampered.encodeToByteArray()
                    }
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        val dir = project(
            "gate-tampered",
            "    maven {\n        url = uri(\"${repo.toURI()}\")\n        metadataSources { artifact() }\n    }",
        )

        val result = runner(dir, "-P$TYPEDPYTHON_GATE_PROPERTY=io.github.thisisthepy:python-multiplatform-ksp:9.9.9").buildAndFail()

        assertTrue("--require-hashes" in result.output, result.output)
        assertFalse(File(dir, "build/typedpython/report.txt").exists())
    }
}
