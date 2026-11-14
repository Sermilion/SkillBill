package skillbill.cli.kernel.agent

import com.github.ajalt.clikt.core.UsageError
import skillbill.install.model.SupportedAgent
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal fun parsePhaseAgents(
  rawAssignments: List<String>,
  phaseIds: Collection<String> = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds,
): Map<String, String> {
  val parsed = LinkedHashMap<String, String>()
  rawAssignments.forEach { assignment ->
    val separatorIndex = assignment.indexOf('=')
    if (separatorIndex <= 0 || separatorIndex == assignment.length - 1) {
      invalidPhaseAgent(
        "--phase-agent must be phase=agent, e.g. --phase-agent plan=claude (got '$assignment').",
      )
    }
    val phaseId = assignment.substring(0, separatorIndex).trim()
    val agentId = assignment.substring(separatorIndex + 1).trim()
    if (phaseId !in phaseIds) {
      invalidPhaseAgent(
        "--phase-agent phase '$phaseId' is not a runtime phase " +
          "(${phaseIds.joinToString()}).",
      )
    }
    val provider =
      SupportedAgent.fromWireOrNull(agentId)
        ?: invalidPhaseAgent(
          "--phase-agent provider '$agentId' is not a supported provider id " +
            "(${SupportedAgent.entries.joinToString { it.id }}).",
        )
    parsed[phaseId] = provider.id
  }
  return parsed
}

internal fun invalidPhaseAgent(message: String): Nothing = throw UsageError(message)
