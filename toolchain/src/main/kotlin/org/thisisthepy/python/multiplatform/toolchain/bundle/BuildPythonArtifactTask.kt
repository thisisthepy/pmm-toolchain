package org.thisisthepy.python.multiplatform.toolchain.bundle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.thisisthepy.python.multiplatform.packpack.bundle.BundlerInterface
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleRequest
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleResult
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonVersion
import org.thisisthepy.python.multiplatform.toolchain.resolveBuildLevel
import java.io.File

/**
 * Delegates "build a Python bundle" to `pypackpack`'s `resource` bundler
 * (`org.thisisthepy.python.multiplatform.packpack.bundle.resource.ResourceBundler`, reached
 * through `BundlerInterface.create(BundleType.RESOURCE)`) instead of hand-copying files.
 *
 * `pypackpack` owns Python distribution acquisition, dependency resolution and bundling
 * (`docs/ecosystem.md` §1, §5: "ppp owns the work, toolchain owns the Gradle vocabulary"); this
 * task's only job is translating this project's Gradle-side inputs (`python.localLibraryPath`, a
 * target triple, the Gradle build type) into a [BundleRequest] and surfacing the result through
 * Gradle's task machinery. The previous implementation copied whatever
 * `python.localLibraryPath` held into `build/pythonBundle/libs` verbatim; it did not resolve
 * dependencies, apply platform overlays, or produce a manifest -- all of which the delegated
 * `ResourceBundler` now does for real.
 */
open class BuildPythonArtifactTask : DefaultTask() {
    // `@Internal` on every property below, not `@Input`/`@InputDirectory`: Gradle 8's task property
    // validation (run at execution time, not compile time -- this is why the naive implementation
    // never surfaced it, since nothing had ever actually run this task before `usage-example`
    // started applying the plugin) rejects any public task property with no annotation at all.
    // `@Internal` is the honest answer for now -- none of these participate in up-to-date checking
    // yet, so marking them `@Input`/`@InputDirectory` would be a false promise of incremental
    // build correctness this task does not implement. Wiring real incrementality (so a
    // `ResourceBundler` re-run is skipped when `packageDir`'s contents have not changed) is left
    // for follow-up.
    @get:Internal
    var pythonVersion: String = "default"

    /**
     * Why `compileSdk` cannot be provided (a version python-multiplatform has no runtime for, which
     * would need an automatic CPython build). Fails this task only when it has a package to bundle.
     */
    @get:Input
    @get:Optional
    var pythonSdkRejection: String? = null

    /**
     * The resolved `compileSdk` (`PythonVersion.toString()`, e.g. `3.14.7`), `null` when none is
     * declared. Read only at `compileLevel = "bytecode"`, where a `.pyc`'s magic number ties it to
     * one CPython minor version: [bytecodeInterpreterRejection] refuses a `.venv` whose minor
     * version differs from this one.
     */
    @get:Input
    @get:Optional
    var compileSdkVersion: String? = null

    /** `defaultConfig { versionName; versionCode }`, the payload's version; forwarded to the manifest. */
    @get:Input
    @get:Optional
    var versionName: String? = null

    @get:Input
    @get:Optional
    var versionCode: Int? = null

    /** Why declared `projectFlavors` cannot apply (no platform variant); fails like [pythonSdkRejection]. */
    @get:Input
    @get:Optional
    var flavorRejection: String? = null

    /**
     * The `pypackpack` package directory: must contain `pyproject.toml` and a Python source root
     * (`src/main`, `src`, or the package root itself -- see `ResourceBundler`'s kdoc for the exact
     * fallback order). `null` means no package is configured yet, which is the case for any
     * consumer that has not set `python.localLibraryPath` to a real ppp package -- packaging is
     * skipped rather than failed, since not every project bundles Python resources.
     */
    @get:Internal
    var packageDir: File? = null

    /** A target string accepted by `Platforms.normalizeTarget` (alias or canonical triple). */
    @get:Internal
    var target: String = Platforms.detectHostTarget()

    @get:Internal
    var buildType: String = "debug"

