package skillbill.infrastructure.contracts

import me.tatarka.inject.annotations.Inject
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.infrastructure.contracts.workflow.featuretask.AcceptanceCriterionRepairAuxiliarySchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.AcceptanceCriterionRepairStateSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeBuildReceiptSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeHandoffEnvelopeSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeImplementationAttemptSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimePersistenceSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimePhaseHandoffSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeProjectionMeasurementSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeQuarantineSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator
import skillbill.infrastructure.contracts.workflow.goal.GoalObservabilityEventSchemaValidator
import skillbill.infrastructure.contracts.workflow.goal.GoalPlanningPreparationSchemaValidator
import skillbill.infrastructure.contracts.workflow.goal.GoalProgressEventSchemaValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimeExecutionPlanValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputMigration
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

@Inject
class FeatureTaskRuntimeWireArtifactValidator(
  private val executionPlans: FeatureTaskRuntimeExecutionPlanValidator,
  private val phaseOutputs: FeatureTaskRuntimePhaseOutputMigration,
) : FeatureTaskRuntimeWireArtifactValidator {
  override fun handoffEnvelopeRejection(
    payload: FeatureTaskRuntimeWorkflowArtifactMap,
    sourceLabel: String,
  ): InvalidFeatureTaskRuntimeHandoffProjectionContext? =
    FeatureTaskRuntimeHandoffEnvelopeSchemaValidator.rejection(
      requireFeatureTaskRuntimeArtifactMap(FeatureTaskRuntimeWireArtifactKind.HANDOFF_ENVELOPE, payload, sourceLabel),
      sourceLabel,
    )

  override fun validate(
    kind: FeatureTaskRuntimeWireArtifactKind,
    payload: FeatureTaskRuntimeWorkflowArtifactMap,
    sourceLabel: String,
  ) {
    val wireMap = requireFeatureTaskRuntimeArtifactMap(kind, payload, sourceLabel)
    when (kind) {
      FeatureTaskRuntimeWireArtifactKind.QUARANTINE_RECORD ->
        FeatureTaskRuntimeQuarantineSchemaValidator.validate(wireMap, sourceLabel)
      FeatureTaskRuntimeWireArtifactKind.IMPLEMENTATION_ATTEMPT ->
        FeatureTaskRuntimeImplementationAttemptSchemaValidator.validate(wireMap, sourceLabel)
      FeatureTaskRuntimeWireArtifactKind.BUILD_RECEIPT ->
        FeatureTaskRuntimeBuildReceiptSchemaValidator.validate(wireMap, sourceLabel)
      FeatureTaskRuntimeWireArtifactKind.HANDOFF_DECLARATION ->
        FeatureTaskRuntimePhaseHandoffSchemaValidator.validate(wireMap, sourceLabel)
      FeatureTaskRuntimeWireArtifactKind.HANDOFF_PERSISTENCE_RECORD ->
        FeatureTaskRuntimePersistenceSchemaValidator.validate(wireMap, sourceLabel)
      FeatureTaskRuntimeWireArtifactKind.HANDOFF_MEASUREMENT ->
        FeatureTaskRuntimeProjectionMeasurementSchemaValidator.validate(wireMap, sourceLabel)
      FeatureTaskRuntimeWireArtifactKind.HANDOFF_SHARED_EVIDENCE_PROJECTION ->
        FeatureTaskRuntimeSharedEvidenceProjectionSchemaValidator.validate(wireMap, sourceLabel)
      FeatureTaskRuntimeWireArtifactKind.HANDOFF_ENVELOPE ->
        FeatureTaskRuntimeHandoffEnvelopeSchemaValidator.validate(wireMap, sourceLabel)
      FeatureTaskRuntimeWireArtifactKind.GOAL_PROGRESS_EVENT ->
        GoalProgressEventSchemaValidator.validate(wireMap, sourceLabel)
      FeatureTaskRuntimeWireArtifactKind.GOAL_OBSERVABILITY_EVENT ->
        GoalObservabilityEventSchemaValidator.validate(wireMap, sourceLabel)
      FeatureTaskRuntimeWireArtifactKind.GOAL_PLANNING_PREPARATION_ENVELOPE ->
        GoalPlanningPreparationSchemaValidator.validate(wireMap, sourceLabel)
      else -> validateSerial(kind, wireMap, sourceLabel)
    }
  }

  private fun validateSerial(
    kind: FeatureTaskRuntimeWireArtifactKind,
    wireMap: Map<String, Any?>,
    sourceLabel: String,
  ) {
    when (kind) {
      FeatureTaskRuntimeWireArtifactKind.ACCEPTANCE_CRITERION_REPAIR_STATE ->
        AcceptanceCriterionRepairStateSchemaValidator.validate(wireMap, sourceLabel)
      FeatureTaskRuntimeWireArtifactKind.ACCEPTANCE_CRITERION_AUDIT_RESULT ->
        AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(wireMap, sourceLabel)
      FeatureTaskRuntimeWireArtifactKind.ACCEPTANCE_CRITERION_REPAIR_HANDOFF ->
        AcceptanceCriterionRepairAuxiliarySchemaValidator.validateRepairHandoff(wireMap, sourceLabel)
      FeatureTaskRuntimeWireArtifactKind.ACCEPTANCE_CRITERION_REPAIR_ADOPTION ->
        AcceptanceCriterionRepairAuxiliarySchemaValidator.validateRepairAdoption(
          wireMap,
          sourceLabel,
          executionPlans,
          phaseOutputs,
        )
      else -> error("Unexpected non-serial wire artifact $kind")
    }
  }

  override fun violation(
    kind: FeatureTaskRuntimeWireArtifactKind,
    payload: FeatureTaskRuntimeWorkflowArtifactMap,
    sourceLabel: String,
  ): String? =
    if (kind == FeatureTaskRuntimeWireArtifactKind.HANDOFF_PERSISTENCE_RECORD) {
      if (payload.isObject) {
        FeatureTaskRuntimePersistenceSchemaValidator.violation(payload, sourceLabel)
      } else {
        "<root> must be an object."
      }
    } else {
      validate(kind, payload, sourceLabel)
      null
    }
}
