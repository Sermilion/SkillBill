# SKILL-419 Mobile QA phase

## Outcome

Skill Bill exercises changed mobile behavior on a usable device or simulator after acceptance audit. Android uses ADB. iOS uses existing project automation or Appium with the XCUITest driver and WebDriverAgent. Missing capabilities produce a recorded skip and let the workflow continue. Observed product bugs remain findings and enter bounded repair.

The same QA capability is available as a standalone phase and as an explicitly enabled evaluator in the existing verify operation. Verify without QA keeps its current behavior.

## Scope

- One horizontal QA phase with a `qa_fix` repair substep, its outcome contract, capability discovery, evidence, execution budgets, workflow integration, and operator forms.
- Android and iOS execution guidance declared through manifest-owned platform packs.
- Standalone `skill-bill phase qa [<instructions>] --agent <agent>` and `/skill-bill [<instructions>] phase:qa`.
- Optional in-workflow QA immediately after a satisfied `audit`, before downstream review and quality checks.
- Optional QA in `skill-bill operation verify <intake> qa:on`, after `completeness_audit` and before the consolidated verdict.
- Resume, status, telemetry, generated guidance, and installation parity for these entry points.

## Operator behavior

Full runs accept `qa:auto|off`. Omission selects `auto`, which attempts QA when a supported target and isolated test configuration are usable. `off` records a disabled skip. Automatic means that the operator does not need to install tools, connect devices, enter signing credentials, or answer setup questions during the run.

Standalone QA is report-only against the checked-out tree. It may build, install, launch, interact with the QA app, and use disposable test data. It does not repair application code, commit, push, or open a PR. Instructions can narrow flows and select a device. Without instructions, it discovers changed behavior using the repository's existing scope conventions. If no actionable flows can be established, it records that reason instead of inventing coverage.

Verify accepts `qa:on|off`, defaulting to `off`. `on` opts into capability discovery and execution; it does not require a device to exist. Missing prerequisites produce a skip with a reason. The selected option is shown with the initial criteria proposal, persisted with the verify workflow, and retained on confirmation and resume. Confirmation cannot silently change it. Duplicate, conflicting, or unknown values fail with actionable usage errors. The dispatcher forwards supported tokens without interpreting the platform.

## Mobile execution

### Android

Discover devices through ADB and distinguish authorized, unauthorized, and offline devices. Select an explicitly requested device or the single suitable available device. Multiple suitable devices without a declared selection produce an ambiguous-target skip. Do not ask the operator to unblock an unattended run.

Build or locate an artifact belonging to the tested revision, install the QA application, and exercise the affected flows. Use ADB for application lifecycle, input, screenshots, UI hierarchy where available, and filtered device logs. Reuse existing UI automation for interactions and assertions when it provides stronger evidence than coordinate taps. A successful command or app launch alone does not prove an acceptance flow.

### iOS

Prefer working project UI automation when it can exercise the affected flows. Otherwise use Appium's XCUITest driver and WebDriverAgent for interactive exploration on a physical iPhone or iPad. WebDriverAgent provides UI interaction through Apple's XCTest APIs; `devicectl` manages connected devices and application lifecycle rather than replacing the UI driver.

Execution requires a usable macOS host, compatible Xcode, paired device, Developer Mode, and existing signing configuration for the application and automation runner. Discover those prerequisites and skip when unavailable. Do not initiate account login, provisioning changes, or interactive device trust ceremonies.

A usable simulator is an allowed fallback and is recorded as simulator coverage. Reuse XCTest UI tests or available simulator automation such as Maestro. Do not claim physical-device or hardware-specific coverage from simulator execution. Flows requiring unavailable hardware remain untested with a reason. Simulator fallback is a recorded degradation, never an invisible substitution for an explicitly requested physical target.

Do not create a bespoke device automation server. In-workflow QA may retain small regression flows for reproduced bugs, using the project's existing test conventions. Standalone and verify keep generated scripts and evidence outside tracked source and do not create or maintain committed harness code.

## Outcomes and repair

QA records `passed`, `findings_open`, `skipped`, or `incomplete`. These are QA verdicts mapped deliberately onto existing workflow step statuses; they are not interchangeable with phase completion. An executed evaluator may finish with findings.

