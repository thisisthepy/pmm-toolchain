package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.dsl.BuildTypesContainer
import org.thisisthepy.python.multiplatform.toolchain.dsl.PlatformsExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per-variant task graph three other Issue #2 items were all blocked behind.
 *
 * `6a87bdc` closed platform mapping, compile level and Python version by *rejecting* what could not
 * be routed, and recorded one shared reason for stopping there: "`buildTask`/`packageTask` are each
 * one task, not a per-variant graph, so there is nowhere yet to route a validated triple to". That
 * is what [resolveVariants] produces -- the list `PythonPlugin.apply` turns into one
 * `buildPython<Variant>` / `packagePython<Variant>` task pair each, aggregated under the existing
 * `buildPython` / `packagePython` names.
 *
 * ## One task per variant, not one task looping variants
 *
 * Gradle's own convention is the former, and both plugins this DSL imitates follow it: AGP
 * registers `assembleDebug`/`assembleRelease` (plus a lifecycle `assemble` depending on them), and
 * the Kotlin Multiplatform plugin registers `compileKotlinIosArm64`, `linkDebugFrameworkIosArm64`
 * and so on, one per target. Four consequences of that choice are what actually decide it here:
 *
 * 1. **Failure isolation, which Issue #2 asks for by name.** The brief for the compile-level item is
 *    "reject only that variant and let the rest run". A single task looping variants cannot do that
 *    -- the first `resolveBuildLevel` rejection ends the whole task. With one task per variant, the
 *    rejection lands inside that task's action, and `--continue` runs the others.
 * 2. **Up-to-date checking is per task.** One task covering every variant is one up-to-date unit, so
 *    editing one platform's overlay would re-bundle all of them.
 * 3. **Selectability.** `gradle buildPythonAndroidArm64Debug` is how a consumer asks for one variant.
 *    The loop version has no such name, which is exactly why the previous round had to select the
 *    build type with a `-Ppython.buildType` project property instead of a task name.
 * 4. **Distinct outputs.** Each variant bundles to its own directory and zips to its own archive;
 *    Gradle declares outputs per task.
 *
 * ## Naming
 *
 * `<existing task name><PlatformVariant><BuildType>`, e.g. `buildPythonAndroidArm64Debug`. AGP's
 * `assemble<Flavor><BuildType>` and KMP's `compileKotlin<Target>` are both "verb + capitalized
 * dimensions", with the build type last; [PythonVariant.taskSuffix] is the same, and the platform
 * segment is spelled exactly as the KMP target it corresponds to (`androidArm64`,
 * `iosSimulatorArm64`), which is also how the DSL already spells it.
 *
 * ## Why the graph is opt-in
 *
 * [resolveVariants] returns an empty list when `python { }` platform declares nothing, and
 * `PythonPlugin` then leaves `buildPython`/`packagePython` doing exactly what they do today: one
 * host-target bundle, one `<fileName>.zip`. `usage-example` declares no platforms, so the only
 * chain that currently runs end to end is unaffected by any of this. The platform block is what
 * creates more than one target; without it there is exactly one, and one build type chosen by
 * `-Ppython.buildType` as before.
 */
class PythonPluginVariantGraphTest {
    @Test
    fun `no declared platforms yields no variants, preserving today's single-task chain`() {
        val variants = resolveVariants(PlatformsExtension(), BuildTypesContainer())

        assertEquals(emptyList(), variants)
    }

    @Test
    fun `declared platforms with no declared build types default to one debug variant each`() {
        val platforms = PlatformsExtension()
        platforms.android(); platforms.androidArm64(); platforms.androidX64()

        val variants = resolveVariants(platforms, BuildTypesContainer())

        assertEquals(
            listOf("androidArm64" to "debug", "androidX64" to "debug"),
            variants.map { it.platformVariantName to it.buildTypeName },
        )
    }

    @Test
    fun `every platform variant is crossed with every declared build type`() {
        val platforms = PlatformsExtension()
        platforms.android(); platforms.androidArm64()
        platforms.ios(); platforms.iosSimulatorArm64()
        val buildTypes = BuildTypesContainer()
        buildTypes.getByName("debug")
        buildTypes.getByName("release")

        val variants = resolveVariants(platforms, buildTypes)

        assertEquals(
            listOf(
                "androidArm64" to "debug",
                "androidArm64" to "release",
                "iosSimulatorArm64" to "debug",
                "iosSimulatorArm64" to "release",
            ),
            variants.map { it.platformVariantName to it.buildTypeName },
        )
    }

    @Test
    fun `each variant carries the canonical target triple its platform maps to`() {
        val platforms = PlatformsExtension()
        platforms.android(); platforms.androidArm64()
        platforms.ios(); platforms.iosArm64()
        platforms.desktop(); platforms.macosArm64()

        val variants = resolveVariants(platforms, BuildTypesContainer())

        assertEquals(
            listOf("aarch64-linux-android", "arm64-apple-ios", "aarch64-apple-darwin"),
            variants.map { it.target },
        )
    }

