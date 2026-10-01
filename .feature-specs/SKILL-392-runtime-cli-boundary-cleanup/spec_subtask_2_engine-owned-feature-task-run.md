# SKILL-392 Subtask 2 - engine-owned feature-task run

Parent spec: [.feature-specs/SKILL-392-runtime-cli-boundary-cleanup/spec.md](spec.md)
Issue key: SKILL-392

## Scope

Covers F-001, F-011 and F-012 from [investigation.md](investigation.md). Touches:
- runtime-engine `featuretask.runner` and `featuretask.lifecycle.execution`;
- runtime-cli `featuretask`, `model` and `goal.core`;
- runtime-ports `system/HostPlatformPort.kt` and runtime-infra/host `JdkHostPlatformPort`;
- the three handwritten `HostPlatformPort` test substitutes;
- runtime-core `RuntimeEngineInboundApiTest` pins;
- one test moved from runtime-cli to runtime-core.

Changes:
- **F-001.** Move the feature-task execution sequence into one `@Inject` entry in runtime-engine: open or reuse the workflow id with its execution plan, derive the identity once, own the worker lease, read the run invariants, assemble the request and run it. `FeatureTaskRuntimeExecutionEntry.admit` uses the same identity derivation. The CLI keeps option parsing, preparation, `UsageError` texts, presentation, exit codes and the telemetry drain. The retained run-override seam is retyped to the new entry's input and output.
- **F-011.** Read the goal-run provenance java command through `HostPlatformPort`.
- **F-012.** Move `IdeStatusReadSnapshotConcurrencyTest` into runtime-core `src/test` under `skillbill.di.*`.

## Acceptance Criteria

1. runtime-cli main references none of `FeatureTaskRuntimeRunner`, `FeatureTaskRuntimeWorkerCoordinator`, `FeatureTaskRuntimeExecutionPlanResolver`, `FeatureTaskRuntimeExecutionPlanCreationRequest` or `FeatureTaskRuntimeRunInvariantsSource`. It does not construct `FeatureTaskExecutionIdentity` and does not call `WorkflowService.openFeatureTask` for the run, explicit-run, resume or deprecated-alias paths. Those commands call one runtime-engine entry that returns `FeatureTaskRuntimeRunReport`.
2. runtime-engine main has one function that derives the expected `FeatureTaskExecutionIdentity` for a feature-task run from the workflow id, issue key, repository root, spec path and route scope. Both the new entry and `FeatureTaskRuntimeExecutionEntry.admit` use it. It normalizes the issue key through `FeatureTaskExecutionIdentityPolicy`, and for existing standalone and goal-child workflows it produces the same governed spec path and repository identity strings that are persisted today.
3. The new entry acquires the feature-task worker lease before running and releases it on every exit, including failure and cancellation. The existing worker-coordinator, takeover-fencing and admission suites pass unchanged.
4. Running, resuming and goal-child continuation produce the same workflow rows, the same `FeatureTaskRuntimeRunReport`, and the same stdout, stderr, exit codes and payloads as at `ae23f4f28`. An invalid governed spec path and a workflow-open failure keep their current `UsageError` texts.
5. `RuntimeEngineInboundApiTest.PINNED_ENGINE_INBOUND_API_TYPES` adds the new entry and its input model. It drops every engine type that no runtime-application, runtime-cli or runtime-mcp main file references afterwards.
6. `CliRuntimeContext` and `CliRunInputs` still offer one run-override seam, typed as the new entry's input to `FeatureTaskRuntimeRunReport`. `FeatureTaskRuntimeGoalContinuationProtocolTest` still asserts that contract-built continuation argv populates every goal-continuation field.
7. runtime-cli main contains no `ProcessHandle` reference. `HostPlatformPort` supplies the java command, `JdkHostPlatformPort` implements it, and every handwritten substitute compiles.
8. `IdeStatusReadSnapshotConcurrencyTest` lives in runtime-core `src/test` under a `skillbill.di.*` package, and its assertions are unchanged. runtime-cli tests contain no test that exercises only engine services without `CliRuntime` or a CLI declaration.
9. The new engine entry has at most 12 constructor parameters, all private. Its package stays within 12 sibling files, and the engine model package within 20. No new architecture-test class, baseline entry, detekt suppression or module is added.

## Non-Goals

- Moving run preparation or resume verification out of the CLI.
- Changing the goal-child persisted identity derivation in `GoalRunnerSubtaskLaunchPrepare`.
- Engine-wide issue-key normalization, `FeatureTaskRuntimeRunner` getter visibility, or preflight add-on resolution (recorded follow-ups).
- Adding an interface in front of the new entry.

## Dependency Notes

Depends on: subtask 1. It waits for no other issue: if SKILL-387 or SKILL-389 (runtime-core) has landed, rebase onto it first; otherwise implement against the current tree, and whichever lands second keeps both edits. Subtask 1 removes `VerifyRuntimeResumeArgs` and the port field from `CliRunInputs`, which this subtask's CLI edits build on.

SKILL-393 (runtime-ports) also edits `PINNED_ENGINE_INBOUND_API_TYPES` and deletes the engine `work.model` typealiases. Whichever lands second keeps both pin edits. If SKILL-393 lands first, the moved `IdeStatusReadSnapshotConcurrencyTest` imports `IdeStatusProblemCode` from `skillbill.ports.idestatus.model`.

SKILL-387 has no shared symbol but rewrites engine phase-output admission. Whichever lands second rechecks `FeatureTaskRuntimeRunner.run`, `FeatureTaskRuntimeRunRequest` and `FeatureTaskRuntimeExecutionEntry` against the other's change. Before implementing, recheck `.feature-specs/` for runtime-engine `featuretask.runner` or runtime-ports `HostPlatformPort` bundles from parallel sessions.

## Validation Strategy

- **Build**: compiles runtime-ports, runtime-infra/host, runtime-engine, runtime-core and runtime-cli, proving kotlin-inject resolution of the new entry.
- **Validate**: runs the full project checks, including:
  - `RuntimeEngineInboundApiTest`;
  - the engine worker-coordinator, fencing and admission suites (identity and lease evidence);
  - the runtime-cli feature-task and goal suites, including `FeatureTaskRuntimeGoalContinuationProtocolTest` and `CliGoalRuntimeTest`;
  - the moved runtime-core IdeStatus test;
  - the runtime-mcp parity tests.

## Next Path

skill-bill goal SKILL-392

## Spec Path

.feature-specs/SKILL-392-runtime-cli-boundary-cleanup/spec_subtask_2_engine-owned-feature-task-run.md
