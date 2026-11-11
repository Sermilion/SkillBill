package skillbill.infrastructure.sqlite

import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QA_PANEL_HISTORY_CONTRACT_VERSION
import skillbill.contracts.qa.QaOutcomePayloadKeys
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.QA_FAILURE_KIND_COLUMN
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.QA_REPORT_TEXT_COLUMN
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.qadetails.QA_DETAILS_COLUMN
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.qadetails.SqliteQaDetailsKeys
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.qadetails.decodeQaDetails
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.qadetails.decodeQaOccurrence
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.qadetails.encodeQaDetails
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.qadetails.encodeQaOccurrence
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.featuretask.FeatureTaskQaOutcomeRepository
import skillbill.ports.featuretask.model.FeatureTaskQaFailureKind
import skillbill.ports.featuretask.model.FeatureTaskQaFinding
import skillbill.ports.featuretask.model.FeatureTaskQaFindingStatus
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.workflow.taskruntime.model.qa.QaRunOccurrence
import skillbill.workflow.taskruntime.model.qa.QaRunScreenshot
import skillbill.workflow.taskruntime.model.qa.QaRunStage
import java.sql.Connection

internal class SqliteFeatureTaskQaOutcomeStore(
  private val connection: Connection,
) : FeatureTaskQaOutcomeRepository {
  override fun upsert(outcome: FeatureTaskQaOutcome) {
    connection.prepareStatement(
      """
      INSERT INTO feature_task_qa_outcomes (
        workflow_id, outcome, reason, qa_fix_attempts, tested_tree_identity, recorded_at, findings_summary,
        $QA_REPORT_TEXT_COLUMN, $QA_FAILURE_KIND_COLUMN, $QA_DETAILS_COLUMN
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(workflow_id) DO UPDATE SET
        outcome = excluded.outcome,
        reason = excluded.reason,
        qa_fix_attempts = excluded.qa_fix_attempts,
        tested_tree_identity = excluded.tested_tree_identity,
        recorded_at = excluded.recorded_at,
        findings_summary = excluded.findings_summary,
        $QA_REPORT_TEXT_COLUMN = excluded.$QA_REPORT_TEXT_COLUMN,
        $QA_FAILURE_KIND_COLUMN = excluded.$QA_FAILURE_KIND_COLUMN,
        $QA_DETAILS_COLUMN = excluded.$QA_DETAILS_COLUMN
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
        outcome.details?.copy(occurrences = emptyList())?.let(::encodeQaDetails),
      )
      statement.executeUpdate()
    }
    outcome.details?.occurrences.orEmpty().forEach { occurrence ->
      upsertOccurrence(outcome.workflowId, occurrence)
    }
  }

  override fun find(workflowId: String): FeatureTaskQaOutcome? =
    connection.prepareStatement(
      """
      SELECT workflow_id, outcome, reason, qa_fix_attempts, tested_tree_identity, recorded_at, findings_summary,
             $QA_REPORT_TEXT_COLUMN, $QA_FAILURE_KIND_COLUMN, $QA_DETAILS_COLUMN
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
          details =
            rows.getString(QA_DETAILS_COLUMN)?.let(::decodeQaDetails)?.let { details ->
              val occurrences = readOccurrences(workflowId)
              details.copy(
                occurrences = occurrences.ifEmpty { details.occurrences },
                historyAvailability = details.historyAvailability,
              )
            },
        )
      }
    }

  override fun findByWorkspaceInvocation(invocationId: String): FeatureTaskQaOutcome? {
    val workflowId =
      connection.prepareStatement(
        "SELECT workflow_id FROM feature_task_qa_panel_occurrences " +
          "WHERE json_extract(occurrence_json, '$.${SqliteQaDetailsKeys.WORKSPACE_INVOCATION_ID}') = ? LIMIT 1",
      ).use { statement ->
        statement.bindAll(invocationId)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getString("workflow_id") else null }
      } ?: return null
    return find(workflowId)
  }

  private fun readOccurrences(workflowId: String): List<QaRunOccurrence> =
    connection.prepareStatement(
      "SELECT contract_version, occurrence_json FROM feature_task_qa_panel_occurrences " +
        "WHERE workflow_id = ? ORDER BY rowid",
    ).use { statement ->
      statement.bindAll(workflowId)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            add(decodeStoredOccurrence(rows.getString("contract_version"), rows.getString("occurrence_json")))
          }
        }
      }
    }

  private fun upsertOccurrence(
    workflowId: String,
    incoming: QaRunOccurrence,
  ) {
    val existing =
      connection.prepareStatement(
        "SELECT contract_version, occurrence_json FROM feature_task_qa_panel_occurrences " +
          "WHERE workflow_id = ? AND occurrence_id = ?",
      ).use { statement ->
        statement.bindAll(workflowId, incoming.occurrenceId)
        statement.executeQuery().use { rows ->
          if (rows.next()) {
            decodeStoredOccurrence(rows.getString("contract_version"), rows.getString("occurrence_json"))
          } else {
            null
          }
        }
      }
    val occurrence = existing?.let { prior -> mergeOccurrence(prior, incoming) } ?: incoming
    connection.prepareStatement(
      "INSERT INTO feature_task_qa_panel_occurrences (workflow_id, occurrence_id, contract_version, occurrence_json) " +
        "VALUES (?, ?, ?, ?) ON CONFLICT(workflow_id, occurrence_id) " +
        "DO UPDATE SET occurrence_json = excluded.occurrence_json",
    ).use { statement ->
      statement.bindAll(
        workflowId,
        occurrence.occurrenceId,
        QA_PANEL_HISTORY_CONTRACT_VERSION,
        encodeQaOccurrence(occurrence),
      )
      statement.executeUpdate()
    }
  }

  private fun mergeOccurrence(
    prior: QaRunOccurrence,
    incoming: QaRunOccurrence,
  ): QaRunOccurrence {
    val phaseChanged = prior.kind != incoming.kind || prior.phaseAttempt != incoming.phaseAttempt
    val invocationChanged =
      prior.workspaceInvocationId != incoming.workspaceInvocationId || prior.startedAt != incoming.startedAt
    if (phaseChanged || invocationChanged) {
      throw invalidWorkflowStateSchemaError("QA occurrence '${prior.occurrenceId}' changed its immutable identity.")
    }
    if (prior.isTerminal()) return prior
    val stages = (prior.stages + incoming.stages).associateBy(QaRunStage::name).values.toList()
    val screenshots = (prior.screenshots + incoming.screenshots).distinctBy(QaRunScreenshot::path)
    return incoming.copy(
      finishedAt = incoming.finishedAt ?: prior.finishedAt,
      resultReference = incoming.resultReference ?: prior.resultReference,
      frameReference = incoming.frameReference ?: prior.frameReference,
      frameAvailability =
        if (incoming.frameReference == null && incoming.frameAvailability == "not_captured") {
          prior.frameAvailability
        } else {
          incoming.frameAvailability
        },
      screenshots = screenshots,
      stages = stages,
    )
  }

  private fun decodeStoredOccurrence(
    version: String,
    json: String,
  ): QaRunOccurrence {
    if (version != QA_PANEL_HISTORY_CONTRACT_VERSION) {
      throw invalidWorkflowStateSchemaError("Stored QA panel history contract '$version' is unsupported.")
    }
    return decodeQaOccurrence(json)
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
          SqliteFeatureTaskQaFindingKeys.OBSERVED_TEXT to finding.observed,
          SqliteFeatureTaskQaFindingKeys.EXPECTED to finding.expected,
          SqliteFeatureTaskQaFindingKeys.STEPS to finding.steps,
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
        observed = entry[SqliteFeatureTaskQaFindingKeys.OBSERVED_TEXT] as? String,
        expected = entry[SqliteFeatureTaskQaFindingKeys.EXPECTED] as? String,
        steps = entry[SqliteFeatureTaskQaFindingKeys.STEPS] as? String,
      )
    }
  }

  private fun invalidStoredFindings(reason: String): Nothing = throw invalidWorkflowStateSchemaError(reason)
}

internal object SqliteFeatureTaskQaFindingKeys {
  const val EVIDENCE_PATHS: String = "evidence_paths"
  const val RECEIPT_PATH: String = "receipt_path"
  const val OBSERVED_TEXT: String = "observed_text"
  const val EXPECTED: String = QaOutcomePayloadKeys.EXPECTED
  const val STEPS: String = QaOutcomePayloadKeys.STEPS
}
