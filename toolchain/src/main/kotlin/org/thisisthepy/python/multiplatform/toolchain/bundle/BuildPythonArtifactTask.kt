package com.github.thisisthepy.python.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import java.io.File


open class BuildPythonArtifactTask : DefaultTask() {
    var pythonVersion: String = "default"

    @TaskAction
    fun buildPython() {
        logger.lifecycle("Building Python bundle for version: $pythonVersion")

        val bundleDir = File(project.layout.buildDirectory.get().asFile, "pythonBundle")
        if (!bundleDir.exists()) {
            bundleDir.mkdirs()
        }

        val libsDir = File(project.layout.buildDirectory.get().asFile, "pythonLibraries")
        if (libsDir.exists()) {
            libsDir.copyRecursively(File(bundleDir, "libs"), overwrite = true)
        }
    }
}
