package dev.skillbill.desktop.core.data.control

import dev.skillbill.desktop.core.data.repository.workItem
import dev.skillbill.desktop.core.domain.model.DecisionOutcome
import dev.skillbill.desktop.core.domain.model.OperatorDecision
import kotlin.test.Test
import kotlin.test.assertEquals

class DecisionPrecheckTest {
  // The repository returns the precheck's outcome before it reaches the runtime, so a blank retry never reaches the runner.
  @Test
  fun `a retry with blank instructions requires instructions`() {
    val item = workItem("wf").copy(issueKey = "SKILL-1", currentSubtaskId = "2", awaitsOperatorDecision = true)
    assertEquals(DecisionOutcome.InstructionsRequired, decisionPrecheck(item, OperatorDecision.RETRY_FIX, "  "))
  }
}
