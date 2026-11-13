package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.AuditCriterion
import dev.skillbill.desktop.core.domain.model.AuditOutcome
import dev.skillbill.desktop.core.domain.model.ReviewPass
import dev.skillbill.desktop.core.domain.model.SubtaskAuditResult
import dev.skillbill.desktop.core.domain.model.RunningReviewLane
import dev.skillbill.desktop.core.domain.model.RunningReviewPass
import dev.skillbill.desktop.core.domain.model.SubtaskPhaseResults
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtaskResultsStateTest {
  private val empty = SubtaskPhaseResults(1, emptyList(), null, null)
  private val pass = ReviewPass(1, "approved", 0, emptyList(), emptyList())

  @Test
  fun `a degraded subtask read is unreadable whatever the kind`() {
    assertEquals(
      SubtaskResultsState.Unreadable("subtask_phase_results"),
      subtaskResultsState(StatusKind.Pending, empty.copy(degradedReadSource = "subtask_phase_results"), null),
    )
  }

  @Test
  fun `a pending subtask with recorded phase results still shows them`() {
    val audit = SubtaskAuditResult(AuditOutcome.Passed, 1, 1, listOf(AuditCriterion("AC-001", "Criterion", true, null)), emptyList(), 1)
    assertEquals(
      SubtaskResultsState.Shown(SubtaskReviewState.Omitted),
      subtaskResultsState(StatusKind.Pending, empty.copy(auditResult = audit), null),
    )
  }

  @Test
  fun `a missing goal status is unreadable except while pending`() {
    assertEquals(SubtaskResultsState.Unreadable("goal_status"), subtaskResultsState(StatusKind.Skipped, null, "goal_status"))
    assertEquals(SubtaskResultsState.Upcoming, subtaskResultsState(StatusKind.Pending, null, "goal_status"))
  }

  @Test
  fun `a readable page shows results after an earlier goal status failure`() {
    assertEquals(
      SubtaskResultsState.Shown(SubtaskReviewState.Passes(listOf(pass))),
      subtaskResultsState(StatusKind.Completed, empty.copy(reviewPasses = listOf(pass)), "goal_status"),
    )
  }

  @Test
  fun `a running pass is review content and unreadable still takes priority`() {
    val running = RunningReviewPass(2, listOf(RunningReviewLane("lane-a", false)))
    assertEquals(
      SubtaskResultsState.Shown(SubtaskReviewState.Passes(emptyList(), running)),
      subtaskResultsState(StatusKind.Active, empty.copy(runningReviewPass = running), null),
    )
    assertEquals(
      SubtaskResultsState.Unreadable("subtask_review_lanes"),
      subtaskResultsState(
        StatusKind.Active,
        empty.copy(runningReviewPass = running, degradedReadSource = "subtask_review_lanes"),
        null,
      ),
    )
  }

  @Test
  fun `a started subtask with no entry is loading, never pending`() {
    listOf(StatusKind.Completed, StatusKind.Active, StatusKind.Blocked, StatusKind.CompletedNoChange).forEach { kind ->
      assertEquals(SubtaskResultsState.Loading, subtaskResultsState(kind, null, null), kind.name)
    }
  }

  @Test
  fun `an unpaged skipped subtask is loading, not an empty read`() {
    assertEquals(SubtaskResultsState.Loading, subtaskResultsState(StatusKind.Skipped, null, null, pagePending = true))
  }

  @Test
  fun `a skipped subtask with no entry omits review`() {
    assertEquals(
      SubtaskResultsState.Shown(SubtaskReviewState.Omitted),
      subtaskResultsState(StatusKind.Skipped, null, null),
    )
  }

  @Test
  fun `a blocked subtask with a pass shows it and a completed one with none omits review`() {
    val blocked = subtaskResultsState(StatusKind.Blocked, empty.copy(reviewPasses = listOf(pass)), null)
    assertEquals(SubtaskResultsState.Shown(SubtaskReviewState.Passes(listOf(pass))), blocked)
    assertEquals(
      SubtaskResultsState.Shown(SubtaskReviewState.Omitted),
      subtaskResultsState(StatusKind.Completed, empty, null),
    )
  }
}
