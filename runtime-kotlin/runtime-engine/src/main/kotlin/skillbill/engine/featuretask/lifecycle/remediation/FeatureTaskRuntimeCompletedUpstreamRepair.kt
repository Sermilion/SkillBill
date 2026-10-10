package skillbill.engine.featuretask.lifecycle.remediation

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys
import skillbill.engine.featuretask.lifecycle.checkpoint.completedUpstreamRepairRetryEntry
import skillbill.engine.featuretask.lifecycle.checkpoint.completedUpstreamRepairWorkflowUpdate
import skillbill.engine.featuretask.lifecycle.checkpoint.phasesToReopenForCompletedUpstreamRepair
import skillbill.engine.featuretask.lifecycle.checkpoint.qualityGateOmittedStepIds
import skillbill.engine.featuretask.lifecycle.checkpoint.settledPhaseOutputs
import skillbill.engine.featuretask.model.subtask.CompletedUpstreamRepairRequest
import skillbill.engine.featuretask.phase.record.asPendingForOperatorResume
import skillbill.engine.featuretask.runner.missingUpstream
import skillbill.engine.featuretask.runner.phaseDeclaration
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.decodeRunInvariantsFromArtifact
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal fun featureSizeFromArtifacts(artifacts: Map<String, Any?>): FeatureTaskRuntimeFeatureSize {
  val raw =
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RUN_INVARIANTS.value(artifacts) as? Map<*, *>
      ?: return FeatureTaskRuntimeFeatureSize.MEDIUM
  val invariantsMap = JsonCodec.anyToStringAnyMap(raw) ?: return FeatureTaskRuntimeFeatureSize.MEDIUM
  return decodeRunInvariantsFromArtifact(invariantsMap)?.featureSize
    ?: FeatureTaskRuntimeFeatureSize.MEDIUM
}

internal fun qualityGateSelectionFromArtifacts(
  artifacts: Map<String, Any?>,
  continuationSelection: FeatureTaskRuntimeQualityGateSelection?,
): FeatureTaskRuntimeQualityGateSelection {
  val payload =
    JsonCodec.anyToStringAnyMap(
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(artifacts),
    )
  val token = payload?.get(FeatureTaskRuntimeExecutionPlanKeys.QUALITY_GATE_SELECTION) as? String
  return token?.let(FeatureTaskRuntimeQualityGateSelection::fromWire)
    ?: continuationSelection
    ?: FeatureTaskRuntimeQualityGateSelection.VALIDATE
}

fun diagnoseUnsettledCompletedUpstreamPhaseId(
  phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  featureSize: FeatureTaskRuntimeFeatureSize,
  qualityGateSelection: FeatureTaskRuntimeQualityGateSelection =
    FeatureTaskRuntimeQualityGateSelection.VALIDATE,
): String? {
  val recordedOutputs = settledPhaseOutputs(phaseRecords)
  val stepOrder = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds
  val blockedConsumers =
    phaseRecords.filterValues {
      it.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED
    }.keys
  for (consumerPhaseId in blockedConsumers) {
    val declaration = phaseDeclaration(consumerPhaseId, featureSize, qualityGateOmittedStepIds(qualityGateSelection))
    val blockedReason = phaseRecords[consumerPhaseId]?.blockedReason.orEmpty()
    val missing =
      missingUpstream(declaration, recordedOutputs)
        ?.filter { upstreamId ->
          val upstream = phaseRecords[upstreamId] ?: return@filter false
          upstream.outputArtifact.isNullOrBlank()
        }
    if (!missing.isNullOrEmpty()) {
      return missing.minBy { stepOrder.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
    }
    if (
      blockedReason.contains("upstream output", ignoreCase = true) &&
      blockedReason.contains("not present", ignoreCase = true)
    ) {
      return consumerPhaseId
    }
  }
  return null
}

fun buildCompletedUpstreamMissingOutputRepair(request: CompletedUpstreamRepairRequest): WorkflowUpdateInput {
  val recordedOutputs = settledPhaseOutputs(request.phaseRecords)
  val phasesToReopen = phasesToReopenForCompletedUpstreamRepair(request, recordedOutputs)
  val reopenedRecords = LinkedHashMap(request.phaseRecords)
  phasesToReopen.forEach { phaseId ->
    val existing =
      requireNotNull(reopenedRecords[phaseId]) {
        "Cannot reopen missing phase record '$phaseId'."
      }
    reopenedRecords[phaseId] = existing.asPendingForOperatorResume()
  }
  return completedUpstreamRepairWorkflowUpdate(
    request,
    phasesToReopen,
    reopenedRecords,
    completedUpstreamRepairRetryEntry(request),
  )
}
