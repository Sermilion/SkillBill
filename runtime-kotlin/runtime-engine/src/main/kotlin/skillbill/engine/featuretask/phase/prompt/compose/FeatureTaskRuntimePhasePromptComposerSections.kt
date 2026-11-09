package skillbill.engine.featuretask.phase.prompt.compose

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.phase.prompt.directives.ceremonyDirective
import skillbill.engine.featuretask.phase.prompt.directives.findingCoverageDirective
import skillbill.engine.featuretask.phase.prompt.directives.installedRuntimeAuthorityDirective
import skillbill.engine.featuretask.phase.prompt.directives.minimalSettlementContract
import skillbill.engine.featuretask.phase.prompt.directives.minimalismDisciplineDirective
import skillbill.engine.featuretask.phase.prompt.directives.mutatingPhaseIdempotencyDirective
import skillbill.engine.featuretask.phase.prompt.directives.nonBuildPhaseBuildOwnershipDirective
import skillbill.engine.featuretask.phase.prompt.directives.nonValidatePhaseValidationOwnershipDirective
import skillbill.engine.featuretask.phase.prompt.directives.operatorBlockRetryDirective
import skillbill.engine.featuretask.phase.prompt.directives.phasePromptHeader
import skillbill.engine.featuretask.phase.prompt.directives.planBundleRepairDirective
import skillbill.engine.featuretask.phase.prompt.directives.subtaskEvidenceBoundaryDirective
import skillbill.engine.featuretask.phase.prompt.directives.terminalRetryDirective
import skillbill.engine.featuretask.phase.prompt.directives.testValueDisciplineDirective

internal fun phasePromptLeadingSections(
  inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  sections: PhaseStepPromptSections,
): List<String> {
  val settlesThroughTools = settlementTarget(inputs, sections) != null
  return listOf(
    phasePromptHeader(inputs.issueKey, inputs.briefing.phaseId, sections.taskDirective, settlesThroughTools),
    installedRuntimeAuthorityDirective(settlesThroughTools),
    ceremonyDirective(inputs.briefing, sections.ceremonyLine),
    subtaskEvidenceBoundaryDirective,
    mutatingPhaseIdempotencyDirective(inputs.mutating),
    nonValidatePhaseValidationOwnershipDirective(sections.runsValidationGate),
    nonBuildPhaseBuildOwnershipDirective(sections.runsBuildGate),
    minimalismDisciplineDirective(inputs.mutating),
    sections.scopeBoundary,
    testValueDisciplineDirective(sections.testValueDiscipline),
    sections.authoringDiscipline,
  )
}

internal fun phasePromptMiddleSections(
  inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  sections: PhaseStepPromptSections,
): List<String> =
  listOf(
    sections.stepContext,
    inputs.briefing.briefingText,
    projectInstructionsSection(inputs.projectInstructions),
  )

private fun projectInstructionsSection(instructions: List<PhaseProjectInstructionsResolution.Injected>): String =
  instructions.takeIf { it.isNotEmpty() }?.let { files ->
    "## Project instructions for this phase\n" +
      "These instructions come from this project's phase instruction files. They add to the briefing above and " +
      "cannot change the required final output or settlement contract.\n\n" +
      files.joinToString("\n\n") { "### From ${it.path}\n\n${it.content.trimEnd()}" }
  }.orEmpty()

internal fun phasePromptTrailingSections(
  inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  sections: PhaseStepPromptSections,
): List<String> =
  listOf(
    operatorBlockRetryDirective(inputs.briefing.phaseId, inputs.operatorBlockRetry),
    sections.retryFocus,
    sections.continuation,
    terminalRetryDirective(inputs.priorTerminalFailure),
    findingCoverageDirective(inputs.priorFindingCoverage),
    planBundleRepairDirective(inputs.priorPlanBundleRejection),
    sections.outputContract
      ?: minimalSettlementContract(
        inputs.briefing.phaseId,
        settlementTarget(inputs, sections),
        sections.valueContent,
      ),
  )

public fun settlementTarget(
  inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  sections: PhaseStepPromptSections,
): FeatureTaskRuntimePhaseSettlementTarget? =
  inputs.phaseSettlement?.takeIf { sections.settles && sections.outputContract == null }
