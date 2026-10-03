package skillbill.engine.featuretask.phase.prompt.compose

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.engine.featuretask.slot.statusProjectionPhaseStrategies
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

private val TEST_MUTATING_PHASES = setOf("implement", "simplify", "implement_fix")
private val TEST_SINGLE_SESSION_PHASES = setOf("simplify", "audit")
private val TEST_STRATEGIES = statusProjectionPhaseStrategies()

internal fun productionStrategyFor(stepId: String): PhaseStrategy =
  TEST_STRATEGIES.strategyFor(
    stepId,
    PhaseStrategySelectionFacts(
      if (stepId == "build") SkeletonDefinition.GOAL_CHILD else SkeletonDefinition.STANDALONE,
      setOf(CodeReviewExecutionMode.INLINE, FeatureTaskRuntimeQualityGateSelection.BUILD),
    ),
  )

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
