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
| `offline` | adb sees the device but cannot talk to it | skip with `offline_target`. Fix: reconnect the cable or run `adb kill-server && adb start-server`. |
| no lines | nothing attached | skip with `unsupported_target`. Fix: attach a device or start an emulator. |

Selection:

- When the user named a serial, use it (`selection: explicit`). If it is not an authorized candidate, skip with the matching reason.
- When exactly one authorized candidate exists, use it (`selection: single`).
- When more than one authorized candidate exists and the user named none, skip with `ambiguous_target` and list how to name one, without printing serials into the receipt.

`device_fingerprint` is the lowercase hex sha256 of the trimmed serial. It is the same value `skill-bill qa lease` prints.

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
