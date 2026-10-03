# SKILL-399 Subtask 2 - scaffold-and-review-context

Parent spec: [.feature-specs/SKILL-399-shell-content-error-codes/spec.md](./spec.md)
Issue key: SKILL-399

## Scope

Convert every class in `ScaffoldShellContentErrors.kt` (10 classes) and `ReviewContextShellContentErrors.kt` (10 classes) to coded failures.

- **Scaffold.** `ScaffoldError` is `open`, and it and its 9 subclasses extend `SkillBillRuntimeException` directly, not `ShellContentContractException`. That means `ScaffoldFailureCode` is never part of `isShellContentContractFailure()`.
  - `ScaffoldFailureCode` entries: payload version mismatch, invalid payload, retired kind, unknown skill kind, unknown pre-shell family, skill already exists.
  - Family entry: `ScaffoldError` direct throws, missing platform pack, missing supporting file target, rollback.
  - The CLI scaffold arm (`completeScaffoldError`) keeps the same text and exit code for each former class.
- **ReviewContext.** `ReviewContextFailureCode` entries: identity mismatch, review context schema, rule text too long, title too long, hunk locator missing, hunk locator unreadable, hunk integrity, spec intent unreadable, aggregation integrity. Family entry: invalid skill content identity.
  - Keep `REVIEW_HUNK_EVIDENCE_INTEGRITY` public only if code outside the file references it; otherwise make it private.
  - Known `is` site: `CodeReviewStep.kt:391-393`. Its class-name reason at `CodeReviewStep.kt:402` uses `failureCodeLabel()`.

## Acceptance Criteria

1. The two files declare no class; they hold only `ScaffoldFailureCode`, `ReviewContextFailureCode` and message functions.
2. Each former failure throws `SkillBillRuntimeException` with an entry of one of the two enums, or is a `require`/`check`/`error()` defect.
3. No main code reads a typed property from a caught failure of these areas.
4. Scaffold CLI output (stdout, stderr, exit code) is unchanged for every former scaffold class.

## Non-Goals

The other shell-content areas. SKILL-398 subtask 6's conversion of scaffold payload input sources: if it has landed, keep its edits and point them at the scaffold payload code.

## Test obligations

None beyond the converted assertions.

## Shared Rules

Apply `spec.md` "Conversion rules", "Shared transition pieces" and "Execution Rule". If a shared transition piece is missing, add it as written there. If any main `catch`, `is` or `as?` on `ShellContentContractException` lacks the `isShellContentContractFailure()` guard, add the guard. Add each area enum this subtask creates to `isShellContentContractFailure()`, unless it is `ScaffoldFailureCode`.

After this subtask's edits, check whether any class in main still extends `SkillBillRuntimeException` or `ShellContentContractException`. If none does, finish the transition as `spec.md` "Target failure model" describes.

## Common Acceptance Criteria

- Every user-visible message is byte-identical. No expected-output, wire-fixture or payload assertion is edited, other than replacing an exception-type assertion with a code assertion, or a pinned class name with the code label.
- No main source declares a typealias named after a deleted class.
- `custom-throwable-baseline.txt` lists none of this subtask's deleted classes, and `FailureCodeTotalityArchitectureTest` passes.
- Classes this subtask does not own are unchanged, except for catch sites that must accept a code this subtask introduced.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Existing tests pass with type-to-code assertion edits only. Add only the behavioural tests listed under Test obligations, and only where no converted existing test already asserts the branch.

## Implementation Details

This plan uses the upstream preplan digest from checkout `8527efaee41cfc2beb316be9b6eec4be017f9deb`. It covers only subtask 2 and creates no dependency on the other seven slices. The plan phase changes only this spec. Implementation confirms current anchors and any assumptions below without repeating preplan here.

