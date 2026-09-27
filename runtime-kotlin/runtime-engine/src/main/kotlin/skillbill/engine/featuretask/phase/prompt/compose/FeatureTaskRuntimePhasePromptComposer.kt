package skillbill.engine.featuretask.phase.prompt.compose

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing

object FeatureTaskRuntimePhasePromptComposer {
  fun compose(
    issueKey: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    source: PhaseStepPromptSource,
    configure: FeatureTaskRuntimePhasePromptComposeInputs.() -> FeatureTaskRuntimePhasePromptComposeInputs = { this },
  ): String =
    compose(
      configure(
        FeatureTaskRuntimePhasePromptComposeInputs(
          issueKey = issueKey,
          briefing = briefing,
        ),
      ),
      source,
    )

  fun compose(
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
    source: PhaseStepPromptSource,
  ): String = composePhasePrompt(inputs, source)

  fun budgetedAddonsFor(selection: HydratedAgentAddonSelection): HydratedAgentAddonSelection = selection
}
