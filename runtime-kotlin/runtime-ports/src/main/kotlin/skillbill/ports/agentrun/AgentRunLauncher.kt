package skillbill.ports.agentrun

import skillbill.ports.agentrun.model.AgentRunLaunchCapability
import skillbill.ports.agentrun.model.AgentRunLaunchCapabilityRequest
import skillbill.ports.agentrun.model.AgentRunLaunchModelRequest
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunLocalAvailability
import skillbill.ports.agentrun.model.AgentRunLocalAvailabilityRequest
import skillbill.workflow.taskruntime.model.skeleton.EffectiveLaunchModel

interface AgentRunLauncher {
  fun queryLaunchCapability(request: AgentRunLaunchCapabilityRequest): AgentRunLaunchCapability

  /** Read-only local executable availability for a supported provider; launch-time checks stay authoritative. */
  fun queryLocalAvailability(request: AgentRunLocalAvailabilityRequest): AgentRunLocalAvailability

  fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome

  fun resolveLaunchModel(request: AgentRunLaunchModelRequest): EffectiveLaunchModel
}
