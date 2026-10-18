# SKILL-425 - validate-full-gate-on-every-goal-subtask

## Mode

decomposed

## Intended Outcome

SKILL-425 Validate full gate on every goal subtask

Every non-skipped decomposed goal child must run the full project validation gate.

Problem
Goal children currently stamp BUILD for every subtask except the last non-skipped one, which alone gets VALIDATE. Owner: GoalRunnerQualityGateSelectionResolver in runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/goalrunner/GoalRunnerPolicy.kt. Tests lock this as `build, build, validate` for a three-child goal (GoalRunnerQualityGateSelectionResolverTest, GoalRunnerQualityGateSelectionTest). BUILD is compile-only pack build_command (Kotlin: ./gradlew compileKotlin). It does not run ./gradlew check, detekt, tests, skill-bill validate, or other full-project checks. Completed earlier subtasks therefore record no boolean validation result (goal status validation_integrity: "Completed subtask has no boolean validation result"). On SKILL-422 this deferred check/detekt debt into the last subtask validate session, which then tried to repair accumulated production TooManyFunctions/LongMethod findings instead of finishing that subtask's own gate.

Outcome
Stamp FeatureTaskRuntimeQualityGateSelection.VALIDATE on every launched non-skipped goal child, including first, middle, and last. A three-subtask goal is validate, validate, validate. A one-subtask goal stays validate. Skipped subtasks stay skipped and do not consume a gate. Collapse the last-non-skipped promotion rule; it is unnecessary once every non-skipped child is VALIDATE.

Acceptance criteria
- GoalRunnerQualityGateSelectionResolver.resolve returns VALIDATE for every non-skipped subtask id in a multi-child manifest.
- Goal child launches persist quality_gate_selection=validate and execute the validate slot (review -> validate -> write_history), not review -> build -> write_history.
- Tests that currently expect build, build, validate for three children expect validate for each child. Remaining non-skipped children after a skipped last child still get VALIDATE.
- BUILD remains a valid explicit selection for CLI/env --quality-gate-selection build and any non-goal-child path that already stamps it. Do not delete PackBuildStrategy or change compile-only BUILD semantics.
- SKILL-415's unrouted generic BUILD to agent-validate fallback stays for explicit BUILD requests; it is not the mechanism that makes every goal child validate.
- Standalone phase validation and standalone feature-task VALIDATE defaults stay unchanged.
- docs/runtime-command-guidance.md and any authoring briefing that says only the last goal child validates must describe per-subtask full validation. implement and implement_fix still must not run check; validate still owns the full gate, now on every subtask.
- New multi-subtask runs record a boolean validation result on each completed VALIDATE child so validation_integrity is satisfied.
- Do not rewrite in-flight SKILL-422 child rows. Selection applies to new launches and to resume of a child that has not yet started its quality gate. A live child already recorded as BUILD finishes that recorded plan; do not force a mid-session BUILD attempt to become VALIDATE.
- Do not weaken detekt, baselines, guards, or skip tests to make per-subtask validate cheaper.

Non-goals
- Not a SKILL-422 sqlite-maintenance repair.
- Not changing review, audit, commit_push, or parent CI monitor.
- Not making BUILD run the full check suite.
- Not retroactively revalidating historical completed BUILD children.

Affected areas
GoalRunnerQualityGateSelectionResolver and its tests, goal-child continuation/slot-baseline fixtures that stamp BUILD for non-last children, runtime-command-guidance.md, and feature-task skeleton slot order for goal children.

## Overview

SKILL-425 Validate full gate on every goal subtask

Every non-skipped decomposed goal child must run the full project validation gate.

Problem
Goal children currently stamp BUILD for every subtask except the last non-skipped one, which alone gets VALIDATE. Owner: GoalRunnerQualityGateSelectionResolver in runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/goalrunner/GoalRunnerPolicy.kt. Tests lock this as `build, build, validate` for a three-child goal (GoalRunnerQualityGateSelectionResolverTest, GoalRunnerQualityGateSelectionTest). BUILD is compile-only pack build_command (Kotlin: ./gradlew compileKotlin). It does not run ./gradlew check, detekt, tests, skill-bill validate, or other full-project checks. Completed earlier subtasks therefore record no boolean validation result (goal status validation_integrity: "Completed subtask has no boolean validation result"). On SKILL-422 this deferred check/detekt debt into the last subtask validate session, which then tried to repair accumulated production TooManyFunctions/LongMethod findings instead of finishing that subtask's own gate.

Outcome
Stamp FeatureTaskRuntimeQualityGateSelection.VALIDATE on every launched non-skipped goal child, including first, middle, and last. A three-subtask goal is validate, validate, validate. A one-subtask goal stays validate. Skipped subtasks stay skipped and do not consume a gate. Collapse the last-non-skipped promotion rule; it is unnecessary once every non-skipped child is VALIDATE.

