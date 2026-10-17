package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonExtension
import org.thisisthepy.python.multiplatform.toolchain.dsl.BuildTypesContainer
import org.thisisthepy.python.multiplatform.toolchain.dsl.PlatformTargetMapping
import org.thisisthepy.python.multiplatform.toolchain.dsl.PlatformsExtension
import org.thisisthepy.python.multiplatform.toolchain.dsl.ProjectFlavorsContainer
import org.thisisthepy.python.multiplatform.toolchain.dsl.resolvePythonSdk
import org.thisisthepy.python.multiplatform.toolchain.dsl.SourceSetConfig
import org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python.InstallDependenciesTask
import org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python.InstallTargetDependenciesTask
import org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python.resolvePipSettings
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.toolchain.bundle.AssemblePythonPackageTask
import org.thisisthepy.python.multiplatform.toolchain.bundle.BuildPythonArtifactTask
import org.thisisthepy.python.multiplatform.toolchain.bundle.PythonStagingPlatform
import org.thisisthepy.python.multiplatform.toolchain.bundle.StagePythonBundleTask
import org.thisisthepy.python.multiplatform.toolchain.hotreload.CodePushPendingTask
import org.thisisthepy.python.multiplatform.toolchain.hotreload.HotReloadPushTask
import org.thisisthepy.python.multiplatform.toolchain.hotreload.validateCodePushConfig
import org.thisisthepy.python.multiplatform.toolchain.hotreload.validateHotReloadConfig
import org.thisisthepy.python.multiplatform.toolchain.typedpython.DEFAULT_PYREFLY_VERSION
import org.thisisthepy.python.multiplatform.toolchain.typedpython.DEFAULT_TYPEDPYTHON_MODE
import org.thisisthepy.python.multiplatform.toolchain.typedpython.DEFAULT_TYPEDPYTHON_GATE
import org.thisisthepy.python.multiplatform.toolchain.typedpython.DEFAULT_TYPEDPYTHON_VERSION
import org.thisisthepy.python.multiplatform.toolchain.typedpython.TYPEDPYTHON_GATE_PROPERTY
import org.thisisthepy.python.multiplatform.toolchain.typedpython.TYPEDPYTHON_WHEEL_DIR_PROPERTY
import org.thisisthepy.python.multiplatform.toolchain.typedpython.TypedpythonCheckTask
import org.thisisthepy.python.multiplatform.toolchain.typedpython.typedpythonCheckedDirs
import org.thisisthepy.python.multiplatform.toolchain.typedpython.typedpythonGateNotation
import org.thisisthepy.python.multiplatform.toolchain.typedpython.typedpythonSourceExcluded
import java.io.File


class PythonPlugin : Plugin<Project> {
    companion object {
        const val TASK_GROUP = "python"
        const val BUILD_TASK = "buildPython"
        const val INSTALL_TASK = "installPythonDependencies"
        const val PACKAGE_TASK = "packagePython"

        /**
         * Prefix of the tasks that put a built bundle where a platform's packaging step reads it --
         * `stagePythonBundleAndroid`, `stagePythonBundleIos`, `stagePythonBundleDesktop`, plus a
         * lifecycle task under the bare name. See `PythonPluginStagingTest` for why the dimension in
         * the name is the destination rather than the variant.
         */
        const val STAGE_TASK = "stagePythonBundle"
        const val HOT_RELOAD_TASK = "hotReloadPython"
        const val CODE_PUSH_TASK = "codePushPython"

        /** `docs/SPEC.md` §1.18: one check per project, before every `buildPython*`. */
        const val TYPEDPYTHON_CHECK_TASK = "typedpythonCheck"

        /** Stub directories handed to the check explicitly, each as `--search-path`. */
        const val TYPEDPYTHON_STUBS_CONFIGURATION = "typedpythonStubs"
    }

