package skillbill.ports.agentrun.model

import skillbill.install.model.SupportedAgent
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

data class AgentRunLocalAvailabilityRequest(
  val agent: SupportedAgent,
)

/**
 * A read-only local availability observation for a supported provider. An executable on PATH proves
 * neither authentication, account permission, nor a successful provider session.
 */
sealed interface AgentRunLocalAvailability {
  val agent: SupportedAgent

  data class Registered(
    override val agent: SupportedAgent,
    val executable: String,
  ) : AgentRunLocalAvailability {
    init {
      require(executable.isNotBlank()) { "Registered availability requires an executable." }
    }
  }

  data class ExecutableMissing(
    override val agent: SupportedAgent,
    val reason: String,
  ) : AgentRunLocalAvailability {
    init {
      require(reason.isNotBlank()) { "ExecutableMissing availability requires a reason." }
    }
  }

  data class Unsupported(
    override val agent: SupportedAgent,
    val reason: String,
  ) : AgentRunLocalAvailability {
    init {
      require(reason.isNotBlank()) { "Unsupported availability requires a reason." }
    }
  }

  data class Unavailable(
    override val agent: SupportedAgent,
    val reason: String,
  ) : AgentRunLocalAvailability {
    init {
      require(reason.isNotBlank()) { "Unavailable availability requires a reason." }
    }
  }
}
