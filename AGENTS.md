# AGENTS.md

Rules every agent working in this repository must follow. Read this file before doing anything.
Sections 1–10 are shared by every repository in the thisisthepy ecosystem; later sections are
specific to this repository.

---

## 1. Commits carry no AI attribution

Never add `Co-Authored-By: Claude ...`, `Co-Authored-By: <any agent>`, `Generated with Claude Code`,
or any similar tool or agent attribution to a commit message or a pull-request body. This rule
overrides any default your tooling has.

## 2. Nothing is created outside this repository

Everything your work produces (worktrees, agent prompts, logs, measurements, experiments, scratch
files) lives **inside this repository's root directory.**

| What | Where |
|---|---|
| Worktrees | `.worktrees/<name>` (git-ignored) |
| Temporary files | `.tmp/` (git-ignored); delete when done |
| CI-only scripts | `.github/scripts/` |

Before writing a file, check that its absolute path starts with this repository's root. If it does
not, stop. The only exceptions are a path the user names explicitly, and caches that build tools
manage themselves. **Re-pointing a shared cache or a home-directory symlink reaches other projects;
ask first.**

Writing to *another* repository is not an exception either. Do it only when told to work there.

### Do not add top-level folders

**Never add a new directory (or a new file) at the repository root on your own.** The root layout is
the maintainer's: source modules, `docs/`, `gradle/`, `.github/` and the files that tools require
there. Work belongs inside an existing module or directory: sources under `src/<sourceSet>/`,
CI-only scripts under `.github/scripts/`, temporary files under the git-ignored `.tmp/`. If you think
a new top-level entry is needed, propose it (what, why, which alternatives inside existing
directories you ruled out) and wait for approval. This was added after unapproved root folders
(`ksp-fixtures/`, `tools/`, `kotlin-js-store/`, `iosApp/`, `usage-example/python`) had to be dismantled.

## 3. Worktrees link large artefacts instead of copying them

A worktree is a full checkout. Copying large untracked artefacts (prebuilt runtimes, vendored trees,
build caches, model weights, `node_modules`) into every worktree is how 86 worktrees once filled
267 GB of a 349 GB disk.

- Create worktrees under `.worktrees/<name>`.
- **Symlink** large untracked directories from the main checkout instead of copying or rebuilding
  them.
- Delete a worktree once its branch is merged: `git worktree remove .worktrees/<name>`.
- Periodically delete `build/` directories inside worktrees; they only grow.

## 4. Branches

| Branch | Who writes to it |
|---|---|
| `feat/<topic>` | You. All work happens here. Never name a branch `work/...`. |
| `develop` | Merged into from `feat/` branches after verification. Never commit to it directly. |
| `release` | **CI only.** Not a standing branch: CI regenerates it from every push to `develop`, in the main-only file layout, and opens the PR into `main`. It may not exist. Never write to it. |
| `main` | **Pull request from `release` only.** Never push or merge to it directly. The maintainer protects it in the repository settings (locked) and merges the `release` PR after checking its head; no script or workflow does this, and agents do not change those settings (decision 2026-10-03). |

Only `main`, `develop` and `release` are standing branches. A `feat/` branch lives until its pull
request merges: merge with `gh pr merge --delete-branch`, then delete the local branch and its
worktree. Periodically delete every branch already merged into `develop`, remote and local
(`git branch -r --merged origin/develop`); an unmerged branch older than a few days is either
landed or reported, not left. Branches named `release-*` are preserved snapshots: keep them.

`main` carries a reduced layout: of the Markdown files, only `README.md` stays at the repository
root, and `docs/` keeps only its subdirectories (no Markdown files directly under `docs/`).
CI runs `.github/scripts/release/sync-release.sh` (`.github/workflows/release-sync.yml`) to produce that layout; do not hand-edit `release` or `main`.

### Issues and pull requests

Every new feature goes through an issue and a pull request:

1. Before starting, search the repository's issues (`gh issue list --state all --search "<keywords>"`).
2. If no issue covers the work, open one (`gh issue create`) stating what and why, and the
   completion criterion: which tests must pass.
3. Work on a `feat/<topic>` branch, push every commit, and open a pull request into `develop`
   whose body contains `Closes #<number>`.