    @Test
    fun `task names follow the verb plus capitalized dimensions convention`() {
        val platforms = PlatformsExtension()
        platforms.android(); platforms.androidArm64()
        platforms.ios(); platforms.iosSimulatorArm64()
        val buildTypes = BuildTypesContainer()
        buildTypes.getByName("release")

        val variants = resolveVariants(platforms, buildTypes)

        assertEquals(
            listOf("buildPythonAndroidArm64Release", "buildPythonIosSimulatorArm64Release"),
            variants.map { PythonPlugin.BUILD_TASK + it.taskSuffix },
        )
        assertEquals(
            listOf("packagePythonAndroidArm64Release", "packagePythonIosSimulatorArm64Release"),
            variants.map { PythonPlugin.PACKAGE_TASK + it.taskSuffix },
        )
    }

    @Test
    fun `each variant gets its own output directory and archive name`() {
        val platforms = PlatformsExtension()
        platforms.android(); platforms.androidArm64(); platforms.androidX64()
        val buildTypes = BuildTypesContainer()
        buildTypes.getByName("debug")
        buildTypes.getByName("release")

        val dirNames = resolveVariants(platforms, buildTypes).map { it.dirName }

        assertEquals(
            listOf(
                "androidArm64-debug",
                "androidArm64-release",
                "androidX64-debug",
                "androidX64-release",
            ),
            dirNames,
        )
        assertEquals(dirNames.size, dirNames.toSet().size, "variant output directories must be unique")
    }

    @Test
    fun `a variant carries its own build type's compile level, so levels can differ per variant`() {
        val platforms = PlatformsExtension()
        platforms.android(); platforms.androidArm64()
        val buildTypes = BuildTypesContainer()
        buildTypes.getByName("debug") { compileLevel = "instant" }
        buildTypes.getByName("release") { compileLevel = "native" }

        val variants = resolveVariants(platforms, buildTypes)

        assertEquals(
            listOf("debug" to "instant", "release" to "native"),
            variants.map { it.buildTypeName to it.compileLevel },
        )
    }

    @Test
    fun `an unsupported compile level rejects only its own variant, leaving the rest resolvable`() {
        val platforms = PlatformsExtension()
        platforms.android(); platforms.androidArm64()
        val buildTypes = BuildTypesContainer()
        buildTypes.getByName("debug")
        buildTypes.getByName("release") { compileLevel = "native" }

        // Resolving the graph must not throw -- the rejection belongs to one variant's task action,
        // not to configuration, or declaring `release { compileLevel = "native" }` would take the
        // whole build down with it and `--continue` would have nothing left to continue.
        val variants = resolveVariants(platforms, buildTypes)

        val debug = variants.single { it.buildTypeName == "debug" }
        val release = variants.single { it.buildTypeName == "release" }
        assertEquals("instant", resolveBuildLevel(debug.compileLevel))
        assertFailsWith<IllegalArgumentException> { resolveBuildLevel(release.compileLevel) }
    }

    @Test
    fun `an android variant carries the android platform's declared min sdk`() {
        val platforms = PlatformsExtension()
        platforms.android { androidSdk = 24 }
        platforms.androidArm64()

        assertEquals(listOf(24), resolveVariants(platforms, BuildTypesContainer()).map { it.minSdk })
    }

    @Test
    fun `an ios variant carries the ios platform's declared min sdk and a desktop variant carries none`() {
        val platforms = PlatformsExtension()
        platforms.ios { iosSdk = 14 }
        platforms.iosArm64()
        platforms.desktop(); platforms.linuxX64()

        val variants = resolveVariants(platforms, BuildTypesContainer())

        assertEquals(14, variants.first { it.platformVariantName == "iosArm64" }.minSdk)
        assertNull(variants.first { it.platformVariantName == "linuxX64" }.minSdk)
    }

    @Test
    fun `an undeclared min sdk is null rather than the DSL's zero default`() {
        val platforms = PlatformsExtension()
        platforms.android(); platforms.androidArm64()

        assertNull(resolveVariants(platforms, BuildTypesContainer()).single().minSdk)
    }

    @Test
    fun `a negative min sdk is rejected instead of being carried into a task`() {
        val platforms = PlatformsExtension()
        platforms.android { androidSdk = -1 }
        platforms.androidArm64()

        val error = assertFailsWith<IllegalArgumentException> {
            resolveVariants(platforms, BuildTypesContainer())
        }
        assertTrue(
            error.message.orEmpty().contains("androidSdk"),
            "rejection should name the DSL property that is wrong, was: ${error.message}",
        )
    }
}
