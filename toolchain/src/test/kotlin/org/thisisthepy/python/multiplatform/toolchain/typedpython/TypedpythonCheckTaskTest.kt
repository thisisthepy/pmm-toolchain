package org.thisisthepy.python.multiplatform.toolchain.typedpython

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import kotlin.test.BeforeTest

/**
 * `typedpythonCheck` applied to a real Gradle build through TestKit (`docs/SPEC.md` §1.18, issue
 * `toolchain#23`): the gate is really installed into `build/typedpython/venv` and really run.
 *
 * Needs `uv` on `PATH` and a wheel directory holding the `typedpython` wheel **and** the `pyrefly`
 * wheel it pins (the gate is installed with `--no-index`, so nothing comes from PyPI): run with
 * `-Ptypedpython.wheelDir=<dir>` (`toolchain/build.gradle.kts` forwards it as a system property).
 * Without it these tests are skipped with that reason -- CI has no wheel directory.
 *
 * Every test project lives under this module's `build/` (AGENTS.md §2), and so does the TestKit
 * Gradle home.
 *
 * The build scripts are Kotlin DSL (`build.gradle.kts`), as a consumer's are. What these tests
 * **cannot** see: `withPluginClasspath()` injects the plugin's classes instead of resolving them, so
 * the Kotlin DSL's pin of the buildscript `kotlin-stdlib` to Gradle's embedded version
 * (`{strictly 1.9.23}` on Gradle 8.9, see `:usage-example:buildEnvironment`) does not apply here. A
 * gate install through `pypackpack`'s suspend backend passed every test below and still died in
 * `usage-example` with `NoClassDefFoundError: kotlin/coroutines/jvm/internal/SpillingKt` -- see
 * [TypedpythonCheckTask]. Classpath problems show up in `usage-example`, not here.
 *
 * Before `typedpythonCheck` exists, these fail with "Task 'typedpythonCheck' not found" or with
 * `buildPython` succeeding over an `Any` leak -- the pre-implementation failure, not a regression.
 */
class TypedpythonCheckTaskTest {
    private val wheelDir: String = System.getProperty("typedpython.wheelDir").orEmpty()

    /**
     * Without the wheel directory (CI does not have one) these tests are *skipped*, with the reason
     * below, not failed. The decision tests (`TypedpythonDecisionsTest`) and
     * `TypedpythonNoWheelTest` run everywhere.
     */
    @BeforeTest
    fun requireWheelDir() {
        assumeTrue(
            "SKIPPED: needs the typedpython and pyrefly wheels; run with " +
                "-Ptypedpython.wheelDir=<directory holding typedpython-0.1.0-py3-none-any.whl and the pyrefly wheel>",
            wheelDir.isNotBlank(),
        )
    }

    private val testKitHome = File("build/testkit-home").absoluteFile

