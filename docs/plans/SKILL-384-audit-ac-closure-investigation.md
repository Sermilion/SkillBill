# Why SKILL-384 does not close its acceptance criteria

## Result at investigation time

Investigation on 2026-09-29 inspected the durable child workflow
`wftr-20260929-182426-w78b`, the installed runtime source in the base worktree,
and the repaired feature checkout. Database inspection used a read-only connection.
No workflow reset, acceptance edit, or feature-source edit occurred.

Audit 6 finished at 20:29 UTC. It reported AC-002, AC-003, AC-004, AC-006, and
AC-008. The preceding audit had three open criteria, AC-002 through AC-004.
The installed runtime blocked on the increase from three to five. Subtasks 1 and
2 remain complete. The goal is idle and blocked in subtask 3's audit.

## Confirmed runtime defects

### Partial repair history is saved but missing from the next repair prompt

`PhaseAttemptContinuations.settleIncompleteWork` records the partial report,
increments the attempt, clears `loop.priorCorrection`, and permits another
launch. It does not use the incomplete-work corrective reason or enforce
`singleAgentSession` on this path.

`PhaseLaunchPreparation.composeLaunchPromptInputs` loads
`implementationContinuation` from the durable attempt history. However,
`AcceptanceAuditStrategy.promptSections` calls
`AuditImplementFixPromptSections.sections()` without passing those inputs. That
function fills only the directive and value instructions. Its continuation section
is empty. `ImplementationPromptSections.continuationFor` consumes the history for
ordinary implementation and simplification, but the audit-repair prompt has no
corresponding consumer.

Consequently a fresh repair launch receives the last accepted audit and plan,
plus the current checkout, without the previous repair report's explicit remaining
work. It must rediscover and reconcile the partial edits. This is a confirmed
prompt handoff omission, not an inference from the elapsed time.

Since the reinstall, repair attempt 12 resumed at 19:48 UTC. The durable ledger
then records implementation continuations into attempts 13 through 20, at
19:54, 19:57, 20:03, 20:08, 20:12, 20:16, 20:19, and 20:23 UTC. No intervening audit
finished. Attempt 20 finally emitted the standalone completion marker at 20:27.

The audit progress guard runs only after repair declares completion. These
incomplete repair launches never reached that guard. The repair path therefore
requires its own session and continuation enforcement.

## Confirmed implementation gaps

### AC2 and AC3 keep receiving replacement access paths

The accepted plan requires exclusive ownership of coupled mutations and detached
observations. It explicitly prohibits getter collections and downcast paths.
The repairs change where callers obtain the authority while retaining access.

The repaired feature source contains these concrete paths:

- `RunLoopCoupledStateAccess.runLoopCoupledProgress()` casts the progress
  observation back to `FeatureTaskRuntimeRunState`. The session equivalent returns
  the mutable run-loop session.
- `RunLoopSettlementCoupling` returns both mutable objects to settlement helpers.
  The cast and returned objects remain after the earlier getter was removed.
- `PhaseAttemptRunHost` hides its backing state field but exposes records, goal,
  settlements, checkpoints, gates, strategy lookup, fan-out, and attempt execution.
  Its `RunLoopSettlementHost` implementation returns mutable progress and session.
- `PhaseQualityGateCycleScope` and `PhaseRuntimeFinalizationScope` expose
  `runLoopAttemptHost()`. An engine consumer can cast to the concrete scope and
  recover the broad host. Kotlin `internal` permits access throughout the module.
- `RuntimeCommitCycle.complete` obtains mutable progress through
  `finalizationCoupledProgress()` for its block path.

These facts support the audit's rejection of AC2 and AC3. A private field and a
narrow interface do not satisfy the criterion while reachable helpers restore the
original authority. The plan already names this requirement; relaxing its wording
would conceal unfinished work.

### AC4 remains open, though some audit wording needs stronger proof

Gate and finalization contexts still expose recorder, goal-recorder, gate, and
checkpoint aggregates. The same concrete-scope-to-host paths remain available.
`PhaseAttemptRunHost.recordReviewRunForRunStatePorts` forwards to backing review
state without a review-specific accepted-step check in that method. These are
concrete reachable operations to account for in the capability census.

The latest repair did separate review bindings from ordinary agent markers. A
cast performed inside a review consumer, such as `CodeReviewSlot`, does not alone
prove that a non-review consumer can obtain review mutation. Audit 6's wording
mentions those casts without giving a complete consumer-to-operation path. That
part needs a specific access path or should be removed from the finding. The
verified gate/finalization gaps still prevent AC4 from closing.