    override fun apply(project: Project) {
        val extension = project.extensions.create("python", PythonExtension::class.java, project.objects)

        val installTask = project.tasks.register<InstallDependenciesTask>(INSTALL_TASK) {
            group = TASK_GROUP
            description = "Installs Python dependencies using uv"
        }
        val buildTask = project.tasks.register<BuildPythonArtifactTask>(BUILD_TASK) {
            group = TASK_GROUP
            description = "Builds the Python bundle (python/: sources and dependencies; no interpreter)"
        }
        // `packageDir` is read lazily in `afterEvaluate` below, once `extension.localLibraryPath`
        // has its final value -- DSL blocks run before the plugin's own `afterEvaluate` callbacks.
        val packageTask = project.tasks.register<AssemblePythonPackageTask>(PACKAGE_TASK) {
            group = TASK_GROUP
            description = "Packages the Python application"
            fileName = extension.packaging.fileName
        }

        // TypedPython (issue #23). The stubs arrive explicitly through this configuration --
        // python-multiplatform's plugin adds its stub task's output to it -- not by a path convention.
        val typedpythonStubs = project.configurations.create(TYPEDPYTHON_STUBS_CONFIGURATION) {
            isCanBeConsumed = false
            isCanBeResolved = true
            description = "Stub directories passed to typedpythonCheck as --search-path"
        }
        val typedpythonCheckTask = project.tasks.register<TypedpythonCheckTask>(TYPEDPYTHON_CHECK_TASK) {
            group = TASK_GROUP
            description = "Type-checks the project's Python with the TypedPython gate"
            mode.convention(DEFAULT_TYPEDPYTHON_MODE)
            gateVersion.convention(DEFAULT_TYPEDPYTHON_VERSION)
            pyreflyVersion.convention(DEFAULT_PYREFLY_VERSION)
            wheelDir.set(
                project.providers.gradleProperty(TYPEDPYTHON_WHEEL_DIR_PROPERTY).map { project.file(it).absolutePath },
            )
            stubDirs.from(typedpythonStubs)
            // The gate zip (#90), resolved leniently: a project whose repositories lack it gets the
            // skip warning, not a failed configuration. -Ptypedpython.wheelDir wins over it.
            val gate = project.providers.gradleProperty(TYPEDPYTHON_GATE_PROPERTY).orElse(DEFAULT_TYPEDPYTHON_GATE)
            gateCoordinates.set(gate)
            gateArchive.from(
                project.provider {
                    if (project.providers.gradleProperty(TYPEDPYTHON_WHEEL_DIR_PROPERTY).isPresent) {
                        project.files()
                    } else {
                        project.configurations
                            .detachedConfiguration(project.dependencies.create(typedpythonGateNotation(gate.get())))
                            .apply { isTransitive = false }
                            .incoming.artifactView { isLenient = true }.files
                    }
                },
            )
            gateDir.set(project.layout.buildDirectory.dir("typedpython/gate"))
            venvDir.set(project.layout.buildDirectory.dir("typedpython/venv"))
            report.set(project.layout.buildDirectory.file("typedpython/report.txt"))
        }

        buildTask.configure { dependsOn(installTask, typedpythonCheckTask) }
        packageTask.configure { dependsOn(buildTask) }

        // ---------------------------------------------------------------------------------------
        // Staging. Registered *here*, not in `afterEvaluate`, and that is the whole reason the
        // destination roots are derived from the build directory alone (`PythonStagingPlatform.
        // rootIn`): an AGP asset source root has to be declared while the consumer's build script is
        // still being evaluated, because AGP reads its source sets in its own `afterEvaluate` and
        // this plugin -- applied last in a `plugins { }` block, as `usage-example` applies it --
        // registers its `afterEvaluate` callback after AGP's and therefore runs after it. A
        // `srcDir` added at that point is added to a source set nothing will look at again.
        //
        // Only the *sources* of these tasks need the DSL, and those are filled in below.
        // ---------------------------------------------------------------------------------------
        val stageTasks = registerStagingTasks(project)
        attachStagingToPackaging(project, stageTasks)
        org.thisisthepy.python.multiplatform.toolchain.bundle.registerStagePythonBundleIosForXcode(
            project, stageTasks.getValue(PythonStagingPlatform.IOS),
        )

        // python-multiplatform's `pythonVersion` is read from its extension in `afterEvaluate` below
        // (SPEC §1.12, #42), so a `:python-multiplatform` project of this build is evaluated first.
        evaluatePythonMultiplatformFirst(project)

        project.afterEvaluate {
            // Blank stays a no-op skip, the same convention `packageDir == null` already uses below
            // (and in `BuildPythonArtifactTask`/`InstallDependenciesTask`): a consumer that has not
            // configured Python yet gets silence, not a spurious failure, while a malformed non-blank
            // string is a real configuration mistake and fails loudly. See `PythonVersion`/
            // `PythonReleaseChannel` (`dsl/PythonVersion.kt`) for the format and the Issue #2 item
            // ("Python version setup -- Version Enum (alpha, rc, normal)") this implements.
            //
            // A version python-multiplatform does not provide is not a configuration failure: it is
            // carried to the bundling tasks as `pythonSdkRejection` and fails them there (§14).
            val pythonSdk = resolvePythonSdk(extension.compileSdk)
            val embedOverride = project.findProperty(EMBED_LEVEL_PROPERTY)?.toString()
            // A bad value is a configuration mistake with no valid task to register: fail here.
            val hostEmbedFamily = Platforms.getPlatformFamily(Platforms.detectHostTarget())
            val (hostEmbedLevel, hostEmbedWarning) =
                resolveEmbedLevel(extension.packaging.embedLevel, embedOverride, hostEmbedFamily)
            hostEmbedWarning?.let { project.logger.warn(it) }
            packageTask.configure {
                embedLevel = hostEmbedLevel
                embedFamily = hostEmbedFamily
                embedWarning = hostEmbedWarning
                interpreterVersion = expectedInterpreterVersion(hostEmbedLevel, pythonSdk)
            }
            // python-multiplatform embeds the interpreter at level 2; compileSdk must name the same
            // X.Y.Z (SPEC §1.12, #42). The property wins over the `:python-multiplatform` extension
            // (python-multiplatform#61), then the published dependency's metadata or jar resource (#49);
            // read once, when the first level-2 bundling task is configured.
            val pythonMultiplatformVersion by lazy {
                selectPythonMultiplatformVersion(
                    project.findProperty(PYTHON_MULTIPLATFORM_VERSION_PROPERTY)?.toString(),
                    readPythonMultiplatformExtensionVersion(project),
                ) { readPublishedPythonMultiplatformVersion(project) }.also { version ->
                    if (version == null) {
                        project.logger.lifecycle(
                            "python-multiplatform's pythonVersion is unknown (no $PYTHON_MULTIPLATFORM_VERSION_PROPERTY " +
                                "property, no $PYTHON_MULTIPLATFORM_PROJECT_PATH project with a " +
                                "$PYTHON_MULTIPLATFORM_EXTENSION extension, no published python-multiplatform " +
                                "dependency stating it): compileSdk is not checked against it.",
                        )
                    }
                }
            }
            fun pythonVersionRejectionFor(embedLevel: Int): String? =
                if (embedLevel == 2) checkPythonVersionAgreement(pythonSdk, pythonMultiplatformVersion) else null
            if (pythonSdk != null) {
                project.logger.lifecycle(
                    "Configured Python compileSdk: ${extension.compileSdk} " +
                        "(release ${pythonSdk.version.toReleaseString()}, channel ${pythonSdk.version.channel}" +
                        (if (pythonSdk.fromConstant) ", named constant" else "") + ")",
                )
            } else {
                project.logger.lifecycle("Configured Python compileSdk: ${extension.compileSdk}")
            }
            val pythonVersionLabel = pythonSdk?.version?.toString() ?: "default"
            val payloadVersionName = extension.defaultConfig.versionName
            val payloadVersionCode = extension.defaultConfig.versionCode
            val compileSdkLabel = pythonSdk?.version?.toString()
            buildTask.configure {
                pythonVersion = pythonVersionLabel
                pythonSdkRejection = pythonSdk?.rejection
                compileSdkVersion = compileSdkLabel
                versionName = payloadVersionName
                versionCode = payloadVersionCode
            }

            // Only wires the directory through today; it must already be a `pypackpack` package
            // (`pyproject.toml` + `src/{main,<platform>}`) for `BuildPythonArtifactTask` to bundle
            // it, which nothing in this DSL enforces or documents yet -- see this task's report for
            // why that is left as a follow-up rather than done here.
            //
            // `resolvePackageDir` also reads `sourceSets { commonMain { srcDirs(...) } }` as a
            // fallback when `localLibraryPath` is unset -- see its kdoc and `PythonPluginSourceSetTest`.
            val resolvedPackageDir =
                resolvePackageDir(project.projectDir, extension.localLibraryPath, extension.sourceSets.allSourceSets())
            // `metaDirs`/`libDirs` (`DSLBuild.kt`'s `SourceSetConfig`) reach `pypackpack`'s
            // `BundleRequest` the same way `commonMain.srcDirs` reaches `packageDir` above --
            // resolved once here and threaded into whichever build task(s) actually run below.
            val resolvedMetaDirs = resolveMetaDirs(project.projectDir, extension.sourceSets.allSourceSets())
            val resolvedLibDirs = resolveLibDirs(project.projectDir, extension.sourceSets.allSourceSets())
            // `metaDirs` is set per bundle task below, once its build type is known:
            // `buildFeatures { metaclass }` and that build type's `excludeMetaclass` decide whether
            // they are forwarded (`resolveBundledMetaDirs`).
            // `libDirs` is set per bundle task below: each one also takes its own dependency set's
            // `build/pythonDeps/<set>/` (`InstallTargetDependenciesTask`).
            buildTask.configure {
                packageDir = resolvedPackageDir
            }
            installTask.configure {
                packageDir = resolvedPackageDir
            }
            // Exactly the directory that gets bundled is what gets checked.
            typedpythonCheckTask.configure {
                packageDir = resolvedPackageDir
                // metaDirs/libDirs are bundled but deliberately not checked: typedpythonCheckedDirs.
                resolvedPackageDir?.let {
                    typedpythonCheckedDirs(it, resolvedMetaDirs, resolvedLibDirs)
                }.orEmpty().forEach { dir ->
                    sources.from(
                        project.fileTree(dir) {
                            include("**/*.py", "**/*.pyi")
                            exclude { it.isDirectory && typedpythonSourceExcluded(it.name) }
                        },
                    )
                }
            }

            // `python { }` platform (`DSLPlatforms.kt`) was read by nothing at all until now
            // -- `docs/ecosystem.md`'s gap list and this round's own prior report both name it
            // unwired. `validateDeclaredPlatforms` closes the mapping gap the prior round stopped at
            // ("declared Android variants ... have no entry in Platforms.SUPPORTED_TARGETS"): every
            // declared variant must now resolve to a real `pypackpack` target triple or the build
            // fails loudly, instead of the variant compiling and doing nothing.
            //
            // Validation runs first and independently of the graph below: an unmapped variant is a
            // configuration mistake with no valid task to register, so it must fail configuration
            // rather than produce a task that fails later.
            validateDeclaredPlatforms(extension.platforms)

            // "Check Kotlin-side enabled build target" (Issue #2's other `platforms` sub-item):
            // cross-references each declared variant's Kotlin target name (`PlatformTargetMapping.
            // kotlinTargetName`) against the targets the consumer's own `kotlin { }` block actually
            // registered. A mismatch is not failed outright -- unlike an unmapped variant, a target
            // Kotlin has not enabled yet is not necessarily a mistake (the DSL may be declared ahead
            // of the Kotlin target), so this only warns, but it is a real warning based on the
            // project's actual `KotlinMultiplatformExtension.targets`, not a decorative check.
            val kotlinExtension = project.extensions.findByType(KotlinMultiplatformExtension::class.java)
            if (kotlinExtension != null) {
                val enabledTargetNames = kotlinExtension.targets.names
                val mismatches = findPlatformsWithoutEnabledKotlinTarget(extension.platforms, enabledTargetNames)
                mismatches.forEach { variantName ->
                    project.logger.warn(
                        "python { } declares '$variantName', but Kotlin target " +
                            "'${PlatformTargetMapping.kotlinTargetName(variantName)}' is not enabled " +
                            "in this project's kotlin { } block.",
                    )
                }
            }
            // The naive copy-to-`build/pythonLibraries`-then-to-`src/main/assets/python` logic that
            // used to live here is gone: `docs/ecosystem.md`'s toolchain gap list ("Delete redundant
            // tasks ... and asset copy") named it directly, and it is now dead weight rather than a
            // second code path doing the same job -- `buildTask` (`BuildPythonArtifactTask`) already
            // delegates the real work to `pypackpack`'s `ResourceBundler` into `build/pythonBundle`
            // (this task's own report; `docs/ecosystem.md` §1, §5). Confirmed nothing depended on
            // it before deleting: no source under `usage-example/` reads `src/main/assets/python` or
            // `build/pythonLibraries`, `usage-example` itself never sets `python.localLibraryPath`
            // today, and `copyPythonLibrariesToAssets`/`preBuild` wiring existed only inside this
            // now-removed block, so nothing outside it could have depended on that task by name
            // either. Wiring the real bundle output into Android assets (or any other platform's
            // resource step) is a separate, not-yet-designed follow-up, not a like-for-like
            // replacement of this naive copy.

            // `buildFeatures { compose = true }`, Python half: `pythonx-compose` joins the install
            // list, a wheel directory joins pip's `find-links`, a missing property fails this task's
            // action only (`resolveComposePythonInstall`).
            val composePython = resolveComposePythonInstall(
                extension.buildFeatures.compose,
                project.findProperty(COMPOSE_PYTHON_PROPERTY)?.toString(),
                project.projectDir,
            )
            val pip = resolvePipSettings(extension.defaultConfig.pip)
            val pipArgumentsWithCompose = mergeComposeFindLinks(pip.arguments.orEmpty(), composePython.findLinks)
            installTask.configure {
                dependenciesList = collectInstallDependencies(extension.sourceSets.allSourceSets()) +
                    composePython.requirements
                integrationsList = collectIntegrationDependencies(extension.sourceSets.allSourceSets())
                pipArguments = pipArgumentsWithCompose
                pipRejection = pip.rejection
                composeRejection = composePython.rejection
            }

            // Per-target installation (SPEC §1.10, #16). `installPythonDependencies` above stays: its
            // `uv add` records every source set's dependencies in the package's pyproject.toml and
            // leaves the `<package>/.venv` that `compileLevel = "bytecode"` compiles with. What reaches
            // a bundle is installed separately, per dependency set, for that set's own triple.
            val projectBuildDir = project.layout.buildDirectory.get().asFile
            val declaredSourceSets = extension.sourceSets.allSourceSets()
            val sourceSetRejection =
                unreachableSourceSetRejection(declaredSourceSets, extension.projectFlavors.all().map { it.name })
            val targetArguments = targetInstallArguments(pythonSdk?.version, pipArgumentsWithCompose)
            val hasPackage = resolvedPackageDir != null
            fun registerTargetInstall(
                taskSuffix: String,
                dependencySet: String,
                target: String,
                flavorName: String?,
            ): TaskProvider<InstallTargetDependenciesTask> {
                val platform = PythonStagingPlatform.forTarget(target)
                val setRequirements =
                    collectTargetDependencies(declaredSourceSets, platform, flavorName) + composePython.requirements
                return project.tasks.register<InstallTargetDependenciesTask>(INSTALL_TASK + taskSuffix) {
                    group = TASK_GROUP
                    description = "Installs the Python dependencies of " +
                        dependencySourceSetNames(platform, flavorName).joinToString(", ") +
                        " for $target into build/$TARGET_DEPENDENCIES_DIRECTORY/$dependencySet"
                    requirements = setRequirements.distinct()
                    pythonPlatform = target
                    installArguments = targetArguments
                    rejection = composePython.rejection ?: sourceSetRejection
                    pipRejection = pip.rejection
                    installDir = targetDependenciesDir(projectBuildDir, dependencySet)
                    // No package means no bundle reads this directory (`BuildPythonArtifactTask` skips
                    // too), so nothing is downloaded for it.
                    onlyIf { hasPackage }
                }
            }

            // Kotlin half: see `resolveComposeKotlinDependency` for why a missing coordinate fails
            // configuration here and why a project without Kotlin Multiplatform only gets a warning.
            when (
                val composeKotlin = resolveComposeKotlinDependency(
                    extension.buildFeatures.compose,
                    kotlinExtension != null,
                    project.findProperty(COMPOSE_KOTLIN_PROPERTY)?.toString(),
                )
            ) {
                is ComposeKotlinDependency.Add ->
                    kotlinExtension!!.sourceSets.getByName("commonMain").dependencies {
                        implementation(composeKotlin.coordinate)
                    }
                is ComposeKotlinDependency.Skipped -> project.logger.warn(composeKotlin.reason)
                ComposeKotlinDependency.None -> Unit
            }

            // ---------------------------------------------------------------------------------
            // hotReload / codePush task registration.
            //
            // "성립하는 부분": DSL 검증 + 태스크 등록. 태스크가 하는 것과 못 하는 것을 태스크 자체가 말한다.
            //
            // hotReload: file-watch + adb-push + am-broadcast until python-multiplatform's runtime
            //   receives HOT_RELOAD_BROADCAST_ACTION and calls importlib.reload(). That boundary
            //   is the device runtime's job, not this task's.
            //
            // codePush: DSL validation only. Upload is NOT implemented -- there is no server.
            //   CodePushPendingTask prints this explicitly when run, matching pypackpack's
            //   UnimplementedDeployer pattern ("Result.failure, not TODO()").
            // ---------------------------------------------------------------------------------
            val anyBuildTypeEnablesHotReload = extension.buildTypes.all().any { it.enableHotReload }
            val anyBuildTypeEnablesCodePush = extension.buildTypes.all().any { it.enableCodePush }

            // Validate first; an invalid cert combination fails configuration before any task runs.
            val hotReloadConfig = validateHotReloadConfig(
                enabled = anyBuildTypeEnablesHotReload,
                ext = extension.packaging.hotReload,
            )
            val codePushConfig = validateCodePushConfig(
                enabled = anyBuildTypeEnablesCodePush,
                ext = extension.packaging.codePush,
            )

            project.tasks.register<HotReloadPushTask>(HOT_RELOAD_TASK) {
                group = TASK_GROUP
                description = if (anyBuildTypeEnablesHotReload) {
                    "Pushes changed Python source files to the connected device and signals a reload " +
                        "(adb push + am broadcast ${org.thisisthepy.python.multiplatform.toolchain.hotreload.HOT_RELOAD_BROADCAST_ACTION})"
                } else {
                    "Hot reload is not enabled for any build type. " +
                        "Set enableHotReload = true in python { buildTypes { getByName(\"debug\") { } } }."
                }
                hotReloadEnabled = hotReloadConfig.enabled
                sourceRoot = extension.localLibraryPath?.let { project.file(it) }
                remoteBasePath = "/data/local/tmp/${extension.packaging.fileName}/python"
                serverHost = hotReloadConfig.serverHost
            }

            project.tasks.register<CodePushPendingTask>(CODE_PUSH_TASK) {
                group = TASK_GROUP
                description = if (anyBuildTypeEnablesCodePush) {
                    "Code push is enabled but the upload client is not implemented yet. " +
                        "See CodePushPendingTask kdoc for the boundary explanation."
                } else {
                    "Code push is not enabled for any build type. " +
                        "Set enableCodePush = true in python { buildTypes { getByName(\"release\") { } } }."
                }
                codePushEnabled = codePushConfig.enabled
                serverHost = codePushConfig.serverHost
            }

            // ---------------------------------------------------------------------------------
            // The per-variant task graph. Everything above this point either validates a declared
            // value or rejects it; this is what finally *routes* one. See `resolveVariants` and
            // `PythonPluginVariantGraphTest` for the design and its grounds (one task per variant,
            // not one task looping variants; `<verb><PlatformVariant><BuildType>` naming; opt-in via
            // a declared platform variant so the existing chain is untouched).
            // ---------------------------------------------------------------------------------
            val variants = resolveVariants(extension.platforms, extension.buildTypes, extension.projectFlavors)
            val bundleRoot = File(project.layout.buildDirectory.get().asFile, "pythonBundle")
            // Hoisted out of the `variants.isEmpty()` branch it used to live in: staging has to know
            // which build type is active in *both* cases -- to pick a variant's bundle in one, and
            // to name the one it staged in the other.
            val requestedBuildType = (project.findProperty("python.buildType") as? String) ?: DEFAULT_BUILD_TYPE
            val activeBuildTypeName = resolveActiveBuildType(extension.buildTypes, requestedBuildType)
            val activeFlavorName = resolveActiveFlavor(extension.projectFlavors, project.findProperty("python.flavor") as? String)
            buildTask.configure { flavorRejection = flavorsWithoutVariantsRejection(extension.projectFlavors, variants) }

            if (variants.isEmpty()) {
                // No declared platform variant: exactly the pre-graph behavior, unchanged. One host
                // target, and the build type picked by a project property because there is no
                // variant task name to ask for -- `-Ppython.buildType=release`, defaulting to
                // `"debug"`. `resolveActiveBuildType` fails loudly on an undeclared name rather than
                // silently falling back, so a typo in `-P` surfaces immediately.
                val activeBuildType = extension.buildTypes.all().firstOrNull { it.name == activeBuildTypeName }
                // The host bundle carries the host triple's wheels: `commonMain` + `desktopMain`,
                // installed for `Platforms.detectHostTarget()` -- the triple `buildTask.target`
                // defaults to. Installed packages come first in `libDirs`, so a directory the
                // consumer declares with `libDirs(...)` (a vendored tree) overrides them.
                val hostInstall = registerTargetInstall("Host", HOST_DEPENDENCY_SET, Platforms.detectHostTarget(), null)
                buildTask.configure {
                    pythonVersionRejection = pythonVersionRejectionFor(hostEmbedLevel)
                    libDirs = listOf(targetDependenciesDir(projectBuildDir, HOST_DEPENDENCY_SET)) + resolvedLibDirs
                    dependsOn(hostInstall)
                    buildType = activeBuildTypeName
                    compileLevel = activeBuildType?.compileLevel ?: ""
                    metaDirs = resolveBundledMetaDirs(
                        resolvedMetaDirs,
                        extension.buildFeatures.metaclass,
                        activeBuildType?.excludeMetaclass ?: false,
                    )
                }

                // Every destination gets the one host bundle, because that is the only bundle that
                // exists. It carries the *host* family's `src/<family>` overlay, which is wrong for
                // Android and iOS -- so it is a warning, not silence: declaring a platform variant is
                // what makes each destination get a bundle built for it.
                val hostTarget = Platforms.detectHostTarget()
                stageTasks.forEach { (platform, stageTask) ->
                    stageTask.configure {
                        bundleDir = bundleRoot
                        variantName = "host-$activeBuildTypeName"
                        dependsOn(buildTask)
                    }
                    if (PythonStagingPlatform.forTarget(hostTarget) != platform) {
                        project.logger.info(
                            "Staging the host bundle ($hostTarget) into ${platform.name.lowercase()}'s " +
                                "packaging step: no platform variant is declared in python { }, so " +
                                "there is no ${platform.name.lowercase()} bundle to stage instead.",
                        )
                    }
                }
            } else {
                // One install per dependency set (platform variant x flavor), shared by its build types.
                val targetInstalls = mutableMapOf<String, TaskProvider<InstallTargetDependenciesTask>>()
                variants.forEach { variant ->
                    val variantBundleDir = File(bundleRoot, variant.dirName)
                    val targetInstall = targetInstalls.getOrPut(variant.dependencySetName) {
                        registerTargetInstall(
                            variant.dependencyTaskSuffix,
                            variant.dependencySetName,
                            variant.target,
                            variant.flavorName,
                        )
                    }

                    val variantFamily = Platforms.getPlatformFamily(variant.target)
                    val (variantEmbedLevel, variantEmbedWarning) =
                        resolveEmbedLevel(extension.packaging.embedLevel, embedOverride, variantFamily)
                    variantEmbedWarning?.let { project.logger.warn("${variant.dirName}: $it") }

                    val variantBuildTask =
                        project.tasks.register<BuildPythonArtifactTask>(BUILD_TASK + variant.taskSuffix) {
                            group = TASK_GROUP
                            description =
                                "Builds the Python bundle for ${variant.platformVariantName} " +
                                    "(${variant.target}, ${variant.buildTypeName})"
                            pythonVersion = pythonVersionLabel
                            pythonSdkRejection = pythonSdk?.rejection
                            compileSdkVersion = compileSdkLabel
                            versionName = payloadVersionName
                            versionCode = payloadVersionCode
                            packageDir = resolvedPackageDir
                            metaDirs = resolveBundledMetaDirs(
                                resolvedMetaDirs,
                                extension.buildFeatures.metaclass,
                                extension.buildTypes.all()
                                    .firstOrNull { it.name == variant.buildTypeName }?.excludeMetaclass ?: false,
                            )
                            // Installed packages first, so a declared `libDirs(...)` overrides them.
                            libDirs = listOf(targetDependenciesDir(projectBuildDir, variant.dependencySetName)) +
                                resolvedLibDirs
                            target = variant.target
                            buildType = variant.buildTypeName
                            // Raw, not resolved: an unsupported level must fail this one task at
                            // execution, not configuration for every variant. See
                            // `BuildPythonArtifactTask.compileLevel`.
                            compileLevel = variant.compileLevel
                            minSdk = variant.minSdk
                            bundleDir = variantBundleDir
                            pythonVersionRejection = pythonVersionRejectionFor(variantEmbedLevel)
                            dependsOn(installTask, targetInstall, typedpythonCheckTask)
                        }

                    val variantPackageTask =
                        project.tasks.register<AssemblePythonPackageTask>(PACKAGE_TASK + variant.taskSuffix) {
                            group = TASK_GROUP
                            description =
                                "Packages the Python bundle for ${variant.platformVariantName} " +
                                    "(${variant.buildTypeName})"
                            embedLevel = variantEmbedLevel
                            embedFamily = variantFamily
                            embedWarning = variantEmbedWarning
                            interpreterVersion = expectedInterpreterVersion(variantEmbedLevel, pythonSdk)
                            fileName = extension.packaging.fileName
                            bundleDir = variantBundleDir
                            variantName = variant.dirName
                            dependsOn(variantBuildTask)
                        }

                    buildTask.configure { dependsOn(variantBuildTask) }
                    packageTask.configure { dependsOn(variantPackageTask) }
                }

                // `buildPython`/`packagePython` become lifecycle tasks, the way AGP's `assemble` is
                // once `assembleDebug`/`assembleRelease` exist: they keep their names (nothing that
                // invokes them today has to change) but stop doing work of their own, because doing
                // it would mean bundling the *host* triple that no declared variant asked for. An
                // `onlyIf { false }` action is Gradle's own idiom for that; the task reports SKIPPED
                // and its per-variant dependencies still run.
                buildTask.configure {
                    onlyIf { false }
                }
                packageTask.configure {
                    onlyIf { false }
                }
                project.logger.lifecycle(
                    "Python variant graph: " + variants.joinToString(", ") { "${it.dirName} -> ${it.target}" },
                )

                // One destination takes one directory, so one variant per destination is chosen --
                // see `selectStagingVariants` and `PythonPluginStagingTest` for the two rules and
                // what they are grounded in. A destination with no eligible variant is left staging
                // nothing rather than being handed a bundle built for a different platform.
                val selected = selectStagingVariants(variants, activeBuildTypeName, Platforms.detectHostTarget(), activeFlavorName)
                stageTasks.forEach { (platform, stageTask) ->
                    val variant = selected[platform]
                    if (variant == null) {
                        project.logger.info(
                            "No python { } platform variant of build type " +
                                "'$activeBuildTypeName' maps to ${platform.name.lowercase()}, so " +
                                "${STAGE_TASK + platform.taskSuffix} stages nothing.",
                        )
                        stageTask.configure { bundleDir = File(bundleRoot, "unselected-${platform.directoryName}") }
                        return@forEach
                    }
                    stageTask.configure {
                        bundleDir = File(bundleRoot, variant.dirName)
                        variantName = variant.dirName
                        dependsOn(BUILD_TASK + variant.taskSuffix)
                    }
                }
                project.logger.lifecycle(
                    "Python staging: " + PythonStagingPlatform.values().joinToString(", ") { platform ->
                        "${platform.directoryName} <- ${selected[platform]?.dirName ?: "(nothing)"}"
                    },
                )
            }
        }
    }
}

