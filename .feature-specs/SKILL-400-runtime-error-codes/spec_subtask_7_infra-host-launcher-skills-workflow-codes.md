# SKILL-400 Subtask 7 - infra-host-launcher-skills-workflow-codes

Parent spec: [.feature-specs/SKILL-400-runtime-error-codes/spec.md](./spec.md)
Issue key: SKILL-400

## Scope

Convert the runtime-infra classes, and give the codeless scaffold constructions a code:

- **host** `skillbill.infrastructure.host.jvm` (`GateJvmResolutionErrors.kt`, 6 classes, all extending `SkillBillRuntimeException`). They become `GateJvmFailureCode { GUARD_RESOURCE_MISSING, GUARD_EXECUTION, GUARD_OUTPUT, GUARD_TIMEOUT, UNRESOLVED, STARTUP_FAILURE }`.
  - The factories keep their text. `rejectedCandidate`, `requiredMajor` and `resolvedJvm` only feed messages.
  - Rename the file to the enum. MCP captured these before and still does.
- **launcher** `skillbill.infrastructure.launcher.review` (`CursorReviewStreamErrors.kt`; the base extends `Exception`). It becomes `CursorReviewStreamFailureCode { MALFORMED, FORBIDDEN_OPERATION, PROVIDER_FAILURE, TERMINATION, UNKNOWN }`.
  - Delete the never-constructed `CursorReviewStreamEmptyError`.
  - Throw sites are in `CursorAgentRunDecoding.kt`. `AgentRunAdapters.kt:230` becomes `error is SkillBillRuntimeException && error.code == MALFORMED`. `CursorStreamParse.error` keeps holding the throwable value.
  - Handled-set rule: an `Exception` that becomes a `SkillBillRuntimeException` must not be absorbed by a launcher or engine catch that did not catch it before.
- **skills, symlink.** `InstallSymlinkException` (extends `SkillBillRuntimeException`, with typed `linkPath` and `guidance`) becomes `InstallApplyFailureCode.SYMLINK` in `skillbill.infrastructure.skills.install.apply`. `symbolicLinkFailure` returns the coded exception with today's message.
  - The `as? InstallSymlinkException` readers at `InstallApplyNativeAgents.kt:144` and `InstallApplySkillLinks.kt:119` read `linkPath` and `guidance`. They take the path from the link they attempted, and the guidance from `windowsSymlinkGuidance()` when `code == SYMLINK`. If a reader does not hold the link path, the linking function returns it in a result instead.
  - The `InstallApplyIssue` fields stay the same, and `causeClass` renders `failureCodeLabel()`.
- **skills, release license.** `ReleaseLicensePolicyError` (extends `IllegalArgumentException`; thrown in `RepoValidationRuntime*ReleasePolicy*`). If SKILL-398 subtask 6 already made `validateReleaseRef` return a result and deleted the class, skip it.
  - Otherwise it becomes `ReleasePolicyFailureCode.LICENSE` in `skillbill.error.core`, because the CLI discriminates it.
  - `RepoValidationCliCommands.kt:116` handles the code beside its IAE catch, with identical payload, text and exit code. The code joins `uncapturedAtMcp()` if MCP can reach it.
- **workflow.** `ValidationGateProcessException` (`FileSystemValidationGateRunner.kt`, extends `RuntimeException`) becomes `ValidationGateProcessFailureCode { TIMED_OUT, LAUNCH_FAILED }` in `skillbill.infrastructure.workflow.validation`. MCP captured it before and still does. Apply the handled-set rule to every `SkillBillRuntimeException` catch it can reach.
- **Codeless scaffold constructions.** There are 12 `SkillBillRuntimeException(message[, cause])` calls without a code in runtime-infra/skills `scaffold/authoring/` and `scaffold/rendering/`:
  - `AuthoringDiscovery.kt:31,72`, `AuthoringMutation.kt:26`, `AuthoringOperations.kt:177`;
  - `AuthoringContentMutation.kt:27,40,47,94,105`, `AuthoredContentRendering.kt:20,26`;
  - `ScaffoldTemplateRendering.kt:119`.

  They get `ScaffoldAuthoringFailureCode` entries in `skillbill.infrastructure.skills.scaffold.authoring`: one family entry, plus an entry only where main code or a test discriminates. `AuthoringDiscovery.kt:43` and `AuthoringMutation.kt:55,88` keep their handled set.
