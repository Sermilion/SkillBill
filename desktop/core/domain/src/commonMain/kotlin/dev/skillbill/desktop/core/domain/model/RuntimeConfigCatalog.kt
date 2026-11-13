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

data class ModelMatrixView(val document: ConfigDocument<MachineConfig>, val agents: Map<String, AgentModels>)

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
