package skillbill.ports.taskruntime

import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

fun FeatureTaskRuntimeWireArtifactValidator.validateQuarantineRecord(
  quarantineRecord: Any,
  sourceLabel: String,
) {
  validate(
    FeatureTaskRuntimeWireArtifactKind.QUARANTINE_RECORD,
    FeatureTaskRuntimeWorkflowArtifactMap.from(quarantineRecord),
    sourceLabel,
  )
}

fun FeatureTaskRuntimeWireArtifactValidator.validateImplementationAttemptRecord(
  attemptRecord: Any,
  sourceLabel: String,
) {
  validate(
    FeatureTaskRuntimeWireArtifactKind.IMPLEMENTATION_ATTEMPT,
    FeatureTaskRuntimeWorkflowArtifactMap.from(attemptRecord),
    sourceLabel,
  )
}

fun FeatureTaskRuntimeWireArtifactValidator.validateBuildReceipt(
  buildReceipt: Any,
  sourceLabel: String,
) {
  validate(
    FeatureTaskRuntimeWireArtifactKind.BUILD_RECEIPT,
    FeatureTaskRuntimeWorkflowArtifactMap.from(buildReceipt),
    sourceLabel,
  )
}

fun FeatureTaskRuntimeWireArtifactValidator.validateDeclaration(
  payload: Any,
  sourceLabel: String,
) {
  validate(
    FeatureTaskRuntimeWireArtifactKind.HANDOFF_DECLARATION,
    FeatureTaskRuntimeWorkflowArtifactMap.from(payload),
    sourceLabel,
  )
}

fun FeatureTaskRuntimeWireArtifactValidator.validatePersistenceRecord(
  payload: Any,
  sourceLabel: String,
) {
  validate(
    FeatureTaskRuntimeWireArtifactKind.HANDOFF_PERSISTENCE_RECORD,
    FeatureTaskRuntimeWorkflowArtifactMap.from(payload),
    sourceLabel,
  )
}

fun FeatureTaskRuntimeWireArtifactValidator.validateMeasurement(
  payload: Any,
  sourceLabel: String,
) {
  validate(
    FeatureTaskRuntimeWireArtifactKind.HANDOFF_MEASUREMENT,
    FeatureTaskRuntimeWorkflowArtifactMap.from(payload),
    sourceLabel,
  )
}

fun FeatureTaskRuntimeWireArtifactValidator.validateSharedEvidenceProjection(
  payload: Any,
  sourceLabel: String,
) {
  validate(
    FeatureTaskRuntimeWireArtifactKind.HANDOFF_SHARED_EVIDENCE_PROJECTION,
    FeatureTaskRuntimeWorkflowArtifactMap.from(payload),
    sourceLabel,
  )
}

fun FeatureTaskRuntimeWireArtifactValidator.validateEnvelope(
  envelope: Any,
  workflowId: String? = null,
) {
  validate(
    FeatureTaskRuntimeWireArtifactKind.HANDOFF_ENVELOPE,
    FeatureTaskRuntimeWorkflowArtifactMap.from(envelope),
    workflowId ?: "handoff-envelope",
  )
}

fun FeatureTaskRuntimeWireArtifactValidator.validateGoalProgressEvent(
  event: Any,
  sourceLabel: String,
) {
  validate(
    FeatureTaskRuntimeWireArtifactKind.GOAL_PROGRESS_EVENT,
    FeatureTaskRuntimeWorkflowArtifactMap.from(event),
    sourceLabel,
  )
}

fun FeatureTaskRuntimeWireArtifactValidator.validateGoalObservabilityEvent(
  event: Any,
  sourceLabel: String,
) {
  validate(
    FeatureTaskRuntimeWireArtifactKind.GOAL_OBSERVABILITY_EVENT,
    FeatureTaskRuntimeWorkflowArtifactMap.from(event),
    sourceLabel,
  )
}

fun FeatureTaskRuntimeWireArtifactValidator.validateGoalPlanningPreparationEnvelope(
  envelope: Any,
  sourceLabel: String,
) {
  validate(
    FeatureTaskRuntimeWireArtifactKind.GOAL_PLANNING_PREPARATION_ENVELOPE,
    FeatureTaskRuntimeWorkflowArtifactMap.from(envelope),
    sourceLabel,
  )
}
