package com.github.thisisthepy.python

import org.gradle.api.Project
import java.io.File


object PythonLocalLoader {
    fun loadLibraryFilesFromLocalPath(project: Project, path: String): List<File> {
        val libFolder = project.file(path)
        if (!libFolder.exists() || !libFolder.isDirectory) {
            throw IllegalStateException("Library folder does not exist at: $path")
        }
        return libFolder.walkTopDown().filter { it.isFile }.toList()
    }
}