/**
 * Registers the three destination staging tasks plus the lifecycle task that runs all of them.
 *
 * Called from `apply`, not from `afterEvaluate` -- see the call site for why that is forced by AGP's
 * source-set reading, and [PythonStagingPlatform.rootIn] for what it costs (the destination path
 * cannot depend on the DSL).
 */
private fun registerStagingTasks(project: Project): Map<PythonStagingPlatform, TaskProvider<StagePythonBundleTask>> {
    val buildDir = project.layout.buildDirectory.get().asFile
    val tasks = PythonStagingPlatform.values().associateWith { platform ->
        project.tasks.register<StagePythonBundleTask>(PythonPlugin.STAGE_TASK + platform.taskSuffix) {
            group = PythonPlugin.TASK_GROUP
            description = "Stages the built Python bundle where ${platform.directoryName} packaging reads it"
            destinationDir = platform.rootIn(buildDir)
        }
    }
    project.tasks.register(PythonPlugin.STAGE_TASK) {
        group = PythonPlugin.TASK_GROUP
        description = "Stages the built Python bundle into every platform destination this project has"
        dependsOn(tasks.values)
    }
    return tasks
}

/**
 * Hands each staged root to the packaging step that reads it.
 *
 * Three destinations, and they are not equally solved -- the honest summary of what each line below
 * achieves:
 *
 * - **Desktop is wired end to end.** The staged root is added to the JVM target's own
 *   `processResources`, so the payload is inside `desktopJar` and inside the `run` task's runtime
 *   classpath directory. `from(task)` carries the task dependency as well as the files, which is
 *   why no separate `dependsOn` is needed.
 * - **Android is wired end to end.** The staged root is registered as an extra asset source root on
 *   `main`, so it is merged into `assets/` in the APK -- without generating anything into `src/`,
 *   which is what the deleted `afterEvaluate` copy did.
 * - **iOS is staged but not attached.** A Kotlin/Native framework has no Gradle-side resource
 *   mechanism to add files to: resources reach an iOS app through an Xcode "Copy Bundle Resources"
 *   build phase, in a project file this plugin does not own (`usage-example`'s is
 *   `src/iosMain/app.xcodeproj`, hand-maintained). The staged directory is produced and its path is
 *   logged so the phase can point at it; automating the `.pbxproj` edit is a separate piece of work
 *   and is not pretended to be done here.
 *
 * AGP and the Kotlin Multiplatform plugin are reached through `plugins.withId`, not by assuming they
 * are already applied: this plugin may be applied before either, and `withId` fires whichever way
 * round it happens. AGP is additionally not on this plugin's own compile classpath -- only
 * `kotlin-gradle-plugin` is (`toolchain/build.gradle.kts`) -- so its source sets are reached
 * reflectively, with a message rather than a `ClassCastException` if that surface moves.
 */
