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

## Implementation Details

Paths are under `runtime-kotlin/` unless noted. `R-engine` is
`runtime-engine/src/main/kotlin/skillbill/engine`, and `R-domain` is
`runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime`. The owned paths above are where the
work lands. Repairs to tests, fixtures, architecture guards or wiring elsewhere that these changes
require are in scope.

### Decisions settled for this subtask

- **`STANDALONE` stays as a decode-only definition.** `SkeletonDefinition.STANDALONE` stays declared
  and stays in `entries`, so `byId("standalone")` still decodes stored execution plans (AC-003).
  Nothing opens a workflow with it, `forRun` and `admittedForRun` never return it, and it is not
  `standaloneInvocable`. Deleting it would break old rows, which the parent spec forbids.
- **Resume refusal goes in the engine, not the CLI**, so every resume entry point refuses: the
  `feature-task resume` control command, the deprecated resume alias, and any MCP or programmatic
  resume. A resume is admitted against `admittedForRun` (decision `fdca0392d130`, "admit by
  definition"), and `STANDALONE` is no longer in that set, so the admission refusal already fires.
  Give that refusal a dedicated message when the stored skeleton is `standalone`, for example:
  "The standalone feature-task run is retired. Run `skill-bill <KEY>` to plan and run this work."
  `<KEY>` is the row's issue key. Return this through the existing refusal or blocked result of the
  resume path. Do not add a new exception class (failure model: few custom exceptions).
- **Prose-only plan mode.** In `AgentPlanStrategy`, the prose-only mode is the branch where neither
  `suppressDecomposition` nor `specBundleRequired` is set: sections hold only `featureSpecDirective`,
  and the directive is the prose `DIRECTIVE`. Only `standalone` reaches that branch. After this
  subtask, a run that is not a goal continuation always gets the bundle sections and
  `BUNDLE_DIRECTIVE`. Delete `DIRECTIVE` if nothing reads it any more. If subtask 3 has not landed,
  the goal-continuation branch may still read it, so keep it for that branch only and leave that
  branch to subtask 3. If subtask 3 has landed (flags gone, bundle always on), check that no prose
  mode is left and change nothing else.
- **Goal-child launch tokens.** `FEATURE_TASK_COMMAND` and `RUN_SUBCOMMAND` in
  `runtime-contracts/.../FeatureTaskRuntimeGoalContinuationLaunchTokens.kt` are not owned here, and
  goal-child behaviour must not change.
  - Assumption for implement to confirm: goal children launch in process through the goal runner and
    `FeatureTaskRuntimeRunEntry`, not by spawning a `skill-bill feature-task run` subprocess.
  - Before deleting any command, grep main sources for both constants.
  - If their only uses are the removed CLI commands, delete the uses but keep the constants unless
    nothing references them.
  - If a live child-launch path still invokes `feature-task run` as a subprocess, do not delete
    that path. Report it as a blocker on AC-001 instead of rerouting goal children.
- **`./install.sh` is not part of this goal.** The spec asks for `./install.sh` after
  `skills/skill-bill/content.md` changes, but the goal-continuation rules forbid install commands
  inside a goal child. Implement edits `content.md` only. The operator runs `./install.sh` after
  the branch lands. Mention this in the PR description.

### Ordered tasks

1. **Domain skeleton admission** (AC-002, AC-003). File:
   `R-domain/model/skeleton/SkeletonDefinition.kt`.
   - `admittedForRun(goalContinuation)` returns `{GOAL_CHILD}` for goal continuation and `{PLAN}`
     otherwise.
   - Delete `forRun`. Its only main-source caller per the digest is
     `R-engine/featuretask/runner/FeatureTaskRuntimeRunnerPolicies.kt:29` (task 2).
   - Set `STANDALONE`'s `standaloneInvocable` to false if it is true, and keep its slots unchanged so
     old plans decode.
   - Update `runtime-domain/src/test/.../phase/task/SkeletonDefinitionTest.kt`. Remove assertions
     that `forRun(false)` or `admittedForRun(false)` yields `STANDALONE`. Add one assertion that
     neither admitted set contains `STANDALONE` while `byId("standalone")` still resolves it. The bug
     this catches: a later edit puts the retired skeleton back into admission, or drops it from
     `entries` and breaks decoding of old rows.
2. **Runner skeleton resolution** (AC-002). Files: `R-engine/featuretask/runner/` (start with
   `FeatureTaskRuntimeRunnerPolicies.kt`).
   - Resolve the run's skeleton from the explicit `FeatureTaskRuntimeRunInput.definition`, else
     `GOAL_CHILD` for a goal continuation.
   - A non-goal-continuation run with no explicit definition is a defect once the commands are gone,
     so fail it with `require` and a message saying the standalone skeleton is retired.
   - Check the other runner files that branch on goal continuation (`...ExecutePrepared.kt:84`,
     `...Execute.kt:89`). Keep their execution behaviour and remove only any implicit `STANDALONE`
     fallback.
3. **Continuation lookup and resume refusal** (AC-003, AC-004). Files:
   `R-engine/featuretask/model/continuation/` (`FeatureTaskContinuationLookupQuery`) and the resume
   admission point (the `admittedForRun` caller on resume, likely in `featuretask/lifecycle/continuation/`
   or `featuretask/runner/`).
   - Remove the `admittedDefinition = SkeletonDefinition.STANDALONE` default and make the parameter
     explicit.
   - `FeatureTaskContinuationLookupService.kt:180` already passes `PLAN`.
   - Callers that used the default to find a key's feature-task row for lookup, status, list or
     abandon pass `SkeletonDefinition.STANDALONE` explicitly. Reading a retired row is allowed, and
     this keeps those rows visible (AC-003).
   - Resume of a matched row whose stored skeleton is `standalone` returns the refusal from the
     settled decisions above (AC-004).
   - Abandon must not go through resume admission. Confirm that it writes the abandoned state with
     no skeleton admission check, and remove any admission check that would block a `standalone`
     row.
4. **Strategy bindings** (AC-002). Files:
   `R-engine/featuretask/slot/skeleton/SkeletonStrategyBindings.kt` and
   `runtime-core/.../di/featuretask/RuntimeFeatureTaskSlotProvides.kt`.
   - Remove the `STANDALONE` entry from `bindings`.
   - Assumption for implement to confirm: status, list and abandon read a stored execution plan from
     its recorded strategy ids (`mapRecordedExecutionPlan`, `FeatureTaskRuntimeExecutionPlanResolver`)
     and do not need a live binding.
   - If reading a stored `standalone` plan does need a binding, keep the `STANDALONE` entry on
     `sharedBindings()` as decode-only, and note it in the decisions entry. The task 7 test settles
     this.
   - Update `RuntimeFeatureTaskSlotProvidesTest`, `PhaseStrategyCompositionTest`,
     `PhaseModelProfileSelectionTest` and `FeatureTaskRuntimeExecutionPlanResolverTest` wherever
     they enumerate `STANDALONE` bindings.
5. **Remove the prose-only plan mode** (AC-005). File:
   `R-engine/featuretask/slot/plan/AgentPlanStrategy.kt`, following the settled decision above.
   - Keep `PlanStepHooks`, `PlanResumeRules`, `specBundleRequirement`, `MANIFEST_TEMPLATE` and
     `BUNDLE_DIRECTIVE` unchanged.
   - Do not remove the `suppressDecomposition`, `specBundleRequired` or `specRewritable` prompt
     inputs. Subtask 3 owns them.
   - In `FeatureTaskRuntimePhasePromptComposeContentTest`, delete the tests that assert the prose
     plan prompt. Keep the bundle-prompt and goal-continuation assertions.
6. **Remove the CLI run commands** (AC-001). Files: `runtime-cli/.../cli/featuretask/` and
   `runtime-cli/.../core/CliCommandGroups.kt`.
   - Delete `FeatureTaskRuntimeRunCommand` (`FeatureTaskRuntimeCliCommands.kt:116`),
     `FeatureTaskRuntimeExplicitRunCommand` (line 148) and `FeatureTaskRuntimeDeprecatedRunCommand`
     (`FeatureTaskRuntimeAliasCliCommands.kt:14`), with their registration in the command groups.
   - Keep the control commands (Lookup, Status, Resume, Abandon, RetryBlocked, RepairIdentity) and
     `FeatureTaskRuntimeDeprecatedResumeCommand`. Resume now refuses `standalone` rows through the
     engine.
   - Delete CLI tests that only exercise the removed commands. Update any command-list or help
     snapshot test.
   - Follow the token decision above for `FEATURE_TASK_COMMAND` and `RUN_SUBCOMMAND`.
7. **Move shared run-loop tests off `standalone`, and add the retired-row test** (AC-009,
   AC-003, AC-004, AC-006).
   - Grep test sources and fixtures under `runtime-kotlin` for `SkeletonDefinition.STANDALONE` and
     for the `"standalone"` skeleton id. Do not change route-scope uses of `"standalone"`.
   - Tests of execution-phase behaviour (implement through monitor, run-loop blocking, checkpoints,
     review completion) move to `GOAL_CHILD`, with a goal-continuation request if the fixture needs
     one.
   - Tests of preplan or plan run-loop behaviour move to `PLAN`.
   - Fixtures to check first: `runtime-engine/src/testFixtures/.../slot/TestExecutionPlan.kt`,
     `TestPhaseStrategies.kt`, and the slot baseline tests with
     `runtime-engine/src/test/resources/featuretask/slotbaseline/`. Drop any `standalone` baseline,
     and update its README if it lists that baseline.
   - Delete tests whose only subject is standalone-only behaviour, such as the prose plan or a
     standalone run completing at monitor. Do not re-point them.
   - Add one test class, for example `RetiredStandaloneWorkflowTest`, beside the continuation lookup
     tests in `runtime-engine/src/test/.../featuretask/`. It uses the existing workflow-store test
     fixture to store a feature-task row whose recorded execution plan names skeleton `standalone`
     and records the pre-change step ids. It asserts that:
     - the row decodes and appears in status and list results (AC-003);
     - abandon succeeds and the row reads back abandoned (AC-003);
     - resume returns the refusal, and its message contains `skill-bill <KEY>` (AC-004).
   - The bugs it catches: dropping `STANDALONE` from decode breaks old rows, and a retired skeleton
     resumes silently. Use explicit fixture data. Use `relaxUnitFun = true` for any mock, never
     `relaxed = true`.
   - AC-006 needs no new test. Audit confirms that `FeatureTaskRouteScope.STANDALONE` is unchanged
     and that the plan run path still uses it.
8. **Docs** (AC-007).
   - Describe the parent spec's target model: one `plan` skeleton (`intake`, `preplan`, `plan` with
     the steps `plan` and `plan_subtasks`). `phase plan` stops after planning. The full run plans
     first for new work, then executes the bundle. Goal children hydrate from the linked plan
     workflow, and legacy goals hydrate from the read-only `goal_*` tables. `feature-task run` and
     the `standalone` skeleton are retired.
   - `skills/skill-bill/content.md`: in the `phase:plan` paragraph, the full run uses the same
     planning skeleton for new work, not only to finish an incomplete plan. Remove any
     `feature-task run` mention.
   - `runtime-kotlin/ARCHITECTURE.md`: the planning, goal-planning and child-hydration paragraphs.
   - `README.md`, `docs/getting-started.md` and `docs/runtime-command-guidance.md`: drop
     `feature-task run` and describe planning as intake, preplan, plan. If the guidance has a
     retired-names section, add `feature-task run` there and point to `skill-bill <KEY>`.
   - Grep the remaining current-behaviour docs for `feature-task run` and `standalone` skeleton
     mentions, and update the matches: `docs/capabilities.md`,
     `docs/model-specific-phase-strategies.md`, `AGENTS.md` and
     `orchestration/workflow-contract/PLAYBOOK.md`.
   - Leave dated entries in the existing `agent/history.md` and `agent/decisions.md` files, and
     everything under `.feature-specs/`, as they are. They are records, not current docs.
   - When another subtask already rewrote one of these paragraphs, keep its text and fit this change
     into it.
