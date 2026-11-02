package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.DatabaseFileIdentity
import dev.skillbill.desktop.core.domain.model.ExecutionInstance
import dev.skillbill.desktop.core.domain.model.ReviewFindingStatus
import dev.skillbill.desktop.core.domain.model.ReviewReport
import dev.skillbill.desktop.core.domain.model.ReviewReportFinding
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ExecutionSnapshotTest {
  @Test
  fun `standalone review context includes only its own findings and scoped feedback actions`() {
    val base = workItem("review")
    val item = base.copy(standaloneExecution = true,
      control = base.control.copy(phaseId = "review", invocationId = "original",
        databaseFile = DatabaseFileIdentity("/selected.db", "file")))
    val report = ReviewReport("changes_requested", null,
      listOf(ReviewReportFinding("F-007", "Major", null, null, "src/A.kt:9", "Broken boundary", ReviewFindingStatus.Fixed)))
    val run = ExecutionInstance("original", "execution", "terminal", epoch, epoch, epoch, null, null, null, null, null, report)
    val other = run.copy(invocationId = "another", reviewReport = report.copy(findings = listOf(report.findings.single().copy(ref = "F-999"))))
    val entries = snapshot(item, emptyDetail.copy(executionHistory = listOf(other, run))).entries.associate { it.key to it.value }
    assertTrue(entries.getValue("detail.review.finding.F-007").endsWith("(Fixed)"))
    assertFalse("detail.review.finding.F-999" in entries)
    val verify = entries.getValue("detail.review.action.verify")
    assertTrue(verify.contains("--invocation-id 'original'"))
    assertTrue(verify.contains("Do not edit source files"))
    assertTrue(verify.endsWith("any checks you could not run."))
    assertTrue(entries.getValue("detail.review.action.fix").contains("Fix the open findings"))
  }

  @Test
  fun `the diff reports changed, added and removed keys and never the per-read timestamps`() {
    val item = workItem("SKILL-1")
    val later = Instant.fromEpochMilliseconds(60_000)
    val previous = snapshot(item.copy(activeDurationMs = 1), emptyDetail.copy(currentPhase = "plan", workflowId = "wf-1"))
    val current = snapshot(
      item.copy(activeDurationMs = 2, activeDurationAsOf = later),
      emptyDetail.copy(currentPhase = "implement", invocationId = "inv-1", observedAt = later, updatedAt = later),
    )

    val diff = diff(previous, current)

    assertEquals(listOf(ChangedEntry("detail.currentPhase", "plan", "implement")), diff.changed)
    assertEquals(listOf(SnapshotEntry("detail.invocationId", "inv-1")), diff.added)
    assertEquals(listOf("detail.workflowId"), diff.removed)
    listOf(previous.render(), current.render(), diff.render()).forEach { text ->
      listOf("observedAt", "updatedAt", "activeDuration", later.toString()).forEach { assertFalse(it in text, "$it in $text") }
    }
  }
}