private fun attachStagingToPackaging(
    project: Project,
    stageTasks: Map<PythonStagingPlatform, TaskProvider<StagePythonBundleTask>>,
) {
    val desktopStage = stageTasks.getValue(PythonStagingPlatform.DESKTOP)
    project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
        val kotlin = project.extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return@withId
        // A live view: `kotlin { jvm("desktop") }` is declared after `plugins { }`, so the target
        // does not exist yet at this point and `forEach` would see nothing.
        kotlin.targets.matching { it.platformType == KotlinPlatformType.jvm }.all {
            // `"<target>ProcessResources"` rather than `KotlinCompilation.processResourcesTaskName`:
            // that property is not on the `kotlin-gradle-plugin-api` interface (checked with
            // `javap` against 2.0.20 -- `KotlinCompilation` exposes `compileKotlinTaskName` and
            // `compileAllTaskName` and no resources equivalent), so reaching it would mean an
            // internal type. `tasks.matching` is also a live view, so it is unaffected by whether
            // the target's tasks exist yet, and matches nothing rather than failing if the naming
            // convention ever changes -- with the `warn` below saying so.
            val resourcesTaskName = "${name}ProcessResources"
            var found = false
            project.tasks.matching { it.name == resourcesTaskName }.configureEach {
                found = true
                val copy = this as? org.gradle.api.tasks.Copy
                if (copy == null) {
                    // Never observed; recorded rather than silently swallowed, because a
                    // `dependsOn` alone would make the task run and put nothing in the jar.
                    dependsOn(desktopStage)
                    project.logger.warn(
                        "$resourcesTaskName is not a Copy task, so the staged Python payload could " +
                            "not be added to its resources; the desktop artifact will contain no Python.",
                    )
                } else {
                    copy.from(desktopStage)
                }
            }
            project.gradle.taskGraph.whenReady {
                if (!found) {
                    project.logger.info(
                        "no '$resourcesTaskName' task exists, so the staged Python payload was not " +
                            "added to the '$name' JVM target's resources.",
                    )
                }
            }
        }
    }

    val androidStage = stageTasks.getValue(PythonStagingPlatform.ANDROID)
    val androidRoot = PythonStagingPlatform.ANDROID.rootIn(project.layout.buildDirectory.get().asFile)
    listOf("com.android.application", "com.android.library").forEach { pluginId ->
        project.plugins.withId(pluginId) {
            registerAndroidAssetSourceDirectory(project, androidRoot)
            // `assets.srcDir(File)` carries no task dependency, so the producing task has to be
            // named. `preBuild` is AGP's own documented anchor and is what the deleted copy used;
            // the merge tasks are added as well because they are what actually reads the directory
            // and `preBuild` being upstream of them is a convention, not a guarantee.
            project.tasks.matching {
                it.name == "preBuild" || (it.name.startsWith("merge") && it.name.endsWith("Assets"))
            }.configureEach { dependsOn(androidStage) }
        }
    }
}

