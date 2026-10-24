package skillbill.infrastructure.sqlite

import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QaOutcomePayloadKeys
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.QA_FAILURE_KIND_COLUMN
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.QA_REPORT_TEXT_COLUMN
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.featuretask.FeatureTaskQaOutcomeRepository
import skillbill.ports.featuretask.model.FeatureTaskQaFailureKind
import skillbill.ports.featuretask.model.FeatureTaskQaFinding
import skillbill.ports.featuretask.model.FeatureTaskQaFindingStatus
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import java.sql.Connection

internal class SqliteFeatureTaskQaOutcomeStore(
  private val connection: Connection,
) : FeatureTaskQaOutcomeRepository {
  override fun upsert(outcome: FeatureTaskQaOutcome) {
    connection.prepareStatement(
      """
      INSERT INTO feature_task_qa_outcomes (
        workflow_id, outcome, reason, qa_fix_attempts, tested_tree_identity, recorded_at, findings_summary,
        $QA_REPORT_TEXT_COLUMN, $QA_FAILURE_KIND_COLUMN
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(workflow_id) DO UPDATE SET
        outcome = excluded.outcome,
        reason = excluded.reason,
        qa_fix_attempts = excluded.qa_fix_attempts,
        tested_tree_identity = excluded.tested_tree_identity,
        recorded_at = excluded.recorded_at,
        findings_summary = excluded.findings_summary,
        $QA_REPORT_TEXT_COLUMN = excluded.$QA_REPORT_TEXT_COLUMN,
        $QA_FAILURE_KIND_COLUMN = excluded.$QA_FAILURE_KIND_COLUMN
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        outcome.workflowId,
        outcome.outcome.wireValue,
        outcome.reason,
        outcome.qaFixAttempts,
        outcome.testedTreeIdentity,
        outcome.recordedAt,
        outcome.findings.takeIf(List<FeatureTaskQaFinding>::isNotEmpty)?.let(::encodeFindings),
        outcome.reportText,
        outcome.failureKind?.wireValue,
      )
      statement.executeUpdate()
    }
  }

  override fun find(workflowId: String): FeatureTaskQaOutcome? =
    connection.prepareStatement(
      """
      SELECT workflow_id, outcome, reason, qa_fix_attempts, tested_tree_identity, recorded_at, findings_summary,
             $QA_REPORT_TEXT_COLUMN, $QA_FAILURE_KIND_COLUMN
      FROM feature_task_qa_outcomes
      WHERE workflow_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(workflowId)
      statement.executeQuery().use { rows ->
        if (!rows.next()) return@use null
        val raw = rows.getString("outcome")
        val outcome =
          StandaloneQaOutcome.fromWire(raw)
            ?: throw invalidWorkflowStateSchemaError("Stored QA outcome '$raw' is not a known outcome.")
        val decodedFindings = rows.getString("findings_summary")?.let(::decodeFindings).orEmpty()
        val storedReportText = rows.getString(QA_REPORT_TEXT_COLUMN)
        FeatureTaskQaOutcome(
          workflowId = rows.getString("workflow_id"),
          outcome = outcome,
          reason = rows.getString("reason"),
          qaFixAttempts = rows.getInt("qa_fix_attempts"),
          testedTreeIdentity = rows.getString("tested_tree_identity"),
          recordedAt = rows.getString("recorded_at"),
          findings = decodedFindings,
          reportText = storedReportText,
          failureKind = FeatureTaskQaFailureKind.fromWire(rows.getString(QA_FAILURE_KIND_COLUMN)),
        )
      }
    }

  private fun encodeFindings(findings: List<FeatureTaskQaFinding>): String =
    JsonCodec.valueToJsonString(
      findings.map { finding ->
        mapOf(
          QaOutcomePayloadKeys.ID to finding.id,
          QaOutcomePayloadKeys.OBSERVED to finding.title,
          SqliteFeatureTaskQaFindingKeys.EVIDENCE_PATHS to finding.evidencePaths,
          SqliteFeatureTaskQaFindingKeys.RECEIPT_PATH to finding.reportPath,
          QaOutcomePayloadKeys.STATUS to finding.status.wireValue,
        )
      },
    )

  private fun decodeFindings(json: String): List<FeatureTaskQaFinding> {
    val entries =
      JsonCodec.anyToStringAnyMapList(JsonCodec.parseValue(json))
        ?: invalidStoredFindings("Stored QA findings summary is not a list of objects.")
    return entries.map { entry ->
      val status = entry[QaOutcomePayloadKeys.STATUS] as? String
      val observed = (entry[QaOutcomePayloadKeys.OBSERVED] as? String).orEmpty()
      FeatureTaskQaFinding(
        id =
          entry[QaOutcomePayloadKeys.ID] as? String
            ?: invalidStoredFindings("Stored QA finding has no id."),
        title = observed,
        evidencePaths = JsonCodec.anyToStringList(entry[SqliteFeatureTaskQaFindingKeys.EVIDENCE_PATHS]).orEmpty(),
        reportPath = entry[SqliteFeatureTaskQaFindingKeys.RECEIPT_PATH] as? String,
        status =
          FeatureTaskQaFindingStatus.fromWire(status)
            ?: invalidStoredFindings("Stored QA finding status '$status' is not known."),
      )
    }
  }

  private fun invalidStoredFindings(reason: String): Nothing = throw invalidWorkflowStateSchemaError(reason)
}

internal object SqliteFeatureTaskQaFindingKeys {
  const val EVIDENCE_PATHS: String = "evidence_paths"
  const val RECEIPT_PATH: String = "receipt_path"
}
