package skillbill.engine.featuretask.lifecycle.subtask

import skillbill.engine.featuretask.lifecycle.checkpoint.completedUpstreamRepairWorkflowUpdate
import skillbill.engine.featuretask.model.subtask.CompletedUpstreamRepairRequest
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

private val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-23T10:15:30Z"), ZoneOffset.UTC)
private const val RETRIED_AT = "2026-09-23T10:15:30Z"

class FeatureTaskRuntimeCompletedUpstreamRepairArtifactBytesTest {
  @Test
  fun `the operator-block-retry artifact and step updates keep their pre-change wire shape`() {
    val request =
      CompletedUpstreamRepairRequest(
        phaseRecords =
          mapOf(
            "implement" to
              FeatureTaskRuntimePhaseRecord(
                phaseId = "implement",
                status = WorkflowStepStatus.BLOCKED,
                attemptCount = 2,
                startedAt = RETRIED_AT,
                resolvedAgentId = "agent-implement",
              ),
          ),
        ledger = emptyList(),
        featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
        resumePhaseId = "implement",
        reason = "upstream plan output went missing",
        clock = FIXED_CLOCK,
      )
    val retryEntry =
      FeatureTaskRuntimePhaseLedgerEntry(
        action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
        sequenceNumber = 0,
        timestamp = RETRIED_AT,
        phaseId = "implement",
        attemptCount = 2,
        resolvedAgentId = "agent-implement",
      )

    val update = completedUpstreamRepairWorkflowUpdate(request, listOf("implement"), emptyMap(), retryEntry)

    assertEquals(
      listOf(mapOf("step_id" to "implement", "status" to "pending", "attempt_count" to 0)),
      requireNotNull(update.stepUpdates).asEntries(),
    )
    assertEquals(
      mapOf(
        "phase_id" to "implement",
        "reason" to "upstream plan output went missing",
        "retried_at" to RETRIED_AT,
        "previous_blocked_reason" to "completed_upstream_missing_output",
        "reopened_phase_ids" to listOf("implement"),
      ),
      requireNotNull(update.artifactsPatch)["operator_block_retry"],
    )
    assertEquals(
      listOf(
        "feature_task_runtime_phase_records",
        "feature_task_runtime_phase_ledger",
        "operator_block_retry",
        "goal_continuation_outcome",
      ),
      requireNotNull(update.artifactsPatch).keys.toList(),
    )
  }
}
