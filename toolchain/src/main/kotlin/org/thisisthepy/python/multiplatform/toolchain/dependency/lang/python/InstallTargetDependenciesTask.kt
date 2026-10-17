package org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python

import org.thisisthepy.python.multiplatform.toolchain.deleteWithoutFollowingLinks
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendType
import java.io.File
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendInterface as DependencyBackend

/**
 * Installs one dependency set's requirements for one target triple into [installDir], through
 * `pypackpack`'s `UVBackend.installDependenciesToTarget` (`uv pip install <requirements…> --target
 * <dir> --python-platform <triple>`). docs/SPEC.md §1.10, issue #16.
 *
 * One task per *dependency set* -- a platform variant crossed with a flavor -- not per bundle variant:
 * the build type changes neither the requirement list nor the triple, so `debug` and `release` of
 * `androidArm64` share `installPythonDependenciesAndroidArm64` and `build/pythonDeps/androidArm64/`
 * instead of installing the same wheels twice. The host chain has `installPythonDependenciesHost`.
 *
 * [installDir] is a `libDirs` entry of every bundle task of the set, so `pypackpack`'s
 * `ResourceBundler` merges the installed packages into the payload's `python/`.
 *
 * Everything that decides the output is an `@Input`, so a changed requirement, triple, Python version
 * or pip option re-installs (AGENTS.md §15). Two things are not inputs, deliberately: the contents of a
 * `find-links` directory, and what an index serves for an unpinned requirement -- like any Gradle
 * task that downloads, a new upstream release does not make it out of date. `pip { autoUpdate = true }`
 * (`--upgrade`) is the DSL's way to ask for that, so it makes the task never up to date.
 */
open class InstallTargetDependenciesTask : DefaultTask() {
    /** Requirements in PEP 508 form, from `collectTargetDependencies` (plus compose's `pythonx-compose`). */
    @get:Input
    var requirements: List<String> = emptyList()

    /** The `--python-platform` value: the variant's canonical `pypackpack` triple, or the host's. */
    @get:Input
    var pythonPlatform: String = ""

    /** `uv pip install` options from `targetInstallArguments`: pip repositories, `python-version`, `only-binary`. */
    @get:Input
    var installArguments: Map<String, String> = emptyMap()

    /**
     * A reason this set cannot be installed whatever it holds (a compose rejection, dependencies in a
     * source set no variant reads). Fails this task's action, not configuration (AGENTS.md §14).
     */
    @get:Input
    @get:Optional
    var rejection: String? = null

    /** Why the pip settings cannot be honoured; fails this task only when it has something to install. */
    @get:Input
    @get:Optional
    var pipRejection: String? = null

    /** `build/pythonDeps/<dependency set>/`. Cleared before every install, so a removed requirement leaves. */
    @get:OutputDirectory
    lateinit var installDir: File

    init {
        outputs.upToDateWhen { !(it as InstallTargetDependenciesTask).installArguments.containsKey("upgrade") }
    }

    @TaskAction
    fun install() {
        rejection?.let { throw GradleException(it) }
        if (requirements.isNotEmpty()) pipRejection?.let { throw GradleException(it) }

        val output = installDependenciesForTarget(requirements, pythonPlatform, installDir, temporaryDir, installArguments)
        if (requirements.isEmpty()) {
            logger.lifecycle("No Python dependencies for $pythonPlatform; $installDir is left empty.")
        } else {
            logger.lifecycle(
                "Installed ${requirements.size} Python requirement(s) for $pythonPlatform into $installDir " +
                    "via packpack's uv backend: $output",
            )
        }
    }
}

/**
 * The delegation to `pypackpack`, factored out of [InstallTargetDependenciesTask.install] so it runs
 * without a Gradle project -- `InstallTargetDependenciesTaskTest` drives a real install with it.
 *
 * [installDir] is emptied first (and created), so it holds exactly this call's packages: `uv pip
 * install --target` adds to a directory and never removes, and a requirement dropped from the DSL
 * must leave the bundle. An empty [requirements] stops there, without calling `uv`.
 *
 * Otherwise [requirements] go to `installDependenciesToTarget(requirements = …)` (pypackpack#36), which
 * passes them to `uv` as arguments; nothing is written. [requirementsDir] is only the directory `uv`
 * runs in, kept outside [installDir] so nothing `uv` might leave there is bundled.
 *
 * A failure -- including a requirement with no wheel for [pythonPlatform] under `only-binary` --
 * becomes a [GradleException] carrying uv's message, which names the package.
 */
fun installDependenciesForTarget(
    requirements: List<String>,
    pythonPlatform: String,
    installDir: File,
    requirementsDir: File,
    extraArgs: Map<String, String> = emptyMap(),
): String {
    require(!requirementsDir.canonicalPath.startsWith(installDir.canonicalPath + File.separator) &&
        requirementsDir.canonicalFile != installDir.canonicalFile) {
        "the requirements directory $requirementsDir must not be inside the install directory $installDir"
    }
    if (installDir.exists() && !installDir.deleteWithoutFollowingLinks()) {
        throw GradleException("could not clear $installDir before installing Python dependencies into it")
    }
    installDir.mkdirs()
    if (requirements.isEmpty()) return ""

    requirementsDir.mkdirs()

    val backend = DependencyBackend.create(BackendType.UV)
    backend.initialize()
    return runBlocking {
        backend.installDependenciesToTarget(
            targetDir = installDir.absolutePath,
            pythonPlatform = pythonPlatform,
            extraArgs = extraArgs,
            workingDir = requirementsDir,
            requirements = requirements,
        )
    }.getOrElse { error ->
        throw GradleException(
            "packpack could not install ${requirements.joinToString(", ")} for $pythonPlatform: ${error.message}",
            error,
        )
    }
}
