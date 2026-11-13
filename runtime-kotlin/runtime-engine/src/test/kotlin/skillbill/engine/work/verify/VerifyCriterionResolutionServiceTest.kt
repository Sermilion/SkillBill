package skillbill.engine.work.verify

import skillbill.engine.operation.verify.VerifyWorkflow
import skillbill.engine.work.model.IdeStatusCandidate
import skillbill.engine.work.model.IdeStatusSelectionTier
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.idestatus.model.VerifyCriterionResolution
import skillbill.ports.idestatus.model.VerifyRunDetails
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.review.model.FeedbackRequest
import skillbill.review.model.FeedbackTelemetryOptions
import skillbill.review.model.ImportedFinding
import skillbill.review.model.ImportedReview
import skillbill.review.model.ReviewFindingStatus
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class VerifyCriterionResolutionServiceTest {
  private val home = Files.createTempDirectory("verify-criterion-resolution")
  private val database: SQLiteDatabaseSessionFactory =
    sqliteDatabaseSessionFactory(home, home.resolve("metrics.db").toString(), emptyMap())
  private val service = VerifyCriterionResolutionService(database, Clock.fixed(RECORDED_AT, ZoneOffset.UTC))

  @Test
  fun `a resolved criterion and a fixed finding show over the finished run's frozen grades`() {
    saveFinishedVerifyRun()
    importReviewWithFixedFinding()

    assertIs<VerifyCriterionResolveResult.Recorded>(service.resolve(WORKFLOW, 2, " Device run passed. "))
    val details = details()

    val grades = details.completenessAudit?.grades.orEmpty().associateBy { it.number }
    assertNull(grades.getValue(1).resolution)
    assertEquals(VerifyCriterionResolution(2, "Device run passed.", RECORDED_AT), grades.getValue(2).resolution)
    assertEquals(REVIEW_RUN, details.reviewFindings?.reviewRunId)
    assertEquals(ReviewFindingStatus.FIXED, details.reviewFindings?.items?.single()?.status)
  }

  @Test
  fun `only a criterion the run graded below PASS can be resolved`() {
    saveFinishedVerifyRun()

    assertEquals(VerifyCriterionResolveResult.AlreadyMet, service.resolve(WORKFLOW, 1, "evidence"))
    assertEquals(VerifyCriterionResolveResult.UnknownCriterion(listOf(1, 2)), service.resolve(WORKFLOW, 9, "evidence"))
    assertEquals(VerifyCriterionResolveResult.WorkflowNotFound, service.resolve("wfv-missing", 2, "evidence"))
    assertNull(details().completenessAudit?.grades?.firstNotNullOfOrNull { it.resolution })
  }

  private fun saveFinishedVerifyRun() {
    val artifacts =
      mapOf(
        VerifyWorkflow.COMPLETENESS_AUDIT_RECEIPT to
          VerifyWorkflow.receipt("had_gaps", listOf("[PASS] #1: Writes keep edits.", "[FAIL] #2: Device run.")),
        VerifyWorkflow.CODE_REVIEW_RECEIPT to
          VerifyWorkflow.receipt("approved", listOf("- [F-001] Minor | Medium | A.kt:5 | Hand-kept list.")),
      )
    database.transaction { unit ->
      unit.workflowStates.saveOrdinary(
        WorkflowFamily.VERIFY,
        WorkflowStateSnapshot(
          workflowId = WORKFLOW,
          sessionId = SESSION,
          workflowName = "bill-feature-verify",
          contractVersion = "0.1",
          workflowStatus = WorkflowStatus.COMPLETED,
          currentStepId = VerifyWorkflow.FINISH,
          steps = emptyList(),
          artifacts = DurableWorkflowArtifacts.fromMap(artifacts),
          startedAt = RECORDED_AT,
          updatedAt = RECORDED_AT,
          finishedAt = RECORDED_AT,
        ),
      )
    }
  }

  private fun importReviewWithFixedFinding() {
    database.transaction { unit ->
      unit.reviews.saveImportedReview(
        ImportedReview(
          reviewRunId = REVIEW_RUN,
          reviewSessionId = SESSION,
          rawText = "- [F-001] Minor | Medium | A.kt:5 | Hand-kept list.",
          routedSkill = null,
          detectedScope = null,
          detectedStack = null,
          executionMode = null,
          specialistReviews = emptyList(),
          findings =
            listOf(ImportedFinding("F-001", "Minor", "Medium", "A.kt:5", "Hand-kept list.", "Hand-kept list.")),
        ),
        sourcePath = null,
      )
      unit.reviews.recordFeedback(
        FeedbackRequest(REVIEW_RUN, listOf("F-001"), "fix_applied", "test added"),
        FeedbackTelemetryOptions(enabled = false),
      )
    }
  }

  private fun details(): VerifyRunDetails =
    database.read { unit ->
      VerifyRunDetailsProjection.project(
        IdeStatusCandidate(
          workflowId = WORKFLOW,
          workflowFamily = IdeStatusWorkflowFamily.FEATURE_VERIFY,
          issueKey = null,
          currentState = WorkflowStatus.COMPLETED.wireValue,
          lifecycleState = IdeStatusLifecycleState.ACTIVE,
          selectionTier = IdeStatusSelectionTier.ACTIVE,
          updatedAt = RECORDED_AT,
          startedAt = RECORDED_AT,
        ),
        unit,
      )
    } ?: error("verify run details were not projected")

  private companion object {
    const val WORKFLOW = "wfv-20261113-131906-test"
    const val SESSION = "fvr-20261113-131950-test"
    const val REVIEW_RUN = "rvw-20261113-132544-test"
    val RECORDED_AT: Instant = Instant.parse("2026-11-13T15:00:00Z")
  }
}
