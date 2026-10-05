package skillbill.ports.agentrun.model

import skillbill.workflow.taskruntime.model.skeleton.LaunchEnvironmentKind

data class AgentRunLaunchModelRequest(
  val agentId: String,
  val requestedModel: String?,
  val requestedEffort: String?,
  val environmentKind: LaunchEnvironmentKind,
) {
  init {
    require(agentId.isNotBlank()) { "agentId is required." }
    requestedModel?.let { model -> require(model.isNotBlank()) { "requestedModel must be non-blank when provided." } }
    requestedEffort?.let { effort ->
      require(effort.isNotBlank()) { "requestedEffort must be non-blank when provided." }
    }
  }
}
