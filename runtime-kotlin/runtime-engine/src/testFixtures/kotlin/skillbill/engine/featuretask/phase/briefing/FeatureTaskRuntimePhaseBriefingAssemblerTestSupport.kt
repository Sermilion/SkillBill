package skillbill.engine.featuretask.phase.briefing

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.error.shellcontent.invalidFeatureTaskRuntimeHandoffProjection
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseHandoff

fun assembleFeatureTaskRuntimeBriefing(
  handoff: FeatureTaskRuntimePhaseHandoff,
  workflowId: String? = null,
  agentAddonSelection: HydratedAgentAddonSelection = HydratedAgentAddonSelection(),
  scope: FeatureTaskRuntimeBriefingScope = FeatureTaskRuntimeBriefingScope(),
): FeatureTaskRuntimePhaseLaunchBriefing =
  when (val result = FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff, workflowId, agentAddonSelection, scope)) {
    is FeatureTaskRuntimePhaseBriefingAssemblyResult.Accepted -> result.briefing
    is FeatureTaskRuntimePhaseBriefingAssemblyResult.Rejected ->
      throw invalidFeatureTaskRuntimeHandoffProjection(result.context)
  }
