import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.chaquo.python)

    id("org.thisisthepy.toolchain").version("0.0.1-alpha")
}


group = "io.github.thisisthepy"
version = "1.0.0.0"


kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(projects.pycomposeui)
        }

        commonMain.dependencies {
            implementation(projects.pycomposeui)
        }
    }
}


chaquopy {
    defaultConfig {
        version = libs.versions.python.get()

        pip {
            // Use the local repository for the Python packages.
            options("--extra-index-url", "libs/pip/local")

            // Dependencies for the kotlin-python meta package.
            install("Deprecated")

            // Dependencies for the llama-cpp-python package.
            install("typing-extensions")
            install("numpy")
            install("diskcache")
            install("jinja2")
            install("MarkupSafe")
            install("llama-cpp-python")

            // Dependencies for the huggingface_hub package.
            install("PyYAML")
            install("huggingface_hub")

            // Dependencies for the jupyter package.
            install("pyzmq")
            install("rpds-py")
            install("argon2-cffi-bindings")
            install("jupyter")
        }
    }
    sourceSets {
        getByName("main") {
            srcDirs(
                "src/androidMain/python", "src/androidMain/generated/meta",
                "src/commonMain/python", "src/commonMain/generated/meta"
            )
        }
    }
}


android {
    namespace = "$group.pythonapptemplate"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/resources")

    defaultConfig {
        applicationId = "$group.pythonapptemplate"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = version.toString()
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
        }
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
    buildFeatures {
        compose = true
    }
    dependencies {
        debugImplementation(compose.uiTooling)
    }
}

python {

}
