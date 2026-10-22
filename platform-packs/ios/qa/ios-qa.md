# iOS QA

Drive the app on one iPhone or simulator, check the changed behavior, and write one prose report. QA is report-only.

## Rules

- Write generated scripts, screenshots, UI hierarchies, and log excerpts only under the invocation directory the briefing names. Nothing goes in the working tree.
- Never edit tracked files, commit, push, or open a pull request.
- Never paste log bodies, raw device UDIDs, credentials, signing identities, or tokens into the report. Evidence is a path under the invocation directory.
- Write the report to the exact `report.md` path the briefing names. An app launch alone is never a pass.

## Test account and environment

- QA runs on a test account with test data. Every in-app action is allowed: create, edit, and delete records, add profiles, remove cards, log out. Never leave a flow untested to protect device or account state.
- When the app or build offers an environment, flavor, or server choice, use Local when a local backend is available, else staging. Never use production.
- When only production is available, exercise no flow. Write a `skipped` report that names the missing environment.

## Prerequisites

Check each one in order. The first that fails decides the skip, and the `skipped` report carries its fix-it line.

| Check | Command | Skip and fix |
|---|---|---|
| macOS host | `uname -s` prints `Darwin` | iOS QA needs a Mac. |
| Xcode tools | `xcrun --find devicectl` and `xcodebuild -version` | Install Xcode and run `xcode-select -s`. |
| Paired device | `xcrun devicectl list devices` shows the device as available | Connect the device, trust this Mac, and keep it unlocked. |
| Developer Mode | the device row reports Developer Mode on | Enable Settings > Privacy & Security > Developer Mode. |
| Signing | the scheme builds for the device with the project's team | Select a team and a signing identity in the project. |
| WebDriverAgent | the Appium XCUITest driver can build and launch WebDriverAgent on the device | Run `appium driver run xcuitest open-wda` and sign the runner. |

When the user named a UDID or simulator, use only that target. If it is unavailable, unauthorized, contended, or unusable, release any held lease, settle `skipped`, and say why. When no target was named, choose any authorized free booted or bootable simulator or free device and lease it with `skill-bill qa lease acquire`. If the chosen target is contended or unusable, release the lease when held and try another untried target. Try each candidate at most once. If none can be used, settle `skipped` and describe the obstacles; call it device-contended only when every authorized target is leased by someone else.

Name the device or simulator used in the report by its lowercase hex sha256 of the trimmed device UDID, the same value `skill-bill qa lease` prints, and say why it was chosen. Never print the raw UDID.

## Background processes

A command that never exits holds your shell, so the run never finishes and the report never settles.

- Start every long-running process the run needs detached so the command returns: a local backend, API simulator, mock server, Metro or another packager, an Appium server, or a log tail. Use `nohup <cmd> > <invocation dir>/<name>.log 2>&1 &` and record its PID from `$!`.
- Wait for readiness by polling a health URL or a log line in a bounded loop, for example every 2 seconds for up to 120 seconds. Never run the process in the foreground to watch it start.
- Never run a command that does not end on its own in the foreground: servers, watch modes, `tail -f`, or `log stream`. Use bounded forms such as `log show --last 5m` or `timeout <seconds> <cmd>`.
- Before writing the report, stop every process this run started by its recorded PID and say so in the report. Leave processes that were already running alone. If a stop fails, say in the report which process is still running and tell the user how to stop it.
- Write the report last, then exit. Nothing this run started may keep running in your foreground after the report is written.

## Device lease

Before installing anything, run:

```
skill-bill qa lease acquire --invocation <invocation id> --device <udid>
```

`contended` means another QA run owns the device; do not use it or release its lease. If no target was named, try another untried authorized target as described above. Skip when the named target is contended or no usable candidate remains, and say why. Release only leases this invocation acquired, using the same command with `release`, before switching targets and when you finish.

## Build, install, and launch

- Build the app for the tested revision with the project's scheme (`xcodebuild -scheme <scheme> -destination id=<udid> build`). Name the `.app` path in the report.
- Install, launch, and terminate with `xcrun devicectl device install app`, `xcrun devicectl device process launch`, and `xcrun devicectl device process terminate`.

## Exercise the change

- Derive flows from the run's acceptance criteria only. Do not add flows the criteria do not ask for; report anything else you notice as a finding.
- Prefer existing automation. Reuse the project's XCUITest schemes for the changed screens before writing new steps.
- Otherwise drive the device through the Appium XCUITest driver with WebDriverAgent. Save screenshots and page source after each step that matters.
- Keep only the log excerpt that explains a failure, saved as a file under the invocation directory.

## Simulator fallback

When no physical device can run the flow, a simulator may stand in:

- Boot one with `xcrun simctl boot`, install with `xcrun simctl install`, and reuse Maestro flows when the repository has them.
- Name the simulator in the report and say why it stood in for a device.
- A simulator never exercises a hardware-only flow. Report that flow as untested with the reason. The run cannot pass unless every acceptance criterion was exercised on another flow.

## Report

Write `report.md` as markdown prose. The runtime applies no schema, enum, or length cap to it.

- The first line names the verdict with one word: `passed`, `findings`, `incomplete`, or `skipped`. The first line that names one of them decides the verdict.
- Follow it with a short summary of what you exercised and what you left untested, and why.
- Name the device or simulator you used (by the hashed identifier, never the raw UDID) and any fallback, such as a simulator standing in for a device, in prose.
- Write each finding as a `### <finding-id> <title>` section. The id contains a digit, for example `QA-1`. State the steps, the expected behavior, the observed behavior, and the evidence as absolute paths under the evidence prefix the briefing names. The runtime hands this section to `qa_fix` verbatim.
- Add any other sections you need, such as `### Cleanup` or `### Device`. A heading whose first word has no digit is not a finding.
- Use `passed` only when every acceptance criterion was exercised and no finding is open. Use `findings` when at least one finding is open. Use `incomplete` when the run started but could not finish, and `skipped` when it could not start; say how to fix it.

QA never blocks the run. The runtime never rewrites the verdict you state, so claim only what you exercised.
