package skillbill.engine.goalrunner.planning.outcome

import skillbill.agent.model.PhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput

internal fun proseRecordPayload(
  phaseId: String,
  prose: String,
): String =
  NormalizedFeatureTaskRuntimePhaseOutput(
    phaseId = phaseId,
    status = "completed",
    summary = proseRecordSummary(prose),
    output = PhaseOutput(value = prose),
  ).canonicalJson

private fun proseRecordSummary(prose: String): String =
  prose.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty().take(MAX_SUMMARY_CHARS)

private const val MAX_SUMMARY_CHARS = 240
