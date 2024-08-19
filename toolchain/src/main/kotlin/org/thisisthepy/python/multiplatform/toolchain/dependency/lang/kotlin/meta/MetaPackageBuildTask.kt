package org.thisisthepy.python.multiplatform.toolchain.dependency.lang.kotlin.meta

import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.thisisthepy.python.multiplatform.toolchain.dependency.DependencyType
import org.thisisthepy.python.multiplatform.toolchain.dependency.resolveDependenciesForPython


class MetaPackageBuildTask : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.register("createKotlinMetaPackageForPython") {
            val (dependencyTree, unresolvedTree, isChanged) = project.resolveDependenciesForPython()
            outputs.upToDateWhen {
                false//!isChanged
            }
            doLast {
                //if (!isChanged) return@doLast  // Skip if the meta_json.lock file is not changed

                // Print dependency tree
                dependencyTree.forEach { (sourceSet, dependencies) ->
                    println("Dependencies for source set: ${sourceSet.name}")
                    println("  Meta package generation directory: ${dependencies.first}")
                    val unresolvedDependencies = unresolvedTree[sourceSet] ?: emptyMap()

                    dependencies.second.forEach { (type, artifacts) ->
                        val unresolved = unresolvedDependencies[type] ?: emptyList()

                        println("  Configuration: ${sourceSet.name}$type")
                        println("    Resolved artifacts:" + if (artifacts.isNotEmpty()) "" else " NONE")
                        artifacts.forEach { artifact ->
                            println("      ${artifact.file.absolutePath}")
                        }
                        println("    Unresolved dependencies:" + if (unresolved.isNotEmpty()) "" else " NONE")
                        unresolved.forEach { unresolvedDependency ->
                            println("      ${unresolvedDependency.selector}")
                            val message = unresolvedDependency.problem.message.toString()
                                .replace("    project", "            project")
                                .replace("Required by:", "          Required by:")
                            println("        - Problem: $message")
                        }
                    }
                }

                // Create meta package
                print("\nAssembling meta package for Kotlin source sets...")
                System.out.flush()
                dependencyTree.forEach { (_, dep) ->
                    val artifacts = dep.second[DependencyType.IMPLEMENTATION]!! + dep.second[DependencyType.API]!!
                    if (artifacts.isEmpty()) return@forEach  // Skip if there are no artifacts
                    val metaPackageDir = File(dep.first.toString())
                    if (metaPackageDir.exists()) {
                        metaPackageDir.deleteRecursively()  // Clean the directory
                    }
                    metaPackageDir.mkdirs()  // Create the directory
                    artifacts.forEach {
                        processJarForMetaPackage(it.file.absolutePath, metaPackageDir)  // Process lib files
                    }
                }
                println(" Done!")
            }
        }
    }
}
