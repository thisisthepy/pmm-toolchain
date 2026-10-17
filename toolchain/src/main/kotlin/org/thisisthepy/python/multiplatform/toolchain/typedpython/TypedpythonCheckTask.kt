package org.thisisthepy.python.multiplatform.toolchain.typedpython

import org.thisisthepy.python.multiplatform.toolchain.deleteWithoutFollowingLinks
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendType
import java.io.File
import java.io.IOException

/** `docs/SPEC.md` §1.18. The gate's version until a newer one is pinned here. */
const val DEFAULT_TYPEDPYTHON_VERSION = "0.1.0"
/** The `pyrefly` the gate pins (`typedpython` 0.1.0 `Requires-Dist: pyrefly==1.3.2`). */
const val DEFAULT_PYREFLY_VERSION = "1.3.2"
const val DEFAULT_TYPEDPYTHON_MODE = "checked"
val TYPEDPYTHON_MODES = listOf("checked", "compiled")

/** The `typedpython` 0.1.0 wheel's own `Requires-Python`. The gate's venv is created for it. */
const val TYPEDPYTHON_PYTHON_REQUEST = ">=3.13"

/** Project property naming a directory holding both wheels; overrides the gate artifact. */
const val TYPEDPYTHON_WHEEL_DIR_PROPERTY = "typedpython.wheelDir"

const val TYPEDPYTHON_NO_STUBS_NOTICE =
    "typedpythonCheck: the typedpythonStubs configuration is empty; checking without stubs."

/**
 * Statically checks the project's own Python with the TypedPython gate before it is bundled
 * (issue `toolchain#23`, `docs/SPEC.md` section 1.18).
 *
 * The gate's venv ([venvDir], `build/typedpython/venv`) is created through `pypackpack`'s UV backend
 * (`createVirtualEnvironment`). The install into it calls `uv` directly: the backend has no operation
 * that installs named requirements into an existing venv from a local wheel directory.
 * `installDependenciesToTarget` always reads `-r pyproject.toml` into a `--target` directory, and
 * `addDependencies` edits `pyproject.toml`. What is missing is a `pip install <requirements>
 * --python <venv> [--no-index] [--find-links <dir>]` operation; with it, [ensureTypedpythonGate] is
 * the one place to switch. That is a `pypackpack` change, proposed rather than made here.
 */
abstract class TypedpythonCheckTask : DefaultTask() {
    /** The `.py`/`.pyi` files of the package directory -- see [typedpythonSourceExcluded]. */
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Where [sources] come from. Location only: its Python content is fingerprinted by [sources]. */
    @get:Internal
    var packageDir: File? = null

    @get:Input
    abstract val mode: Property<String>

    @get:Input
    abstract val gateVersion: Property<String>

    /** Installed explicitly (`pyrefly==<v>`) so the check re-runs when it changes. */
    @get:Input
    abstract val pyreflyVersion: Property<String>

    /**
     * The `typedpython-gate` zip resolved from [gateCoordinates] (#90), empty when the project's
     * repositories do not have it.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val gateArchive: ConfigurableFileCollection

    /** `group:artifact:version` of the gate ([DEFAULT_TYPEDPYTHON_GATE] or `-Ptypedpython.gate`). */
    @get:Input
    abstract val gateCoordinates: Property<String>

    /** Where [gateArchive] is unzipped. Derived from it, so not an input of its own. */
    @get:Internal
    abstract val gateDir: DirectoryProperty

    /** `-Ptypedpython.wheelDir`, resolved against the project directory. */
    @get:Input
    @get:Optional
    abstract val wheelDir: Property<String>

    /** The `typedpythonStubs` configuration's files, each passed as `--search-path`, in order. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stubDirs: ConfigurableFileCollection

    /** Where the gate is installed. Never the project's `.venv`. */
    @get:Internal
    abstract val venvDir: DirectoryProperty

