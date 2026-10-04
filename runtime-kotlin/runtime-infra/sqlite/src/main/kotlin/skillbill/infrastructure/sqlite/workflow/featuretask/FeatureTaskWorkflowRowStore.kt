package skillbill.infrastructure.sqlite.workflow.featuretask
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.error.shellcontent.proseFeatureTaskWorkflowWriteRefusedError
import skillbill.infrastructure.sqlite.workflow.FeatureTaskWorkflowUpsertRequest
import skillbill.infrastructure.sqlite.workflow.defaultContractVersion
import skillbill.infrastructure.sqlite.workflow.defaultImplementationSkill
import skillbill.infrastructure.sqlite.workflow.getFeatureTaskWorkflowRow
import skillbill.infrastructure.sqlite.workflow.listFeatureTaskWorkflowRows
import skillbill.infrastructure.sqlite.workflow.terminalizeLegacyProseFeatureTaskWorkflowRow
import skillbill.infrastructure.sqlite.workflow.upsertFeatureTaskWorkflowRow
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.sql.Connection
import java.time.Clock

internal class FeatureTaskWorkflowRowStore(
  private val connection: Connection,
  private val clock: Clock,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
) {
  fun saveFeatureTaskWorkflow(
    row: WorkflowStateRecord,
    mode: FeatureTaskWorkflowMode,
  ) {
    if (mode == FeatureTaskWorkflowMode.PROSE) {
      throw proseFeatureTaskWorkflowWriteRefusedError(row.workflowId)
    }
    connection.upsertFeatureTaskWorkflowRow(
      row = row,
      request =
        FeatureTaskWorkflowUpsertRequest(
          mode = mode,
          implementationSkill = row.implementationSkill.orEmpty().ifBlank { mode.defaultImplementationSkill },
          defaultContractVersion = mode.defaultContractVersion,
          clock = clock,
          workflowSnapshotValidator = workflowSnapshotValidator,
        ),
    )
  }

  fun getFeatureTaskWorkflow(workflowId: String): WorkflowStateRecord? =
    connection.getFeatureTaskWorkflowRow(workflowId)

  fun getFeatureTaskWorkflowAsMode(
    workflowId: String,
    mode: FeatureTaskWorkflowMode,
  ): WorkflowStateRecord? {
    val row = connection.getFeatureTaskWorkflowRow(workflowId) ?: return null
    if (row.mode != mode) {
      throw invalidWorkflowStateSchemaError(
        "Feature-task workflow '$workflowId' is mode='${row.mode?.wireValue.orEmpty()}', not '${mode.wireValue}'.",
      )
    }
    return row
  }

  fun listFeatureTaskWorkflows(
    mode: FeatureTaskWorkflowMode,
    limit: Int,
  ): List<WorkflowStateRecord> = connection.listFeatureTaskWorkflowRows(mode, limit)

  fun findFeatureTaskWorkflowsForIssue(
    mode: FeatureTaskWorkflowMode,
    normalizedIssueKey: String,
    repositoryIdentity: String?,
  ): List<WorkflowStateRecord> =
    connection.listFeatureTaskWorkflowRows(mode, Int.MAX_VALUE, normalizedIssueKey, repositoryIdentity)

  fun latestFeatureTaskWorkflow(mode: FeatureTaskWorkflowMode): WorkflowStateRecord? =
    listFeatureTaskWorkflows(mode, 1).firstOrNull()

  fun terminalizeLegacyProseFeatureTaskWorkflow(row: WorkflowStateRecord) {
    connection.terminalizeLegacyProseFeatureTaskWorkflowRow(row)
  }
}
