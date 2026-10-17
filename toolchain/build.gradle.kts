group = rootProject.group
version = rootProject.version


plugins {
    `kotlin-dsl`
    `maven-publish`
    `java-gradle-plugin`
    signing
    // The Gradle Plugin Portal's publishing (`publishPlugins`); it also adds the sources and javadoc
    // jars the portal and Maven Central require (toolchain#97). Nothing runs it here.
    id("com.gradle.plugin-publish") version "1.3.1"
    // The native `tcl` (toolchain-lite) that the PyPI wheels carry; see the cli source sets below.
    id("org.graalvm.buildtools.native") version "0.10.6"
}

// packpack's coordinate, from gradle.properties (#99).
val packpack = "${providers.gradleProperty("packpackModule").get()}:${providers.gradleProperty("packpackVersion").get()}"

dependencies {
    //implementation(gradleApi())
    //implementation(localGroovy())
    //implementation("org.jetbrains.kotlin:kotlin-stdlib")

    implementation("org.jetbrains.kotlinx:kotlinx-metadata-jvm:0.5.0")
    // Pinned to the same version as the root catalog's `kotlin` entry (`gradle/libs.versions.toml`),
    // not left as an independent literal. A skewed pin here (previously `2.0.0` against a `2.1.0`
    // root) put two different `kotlin-gradle-plugin` versions on the plugin classpath any consumer
    // sees once it applies both this plugin and `org.jetbrains.kotlin.multiplatform` in the same
    // `plugins {}` block (as `usage-example/build.gradle.kts` does). Gradle does not merge/conflict
    // -resolve those into one: `usage-example/build.gradle.kts` compiled its `KotlinWebpackConfig
    // .DevServer()` call (browser { commonWebpackConfig { ... } }) against 2.0.0's shape (`proxy:
    // Map<String, Any>`), while the actually-applied multiplatform plugin (2.1.0, `proxy:
    // List<Proxy>` as of that release) drove `wasmJsBrowserTest` task creation at runtime --
    // `NoSuchMethodError` on `DevServer.<init>` (root `./gradlew build` failure, reproduced via
    // `ANDROID_HOME=... ./gradlew build --stacktrace`; confirmed by decompiling both
    // `kotlin-gradle-plugin-{2.0.0,2.1.0}.jar`'s `KotlinWebpackConfig$DevServer.class` with `javap`).
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.ow2.asm:asm-util:9.4")
    implementation("org.ow2.asm:asm:9.4")
    // Needed to call `pypackpack`'s `dependency.backend.BaseInterface` (`suspend fun
    // addDependencies`) from `InstallDependenciesTask` via `runBlocking`. `packpack` depends on the
    // same artifact itself (`pypackpack/gradle/libs.versions.toml`), but as `implementation`, so it
    // is not on this module's compile classpath transitively -- declared explicitly here at the same
    // version to avoid a runtime-classpath-only mismatch.
    implementation(libs.kotlinx.coroutines.core)

    // `toolchain` translates the Gradle DSL into `pypackpack` middleware calls -- it owns none of
    // the packaging/dependency-resolution/bundling logic itself (docs/ecosystem.md §1, §5). This is
    // resolved through `mavenLocal()` (declared in the root `settings.gradle.kts`
    // `pluginManagement`/`dependencyResolutionManagement` repositories), which requires
    // `pypackpack`'s `packpack/build.gradle.kts` to have run `publishToMavenLocal` at least once.
    implementation(packpack)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    // Test-only: `PythonPluginAttachmentTest` applies `com.android.application` to a ProjectBuilder
    // project to check that the staged Android root really lands in `android.sourceSets.main.assets`
    // (the plugin reaches that object reflectively, so only a real AGP proves the method chain).
    // Same version as `usage-example` (`gradle/libs.versions.toml` `agp`); never on the plugin's
    // own runtime classpath.
    testImplementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
}

gradlePlugin {
    website.set("https://thisisthepy.github.io/toolchain/")
    vcsUrl.set("https://github.com/thisisthepy/toolchain")
    plugins {
        create("toolchain") {
            // The id comes from gradle.properties (#99); the class is a Kotlin package, not a coordinate.
            id = providers.gradleProperty("pluginId").get()
            implementationClass = "org.thisisthepy.python.multiplatform.toolchain.PythonPlugin"
            displayName = "thisisthepy toolchain"
            description =
                "Python in Kotlin Multiplatform apps: dependencies per target, bundling and staging of the " +
                    "app's Python, through pypackpack."
            tags.set(listOf("python", "kotlin-multiplatform", "android", "ios", "packaging"))
        }
    }
}

