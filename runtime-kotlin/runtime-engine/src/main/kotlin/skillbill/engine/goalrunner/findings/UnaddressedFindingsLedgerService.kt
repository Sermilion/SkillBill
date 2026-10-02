package skillbill.engine.goalrunner.findings

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.goalrunner.telemetry.GoalRunnerBestEffortEmission
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.GovernedReviewFailureCode
import skillbill.goalrunner.model.UNADDRESSED_FINDING_CATEGORIES
import skillbill.goalrunner.model.UNADDRESSED_FINDING_SEVERITIES
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.model.UnaddressedFindingsLedger
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.taskruntime.model.persistence.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition

@Inject
class UnaddressedFindingsLedgerService(
  private val database: DatabaseSessionFactory,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun ledger(issueKey: String): UnaddressedFindingsLedger? =
    database.read { unitOfWork ->
      if (!unitOfWork.unaddressedFindings.issueExists(issueKey)) {
        null
      } else {
        val findings = unitOfWork.unaddressedFindings.fetchLedger(issueKey)
        findings.forEach { finding ->
          if (!isValidFinding(issueKey, finding)) {
            throw SkillBillRuntimeException(
              GovernedReviewFailureCode.INVALID_LEDGER_SCHEMA,
              "Malformed unaddressed-findings ledger row for issue '$issueKey'.",
            )
          }
        }
        UnaddressedFindingsLedger(issueKey, findings)
      }
    }

  fun verificationDispositions(issueKey: String): List<FeatureTaskRuntimeFindingVerificationDisposition>? =
    database.read { unitOfWork ->
      if (!unitOfWork.unaddressedFindings.issueExists(issueKey)) {
        null
      } else {
        verificationDispositionsOf(unitOfWork, issueKey)
      }
    }

  fun repairLedgersByWorkflow(issueKey: String): Map<String, FeatureTaskRuntimeRepairLedger>? =
    database.read { unitOfWork ->
      if (!unitOfWork.unaddressedFindings.issueExists(issueKey)) {
        null
      } else {
        repairLedgersOf(unitOfWork, issueKey)
      }
    }

  private fun verificationDispositionsOf(
    unitOfWork: UnitOfWork,
    issueKey: String,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition> =
    unitOfWork.unaddressedFindings.workflowIdsForIssue(issueKey).flatMap { workflowId ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@flatMap emptyList()
      val artifacts = record.artifacts
      val artifactFamily =
        when {
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS.value(artifacts) !=
            null ->
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT.value(artifacts) !=
            null ->
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT
          else -> return@flatMap emptyList()
        }
      val raw = artifactFamily.value(artifacts) ?: return@flatMap emptyList()
      runCatching {
        FeatureTaskRuntimeFindingVerificationDisposition.parseList(
          raw,
          artifactFamily.label(),
        )
      }.getOrElse { error ->
        val message =
          "Malformed finding verification disposition artifact for issue '$issueKey' workflow '$workflowId'."
        RuntimeDiagnosticsBestEffortWarning.record(diagnostics, message, error)
        throw SkillBillRuntimeException(GovernedReviewFailureCode.INVALID_LEDGER_SCHEMA, message)
      }
    }

  private fun repairLedgersOf(
    unitOfWork: UnitOfWork,
    issueKey: String,
  ): Map<String, FeatureTaskRuntimeRepairLedger> =
    unitOfWork.unaddressedFindings.workflowIdsForIssue(issueKey).mapNotNull { workflowId ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@mapNotNull null
      val repairLedger =
        GoalRunnerBestEffortEmission.runCancellable {
          GoalSubtaskReviewArtifactDecoder.decodeReviewStateOnly(record.artifacts)?.repairLedger
        }.getOrElse { error ->
          GoalRunnerBestEffortEmission.rethrowIfCancellation(error)
          val message =
            "Malformed goal subtask review state for issue '$issueKey' workflow '$workflowId'."
          RuntimeDiagnosticsBestEffortWarning.record(diagnostics, message, error)
          throw SkillBillRuntimeException(GovernedReviewFailureCode.INVALID_LEDGER_SCHEMA, message, error)
        } ?: return@mapNotNull null
      repairLedger
        .takeUnless(FeatureTaskRuntimeRepairLedger::isEmpty)
        ?.let { workflowId to it }
    }.toMap()

  private fun isValidFinding(
    issueKey: String,
    finding: UnaddressedFinding,
  ): Boolean =
    finding.issueKey == issueKey &&
      finding.workflowId.isNotBlank() &&
      finding.subtaskId > 0 &&
      finding.reviewPassNumber > 0 &&
      finding.findingOrdinal > 0 &&
      finding.location.isNotBlank() &&
      finding.summary.isNotBlank() &&
      finding.severity in UNADDRESSED_FINDING_SEVERITIES &&
      finding.issueCategory in UNADDRESSED_FINDING_CATEGORIES
}