1. Replace the two owned throwable hierarchies with owner codes and message functions. This serves AC-001 and AC-002. The files are `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/ScaffoldShellContentErrors.kt` and `ReviewContextShellContentErrors.kt`. Both enums implement the existing `RuntimeFailureCode`. Scaffold gets distinct entries for payload-version mismatch, invalid payload, retired kind, unknown skill kind, unknown pre-shell family and existing skill. Its family entry covers direct scaffold failures, missing platform pack, missing supporting target and rollback. ReviewContext gets distinct entries for identity mismatch, context schema, rule text too long, title too long, hunk locator missing, hunk locator unreadable, hunk integrity, unreadable spec intent and aggregation integrity. Invalid skill-content identity uses its family entry. Delete all ten classes in each file. Message-only constructors become direct coded constructions at their callers. Structured constructors used at multiple sites become functions returning `SkillBillRuntimeException`, preserving the old parameters and optional cause. Keep messages byte-identical, including blank substitutions, `REVIEW_*` prefixes, the nonblank definition-name suffix and sorted nonempty aggregation lane suffix. Retain the public `REVIEW_HUNK_EVIDENCE_INTEGRITY` token because `ReviewPreparationServiceTest.kt` reads it. Assumption for implement to confirm: these input-driven failures all remain coded failures; use a defect assertion only if the current producer proves that external input cannot trigger it. No new test obligation applies to these declarations; task 5 converts their existing boundary assertions.

2. Convert scaffold producers and preserve the CLI failure boundary. This serves AC-002, AC-003 and AC-004. Touch `runtime-domain/src/main/kotlin/skillbill/scaffold/model/SkillKind.kt`, scaffold policy files `ScaffoldPayloadPolicy.kt`, `ScaffoldPolicyChecks.kt` and `ScaffoldPolicyConstants.kt`, `runtime-contracts/src/main/kotlin/skillbill/contracts/scaffold/wire/ScaffoldPayloadParsing.kt`, application scaffold request parsers, and infrastructure scaffold payload, manifest, adapter and runtime-service packages. Replace every construction, throw, return type and failure-producing callback for the removed classes with the appropriate coded construction or message function. Preserve SKILL-398's nullable payload-object decoding. In `runtime-cli/src/main/kotlin/skillbill/cli/scaffold/payload/NativeScaffoldPayloadRun.kt`, adapt `completeScaffoldError` to the scaffold code family while preserving stdout, stderr and exit code for each of the ten former classes. Adapt wizard callers in `scaffold/wizard/ScaffoldWizardRun.kt`. Keep existing database-propagation guards and unrelated failure propagation. Do not expand a catch to all runtime failures. The existing scaffold policy, payload parsing, infrastructure parity and governance, CLI request-parser and MCP request-parser tests provide the test obligations for these boundaries; no additional test is planned.

3. Convert ReviewContext producers and its discriminating consumers. This serves AC-002 and AC-003. Touch review context models, hunk evidence, application review preparation and spec resolution, infrastructure contract validation, review evidence brokers and shared-evidence reads. Replace removed constructors and concrete failure callback types without reading removed properties from caught failures or parsing their messages. In `runtime-engine/src/main/kotlin/skillbill/engine/featuretask/slot/codereview/CodeReviewStep.kt`, change `launchFailure(Throwable)` to recognize unreadable spec intent and invalid review context by code before the runtime-owned-fact and generic arms. Preserve messages, dispositions and `failureCodeLabel() ?: existingExpression` rendering. Guard the schema catch in `runtime-application/src/main/kotlin/skillbill/application/review/spec/SpecIntentProjectionExtractor.kt` by the review-context schema code. Guard the aggregation catch in `runtime-cli/src/main/kotlin/skillbill/cli/codereview/CodeReviewCommand.kt` by its code, retaining text and exit code 1. In `runtime-infra/skills/src/main/kotlin/skillbill/infrastructure/skills/install/apply/InstallApply.kt`, preserve the identity-mismatch rethrow before other staging failures become an issue. This is a ReviewContext code, not an Install code. Catch only `SkillBillRuntimeException` at converted narrow seams and use `rethrowUnless` with the previous handled set. When catches merge, branch on code and preserve existing order and propagation. Existing preparation, schema-validator, review-broker and shared-evidence tests cover these conversions; task 5 preserves their output assertions.