- **MCP reach.** Confirm that `GateJvmFailureCode`, `InstallApplyFailureCode`, `CursorReviewStreamFailureCode`, `ValidationGateProcessFailureCode` and `ScaffoldAuthoringFailureCode` cannot reach an MCP tool on the no-capture side. They were all captured before. If one must stay uncaptured, move its enum to `skillbill.error.core` and add it to `uncapturedAtMcp()`.

## Acceptance Criteria

1. No main source declares the 15 classes, and no main source calls the codeless `SkillBillRuntimeException` constructor.
2. Each former failure throws `SkillBillRuntimeException` with an entry of `GateJvmFailureCode`, `CursorReviewStreamFailureCode`, `InstallApplyFailureCode`, `ReleasePolicyFailureCode`, `ValidationGateProcessFailureCode` or `ScaffoldAuthoringFailureCode`, unless SKILL-398 subtask 6 already replaced `ReleaseLicensePolicyError` with a result.
3. Install apply issues keep the same `kind`, `message`, `path` and `guidance` for symlink failures. Cursor review streams classify malformed output as undecodable exactly as before. `skill-bill` repo validation prints the same license-policy output.
4. No main code reads `linkPath`, `guidance` or any other typed property from a caught failure of these classes.

## Non-Goals

The SKILL-399 Scaffold area (`ScaffoldShellContentErrors.kt`); the gate-JVM resolution policy; install apply semantics.

## Test obligations

- **Symlink issue.** If no existing test asserts `path` and `guidance` on a symlink failure issue, add one in `InstallApplyNativeAgents` or `InstallApplySkillLinks`. Bug it catches: the issue loses its path or guidance once the typed properties are gone.
## Shared Rules

Apply `spec.md` "Conversion rules", "Shared pieces", "Transition finish" and "Execution Rule". If a shared piece is missing, add it as written there. After this subtask's edits, check the transition-finish condition and finish the transition if it holds.

## Common Acceptance Criteria

- Every user-visible message is byte-identical. No expected-output, wire-fixture or payload assertion is edited, other than replacing an exception-type assertion with a code assertion, or a pinned class name with the code label.
- MCP telemetry capture happens for exactly the failures it happened for before, and no unguarded `SkillBillRuntimeException` catch absorbs a failure it did not catch before.
- No main source declares a typealias named after a deleted class.
- `custom-throwable-baseline.txt` lists none of this subtask's deleted classes, and `FailureCodeTotalityArchitectureTest` passes.
- Classes this subtask does not own are unchanged, except for catch sites that must accept a code this subtask introduced.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Existing tests pass with type-to-code assertion edits only. Add only the behavioural tests listed under Test obligations, and only where no converted existing test already asserts the branch. Run nothing in implement; build, tests, detekt and repoTest belong to the build and validate phases.

## Implementation Details

This plan uses only the supplied preplan digest, discovered at `6e512ed9ca1d0bf43619880b9f7560a58704d5f2`. AC-001 through AC-004 refer to the numbered Acceptance Criteria above. Paths below are relative to `runtime-kotlin`; production package paths sit beneath each named module's `src/main/kotlin`, and tests beneath `src/test/kotlin` unless stated otherwise. Implementation must preserve earlier same-branch edits in shared files. No new decomposition or dependency work is required.

### Ordered implementation tasks

1. Convert gate-JVM failures. Serves AC-001 and AC-002 and the common message and capture criteria. Replace `runtime-infra/host` package `skillbill/infrastructure/host/jvm/GateJvmResolutionErrors.kt` with `GateJvmFailureCode.kt`, declaring the six entries specified in Scope and factories retaining the old parameters, causes and exact messages. Update `GateJvmResolver.kt` and `runtime-infra/workflow` package `skillbill/infrastructure/workflow/validation/FileSystemValidationGateRunner.kt` to use the factories. Preserve startup output excerpts byte-for-byte. Parameters such as `rejectedCandidate`, `requiredMajor` and `resolvedJvm` remain message inputs, never exception properties. Keep the enum in its host owner; these failures stay captured and outside shell-content classification. Retarget existing gate-runner assertions to the shared exception and exact code without adding duplicate message tests.

