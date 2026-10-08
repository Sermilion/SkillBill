package skillbill.goalrunner

import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangePause

/**
 * The operator-facing detail of a no-change pause: the reason, every criterion's verdict and evidence, the citations,
 * the boundary trace, the owning system when known, the suggested handoff, and the three operator choices.
 */
fun FeatureTaskRuntimeNoChangePause.stopDetail(subtaskId: Int): String =
  buildString {
    appendLine("Subtask $subtaskId paused on a confirmed no-change claim (reason: ${reason.wireValue}).")
    appendLine("Criteria:")
    criteria.forEach { criterion ->
      appendLine("- ${criterion.criterionId}: ${criterion.verdict.wireValue}. Evidence: ${criterion.evidence}")
    }
    appendLine("Citations: ${citations.joinToString(", ")}")
    appendLine("Boundary trace: $boundaryTrace")
    owningSystem?.let { appendLine("Owning system: $it") }
    appendLine("Suggested handoff: $suggestedHandoff")
    append(
      "Operator choices: accept_and_advance; retry_fix --instructions \"<text>\"; abandon_subtask. " +
        "Record one with the goal operator-decision command, then resume the goal.",
    )
  }
