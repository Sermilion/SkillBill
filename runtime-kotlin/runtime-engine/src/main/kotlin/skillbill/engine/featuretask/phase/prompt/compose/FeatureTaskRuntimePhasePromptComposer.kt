package skillbill.engine.featuretask.phase.prompt.compose

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing

public object FeatureTaskRuntimePhasePromptComposer {
  public fun compose(
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

  public fun compose(
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
    source: PhaseStepPromptSource,
  ): String = composePhasePrompt(inputs, source)

  public fun budgetedAddonsFor(selection: HydratedAgentAddonSelection): HydratedAgentAddonSelection = selection
}
