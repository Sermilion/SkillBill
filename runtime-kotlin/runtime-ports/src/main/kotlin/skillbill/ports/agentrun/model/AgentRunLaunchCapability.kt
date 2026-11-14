package skillbill.ports.agentrun.model

import skillbill.workflow.taskruntime.model.skeleton.LaunchEnvironmentKind

data class AgentRunLaunchCapabilityRequest(
  val agentId: String,
  val environmentKind: LaunchEnvironmentKind,
) {
  init {
    require(agentId.isNotBlank()) { "agentId must be non-blank." }
  }
}

sealed interface AgentRunLaunchCapability {
  data object Supported : AgentRunLaunchCapability

  data class Unsupported(
    val reason: String,
  ) : AgentRunLaunchCapability {
    init {
      require(reason.isNotBlank()) { "Unsupported launch support requires a reason." }
    }
  }
}
