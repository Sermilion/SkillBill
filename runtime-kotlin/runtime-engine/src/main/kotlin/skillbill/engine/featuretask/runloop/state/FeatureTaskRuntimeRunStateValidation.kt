package skillbill.engine.featuretask.runloop.state

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.decodeValidationEvidenceFromArtifact
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationEvidence

internal class ValidationSettlementState(
  completed: Set<String>,
  val initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  val transitions: FeatureTaskRuntimeTransitionDeclaration,
  gateInvalidatedPhases: Set<String>,
) {
  private val completedState = completed.toMutableSet()
  private val gateInvalidatedState = gateInvalidatedPhases.toMutableSet()

  val completed: Set<String>
    get() = completedState.toSet()

  val gateInvalidatedPhases: Set<String>
    get() = gateInvalidatedState.toSet()

  internal fun invalidateValidationPhase(validationStepId: String) {
    val invalidated =
      transitions.forwardPhaseIds
        .dropWhile { it != validationStepId }
        .filter(completedState::contains) + validationStepId
    completedState.removeAll(invalidated.toSet())
    gateInvalidatedState.addAll(invalidated)
  }

  internal fun invalidateUnsatisfiedGateSuccessors(durableVerdictFor: (String) -> FeatureTaskRuntimeVerdict) {
    FeatureTaskRuntimeRunStateReconstruction.invalidateUnsatisfiedGateSuccessors(
      transitions,
      completedState,
      gateInvalidatedState,
      durableVerdictFor,
    )
  }
}

internal data class ValidationSettlementValidation(
  val validatedRecordToOutput: (FeatureTaskRuntimePhaseRecord) -> FeatureTaskRuntimePhaseOutput?,
  val durableVerdictFor: (String) -> FeatureTaskRuntimeVerdict,
  val resumeRules: (String) -> PhaseResumeRules,
)

internal fun validationEvidenceFromEnvelope(
  envelope: Map<String, Any?>,
  sourceLabel: String,
): FeatureTaskRuntimeValidationEvidence? {
  val produced = JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS])
  val result =
    JsonCodec.anyToStringAnyMap(
      produced?.get(ValidationEvidencePayloadKeys.VALIDATION_RESULT),
    )
  return JsonCodec.anyToStringAnyMap(
    result?.get(ValidationEvidencePayloadKeys.VALIDATION_EVIDENCE),
  )?.let { raw -> decodeValidationEvidenceFromArtifact(raw, sourceLabel) }
}

internal fun invalidateUnsettledResumedCompletions(
  state: ValidationSettlementState,
  validation: ValidationSettlementValidation,
) {
  val gateOutputs =
    state.initialRecords.values
      .filter {
        validation.resumeRules(
          it.phaseId,
        ).requiresValidCompletedOutput && it.status == WorkflowStepStatus.COMPLETED
      }
      .associate { it.phaseId to validation.validatedRecordToOutput(it) }
  state.completed.sortedBy(state.transitions.forwardPhaseIds::indexOf).forEach { stepId ->
    if (stepId !in state.completed) return@forEach
    val record = state.initialRecords[stepId] ?: return@forEach
    val output = {
      try {
        if (stepId in gateOutputs) gateOutputs[stepId] else validation.validatedRecordToOutput(record)
      } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
        if (validation.resumeRules(stepId).requiresValidCompletedOutput
        ) {
          throw error
        }
        null
      }
    }
    if (validation.resumeRules(stepId).invalidatesResumedCompletion(record, output)) {
      state.invalidateValidationPhase(stepId)
      state.invalidateUnsatisfiedGateSuccessors(validation.durableVerdictFor)
    }
  }
}
