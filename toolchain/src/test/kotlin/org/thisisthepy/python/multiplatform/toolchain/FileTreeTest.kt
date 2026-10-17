package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.bundle.PythonStagingLayout
import org.thisisthepy.python.multiplatform.toolchain.bundle.stagePythonPayload
import org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python.installDependenciesForTarget
import org.thisisthepy.python.multiplatform.toolchain.typedpython.ensureTypedpythonGateFromArchive
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deleting a directory never follows a symbolic link out of it (pypackpack#96, the same defect here). Kotlin's
 * `deleteRecursively` walks into a link to a directory and empties its target.
 */
class FileTreeTest {
    // Inside this module's build directory (AGENTS.md §2).
    private val root = File("build/file-tree-test").absoluteFile.apply { deleteWithoutFollowingLinks(); mkdirs() }

    @AfterTest
    fun cleanup() {
        root.deleteWithoutFollowingLinks()
    }

    /** `<dir>/link` -> `<root>/outside`, which holds `keep.txt`. */
    private fun linkOutside(dir: File): File {
        val outside = File(root, "outside").apply { mkdirs() }
        File(outside, "keep.txt").writeText("keep")
        dir.mkdirs()
        Files.createSymbolicLink(File(dir, "link").toPath(), outside.toPath())
        return File(outside, "keep.txt")
    }

    @Test
    fun `a link inside the tree is removed and its target kept`() {
        val tree = File(root, "tree")
        val keep = linkOutside(File(tree, "a"))
        File(tree, "a/file.txt").writeText("x")

        assertTrue(tree.deleteWithoutFollowingLinks())

        assertFalse(tree.exists())
        assertEquals("keep", keep.readText())
        assertTrue(File(root, "missing").deleteWithoutFollowingLinks())
    }

    @Test
    fun `restaging the payload does not follow a link out of the previous one`() {
        val bundle = File(root, "bundle/python/pkg").apply { mkdirs() }
        File(bundle, "__init__.py").writeText("")
        val destination = File(root, "destination")
        val keep = linkOutside(File(destination, PythonStagingLayout.PAYLOAD_ROOT))

        stagePythonPayload(File(root, "bundle"), destination, PythonStagingLayout.PAYLOAD_ROOT)

        assertEquals("keep", keep.readText())
    }

    @Test
    fun `clearing a target's install directory does not follow a link out of it`() {
        val installDir = File(root, "install")
        val keep = linkOutside(installDir)

        installDependenciesForTarget(emptyList(), "aarch64-linux-android", installDir, File(root, "requirements"))

        assertEquals("keep", keep.readText())
    }

    @Test
    fun `clearing the typedpython gate directory does not follow a link out of it`() {
        val gateDir = File(root, "gate")
        val keep = linkOutside(gateDir)
        val archive = File(root, "gate.zip")
        ZipOutputStream(archive.outputStream()).use { it.putNextEntry(ZipEntry("empty.txt")); it.closeEntry() }

        runCatching { ensureTypedpythonGateFromArchive(File(root, "venv"), archive, gateDir) }

        assertEquals("keep", keep.readText())
    }
}
