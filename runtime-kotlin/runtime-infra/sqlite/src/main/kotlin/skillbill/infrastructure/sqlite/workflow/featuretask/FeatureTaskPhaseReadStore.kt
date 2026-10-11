package skillbill.infrastructure.sqlite.workflow.featuretask

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.workflow.model.FeatureTaskRuntimePhaseLedgerPage
import skillbill.ports.workflow.model.FeatureTaskRuntimeWorkflowHeader
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.artifact.decodePhaseLedgerEntryFromArtifact
import java.sql.Connection

internal class FeatureTaskPhaseReadStore(private val connection: Connection) {
  fun artifacts(
    workflowId: String,
    families: Set<DurableWorkflowArtifactFamily>,
  ): DurableWorkflowArtifacts? {
    require(families.isNotEmpty())
    val placeholders = families.joinToString { "?" }
    return connection.prepareStatement(
      """
      SELECT mode, (SELECT json_group_object(key,
        CASE WHEN type IN ('array', 'object') THEN json(value) ELSE value END)
        FROM json_each(artifacts_json) WHERE key IN ($placeholders)) AS selected_artifacts
      FROM feature_task_workflows WHERE workflow_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(families.map { it.label() } + workflowId)
      statement.executeQuery().use { rows ->
        if (!rows.next()) return@use null
        requireRuntimeMode(rows.getString("mode"))
        val value =
          JsonCodec.parseObjectOrNull(rows.getString("selected_artifacts"))
            ?.let(JsonCodec::jsonElementToValue)?.let(JsonCodec::anyToStringAnyMap)
            ?: throw invalidWorkflowStateSchemaError("Selected runtime artifacts must be an object.")
        DurableWorkflowArtifacts.fromMap(value)
      }
    }
  }

  fun header(workflowId: String): FeatureTaskRuntimeWorkflowHeader? =
    connection.prepareStatement(
      "SELECT mode, current_step_id FROM feature_task_workflows WHERE workflow_id = ?",
    ).use { statement ->
      statement.bindAll(workflowId)
      statement.executeQuery().use { rows ->
        if (!rows.next()) return@use null
        val mode =
          FeatureTaskWorkflowMode.fromWireValue(rows.getString(1))
            ?: throw invalidWorkflowStateSchemaError("Unknown feature-task workflow mode.")
        FeatureTaskRuntimeWorkflowHeader(mode, rows.getString(2))
      }
    }

  fun ledgerPage(
    workflowId: String,
    offset: Int,
    limit: Int,
  ): FeatureTaskRuntimePhaseLedgerPage? {
    require(offset >= 0 && limit in 1..WorkPaginationLimits.MAX_PAGE_SIZE)
    val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER
    return connection.prepareStatement(
      """
      SELECT mode, json_type(artifacts_json, '$.${family.label()}') AS ledger_type,
        (SELECT json_group_array(json(value)) FROM (
        SELECT value FROM json_each(CASE WHEN json_type(artifacts_json, '$.${family.label()}') = 'array'
          THEN json_extract(artifacts_json, '$.${family.label()}') ELSE '[]' END)
        ORDER BY CAST(key AS INTEGER) LIMIT ? OFFSET ?
      )) AS ledger_page
      FROM feature_task_workflows WHERE workflow_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(limit + 1, offset, workflowId)
      statement.executeQuery().use { rows ->
        if (!rows.next()) return@use null
        requireRuntimeMode(rows.getString("mode"))
        val type = rows.getString("ledger_type")
        if (type != null && type != "array") {
          throw invalidWorkflowStateSchemaError("Runtime phase ledger must be an array.")
        }
        val values =
          JsonCodec.parseValue(rows.getString("ledger_page")) as? List<*>
            ?: throw invalidWorkflowStateSchemaError("Runtime phase ledger page must be an array.")
        val entries = values.take(limit).map(::requireLedgerEntry)
        FeatureTaskRuntimePhaseLedgerPage(entries, (offset + limit).takeIf { values.size > limit })
      }
    }
  }
}

private fun requireRuntimeMode(mode: String?) {
  if (mode != FeatureTaskWorkflowMode.RUNTIME.wireValue) {
    throw invalidWorkflowStateSchemaError("Runtime artifact read requires runtime workflow mode.")
  }
}

private fun requireLedgerEntry(raw: Any?) =
  decodePhaseLedgerEntryFromArtifact(raw)
    ?: throw invalidWorkflowStateSchemaError("Runtime phase ledger entry must be an object.")
