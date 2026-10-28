package dev.skillbill.desktop.core.data.control

import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import skillbill.engine.work.control.model.WorkControlResult
import kotlin.test.Test
import kotlin.test.assertEquals

class ControlResultMappingTest {
  // The copy table shows a stop as done only when the runtime confirmed the exit.
  @Test
  fun `each result maps to the outcome that claims no more than the runtime reported`() {
    val cases = listOf(
      Triple(WorkControlResult.ExitConfirmed, ControlKind.PAUSE_NOW, ControlOutcome.ExitConfirmed),
      Triple(WorkControlResult.TerminationAttempted, ControlKind.PAUSE_NOW, ControlOutcome.TerminationAttempted),
      Triple(WorkControlResult.Accepted, ControlKind.PAUSE_NOW, ControlOutcome.NoProcessNeeded),
      Triple(WorkControlResult.IntentRecorded, ControlKind.PAUSE_AFTER_SUBTASK, ControlOutcome.IntentRecorded),
      Triple(WorkControlResult.Accepted, ControlKind.LAUNCH, ControlOutcome.RequestAccepted),
    )
    val diagnostics = RecordingDiagnostics()
    cases.forEach { (result, kind, expected) -> assertEquals(expected, result.toControlOutcome(kind, diagnostics), "$result as $kind") }
  }
}
