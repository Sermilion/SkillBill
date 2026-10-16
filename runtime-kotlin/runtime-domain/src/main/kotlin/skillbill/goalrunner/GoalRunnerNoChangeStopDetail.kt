package skillbill.goalrunner

import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangePause

fun FeatureTaskRuntimeNoChangePause.stopDetail(subtaskId: Int): String =
  buildString {
    appendLine("Subtask $subtaskId can resume from its confirmed no-change claim (reason: ${reason.wireValue}).")
    appendLine("Criteria:")
    criteria.forEach { criterion ->
      appendLine("- ${criterion.criterionId}: ${criterion.verdict.wireValue}. Evidence: ${criterion.evidence}")
    }
    appendLine("Citations: ${citations.joinToString(", ")}")
    appendLine("Boundary trace: $boundaryTrace")
    owningSystem?.let { appendLine("Owning system: $it") }
    appendLine("Suggested handoff: $suggestedHandoff")
    append("Resume the goal to continue through audit, review and the selected quality gate without approval.")
  }
