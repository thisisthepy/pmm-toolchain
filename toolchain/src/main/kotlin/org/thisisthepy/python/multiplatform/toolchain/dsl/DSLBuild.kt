package com.github.thisisthepy.python.dsl

import kotlin.reflect.KProperty


open class BuildTypesContainer {
    private val types = mutableMapOf<String, BuildType>()

    fun getByName(name: String, action: BuildType.() -> Unit = {}): BuildType {
        val buildType = when (name) {
            "debug" -> DebugBuildType(name)
            "release" -> ReleaseBuildType(name)
            else -> throw IllegalArgumentException("Unknown build type: $name")
        }
        types[name] = buildType
        buildType.action()
        return buildType
    }
    fun all(): Collection<BuildType> = types.values
}

abstract class BuildType(val name: String) {
    abstract var compileLevel: String
    open var enableHotReload: Boolean = false
    abstract var useCodeMinifier: Boolean
    abstract var excludeMetaclass: Boolean
    abstract var enableCodePush: Boolean
}

class DebugBuildType(name: String) : BuildType(name) {
    override var compileLevel: String = ""
    override var enableHotReload: Boolean = false

    override var useCodeMinifier: Boolean
        get() = false
        set(_) { throw UnsupportedOperationException("useCodeMinifier is not allowed in debug mode") }
    override var excludeMetaclass: Boolean
        get() = false
        set(_) { throw UnsupportedOperationException("excludeMetaclass is not allowed in debug mode") }
    override var enableCodePush: Boolean
        get() = false
        set(_) { throw UnsupportedOperationException("enableCodePush is not allowed in debug mode") }
}

class ReleaseBuildType(name: String) : BuildType(name) {
    override var compileLevel: String = ""
    override var useCodeMinifier: Boolean = false
    override var excludeMetaclass: Boolean = false
    override var enableCodePush: Boolean = false
}

open class BuildFeaturesExtension {
    var metaclass: Boolean = false
    var compose: Boolean = false
}

open class SourceSetConfig constructor(val inputName: String) : org.gradle.api.Named {
    override fun getName(): String = inputName

    val srcDirs = mutableListOf<String>()
    val metaDirs = mutableListOf<String>()
    val libDirs = mutableListOf<String>()
    val dependencies = DependenciesExtension()

    fun srcDirs(vararg dirs: String) {
        if (name == "commonMain") {
            srcDirs.addAll(dirs.toList())
        } else {
            throw IllegalStateException("srcDirs() can only be used in 'commonMain', not in '$name'")
        }
    }

    fun metaDirs(vararg dirs: String) {
        if (name == "commonMain") {
            metaDirs.addAll(dirs.toList())
        } else {
            throw IllegalStateException("metaDirs() can only be used in 'commonMain', not in '$name'")
        }
    }

    fun libDirs(vararg dirs: String) {
        if (name == "commonMain") {
            libDirs.addAll(dirs.toList())
        } else {
            throw IllegalStateException("libDirs() can only be used in 'commonMain', not in '$name'")
        }
    }

    fun dependencies(action: DependenciesExtension.() -> Unit) = dependencies.apply(action)
}

open class DependenciesExtension {
    val implementations = mutableListOf<String>()
    fun implementation(dep: String) {
        implementations.add(dep)
    }
    val integrations = mutableListOf<String>()
    fun integration(dep: String) {
        integrations.add(dep)
    }
}

open class SourceSetsExtension {
    private val sourceSets = mutableMapOf<String, SourceSetConfig>()

    fun getByName(name: String): SourceSetConfig =
        sourceSets.getOrPut(name) { SourceSetConfig(name) }

    fun allSourceSets(): List<SourceSetConfig> = sourceSets.values.toList()

    val getting: AutoSourceSetDelegate
        get() = AutoSourceSetDelegate(this)

    fun getting(action: SourceSetConfig.() -> Unit): AutoSourceSetDelegateWithConfig =
        AutoSourceSetDelegateWithConfig(this, action)
}

class AutoSourceSetDelegate(private val container: SourceSetsExtension) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): SourceSetConfig {
        return container.getByName(property.name)
    }
}

class AutoSourceSetDelegateWithConfig(
    private val container: SourceSetsExtension,
    private val action: SourceSetConfig.() -> Unit
) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): SourceSetConfig {
        val config = container.getByName(property.name)
        config.action()
        return config
    }
}