# SKILL-399 - shell-content-error-codes

Issue key: SKILL-399
Origin: split out of SKILL-398 subtask 4 (`../done/SKILL-398-runtime-exception-reduction`) on 2026-10-02, after that subtask's implement phase blocked as too large. Investigation: `../done/SKILL-398-runtime-exception-reduction`, finding F-005.

## Outcome

Every class declared in `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/` becomes a `SkillBillRuntimeException` with an owner failure code. SKILL-398 subtask 4 converts the AgentAddon and GovernedReview files and lays the shared transition pieces. This bundle converts the other seven files: FeatureTaskRuntime, Install, Manifest, ReviewContext, Scaffold, SkillStaging and Workflow.

Census on `feat/SKILL-398-runtime-exception-reduction` at `f9e4df35d`:

| Area | Classes | Main reference lines / files | catch, `is`, `as?` | `assertFailsWith` | Subtask |
|---|---|---|---|---|---|
| Manifest | 8 | 70 / 15 | 2 | 74 | 1 |
| SkillStaging | 12 | 60 / 19 | 1 | 55 | 1 |
| Scaffold | 10 | 126 / 22 | 0 | 68 | 2 |
| ReviewContext | 10 | 97 / 25 | 6 | 83 | 2 |
| Install | 18 | 242 / 57 | 14 | 199 | 3, 4 |
| FeatureTaskRuntime | 21 | 230 / 70 | 26 | 109 | 5, 6 |
| Workflow | 10 | 281 / 91 | 24 | 192 | 7, 8 |

## Target failure model

The model is SKILL-398's (`../done/SKILL-398-runtime-exception-reduction`, "Target failure model"):

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

Transition: while any class in main still extends `SkillBillRuntimeException` or `ShellContentContractException`, `SkillBillRuntimeException` stays `open` with a secondary constructor `(message, cause)` that sets `code = LegacyFailureCode.UNCLASSIFIED`. The subtask that leaves no such subclass finishes the transition:

- it deletes `ShellContentContractException`, `LegacyFailureCode` and the secondary constructor;
- it makes `SkillBillRuntimeException` final;
- it removes the `is ShellContentContractException` term from `isShellContentContractFailure()` and keeps every guarded edge site with its rethrow, retargeting any `ShellContentContractException` catch, function type or `is` check left in main or tests to `SkillBillRuntimeException` under the same guard. Widening a guarded catch to every `SkillBillRuntimeException` is not allowed: it would absorb database, runtime-owned fact, gate-JVM and validation-gate failures that propagate today. The full rule is `.feature-specs/SKILL-400-runtime-error-codes/spec.md` "Transition finish"; the condition also requires that no source calls the codeless constructor.

Every subtask in this bundle checks that condition after its own edits.

## Shared transition pieces

These come from SKILL-398 subtask 4. A subtask that finds one missing adds it exactly as written:

- `fun SkillBillRuntimeException.rethrowUnless(handled: Boolean): SkillBillRuntimeException` in `skillbill.error.core`. It throws `this` when `handled` is false and returns `this` otherwise.
- `fun Throwable.failureCodeLabel(): String?` in `skillbill.error.core`. It returns `"<CodeEnumSimpleName>.<ENTRY>"` for a `SkillBillRuntimeException` whose code is not `LegacyFailureCode`, and `null` otherwise. Every main site that renders a caught throwable's class name uses `failureCodeLabel() ?: <existing expression>`.
- `fun Throwable.isShellContentContractFailure(): Boolean` in `skillbill.error.shellcontent/ShellContentContractFailures.kt`. It is true for `is ShellContentContractException`, a `FailureWireCode` code, or a code of any shell-content area enum present. `ScaffoldFailureCode` is never included. Each subtask adds the area enums it creates.
- Every main `catch`, `is` or `as?` on `ShellContentContractException` is guarded by `isShellContentContractFailure()` and rethrows other codes.

## Conversion rules (every subtask)

