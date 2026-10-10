## [2026-10-10] SKILL-416 subtask 2 — Plugin accept live over Done
Areas: vscode-extension/application, vscode-extension/src/test
- StatusRefreshCoordinator.acceptsNewerStatus applies live-over-Done when both sequences are present and sequenceOrder < 0: incoming Active, Paused, or Blocked replaces displayed Done for the same repo, branch, and store when executionId differs.
- The existing missing-sequence-among-live accept is unchanged. Live-over-Done uses Active, Paused, or Blocked, not Failed or Stale. Cached Blocked is not the freeze-worthy terminal.
- Pattern: keep the same-execution terminal-regression reject; among two live snapshots, lower runSequence still loses. isStoreReplacement, correlation change, uncorroborated-idle, and hasLegacyExecutionCorrelation stay. reusable
- Coordinators do not rank workflows. Runtime selection remains the source of current work.
- Limitation: plugin-only still shows whatever work status returns. Runtime live-cohort selection is subtask 1. Assert repositoryIdentity only after narrowing to a kind that carries it; unavailable and incompatible omit it.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-14] SKILL-238 subtask 3 — IDE extension YAGNI parity
Areas: vscode-extension/{application,composition,infrastructure/cli,ui,test}, vscode-extension/README.md
- `CliGoalPauseRepository` / `CliGoalStopRepository` collapse into one `CliGoalMutationRepository`; the two ports and outcome types become one `GoalMutationRepository` with `GoalMutationOutcome`. TypeScript has no enum-with-fields, so the descriptor is a `GoalMutation` interface plus `GOAL_PAUSE_MUTATION` / `GOAL_STOP_MUTATION` consts — same parameterization as the IntelliJ enum, same preserved summary strings. Use this shape when mirroring a Kotlin enum-of-descriptors into TS. reusable
- `createGoalMutationRepositories` keeps one repository per dedicated `ProcessRunner` (status, pause, stop), so the SKILL-222 isolation guarantee is unchanged. `GoalMutationWiring.test.ts` asserts the composed pause repository emits `goal pause` and the composed stop repository `goal stop` — the guard against the copy-paste verb regression a single shared class makes possible.
- `StatusBarController` picks the repository by control kind and calls `requestMutation` once instead of duplicating the branch.
- Caller-less `defaultRefreshIntervalSeconds()` deleted from `PreferenceCachePort`; callers read `DEFAULT_REFRESH_INTERVAL_SECONDS` directly.
- The VS Code half of the `StatusClock.from` cut was already absent: `domain/StatusClock.ts` only ever exposed `system()` and `fixed()`, so no edit was made and none was invented. When mirroring an IntelliJ cut, verify the twin exists before claiming a removal. reusable
- Foreshadowing tool-window prose removed from README while the factual deferred entry stays.
- Limitation: build proof is `cd vscode-extension && npm test` (its test script runs `tsc -p ./` before mocha and doubles as the compile proof); no repo-root gate covers it. Older entries below still name the deleted pause/stop repositories; they record what was true then.
Feature flag: N/A
Acceptance criteria: 5/5 implemented (AC-005 build proof executes in the runtime build/validate phases)

## [2026-08-30] SKILL-222 subtask 2 — Goal controls and VSIX release packaging
Areas: vscode-extension/{application,composition,domain,infrastructure/cli,presentation,ui}, .github/workflows/extension-release.yml, RELEASING.md
- Extension mutates workflow state via `GoalStopRepository` / `GoalPauseRepository` issuing `goal stop` / `goal pause` for the snapshot issue key and canonical workspace root. Eligibility lives only in `GoalControlsPresentation` (active + feature-goal + issue key); details UI consumes descriptors and never re-derives them. No Resume kind. reusable
- Composition root wires three distinct `ProcessRunner` instances (status poll, stop, pause) so a mutation cannot coalesce into a poll result; `ProcessRunnerIsolationTest` locks this behaviourally. Failures collapse to bounded summaries — no stdout/stderr/paths in the UI. Pause disables when `pause_requested` is already set on the snapshot.
- Hosted-only `extension-release.yml` on `extension-v*` tags stages exactly `skill-bill-vscode-extension-<version>.vsix` plus a `.sha256` sidecar and fails closed before publishing a partial set; Marketplace publish stays deferred. README documents Install from VSIX parallel to IntelliJ Install from Disk.
- Pattern: mirror IntelliJ SKILL-168 control eligibility + runner isolation in TypeScript presentation/composition; keep release asset naming and checksum sidecar convention aligned with `plugin-release.yml`. reusable
- Limitation: Stop/Pause only — no resume, launch, retry, abandon, tool window, or Marketplace listing.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-08-30] SKILL-222 subtask 1 — VS Code status extension foundation
Areas: vscode-extension/{domain,application,infrastructure/cli,infrastructure/prefs,presentation,ui,composition}, docs
- Greenfield `vscode-extension/` TypeScript package mirrors IntelliJ layered ownership: domain outcomes, application ports, CLI process infra, presentation mapping, thin `extension.ts` / status-bar UI, composition root. Builds, tests, and packages with no `runtime-kotlin` or `intellij-plugin` Gradle edges.
- CLI resolution is settings override → PATH / `SKILL_BILL_BIN_DIR` / `~/.local/bin`; a misconfigured absolute override does not fall back. Status polls `work status --format json` with coalesced refresh, bounded timeouts, redacted failures, and cancel on deactivate / workspace dispose.
- Presentation covers active, idle, stale, blocked, failed, unavailable, incompatible — planning `n/m` and current-phase execution text when present — plus a details view; local elapsed ticker without a CLI poll per tick. Last-known display cache is stale-overlay only.
- Pattern: keep IDE status wire → domain → presentation → UI identical in intent to IntelliJ so future Stop/Pause (subtask 2) can copy the plugin control eligibility and runner-isolation patterns without inventing a second status model. reusable
- Limitation: read-only status only; no Stop/Pause, Marketplace publish, or shared Kotlin extraction.
Feature flag: N/A
Acceptance criteria: 5/5 implemented
