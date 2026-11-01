package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.domain.model.AuditOutcome
import dev.skillbill.desktop.core.domain.model.ReviewFindingStatus
import dev.skillbill.desktop.core.domain.model.ReviewReportFinding
import skillbill.goalrunner.model.GoalRunnerSubtaskAuditAttempt
import skillbill.goalrunner.model.GoalRunnerSubtaskAuditCriterion
import skillbill.goalrunner.model.GoalRunnerSubtaskAuditOutcome
import skillbill.goalrunner.model.GoalRunnerSubtaskAuditResult
import skillbill.goalrunner.model.GoalRunnerReviewFinding
import skillbill.goalrunner.model.GoalRunnerReviewPassResult
import skillbill.goalrunner.model.GoalRunnerRunningReviewLane
import skillbill.goalrunner.model.GoalRunnerRunningReviewPass
import skillbill.goalrunner.model.GoalRunnerSubtaskPhaseResults
import skillbill.goalrunner.model.GoalRunnerSubtaskExecution
import java.time.Instant
import skillbill.review.model.ReviewFindingStatus as RuntimeReviewFindingStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeDetailMapperTest {
  @Test
  fun `phase results keep the degraded source, the lanes and the finding fields`() {
    val finding =
      GoalRunnerReviewFinding("F-1", "major", "high", "lane-a", "src/A.kt:3", "text", RuntimeReviewFindingStatus.FIXED)
    val result = GoalRunnerSubtaskPhaseResults(
      subtaskId = 3,
      reviewPasses = listOf(GoalRunnerReviewPassResult(1, "approved", 0, listOf(finding), listOf("lane-a"))),
      degradedReadSource = "subtask_review_findings",
    ).toDomain()

    assertEquals("subtask_review_findings", result.degradedReadSource)
    val pass = result.reviewPasses.single()
    assertEquals(listOf("lane-a"), pass.lanes)
    assertEquals(
      ReviewReportFinding("F-1", "major", "high", "lane-a", "src/A.kt:3", "text", ReviewFindingStatus.Fixed),
      pass.findings.single(),
    )
  }

  @Test
  fun `phase results map the running pass and lane completion`() {
    val result = GoalRunnerSubtaskPhaseResults(
      subtaskId = 3,
      runningReviewPass = GoalRunnerRunningReviewPass(
        2,
        listOf(GoalRunnerRunningReviewLane("lane-a", true), GoalRunnerRunningReviewLane("lane-b", false)),
      ),
    ).toDomain()

    assertEquals(2, result.runningReviewPass?.passNumber)
    assertEquals(
      listOf("lane-a" to true, "lane-b" to false),
      result.runningReviewPass?.lanes?.map { it.name to it.finished },
    )
  }

  @Test
  fun `phase results map audit counts criteria notes and attempts`() {
    val result = GoalRunnerSubtaskPhaseResults(
      subtaskId = 3,
      auditResult = GoalRunnerSubtaskAuditResult(
        outcome = GoalRunnerSubtaskAuditOutcome.PASSED,
        metCount = 2,
        totalCount = 2,
        criteria = listOf(GoalRunnerSubtaskAuditCriterion("AC-001", "Retains state", true, 2)),
        attempts = listOf(GoalRunnerSubtaskAuditAttempt("audit:1", 1, false, 1, "audit_plan_fix")),
        attemptTotal = 2,
      ),
    ).toDomain().auditResult

    assertEquals(AuditOutcome.Passed, result?.outcome)
    assertEquals(2, result?.metCount)
    assertEquals(2, result?.totalCount)
    assertEquals("Retains state", result?.criteria?.single()?.text)
    assertEquals(2, result?.criteria?.single()?.fixedInAttempt)
    assertEquals("audit_plan_fix", result?.attempts?.single()?.nextPhaseId)
    assertEquals(2, result?.attemptTotal)
  }

  @Test
  fun `phase results map subtask execution timestamps and counts`() {
    val result = GoalRunnerSubtaskPhaseResults(
      subtaskId = 3,
      execution = GoalRunnerSubtaskExecution(
        workflowId = "wf-sub-3",
        agentId = "claude",
        model = "claude-opus-5-5",
        effort = "high",
        startedAt = Instant.parse("2026-10-01T09:00:00Z"),
        updatedAt = Instant.parse("2026-10-01T11:00:00Z"),
        phaseAttemptCounts = mapOf("implement" to 2),
      ),
    ).toDomain().execution

    assertEquals("wf-sub-3", result?.workflowId)
    assertEquals("claude", result?.agentId)
    assertEquals("claude-opus-5-5", result?.model)
    assertEquals("high", result?.effort)
    assertEquals(kotlin.time.Instant.parse("2026-10-01T09:00:00Z"), result?.startedAt)
    assertEquals(kotlin.time.Instant.parse("2026-10-01T11:00:00Z"), result?.updatedAt)
    assertEquals(mapOf("implement" to 2), result?.phaseAttemptCounts)
  }
}
