package skillbill.engine.goalrunner.planning.context

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import skillbill.ports.goalrunner.planning.model.GoalPlanningResolvedBoundaryBodies
import skillbill.workflow.decomposition.model.DecompositionSubtask

object GoalPlanningContextPromptFormatter {
  fun append(
    prompt: String,
    packet: Map<String, Any?>,
    subtask: DecompositionSubtask?,
    phaseId: String,
    resolvedBodies: GoalPlanningResolvedBoundaryBodies = GoalPlanningResolvedBoundaryBodies(),
  ): String =
    buildString {
      append(prompt)
      append("\n\n## Goal planning session context\n")
      append(
        if (phaseId == "plan") {
          "Reuse this immutable shared context for this sub-spec: "
        } else {
          "Use this immutable shared context for the parent goal: "
        },
      )
      append(JsonCodec.mapToJsonString(packet))
      append(
        "\nThis is child-only planning context. Do not copy its payload, implementation summary, " +
          "audit, review, diagnostic, or raw child output into the parent conversation or parent projection.",
      )
      append(" The parent retains manifest metadata, the current subtask index, and terminal outcomes only: ")
      append("{status, commit_sha, workflow_id}.")
      if (phaseId == "plan") {
        val currentSubtask = requireNotNull(subtask) { "plan context requires a governed subtask" }
        append("\nCurrent governed sub-spec: ")
        append(currentSubtask.specPath)
        append("\nCurrent subtask dependency context: ")
        append(
          JsonCodec.mapToJsonString(
            mapOf(
              SharedPayloadKeys.SUBTASK_ID to currentSubtask.id,
              "dependencies" to
                currentSubtask.dependencies.map { dependency ->
                  mapOf(
                    SharedPayloadKeys.SUBTASK_ID to dependency.subtaskId,
                    "optional" to dependency.optional,
                    "skipped" to dependency.skipped,
                  )
                },
            ),
          ),
        )
        append("\nDependency metadata is planning context only. ")
        append("Do not execute, simulate, edit, or mutate dependency work.")
        appendSelectedBoundaryMemory(resolvedBodies)
      } else {
        append(
          "\nboundary_memory is a heading catalog: heading text and stable heading_id only, no entry bodies. " +
            "Walk the headings, stop once they are no longer relevant to this goal's scope, and weave the " +
            "relevant context into your final prose for the plan phase. Recommended headings " +
            "may guide your prose; selected_boundary_headings is not required.",
        )
      }
    }

  private fun StringBuilder.appendSelectedBoundaryMemory(resolved: GoalPlanningResolvedBoundaryBodies) {
    if (resolved.bodies.isEmpty() && resolved.unresolvedHeadingIds.isEmpty()) return
    append("\n\n## Selected boundary memory\n")
    for (body in resolved.bodies) {
      append("\n### ")
      append(body.headingId)
      append("\n")
      append(body.heading)
      append("\n")
      append(body.body)
      append("\n")
    }
    if (resolved.unresolvedHeadingIds.isNotEmpty()) {
      append("\nUnresolved selections (no body delivered): ")
      append(
        resolved.unresolvedHeadingIds
          .take(GoalPlanningContext.MAX_REPORTED_UNRESOLVED_IDS)
          .joinToString(", ", transform = ::singleLineId),
      )
      val omitted = resolved.unresolvedHeadingIds.size - GoalPlanningContext.MAX_REPORTED_UNRESOLVED_IDS
      if (omitted > 0) append(" (+$omitted more)")
      append("\n")
    }
    if (resolved.truncated) append("\nSelected boundary memory was truncated at its resolved-body cap.\n")
  }

  private fun singleLineId(headingId: String): String =
    headingId
      .replace(WHITESPACE_RUN, " ")
      .trim()
      .take(GoalPlanningContext.MAX_REPORTED_UNRESOLVED_ID_CHARS)

  private val WHITESPACE_RUN = Regex("\\s+")
}
