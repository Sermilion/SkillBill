# SKILL-400 - runtime-error-codes

Issue key: SKILL-400
Origin: split out of SKILL-398 subtask 5 (`../done/SKILL-398-runtime-exception-reduction`) on 2026-10-02, after that subtask's implement phase blocked as too large. Investigation: `../done/SKILL-398-runtime-exception-reduction`, finding F-005.

## Outcome

Every custom `Throwable` in production main outside `skillbill.error.shellcontent` becomes a `SkillBillRuntimeException` with an owner failure code, or a `require`/`check`/`error()` defect. Three groups are excluded:

- the persistence and transport classes, which SKILL-398 subtask 5 converts;
- the shell-content classes owned by SKILL-399;
- the classes owned by SKILL-398 subtasks 2, 3 and 6.

This bundle covers the remaining 65 classes, plus the 12 codeless `SkillBillRuntimeException(message)` constructions in scaffold authoring and rendering.

Census on `feat/SKILL-398-runtime-exception-reduction` at `14e681f4b` (after SKILL-398 subtask 4):

| Area | Classes | Main reference lines / files | `assertFailsWith` | Subtask |
|---|---|---|---|---|
| JSON text and failure-wire decode | 4 | 21 / 10 | 8 | 1 |
| External platform packs and add-ons | 6 | 75 / 20 | 22 | 2 |
| Durable decode, feature-spec request, environment field, learning source | 9 | 40 / 20 | 15 | 3 |
| Phase slots and strategy composition | 18 | 60 / 24 | 33 | 4 |
| Execution plan admission, conflict, schema, fingerprint, regeneration | 9 | 70 / 29 | 65 | 5 |
| Domain (review attribution, skill rollback) and MCP tool arguments | 4 | 12 / 9 | 0 | 6 |
| Infra host, launcher, skills, workflow; codeless scaffold constructions | 15 + 12 sites | 42 / 13 | 5 | 7 |

Former supertypes matter for the edge rules below. Most classes in subtasks 1–5 extend `ShellContentContractException`. The exceptions are `InvalidFeatureSpecPreparationRequestError`, `UnresolvedEnvironmentContextFieldError` and `InvalidLearningSourceError` (`SkillBillRuntimeException`), `InvalidPhaseStrategyCompositionError` (`IllegalArgumentException`) and `UnsafeFeatureTaskRuntimeRegenerationError` (`IllegalStateException`). In subtasks 6–7:

- `ReviewAttributionResolutionError` and `ReleaseLicensePolicyError` extend `IllegalArgumentException`;
- `CursorReviewStreamError` extends `Exception`;
- `ValidationGateProcessException` extends `RuntimeException`;
- `InvalidMcpToolArgumentError` extends `ShellContentContractException`;
- the rest extend `SkillBillRuntimeException`.

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

While any class in main still extends `SkillBillRuntimeException` or `ShellContentContractException`, `SkillBillRuntimeException` stays `open` with the codeless secondary constructor (`LegacyFailureCode.UNCLASSIFIED`).

## Shared pieces

These come from SKILL-398 subtasks 4 and 5. A subtask that finds one missing adds it exactly as written:

- `fun SkillBillRuntimeException.rethrowUnless(handled: Boolean): SkillBillRuntimeException` in `skillbill.error.core`. It throws `this` when `handled` is false and returns `this` otherwise.
- `fun Throwable.failureCodeLabel(): String?` in `skillbill.error.core`. It returns `"<CodeEnumSimpleName>.<ENTRY>"` for a `SkillBillRuntimeException` whose code is not `LegacyFailureCode`, and `null` otherwise. Every main site that renders a caught throwable's class name uses `failureCodeLabel() ?: <existing expression>`.
- `fun Throwable.isShellContentContractFailure(): Boolean` in `skillbill.error.shellcontent/ShellContentContractFailures.kt`. It is true for `is ShellContentContractException`, a `FailureWireCode` code, or a code of any enum listed in it. Every main `catch`, `is` or `as?` on `ShellContentContractException` is guarded by it and rethrows other codes.
- `fun SkillBillRuntimeException.rethrowIfDatabaseFailure()` in `skillbill.error.core`. It throws `this` when `code is DatabaseFailureCode`. If `DatabaseFailureCode` does not exist yet, skip this helper; SKILL-398 subtask 5 adds both.
- `private fun Throwable.uncapturedAtMcp(): Boolean` in `McpToolDispatcher.kt` (runtime-mcp `skillbill.mcp.core`). It holds the dispatcher's no-capture condition: shell-content failures, `IllegalArgumentException`, `IllegalStateException`, and the codes listed in it. The failure `when` returns `mcpToolErrorResult` without capture when it is true. If it is missing, extract it from the current `when` with the same condition.

## Conversion rules (every subtask)

