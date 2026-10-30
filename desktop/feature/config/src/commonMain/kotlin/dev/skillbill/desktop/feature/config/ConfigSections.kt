package dev.skillbill.desktop.feature.config

import dev.skillbill.desktop.core.domain.model.AuthoredCompactionField
import dev.skillbill.desktop.core.domain.model.AuthoredCompactionFields
import dev.skillbill.desktop.core.domain.model.AuthoredPhaseCompactionFields
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
  const val MAX_PARENT_PACKET_BYTES = "max_parent_packet_bytes"
  const val MAX_LANE_LAUNCH_BYTES = "max_lane_launch_bytes"
  const val MAX_LANE_EVIDENCE_BYTES = "max_lane_evidence_bytes"
  const val MAX_EVIDENCE_RESULT_BYTES = "max_evidence_result_bytes"
  const val MAX_LANE_RESULT_BYTES = "max_lane_result_bytes"
  const val MAX_ASSIGNMENT_EXPANSIONS = "max_assignment_expansions"
  const val MAX_SPECIALIST_TOOL_CALLS = "max_specialist_tool_calls"
  const val MAX_SPECIALIST_MODEL_TURNS = "max_specialist_model_turns"
  const val MAX_ROUTING_ANALYSIS_PAIRS = "max_routing_analysis_pairs"
  const val MAX_ROUTING_ANALYSIS_BYTES = "max_routing_analysis_bytes"
  const val REVIEW_CONTEXT_BUDGET = "review_context_budget"
  const val TIER = "tier"
  const val LEVEL = "level"
  const val SPEC_TYPE = "spec_type"
  const val GRADLE_WRAPPER = "validation_gate.gradle_wrapper"
  const val PROXY_URL = "proxy_url"
  const val BATCH_SIZE = "batch_size"
}

internal fun executionMatrixSection(
  matrix: ExecutionMatrixConfig,
  defaults: ConfigDefaults,
  baseline: ExecutionMatrixConfig? = null,
): Map<String, Any?> {
  val defaultTiers = defaults.phases.associate { it.id to it.defaultTier }
  val tiers = matrix.phaseTiers.filter { (id, tier) ->
    defaultTiers[id] != tier || baseline?.phaseTiers?.get(id) == tier
  }.mapValues { it.value.wireValue() }
  val agentIds = matrix.agents.keys + matrix.agentPhaseOverrides.keys
  val agents = agentIds.associateWith { agent ->
    val tierDirectives = matrix.agents[agent].orEmpty().mapKeys { it.key.wireValue() }.mapValues { it.value.section() }
    val overrides = matrix.agentPhaseOverrides[agent].orEmpty().filter { (phase, directive) ->
      val tier = matrix.phaseTiers[phase] ?: defaultTiers[phase]
      val wasExplicit = baseline?.agentPhaseOverrides?.get(agent)?.containsKey(phase) == true
      tier == null || wasExplicit || matrix.agents[agent]?.get(tier) != directive
    }.mapValues { it.value.section() }
    tierDirectives + overrides
  }.filterValues { it.isNotEmpty() }
  return buildMap {
    if (tiers.isNotEmpty()) put(ConfigFileKeys.PHASE_TIERS, tiers)
    if (agents.isNotEmpty() || tiers.isNotEmpty()) put(ConfigFileKeys.AGENTS, agents)
  }
}

internal fun compactionSection(
  authored: AuthoredCompactionFields,
  edits: Map<String, Map<String, String>> = emptyMap(),
  editedFields: Map<String, Set<String>> = emptyMap(),
  newPhaseIds: Set<String> = emptySet(),
  removedPhaseIds: Set<String> = emptySet(),
): Map<String, Any?> {
  var enabled = authored.enabled
  var windowTokens = authored.windowTokens
  var triggerPct = authored.triggerPct
  var phases = authored.phases

  edits.forEach { (rowKey, fields) ->
    val dirty = editedFields[rowKey].orEmpty()
    when (rowKey) {
      "compaction.enabled" -> if (ConfigFileKeys.ENABLED in dirty) {
        enabled = AuthoredCompactionField.Value(fields.getValue(ConfigFileKeys.ENABLED).toBooleanStrict())
      }
      "compaction.window_tokens" -> if (ConfigFileKeys.WINDOW_TOKENS in dirty) {
        windowTokens = AuthoredCompactionField.Value(compactionNumber(fields.getValue(ConfigFileKeys.WINDOW_TOKENS)))
      }
      "compaction.trigger_pct" -> if (ConfigFileKeys.TRIGGER_PCT in dirty) {
        triggerPct = AuthoredCompactionField.Value(compactionNumber(fields.getValue(ConfigFileKeys.TRIGGER_PCT)))
      }
      else -> if (rowKey.startsWith("compaction.phases.")) {
        val phaseId = rowKey.removePrefix("compaction.phases.")
        val isNewPhase = phaseId in newPhaseIds
        if (isNewPhase || dirty.any { it == ConfigFileKeys.WINDOW_TOKENS || it == ConfigFileKeys.TRIGGER_PCT }) {
          val phaseFields = phases.valueOrEmpty().toMutableMap()
          val current = phaseFields[phaseId] ?: AuthoredPhaseCompactionFields()
          phaseFields[phaseId] = current.copy(
            windowTokens = if (isNewPhase || ConfigFileKeys.WINDOW_TOKENS in dirty) {
              AuthoredCompactionField.Value(compactionNumber(fields.getValue(ConfigFileKeys.WINDOW_TOKENS)))
            } else current.windowTokens,
            triggerPct = if (isNewPhase || ConfigFileKeys.TRIGGER_PCT in dirty) {
              AuthoredCompactionField.Value(compactionNumber(fields.getValue(ConfigFileKeys.TRIGGER_PCT)))
            } else current.triggerPct,
          )
          phases = AuthoredCompactionField.Value(phaseFields)
        }
      }
    }
  }

  if (removedPhaseIds.isNotEmpty()) {
    phases = AuthoredCompactionField.Value(phases.valueOrEmpty() - removedPhaseIds)
  }

  return buildMap {
    putAuthored(ConfigFileKeys.ENABLED, enabled)
    putAuthored(ConfigFileKeys.WINDOW_TOKENS, windowTokens)
    putAuthored(ConfigFileKeys.TRIGGER_PCT, triggerPct)
    when (phases) {
      AuthoredCompactionField.Missing -> Unit
      AuthoredCompactionField.Null -> put(ConfigFileKeys.PHASES, null)
      is AuthoredCompactionField.Value -> put(
        ConfigFileKeys.PHASES,
        phases.value.mapValues { (_, fields) ->
          buildMap {
            putAuthored(ConfigFileKeys.WINDOW_TOKENS, fields.windowTokens)
            putAuthored(ConfigFileKeys.TRIGGER_PCT, fields.triggerPct)
          }
        },
      )
    }
  }
}

