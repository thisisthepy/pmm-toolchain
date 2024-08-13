import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.chaquo.python)
}


group = "io.github.thisisthepy.pythonapptemplate"
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
            api(projects.pycomposeui)
        }
    }
}


chaquopy {
    defaultConfig {
        version = libs.versions.python.get()

        pip {
            install("typing-extensions")
            install("numpy")
            install("diskcache")
            install("jinja2")
            install("MarkupSafe")
            install("./libs/llama-cpp-python/arm64-v8a/llama_cpp_python-0.2.88-cp311-cp311-linux_aarch64.whl")
            install("./libs/llama-cpp-python/x86_64/llama_cpp_python-0.2.88-cp311-cp311-linux_x86_64.whl")

            install("huggingface_hub")
        }
    }
    sourceSets {
        getByName("main") {
            srcDir("src/androidMain/python")
        }
    }
}


android {
    namespace = group.toString()
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/resources")

    defaultConfig {
        applicationId = group.toString()
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = version.toString()
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
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