- **One code enum per file**, in the same package and named for the area (`ManifestFailureCode`, `SkillStagingFailureCode`, `ScaffoldFailureCode`, `ReviewContextFailureCode`, `InstallFailureCode`, `FeatureTaskRuntimeFailureCode`, `WorkflowFailureCode`). Each implements `RuntimeFailureCode`. When two subtasks share an area, whichever runs first creates the enum and the other adds entries.
- **Entry rule.** One entry per former class that main code discriminates or a test asserts. Every other class in the file shares the area's family entry. Where a class already carries a `FailureWireCode` value, that value is the `code`; add no parallel entry. The four `FailureWireCode` enums (`FeatureTaskRuntimePhaseOutputFailureCode`, `FeatureTaskRuntimePhaseOutputFailureKind`, `FeatureTaskRuntimeHandoffProjectionFailureKind`, `DecompositionManifestValidationFailureCode`) implement `RuntimeFailureCode`; add it where missing. Do not make `FailureWireCode` extend `RuntimeFailureCode`.
- **Messages.** A message function sits next to the enum for each class thrown from more than one site. It returns `SkillBillRuntimeException` and takes the old constructor's parameters, including `cause`. A class thrown at one site is inlined as `SkillBillRuntimeException(CODE, "<same text>", cause)`. Message-only classes (`message, cause`) are inlined as `SkillBillRuntimeException(CODE, message, cause)` at every site. Text is byte-identical, including the `ifBlank { "<unknown>" }`, `"<root>"`, `"<absent>"` and `REVIEW_*` prefixes and any sorted suffix.
- **Defects.** A class whose condition only a code defect can trigger becomes `require`/`check`/`error()` with the same message. No user, agent, file, process or network input may be able to trigger it. When unsure, keep the code.
- **Typed properties.** Main code reads nothing from a caught failure except `code`, `message` and `cause`. Where a reader needs a value to build a result, the throwing function returns that result instead (tier 2). Where the value only feeds a message, the reader uses `message`, or the factory puts the value in the message. Add no property to `SkillBillRuntimeException`.
- **Throw sites.** Every `throw`, and every returned or constructed former class, uses the message function or the coded constructor. Lambdas typed as returning a former class become `SkillBillRuntimeException`.
- **Catch sites.** `catch (e: FormerClass)` becomes `catch (e: SkillBillRuntimeException) { e.rethrowUnless(e.code == X) … }`, or `code is <Enum>` for a `FailureWireCode` family. Two catches on one `try` merge into one catch with a `when (e.code)`. `is FormerClass` and `as? FormerClass` become code checks on `(error as? SkillBillRuntimeException)?.code`. A catch that precedes a generic `SkillBillRuntimeException` catch keeps its order. A code-checked catch-to-null is acceptable; do not widen into decoder refactors. No touched catch becomes a new `runCatching`, and cancellation and interruption keep propagating.
- **Tests.** `assertFailsWith<FormerClass>` becomes `assertFailsWith<SkillBillRuntimeException>` plus `assertEquals(<Code>.<ENTRY>, error.code)`. Message, `contains`, payload and exit-code assertions stay byte-for-byte. Property assertions change only from `error.<property> == x` to `error.code == <entry>`. Tests that construct former classes switch to the message function or the new value. Do not use `relaxed = true` mocks, `environment = emptyMap()` or a new test-helper module.
- **Baseline.** Remove each deleted class's row from `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt` by hand (row format `module:Class`, whole-row compare). Edit no other row.

## Execution Rule

Every subtask runs on the current tree and waits for no other subtask or issue. It applies its rule to the anchors present when it runs:

- where a named class is already gone, it edits whatever replaced it;
- where a class it does not own still exists, it leaves it alone;
- on a shared file, whichever subtask lands second keeps both edits;
- a shared transition piece that is missing is added as written above.

Line numbers come from the SKILL-398 census at `432d427c8`; apply each rule to the code wherever it is now.

## Subtasks

1. `spec_subtask_1`: Manifest and SkillStaging areas.
2. `spec_subtask_2`: Scaffold and ReviewContext areas.
3. `spec_subtask_3`: Install area, schema and config classes.
4. `spec_subtask_4`: goal-planning preparation conflict as repository results, plus the contract-version hard-reset classifier.
5. `spec_subtask_5`: FeatureTaskRuntime phase output, handoff projection and phase order.
6. `spec_subtask_6`: FeatureTaskRuntime evidence, receipt and identity records.
7. `spec_subtask_7`: Workflow state and record classes.
8. `spec_subtask_8`: decomposition-manifest schema and bundle-journal failures.

Split reason: the single shell-content subtask blocked as too large to implement in one phase. The slices follow reader seams, so that each tier-2 conversion (repository results, transition result, projection rejection, manifest validation result) lands with the classes it replaces. No subtask depends on another.

## Acceptance Criteria

The feature is done when every subtask's criteria hold. Together:

1. `skillbill.error.shellcontent` declares no class; it holds only code enums, message functions and, until the transition finishes, `ShellContentContractFailures.kt`.
2. Every former failure throws a coded `SkillBillRuntimeException` or is a `require`/`check`/`error()` defect.
3. Messages, payloads and rendered labels for uncoded throwables are byte-identical.
4. No main code reads a typed property from a caught exception.
5. `custom-throwable-baseline.txt` lists no shell-content class, and `FailureCodeTotalityArchitectureTest` passes.

## Non-Goals

- The AgentAddon and GovernedReview files and the shared transition pieces (SKILL-398 subtask 4).
- Classes outside `skillbill.error.shellcontent` (SKILL-398 subtask 5 and SKILL-400).
- Renaming the `skillbill.error.shellcontent` package (SKILL-372 retention).
- The CLI and MCP top-level arms.

## Constraints

- No new module, dependency, `Result`/`Either` library, typealias for a deleted class, property on `SkillBillRuntimeException`, family metadata on codes, `@Suppress` or new `runCatching`.
- `skillbill.error.core` must not import `skillbill.error.shellcontent` (per-module package-cycle guard).
- runtime-domain stays free of `java.nio` and ports imports. runtime-contracts declares no engine, infra or MCP code.
- Ports hold declarations only: check `PortsDeclarationArchitectureTest` and the SKILL-393 no-behaviour rule.
- The SKILL-380 attempt boundary stays phase-generic.
- detekt limits: `ThrowsCount` 2, `ReturnCount` 4, `LongMethod` 70, `CyclomaticComplexMethod` 15. `ArchitectureScanSupport.kt` must not grow.
- Spotless runs in a plain clone, not a linked worktree.
- Authored Kotlin carries no `//` or non-KDoc block comments.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Existing tests pass with type-to-code assertion edits only. Each subtask names the behavioural tests its tier-2 conversions need.