    /**
     * The raw `python { buildTypes { getByName(...) { compileLevel = ... } } }` value for this
     * task's variant, resolved to `pypackpack`'s `BundleRequest.buildLevel` by
     * [org.thisisthepy.python.multiplatform.toolchain.resolveBuildLevel] **inside the task action,
     * not at configuration time**.
     *
     * Where that resolution happens is the whole point of the per-variant task graph. `pypackpack`'s
     * `ResourceBundler` implements `instant` and `bytecode` only, so a consumer declaring
     * `release { compileLevel = "native" }` has to be rejected somewhere. Rejecting in
     * `PythonPlugin.apply`'s `afterEvaluate` -- what this did before the graph existed -- fails *configuration*, which takes every other variant down with it.
     * Rejecting here fails one task, so `gradle packagePython --continue` still builds every variant
     * whose level `ppp` does support. That is Issue #2's "reject only that variant, let the rest
     * run" requirement, and it is only expressible because there is now one task per variant.
     *
     * Blank resolves to `"instant"`, which is what both `DebugBuildType` and `ReleaseBuildType`
     * default to and what this value was hard-coded to before.
     */
    @get:Internal
    var compileLevel: String = ""

    /**
     * Where this variant's bundle is written. `null` means `build/pythonBundle` -- the single
     * hard-coded location used before the variant graph existed, and still what the aggregate
     * `buildPython` uses when no `python { }` platform block is declared. A variant task
     * sets `build/pythonBundle/<platformVariant>-<buildType>` instead, so two variants cannot
     * overwrite each other's payload.
     */
    @get:Internal
    var bundleDir: File? = null

    /**
     * The declared platform min SDK for this variant (`android { androidSdk = 24 }` or
     * `ios { iosSdk = 14 }`), `null` when that platform declares none.
     *
     * Forwarded to `pypackpack` as `BundleRequest.minSdk` (added in `pypackpack`'s `6e36d3d`, after
     * this field existed here with nowhere to send it -- see git history for that gap). `ppp`'s
     * `ResourceBundler` validates it against the target's platform family and, when declared for an
     * android-family target, records it in the bundle manifest.
     */
    @get:Internal
    var minSdk: Int? = null

    /**
     * `python { sourceSets { commonMain { metaDirs(...) } } }`, resolved by
     * [org.thisisthepy.python.multiplatform.toolchain.resolveMetaDirs] and forwarded to `pypackpack`
     * as [BundleRequest.metaDirs] verbatim. Empty by default, matching every caller that declares no
     * `metaDirs` -- see [BundleRequest.metaDirs]'s kdoc for what it is: generated metadata (`.pyi`
     * stubs) merged into the resource bundle's payload.
     */
    @get:Internal
    var metaDirs: List<File> = emptyList()

    /**
     * `python { sourceSets { commonMain { libDirs(...) } } }`, resolved by
     * [org.thisisthepy.python.multiplatform.toolchain.resolveLibDirs] and forwarded to `pypackpack`
     * as [BundleRequest.libDirs] verbatim. Empty by default -- see [BundleRequest.libDirs]'s kdoc.
     */
    @get:Internal
    var libDirs: List<File> = emptyList()

    /**
     * Why `compileSdk` disagrees with python-multiplatform's `pythonVersion`, the interpreter and
     * stdlib python-multiplatform embeds at embedLevel 2 (`checkPythonVersionAgreement`, docs/SPEC.md
     * §1.12, issue #42). Fails this task like [pythonSdkRejection].
     */
    @get:Input
    @get:Optional
    var pythonVersionRejection: String? = null

    @TaskAction
    fun buildPython() {
        val bundleDir = bundleDir ?: File(project.layout.buildDirectory.get().asFile, "pythonBundle")
        val source = packageDir
        if (source == null) {
            // Preserves the previous naive implementation's behavior for this case: it always
            // created `build/pythonBundle` (empty when there was nothing to copy), which
            // `AssemblePythonPackageTask.copy()` requires to exist or it throws. Skipping
            // packpack here is correct -- there is no ppp package to bundle -- but the directory
            // still has to appear so the downstream `packagePython` Zip task keeps working for a
            // project that has not set `python.localLibraryPath` at all.
            bundleDir.mkdirs()
            logger.lifecycle(
                "No Python package directory configured (python.localLibraryPath); " +
                    "skipping packpack resource bundling.",
            )
            return
        }

        pythonSdkRejection?.let { throw GradleException(it) }
        flavorRejection?.let { throw GradleException(it) }
        pythonVersionRejection?.let { throw GradleException(it) }

        // Throws for anything `ppp`'s ResourceBundler cannot bundle. Deliberately here rather than
        // during configuration -- see `compileLevel`'s kdoc.
        val resolvedBuildLevel = resolveBuildLevel(compileLevel)
        if (resolvedBuildLevel == "bytecode") {
            bytecodeInterpreterRejection(source, compileSdkVersion?.let { PythonVersion.parse(it) })
                ?.let { throw GradleException(it) }
        }

        val result =
            bundleWithPackpack(
                source, target, buildType, bundleDir, resolvedBuildLevel, minSdk, metaDirs, libDirs,
                versionName, versionCode, compileSdkVersion?.let { wheelVersionOf(it) },
            )
        logger.lifecycle(
            "Bundled ${result.fileCount} file(s) for Python '$pythonVersion' via packpack's " +
                "'${result.bundleType.id}' bundler into ${result.outputDir} " +
                "(target $target, buildType $buildType, buildLevel $resolvedBuildLevel" +
                (minSdk?.let { ", declared minSdk $it" } ?: "") +
                ")",
        )
    }
}