- **Enum placement.** A code enum lives in the module and package that declared the replaced class, with two overrides:
  - If the replaced class extended `ShellContentContractException`, its code joins `isShellContentContractFailure()`, so its enum must be visible to runtime-contracts. It lives in runtime-contracts (`skillbill.error.core` or `skillbill.error.featuretask`), or is an entry of an existing shell-content area enum.
  - If another module must discriminate the code (an edge catch, the MCP predicate, a CLI reader), the enum lives in runtime-contracts `skillbill.error.core` or `.featuretask`.

  Otherwise a code only one non-contracts module throws and reads lives in that module. runtime-contracts declares no MCP-only code.
- **Entry rule.** One entry per former class that main code discriminates or a test asserts. Other classes in the same file share a family entry. Each enum implements `RuntimeFailureCode`. When two subtasks share an enum, whichever runs first creates it and the other adds entries.
- **Messages.** A message function sits next to the enum for each class thrown from more than one site. It returns `SkillBillRuntimeException` and takes the old constructor's parameters, including `cause`. A class thrown at one site is inlined as `SkillBillRuntimeException(CODE, "<same text>", cause)`. Text is byte-identical, including the `ifBlank { "<unknown>" }`, `"<root>"` and quoting.
- **Defects.** A class becomes `require`/`check`/`error()` with the same message only when nothing but runtime composition or a code bug can trigger it, and no existing CLI or MCP test drives it through an edge. When unsure, keep a code.
- **Typed properties.** Main code reads nothing from a caught failure except `code`, `message` and `cause`. Where a reader needs a value to build a result, the throwing function returns that result instead (tier 2), or the reader takes the value from its own context. Where the value only feeds a message, it goes into the message. Add no property to `SkillBillRuntimeException`.
- **Throw and catch sites.**
  - Every `throw`, and every returned or constructed former class, uses the message function or the coded constructor.
  - `catch (e: FormerClass)` becomes `catch (e: SkillBillRuntimeException) { e.rethrowUnless(e.code == X) … }`, or `code is <Enum>` for a family. Two catches on one `try` merge into one catch with a `when (e.code)`. `is`/`as?` become code checks on `(error as? SkillBillRuntimeException)?.code`.
  - A catch that precedes a generic `SkillBillRuntimeException` catch keeps its order. No touched catch becomes a new `runCatching`, and cancellation and interruption keep propagating.
- **Keep the handled set.** When a former class that did not extend `SkillBillRuntimeException` becomes one, every unguarded `catch (e: SkillBillRuntimeException)` or `is SkillBillRuntimeException` arm it can reach rethrows it, so it keeps today's route. Conversely, a former `IllegalArgumentException`, `IllegalStateException` or `RuntimeException` handler that caught it now checks its code. Check reachability per site. Unguarded sites at `14e681f4b`:
  - `PlanDecompositionStop.kt:89,211`, `FeatureTaskRuntimeRejectedOutputRecorder.kt:209,239`;
  - `InstallStaging.kt:204`, `AuthoringDiscovery.kt:43`, `AuthoringMutation.kt:55`;
  - `InstallCliCommands.kt:213`, `ScaffoldWizardRun.kt:39`, `NativeScaffoldPayloadRun.kt:37,52,111,156,172`;
  - `SkillRemove.kt:141`.
- **MCP capture parity.** A code whose former class was on the no-capture side joins `uncapturedAtMcp()` unless `isShellContentContractFailure()` already covers it. The no-capture side is shell-content, `InvalidLearningSourceError`, IAE and ISE. A code whose former class was captured stays out. The enum must be visible to runtime-mcp; runtime-mcp depends on runtime-contracts, -core, -domain, -application, -engine and -ports, but not on runtime-infra. If an infra-owned code can reach an MCP tool and must stay uncaptured, its enum moves to `skillbill.error.core`.
- **Accepted framing change.** A former `RuntimeException`, `Exception` or ISE class that reaches `CliRuntime` now prints through the `SkillBillRuntimeException` arm, without the `ClassName: ` prefix or the diagnostics record. Its message text does not change. Pinned class names rendered through `failureCodeLabel()` become the code label.
- **Tests.** `assertFailsWith<FormerClass>` becomes `assertFailsWith<SkillBillRuntimeException>` plus `assertEquals(<Code>.<ENTRY>, error.code)`. For a class that became a defect it becomes `assertFailsWith<IllegalArgumentException>` or `<IllegalStateException>`. Message, `contains`, payload and exit-code assertions stay byte-for-byte. Tests that construct a former class switch to the message function. Do not use `relaxed = true` mocks, `environment = emptyMap()` or a new test-helper module.
- **Baseline.** Remove each deleted class's row from `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt` by hand. Row format is `module:Class`; compare whole rows and edit no other row.

## Transition finish

