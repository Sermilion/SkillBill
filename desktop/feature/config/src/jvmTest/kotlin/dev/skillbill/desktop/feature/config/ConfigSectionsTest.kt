package dev.skillbill.desktop.feature.config

import dev.skillbill.desktop.core.domain.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ConfigSectionsTest {
  @Test
  fun editingOneAgentPreservesOtherAgentOverrides() {
    val matrix = ExecutionMatrixConfig(
      mapOf("review" to Tier.REASONING),
      mapOf("codex" to mapOf(Tier.REASONING to ModelDirective("new-model", null))),
      mapOf("claude" to mapOf("review" to ModelDirective("custom-model", "high"))),
    )
    val section = executionMatrixSection(matrix, defaults())
    assertFalse(ConfigFileKeys.PHASE_TIERS in section)
    val agents = section[ConfigFileKeys.AGENTS] as Map<*, *>
    val claude = agents["claude"] as Map<*, *>
    assertEquals(mapOf("model" to "custom-model", "effort" to "high"), claude["review"])
  }

  @Test
  fun matchingPhaseDirectiveDoesNotBecomeAnOverride() {
    val directive = ModelDirective("model", "high")
    val matrix = ExecutionMatrixConfig(
      emptyMap(),
      mapOf("codex" to mapOf(Tier.REASONING to directive)),
      mapOf("codex" to mapOf("review" to directive)),
    )
    val agents = executionMatrixSection(matrix, defaults())[ConfigFileKeys.AGENTS] as Map<*, *>
    val codex = agents["codex"] as Map<*, *>
    assertEquals(setOf("reasoning"), codex.keys)
  }

  @Test
  fun unrelatedEditKeepsExplicitDefaultTierAndMatchingPhaseOverride() {
    val directive = ModelDirective("model", "high")
    val baseline = ExecutionMatrixConfig(
      mapOf("review" to Tier.REASONING),
      mapOf("claude" to mapOf(Tier.REASONING to directive, Tier.IMPLEMENTATION to ModelDirective("impl", null))),
      mapOf("claude" to mapOf("review" to directive)),
    )
    val unrelatedEdit = baseline.copy(
      agents = baseline.agents + ("codex" to mapOf(Tier.REASONING to ModelDirective("codex", null))),
    )
    val section = executionMatrixSection(unrelatedEdit, defaults(), baseline)
    val tiers = section[ConfigFileKeys.PHASE_TIERS] as Map<*, *>
    val agents = section[ConfigFileKeys.AGENTS] as Map<*, *>
    val claude = agents["claude"] as Map<*, *>

    assertEquals("reasoning", tiers["review"])
    assertEquals(mapOf("model" to "model", "effort" to "high"), claude["review"])

    val changedTier = unrelatedEdit.copy(phaseTiers = unrelatedEdit.phaseTiers + ("review" to Tier.IMPLEMENTATION))
    val changedSection = executionMatrixSection(changedTier, defaults(), baseline)
    val changedAgents = changedSection[ConfigFileKeys.AGENTS] as Map<*, *>
    val changedClaude = changedAgents["claude"] as Map<*, *>
    assertEquals("implementation", (changedSection[ConfigFileKeys.PHASE_TIERS] as Map<*, *>)["review"])
    assertEquals(mapOf("model" to "model", "effort" to "high"), changedClaude["review"])
  }

  @Test
  fun returningCompactionToDefaultsRemovesSectionWithoutDroppingPhaseOverrides() {
    val baseline = defaults().compaction
    assertEquals(emptyMap(), compactionSection(baseline, baseline))
    val section = compactionSection(baseline.copy(phases = mapOf("review" to PhaseCompaction(500000, 75))), baseline)
    assertEquals(mapOf("review" to mapOf("window_tokens" to 500000, "trigger_pct" to 75)), section[ConfigFileKeys.PHASES])
  }

  private fun defaults() = ConfigDefaults(
    listOf(RuntimePhase("review", Tier.REASONING, PhaseGroupKind.REVIEW)), emptyList(),
    CompactionConfig(true, 400000, 70, emptyMap()), 50,
    ReviewBudget(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1), 16384, "install-selection.json",
  )
}
