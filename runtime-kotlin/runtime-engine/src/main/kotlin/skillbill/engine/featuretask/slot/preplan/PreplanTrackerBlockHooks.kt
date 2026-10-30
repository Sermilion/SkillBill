package skillbill.engine.featuretask.slot.preplan

import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

internal object PreplanTrackerBlockHooks : PhaseStepHooks<PhaseAcceptedStepExecution> {
  override val blockedOutputRetryCorrection: (FeatureTaskRuntimeWorkflowArtifactMap) -> String? =
    { outputMap -> TRACKER_BLOCK_CORRECTION.takeIf { blocksOnTrackerData(outputMap) } }

  internal fun blocksOnTrackerData(outputMap: FeatureTaskRuntimeWorkflowArtifactMap): Boolean {
    if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() != WorkflowStepStatus.BLOCKED) {
      return false
    }
    return blockReasonText(outputMap)
      .split(SENTENCE_BREAK)
      .any { sentence -> TRACKER_SUBJECT.containsMatchIn(sentence) && TRACKER_FAILURE.containsMatchIn(sentence) }
  }

  private fun blockReasonText(outputMap: FeatureTaskRuntimeWorkflowArtifactMap): String {
    val produced = outputMap[SharedPayloadKeys.PRODUCED_OUTPUTS] as? Map<*, *>
    val reasons =
      when (val value = produced?.get("blocking_reasons")) {
        is List<*> -> value.mapNotNull { it as? String }
        is String -> listOf(value)
        else -> emptyList()
      }
    return (
      listOf(outputMap[SharedPayloadKeys.SUMMARY], produced?.get(SharedPayloadKeys.VALUE)).mapNotNull {
        it as? String
      } + reasons
    ).joinToString("\n")
  }

  const val TRACKER_BLOCK_CORRECTION =
    "Pre-planning blocked on tracker data. The intake already carries the requirements, and pre-planning needs no " +
      "tracker issue, comments, or connection. Do not call tracker tools. Produce the digest from the intake and " +
      "the repository. Block only for a reason that does not depend on tracker data."

  private val SENTENCE_BREAK = Regex("""[.;\n]+""")

  private val TRACKER_SUBJECT =
    Regex(
      """\b(?:tracker|linear|jira|get_issue|list_comments|issue comments|issue text|issue description)\b""",
      RegexOption.IGNORE_CASE,
    )

  private val TRACKER_FAILURE =
    Regex(
      """\b(?:not found|could not find|cannot find|can't find|unavailable|not connected|no access|""" +
        """access denied|invalid_request|unauthori[sz]ed|failed|missing|unreachable)\b""",
      RegexOption.IGNORE_CASE,
    )
}