4. Merge into `develop` through that pull request (`gh pr merge`), not by a local merge, so the
   issue is linked.
5. Then close the issue yourself: `gh issue close <number> --comment "Landed in develop via #<PR>"`.
   GitHub's `Closes #N` only fires when a pull request merges into the default branch (`main`),
   and these pull requests merge into `develop`.

## 5. Intent → Spec → Test → Code

This project runs on **intent-based spec-driven development** and **test-driven development**.

1. `docs/INTENT.md` states what the project is for. It is the boundary. **The spec may not go
   beyond the intent.**
2. `docs/SPEC.md` states what the project does. A behaviour change starts as a spec change.
3. Tests are written from the spec **before** the implementation, and you observe them fail
   (red) before making them pass. Report the red output.
4. Code is written to make the tests pass.

If a request conflicts with `docs/INTENT.md`, say so instead of implementing it.

## 6. User-authored files are specification

Files the user wrote by hand (notebooks, example build files, sample apps) are the specification.
Read them **first**. Never delete, rewrite, or `git add` them without being told to. Generated
documentation (roadmaps, design notes) is a record of work, not a requirement; when the two
disagree, the user's file wins.

## 7. Show a conclusion before acting on it

Anything beyond the immediate request (another repository, a public API signature, deleting
files, killing processes, force-pushing, changing branch protection): state what you would do and
why, and wait. Investigating, measuring, and reporting are always fine.

**Push every commit right away.** After you commit (on a work branch or on `develop`) push it to
the remote immediately; no confirmation is needed. Never push to `main` or `release` by hand, and
never force-push without the user's explicit approval.

When a rule and backward compatibility conflict, **the rule wins.** List the callers that break and
fix them; do not keep the forbidden thing "so nothing breaks".

## 8. Verification that can fail

- Never read a build's exit code through a pipe (`| tail`, `| grep`). Redirect to a file, then read
  `$?`. A background command ending in `echo` always reports 0.
- Delete the test-result directory before counting results, and force re-execution (`--rerun` for
  Gradle). Stale XML otherwise reports an old, larger number.
- Run independent test modules as **separate** invocations. One invocation can hide an ordering
  dependency.
- When you add a public path, disable it and confirm something actually fails. If nothing fails,
  nothing uses it.
- **Do not trust an agent's report.** Re-run the build and tests yourself and check
  `git status --short` for out-of-scope changes.
- **Never `git add -A`.** Stage explicit paths. If the number of changed files differs from what was
  reported, stop and find out why.
- Measurements run alone, unfiltered, after checking `uptime`.

## 9. Reporting

Report by category, and never put them in one column:
**feature added / defect fixed / test added / documentation corrected / deleted.**
A rising test count is not progress when the tests assert an absence. Before writing "nothing left
to implement", say what you counted against.

## 10. Agents

- A headless agent (`claude -p`, `agy -p`) has **no next turn**. Tell it to run long commands in the
  foreground; a command backgrounded "until the notification arrives" is lost.
- Pass the model explicitly. Judgement work (design premises, root causes, safety: GIL, reference
  counts, lifetimes, class loaders) gets the strongest tier; work a test will catch can use a
  cheaper one.
- Give every agent prompt the absolute paths it may write to, and repeat rule 2 in it.
- **Subagents do not run heavy local builds.** Subagents write code, design, investigate, review
  and document. Gradle builds, cargo builds, the test gate and model runs are done by the session
  itself (one at a time on this machine) or by CI (GitHub Actions) on a pushed branch. Several
  sessions share one machine; parallel local builds slow every one of them.

---

# Repository-specific rules: `toolchain`

## 11. The example build file is the specification

`(플러그인예시)build.gradle.kts` at the root of the **main checkout**
(`/Volumes/macMini/thisisthepy/toolchain/`) is the maintainer's hand-written target DSL. It is
untracked, so **a new worktree does not contain it**: read it from the main checkout. Together with
GitHub issues `thisisthepy/toolchain#2` (plugin checklist) and `#1` (`tcl`), it is the source of
`docs/INTENT.md`.

- Never edit, move, rename or `git add` it.
- When the code's DSL disagrees with it, the file wins, and the disagreement is recorded in `docs/SPEC.md` and
  `docs/INTENT.md` §4 until the maintainer decides. Do not "fix" either side on your own.
