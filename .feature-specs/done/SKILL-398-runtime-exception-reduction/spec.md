# SKILL-398 - runtime-exception-reduction

## Mode

decomposed

## Intended Outcome

The runtime uses as few custom exceptions as possible. Expected outcomes (absent, refused, conflicting, invalid input the runtime anticipates) flow back to the caller as sealed results, nullables or existing outcome types. Code defects use Kotlin's `require`, `check` and `error()` and are never caught for control flow. Failures that end the run with an operator message use one type, `SkillBillRuntimeException(code, message, cause)`, where `code` is an entry of an owner-declared enum. A two-sided baseline lists every custom `Throwable` declared in main, so a new one fails the build. Today main declares 231 custom throwables, 62% of which nothing catches by type, while both edges discard the type and print the message.

Evidence, censuses, findings F-001 to F-008, what stays unchanged, the over-engineering register and coordination: [investigation.md](investigation.md). Census tree: `8cea54bafc732f8e4ff79333aff18939797893b4` (SKILL-391 feature branch over base `3f2b96cde1e930672cdeaf96ed753a4a2c306600`).

## Target failure model

Every subtask builds toward this model. Any subtask that needs a piece of it and finds it missing adds it exactly as written here; a subtask that finds it present keeps it.

```kotlin
package skillbill.error.core

/** Marker for owner-declared failure code enums. */
interface RuntimeFailureCode

class SkillBillRuntimeException(
  val code: RuntimeFailureCode,
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
```

- Codes are enums that implement `RuntimeFailureCode`, declared by the owner of the vocabulary: kernel areas in their `skillbill.error.<area>` package, module-local areas in the package that declared the replaced classes. The existing `FailureWireCode` enums (`FeatureTaskRuntimePhaseOutputFailureCode`, `DecompositionManifestValidationFailureCode`, and the two failure-kind enums) implement `RuntimeFailureCode` and serve as the code for their failures.
- A former class becomes one enum entry when main code discriminates it or a test asserts it. Classes that neither main code nor tests discriminate share their file's family entry.
- A former class's message format moves to a function next to its code when it is thrown from more than one site, and is inlined at a single throw site otherwise. Message text is byte-identical.
- Transition: while any class in main still extends `SkillBillRuntimeException` or `ShellContentContractException`, `SkillBillRuntimeException` stays `open` with a secondary constructor `(message, cause)` that sets `code = LegacyFailureCode.UNCLASSIFIED` (`enum class LegacyFailureCode : RuntimeFailureCode { UNCLASSIFIED }`). The subtask that leaves no such subclass deletes `ShellContentContractException`, `LegacyFailureCode` and the secondary constructor, makes `SkillBillRuntimeException` final, and replaces the remaining `catch (e: ShellContentContractException)` sites with `catch (e: SkillBillRuntimeException)`.

## Execution Rule

Every subtask runs on the current tree and waits for no other subtask or issue. It applies its rule to the anchors present when it runs: where a named class is already gone, it edits whatever replaced it (a result, a code, a `require`); where a class it does not own still exists, it leaves it. On a shared file, whichever subtask lands second keeps both edits. The throwable baseline (subtask 1) is two-sided: any subtask that runs after it exists removes the rows of the classes it deletes; any subtask that runs before it does nothing to it.

## Subtasks

1. `spec_subtask_1`: failure policy, target type and throwable baseline (F-001, F-002).
2. `spec_subtask_2`: operation, rejected-output and required-phase-write outcomes as results (F-003).
3. `spec_subtask_3`: local control-flow exceptions as results, no message-text branching (F-004).
4. `spec_subtask_4`: collapse `skillbill.error.shellcontent` into codes (F-005, 96 classes).
5. `spec_subtask_5`: collapse the remaining custom exceptions into codes or defects (F-005, the rest).
6. `spec_subtask_6`: stop catching `IllegalArgumentException` and `IllegalStateException` for control flow (F-006).

Split reason: subtask 1 is policy, the target type and the guard. Subtasks 2 and 3 change signatures and control flow, so each needs a careful semantic review. Subtasks 4 and 5 are large and mostly mechanical (class to code, test assertions to code checks); kept with 2 and 3 they would bury those diffs. Subtask 6 is a per-site validation rewrite across domain, infra and CLI. No subtask depends on another.

## Acceptance Criteria

Each subtask spec holds the detailed, checkable criteria. The feature is done when all of them hold, which means:

1. `../../../docs/code-principles.md`, `AGENTS.md`, `runtime-kotlin/ARCHITECTURE.md` and a new `runtime-kotlin/agent/decisions.md` entry state the three-tier failure model and the "earns its place" rule, and no longer prescribe a typed exception class per contract (subtask 1).
2. A two-sided baseline lists every custom `Throwable` subclass declared in production main; a new declaration fails the runtime-core repoTest suite, and so does a listed class that no longer exists (subtask 1).
3. No operation, rejected-output diagnostic, required-phase write, or any of the local control-flow cases named in subtask 3 reports an expected outcome by throwing; callers branch on values, and no main code branches on exception message text (subtasks 2, 3).
4. `skillbill.error.shellcontent` and the other replaced leaf classes are gone; their failures throw `SkillBillRuntimeException` with an owner-declared code, or use `require`/`check`/`error()` where only a code defect can trigger them (subtasks 4, 5).
5. No main code catches `IllegalArgumentException` or `IllegalStateException` to steer control flow, outside the CLI and MCP top-level arms (subtask 6).
6. When all subtasks have landed, the baseline lists only `SkillBillRuntimeException` plus any class whose retention reason is recorded in `../../../runtime-kotlin/agent/decisions.md`.
7. CLI stdout, stderr and exit codes, MCP tool payloads and persisted bytes are unchanged: no existing expected-output, wire-fixture or payload assertion is edited, other than replacing an exception type assertion with a code assertion.

## Constraints

- No new module, dependency, `Result`/`Either` library, typealias for a removed class, test helper module or architecture-test class. The guard extends `FailureCodeTotalityArchitectureTest` and the existing baseline mechanism.
- Every user-visible message keeps its exact text.
- `CancellationException` and `InterruptedException` keep propagating wherever they do today.
- Any `runCatching` a subtask touches becomes a direct call, a narrow `try`/`catch` of the specific expected type, or uses the cooperative rethrow; no subtask adds a `runCatching`.
- This bundle runs on the current tree and waits for no other issue. Where SKILL-390, SKILL-391, SKILL-392, SKILL-396 or SKILL-397 has landed, rebase onto it and apply each rule to the files as they are; where not, implement against the current tree (investigation.md, Coordination with concurrent bundles).

## Non-Goals

- The repo-wide `runCatching` sweep (investigation F-007), beyond sites a subtask touches.
- Changing the `IllegalArgumentException` / `IllegalStateException` arms in `CliRuntime.kt` and `McpToolDispatcher.kt` (investigation F-008).
- Changing exceptions declared in test sources, or library and framework exceptions.
- Changing module ownership of failure vocabulary decided by SKILL-374 and SKILL-391.
- Renaming `SkillBillRuntimeException`.

## Validation Strategy

Each subtask runs the goal gates: build, unit tests, detekt and the runtime-core repoTest architecture suite, which includes `FailureCodeTotalityArchitectureTest` with the throwable baseline and `TypedParseBoundaryArchitectureTest`. Existing CLI, MCP and wire-fixture tests pass with only type-to-code assertion edits, which shows output is unchanged. Test obligations cover the high-value behaviour only: the baseline guard's synthetic new-declaration and stale-row cases (subtask 1), and the new result branches where an outcome used to be thrown (subtasks 2, 3).
