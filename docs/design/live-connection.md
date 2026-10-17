# Live connection: a notebook attached to the running app, and hot reload on every platform

Design investigation, 2026-10-17 (the lead's instruction; the ecosystem survey's third gap). No code
changes. Everything below "Facts" was checked in the repositories named; the rest is a proposal.

## 1. What the user's specification expects

The specification is `pythonx-compose/UI.ipynb` (the maintainer's 2024 lecture notebook) and
`python-multiplatform/docs/design/ecosystem.md` §3.

**The notebook's kernel is the running app's own interpreter.** Its cells do not describe a UI that is
later built; they act on the app that is on screen:

| Cell | What it does to the running app |
|---|---|
| `import main` then `main.App` | reaches the live root object of the app's UI |
| `main.App.messages.getValue()` / `.setValue("...")` | reads and writes UI state; the screen follows |
| `@Composable def NewUI(): ...` then `main.App.update(NewUI)` | replaces what the app draws, without a restart |
| `TextField(text_state=main.App.messages)`, then `main.App.messages.getValue()` | text typed on the device is read back in the notebook |
| `for chunk in token_streamer(...): print_messages(chunk)` | the app's on-device model streams tokens into the UI, driven from a cell |

So the experience is **cell runs, the running app changes, at once, both ways**: the notebook sees state
the user changed on the device, and the device shows what the cell set. It is not "edit a file, rebuild,
redeploy".

**How 2024 did it** (PyREPL, `app/src/androidMain/python/main.py` and `repl/kernel/kernel_android.py`,
Chaquopy): the Android app bundled `jupyterlab`, `jupyter`, `pyzmq` and `ipykernel`; a button started a
JupyterLab server inside the app and opened the device browser on it (IP, port and password shown on the
app's screen); `UIThreadKernelManager` launched `IPKernelApp` inside the app's own process ("UI thread
sync mode", one kernel at a time). Cells therefore executed in the app's interpreter, which is why
`import main` reached the live `App`.

**What exists now**:

- pythonx-compose has the UI half, rebuilt on python-multiplatform: `pythonx.compose.runtime.app(fn)`
  writes the root into a Compose `MutableState` (`app_root`) that the host draws, so redeclaring the root
  in a cell replaces the screen with no `update` call; `remember_saveable` returns state with
  `getValue`/`setValue`. python-multiplatform's notebook E2E (#26, CI job `notebook-e2e`) runs the
  notebook's scenarios **in-process in a desktop test host**: it proves the UI semantics, not a live
  connection from a notebook to a separately running app.
- toolchain's hot reload (SPEC §1.14) is Android only: `adb push` of every `.py` to `/data/local/tmp/...`
  and an `org.thisisthepy.python.RELOAD_PYTHON` broadcast. Its `serverHost` and `cert` settings are
  validated but carry nothing. python-multiplatform has no handler for that broadcast (no `RELOAD_PYTHON` in its runtime, plugin or compose sources), so today's hot reload pushes files nothing reads.
- toolchain's code push (SPEC §1.15) validates its DSL and uploads nothing.
- python-multiplatform's development-mode import gate (SPEC N-7b, #183) already defines what happens when a
  notebook cell or a hot reload imports a project module: it is type-checked first.

So the gap is exactly the **channel**: nothing connects a notebook (or a file save) on the laptop to the
interpreter inside a running desktop, Android or iOS app.

## 2. Getting changed Python into the running app, per platform

Two things travel over the same channel, so it is worth having one:

1. **Code to execute** (a notebook cell): run in the app's interpreter, in a namespace that persists across
   cells, with stdout, stderr, results and errors sent back.
2. **Files that changed** (hot reload on save): written into the app's payload directory, then the
   affected modules re-imported.

### Transport

| Platform | How the laptop reaches the app | Notes |
|---|---|---|
| Desktop | `127.0.0.1:<port>` | same machine |
| Android emulator / device | the app listens on its loopback; `adb forward tcp:<port> tcp:<port>` | needs `INTERNET` permission, in the debug manifest only; no network exposure |
| iOS simulator | `127.0.0.1:<port>` | the simulator shares the Mac's network stack |
| iOS device | `iproxy` / usbmuxd port forwarding over USB, or the local network with Bonjour discovery | the only case without a loopback path; local network needs `NSLocalNetworkUsageDescription` |

A socket for both, not file synchronisation: file sync (today's `adb push`) cannot run code, has no
equivalent on iOS, and cannot carry results back. Files go over the socket as content and are written by
the app itself into its payload directory, which is where python-multiplatform already loads from.

### Re-import

- A cell's code is `exec`'d into one persistent namespace (`__main__`, so `import main` and module state
  behave as in 2024).
- A changed file replaces its payload copy, then `importlib.reload` runs on the modules whose files changed,
  children first. Functions and objects already held by the UI keep their old code (plain CPython
  semantics), so the UI is refreshed by re-declaring the root: after a reload, the runtime calls the root
  module's root function again through pythonx-compose's `app(...)` if the root's module was among them.
- The N-7b import gate runs on every re-import (it is already defined for this case): a type error stops
  the reload and is sent back to the notebook or the build output, and the app keeps running the old code.
- Execution thread: by default on the channel's own thread (Compose snapshot state may be written from any
  thread; the interpreter is free-threaded 3.15t). A cell that must touch main-thread-only Kotlin API can ask
  for the main thread (PyREPL's "UI thread sync mode"); python-multiplatform dispatches it to the platform's
  main dispatcher.

## 3. Attaching a Jupyter kernel to the app's interpreter

| | A. `ipykernel` inside the app (2024's approach) | B. Proxy kernel on the laptop + a small agent in the app (proposed) |
|---|---|---|
| In the app | ipykernel, jupyter_client, pyzmq + libzmq, tornado, traitlets... | a stdlib-only agent: one TCP connection, JSON messages |
| Native dependencies in the app | pyzmq/libzmq. **Checked 2026-10-17: pyzmq 27.2.0 has `cp315t` wheels for desktop only; Android has `cp315` (GIL) only, iOS and WASM none.** pypackpack would have to build pyzmq and libzmq for every mobile target | none |
| Ports to forward | five ZMQ sockets (shell, iopub, stdin, control, heartbeat) per app | one |
| Jupyter fidelity | complete: completion, inspection, comms, ipywidgets, interrupts as far as ipykernel manages them in a non-main thread | what the proxy implements: execute, stdout/stderr, results and errors, completion and inspection forwarded; comms and widgets later or never |
| Free-threaded 3.15t, in-process | ipykernel's threading and signal assumptions on a non-main thread, untested on 3.15t | the agent owns its threading |
| App size and attack surface | large, debug builds only | small, debug builds only |
| Works for hot reload too | no (a kernel, not a file channel) | yes, same connection |

**Proposed: B.** The laptop runs an ordinary Jupyter kernel (a subclass of `ipykernel.kernelbase.Kernel`,
installed as a kernelspec such as "usage-example (desktop debug)") that forwards `execute_request`,
`complete_request` and `inspect_request` over one socket to an agent inside the app, and relays the agent's
streams, results and errors back as Jupyter messages. The agent speaks a small subset of Jupyter's own
message shapes (`execute_request` / `stream` / `execute_result` / `error` / `complete_reply`), so moving to A
later stays possible. Jupyter itself (JupyterLab, VS Code, PyCharm) needs nothing special: it talks to a
normal kernel on the laptop.

Also considered: **JupyterLab served from inside the app** (the 2024 setup in full): heavier still, and
the notebook ends up in the device's browser. **debugpy**: a debugger, not a REPL; complementary
(breakpoints), not a replacement, and also a C extension.

Security, for any option: the agent exists in debug builds only (toolchain already gates hot reload on
`enableHotReload` per build type); it listens on loopback only (iOS device: USB or explicit opt-in to the
local network); every connection presents a token generated at build or launch and given to the laptop
side (adb, the simulator's container, or the desktop launcher), never typed by hand. The `serverHost` and
`cert` settings toolchain validates today map onto the iOS-device and remote cases.

## 4. Which repository owns what

| Repository | Owns |
|---|---|
| **python-multiplatform** (runtime) | The in-app agent: started in debug builds, its socket, the token, `exec` into a persistent `__main__`, stream capture, main-thread dispatch, writing pushed files into the payload directory and re-importing them (with N-7b's gate), and the platform pieces (Android debug-manifest `INTERNET`, iOS local-network string for the device case). toolchain's `AGENTS.md` §13 already says reacting to the hot-reload broadcast belongs here. |
| **toolchain** (developer side) | Finding and reaching the running app per platform (`adb forward`, the simulator's loopback, the desktop launcher's port, `iproxy` for a device); the `hotReloadPython` task rebuilt on the channel (replacing `adb push` and the broadcast), with a watch mode; installing the kernelspec for a variant; the proxy kernel itself, shipped in `toolchain_lite` (the PyPI package `tcl` already lives in) as `tcl kernel install` / `tcl connect`, so a notebook user needs only `uv tool install toolchain-lite`. |
| **pythonx-compose** (UI semantics) | What "replace the screen" and "UI state" mean: `app(...)`, `remember_saveable`, and re-declaring the root after a module reload. Already implemented for the notebook's semantics; the reload hook is the only addition. |
| **pypackpack** | Nothing on B's critical path. Under A it would build pyzmq and libzmq for Android and iOS 3.15t. |

## 5. First milestone

**M1, desktop, end to end**: `usage-example` (desktop, debug) runs; its agent listens on `127.0.0.1` with a
token; `tcl kernel install` registers the "usage-example (desktop debug)" kernelspec; a notebook running the
`UI.ipynb` cells (`import main`, state `getValue`/`setValue`, re-declaring the root) changes the open window
and reads back what was typed in it. Tests: the agent's protocol (execute, streams, errors, persistent
namespace, a reload under the import gate) without a UI; and a CI job that starts the packaged desktop app
and keeps it running (under Xvfb: today's `desktop-e2e` runs the app headless only for a `--python-smoke`
check that exits before any UI), connects the proxy kernel, runs the scenarios through Jupyter's client API
and fails if any regresses. Disabling the agent must make it fail.

Then:

- **M2, Android**: `adb forward`; `hotReloadPython` moves onto the channel (the `adb push` + broadcast path is
  removed, as it has no receiver); the `android-e2e` job runs the same scenarios on the emulator.
- **M3, iOS simulator**: loopback; `ios-e2e` runs them.
- **M4, iOS device**: `iproxy` or Bonjour; manual check only (no device in CI).
- **M5, code push** (SPEC §1.15) reuses the file half of the channel against a server instead of a laptop;
  separate from FastTrack's deploy (pypackpack), which waits for the server-ownership decision.

Open for the maintainer: whether the 2024 "JupyterLab on the device" mode (a notebook with no laptop) is a
goal at all; if it is, A (or an in-app JupyterLab) comes back for that mode only.