### AC6 has an incomplete guard, and repair keeps excluding it

The stored audit 5 declares AC6 and AC7 test-only and omits them. That audit remains
the repair input throughout attempts 12 through 20. Repair reports 17 through 20
repeat the exclusion; report 19 explicitly excludes both the synthetic cases and
the production scan. AC6 also requires the guard implementation, which the revised
repair directive explicitly includes in scope even under a test source set. The
prompt clarification did not refresh the older audit's contradictory scope claim.
Audit 6 finally evaluates the guard implementation and reopens AC6. This is a
scope mismatch between saved audit input, the revised instruction, and repair's
interpretation, not evidence that every omitted guard gap was created by repair.

`StrategyCapabilityBoundaryArchitectureTest` excludes the `slot/state` and
`slot/attempt` roots. `StrategyCapabilityAuthorityGraph.violations` receives one
source string at a time and checks direct declaration and typed member patterns.
It does not build a cross-file helper/factory/callback operation graph. Its name
therefore overstates what the implementation proves. This leaves the transitive
access required by AC6 unimplemented.

### AC8 documentation has fallen behind the source

`ARCHITECTURE.md` still describes `PhaseAttemptCollaboratorContext` and
`PhaseStepCall.boundReviewStep`, and claims `PhaseAttemptEnvironment` exports
`PhaseRunState`. Other sections describe the newer interfaces. The accumulated
census preserves prior findings but also records remaining broad authority. Audit
6 correctly asks for a reconciled final ownership description.

## Completion claim does not establish closure

Reports 17 through 19 explicitly leave AC2 through AC4 work for another repair or
audit. Report 20 still acknowledges that gate/finalization contexts are broader
than named operations, then emits `audit_repair_complete: true`. Exact marker
matching prevents accidental completion from a refusal, but cannot establish the
truth of a self-reported completion claim. The fresh audit appropriately checks
that claim against source and found five open criteria.

## Recommended correction

1. Fix the base runtime's incomplete-repair path. Enforce its single-session
   policy and block on unfinished final output instead of launching fresh repair
   agents indefinitely. Preserve the partial report and durable attempts.
2. Render the saved audit-repair continuation on an authorized resume, including
   the latest remaining gaps and already-applied work. Pass prompt inputs into the
   audit-repair sections and retain the corrective reason. Prove the delivered
   prompt contains that history.
3. Give the feature repair a finite list of actual consumer-to-operation paths.
   Finish owner operations and remove the raw state, session, recorder, gate, and
   host recovery paths. Register concrete closure evidence for each path. Keep
   legitimate shared-loop coordination behind its existing owner.
4. Repair the AC6 guard implementation, keep its regression cases in the owning
   test phase, and reconcile AC8 documentation after source converges.
5. Preserve the current blocked workflow and completed subtasks. Resume after the
   runtime and feature work address these findings. Do not reset the audit count,
   weaken acceptance criteria, or claim casts inside review consumers prove a leak
   without a concrete non-review access path.

## Evidence locations

Runtime prompt and continuation evidence is in the base worktree:

- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/slot/audit/AcceptanceAuditStrategy.kt`
- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/slot/audit/AuditImplementFixPromptSections.kt`
- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/slot/attempt/PhaseAttemptContinuations.kt`
- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/slot/attempt/PhaseLaunchPreparation.kt`
- `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/slot/implementation/ImplementationPromptSections.kt`

Capability and guard evidence is in `/home/sermilion/StudioProjects/skill-bill`,
on `feature/SKILL-384-workflow-skeleton-execution-contracts`.
Read-only copies of settled audit and repair reports are in
`/tmp/SKILL-384-audit-investigation`. The current blocking reason is available
through `skill-bill goal status SKILL-384 --repo-root /home/sermilion/StudioProjects/skill-bill`.

## Runtime correction

The base runtime now enforces single-session settlement for incomplete repair output, includes saved reports in resumed repair prompts, and retains the latest valid audit report when progress blocks. An explicit operator retry can establish one new audit baseline. Criterion validation still applies, and the next automatic round must shrink.

Repair guidance now requires source evidence through actual consumer-to-operation paths. It also requires the criterion's guard implementation even when an older report classified it as test-only. The feature acceptance criteria remain unchanged.
