package org.thisisthepy.python.multiplatform.toolchain.bundle

import org.thisisthepy.python.multiplatform.toolchain.deleteWithoutFollowingLinks
import org.thisisthepy.python.multiplatform.toolchain.devagent.DEV_AGENT_ENABLE_MARKER
import org.gradle.api.DefaultTask
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import java.io.File

/**
 * Puts a built Python bundle where a platform's own packaging step will pick it up.
 *
 * `f60bc3b` ended with the sentence this task answers: *"nothing stages the bundle into Android
 * assets, iOS resources or desktop resources."* [BuildPythonArtifactTask] produces
 * `<bundleDir>/python/` (plus a `resource-manifest.json`); nothing after it moved that anywhere, so
 * every artifact this plugin's consumers built -- jar, APK, framework -- contained no Python at all
 * even when the bundle on disk was correct.
 *
 * ## This is not the old asset copy
 *
 * `PythonPlugin`'s `afterEvaluate` used to copy `python.localLibraryPath` straight into
 * `src/main/assets/python` by hand, and `f60bc3b` deleted it. Three things differ here and each was
 * a defect there:
 *
 * 1. **The source is a built bundle, not a raw directory.** What is staged has been through
 *    `pypackpack`'s `ResourceBundler`: dependencies resolved, `src/<family>` overlaid on `src/main`,
 *    `__pycache__`/`build`/`dist` excluded, a manifest written. The old copy shipped the developer's
 *    working tree verbatim.
 * 2. **The destination is under `build/`, registered as an extra source root** -- not `src/`. A
 *    generated tree inside `src/main/assets` is checked into git by the next `git add -A`, and is
 *    invisible to `clean`.
 * 3. **The bundle staged is chosen, not assumed.** There is one bundle per
 *    platform-crossed-with-build-type now, and one instance of this task per *destination* -- so
 *    which bundle each destination gets is an explicit, logged decision (`selectStagingVariants`)
 *    rather than "whatever was in the one directory".
 *
 * ## Inputs and outputs
 *
 * [bundleDir] is `@Internal` and [bundlePayload] is the declared `@InputFiles`. That split is not
 * cosmetic and the first version of this task got it wrong: with the bundle declared nowhere,
 * Gradle had no input to compare, reported the task `UP-TO-DATE` on every run after the first, and
 * the staged tree -- and therefore the jar and the APK -- kept a payload from an earlier revision of
 * the Python package. Caught by building the jar, deleting a module, and building again: the module
 * was still in the archive.
 *
 * It is `@InputFiles` over a `FileTree` rather than `@InputDirectory` because the directory does not
 * exist until [BuildPythonArtifactTask] has run, and a missing `@InputDirectory` is a validation
 * failure while a missing file tree is simply empty. There is deliberately **no** `@SkipWhenEmpty`:
 * an empty payload still has to run, because clearing a destination that should now be empty is
 * exactly the case this task exists to handle. (`@SkipWhenEmpty` on a source is also what made
 * `packagePython` silently produce nothing -- see [AssemblePythonPackageTask]'s `init` comment.)
 *
 * [destinationDir] is a real `@OutputDirectory`, so `processResources.from(stageTask)` picks up both
 * the files and the task dependency in one call.
 */
open class StagePythonBundleTask : DefaultTask() {

    /** The variant bundle directory to stage from -- `<buildDir>/pythonBundle[/<variant>]`. */
    @get:Internal
    var bundleDir: File? = null

    /**
     * The `python/` subtree of [bundleDir] -- what actually gets staged, and what Gradle compares to
     * decide whether this task has anything to do. See the class KDoc for why this is `@InputFiles`
     * over a tree rather than `@InputDirectory`, and what happened when it was neither.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val bundlePayload: FileTree
        get() = project.fileTree(File(resolvedBundleDir(), PythonStagingLayout.PAYLOAD_ROOT))

    /**
     * The root this task owns and writes [payloadPath] into. Registered with the consuming
     * packaging step (a JVM resource root, an AGP asset source root), so it must be a directory
     * nothing else writes to: [stagePythonPayload] clears the payload subtree on every run.
     */
    @get:OutputDirectory
    var destinationDir: File = File("unset")

    /**
     * Where under [destinationDir] the payload goes. [PythonStagingLayout.PAYLOAD_ROOT] for every
     * platform today; a property rather than a constant because the *only* thing that would differ
     * between platforms is this path, and a platform that needs a different one should not need a
     * different task class.
     */
    @get:Input
    var payloadPath: String = PythonStagingLayout.PAYLOAD_ROOT

    /**
     * Whether the payload carries python-multiplatform's dev-agent marker ([DEV_AGENT_ENABLE_MARKER]): true for
     * a build type with `enableHotReload` (toolchain#105). An `@Input`, so switching build types re-stages.
     */
    @get:Input
    var devAgentMarker: Boolean = false

