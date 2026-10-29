package dev.skillbill.desktop.feature.config

import dev.skillbill.desktop.core.domain.model.CompactionConfig
import dev.skillbill.desktop.core.domain.model.ConfigDefaults
import dev.skillbill.desktop.core.domain.model.ExecutionMatrixConfig
import dev.skillbill.desktop.core.domain.model.ModelDirective
import dev.skillbill.desktop.core.domain.model.ReviewBudget
import dev.skillbill.desktop.core.domain.model.Tier

internal object ConfigFileKeys {
  const val PHASE_TIERS = "phase_tiers"
  const val AGENTS = "agents"
  const val MODEL = "model"
  const val EFFORT = "effort"
  const val ENABLED = "enabled"
  const val WINDOW_TOKENS = "window_tokens"
  const val TRIGGER_PCT = "trigger_pct"
  const val PHASES = "phases"
}

internal fun executionMatrixSection(matrix: ExecutionMatrixConfig, defaults: ConfigDefaults): Map<String, Any?> {
  val defaultTiers = defaults.phases.associate { it.id to it.defaultTier }
  val tiers = matrix.phaseTiers.filter { (id, tier) -> defaultTiers[id] != tier }.mapValues { it.value.wireValue() }
  val agentIds = matrix.agents.keys + matrix.agentPhaseOverrides.keys
  val agents = agentIds.associateWith { agent ->
    val tierDirectives = matrix.agents[agent].orEmpty().mapKeys { it.key.wireValue() }.mapValues { it.value.section() }
    val overrides = matrix.agentPhaseOverrides[agent].orEmpty().mapValues { it.value.section() }
    tierDirectives + overrides
  }.filterValues { it.isNotEmpty() }
  return buildMap {
    if (tiers.isNotEmpty()) put(ConfigFileKeys.PHASE_TIERS, tiers)
    if (agents.isNotEmpty()) put(ConfigFileKeys.AGENTS, agents)
  }
}

internal fun compactionSection(value: CompactionConfig, defaults: CompactionConfig): Map<String, Any?> = buildMap {
  if (value.enabled != defaults.enabled) put(ConfigFileKeys.ENABLED, value.enabled)
  if (value.windowTokens != defaults.windowTokens) put(ConfigFileKeys.WINDOW_TOKENS, value.windowTokens)
  if (value.triggerPct != defaults.triggerPct) put(ConfigFileKeys.TRIGGER_PCT, value.triggerPct)
  if (value.phases.isNotEmpty()) put(ConfigFileKeys.PHASES, value.phases.mapValues { (_, phase) ->
    mapOf(ConfigFileKeys.WINDOW_TOKENS to phase.windowTokens, ConfigFileKeys.TRIGGER_PCT to phase.triggerPct)
  })
}

internal fun reviewBudgetValues(value: ReviewBudget): Map<String, Number> = mapOf(
  "max_parent_packet_bytes" to value.maxParentPacketBytes,
  "max_lane_launch_bytes" to value.maxLaneLaunchBytes,
  "max_lane_evidence_bytes" to value.maxLaneEvidenceBytes,
  "max_evidence_result_bytes" to value.maxEvidenceResultBytes,
  "max_lane_result_bytes" to value.maxLaneResultBytes,
  "max_assignment_expansions" to value.maxAssignmentExpansions,
  "max_specialist_tool_calls" to value.maxSpecialistToolCalls,
  "max_specialist_model_turns" to value.maxSpecialistModelTurns,
  "max_routing_analysis_pairs" to value.maxRoutingAnalysisPairs,
  "max_routing_analysis_bytes" to value.maxRoutingAnalysisBytes,
)

private fun ModelDirective.section(): Map<String, String> = buildMap {
  put(ConfigFileKeys.MODEL, model)
  effort?.let { put(ConfigFileKeys.EFFORT, it) }
}

private fun Tier.wireValue() = when (this) {
  Tier.REASONING -> "reasoning"
  Tier.IMPLEMENTATION -> "implementation"
}
