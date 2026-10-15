package skillbill.infrastructure.sqlite.worklist

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.error.shellcontent.invalidWorkListRowError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.work.model.WorkGoalDependency
import skillbill.ports.work.model.WorkGoalDependencyAnchor
import skillbill.ports.work.model.WorkGoalDependencyPage
import skillbill.workflow.decomposition.model.DecompositionDependency
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import java.sql.Connection
import java.sql.ResultSet

/**
 * One page of dependency edges keyed by `(subtask id, target id)`. A target listed twice under one subtask is one edge:
 * only its first listing is read, so the strict anchor never skips a sibling row.
 */
internal fun readWorkGoalDependencyPage(
  connection: Connection,
  workflowId: String,
  offset: Int,
  limit: Int,
  subtaskId: Int?,
  after: WorkGoalDependencyAnchor? = null,
): WorkGoalDependencyPage {
  require(offset >= 0 && limit in 1..WorkPaginationLimits.MAX_PAGE_SIZE)
  require(after == null || offset == 0) { "An anchored dependency page cannot also carry an offset." }
  val manifestPath = "$.${DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label()}"
  val path = "$manifestPath.${DecompositionPlanningPayloadKeys.SUBTASKS}"
  return connection.prepareStatement(
    """
    SELECT json_extract(subtask.value, '$.${DecompositionPlanningPayloadKeys.ID}') AS subtask_id,
      json_extract(dependency.value, '$.${SharedPayloadKeys.SUBTASK_ID}') AS dependency_id,
      json_extract(dependency.value, '$.${DecompositionPlanningPayloadKeys.OPTIONAL}') AS optional,
      json_extract(dependency.value, '$.${DecompositionPlanningPayloadKeys.SKIPPED}') AS skipped,
      json_type(subtask.value, '$.${DecompositionPlanningPayloadKeys.ID}') AS source_type,
      json_type(dependency.value, '$.${SharedPayloadKeys.SUBTASK_ID}') AS target_type,
      json_type(dependency.value, '$.${DecompositionPlanningPayloadKeys.OPTIONAL}') AS optional_type,
      json_type(dependency.value, '$.${DecompositionPlanningPayloadKeys.SKIPPED}') AS skipped_type
    FROM feature_task_workflows,
      json_each(artifacts_json, '$path') AS subtask,
      json_each(subtask.value, '$.${DecompositionPlanningPayloadKeys.DEPENDENCIES}') AS dependency
    WHERE workflow_id = ? AND (? IS NULL OR json_extract(subtask.value, '$.${DecompositionPlanningPayloadKeys.ID}') = ?)
      AND (? IS NULL OR (json_extract(subtask.value, '$.${DecompositionPlanningPayloadKeys.ID}'),
        json_extract(dependency.value, '$.${SharedPayloadKeys.SUBTASK_ID}')) > (?, ?))
      AND dependency.key = (SELECT MIN(first_listing.key)
        FROM json_each(subtask.value, '$.${DecompositionPlanningPayloadKeys.DEPENDENCIES}') AS first_listing
        WHERE json_extract(first_listing.value, '$.${SharedPayloadKeys.SUBTASK_ID}') =
          json_extract(dependency.value, '$.${SharedPayloadKeys.SUBTASK_ID}'))
    ORDER BY json_extract(subtask.value, '$.${DecompositionPlanningPayloadKeys.ID}'),
      json_extract(dependency.value, '$.${SharedPayloadKeys.SUBTASK_ID}'),
      CAST(subtask.key AS INTEGER), CAST(dependency.key AS INTEGER) LIMIT ? OFFSET ?
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      workflowId, subtaskId, subtaskId, after?.subtaskId, after?.subtaskId, after?.dependencyId, limit + 1, offset,
    )
    statement.executeQuery().use { rows ->
      val entries =
        buildList {
          while (rows.next()) {
            add(rows.toWorkGoalDependency())
          }
        }
      WorkGoalDependencyPage(entries.take(limit), (offset + limit).takeIf { entries.size > limit })
    }
  }
}

private fun ResultSet.toWorkGoalDependency(): WorkGoalDependency {
  val source = getInt(SharedPayloadKeys.SUBTASK_ID)
  val target = getInt("dependency_id")
  val integerIds = getString("source_type") == "integer" && getString("target_type") == "integer"
  if (source < 1 || target < 1 || !integerIds) {
    throw invalidWorkListRowError("Goal dependency ids must be positive integers.")
  }
  if (getString("optional_type") !in setOf("true", "false") || getString("skipped_type") !in setOf("true", "false")) {
    throw invalidWorkListRowError("Goal dependency flags must be booleans.")
  }
  return WorkGoalDependency(
    source,
    DecompositionDependency(
      target,
      getBoolean(DecompositionPlanningPayloadKeys.OPTIONAL),
      getBoolean(DecompositionPlanningPayloadKeys.SKIPPED),
    ),
  )
}
