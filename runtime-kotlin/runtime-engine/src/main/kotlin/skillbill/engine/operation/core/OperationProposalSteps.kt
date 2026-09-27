package skillbill.engine.operation.core

/** Runs [stepName] read-only and turns its report into a proposal to store; a failed step stores nothing. */
internal fun OperationContext.proposeFromStep(
  stepName: String,
  directive: String,
  title: String,
): OperationRunResult =
  when (val step = steps.runReadOnly(this, stepName, directive)) {
    is OperationStepResult.Failed -> OperationRunResult.Finished(OperationOutcome.Failed(step.reason))
    is OperationStepResult.Settled ->
      OperationRunResult.Proposed(
        value = step.value,
        summary = "$title\n\n${step.value.trim()}\n",
        operationValues = emptyMap(),
      )
  }

/**
 * Runs [applyStep] fed the stored proposal verbatim as the [proposalStep] prior value; nothing recomputes it. Text
 * passed alongside `confirm:<token>` never reaches the editing step, so the confirmed plan is all it acts on.
 */
internal fun OperationContext.applyStoredProposal(
  proposalStep: String,
  applyStep: String,
  directive: String,
  proposal: ConfirmedOperationProposal,
): OperationStepResult =
  steps.runEditing(copy(instructions = null), applyStep, directive, mapOf(proposalStep to proposal.value))