    /** Written only by a passing run, so an unchanged rerun is `UP-TO-DATE`. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val checkMode = mode.get()
        validateTypedpythonMode(checkMode)
        val reportFile = report.get().asFile
        val dir = packageDir
        val files = dir?.let { collectTypedpythonSources(it) }.orEmpty()
        if (dir == null || files.isEmpty()) {
            logger.lifecycle("typedpythonCheck: no .py file in the Python package directory; nothing to check.")
            reportFile.parentFile.mkdirs()
            reportFile.writeText("no files checked\n")
            return
        }

        // Before anything is created or run. `typedpython` is not ours on PyPI, so it is installed from
        // a wheel directory (-Ptypedpython.wheelDir) or from the gate artifact as a hash-pinned file,
        // never by name from an index. With neither: skip, loudly.
        val wheels = wheelDir.orNull
        val archive = gateArchive.files.firstOrNull { it.isFile }
        val venv = venvDir.get().asFile
        val executable = when {
            wheels != null -> ensureTypedpythonGate(venv, gateVersion.get(), pyreflyVersion.get(), wheels)
            archive != null -> ensureTypedpythonGateFromArchive(venv, archive, gateDir.get().asFile)
            else -> {
                logger.warn(typedpythonSkippedWarning(gateVersion.get(), pyreflyVersion.get()))
                reportFile.parentFile.mkdirs()
                reportFile.writeText("skipped: no gate artifact and no -P$TYPEDPYTHON_WHEEL_DIR_PROPERTY\n")
                return
            }
        }

        val stubs = stubDirs.files.toList()
        if (stubs.isEmpty()) logger.lifecycle(TYPEDPYTHON_NO_STUBS_NOTICE)
        val command = typedpythonCheckCommand(executable, checkMode, listOf(typedpythonImportRoot(dir)) + stubs, files)
        logger.info("typedpythonCheck: ${command.joinToString(" ")}")

        val work = venv.parentFile
        // The gate writes a Pyrefly config into a temporary directory; keep it under build/.
        val tmp = File(work, "tmp").apply { mkdirs() }
        val stdoutFile = File(work, "check.stdout")
        val stderrFile = File(work, "check.stderr")
        val exitCode = ProcessBuilder(command)
            .directory(project.projectDir)
            .redirectOutput(stdoutFile)
            .redirectError(stderrFile)
            .apply { environment()["TMPDIR"] = tmp.absolutePath }
            .start()
            .waitFor()

        when (val result = interpretTypedpythonResult(exitCode, stdoutFile.readText(), stderrFile.readText())) {
            is TypedpythonResult.Passed -> {
                result.warnings.forEach { logger.warn(it) }
                reportFile.writeText(
                    "typedpython $checkMode: ${files.size} file(s), 0 errors, ${result.warnings.size} warning(s)\n" +
                        result.warnings.joinToString("") { "$it\n" },
                )
            }
            is TypedpythonResult.TypeErrors -> {
                result.warnings.forEach { logger.warn(it) }
                throw GradleException(
                    "typedpython ($checkMode) found ${result.errors.size} error(s):\n" +
                        result.errors.joinToString("\n"),
                )
            }
            is TypedpythonResult.ToolFailure ->
                throw GradleException("typedpython could not check the package (exit $exitCode):\n${result.message}")
        }
    }
}

/**
 * Installs `typedpython==<version>` and `pyrefly==<pyreflyVersion>` into [venv] from [wheelDir] only
 * (`--no-index`) unless the same request is already installed there, and returns its console script.
 */
fun ensureTypedpythonGate(venv: File, version: String, pyreflyVersion: String, wheelDir: String): File {
    val executable = typedpythonExecutable(venv)
    val marker = File(venv, ".typedpython-request")
    val request = "typedpython==$version pyrefly==$pyreflyVersion no-index find-links=$wheelDir"
    if (executable.isFile && marker.isFile && marker.readText() == request) return executable

    val work = venv.parentFile.apply { mkdirs() }
    createTypedpythonVenv(venv, work)
    runUv(typedpythonInstallCommand(venv, version, pyreflyVersion, wheelDir), work).let { (exit, output) ->
        if (exit != 0) {
            throw GradleException(
                "Could not install typedpython==$version and pyrefly==$pyreflyVersion into $venv from $wheelDir " +
                    "(uv exit $exit, --no-index):\n$output\n" +
                    typedpythonWheelInstruction(version, pyreflyVersion),
            )
        }
    }
    if (!executable.isFile) throw GradleException("typedpython was installed into $venv but $executable does not exist")
    marker.writeText(request)
    return executable
}

/**
 * Unzips the `typedpython-gate` [archive] into [gateDir], then installs typedpython from it and pyrefly
 * from PyPI, both pinned by the manifest's hashes (`--require-hashes`), unless the same requirements are
 * already installed. A host without a pyrefly wheel in the manifest fails, naming the platform.
 */
fun ensureTypedpythonGateFromArchive(venv: File, archive: File, gateDir: File): File {
    gateDir.deleteWithoutFollowingLinks()
    gateDir.mkdirs()
    java.util.zip.ZipFile(archive).use { zip ->
        zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
            val target = File(gateDir, entry.name).canonicalFile
            check(target.toPath().startsWith(gateDir.canonicalFile.toPath())) { "Entry ${entry.name} leaves $gateDir" }
            target.parentFile.mkdirs()
            zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }
    val manifestFile = File(gateDir, TYPEDPYTHON_GATE_MANIFEST)
    if (!manifestFile.isFile) throw GradleException("$archive has no $TYPEDPYTHON_GATE_MANIFEST at its root.")
    val manifest = try {
        parseTypedpythonGateManifest(manifestFile.readText())
    } catch (e: IllegalArgumentException) {
        throw GradleException("$archive: ${e.message}", e)
    }
    val osName = System.getProperty("os.name").orEmpty()
    val osArch = System.getProperty("os.arch").orEmpty()
    if (pyreflyWheelForHost(manifest, osName, osArch) == null) {
        throw GradleException(pyreflyNoWheelMessage(manifest, osName, osArch))
    }
    val requirementsText = try {
        typedpythonGateRequirements(manifest, gateDir)
    } catch (e: IllegalStateException) {
        throw GradleException(e.message.orEmpty(), e)
    }

    val executable = typedpythonExecutable(venv)
    val marker = File(venv, ".typedpython-request")
    if (executable.isFile && marker.isFile && marker.readText() == requirementsText) return executable

    val work = venv.parentFile.apply { mkdirs() }
    createTypedpythonVenv(venv, work)
    val requirements = File(work, "gate-requirements.txt").apply { writeText(requirementsText) }
    runUv(typedpythonHashedInstallCommand(venv, requirements), work).let { (exit, output) ->
        if (exit != 0) {
            throw GradleException(
                "Could not install typedpython ${manifest.typedpythonVersion} (from $archive) and pyrefly " +
                    "${manifest.pyreflyVersion} (from PyPI) into $venv with --require-hashes (uv exit $exit):\n$output",
            )
        }
    }
    if (!executable.isFile) throw GradleException("typedpython was installed into $venv but $executable does not exist")
    marker.writeText(requirementsText)
    return executable
}

fun typedpythonWheelInstruction(version: String, pyreflyVersion: String): String =
    "The directory named by -P$TYPEDPYTHON_WHEEL_DIR_PROPERTY=<directory> must hold the " +
        "typedpython-$version wheel and the pyrefly-$pyreflyVersion wheel for this platform; nothing " +
        "is fetched from an index."

/**
 * The warning printed when the check is skipped for want of a wheel directory. It says what was
 * skipped, why, and how to turn the check on. The gate is never installed from an index: `typedpython`
 * is unclaimed on PyPI, so a bare-name install would run whatever someone publishes under that name.
 */
fun typedpythonSkippedWarning(version: String, pyreflyVersion: String): String =
    "\n!!! typedpythonCheck SKIPPED: the project's Python was NOT type-checked !!!\n" +
        "Why: the gate artifact ($DEFAULT_TYPEDPYTHON_GATE:$TYPEDPYTHON_GATE_CLASSIFIER@zip, or the one " +
        "-P$TYPEDPYTHON_GATE_PROPERTY names) is not in this project's repositories, and the typedpython gate " +
        "is never installed by name from an index (a package of that name there would not be ours).\n" +
        "To turn the check on: add the repository that has the gate (python-multiplatform's " +
        "`./gradlew publishAllToMavenLocal` puts it in mavenLocal()), or pass " +
        "-P$TYPEDPYTHON_WHEEL_DIR_PROPERTY=<directory>. " +
        typedpythonWheelInstruction(version, pyreflyVersion) + "\n"

/**
 * The directories whose Python is checked: the bundled package directory only. `libDirs` (third-party
 * site-packages) and `metaDirs` (generated metadata) are bundled too but are not code the user wrote.
 */
fun typedpythonCheckedDirs(packageDir: File, metaDirs: List<File>, libDirs: List<File>): List<File> = listOf(packageDir)

/** Creates [venv] through pypackpack's UV backend (AGENTS.md section 13) unless it exists. */
private fun createTypedpythonVenv(venv: File, work: File) {
    if (File(venv, "pyvenv.cfg").isFile) return
    val created = runBlocking {
        BackendInterface.create(BackendType.UV).createVirtualEnvironment(
            path = venv.path,
            pythonVersion = TYPEDPYTHON_PYTHON_REQUEST,
            extraArgs = typedpythonVenvOptions(),
            workingDir = work,
        )
    }
    created.onFailure { throw GradleException("Could not create the typedpython venv at $venv: ${it.message}", it) }
}

/** `uv venv` options for the gate: never the user's `pyproject.toml` project, and a fresh venv. */
fun typedpythonVenvOptions(): Map<String, String> = linkedMapOf("no-project" to "", "clear" to "")

/** `--no-index`: both packages come from [wheelDir], so nothing can be fetched from PyPI. */
fun typedpythonInstallCommand(venv: File, version: String, pyreflyVersion: String, wheelDir: String): List<String> =
    listOf(
        "uv", "pip", "install", "--python", venv.path, "--no-index", "--find-links", wheelDir,
        "typedpython==$version", "pyrefly==$pyreflyVersion",
    )

private fun runUv(command: List<String>, workingDir: File): Pair<Int, String> {
    val process = try {
        ProcessBuilder(command).directory(workingDir).redirectErrorStream(true).start()
    } catch (e: IOException) {
        throw GradleException("typedpythonCheck needs uv on PATH to install the gate: ${e.message}", e)
    }
    val output = process.inputStream.bufferedReader().readText()
    return process.waitFor() to output
}

private fun typedpythonExecutable(venv: File): File =
    if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
        File(venv, "Scripts/typedpython.exe")
    } else {
        File(venv, "bin/typedpython")
    }