- `passed` means every selected applicable flow ran and met its assertions on the reported revision and environment.
- `findings_open` contains reproduced product failures, affected criteria, reproduction steps, expected and observed behavior, and evidence references.
- `skipped` names absent capability, unsupported target, disabled policy, unavailable isolation, ambiguous target, or an empty applicable scope. It never counts as a QA pass.
- `incomplete` names interrupted execution, broken automation, budget exhaustion, lost device, or other unfinished evidence. It retains completed observations and never clears existing findings.

Expected environment unavailability and infrastructure-only incompleteness let optional in-workflow QA continue after reporting the cause. A valid receipt must exist. Contract errors, corrupt state, runtime defects, or cancellation do not become capability skips. If execution already reproduced a product failure, later loss of tooling cannot erase it or turn the phase into a skip.

In-workflow `qa` findings enter the `qa_fix` substep with at most three repair attempts. `qa_fix` consumes the latest accepted findings and their reproduction evidence, fixes the identified bugs, and retains useful regression flows. It runs only when product findings remain open; passed, skipped, or infrastructure-only incomplete QA does not launch it.

Each `qa_fix` attempt is followed by acceptance audit and `qa` on the changed tree before advancing. Exhaustion blocks with the retained findings. Existing audit repair and non-shrinking limits remain in force. `qa_fix` cannot mark criteria satisfied or clear findings itself. Only subsequent audit and QA evidence can establish closure.

`qa_fix` has its own recorded step identity, attempt count, progress, and repair output under the shared QA phase. Resume retains its consumed attempts and latest open findings, reconciles existing edits, and completes re-audit and QA rather than replaying a completed repair. It is an internal substep, not a standalone command or listed skill.

Standalone QA presents findings without repair. Verify presents findings in its consolidated verdict without repair. A valid report with product findings is an executed evaluator, while malformed or missing output fails execution through the existing operator error conventions.

## Evidence and lifecycle

Each receipt identifies the repository checkpoint and any uncommitted tree identity, built artifact identity, policy, selected target, physical or simulated execution, tool versions where obtainable, exercised and untested flows, assertions, findings, skip or incomplete reasons, and bounded evidence references. Assertions map to acceptance criteria when available. Screenshots and logs are private artifacts; compact governed receipts carry references rather than full bodies.

QA builds and installs the version it reports. Review repair or another later behavior-changing edit invalidates prior QA evidence and re-enters audit and QA before the subtask finishes. A message-only amend with an unchanged tree does not invalidate evidence. Infrastructure recovery must not replay passed work against an unidentified application build.

Device access is scoped to one invocation, with exclusive ownership for destructive or stateful interactions. Concurrent runs cannot install over or manipulate the same device at once. Contention is an observable capability outcome. Test builds and fixtures use dedicated test state. Do not reset personal device data, uninstall unrelated apps, or use production accounts or services.

Started processes, temporary artifacts, and owned test resources have bounded cleanup on success, skip, failure, cancellation, and resume reconciliation. Failed cleanup is reported with retained resource identities and recovery guidance. Cancellation propagates. Resume checks target and revision validity rather than reusing stale receipts or assuming a disconnected device still exists.

## Acceptance criteria

