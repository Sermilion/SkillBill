package dev.skillbill.desktop.core.domain.model

enum class PhaseGroupKind { PLANNING, BUILD, AUDIT, REVIEW, VALIDATE_AND_QA, SHIP, GOAL_FINISH, OTHER }

data class RuntimePhase(val id: String, val defaultTier: Tier, val group: PhaseGroupKind)

data class ConfigAgent(val id: String, val modelCapable: Boolean)

data class ConfigDefaults(
  val phases: List<RuntimePhase>,
  val agents: List<ConfigAgent>,
  val compaction: CompactionConfig,
  val telemetryBatchSize: Int,
  val reviewBudget: ReviewBudget,
  val phaseInstructionCapBytes: Long,
  val installSelectionPath: String,
)

data class ResolvedPhase(val phaseId: String, val tier: Tier, val tierChanged: Boolean, val directive: ModelDirective?, val override: Boolean)

data class AgentModels(val agentId: String, val tiers: Map<Tier, ModelDirective?>, val phases: List<ResolvedPhase>)

/** Where a phase's provider comes from when no invocation context exists. */
enum class AgentRoutingSource { CONFIGURED_PHASE, CONFIGURED_TIER, INHERITED_INVOCATION }

data class PhaseRoutingPreview(
  val phaseId: String,
  val tier: Tier,
  val source: AgentRoutingSource,
  val providerId: String?,
)

/** The runtime's read-only local launch observation for a provider; never derived from directory detection. */
sealed interface AgentRuntimeAvailability {
  data class Registered(val executable: String) : AgentRuntimeAvailability

  data class ExecutableMissing(val reason: String) : AgentRuntimeAvailability

  data class Unsupported(val reason: String) : AgentRuntimeAvailability

  data class Unknown(val reason: String) : AgentRuntimeAvailability
}

data class AgentRoutingView(
  val tierAgents: Map<Tier, String>,
  val phaseAgents: Map<String, String>,
  val phases: List<PhaseRoutingPreview>,
  val availability: Map<String, AgentRuntimeAvailability>,
) {
  companion object {
    val EMPTY: AgentRoutingView = AgentRoutingView(emptyMap(), emptyMap(), emptyList(), emptyMap())
  }
}

data class ModelMatrixView(
  val document: ConfigDocument<MachineConfig>,
  val agents: Map<String, AgentModels>,
  val routing: AgentRoutingView = AgentRoutingView.EMPTY,
)

data class AdvertisedAgentBinding(val providerId: String, val profile: String?, val workingDirectory: String)

data class AdvertisedChoice(val id: String, val label: String)

sealed interface AdvertisedEffortSupport {
  data class Offered(val values: List<AdvertisedChoice>) : AdvertisedEffortSupport
  data object Unsupported : AdvertisedEffortSupport
}

sealed interface AdvertisedAgentChoices {
  data class Choices(
    val binding: AdvertisedAgentBinding,
    val models: List<AdvertisedChoice>,
    val selectedModel: AdvertisedChoice,
    val effort: AdvertisedEffortSupport,
  ) : AdvertisedAgentChoices

  data class Unavailable(val reason: Reason) : AdvertisedAgentChoices {
    enum class Reason { EXECUTABLE, AUTHENTICATION, CAPABILITY }
  }

  data class InvalidSelection(val requestedModel: String) : AdvertisedAgentChoices

  data class Failed(val reason: Reason) : AdvertisedAgentChoices {
    enum class Reason { ACQUISITION, PROTOCOL }
  }
}
