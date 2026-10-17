package org.thisisthepy.python.multiplatform.toolchain.typedpython

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gate from python-multiplatform-ksp's `typedpython-gate` zip (#90, `docs/SPEC.md` §1.18): the
 * coordinates, the manifest, the hash-pinned requirements and pyrefly's wheel for the host. No Gradle.
 */
class TypedpythonGateTest {
    private val root = File("build/typedpython-gate-unit").absoluteFile

    @BeforeTest
    fun clean() {
        root.deleteRecursively()
        root.mkdirs()
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val manifestJson =
        """
        {"format": 1,
         "typedpython": {"version": "0.1.0", "wheel": "typedpython-0.1.0-py3-none-any.whl", "sha256": "${"a".repeat(64)}"},
         "pyrefly": {"version": "1.3.2", "sha256": {
           "pyrefly-1.3.2-py3-none-macosx_11_0_arm64.whl": "${"1".repeat(64)}",
           "pyrefly-1.3.2-py3-none-macosx_10_12_x86_64.whl": "${"2".repeat(64)}",
           "pyrefly-1.3.2-py3-none-manylinux_2_17_x86_64.manylinux2014_x86_64.whl": "${"3".repeat(64)}",
           "pyrefly-1.3.2-py3-none-manylinux_2_17_aarch64.manylinux2014_aarch64.whl": "${"4".repeat(64)}",
           "pyrefly-1.3.2-py3-none-win_amd64.whl": "${"5".repeat(64)}"}}}
        """.trimIndent()

    @Test
    fun `the gate is the typedpython-gate zip of python-multiplatform-ksp, from one constant`() {
        assertEquals("io.github.thisisthepy:python-multiplatform-ksp:3.13.0", DEFAULT_TYPEDPYTHON_GATE)
        assertEquals(
            "io.github.thisisthepy:python-multiplatform-ksp:3.13.0:typedpython-gate@zip",
            typedpythonGateNotation(DEFAULT_TYPEDPYTHON_GATE),
        )
        assertEquals("typedpython.gate", TYPEDPYTHON_GATE_PROPERTY)
        assertFailsWith<IllegalArgumentException> { typedpythonGateNotation("python-multiplatform-ksp") }
    }

    @Test
    fun `the manifest names the typedpython wheel and every pyrefly wheel's hash`() {
        val manifest = parseTypedpythonGateManifest(manifestJson)

        assertEquals("0.1.0", manifest.typedpythonVersion)
        assertEquals("typedpython-0.1.0-py3-none-any.whl", manifest.typedpythonWheel)
        assertEquals("a".repeat(64), manifest.typedpythonSha256)
        assertEquals("1.3.2", manifest.pyreflyVersion)
        assertEquals(5, manifest.pyreflySha256.size)
    }

    @Test
    fun `a manifest of another format or without a hash is refused`() {
        assertFailsWith<IllegalArgumentException> { parseTypedpythonGateManifest(manifestJson.replace("\"format\": 1", "\"format\": 2")) }
        assertFailsWith<IllegalArgumentException> { parseTypedpythonGateManifest(manifestJson.replace("\"sha256\": \"${"a".repeat(64)}\"", "\"sha256\": \"\"")) }
        assertFailsWith<IllegalArgumentException> { parseTypedpythonGateManifest("{\"format\": 1}") }
    }

    /**
     * typedpython is a direct file reference, so it is never looked up on an index whatever someone
     * publishes under that name; pyrefly comes from PyPI, and every line carries its hashes.
     */
    @Test
    fun `the requirements pin typedpython to the unzipped wheel and pyrefly to the manifest's hashes`() {
        val wheel = File(root, "typedpython-0.1.0-py3-none-any.whl").apply { writeText("wheel") }
        val manifest = parseTypedpythonGateManifest(manifestJson)

        val lines = typedpythonGateRequirements(manifest, root).lines().filter { it.isNotBlank() }

        assertEquals(2, lines.size, lines.joinToString("\n"))
        assertEquals("typedpython @ ${wheel.toPath().toUri()} --hash=sha256:${"a".repeat(64)}", lines[0])
        assertTrue(lines[1].startsWith("pyrefly==1.3.2 "), lines[1])
        assertEquals(5, Regex("--hash=sha256:[0-9a-f]{64}").findAll(lines[1]).count(), lines[1])
    }

    @Test
    fun `the requirements are refused when the zip lacks the wheel the manifest names`() {
        val error = assertFailsWith<IllegalStateException> {
            typedpythonGateRequirements(parseTypedpythonGateManifest(manifestJson), root)
        }
        assertTrue("typedpython-0.1.0-py3-none-any.whl" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `the install requires hashes and reads only the generated requirements`() {
        assertEquals(
            listOf("uv", "pip", "install", "--python", "/b/venv", "--require-hashes", "-r", "/b/gate-requirements.txt"),
            typedpythonHashedInstallCommand(File("/b/venv"), File("/b/gate-requirements.txt")),
        )
    }

    @Test
    fun `pyrefly's wheel for the host is found by os and architecture, or the platform is named`() {
        val manifest = parseTypedpythonGateManifest(manifestJson)

        assertEquals("pyrefly-1.3.2-py3-none-macosx_11_0_arm64.whl", pyreflyWheelForHost(manifest, "Mac OS X", "aarch64"))
        assertEquals("pyrefly-1.3.2-py3-none-macosx_10_12_x86_64.whl", pyreflyWheelForHost(manifest, "Mac OS X", "x86_64"))
        assertEquals(
            "pyrefly-1.3.2-py3-none-manylinux_2_17_x86_64.manylinux2014_x86_64.whl",
            pyreflyWheelForHost(manifest, "Linux", "amd64"),
        )
        assertEquals("pyrefly-1.3.2-py3-none-win_amd64.whl", pyreflyWheelForHost(manifest, "Windows 11", "amd64"))
        assertNull(pyreflyWheelForHost(manifest, "Windows 11", "aarch64"))
        assertNull(pyreflyWheelForHost(manifest, "FreeBSD", "amd64"))

        val message = pyreflyNoWheelMessage(manifest, "FreeBSD", "amd64")
        assertTrue("FreeBSD" in message && "amd64" in message && "pyrefly 1.3.2" in message, message)
    }

    @Test
    fun `the skip warning names the gate artifact and both ways to provide it`() {
        val warning = typedpythonSkippedWarning("0.1.0", "1.3.2")
        assertTrue("SKIPPED" in warning && "NOT type-checked" in warning, warning)
        assertTrue("python-multiplatform-ksp" in warning && "typedpython-gate" in warning, warning)
        assertTrue("-Ptypedpython.gate" in warning && "-Ptypedpython.wheelDir" in warning, warning)
    }
}
