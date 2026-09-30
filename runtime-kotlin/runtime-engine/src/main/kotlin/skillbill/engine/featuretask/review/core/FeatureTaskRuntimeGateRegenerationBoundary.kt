package skillbill.engine.featuretask.review.core

import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
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
  val checkpoints =
    decodeCheckpointIdentitiesFromArtifact(
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES.value(record.artifacts),
    )
  val downstream = order.drop(position + 1).toSet()
  val consumer = record.currentStepId
  val latestConsumer = ledger.filter { it.phaseId == consumer }.maxByOrNull { it.sequenceNumber }
  val consumerIsSafeBoundary =
    consumer in downstream && order.getOrNull(position + 1) == consumer &&
      records[consumer]?.status != WorkflowStepStatus.COMPLETED &&
      latestConsumer?.action != FeatureTaskRuntimePhaseLedgerAction.COMPLETE &&
      checkpoints.none { it.phaseId == consumer }
  val downstreamBeyondConsumer = downstream - consumer
  val producerIsProven =
    records[producer]?.status == WorkflowStepStatus.COMPLETED &&
      latest?.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE &&
      latest.attemptCount == records[producer]?.attemptCount
  val retainedProducerCheckpoint = checkpoints.any { it.phaseId in order.take(position + 1) }
  val untouchedDownstream =
    records.keys.none { it in downstreamBeyondConsumer } &&
      ledger.none { it.phaseId in downstreamBeyondConsumer } &&
      checkpoints.none { it.phaseId in downstreamBeyondConsumer }
  val provenBoundary = position >= 0 && consumerIsSafeBoundary && producerIsProven
  if (!provenBoundary || !retainedProducerCheckpoint || !untouchedDownstream) {
    throw UnsafeFeatureTaskRuntimeRegenerationError(FeatureTaskRuntimeRegenerationRefusal.UNPROVEN_GATE_SEMANTICS)
  }
}
