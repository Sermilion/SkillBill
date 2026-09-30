package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.cli.kernel.agent.requireInvokingAgentId
import skillbill.config.model.CompactionSettings
import skillbill.config.model.PhaseModelDirective
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.workflow.model.goalreview.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

internal fun parsePhaseAgents(rawAssignments: List<String>): Map<String, String> {
  val parsed = LinkedHashMap<String, String>()
  rawAssignments.forEach { assignment ->
    val separatorIndex = assignment.indexOf('=')
    if (separatorIndex <= 0 || separatorIndex == assignment.length - 1) {
      throw UsageError("--phase-agent must be phase=agent, e.g. --phase-agent plan=claude (got '$assignment').")
    }
    val phaseId = assignment.substring(0, separatorIndex).trim()
    val agentId = assignment.substring(separatorIndex + 1).trim()
    if (phaseId !in FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds) {
      throw UsageError(
        "--phase-agent phase '$phaseId' is not a runtime phase " +
          "(${FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.joinToString()}).",
      )
    }
    parsed[phaseId] = agentId
  }
  return parsed
}

internal fun parsePhaseModels(rawAssignments: List<String>): Map<String, PhaseModelDirective> {
  val parsed = LinkedHashMap<String, PhaseModelDirective>()
  rawAssignments.forEach { assignment ->
    val separatorIndex = assignment.indexOf('=')
    if (separatorIndex <= 0 || separatorIndex == assignment.length - 1) {
      invalidPhaseModel(
        "--phase-model must be phase=model[@effort], e.g. --phase-model plan=claude-opus-4-8@high " +
          "(got '$assignment').",
      )
    }
    val phaseId = assignment.substring(0, separatorIndex).trim()
    val modelAndEffort = assignment.substring(separatorIndex + 1).trim()
    if (phaseId !in FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds) {
      invalidPhaseModel(
        "--phase-model phase '$phaseId' is not a runtime phase " +
          "(${FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.joinToString()}).",
      )
    }
    if (modelAndEffort.count { it == '@' } > 1) {
      invalidPhaseModel("--phase-model allows at most one @ separating model and effort (got '$assignment').")
    }
    val effortSeparator = modelAndEffort.indexOf('@')
    val model = modelAndEffort.substringBefore('@').trim()
    val effort = if (effortSeparator == -1) null else modelAndEffort.substring(effortSeparator + 1).trim()
    if (model.isBlank() || effort?.isBlank() == true) {
      invalidPhaseModel("--phase-model requires non-blank model and effort segments (got '$assignment').")
    }
    parsed[phaseId] = PhaseModelDirective(model = model, effort = effort)
  }
  return parsed
}

internal fun invalidPhaseModel(message: String): Nothing = throw UsageError(message)

internal data class PreparedRuntimeRun(
  val issueKey: String,
  val specPath: String,
  val repoRoot: Path,
  val invokedAgentId: String,
  val agentAssignment: FeatureTaskRuntimeAgentAssignment,
  val modelAssignment: FeatureTaskRuntimeModelAssignment,
  val compactionSettings: CompactionSettings,
  val agentAddonSelection: HydratedAgentAddonSelection,
  val goalContinuation: FeatureTaskRuntimeGoalContinuationContext?,
  val operatorDecision: GoalSubtaskOperatorDecision?,
)

internal fun resolveInvokedRuntimeAgentId(
  explicitAgent: String?,
  environment: Map<String, String>,
): String = requireInvokingAgentId(explicitAgent, environment, "--agent")
