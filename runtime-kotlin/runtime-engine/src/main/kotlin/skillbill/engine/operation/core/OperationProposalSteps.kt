package skillbill.engine.operation.core

internal fun OperationContext.proposeFromStep(
  stepName: String,
  directive: String,
  title: String,
): OperationRunResult =
  when (val step = steps.runReadOnly(this, stepName, directive)) {
    is OperationStepResult.Failed -> OperationRunResult.Finished(OperationOutcome.Failed(step.reason))
    is OperationStepResult.Refused -> OperationRunResult.Finished(step.refusal)
    is OperationStepResult.Settled ->
      OperationRunResult.Proposed(
        value = step.value,
        summary = "$title\n\n${step.value.trim()}\n",
        operationValues = emptyMap(),
      )
  }

internal fun OperationContext.applyStoredProposal(
  proposalStep: String,
  applyStep: String,
  directive: String,
  proposal: ConfirmedOperationProposal,
): OperationStepResult =
  steps.runEditing(copy(instructions = null), applyStep, directive, mapOf(proposalStep to proposal.value))
