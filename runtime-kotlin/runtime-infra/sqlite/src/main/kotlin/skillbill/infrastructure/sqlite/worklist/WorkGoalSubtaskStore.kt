package skillbill.infrastructure.sqlite.worklist

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.error.shellcontent.invalidWorkListRowError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.work.model.WorkGoalSubtaskPage
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.DecompositionStatus
import java.sql.Connection

internal class WorkGoalSubtaskStore(private val connection: Connection) {
  fun page(
    workflowId: String,
    offset: Int,
    limit: Int,
    completedOnly: Boolean,
    subtaskIds: Set<Int>? = null,
    afterSubtaskId: Int? = null,
  ): WorkGoalSubtaskPage? {
    require(offset >= 0 && limit in 1..WorkPaginationLimits.MAX_PAGE_SIZE)
    require(subtaskIds == null || subtaskIds.size in 1..WorkPaginationLimits.MAX_PAGE_SIZE)
    require(afterSubtaskId == null || offset == 0) { "An anchored subtask page cannot also carry an offset." }
    val idPath = "json_extract(value, '$.${DecompositionPlanningPayloadKeys.ID}')"
    val matching =
      if (subtaskIds != null) {
        listOf("$idPath IN (${subtaskIds.joinToString { "?" }})")
      } else if (completedOnly) {
        listOf("json_extract(value, '$.${SharedPayloadKeys.STATUS}') = '${DecompositionStatus.COMPLETE.wireValue}'")
      } else {
        emptyList()
      }
    val predicate = matching.toWhere()
    val pagePredicate = (matching + listOfNotNull(afterSubtaskId?.let { "$idPath > ?" })).toWhere()
    val manifestPath = "$.${DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label()}"
    val subtasksPath = "$manifestPath.${DecompositionPlanningPayloadKeys.SUBTASKS}"
    val currentPath =
      "$manifestPath.${DecompositionManifestPayloadKeys.CURRENT_SUBTASK_INTENT}.${SharedPayloadKeys.SUBTASK_ID}"
    return connection.prepareStatement(
      """
      SELECT json_set(json_extract(artifacts_json, '$manifestPath'),
        '$.${DecompositionPlanningPayloadKeys.SUBTASKS}', json((
          SELECT json_group_array(json(value)) FROM (
            SELECT json_set(value,
              '$.${DecompositionPlanningPayloadKeys.DEPENDENCIES}', json((SELECT json_group_array(json(value)) FROM (
                SELECT value FROM json_each(subtask.value, '$.${DecompositionPlanningPayloadKeys.DEPENDENCIES}')
                ORDER BY CAST(key AS INTEGER) LIMIT $DEPENDENCY_PREVIEW_SIZE
              ))), '$.${DecompositionManifestPayloadKeys.PARTICIPATING_AGENT_IDS}', json((
                SELECT json_group_array(value) FROM (SELECT value
                  FROM json_each(subtask.value, '$.${DecompositionManifestPayloadKeys.PARTICIPATING_AGENT_IDS}')
                  ORDER BY CAST(key AS INTEGER) LIMIT 1)
              ))) AS value
            FROM json_each(artifacts_json, '$subtasksPath') AS subtask $pagePredicate
            ORDER BY $idPath, CAST(key AS INTEGER) LIMIT ? OFFSET ?
          ))), '$.${DecompositionPlanningPayloadKeys.STACK_BRANCHES}', json('[]')) AS manifest_page,
        (SELECT count(*) FROM json_each(artifacts_json, '$subtasksPath') $predicate) AS total_count,
        (SELECT count(*) FROM json_each(artifacts_json, '$subtasksPath') $pagePredicate) AS after_count,
        (SELECT json_extract(value, '$.${SharedPayloadKeys.WORKFLOW_ID}')
         FROM json_each(artifacts_json, '$subtasksPath')
         WHERE json_extract(value, '$.${DecompositionPlanningPayloadKeys.ID}') = json_extract(artifacts_json, '$currentPath')
         LIMIT 1) AS current_workflow_id
      FROM feature_task_workflows WHERE workflow_id = ?
      """.trimIndent(),
    ).use { statement ->
      val ids = subtaskIds.orEmpty().toList()
      val after = listOfNotNull(afterSubtaskId)
      statement.bindAll(ids + after + listOf(limit, offset) + ids + ids + after + workflowId)
      statement.executeQuery().use { rows ->
        if (!rows.next()) return@use null
        val json = rows.getString("manifest_page") ?: return@use null
        val value =
          JsonCodec.parseObjectOrNull(json)?.let(JsonCodec::jsonElementToValue)
            ?: throw invalidWorkListRowError("Goal manifest page is invalid.")
        val artifacts =
          DurableWorkflowArtifacts.fromMap(mapOf(DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.entry(value)))
        val manifest =
          artifacts.decompositionRuntime()
            ?: throw invalidWorkListRowError("Goal manifest page is absent.")
        val total = rows.getInt("total_count")
        val remaining = if (afterSubtaskId != null) rows.getInt("after_count") else (total - offset).coerceAtLeast(0)
        WorkGoalSubtaskPage(manifest, total, rows.getString("current_workflow_id"), remaining)
      }
    }
  }
}

private fun List<String>.toWhere(): String = if (isEmpty()) "" else "WHERE ${joinToString(" AND ")}"

private const val DEPENDENCY_PREVIEW_SIZE = 10
