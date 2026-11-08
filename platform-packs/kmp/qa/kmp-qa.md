# Kotlin Multiplatform QA

This pack declares two target kinds: `android` and `desktop`. Drive each app the acceptance criteria describe, check that behavior, and write one prose report. QA reports what it finds and repairs nothing.

## Choose targets

Map the acceptance criteria to the apps that show the behavior before building anything:

- `android`: behavior of an Android app, built from an Android application module. Follow [Android target](#android-target).
- `desktop`: behavior of a JVM desktop app, built from a module that applies `org.jetbrains.compose` with a `compose.desktop { application { ... } }` block. Follow [Desktop target](#desktop-target).
- Shared `commonMain` code alone does not put every dependent app in scope. Exercise another app when the criteria explicitly require its behavior or a cross-platform regression check. A desktop-only criterion stays desktop-only even when its implementation is shared. Record an out-of-scope kind as `skipped` with `reason: empty_scope` before probing devices or building it.

Desktop UI is user-facing UI. Criteria that describe only a desktop app still have flows to exercise, so they never settle `empty_scope`. Give each declared kind a `## Coverage` line. A kind no criterion reaches gets `skipped` with `reason: empty_scope`. Settle the whole report `skipped` with `Reason: empty_scope` only when no criterion describes behavior on any declared kind, for example a build-script or documentation-only change.

## Rules

- Write generated scripts, screenshots, UI dumps, and log excerpts only under the invocation directory the briefing names, outside the QA checkout. Nothing goes in the working tree.
- Build, install, and test from the QA checkout the briefing names. It is a temporary worktree of the tree under test, and the runtime deletes it when QA ends.
- When a criterion or changed behavior needs a state the environment does not produce on its own, force it. Edit any file in the QA checkout to inject a fault, force an error response, add a debug hook, or shorten a timeout, or change the environment, for example by cutting the network while a response streams. Rebuild after an edit, and list every such edit, with the flows it made possible, under `## Temporary edits` in the report.
- That the environment could not produce a state is never a reason to leave a flow unexercised. Report a flow as not exercised only when a forced attempt failed, and name what you tried and why it failed.
- Edit nothing outside the QA checkout and the invocation directory. Never commit, push, or open a pull request.
- Never paste log bodies, raw device serials, credentials, or tokens into the report. Evidence is a path under the invocation directory.
- Write the report to the exact `report.md` path the briefing names. An app launch alone is never a pass.

## Android target

The sections from here to [Desktop target](#desktop-target) apply to the `android` kind.

## Test account and environment

- Build, install, launch, and sign in as needed. An existing signed-in session may be used. Do not require proof that the account was created as synthetic QA data.
- Look for a staging environment switch in the app, build flavors, or configuration. Select staging when available; otherwise continue with the app's default environment. Record which was used.
- Exercise every in-app action required by the acceptance criteria, including creating, editing, deleting records, and logging out. Never leave a flow untested to protect device or account state.
- Device QA does not require a local backend, disposable database, or outbound substitutes. Backend and web isolation requirements apply only to those targets. Their setup failures do not block device flows.

## Device discovery

Run `adb devices -l` and classify each line:

| State | Meaning | Outcome |
|---|---|---|
| `device` | authorized and online | candidate |
| `unauthorized` | the host key is not accepted on the device | settle `skipped` and say the target is unauthorized. Fix: unlock the device and accept the USB debugging prompt. |
| `offline` | adb sees the device but cannot talk to it | An `emulator-*` serial that is still booting: wait for boot as in [Emulator boot](#emulator-boot), then classify again. Otherwise settle `skipped` and say the target is offline. Fix: reconnect the cable or run `adb kill-server && adb start-server`. |
| no lines | nothing attached | boot an AVD as in [Emulator boot](#emulator-boot). |

Selection:

- When the user named a serial, use it. If it is not an authorized candidate, settle `skipped` and say why.
- When exactly one authorized candidate exists, use it.
- When more than one authorized candidate exists and the user named none, choose any free candidate, lease it with `skill-bill qa lease acquire`, and continue with that target.
- If the candidate is contended or unusable, for example because it is out of storage, locked, or failing installation, release any lease this invocation acquired. When the user named a serial or AVD, settle `skipped` and say why; never substitute another target. Otherwise try another untried authorized candidate.
- Try each candidate at most once. If none can be used, settle `skipped` and describe the obstacles; call it device-contended only when every authorized candidate is leased by someone else.

Name the device used in the report by its lowercase hex sha256 of the trimmed serial, the same value `skill-bill qa lease` prints, and say why it was chosen. Never print the raw serial.

## Emulator boot

When `adb devices -l` lists nothing, start an emulator instead of skipping.

1. Find the emulator binary at `$ANDROID_HOME/emulator/emulator`, `$ANDROID_SDK_ROOT/emulator/emulator`, or `<sdk.dir>/emulator/emulator` from the repository's `local.properties`. When none exists, settle `skipped`. Fix: install the Android Emulator from the SDK Manager.
2. Run `<emulator> -list-avds`.
   - When the user named an AVD, use it. If it is not listed, settle `skipped` and name the listed AVDs in the report.
   - When exactly one AVD is listed, use it.
   - When more than one is listed and the user named none, choose an AVD that can be booted, then lease its emulator target with `skill-bill qa lease acquire`.
   - If its target is contended or the booted or leased target is unusable, release any lease this invocation acquired and stop only the emulator this invocation booted. If the user named an AVD, settle `skipped` and say why; otherwise repeat steps 3–6 for another untried AVD.
   - Try each AVD at most once. If none can be used, settle `skipped` and describe the obstacles; call it device-contended only when every AVD target is contended.
   - When none is listed, settle `skipped`. Fix: create an AVD in Android Studio's Device Manager or with `avdmanager create avd`.
3. Start it detached so it outlives the command: `nohup <emulator> -avd <name> -no-snapshot-save -no-boot-anim > <invocation dir>/emulator.log 2>&1 &`. Add `-no-window` when neither `DISPLAY` nor `WAYLAND_DISPLAY` is set.
4. Run `adb wait-for-device`, then poll `adb -s <serial> shell getprop sys.boot_completed` every few seconds until it prints `1`, for up to 300 seconds. The serial is the `emulator-*` line that `adb devices` now shows.
5. If boot does not finish in time, stop only the emulator this run booted with `adb -s <serial> emu kill`, release any lease held for it, and follow step 2's retry or skip rule. Save the relevant log excerpt under the invocation directory and reference its path in the report.
6. Unlock the screen with `adb -s <serial> shell input keyevent 82`, then continue with the device lease.

Name a booted AVD in the report as an emulator and say why you booted it, for example "No device attached; booted the only AVD."

When the run finishes, stop only an emulator this run booted, with `adb -s <serial> emu kill`, and say in the report that you stopped it. Leave an emulator that was already running alone. If the kill fails, say in the report that the emulator is still running, name the AVD, and tell the user how to stop it.

## Background processes

A command that never exits holds your shell, so the run never finishes and the report never settles.

- Start every long-running process the run needs detached so the command returns: a local backend, API simulator, mock server, Metro or another packager, or a log tail. Use `nohup <cmd> > <invocation dir>/<name>.log 2>&1 &` and record its PID from `$!`.
- Wait for readiness by polling a health URL or a log line in a bounded loop, for example every 2 seconds for up to 120 seconds. Never run the process in the foreground to watch it start.
- Never run a command that does not end on its own in the foreground: servers, watch modes, `tail -f`, or `adb logcat` without `-d`. Use bounded forms such as `adb logcat -d` or `timeout <seconds> <cmd>`.
- Before writing the report, stop every process this run started by its recorded PID and say so in the report. Leave processes that were already running alone. If a stop fails, say in the report which process is still running and tell the user how to stop it.
- Write the report last, then exit. Nothing this run started may keep running in your foreground after the report is written.

## Device lease

Before installing anything, run:

```
skill-bill qa lease acquire --invocation <invocation id> --device <serial>
```

`contended` means another QA run owns the device; do not use it or release its lease. If no serial or AVD was named, try another untried authorized target as described above. Skip when the named target is contended or no usable candidate remains, and say why. Release only leases this invocation acquired, using the same command with `release`, before switching targets and when you finish. The runtime also releases every lease of the invocation when the phase ends.

## Build and install

- Build or locate the debug APK for the tested revision with the project's own Gradle task (for example `./gradlew :app:assembleDebug`). Name its path in the report.
- Install with `adb -s <serial> install -r <apk>`.
- Launch an explicit activity, never through `monkey -c android.intent.category.LAUNCHER`: debug builds often add a second launcher activity to the same package, such as LeakCanary's, and monkey picks between them at random.
- List the launcher activities with `adb -s <serial> shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER` and keep the ones under the applicationId. When more than one remains, pick the app's own activity and skip debug-tool launchers such as `leakcanary.*`.
- Launch it with `adb -s <serial> shell am start -n <applicationId>/<activity>`, then confirm `adb -s <serial> shell dumpsys activity activities | grep topResumedActivity` names that activity. If a debug-tool screen is in front, launch the app's activity again before exercising anything.

## Exercise the criteria

- Derive flows from the run's acceptance criteria only. Do not add flows the criteria do not ask for; report anything else you notice as a finding.
- Run every flow the criteria describe on the app built from the QA checkout. Do not inspect git history, diffs, or source to decide whether the change is present. Missing or wrong behavior is a finding. Skip only when the tools to perform QA are unavailable.
- Prefer existing automation. Reuse instrumentation tests (`./gradlew connectedDebugAndroidTest` filtered to the screens the criteria cover) or Maestro flows already in the repository before writing new steps.
- Otherwise drive the app with `adb shell input tap|text|swipe|keyevent`, reading positions from `adb shell uiautomator dump`.
- After each step that matters, capture `adb exec-out screencap -p` and a `uiautomator dump` into the invocation directory.
- Read `adb logcat -d --pid=$(adb shell pidof -s <applicationId>)` and keep only the excerpt that explains a failure, saved as a file under the invocation directory.

## Desktop target

A desktop app runs on the host, so the `desktop` kind needs no adb, emulator, or device lease.

### Find the app

- Find the application module from its `compose.desktop { application { mainClass = ... } }` block, and any root task alias such as `./gradlew run`. Name the module and main class in the report.
- Read the app's entry point for the local state it loads at startup: files under the home directory, a local database, environment variables, or a sibling process it talks to.

### Isolate local data

The app runs on the operator's machine, so its default data is the operator's real data.

- Point the app at a disposable copy before any flow that writes. Use the override the app already supports, such as a state-directory environment variable, a system property, or a `-Duser.home=<invocation dir>/home` JVM argument, and seed it from the project's fixtures or by copying only what the criteria need.
- When the app offers no override, run only read-only flows against the default data and list the write flows as untested with that reason. Never mutate the operator's real app data.
- Give the app its own temporary directory as well: create `<invocation dir>/tmp`, then set `TMPDIR` and `-Djava.io.tmpdir` to it. `-Duser.home` does not move single-instance locks kept in the temporary directory, so a copy of the app the operator already has open can make the QA copy exit with status 0 and no error. `./gradlew :<app module>:run` forks the app's JVM, so pass these properties through `JAVA_TOOL_OPTIONS`.
- Record the data directory, the temporary directory, and how you seeded the data under `## Isolation`.

### Exercise the criteria with Compose UI tests

This layer works without a display, so run it on every desktop QA run.

- Reuse existing `jvmTest` tests that cover the screens the criteria describe. They use `runComposeUiTest` or `createComposeRule` from `org.jetbrains.compose.ui:ui-test`. Run them filtered, for example `./gradlew :feature:chat:jvmTest --tests '<class>'`.
- When no test covers a criterion, write a temporary test in the QA checkout. Render the screen's composable wired to its real state holder with the state the criterion needs. Drive it with `performClick`, `performTextInput`, and `performKeyInput` for keyboard shortcuts and focus moves, then assert on semantics nodes. List the file under `## Temporary edits`.
- Capture screenshots with `onRoot().captureToImage().toAwtImage()` written as PNG under the invocation directory.
- A rendered composable with no asserted outcome is not a pass.

### Exercise the running app

Launch the real app as well when a display is available, because window size, menus, multiple windows, and startup wiring only show up there.

- Discover display and input separately before deciding that live QA is unavailable. Check whether `DISPLAY` or `WAYLAND_DISPLAY` connects, and look for a private display server, screenshot tool, window inspection and resize tool, and input driver. Record the checks and their results under the invocation directory. A missing `xdotool` alone does not prove that input is unavailable; check other usable automation already installed.
- Prefer a private X server. Prepare `<invocation dir>/live-preview` with owner-only access using `install -d -m 700 -- <invocation dir>/live-preview`, then register that exact child with `skill-bill qa resource register --invocation <invocation id> --kind directory --identity <invocation dir>/live-preview` before starting capture. If preparation or registration fails, or registration reports `conflict` or `rejected`, do not use that directory even when the command exits zero. Record the refusal and its reason in the invocation evidence and QA report as a private-capture limitation.
- After preparation and ownership registration succeed, start Xvfb with `setsid Xvfb -displayfd 3 -screen 0 1600x1000x24 -fbdir <invocation dir>/live-preview 3> <invocation dir>/xvfb.display > <invocation dir>/xvfb.log 2>&1 < /dev/null &`. When preview preparation or registration fails, start the private server without capture using `setsid Xvfb -displayfd 3 -screen 0 1600x1000x24 3> <invocation dir>/xvfb.display > <invocation dir>/xvfb.log 2>&1 < /dev/null &`. In either case, record `$!` as its PID and register it with `skill-bill qa resource register --invocation <invocation id> --kind process --identity <pid>`. Poll until `<invocation dir>/xvfb.display` is non-empty within the 120 second readiness bound in the QA run prompt, then export `DISPLAY=:<number read from the file>` and verify that the server accepts connections. When the owned file-backed source is available, its fixed path is `<invocation dir>/live-preview/Xvfb_screen0`. If Xvfb or the file-backed source is unavailable, an existing display may support QA launch and observation, but it is never a preview source. Do not substitute ambient `DISPLAY` or a personal display for the private framebuffer. Do not drive the operator's visible session without permission. Never report that no display exists merely because a private server is absent.
- Use plain `setsid`, not `setsid -f`, because `-f` forks and `$!` no longer names the server or app process. `ps -o pid,sid -p <pid>` confirms the PID leads its own session.
- Start the app detached from the QA checkout with `setsid nohup ./gradlew :<app module>:run > <invocation dir>/app.log 2>&1 < /dev/null &`, with the data and temporary directory overrides from [Isolate local data](#isolate-local-data). Record `$!` as its PID and register it with `skill-bill qa resource register --invocation <invocation id> --kind process --identity <pid>`.
- The Gradle daemon may launch the app JVM outside the launcher's process tree. Identify the new app PID using its main class and invocation-specific data or temporary directory, verify it belongs to this run, and register it separately with the same command. Do not register or stop a pre-existing app or Gradle daemon.
- Wait in a bounded loop for the window to appear, for example `xdotool search --sync --name '<window title>'` under `timeout 120`, or a startup log line.
- When the launch ends with status 0 and no window, find what ended the process before reporting a defect or an unavailable environment. Relaunch with `-XX:StartFlightRecording:filename=<invocation dir>/app.jfr,dumponexit=true` added to `JAVA_TOOL_OPTIONS`, then read the exit stack with `jfr print --events jdk.Shutdown <invocation dir>/app.jfr`.
- When the app opens its own refusal or startup-gate window, such as one saying a required runtime is unavailable, treat it as app behavior rather than a missing environment. Read the entry point described above and find the app's own overrides, including environment variables, system properties, config files, or launch flags. Apply an override and relaunch with the same `setsid` form. If none satisfies the gate, record a `#### app_startup_gate` entry under `## Unblock` with the override search you tried and the override or setup the operator must provide. Do not end with a bare `environment_unavailable`.
- Check each required capability independently: app launch, screenshots, window size and resize, pointer input, and keyboard input. Use available X11 or Wayland tools appropriate to the display. For X11, `import -window root`, `xprop`, and `xdotool` are examples, not mandatory dependencies. A private X server may also need a window manager for size and resize checks.
- Launch the app when a usable display and isolated data are available even if an input driver is missing. Exercise only checks the available tools support; a launch alone does not prove a flow. Name each untested assertion, the missing capability, the attempted alternatives, and a concrete install or setup remedy as a `## Unblock` entry, with a capability such as `input` or `display`. Mark the desktop target `incomplete` when a required live-app assertion remains untested, even if Compose UI tests pass.
- When no display can be created or connected, rely on the Compose UI test layer and list the running-app checks as untested with the reason. Record the missing display and its install or setup remedy in a `#### display` entry under `## Unblock`. Do not settle the kind `skipped` while the UI test layer still ran.
- Stop the registered app JVM, launcher, and display server this run started by PID before explicitly releasing any `live-preview` DIRECTORY this run registered with `skill-bill qa resource release --invocation <invocation id> --kind directory --identity <invocation dir>/live-preview`. Verify each process has exited before release. Report any process or directory release that is retained, and preserve the runtime's retained-resource diagnostics and lifecycle finalization as the failure and cancellation cleanup safety net. Do not delete the invocation directory or reports, and do not create a second resource registry.

Say in `## Assertions` which layer, UI test or running app, proved each assertion.

## Report

Write `report.md` as markdown prose. The runtime applies no schema, enum, or length cap to it.

- The first line names the verdict with one word: `passed`, `findings_open`, `incomplete`, or `skipped`. The first line that names one of them decides the verdict.
- Follow it with a short summary of what you exercised on each target and what you left untested, and why.
- Name the Android device or emulator you used (by the hashed identifier, never the raw serial) and any fallback, such as a booted emulator, in prose. For desktop, name the app module, the display you used, and the data directory.
- Write each finding as a `### <finding-id> <title>` section. The id contains a digit, for example `QA-1`. State the steps, the expected behavior, the observed behavior, and the evidence as absolute paths under the evidence prefix the briefing names. The runtime hands this section to `qa_fix` verbatim.
- Add any other sections you need, such as `### Cleanup` or `### Device`. A heading whose first word has no digit is not a finding.
- Missing capabilities go under a final `## Unblock` section in the format defined by the QA run prompt. Use `passed` only when every acceptance criterion was exercised and no finding is open. Use `findings_open` when at least one finding is open. Use `incomplete` when the run started but could not finish, and `skipped` when it could not start; say how to fix it in `## Unblock`.

QA never blocks the run. The runtime never rewrites the verdict you state, so claim only what you exercised.
