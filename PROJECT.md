# PROJECT: toolchain

Kotlin Multiplatform 앱의 Python 부분을 Gradle `python { }` 블록으로 선언하게 해 주는 Gradle 플러그인,
그리고 Gradle 없이 쓰는 CLI `tcl`. 실제 작업(의존성 해석, 번들링)은 `pypackpack` 에 위임한다.

## 사양의 출처

| 무엇 | 어디 |
|---|---|
| 목표 DSL (사용자 작성) | 메인 체크아웃 루트의 `(플러그인예시)build.gradle.kts`: 추적되지 않는 파일, worktree 에는 없다 |
| 플러그인 체크리스트 | GitHub 이슈 `thisisthepy/toolchain#2` |
| toolchain-lite | GitHub 이슈 `thisisthepy/toolchain#1` (`tcl install pythonx-compose`) |
| 실행 가능한 부분집합 | `usage-example/build.gradle.kts` |
| 의도 / 계약 | `docs/INTENT.md` / `docs/SPEC.md` |

## 현재 상태 (2026-10-03, 코드와 테스트를 읽고 판정)

**구현 (테스트 있음)**
- `compileSdk` 버전 문자열 파싱 (alpha / rc / normal 채널), 그리고 이름 상수 `PY3_15_0`·`PY3_14_7`·`PY3_13_0`
  (python-multiplatform 이 런타임을 제공하는 버전만). 제공되지 않는 버전 문자열은 `buildPython…` 에서 이유와 함께 실패
- `python { }` 최상위의 `android("android") { }` / `ios { }` / `desktop()` 과 변형 호출
  (`androidArm64()` 등) → `pypackpack` 타깃 트리플 매핑, Kotlin 타깃 대조 경고, 최소 SDK 전달.
  안드로이드는 CPython 공식 지원(PEP 738)대로 arm64·x86_64 만: `androidArm32()`/`androidX86()` 은
  이유를 밝히는 컴파일 오류
- `debug` / `release` 빌드 타입, `-Ppython.buildType`, 변형 × 빌드 타입 태스크 그래프
- `projectFlavors { create("free") }` → 변형 × 플레이버 × 빌드 타입(`buildPythonAndroidArm64FreeDebug`),
  스테이징은 `-Ppython.flavor`(기본: 첫 플레이버). 플랫폼 변형 없이 선언하면 `buildPython` 이 이유와 함께 실패
