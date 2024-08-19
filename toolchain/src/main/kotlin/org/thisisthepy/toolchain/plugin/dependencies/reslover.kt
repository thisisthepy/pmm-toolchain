package org.thisisthepy.toolchain.plugin.dependencies

import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import org.gradle.api.artifacts.UnresolvedDependency
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.Project
import com.google.gson.Gson
import java.nio.file.Paths
import java.nio.file.Path
import java.io.File


fun Project.resolveDependenciesForPython(isSubModule: Boolean = false, sourceSetName: String? = null)
        : Triple<
        MutableMap<KotlinSourceSet, Pair<Path, MutableMap<DependencyType, List<ResolvedArtifact>>>>,
        MutableMap<KotlinSourceSet, MutableMap<DependencyType, List<UnresolvedDependency>>>,
        Boolean>
{
    val dependencyTree = mutableMapOf<KotlinSourceSet, Pair<Path, MutableMap<DependencyType, List<ResolvedArtifact>>>>()
    val unresolvedTree = mutableMapOf<KotlinSourceSet, MutableMap<DependencyType, List<UnresolvedDependency>>>()
    var isMetaJsonLockFileChanged = false

    // Build a dependency tree
    this.kotlinExtension.sourceSets.forEach { sourceSet ->
        // Skip source set if sourceSetName is not null and does not match to the requested source set
        if (sourceSetName != null && sourceSet.name != sourceSetName) return@forEach

        val sourceSetDependencyTree = mutableMapOf<DependencyType, List<ResolvedArtifact>>()
        val sourceSetUnresolvedTree = mutableMapOf<DependencyType, List<UnresolvedDependency>>()

        // default source set
        val srcNameWithDefaults = sourceSet.kotlin.srcDirs.mapNotNull {
            if (it.toString().contains(Paths.get(sourceSet.name, "kotlin").toString())) it else null
        }
        // custom named source set (kotlin, java)
        val srcNameWithCustom = sourceSet.kotlin.srcDirs.mapNotNull {
            if (it.toString().endsWith(Paths.get("", "kotlin").toString())
                || it.toString().endsWith(Paths.get("", "java").toString())) it else null
        }
        val targetDir = srcNameWithDefaults.firstOrNull() ?: srcNameWithCustom.firstOrNull() ?: sourceSet.kotlin.srcDirs.first()
        val generationDir = Paths.get(targetDir.parent!!, "generated", "meta")

        dependencyTree[sourceSet] = Pair(generationDir, sourceSetDependencyTree)
        unresolvedTree[sourceSet] = sourceSetUnresolvedTree

        when (isSubModule) {
            false -> mapOf(
                DependencyType.IMPLEMENTATION to configurations.getByName(sourceSet.implementationConfigurationName),
                DependencyType.API to configurations.getByName(sourceSet.apiConfigurationName)
            )
            true -> mapOf(  // when the project is a submodule
                DependencyType.API to configurations.getByName(sourceSet.apiConfigurationName)
            )
        }.forEach { (dependencyType, configuration) ->
            // Making configuration copy with isCanBeResolved = true
            val resolved = try {
                configurations.create(configuration.name + "Resolved") {
                    extendsFrom(configuration)
                    isCanBeResolved = true
                }
            } catch (ignored: Exception) { configurations.getByName(configuration.name + "Resolved") }
            val lenientConfiguration = resolved.resolvedConfiguration.lenientConfiguration

            // Adding dependencies to the tree
            val artifacts = lenientConfiguration.artifacts.toMutableList()
            sourceSetDependencyTree[dependencyType] = artifacts
            val unresolved = mutableListOf<UnresolvedDependency>()
            sourceSetUnresolvedTree[dependencyType] = unresolved

            // Handling unresolved dependencies
            lenientConfiguration.unresolvedModuleDependencies.forEach { unresolvedDependency ->
                if (
                    unresolvedDependency.problem.message?.contains(
                        "project :${unresolvedDependency.selector.name}"
                    ) == true
                ) {
                    val (submoduleDependency, raised, _) = project(":"+unresolvedDependency.selector.name)
                        .resolveDependenciesForPython(isSubModule = true, sourceSetName = sourceSet.name)
                    submoduleDependency.forEach { (key, value) ->
                        if (key.name == sourceSet.name) { artifacts.addAll(value.second[DependencyType.API]!!) }
                    }
                    raised.forEach { (key, value) ->
                        if (key.name == sourceSet.name) { unresolved.addAll(value[DependencyType.API]!!) }
                    }
                } else {
                    unresolved.add(unresolvedDependency)
                }
            }
        }
    }

    if (!isSubModule) {
        val gson = Gson()
        val metaJsonDir = File(project.projectDir, Paths.get( "build", "kotlin-python").toString())
        metaJsonDir.mkdirs()
        val metaJsonLockFile = File(metaJsonDir, "meta_json.lock")
        val metaJsonNewFile = File(metaJsonDir, "meta_json.new")
        metaJsonLockFile.createNewFile()
        metaJsonNewFile.createNewFile()

        // Create build/kotlin-python/meta_json.new file
        metaJsonNewFile.writeText(gson.toJson(dependencyTree.map {
            it.key to mapOf(
                "directory" to it.value.first.toString(),
                "artifacts" to it.value.second.map { (type, artifacts) ->
                    type.toString() to artifacts.map { artifact ->
                        mapOf(
                            "name" to artifact.name,
                            "group" to artifact.moduleVersion.id.group,
                            "version" to artifact.moduleVersion.id.version,
                            "file" to artifact.file.absolutePath
                        )
                    }
                }.toMap()
            )
        }.associate { (sourceSet, dependencies) ->
            sourceSet.name to dependencies
        }))
        val metaJsonNew = gson.fromJson(metaJsonNewFile.readText(), Map::class.java)

        // Compare artifacts with build/kotlin-python/meta_json.lock
        if (metaJsonLockFile.exists()) {
            val metaJsonLock = gson.fromJson(metaJsonLockFile.readText(), Map::class.java)
            if (metaJsonLock != metaJsonNew) {
                isMetaJsonLockFileChanged = true

                // Replace build/kotlin-python/meta_json.lock with .new
                metaJsonLockFile.writeText(gson.toJson(metaJsonNew))
            }
        }
        metaJsonNewFile.delete()
    }
    return Triple(dependencyTree, unresolvedTree, isMetaJsonLockFileChanged)
}
