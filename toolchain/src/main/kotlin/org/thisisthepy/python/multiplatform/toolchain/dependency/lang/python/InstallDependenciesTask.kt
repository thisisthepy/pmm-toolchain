package org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import java.io.File


open class InstallDependenciesTask : DefaultTask() {
    var dependenciesList: List<String> = emptyList()

    @TaskAction
    fun installDependencies() {
        logger.lifecycle("Installing Python dependencies using uv...")

        if (dependenciesList.isEmpty()) {
            logger.lifecycle("No dependencies specified, skipping uv install.")
            return
        }

        val requirementsFile = File(project.layout.buildDirectory.get().asFile, "generated_requirements.txt")
        requirementsFile.writeText(dependenciesList.joinToString("\n"))
        logger.lifecycle("Generated requirements file at: ${requirementsFile.absolutePath}")

        project.exec {
            commandLine("uv", "install", "-r", requirementsFile.absolutePath)
        }
    }
}