- `compileLevel`: `instant`, `bytecode`(debug 는 `.py`+`.pyc`, release 는 `.pyc` 만) 지원. `bytecode` 는
  패키지 위쪽의 `.venv` 가 없거나 그 마이너 버전이 `compileSdk` 와 다르면 해당 변형에서 실패.
  `native`/`mixed` 는 해당 변형의 태스크에서만 실패 (pypackpack#19)
- `commonMain` 의 `srcDirs` / `metaDirs` / `libDirs` 전달
- `implementation` / `integration` 의존성 → `uv add` (`pypackpack` 백엔드, 패키지의 `.venv` 와 `pyproject.toml`)
- 타깃별 의존성 설치 (#16): 의존성 집합(플랫폼 변형 × 플레이버)마다 `installPythonDependencies<Set>` 이
  `commonMain` + `<계열>Main`(`androidMain`/`iosMain`/`desktopMain`) + `<flavor>Main` 의 의존성을 그 트리플용으로
  `build/pythonDeps/<set>/` 에 설치(`uv pip install --target --python-platform`, `compileSdk` 의 X.Y,
  `only-binary`)하고, 그 집합의 번들 태스크가 이 디렉터리를 `libDirs` 맨 앞에 받는다. 변형이 없으면
  `installPythonDependenciesHost`(호스트 트리플, `build/pythonDeps/host/`). 어떤 변형도 읽지 않는 소스셋의
  의존성은 설치 태스크에서 거부 (`TargetDependenciesTest`, `PythonPluginTargetDependenciesTest`,
  `InstallTargetDependenciesTaskTest`)
- `integration` 패키지는 설치 후 `.venv` site-packages 의 `*.dist-info/KLIBDEPENS` 를 확인하고 없으면 `implementation` 을 쓰라고 경고 (실패 아님; KLIBDEPENS 위치는 가정: 어디에도 정의 없음)
- `defaultConfig { pip { autoUpdate; repositories { central / local } } }` → `uv add` 옵션
  (`--default-index`·`--index` / `--find-links` / `--upgrade`). `jit` 는 설치 태스크에서만 이유와 함께 거부
- `defaultConfig { versionCode, versionName }` → 페이로드 버전으로 번들 manifest 에 기록(`"versionName"`/`"versionCode"`)
- `buildFeatures { metaclass }`(기본 `true`) 와 `release` 의 `excludeMetaclass`: `false`/`true` 이면 해당
  번들 태스크에서 `metaDirs` 를 전달하지 않음 (`BuildFeaturesTest`, `PythonPluginBuildFeaturesTest`)
- `buildFeatures { compose }`: `pythonx-compose` 를 설치 목록에, `python-multiplatform-compose` 를 Kotlin
  `commonMain` 의 `implementation` 에 추가. 위치는 Gradle 속성 `python.compose.pythonxCompose`(pip 요구사항
  또는 wheel 디렉터리 → `--find-links` 에 쉼표로 추가)와 `python.compose.kotlinModule`(`group:artifact:version`).
  앞의 것이 없으면 설치 태스크에서만, 뒤의 것이 없으면 구성 단계에서 실패. KMP 가 없으면 Kotlin 쪽은 경고 후 생략
- `pypackpack` `ResourceBundler` 로 번들링
- 플러그인 적용: `python` 확장과 모든 태스크 등록 (`PythonPluginApplyTest`)
- `packagePython` zip: 파일명·위치·항목, 번들 디렉터리가 없으면 실패 (`AssemblePythonPackageTaskTest`)
- 스테이징 복사 규칙과 플랫폼별 변형 선택
- 데스크톱 jar 로의 스테이징 연결: `desktopProcessResources` (`PythonPluginAttachmentTest`)
- `tcl install <package>`
- `embedLevel`: 플랫폼별 0/1/2 결정(Android·iOS 는 경고와 함께 2 로 상향), `python.embedLevel` 속성 재정의,
  `<zip>.embed.json` 기록(`embedLevel`, `platformFamily`, `warning`, `interpreterVersion`) (`EmbedLevelTest`,
  `PythonPluginEmbedLevelTest`). 레벨 2 = python-multiplatform 이 인터프리터를 내장, 1 = 외부, 0 = 없음.
  toolchain 은 어느 레벨에서도 인터프리터를 싣지 않고 번들은 `python/` 만 담음 (`PythonPluginPythonOnlyTest`)

**부분**
- APK 로의 스테이징 연결: `android.sourceSets.main.assets` 등록은 실제 AGP 로 테스트됨
  (`PythonPluginAttachmentTest`), `preBuild`/`merge*Assets` 의존성은 Android SDK 가 있어야 생기므로 테스트 없음
- iOS: `stagePythonBundleIosForXcode` 가 스테이징 후 `PYTHON_PAYLOAD_DIR=<경로>` 한 줄을 출력(python-multiplatform 의 `install-python.sh` 가 `PYTHON_PAYLOAD_DIR` 로 받아 복사하며, `python/` 을 복사하는 Xcode 단계는 하나뿐이어야 함. 손으로 배선할 때의 안전한 스니펫은 가이드에 있음; 스크립트 파일은 #62 에서 삭제). `.app` 으로 복사하는 Xcode 단계는 python-multiplatform(#59) 소유
- 핫 리로드: Android 전용 `adb push` + 브로드캐스트. `serverHost`, `cert` 는 검증만
- 코드 푸시: 검증과 안내 태스크만, 업로드 없음
- `installPythonDependencies`(`uv add`)는 여전히 모든 소스셋을 호스트용으로 설치하므로, 호스트 wheel 이 없는
  `androidMain` 전용 패키지는 이 태스크에서 실패할 수 있음. `ResourceBundler` 가 `.pyd` 를 빼므로 Windows
  확장 모듈은 `mingwX64` 번들에 들어가지 않음
- `compileSdk` 와 python-multiplatform `pythonVersion` 대조(#42): 레벨 2 변형에서 둘의 `X.Y.Z` 가 다르면 그 변형의
  `buildPython…` 만 두 버전을 밝히며 실패 (`PythonVersionAgreementTest`, `PythonPluginPythonOnlyTest`). 버전은
  `python.multiplatform.pythonVersion` 속성, 없으면 같은 빌드의 `:python-multiplatform` 프로젝트의
  `pythonMultiplatform` 확장(python-multiplatform#61), 그것도 없으면 배포된 의존의 모듈 메타데이터
  `org.thisisthepy.python.version` 이나 jar 리소스 `META-INF/python-multiplatform/python.properties` 에서 읽음
  (#49, `PublishedPythonVersionTest`)

**계획 (선언만 있거나 없음)**
- `native` / `mixed` 컴파일 레벨 (pypackpack#19 선행 필요)
- `useCodeMinifier`,
  `pip { jit }`

## 마일스톤 (2026-10-03 확정, GitHub 마일스톤과 연결)

"실제로 쓸 수 있는 수준" = 예시 빌드 파일(32비트 안드로이드 제외)로 Android(arm64·x86_64)·iOS·데스크톱 앱에
Python 코드와 의존성이 실려 빌드·실행되는 상태. 기한을 맞추려고 품질 기준은 낮추지 않고 범위를 줄인다.

| 마일스톤 | 기한 | 범위 | 완료 기준 | 근거 |
|---|---|---|---|---|
| M1 DSL = 예시 파일 | 10-17 | 플랫폼(#7 완료), pip(#9), `compileSdk` 상수(#10), `defaultConfig` 버전(#11), `buildFeatures`(#12), `projectFlavors`(#13), 플러그인 적용·zip·jar/APK 테스트(#14) | 예시 파일의 모든 속성이 컴파일되고, 읽히거나 좁게 거부됨. `usage-example` 이 그 모양으로 빌드됨 | 이슈 7개, 외부 의존 없음 |
| M2 실제 페이로드 | 11-07 | `bytecode`(#15), 소스셋·타깃별 의존성(#16), `embedLevel`(#17), ~~`compileSdk` 로 인터프리터 선택(#18)~~ → python-multiplatform `pythonVersion` 대조(#42), `KLIBDEPENS`(#19) | 각 변형의 번들에 그 타깃의 wheel 이 들어가고, bytecode 변형은 `.pyc` 를 실음 | pypackpack M2(타깃별 실제 설치)에 의존 |
| M3 쓸 수 있는 수준 | 11-30 | iOS 페이로드 연결(#20), TypedPython 검사 태스크(#21), 세 플랫폼 실행(#22) | 예시 앱이 Android 에뮬레이터·iOS 시뮬레이터·데스크톱에서 Python 과 의존성을 실행 | python-multiplatform 런타임 로딩에 의존 |

11-30 이후로 넘김: `native`/`mixed`(pypackpack Cython 슬롯은 pypackpack M3, 나머지 컴파일러는 빈 껍데기),
`useCodeMinifier`(pypackpack 에 minifier 없음), 코드 푸시 업로드와 HTTPS 핫 리로드 서버
(지금은 `adb` 로 동작), 서버에 없는 CPython 자동 빌드(빌드 파이프라인 필요), `pip { jit }`(레시피 빌드 필요).

## 구조

```
toolchain/       Gradle 플러그인  (dsl/, bundle/, dependency/, hotreload/)
toolchain/src/cliMain, src/cliTest   toolchain-lite CLI `tcl` (`:toolchain` 의 별도 소스셋, #78)
usage-example/   별도 Gradle 빌드: 플러그인과 python-multiplatform 을 적용한 Compose Multiplatform 앱 + python/
                 (python-multiplatform 의 Kotlin 2.4.20-Beta2 / Compose 1.11.1 / AGP 8.10.1, 자체 wrapper)
docs/            INTENT.md, SPEC.md, locale/, guide/ (GitHub Pages)
```

## 빌드와 테스트

전제: `~/.m2` 에 `packpack:0.1.0` (pypackpack 에서 `publishToMavenLocal`), `PATH` 에 `uv`, 네트워크.

```bash
./gradlew :toolchain:test --rerun --console=plain > .tmp/toolchain-test.log 2>&1; echo "EXIT=$?"
./gradlew :toolchain:cliTest --rerun --console=plain > .tmp/tcl-test.log 2>&1; echo "EXIT=$?"
./gradlew :toolchain:publishToMavenLocal     # usage-example 빌드 전에 반드시
python3 docs/guide/check_guide.py            # 가이드 검사
# usage-example 은 별도 빌드: python-multiplatform 체크아웃에서 ./gradlew publishAllToMavenLocal 후
./gradlew -p usage-example packagePython
./gradlew -p usage-example createDistributable # 데스크톱 앱; 실행 시 --python-smoke 로 헤드리스 확인
```

Python 테스트는 없다. 루트 `pyproject.toml` 이 가리키는 Python 패키지도 존재하지 않는다.

## 결정 사항

- **usage-example 은 별도 빌드** (#22, 리드 결정 2026-10-03): python-multiplatform 은 Kotlin 2.4.20-Beta2 로 빌드되고 Kotlin 2.1
  컴파일러는 그 klib 을 읽지 못한다. 소비자인 usage-example 이 의존성의 툴체인에 맞추고, 플러그인 자체는 소비자를 위해
  지금의 Kotlin 에 둔다.

- **위임**: 의존성은 `BackendInterface.create(BackendType.UV)`, 번들은
  `BundlerInterface.create(BundleType.RESOURCE)`. 둘 다 `workingDir` 를 명시적으로 받는 백엔드 계층을
  호출한다 (`user.dir` 전역 상태 회피).
- **조용한 무시 금지**: 아무것도 읽지 않는 DSL 값은 거부하거나 SPEC 에 `planned` 로 남긴다.
- **변형별 실패**: 지원되지 않는 `compileLevel` 은 그 변형의 태스크만 실패시킨다.
- **스테이징은 `build/` 아래**: `src/` 에 생성물을 만들지 않는다.
- **`bytecode` 의 인터프리터는 toolchain 이 만들지 않는다** (#15): `pypackpack` `ResourceBundler` 는 패키지
  디렉터리에서 위로 올라가며 찾은 `.venv/bin/python3` 로 컴파일하고, 의존성을 선언하면
  `installPythonDependencies` 의 `uv add` 가 바로 그 `<package>/.venv` 를 만든다. 없으면 이유와 만드는 법을
  밝히고 실패한다. 인터프리터를 고르고 받아 오는 일은 `pypackpack` 몫(AGENTS.md §13)이고, `uv venv` 는
  `uv add` 가 만든 `.venv` 를 덮어쓴다. `.pyc` 매직 넘버 때문에 `pyvenv.cfg` 의 마이너 버전을 `compileSdk`
  와 비교해 다르면 거부한다(`compileSdk` 미선언이거나 `pyvenv.cfg` 가 없으면 비교하지 않음).
- **번들의 의존성은 타깃별 설치에서 온다** (#16): `uv add` 의 `.venv` 는 호스트용이라 번들에 쓰지 않는다.
  설치 단위는 변형이 아니라 의존성 집합(플랫폼 × 플레이버)이다. 빌드 타입은 요구사항 목록도 트리플도 바꾸지
  않으므로 debug/release 가 한 번 설치를 공유한다. 요구사항 목록은 `pypackpack` 의
  `installDependenciesToTarget(requirements = …)`(pypackpack#36)로 그대로 넘기며 파일을 쓰지 않는다(#45). 설치된 패키지를 `libDirs` 맨 앞에 두어 사용자가 선언한
  `libDirs(...)` 가 덮어쓸 수 있게 한다.
- **toolchain 은 `python/` 만 싣는다** (#42, 리드 결정 2026-10-03): 인터프리터와 stdlib 는 python-multiplatform
  몫이다. python-multiplatform 은 libpython 을 자기 바이너리(Android JNI, iOS 프레임워크, 데스크톱 FFM)에 링크하고
  맞는 stdlib 를 직접 싣는다(Android `assets/<abi>/lib/python3.14`, PYTHONHOME). toolchain 이 따로 받은
  인터프리터는 같은 버전이어도 링크된 것과 다를 수 있고(iOS 3.14.6 사례), APK 에 두 벌이 들어간다. 그래서 #40 의
  인터프리터 확보(`acquirePythonInterpreter…`, `build/pythonRuntime/`)와 `runtime/`·`runtime-manifest.json`
  번들링, 기록의 `interpreterBundled` 를 걷어냈다. `compileSdk` 는 wheel 의 `--python-version` 만 고르고,
  레벨 2 에서는 python-multiplatform 의 `pythonVersion` 과 대조한다. 의존성은 이미 `python/` 에 평평하게
  들어가므로(`ResourceBundler` 가 `libDirs` 를 합침) `site-packages` 하위 디렉터리 규칙은 필요 없다.

## 열린 질문

1. ~~서버에 없는 `compileSdk` 버전 자동 빌드~~: **결정(2026-10-03)**: 문자열로 주면 서버에 없을 때
   자동 빌드, `PY3_11_9_ALPHA` 같은 Enum 으로 주면 서버에 있는 버전만. (구현은 계획)
2. ~~플랫폼 DSL 모양~~: **결정(2026-10-03)**: 예시 파일 모양(최상위 `android("android") { }` +
   `listOf(...)`). 안드로이드 변형은 CPython 공식 지원(arm64·x86_64)만. #7
3. ~~`pip { repositories { central / local / jit } }` 이름~~: **결정(2026-10-03)**: 예시 파일 철자.
4. 예시 파일에 없는 `localLibraryPath`, `-Ppython.buildType`, `adb` 기반 핫 리로드를 유지할 것인가.
5. 참조되지 않는 코드(`PythonMultiplatformPlugin.kt`, `reslover.kt`, `decompileKotlinMeta.kt`,
   `PythonLocalLoader.kt`, `DependencyType.kt`, `FrozenPackConfig` 등)와 빈 `pyproject.toml` 을
   지울 것인가.
6. ~~레벨 2 인터프리터 확보에 필요한 pypackpack API~~: **해결 후 철회(2026-10-03)**: pypackpack#37 의
   `installPython(version, target, installDir)` 를 썼으나, #42 결정으로 toolchain 은 인터프리터를 받지 않는다.
7. python-multiplatform `pythonVersion` 을 어디서 읽을 것인가: 같은 빌드는 `:python-multiplatform` 의
   `pythonMultiplatform` 확장(구현), 배포된 의존은 모듈 메타데이터나 jar 리소스(미구현). 리드 결정 대기
   (python-multiplatform#61).