/** Directory names under the package directory whose contents are never user code. */
fun typedpythonSourceExcluded(directoryName: String): Boolean =
    directoryName.startsWith(".") || directoryName == "__pycache__" || directoryName == "build"

/** The `.py` files the gate is given, sorted. Empty when [packageDir] does not exist. */
fun collectTypedpythonSources(packageDir: File): List<File> {
    if (!packageDir.isDirectory) return emptyList()
    return packageDir.walkTopDown()
        .onEnter { it == packageDir || !typedpythonSourceExcluded(it.name) }
        .filter { it.isFile && it.extension == "py" }
        .sortedBy { it.relativeTo(packageDir).invariantSeparatorsPath }
        .toList()
}

/**
 * The package's own import root: `src/main` in the `pypackpack` layout, else the package directory.
 * The 0.1.0 gate otherwise takes its temporary config directory as the import root, and the
 * package's modules cannot import one another.
 */
fun typedpythonImportRoot(packageDir: File): File =
    File(packageDir, "src/main").takeIf { it.isDirectory } ?: packageDir

fun validateTypedpythonMode(mode: String) {
    require(mode in TYPEDPYTHON_MODES) {
        "typedpythonCheck mode '$mode' is not a TypedPython mode; use one of ${TYPEDPYTHON_MODES.joinToString()}."
    }
}

