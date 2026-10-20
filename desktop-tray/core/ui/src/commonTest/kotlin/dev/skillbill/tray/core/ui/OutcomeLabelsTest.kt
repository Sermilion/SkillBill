package dev.skillbill.tray.core.ui

import dev.skillbill.tray.core.domain.model.ExecutionScope
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.QaVerdict
import dev.skillbill.tray.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class OutcomeLabelsTest {
  private fun qa(verdict: QaVerdict?) = workItem("qa-run", Lifecycle.TERMINAL, family = null, issueKey = null)
    .copy(scope = ExecutionScope.STANDALONE_PHASE, phaseId = "qa", qaVerdict = verdict)

  @Test
  fun `finished runs read as their real outcome, never as a lifecycle name`() {
    assertEquals(
      listOf("Skipped", "Passed", "Findings", "Incomplete", "Incomplete", "Complete", "Cancelled", "Failed"),
      listOf(
        qa(QaVerdict.SKIPPED).outcomeLabel(),
        qa(QaVerdict.PASSED).outcomeLabel(),
        qa(QaVerdict.FINDINGS).outcomeLabel(),
        qa(QaVerdict.INCOMPLETE).outcomeLabel(),
        qa(QaVerdict.ATTEMPTED).outcomeLabel(),
        workItem("goal", Lifecycle.TERMINAL).outcomeLabel(),
        workItem("goal", Lifecycle.CANCELED).outcomeLabel(),
        workItem("goal", Lifecycle.FAILED).outcomeLabel(),
      ),
    )
  }

  @Test
  fun `a stalled active run reads as Stalled with how long it has gone without progress`() {
    val now = Instant.parse("2026-10-20T11:35:00Z")
    val stalled = workItem("goal").copy(stalledSince = now - 106.minutes)
    val live = workItem("goal")

    assertEquals(listOf("Stalled", "Active"), listOf(stalled.outcomeLabel(), live.outcomeLabel()))
    assertEquals("stalled · no progress for 1h 46m", livenessText("live", stalled, now))
    assertEquals("live", livenessText("live", live, now))
  }
}
