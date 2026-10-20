# Android QA

Drive the app on one Android device or emulator, check the changed behavior, and write one receipt. QA is report-only.

## Rules

- Write generated scripts, screenshots, UI dumps, and log excerpts only under the invocation directory the briefing names. Nothing goes in the working tree.
- Never edit tracked files, commit, push, or open a pull request.
- Never paste log bodies, raw device serials, credentials, or tokens into the receipt. Evidence is a path under the invocation directory plus its sha256.
- Write the receipt to the exact path the briefing names. An app launch alone is never a pass.

## Device discovery

Run `adb devices -l` and classify each line:

| State | Meaning | Outcome |
|---|---|---|
| `device` | authorized and online | candidate |
| `unauthorized` | the host key is not accepted on the device | skip with `unauthorized_target`. Fix: unlock the device and accept the USB debugging prompt. |
| `offline` | adb sees the device but cannot talk to it | An `emulator-*` serial that is still booting: wait for boot as in [Emulator boot](#emulator-boot), then classify again. Otherwise skip with `offline_target`. Fix: reconnect the cable or run `adb kill-server && adb start-server`. |
| no lines | nothing attached | boot an AVD as in [Emulator boot](#emulator-boot). |

Selection:

- When the user named a serial, use it (`selection: explicit`). If it is not an authorized candidate, skip with the matching reason.
- When exactly one authorized candidate exists, use it (`selection: single`).
- When more than one authorized candidate exists and the user named none, skip with `ambiguous_target` and list how to name one, without printing serials into the receipt.

`device_fingerprint` is the lowercase hex sha256 of the trimmed serial. It is the same value `skill-bill qa lease` prints.

## Emulator boot

When `adb devices -l` lists nothing, start an emulator instead of skipping.

1. Find the emulator binary at `$ANDROID_HOME/emulator/emulator`, `$ANDROID_SDK_ROOT/emulator/emulator`, or `<sdk.dir>/emulator/emulator` from the repository's `local.properties`. When none exists, skip with `unsupported_target`. Fix: install the Android Emulator from the SDK Manager.
2. Run `<emulator> -list-avds`.
   - When the user named an AVD, use it. If it is not listed, skip with `unsupported_target` and name the listed AVDs in `reason_detail`.
   - When exactly one AVD is listed, use it.
   - When more than one is listed and the user named none, skip with `ambiguous_target`. Name the listed AVDs in `reason_detail` and tell the user to pass the AVD name in the phase instructions.
   - When none is listed, skip with `unsupported_target`. Fix: create an AVD in Android Studio's Device Manager or with `avdmanager create avd`.
3. Start it detached so it outlives the command: `nohup <emulator> -avd <name> -no-snapshot-save -no-boot-anim > <invocation dir>/emulator.log 2>&1 &`. Add `-no-window` when neither `DISPLAY` nor `WAYLAND_DISPLAY` is set.
4. Run `adb wait-for-device`, then poll `adb -s <serial> shell getprop sys.boot_completed` every few seconds until it prints `1`, for up to 300 seconds. The serial is the `emulator-*` line that `adb devices` now shows.
5. If boot does not finish in time, stop the emulator with `adb -s <serial> emu kill` and skip with `unsupported_target`. Put the last lines of `emulator.log` in `reason_detail` without serials.
6. Unlock the screen with `adb -s <serial> shell input keyevent 82`, then continue with the device lease.

Record a booted AVD as `target.kind: simulator` and `target.selection: substituted`, and add a `fallbacks` entry with `kind: simulator_substitution` and the reason, for example "No device attached; booted the only AVD."

When the run finishes, stop only an emulator this run booted, with `adb -s <serial> emu kill`, and report `cleanup.outcome: released`. Leave an emulator that was already running alone. If the kill fails, report `cleanup.outcome: retained` with a `retained_resources` entry of `kind: emulator` and the AVD name, plus `guidance` telling the user how to stop it.

## Device lease

Before installing anything, run:

```
skill-bill qa lease acquire --invocation <invocation id> --device <serial>
```

`contended` means another QA run owns the device. Stop and write a `skipped` receipt with `device_contended`. When you finish, run the same command with `release`. The runtime also releases every lease of the invocation when the phase ends.

## Build and install

- Build or locate the debug APK for the tested revision with the project's own Gradle task (for example `./gradlew :app:assembleDebug`). Record its path in `artifact.path_ref` and its sha256 in `artifact.sha256`.
- Install with `adb -s <serial> install -r <apk>`.
- Launch with `adb -s <serial> shell monkey -p <applicationId> -c android.intent.category.LAUNCHER 1` or the launcher activity through `am start`.

## Exercise the change

- Prefer existing automation. Reuse instrumentation tests (`./gradlew connectedDebugAndroidTest` filtered to the changed screens) or Maestro flows already in the repository before writing new steps.
- Otherwise drive the app with `adb shell input tap|text|swipe|keyevent`, reading positions from `adb shell uiautomator dump`.
- After each step that matters, capture `adb exec-out screencap -p` and a `uiautomator dump` into the invocation directory.
- Read `adb logcat --pid=$(adb shell pidof -s <applicationId>)` and keep only the excerpt that explains a failure, saved as a file under the invocation directory.

Each assertion names the flow it checks, what it verified, and its outcome. A finding records the steps, expected and observed behavior, and evidence refs.

## Verdict

- `passed`: every selected flow was exercised, each has a passing assertion, and there are no findings.
- `findings_open`: at least one finding.
- `incomplete`: the run started but could not finish, with `incomplete_reason`.
- `skipped`: the run could not start, with `skip_reason` and `reason_detail` holding the fix-it line.
