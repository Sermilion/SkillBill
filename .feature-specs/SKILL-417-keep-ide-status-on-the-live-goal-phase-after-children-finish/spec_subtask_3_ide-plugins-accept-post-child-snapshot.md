# Subtask 3. Both IDE plugins accept the post-child parent/goal snapshot

## Scope

After ide-status selects one live parent/goal monitor snapshot, both IDE plugins must accept it even when they already displayed a child implement snapshot at a higher `run_sequence`. The plugin never sees the child become Done if selection skips the terminal child and returns the lower-sequence parent/goal instead.

Depends on subtask 2 because each poll is one selected snapshot, not a pair of child-plus-parent rows.

Files:

- `intellij-plugin/src/main/kotlin/dev/skillbill/intellij/application/StatusRefreshCoordinator.kt` — `acceptsNewerStatus`
- `vscode-extension/src/application/StatusRefreshCoordinator.ts` — `acceptsNewerStatus`

Change both in lockstep. They share the identity/ordering contract: `repositoryIdentity`, `branchCorrelation`, `statusStoreId`, then `runSequence` via decimal-string compare.

## Implementation Details

Today a lower incoming `runSequence` is allowed only through `isLiveOverDone`, which requires current to be Done (IntelliJ `SkillBillStatusOutcome.Done`; VS Code `kind === "done"`) and incoming to be Active/Paused/Blocked with a different `executionId`. That is why a held Active implement snapshot at `run_sequence` N freezes against a later Active/Paused monitor snapshot at `run_sequence` less than N.

VS Code also returns false when current is terminal and incoming is not, at equal sequence. Keep that same-execution resurrection guard.

Do not loosen the existing same-class live-versus-live case:

- IntelliJ: `an older live execution does not replace a newer live execution`
- VS Code: `rejects an older live snapshot when a newer live snapshot is displayed`

Active seq 11 must not be replaced by Active seq 10 when both are live standalone-style work.

The new case: first accept child Active implement at sequence N, then accept incoming live parent/goal monitor at sequence less than N with a different `executionId` / workflow, rather than keeping Implementation.

Keep `a lower-sequence live execution replaces a displayed done snapshot` and the no-`runSequence` variant. Keep `the same execution does not regress from done to active` / `rejects resurrection of the same execution from done to active`.

Do not accept every lower `runSequence` across execution ids. The allowed cross-execution downgrade is the post-child parent/goal live snapshot replacing a now-stale child snapshot after that child is no longer the selected live work, not an older live standalone execution replacing a newer live standalone execution.

## Acceptance Criteria

1. IntelliJ `StatusRefreshCoordinatorTest` includes a 0AC-46 polling case: after accepting child Active implement at `run_sequence` N, an incoming live parent/goal monitor snapshot at `run_sequence` less than N with a different `executionId` is accepted.
2. VS Code `StatusRefreshCoordinator.test.ts` includes the same 0AC-46 polling case and accepts that incoming monitor snapshot.
3. The existing same-class live-versus-live tests still reject Active seq 10 replacing Active seq 11 when both are live standalone-style work.
4. Existing Done-replacement and same-execution resurrection guards remain: a lower-sequence live execution still replaces a displayed Done snapshot; the same execution still does not regress from Done to Active. VS Code still rejects same-sequence resurrection from terminal to live.

## Test Obligations

Each obligation names the realistic bug it would catch while the rest of the suite stayed green.

1. One new IntelliJ `StatusRefreshCoordinatorTest` 0AC-46 polling case. Bug: after accepting child Active implement at sequence N, `acceptsNewerStatus` rejects the later live parent/goal monitor at sequence less than N because current is still Active, so the plugin stays on Implementation.
2. One matching VS Code `StatusRefreshCoordinator.test.ts` 0AC-46 polling case. Bug: the TypeScript copy keeps the same freeze after the Kotlin copy is fixed, so VS Code still shows Implementation.

Extend, do not replace, the named live-versus-live, Done-replacement, and resurrection tests in both files.

Do not run these tests in implement. Validate owns execution.

## Non-Goals

- Changing ide-status selection so the plugin sees the child become Done (subtask 2 owns the single selected snapshot).
- Loosening same-class live-versus-live ordering.
- Bumping `ide_status_workflow_execution.status_revision` so the incoming snapshot outranks on revision instead of acceptance rules.
- Changing phase order, review policy, or commit-before-review.

## Dependency Notes

Depends on subtask 2. The incoming snapshot this subtask must accept is the live parent/goal monitor snapshot subtask 2 selects. Without that snapshot, plugin acceptance cannot display monitor.

## Validation Strategy

Validate phase runs the pack gate / `./gradlew check`, including both plugin test files. Build phase owns compile. After this subtask the 0AC-46 reproduction in the tree is: parent feature-task paused at plan with implement pending; child completed at commit_push; goal runner `current_step` monitor; ide-status and both IDE coordinators accept monitor and not implement or plan; `latest_liveness_signal` agrees with monitor; an actual operator pause still shows Paused.

## Next path

```bash
skill-bill goal SKILL-417
```