Acceptance criteria
- GoalRunnerQualityGateSelectionResolver.resolve returns VALIDATE for every non-skipped subtask id in a multi-child manifest.
- Goal child launches persist quality_gate_selection=validate and execute the validate slot (review -> validate -> write_history), not review -> build -> write_history.
- Tests that currently expect build, build, validate for three children expect validate for each child. Remaining non-skipped children after a skipped last child still get VALIDATE.
- BUILD remains a valid explicit selection for CLI/env --quality-gate-selection build and any non-goal-child path that already stamps it. Do not delete PackBuildStrategy or change compile-only BUILD semantics.
- SKILL-415's unrouted generic BUILD to agent-validate fallback stays for explicit BUILD requests; it is not the mechanism that makes every goal child validate.
- Standalone phase validation and standalone feature-task VALIDATE defaults stay unchanged.
- docs/runtime-command-guidance.md and any authoring briefing that says only the last goal child validates must describe per-subtask full validation. implement and implement_fix still must not run check; validate still owns the full gate, now on every subtask.
- New multi-subtask runs record a boolean validation result on each completed VALIDATE child so validation_integrity is satisfied.
- Do not rewrite in-flight SKILL-422 child rows. Selection applies to new launches and to resume of a child that has not yet started its quality gate. A live child already recorded as BUILD finishes that recorded plan; do not force a mid-session BUILD attempt to become VALIDATE.
- Do not weaken detekt, baselines, guards, or skip tests to make per-subtask validate cheaper.

Non-goals
- Not a SKILL-422 sqlite-maintenance repair.
- Not changing review, audit, commit_push, or parent CI monitor.
- Not making BUILD run the full check suite.
- Not retroactively revalidating historical completed BUILD children.

Affected areas
GoalRunnerQualityGateSelectionResolver and its tests, goal-child continuation/slot-baseline fixtures that stamp BUILD for non-last children, runtime-command-guidance.md, and feature-task skeleton slot order for goal children.

## Acceptance Criteria

1. SKILL-425 Validate full gate on every goal subtask Every non-skipped decomposed goal child must run the full project validation gate. Problem Goal children currently stamp BUILD for every subtask except the last non-skipped one, which alone gets VALIDATE. Owner: GoalRunnerQualityGateSelectionResolver in runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/goalrunner/GoalRunnerPolicy.kt. Tests lock this as `build, build, validate` for a three-child goal (GoalRunnerQualityGateSelectionResolverTest, GoalRunnerQualityGateSelectionTest). BUILD is compile-only pack build_command (Kotlin: ./gradlew compileKotlin). It does not run ./gradlew check, detekt, tests, skill-bill validate, or other full-project checks. Completed earlier subtasks therefore record no boolean validation result (goal status validation_integrity: "Completed subtask has no boolean validation result"). On SKILL-422 this deferred check/detekt debt into the last subtask validate session, which then tried to repair accumulated production TooManyFunctions/LongMethod findings instead of finishing that subtask's own gate. Outcome Stamp FeatureTaskRuntimeQualityGateSelection.VALIDATE on every launched non-skipped goal child, including first, middle, and last. A three-subtask goal is validate, validate, validate. A one-subtask goal stays validate. Skipped subtasks stay skipped and do not consume a gate. Collapse the last-non-skipped promotion rule; it is unnecessary once every non-skipped child is VALIDATE. Acceptance criteria - GoalRunnerQualityGateSelectionResolver.resolve returns VALIDATE for every non-skipped subtask id in a multi-child manifest. - Goal child launches persist quality_gate_selection=validate and execute the validate slot (review -> validate -> write_history), not review -> build -> write_history. - Tests that currently expect build, build, validate for three children expect validate for each child. Remaining non-skipped children after a skipped last child still get VALIDATE. - BUILD remains a valid explicit selection for CLI/env --quality-gate-selection build and any non-goal-child path that already stamps it. Do not delete PackBuildStrategy or change compile-only BUILD semantics. - SKILL-415's unrouted generic BUILD to agent-validate fallback stays for explicit BUILD requests; it is not the mechanism that makes every goal child validate. - Standalone phase validation and standalone feature-task VALIDATE defaults stay unchanged. - docs/runtime-command-guidance.md and any authoring briefing that says only the last goal child validates must describe per-subtask full validation. implement and implement_fix still must not run check; validate still owns the full gate, now on every subtask. - New multi-subtask runs record a boolean validation result on each completed VALIDATE child so validation_integrity is satisfied. - Do not rewrite in-flight SKILL-422 child rows. Selection applies to new launches and to resume of a child that has not yet started its quality gate. A live child already recorded as BUILD finishes that recorded plan; do not force a mid-session BUILD attempt to become VALIDATE. - Do not weaken detekt, baselines, guards, or skip tests to make per-subtask validate cheaper. Non-goals - Not a SKILL-422 sqlite-maintenance repair. - Not changing review, audit, commit_push, or parent CI monitor. - Not making BUILD run the full check suite. - Not retroactively revalidating historical completed BUILD children. Affected areas GoalRunnerQualityGateSelectionResolver and its tests, goal-child continuation/slot-baseline fixtures that stamp BUILD for non-last children, runtime-command-guidance.md, and feature-task skeleton slot order for goal children.

## Constraints

- Supplied requirements are authoritative and need no tracker lookup. Locally allocated issue keys do not require a tracker connection. Only an explicit unresolved tracker reference without requirements needs lookup through its connected tracker before planning. Use the returned requirements, not the URL title. If that lookup fails, block with the returned reason before implementation; never infer or substitute requirements.

## Non-Goals

- None

## Validation Strategy

Run the repository's required checks and verify every supplied acceptance criterion.
