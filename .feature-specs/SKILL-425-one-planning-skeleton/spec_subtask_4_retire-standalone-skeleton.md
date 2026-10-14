# SKILL-425 Subtask 4 - Retire the standalone skeleton and update docs

Parent spec: [spec.md](./spec.md). Read its Model, Decisions and Execution rule first.
Issue key: SKILL-425

## Scope

Retire the `standalone` skeleton. It is a single feature run without a goal, opened by
`skill-bill feature-task run`, and its `plan` slot uses a prose-only plan mode. The full run covers
that case: a small feature plans into a one-subtask bundle and runs to a pull request.

- Remove `FeatureTaskRuntimeRunCommand`, `FeatureTaskRuntimeExplicitRunCommand` and the alias
  `FeatureTaskRuntimeDeprecatedRunCommand`, with their command-group wiring.
- No code path opens a new workflow with the `standalone` skeleton. `SkeletonDefinition.forRun`,
  `admittedForRun` and `FeatureTaskContinuationLookupQuery.admittedDefinition` stop defaulting to it.
- Existing `standalone` rows stay readable. `SkeletonDefinition.byId("standalone")` still decodes
  their stored execution plans, and status, list and abandon work on them. Resuming one is refused
  with a message naming `skill-bill <KEY>`.
- Remove `AgentPlanStrategy`'s prose `DIRECTIVE` mode once no executed skeleton uses it.
- Keep the route scope `FeatureTaskRouteScope.STANDALONE` ("standalone"). Plan workflows use it.

Update the docs so they describe one planning skeleton:

- `skills/skill-bill/content.md`: the `phase:plan` paragraph says the full run uses the same
  planning skeleton for new work, not only to finish an incomplete plan. Run `./install.sh` after.
- `runtime-kotlin/ARCHITECTURE.md`: the planning, goal planning and child hydration paragraphs.
- `README.md`, `docs/getting-started.md`, `docs/runtime-command-guidance.md`: drop
  `feature-task run`, and describe planning as intake, preplan, plan.
- `runtime-kotlin/agent/decisions.md`: one entry for retiring the standalone skeleton.

Owned paths (elided segments are `...`):

- `runtime-cli/.../featuretask/`, `runtime-cli/.../core/CliCommandGroups.kt`
- `runtime-domain/.../taskruntime/model/skeleton/SkeletonDefinition.kt`
- `runtime-engine/.../featuretask/model/continuation/`, `featuretask/runner/`, `featuretask/slot/skeleton/`, `featuretask/slot/plan/AgentPlanStrategy.kt`
- `skills/skill-bill/content.md`, `runtime-kotlin/ARCHITECTURE.md`, `README.md`, `docs/`

## Acceptance Criteria

1. `skill-bill feature-task run` and its explicit and deprecated forms no longer exist.
2. No code path opens a new workflow with the `standalone` skeleton.
3. An existing `standalone` row still decodes, shows in status and list, and can be abandoned.
4. Resuming an existing `standalone` row is refused with a message naming `skill-bill <KEY>`.
5. `AgentPlanStrategy` has no prose-only plan mode.
6. The route scope `standalone` still admits plan workflows.
7. The dispatcher skill text, `ARCHITECTURE.md`, `README.md` and the docs describe one planning skeleton and no longer mention `feature-task run`.
8. `runtime-kotlin/agent/decisions.md` records the retirement.
9. Tests that used the `standalone` skeleton to exercise shared run-loop behaviour use `goal-child` or `plan` instead, and a test covers criteria 3 and 4 with a stored `standalone` row.

## Non-Goals

- No removal of the `standalone` route scope.
- No change to `goal-child` or `plan` behaviour.
- No migration of stored `standalone` rows.

## Dependency Notes

None. This subtask runs on the current tree. If `AgentPlanStrategy` still has other modes that
another subtask owns, remove only the prose-only mode used by `standalone`. When another subtask
already edited a doc paragraph named here, keep its change and make this one fit.

## Validation Strategy

- The build phase runs the pack build command.
- The validate phase runs the pack validation gate and `./gradlew check`.
- Implement and audit do not run builds or tests.

## Next Path

Complete the goal and prepare its pull request.