/**
 * `android.sourceSets.getByName("main").assets.srcDir(directory)`, reflectively.
 *
 * AGP is not a dependency of this plugin, so there is no `AndroidSourceSet` type to call. Each hop
 * fails with a message naming what was not found rather than an NPE or a `ClassCastException`,
 * because the failure mode this replaces -- the payload silently not reaching the APK -- is the one
 * thing this whole change exists to stop.
 */
private fun registerAndroidAssetSourceDirectory(project: Project, directory: File) {
    val android = project.extensions.findByName("android") ?: run {
        project.logger.warn(
            "an Android plugin is applied but there is no `android` extension, so the staged " +
                "Python payload was not added to the APK's assets.",
        )
        return
    }
    runCatching {
        val sourceSets = android.javaClass.methods.first { it.name == "getSourceSets" && it.parameterCount == 0 }
            .invoke(android)
        val main = sourceSets!!.javaClass.methods.first { it.name == "getByName" && it.parameterCount == 1 }
            .invoke(sourceSets, "main")
        val assets = main!!.javaClass.methods.first { it.name == "getAssets" && it.parameterCount == 0 }
            .invoke(main)
        assets!!.javaClass.methods.first { it.name == "srcDir" && it.parameterCount == 1 }
            .invoke(assets, directory)
    }.onFailure { error ->
        project.logger.warn(
            "could not add '$directory' to android.sourceSets.main.assets " +
                "(${error.message}); the staged Python payload will not reach the APK.",
        )
    }
}

