package skillbill.engine.operation.featureguardcleanup

import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.operation.core.ConfirmableOperation
import skillbill.engine.operation.core.ConfirmedOperationProposal
import skillbill.engine.operation.core.CurrentOperationAnchors
import skillbill.engine.operation.core.OperationContext
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationRefusal
import skillbill.engine.operation.core.OperationRunResult
import skillbill.engine.operation.core.OperationStepResult
import skillbill.engine.operation.core.applyStoredProposal
import skillbill.engine.operation.core.missingIntake
import skillbill.engine.operation.core.proposeFromStep

class FeatureGuardCleanupOperation(
  private val runPhase: (PhaseRunRequest) -> PhaseRunResult,
) : ConfirmableOperation {
  override val id: String = "feature-guard-cleanup"

  override fun pre(context: OperationContext): OperationRefusal? =
    if (!context.confirming && context.instructions.isNullOrBlank()) {
      missingIntake(id, "name the feature flag to clean up")
    } else {
      null
    }

  override fun run(context: OperationContext): OperationRunResult =
    context.proposeFromStep(
      FeatureGuardCleanupPromptRules.PROPOSAL_STEP,
      FeatureGuardCleanupPromptRules.proposal,
      PROPOSAL_TITLE,
    )

  override fun currentAnchors(context: OperationContext): CurrentOperationAnchors =
    CurrentOperationAnchors.Read(emptyMap())

  override fun execute(
    context: OperationContext,
    proposal: ConfirmedOperationProposal,
  ): OperationOutcome {
    val step =
      context.applyStoredProposal(
        FeatureGuardCleanupPromptRules.PROPOSAL_STEP,
        FeatureGuardCleanupPromptRules.APPLY_STEP,
        FeatureGuardCleanupPromptRules.apply,
        proposal,
      )
    val applied =
      when (step) {
        is OperationStepResult.Failed -> return OperationOutcome.Failed(step.reason)
        is OperationStepResult.Refused -> return step.refusal
        is OperationStepResult.Settled -> step.value.trimEnd()
      }
    val agentId = requireNotNull(context.invokedAgentId)
    return when (val validation = runPhase(PhaseRunRequest(VALIDATION_DEFINITION, context.repoRoot, agentId))) {
      is PhaseRunResult.Completed ->
        OperationOutcome.Completed(
          "$applied\n\nValidation passed (${validation.invocationId}).\n${validation.value.orEmpty().trimEnd()}\n",
        )
      is PhaseRunResult.Blocked ->
        OperationOutcome.Failed(
          "$applied\n\nThe cleanup edits are in place, but validation (${validation.invocationId}) blocked at " +
            "'${validation.stepId}': ${validation.reason}",
        )
    }
  }
}

private const val PROPOSAL_TITLE = "Feature guard cleanup proposal"
private const val VALIDATION_DEFINITION = "validation"
