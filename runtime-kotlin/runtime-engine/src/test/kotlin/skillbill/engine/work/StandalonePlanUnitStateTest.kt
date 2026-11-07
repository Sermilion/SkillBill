package skillbill.engine.work

import skillbill.ports.idestatus.model.StandalonePlanUnitState
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class StandalonePlanUnitStateTest {
  @Test
  fun `blocked records take precedence over saved output and only unfinished records are writing`() {
    val started = Instant.parse("2026-11-06T00:00:00Z")
    val completed = phaseRecord(WorkflowStepStatus.COMPLETED, started, started, "saved")
    val blockedWithOutput = phaseRecord(WorkflowStepStatus.BLOCKED, started, started, "saved")
    val blockedByReason = phaseRecord(WorkflowStepStatus.COMPLETED, started, started, "saved", "conflict")
    val writing = phaseRecord(WorkflowStepStatus.RUNNING, started, null, null)
    val failed = phaseRecord(WorkflowStepStatus.FAILED, started, started, null)

    assertEquals(StandalonePlanUnitState.SAVED, standalonePlanUnitState(completed))
    assertEquals(StandalonePlanUnitState.BLOCKED, standalonePlanUnitState(blockedWithOutput))
    assertEquals(StandalonePlanUnitState.BLOCKED, standalonePlanUnitState(blockedByReason))
    assertEquals(StandalonePlanUnitState.WRITING, standalonePlanUnitState(writing))
    assertEquals(StandalonePlanUnitState.QUEUED, standalonePlanUnitState(failed))
    assertEquals(StandalonePlanUnitState.QUEUED, standalonePlanUnitState(null))
  }

  private fun phaseRecord(
    status: WorkflowStepStatus,
    startedAt: Instant,
    finishedAt: Instant?,
    outputArtifact: String?,
    blockedReason: String? = null,
  ) = FeatureTaskRuntimePhaseRecord(
    phaseId = "plan_subtasks:1",
    status = status,
    attemptCount = 1,
    startedAt = startedAt,
    finishedAt = finishedAt,
    resolvedAgentId = "agent",
    outputArtifact = outputArtifact,
    blockedReason = blockedReason,
  )
}