2. Convert Cursor stream and validation-process failures. Serves AC-001, AC-002 and AC-003. In `runtime-infra/launcher`, replace `skillbill/infrastructure/launcher/review/CursorReviewStreamErrors.kt` with `CursorReviewStreamFailureCode.kt` and its five specified entries and required factories. Delete the unused empty-stream class. Update `skillbill/infrastructure/launcher/agentrun/CursorAgentRunDecoding.kt` and `AgentRunAdapters.kt` so only `MALFORMED` produces the existing undecodable classification. Keep the original throwable in `CursorStreamParse.error`. In `runtime-infra/workflow`, replace the inline `ValidationGateProcessException` in `FileSystemValidationGateRunner.kt` with owner-local `ValidationGateProcessFailureCode.TIMED_OUT` and `.LAUNCH_FAILED`, retaining message and cause behavior. Inspect reachable shared-exception handlers during implementation and guard them so newly shared Cursor and process failures retain their previous route. Neither family joins shell-content classification or MCP's client-failure predicate. Update existing `CursorAgentRunCommandBuildersTest.kt` and `FileSystemValidationGateRunnerTest.kt` assertions, preserving outcomes and messages.

3. Carry symlink failure context as a returned value. Serves all four ACs. Replace `runtime-infra/skills` package `skillbill/infrastructure/skills/install/apply/InstallSymlinkException.kt` with `InstallApplyFailureCode.kt`; `symbolicLinkFailure(linkPath, cause)` returns a shared exception coded `SYMLINK` with the existing text and cause. In `InstallSymlinkReplacement.kt`, return the failure and actual failed path from the operation that knows it, including the generated temporary path `.${linkPath.fileName}.tmp-${UUID.randomUUID()}`. Extend the existing native-agent result flow rather than introducing a module or generic result library. Carry that value through `InstallNativeAgentResult`, `install/nativeagent/link/InstallNativeAgentLinking.installNativeAgentFile`, `InstallNativeAgentOperationsLinkTargetResolve.linkGeneratedNativeAgentFiles`, `NativeAgentLinkOutcome`, provider operations in `InstallNativeAgentOperations.kt`, and `NativeAgentInstaller.link` in `InstallApplyNativeAgents.kt`.

   Stop processing at the same failure boundary. Make `linkProviderAgentsWithJournal` restore `ProviderMutationJournal` for returned failures as well as thrown failures, preserve suppressed restoration failures on the original failure, and prevent subsequent inventory writes or catalog promotion. Retain throwing adapters for skill-link and platform-pack-view callers, including `InstallApplyPlatformPackView.kt`. `InstallApplySkillLinks.failedSkillLinkOutcome` obtains its logical path from context. `InstallApplyNativeAgents.failedNativeAgentOutcome` obtains the exact failed path from the returned value. Both derive guidance through `windowsSymlinkGuidance()` only for `SYMLINK`. Preserve issue `kind`, `message`, `path` and `guidance`, plus existing outcome paths; render `causeClass` with `failureCodeLabel()`. Do not parse a message or recover context from caught-exception properties.

4. Code scaffold authoring failures and retain release-policy results. Serves AC-001, AC-002 and AC-003. Add `ScaffoldAuthoringFailureCode` in `runtime-infra/skills` package `skillbill.infrastructure.skills.scaffold.authoring`, using a family entry unless an existing reader or assertion requires distinct classification. Convert the 12 constructions in `AuthoringDiscovery.kt`, `AuthoringMutation.kt`, `AuthoringOperations.kt`, `AuthoringContentMutation.kt`, `AuthoredContentRendering.kt`, and sibling `scaffold/rendering/ScaffoldTemplateRendering.kt`. Preserve discovery and mutation rollback-and-rethrow behavior, database guards, and `AuthoringMutation.kt`'s separate shell-content-only validation catch. Leave the already-coded `AuthoringRenderOutput.kt` constructor unchanged. The digest settles release-policy omission: `ReleaseLicensePolicyError` is already gone and validation returns a result. Preserve that result route and CLI output; add no replacement release enum or capture registration. Retarget existing authoring tests and render snapshot assertions only where type or pinned class-label changes require it.

