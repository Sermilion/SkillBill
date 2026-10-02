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

## Next Path

skill-bill goal SKILL-400

## Spec Path

.feature-specs/SKILL-400-runtime-error-codes/spec_subtask_7_infra-host-launcher-skills-workflow-codes.md
