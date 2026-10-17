# Specification

What `toolchain` does: the behavioural contract. Every item stays inside `docs/INTENT.md`; an item
that does not is listed at the end under **Outside intent: needs a decision**.

Each item carries a status:

- **implemented**: the behaviour exists in code *and* a test in this repository exercises it
  (the test file is cited).
- **partial**: some of the behaviour exists, or it exists but no test here exercises it. What is
  missing is said.
- **planned**: the example build file or an issue asks for it; the code does not do it (or only
  declares a DSL property that nothing reads).

Status was assigned on 2026-10-02 by reading `toolchain/src`, `tcl/src` (now `toolchain/src/cliMain`, #78) and their tests. No status
here comes from a roadmap, a commit message or an issue checkbox.

Paths below are abbreviated: `plugin/` = `toolchain/src/main/kotlin/org/thisisthepy/python/multiplatform/toolchain/`,
`ptest/` = the matching `toolchain/src/test/kotlin/...` directory, `tcl/` = `toolchain/src/{cliMain,cliTest}/kotlin/org/thisisthepy/python/multiplatform/tcl/`.

---

## 1. Plugin

### 1.1 Applying the plugin
Plugin id `org.thisisthepy.python.multiplatform`, implementation class `PythonPlugin`
(`toolchain/build.gradle.kts` `gradlePlugin { }`). Applying it creates the `python` extension
(`PythonExtension`) and registers the tasks of §1.10–§1.15 and §1.18, all in group `python`.
Consumers resolve it from `mavenLocal()` after `./gradlew :toolchain:publishToMavenLocal`; the plugin
itself depends on `org.thisisthepy.python.multiplatform:packpack:0.1.0` from `mavenLocal()`.

**Status: implemented**. `plugin/PythonPlugin.kt`; `ptest/PythonPluginApplyTest.kt` applies the
plugin id to a ProjectBuilder project and checks the extension, every task name and type
(`installPythonDependencies`, `buildPython`, `packagePython`, `stagePythonBundle` and its
`Android`/`Ios`/`Desktop` tasks; `hotReloadPython` and `codePushPython` after evaluation), the
`python` group, and the `packagePython → buildPython → installPythonDependencies` chain. Resolving
the plugin from `mavenLocal()` is still exercised only by building `usage-example`.

### 1.2 `compileSdk`: the Python version
`compileSdk` accepts `X.Y`, `X.Y.Z`, `X.Y.Z-alpha[N]` or `X.Y.Z-rc[N]` and classifies it into
`PythonReleaseChannel { ALPHA, RC, NORMAL }`. `toReleaseString()` strips the channel to the `X.Y.Z`
form `pypackpack` accepts. A blank value is a silent skip; a malformed one fails configuration.

**Status: implemented**. `plugin/dsl/PythonVersion.kt`; `ptest/dsl/PythonVersionTest.kt`.

`compileSdk` takes either form the example build file writes, both with `=` (Gradle's Kotlin
assignment overloading on `PythonSdk`): a string, `compileSdk = "3.15.0"`, or a named constant,
`compileSdk = PY3_15_0`. Constants exist only for versions python-multiplatform provides a runtime
for (its pinned `pythonVersion`, 3.15.0), so the example's `PY3_11_9_ALPHA` does not compile (INTENT §4.1). A string resolves to a provided version (`X.Y`
takes the newest `X.Y.*`). A string naming a version that is not provided would be built
automatically (INTENT §4.1), which is not available yet: the reason fails `buildPython…` (only
when there is a package to bundle), not the configuration.

