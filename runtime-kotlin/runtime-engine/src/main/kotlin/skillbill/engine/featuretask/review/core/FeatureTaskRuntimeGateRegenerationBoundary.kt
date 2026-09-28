package skillbill.engine.featuretask.review.core

import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.slot.execution.model.AdmittedFeatureTaskRuntimeExecution
import skillbill.error.featuretask.FeatureTaskRuntimeRegenerationRefusal
import skillbill.error.featuretask.UnsafeFeatureTaskRuntimeRegenerationError
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.decodeCheckpointIdentitiesFromArtifact
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

internal fun requireAdmittedGateRegenerationBoundary(
  record: WorkflowStateSnapshot,
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
  producer: String,
  admitted: AdmittedFeatureTaskRuntimeExecution,
) {
  val order = admitted.plan.traversal.forwardPhaseIds
  val position = order.indexOf(producer)
  val ledger = decodePhaseLedger(record.artifacts)
  val latest = ledger.filter { it.phaseId == producer }.maxByOrNull { it.sequenceNumber }
  val checkpoints = decodeCheckpointIdentitiesFromArtifact(
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES.value(record.artifacts),
  )
  val downstream = order.drop(position + 1).toSet()
  if (position < 0 || record.currentStepId != producer ||
    records[producer]?.status != WorkflowStepStatus.COMPLETED ||
    latest?.action != FeatureTaskRuntimePhaseLedgerAction.COMPLETE ||
    latest.attemptCount != records[producer]?.attemptCount ||
    checkpoints.none { it.phaseId in order.take(position + 1) } ||
    records.keys.any { it in downstream } || ledger.any { it.phaseId in downstream } ||
    checkpoints.any { it.phaseId in downstream }
  ) {
    throw UnsafeFeatureTaskRuntimeRegenerationError(FeatureTaskRuntimeRegenerationRefusal.UNPROVEN_GATE_SEMANTICS)
  }
}
