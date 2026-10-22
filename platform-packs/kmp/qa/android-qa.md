# Android QA

Drive the app on one Android device or emulator, check the changed behavior, and write one prose report. QA is report-only.

## Rules

- Write generated scripts, screenshots, UI dumps, and log excerpts only under the invocation directory the briefing names. Nothing goes in the working tree.
- Never edit tracked files, commit, push, or open a pull request.
- Never paste log bodies, raw device serials, credentials, or tokens into the report. Evidence is a path under the invocation directory.
- Write the report to the exact `report.md` path the briefing names. An app launch alone is never a pass.

## Test account and environment

- QA runs on a test account with test data. Every in-app action is allowed: create, edit, and delete records, add profiles, remove cards, log out. Never leave a flow untested to protect device or account state.
- When the app or build offers an environment, flavor, or server choice, use Local when a local backend is available, else staging. Never use production.
- When only production is available, exercise no flow. Write a `skipped` report that names the missing environment.

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
- Launch with `adb -s <serial> shell monkey -p <applicationId> -c android.intent.category.LAUNCHER 1` or the launcher activity through `am start`.

## Exercise the change

- Derive flows from the run's acceptance criteria only. Do not add flows the criteria do not ask for; report anything else you notice as a finding.
- Prefer existing automation. Reuse instrumentation tests (`./gradlew connectedDebugAndroidTest` filtered to the changed screens) or Maestro flows already in the repository before writing new steps.
- Otherwise drive the app with `adb shell input tap|text|swipe|keyevent`, reading positions from `adb shell uiautomator dump`.
- After each step that matters, capture `adb exec-out screencap -p` and a `uiautomator dump` into the invocation directory.
- Read `adb logcat -d --pid=$(adb shell pidof -s <applicationId>)` and keep only the excerpt that explains a failure, saved as a file under the invocation directory.

## Report

Write `report.md` as markdown prose. The runtime applies no schema, enum, or length cap to it.

- The first line names the verdict with one word: `passed`, `findings`, `incomplete`, or `skipped`. The first line that names one of them decides the verdict.
- Follow it with a short summary of what you exercised and what you left untested, and why.
- Name the device or emulator you used (by the hashed identifier, never the raw serial) and any fallback, such as a booted emulator, in prose.
- Write each finding as a `### <finding-id> <title>` section. The id contains a digit, for example `QA-1`. State the steps, the expected behavior, the observed behavior, and the evidence as absolute paths under the evidence prefix the briefing names. The runtime hands this section to `qa_fix` verbatim.
- Add any other sections you need, such as `### Cleanup` or `### Device`. A heading whose first word has no digit is not a finding.
- Use `passed` only when every acceptance criterion was exercised and no finding is open. Use `findings` when at least one finding is open. Use `incomplete` when the run started but could not finish, and `skipped` when it could not start; say how to fix it.

QA never blocks the run. The runtime never rewrites the verdict you state, so claim only what you exercised.
