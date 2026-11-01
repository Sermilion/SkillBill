package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.domain.model.ReviewFindingStatus
import dev.skillbill.desktop.core.domain.model.ReviewReportFinding
import skillbill.goalrunner.model.GoalRunnerReviewFinding
import skillbill.goalrunner.model.GoalRunnerReviewPassResult
import skillbill.goalrunner.model.GoalRunnerSubtaskPhaseResults
import skillbill.review.model.ReviewFindingStatus as RuntimeReviewFindingStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeDetailMapperTest {
  @Test
  fun `phase results keep the degraded source, the lanes and the finding fields`() {
    val finding = GoalRunnerReviewFinding("F-1", "major", "high", "lane-a", "src/A.kt:3", "text", RuntimeReviewFindingStatus.FIXED)
    val result = GoalRunnerSubtaskPhaseResults(
      subtaskId = 3,
      reviewPasses = listOf(GoalRunnerReviewPassResult(1, "approved", 0, listOf(finding), listOf("lane-a"))),
      degradedReadSource = "subtask_review_findings",
    ).toDomain()

    assertEquals("subtask_review_findings", result.degradedReadSource)
    val pass = result.reviewPasses.single()
    assertEquals(listOf("lane-a"), pass.lanes)
    assertEquals(ReviewReportFinding("F-1", "major", "high", "lane-a", "src/A.kt:3", "text", ReviewFindingStatus.Fixed), pass.findings.single())
  }
}
