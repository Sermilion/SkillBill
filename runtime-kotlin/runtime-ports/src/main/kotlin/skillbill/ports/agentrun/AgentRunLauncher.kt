package skillbill.ports.agentrun

import skillbill.ports.agentrun.model.AgentRunLaunchModelRequest
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.workflow.taskruntime.model.skeleton.EffectiveLaunchModel

interface AgentRunLauncher {
  fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome

  fun resolveLaunchModel(request: AgentRunLaunchModelRequest): EffectiveLaunchModel
}
