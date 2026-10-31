package skillbill.engine.goalrunner.planning.context

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.engine.goalrunner.planning.model.GoalPlanningSubtaskPlanningDisposition
import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryHeadingKind
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import skillbill.text.sha256HexUtf8

public object GoalPlanningSharedContextPacketValidation {
  private val BOUNDARY_MEMORY_FIELDS =
    setOf(
      GoalPlanningSharedContextPacketPayloadKeys.CATALOG,
      GoalPlanningSharedContextPacketPayloadKeys.TRUNCATED,
    )
  private val CATALOG_ENTRY_FIELDS =
    setOf(
      GoalPlanningSharedContextPacketPayloadKeys.HEADING_ID,
      GoalPlanningSharedContextPacketPayloadKeys.SOURCE_PATH,
      GoalPlanningSharedContextPacketPayloadKeys.KIND,
      GoalPlanningSharedContextPacketPayloadKeys.HEADING,
    )
  private val CATALOG_KINDS = setOf(GoalPlanningContext.KIND_HISTORY, GoalPlanningContext.KIND_DECISIONS)
  private val SUBTASK_FIELDS =
    setOf(
      DecompositionPlanningPayloadKeys.ID,
      DecompositionPlanningPayloadKeys.NAME,
      DecompositionPlanningPayloadKeys.SPEC_PATH,
      GoalPlanningSharedContextPacketPayloadKeys.PLANNING_DISPOSITION,
      DecompositionPlanningPayloadKeys.DEPENDENCIES,
    )
  private val DEPENDENCY_FIELDS =
    setOf(
      SharedPayloadKeys.SUBTASK_ID,
      DecompositionPlanningPayloadKeys.OPTIONAL,
      DecompositionPlanningPayloadKeys.SKIPPED,
    )
  private val DISPOSITIONS = GoalPlanningSubtaskPlanningDisposition.entries.map { it.wireValue }.toSet()

