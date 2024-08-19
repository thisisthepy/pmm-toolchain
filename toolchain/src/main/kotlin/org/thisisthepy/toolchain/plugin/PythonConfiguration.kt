package org.thisisthepy.toolchain.plugin

import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.gradle.api.artifacts.UnresolvedDependency
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Plugin
import com.google.gson.Gson
import org.gradle.api.NamedDomainObjectContainer
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.InternalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl
import java.nio.file.Paths
import java.nio.file.Path
import java.io.File


fun Project.python(configure: Action<PyToolChainExtension>) {
    configure.execute(extensions.getByType(PyToolChainExtension::class.java))

    this.apply {
        PythonMultiplatformPlugin::class.java
    }
}


@OptIn(ExperimentalKotlinGradlePluginApi::class)
abstract class PyToolChainExtension(project: Project):
    KotlinProjectExtension(project),
    KotlinTargetContainerWithPresetFunctions,
    KotlinTargetContainerWithJsPresetFunctions,
    KotlinTargetContainerWithWasmPresetFunctions,
    KotlinHierarchyDsl,
    HasConfigurableKotlinCompilerOptions<KotlinCommonCompilerOptions>,
    KotlinMultiplatformSourceSetConventions by KotlinMultiplatformSourceSetConventionsImpl
{
    var version: String = ""
    var pythonVersion: String = ""
    var pythonPackages: List<String> = emptyList()

    var pipCentral: List<String> = emptyList()
    var pipLocal: List<String> = emptyList()
    var pipJit: List<String> = emptyList()

    fun execute() {
        println("Python version: $pythonVersion")
        println("Python packages: $pythonPackages")
    }
}


@OptIn(ExperimentalKotlinGradlePluginApi::class)
internal object KotlinMultiplatformSourceSetConventionsImpl : KotlinMultiplatformSourceSetConventions {
    override val NamedDomainObjectContainer<KotlinSourceSet>.commonMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.commonTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.nativeMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.nativeTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.appleMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.appleTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.iosMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.iosTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.tvosMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.tvosTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.watchosMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.watchosTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.macosMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.macosTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.linuxMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.linuxTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.mingwMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.mingwTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.androidNativeMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.androidNativeTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.jvmMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.jvmTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.jsMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.jsTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.androidMain by KotlinSourceSetConvention

    @ExperimentalWasmDsl
    override val NamedDomainObjectContainer<KotlinSourceSet>.wasmJsMain by KotlinSourceSetConvention

    @ExperimentalWasmDsl
    override val NamedDomainObjectContainer<KotlinSourceSet>.wasmJsTest by KotlinSourceSetConvention

    @ExperimentalWasmDsl
    override val NamedDomainObjectContainer<KotlinSourceSet>.wasmWasiMain by KotlinSourceSetConvention

    @ExperimentalWasmDsl
    override val NamedDomainObjectContainer<KotlinSourceSet>.wasmWasiTest by KotlinSourceSetConvention
}