9. **Decision record** (AC-008). Add one entry to `runtime-kotlin/agent/decisions.md` in the file's
   existing entry format. It records:
   - that the `standalone` skeleton and the `feature-task run` commands are retired because the full
     run covers single-feature work with a one-subtask bundle;
   - that stored `standalone` rows stay decodable, visible and abandonable, with no migration;
   - that resume is refused with a message naming `skill-bill <KEY>`;
   - that the `standalone` route scope stays for plan workflows;
   - that the prose-only plan mode is gone;
   - whether `STANDALONE` kept a decode-only binding (from task 4).

### Constraints

- Authored Kotlin follows `AGENTS.md`: no line comments, KDoc only on interfaces.
- No schema change and no migration. Stored rows are read as they are.
- Do not change `goal-child` or `plan` behaviour, the `FeatureTaskRouteScope` values, or the
  execution identity contract.
- Implement and audit do not build or run tests. The build phase and the validate phase
  (`./gradlew check`, which includes the architecture repoTests and agent-config validation) own
  that.
- Audit check for AC-002: after the change, `SkeletonDefinition.STANDALONE` appears in main sources
  only in four places:
  - its declaration and `entries`;
  - the resume-refusal special case;
  - explicit read-only lookup callers;
  - a decode-only binding, if task 4 kept one.
