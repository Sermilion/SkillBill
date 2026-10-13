# SKILL-422 subtask 1: evidence

Tests and reproductions listed here are **authored, not executed** in implement; validate owns execution evidence.

## Acceptance criteria mapping

| AC | Evidence artifact | Result (source-based) |
| --- | --- | --- |
| AC-001 | `state-ownership-inventory.md` | Tables/stores classified; `feature_task_phase_settlements` distinguished from `schema_migrations` |
| AC-002 | `transition-matrix.md` | Categories separated; unsupported states documented without new enums |
| AC-003 | `findings-register.md` + this file | F1–F7 ranked by severity; each entry lists full identity slots (including explicit not-applicable), causal root cause, before/after or pending capture recipe, isolated reproduction inputs and expected boundary outcomes; repair entries F1/F2/F3/F6/F7 quote rejected bytes with provenance in the register; classified matrix scenarios and no-finding rows cite production owners (see register sections **Classified durable/ownership scenarios** and **Verified no-finding areas**). Tests not executed. |
| AC-004 | Code: `WorkflowStateWrites.kt`, `WorkflowStateStore.kt`, `FeatureTaskRuntimePhaseStateRecorder.kt`, `WorkflowService.kt` | Terminal fence + coupled transaction paths; repairs F1,F3,F5 partial |
| AC-005 | `FeatureTaskRuntimeWorkerStore.kt`, coordinator/reconciler/supervisor tests cited in register | Fencing and expiry paths documented |
| AC-006 | `StandalonePhaseStatusStore`, goal stop verbs | Finite lease + honest stop documented |
| AC-007 | Tests named in register (T1-01–T1-08 mapping) | Added/updated: terminal reopen, settlement conflict/terminal refusal, service contradiction |
| AC-008 | `FeatureTaskPhaseSettlementRefusalReason`, `FeatureTaskRuntimeFailureCode.PHASE_SETTLEMENT_CONFLICT`, `McpToolPayloadKeys` | No guard/baseline widening |

## Test obligations (authored)

| ID | Test location | Realistic bug |
| --- | --- | --- |
| T1-01 | `WorkflowStateStoreTest.late running update cannot reopen a completed runtime workflow` | Late running update clears terminal `finished_at` |
| T1-02 | `SqliteFeatureTaskPhaseSettlementRepositoryTest` + `FeatureTaskPhaseSettlementServiceTest` | Contradictory settlement or terminal workflow settlement |
| T1-03 | existing transactional tests + pending validate fault injection | Success before COMMIT with rolled-back persistence |
| T1-04 | `WorkerTakeoverFencingTest` | Old owner write after takeover |
| T1-05 | `FeatureTaskRuntimeWorkerCoordinatorTest`, `FeatureTaskRuntimeCrashReconcilerTest`, lease tests | Expired/mismatched ownership |
| T1-06 | `GoalRunnerControlStoreTest`, `GoalRunnerExecutionLeaseInstantTest` | Stale control renewal |
| T1-07 | `StandalonePhaseStatusStoreTest` | Superseded monitor renewal |
| T1-08 | `GoalRunnerStopVerbTest`, `JdkFeatureTaskRuntimeWorkerSupervisorTest` | False STOPPED |

## Architecture guidelines (A1–A12) applicability