// `pypackpack` is built with Kotlin 2.3.0 (`pypackpack/gradle/libs.versions.toml`); this module's
// own `compileKotlin` resolves to Kotlin 2.1.0's compiler (whichever Kotlin Gradle Plugin version
// wins the shared plugin classpath across this settings -- `gradlew -v` reports the Gradle
// distribution's embedded 1.9.23, which is a different number and not what actually compiles
// `src/main/kotlin` here). A 2.1.0 reader rejects 2.3.0 metadata outright:
// "Module was compiled with an incompatible version of Kotlin. The binary version of its metadata
// is 2.3.0, expected version is 2.1.0." `-Xskip-metadata-version-check` is the targeted escape
// hatch for that specific check; it does not relax language/API level compatibility, only the
// metadata-format version gate, and the surface used from this module (data classes, a factory
// function, an enum) predates 2.1 either way. Bumping this whole build to Kotlin 2.3.0 instead was
// rejected as out of scope -- `compose-multiplatform 1.7.0` (`gradle/libs.versions.toml`) is not
// verified against it, and that is exactly the kind of build-wide change a single delegation should
// not carry.
// `TypedpythonCheckTaskTest` installs the real `typedpython` gate, which is not on PyPI yet:
// `-Ptypedpython.wheelDir=<dir>` names the directory holding its wheel. Forwarded as-is; the test
// fails with that instruction when it is missing rather than skipping.
tasks.test {
    systemProperty("typedpython.wheelDir", providers.gradleProperty("typedpython.wheelDir").getOrElse(""))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
}

