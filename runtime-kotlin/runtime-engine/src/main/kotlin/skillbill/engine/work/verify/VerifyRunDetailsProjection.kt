package skillbill.engine.work.verify

import skillbill.contracts.qa.QaOutcomePayloadKeys
import skillbill.engine.featuretask.qa.report.QaReportParser
import skillbill.engine.featuretask.qa.report.QaRunDetailsMapping
import skillbill.engine.operation.verify.VerifyCriteria
import skillbill.engine.operation.verify.VerifyIntake
import skillbill.engine.operation.verify.VerifyWorkflow
import skillbill.engine.work.model.IdeStatusCandidate
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.UnitOfWork
import skillbill.ports.idestatus.model.VerifyCompletenessAudit
import skillbill.ports.idestatus.model.VerifyGradeStatus
import skillbill.ports.idestatus.model.VerifyReviewSection
import skillbill.ports.idestatus.model.VerifyRunDetails
import skillbill.ports.idestatus.model.VerifyRunStep
import skillbill.ports.idestatus.model.VerifyRunVerdict
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.taskruntime.model.qa.QaRunDetails

internal object VerifyRunDetailsProjection {
  fun project(
    candidate: IdeStatusCandidate,
    unitOfWork: UnitOfWork,
  ): VerifyRunDetails? {
    if (candidate.workflowFamily != IdeStatusWorkflowFamily.FEATURE_VERIFY) return null
    val snapshot = unitOfWork.workflowStates.get(WorkflowFamily.VERIFY, candidate.workflowId) ?: return null
    val artifacts = snapshot.artifacts
    val inputContext = artifacts[VerifyWorkflow.INPUT_CONTEXT]

    fun input(field: String): String? = VerifyWorkflow.string(inputContext, field)
    val criteriaSummary = VerifyCriteria.fromArtifact(artifacts[VerifyWorkflow.CRITERIA_SUMMARY])
    val (criteria, criteriaRaw) = VerifyCriteriaParser.criteria(criteriaSummary.acceptanceCriteria)
    val qaArtifact = VerifyWorkflow.qaArtifact(artifacts)
    val qaVerdict = VerifyWorkflow.string(qaArtifact, VerifyWorkflow.VERDICT_FIELD)
    val qa = qaDetails(VerifyWorkflow.string(qaArtifact, QaOutcomePayloadKeys.REPORT_TEXT), qaVerdict)
    return VerifyRunDetails(
      workflowId = snapshot.workflowId,
      issueKey = input(VerifyWorkflow.ISSUE_KEY),
      workflowStatus = snapshot.workflowStatus.wireValue,
      currentStepId = snapshot.currentStepId,
      startedAt = snapshot.startedAt,
      finishedAt = snapshot.finishedAt,
      intake = intake(inputContext),
      target = input(VerifyWorkflow.TARGET),
      repoRoot = input(VerifyWorkflow.REPO_ROOT),
      baseRevision = input(VerifyWorkflow.BASE_REVISION),
      headRevision = input(VerifyWorkflow.HEAD_REVISION),
      reviewMode = input(VerifyWorkflow.REVIEW_MODE),
      qaSelection = input(VerifyWorkflow.QA_SELECTION),
      specPath = input(VerifyWorkflow.SPEC_PATH),
      steps = snapshot.steps.map { VerifyRunStep(it.stepId, it.status.wireValue, it.attemptCount) },
      criteria = criteria,
      criteriaRaw = criteriaRaw,
      nonGoals = VerifyCriteriaParser.bullets(criteriaSummary.nonGoals),
      technicalConstraints = VerifyCriteriaParser.bullets(criteriaSummary.technicalConstraints),
      rolloutExpectation = criteriaSummary.rolloutExpectation.trim().takeIf(String::isNotEmpty),
      completenessAudit =
        VerifyCriteriaParser.completeness(artifacts[VerifyWorkflow.COMPLETENESS_AUDIT_RECEIPT])
          ?.withResolutions(snapshot.workflowId, unitOfWork),
      reviewFindings =
        VerifyReviewParser.parse(artifacts[VerifyWorkflow.CODE_REVIEW_RECEIPT])
          ?.withFindingStatuses(snapshot.sessionId.ifBlank { snapshot.workflowId }, unitOfWork),
      flagAudit = VerifyFlagAuditParser.parse(artifacts[VerifyWorkflow.FEATURE_FLAG_AUDIT_RECEIPT]),
      unitTestValue = VerifyUnitTestValueParser.parse(artifacts[VerifyWorkflow.UNIT_TEST_VALUE_RECEIPT]),
      changedFiles = VerifyWorkflow.strings(artifacts[VerifyWorkflow.DIFF_PROJECTION], VerifyWorkflow.CHANGED_FILES),
      verdict = verdict(artifacts[VerifyWorkflow.VERDICT_RESULT]),
      qa = qa,
      qaVerdict = qaVerdict,
      qaOutcome = VerifyWorkflow.string(artifacts[VerifyWorkflow.QA_OUTCOME], QaOutcomePayloadKeys.OUTCOME),
      qaReportText = VerifyWorkflow.string(qaArtifact, QaOutcomePayloadKeys.REPORT_TEXT),
      qaFindingLocations =
        qa
          ?.findings
          ?.associate { it.id to VerifyReceiptLocations.of(it.observed, it.steps) }
          ?.filterValues(List<String>::isNotEmpty)
          .orEmpty(),
      supersededBy = VerifyWorkflow.string(artifacts[VerifyWorkflow.SESSION_NOTES], VerifyWorkflow.SUPERSEDED_BY),
      staleReason = unitOfWork.lifecycleTelemetry.featureVerifyStaleReason(snapshot.sessionId),
    )
  }