| Rule | Applicability | Source result |
| --- | --- | --- |
| A1 Dependencies point inward, and each module keeps its charter | applicable | AC-008 edits stay in `runtime-ports`, `runtime-contracts`, `runtime-application`, `runtime-engine`, `runtime-infra/sqlite`, and `runtime-mcp`; no new adapter→engine shortcuts |
| A2 Every declaration has one owner | applicable | `WorkflowStateWriteResult` in ports; `TERMINAL_WORKFLOW_WRITE_REFUSED` via `terminalWorkflowWriteRefusedError` on `WorkflowStateStore.save`/`saveOrdinary`/`saveRecord`, SQL upserts in `WorkflowStateWrites`, and row-store fences `FeatureTaskWorkflowRowStore.saveFeatureTaskWorkflow` / `FeatureVerifyWorkflowStateStore.saveWorkflow`; settlement ack/kind owners unchanged; MCP reads settlement owners |
| A3 One composition root, no dependency bags | applicable | No new hand-built collaborators; settlement and workflow paths still use `DatabaseSessionFactory` / `UnitOfWork` |
| A4 Ports are purpose-built | applicable | Port result types only; no vendor SQL strings added to ports |
| A5 Abstractions earn their place, and fixes delete | applicable | `saveRecord` terminal compare consolidated into `terminalOrdinaryWriteResult`; sqlite repository conflict throw removed instead of forwarding |
| A6 Wire vocabulary has one owner | applicable | Envelope status via `WorkflowStepStatus` wire values; ack status via `FeatureTaskPhaseSettlementAckStatus`; settlement kind map key via `FeatureTaskPhaseSettlementPayloadKeys` (not `ReviewAccountingPayloadKeys`) |
| A7 Few exceptions, results for expected outcomes, never silent | applicable | Expected terminal refusal = `WorkflowStateWriteResult.TerminalRefused` / `WorkflowUpdateResult.Error.diagnosticCode` on ordinary paths, or `terminalWorkflowWriteRefusedError` (`TERMINAL_WORKFLOW_WRITE_REFUSED`) on persist-layer `saveFeatureTaskWorkflow` / `saveWorkflow` and direct `ensureWorkflowOpen` repair writes; mode-mismatch schema errors stay on `INVALID_WORKFLOW_STATE_SCHEMA`; settlement conflict = returned `Conflicting` mapped to `diagnosticCode` without sqlite throw |
| A8 Ambient effects are injected | applicable | No new `Clock`/`System` reads in this repair |
| A9 Packages are acyclic and readable | applicable | New types placed in existing packages only |
| A10 Tests live beside the code they exercise | applicable | No new test packages in this criterion; compile-follow only on touched tests |
| A11 Narrow visibility by default | applicable | New helpers `private`/`internal` on owning stores |
| A12 Inner layers stay typed | applicable | Ack status is an enum; ordinary-write outcome is a sealed port result, not raw maps |

## Guard checklist (G1–G7)

| Guard | Result |
| --- | --- |
| G1 Every rule has a status | no new `PrincipleEnforcementInventory.enforceableRules` or review-only entries |
| G2 A guard proves it reads files | no scan-set or guard source edits |
| G3 A guard proves it can fail | no architecture fixture hollowed |
| G4 Coverage comes from the module catalog | no hand-listed module changes in guards; `productionLineCeilingExemptions` still empty |
| G5 Scans see what the compiler sees | no scan evasion |
| G6 The build cannot skip a guard | source-inspected existing `repoTest` wiring only; suite not executed here |
| G7 Exceptions only shrink | no baseline, exemption, allow-list, or ceiling widening |

## Repair bytes preserved

Authoritative quoted payloads live on repair finding rows in `findings-register.md` (not test class names or fixture labels):

- **F1:** late running snapshot vs retained terminal row (`wftr-terminal-reopen` field bytes).
- **F2:** kept vs refused `envelope_json` at `(wftr-conflict, implement, 2)`.
- **F3:** refused stale-worker persist shape (no durable late envelope — fence before write).
- **F6:** refused `{"status":"completed"}` at `(wftr-settle-terminal, implement, 1)` against terminal workflow.
- **F7:** rejected `STOPPED` success token; retained pause control bytes; commit `925672c6a` is implementation provenance only.

Validate captures execution evidence; `tests_executed` remains empty here.

## Project authoring / static analysis (implement phase)

- **Configuration sources**: root `AGENTS.md`, `docs/architecture-guidelines.md`, `docs/code-principles.md`, Kotlin Spotless/Detekt via `runtime-kotlin` modules.
- **Safe commands**: not run — module-scoped Spotless/Detekt can rewrite whole modules; implement defers formatting to validate or file-scoped hooks if available.
- **tests_executed**: (empty — validate owns execution)

## Working tree note

- `decomposition-manifest.yaml`: subtask 1 resumed to workflow `wftr-20261017-072336-briq` (unblocks prior `audit_implement_fix` block).
- Prior code checkpoint on branch `feat/SKILL-422-workflow-state-correctness-audit`; this implement pass adds audit markdown, settlement admission, and regression tests.