    /** Only for the log line, so it says which variant landed where. */
    @get:Internal
    var variantName: String? = null

    /** `null` [bundleDir] means the pre-variant-graph default, matching the other two bundle tasks. */
    private fun resolvedBundleDir(): File =
        bundleDir ?: File(project.layout.buildDirectory.get().asFile, "pythonBundle")

    @TaskAction
    fun stage() {
        val source = resolvedBundleDir()
        val result = stagePythonPayload(source, destinationDir, payloadPath, devAgentMarker)
        logger.lifecycle(
            "Staged ${result.fileCount} Python file(s)" +
                (variantName?.let { " for $it" } ?: "") +
                " from $source into ${result.payloadDir}" +
                (if (devAgentMarker) ", with the dev-agent marker" else ""),
        )
    }
}

/** What [stagePythonPayload] did, for the task action to log and for tests to assert on. */
data class PythonStagingResult(
    /** The directory the payload was written into: `<destinationRoot>/<payloadPath>`. */
    val payloadDir: File,
    /** Files written. Zero for a bundle with no `python/` root, which is a legal state. */
    val fileCount: Int,
)

/**
 * Copies `<bundleDir>/python/` into `<destinationRoot>/<payloadPath>/`, replacing whatever was
 * there.
 *
 * Factored out of [StagePythonBundleTask] so the staging rule can be exercised without a Gradle
 * `Project`, the same split [bundleWithPackpack] uses -- see `StagePythonBundleTaskTest` for the
 * grounds behind each of the three decisions below.
 *
 * - **Only `python/` travels.** `resource-manifest.json` is build metadata; the staged directory is
 *   meant to go on `sys.path` verbatim (`ResourceBundler`'s own words) and a manifest there is a
 *   file nothing can import and every packager has to ship.
 * - **The payload subtree is deleted first.** A plain copy never removes what it stopped copying,
 *   so a module deleted from the Python package would keep shipping in every APK and jar built on
 *   that machine. `python-multiplatform`'s `copyAndroidPythonAssets` carries a `doFirst { delete }`
 *   for exactly this reason, with a comment recording that this is how excluded directories
 *   survived their own exclusion.
 * - **A bundle with no `python/` root is silence, not failure.** [BuildPythonArtifactTask] `mkdirs`
 *   the bundle and skips `pypackpack` entirely when no `python.localLibraryPath` is configured, so
 *   this is the normal state of a consumer that has not pointed the DSL at a ppp package yet. The
 *   destination is still created, because by then it is already registered as somebody's resource
 *   or asset source root.
 * - **[devAgentMarker] writes [DEV_AGENT_ENABLE_MARKER] at the payload root** (toolchain#105), the switch
 *   python-multiplatform's runtime requires before it starts a dev agent. It is not counted in
 *   [PythonStagingResult.fileCount]. The clean sync above is what removes it from a later staging without it.
 */
fun stagePythonPayload(
    bundleDir: File,
    destinationRoot: File,
    payloadPath: String = PythonStagingLayout.PAYLOAD_ROOT,
    devAgentMarker: Boolean = false,
): PythonStagingResult {
    val payloadDir = File(destinationRoot, payloadPath)
    payloadDir.deleteWithoutFollowingLinks()
    if (!payloadDir.mkdirs() && !payloadDir.isDirectory) {
        throw java.io.IOException("could not create $payloadDir to stage the Python payload into")
    }

    if (devAgentMarker) File(payloadDir, DEV_AGENT_ENABLE_MARKER).writeText("")

    val source = File(bundleDir, PythonStagingLayout.PAYLOAD_ROOT)
    if (!source.isDirectory) return PythonStagingResult(payloadDir, 0)

    var count = 0
    source.walkTopDown().forEach { entry ->
        val relative = entry.relativeTo(source).path
        if (relative.isEmpty()) return@forEach
        val destination = File(payloadDir, relative)
        if (entry.isDirectory) {
            destination.mkdirs()
        } else {
            destination.parentFile?.mkdirs()
            entry.copyTo(destination, overwrite = true)
            count++
        }
    }
    return PythonStagingResult(payloadDir, count)
}

/**
 * The one place the staged layout is written down.
 *
 * There is exactly one path constant here rather than one per platform, and that is a finding
 * rather than a simplification -- see [PythonStagingPlatform] for what is and is not known about
 * where each runtime looks.
 */
object PythonStagingLayout {
    /**
     * `python`, matching `ResourceBundler.PYTHON_ROOT` and the `pythonRoot` field its manifest
     * records. Staging under any other name would leave the manifest describing a layout that stops
     * existing the moment it is staged.
     */
    const val PAYLOAD_ROOT = "python"

