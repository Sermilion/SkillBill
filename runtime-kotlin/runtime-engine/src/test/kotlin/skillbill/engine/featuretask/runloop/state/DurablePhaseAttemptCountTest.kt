package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals

class DurablePhaseAttemptCountTest {
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
