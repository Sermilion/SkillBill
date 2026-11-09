package skillbill.infrastructure.sqlite.core.migration.area.criterion

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.migration.AcceptanceCriterionMigrationFailureCode
import skillbill.infrastructure.contracts.workflow.featuretask.preservation.AcceptanceCriterionMigrationCodec
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.artifact.historical.targets
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionArtifactTarget
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionMigrationTarget
import java.sql.Connection

internal object AcceptanceCriterionArtifactsMigration {
  private const val CONVERTED_PARAMETER = 1
  private const val WORKFLOW_PARAMETER = 2
  private const val ORIGINAL_PARAMETER = 3

  fun prepareCompatible(
    connection: Connection,
    diagnostics: RuntimeDiagnostics,
  ) = apply(connection, diagnostics, AcceptanceCriterionMigrationTarget.COMPATIBLE)

  fun apply(
    connection: Connection,
    diagnostics: RuntimeDiagnostics,
    target: AcceptanceCriterionMigrationTarget,
  ) = apply(connection, diagnostics, target.targets())

  fun apply(
    connection: Connection,
    diagnostics: RuntimeDiagnostics,
    targets: List<AcceptanceCriterionArtifactTarget>,
  ) {
    val sources =
      connection.prepareStatement(
        "SELECT workflow_id, artifacts_json FROM feature_task_workflows WHERE mode = 'runtime' ORDER BY workflow_id",
      ).use { statement ->
        statement.executeQuery().use { rows ->
          buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) }
        }
      }
    val targetLabels = targets.joinToString { "${it.family.label()}:${it.version}" }
    sources.forEach { (workflowId, original) ->
      val converted =
        try {
          AcceptanceCriterionMigrationCodec.prepare(workflowId, original, targets)
        } catch (cause: SkillBillRuntimeException) {
          diagnostics.warning(
            "seam=criterion_artifact_migration workflow_id=$workflowId targets=$targetLabels " +
              "failure_code=${cause.code} value_used=original_source_retained action=rollback_and_retry_readiness",
          )
          throw SkillBillRuntimeException(
            AcceptanceCriterionMigrationFailureCode.ARTIFACT_MIGRATION_REFUSED,
            "Workflow '$workflowId' criterion migration failed. The transaction retains original artifacts_json " +
              "and its ledger. Keep the previous runtime available, resolve the source-version failure, " +
              "then retry readiness. ${cause.message}",
            cause,
          )
        }
      if (converted != original) {
        connection.prepareStatement(
          "UPDATE feature_task_workflows SET artifacts_json = ? WHERE workflow_id = ? AND artifacts_json = ?",
        ).use { statement ->
          statement.setString(CONVERTED_PARAMETER, converted)
          statement.setString(WORKFLOW_PARAMETER, workflowId)
          statement.setString(ORIGINAL_PARAMETER, original)
          check(statement.executeUpdate() == 1) { "Migration lost the owning source row for $workflowId" }
        }
        diagnostics.info(
          "seam=criterion_artifact_migration workflow_id=$workflowId targets=$targetLabels " +
            "value_used=retained_source_conversion",
        )
      }
    }
  }
}