4. Integrate the codes with shared classification and remove only deleted baseline rows. This serves AC-001 through AC-003 and the common acceptance criteria. Add `ReviewContextFailureCode` to `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/ShellContentContractFailures.kt`. Keep `ScaffoldFailureCode` outside that classifier, and retain AgentAddon, GovernedReview, wire-code and `GoalTelemetryRowFailureCode` classification. Preserve every shell-content guarded rethrow, adding a missing guard where required by the shared rules. Use the existing helpers in `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/core/RuntimeExceptionBases.kt`; core must not import shellcontent. Remove only whole rows for the twenty deleted classes from `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt`. After implementation edits, inspect the transition condition for remaining subclasses and codeless constructions. The digest establishes external legacy users, so retain the open exception, codeless constructor and legacy base while any remain. Finish the parent-defined transition only if intervening work has removed every such use. Do not delete legacy classification prematurely or weaken the coded handled set. The existing `FailureCodeTotalityArchitectureTest` and its synthetic fixtures are the later validation obligations; add no duplicate baseline test and do not grow `ArchitectureScanSupport.kt`.

5. Convert existing tests without changing their behavioral contracts. This serves AC-002 through AC-004 and the common acceptance criteria. Replace `assertFailsWith<FormerClass>` with `assertFailsWith<SkillBillRuntimeException>` plus exact owner-code assertions. Convert constructed failures to the new functions or coded constructors. Preserve all message, payload, expected-output, wire-fixture and exit-code assertions, except pinned converted class labels become their code labels. Relevant tests include domain `ScaffoldPayloadPolicyTest.kt`, contracts `ScaffoldPayloadParsingTest.kt`, infrastructure scaffold parity and governance tests, CLI and MCP scaffold request-parser tests, application `review/preparation/ReviewPreparationServiceTest.kt`, core `skillbill/di/review/ReviewContextSchemaValidatorTest.kt`, and infrastructure workflow review-broker and shared-evidence tests. Keep tests and fixtures in their existing source sets and keep persisted bytes and slotbaseline resources at their current paths. New test obligations are empty because the digest prescribes no new behavior test for this slice. The realistic regressions covered by converted existing assertions are changed scaffold output, wrong owner codes, changed review-schema suffixes or aggregation lane order, and loss of review failure disposition. Implement must confirm which existing cases cover these facts; no new test may merely mirror a factory or repeat a branch with different literals.

6. Prepare the implementation for audit and review without running validation commands. This serves every acceptance criterion. Confirm the repository end state has no owned throwable class or deleted-class alias, no former construction or typed caught-property read, narrow code-based discrimination, unchanged scaffold process output, and only the owned baseline row removals. Apply architecture rules A1 through A12 and G1 through G7, particularly A7 handled sets, A6 wire ownership, A9 package cycles and A10 test placement. Keep validators in their existing owners, ports declaration-only under A4, the attempt boundary phase-generic, and accepted-step authority intact. No new module, dependency, alias, exception property, code metadata, public raw-map result, locator, dependency bag, suppression or `runCatching` is allowed. Preserve cancellation, interruption, database and gate failure propagation. Keep the stated detekt limits and add no authored line comments, non-KDoc block comments or non-interface KDoc. Audit later inspects each criterion, and review owns branch-diff assessment and repairs. These constraints preserve behavior and architecture; they do not prohibit necessary production wiring, test setup, formatting or lint repairs in their owning phases.

7. Leave execution evidence to the owning runtime phases. This serves all criteria and the unchanged Validation Strategy. Build proof belongs only to a build phase authorized by the runtime; this plan does not launch one. Validate owns unit tests, detekt, formatting, strict agnix and the runtime-core repository architecture suites. Its full Kotlin gate is `./gradlew check --continue --parallel -q --warning-mode none` under the required JDK 21 environment. Include failure-code totality, typed parse boundaries, ports declarations, package cycles, wire vocabulary and comment guards. Spotless must run in a plain clone. No command runs during plan, and any `tests_executed` receipt stays empty. No persisted schema, payload, recovery record, quarantine record, diagnostic record, feature flag or dependency change is needed. Preserve uncoded throwable rendering; code labels are the intended rendering change for converted failures, subject to AC-004's unchanged scaffold output. History, commits, pushes, PR work and runtime settlement remain with their owning phases. This slice changes no authored skill or generated artifact and requires no installation task.

The digest supplies the design decisions needed for this slice. It names some producer and test families without individual filenames; implement resolves those references in their existing packages rather than creating replacement owners. No repository-answerable question blocks this plan, and no additional decomposition is proposed.

## Next Path

skill-bill goal SKILL-399

## Spec Path

.feature-specs/SKILL-399-shell-content-error-codes/spec_subtask_2_scaffold-and-review-context.md