**CPython 3.15t only (#76).** The runtime is CPython 3.15t, free-threaded only (python-multiplatform #158).
`PY3_14_7` and `PY3_13_0` are gone, and a string naming a CPython before 3.15 (`"3.14.7"`, `"3.13"`) is
refused as a GIL-only build, inside `buildPython…` as above, with a message that points to `"3.15t"`.
Test: `PythonSdkTest` (`a CPython before 3_15 is refused as a GIL build, inside the task`).

**Temporary exception: `3.15.0` without `t` (`PY3_15_0`).** It names python-multiplatform's GIL build,
which is what python-multiplatform publishes today: its `gradle.properties` has `pythonFreeThreaded=false`,
its artifact version (`3.15.0-alpha01`) carries no flavour, and Android and iOS have no 3.15t build yet
(python-multiplatform #163). Selecting `cp315t` wheels for that runtime would install wheels it cannot
load. The exception is removed, and `3.15.0` then means `3.15.0t`, when python-multiplatform publishes
free-threaded as its default with the flavour in its version or metadata, and #163 lands; #76 tracks it.

A trailing `t` names the free-threaded build (`compileSdk = "3.15t"`, `"3.15.0t"`); it resolves
against the same published list (python-multiplatform ships both builds of a pinned version) and
there is no free-threaded constant. The `t` never reaches `toReleaseString()`, so the agreement
check with python-multiplatform's `pythonVersion` (§1.12) compares `X.Y.Z` only. **Not checked:**
whether python-multiplatform was built free-threaded (its `freeThreaded` flag is a separate
attribute that toolchain does not read).

The resolved version is also forwarded to every bundle as pypackpack's
`BundleRequest.pythonVersion` (`3.15.0`, or `3.15.0t`; absent without `compileSdk`), which fixes the
wheel tags (`cp315-cp315`, `cp315-cp315t`). **Status: implemented**.
`ptest/bundle/BuildPythonArtifactTaskTest.kt`, `ptest/dsl/PythonSdkTest.kt`,
`ptest/dsl/PythonVersionTest.kt`. Needs a `packpack` in `~/.m2` built from pypackpack#77 or later
(the same `0.1.0` coordinates; CI publishes it from pypackpack `develop`).

**Status: implemented**. `plugin/dsl/PythonSdk.kt`; `ptest/dsl/PythonSdkTest.kt`,
`ptest/bundle/BuildPythonSdkRejectionTest.kt`; `usage-example` uses `compileSdk = PY3_15_0`.

**What `compileSdk` selects (#42).** `compileSdk` selects the Python version of the wheels that
go into `python/`: it is the `--python-version` of every per-target install (§1.10, #16). It does
not fetch or bundle an interpreter. **toolchain ships only `python/`**: the interpreter and the
stdlib belong to python-multiplatform, which links libpython into its binaries (Android JNI, the iOS
framework, desktop FFM) and ships the matching stdlib itself (Android
`assets/<abi>/lib/python3.X`, `PYTHONHOME`). A second interpreter from toolchain could differ from
the linked one even at the same version (iOS 3.14.6 against 3.14.7, for example), and the app would
carry two copies. The interpreter acquisition and `runtime/` bundling added for #18 (#40) were
removed for this reason.

**Status: implemented**. `ptest/TargetDependenciesTest.kt`, `ptest/PythonPluginTargetDependenciesTest.kt`,
`ptest/PythonPluginPythonOnlyTest.kt` (no `acquirePythonInterpreter…` task and no `runtime/` at
embedLevel 2; `--python-version` still follows `compileSdk`).

**Agreement with python-multiplatform's `pythonVersion` (#42).** At embedLevel 2 (§1.12),
python-multiplatform embeds the interpreter named by its `pythonVersion` (its `gradle.properties`),
so `compileSdk` must name the same `X.Y.Z`. `checkPythonVersionAgreement(compileSdk,
pythonMultiplatformVersion)` returns a rejection naming both versions when both are known and their
`X.Y.Z` differ (a `pythonMultiplatformVersion` that is not a version is rejected too); either side
unknown means no check. The rejection fails only that variant's `buildPython…` (with a package to
bundle), like the `compileSdk` rejection above. Levels 0 and 1 are not checked: python-multiplatform
embeds nothing there.

Where `pythonMultiplatformVersion` comes from, in order (`selectPythonMultiplatformVersion`):

1. the `python.multiplatform.pythonVersion` project property (`gradle.properties` or `-P`), when set;
2. otherwise, when this build has a `:python-multiplatform` project, its `pythonMultiplatform`
   extension's `pythonVersion` (type `python.multiplatform.gradle.EmbeddedPythonVersion`,
   python-multiplatform#61), read reflectively by name; that project is made to evaluate first
   (`evaluationDependsOn`);
3. otherwise, for a consumer of the **published** artifact (`io.github.thisisthepy:python-multiplatform*`,
   python-multiplatform#61): the first resolvable runtime classpath that declares it is resolved, and
   the version is the resolved variant's module-metadata attribute `org.thisisthepy.python.version`,
   else the jar resource `META-INF/python-multiplatform/python.properties` (`pythonVersion`; inside
   an AAR's `classes.jar`). This resolves a configuration, so it runs only when 1 and 2 give nothing,
   and only for a level-2 bundle (#49);
4. otherwise unknown: no check, logged once.

**Status: implemented**. `plugin/PythonVersionAgreement.kt`, `plugin/PublishedPythonVersion.kt`;
`ptest/PythonVersionAgreementTest.kt` (the check and the precedence),
`ptest/PythonPluginPythonOnlyTest.kt` (the property and the extension reaching the bundling task, the
property winning), `ptest/PublishedPythonVersionTest.kt` (a local Maven repository with a fake
python-multiplatform module: the metadata attribute, the jar-resource fallback, the AAR's
`classes.jar`, and the published source consulted last). An included build's projects are not
reachable through `rootProject.findProject`; such a build states the version with the property. The
`freeThreaded` flag is not read.
→ *Automatic build of a version python-multiplatform does not provide: **planned**, past 2026-11.*

### 1.3 `defaultConfig { versionCode, versionName, pip { … } }`
`pip { autoUpdate; repositories { central { setUrl(…) }; local { url = … }; jit { url; localRecipes { add(…) } } } }`,
spelled as the example build file spells it (INTENT §4.3), becomes options of the `uv add` that
`installPythonDependencies` runs and of every per-target `uv pip install` (§1.10):

| DSL | `uv add` option |
|---|---|
| `central { setUrl(a, b, …) }` | `--default-index a`; the other URLs, minus repeats of `a`, as `--index` |
| `local { url = … }` | `--find-links <dir>`; a `file:` URI becomes a path |
| `autoUpdate = true` | `--upgrade` |
| `jit { … }` | **rejected**: pypackpack has no recipe build. The rejection fails `installPythonDependencies` and the per-target install tasks, each only when it has something to install. |

**Status: implemented**. `resolvePipArguments` / `resolvePipSettings` in
`plugin/dependency/lang/python/PipRepositories.kt`; `ptest/dependency/lang/python/PipRepositoriesTest.kt`
(including a real `uv add` sent to the declared index).
→ *`jit` / `localRecipes`: **planned**, needs a recipe build in pypackpack.*
`versionCode` / `versionName` are the Python payload's version (decided 2026-10-03): handed to every
`buildPython…` task and forwarded to pypackpack's `BundleRequest`, which records them in
`resource-manifest.json` as `"versionName"` / `"versionCode"` beside the package's own `"version"`.
Undeclared stays `null`: nothing is written, and no default is invented. Code push reads them later.
**Status: implemented**. `ptest/PythonPluginPayloadVersionTest.kt` (host and variant tasks),
`ptest/bundle/BuildPythonArtifactTaskTest.kt` (the real manifest).

### 1.4 Platforms
Declared directly inside `python { }`, as the example build file writes them:
`android("android") { androidSdk = 24 }`, `listOf(androidArm64(), androidX64())`, `ios { iosSdk = 14 }`,
`listOf(iosArm64(), …)`, `desktop()`, `listOf(macosX64(), …)`. Calling a variant function declares
that variant (the `listOf` only groups); a variant called without its platform block creates the
block with its defaults; a platform block may come before or after its variants; a variant
called twice is declared once. There is no `platforms { }` block.
Each variant maps to a `pypackpack` target triple and a Kotlin target name:

| Variant | Target triple | Kotlin target |
|---|---|---|
| `androidArm64` | `aarch64-linux-android` | `androidNativeArm64` |
| `androidX64` | `x86_64-linux-android` | `androidNativeX64` |
| `iosArm64` | `arm64-apple-ios` | `iosArm64` |
| `iosX64` | `x86_64-apple-ios-simulator` | `iosX64` |
| `iosSimulatorArm64` | `arm64-apple-ios-simulator` | `iosSimulatorArm64` |
| `macosX64` / `macosArm64` | `x86_64-apple-darwin` / `aarch64-apple-darwin` | same name |
| `linuxX64` / `linuxArm64` | `x86_64-unknown-linux-gnu` / `aarch64-unknown-linux-gnu` | same name |
| `mingwX64` | `x86_64-pc-windows-msvc` | `mingwX64` |

Android follows CPython's official Android support (PEP 738): `androidArm32()` and `androidX86()`
are **compile errors** (`@Deprecated(level = ERROR)`) whose message names the two supported
triples. An unknown name reaching the mapping is rejected with the supported list. A
declared variant whose Kotlin target is not enabled in the consumer's `kotlin { }` block produces a
**warning**, not a failure. `androidSdk`/`iosSdk` of `0` mean "undeclared"; negative values are
rejected; a declared value is forwarded to `pypackpack` as `BundleRequest.minSdk`.

**Status: implemented**. `plugin/dsl/DSLPlatforms.kt`, `plugin/dsl/DSLCore.kt`, `plugin/PythonPlugin.kt`;
`ptest/dsl/PythonExtensionPlatformDslTest.kt` (the example's declarations through `PythonExtension`),
`ptest/dsl/PlatformTargetMappingTest.kt`, `ptest/PythonPluginPlatformsTest.kt`,
`ptest/PythonPluginVariantGraphTest.kt`, `ptest/bundle/BuildPythonArtifactTaskTest.kt` (minSdk).

The shape is the example build file's (INTENT §4.2, decided). The example's 32-bit/x86 Android
variants predate the decision to follow CPython's Android support and are not built.

### 1.5 Build types
`buildTypes { getByName("debug") { … }; getByName("release") { … } }`. Only `debug` and `release`
exist; any other name throws. `debug` forbids setting `useCodeMinifier`, `excludeMetaclass` and
`enableCodePush` (setting them throws). Without a declared platform variant the active build type is chosen
by `-Ppython.buildType=<name>` (default `debug`); an undeclared name fails loudly.

**Status: implemented**. `plugin/dsl/DSLBuild.kt`, `resolveActiveBuildType` in `plugin/PythonPlugin.kt`;
`ptest/PythonPluginBuildTypeTest.kt`. (The debug-only setter rejections have no test: partial.)

### 1.6 `compileLevel`
Blank resolves to `instant`. `instant` and `bytecode` pass through to `BundleRequest.buildLevel`.
`native` and `mixed` are rejected with a message naming the planned compile slot (pypackpack#19);
any other value is rejected too. Rejection happens **inside the variant's task action**, so
`--continue` still builds every other variant.

`bytecode` is `pypackpack`'s `ResourceBundler`: `compileall -b` writes `foo.pyc` beside `foo.py`;
`debug` keeps the `.py`, `release` deletes it and ships only `.pyc`. It compiles with the interpreter
at `<dir>/.venv/{bin/python3,bin/python,Scripts/python.exe}`, the first found walking up from the
package directory. Before calling it, the task checks that interpreter
(`bytecodeInterpreterRejection`) and fails that variant when:

- **no `.venv` is found**: the message names the directory searched and two ways to make one:
  declare a dependency, so `installPythonDependencies` (`uv add`) creates `<package>/.venv`, or run
  `uv venv --python <X.Y>` there. toolchain does not create the venv itself: choosing and acquiring
  an interpreter is `pypackpack`'s work, and `uv venv` would replace a `.venv` that `uv add` made.
- **its minor version differs from `compileSdk`'s**: a `.pyc`'s magic number changes with every
  CPython minor release, so a 3.13 `.pyc` does not load on a 3.14 runtime. The venv's version is read
  from its `pyvenv.cfg` (`version =` from the stdlib `venv`, `version_info =` from `uv`/`virtualenv`).

**Status: implemented**. `resolveBuildLevel` in `plugin/PythonPlugin.kt`, `bytecodeInterpreterRejection`
in `plugin/bundle/BuildPythonArtifactTask.kt`; `ptest/PythonPluginBuildLevelTest.kt`,
`ptest/PythonPluginVariantGraphTest.kt`, `ptest/bundle/BytecodeLevelTest.kt` (real `compileall`:
debug has `.py` + `.pyc`, release has `.pyc` only; needs `python3` on `PATH`),
`ptest/dependency/lang/python/InstallDependenciesTaskTest.kt` (the `uv add` venv is found and read).

Known limits of the version check: it is skipped when `compileSdk` is not declared (nothing to
compare with), and when the venv has no readable `pyvenv.cfg` (a hand-made `.venv/bin/python3`
symlink, for example). In both cases the `.venv`'s own interpreter decides the magic number.
→ *Building at `native` / `mixed`: **planned**, blocked on pypackpack#19.*

### 1.7 `useCodeMinifier`, `excludeMetaclass`
Both are declared on `ReleaseBuildType` (`debug`'s setters throw, §1.5).

`excludeMetaclass = true` leaves `commonMain`'s `metaDirs` out of that build type's bundle tasks
only: `buildPython<Variant><BuildType>` for each variant of it, and the host `buildPython` when it is
the active build type (`-Ppython.buildType`). Other build types keep them (§1.16 `metaclass` aside).

**Status: implemented** (`excludeMetaclass`). `resolveBundledMetaDirs` in `plugin/BuildFeatures.kt`;
`ptest/BuildFeaturesTest.kt`, `ptest/PythonPluginBuildFeaturesTest.kt`.
→ *`useCodeMinifier`: declared, read by nothing. **planned**, blocked on `pypackpack` (no minifier).*

### 1.8 Per-variant task graph
When at least one platform variant is declared, every variant is crossed with every declared build
type (or `debug` alone if none is declared). Each pair gets
`buildPython<Variant><BuildType>` and `packagePython<Variant><BuildType>`, writes to
`build/pythonBundle/<variant>-<buildType>/`, and zips to
`build/distributions/<fileName>-<variant>-<buildType>.zip`. `buildPython` and `packagePython` become
lifecycle tasks (their own action is skipped). Without a declared variant there is exactly one
host-target chain, as before.

**Status: implemented** (resolution and naming). `resolveVariants`, `PythonVariant`;
`ptest/PythonPluginVariantGraphTest.kt`. The task registration itself is untested here.

### 1.9 Source sets
`sourceSets { val commonMain by getting { srcDirs(…); metaDirs(…); libDirs(…) } }`. The three
directory functions are accepted only on `commonMain` and throw elsewhere. The package directory is
`localLibraryPath` if set, else the **first** `commonMain.srcDirs` entry, else none (bundling and
installation are skipped). Every `metaDirs` and `libDirs` entry is forwarded to `pypackpack` as
`BundleRequest.metaDirs` / `libDirs`: `metaDirs` subject to `buildFeatures { metaclass }` and
`excludeMetaclass` (§1.16, §1.7).

The `by getting { … }` block is applied at declaration (`provideDelegate`), so it takes effect even if
the property is never read (issue #35); see `SourceSetGettingDelegateTest`.

**Status: implemented**. `resolvePackageDir`, `resolveMetaDirs`, `resolveLibDirs`;
`ptest/PythonPluginSourceSetTest.kt`, `ptest/bundle/BuildPythonArtifactTaskTest.kt`.

### 1.10 Dependencies and `installPythonDependencies`
`dependencies { implementation("pkg"); integration("pkg") }` in a source set. Two installations
read these declarations.

**Into the package's venv: `installPythonDependencies`.** All source sets' `implementation` **and**
`integration` entries are flattened into one list and installed into the package directory through
`pypackpack`: `BackendInterface.create(BackendType.UV)` → `addDependencies(…, workingDir = packageDir)`
(a real `uv add`). This records the dependencies in the package's `pyproject.toml` and leaves the
`<package>/.venv` that `compileLevel = "bytecode"` compiles with (§1.6). It does not feed a bundle. An
empty list or no package directory is a skip, not a failure.

**Per target: `installPythonDependencies<Set>`.** What reaches a bundle is installed separately,
once per *dependency set*: a platform variant crossed with a flavor (§1.17). The build type is not
part of it, because it changes neither the requirement list nor the triple, so `debug` and `release`
share one install.

- A set reads `commonMain`, then its family's source set: `androidMain`, `iosMain` or `desktopMain`
  (macOS, Linux and Windows are one `desktopMain`, as they are one staging destination, §1.13),
  then `<flavor>Main`. Entries keep that order; repeats are dropped. `buildFeatures { compose }` adds
  `pythonx-compose` to every set (§1.16).
- The task (`installPythonDependenciesAndroidArm64`, `…AndroidArm64Free`) clears
  `build/pythonDeps/<set>/` (`androidArm64`, `androidArm64-free`) and calls `pypackpack`'s
  `UVBackend.installDependenciesToTarget(targetDir, pythonPlatform = <the variant's triple>, extraArgs,
  workingDir, requirements = <the set's list>)`: `uv pip install <requirements…> --target <dir>
  --python-platform <triple>` (pypackpack#36). The list goes to `uv` as arguments; nothing is
  written. `uv` runs in the task's temporary directory (`build/tmp/<task>/`), outside the install
  directory.
- Options: the `pip { }` repositories (§1.3), `python-version` = `compileSdk`'s `major.minor` (wheel
  tags carry the CPython ABI), and `only-binary = :all:` (no sdist is built with the host compiler).
  A requirement with no wheel for the triple fails that set's task with uv's message, which names it.
- Every bundle task of the set (§1.11) lists `build/pythonDeps/<set>/` **first** in `libDirs` and
  depends on the task, so a declared `libDirs(…)` tree overrides an installed file.
- Without a platform variant the host chain does the same: `installPythonDependenciesHost`,
  `commonMain` + `desktopMain`, the host triple, `build/pythonDeps/host/`.
- Dependencies in a source set no set reads (`androidArm64Main`, `fooMain`, or `<flavor>Main` with no
  such flavor) fail every per-target task with a message naming it, instead of reaching no bundle.
- Task inputs are the requirement list, triple, options and rejections; the output is the directory.
  `pip { autoUpdate = true }` makes the task never up to date. No package directory skips the task.

**Status: implemented**. `plugin/dependency/lang/python/InstallDependenciesTask.kt`,
`plugin/dependency/lang/python/InstallTargetDependenciesTask.kt`, `plugin/TargetDependencies.kt`;
`ptest/PythonPluginDependencyTest.kt`, `ptest/TargetDependenciesTest.kt` (source-set selection per
family and flavor, options, rejection), `ptest/PythonPluginTargetDependenciesTest.kt` (tasks,
install directories, `libDirs` and dependencies on an applied plugin),
`ptest/dependency/lang/python/InstallDependenciesTaskTest.kt` and
`ptest/dependency/lang/python/InstallTargetDependenciesTaskTest.kt` (a real install of `six` for
`aarch64-linux-android`; both need `uv` and network). CI's consumer job checks that usage-example's
`iniconfig` is in the bundle and the zip.
→ *Known limits: without `compileSdk` no `python-version` is passed and uv uses the interpreter it
finds. `installPythonDependencies` still `uv add`s every source set for the host, so a package with
no host wheel fails it even if only `androidMain` declares it. `pypackpack`'s `ResourceBundler` drops
`.pyd` files and directories named `build`/`dist`, so a Windows extension module does not reach a
`mingwX64` bundle.*

**`KLIBDEPENS` check.** `installPythonDependencies` also carries the `integration` entries on their own (`integrationsList`). After the install it
looks in the package directory's venv (`<package dir>/.venv`: `lib/python3.X/site-packages`, or
`Lib/site-packages` on Windows) for each integration's `<name>-<version>.dist-info/` directory, matching
the name under PEP 503 normalization (case-insensitive; runs of `-`, `_`, `.` equal; version specifiers,
extras and markers in the spec are ignored). A package whose dist-info has no `KLIBDEPENS` file (or that
is not found) gets a warning naming it and suggesting `implementation(...)`; it is never a failure. If no
`site-packages` exists the task warns that it cannot check.
*Assumption:* no repository defines `KLIBDEPENS`; it is taken to be a file of that name inside the
wheel's `*.dist-info/` directory, and only its presence is checked, not its content.

KLIBDEPENS check (**Status: implemented**) `plugin/dependency/lang/python/InstallDependenciesTask.kt`;
`plugin/dependency/lang/python/KlibDepens.kt`; `ptest/PythonPluginDependencyTest.kt`,
`ptest/PythonPluginIntegrationListTest.kt`, `ptest/dependency/lang/python/KlibDepensTest.kt` (fake
site-packages trees), `ptest/dependency/lang/python/InstallDependenciesTaskTest.kt` (needs `uv` and network).

### 1.11 Bundling: `buildPython`
Builds a `pypackpack` `BundleRequest` (package dir, target triple, build type, build level, output
dir, `overwrite = true`, minSdk, metaDirs, libDirs) and calls
`BundlerInterface.create(BundleType.RESOURCE).bundle(request)`. `libDirs` is the task's
dependency set's `build/pythonDeps/<set>/` (§1.10) followed by the declared `libDirs(…)` (§1.9), so
the installed packages land in `python/` beside the app's modules. The result is `python/` plus a
`resource-manifest.json`. A failure becomes a `GradleException`. No package directory: the output
directory is created empty and `pypackpack` is not called.

**Status: implemented**. `plugin/bundle/BuildPythonArtifactTask.kt`;
`ptest/bundle/BuildPythonArtifactTaskTest.kt`.

### 1.12 Packaging: `packagePython`
Zips the bundle directory to `build/distributions/<fileName>.zip` (or `<fileName>-<variant>.zip`).
Fails if the bundle directory does not exist.

**Status: implemented**. `plugin/bundle/AssemblePythonPackageTask.kt`;
`ptest/bundle/AssemblePythonPackageTaskTest.kt` runs the real task's action: the archive name and
location for the aggregate and a variant task, the zip entries, and the failure when the bundle
directory is missing.
`packaging { embedLevel }`: 0 no interpreter, 1 the app uses an external interpreter, 2
python-multiplatform embeds the interpreter. **toolchain ships no interpreter at any level**: the
bundle and the zip hold `python/` (and `resource-manifest.json`) only (§1.2). The level is resolved
per packaging task by `resolveEmbedLevel(declared, override, platformFamily)` (the family is
`Platforms.getPlatformFamily` of the variant's target; the host chain uses the host's family):

| family | 0 | 1 | 2 |
|---|---|---|---|
| macos, linux, windows | 0 | 1 | 2 |
| android, ios (sandboxed, no system Python) | raised to 2, warning | raised to 2, warning | 2 |

The `python.embedLevel` property (`gradle.properties` or `-P`) overrides the DSL value and is then
raised the same way. A value outside 0..2, or not a number, fails configuration naming the property.
Each packaging task carries the resolved level as an `@Input`, logs it, and writes
`<archive>.embed.json` beside the zip: `embedLevel`, `platformFamily`, `warning` and
`interpreterVersion`: the version the app expects (`expectedInterpreterVersion`): the `compileSdk`
release at levels 1 and 2 (at 2, the one python-multiplatform is expected to embed), otherwise, or
with no or a rejected `compileSdk`, `null`. The level never changes the payload. At level 2,
`compileSdk` is checked against python-multiplatform's `pythonVersion` (§1.2).

**Status: implemented**. `ptest/EmbedLevelTest.kt`, `ptest/PythonPluginEmbedLevelTest.kt`,
`ptest/PythonPluginPythonOnlyTest.kt` (zips a level 2 variant bundle, finds `python/` and no
`runtime/`, and reads its record).

### 1.13 Staging into the app: `stagePythonBundle{Android,Ios,Desktop}`
Copies the bundle's `python/` subtree (never the manifest) into
`build/pythonStaging/<android|ios|desktop>/python/`, deleting what a previous run staged first. The
bundle payload is a declared input, so a changed package re-stages. One variant is chosen per
destination: only variants of the active build type; desktop prefers the host's own triple, else the
first declared; Android and iOS take the first declared. A destination with no matching variant
stages nothing.

`python/` is all toolchain stages, at every `embedLevel`: the interpreter and the stdlib reach the app
through python-multiplatform, which links libpython and ships the matching stdlib itself (§1.2, #42).

**Status: implemented** (copy rule and selection). `plugin/bundle/StagePythonBundleTask.kt`,
`selectStagingVariants`; `ptest/bundle/StagePythonBundleTaskTest.kt`, `ptest/PythonPluginStagingTest.kt`.

Hand-off to the platform's packaging step:

- Desktop: the staged root is added to the JVM target's `<target>ProcessResources`, so the payload
  is in the desktop jar. **implemented**: `ptest/PythonPluginAttachmentTest.kt` (Kotlin
  Multiplatform with `jvm("desktop")`: `stagePythonBundleDesktop` is a dependency of
  `desktopProcessResources` and the staged file is among its sources). Not tested: the fallback for
  a `<target>ProcessResources` that is not a `Copy` task (dependency plus warning), and the built jar
  itself.
- Android: the staged root is added to `android.sourceSets.main.assets` (reflectively), so the
  payload is in the APK's `assets/`. **partial**: `ptest/PythonPluginAttachmentTest.kt` applies the
  real `com.android.application` (AGP 8.5.2, test classpath only) and checks the staged root is a
  `main` asset source directory. Not tested: the `preBuild` / `merge*Assets` → `stagePythonBundleAndroid`
  dependency (AGP creates those tasks only when the project is evaluated against an Android SDK,
  which `:toolchain:test` does not require), and the built APK.
- iOS: toolchain stages and exposes the path; python-multiplatform (#59) owns the Xcode build phase
  that attaches it. `stagePythonBundleIosForXcode` (group `python`) depends on `stagePythonBundleIos`
  and prints exactly one line `PYTHON_PAYLOAD_DIR=<absolute path>` (stdout, visible under `--quiet`).
  It fails with a reason when no iOS variant of the active build type exists, or the staged
  `build/pythonStaging/ios/python/` is missing or empty (no package configured). It never copies into
  the `.app`. With python-multiplatform, its `tools/xcode/install-python.sh` is the single phase that
  copies `python/` (and the stdlib): it takes `PYTHON_PAYLOAD_DIR` (or `PYTHON_PAYLOAD_TASK` within its
  own Gradle root); usage-example's `usage-example/src/iosMain/install-python-phase.sh` computes the directory
  with this task and passes it. Exactly one Xcode phase may copy `python/`. Wiring by hand without
  python-multiplatform: the guide's staging page has the safe phase snippet (capture, parse, check
  the directory, then `rsync --delete`); the repository no longer ships it as a script (#62).
  **partial**: `xcodePayloadLine`, `ptest/bundle/StagePythonBundleIosForXcodeTaskTest.kt`,
  `ptest/bundle/StagePythonPayloadScriptTest.kt`. Not tested: an actual Xcode build.
- Putting the staged `python/` on `sys.path` at run time is `python-multiplatform`'s side.

### 1.14 Hot reload: `hotReloadPython`
`packaging { hotReload { serverHost; redirectErrorStream; cert { keyStore | autoGenerate } } }` is
validated when any build type sets `enableHotReload = true`: `serverHost` must be non-blank, and
`keyStore` and `autoGenerate` are mutually exclusive. The task collects every `.py` under
`localLibraryPath`, runs `adb push` for each to `/data/local/tmp/<fileName>/python/…`, then
`adb shell am broadcast -a org.thisisthepy.python.RELOAD_PYTHON`.

**Status: partial**. `plugin/hotreload/`; `ptest/PythonPluginHotReloadTest.kt`,
`ptest/hotreload/WatchAndPushTest.kt` test the validation and the command list; the task action
itself is untested. `serverHost` and `cert` are validated but not used for transport (adb only);
the source root ignores `commonMain.srcDirs`; Android only (a desktop copy helper,
`executeLocalCopy`, exists and is tested but no task calls it).
→ *A hot-reload server reachable at `serverHost`, and a "run" entry point for it: **planned**.*

### 1.15 Code push: `codePushPython`
`packaging { codePush { serverHost; cert; uploadConfig { forceUpload; login { id; password } } } }`
is validated the same way when any build type sets `enableCodePush = true`. The task prints that no
upload client exists.

**Status: partial** (validation). `plugin/hotreload/HotReloadConfig.kt`, `CodePushPendingTask.kt`;
`ptest/PythonPluginCodePushTest.kt`. → *Upload: **planned**.*

### 1.16 `buildFeatures { metaclass, compose }`
**`metaclass`** (default `true`): `true` forwards `commonMain`'s `metaDirs` to `BundleRequest.metaDirs`
as §1.9 describes; `false` forwards none, from every bundle task. A build type's `excludeMetaclass`
(§1.7) also drops them, for that build type only.

**`compose`** (default `false`): `true` wires the Compose wrapper in two halves. Neither artifact is
published to a remote yet, so their locations come from Gradle project properties (`gradle.properties`
or `-P`) with no default:

| Property | Value | Effect |
|---|---|---|
| `python.compose.pythonxCompose` | a pip requirement naming `pythonx-compose` (`pythonx-compose==0.1.0`), or an existing directory of wheels (absolute, relative to the project directory, or a `file:` URI) | The requirement (or `pythonx-compose` for a directory) is appended to `installPythonDependencies`' list and to every per-target list (§1.10). A directory is appended to the `find-links` option `pip { repositories { local } }` produces, comma-separated after it (uv splits `--find-links` on commas). |
| `python.compose.kotlinModule` | a Maven coordinate `group:artifact:version` of `python-multiplatform-compose` | Added to Kotlin Multiplatform `commonMain`'s `implementation` when `org.jetbrains.kotlin.multiplatform` is applied. |

Failures, each naming the property and what it is for:
- `pythonxCompose` missing, or neither a directory nor a `pythonx-compose` requirement: carried to
  `installPythonDependencies` and the per-target install tasks and thrown from their actions (§14), so
  tasks that install nothing still run.
- `kotlinModule` missing or not `group:artifact:version`, with the Kotlin Multiplatform plugin
  applied: fails configuration. A dependency has no task action to carry a rejection to, and every
  Kotlin compilation includes `commonMain`, so there is no narrower valid place.
- Kotlin Multiplatform not applied: the Kotlin half is skipped with a warning and `kotlinModule` is
  not required; `pythonx-compose` is still installed.

**Status: implemented**. `resolveBundledMetaDirs`, `resolveComposePythonInstall`,
`mergeComposeFindLinks`, `resolveComposeKotlinDependency` in `plugin/BuildFeatures.kt`;
`ptest/BuildFeaturesTest.kt`, `ptest/PythonPluginBuildFeaturesTest.kt` (task wiring on a ProjectBuilder
project, the Kotlin dependency with `org.jetbrains.kotlin.multiplatform` applied). Installing a real
`pythonx-compose` is not exercised: the package is not published.

### 1.17 `projectFlavors { }`
`projectFlavors { create("free"); create("paid") }`, AGP-style (decided 2026-10-03; the example
build file declares the block empty). Each flavor is crossed into the per-variant graph (§1.8)
between platform and build type: `buildPythonAndroidArm64FreeDebug`,
`build/pythonBundle/androidArm64-free-debug/`. A flavor name is lower-camel, unique and not a build
type's name; anything else fails configuration. Staging (§1.13) takes the variants of one flavor:
`-Ppython.flavor=<name>`, else the first declared; an undeclared name fails loudly. Flavors with
no platform variant could change nothing, so the host `buildPython` fails with that reason. A
flavor's own dependencies go in a `<flavor>Main` source set and are installed only for that
flavor's variants (§1.10).

**Status: implemented**. `plugin/dsl/DSLFlavors.kt`, `resolveVariants`, `resolveActiveFlavor`,
`flavorsWithoutVariantsRejection` in `plugin/PythonPlugin.kt`; `ptest/PythonPluginFlavorsTest.kt`
(including task registration on an applied plugin).
→ *Per-flavor properties: **planned**.*

### 1.18 TypedPython check: `typedpythonCheck`
Source: issue [`toolchain#23`](https://github.com/thisisthepy/toolchain/issues/23) (TypedPython step
1b; checking is on by default, maintainer decision 2026-10-02).

One `typedpythonCheck` task per project statically checks the project's own Python before it is
bundled. Every `buildPython<Variant><BuildType>` and the single host `buildPython` depend on it.

- **Sources.** Every `.py` file under the directory `resolvePackageDir` resolves to (§1.9): the
  directory that gets bundled: excluding hidden directories (`.venv`, …), `__pycache__` and
  `build`. `metaDirs` and `libDirs` are bundled but **not checked**: `libDirs` holds third-party
  site-packages and `metaDirs` generated metadata, neither of which the user wrote or could fix (asserted
  by `typedpythonCheckedDirs`). `.py` and `.pyi` files there are `@InputFiles` with `@PathSensitive(RELATIVE)`: a changed
  `.py` re-runs the check, a change to any other file leaves it `UP-TO-DATE`. No package directory,
  or no `.py` file in it: the check is skipped with a log line and the gate is not installed.
- **Gate.** The `typedpython` wheel and the `pyrefly` it pins, both at pinned versions (`@Input`,
  defaults `0.1.0` and `1.3.2`), installed into `build/typedpython/venv`: never the project's `.venv`.
  The venv is created through `pypackpack`'s UV backend (`createVirtualEnvironment`, `--no-project
  --clear`, Python `>=3.13`; only when the venv does not exist). The install is
  `uv pip install --python <venv> --no-index --find-links <dir> typedpython==<v> pyrefly==<v>`
  (skipped when the same request is already installed): **both packages come from `<dir>` and
  nothing is ever fetched from an index**: `typedpython` is unclaimed on PyPI, so a bare-name install
  would run whatever someone publishes under that name on every consumer's build. `<dir>` is the
  project property `typedpython.wheelDir` and must hold the `typedpython` wheel and the `pyrefly` wheel
  for the platform. An install failure fails the task and names that requirement. The install calls
  `uv` directly: `pypackpack`'s backend has no operation that installs named requirements into an
  existing venv from a local wheel directory (`installDependenciesToTarget` reads `-r pyproject.toml`
  into `--target`; `addDependencies` edits `pyproject.toml`): a `pypackpack` change, not made here.
- **No wheel directory.** The check is **skipped with a loud warning** (what was skipped, that the
  gate is not on PyPI yet, and `-Ptypedpython.wheelDir=<dir>` to turn it on); nothing is created or
  installed. With a wheel directory a failed check fails the build.
- **Run.** `<venv>/bin/typedpython check --mode <mode> [--search-path <dir>]... <file>...`, with
  explicit files (the 0.1.0 gate crashes on a directory argument). `mode` is an `@Input`, default
  `checked`; any value other than `checked`/`compiled` fails the task.
- **Import root.** The package's own import root (`<packageDir>/src/main` when it exists, which is the
  `pypackpack` layout, else the package directory) is passed as the first `--search-path`, so the
  package's modules can import one another (the 0.1.0 gate otherwise infers its temporary config
  directory as the import root).
- **Stubs.** The plugin creates a resolvable configuration `typedpythonStubs`; its files are
  `@InputFiles` and each is passed as `--search-path`, in order. With nothing in it, the check runs
  without stubs and logs that once.
- **Result.** Exit 0 passes and prints any `WARNING` lines. Exit 1 with diagnostics fails the build
  with them. Exit 2 (or exit 1 with no diagnostic line, which is how an uncaught exception in the
  gate exits) fails the build with the tool's own output. A passing run writes
  `build/typedpython/report.txt` (the task's output, so an unchanged rerun is `UP-TO-DATE`).

**Status: implemented**. `plugin/typedpython/TypedpythonCheckTask.kt`, wiring in `plugin/PythonPlugin.kt`;
`ptest/typedpython/TypedpythonCheckTaskTest.kt` (TestKit: an `Any` leak fails `buildPython` and the
fix passes; per-variant `buildPython*` depend on the check; only a `.py` change re-runs it; a
`typedpythonStubs` directory resolves an import that fails without it; the no-stubs line appears
once; the package's modules import one another; a `pyrefly` pin absent from the wheel directory fails
instead of being fetched; `metaDirs`/`libDirs` are not checked),
`ptest/typedpython/TypedpythonNoWheelTest.kt` (no wheel directory: skipped with the warning),
`ptest/typedpython/TypedpythonDecisionsTest.kt` (source selection, import root, `--no-index`
commands, skip warning, mode, exit-code interpretation). The TestKit tests in
`TypedpythonCheckTaskTest` need `-Ptypedpython.wheelDir=<dir>` (holding the `typedpython` and
`pyrefly` wheels) and `uv`; without it they are **skipped** with that reason, as on CI.
Not covered by a test here: exit 2 end to end (only `interpretTypedpythonResult`), and the install
in a *consumer's* classpath: `withPluginClasspath()` bypasses the Kotlin DSL's `kotlin-stdlib` pin;
that was verified by building usage-example's `buildPython`.
→ *Until `typedpython` is on PyPI, a build without `-Ptypedpython.wheelDir` skips the check with a warning.*
→ *Platform overlays (`src/<family>`) are checked as files but only `src/main` is an import root:
**partial**.*

## 2. `tcl`: toolchain-lite

### 2.1 `tcl install <package>`
Finds the nearest `pyproject.toml` at or above the working directory. If there is none, runs
`uv init --bare` there first (through `pypackpack`'s `BackendInterface.initProject`), then
`addDependencies` for the package. Exit code 0 on success and the `uv` output on stdout; 1 with a
message on stderr for a missing argument, an unknown command or a failed install. No arguments
prints `Usage: tcl install <package>`.

**Status: implemented**. `tcl/Cli.kt`, `tcl/Installer.kt`; `tcl/CliArgsTest.kt`,
`tcl/InstallerTest.kt` (needs `uv` and network). Run with `./gradlew :toolchain:runTcl --args="install <pkg>"`.

### 2.2 `tcl --help` and `tcl --version`
`--help` (or `-h`) prints the usage line on stdout and exits 0. `--version` prints `tcl <version>`
and exits 0. The version is tcl's own (`tclVersion` in `toolchain/build.gradle.kts`, 0.1.0), separate from the plugin's,
written by the build into `build-info.properties` and read by `BuildInfo`.

**Status: implemented**. `tcl/Cli.kt`, `tcl/BuildInfo.kt`; `tcl/CliArgsTest.kt` (the version test
compares against the version the build passes to the test JVM).

### 2.3 toolchain-lite on PyPI
```shell
uv tool install toolchain-lite
tcl --help
```
- One wheel per platform, `py3-none-<platform>`: Linux x86_64 and aarch64 (manylinux), macOS arm64
  (11+), Windows x86_64. Each carries the GraalVM native image `tcl` as a script, plus the launcher
  package `toolchain_lite` (`toolchain/src/cliMain/python/`) for `python -m toolchain_lite`.
- The platform tag is read from the binary (`.github/scripts/pypi/build_wheel.py`). No sdist:
  building needs a JDK and GraalVM.
- `.github/workflows/publish-pypi.yml` runs on a published GitHub Release only and uploads through
  trusted publishing (environment `pypi`, which accepts only `toolchain-lite-v*` tags). Before uploading
  it requires the tag to be `toolchain-lite-v<version>` (bare `v*` tags are the Gradle plugin's, #71),
  `pyproject.toml` and `toolchain/build.gradle.kts`'s `tclVersion` to carry that version, the version to be new on PyPI,
  and every wheel to pass `smoke_wheel.py` in a fresh venv (`tcl --help`, `tcl --version`,
  `python -m toolchain_lite --version`, and `tcl install six` writing a `pyproject.toml`).
  Pull requests that touch these files run everything but the upload.
- PyPI's 0.0.1 (2025-06-12) packed the old `toolchain/` Gradle directory and installs no `tcl`. It is not
  yanked yet; yanking it is the maintainer's step on PyPI.

**Status: implemented**. 0.1.0 is on PyPI (uploaded 2026-10-03 from the `toolchain-lite-v0.1.0` release):
the four wheels above, each smoke-tested before upload. `uvx --from toolchain-lite==0.1.0 tcl --version`
prints `tcl 0.1.0` (checked 2026-10-17, macOS arm64). Pull requests that touch these files still build
and smoke-test the wheels without uploading.

---

## Outside intent: needs a decision

These exist in the code but are not asked for by the example build file or the issues.

1. **`python { localLibraryPath = "…" }`**: not in the example build file, which uses
   `sourceSets { commonMain { srcDirs(…) } }`. It overrides `srcDirs` and is the only source root
   hot reload reads. `usage-example` depends on it.
2. **`-Ppython.buildType=<name>`**: a project property choosing the active build type when no
   platform variant is declared. The example build file says nothing about selecting a build type.
3. **Hot reload over `adb push` + broadcast.** The example describes an HTTPS `serverHost` with a
   certificate; the implemented transport is Android-only `adb`.
4. **Unreferenced code**: `PythonMultiplatformPlugin.kt` (a second `Plugin` that is not registered
   in `gradlePlugin { }`), `dependency/reslover.kt`, `dependency/lang/kotlin/decompileKotlinMeta.kt`
   (a `main()` generating Python from Kotlin metadata, related to `buildFeatures.metaclass` but not
   wired), `bundle/PythonLocalLoader.kt`, `dependency/DependencyType.kt`, and
   `BinariesExtension` / `FrozenPackConfig` / `BuildTypeEnum` in `dsl/DSLPlatforms.kt`. Nothing calls
   any of them.
5. **`pyproject.toml`** is toolchain-lite's PyPI build, §2.3.
