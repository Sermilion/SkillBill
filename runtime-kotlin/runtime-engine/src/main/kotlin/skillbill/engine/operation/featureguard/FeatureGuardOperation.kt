package skillbill.engine.operation.featureguard

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

class FeatureGuardOperation : ConfirmableOperation {
  override val id: String = "feature-guard"

  override fun pre(context: OperationContext): OperationRefusal? =
    if (!context.confirming && context.instructions.isNullOrBlank()) {
      missingIntake(id, "describe the change to guard behind a feature flag")
    } else {
      null
    }

  override fun run(context: OperationContext): OperationRunResult =
    context.proposeFromStep(FeatureGuardPromptRules.PROPOSAL_STEP, FeatureGuardPromptRules.proposal, PROPOSAL_TITLE)

  override fun currentAnchors(context: OperationContext): CurrentOperationAnchors =
    CurrentOperationAnchors.Read(emptyMap())

  override fun execute(
    context: OperationContext,
    proposal: ConfirmedOperationProposal,
  ): OperationOutcome {
    val step =
      context.applyStoredProposal(
        FeatureGuardPromptRules.PROPOSAL_STEP,
        FeatureGuardPromptRules.APPLY_STEP,
        FeatureGuardPromptRules.apply,
        proposal,
      )
    return when (step) {
      is OperationStepResult.Failed -> OperationOutcome.Failed(step.reason)
      is OperationStepResult.Refused -> step.refusal
      is OperationStepResult.Settled -> OperationOutcome.Completed(step.value.trimEnd() + "\n")
    }
  }
}

private const val PROPOSAL_TITLE = "Feature guard proposal"
