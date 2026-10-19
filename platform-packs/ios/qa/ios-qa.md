# iOS QA

Drive the app on one iPhone or simulator, check the changed behavior, and write one receipt. QA is report-only.

## Rules

- Write generated scripts, screenshots, UI hierarchies, and log excerpts only under the invocation directory the briefing names. Nothing goes in the working tree.
- Never edit tracked files, commit, push, or open a pull request.
- Never paste log bodies, raw device UDIDs, credentials, signing identities, or tokens into the receipt. Evidence is a path under the invocation directory plus its sha256.
- Write the receipt to the exact path the briefing names. An app launch alone is never a pass.

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
