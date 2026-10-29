package dev.skillbill.desktop.core.data.config

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.AgentModels
import dev.skillbill.desktop.core.domain.model.ConfigAgent
import dev.skillbill.desktop.core.domain.model.ConfigDefaults
import dev.skillbill.desktop.core.domain.model.PhaseGroupKind
import dev.skillbill.desktop.core.domain.model.ResolvedPhase
import dev.skillbill.desktop.core.domain.model.RuntimePhase
import skillbill.config.model.CompactionSettings
import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionTier
import skillbill.engine.featuretask.phase.prompt.compose.PhaseProjectInstructions
import skillbill.install.model.MODEL_DIRECTIVE_CAPABLE_AGENTS
import skillbill.install.model.SupportedAgent
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.telemetry.DEFAULT_TELEMETRY_BATCH_SIZE
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import java.nio.file.Path

private val phaseGroups = linkedMapOf(
  PhaseGroupKind.PLANNING to listOf("intake", "preplan", "plan", "plan_subtasks"),
  PhaseGroupKind.BUILD to listOf("implement", "simplify", "implement_fix", "build"),
  PhaseGroupKind.AUDIT to listOf("audit", "audit_plan_fix", "audit_implement_fix"),
  PhaseGroupKind.REVIEW to listOf("review", "verify_findings", "present_findings"),
  PhaseGroupKind.VALIDATE_AND_QA to listOf("validate", "qa", "qa_fix"),
  PhaseGroupKind.SHIP to listOf("write_history", "commit_push", "pr", "monitor", "monitor_fix"),
  PhaseGroupKind.GOAL_FINISH to listOf("goal_assessment", "goal_assessment_fix"),
)

internal fun runtimeConfigDefaults(userHome: Path, diagnostics: Diagnostics): ConfigDefaults {
  val available = PhaseSlot.entries.flatMap { it.steps }.distinct()
  val known = phaseGroups.values.flatten().toSet()
  val ordered = phaseGroups.flatMap { (group, ids) -> ids.filter { it in available }.map { it to group } } +
    available.filterNot { it in known }.map { it to PhaseGroupKind.OTHER }
  val phases = ordered.mapNotNull { (id, group) ->
    val tier = try {
      ExecutionMatrix().tierOf(id)
    } catch (_: NoSuchElementException) {
      diagnostics.record("desktop_config_phase_default_missing")
      return@mapNotNull null
    }
    RuntimePhase(id, tier.toDomain(), group)
  }
  val agents = SupportedAgent.entries.sortedBy { it !in MODEL_DIRECTIVE_CAPABLE_AGENTS }
    .map { ConfigAgent(it.id, it in MODEL_DIRECTIVE_CAPABLE_AGENTS) }
  return ConfigDefaults(
    phases, agents, CompactionSettings.DEFAULT.toDomain(), DEFAULT_TELEMETRY_BATCH_SIZE,
    ReviewContextBudgetPolicy().toDomain(), PhaseProjectInstructions.MAX_BYTES.toLong(),
    userHome.resolve(".skill-bill").resolve("install-selection.json").toString(),
  )
}

internal fun resolveModelMatrix(matrix: ExecutionMatrix?, defaults: ConfigDefaults): Map<String, AgentModels> {
  val value = matrix ?: ExecutionMatrix()
  return SupportedAgent.entries.filter { it in MODEL_DIRECTIVE_CAPABLE_AGENTS }.associate { agent ->
    agent.id to AgentModels(
      agent.id,
      ExecutionTier.entries.associate { it.toDomain() to value.agents[agent]?.get(it)?.toDomain() },
      defaults.phases.map { phase ->
        val tier = value.tierOf(phase.id).toDomain()
        ResolvedPhase(
          phase.id, tier, phase.id in value.phaseTiers && tier != phase.defaultTier,
          value.directiveFor(agent.id, phase.id)?.toDomain(), value.agentPhaseOverrides[agent]?.containsKey(phase.id) == true,
        )
      },
    )
  }
}