/**
 * Picks the one variant each destination is staged from.
 *
 * Factored out of [PythonPlugin.apply] the same way [resolveActiveBuildType] and [resolveVariants]
 * were, so the rules can be exercised without a Gradle [Project] -- see `PythonPluginStagingTest`,
 * which carries the reasoning behind both of them.
 *
 * - Only variants of [activeBuildType] are eligible, because a destination that mixed build types
 *   would ship the release payload out of a debug build with nothing saying so.
 * - Among the eligible ones, desktop prefers the variant whose triple *is* [hostTarget] (three
 *   desktop families are three different `src/<family>` overlays and only one of them runs here),
 *   and falls back to the first declared. Android and iOS take the first declared, because at build
 *   level `instant` a resource bundle carries no per-ABI content for them to differ by.
 *
 * A destination with no eligible variant is absent from the result rather than filled in with
 * something built for another platform.
 */
fun selectStagingVariants(
    variants: List<PythonVariant>,
    activeBuildType: String,
    hostTarget: String,
    activeFlavor: String? = null,
): Map<PythonStagingPlatform, PythonVariant> {
    val eligible = variants.filter { it.buildTypeName == activeBuildType && (activeFlavor == null || it.flavorName == activeFlavor) }
    return PythonStagingPlatform.values().mapNotNull { platform ->
        val candidates = eligible.filter { PythonStagingPlatform.forTarget(it.target) == platform }
        val chosen = when (platform) {
            PythonStagingPlatform.DESKTOP -> candidates.firstOrNull { it.target == hostTarget } ?: candidates.firstOrNull()
            else -> candidates.firstOrNull()
        }
        chosen?.let { platform to it }
    }.toMap()
}

/** What `buildTypes { }` resolves to when a consumer declares none, matching the pre-graph default. */
const val DEFAULT_BUILD_TYPE = "debug"

/**
 * One node of the per-variant task graph: a declared `python { }` platform variant crossed
 * with a declared `python { buildTypes { ... } }` entry.
 *
 * Both dimensions are needed. The platform variant decides the `pypackpack` target triple and the
 * min SDK; the build type decides `BundleRequest.buildType` (which is `ppp`'s output path segment)
 * and, through [compileLevel], the build level. Issue #2 asks for "a different level per variant",
 * and `compileLevel` lives on `BuildType`, so a graph indexed only by platform could not express it.
 */
data class PythonVariant(
    val platformVariantName: String,
    val buildTypeName: String,
    /** The canonical `pypackpack` target triple, from [PlatformTargetMapping]. */
    val target: String,
    /** Raw `BuildType.compileLevel`; resolved (and possibly rejected) per task, not here. */
    val compileLevel: String,
    /** Declared platform min SDK, or `null` when the platform declares none. */
    val minSdk: Int?,
    /** The `projectFlavors` flavor, or `null` when none are declared. */
    val flavorName: String? = null,
) {
    /**
     * Appended to `buildPython`/`packagePython` to name this variant's tasks:
     * `buildPythonAndroidArm64Release`.
     *
     * AGP spells variant tasks `assemble<Flavor><BuildType>` and KMP spells target tasks
     * `compileKotlin<Target>`; both are "verb + capitalized dimensions" with the build type last,
     * and this is the same. The platform segment keeps the DSL's own spelling, which is also the KMP
     * target name (`androidArm64`, `iosSimulatorArm64`) `PlatformTargetMapping.kotlinTargetName`
     * cross-references.
     */
    val taskSuffix: String
        get() = platformVariantName.capitalizeFirst() + flavorName.orEmpty().capitalizeFirst() + buildTypeName.capitalizeFirst()

    /**
     * This variant's output directory name under `build/pythonBundle/`, and the suffix on its zip.
     * Lower-camel and hyphenated rather than [taskSuffix]'s concatenation, so a path stays readable:
     * `build/pythonBundle/androidArm64-release/`, `build/distributions/app-androidArm64-release.zip`.
     */
    val dirName: String
        get() = listOfNotNull(platformVariantName, flavorName, buildTypeName).joinToString("-")

    /**
     * The dependency set this variant's bundle takes its installed packages from: [dirName] without
     * the build type, because the build type changes neither the requirement list
     * (`collectTargetDependencies`) nor the triple. `build/pythonDeps/androidArm64-free/`, shared by
     * `androidArm64-free-debug` and `androidArm64-free-release`.
     */
    val dependencySetName: String
        get() = listOfNotNull(platformVariantName, flavorName).joinToString("-")

    /** Appended to `installPythonDependencies` to name the set's install task: `installPythonDependenciesAndroidArm64Free`. */
    val dependencyTaskSuffix: String
        get() = platformVariantName.capitalizeFirst() + flavorName.orEmpty().capitalizeFirst()
}

/**
 * Expands the `platforms` and `buildTypes` DSL blocks into the variant list `PythonPlugin.apply`
 * registers one `buildPython<Variant>`/`packagePython<Variant>` pair for -- see
 * `PythonPluginVariantGraphTest` for the design record, and `PythonPlugin.apply` for the wiring.
 *
 * Empty when no platform variant is declared, which is deliberately the case for every consumer
 * that exists today (`usage-example` declares no platform variant). The graph is opt-in: the
 * platform block is what creates more than one target, and without it there is exactly one -- the
 * host -- built by the single `buildPython` task exactly as before.
 *
 * Declaring platforms but no build types yields one [DEFAULT_BUILD_TYPE] variant per platform,
 * matching what `resolveActiveBuildType` already defaults to.
 *
 * Throws for a platform variant `pypackpack` has no triple for (via [PlatformTargetMapping]) or a
 * negative min SDK: both are configuration mistakes with no correct task to register. It does *not*
 * throw for an unsupported `compileLevel` -- that rejection is per variant, and belongs to that
 * variant's task action ([org.thisisthepy.python.multiplatform.toolchain.bundle.BuildPythonArtifactTask.compileLevel]).
 */