  internal fun intake(inputContext: Any?): String? =
    VerifyIntake.stored(inputContext)?.let { stored ->
      when (stored) {
        is VerifyIntake.SpecFile -> "spec:${stored.value}"
        is VerifyIntake.Text -> stored.value
      }
    }

  private fun VerifyCompletenessAudit.withResolutions(
    workflowId: String,
    unitOfWork: UnitOfWork,
  ): VerifyCompletenessAudit {
    val resolutions = unitOfWork.verifyCriterionResolutions.resolutions(workflowId).associateBy { it.criterionNumber }
    if (resolutions.isEmpty()) return this
    return copy(
      grades =
        grades.map { grade ->
          grade.copy(resolution = resolutions[grade.number]?.takeIf { grade.status != VerifyGradeStatus.PASS })
        },
    )
  }

  private fun VerifyReviewSection.withFindingStatuses(
    reviewSessionId: String,
    unitOfWork: UnitOfWork,
  ): VerifyReviewSection {
    val reviewRunId = unitOfWork.reviews.latestSessionReviewRunId(reviewSessionId) ?: return this
    val statuses = unitOfWork.reviews.fetchFindingStatuses(reviewRunId)
    return copy(reviewRunId = reviewRunId, items = items.map { it.copy(status = statuses[it.ref]) })
  }

  private fun qaDetails(
    reportText: String?,
    qaVerdict: String?,
  ): QaRunDetails? {
    if (reportText.isNullOrBlank() || qaVerdict == VerifyWorkflow.SKIPPED) return null
    return QaRunDetailsMapping.verify(QaReportParser.parse(reportText, evidencePrefix = ""))
  }

  private fun verdict(result: Any?): VerifyRunVerdict? =
    result?.let {
      VerifyRunVerdict(
        VerifyWorkflow.string(it, VerifyWorkflow.VERDICT_FIELD),
        VerifyWorkflow.strings(it, VerifyWorkflow.FINDINGS),
        VerifyWorkflow.string(it, VerifyWorkflow.SUMMARY)?.takeIf(String::isNotBlank),
      )
    }
}
