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
      append(
        JsonCodec.mapToJsonString(
          packet - GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY,
        ),
      )
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
        appendBoundaryHeadingList(packet)
      }
    }

  private fun StringBuilder.appendBoundaryHeadingList(packet: Map<String, Any?>) {
    val boundary =
      packet[GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY] as? Map<*, *> ?: return
    val catalog = boundary[GoalPlanningSharedContextPacketPayloadKeys.CATALOG] as? List<*> ?: emptyList<Any?>()
    append("\n\n## Boundary heading list\n")
    append("Headings only, in walk order. No entry bodies.\n")
    var listed = 0
    for (raw in catalog) {
      val entry = raw as? Map<*, *> ?: continue
      val headingId = entry[GoalPlanningSharedContextPacketPayloadKeys.HEADING_ID] as? String ?: continue
      val sourcePath = entry[GoalPlanningSharedContextPacketPayloadKeys.SOURCE_PATH] as? String ?: continue
      val kind = entry[GoalPlanningSharedContextPacketPayloadKeys.KIND] as? String ?: continue
      val heading = entry[GoalPlanningSharedContextPacketPayloadKeys.HEADING] as? String ?: continue
      listed += 1
      append(listed)
      append(". ")
      append(singleLine(headingId))
      append(" | ")
      append(singleLine(sourcePath))
      append(" | ")
      append(singleLine(kind))
      append(" | ")
      append(singleLine(heading))
      append("\n")
    }
    if (listed == 0) append("The heading list is empty.\n")
    if (boundary[GoalPlanningSharedContextPacketPayloadKeys.TRUNCATED] == true) {
      append("The heading list was truncated.\n")
    }
    append(BOUNDARY_HEADING_WALK)
    append("\n")
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

  private fun singleLine(value: String): String = value.replace(WHITESPACE_RUN, " ").trim()

  private fun singleLineId(headingId: String): String =
    headingId
      .replace(WHITESPACE_RUN, " ")
      .trim()
      .take(GoalPlanningContext.MAX_REPORTED_UNRESOLVED_ID_CHARS)

  private val WHITESPACE_RUN = Regex("\\s+")

  internal const val BOUNDARY_HEADING_WALK: String =
    "Walk this list from the start. Judge each heading from its heading text alone. Stop when the three " +
      "headings you just read are all irrelevant to this task. Then read the body of each heading you judged " +
      "relevant: open its source file, find that heading, and read only that section. Do not read a history or " +
      "decisions file from start to finish, and do not read the body of a heading you judged irrelevant."
}