After its own edits, every subtask checks the tree. If no class in main extends `SkillBillRuntimeException` or `ShellContentContractException`, and no main, test or testFixtures source calls the codeless constructor, the subtask finishes the transition:

- delete `ShellContentContractException`, `LegacyFailureCode` and the secondary constructor, and make `SkillBillRuntimeException` final;
- remove the `is ShellContentContractException` term from `isShellContentContractFailure()`;
- keep the guarded edge sites and their rethrow, so each site handles exactly the failures it handled before;
- retarget any `ShellContentContractException` catch, function type or `is` check left in main or tests to `SkillBillRuntimeException`, under the same guard;
- update the `skillbill.error.*` package description in `runtime-kotlin/ARCHITECTURE.md`, and grep `docs/`, `AGENTS.md` and `ARCHITECTURE.md` for `ShellContentContractException`.

Widening a guarded catch to every `SkillBillRuntimeException` is not allowed: it would absorb database, runtime-owned fact, gate-JVM and validation-gate failures that propagate today. If a subclass remains, the subtask leaves the transition open and says so in its summary.

## Execution Rule

Every subtask runs on the current tree and waits for no other subtask or issue. It applies its rule to the anchors present when it runs:

- where a named class is already gone, it edits whatever replaced it;
- where a class it does not own still exists, it leaves it alone;
- on a shared file, whichever subtask lands second keeps both edits;
- a missing shared piece is added as written above.

Line numbers come from the census at `14e681f4b`; apply each rule to the code wherever it is now.

## Subtasks

1. `spec_subtask_1`: JSON text and failure-wire decode codes.
2. `spec_subtask_2`: external platform-pack and add-on codes.
3. `spec_subtask_3`: durable decode, feature-spec request, environment field and learning-source codes.
4. `spec_subtask_4`: phase-slot codes and strategy-composition defects.
5. `spec_subtask_5`: execution-plan admission, conflict, schema, fingerprint and regeneration codes.
6. `spec_subtask_6`: domain review-attribution and skill-rollback codes, and the MCP tool-argument code.
7. `spec_subtask_7`: infra host, launcher, skills and workflow codes, and the codeless scaffold constructions.

Split reason: the single remaining-errors subtask blocked as too large to implement in one phase. The slices follow owner areas and reader seams, so each conversion lands with the catch sites that read it. No subtask depends on another.

## Acceptance Criteria

The feature is done when every subtask's criteria hold. Together:

1. No production main source outside `skillbill.error.shellcontent` declares a custom `Throwable` other than `SkillBillRuntimeException`, classes owned by SKILL-398 or SKILL-399 that are still present, and classes whose retention reason is recorded in `runtime-kotlin/agent/decisions.md`.
2. Every former failure throws a coded `SkillBillRuntimeException` or is a `require`/`check`/`error()` defect. runtime-contracts declares no MCP-only code.
3. Messages, payloads and rendered labels for uncoded throwables are byte-identical, and MCP telemetry capture happens for exactly the failures it happened for before.
4. No main code reads a typed property from a caught exception.
5. `custom-throwable-baseline.txt` lists none of the deleted classes, and `FailureCodeTotalityArchitectureTest` passes.

## Non-Goals

- Persistence and transport classes (`DatabaseAccessError`, `DatabaseBusyError`, the telemetry HTTP errors, `InvalidGoalTelemetryRowError`, `RuntimeOwnedFactUnavailable`): SKILL-398 subtask 5.
- `skillbill.error.shellcontent` classes: SKILL-398 subtask 4 and SKILL-399.
- Classes owned by SKILL-398 subtasks 2, 3 and 6.
- The CLI and MCP top-level arms (F-008), beyond the `uncapturedAtMcp` predicate.
- Test-source throwables.

## Constraints

- No new module, dependency, `Result`/`Either` library, typealias for a deleted class, property on `SkillBillRuntimeException`, family metadata on codes, `@Suppress` or new `runCatching`.
- `skillbill.error.core` must not import `skillbill.error.shellcontent` (per-module package-cycle guard).
- runtime-domain stays free of `java.nio` and ports imports. runtime-contracts declares no engine-, infra- or MCP-only code, except codes the enum-placement override puts there.
- Ports hold declarations only: check `PortsDeclarationArchitectureTest` and the SKILL-393 no-behaviour rule.
- `CancellationException`/`InterruptedException` keep propagating; keep `CooperativeFailurePropagation`.
- detekt limits: `ThrowsCount` 2, `ReturnCount` 4, `LongMethod` 70, `CyclomaticComplexMethod` 15, `MatchingDeclarationName` (a file left holding one enum plus factories is renamed to the enum). `ArchitectureScanSupport.kt` must not grow.
- Spotless runs in a plain clone, not a linked worktree.
- Authored Kotlin carries no `//` or non-KDoc block comments.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Existing tests pass with type-to-code assertion edits only. Each subtask names the behavioural tests its tier-2 conversions need.
