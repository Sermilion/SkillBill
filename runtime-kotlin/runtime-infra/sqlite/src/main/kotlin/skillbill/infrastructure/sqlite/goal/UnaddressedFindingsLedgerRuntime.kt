package skillbill.infrastructure.sqlite.goal

import skillbill.contracts.SharedPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.GovernedReviewFailureCode
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.review.model.ReviewFindingCitation
import java.sql.Connection

internal class UnaddressedFindingsLedgerRuntime(private val connection: Connection) {
  fun replaceLedgerForPass(
    workflowId: String,
    reviewPassNumber: Int,
    findings: List<UnaddressedFinding>,
  ) {
    findings.mapNotNull(UnaddressedFinding::reviewRunId).distinct().forEach { reviewRunId ->
      connection.prepareStatement("SELECT 1 FROM review_runs WHERE review_run_id = ? LIMIT 1").use { statement ->
        statement.bindAll(reviewRunId)
        statement.executeQuery().use { rows ->
          if (!rows.next()) {
            throw SkillBillRuntimeException(
              GovernedReviewFailureCode.REVIEW_RUN_PARENT_MISSING,
              "Cannot replace an unaddressed-findings pass for missing review run '$reviewRunId'.",
            )
          }
        }
      }
    }
    deletePassesUpTo(workflowId, reviewPassNumber)
    connection.prepareStatement(
      """
      INSERT INTO unaddressed_findings (
        issue_key, workflow_id, subtask_id, review_pass_number, finding_ordinal,
        severity, issue_category, location, summary, review_run_id, finding_id,
        claim_verdict, scope_disposition, citations,
        severity_adjustment_direction, severity_adjustment_justification,
        verification_disposition, verification_reason
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      findings.forEach { finding ->
        statement.bindAll(
          finding.issueKey,
          finding.workflowId,
          finding.subtaskId,
          finding.reviewPassNumber,
          finding.findingOrdinal,
          finding.severity,
          finding.issueCategory,
          finding.location,
          finding.summary,
          finding.reviewRunId,
          finding.findingId,
          finding.claimVerdict?.wireValue,
          finding.scopeDisposition?.wireValue,
          ReviewFindingCitation.encodeList(finding.citations),
          finding.severityAdjustment?.direction?.wireValue,
          finding.severityAdjustment?.justification,
          finding.verificationDisposition,
          finding.verificationReason,
        )
        statement.addBatch()
      }
      statement.executeBatch()
    }
  }

  fun clearWorkflowLedger(workflowId: String) {
    connection.prepareStatement("DELETE FROM unaddressed_findings WHERE workflow_id = ?").use { statement ->
      statement.bindAll(workflowId)
      statement.executeUpdate()
    }
  }

  fun fetchLedger(issueKey: String): List<UnaddressedFinding> = fetchLedgerBy("issue_key", issueKey)

  fun fetchWorkflowLedger(workflowId: String): List<UnaddressedFinding> = fetchLedgerBy("workflow_id", workflowId)

  fun workflowIdsForIssue(issueKey: String): List<String> =
    connection.prepareStatement(
      "SELECT workflow_id FROM feature_task_workflows WHERE issue_key = ? ORDER BY workflow_id",
    ).use { statement ->
      statement.bindAll(issueKey)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            rows.getString(SharedPayloadKeys.WORKFLOW_ID)?.takeIf(String::isNotBlank)?.let(::add)
          }
        }
      }
    }

  fun issueExists(issueKey: String): Boolean =
    connection.prepareStatement(
      "SELECT 1 FROM feature_task_workflows WHERE issue_key = ? LIMIT 1",
    ).use { statement ->
      statement.bindAll(issueKey)
      statement.executeQuery().use { it.next() }
    }

  private fun deletePassesUpTo(
    workflowId: String,
    reviewPassNumber: Int,
  ) {
    connection.prepareStatement(
      "DELETE FROM unaddressed_findings WHERE workflow_id = ? AND review_pass_number <= ?",
    ).use { statement ->
      statement.bindAll(workflowId, reviewPassNumber)
      statement.executeUpdate()
    }
  }

  private fun fetchLedgerBy(
    column: String,
    value: String,
  ): List<UnaddressedFinding> =
    connection.prepareStatement(
      """
      SELECT issue_key, workflow_id, subtask_id, review_pass_number, finding_ordinal,
             severity, issue_category, location, summary, review_run_id, finding_id,
             claim_verdict, scope_disposition, citations,
             severity_adjustment_direction, severity_adjustment_justification,
             verification_disposition, verification_reason
      FROM unaddressed_findings
      WHERE $column = ?
      ORDER BY subtask_id, review_pass_number, finding_ordinal
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(value)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            add(readUnaddressedFinding(rows))
          }
        }
      }
    }
}
