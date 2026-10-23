package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunRecords
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteKind
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class DurablePhaseAttemptCountTest {
  @Test
  fun `resumed review checkpoints use the acknowledged running attempt before its output settles`() {
    val review = "review"
    val state = runState(mapOf(review to auditRecord(1).copy(phaseId = review, status = WorkflowStepStatus.BLOCKED)))
    val owner = coupledRunTransitionOwner(state, FeatureTaskRuntimeRunLoopSession(null, null))
    val beforeStart = state.progressSnapshot
    val records = InMemoryPhaseRunRecords(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), null)
    val start = reviewStart(attempt = 2)

    assertEquals(RequiredPhaseWrite.Acknowledged, owner.acknowledgeRequiredPhaseStart(records, start))

    assertEquals(2, records.loadPhaseRecords("workflow")[review]?.attemptCount)
    assertEquals(2, durablePhaseAttemptCount(state.progressSnapshot, review))
    assertEquals(2, state.phase(review).nextIteration)
    assertEquals(1, durablePhaseAttemptCount(beforeStart, review))

    state.recordCompleted(FeatureTaskRuntimePhaseOutput(review, 2, "{}"))

    assertEquals(2, durablePhaseAttemptCount(state.progressSnapshot, review))
    assertEquals(3, state.phase(review).nextIteration)

    assertEquals(
      RequiredPhaseWrite.Acknowledged,
      owner.acknowledgeRequiredPhaseStart(records, reviewStart(attempt = 3)),
    )

    assertEquals(3, durablePhaseAttemptCount(state.progressSnapshot, review))
    assertEquals(3, state.phase(review).nextIteration)
  }

  @Test
  fun `a refused review start cannot authorize a checkpoint for the refused attempt`() {
    val review = "review"
    val state = runState(mapOf(review to auditRecord(1).copy(phaseId = review, status = WorkflowStepStatus.BLOCKED)))
    val owner = coupledRunTransitionOwner(state, FeatureTaskRuntimeRunLoopSession(null, null))
    val rejection = RequiredPhaseWrite.Rejected(RequiredPhaseWriteKind.START, "workflow", review, 2)
    val records =
      object : PhaseRunRecords by InMemoryPhaseRunRecords(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), null) {
        override fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite =
          rejection
      }

    assertEquals(rejection, owner.acknowledgeRequiredPhaseStart(records, reviewStart(attempt = 2)))

    assertEquals(1, durablePhaseAttemptCount(state.progressSnapshot, review))
    assertEquals(2, state.phase(review).nextIteration)
  }

  @Test
  fun `an attempt settled in this process outranks the record loaded at process start`() {
    val state = runState(initialRecords = mapOf(AUDIT to auditRecord(attemptCount = 5)))

    state.recordCompleted(FeatureTaskRuntimePhaseOutput(phaseId = AUDIT, iteration = 6, payload = "{}"))

    assertEquals(6, durablePhaseAttemptCount(state, AUDIT))
  }

  @Test
  fun `a phase not rerun in this process keeps its durable attempt count`() {
    val state = runState(initialRecords = mapOf(AUDIT to auditRecord(attemptCount = 5)))

    assertEquals(5, durablePhaseAttemptCount(state, AUDIT))
  }

  @Test
  fun `a phase with no record falls back to its next iteration`() {
    val state = runState(initialRecords = emptyMap())

    assertEquals(1, durablePhaseAttemptCount(state, AUDIT))
  }

  private fun runState(initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>): FeatureTaskRuntimeRunState =
    FeatureTaskRuntimeRunState(
      initialRecords = initialRecords,
      transitions = FeatureTaskRuntimeTransitionDeclaration(listOf("implement", AUDIT)),
      resumeRulesFn = { PhaseResumeRules.None },
    )

  private fun reviewStart(attempt: Int) =
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = "workflow",
      phaseId = "review",
      status = WorkflowStepStatus.RUNNING.wireValue,
      attemptCount = attempt,
      resolvedAgentId = "codex",
      finished = false,
    )

  private fun auditRecord(attemptCount: Int): FeatureTaskRuntimePhaseRecord =
    FeatureTaskRuntimePhaseRecord(
      phaseId = AUDIT,
      status = WorkflowStepStatus.COMPLETED,
      attemptCount = attemptCount,
      startedAt = "2026-01-01T00:00:00Z",
      resolvedAgentId = "claude",
      finishedAt = "2026-01-01T00:01:00Z",
    )

  private companion object {
    const val AUDIT = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
  }
}
