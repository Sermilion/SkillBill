package dev.skillbill.tray.core.ui

import dev.skillbill.tray.core.domain.model.ExecutionScope
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.QaVerdict
import dev.skillbill.tray.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals

class OutcomeLabelsTest {
  private fun qa(verdict: QaVerdict?) = workItem("qa-run", Lifecycle.TERMINAL, family = null, issueKey = null)
    .copy(scope = ExecutionScope.STANDALONE_PHASE, phaseId = "qa", qaVerdict = verdict)

  @Test
  fun `finished runs read as their real outcome, never as a lifecycle name`() {
    assertEquals(
      listOf("Skipped", "Passed", "Findings", "Incomplete", "Complete", "Complete", "Cancelled", "Failed"),
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
}
