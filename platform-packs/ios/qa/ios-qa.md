# iOS QA

Drive the app on one iPhone or simulator, check the changed behavior, and write one receipt. QA is report-only.

## Rules

- Write generated scripts, screenshots, UI hierarchies, and log excerpts only under the invocation directory the briefing names. Nothing goes in the working tree.
- Never edit tracked files, commit, push, or open a pull request.
- Never paste log bodies, raw device UDIDs, credentials, signing identities, or tokens into the receipt. Evidence is a path under the invocation directory plus its sha256.
- Write the receipt to the exact path the briefing names. An app launch alone is never a pass.

## Test account and environment

- QA runs on a test account with test data. Every in-app action is allowed: create, edit, and delete records, add profiles, remove cards, log out. Never leave a flow untested to protect device or account state.
- When the app or build offers an environment, flavor, or server choice, use Local when a local backend is available, else staging. Never use production.
- When only production is available, exercise no flow. Write a `skipped` receipt with `production_only` and name the missing environment in `reason_detail`.

## Prerequisites

Check each one in order. The first that fails decides the skip, and `reason_detail` carries its fix-it line.

| Check | Command | Skip and fix |
|---|---|---|
| macOS host | `uname -s` prints `Darwin` | `unsupported_target`. iOS QA needs a Mac. |
| Xcode tools | `xcrun --find devicectl` and `xcodebuild -version` | `unsupported_target`. Install Xcode and run `xcode-select -s`. |
| Paired device | `xcrun devicectl list devices` shows the device as available | `offline_target`. Connect the device, trust this Mac, and keep it unlocked. |
| Developer Mode | the device row reports Developer Mode on | `unauthorized_target`. Enable Settings > Privacy & Security > Developer Mode. |
| Signing | the scheme builds for the device with the project's team | `unauthorized_target`. Select a team and a signing identity in the project. |
| WebDriverAgent | the Appium XCUITest driver can build and launch WebDriverAgent on the device | `isolation_unavailable`. Run `appium driver run xcuitest open-wda` and sign the runner. |

More than one available device with none named by the user is `ambiguous_target`.

`device_fingerprint` is the lowercase hex sha256 of the trimmed device UDID. It is the same value `skill-bill qa lease` prints.

## Background processes

A command that never exits holds your shell, so the run never finishes and the receipt never settles.

- Start every long-running process the run needs detached so the command returns: a local backend, API simulator, mock server, Metro or another packager, an Appium server, or a log tail. Use `nohup <cmd> > <invocation dir>/<name>.log 2>&1 &` and record its PID from `$!`.
- Wait for readiness by polling a health URL or a log line in a bounded loop, for example every 2 seconds for up to 120 seconds. Never run the process in the foreground to watch it start.
- Never run a command that does not end on its own in the foreground: servers, watch modes, `tail -f`, or `log stream`. Use bounded forms such as `log show --last 5m` or `timeout <seconds> <cmd>`.
- Before writing the receipt, stop every process this run started by its recorded PID and report it in `cleanup`. Leave processes that were already running alone. If a stop fails, report `cleanup.outcome: retained` with a `retained_resources` entry of `kind: process` and the command name, plus `guidance` telling the user how to stop it.
- Write the receipt last, then exit. Nothing this run started may keep running in your foreground after the receipt is written.

## Device lease

Before installing anything, run:

```
skill-bill qa lease acquire --invocation <invocation id> --device <udid>
```

`contended` means another QA run owns the device. Stop and write a `skipped` receipt with `device_contended`. When you finish, run the same command with `release`.

## Build, install, and launch

- Build the app for the tested revision with the project's scheme (`xcodebuild -scheme <scheme> -destination id=<udid> build`). Record the `.app` path and its sha256 in `artifact`.
- Install, launch, and terminate with `xcrun devicectl device install app`, `xcrun devicectl device process launch`, and `xcrun devicectl device process terminate`.

## Exercise the change

- Prefer existing automation. Reuse the project's XCUITest schemes for the changed screens before writing new steps.
- Otherwise drive the device through the Appium XCUITest driver with WebDriverAgent. Save screenshots and page source after each step that matters.
- Keep only the log excerpt that explains a failure, saved as a file under the invocation directory.

## Simulator fallback

When no physical device can run the flow, a simulator may stand in:

- Boot one with `xcrun simctl boot`, install with `xcrun simctl install`, and reuse Maestro flows when the repository has them.
- Set `target.kind: simulator` and `target.selection: substituted`, and add a `fallbacks` entry with `kind: simulator_substitution` and the reason.
- A simulator never marks a `hardware_only` flow exercised. Mark it `untested` with the reason. A run with an untested flow cannot pass.

## Verdict

- `passed`: every selected flow was exercised, each has a passing assertion, and there are no findings.
- `findings_open`: at least one finding.
- `incomplete`: the run started but could not finish, with `incomplete_reason`.
- `skipped`: the run could not start, with `skip_reason` and `reason_detail` holding the fix-it line.

QA never blocks the run. The runtime records a missing, malformed, or contradictory receipt as a warning and continues, so claim only what you exercised.
