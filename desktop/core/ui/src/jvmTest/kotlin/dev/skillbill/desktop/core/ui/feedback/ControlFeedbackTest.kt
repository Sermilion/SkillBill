package dev.skillbill.desktop.core.ui.feedback

import dev.skillbill.desktop.core.domain.model.ControlOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

class ControlFeedbackTest {
  @Test
  fun `each pause and stop outcome claims only what the runtime reported`() {
    val subject = FeedbackSubject("SKILL-1", launchLabel = null, agentId = null)
    val cases = listOf(
      Triple(ControlAction.PauseAfterSubtask, ControlOutcome.IntentRecorded, "Pause recorded. The goal pauses at its next checkpoint."),
      Triple(ControlAction.SpecPause, ControlOutcome.IntentRecorded, "Pause recorded. The goal stops after its current subtask."),
      Triple(ControlAction.PauseNow, ControlOutcome.ExitConfirmed, "Stop confirmed. The goal runner has exited."),
      Triple(
        ControlAction.PauseNow,
        ControlOutcome.TerminationAttempted,
        "Stop signal sent. The goal runner's exit was not confirmed; refresh and check it before retrying.",
      ),
      Triple(ControlAction.PauseNow, ControlOutcome.NoProcessNeeded, "Request accepted. No running goal process needed stopping."),
      Triple(ControlAction.Cancel, ControlOutcome.TerminationAttempted, "The runner has not stopped yet. Refresh and retry cancellation."),
    )
    cases.forEach { (action, outcome, expected) ->
      assertEquals(expected, controlFeedback(action, outcome, subject).text, "$action + $outcome")
    }
  }
}
