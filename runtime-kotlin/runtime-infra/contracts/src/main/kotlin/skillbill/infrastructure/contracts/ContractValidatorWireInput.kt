package skillbill.infrastructure.contracts

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION
import skillbill.error.core.ShellContentContractException
import skillbill.error.featuretask.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeBuildReceiptSchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeImplementationAttemptSchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePersistenceSchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseHandoffSchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeProjectionMeasurementSchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeQuarantineSchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeSharedEvidenceProjectionSchemaError
import skillbill.error.shellcontent.InvalidGoalObservabilityEventSchemaError
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import skillbill.error.shellcontent.InvalidGoalProgressEventSchemaError
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeHandoffEnvelopeSchemaPaths
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

internal fun requireValidatorWireMap(
  payload: Any,
  sourceLabel: String,
  nonObjectError: (sourceLabel: String, reason: String) -> ShellContentContractException,
): Map<String, Any?> =
  JsonCodec.anyToStringAnyMap(payload)
    ?: throw nonObjectError(sourceLabel, "<root> must be an object.")

internal fun requireFeatureTaskRuntimeArtifactMap(
  kind: FeatureTaskRuntimeWireArtifactKind,
  payload: FeatureTaskRuntimeWorkflowArtifactMap,
  sourceLabel: String,
): Map<String, Any?> =
  if (payload.isObject) {
    payload
  } else {
    throw featureTaskRuntimeWireArtifactNonObjectError(kind, sourceLabel, "<root> must be an object.")
  }

internal fun featureTaskRuntimeWireArtifactNonObjectError(
  kind: FeatureTaskRuntimeWireArtifactKind,
  sourceLabel: String,
  reason: String,
): ShellContentContractException =
  when (kind) {
    FeatureTaskRuntimeWireArtifactKind.QUARANTINE_RECORD ->
      InvalidFeatureTaskRuntimeQuarantineSchemaError(sourceLabel = sourceLabel, reason = reason)
    FeatureTaskRuntimeWireArtifactKind.IMPLEMENTATION_ATTEMPT ->
      InvalidFeatureTaskRuntimeImplementationAttemptSchemaError(sourceLabel = sourceLabel, reason = reason)
    FeatureTaskRuntimeWireArtifactKind.BUILD_RECEIPT ->
      InvalidFeatureTaskRuntimeBuildReceiptSchemaError(
        sourceLabel = sourceLabel,
        reason = reason,
        payloadFreeReason = reason,
      )
    FeatureTaskRuntimeWireArtifactKind.HANDOFF_DECLARATION ->
      InvalidFeatureTaskRuntimePhaseHandoffSchemaError(sourceLabel = sourceLabel, reason = reason)
    FeatureTaskRuntimeWireArtifactKind.HANDOFF_PERSISTENCE_RECORD ->
      InvalidFeatureTaskRuntimePersistenceSchemaError(sourceLabel = sourceLabel, reason = reason)
    FeatureTaskRuntimeWireArtifactKind.HANDOFF_MEASUREMENT ->
      InvalidFeatureTaskRuntimeProjectionMeasurementSchemaError(sourceLabel = sourceLabel, reason = reason)
    FeatureTaskRuntimeWireArtifactKind.HANDOFF_SHARED_EVIDENCE_PROJECTION ->
      InvalidFeatureTaskRuntimeSharedEvidenceProjectionSchemaError(sourceLabel = sourceLabel, reason = reason)
    FeatureTaskRuntimeWireArtifactKind.HANDOFF_ENVELOPE ->
      InvalidFeatureTaskRuntimeHandoffProjectionError(
        context =
          InvalidFeatureTaskRuntimeHandoffProjectionContext(
            workflowId = null,
            consumerPhaseId = sourceLabel,
            projectionName = "<root>",
            projectionContractId = FeatureTaskRuntimeHandoffEnvelopeSchemaPaths.EXPECTED_SCHEMA_ID,
            projectionContractVersion = FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION,
            failureKind = FeatureTaskRuntimeHandoffProjectionFailureKind.SCHEMA_INVALID,
            reason = reason,
          ),
      )
    FeatureTaskRuntimeWireArtifactKind.GOAL_PROGRESS_EVENT ->
      InvalidGoalProgressEventSchemaError(
        sourceLabel = sourceLabel,
        fieldPath = "<root>",
        reason = reason,
      )
    FeatureTaskRuntimeWireArtifactKind.GOAL_OBSERVABILITY_EVENT ->
      InvalidGoalObservabilityEventSchemaError(
        sourceLabel = sourceLabel,
        fieldPath = "<root>",
        reason = reason,
      )
    FeatureTaskRuntimeWireArtifactKind.GOAL_PLANNING_PREPARATION_ENVELOPE ->
      InvalidGoalPlanningPreparationSchemaError(
        sourceLabel = sourceLabel,
        fieldPath = "<root>",
        reason = reason,
      )
  }
