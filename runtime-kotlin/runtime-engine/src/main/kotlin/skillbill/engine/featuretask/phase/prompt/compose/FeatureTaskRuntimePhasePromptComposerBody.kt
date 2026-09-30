package skillbill.engine.featuretask.phase.prompt.compose

fun composePhasePrompt(
  inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  source: PhaseStepPromptSource,
): String = phasePromptSections(inputs, source).filter(String::isNotBlank).joinToString(separator = "\n\n")

fun phasePromptSections(
  inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  source: PhaseStepPromptSource,
): List<String> {
  require(inputs.issueKey.isNotBlank()) { "issueKey is required to compose a phase prompt." }
  val sections = source.sections(inputs)
  val effectiveInputs =
    sections.briefingRewrite?.let { rewrite -> inputs.copy(briefing = rewrite(inputs.briefing)) } ?: inputs
  return phasePromptLeadingSections(effectiveInputs, sections) +
    phasePromptMiddleSections(effectiveInputs, sections) +
    phasePromptTrailingSections(effectiveInputs, sections)
}