  internal fun requireValidCatalog(value: Any?) {
    val boundaryMemory =
      value as? Map<*, *>
        ?: invalidGoalPlanningSharedContextPacket(
          GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY,
          "shared context boundary memory is invalid",
        )
    if (boundaryMemory.keys != BOUNDARY_MEMORY_FIELDS) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY,
        "shared context boundary memory is invalid",
      )
    }
    if (boundaryMemory[GoalPlanningSharedContextPacketPayloadKeys.TRUNCATED] !is Boolean) {
      invalidGoalPlanningSharedContextPacket(
        "${GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY}." +
          GoalPlanningSharedContextPacketPayloadKeys.TRUNCATED,
        "shared context boundary memory truncation flag is invalid",
      )
    }
    val catalog =
      boundaryMemory[GoalPlanningSharedContextPacketPayloadKeys.CATALOG] as? List<*>
        ?: invalidGoalPlanningSharedContextPacket(
          "${GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY}." +
            GoalPlanningSharedContextPacketPayloadKeys.CATALOG,
          "shared context boundary memory catalog is invalid",
        )
    if (catalog.size > GoalPlanningContext.MAX_CATALOG_HEADINGS) {
      invalidGoalPlanningSharedContextPacket(
        "${GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY}." +
          GoalPlanningSharedContextPacketPayloadKeys.CATALOG,
        "shared context boundary memory catalog exceeds the heading cap",
      )
    }
    val headingIds = mutableSetOf<String>()
    for (raw in catalog) {
      validateCatalogEntry(raw, headingIds)
    }
  }

  private fun validateCatalogEntry(
    raw: Any?,
    headingIds: MutableSet<String>,
  ) {
    val entry =
      raw as? Map<*, *>
        ?: invalidGoalPlanningSharedContextPacket(
          "${GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY}." +
            GoalPlanningSharedContextPacketPayloadKeys.CATALOG,
          "shared context boundary memory catalog entry is invalid",
        )
    if (entry.keys != CATALOG_ENTRY_FIELDS || !entry.values.all { it is String }) {
      invalidGoalPlanningSharedContextPacket(
        "${GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY}." +
          GoalPlanningSharedContextPacketPayloadKeys.CATALOG,
        "shared context boundary memory catalog entry is invalid",
      )
    }
    val sourcePath = entry[GoalPlanningSharedContextPacketPayloadKeys.SOURCE_PATH] as String
    if (sourcePath.isBlank() || sourcePath.startsWith("/") || ".." in sourcePath) {
      invalidGoalPlanningSharedContextPacket(
        "${GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY}." +
          "${GoalPlanningSharedContextPacketPayloadKeys.CATALOG}." +
          GoalPlanningSharedContextPacketPayloadKeys.SOURCE_PATH,
        "shared context boundary memory source path is invalid",
      )
    }
    if (
      GoalPlanningBoundaryHeadingKind.fromWire(entry[GoalPlanningSharedContextPacketPayloadKeys.KIND] as String) !in
      CATALOG_KINDS
    ) {
      invalidGoalPlanningSharedContextPacket(
        "${GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY}." +
          "${GoalPlanningSharedContextPacketPayloadKeys.CATALOG}." +
          GoalPlanningSharedContextPacketPayloadKeys.KIND,
        "shared context boundary memory kind is invalid",
      )
    }
    if ((entry[GoalPlanningSharedContextPacketPayloadKeys.HEADING] as String).length >
      GoalPlanningContext.MAX_HEADING_TEXT_CHARS
    ) {
      invalidGoalPlanningSharedContextPacket(
        "${GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY}." +
          "${GoalPlanningSharedContextPacketPayloadKeys.CATALOG}." +
          GoalPlanningSharedContextPacketPayloadKeys.HEADING,
        "shared context boundary memory heading exceeds the length cap",
      )
    }
    if (!headingIds.add(entry[GoalPlanningSharedContextPacketPayloadKeys.HEADING_ID] as String)) {
      invalidGoalPlanningSharedContextPacket(
        "${GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY}." +
          "${GoalPlanningSharedContextPacketPayloadKeys.CATALOG}." +
          GoalPlanningSharedContextPacketPayloadKeys.HEADING_ID,
        "shared context boundary memory heading ids must be unique",
      )
    }
  }

  internal fun normalizedSubtasks(value: Any?): List<Map<String, Any?>> {
    val entries =
      value as? List<*>
        ?: invalidGoalPlanningSharedContextPacket(
          GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS,
          "shared context ordered subtasks must be a list",
        )
    return entries.map { entry ->
      val subtask =
        entry as? Map<*, *>
          ?: invalidGoalPlanningSharedContextPacket(
            GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS,
            "shared context ordered subtask must be an object",
          )
      if (subtask.keys != SUBTASK_FIELDS) {
        invalidGoalPlanningSharedContextPacket(
          GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS,
          "shared context ordered subtask fields are invalid",
        )
      }
      val id =
        (subtask[DecompositionPlanningPayloadKeys.ID] as? Number)?.toInt()
          ?: invalidGoalPlanningSharedContextPacket(
            "${GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS}.${DecompositionPlanningPayloadKeys.ID}",
            "shared context ordered subtask id is invalid",
          )
      val name =
        subtask[DecompositionPlanningPayloadKeys.NAME] as? String
          ?: invalidGoalPlanningSharedContextPacket(
            "${GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS}.${DecompositionPlanningPayloadKeys.NAME}",
            "shared context ordered subtask name is invalid",
          )
      val specPath =
        subtask[DecompositionPlanningPayloadKeys.SPEC_PATH] as? String
          ?: invalidGoalPlanningSharedContextPacket(
            "${GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS}." +
              DecompositionPlanningPayloadKeys.SPEC_PATH,
            "shared context ordered subtask spec path is invalid",
          )
      val disposition =
        subtask[GoalPlanningSharedContextPacketPayloadKeys.PLANNING_DISPOSITION] as? String
          ?: invalidGoalPlanningSharedContextPacket(
            "${GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS}." +
              GoalPlanningSharedContextPacketPayloadKeys.PLANNING_DISPOSITION,
            "shared context ordered subtask planning disposition is invalid",
          )
      if (disposition !in DISPOSITIONS) {
        invalidGoalPlanningSharedContextPacket(
          "${GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS}." +
            GoalPlanningSharedContextPacketPayloadKeys.PLANNING_DISPOSITION,
          "shared context ordered subtask planning disposition is invalid",
        )
      }
      linkedMapOf(
        DecompositionPlanningPayloadKeys.ID to id,
        DecompositionPlanningPayloadKeys.NAME to name,
        DecompositionPlanningPayloadKeys.SPEC_PATH to specPath,
        GoalPlanningSharedContextPacketPayloadKeys.PLANNING_DISPOSITION to disposition,
        DecompositionPlanningPayloadKeys.DEPENDENCIES to
          normalizedDependencies(
            subtask[DecompositionPlanningPayloadKeys.DEPENDENCIES],
          ),
      )
    }
  }

  private fun normalizedDependencies(value: Any?): List<Map<String, Any?>> {
    val dependencies =
      value as? List<*>
        ?: invalidGoalPlanningSharedContextPacket(
          "${GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS}." +
            DecompositionPlanningPayloadKeys.DEPENDENCIES,
          "shared context subtask dependencies must be a list",
        )
    return dependencies.map { entry ->
      val dependency =
        entry as? Map<*, *>
          ?: invalidGoalPlanningSharedContextPacket(
            "${GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS}." +
              DecompositionPlanningPayloadKeys.DEPENDENCIES,
            "shared context subtask dependency must be an object",
          )
      if (dependency.keys != DEPENDENCY_FIELDS) {
        invalidGoalPlanningSharedContextPacket(
          "${GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS}." +
            DecompositionPlanningPayloadKeys.DEPENDENCIES,
          "shared context subtask dependency fields are invalid",
        )
      }
      linkedMapOf(
        SharedPayloadKeys.SUBTASK_ID to (
          (dependency[SharedPayloadKeys.SUBTASK_ID] as? Number)?.toInt()
            ?: invalidGoalPlanningSharedContextPacket(
              "${GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS}." +
                DecompositionPlanningPayloadKeys.DEPENDENCIES,
              "shared context dependency subtask id is invalid",
            )
        ),
        DecompositionPlanningPayloadKeys.OPTIONAL to (
          dependency[DecompositionPlanningPayloadKeys.OPTIONAL] as? Boolean
            ?: invalidGoalPlanningSharedContextPacket(
              "${GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS}." +
                DecompositionPlanningPayloadKeys.DEPENDENCIES,
              "shared context dependency optional flag is invalid",
            )
        ),
        DecompositionPlanningPayloadKeys.SKIPPED to (
          dependency[DecompositionPlanningPayloadKeys.SKIPPED] as? Boolean
            ?: invalidGoalPlanningSharedContextPacket(
              "${GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS}." +
                DecompositionPlanningPayloadKeys.DEPENDENCIES,
              "shared context dependency skipped flag is invalid",
            )
        ),
      )
    }
  }

  internal fun isStringMap(value: Any?): Boolean =
    value is Map<*, *> && value.keys.all { it is String } && value.values.all { it is String }

  public fun digest(packet: Map<String, Any?>): String = sha256HexUtf8(JsonCodec.mapToJsonString(packet))
}