internal fun compactionSection(
  value: CompactionConfig,
  defaults: CompactionConfig,
): Map<String, Any?> = compactionSection(
  AuthoredCompactionFields(
    enabled = authoredIfDifferent(value.enabled, defaults.enabled),
    windowTokens = authoredIfDifferent(value.windowTokens, defaults.windowTokens),
    triggerPct = authoredIfDifferent(value.triggerPct, defaults.triggerPct),
    phases = if (value.phases.isEmpty()) {
      AuthoredCompactionField.Missing
    } else {
      AuthoredCompactionField.Value(value.phases.mapValues { (_, phase) ->
        AuthoredPhaseCompactionFields(
          windowTokens = AuthoredCompactionField.Value(phase.windowTokens),
          triggerPct = AuthoredCompactionField.Value(phase.triggerPct),
        )
      })
    },
  ),
)

private fun <T> authoredIfDifferent(value: T, default: T): AuthoredCompactionField<T> =
  if (value == default) AuthoredCompactionField.Missing else AuthoredCompactionField.Value(value)

private fun AuthoredCompactionField<Map<String, AuthoredPhaseCompactionFields>>.valueOrEmpty(): Map<String, AuthoredPhaseCompactionFields> =
  when (this) {
    AuthoredCompactionField.Missing, AuthoredCompactionField.Null -> emptyMap()
    is AuthoredCompactionField.Value -> value
  }

private fun <T> MutableMap<String, Any?>.putAuthored(key: String, field: AuthoredCompactionField<T>) {
  when (field) {
    AuthoredCompactionField.Missing -> Unit
    AuthoredCompactionField.Null -> put(key, null)
    is AuthoredCompactionField.Value -> put(key, field.value)
  }
}

private fun compactionNumber(value: String): Int =
  value.replace(",", "").replace("_", "").trim().toIntOrNull()
    ?: throw IllegalArgumentException("Enter a whole number.")

internal fun reviewBudgetValues(value: ReviewBudget): Map<String, Number> = mapOf(
  ConfigFileKeys.MAX_PARENT_PACKET_BYTES to value.maxParentPacketBytes,
  ConfigFileKeys.MAX_LANE_LAUNCH_BYTES to value.maxLaneLaunchBytes,
  ConfigFileKeys.MAX_LANE_EVIDENCE_BYTES to value.maxLaneEvidenceBytes,
  ConfigFileKeys.MAX_EVIDENCE_RESULT_BYTES to value.maxEvidenceResultBytes,
  ConfigFileKeys.MAX_LANE_RESULT_BYTES to value.maxLaneResultBytes,
  ConfigFileKeys.MAX_ASSIGNMENT_EXPANSIONS to value.maxAssignmentExpansions,
  ConfigFileKeys.MAX_SPECIALIST_TOOL_CALLS to value.maxSpecialistToolCalls,
  ConfigFileKeys.MAX_SPECIALIST_MODEL_TURNS to value.maxSpecialistModelTurns,
  ConfigFileKeys.MAX_ROUTING_ANALYSIS_PAIRS to value.maxRoutingAnalysisPairs,
  ConfigFileKeys.MAX_ROUTING_ANALYSIS_BYTES to value.maxRoutingAnalysisBytes,
)

private fun ModelDirective.section(): Map<String, String> = buildMap {
  put(ConfigFileKeys.MODEL, model)
  effort?.let { put(ConfigFileKeys.EFFORT, it) }
}

private fun Tier.wireValue() = when (this) {
  Tier.REASONING -> "reasoning"
  Tier.IMPLEMENTATION -> "implementation"
}
