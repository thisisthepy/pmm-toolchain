// usage-example/ is its own Gradle build (toolchain#22, #62), run with the repository's wrapper:
// ./gradlew -p usage-example …. It consumes python-multiplatform, which is built with Kotlin 2.4, so it uses
// that toolchain (gradle/libs.versions.toml here); the plugin in ../toolchain stays on
// its own Kotlin for its consumers. Both plugins and the
// python-multiplatform library come from mavenLocal: publish them first (see AGENTS.md §16).
pluginManagement {
    repositories {
        mavenLocal()
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "usage-example"