fun resolveVariants(
    platforms: PlatformsExtension,
    buildTypes: BuildTypesContainer,
    flavors: ProjectFlavorsContainer = ProjectFlavorsContainer(),
): List<PythonVariant> {
    val platformVariants = declaredPlatformVariantsWithMinSdk(platforms)
    if (platformVariants.isEmpty()) return emptyList()

    val declaredBuildTypes = buildTypes.all().toList()
    val buildTypeNames =
        if (declaredBuildTypes.isEmpty()) listOf(DEFAULT_BUILD_TYPE) else declaredBuildTypes.map { it.name }
    val compileLevels = declaredBuildTypes.associate { it.name to it.compileLevel }
    // No flavors is one `null` flavor, so the names stay exactly what they were before flavors.
    val flavorNames: List<String?> = flavors.all().map { it.name }.ifEmpty { listOf(null) }

    return platformVariants.flatMap { (variantName, minSdk) ->
        flavorNames.flatMap { flavorName ->
            buildTypeNames.map { buildTypeName ->
                PythonVariant(
                    platformVariantName = variantName,
                    buildTypeName = buildTypeName,
                    target = PlatformTargetMapping.canonicalTarget(variantName),
                    compileLevel = compileLevels[buildTypeName].orEmpty(),
                    minSdk = minSdk,
                    flavorName = flavorName,
                )
            }
        }
    }
}

/**
 * The flavor staging uses: `-Ppython.flavor=<name>`, else the first declared, else `null` (no
 * flavors). An undeclared name fails loudly, as [resolveActiveBuildType] does for build types.
 */
fun resolveActiveFlavor(
    flavors: ProjectFlavorsContainer,
    requestedName: String?,
): String? {
    val declared = flavors.all()
    if (declared.isEmpty()) return null
    if (requestedName == null) return declared.first().name
    return flavors.getByName(requestedName).name
}

/**
 * Flavors only name and select variants, so without a platform variant they can change nothing.
 * That is refused rather than ignored (AGENTS.md §14), as a reason the host `buildPython` fails with.
 */
fun flavorsWithoutVariantsRejection(
    flavors: ProjectFlavorsContainer,
    variants: List<PythonVariant>,
): String? =
    if (flavors.all().isNotEmpty() && variants.isEmpty()) {
        "python { projectFlavors { ${flavors.all().joinToString { it.name }} } } declares flavors but no " +
            "platform variant (androidArm64(), iosArm64(), macosArm64(), ...), so there is nothing for " +
            "a flavor to apply to."
    } else {
        null
    }

/**
 * Pairs each declared platform variant with the min SDK its platform block declares
 * (`android { androidSdk = 24 }`, `ios { iosSdk = 14 }`); desktop has no such property in the DSL,
 * so its variants carry `null`.
 *
 * `0` is the DSL default for both properties and means *undeclared*, so it maps to `null` rather
 * than being carried into a task as a real API level. A negative value is a mistake and is rejected
 * by name.
 */
private fun declaredPlatformVariantsWithMinSdk(platforms: PlatformsExtension): List<Pair<String, Int?>> =
    buildList {
        platforms.android?.let { android ->
            val minSdk = normalizeMinSdk(android.androidSdk, "androidSdk")
            android.variants.forEach { add(it.name to minSdk) }
        }
        platforms.ios?.let { ios ->
            val minSdk = normalizeMinSdk(ios.iosSdk, "iosSdk")
            ios.variants.forEach { add(it.name to minSdk) }
        }
        platforms.desktop?.variants?.forEach { add(it.name to null) }
    }

private fun normalizeMinSdk(
    declared: Int,
    propertyName: String,
): Int? {
    require(declared >= 0) {
        "python { } declares a negative $propertyName ($declared); " +
            "a min SDK must be zero (undeclared) or positive."
    }
    return declared.takeIf { it > 0 }
}

private fun String.capitalizeFirst(): String = replaceFirstChar { it.uppercaseChar() }

/**
 * Resolves which declared `python { buildTypes { ... } }` entry is active, factored out of
 * [PythonPlugin.apply] so it can be exercised without a Gradle [org.gradle.api.Project] -- see
 * `PythonPluginBuildTypeTest`.
 *
 * No declared build types at all (the common case today -- `usage-example` does not use the
 * `buildTypes { }` block) passes [requestedName] straight through, so `buildType` keeps behaving
 * exactly as it did before this DSL block was wired up. Once at least one build type is declared,
 * [requestedName] must name one of them; an unmatched name throws rather than silently defaulting,
 * since that is a configuration mistake and `bundleWithPackpack`'s `BundleRequest.buildType` value
 * only affects packpack's output path convention (`<package>/build/packpack/resource/<buildType>/...`),
 * not something that would otherwise fail loudly on its own.
 */
fun resolveActiveBuildType(
    buildTypes: BuildTypesContainer,
    requestedName: String,
): String {
    val declared = buildTypes.all()
    if (declared.isEmpty()) return requestedName

    return declared.firstOrNull { it.name == requestedName }?.name
        ?: throw IllegalArgumentException(
            "Unknown Python build type '$requestedName'. Declared build types: " +
                declared.joinToString(", ") { it.name },
        )
}

/**
 * Resolves `python.localLibraryPath` / `python { sourceSets { commonMain { srcDirs(...) } } }` to
 * the single package directory [BuildPythonArtifactTask.packageDir] /
 * [org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python.InstallDependenciesTask.packageDir]
 * take, factored out of [PythonPlugin.apply] the same way [resolveActiveBuildType] was -- see
 * `PythonPluginSourceSetTest`.
 *
 * `localLibraryPath` is the explicit override and wins whenever it is declared, matching its
 * existing pre-graph behavior exactly (`usage-example` sets only `localLibraryPath`, no
 * `sourceSets` block, and keeps resolving the same way). `commonMain.srcDirs` -- `DSLBuild.kt`'s
 * `SourceSetConfig.srcDirs`, declared but read by nothing until now -- is the fallback: its first
 * entry, resolved against [projectDir]. A [SourceSetConfig] carries a list because a Kotlin source
 * set can add more than one Gradle `srcDir`, but `BuildPythonArtifactTask`/`InstallDependenciesTask`
 * each take one package directory, so only the first is used -- the same single-value narrowing
 * [selectStagingVariants] already does for desktop's staging variant.
 *
 * `null` when neither is declared, matching `localLibraryPath == null`'s existing meaning: no
 * package configured yet, so `buildTask`/`installTask` skip `pypackpack` rather than failing.
 */
fun resolvePackageDir(
    projectDir: File,
    localLibraryPath: String?,
    sourceSets: List<SourceSetConfig>,
): File? {
    localLibraryPath?.let { return File(projectDir, it) }
    val commonMainSrcDir = sourceSets.firstOrNull { it.name == "commonMain" }?.srcDirs?.firstOrNull()
    return commonMainSrcDir?.let { File(projectDir, it) }
}

/**
 * Resolves `python { sourceSets { commonMain { metaDirs(...) } } }` (`DSLBuild.kt`'s
 * `SourceSetConfig.metaDirs`) to the `List<File>` `pypackpack`'s `BundleRequest.metaDirs` takes,
 * factored out the same way [resolvePackageDir] was -- see `PythonPluginSourceSetTest`.
 *
 * Unlike [resolvePackageDir], which narrows `commonMain.srcDirs` to a single package directory,
 * every declared entry is kept: `ResourceBundler` merges each `metaDirs` directory wholesale
 * (`ResourceBundler`'s KDoc, assumption 9), so there is no single value to pick here. Empty when
 * `commonMain` declares none, or when there is no `commonMain` source set at all -- both preserve
 * the pre-existing behavior of a bundle request with no `metaDirs`.
 */
fun resolveMetaDirs(
    projectDir: File,
    sourceSets: List<SourceSetConfig>,
): List<File> {
    val metaDirs = sourceSets.firstOrNull { it.name == "commonMain" }?.metaDirs.orEmpty()
    return metaDirs.map { File(projectDir, it) }
}