    /** `<buildDir>/pythonStaging` -- the parent of every per-variant staging root. */
    const val STAGING_DIRECTORY = "pythonStaging"
}

/**
 * The three destinations a payload can be staged into, and the mapping from a `pypackpack` target
 * triple onto them.
 *
 * ## What was checked before choosing these
 *
 * `python-multiplatform` was read for the path each runtime actually opens, because a staged
 * location that no runtime reads is decoration. What is there:
 *
 * - **Android**: `PythonBootstrap.stageStdlib` (`androidMain/.../env/PythonBootstrap.kt`) unpacks
 *   `assets/<abi>/lib/python<X.Y>/` into `filesDir` and points `PYTHONHOME` at it. That tree is the
 *   *standard library*, staged by `python-multiplatform`'s own `copyAndroidPythonAssets` task, not
 *   by any consumer.
 * - **Desktop**: `PYTHONHOME` names a python-build-standalone prefix, staged into the Gradle user
 *   home by the bindings plugin's `stagePythonHome` and shared by every project on the machine.
 * - **iOS**: the framework ships no stdlib at all, so `PYTHONHOME` must be set by the host app.
 *
 * **None of those is a path for a consumer's own Python code**, and the library says so in its own
 * source: `PythonProxySource`'s KDoc gives "there is no resource path to put a `.py` on ... getting
 * one generated file into each of those, and onto `sys.path` before the first import, is a
 * per-platform packaging problem this repository has not solved for anything" as its reason for
 * rendering Python as a Kotlin string instead of shipping a file.
 *
 * So the receiving contract does not exist yet, and the only written one is the *producing* side's:
 * `ResourceBundler`'s "payload root is `python/` ... intended to be placed on `sys.path` verbatim
 * (staged into Android assets, an iOS resource directory, or a desktop resource folder)". That
 * sentence names these three destinations, and [PAYLOAD_ROOT][PythonStagingLayout.PAYLOAD_ROOT] is
 * the name it gives them. What remains after this task is a runtime that puts the staged directory
 * on `sys.path` -- which is `python-multiplatform`'s side of the boundary, not this plugin's.
 *
 * ## Why the family comes from `ppp` and not from the DSL name
 *
 * `Platforms.getPlatformFamily` is the same function `ResourceBundler` uses to decide which
 * `src/<family>` overlay a bundle gets, so classifying by it means the staged payload and the
 * destination it is staged into cannot disagree about what platform they are for. Deriving it from
 * the DSL variant spelling instead would be a second classifier able to drift from the first.
 */
enum class PythonStagingPlatform(
    /**
     * Appended to `stagePythonBundle` to name this destination's task, and the directory name under
     * `<buildDir>/pythonStaging`. Lower-case for the directory, capitalised for the task, from one
     * value so the two cannot drift.
     */
    val directoryName: String,
) {
    /** An AGP asset source root, merged into `assets/` in the APK. */
    ANDROID("android"),

    /** A directory for an Xcode resource build phase to reference; see `PythonPlugin` for what is *not* wired. */
    IOS("ios"),

    /** A JVM resource root, ending up at the root of the desktop jar. */
    DESKTOP("desktop"),
    ;

    /** `Android`, `Ios`, `Desktop` -- the task-name segment. */
    val taskSuffix: String get() = directoryName.replaceFirstChar { it.uppercaseChar() }

    /**
     * `<buildDir>/pythonStaging/<directoryName>`.
     *
     * **Derived from the build directory alone, deliberately.** This path is handed to AGP as an
     * asset source root and to the Kotlin JVM target as a resource root, and both of those
     * registrations have to happen while the consumer's build script is still being evaluated --
     * before `afterEvaluate`, where every DSL-derived value in this plugin becomes readable. A root
     * that depended on the DSL could not be registered in time; one that does not, can.
     */
    fun rootIn(buildDir: File): File =
        File(File(buildDir, PythonStagingLayout.STAGING_DIRECTORY), directoryName)

    companion object {
        /**
         * `macos`, `linux` and `windows` are three `pypackpack` families and one destination here:
         * all three package Python through a JVM resource root, because the desktop target is a JVM
         * target. Android and iOS are one family each.
         */
        fun forTarget(target: String): PythonStagingPlatform =
            when (val family = Platforms.getPlatformFamily(target)) {
                "android" -> ANDROID
                "ios" -> IOS
                "macos", "linux", "windows" -> DESKTOP
                else -> throw IllegalArgumentException(
                    "target '$target' has platform family '$family', which has no staging " +
                        "destination in this plugin. Add one to PythonStagingPlatform rather than " +
                        "staging it somewhere that no packaging step reads.",
                )
            }
    }
}
