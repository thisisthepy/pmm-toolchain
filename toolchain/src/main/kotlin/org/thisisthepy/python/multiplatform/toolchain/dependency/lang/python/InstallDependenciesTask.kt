package org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python

import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendType
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendInterface as DependencyBackend
import java.io.File

/**
 * Delegates "install these Python dependencies" to `pypackpack`'s `uv` dependency backend
 * (`org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendInterface.create(BackendType.UV)`)
 * instead of hand-rolling a requirements file and shelling out to a `uv install` subcommand that
 * does not exist (`uv` has no `install` verb; the previous implementation was never exercised end to
 * end -- see `BuildPythonArtifactTask`'s kdoc and this module's report for why nothing reached this
 * task's execution before `usage-example` applied the plugin).
 *
 * `pypackpack` owns dependency resolution (`docs/ecosystem.md` §1, §5); this task's only job is
 * translating this project's Gradle-side inputs (the DSL-resolved dependency list, the `ppp`
 * package directory) into a real `uv add` and surfacing the result through Gradle's task machinery.
 *
 * Deliberately calls the `dependency.backend` layer (explicit `workingDir: File?` parameter) rather
 * than the higher-level `dependency.frontend`/`dependency.middleware` layers `pypackpack`'s CLI
 * uses: those resolve the target project by walking up from the JVM-global
 * `System.getProperty("user.dir")` (`pypackpack`'s internal `findProjectRoot()`), which a Gradle
 * daemon cannot safely mutate -- it is a single process that may run tasks from unrelated projects,
 * and possibly in parallel, sharing that one property. The backend layer takes the package directory
 * as an explicit parameter instead, which is what [BuildPythonArtifactTask] and its `BundleRequest`
 * already do for bundling; this keeps both delegations equally safe for concurrent Gradle execution.
 */
open class InstallDependenciesTask : DefaultTask() {
    // `@Internal`, matching `BuildPythonArtifactTask`'s fix for the same problem: Gradle 8's task
    // property validation (execution-time, not compile-time) rejects any public task property with
    // no annotation at all. This surfaced only once `usage-example` started applying the plugin and
    // actually running `installPythonDependencies` as part of the `packagePython` chain -- nothing
    // had ever reached this task's execution before. `@Internal` is the honest answer for now; this
    // property does not participate in up-to-date checking.
    @get:Internal
    var dependenciesList: List<String> = emptyList()

    /**
     * The `integration(...)` subset of [dependenciesList] (which holds them too, installed like
     * `implementation`). After installation each is checked for a `KLIBDEPENS` file
     * ([findIntegrationsWithoutKlibDepens]). `@Internal`: it only drives a warning.
     */
    @get:Internal
    var integrationsList: List<String> = emptyList()

    /**
     * The `pypackpack` package directory dependencies are added to: must already contain a
     * `pyproject.toml` (`uv add` requires one). `null` mirrors `BuildPythonArtifactTask.packageDir`
     * -- no package configured yet means installation is skipped rather than failed.
     */
    @get:Internal
    var packageDir: File? = null

    /** `uv add` options from `defaultConfig { pip { … } }` ([resolvePipSettings]). */
    @get:Input
    var pipArguments: Map<String, String> = emptyMap()

    /** Why the pip settings cannot be honoured; fails this task only when it has something to install. */
    @get:Input
    @get:Optional
    var pipRejection: String? = null

    /**
     * Why `buildFeatures { compose = true }` cannot add `pythonx-compose` (a missing or unusable
     * `python.compose.pythonxCompose`; `resolveComposePythonInstall`). Thrown from this task's action,
     * not at configuration, so builds that never install still run.
     */
    @get:Input
    @get:Optional
    var composeRejection: String? = null

    @TaskAction
    fun installDependencies() {
        logger.lifecycle("Installing Python dependencies using uv...")

        // A compose rejection means `pythonx-compose` should have been in the list: there is
        // something to install, so an otherwise empty list is not a skip.
        if (dependenciesList.isEmpty() && composeRejection == null) {
            logger.lifecycle("No dependencies specified, skipping.")
            return
        }
        val dir = packageDir
        if (dir == null) {
            logger.lifecycle(
                "No Python package directory configured (python.localLibraryPath); " +
                    "skipping packpack dependency installation.",
            )
            return
        }

        composeRejection?.let { throw GradleException(it) }
        pipRejection?.let { throw GradleException(it) }

        val output = installWithPackpack(dir, dependenciesList, pipArguments)
        logger.lifecycle("Installed ${dependenciesList.size} dependenc(y/ies) via packpack's uv backend: $output")

        if (integrationsList.isNotEmpty()) {
            val sitePackages = findSitePackages(File(dir, ".venv"))
            if (sitePackages == null) {
                logger.warn(
                    "Cannot check integration() packages for $KLIBDEPENS_FILE_NAME: no site-packages " +
                        "under ${File(dir, ".venv")}.",
                )
            } else {
                findIntegrationsWithoutKlibDepens(sitePackages, integrationsList)
                    .forEach { logger.warn(klibDepensWarning(it)) }
            }
        }
    }
}

/**
 * The actual delegation to `pypackpack`, factored out of
 * [InstallDependenciesTask.installDependencies] so it can be exercised without a Gradle
 * [org.gradle.api.Project] -- see `InstallDependenciesTaskTest`, which is what proves this call is
 * real (produces a genuine `uv add` mutation to `pyproject.toml`) rather than merely compiling
 * against the dependency.
 *
 * Empty [dependencies] is a no-op (returns `""`) without touching `pypackpack` at all -- `uv add`
 * with no packages is not a meaningful call, and the middleware layer this mirrors
 * (`dependency.middleware.DefaultInterface.addDependencies`) treats it as a failure rather than a
 * no-op, which would be the wrong signal for a Gradle task with nothing to install.
 */
fun installWithPackpack(
    packageDir: File,
    dependencies: List<String>,
    extraArgs: Map<String, String> = emptyMap(),
): String {
    if (dependencies.isEmpty()) return ""

    val backend = DependencyBackend.create(BackendType.UV)
    backend.initialize()
    return runBlocking {
        backend.addDependencies(
            packageName = null,
            dependencies = dependencies,
            extraArgs = extraArgs,
            workingDir = packageDir,
        )
    }.getOrElse { error ->
        throw GradleException("packpack dependency installation failed: ${error.message}", error)
    }
}
