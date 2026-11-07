package skillbill.goalrunner.model.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoalQualityExecutionValidationTest {
  @Test
  fun `skipped QA settles only after terminal validation evidence is retained`() {
    val pending =
      GoalQualityExecutionRecord(
        readiness = GoalQualityReadiness.ADMITTED,
        execution =
          GoalQualityExecution(
            parentWorkflowId = "parent",
            invocationId = "invocation",
            baselineContentFingerprint = "baseline",
            selectionFingerprint = "selection",
            governingInputFingerprint = "inputs",
            governingInputs = null,
            executionPlans = null,
            progress = null,
            status = GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING,
            validation = GoalQualityValidationDisposition.REQUIRED_PENDING,
            postQaContentFingerprint = "after-qa",
            skipReason = "unsupported_target",
          ),
      )
    val running = pending.startValidation()

    assertFailsWith<IllegalStateException> {
      running.settle(GoalQualityExecutionStatus.SETTLED_SKIPPED, null, "settled")
    }

    val terminalObserved =
      running.copy(
        execution = requireNotNull(running.execution).copy(finalValidationContentFingerprint = "validated"),
      )
    val settled =
      terminalObserved.completeValidation().settle(
        GoalQualityExecutionStatus.SETTLED_SKIPPED,
        null,
        "settled",
      )

    assertEquals(GoalQualityExecutionStatus.SETTLED_SKIPPED, settled.execution?.status)
    assertEquals(GoalQualityValidationDisposition.COMPLETED, settled.execution?.validation)
    assertEquals("unsupported_target", settled.execution?.skipReason)
    assertEquals("validated", settled.execution?.finalValidationContentFingerprint)
  }
}
