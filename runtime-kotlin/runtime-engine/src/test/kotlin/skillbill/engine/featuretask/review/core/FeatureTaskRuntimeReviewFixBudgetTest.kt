package skillbill.engine.featuretask.review.core

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeContinuationKind
import skillbill.engine.featuretask.runner.INVOKED_AGENT
import skillbill.engine.featuretask.runner.RunnerHarness
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.SESSION_ID
import skillbill.engine.featuretask.runner.WORKFLOW_ID
import skillbill.engine.featuretask.runner.runnerHarness
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeatureTaskRuntimeReviewFixBudgetTest {
  @Test
  fun `a repair round that later completed spent no budget and an exhaustion stays readable after a resume`() {
    val harness = runnerHarness()
    val budget = FeatureTaskRuntimeReviewFixBudget(harness.recorder, harness.goalContinuationRecorder)
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.appendLoopEdge(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID, edgeIteration = 1)

    assertEquals(
      false,
      budget.reviewFixCapExhaustion(WORKFLOW_ID),
      "entering the repair loop once is an ordinary repair, not a spent budget",
    )

    harness.appendLoopCapExhausted(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID)
    harness.appendLedger(FeatureTaskRuntimePhaseLedgerAction.RESUME)
    harness.appendLedger(FeatureTaskRuntimePhaseLedgerAction.COMPLETE)

    assertEquals(
      true,
      budget.reviewFixCapExhaustion(WORKFLOW_ID),
      "an exhaustion a later resume worked past is still an exhaustion that happened",
    )
  }

  @Test
  fun `a run with no durable ledger reports both measurements unavailable rather than intact`() {
    val harness = runnerHarness(runtimeConfig = RuntimeHarnessConfig(seedDurableWorkflow = false))
    val budget = FeatureTaskRuntimeReviewFixBudget(harness.recorder, harness.goalContinuationRecorder)

    assertNull(
      budget.reviewFixCapExhaustion(WORKFLOW_ID),
    )
    assertNull(
      budget.auditGapIterationCount(WORKFLOW_ID),
    )
  }

  @Test
  fun `a relaunched stateless audit counts as a gap round and a satisfied first audit stays a measured zero`() {
    val harness = runnerHarness()
    val budget = FeatureTaskRuntimeReviewFixBudget(harness.recorder, harness.goalContinuationRecorder)
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.appendLoopEdge(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID, edgeIteration = 1)
    harness.appendAuditContinuation(FeatureTaskRuntimeContinuationKind.IMPLEMENTATION_CONTINUATION)

    assertEquals(
      0,
      budget.auditGapIterationCount(WORKFLOW_ID),
      "a run that never re-entered the audit measured zero rounds; another loop or kind is not one",
    )

    harness.appendAuditContinuation(FeatureTaskRuntimeContinuationKind.AUDIT_AC_RETRY)
    harness.appendLoopEdge(FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_REPAIR_LOOP_ID, edgeIteration = 1)

    assertEquals(
      2,
      budget.auditGapIterationCount(WORKFLOW_ID),
      "the audit_gap backward edge is retired, so counting only it reported every run as first-pass",
    )
  }
}

private fun RunnerHarness.appendLedger(
  action: FeatureTaskRuntimePhaseLedgerAction,
  phaseId: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
  detail: FeatureTaskRuntimePhaseLedgerRequest.() -> FeatureTaskRuntimePhaseLedgerRequest = { this },
) = recorder.appendLedgerEntry(
  FeatureTaskRuntimePhaseLedgerRequest(
    workflowId = WORKFLOW_ID,
    action = action,
    phaseId = phaseId,
    attemptCount = 1,
    resolvedAgentId = INVOKED_AGENT,
  ).detail(),
)

private fun RunnerHarness.appendAuditContinuation(kind: FeatureTaskRuntimeContinuationKind) =
  appendLedger(
    FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION,
    phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
  ) {
    copy(
      blockedReason = FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX + kind.wireValue,
      fixLoopIteration = 1,
    )
  }

private fun RunnerHarness.appendLoopEdge(
  loopId: String,
  edgeIteration: Int,
) = appendLedger(FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE) {
  copy(loopId = loopId, edgeIteration = edgeIteration)
}

private fun RunnerHarness.appendLoopCapExhausted(loopId: String) =
  appendLedger(FeatureTaskRuntimePhaseLedgerAction.LOOP_CAP_EXHAUSTED) {
    copy(loopId = loopId, edgeIteration = 1)
  }
