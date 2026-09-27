package skillbill.engine.featuretask.phase.prompt.compose

import skillbill.workflow.taskruntime.model.repair.task.FeatureTaskRuntimeCorrectiveRepairContext

fun composePhasePrompt(
  inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  source: PhaseStepPromptSource,
): String = phasePromptSections(inputs, source).filter(String::isNotBlank).joinToString(separator = "\n\n")

fun phasePromptSections(
  inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  source: PhaseStepPromptSource,
): List<String> {
  requireComposableInputs(
    issueKey = inputs.issueKey,
    priorSchemaFailure = inputs.priorSchemaFailure,
    priorTerminalFailure = inputs.priorTerminalFailure,
    priorFindingCoverage = inputs.priorFindingCoverage,
    correctiveRepairContext = inputs.correctiveRepairContext,
  )
  val sections = source.sections(inputs)
  val effectiveInputs =
    sections.briefingRewrite?.let { rewrite -> inputs.copy(briefing = rewrite(inputs.briefing)) } ?: inputs
  return phasePromptLeadingSections(effectiveInputs, sections) +
    phasePromptMiddleSections(effectiveInputs, sections) +
    phasePromptTrailingSections(effectiveInputs, sections)
}

private fun requireComposableInputs(
  issueKey: String,
  priorSchemaFailure: String?,
  priorTerminalFailure: String?,
  priorFindingCoverage: String?,
  correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext?,
) {
  require(issueKey.isNotBlank()) { "issueKey is required to compose a phase prompt." }
  require(correctiveRepairContext == null || !priorSchemaFailure.isNullOrBlank()) {
    "correctiveRepairContext requires a non-blank priorSchemaFailure; raw repair context belongs " +
      "only to schema-gate retries."
  }
  require(correctiveRepairContext == null || priorTerminalFailure.isNullOrBlank()) {
    "correctiveRepairContext cannot accompany a retryable-terminal failure; the correction kinds " +
      "must stay separate."
  }
  require(priorFindingCoverage.isNullOrBlank() || priorSchemaFailure.isNullOrBlank()) {
    "priorFindingCoverage cannot accompany a schema-gate failure; a receipt is either short of its " +
      "carried findings or rejected, never both in one correction."
  }
}