// `java-gradle-plugin` (applied above) already registers a `pluginMaven` publication `from(
// components["java"])` for every `gradlePlugin { plugins { ... } }` entry, plus that plugin's
// marker publication. A hand-written `mavenJava` publication doing the same `from(components
// ["java"])` used to sit here too -- both published to the exact same coordinates (`group`/
// `artifactId` default to the project's, and neither publication overrode them), so `publish
// ToMavenLocal` published `mavenJava` then `pluginMaven` over it and warned "will overwrite each
// other". Nothing on the consumer side ever resolved `mavenJava` by name -- consumers apply the
// plugin id (resolved via the marker) and get the jar through `pluginMaven` -- so it was pure
// duplication, not an intentional second artifact. Removed rather than re-coordinated: there was
// no second artifact to keep.
// Maven Central and the Plugin Portal read the same POM fields (toolchain#97); both publications, the
// plugin's jar (`pluginMaven`) and its marker, carry them.
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("thisisthepy toolchain")
            description.set("The Gradle plugin that puts an app's Python into Kotlin Multiplatform builds, through pypackpack.")
            url.set("https://github.com/thisisthepy/toolchain")
            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("thisisthepy")
                    name.set("thisisthepy")
                    url.set("https://github.com/thisisthepy")
                }
            }
            scm {
                url.set("https://github.com/thisisthepy/toolchain")
                connection.set("scm:git:https://github.com/thisisthepy/toolchain.git")
                developerConnection.set("scm:git:ssh://git@github.com/thisisthepy/toolchain.git")
            }
        }
    }
    repositories {
        mavenLocal()
        // A rehearsal of a release: `publishAllPublicationsToStagingRepository` writes build/staging-deploy,
        // which `.github/scripts/maven/check_central_bundle.py --plugin-id ...` checks. Nothing uploads.
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

// Signs only when a key is given (`-PsigningInMemoryKey`, `-PsigningInMemoryKeyPassword`, or the
// ORG_GRADLE_PROJECT_ environment variables of the same names), as pypackpack does.
signing {
    val key = providers.gradleProperty("signingInMemoryKey").orNull
    // plugin-publish signs every publication once `signing` is applied; without a key, skip instead of failing.
    isRequired = key != null
    if (key != null) {
        useInMemoryPgpKeys(key, providers.gradleProperty("signingInMemoryKeyPassword").orNull)
        sign(publishing.publications)
    }
}

// ---------------------------------------------------------------------------------------------
// tcl (toolchain-lite): the command line for Python users, `tcl install <package>`
//
// It lives inside this module, in its own source sets (#78; it was a root `tcl/` module from
// 9560853 until then): `src/cliMain/kotlin`, the PyPI launcher package in `src/cliMain/python`, and
// tests in `src/cliTest/kotlin`. These source sets do not extend `main`'s configurations: through
// `java-gradle-plugin`, `main` carries the Gradle API and the Kotlin Gradle plugin, which tcl
// neither uses nor may drag into its native image. In the other direction, the plugin's
// publication is `main` alone, so it carries no tcl (test.yml checks the jar).
//
// `tclVersion` is tcl's own version, which `tcl --version` prints and the toolchain-lite wheel
// carries; publish-pypi.yml refuses to publish unless it equals pyproject.toml's. The plugin keeps
// rootProject.version.
val tclVersion = "0.1.0"
val tclMainClass = "org.thisisthepy.python.multiplatform.tcl.CliKt"

val cliMain: SourceSet by sourceSets.creating
val cliTest: SourceSet by sourceSets.creating
cliTest.compileClasspath += cliMain.output
cliTest.runtimeClasspath += cliMain.output
configurations[cliTest.implementationConfigurationName].extendsFrom(configurations[cliMain.implementationConfigurationName])
configurations[cliTest.runtimeOnlyConfigurationName].extendsFrom(configurations[cliMain.runtimeOnlyConfigurationName])

dependencies {
    // A thin CLI over pypackpack's dependency backend: the same packpack artifact `main` resolves
    // from mavenLocal (see this module's dependencies above).
    "cliMainImplementation"(libs.kotlinx.coroutines.core)
    "cliMainImplementation"(packpack)
    "cliTestImplementation"(libs.kotlin.test)
    "cliTestImplementation"(libs.kotlin.test.junit)
}

kotlin.target.compilations.getByName("cliTest").associateWith(kotlin.target.compilations.getByName("cliMain"))

val cliTestTask = tasks.register<Test>("cliTest") {
    description = "Runs tcl's tests (src/cliTest). InstallerTest needs uv and network access."
    group = "verification"
    testClassesDirs = cliTest.output.classesDirs
    classpath = cliTest.runtimeClasspath
    systemProperty("tcl.expectedVersion", tclVersion)
}
tasks.check { dependsOn(cliTestTask) }

// `./gradlew :toolchain:runTcl --args="install <package>"`
tasks.register<JavaExec>("runTcl") {
    description = "Runs tcl from source."
    group = "application"
    mainClass.set(tclMainClass)
    classpath = cliMain.runtimeClasspath
    workingDir = rootDir
}

// `BuildInfo` reads this resource, so the version is not a literal in the source.
val generateBuildInfo by tasks.registering {
    val version = tclVersion
    val outputDir = layout.buildDirectory.dir("generated/build-info")
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("org/thisisthepy/python/multiplatform/tcl/build-info.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$version\n")
    }
}
cliMain.resources.srcDir(generateBuildInfo)

val cliJar = tasks.register<Jar>("cliJar") {
    archiveBaseName.set("tcl")
    archiveVersion.set(tclVersion)
    from(cliMain.output)
}

// The native `tcl` binary that the toolchain-lite wheel carries (publish-pypi.yml). The same
// settings as pypackpack's CLI, which tcl links: io.ktor reaches org.slf4j (slf4j-nop, no I/O at
// init), which GraalVM 21 otherwise refuses to initialize at build time.
graalvmNative {
    binaries {
        named("main") {
            imageName.set("tcl")
            mainClass.set(tclMainClass)
            // An executable, not a library: the native plugin defaults to a shared library when
            // `java-library` is applied, which `java-gradle-plugin` does for this module.
            sharedLibrary.set(false)
            classpath.setFrom(cliJar, configurations[cliMain.runtimeClasspathConfigurationName])
            buildArgs.addAll(
                "--no-fallback",
                "--install-exit-handlers",
                "--initialize-at-build-time=kotlin,kotlinx.coroutines,io.ktor,kotlinx.io,org.slf4j",
                "-H:+AddAllCharsets",
                "--gc=serial",
            )
            if (System.getProperty("os.name").lowercase().contains("mac")) {
                // Without this the binary's minimum macOS is the build host's.
                buildArgs.add("-H:NativeLinkerOption=-mmacosx-version-min=11.0")
            }
            resources.autodetect()
        }
    }
    // native-image comes from GRAALVM_HOME/JAVA_HOME, not a toolchain Gradle might pick.
    toolchainDetection.set(false)
}
