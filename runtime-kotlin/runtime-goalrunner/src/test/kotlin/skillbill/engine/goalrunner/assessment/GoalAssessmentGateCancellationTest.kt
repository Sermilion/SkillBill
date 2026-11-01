package skillbill.engine.goalrunner.assessment

import org.junit.jupiter.api.io.TempDir
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GoalAssessmentGateCancellationTest {
  @TempDir lateinit var home: Path

  @Test
  fun `assessment cancellation propagates without recording an unavailable exit`() {
    val stopped = CancellationException("goal stop")
    val fixture =
      GoalAssessmentGateFixture(home) { call ->
        if (call == AssessmentCall.FIRST_SUBTASK) throw stopped
        "Finding: none"
      }

    val thrown =
      assertFailsWith<CancellationException> {
        fixture.gate().run(fixture.state(), fixture.request())
      }
    assertSame(stopped, thrown)
    assertTrue(fixture.store.persisted.none { it.exitReason == GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE })
    assertTrue(fixture.diagnostics.warnings.isEmpty())
  }
}