/** Files, not directories: the 0.1.0 gate crashes (`IsADirectoryError`) on a directory argument. */
fun typedpythonCheckCommand(executable: File, mode: String, searchPaths: List<File>, files: List<File>): List<String> =
    buildList {
        addAll(listOf(executable.path, "check", "--mode", mode))
        searchPaths.forEach { addAll(listOf("--search-path", it.path)) }
        files.forEach { add(it.path) }
    }

sealed interface TypedpythonResult {
    data class Passed(val warnings: List<String>) : TypedpythonResult
    data class TypeErrors(val errors: List<String>, val warnings: List<String>) : TypedpythonResult
    data class ToolFailure(val message: String) : TypedpythonResult
}

/**
 * Exit 0 clean, 1 errors, 2 the gate could not run. An exit 1 with no `ERROR` line is how an
 * uncaught Python exception exits, so it is a tool failure too.
 */
fun interpretTypedpythonResult(exitCode: Int, stdout: String, stderr: String): TypedpythonResult {
    // A diagnostic is its `ERROR `/`WARNING ` line plus the indented lines after it: a multi-line
    // Pyrefly message puts its `[rule]` on the last of them.
    val diagnostics = mutableListOf<String>()
    stdout.lines().forEach { line ->
        when {
            line.startsWith("ERROR ") || line.startsWith("WARNING ") -> diagnostics.add(line)
            diagnostics.isNotEmpty() && line.isNotBlank() -> diagnostics[diagnostics.lastIndex] += "\n$line"
        }
    }
    val errors = diagnostics.filter { it.startsWith("ERROR ") }
    val warnings = diagnostics.filter { it.startsWith("WARNING ") }
    return when {
        exitCode == 0 -> TypedpythonResult.Passed(warnings)
        exitCode == 1 && errors.isNotEmpty() -> TypedpythonResult.TypeErrors(errors, warnings)
        else -> TypedpythonResult.ToolFailure(
            listOf(stderr.trim(), stdout.trim()).filter { it.isNotEmpty() }.joinToString("\n")
                .ifEmpty { "(no output)" },
        )
    }
}
