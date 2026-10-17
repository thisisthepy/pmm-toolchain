package org.thisisthepy.python.multiplatform.toolchain.typedpython

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The decisions `typedpythonCheck` makes, as top-level functions that take no `Project`
 * (AGENTS.md §14), driven without Gradle. `docs/SPEC.md` §1.18.
 *
 * Before `typedpython/TypedpythonCheckTask.kt` exists this file fails to compile -- the
 * pre-implementation failure, not a regression.
 */
class TypedpythonDecisionsTest {
    // Inside this module's build directory, not the system temp directory (AGENTS.md §2).
    private val root = File("build/typedpython-unit").absoluteFile

    @BeforeTest
    fun clean() {
        root.deleteRecursively()
        root.mkdirs()
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun write(relative: String, text: String = "") =
        File(root, relative).apply {
            parentFile.mkdirs()
            writeText(text)
        }

    @Test
    fun `sources are the package's py files without venvs, caches or build output`() {
        write("src/main/pkg/__init__.py")
        write("src/main/pkg/mod.py")
        write("src/main/pkg/data/table.json")
        write("src/main/pkg/stub.pyi")
        write(".venv/lib/python3.13/site-packages/dep.py")
        write("src/main/pkg/__pycache__/mod.cpython-313.py")
        write("build/generated.py")

        val files = collectTypedpythonSources(root).map { it.relativeTo(root).invariantSeparatorsPath }

        assertEquals(listOf("src/main/pkg/__init__.py", "src/main/pkg/mod.py"), files)
    }

    @Test
    fun `a missing package directory has no sources`() {
        assertEquals(emptyList(), collectTypedpythonSources(File(root, "absent")))
    }

    @Test
    fun `the import root is src-main in the pypackpack layout, else the package directory`() {
        assertEquals(root, typedpythonImportRoot(root))
        File(root, "src/main").mkdirs()
        assertEquals(File(root, "src/main"), typedpythonImportRoot(root))
    }

    @Test
    fun `the command passes mode, every search path in order, then the files`() {
        val command = typedpythonCheckCommand(
            executable = File("/venv/bin/typedpython"),
            mode = "checked",
            searchPaths = listOf(File("/pkg/src/main"), File("/stubs/a"), File("/stubs/b")),
            files = listOf(File("/pkg/src/main/x.py")),
        )
        assertEquals(
            listOf(
                "/venv/bin/typedpython", "check", "--mode", "checked",
                "--search-path", "/pkg/src/main",
                "--search-path", "/stubs/a",
                "--search-path", "/stubs/b",
                "/pkg/src/main/x.py",
            ),
            command,
        )
    }

    @Test
    fun `the gate venv is created for the wheel's Python without discovering the user's project`() {
        assertEquals(">=3.13", TYPEDPYTHON_PYTHON_REQUEST)
        assertEquals(listOf("no-project", "clear"), typedpythonVenvOptions().keys.toList())
    }

    @Test
    fun `the gate is installed from the wheel directory only -- --no-index, both packages pinned`() {
        assertEquals(
            listOf(
                "uv", "pip", "install", "--python", "/b/venv", "--no-index", "--find-links", "/w",
                "typedpython==0.1.0", "pyrefly==1.3.2",
            ),
            typedpythonInstallCommand(File("/b/venv"), "0.1.0", "1.3.2", "/w"),
        )
    }

    @Test
    fun `the skip warning says what was skipped, why, and how to turn the check on`() {
        val warning = typedpythonSkippedWarning("0.1.0", "1.3.2")
        assertTrue("SKIPPED" in warning && "NOT type-checked" in warning, warning)
        assertTrue("from an index" in warning, warning)
        assertTrue("-Ptypedpython.wheelDir" in warning, warning)
        assertTrue("typedpython-0.1.0" in warning && "pyrefly-1.3.2" in warning, warning)
    }

    @Test
    fun `only the package directory is checked, not metaDirs or libDirs`() {
        // libDirs hold third-party site-packages and metaDirs generated metadata: neither is code
        // the user wrote, and a diagnostic there could not be fixed by them. SPEC §1.18.
        val pkg = File("/p/pkg")
        assertEquals(
            listOf(pkg),
            typedpythonCheckedDirs(pkg, metaDirs = listOf(File("/p/meta")), libDirs = listOf(File("/p/site-packages"))),
        )
    }

    @Test
    fun `only checked and compiled are modes`() {
        validateTypedpythonMode("checked")
        validateTypedpythonMode("compiled")
        val error = assertFailsWith<IllegalArgumentException> { validateTypedpythonMode("strict") }
        assertTrue("checked" in error.message.orEmpty() && "compiled" in error.message.orEmpty())
    }

    @Test
    fun `exit 0 passes and keeps the warnings`() {
        val result = interpretTypedpythonResult(
            exitCode = 0,
            stdout = "WARNING /p/w.py:3:1: `exec` runs code the checker cannot see [forbidden/exec]\n",
            stderr = "",
        )
        val passed = assertIs<TypedpythonResult.Passed>(result)
        assertEquals(listOf("WARNING /p/w.py:3:1: `exec` runs code the checker cannot see [forbidden/exec]"), passed.warnings)
    }

    @Test
    fun `exit 1 with diagnostics is a type failure carrying them`() {
        val result = interpretTypedpythonResult(
            exitCode = 1,
            stdout = "ERROR /p/a.py:1:7: missing annotation [pyrefly/implicit-any-parameter]\n" +
                "WARNING /p/a.py:3:1: exec [forbidden/exec]\n",
            stderr = "",
        )
        val failed = assertIs<TypedpythonResult.TypeErrors>(result)
        assertEquals(listOf("ERROR /p/a.py:1:7: missing annotation [pyrefly/implicit-any-parameter]"), failed.errors)
        assertEquals(listOf("WARNING /p/a.py:3:1: exec [forbidden/exec]"), failed.warnings)
    }

    @Test
    fun `exit 2 is a tool failure carrying the tool's message`() {
        val result = interpretTypedpythonResult(2, stdout = "", stderr = "typedpython: pyrefly exited 3: boom\n")
        val failure = assertIs<TypedpythonResult.ToolFailure>(result)
        assertTrue("pyrefly exited 3: boom" in failure.message)
    }

    @Test
    fun `exit 1 with no diagnostic line is a tool failure, not a clean type failure`() {
        // An uncaught Python exception exits 1 -- the same code as "errors found". Seen with the
        // 0.1.0 gate given a directory: `IsADirectoryError` and a traceback on stderr.
        val result = interpretTypedpythonResult(
            1,
            stdout = "",
            stderr = "Traceback (most recent call last):\nIsADirectoryError: [Errno 21] Is a directory: '/p'\n",
        )
        val failure = assertIs<TypedpythonResult.ToolFailure>(result)
        assertTrue("IsADirectoryError" in failure.message)
    }
}
