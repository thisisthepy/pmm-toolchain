package org.thisisthepy.python.multiplatform.toolchain.dsl

import org.gradle.api.model.ObjectFactory
import javax.inject.Inject


open class PythonExtension @Inject constructor(objects: ObjectFactory) {
    /** `compileSdk = "3.15.0"` or `compileSdk = PY3_15_0` (`"3.15t"` for the free-threaded build); see [PythonSdk]. */
    val compileSdk: PythonSdk = PythonSdk()
    val PY3_15_0: PythonSdkVersion = PythonSdkVersion.PY3_15_0
    var localLibraryPath: String? = null

    val defaultConfig: DefaultConfig = DefaultConfig()
    val packaging: PackagingExtension = PackagingExtension()
    val buildTypes: BuildTypesContainer = BuildTypesContainer()
    val buildFeatures: BuildFeaturesExtension = BuildFeaturesExtension()
    val sourceSets: SourceSetsExtension = objects.newInstance(SourceSetsExtension::class.java)
    val platforms: PlatformsExtension = PlatformsExtension()
    val projectFlavors: ProjectFlavorsContainer = ProjectFlavorsContainer()

    fun defaultConfig(action: DefaultConfig.() -> Unit) = defaultConfig.apply(action)
    fun packaging(action: PackagingExtension.() -> Unit) = packaging.apply(action)
    fun buildTypes(action: BuildTypesContainer.() -> Unit) = buildTypes.apply(action)
    fun buildFeatures(action: BuildFeaturesExtension.() -> Unit) = buildFeatures.apply(action)
    fun sourceSets(action: SourceSetsExtension.() -> Unit) = sourceSets.apply(action)
    fun projectFlavors(action: ProjectFlavorsContainer.() -> Unit) = projectFlavors.apply(action)

    // Platforms are declared directly inside `python { }`, as the example build file writes them
    // (see [PlatformsExtension]); there is no `platforms { }` block.
    fun android(name: String = "android", action: AndroidPlatformExtension.() -> Unit = {}) = platforms.android(name, action)
    fun ios(action: IosPlatformExtension.() -> Unit = {}) = platforms.ios(action)
    fun desktop(action: DesktopPlatformExtension.() -> Unit = {}) = platforms.desktop(action)

    fun androidArm64() = platforms.androidArm64()
    fun androidX64() = platforms.androidX64()

    @Deprecated(PlatformsExtension.UNSUPPORTED_ANDROID_VARIANT, level = DeprecationLevel.ERROR)
    fun androidArm32(): AndroidVariant = @Suppress("DEPRECATION_ERROR") platforms.androidArm32()

    @Deprecated(PlatformsExtension.UNSUPPORTED_ANDROID_VARIANT, level = DeprecationLevel.ERROR)
    fun androidX86(): AndroidVariant = @Suppress("DEPRECATION_ERROR") platforms.androidX86()

    fun iosArm64() = platforms.iosArm64()
    fun iosX64() = platforms.iosX64()
    fun iosSimulatorArm64() = platforms.iosSimulatorArm64()

    fun macosX64() = platforms.macosX64()
    fun macosArm64() = platforms.macosArm64()
    fun linuxX64() = platforms.linuxX64()
    fun linuxArm64() = platforms.linuxArm64()
    fun mingwX64() = platforms.mingwX64()
}

open class DefaultConfig {
    /** The Python payload's version, written to the bundle manifest; `null` when undeclared. */
    var versionCode: Int? = null
    var versionName: String? = null
    val pip: PipExtension = PipExtension()

    fun pip(action: PipExtension.() -> Unit) = pip.apply(action)
}

/**
 * `pip { autoUpdate; repositories { central / local / jit } }`, spelled as
 * `(플러그인예시)build.gradle.kts` spells it. `resolvePipSettings`
 * (`dependency/lang/python/PipRepositories.kt`) turns it into `uv add` options.
 */
open class PipExtension {
    /** Upgrade dependencies that are not pinned to a version (`uv add --upgrade`). */
    var autoUpdate: Boolean = false
    val repositories: PipRepositories = PipRepositories()

    fun repositories(action: PipRepositories.() -> Unit) = repositories.apply(action)
}

open class PipRepositories {
    var central: RepositoryConfig? = null
    var local: RepositoryConfig? = null
    var jit: JitRepositoryConfig? = null

    fun central(action: RepositoryConfig.() -> Unit) {
        central = (central ?: RepositoryConfig()).apply(action)
    }
    fun local(action: RepositoryConfig.() -> Unit) {
        local = (local ?: RepositoryConfig()).apply(action)
    }
    fun jit(action: JitRepositoryConfig.() -> Unit) {
        jit = (jit ?: JitRepositoryConfig()).apply(action)
    }
}

open class RepositoryConfig {
    val urls: MutableList<String> = mutableListOf()

    /** The first URL; assigning it replaces every URL declared so far. */
    var url: String?
        get() = urls.firstOrNull()
        set(value) {
            urls.clear()
            if (value != null) urls.add(value)
        }

    fun setUrl(vararg urls: String) {
        this.urls.addAll(urls)
    }
}

open class JitRepositoryConfig : RepositoryConfig() {
    val localRecipes: LocalRecipes = LocalRecipes()

    fun localRecipes(action: LocalRecipes.() -> Unit) = localRecipes.apply(action)
}

open class LocalRecipes {
    val paths: MutableList<String> = mutableListOf()

    fun add(path: String) {
        paths.add(path)
    }
}
