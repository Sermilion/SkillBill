package skillbill.infrastructure.sqlite

import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QaReceiptPayloadKeys
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.QA_CRITERIA_SUMMARY_COLUMN
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.decodeStandaloneQaReport
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.encodeStandaloneQaReport
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.featuretask.FeatureTaskQaOutcomeRepository
import skillbill.ports.featuretask.model.FeatureTaskQaFinding
import skillbill.ports.featuretask.model.FeatureTaskQaFindingStatus
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaReport
import java.sql.Connection

internal class SqliteFeatureTaskQaOutcomeStore(
  private val connection: Connection,
) : FeatureTaskQaOutcomeRepository {
  override fun upsert(outcome: FeatureTaskQaOutcome) {
    connection.prepareStatement(
      """
      INSERT INTO feature_task_qa_outcomes (
        workflow_id, outcome, reason, qa_fix_attempts, tested_tree_identity, recorded_at, findings_summary,
        criteria_summary
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(workflow_id) DO UPDATE SET
        outcome = excluded.outcome,
        reason = excluded.reason,
        qa_fix_attempts = excluded.qa_fix_attempts,
        tested_tree_identity = excluded.tested_tree_identity,
        recorded_at = excluded.recorded_at,
        findings_summary = excluded.findings_summary,
        criteria_summary = excluded.criteria_summary
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
        outcome.report?.let(::encodeStandaloneQaReport),
      )
      statement.executeUpdate()
    }
  }

  override fun find(workflowId: String): FeatureTaskQaOutcome? =
    connection.prepareStatement(
      """
      SELECT workflow_id, outcome, reason, qa_fix_attempts, tested_tree_identity, recorded_at, findings_summary,
             criteria_summary
      FROM feature_task_qa_outcomes
      WHERE workflow_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(workflowId)
      statement.executeQuery().use { rows ->
        if (!rows.next()) return@use null
        val raw = rows.getString("outcome")
        FeatureTaskQaOutcome(
          workflowId = rows.getString("workflow_id"),
          outcome =
            StandaloneQaOutcome.fromWire(raw)
              ?: throw invalidWorkflowStateSchemaError("Stored QA outcome '$raw' is not a known outcome."),
          reason = rows.getString("reason"),
          qaFixAttempts = rows.getInt("qa_fix_attempts"),
          testedTreeIdentity = rows.getString("tested_tree_identity"),
          recordedAt = rows.getString("recorded_at"),
          findings = rows.getString("findings_summary")?.let(::decodeFindings).orEmpty(),
          report = rows.getString(QA_CRITERIA_SUMMARY_COLUMN)?.let(::decodeCriteria),
        )
      }
    }

  private fun encodeFindings(findings: List<FeatureTaskQaFinding>): String =
    JsonCodec.valueToJsonString(
      findings.map { finding ->
        mapOf(
          QaReceiptPayloadKeys.ID to finding.id,
          QaReceiptPayloadKeys.OBSERVED to finding.title,
          QaReceiptPayloadKeys.AFFECTED_CRITERIA to finding.affectedCriteria,
          SqliteFeatureTaskQaFindingKeys.EVIDENCE_PATHS to finding.evidencePaths,
          SqliteFeatureTaskQaFindingKeys.RECEIPT_PATH to finding.receiptPath,
          QaReceiptPayloadKeys.STATUS to finding.status.wireValue,
        )
      },
    )

  private fun decodeFindings(json: String): List<FeatureTaskQaFinding> {
    val entries =
      JsonCodec.anyToStringAnyMapList(JsonCodec.parseValue(json))
        ?: invalidStoredFindings("Stored QA findings summary is not a list of objects.")
    return entries.map { entry ->
      val status = entry[QaReceiptPayloadKeys.STATUS] as? String
      FeatureTaskQaFinding(
        id =
          entry[QaReceiptPayloadKeys.ID] as? String
            ?: invalidStoredFindings("Stored QA finding has no id."),
        title = (entry[QaReceiptPayloadKeys.OBSERVED] as? String).orEmpty(),
        affectedCriteria = JsonCodec.anyToStringList(entry[QaReceiptPayloadKeys.AFFECTED_CRITERIA]).orEmpty(),
        evidencePaths = JsonCodec.anyToStringList(entry[SqliteFeatureTaskQaFindingKeys.EVIDENCE_PATHS]).orEmpty(),
        receiptPath = entry[SqliteFeatureTaskQaFindingKeys.RECEIPT_PATH] as? String,
        status =
          FeatureTaskQaFindingStatus.fromWire(status)
            ?: invalidStoredFindings("Stored QA finding status '$status' is not known."),
      )
    }
  }

  private fun decodeCriteria(json: String): StandaloneQaReport =
    decodeStandaloneQaReport(json) { reason -> invalidStoredFindings(reason) }

  private fun invalidStoredFindings(reason: String): Nothing = throw invalidWorkflowStateSchemaError(reason)
}

internal object SqliteFeatureTaskQaFindingKeys {
  const val EVIDENCE_PATHS: String = "evidence_paths"
  const val RECEIPT_PATH: String = "receipt_path"
}