/** The [BundleRequest] [bundleWithPackpack] hands pypackpack; separate so a test can read what is forwarded. */
fun bundleRequestFor(
    packageDir: File,
    target: String,
    buildType: String,
    outputDir: File,
    buildLevel: String = "instant",
    minSdk: Int? = null,
    metaDirs: List<File> = emptyList(),
    libDirs: List<File> = emptyList(),
    versionName: String? = null,
    versionCode: Int? = null,
    pythonVersion: String? = null,
): BundleRequest =
    BundleRequest(
        packageDir = packageDir,
        target = target,
        buildType = buildType,
        buildLevel = buildLevel,
        outputDir = outputDir,
        overwrite = true,
        minSdk = minSdk,
        metaDirs = metaDirs,
        libDirs = libDirs,
        versionName = versionName,
        versionCode = versionCode,
        pythonVersion = pythonVersion,
    )

/**
 * The actual delegation to `pypackpack`, factored out of [BuildPythonArtifactTask.buildPython] so
 * it can be exercised without a Gradle [org.gradle.api.Project] -- see
 * `BuildPythonArtifactTaskTest`, which is what proves this call is real (produces
 * `ResourceBundler`'s actual on-disk manifest and payload) rather than merely compiling against
 * the dependency.
 *
 * @param minSdk forwarded to [BundleRequest.minSdk] verbatim. `null` (the default) matches every
 *   caller that has no platform SDK to declare (e.g. a non-android target); `ppp`'s
 *   `Platforms.requireValidMinSdk` rejects a non-null value declared against a non-android-family
 *   [target], so callers should not pass one for those targets.
 * @param metaDirs forwarded to [BundleRequest.metaDirs] verbatim. Empty by default, matching every
 *   caller that declares no `python { sourceSets { commonMain { metaDirs(...) } } }`.
 * @param libDirs forwarded to [BundleRequest.libDirs] verbatim. Empty by default, matching every
 *   caller that declares no `python { sourceSets { commonMain { libDirs(...) } } }`.
 * @param pythonVersion forwarded to [BundleRequest.pythonVersion] verbatim (`3.15.0`, `3.15.0t`): the
 *   app's interpreter, which fixes the wheel tags of the wheel bundlers. `null` (no `compileSdk`)
 *   leaves pypackpack's own default.
 */
fun bundleWithPackpack(
    packageDir: File,
    target: String,
    buildType: String,
    outputDir: File,
    buildLevel: String = "instant",
    minSdk: Int? = null,
    metaDirs: List<File> = emptyList(),
    libDirs: List<File> = emptyList(),
    versionName: String? = null,
    versionCode: Int? = null,
    pythonVersion: String? = null,
): BundleResult {
    val request =
        bundleRequestFor(
            packageDir, target, buildType, outputDir, buildLevel, minSdk, metaDirs, libDirs,
            versionName, versionCode, pythonVersion,
        )
    return BundlerInterface.create(BundleType.RESOURCE)
        .bundle(request)
        .getOrElse { error ->
            throw GradleException("packpack resource bundling failed: ${error.message}", error)
        }
}

/** pypackpack's `ResourceBundler.locateBytecodeCompiler` candidates, in its order. */
private val VENV_INTERPRETERS = listOf("bin/python3", "bin/python", "Scripts/python.exe")

/**
 * The interpreter `pypackpack`'s `ResourceBundler` will compile `bytecode` with: the first
 * `<dir>/.venv/{bin/python3,bin/python,Scripts/python.exe}` walking up from [packageDir], or `null`.
 *
 * This mirrors `ResourceBundler.locateBytecodeCompiler`, which is private, so toolchain can name what
 * is missing in its own words and read that venv's version before `compileall` runs. If the two ever
 * disagree, `ResourceBundler`'s own error is still the backstop.
 */