1. Standalone `phase:qa` and in-workflow mobile QA use the same capability discovery, execution policy, and receipt validation. `/skill-bill` remains the only listed skill; QA creates no listed platform skill.
2. Automatic workflow QA occurs after satisfied acceptance audit. An unavailable device or tool produces an observable skip and downstream phases continue without operator participation. `qa:off` disables execution with a recorded reason.
3. Android QA can install and exercise the current artifact on a suitable ADB device, capture evidence, and report an assertion failure. Unauthorized, offline, absent, and ambiguous targets remain distinguishable.
4. iOS QA can reuse existing UI automation or drive a prepared physical device through Appium/WebDriverAgent. Missing host, pairing, toolchain, signing, or runner readiness produces an actionable skip. Simulator execution is identified and cannot satisfy a hardware-only flow.
5. Standalone QA leaves tracked files and HEAD unchanged, reports exercised flows and findings, and performs no application repair, commit, push, or PR mutation.
6. Verify omitting QA follows its existing proposal, confirmation, evaluator sequence, and verdict behavior. `qa:on` adds QA after completeness audit and before verdict; selection persists through confirmation and resume. `qa:off` performs no device probing or QA launch.
7. Opted-in verify binds the installed artifact to its resolved target head. It does not test another checkout or the operator's dirty working tree as if it were that target. It may use a temporary target checkout and disposable runtime state without editing the operator's source, HEAD, or review target.
8. Verify includes reproduced QA bugs in its consolidated findings and cannot issue an unqualified approval that ignores them. Skipped or incomplete QA stays visible as untested coverage without automatically converting optional unavailability into a product defect.
9. In-workflow QA findings launch the named `qa_fix` substep for at most three repair attempts, each followed by audit and QA. Passed, skipped, or infrastructure-only incomplete QA never launches `qa_fix`. Its identity, output, and consumed attempts persist across resume. Exhaustion blocks. A repair claim or later environment skip never clears previously open findings.
10. Reports cannot claim QA success from app launch alone, an old screenshot, another revision, partial execution, or malformed output. Behavior-changing later edits invalidate the receipt before completion.
11. Pack removal, custom packs, and unsupported targets use dynamic declarations and explicit capability results rather than hard-coded slug routing. Missing or malformed declared contracts fail loudly. Unsupported platform QA skips with a reason.
12. New durable QA records validate through canonical versioned schemas at producer and consumer boundaries, with Kotlin versions, owning keys, parity tests, and typed failure codes. Resume of pre-QA workflows retains their recorded composition or uses an explicitly checked mapping; it does not silently change an accepted execution plan.
13. Standalone phase status, verify state, and standalone CLI reports distinguish attempted, passed, findings, skipped, and incomplete QA. A full run durably records its QA outcome and the goal's pull request states it; displaying the full-run outcome in progress or IDE status is deferred. Every fallback, target substitution, retry, and cleanup failure emits a record without leaking credentials or private device logs.
14. Device contention, cancellation, interruption, and resumed cleanup do not corrupt another run's state or resource ownership. Generated scripts stay outside tracked source in report-only modes.

## Non-goals

- Building a custom iOS automation framework or requiring a new harness on every run.
- Installing SDKs, Appium, Xcode, simulator runtimes, or signing credentials automatically.
- Requiring physical hardware for every mobile task.
- Production accounts, personal-data resets, paid device clouds, or store distribution.
- Replacing code review, acceptance audit, existing build gates, or validation with QA.
- Repairing application code during standalone QA or verify.
- Backend/web environment adapters, which are owned by SKILL-420.

## Constraints and ownership

Shared QA composition, verdict semantics, evidence, budgets, repair policy, and verify integration belong to existing horizontal orchestration and runtime owners. Platform-specific guidance and capability declarations belong in manifest-declared packs. CLI and MCP adapters parse and render; engine coordinates; infrastructure owns external tooling; the existing composition root wires collaborators. Do not introduce a generic plugin framework, second composition root, or duplicated mobile workflow.

Apply `docs/runtime-command-guidance.md`, `docs/architecture-guidelines.md` A1 through A12 and G1 through G7, `runtime-kotlin/ARCHITECTURE.md` Design Principles, `docs/code-principles.md`, `docs/observability-policy.md`, and `docs/skill-source-generation.md`. Authored guidance is `content.md`; generated pointers and provider outputs are not committed. Run `./install.sh` when implementing skill, renderer, or support-pointer changes.

Base branch is `main`. This request creates requirements only and does not start implementation or create a decomposition manifest. Planning must use current source and preserve the user's ongoing SKILL-418 work.

## Validation strategy

Implementation names each realistic regression before adding its test. Required boundary evidence covers capability skip versus product failure, unauthorized and ambiguous devices, simulator attribution, immutable verify target, opt-in persistence, no source mutation in report-only modes, bounded repair and re-audit, stale receipt rejection, exclusive device access, cancellation cleanup, and pre-QA resume admission. Contract tests exercise accepted and rejected receipts through real validators. Tool-dependent smoke tests run only on available hosts and retain evidence or an explicit skip; fake adapters do not prove physical-device support.

Validation discovers and runs the repository's required maintainer checks. QA execution may build and run the product under test even though acceptance audit remains inspection-only. Existing full validation and pack build semantics remain separate obligations.

## Related work and references

SKILL-420 extends this horizontal phase with backend/web targets. It reuses these contracts and operator semantics rather than implementing another QA phase.

- [Appium WebDriverAgent](https://github.com/appium/WebDriverAgent)
- [Appium physical-device setup](https://github.com/appium/appium-xcuitest-driver/blob/master/docs/getting-started/device-setup.md)
- [Apple XCUIAutomation](https://developer.apple.com/documentation/xcuiautomation)
- [Apple command-line tools](https://developer.apple.com/documentation/xcode/xcode-command-line-tool-reference)
- [Maestro supported platforms](https://docs.maestro.dev/get-started/supported-platform)
