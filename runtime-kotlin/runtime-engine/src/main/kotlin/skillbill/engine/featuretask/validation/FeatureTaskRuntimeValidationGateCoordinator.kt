package skillbill.engine.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.engine.featuretask.model.phase.ValidationFindingSetProjection
import skillbill.engine.featuretask.persist.workflowArtifactEntryMap
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.engine.featuretask.validation.model.ValidationGateCycleResult
import skillbill.engine.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationCommandResult
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationEvidence
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateExecutionEvidence
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord

private const val VALIDATE_PHASE_STATUS_COMPLETED = "completed"

@Inject
class FeatureTaskRuntimeValidationGateCoordinator {
  fun execute(agentRepairLauncher: ValidationGateAgentRepairLauncher): ValidationGateCycleResult =
    when (
      val result = agentRepairLauncher.launch(ValidationFindingSetProjection(emptyList()), 1, null)
    ) {
      is ValidationGateAgentRepairResult.Paused ->
        ValidationGateCycleResult.Terminal(
          ValidationGateCycleTerminalOutcome.Paused(result.reason),
        )
      is ValidationGateAgentRepairResult.Completed ->
        ValidationGateCycleResult.Terminal(
          ValidationGateCycleTerminalOutcome.Completed(result.output),
        )
      is ValidationGateAgentRepairResult.Blocked ->
        terminalBlockedResult(
          result.reason,
          failureDisposition = result.failureDisposition,
        )
    }

  companion object {
    private fun invalidValidationEvidence(
      phaseId: String,
      reason: String,
    ): Nothing = throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(phaseId, reason)

    fun runtimeOwnedValidationOutput(
      phaseId: String,
      repositoryCheckpoint: String,
      measurements: List<FeatureTaskRuntimeValidationGateRunRecord>,
      requiredCommand: String,
    ): FeatureTaskRuntimePhaseOutput {
      val evidence =
        measurements.mapNotNull { measurement ->
          val command = measurement.command ?: return@mapNotNull null
          val exitCode = measurement.exitCode ?: return@mapNotNull null
          FeatureTaskRuntimeValidationCommandResult(command, exitCode)
        }
      if (evidence.isEmpty()) {
        invalidValidationEvidence(
          phaseId,
          "runtime-owned validation evidence has no command results.",
        )
      }
      val commandEvidence = FeatureTaskRuntimeValidationEvidence(evidence)
      val terminalResult = commandEvidence.requireSuccessfulResult(phaseId)
      if (terminalResult.command != requiredCommand) {
        invalidValidationEvidence(
          phaseId,
          "terminal validation command does not match the required verification command.",
        )
      }
      if (measurements.size != evidence.size ||
        measurements.zip(evidence).any { (measurement, result) ->
          measurement.command != result.command || measurement.exitCode != result.exitCode
        }
      ) {
        invalidValidationEvidence(
          phaseId,
          "validation command results must match the ordered gate run records.",
        )
      }
      val gateExecutionEvidence = FeatureTaskRuntimeValidationGateExecutionEvidence.fromGateMeasurements(measurements)
      val validationResult =
        linkedMapOf<String, Any?>().apply {
          putAll(workflowArtifactEntryMap(gateExecutionEvidence.asWorkflowArtifactEntry(repositoryCheckpoint)))
          put(
            ValidationEvidencePayloadKeys.VALIDATION_EVIDENCE,
            FeatureTaskRuntimeValidationEvidence(evidence).asWorkflowArtifactEntry(),
          )
        }
      val payload =
        JsonCodec.mapToJsonString(
          mapOf(
            SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
            SharedPayloadKeys.PHASE_ID to phaseId,
            SharedPayloadKeys.STATUS to VALIDATE_PHASE_STATUS_COMPLETED,
            SharedPayloadKeys.SUMMARY to "Validation satisfied by runtime-owned gate execution.",
            SharedPayloadKeys.VERDICT to FeatureTaskRuntimeVerdict.SATISFIED.wireValue,
            SharedPayloadKeys.PRODUCED_OUTPUTS to
              mapOf(
                SharedPayloadKeys.VALUE to "The runtime confirmed the pack validation gate passed.",
                ValidationEvidencePayloadKeys.VALIDATION_PASSED to true,
                ValidationEvidencePayloadKeys.VALIDATION_RESULT to validationResult,
              ),
          ),
        )
      return FeatureTaskRuntimePhaseOutput(
        phaseId = phaseId,
        iteration = 1,
        payload = payload,
      )
    }
  }
}

internal fun terminalBlockedResult(
  reason: String,
  remainingFindings: ValidationFindingSetProjection? = null,
  measurements: List<FeatureTaskRuntimeValidationGateRunRecord> = emptyList(),
  failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
): ValidationGateCycleResult =
  ValidationGateCycleResult.Terminal(
    ValidationGateCycleTerminalOutcome.Blocked(
      reason = reason,
      remainingFindings = remainingFindings,
      measurements = measurements,
      failureDisposition = failureDisposition,
    ),
  )