5. Remove deleted baseline rows and conditionally finish the transition. Serves AC-001, AC-004 and the common baseline and propagation criteria. Hand-remove only whole rows belonging to deleted classes from `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt`. Keep `FailureCodeTotalityArchitectureTest`'s nonempty real-tree scan and synthetic rejection coverage, and leave `ArchitectureScanSupport.kt` unchanged. After the owned conversions, implementation must inspect all main, test and testFixtures source sets for remaining subclasses and codeless constructors. Planning assumes no intervening legacy caller, based on the digest, but does not assume other SKILL-400 subtasks have already completed. If retirement is eligible, remove `ShellContentContractException`, `LegacyFailureCode` and the secondary constructor from contracts `skillbill/error/core/RuntimeExceptionBases.kt`, make `SkillBillRuntimeException` final, and remove only the legacy-type term from `ShellContentContractFailures.kt`. Preserve every coded classification guard and rethrow. Remove the retired base's baseline row and update `runtime-kotlin/ARCHITECTURE.md` and stale references in `docs/`, `AGENTS.md`, `ARCHITECTURE.md` and the transition wording identified by decision `runtime-kotlin/agent/decisions.md#a34bb8414a73`. If an unrelated legacy caller remains, leave the transition open and name it in the implementation handoff. Do not convert unowned classes to force retirement.

6. Author focused regression coverage and hand off command verification. Serves AC-002, AC-003, AC-004 and common criteria. The named realistic bug is a returned native-agent symlink failure losing its temporary path or Windows guidance, or bypassing restoration and allowing inventory or catalog mutation. Extend existing install coverage with one deterministic structured-failure scenario that asserts the carried failed path, unchanged issue fields and guidance, restored prior links, and unchanged durable inventory or catalog state where the existing fixture exposes them. Reuse `InstallApplyTestSupport.setupApplyFixture()`, `InstallNativeAgentLinkApplyTestSupport`, existing symlink capability helpers and replacement rollback coverage. The digest does not specify a deterministic failure-injection seam. Implementation must confirm and use an existing seam where available; otherwise force a filesystem conflict at the relevant boundary without adding an architectural dependency. Do not rely solely on read-only permissions, which may permit links for the executing user. Preserve existing suppressed-restore assertions, adding an outcome assertion only if that branch lacks coverage. Existing conversion assertions and governed parity tests remain mandatory; add no separate tests for constructor glue.

### Constraints and later-phase verification

Implementation produces code and test end states without running commands for build or test proof. Audit inspects every criterion and the returned-failure route. Build alone owns compile proof. Validate owns execution of affected install tests, `CursorAgentRunCommandBuildersTest`, `FileSystemValidationGateRunnerTest`, `AuthoringOperationsTest`, `AuthoringContentMutationTest`, `AuthoringRenderOutputTest`, repoTest `skillbill/scaffold/AuthoringRenderSnapshotTest.kt`, failure-code totality and the required full project gate. Validate also owns detekt, formatting and architecture checks, with Spotless in a plain clone. Preserve the existing license-policy output assertions in validation. Review and validation may repair production wiring, test setup, formatting or lint while preserving behavior and architecture rules. History, commits and PR work remain with their owning phases.

Apply A1 and A2 for code ownership, A4 for declaration-only ports, A7 for classification and propagation, A9 and A10 for package and test placement, and G7 for shrinking baselines. Keep runtime-domain free of `java.nio` and ports imports. Add no module, dependency, typealias, exception property, code-family metadata, suppression, relaxed mock or new `runCatching`. Preserve cancellation, interruption, cooperative propagation, causes and suppression. Keep exact code checks for handled failures and rethrow unrelated codes. Keep detekt limits and enum filenames, and add no Kotlin comments outside permitted interface KDoc. No schema version or persisted wire-format change is needed. Planning reads and edits only this sub-spec and runs no build, test or validation command.

## Next Path

skill-bill goal SKILL-400

## Spec Path

.feature-specs/SKILL-400-runtime-error-codes/spec_subtask_7_infra-host-launcher-skills-workflow-codes.md
