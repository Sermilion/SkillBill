package dev.skillbill.desktop.core.data.config

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.AgentRoutingSource
import dev.skillbill.desktop.core.domain.model.AgentRuntimeAvailability
import dev.skillbill.desktop.core.domain.model.CompactionConfig
import dev.skillbill.desktop.core.domain.model.ConfigDefaults
import dev.skillbill.desktop.core.domain.model.MachineConfigChange
import dev.skillbill.desktop.core.domain.model.MachineConfigExecutionMatrixEditIntent
import dev.skillbill.desktop.core.domain.model.MachineConfigPhaseAgentEdit
import dev.skillbill.desktop.core.domain.model.MachineConfigTierAgentEdit
import dev.skillbill.desktop.core.domain.model.PhaseGroupKind
import dev.skillbill.desktop.core.domain.model.ReviewBudget
import dev.skillbill.desktop.core.domain.model.RuntimePhase
import dev.skillbill.desktop.core.domain.model.Tier
import skillbill.application.config.model.MachineConfigEdit
import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionTier
import skillbill.install.model.SupportedAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentRoutingTransportTest {
  @Test
  fun `machine values carry authored tier and phase routing`() {
    val matrix =
      ExecutionMatrix(
        tierAgents = mapOf(ExecutionTier.REASONING to SupportedAgent.CLAUDE),
        phaseAgents = mapOf("goal_assessment" to SupportedAgent.CODEX),
      )

    val mapped = matrix.toDomain()

    assertEquals(mapOf(Tier.REASONING to "claude"), mapped.tierAgents)
    assertEquals(mapOf("goal_assessment" to "codex"), mapped.phaseAgents)
  }

  @Test
  fun `routing edit intent reaches the runtime edit model`() {
    val intent =
      MachineConfigExecutionMatrixEditIntent(
        tierAgents = listOf(MachineConfigTierAgentEdit(Tier.IMPLEMENTATION)),
        phaseAgents = listOf(MachineConfigPhaseAgentEdit("review")),
      )
    val change = MachineConfigChange.SetExecutionMatrix(emptyMap(), intent)

    val mapped = change.toRuntime() as MachineConfigEdit.SetExecutionMatrix

    assertEquals(
      ExecutionTier.IMPLEMENTATION,
      mapped.editIntent?.tierAgents?.single()?.tier,
    )
    assertEquals("review", mapped.editIntent?.phaseAgents?.single()?.phaseId)
  }

  @Test
  fun `routing view distinguishes configured and inherited sources`() {
    val defaults = defaults()
    val matrix =
      ExecutionMatrix(
        tierAgents = mapOf(ExecutionTier.REASONING to SupportedAgent.CLAUDE),
        phaseAgents = mapOf("review" to SupportedAgent.CODEX),
      )

    val routing =
      resolveRoutingView(
        matrix,
        defaults,
        mapOf("junie" to AgentRuntimeAvailability.ExecutableMissing("no junie on PATH")),
        NoopDiagnostics,
      )

    val review = routing.phases.single { it.phaseId == "review" }
    assertEquals(AgentRoutingSource.CONFIGURED_PHASE, review.source)
    assertEquals("codex", review.providerId)

    val plan = routing.phases.single { it.phaseId == "plan" }
    assertEquals(AgentRoutingSource.CONFIGURED_TIER, plan.source)
    assertEquals("claude", plan.providerId)

    val implement = routing.phases.single { it.phaseId == "implement" }
    assertEquals(AgentRoutingSource.INHERITED_INVOCATION, implement.source)
    assertNull(implement.providerId)
  }

  private fun defaults() = ConfigDefaults(
    listOf(
      RuntimePhase("plan", Tier.REASONING, PhaseGroupKind.PLANNING),
      RuntimePhase("implement", Tier.IMPLEMENTATION, PhaseGroupKind.BUILD),
      RuntimePhase("review", Tier.REASONING, PhaseGroupKind.REVIEW),
    ),
    emptyList(),
    CompactionConfig(true, 400000, 70, emptyMap()),
    50,
    ReviewBudget(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
    16384,
    "install-selection.json",
  )

  private object NoopDiagnostics : Diagnostics {
    override fun record(category: String) = Unit

    override fun recordRequest(category: String, fields: Map<String, Number>) = Unit
  }
}
