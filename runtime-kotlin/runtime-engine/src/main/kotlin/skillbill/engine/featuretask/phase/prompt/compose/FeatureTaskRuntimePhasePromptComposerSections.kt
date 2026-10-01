package skillbill.engine.featuretask.phase.prompt.compose

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
import skillbill.engine.featuretask.phase.prompt.directives.terminalRetryDirective
import skillbill.engine.featuretask.phase.prompt.directives.testValueDisciplineDirective

fun phasePromptLeadingSections(
  inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  sections: PhaseStepPromptSections,
): List<String> =
  listOf(
    phasePromptHeader(inputs.issueKey, inputs.briefing.phaseId, sections.taskDirective),
    installedRuntimeAuthorityDirective(),
    ceremonyDirective(inputs.briefing, sections.ceremonyLine),
    mutatingPhaseIdempotencyDirective(inputs.mutating),
    nonValidatePhaseValidationOwnershipDirective(sections.runsValidationGate),
    nonBuildPhaseBuildOwnershipDirective(sections.runsBuildGate),
    minimalismDisciplineDirective(inputs.mutating),
    sections.scopeBoundary,
    testValueDisciplineDirective(sections.testValueDiscipline),
    sections.authoringDiscipline,
  )

fun phasePromptMiddleSections(
  inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  sections: PhaseStepPromptSections,
): List<String> =
  listOf(
    sections.stepContext,
    inputs.briefing.briefingText,
  )

fun phasePromptTrailingSections(
  inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  sections: PhaseStepPromptSections,
): List<String> =
  listOf(
    operatorBlockRetryDirective(inputs.briefing.phaseId, inputs.operatorBlockRetry),
    sections.retryFocus,
    sections.continuation,
    terminalRetryDirective(inputs.priorTerminalFailure),
    findingCoverageDirective(inputs.priorFindingCoverage),
    sections.outputContract
      ?: minimalSettlementContract(
        inputs.briefing.phaseId,
        inputs.phaseSettlement?.takeIf { sections.settles },
        sections.valueContent,
      ),
  )