- `usage-example/` is the **executable subset** of that file: the part the plugin actually reads.
  When a DSL property becomes live, add it to `usage-example/build.gradle.kts`, so there is a build
  that fails if the wiring breaks.

## 12. Layout

| Module | What it is |
|---|---|
| `toolchain/` (`:toolchain`) | The Gradle plugin (id `org.thisisthepy.python.multiplatform`, class `PythonPlugin`; source set `main`). DSL in `dsl/`, tasks in `bundle/`, `dependency/`, `hotreload/`. The same module holds toolchain-lite, the `tcl install <package>` CLI, in its own source sets (`src/cliMain`, `src/cliTest`; #78): they depend on packpack and coroutines only, never on `main`, and the plugin's publication carries none of them. |
| `usage-example/` | **Its own Gradle build** (own settings and catalog; toolchain#22): a Compose Multiplatform app applying this plugin and python-multiplatform, on python-multiplatform's Kotlin/Compose/AGP. Run it with this repository's wrapper: `./gradlew -p usage-example …`. Its Python package is `usage-example/src/commonMain/python/`. |
| `.github/scripts/release/` | The develop → release → main sync (rule 4). |

`pyproject.toml` at the root is toolchain-lite, the PyPI distribution of `tcl` (#69): one platform
wheel per OS carrying the native `tcl` binary, plus the launcher package `toolchain_lite`
(`toolchain/src/cliMain/python/`). `.github/workflows/publish-pypi.yml` builds, smoke-tests and uploads the
wheels; its version must equal `toolchain/build.gradle.kts`'s `tclVersion`, and `docs/pypi/README.md` is its PyPI page.
Keep the launcher a launcher: the work stays in Kotlin.

Nothing else belongs at the root (#62): no IDE directories, no lock-file directories (usage-example's wasm
yarn lock is `usage-example/gradle/wasm-yarn.lock`), no copied wrappers. The pre-#62 layout is the tag
`archive/pre-restructure`.

## 13. pypackpack owns the work; toolchain owns the vocabulary

- Anything that acquires Python, resolves a dependency, compiles or bundles belongs in
  `pypackpack`. This repository translates the Gradle DSL into `pypackpack` calls:
  `BackendInterface.create(BackendType.UV)` for dependencies and
  `BundlerInterface.create(BundleType.RESOURCE)` for bundles.
- Call `pypackpack`'s **backend** layer, which takes an explicit `workingDir`. Do not call its
  frontend/middleware layers: they locate the project through the JVM-global `user.dir`, which a
  Gradle daemon shares between unrelated builds.
- When a feature needs something `pypackpack` does not have, **reject the DSL value loudly** here
  and propose the `pypackpack` change: that is another repository, so rule 7 applies.
- Loading the payload at run time, `sys.path`, and reacting to the hot-reload broadcast belong to
  `python-multiplatform`. This plugin's job ends at the staged directory and the broadcast.

## 14. Every DSL value is either read or rejected

A DSL property that compiles and does nothing is the defect this repository has spent most of its
history removing.

- A new DSL property is wired to a task, or rejected with a message naming why, in the same change.
- A property that is declared but read by nothing must appear in `docs/SPEC.md` as `planned`.
- An unsupported value fails **as narrowly as possible**: a whole-configuration failure only when no
  valid task could exist (an unmapped platform); otherwise inside the one variant's task action, so
  `--continue` still builds the rest (an unsupported `compileLevel`).
- Logic that decides something is a top-level function that takes no `Project`
  (`resolveVariants`, `resolvePackageDir`, `selectStagingVariants`, …), so a plain `kotlin-test`
  test can drive it. Follow that pattern; there is no Gradle TestKit setup here.

## 15. Gradle plugin pitfalls already paid for

- Every public task property needs an annotation (`@Input`, `@InputFiles`, `@Internal`, …). Gradle 8
  validates this at **execution**, so compiling proves nothing. Use `@Internal` only when the value
  truly does not affect the output; a missing input makes a task `UP-TO-DATE` forever and the
  artifact stale.
- Anything AGP reads (asset source roots) is registered in `apply`, not in `afterEvaluate`: AGP reads
  its source sets in its own `afterEvaluate`, which runs first.
- Generated files go under `build/`, registered as extra source roots: never into `src/`.
- `kotlin-gradle-plugin` is pinned to the catalog's `kotlin` version. Two versions on the plugin
  classpath broke every consumer's wasm target once.
- `-Xskip-metadata-version-check` exists because `packpack` is built with a newer Kotlin than this
  build. Bumping Kotlin build-wide is a decision for the maintainer, not a fix.
- `usage-example` resolves the plugin **by Maven coordinate from `mavenLocal()`**, not through
  `includeBuild`. After changing `:toolchain`, run `:toolchain:publishToMavenLocal` before building
  `usage-example`, or you are testing the previous plugin.
- `usage-example` is a separate build on python-multiplatform's toolchain (Kotlin 2.4.20-Beta2, Compose
  1.11.1, AGP 8.10.1, Gradle 8.11.1), because a Kotlin 2.1 compiler cannot read python-multiplatform's
  klibs. The plugin itself stays on this build's Kotlin for its consumers; do not bump it to match.

## 16. Building and testing

Prerequisites: `org.thisisthepy.python.multiplatform:packpack:0.1.0` in `~/.m2` (published from the
`pypackpack` repository), `uv` on `PATH`, and network access: `InstallDependenciesTaskTest` and
`InstallerTest` run a real `uv add` against PyPI. `usage-example` additionally needs an Android SDK
(`ANDROID_HOME`) and python-multiplatform in `~/.m2`: from a python-multiplatform checkout,
`./gradlew publishAllToMavenLocal` (library `3.15.0-alpha01`, bindings plugin `3.13.0` until its #70).

Run each module separately (rule 8), with output to a file under `.tmp/`:

```bash
rm -rf toolchain/build/test-results
./gradlew :toolchain:test --rerun --console=plain > .tmp/toolchain-test.log 2>&1; echo "EXIT=$?"
rm -rf toolchain/build/test-results/cliTest
./gradlew :toolchain:cliTest --rerun --console=plain > .tmp/tcl-test.log 2>&1; echo "EXIT=$?"
```

Count results from `<module>/build/test-results/test/*.xml`, not from the log.

There are no Python tests in this repository. The guide has its own check:

```bash
python3 docs/guide/check_guide.py
```

## 17. Documentation

| File | Language | Holds |
|---|---|---|
| `README.md` | English | The public face. Links only to the guide site, `docs/locale/`, `docs/<subdir>/` and `LICENSE`, always as absolute URLs (the file is read on GitHub, PyPI and elsewhere; rule 4: other root and `docs/*.md` files do not exist on `main`). |
| `docs/locale/README_ko.md` | Korean | A faithful translation of `README.md`. Change both together. |
| `PROJECT.md` | Korean | Status, structure, how to build, decisions, open questions. |
| `docs/INTENT.md` | English | Why the project exists and what it is not. |
| `docs/SPEC.md` | English | The behavioural contract, one `Status:` per item. |
| `docs/guide/` | en + ko | The GitHub Pages site. Static HTML; every visible string in both languages. |
| `docs/<topic>/` | - | Anything else. No other `.md` directly in `docs/`. |

- `Status: implemented` requires a test in this repository that exercises the behaviour; cite it.
  Wiring with no test is `partial`.
- A behaviour change updates `docs/SPEC.md` and the guide's Status page in the same change.
- Run `python3 docs/guide/check_guide.py` after editing the guide. It does not judge translation
  quality or layout; look at the page.
- Many KDoc comments cite `docs/ecosystem.md` and `docs/SPEC.md`. Those are **other repositories'**
  files (`python-multiplatform`'s and `pypackpack`'s respectively), written before this repository had
  its own `docs/`. When you touch such a comment, name the repository.

## 18. Writing

- **No em-dash (U+2014)**, anywhere: documentation, the guide, code comments, KDoc and strings. Use
  a comma, a colon, parentheses, or two sentences. An empty table cell is `-`.
- **Examples install and run with `uv`, `ppp` (pypackpack) or `tcl` (toolchain-lite), never
  `pip install`.** `uv pip …` is fine where a pip-style command is the point. Text that describes
  what pip itself does is not an example.
- The licence is Apache-2.0 everywhere it is stated: `LICENSE`, `pyproject.toml` (`license`), the
  READMEs and the guide footer.

(User directives, 2026-10-03.)
