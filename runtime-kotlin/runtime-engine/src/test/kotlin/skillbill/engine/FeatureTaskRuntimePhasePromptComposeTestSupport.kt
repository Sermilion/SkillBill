package skillbill.engine

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposer
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.statusProjectionPhaseStrategies

private val TEST_MUTATING_PHASES = setOf("implement", "simplify", "implement_fix")
private val TEST_SINGLE_SESSION_PHASES = setOf("simplify", "audit")
private val TEST_STRATEGIES = statusProjectionPhaseStrategies().registry.strategies

internal fun productionStrategyFor(stepId: String): PhaseStrategy = TEST_STRATEGIES.first { stepId in it.steps }

internal fun productionPromptSource(stepId: String): PhaseStepPromptSource =
  PhaseStepPromptSource { inputs -> productionStrategyFor(stepId).promptSections(stepId, inputs) }

internal fun composePhasePrompt(inputs: FeatureTaskRuntimePhasePromptComposeInputs): String =
  FeatureTaskRuntimePhasePromptComposer.compose(inputs, productionPromptSource(inputs.briefing.phaseId))

internal fun composePhasePrompt(
  issueKey: String,
  briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  configure: FeatureTaskRuntimePhasePromptComposeInputs.() -> FeatureTaskRuntimePhasePromptComposeInputs = { this },
): String =
  composePhasePrompt(
    FeatureTaskRuntimePhasePromptComposeInputs(
      issueKey = issueKey,
      briefing = briefing,
      mutating = briefing.phaseId in TEST_MUTATING_PHASES,
      singleAgentSession = briefing.phaseId in TEST_SINGLE_SESSION_PHASES,
    ).configure(),
  )