fun locateVenvInterpreter(packageDir: File): File? {
    var current: File? = packageDir.canonicalFile
    while (current != null) {
        val venv = File(current, ".venv")
        VENV_INTERPRETERS.map { File(venv, it) }.firstOrNull { it.isFile }?.let { return it }
        current = current.parentFile
    }
    return null
}

/**
 * `(major, minor)` of the venv [interpreter] belongs to, read from `<venv>/pyvenv.cfg` without
 * starting a process. The stdlib `venv` writes `version = 3.13.0`; `uv` writes `version_info = 3.13.5`
 * and `virtualenv` `version_info = 3.13.5.final.0`. `null` when the file is absent or names no version.
 */
fun venvPythonVersion(interpreter: File): Pair<Int, Int>? {
    // `.venv/bin/python3` -> `.venv`. Not canonicalised: the interpreter is usually a symlink out of
    // the venv, and the venv is where the link sits.
    val cfg = File(interpreter.parentFile?.parentFile ?: return null, "pyvenv.cfg")
    if (!cfg.isFile) return null
    val values =
        cfg.readLines().mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq < 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
        }.toMap()
    val raw = values["version_info"] ?: values["version"] ?: return null
    val match = Regex("""^(\d+)\.(\d+)""").find(raw) ?: return null
    return match.groupValues[1].toInt() to match.groupValues[2].toInt()
}

/**
 * Why `compileLevel = "bytecode"` cannot run for [packageDir], or `null` when it can.
 *
 * **Where the interpreter comes from.** toolchain does not create one. `pypackpack`'s
 * `ResourceBundler` compiles with `<project>/.venv`, walking up from the package directory
 * ([locateVenvInterpreter]), and `installPythonDependencies` already leaves exactly that behind:
 * `uv add` creates `<package>/.venv` (`bin/python3` and a `pyvenv.cfg`; pinned by
 * `InstallDependenciesTaskTest`). A project with no dependencies has no such venv, and creating one
 * here would mean toolchain choosing and acquiring a Python, which is `pypackpack`'s work (AGENTS.md
 * §13) -- and `uv venv` over an existing `.venv` from `uv add` would replace it. So a missing venv is
 * refused with a message naming the directory searched and the two ways to make one.
 *
 * **Version.** A `.pyc` carries its compiler's magic number, which changes with every CPython minor
 * release, so the venv's `X.Y` must equal [compileSdk]'s. That is compared from `pyvenv.cfg`
 * ([venvPythonVersion]) and a mismatch is refused. It is not compared when [compileSdk] is `null`
 * (nothing to compare against) or the venv has no readable `pyvenv.cfg` -- `docs/SPEC.md` §1.6 records
 * both as known limits.
 */
fun bytecodeInterpreterRejection(
    packageDir: File,
    compileSdk: PythonVersion?,
): String? {
    val interpreter =
        locateVenvInterpreter(packageDir)
            ?: return "compileLevel 'bytecode' compiles with the Python in a '.venv' at or above " +
                "${packageDir.canonicalPath}, and there is none (looked for .venv/" +
                VENV_INTERPRETERS.joinToString(", .venv/") + "). Declare a Python dependency so " +
                "installPythonDependencies (uv add) creates <package>/.venv, or run " +
                "`uv venv --python ${compileSdk?.let { "${it.major}.${it.minor}" } ?: "<compileSdk X.Y>"}` " +
                "in ${packageDir.canonicalPath}."
    if (compileSdk == null) return null
    val (major, minor) = venvPythonVersion(interpreter) ?: return null
    if (major == compileSdk.major && minor == compileSdk.minor) return null
    return "compileLevel 'bytecode' would compile with Python $major.$minor (${interpreter.path}), " +
        "but compileSdk is $compileSdk: a .pyc only loads on the CPython minor version that wrote it. " +
        "Recreate that .venv with Python ${compileSdk.major}.${compileSdk.minor} " +
        "(`uv venv --python ${compileSdk.major}.${compileSdk.minor}`)."
}

/** [PythonVersion.toWheelVersion] of a resolved `compileSdk` label (`PythonVersion.toString()`). */
fun wheelVersionOf(compileSdkLabel: String): String = PythonVersion.parse(compileSdkLabel).toWheelVersion()
