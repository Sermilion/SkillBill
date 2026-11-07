# QA device picker in the sidebar footer

Visual reference: `design/qa-device-picker/index.html`, variant A (switcher row).

## Goal

Show connected Android debug devices and AVDs in a dropdown at the bottom of the desktop app sidebar, and let the operator pick the device QA runs use.

## In scope

- A switcher row as the last item in the sidebar footer (`ShellSidebar.kt`), below Keyboard shortcuts, styled like `WorkspaceSwitcher`. Closed, it shows the current pick and a status dot: "Let QA choose · N ready", or the device name with transport and Android version. The dot is orange when any device is unauthorized or offline.
- The dropdown opens upward and lists:
  - "Let QA choose" first. This is the default and keeps today's behavior.
  - Connected: phones and running emulators from `adb devices -l`. Each row shows state (ready, unauthorized, offline, in use by `<run key>` when a QA device lease holds it), USB or Wi-Fi, and Android version.
  - Emulators: AVDs from `emulator -list-avds` that are not running, marked "Not running · QA boots it".
- Unauthorized, offline, and leased devices can't be picked. Unauthorized shows "Unlock the phone and accept the USB debugging prompt." inline.
- Empty states: no devices attached, no AVDs, and adb not found (with how to fix it).
- Discovery runs when the dropdown opens and on the refresh button or R. No background polling.
- Find adb and the emulator binary the same way the KMP QA pack does: `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`.
- The pick persists across app restarts.
- QA runs started from the app use the picked device as the named target. This covers standalone QA and the QA phase of goal runs. "Let QA choose" passes nothing and QA behaves as today.
- Runtime channel for the pick. The QA intake is not usable for this: the `qa` skeleton sets `intakeIsRequirements = true`, so `PhaseRunIntakeResolver` turns intake text into the run's only acceptance criterion, and `goal-qa` takes no operator text at all. Add a separate QA device field (serial or AVD name) to the QA run request and the goal QA phase, apart from the intake. Render it in the QA prompt next to the device lease lines in `QaPromptSections.kt` as the named target, so the KMP pack's existing named-serial and named-AVD rules apply (use it, skip with a reason if unusable, never substitute). Requirements and acceptance criteria stay unchanged.
- The "QA my branch" chip reads "QA my branch on <device>" when a device is picked.
- Keyboard: arrow keys move through pickable rows, Enter picks, Esc closes.

## Out of scope

- iOS devices and simulators (follow-up).
- Booting or stopping emulators from the UI.
- Logcat, screenshots, or other device actions.
- Force-releasing a QA lease.
- Showing raw serials in QA reports. The packs' sha256 rule stays as is.

## Done when

- The switcher row and dropdown match the mock's variant A in every scenario the mock covers.
- Picking a device and clicking "QA my branch" starts a QA run that uses that device, and the run's acceptance criteria are the same as without a pick.
- A goal run's QA phase uses the picked device.
- A picked device that is unplugged or leased by the time QA starts settles `skipped` with the reason, without falling back to another device.
- "Let QA choose" starts QA exactly as it does today.
- Tests cover the QA device field reaching the QA prompt without changing acceptance criteria.
- Unit tests cover parsing `adb devices -l` and `emulator -list-avds` output, the state classification, and the pickable rules.
