package skillbill.infrastructure.sqlite.workflow.goalrunner.planning

import skillbill.contracts.JsonCodec
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import java.sql.Connection

internal fun Connection.tableHasColumn(
  table: String,
  column: String,
): Boolean =
  prepareStatement("SELECT 1 FROM pragma_table_info(?) WHERE name = ?").use { statement ->
    statement.setString(1, table)
    statement.setString(2, column)
    statement.executeQuery().use { rows -> rows.next() }
  }

internal fun repairEvidenceJsonValue(repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?): String? =
  repairEvidence?.asWorkflowArtifactEntry()?.let(JsonCodec::valueToJsonString)
