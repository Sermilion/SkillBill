package dev.skillbill.tray.core.data.runtime

import dev.skillbill.tray.core.domain.model.GoalAssessmentFindingState
import dev.skillbill.tray.core.domain.model.GoalAssessmentState
import dev.skillbill.tray.core.domain.model.GoalAssessmentVerification
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.model.GoalRunnerAssessmentFinding
import skillbill.goalrunner.model.GoalRunnerAssessmentResult
import skillbill.goalrunner.model.GoalRunnerAssessmentState
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.idestatus.model.IdeStatusFreshness
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusStep
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant as KotlinInstant
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState as RuntimeFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification as RuntimeVerification

class RuntimeWorkMapperTest {
  @Test
  fun `current criterion reaches the desktop independently of summary prose`() {
    val snapshot = snapshot().copy(
      currentStep = IdeStatusStep("audit", "Audit"),
      currentPhaseExecution = IdeStatusCurrentPhaseExecution(
        phaseId = "audit", kind = IdeStatusCurrentPhaseExecutionKind.ATTEMPT,
        count = 2, criterionRef = "AC-005",
      ),
    )

    val execution = snapshot.toDomain("/repo", null, null).phaseExecution!!
    assertEquals("AC-005", execution.criterionRef)
    assertEquals(2, execution.count)
  }

  @Test
  fun `a mixed confirmed, refuted and unverified assessment keeps every verification and state`() {
    val assessment = GoalRunnerAssessmentResult(
      state = GoalRunnerAssessmentState.COMPLETED,
      round = 3,
      exitReason = GoalAssessmentExitReason.PERSISTENT_FINDING,
      fixRan = true,
      openFindingRefs = listOf("GA-002", "GA-006"),
      findings = listOf(
        finding("GA-002", RuntimeVerification.CONFIRMED, RuntimeFindingState.OPEN, "Reproduced twice."),
        finding("GA-004", RuntimeVerification.REFUTED, RuntimeFindingState.REFUTED, "The caller guards the null."),
        finding("GA-006", RuntimeVerification.UNVERIFIED, RuntimeFindingState.OPEN, ""),
      ),
    )

    val mapped = requireNotNull(detail(assessment).toDomain(KotlinInstant.fromEpochSeconds(0), null).goalAssessment)

    assertEquals(GoalAssessmentState.COMPLETED, mapped.state)
    assertEquals(3, mapped.round)
    assertEquals("persistent_finding", mapped.exitReason)
    assertEquals(
      listOf(
        GoalAssessmentVerification.CONFIRMED,
        GoalAssessmentVerification.REFUTED,
        GoalAssessmentVerification.UNVERIFIED,
      ),
      mapped.findings.map { it.verification },
    )
    assertEquals(
      listOf(GoalAssessmentFindingState.OPEN, GoalAssessmentFindingState.REFUTED, GoalAssessmentFindingState.OPEN),
      mapped.findings.map { it.state },
    )
    assertEquals("major", mapped.findings.first().severity)
    assertEquals("The caller guards the null.", mapped.findings[1].evidence)
  }

  @Test
  fun `a goal without an assessment record maps to no assessment and a skipped one stays skipped`() {
    assertNull(detail(null).toDomain(KotlinInstant.fromEpochSeconds(0), null).goalAssessment)

    val skipped = GoalRunnerAssessmentResult(
      state = GoalRunnerAssessmentState.SKIPPED,
      round = 0,
      exitReason = GoalAssessmentExitReason.SKIPPED_NO_CHANGES,
      fixRan = false,
      openFindingRefs = emptyList(),
      findings = emptyList(),
    )

    assertEquals(
      GoalAssessmentState.SKIPPED,
      detail(skipped).toDomain(KotlinInstant.fromEpochSeconds(0), null).goalAssessment?.state,
    )
  }

  private fun finding(ref: String, verification: RuntimeVerification, state: RuntimeFindingState, evidence: String) =
    GoalRunnerAssessmentFinding(
      ref = ref,
      severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
      path = "src/Retry.kt",
      line = 12,
      failureScenario = "The retry loop never stops.",
      verification = verification,
      state = state,
      evidence = evidence,
    )

  private fun snapshot() = IdeStatusSnapshot(
    repositoryIdentity = "repo-root-realpath-v1:/repo",
    workflowId = "workflow-1",
    lifecycleState = IdeStatusLifecycleState.ACTIVE,
    currentStep = IdeStatusStep("goal_assessment", "Goal assessment"),
    updatedAt = Instant.parse("2026-10-22T12:00:00Z"),
    freshness = IdeStatusFreshness.FRESH,
    summary = "Goal assessment is active.",
  )

  private fun detail(assessment: GoalRunnerAssessmentResult?) = WorkDetailItem(
    snapshot = snapshot(),
    recoveryWorkId = "goal-1",
    repositoryRoot = "/repo",
    stepPipeline = emptyList(),
    goalStatus = GoalRunnerStatusProjection(
      issueKey = "SKILL-442",
      completeCount = 1,
      pendingCount = 0,
      blockedCount = 0,
      currentSubtaskId = null,
      currentStep = null,
      activeAgent = null,
      goalAssessmentResult = assessment,
    ),
  )
}
