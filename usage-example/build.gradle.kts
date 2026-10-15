import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig


group = "org.thisisthepy.python.multiplatform"
version = "1.0.0-alpha"

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    // usage-example is its own build (settings.gradle.kts here). The toolchain plugin is resolved by
    // coordinate from mavenLocal (`./gradlew :toolchain:publishToMavenLocal` in ../), as is
    // python-multiplatform's bindings plugin, which stages and packages the CPython stdlib (#22).
    alias(libs.plugins.toolchain)
    alias(libs.plugins.python.multiplatform.bindings)
}

// Exercises the target DSL surface end to end -- until now `usage-example` did not apply the
// plugin at all, so the DSL in `dsl/` had no executable definition (`docs/ecosystem.md` §2, §4
// item 7). This is deliberately the subset of `(플러그인예시)build.gradle.kts` that today's tasks
// (`PythonPlugin.kt`) actually read: `compileSdk`, `packaging`, `buildTypes`, `platforms`, and
// `integration()`. The rest of the DSL surface (`buildFeatures`) is declared but not wired to any
// task yet -- see the plugin's own README/report for what is scaffolding versus live.
python {
    compileSdk = PY3_15_0
    // A real `pypackpack` package (`pyproject.toml` + `src/main/<pkg>`), so the chain has a payload
    // to carry. Without it `buildPython` skips `pypackpack` entirely and every step downstream of it
    // -- the zip, and now the staging tasks -- correctly produces nothing, which makes "the artifact
    // contains Python" unfalsifiable. `example_py` deliberately includes a non-`.py` file, because
    // `ResourceBundler` carries data files next to modules and staging has to preserve that.
    localLibraryPath = "src/commonMain/python"
    // One real dependency, so a bundle and the packaged desktop app prove that installed packages
    // arrive (CI's consumer and desktop-e2e jobs; the --python-smoke mode imports it).
    sourceSets {
        val commonMain by getting {
            dependencies { implementation("iniconfig") }
        }
    }
    // The example build file's pip block, minus `jit` (rejected until pypackpack builds recipes).
    // PyPI repeated as the default index changes nothing, but it makes the wiring part of a real build.
    defaultConfig {
        versionCode = 1
        versionName = "1.0.0"
        pip {
            autoUpdate = false
            repositories {
                central {
                    setUrl("https://pypi.org/simple", "https://pypi.org/simple")
                }
            }
        }
    }
    packaging {
        fileName = "usage-example"
    }
    // `compose = true` is left out: it needs `python.compose.pythonxCompose` and
    // `python.compose.kotlinModule`, and neither artifact is published yet (docs/SPEC.md §1.16).
    buildFeatures {
        metaclass = true
    }
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    // No iosX64: Compose Multiplatform 1.11 publishes no iosX64 artifacts (Intel simulators are gone).
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Project"
            isStatic = true
        }
    }
    
    jvm("desktop")
    
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("Project")
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "Project.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        // Serve sources to debug inside browser
                        add(rootDirPath)
                        add(projectDirPath)
                    }
                }
            }
        }
        binaries.executable()
    }
    
    sourceSets {
        val desktopMain by getting
        
        androidMain.dependencies {
            // CPython for Android; `--ez python-smoke true` (PythonSmoke.android.kt) runs it headless.
            implementation(libs.python.multiplatform)
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }
        iosMain.dependencies {
            // CPython for iOS; `--python-smoke` (PythonSmoke.ios.kt) runs it on a simulator.
            implementation(libs.python.multiplatform)
        }
        desktopMain.dependencies {
            // CPython for the desktop app; `--python-smoke` (PythonSmoke.kt) runs it headless.
            implementation(libs.python.multiplatform)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

android {
    namespace = "$group.toolchain.example"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "$group.toolchain.example"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = version as String
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "$group.toolchain.example.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "$group.toolchain.example"
            packageVersion = (version as String).substringBefore("-")
        }
    }
}

// The wasm target's yarn lock lives in this build's gradle/ rather than a kotlin-js-store/ at the root
// (toolchain#62; the same decision as python-multiplatform #110).
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin> {
    extensions.getByType<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension>().apply {
        lockFileDirectoryProperty.set(layout.projectDirectory.dir("gradle"))
        lockFileNameProperty.set("wasm-yarn.lock")
    }
}
