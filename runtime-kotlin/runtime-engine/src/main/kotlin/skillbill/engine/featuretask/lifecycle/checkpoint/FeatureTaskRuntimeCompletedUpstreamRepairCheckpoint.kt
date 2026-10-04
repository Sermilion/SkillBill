package skillbill.engine.featuretask.lifecycle.checkpoint

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.engine.featuretask.model.subtask.CompletedUpstreamRepairRequest
import skillbill.engine.featuretask.runner.missingUpstream
import skillbill.engine.featuretask.runner.phaseDeclaration
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.ZoneOffset

private const val COMPLETED_UPSTREAM_MISSING_OUTPUT_BLOCK_REASON = "completed_upstream_missing_output"

internal fun qualityGateOmittedStepIds(selection: FeatureTaskRuntimeQualityGateSelection): Set<String> =
  selection.omittedStepIds

fun phasesToReopenForCompletedUpstreamRepair(
  request: CompletedUpstreamRepairRequest,
  recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
): List<String> {
  val phaseRecords = request.phaseRecords
  val resumePhaseId = request.resumePhaseId
  val featureSize = request.featureSize
  val omittedStepIds = qualityGateOmittedStepIds(request.qualityGateSelection)
  val stepOrder = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds
  return when {
    phaseRecords[resumePhaseId]?.status?.workflowStepStatus() == WorkflowStepStatus.BLOCKED -> listOf(resumePhaseId)
    else ->
      buildList {
        add(resumePhaseId)
        phaseRecords.forEach { (phaseId, record) ->
          if (record.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED) {
            val missing =
              missingUpstream(
                phaseDeclaration(phaseId, featureSize, omittedStepIds),
                recordedOutputs,
              )
            if (missing?.contains(resumePhaseId) == true) add(phaseId)
          }
        }
      }.distinct().sortedBy { stepOrder.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
  }
}

fun completedUpstreamRepairWorkflowUpdate(
  request: CompletedUpstreamRepairRequest,
  phasesToReopen: List<String>,
  reopenedRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  retryEntry: FeatureTaskRuntimePhaseLedgerEntry,
): WorkflowUpdateInput =
  WorkflowUpdateInput(
    workflowStatus = WorkflowStatus.RUNNING,
    currentStepId = request.resumePhaseId,
    stepUpdates =
      WorkflowStepUpdates.from(
        phasesToReopen.map { phaseId ->
          mapOf(
            SharedPayloadKeys.STEP_ID to phaseId,
            SharedPayloadKeys.STATUS to WorkflowStepStatus.PENDING.wireValue,
            WorkflowWirePayloadKeys.ATTEMPT_COUNT to 0,
          )
        },
      ),
    artifactsPatch =
      WorkflowArtifactPatch.from(
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
            reopenedRecords.mapValues { (_, record) -> record.asWorkflowArtifactEntry() },
          ),
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.entry(
            (request.ledger.map { it.asWorkflowArtifactEntry() } + retryEntry.asWorkflowArtifactEntry()).takeLast(
              FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
            ),
          ),
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY.entry(
            FeatureTaskRuntimeOperatorBlockRetry(
              phaseId = request.resumePhaseId,
              reason = request.reason,
              retriedAt = request.clock.instant().atOffset(ZoneOffset.UTC).toString(),
            ).asWorkflowArtifactEntry(
              previousBlockedReason = COMPLETED_UPSTREAM_MISSING_OUTPUT_BLOCK_REASON,
              reopenedPhaseIds = phasesToReopen,
            ),
          ),
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME.entry(null),
        ),
      ),
    sessionId = "",
  )

fun completedUpstreamRepairRetryEntry(request: CompletedUpstreamRepairRequest): FeatureTaskRuntimePhaseLedgerEntry =
  FeatureTaskRuntimePhaseLedgerEntry(
    action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
    sequenceNumber = (request.ledger.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
    timestamp = request.clock.instant().atOffset(ZoneOffset.UTC).toString(),
    phaseId = request.resumePhaseId,
    attemptCount = requireNotNull(request.phaseRecords[request.resumePhaseId]).attemptCount,
    resolvedAgentId = requireNotNull(request.phaseRecords[request.resumePhaseId]).resolvedAgentId,
  )

fun settledPhaseOutputs(
  phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
): List<FeatureTaskRuntimePhaseOutput> =
  phaseRecords.values.mapNotNull { record ->
    record.outputArtifact?.takeIf(String::isNotBlank)?.let { artifact ->
      FeatureTaskRuntimePhaseOutput(
        phaseId = record.phaseId,
        iteration = record.attemptCount,
        payload = artifact,
      )
    }
  }