    private fun newProject(name: String, buildScript: String = DEFAULT_BUILD_SCRIPT): File {
        val dir = File("build/typedpython-testkit/$name").absoluteFile
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"$name\"\n")
        File(dir, "build.gradle.kts").writeText(buildScript)
        write(dir, "pkg/pyproject.toml", "[project]\nname = \"demo\"\nversion = \"0.0.1\"\nrequires-python = \">=3.13\"\n")
        write(dir, "pkg/src/main/demo/__init__.py", "")
        write(dir, "pkg/src/main/demo/ok.py", TYPED)
        write(dir, "pkg/src/main/demo/data/table.json", "{}\n")
        return dir
    }

    private fun write(dir: File, relative: String, text: String) {
        File(dir, relative).apply {
            parentFile.mkdirs()
            writeText(text)
        }
    }

    private fun runner(dir: File, vararg tasks: String, wheels: String = wheelDir): GradleRunner =
        GradleRunner.create()
            .withProjectDir(dir)
            .withTestKitDir(testKitHome)
            .withPluginClasspath()
            .withArguments(*tasks, "-Ptypedpython.wheelDir=$wheels", "--stacktrace")
            .forwardOutput()

    private fun BuildResult.outcomeOf(task: String): TaskOutcome? = task(":$task")?.outcome

    @Test
    fun `an Any leak fails buildPython with the gate's diagnostic, and fixing it passes`() {
        val dir = newProject("leak")
        write(dir, "pkg/src/main/demo/leak.py", LEAK)

        val failed = runner(dir, "buildPython").buildAndFail()
        assertEquals(TaskOutcome.FAILED, failed.outcomeOf("typedpythonCheck"), failed.output)
        assertTrue("implicit-any-parameter" in failed.output, failed.output)
        assertTrue("leak.py:1:" in failed.output, "the diagnostic names the file and line")

        write(dir, "pkg/src/main/demo/leak.py", TYPED)
        val passed = runner(dir, "buildPython").build()
        assertEquals(TaskOutcome.SUCCESS, passed.outcomeOf("typedpythonCheck"))
        assertEquals(TaskOutcome.SUCCESS, passed.outcomeOf("buildPython"))
        assertTrue(File(dir, "build/typedpython/venv/bin/typedpython").isFile, "the gate lives in build/typedpython/venv")
        assertFalse(File(dir, "pkg/.venv/bin/typedpython").exists(), "the gate is never installed into the project's .venv")
    }

    @Test
    fun `every per-variant buildPython depends on the check`() {
        val dir = newProject(
            "variants",
            DEFAULT_BUILD_SCRIPT.replace("localLibraryPath = \"pkg\"", "localLibraryPath = \"pkg\"\n    macosArm64()\n    linuxX64()"),
        )

        val result = runner(dir, "buildPythonMacosArm64Debug", "buildPythonLinuxX64Debug", "--dry-run").build()

        assertTrue(":typedpythonCheck SKIPPED" in result.output, result.output)
        assertTrue(result.output.indexOf(":typedpythonCheck") < result.output.indexOf(":buildPythonMacosArm64Debug"))
        assertTrue(result.output.indexOf(":typedpythonCheck") < result.output.indexOf(":buildPythonLinuxX64Debug"))
    }

    @Test
    fun `only a py change re-runs the check`() {
        val dir = newProject("uptodate")
        // `--info` so a failure carries Gradle's own "is not up-to-date because" line. One run of
        // this test (in 8 observed) saw a rerun after the json-only change; the reason was not
        // captured then.
        fun check(): BuildResult = runner(dir, "typedpythonCheck", "--info").build()
        fun BuildResult.why(): String =
            output.lines().dropWhile { "is not up-to-date because" !in it }.take(4).joinToString("\n")

        assertEquals(TaskOutcome.SUCCESS, check().outcomeOf("typedpythonCheck"))
        check().let { assertEquals(TaskOutcome.UP_TO_DATE, it.outcomeOf("typedpythonCheck"), it.why()) }

        write(dir, "pkg/src/main/demo/data/table.json", "{\"changed\": true}\n")
        check().let {
            assertEquals(TaskOutcome.UP_TO_DATE, it.outcomeOf("typedpythonCheck"), "a non-Python file is not an input:\n${it.why()}")
        }

        write(dir, "pkg/src/main/demo/ok.py", TYPED + "\n\ndef more(y: str) -> str:\n    return y\n")
        assertEquals(TaskOutcome.SUCCESS, check().outcomeOf("typedpythonCheck"))
    }

    @Test
    fun `a directory in typedpythonStubs is passed as a search path`() {
        val dir = newProject("stubs")
        write(dir, "stubs/extmod.pyi", "def answer() -> int: ...\n")
        write(dir, "pkg/src/main/demo/uses.py", "from extmod import answer\n\n\ndef h() -> int:\n    return answer()\n")

        val unwired = runner(dir, "typedpythonCheck").buildAndFail()
        assertTrue("missing-import" in unwired.output, "without the stub, extmod does not resolve:\n${unwired.output}")

        File(dir, "build.gradle.kts").appendText("\ndependencies {\n    \"typedpythonStubs\"(files(\"stubs\"))\n}\n")
        val wired = runner(dir, "typedpythonCheck").build()
        assertEquals(TaskOutcome.SUCCESS, wired.outcomeOf("typedpythonCheck"), wired.output)
        assertFalse(NO_STUBS_NOTICE in wired.output, "a wired stub directory is not reported as missing")
    }

    @Test
    fun `with nothing in typedpythonStubs the check runs and says so once`() {
        val dir = newProject("nostubs")

        val result = runner(dir, "buildPython").build()

        assertEquals(TaskOutcome.SUCCESS, result.outcomeOf("typedpythonCheck"))
        assertEquals(1, Regex(Regex.escape(NO_STUBS_NOTICE)).findAll(result.output).count(), result.output)
    }

    @Test
    fun `the package's modules import one another`() {
        val dir = newProject("intra")
        write(dir, "pkg/src/main/demo/a.py", "from demo.ok import g\nfrom . import ok\n\n\ndef k() -> int:\n    return g(1) + ok.g(2)\n")

        val result = runner(dir, "typedpythonCheck").build()

        assertEquals(TaskOutcome.SUCCESS, result.outcomeOf("typedpythonCheck"), result.output)
    }

    @Test
    fun `pyrefly is pinned and an input, and comes from the wheel directory only`() {
        val dir = newProject("pyrefly-pin")
        assertEquals(TaskOutcome.SUCCESS, runner(dir, "typedpythonCheck").build().outcomeOf("typedpythonCheck"))

        // 1.3.1 exists on PyPI but is not in the wheel directory: with --no-index the install must
        // fail rather than fetch it, and changing the pin must re-run the (otherwise UP-TO-DATE) check.
        File(dir, "build.gradle.kts").appendText(
            "\ntasks.named<org.thisisthepy.python.multiplatform.toolchain.typedpython.TypedpythonCheckTask>" +
                "(\"typedpythonCheck\") {\n    pyreflyVersion.set(\"1.3.1\")\n}\n",
        )
        val failed = runner(dir, "typedpythonCheck").buildAndFail()
        assertEquals(TaskOutcome.FAILED, failed.outcomeOf("typedpythonCheck"), failed.output)
        assertTrue("pyrefly==1.3.1" in failed.output, failed.output)
    }

    @Test
    fun `a wheel directory without pyrefly fails -- pyrefly is never fetched from an index`() {
        val dir = newProject("no-pyrefly-wheel")
        val onlyTypedpython = File("build/typedpython-testkit/wheels-without-pyrefly").absoluteFile
        onlyTypedpython.deleteRecursively()
        onlyTypedpython.mkdirs()
        File(wheelDir).listFiles { f -> f.name.startsWith("typedpython-") && f.extension == "whl" }!!
            .forEach { it.copyTo(File(onlyTypedpython, it.name)) }

        val failed = runner(dir, "typedpythonCheck", wheels = onlyTypedpython.path).buildAndFail()

        assertEquals(TaskOutcome.FAILED, failed.outcomeOf("typedpythonCheck"), failed.output)
        assertTrue("pyrefly" in failed.output && "--no-index" in failed.output, failed.output)
    }

    @Test
    fun `metaDirs and libDirs are bundled but not checked`() {
        val dir = newProject(
            "meta-lib",
            DEFAULT_BUILD_SCRIPT.replace(
                "localLibraryPath = \"pkg\"",
                "localLibraryPath = \"pkg\"\n    sourceSets {\n        val commonMain by getting {\n" +
                    "            metaDirs(\"meta\")\n            libDirs(\"site-packages\")\n        }\n    }",
            ),
        )
        write(dir, "meta/generated.py", LEAK)
        write(dir, "site-packages/thirdparty.py", LEAK)

        val result = runner(dir, "typedpythonCheck").build()

        assertEquals(TaskOutcome.SUCCESS, result.outcomeOf("typedpythonCheck"), result.output)
    }

    private companion object {
        const val NO_STUBS_NOTICE = "the typedpythonStubs configuration is empty; checking without stubs"

        const val TYPED = "def g(x: int) -> int:\n    return x\n"
        const val LEAK = "def f(x):\n    return x\n"

        val DEFAULT_BUILD_SCRIPT =
            """
            plugins {
                id("org.thisisthepy.python.multiplatform")
            }

            python {
                localLibraryPath = "pkg"
            }
            """.trimIndent() + "\n"
    }
}
