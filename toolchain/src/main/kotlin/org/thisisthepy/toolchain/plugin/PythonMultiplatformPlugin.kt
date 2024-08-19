@file:JvmName("PythonMultiplatformPlugin")
package org.thisisthepy.toolchain.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project


class PythonMultiplatformPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.tasks.register("applyPythonMultiplatformToolchain") {
            outputs.upToDateWhen {
                false
            }
            doLast {
                println("Applying Python multiplatform toolchain...")
            }
        }

        val applyPythonMultiplatformToolchain = project.tasks.getByName("applyPythonMultiplatformToolchain")
        project.tasks.getByName("prepareKotlinIdeaImport").dependsOn(applyPythonMultiplatformToolchain)
    }
}
