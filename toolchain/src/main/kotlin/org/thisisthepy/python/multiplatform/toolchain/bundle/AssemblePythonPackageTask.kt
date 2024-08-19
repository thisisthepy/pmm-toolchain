package org.thisisthepy.python.multiplatform.toolchain.bundle

import org.gradle.api.tasks.bundling.Zip
import java.io.File


open class AssemblePythonPackageTask : Zip() {
    var embedLevel: Int = 0
    var fileName: String = "app"

    init {
        group = "python"
        description = "Packages the Python application"
    }

    override fun copy() {
        logger.lifecycle("Packaging Python application with embedLevel: $embedLevel and fileName: $fileName")

        val bundleDir = File(project.layout.buildDirectory.get().asFile, "pythonBundle")
        if (!bundleDir.exists()) {
            logger.error("Bundle directory does not exist, cannot package.")
            throw RuntimeException("Bundle directory does not exist, cannot package.")
        }

        from(bundleDir)
        archiveFileName.set("$fileName.zip")
        destinationDirectory.set(File(project.layout.buildDirectory.get().asFile, "distributions"))
        super.copy()
    }
}