/**
 * Resolves `python { sourceSets { commonMain { libDirs(...) } } }` to the `List<File>`
 * `pypackpack`'s `BundleRequest.libDirs` takes -- the same wiring as [resolveMetaDirs], for the
 * sibling DSL list.
 */
fun resolveLibDirs(
    projectDir: File,
    sourceSets: List<SourceSetConfig>,
): List<File> {
    val libDirs = sourceSets.firstOrNull { it.name == "commonMain" }?.libDirs.orEmpty()
    return libDirs.map { File(projectDir, it) }
}

/**
 * Collects the flat dependency list handed to [InstallDependenciesTask.dependenciesList], factored
 * out of [PythonPlugin.apply] the same way [resolveActiveBuildType] was -- see
 * `PythonPluginDependencyTest`.
 *
 * Folds `python { sourceSets { <name> { dependencies { integration(...) } } } }`
 * (`DSLBuild.kt`'s `DependenciesExtension.integrations`) in alongside `implementation(...)`. Until
 * now only `implementations` was read here, so an `integration()` declaration compiled but had zero
 * effect -- it never reached [InstallDependenciesTask], so `installWithPackpack` never saw it and no
 * `uv add` for it ever ran.
 *
 * This makes an `integration()` dependency install exactly like an `implementation()` one, no more
 * and no less: `pypackpack`'s `uv` backend (`installWithPackpack` -> `DependencyBackend.addDependencies`)
 * takes one flat `List<String>` with no type parameter, so it cannot treat the two differently even if
 * asked to. The `KLIBDEPENS` check the example's comment describes runs after installation instead
 * (`findIntegrationsWithoutKlibDepens`, fed by [collectIntegrationDependencies]); it only warns.
 */
fun collectInstallDependencies(sourceSets: List<SourceSetConfig>): List<String> =
    sourceSets.flatMap { it.dependencies.implementations + it.dependencies.integrations }

/**
 * Only the `integration(...)` entries, handed to [InstallDependenciesTask.integrationsList] so that,
 * after the flat install, each can be checked for a `KLIBDEPENS` file (`findIntegrationsWithoutKlibDepens`).
 */
fun collectIntegrationDependencies(sourceSets: List<SourceSetConfig>): List<String> =
    sourceSets.flatMap { it.dependencies.integrations }

/**
 * Resolves `python { buildTypes { getByName(...) { compileLevel = ... } } }` to the `buildLevel`
 * value `bundleWithPackpack` hands `pypackpack`'s `BundleRequest`, factored out of
 * [PythonPlugin.apply] the same way [resolveActiveBuildType] was -- see `PythonPluginBuildLevelTest`.
 *
 * Called from [org.thisisthepy.python.multiplatform.toolchain.bundle.BuildPythonArtifactTask]'s
 * task action, **not** from configuration. It used to be called here in `afterEvaluate`, which was
 * correct while there was a single `buildPython` task and wrong the moment there were several: a
 * configuration-time throw fails every variant, and Issue #2 asks for the unsupported variant alone
 * to be refused. See that task's `compileLevel` kdoc.
 *
 * `pypackpack`'s `ResourceBundler` (`bundle/resource/ResourceBundler.kt`, its `SUPPORTED_BUILD_LEVELS`)
 * implements `instant` and `bytecode`, so both pass through (Issue #15). `bytecode` runs `compileall -b`
 * with the interpreter at `<project>/.venv`; the task checks that interpreter first
 * ([org.thisisthepy.python.multiplatform.toolchain.bundle.bytecodeInterpreterRejection]). `native`
 * and `mixed` need the compile stage's output, whose interface is planned as pypackpack#19, so they
 * are refused here -- per variant, because this runs in the task action (AGENTS.md §14). Anything
 * else is refused as well rather than handed to `ResourceBundler` to refuse in its own words.
 *
 * `BuildType.compileLevel` defaults to `""` for both `DebugBuildType` and `ReleaseBuildType`, which
 * is why blank resolves to `"instant"` -- that keeps `usage-example` (which never sets
 * `compileLevel`) building exactly as it did when this value was hard-coded.
 */
fun resolveBuildLevel(compileLevel: String): String {
    val normalized = compileLevel.ifBlank { "instant" }
    return when (normalized) {
        "instant", "bytecode" -> normalized
        "native", "mixed" -> throw IllegalArgumentException(
            "Python compileLevel '$normalized' needs pypackpack's native/mixed compile slot, which is " +
                "planned (pypackpack#19) and not implemented yet; use 'instant' or 'bytecode'.",
        )
        else -> throw IllegalArgumentException(
            "Python compileLevel '$normalized' is not a build level; use 'instant' or 'bytecode' " +
                "('native' and 'mixed' are planned, pypackpack#19).",
        )
    }
}

/**
 * Validates every variant declared under `python { }` platform maps to a real `pypackpack`
 * target triple (via [org.thisisthepy.python.multiplatform.toolchain.dsl.PlatformTargetMapping]),
 * factored out of [PythonPlugin.apply] the same way [resolveActiveBuildType] and
 * [collectInstallDependencies] were -- see `PythonPluginPlatformsTest`.
 *
 * Throws on the first unsupported variant (via `PlatformTargetMapping.canonicalTarget`) rather than
 * collecting every problem and continuing: `platforms` was previously read by nothing at all, so
 * there is no existing behavior a partial validation would need to preserve, and failing on the first
 * bad variant is the same policy [resolveActiveBuildType] already uses for its own unsupported-value
 * case.
 *
 * This stays a *configuration-time* failure even now that [resolveVariants] exists, and deliberately
 * so: an unmapped variant has no target triple, so there is no task that could be registered for it
 * and then fail on its own. That is the opposite of an unsupported `compileLevel`, where a perfectly
 * valid task exists and only its level is out of reach -- which is why that one is rejected per task
 * instead. [resolveVariants] propagates this same rejection when it maps its variants.
 */
fun validateDeclaredPlatforms(platforms: PlatformsExtension): List<String> {
    val variantNames = declaredPlatformVariantNames(platforms)
    return variantNames.map { PlatformTargetMapping.canonicalTarget(it) }
}

/**
 * Cross-references declared `python { }` platform variants against a project's actually
 * enabled Kotlin Multiplatform targets ("Check Kotlin-side enabled build target", Issue #2's other
 * `platforms` sub-item), factored out of [PythonPlugin.apply] so it can run without a Gradle
 * [org.gradle.api.Project] -- see `PythonPluginPlatformsTest`.
 *
 * Returns the variant names with no matching entry in [enabledKotlinTargetNames], in declaration
 * order. [PythonPlugin.apply] logs these as warnings rather than failing the build: unlike an
 * unmapped variant (which [validateDeclaredPlatforms] rejects because no `pypackpack` triple exists
 * for it at all), a Kotlin target that is simply not enabled *yet* is not necessarily a mistake --
 * the DSL may be declared ahead of the `kotlin { }` block being filled in.
 */
fun findPlatformsWithoutEnabledKotlinTarget(
    platforms: PlatformsExtension,
    enabledKotlinTargetNames: Set<String>,
): List<String> =
    declaredPlatformVariantNames(platforms).filter { variantName ->
        PlatformTargetMapping.kotlinTargetName(variantName) !in enabledKotlinTargetNames
    }

private fun declaredPlatformVariantNames(platforms: PlatformsExtension): List<String> =
    buildList {
        platforms.android?.variants?.forEach { add(it.name) }
        platforms.ios?.variants?.forEach { add(it.name) }
        platforms.desktop?.variants?.forEach { add(it.name) }
    }
